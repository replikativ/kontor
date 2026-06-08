(ns kontor.l10n-ca.y2024.t2125
  "T2125 — Statement of Business or Professional Activities (TY2024).

   Computes net self-employment income for routing to T1 line 13500
   (business income) or 13700 (commission) or 13900 (other).

   Scope of this slice:
     - Gross sales/revenue + adjustments → gross income.
     - Itemized expenses → total expenses.
     - Basic CCA per class (half-year rule for additions).
     - Net business income.

   Out of scope (post-MVP):
     - Cost of goods sold (most relevant for inventory businesses).
     - Accelerated Investment Incentive (AII) on CCA.
     - Class 10.1 (passenger vehicles, special $30k cap rules).
     - Immediate expensing (post-2021 limited-time provision).
     - Terminal loss / recapture on dispositions.
     - Home office (T2125 Part 7) — needs business-use % factor input.
     - Vehicle expenses (T2125 Part 8) — needs business-km / total-km.

   CCA half-year rule: in the year of acquisition, only half of the net
   additions count toward CCA basis. We implement this baseline; AII
   (which suspends half-year for designated property) is a future
   refinement."
  (:require [kontor.money :as money]))

(defn- bd ^java.math.BigDecimal [m] (:amount m))
(defn- m-zero [] (money/zero :CAD))
(defn- m-sum [monies] (reduce money/add (m-zero) monies))

(defn cca-claim
  "Compute CCA claim and closing UCC for a single class for TY2024.

   class: {:cca/class      Long          ; CRA class number, e.g. 8
           :cca/rate       BigDecimal    ; declining-balance rate, e.g. 0.20
           :cca/opening-ucc Money         ; UCC at start of year
           :cca/additions  Money         ; net additions during year
           :cca/dispositions Money}       ; net dispositions (proceeds, capped)

   Algorithm (baseline half-year):
     1. half-year basis = additions - dispositions, halved if positive
     2. CCA-basis = opening-UCC + half-year-basis
     3. CCA claim  = CCA-basis × rate
     4. closing UCC = opening + additions - dispositions - CCA-claim

   Returns the input map enriched with :cca/claim and :cca/closing-ucc."
  [{:cca/keys [rate opening-ucc additions dispositions] :as cls}]
  (let [opening (bd opening-ucc)
        adds    (bd (or additions (m-zero)))
        disps   (bd (or dispositions (m-zero)))
        net-add (.subtract adds disps)
        half-year-adj (if (pos? (.signum net-add))
                        (.divide net-add 2M 2
                                 java.math.RoundingMode/HALF_EVEN)
                        net-add)
        cca-basis (.add opening half-year-adj)
        claim-raw (.multiply cca-basis rate)
        claim (.max java.math.BigDecimal/ZERO
                    (.setScale claim-raw 2 java.math.RoundingMode/HALF_EVEN))
        closing (.subtract (.add opening adds) (.add disps claim))]
    (assoc cls
           :cca/claim       (money/money claim :CAD)
           :cca/closing-ucc (money/money
                             (.setScale closing 2 java.math.RoundingMode/HALF_EVEN)
                             :CAD))))

(defn compute
  "Compute net business income for a T2125 form.

   Input:
     {:t2125/gross-income     Money :CAD     ; line 8299 gross
      :t2125/expenses         [Money :CAD]   ; itemized — sum used
      :t2125/cca-classes      [class-map …]} ; one per CCA class

   Returns the input enriched with:
     :t2125/total-expenses    Money :CAD
     :t2125/total-cca         Money :CAD
     :t2125/net-income        Money :CAD     ; lands on T1 line 13500"
  [{:t2125/keys [gross-income expenses cca-classes]
    :or {gross-income (m-zero) expenses [] cca-classes []}
    :as t2125}]
  (let [classes (mapv cca-claim cca-classes)
        total-expenses (m-sum expenses)
        total-cca      (m-sum (map :cca/claim classes))
        net (-> gross-income
                (money/sub total-expenses)
                (money/sub total-cca))]
    (assoc t2125
           :t2125/cca-classes   classes
           :t2125/total-expenses total-expenses
           :t2125/total-cca      total-cca
           :t2125/net-income     net)))
