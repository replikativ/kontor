(ns kontor.core
  "Public surface of the kernel.

   Lifecycle helpers (create-test-db, install-schema!) +
   provider-registration glue. The real public API is reached via
   the per-concern namespaces:

     kontor.posting           build-transaction, sum-to-zero
                              (ADR-021, ADR-031)
     kontor.balance           account-balance (bitemporal-aware)
     kontor.trial             trial-balance
     kontor.ledger            postings-against-account
     kontor.period            open/close/lock periods (ADR-014)
     kontor.bitemporal        with-vt, value-at, as-of-bitemporal,
                              timeline (ADR-048)
     kontor.status-machine    record-status-change! (ADR-034)
     kontor.payment-application apply-payment!, reverse-application!,
                              allocate-fifo! (ADR-043)
     kontor.sealing           seal-transaction! middleware (ADR-007)
     kontor.audit             commit-hash wrapper (ADR-003)
     kontor.tax-rate-provider TaxRateProvider + TaxFacts (ADR-071)
     kontor.import.beancount  round-trip importer (ADR-009)

   See doc/architecture.md for the layer cake and how companions
   compose."
  (:require [datahike.api :as d]
            [kontor.audit-doc :as audit-doc]
            [kontor.dsar :as dsar]
            [kontor.ledger :as ledger]
            [kontor.legal-hold :as legal-hold]
            [kontor.retention :as retention]
            [kontor.schema :as schema]
            [kontor.statute :as statute]
            [kontor.tax-rate-provider :as trp]
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
  {:kontor.analytic-plan/code          "cost-center"
   :kontor.analytic-plan/name          "Cost centers"
   :kontor.analytic-plan/applicability :optional
   :kontor.analytic-plan/active        true})

(defn install-schema!
  "Transact the kernel schema into the connection AND bootstrap
   defaults: primary ledger (ADR-021), primary valuation book
   (ADR-027), cost-center analytic plan (ADR-032). Idempotent —
   safe to re-run on a connection that already has the schema."
  [conn]
  (schema/install! conn)
  (ledger/install-defaults! conn)
  (valuation/install-defaults! conn)
  (legal-hold/install-seeds! conn)   ; ADR-049 status-transition + approval-policy seeds
  (retention/install-seeds! conn)    ; ADR-050 status-transition + approval-policy seeds
  (audit-doc/install-seeds! conn)    ; ADR-051 :kontor.audit-doc/privilege transitions + policies
  (dsar/install-seeds! conn)         ; ADR-052 :dsar-request transitions + policies
  (statute/install-seeds! conn)      ; ADR-101 starter :tax-concept catalogue
  (d/transact conn [cost-center-plan-seed])
  conn)

(defonce ^:private test-template
  ;; Lazy schema'd in-memory template DB. The kernel schema install
  ;; is ~260 ms for the 505 attrs — building it once per JVM and
  ;; CoW-branching per test (datahike's branching API; each branch
  ;; is an isolated fork, connections deduplicate by [store-id
  ;; branch]) cuts per-test setup from ~560 ms to ~1 ms.
  (delay
    (let [cfg (-> default-config (assoc-in [:store :id] (random-uuid)))]
      (d/create-database cfg)
      (let [conn (d/connect cfg)]
        (install-schema! conn)
        {:cfg cfg :conn conn}))))

(defn create-test-db
  "Create an ephemeral in-memory accounting DB with the kernel schema
   installed. The zero-arg form CoW-branches off a shared template
   (the kernel schema is installed once per JVM; each test gets a
   fresh isolated branch); the 2-arg form is a slow-path escape for
   tests that need a non-default `overrides` config (builds a fresh
   DB and re-installs the schema).

   For production / persistent use, build your own config and call
   (install-schema! conn) explicitly.

   Returns a connection (`d/connect` result)."
  ([]
   (let [{:keys [cfg conn]} @test-template
         branch-kw          (keyword (str "t-" (random-uuid)))]
     (d/branch! conn :db branch-kw)
     (d/connect (assoc cfg :branch branch-kw))))
  ([overrides]
   (if (empty? overrides)
     (create-test-db)
     (let [cfg (-> default-config
                   (assoc-in [:store :id] (random-uuid))
                   (merge overrides))]
       (d/create-database cfg)
       (let [conn (d/connect cfg)]
         (install-schema! conn)
         conn)))))

;; ============================================================================
;; Provider registration
;;
;; The kernel doesn't hold global state; the provider is passed
;; explicitly to functions that need it (post-transaction! in Phase 1).
;; This helper just makes the default StaticTableProvider construction
;; one call.
;; ============================================================================

(defn make-default-tax-provider
  "Return a `StaticTableProvider` (the ADR-071 `TaxRateProvider` impl)
   over `conn`, configured for the given default country. See
   `kontor.tax-rate-provider/make-static-table-provider`."
  ([conn] (trp/make-static-table-provider conn))
  ([conn opts] (trp/make-static-table-provider conn opts)))

;; ============================================================================
;; REPL conveniences
;; ============================================================================

(def ^:private internal-namespaces
  "Datahike-internal + datopia-invariant + provider attribute
   namespaces that schema-summary excludes. Everything else is
   considered a domain attribute and surfaced to the REPL user.
   Inverted from a hard-coded allowlist 2026-05-13 (P1-9 review fix)
   so newly-added kernel and companion namespaces appear without an
   allowlist edit."
  #{"db" "db.alter" "db.attr" "db.bootstrap" "db.cardinality" "db.entity"
    "db.excise" "db.fn" "db.install" "db.lang" "db.part" "db.sys"
    "db.type" "db.unique" "fressian"
    ;; datopia/invariant scaffolding
    "invariant"})

(defn schema-summary
  "Return a sorted list of all attribute idents currently in the
   database, excluding datahike-internal and invariant-scaffolding
   namespaces. Useful for poking at the schema in the REPL —
   surfaces every kernel + companion-module attribute installed in
   the connection."
  [conn]
  (let [db (d/db conn)]
    (->> (d/q '[:find [?ident ...]
                :where [_ :db/ident ?ident]]
              db)
         (remove #(contains? internal-namespaces (namespace %)))
         sort
         vec)))
