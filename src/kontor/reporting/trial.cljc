(ns kontor.reporting.trial
  "Trial balance — the per-account, per-commodity total derived from
   balance.clj. Same bitemporal axes (ADR-008).

   The result shape: `{account-eid {commodity-eid Money}}`. Empty
   maps prune themselves: an account with no postings does not appear
   in the outer map; a commodity that nets to zero does not appear in
   the inner map (callers can override with `:include-zero? true`).

   For multi-currency books the trial balance is *not* a single
   number per account — it's a per-commodity collection. The kernel
   never silently FX-converts; that is a presentation-time concern
   (apply rates from a price feed)."
  (:require [datahike.api :as d]
            [kontor.reporting.balance :as balance]
            [kontor.money :as money]))

(defn- now [] #?(:clj (java.util.Date.) :cljs (js/Date.)))

(defn- all-account-eids
  "Every :kontor.account/path entity in the snapshot."
  [db]
  (d/q '[:find [?a ...]
         :where [?a :kontor.account/path _]]
       db))

(defn trial-balance
  "Compute the trial balance over the bitemporal window in `opts`.
   Same options as balance/account-balance — including `:entity` to
   restrict to a single legal entity (ADR-031 per-(entity, ledger,
   commodity) sum-to-zero, so an entity-filtered trial balance is
   itself balanced) — including `:ledger` to scope to one parallel book
   (ADR-021; without it the figures blend every book) — plus:
     :accounts — restrict to a specific seq of account eids
     :include-zero? — if true, retain accounts/commodities that net
                      to zero (defaults false; results are smaller)

   Returns `{account-eid {commodity-eid Money}}`.

   The whole report is ONE snapshot. `:as-of-tx` is resolved once here
   and passed down, so every account is read from the same db value;
   previously each per-account call re-derefed the connection and, with
   no `:as-of-tx` given, computed its own wall-clock `now`, so a write
   landing mid-report could leave the result internally inconsistent —
   a trial balance that does not balance for no visible reason."
  ([conn] (trial-balance conn {}))
  ([conn {:keys [accounts include-zero? as-of-tx] :as opts}]
   (let [as-of-tx (or as-of-tx (now))
         opts     (assoc opts :as-of-tx as-of-tx)
         eids (or accounts (all-account-eids (d/as-of (d/db conn) as-of-tx)))
         ;; balance/account-balance handles the bitemporal slicing per-call;
         ;; we just iterate the candidate accounts.
         per-account
         (into {}
               (keep (fn [eid]
                       (let [bal (balance/account-balance conn eid opts)
                             retained (if include-zero?
                                        bal
                                        (into {}
                                              (remove (fn [[_c m]] (money/zero? m)))
                                              bal))]
                         (when (or include-zero? (seq retained))
                           [eid retained]))))
               eids)]
     per-account)))

(defn balanced?
  "Sanity check: a correctly-kept set of books has trial-balance
   summing to zero per commodity across ALL accounts. (Sums across
   commodities are meaningless without FX.) Returns true iff so."
  [trial]
  (let [per-commodity-totals
        (->> trial
             vals
             (mapcat seq)              ;; flatten {acct {c m}} → [c m]
             (group-by first)          ;; {c [[c m] [c m] ...]}
             (reduce-kv
              (fn [acc c entries]
                (assoc acc c
                       (reduce money/add
                               (money/zero c)
                               (map second entries))))
              {}))]
    (every? money/zero? (vals per-commodity-totals))))
