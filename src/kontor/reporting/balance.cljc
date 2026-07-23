(ns kontor.reporting.balance
  "Account balance queries — bitemporal-aware.

   Per ADR-008 every read takes two implicit dimensions:
     :as-of-tx    — what the database knew at this transaction time.
                    Wraps `(d/as-of db tx-time)`.
     :as-of-valid — what was true in the world as of this valid date.
                    Filters `(<= :db.valid/from valid-time)` on the
                    tx that wrote each posting (upstream datahike vt).

   Both default to `now`. Postings on cancelled / draft transactions
   are excluded by default; pass `:include-states #{:draft :posted
   :cancelled}` to override.

   Returns a map of `{commodity Money}` — accounts may carry postings
   in multiple commodities (multi-currency books). The map is empty
   when an account has no postings."
  (:require [datahike.api :as d]
            [kontor.money :as money]))

;; ============================================================================
;; Commodity + account reference resolution (note-196 F1/F5)
;;
;; `:kontor.posting/commodity` is a `:db.type/ref` and reads never resolved
;; it, so balances keyed by (and Money wrapped) a raw commodity eid. Resolve
;; once, here — the shared home, since `kontor.reporting.report` already
;; requires this ns (no cycle) and reuses it.
;; ============================================================================

(defn resolve-commodity-symbol
  "Normalize a `:kontor.posting/commodity` value to a symbol keyword (:EUR /
   :CAD). Handles a keyword (already a symbol — cljs books store it directly),
   a pulled ref `{:db/id n}` / bare eid (the kernel schema types it
   `:db.type/ref`), or a `[:kontor.commodity/symbol s]` lookup-ref. Falls
   back to the raw value if it can't resolve, so a commodity is never lost."
  [db c]
  (cond
    (keyword? c) c
    (and (vector? c) (= :kontor.commodity/symbol (first c))) (keyword (second c))
    :else
    (let [eid (if (map? c) (:db/id c) c)
          sym (when eid (:kontor.commodity/symbol (d/pull db [:kontor.commodity/symbol] eid)))]
      (if sym (keyword sym) c))))

;; ============================================================================
;; Internal: bitemporal-aware posting lookup
;; ============================================================================

(def ^:private default-included-states
  "Posting states that count toward balance by default. :draft is OUT
   because draft entries are work-in-progress; :cancelled is OUT
   because cancelled entries are reversed (the reversal posts an
   equal-and-opposite to net them to zero — including both sides
   would double-count)."
  #{:posted})

(defn- now [] #?(:clj (java.util.Date.) :cljs (js/Date.)))

(defn- ->ms [x] #?(:clj (.getTime ^java.util.Date x) :cljs (if (number? x) x (inst-ms x))))
(defn- before-or-eq?
  "True iff a is on-or-before b (inclusive on both ends). `.getTime` works
   on both a JVM Date and a js/Date, so no `.compareTo` (js/Date lacks it)."
  [a b]
  (<= (->ms a) (->ms b)))

(defn- include-posting?
  "Filter a pulled posting against `as-of-valid` and `included-states`.
   The :as-of-tx axis is honored by querying against `(d/as-of db tx)`
   *before* this fn runs, so the snapshot already only contains
   tx-visible datoms.

   `as-of-valid` may be nil — in which case there is NO upper-bound
   filter on valid-from. This is the default; consumers
   modelling future scenarios / simulations / forward-looking accruals
   get all posted postings rather than silently losing the future-dated
   ones to wall-clock now.

   Per ADR-008 (revised): only :valid-from is checked. :valid-to was
   dropped — corrections are modeled as reverse-and-repost, not as
   superseding postings with an end-date. Valid-from lives on the tx
   that wrote the posting (upstream `:db.valid/from`)."
  [as-of-valid included-states posting]
  (let [vf (:valid-from posting)
        st (:kontor.transaction/state posting)]
    (and (some? vf)
         (or (nil? as-of-valid) (before-or-eq? vf as-of-valid))
         (contains? included-states st))))

(defn ledger-match-fn
  "Resolve a `:ledger` option to a predicate over a posting's LEDGER EID.

   Per ADR-021 a posting carrying no `:kontor.posting/ledger` is
   conceptually in the PRIMARY book, so when the requested ledger is
   `:kontor.ledger/type :primary` a nil ledger-eid matches too. Returns
   `(constantly true)` when no ledger is requested.

   Takes the eid rather than the posting so the read side can share ONE
   definition of this rule across namespaces that pull postings into
   different shapes (`kontor.reporting.report` keeps `:ledger-eid`; this
   namespace pulls a ref map). Having the rule written twice is how the
   two drift — which is the shape of the bug this option is fixing."
  [db ledger-spec]
  (if (nil? ledger-spec)
    (constantly true)
    (let [{:keys [db/id] :kontor.ledger/keys [type]}
          (d/pull db [:db/id :kontor.ledger/type] ledger-spec)]
      (when-not id
        (throw (ex-info "kontor.reporting: :ledger not found" {:ledger ledger-spec})))
      (let [primary? (= :primary type)]
        (fn [ledger-eid]
          (or (= ledger-eid id)
              (and primary? (nil? ledger-eid))))))))

(defn- pull-postings-against
  "Pull all postings against `account-eid` from `db` (already
   tx-time-snapshotted by the caller). Each posting is a flat map
   suitable for include-posting? and money/posting->money — the
   `:valid-from` key is derived from the posting's creating tx via
   `:db.valid/from` (upstream datahike).

   When `entity-eid` is non-nil, restricts to postings whose
   `:kontor.posting/entity` matches — supports multi-entity / trans-national
   queries against per-entity sum-to-zero books (ADR-031)."
  [db account-eid entity-eid]
  (->> (if entity-eid
         (d/q '[:find ?p ?vf
                :in $ ?account ?entity
                :where
                [?p :kontor.posting/account ?account]
                [?p :kontor.posting/entity ?entity]
                [?p :kontor.posting/transaction _ ?tx]
                [?tx :db/txInstant ?ti]
                [(get-else $ ?tx :db.valid/from ?ti) ?vf]]
              db account-eid entity-eid)
         (d/q '[:find ?p ?vf
                :in $ ?account
                :where
                [?p :kontor.posting/account ?account]
                [?p :kontor.posting/transaction _ ?tx]
                [?tx :db/txInstant ?ti]
                [(get-else $ ?tx :db.valid/from ?ti) ?vf]]
              db account-eid))
       (mapv (fn [[p vf]]
               (let [pulled (d/pull db
                                    [:kontor.posting/amount
                                     :kontor.posting/commodity
                                     :kontor.posting/transaction
                                     :kontor.posting/entity
                                     :kontor.posting/ledger]
                                    p)
                     tx-state (-> (d/pull db [:kontor.transaction/state]
                                          (-> pulled :kontor.posting/transaction :db/id))
                                  :kontor.transaction/state)]
                 (assoc pulled
                        :valid-from vf
                        :ledger-eid (:db/id (:kontor.posting/ledger pulled))
                        :kontor.transaction/state tx-state))))))

