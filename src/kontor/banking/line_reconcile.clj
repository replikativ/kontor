(ns kontor.banking.line-reconcile
  "Posting-level (line) reconciliation — note 198 Tier 2.

   `kontor.banking.payment-application` nets an INVOICE against cash. This
   namespace closes out arbitrary GL lines that offset each other but belong to
   no invoice: a GR/IR goods-received/invoice-received pair, a suspense line, an
   inter-account transfer, a payment's outstanding leg against its bank line.

   The shape mirrors Odoo (`account_move_line.py:242/255/284`,
   `account_partial_reconcile.py`, `account_full_reconcile.py`):

     - a `:partial-reconcile` row matches ONE debit line against ONE credit
       line for an amount;
     - each posting carries a materialised `:kontor.posting/amount-residual`
       (its unmatched remainder), so \"what is still open on this clearing
       account?\" is a direct query rather than a fold over every partial;
     - once every member of a matched set reaches residual zero, a
       `:full-reconcile` group is created and its `:code` is mirrored onto each
       member's `:kontor.posting/matching-number`.

   Reconciliation is a BOOKKEEPING annotation, not a posting: it moves no money
   and writes no GL legs. It therefore does not touch sealed amounts — an
   already-posted line keeps its `:kontor.posting/amount` untouched; only the
   residual/matching annotations are added. (`:amount-residual` is absent until
   first reconciled; readers treat absent as \"the full amount is open\".)"
  (:require [datahike.api :as d]
            [kontor.validation :as validation]))

;; ============================================================================
;; Queries
;; ============================================================================

(defn residual-of
  "Unmatched remainder of a posting. Absent `:amount-residual` means the line
   has never been reconciled, so its whole `:kontor.posting/amount` is open."
  ^java.math.BigDecimal [db posting-eid]
  (let [p (d/pull db [:kontor.posting/amount :kontor.posting/amount-residual]
                  posting-eid)]
    (or (:kontor.posting/amount-residual p)
        (:kontor.posting/amount p)
        0M)))

(def ^:private default-included-states
  "Mirrors `kontor.reporting.balance`'s default. Kept in step with it
   deliberately — see [[open-lines-on-account]]."
  #{:posted})

(defn open-lines-on-account
  "Postings on `account-eid` whose residual is non-zero — the account's open
   items. The clearing-account question (\"does GR/IR net to zero?\") answered
   directly.

   Filtered on the SAME two axes as `kontor.reporting.balance/account-balance`,
   because the only useful reading of this list is against that balance:

     :include-states — transaction states that count (default `#{:posted}`,
                       matching `account-balance`). A draft or cancelled
                       entry is not in the balance, so it is not an open
                       item either.
     :as-of-valid    — upper bound on the writing tx's `:db.valid/from`
                       (default nil = all valid time, again matching
                       `account-balance`).

   note 198 audit M9: this query had NO state or valid-time filter at all,
   so it and `account-balance` disagreed about which lines even exist on the
   account. A draft GR/IR line showed as open against a balance that had
   never counted it, and the clearing account read as un-nettable forever."
  ([db account-eid] (open-lines-on-account db account-eid nil))
  ([db account-eid {:keys [include-states as-of-valid]
                    :or   {include-states default-included-states}}]
   (let [cutoff-ms (when as-of-valid (.getTime ^java.util.Date as-of-valid))]
     (->> (d/q '[:find ?p ?state ?vf
                 :in $ ?acct
                 :where
                 [?p :kontor.posting/account ?acct]
                 [?p :kontor.posting/transaction ?t ?tx]
                 [?t :kontor.transaction/state ?state]
                 [?tx :db/txInstant ?ti]
                 [(get-else $ ?tx :db.valid/from ?ti) ?vf]]
               db account-eid)
          (keep (fn [[p state ^java.util.Date vf]]
                  (when (and (contains? include-states state)
                             (or (nil? cutoff-ms)
                                 (<= (.getTime vf) ^long cutoff-ms)))
                    (let [r (residual-of db p)]
                      (when-not (zero? (.signum ^java.math.BigDecimal r))
                        {:posting-eid p :residual r})))))
          (sort-by :posting-eid)
          vec))))

(defn matched-set
  "Every posting sharing a `:full-reconcile` group with `posting-eid`."
  [db posting-eid]
  (when-let [fr (:db/id (:kontor.posting/full-reconcile
                         (d/pull db [{:kontor.posting/full-reconcile [:db/id]}]
                                 posting-eid)))]
    (vec (sort (d/q '[:find [?p ...]
                      :in $ ?fr
                      :where [?p :kontor.posting/full-reconcile ?fr]]
                    db fr)))))

;; ============================================================================
;; Reconcile
;; ============================================================================

(defn- pull-line [db eid]
  (let [p (d/pull db [:db/id :kontor.posting/amount
                      :kontor.posting/amount-residual
                      {:kontor.posting/commodity [:db/id]}
                      {:kontor.posting/account [:db/id]}]
                  eid)]
    {:eid       (:db/id p)
     :residual  (or (:kontor.posting/amount-residual p) (:kontor.posting/amount p) 0M)
     :commodity (:db/id (:kontor.posting/commodity p))
     :account   (:db/id (:kontor.posting/account p))}))

(defn reconcile-lines-tx-data
  "Pure builder: match the given postings against each other, debit-side against
   credit-side, greedily and deterministically (oldest eid first).

   Emits one `:partial-reconcile` per matched pair, updates every touched
   line's `:kontor.posting/amount-residual`, and — when EVERY line in the set
   lands on residual zero — a `:full-reconcile` group whose `:code` is mirrored
   onto each member's `:kontor.posting/matching-number`.

   Required opts: `:postings` (≥ 2 posting eids), `:matched-at` (instant).
   Optional: `:code` (the full-reconcile code; defaults to a stable code derived
   from the lowest posting eid), `:reconciled-by-uid`.

   Throws when the lines span several commodities — a match is per-commodity
   (cross-currency settlement goes through
   `kontor.banking.payment-application/settle-invoice!`, which converts and
   books the realized FX)."
  [db {:keys [postings matched-at code reconciled-by-uid]}]
  (when (< (count postings) 2)
    (throw (ex-info "reconcile-lines: at least two postings are required"
                    {:type :line-reconcile/too-few-lines :postings postings})))
  (let [lines (mapv #(pull-line db %) postings)
        commodities (into #{} (map :commodity) lines)
        _ (when (> (count commodities) 1)
            (throw (ex-info (str "reconcile-lines: lines span " (count commodities)
                                 " commodities — a match is per-commodity")
                            {:type :line-reconcile/mixed-commodity
                             :commodities commodities})))
        commodity (first commodities)
        matched-at (or matched-at (java.util.Date.))
        debits  (->> lines (filter #(pos? (.signum ^java.math.BigDecimal (:residual %))))
                     (sort-by :eid) vec)
        credits (->> lines (filter #(neg? (.signum ^java.math.BigDecimal (:residual %))))
                     (sort-by :eid) vec)
        ;; Greedy pairing: walk debits and credits together, matching the
        ;; smaller of the two remaining magnitudes each step.
        {:keys [pairs residuals]}
        (loop [ds debits, cs credits, pairs [], residuals {}]
          (if (or (empty? ds) (empty? cs))
            {:pairs pairs
             :residuals (merge residuals
                               (into {} (map (juxt :eid :residual)) ds)
                               (into {} (map (juxt :eid :residual)) cs))}
            (let [d (first ds), c (first cs)
                  dr ^java.math.BigDecimal (:residual d)
                  cr ^java.math.BigDecimal (.negate ^java.math.BigDecimal (:residual c))
                  m  (if (<= (.compareTo dr cr) 0) dr cr)
                  d' (.subtract dr m)
                  c' (.subtract cr m)]
              (recur (if (zero? (.signum d')) (rest ds) (cons (assoc d :residual d') (rest ds)))
                     (if (zero? (.signum c')) (rest cs) (cons (assoc c :residual (.negate c')) (rest cs)))
                     (conj pairs {:debit (:eid d) :credit (:eid c) :amount m})
                     (cond-> residuals
                       (zero? (.signum d')) (assoc (:eid d) 0M)
                       (zero? (.signum c')) (assoc (:eid c) 0M))))))
        fully? (every? #(zero? (.signum ^java.math.BigDecimal (or (get residuals (:eid %)) 0M)))
                       lines)
        fr-code (or code (str "MR-" (apply min (map :eid lines))))
        fr-tempid "full-reconcile"
        partial-rows
        (map-indexed
         (fn [i {:keys [debit credit amount]}]
           (cond-> {:db/id (str "partial-" i)
                    :kontor.partial-reconcile/debit-line debit
                    :kontor.partial-reconcile/credit-line credit
                    :kontor.partial-reconcile/amount amount
                    :kontor.partial-reconcile/matched-at matched-at}
             commodity (assoc :kontor.partial-reconcile/commodity commodity)
             fully?    (assoc :kontor.partial-reconcile/full-reconcile fr-tempid)))
         pairs)
        line-rows
        (map (fn [{:keys [eid]}]
               (cond-> {:db/id eid
                        :kontor.posting/amount-residual (or (get residuals eid) 0M)}
                 fully? (assoc :kontor.posting/full-reconcile fr-tempid
                               :kontor.posting/matching-number fr-code)))
             lines)
        fr-row (when fully?
                 [(cond-> {:db/id fr-tempid
                           :kontor.full-reconcile/code fr-code
                           :kontor.full-reconcile/reconciled-at matched-at}
                    reconciled-by-uid
                    (assoc :kontor.full-reconcile/reconciled-by-uid reconciled-by-uid))])]
    (vec (concat fr-row partial-rows line-rows))))

(defn reconcile-lines!
  "ADR-068 `!` wrapper for [[reconcile-lines-tx-data]] — routes through the
   validation gate. Returns the tx-report."
  [conn opts]
  (validation/transact-with-validation
   conn (reconcile-lines-tx-data (d/db conn) opts)))

(defn fully-reconciled?
  "True iff `posting-eid` belongs to a closed `:full-reconcile` group."
  [db posting-eid]
  (some? (:kontor.posting/full-reconcile
          (d/pull db [{:kontor.posting/full-reconcile [:db/id]}] posting-eid))))
