(ns kontor.collections.aging
  "Collections-aware aging extension — ADR-043.

   Wraps kernel `kontor.reporting.aging` with three extensions:
     - Aging method choice (`:method :due-date | :invoice-date |
       :statement-date`).
     - Open-amount from `:payment-application` rows (kernel
       `open-receivables-by-tx` only looks at `:kontor.transaction/settles`
       offsets; doesn't see partial payments).
     - Per-customer term-relative buckets (`:partner-payment-terms`
       opt).

   Returns a per-invoice aging shape compatible with the kernel
   aging row but with the invoice's net open balance, NOT the gross
   transaction balance."
  (:require [datahike.api :as d]
            [kontor.reporting.aging :as kaging]
            [kontor.banking.payment-application :as papp])
  (:import [java.time Duration]
           [java.util Date]))

;; ============================================================================
;; Per-customer term-relative buckets
;; ============================================================================

(defn- bucket-for
  "Reuses kontor.reporting.aging's bucket convention but accepts a
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
                   :currency :overpaid? :unapplied-credit}.

   NON-POSITIVE ROWS ARE NOT DROPPED (ADR-161). This used to end in
   `(filter #(pos? (:open-amount %)))`, so an OVERPAID invoice vanished
   from the collections view entirely instead of surfacing as a customer
   credit — and an invoice sitting at residual zero with a stale `:sent`
   status vanished with it, hiding exactly the desync this ADR fixes.
   An overpaid row carries `:overpaid? true` and `:unapplied-credit`
   (the positive magnitude of the negative residual).

   `aging-rows` still excludes non-positive rows from the aged buckets —
   a credit balance is not a receivable to age or dun — so the dunning
   path is unaffected. Existence and dunnability are different questions.

   Filter by `:partner-eid` opt to scope to one customer."
  [db {:keys [entity-eid partner-eid as-of-valid]}]
  (let [as-of-valid (or as-of-valid (Date.))
        eids (if partner-eid
               (d/q '[:find [?i ...]
                      :in $ ?e ?p
                      :where
                      [?i :kontor.invoice/buyer ?p]
                      [?i :kontor.invoice/entity ?e]
                      (or [?i :kontor.invoice/status :sent]
                          [?i :kontor.invoice/status :partially-paid])]
                    db entity-eid partner-eid)
               (d/q '[:find [?i ...]
                      :in $ ?e
                      :where
                      [?i :kontor.invoice/entity ?e]
                      (or [?i :kontor.invoice/status :sent]
                          [?i :kontor.invoice/status :partially-paid])]
                    db entity-eid))]
    (->> eids
         (map (fn [eid]
                (let [pulled (d/pull db
                                     '[:db/id
                                       :kontor.invoice/external-id
                                       :kontor.invoice/total-gross
                                       :kontor.invoice/issue-date
                                       :kontor.invoice/currency
                                       {:kontor.invoice/buyer [:db/id :kontor.partner/name]}
                                       {:kontor.invoice/transaction
                                        [:kontor.transaction/due-date]}]
                                     eid)
                      open ^java.math.BigDecimal
                      (papp/open-amount-of-invoice
                       db eid {:as-of-valid as-of-valid})
                      overpaid? (neg? (.signum open))]
                  (cond-> {:invoice-eid (:db/id pulled)
                           :external-id (:kontor.invoice/external-id pulled)
                           :open-amount open
                           ;; N4 (note 196): :kontor.invoice/total-gross is unset
                           ;; on line-based (bridge) invoices, which reported
                           ;; :gross nil. Fall back to the line sum — and reuse
                           ;; the kernel's `gross-of-invoice`, whose aggregate
                           ;; carries `:with ?l`. The inline copy that used to
                           ;; live here did NOT, so datahike set-semantics
                           ;; collapsed two lines of the SAME amount and a
                           ;; 2 x 500.00 invoice reported :gross 500.00
                           ;; (ADR-162). This is the LIVE path for bridge
                           ;; invoices, since modules/invoice never sets
                           ;; :total-gross while bridge.clj creates the lines.
                           :gross (papp/gross-of-invoice db (:db/id pulled))
                           :issue-date (:kontor.invoice/issue-date pulled)
                           :due-date (get-in pulled [:kontor.invoice/transaction
                                                     :kontor.transaction/due-date])
                           :partner-eid (get-in pulled [:kontor.invoice/buyer :db/id])
                           :partner-name (get-in pulled [:kontor.invoice/buyer
                                                         :kontor.partner/name])
                           :currency (:kontor.invoice/currency pulled)}
                    overpaid? (assoc :overpaid? true
                                     :unapplied-credit (.negate open))))))
         vec)))

(defn aging-rows
  "Per-invoice aging with collections-aware open-amount.

   Required opts:
     :entity-eid    ADR-031 scope

   Optional opts:
     :method                :due-date | :invoice-date | :statement-date
                            (default :due-date)
     :as-of                 reference date for days-overdue bucketing
                            (default today)
     :as-of-valid           bitemporal value-time cursor deciding which
                            :payment-applications are visible in the open
                            amount. Defaults to :as-of, NOT to now — this
                            is deliberate: an aging report \"as of D\" must
                            reflect the cash that had actually been applied
                            by D, so a historical run correctly excludes
                            payments received after D (they weren't in yet).
                            Pass :as-of-valid explicitly to decouple the two
                            axes — e.g. age by a past date but with today's
                            payment knowledge. (note 196 N7 — the two axes
                            are separate and independently controllable.)
     :buckets               vector of [label upper-day]
                            (default kontor.reporting.aging/default-buckets)
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
         ;; ADR-161: `open-ar-invoices` now surfaces non-positive rows so an
         ;; overpayment is visible instead of vanishing. Aging is where they
         ;; are correctly excluded: a credit balance is not a receivable to
         ;; age, bucket or dun. Dunning callers derive their `:cases` from
         ;; here, so this is the gate that keeps a credit out of a letter.
         (filter #(pos? (.signum ^java.math.BigDecimal (:open-amount %))))
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