;; ============================================================================
;; Public
;; ============================================================================

(defn account-balance
  "Compute the balance on `account-eid` as of (`:as-of-valid`,
   `:as-of-tx`). Returns a map `{commodity-eid Money}` — empty if no
   matching postings.

   Options (all optional):
     :as-of-valid    — java.util.Date — point-in-time upper bound on
                       valid-from. **Default: nil (all valid time)** —
                       includes future-dated postings. Pass an explicit
                       date for a real point-in-time balance. Note 160
                       §I-17 reverses the prior wall-clock-now default
                       which silently broke simulations and forward-
                       looking accruals.
     :as-of-tx       — java.util.Date  (defaults to now)
     :include-states — set of :kontor.transaction/state values to include
                       (defaults to #{:posted})
     :entity         — restrict to postings of a given :kontor.posting/entity
                       (eid or lookup-ref). Default: all entities. Per
                       ADR-031 books are per-(entity, ledger, commodity)
                       sum-to-zero, so entity-filtered balances are
                       independently balanced and meaningful for trans-
                       national reports.
     :ledger         — restrict to postings of a given :kontor.posting/ledger
                       (eid or lookup-ref). Default: ALL ledgers, which
                       for a book running parallel valuations (ADR-021 —
                       HGB alongside IFRS) means the returned balance is
                       a blend of them and belongs to no framework. Pass
                       the ledger explicitly for a per-book figure. A
                       posting with no ledger counts as the primary book
                       (see [[ledger-match-fn]]).

   Example:
     (account-balance conn rec)
     ;; trial balance as of Q1 close, as known on Mar 31
     (account-balance conn rec
                      {:as-of-valid #inst \"2026-03-31\"
                       :as-of-tx    #inst \"2026-03-31\"})
     ;; DE GmbH only
     (account-balance conn rec {:entity [:kontor.entity/code \"DE-GMBH\"]})"
  ([conn account-eid] (account-balance conn account-eid {}))
  ([conn account-eid {:keys [as-of-valid as-of-tx include-states entity ledger]
                      :or   {include-states default-included-states}}]
   (let [as-of-tx    (or as-of-tx (now))
         db          (d/db conn)
         tx-snap     (if as-of-tx (d/as-of db as-of-tx) db)
         entity-eid  (when entity
                       (or (:db/id (d/pull tx-snap [:db/id] entity))
                           (throw (ex-info "account-balance: :entity not found"
                                           {:entity entity}))))
         ledger-ok?  (ledger-match-fn tx-snap ledger)
         postings    (pull-postings-against tx-snap account-eid entity-eid)
         included    (->> postings
                          (filter (partial include-posting? as-of-valid include-states))
                          (filter (comp ledger-ok? :ledger-eid)))]
     (->> included
          (keep money/posting->money)
          money/sum-by-commodity))))

(defn account-balance-single-commodity
  "Convenience: when an account is restricted to a single commodity,
   return one Money (or zero in the given commodity if no postings).
   Throws if multiple commodities have non-zero postings."
  [conn account-eid commodity-eid & [opts]]
  (let [bal (account-balance conn account-eid (or opts {}))
        non-zero-others (->> bal
                             (remove (fn [[c m]]
                                       (or (= c commodity-eid)
                                           (money/zero? m))))
                             (into {}))]
    (when (seq non-zero-others)
      (throw (ex-info "Account has non-zero postings in commodities other than the requested one"
                      {:account-eid account-eid
                       :requested commodity-eid
                       :others non-zero-others})))
    (or (get bal commodity-eid)
        (money/zero commodity-eid))))
