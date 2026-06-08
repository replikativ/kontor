(ns kontor.l10n-ca.y2024.s4
  "Schedule 4 — Statement of investment income (TY2024).

   Three income types we cover:
     1. Interest (line 12100) — taxed at marginal rate, no gross-up.
     2. Eligible dividends (from large corps) — 1.38× gross-up,
        federal DTC at 15.0198% of taxable amount (= 6/11ths of gross-up).
     3. Non-eligible dividends (CCPC dividends) — 1.15× gross-up,
        federal DTC at 9.0301% of taxable amount.

   Provincial DTC (BC, 2024):
     Eligible:    12.0% of grossed-up amount
     Non-eligible: 1.96% of grossed-up amount

   Taxable dividend amounts feed into T1 line 12000.
   Federal DTC feeds T1 (post-MVP integration: line 40424 reducing
     federal tax before NRTCs).
   BC DTC feeds BC428 as a credit against BC tax.

   Out of scope:
     - Foreign investment income, foreign tax credit (T2209).
     - Tax-deductible carrying charges (S4 Part 3 — line 22100).
     - Charitable donations of securities.
     - REIT-distribution recharacterization."
  (:require [kontor.money :as money]))

(def eligible-gross-up 1.38M)
(def eligible-federal-dtc-rate 0.150198M)
(def eligible-bc-dtc-rate 0.12M)
(def non-eligible-gross-up 1.15M)
(def non-eligible-federal-dtc-rate 0.090301M)
(def non-eligible-bc-dtc-rate 0.0196M)

(defn- m-cents [m]
  (money/money
   (.setScale ^java.math.BigDecimal (:amount m) 2
              java.math.RoundingMode/HALF_EVEN)
   :CAD))

(defn compute
  "Compute investment-income amounts and DTCs.

   Input:
     {:s4/interest             Money :CAD
      :s4/dividends-eligible   Money :CAD    ; actual amount received
      :s4/dividends-non-eligible Money :CAD} ; actual amount received

   Returns:
     {:s4/line-12100         Money       ; interest
      :s4/line-12000         Money       ; total taxable dividends
      :s4/federal-dtc        Money       ; total federal DTC
      :s4/bc-dtc             Money       ; total BC provincial DTC
      :s4/grossed-up-eligible      Money
      :s4/grossed-up-non-eligible  Money}"
  [{:s4/keys [interest dividends-eligible dividends-non-eligible]
    :or {interest               (money/zero :CAD)
         dividends-eligible     (money/zero :CAD)
         dividends-non-eligible (money/zero :CAD)}}]
  (let [gu-elig (money/mul-scalar dividends-eligible eligible-gross-up)
        gu-non  (money/mul-scalar dividends-non-eligible non-eligible-gross-up)
        federal-dtc-elig (money/mul-scalar gu-elig eligible-federal-dtc-rate)
        federal-dtc-non  (money/mul-scalar gu-non  non-eligible-federal-dtc-rate)
        bc-dtc-elig      (money/mul-scalar gu-elig eligible-bc-dtc-rate)
        bc-dtc-non       (money/mul-scalar gu-non  non-eligible-bc-dtc-rate)]
    {:s4/line-12100              (m-cents interest)
     :s4/line-12000              (m-cents (money/add gu-elig gu-non))
     :s4/federal-dtc             (m-cents (money/add federal-dtc-elig federal-dtc-non))
     :s4/bc-dtc                  (m-cents (money/add bc-dtc-elig bc-dtc-non))
     :s4/grossed-up-eligible     (m-cents gu-elig)
     :s4/grossed-up-non-eligible (m-cents gu-non)}))
