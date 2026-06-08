(ns kontor.tax.sole-proprietor
  "The sole-proprietor rung of the individual → corporation continuum
   (note 104 Phase 2).

   A sole proprietor's trade is kept as its own bookkeeping `:entity`
   (ADR-031). Its profit-and-loss stands alone — a kontor DB may hold
   *only* the business, with no personal accounts at all — and that is
   the common case for a side hustle or a freelance practice.

   When the proprietor also files a personal income-tax return, the
   business's NET profit flows onto it: the CA `t2125` pattern,
   generalised. `business-net` marginalizes (σ_E) the business P&L;
   `business-income-input` folds that net into the `:inputs` of a
   `kontor.tax.personal-income-tax` provider call as an addition to the
   personal taxable base. The wiring is optional — the business books
   never depend on the personal return.

   This is composition, not new substrate — `:entity`, `kontor.book`,
   `kontor.reporting.report/marginalize`, the tax providers and
   `kontor.tax.vat-return` already exist; this namespace is the thin
   sole-proprietor seam over them."
  (:require [kontor.money :as money]
            [kontor.reporting.report :as report]))

(defn business-net
  "The sole proprietor's business net profit over a period —
   Σ income − Σ expense, marginalized (σ_E) over the business P&L.
   `opts`:
     :from :to    — the period (half-open)
     :entity      — the business entity (ADR-031); nil = the whole
                    book (the business kept standalone)
     :commodity   — the functional commodity (default :EUR)
   Returns Money — may be negative (a trading loss)."
  [conn {:keys [from to entity commodity] :or {commodity :EUR}}]
  (let [postings (report/report-postings
                  conn (cond-> {:from from :to to}
                         entity (assoc :entity entity)))
        by-type  (report/marginalize postings :account-type
                                     {:sign :inflow :commodity commodity})]
    (money/sub (get-in by-type [:income :value]  (money/zero commodity))
               (get-in by-type [:expense :value] (money/zero commodity)))))

(defn business-income-input
  "Fold a sole proprietor's `business-net` into the `:inputs` of a
   `kontor.tax.personal-income-tax` provider call — the business net joins
   the personal taxable base as an addition (the t2125 pattern:
   business income flows onto the personal return). Additions and
   deductions the consumer already placed in `:inputs :base-transform`
   (expected to be an `:adjustments` transform) are preserved."
  [inputs business-net]
  (let [t (:base-transform inputs)]
    (assoc inputs :base-transform
           {:transform/type :adjustments
            :additions  (conj (vec (:additions t)) (:amount business-net))
            :deductions (vec (:deductions t))})))
