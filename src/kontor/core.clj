(ns kontor.core
  "Public surface of the kernel.

   Phase 0 (this commit): create-test-db, install-schema!, smoke
   utilities for the REPL.

   Phase 1 will add: post-transaction!, balance helpers, period control
   — all bitemporal-aware (ADR-008). Until then, exercise the kernel
   via raw datahike calls and the schema in `schema.clj`."
  (:require [datahike.api :as d]
            [kontor.ledger :as ledger]
            [kontor.schema :as schema]
            [kontor.tax-provider :as tp]
            [kontor.valuation :as valuation]))

;; ============================================================================
;; Defaults
;; ============================================================================

(def default-config
  "Datahike config recommended for accounting use.

   Per ADR-003 + research note 02:
     :crypto-hash? true makes EAVT/AEVT/AVET index nodes content-
       addressed via SHA-512, producing a real Merkle tree.
     :keep-history? true is required for bitemporal as-of and history
       queries. Storage cost is ~2× index size in steady state — the
       audit story requires it.
     :schema-flexibility :write keeps the kernel honest: every attribute
       must be declared. Write-time validation catches typos.

   The :store config below is :memory for ephemeral test/REPL use.
   Production setups override with :file, :lmdb, :rocksdb, :jdbc, :s3.

   :store :id MUST be a UUID — datahike rejects strings here. Callers
   that build their own config typically use (random-uuid) or a fixed
   UUID per tenant."
  {:store              {:backend :memory :id (random-uuid)}
   :schema-flexibility :write
   :keep-history?      true
   :crypto-hash?       true})

;; ============================================================================
;; DB lifecycle
;; ============================================================================

(def cost-center-plan-seed
  "Bootstrap seed for the kernel-level `cost-center` analytic plan
   (ADR-032). Every companion project leans on this plan — HR cost-
   center on `:employment`, project on timesheet, manufacturing
   work-center, asset cost-center, fleet vehicle. Pre-installing
   the plan removes cross-companion coordination overhead."
  {:analytic-plan/code          "cost-center"
   :analytic-plan/name          "Cost centers"
   :analytic-plan/applicability :optional
   :analytic-plan/active        true})

(defn install-schema!
  "Transact the kernel schema into the connection AND bootstrap
   defaults: primary ledger (ADR-021), primary valuation book
   (ADR-027), cost-center analytic plan (ADR-032). Idempotent —
   safe to re-run on a connection that already has the schema."
  [conn]
  (schema/install! conn)
  (ledger/install-defaults! conn)
  (valuation/install-defaults! conn)
  (d/transact conn [cost-center-plan-seed])
  conn)

(defn create-test-db
  "Create an ephemeral in-memory accounting DB with the kernel schema
   installed. Each call creates a fresh database keyed by a random UUID.

   For production / persistent use, build your own config and call
   (install-schema! conn) explicitly.

   Returns a connection (`d/connect` result)."
  ([] (create-test-db {}))
  ([overrides]
   (let [cfg (-> default-config
                 (assoc-in [:store :id] (random-uuid))
                 (merge overrides))]
     (d/create-database cfg)
     (let [conn (d/connect cfg)]
       (install-schema! conn)
       conn))))

;; ============================================================================
;; Provider registration
;;
;; The kernel doesn't hold global state; the provider is passed
;; explicitly to functions that need it (post-transaction! in Phase 1).
;; This helper just makes the default StaticTableProvider construction
;; one call.
;; ============================================================================

(defn make-default-tax-provider
  "Return a StaticTableProvider configured for the given default country.
   See `kontor.tax-provider/make-static-table-provider`."
  ([] (tp/make-static-table-provider {}))
  ([opts] (tp/make-static-table-provider opts)))

;; ============================================================================
;; REPL conveniences
;; ============================================================================

(defn schema-summary
  "Return a sorted list of all kernel attribute idents currently in
   the database. Useful for poking at schema in the REPL."
  [conn]
  (let [db (d/db conn)]
    (->> (d/q '[:find [?ident ...]
                :where [_ :db/ident ?ident]]
              db)
         (filter (fn [k]
                   (contains?
                    #{"create" "write"
                      "commodity" "lot"
                      "account" "account-tag"
                      "journal" "partner" "fiscal-position"
                      "tax" "tax-rep" "tax-group"
                      "period" "balance-assertion"
                      "transaction" "posting"
                      "analytic-plan" "analytic-account" "analytic-distribution"
                      "ledger"
                      "country" "country-code" "country-group"
                      "state" "state-code"
                      "attestation" "complemento"
                      "valuation-book"
                      "valuation-layer" "layer-consumption" "layer-adjustment"
                      "entity"
                      "schedule" "schedule-occurrence"
                      "status-transition" "status-history"}
                    (namespace k))))
         sort
         vec)))
