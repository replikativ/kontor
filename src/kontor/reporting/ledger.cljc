(ns kontor.reporting.ledger
  "Two concerns share this namespace because both are 'ledger' in
   standard accounting terminology:

   1. **Parallel-ledger entity helpers (ADR-021).** Bootstrapping the
      primary ledger, resolving a ledger by code, etc. Postings carry
      `:kontor.posting/ledger` to support IFRS-vs-local-GAAP-style parallel
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
            [kontor.money :as money]
            [kontor.reporting.balance :as balance]))

;; ============================================================================
;; Parallel-ledger entity (ADR-021)
;; ============================================================================

(def primary-code
  "The bootstrap ledger's stable identifier. Posting-builders that
   omit `:kontor.posting/ledger` resolve to this."
  "primary")

(def primary-seed
  "Seed data for the primary ledger. Idempotent under
   `:db.unique/identity` on `:kontor.ledger/code`."
  {:kontor.ledger/code      primary-code
   :kontor.ledger/name      "Primary ledger"
   :kontor.ledger/type      :primary
   :kontor.ledger/framework :local
   :kontor.ledger/active    true})

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
         :where [?e :kontor.ledger/code ?code]]
       db primary-code))

(defn by-code
  "Resolve a ledger entity-id by its `:kontor.ledger/code`. nil when missing."
  [db code]
  (d/q '[:find ?e .
         :in $ ?code
         :where [?e :kontor.ledger/code ?code]]
       db code))

(defn resolve-ledger
  "Coerce `ledger-spec` to an entity-id. Accepts:
     - nil           → primary ledger (or nil if not installed)
     - a string      → looked up by `:kontor.ledger/code`
     - a long / map  → returned as-is (assumed eid or lookup ref)"
  [db ledger-spec]
  (cond
    (nil? ledger-spec)    (primary db)
    (string? ledger-spec) (by-code db ledger-spec)
    :else                 ledger-spec))

;; ============================================================================
;; Account-statement view (per-account history)
;; ============================================================================

(defn- now [] #?(:clj (java.util.Date.) :cljs (js/Date.)))

(defn- ->ms [x] #?(:clj (.getTime ^java.util.Date x) :cljs (if (number? x) x (inst-ms x))))
(defn- before-or-eq? [a b] (<= (->ms a) (->ms b)))

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
     :as-of-valid    Date — **default nil (all valid time)**, matching
                     `balance/account-balance`. Note 160 §I-17 reversed the
                     wall-clock-now default there because it silently
                     dropped future-dated postings from simulations and
                     forward-looking accruals; this namespace kept the old
                     default until it was caught disagreeing with the
                     balance it is supposed to itemise.
     :as-of-tx       Date — default now
     :include-states set  — default #{:posted}
     :entity         eid or lookup-ref — restrict to a single
                     `:kontor.posting/entity` (ADR-031 trans-national books).
     :ledger         eid or lookup-ref — restrict to one book (ADR-021).
                     Default: all ledgers. A posting with no ledger counts
                     as the primary book.
     :order          :asc | :desc — default :asc on :valid-from"
  ([conn account-eid] (postings-against conn account-eid {}))
  ([conn account-eid {:keys [as-of-valid as-of-tx include-states entity ledger order]
                      :or   {include-states default-included-states
                             order :asc}}]
   (let [as-of-tx    (or as-of-tx (now))
         db          (d/db conn)
         tx-snap     (if as-of-tx (d/as-of db as-of-tx) db)
         entity-eid  (when entity
                       (or (:db/id (d/pull tx-snap [:db/id] entity))
                           (throw (ex-info "postings-against: :entity not found"
                                           {:entity entity}))))
         rows
         (->> (if entity-eid
                (d/q '[:find ?p ?vf
                       :in $ ?account ?entity
                       :where
                       [?p :kontor.posting/account ?account]
                       [?p :kontor.posting/entity ?entity]
                       [?p :kontor.posting/transaction _ ?tx]
                       [?tx :db/txInstant ?ti]
                       [(get-else $ ?tx :db.valid/from ?ti) ?vf]]
                     tx-snap account-eid entity-eid)
                (d/q '[:find ?p ?vf
                       :in $ ?account
                       :where
                       [?p :kontor.posting/account ?account]
                       [?p :kontor.posting/transaction _ ?tx]
                       [?tx :db/txInstant ?ti]
                       [(get-else $ ?tx :db.valid/from ?ti) ?vf]]
                     tx-snap account-eid))
              (mapv (fn [[p vf]]
                      (let [pulled (d/pull tx-snap
                                           [:db/id
                                            :kontor.posting/amount
                                            :kontor.posting/commodity
                                            :kontor.posting/transaction
                                            :kontor.posting/narration
                                            :kontor.posting/partner
                                            :kontor.posting/ledger]
                                           p)
                            tx-id (-> pulled :kontor.posting/transaction :db/id)
                            tx-state (:kontor.transaction/state
                                      (d/pull tx-snap [:kontor.transaction/state] tx-id))
                            tx-narr  (:kontor.transaction/narration
                                      (d/pull tx-snap [:kontor.transaction/narration] tx-id))]
                        {:posting     (:db/id pulled)
                         :transaction tx-id
                         :valid-from  vf
                         :amount      (money/posting->money pulled)
                         :commodity   (let [c (:kontor.posting/commodity pulled)]
                                        (if (map? c) (:db/id c) c))
                         :narration   (or (:kontor.posting/narration pulled) tx-narr)
                         :partner     (some-> (:kontor.posting/partner pulled) :db/id)
                         :ledger      (some-> (:kontor.posting/ledger pulled) :db/id)
                         :tx-state    tx-state}))))
         ledger-ok? (balance/ledger-match-fn tx-snap ledger)
         filtered
         (filter (fn [{:keys [valid-from tx-state] :as row}]
                   (and (some? valid-from)
                        ;; nil as-of-valid = all valid time (note 160 §I-17)
                        (or (nil? as-of-valid) (before-or-eq? valid-from as-of-valid))
                        (contains? include-states tx-state)
                        (ledger-ok? (:ledger row))))
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
