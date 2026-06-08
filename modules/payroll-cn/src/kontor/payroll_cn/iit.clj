(ns kontor.payroll-cn.iit
  "IIT (个人所得税) computation oracle — Stage R C11 / ADR-085.

   ## Posture

   kontor does NOT compute IIT. The cumulative-method withholding
   (国家税务总局公告 2018年第61号) and the 年终奖 separate-tax election
   (财税〔2018〕164号 — extended to 2027 by 财政部国家税务总局公告
   2023年第30号) run inside the engine (Yonyou / Kingdee / Beisen).
   The engine's :iit-withheld component IS the authoritative figure.

   This namespace ships:

     1. `iit-summary-for-period` — aggregates the :iit-withheld
        component across a vector of `PayrollFacts` to produce the
        per-period IIT control total. Used by the emit provider for
        the audit-doc payload + by the consumer's reconciliation.

     2. `iit-summary-per-employee` — same aggregation grouped by
        :employment eid (for the IIT filing breakout — every
        employee's monthly IIT must be reported individually to the
        自然人电子税务局).

     3. `annual-bonus-method` — read-helper that pulls the per-fact
        `:cn/annual-bonus-method` from `:jurisdiction-specific-codes`.
        Defaults to `:combined` when unspecified.

   ## What we deliberately do NOT do

   - We do NOT recompute IIT from gross + cumulative YTD. The engine
     is authoritative; recomputing would be a regulator-grade
     exercise (brackets versioned 2024-04) and is out of scope per
     ADR-005 / ADR-071 / ADR-075.

   - We do NOT bundle bracket tables. They are public regulation but
     versioned; the engine holds the current set.

   - We do NOT validate the 年终奖 election. Per the bonus's
     `:jurisdiction-specific-codes` payload the consumer's tax-prep
     engine reads and acts.

   See doc/research/87-cn-payroll-research-before.md §2.1 + §2.4."
  (:require [kontor.payroll-cn.wage-types :as wt])
  (:import [java.math BigDecimal]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- sum-bd
  ^BigDecimal [bds]
  (reduce (fn [a v] (.add ^BigDecimal a ^BigDecimal v)) 0M bds))

(defn- iit-component?
  "True iff a component is the IIT-withheld kind. Per
   kind is `:iit-withheld`."
  [{:keys [kind]}]
  (= :iit-withheld kind))

(defn- bonus-component?
  "True iff a component is the 年终奖 special-tax-treatment kind.
   Open to consumer extension via the extras-map (e.g. a bespoke
   `:exec-bonus` kind tagged :special-tax-treatment?)."
  ([component] (bonus-component? component nil))
  ([{:keys [kind]} extras-map]
   (wt/special-tax-treatment? kind extras-map)))

;; ============================================================================
;; Per-period summary
;; ============================================================================

(defn iit-summary-for-period
  "Aggregate IIT across a vector of PayrollFacts. Returns a map:

     {:total-iit            <BigDecimal> — sum of -:amount across all
                              :iit-withheld components (positive value)
      :total-gross          <BigDecimal> — sum of :gross across facts
      :total-bonus          <BigDecimal> — sum of :amount across all
                              annual-bonus components (positive)
      :employee-count       <long>
      :bonus-method-counts  {<:single | :combined> count}}"
  ([facts] (iit-summary-for-period facts nil))
  ([facts extras-map]
   (let [iit-amounts (->> facts
                          (mapcat :components)
                          (filter iit-component?)
                          (map :amount)
                          (map #(.abs ^BigDecimal %)))
         bonus-amounts (->> facts
                            (mapcat :components)
                            (filter #(bonus-component? % extras-map))
                            (map :amount)
                            (map #(.abs ^BigDecimal %)))
         methods (->> facts
                      (keep (fn [fact]
                              (when (some #(bonus-component? % extras-map)
                                          (:components fact))
                                (or (get-in fact [:jurisdiction-specific-codes
                                                  :cn/annual-bonus-method])
                                    :combined))))
                      frequencies)]
     {:total-iit (sum-bd iit-amounts)
      :total-gross (sum-bd (map :gross facts))
      :total-bonus (sum-bd bonus-amounts)
      :employee-count (count facts)
      :bonus-method-counts methods})))

(defn iit-summary-per-employee
  "Per-employee breakout (each map records the employee's
   :employment, :gross, :iit-withheld, :net, and :annual-bonus
   amounts for the period). Used by the emit provider as the per-row
   payload for the 自然人电子税务局 import."
  ([facts] (iit-summary-per-employee facts nil))
  ([facts extras-map]
   (mapv (fn [fact]
           (let [iit (->> (:components fact)
                          (filter iit-component?)
                          (map :amount)
                          (map #(.abs ^BigDecimal %))
                          sum-bd)
                 bonus (->> (:components fact)
                            (filter #(bonus-component? % extras-map))
                            (map :amount)
                            (map #(.abs ^BigDecimal %))
                            sum-bd)
                 method (when (pos? (.signum ^BigDecimal bonus))
                          (or (get-in fact [:jurisdiction-specific-codes
                                            :cn/annual-bonus-method])
                              :combined))
                 ext-id (get-in fact [:jurisdiction-specific-codes
                                      :employee-external-id])]
             (cond-> {:employment (:employment fact)
                      :gross (:gross fact)
                      :net (:net fact)
                      :iit iit
                      :annual-bonus bonus}
               ext-id (assoc :employee-external-id ext-id)
               method (assoc :annual-bonus-method method))))
         facts)))

;; ============================================================================
;; Annual-bonus method helper
;; ============================================================================

(defn annual-bonus-method
  "Return the elected IIT method for a fact's 年终奖, or nil if the
   fact has no bonus component. Defaults to `:combined` when a bonus
   is present but no explicit method is supplied (per the 2027
   extension default).

   The method is carried in `:jurisdiction-specific-codes
   :cn/annual-bonus-method` and must be one of `:single` (单独计税)
   or `:combined` (并入综合所得)."
  ([fact] (annual-bonus-method fact nil))
  ([fact extras-map]
   (when (some #(bonus-component? % extras-map) (:components fact))
     (or (get-in fact [:jurisdiction-specific-codes :cn/annual-bonus-method])
         :combined))))
