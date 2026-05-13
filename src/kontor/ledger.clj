(ns kontor.ledger
  "Two concerns share this namespace because both are 'ledger' in
   standard accounting terminology:

   1. **Parallel-ledger entity helpers (ADR-021).** Bootstrapping the
      primary ledger, resolving a ledger by code, etc. Postings carry
      `:posting/ledger` to support IFRS-vs-local-GAAP-style parallel
      books. Sum-to-zero in a transaction runs per ledger.

   2. **Account-statement view.** Bitemporal-aware ordered postings
      against a single account (`postings-against`, `running-balance`).
      Same axes as balance.clj (ADR-008). Each row carries the
      posting eid, transaction eid, valid-from, narration, amount,
      commodity, partner. This is the per-account 'subsidiary
      ledger' / 'ledger card' report — distinct from the
      parallel-ledger entity above."
  (:require [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.money :as money])
  (:import [java.util Date]))

;; ============================================================================
;; Parallel-ledger entity (ADR-021)
;; ============================================================================

(def primary-code
  "The bootstrap ledger's stable identifier. Posting-builders that
   omit `:posting/ledger` resolve to this."
  "primary")

(def primary-seed
  "Seed data for the primary ledger. Idempotent under
   `:db.unique/identity` on `:ledger/code`."
  {:ledger/code      primary-code
   :ledger/name      "Primary ledger"
   :ledger/type      :primary
   :ledger/framework :local
   :ledger/active    true})

(defn install-defaults!
  "Idempotently transact the primary ledger. Re-running on a DB that
   already has it is a no-op (the unique-identity match collapses)."
  [conn]
  (d/transact conn [primary-seed]))

(defn primary
  "Resolve the primary ledger entity-id, or nil if not installed.
   `db` is a datahike DB value."
  [db]
  (d/q '[:find ?e .
         :in $ ?code
         :where [?e :ledger/code ?code]]
       db primary-code))

(defn by-code
  "Resolve a ledger entity-id by its `:ledger/code`. nil when missing."
  [db code]
  (d/q '[:find ?e .
         :in $ ?code
         :where [?e :ledger/code ?code]]
       db code))

(defn resolve-ledger
  "Coerce `ledger-spec` to an entity-id. Accepts:
     - nil           → primary ledger (or nil if not installed)
     - a string      → looked up by `:ledger/code`
     - a long / map  → returned as-is (assumed eid or lookup ref)"
  [db ledger-spec]
  (cond
    (nil? ledger-spec)    (primary db)
    (string? ledger-spec) (by-code db ledger-spec)
    :else                 ledger-spec))

;; ============================================================================
;; Account-statement view (per-account history)
;; ============================================================================

(defn- now ^Date [] (Date.))

(defn- before-or-eq? [^Date a ^Date b] (<= (.compareTo a b) 0))

(def ^:private default-included-states #{:posted})

(defn postings-against
  "Return ordered postings against `account-eid` matching the given
   bitemporal window. Each entry is

     {:posting     <eid>
      :transaction <eid>
      :valid-from  Date
      :amount      Money
      :commodity   <eid>
      :narration   string-or-nil
      :partner     <eid-or-nil>
      :tx-state    keyword}

   Options (all optional):
     :as-of-valid    Date — default now
     :as-of-tx       Date — default now
     :include-states set  — default #{:posted}
     :order          :asc | :desc — default :asc on :valid-from"
  ([conn account-eid] (postings-against conn account-eid {}))
  ([conn account-eid {:keys [as-of-valid as-of-tx include-states order]
                      :or   {include-states default-included-states
                             order :asc}}]
   (let [as-of-valid (or as-of-valid (now))
         as-of-tx    (or as-of-tx (now))
         db          (d/db conn)
         tx-snap     (if as-of-tx (d/as-of db as-of-tx) db)
         rows
         (->> (d/q '[:find ?p ?vf
                     :in $ % ?account
                     :where
                     [?p :posting/account ?account]
                     (posting-vf ?p ?vf)]
                   tx-snap kbt/query-rules account-eid)
              (mapv (fn [[p vf]]
                      (let [pulled (d/pull tx-snap
                                           [:db/id
                                            :posting/amount
                                            :posting/commodity
                                            :posting/transaction
                                            :posting/narration
                                            :posting/partner]
                                           p)
                            tx-id (-> pulled :posting/transaction :db/id)
                            tx-state (:transaction/state
                                      (d/pull tx-snap [:transaction/state] tx-id))
                            tx-narr  (:transaction/narration
                                      (d/pull tx-snap [:transaction/narration] tx-id))]
                        {:posting     (:db/id pulled)
                         :transaction tx-id
                         :valid-from  vf
                         :amount      (money/posting->money pulled)
                         :commodity   (let [c (:posting/commodity pulled)]
                                        (if (map? c) (:db/id c) c))
                         :narration   (or (:posting/narration pulled) tx-narr)
                         :partner     (some-> (:posting/partner pulled) :db/id)
                         :tx-state    tx-state}))))
         filtered
         (filter (fn [{:keys [valid-from tx-state]}]
                   (and (some? valid-from)
                        (before-or-eq? valid-from as-of-valid)
                        (contains? include-states tx-state)))
                 rows)
         comparator (case order
                      :asc  #(compare (:valid-from %1) (:valid-from %2))
                      :desc #(compare (:valid-from %2) (:valid-from %1)))]
     (vec (sort comparator filtered)))))

(defn running-balance
  "Like `postings-against` but each row also carries `:running` —
   the cumulative balance after this posting in its commodity. When
   the account is multi-currency the running balance is per-commodity
   independently. Returns the same shape as postings-against with the
   extra `:running` key (a Money)."
  [conn account-eid & [opts]]
  (let [opts-with-asc (assoc (or opts {}) :order :asc)
        rows (postings-against conn account-eid opts-with-asc)
        ;; Per-commodity running totals
        running (atom {})]
    (mapv (fn [{:keys [amount commodity] :as row}]
            (let [prev (get @running commodity (money/zero commodity))
                  next (money/add prev amount)]
              (swap! running assoc commodity next)
              (assoc row :running next)))
          rows)))
