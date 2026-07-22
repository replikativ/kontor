(ns kontor.reporting.aging
  "AR / AP aging reports — categorize open balances by how long
   they've been outstanding past due. Drives collections workflow
   (which customers to chase) and DSO (Days Sales Outstanding)
   reporting.

   Standard buckets:
     not-yet-due  — due-date is in the future
     0-30         — 0-30 days past due
     31-60        — 31-60 days past due
     61-90        — 61-90 days past due
     90+          — > 90 days past due

   Sits on top of `reconciliation/open-receivables-by-tx` and the
   `:kontor.transaction/due-date` attr (set by `payment-term.clj`)."
  (:require [datahike.api :as d]
            [kontor.money :as money]
            [kontor.banking.reconciliation :as recon]))

(defn- ->ms [x] #?(:clj (.getTime ^java.util.Date x) :cljs (if (number? x) x (.getTime x))))
(defn- now [] #?(:clj (java.util.Date.) :cljs (js/Date.)))

;; ============================================================================
;; Bucketing
;; ============================================================================

(def default-buckets
  "Standard aging buckets. `[:label upper-day-bound]`. Last entry
   has nil upper bound (= catch-all). Days are non-negative; a
   negative-or-zero overdue means not-yet-due."
  [[:0-30  30]
   [:31-60 60]
   [:61-90 90]
   [:90+   nil]])

(defn- days-between
  "Calendar days from `from` to `to` (positive when to > from)."
  [from to]
  (quot (- (->ms to) (->ms from)) 86400000))

(defn- bucket-for
  "Pick the bucket label for `days-overdue`. Returns :not-yet-due
   when days-overdue ≤ 0."
  [days-overdue buckets]
  (if (<= days-overdue 0)
    :not-yet-due
    (or (some (fn [[label upper]]
                (when (or (nil? upper) (<= days-overdue upper))
                  label))
              buckets)
        :unknown)))

;; ============================================================================
;; Public: aging
;; ============================================================================

(defn aging-rows
  "Per-invoice aging rows. Each row:
     {:transaction-eid :external-id :open-amount :commodity
      :due-date :days-overdue :bucket
      :partner-eid :partner-name}

   `:open-amount` is a bare platform decimal; `:commodity` is its
   commodity eid. Filter on `:commodity` to age a multi-currency ledger
   one currency at a time — the summary fns below refuse to blend.

   `as-of` defaults to today. `buckets` defaults to `default-buckets`.
   `account-codes` is the set of chart codes treated as AR / AP
   (e.g. #{\"1400\"} for SKR04 receivables)."
  [db account-codes
   & {:keys [as-of buckets ar-or-ap]
      :or {as-of (now) buckets default-buckets ar-or-ap :ar}}]
  (let [opens (case ar-or-ap
                :ar (recon/open-receivables-by-tx db account-codes)
                :ap (recon/open-payables-by-tx db account-codes))]
    (->> opens
         (mapv (fn [{:keys [transaction-eid] :as o}]
                 (let [tx (d/pull db
                                  [:db/id
                                   :kontor.transaction/due-date
                                   {:kontor.transaction/partner [:db/id :kontor.partner/name]}]
                                  transaction-eid)
                       due (or (:kontor.transaction/due-date tx) (:date o))
                       overdue (if due (days-between due as-of) 0)
                       partner (:kontor.transaction/partner tx)]
                   (assoc o
                          :due-date due
                          :days-overdue overdue
                          :bucket (bucket-for overdue buckets)
                          :partner-eid (:db/id partner)
                          :partner-name (:kontor.partner/name partner)))))
         (sort-by :due-date)
         vec)))

(defn- rows-commodity
  "The single commodity `rows` are denominated in, or nil for no rows.
   Throws when they span several.

   These summaries fold every open item into one number per bucket, and
   that number is only meaningful within one currency. The fold used to
   run over bare platform decimals with no commodity anywhere, so a book
   holding EUR and USD receivables produced a silently meaningless total
   that the docstrings nonetheless described as Money. Refusing is the
   same stance `report/sum-postings` takes under `:strict-commodity?`.

   To age a multi-currency ledger, filter `aging-rows` by `:commodity`
   and summarise each currency separately."
  [rows]
  (let [cs (into #{} (keep :commodity) rows)]
    (when (> (count cs) 1)
      (throw (ex-info "aging: open items span multiple commodities"
                      {:type :aging/mixed-commodity :commodities cs})))
    (first cs)))

(defn aging-summary-by-bucket
  "Sum open amounts per bucket. Returns
     {:not-yet-due Money :0-30 Money :31-60 Money :61-90 Money :90+ Money :total Money}
   For AR (positive open amounts) and AP (negative open amounts) the
   sums are signed accordingly (caller can `abs` for display).

   Every value is a real Money — commodity included. Throws
   `:aging/mixed-commodity` if the open items span more than one
   currency; see [[rows-commodity]]."
  [db account-codes & opts]
  (let [rows (apply aging-rows db account-codes opts)
        c    (rows-commodity rows)
        zero (money/zero c)
        add  (fn [m r] (money/add (or m zero) (money/money (:open-amount r) c)))]
    (reduce (fn [acc r]
              (-> acc
                  (update (:bucket r) add r)
                  (update :total      add r)))
            {:not-yet-due zero :0-30 zero :31-60 zero :61-90 zero :90+ zero
             :total zero}
            rows)))

(defn aging-by-partner
  "Per-partner aging breakdown. Returns vec of:
     {:partner-eid :partner-name
      :total Money
      :buckets {bucket Money …}
      :rows [<aging row> …]}
   Sorted by total descending so the bookkeeper sees the biggest
   exposures first."
  [db account-codes & opts]
  (let [rows (apply aging-rows db account-codes opts)
        c    (rows-commodity rows)
        zero (money/zero c)
        add  (fn [m r] (money/add (or m zero) (money/money (:open-amount r) c)))]
    (->> rows
         (group-by :partner-eid)
         (map (fn [[partner-eid partner-rows]]
                (let [total (reduce add zero partner-rows)
                      by-bucket (reduce
                                 (fn [acc r] (update acc (:bucket r) add r))
                                 {} partner-rows)]
                  {:partner-eid partner-eid
                   :partner-name (:partner-name (first partner-rows))
                   :total total
                   :buckets by-bucket
                   :rows partner-rows})))
         (sort-by (fn [r] (- (money/amount-sign (:amount (:total r)))
                             (Math/abs (money/amount->double (:amount (:total r)))))))
         vec)))
