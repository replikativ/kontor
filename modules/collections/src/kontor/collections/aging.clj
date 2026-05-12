(ns kontor.collections.aging
  "Collections-aware aging extension — ADR-043.

   Wraps kernel `kontor.aging` with two market-pain fixes:
     - Aging method choice (`:method :due-date | :invoice-date |
       :statement-date`) — pain #1 in research note 15.
     - Open-amount from `:payment-application` rows (kernel
       `open-receivables-by-tx` only looks at `:transaction/settles`
       offsets; doesn't see partial payments).
     - Per-customer term-relative buckets (`:partner-payment-terms`
       opt) — pain #1 again.

   Returns a per-invoice aging shape compatible with the kernel
   aging row but with the invoice's net open balance, NOT the gross
   transaction balance."
  (:require [datahike.api :as d]
            [kontor.aging :as kaging]
            [kontor.payment-application :as papp])
  (:import [java.time Duration]
           [java.util Date]))

;; ============================================================================
;; Per-customer term-relative buckets
;; ============================================================================

(defn- bucket-for
  "Reuses kontor.aging's bucket convention but accepts a
   `partner-grace-days` override (customer's payment-term offset
   beyond due-date)."
  [days-overdue partner-grace-days buckets]
  (let [effective-overdue (- days-overdue (or partner-grace-days 0))]
    (cond
      (<= effective-overdue 0) :not-yet-due
      :else (or (some (fn [[label upper]]
                        (when (or (nil? upper) (<= effective-overdue upper))
                          label))
                      buckets)
                :unknown))))

(defn- days-between [^Date from ^Date to]
  (.toDays (Duration/between (.toInstant from) (.toInstant to))))

;; ============================================================================
;; Open AR invoices
;; ============================================================================

(defn open-ar-invoices
  "All sales invoices currently in (:sent :partially-paid) state for
   an entity, with their net open-amount from payment-applications.

   Returns vec of {:invoice-eid :external-id :open-amount :gross
                   :issue-date :due-date :partner-eid :partner-name
                   :currency}.

   Filter by `:partner-eid` opt to scope to one customer."
  [db {:keys [entity-eid partner-eid as-of-valid]}]
  (let [as-of-valid (or as-of-valid (Date.))
        eids (if partner-eid
               (d/q '[:find [?i ...]
                      :in $ ?e ?p
                      :where
                      [?i :invoice/buyer ?p]
                      [?i :invoice/entity ?e]
                      (or [?i :invoice/status :sent]
                          [?i :invoice/status :partially-paid])]
                    db entity-eid partner-eid)
               (d/q '[:find [?i ...]
                      :in $ ?e
                      :where
                      [?i :invoice/entity ?e]
                      (or [?i :invoice/status :sent]
                          [?i :invoice/status :partially-paid])]
                    db entity-eid))]
    (->> eids
         (map (fn [eid]
                (let [pulled (d/pull db
                                     '[:db/id
                                       :invoice/external-id
                                       :invoice/total-gross
                                       :invoice/issue-date
                                       :invoice/currency
                                       {:invoice/buyer [:db/id :partner/name]}
                                       {:invoice/transaction
                                        [:transaction/due-date]}]
                                     eid)
                      open (papp/open-amount-of-invoice
                            db eid {:as-of-valid as-of-valid})]
                  {:invoice-eid (:db/id pulled)
                   :external-id (:invoice/external-id pulled)
                   :open-amount open
                   :gross (:invoice/total-gross pulled)
                   :issue-date (:invoice/issue-date pulled)
                   :due-date (get-in pulled [:invoice/transaction
                                             :transaction/due-date])
                   :partner-eid (get-in pulled [:invoice/buyer :db/id])
                   :partner-name (get-in pulled [:invoice/buyer :partner/name])
                   :currency (:invoice/currency pulled)})))
         (filter #(pos? (.signum ^java.math.BigDecimal (:open-amount %))))
         vec)))

(defn aging-rows
  "Per-invoice aging with collections-aware open-amount.

   Required opts:
     :entity-eid    ADR-031 scope

   Optional opts:
     :method                :due-date | :invoice-date | :statement-date
                            (default :due-date)
     :as-of                 reference date (default today)
     :as-of-valid           bitemporal value-time cursor for the
                            invoice's :payment-application net
                            (default :as-of)
     :buckets               vector of [label upper-day]
                            (default kontor.aging/default-buckets)
     :partner-payment-terms map of partner-eid → grace-days; the
                            bucket lookup subtracts the grace from
                            days-overdue when scoring
     :partner-eid           filter to one partner
     :statement-date        used when :method is :statement-date"
  [db {:keys [entity-eid method as-of as-of-valid buckets
              partner-payment-terms partner-eid statement-date]
       :or {method :due-date
            as-of (Date.)
            buckets kaging/default-buckets}
       :as opts}]
  (let [as-of-valid (or as-of-valid as-of)
        invs (open-ar-invoices db {:entity-eid entity-eid
                                   :partner-eid partner-eid
                                   :as-of-valid as-of-valid})]
    (->> invs
         (mapv (fn [{:keys [invoice-eid issue-date due-date partner-eid] :as inv}]
                 (let [reference (case method
                                   :due-date due-date
                                   :invoice-date issue-date
                                   :statement-date (or statement-date issue-date)
                                   due-date)
                       overdue (if reference
                                 (days-between reference as-of) 0)
                       grace (and partner-payment-terms
                                  (get partner-payment-terms partner-eid))
                       bkt (bucket-for overdue grace buckets)]
                   (assoc inv
                          :method method
                          :reference-date reference
                          :days-overdue overdue
                          :grace-days grace
                          :bucket bkt))))
         (sort-by :due-date)
         vec)))

(defn aging-summary
  "Sum open-amounts per bucket. Returns:
     {:not-yet-due BigDecimal
      :0-30 BigDecimal :31-60 BigDecimal :61-90 BigDecimal :90+ BigDecimal
      :total BigDecimal}"
  [db opts]
  (let [rows (aging-rows db opts)
        zero 0M]
    (reduce (fn [acc r]
              (-> acc
                  (update (:bucket r) #(.add ^java.math.BigDecimal
                                             (or % zero)
                                             ^java.math.BigDecimal
                                             (:open-amount r)))
                  (update :total #(.add ^java.math.BigDecimal
                                        (or % zero)
                                        ^java.math.BigDecimal
                                        (:open-amount r)))))
            {:not-yet-due zero :0-30 zero :31-60 zero :61-90 zero :90+ zero
             :total zero}
            rows)))

(defn aging-by-partner
  [db opts]
  (let [rows (aging-rows db opts)]
    (->> rows
         (group-by :partner-eid)
         (mapv (fn [[partner-eid partner-rows]]
                 {:partner-eid partner-eid
                  :partner-name (:partner-name (first partner-rows))
                  :total (reduce (fn [^java.math.BigDecimal acc r]
                                   (.add acc ^java.math.BigDecimal
                                         (:open-amount r)))
                                 0M partner-rows)
                  :rows partner-rows}))
         (sort-by (fn [r] (- (.doubleValue ^java.math.BigDecimal (:total r))))))))
