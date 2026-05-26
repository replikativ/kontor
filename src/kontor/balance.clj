(ns kontor.balance
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
            [kontor.money :as money])
  (:import [java.util Date]))

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

(defn- now ^Date [] (Date.))

(defn- before-or-eq?
  "True iff a is on-or-before b (inclusive on both ends)."
  [^Date a ^Date b]
  (<= (.compareTo a b) 0))

(defn- include-posting?
  "Filter a pulled posting against `as-of-valid` and `included-states`.
   The :as-of-tx axis is honored by querying against `(d/as-of db tx)`
   *before* this fn runs, so the snapshot already only contains
   tx-visible datoms.

   `as-of-valid` may be nil — in which case there is NO upper-bound
   filter on valid-from (note 160 §I-17). This is the default; consumers
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
                                     :kontor.posting/entity]
                                    p)
                     tx-state (-> (d/pull db [:kontor.transaction/state]
                                          (-> pulled :kontor.posting/transaction :db/id))
                                  :kontor.transaction/state)]
                 (assoc pulled
                        :valid-from vf
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

   Example:
     (account-balance conn rec)
     ;; trial balance as of Q1 close, as known on Mar 31
     (account-balance conn rec
                      {:as-of-valid #inst \"2026-03-31\"
                       :as-of-tx    #inst \"2026-03-31\"})
     ;; DE GmbH only
     (account-balance conn rec {:entity [:kontor.entity/code \"DE-GMBH\"]})"
  ([conn account-eid] (account-balance conn account-eid {}))
  ([conn account-eid {:keys [as-of-valid as-of-tx include-states entity]
                      :or   {include-states default-included-states}}]
   (let [as-of-tx    (or as-of-tx (now))
         db          (d/db conn)
         tx-snap     (if as-of-tx (d/as-of db as-of-tx) db)
         entity-eid  (when entity
                       (or (:db/id (d/pull tx-snap [:db/id] entity))
                           (throw (ex-info "account-balance: :entity not found"
                                           {:entity entity}))))
         postings    (pull-postings-against tx-snap account-eid entity-eid)
         included    (filter (partial include-posting? as-of-valid include-states)
                             postings)]
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
