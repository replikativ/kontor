(ns datahike-accounting.balance
  "Account balance queries — bitemporal-aware.

   Per ADR-008 every read takes two implicit dimensions:
     :as-of-tx    — what the database knew at this transaction time.
                    Wraps `(d/as-of db tx-time)`.
     :as-of-valid — what was true in the world as of this valid date.
                    Filters `(<= :posting/valid-from valid-time)`
                    AND `(or (nil? :posting/valid-to)
                              (> :posting/valid-to valid-time))`.

   Both default to `now`. Postings on cancelled / draft transactions
   are excluded by default; pass `:include-states #{:draft :posted
   :cancelled}` to override.

   Returns a map of `{commodity Money}` — accounts may carry postings
   in multiple commodities (multi-currency books). The map is empty
   when an account has no postings."
  (:require [datahike.api :as d]
            [datahike-accounting.money :as money])
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

   Per ADR-008 (revised): only :valid-from is checked. :valid-to was
   dropped — corrections are modeled as reverse-and-repost, not as
   superseding postings with an end-date."
  [as-of-valid included-states posting]
  (let [vf (:posting/valid-from posting)
        st (:transaction/state posting)]
    (and (some? vf)
         (before-or-eq? vf as-of-valid)
         (contains? included-states st))))

(defn- pull-postings-against
  "Pull all postings against `account-eid` from `db` (already
   tx-time-snapshotted by the caller). Each posting is a flat map
   suitable for include-posting? and money/posting->money."
  [db account-eid]
  (->> (d/q '[:find [?p ...]
              :in $ ?account
              :where [?p :posting/account ?account]]
            db account-eid)
       (mapv (fn [p]
               (let [pulled (d/pull db
                                    [:posting/amount
                                     :posting/commodity
                                     :posting/valid-from
                                     :posting/transaction]
                                    p)
                     tx-state (-> (d/pull db [:transaction/state]
                                          (-> pulled :posting/transaction :db/id))
                                  :transaction/state)]
                 (assoc pulled :transaction/state tx-state))))))

;; ============================================================================
;; Public
;; ============================================================================

(defn account-balance
  "Compute the balance on `account-eid` as of (`:as-of-valid`,
   `:as-of-tx`). Returns a map `{commodity-eid Money}` — empty if no
   matching postings.

   Options (all optional, all default to `now`/`now`/`#{:posted}`):
     :as-of-valid    — java.util.Date  (defaults to now)
     :as-of-tx       — java.util.Date  (defaults to now)
     :include-states — set of :transaction/state values to include
                       (defaults to #{:posted})

   Example:
     (account-balance conn rec)
     ;; trial balance as of Q1 close, as known on Mar 31
     (account-balance conn rec
                      {:as-of-valid #inst \"2026-03-31\"
                       :as-of-tx    #inst \"2026-03-31\"})"
  ([conn account-eid] (account-balance conn account-eid {}))
  ([conn account-eid {:keys [as-of-valid as-of-tx include-states]
                      :or   {include-states default-included-states}}]
   (let [as-of-valid (or as-of-valid (now))
         as-of-tx    (or as-of-tx (now))
         db          (d/db conn)
         tx-snap     (if as-of-tx (d/as-of db as-of-tx) db)
         postings    (pull-postings-against tx-snap account-eid)
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
