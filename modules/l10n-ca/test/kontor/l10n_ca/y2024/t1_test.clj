(ns kontor.l10n-ca.y2024.t1-test
  "Tests for the federal T1 core (TY2024).

   Test values are hand-computed against published 2024 CRA brackets and
   thresholds. Where rounding matters, we apply HALF_EVEN per ADR-013."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-ca.y2024.constants :as k]
            [kontor.l10n-ca.y2024.s9 :as s9]
            [kontor.l10n-ca.y2024.t1 :as t1]
            [kontor.money :as money]))

(defn- cad [s] (money/money (bigdec s) :CAD))

(defn- ≈
  "Money equality (delegates to money/equiv?)."
  [a b]
  (money/equiv? a b))

;; ============================================================================
;; apply-brackets — federal progressive math
;; ============================================================================

(deftest apply-brackets-zero
  (is (≈ (cad "0.00") (t1/apply-brackets (cad "0.00") k/federal-brackets))))

(deftest apply-brackets-bracket-1
  (testing "$10,000 in 15% bracket → $1,500.00"
    (is (≈ (cad "1500.00")
           (t1/apply-brackets (cad "10000.00") k/federal-brackets)))))

(deftest apply-brackets-top-of-bracket-1
  (testing "$55,867 at top of bracket 1 → $8,380.05"
    (is (≈ (cad "8380.05")
           (t1/apply-brackets (cad "55867.00") k/federal-brackets)))))

(deftest apply-brackets-into-bracket-2
  (testing "$100,000 spans brackets 1 and 2.
            15% × 55,867 = 8,380.05
            20.5% × (100,000 - 55,867) = 20.5% × 44,133 = 9,047.265
            Total 17,427.315 → HALF_EVEN to cents = 17,427.32"
    (is (≈ (cad "17427.32")
           (t1/apply-brackets (cad "100000.00") k/federal-brackets)))))

(deftest apply-brackets-into-bracket-4
  (testing "$200,000 spans brackets 1-4.
            Bracket 1: 55,867 × 15%       =  8,380.05
            Bracket 2: 55,866 × 20.5%     = 11,452.53
            Bracket 3: 61,472 × 26%       = 15,982.72
            Bracket 4: 26,795 × 29%       =  7,770.55
            Total                         = 43,585.85"
    (is (≈ (cad "43585.85")
           (t1/apply-brackets (cad "200000.00") k/federal-brackets)))))

;; ============================================================================
;; Federal BPA — phase-out math
;; ============================================================================

(deftest bpa-low-income-full
  (is (≈ (cad "15705.00") (t1/federal-bpa (cad "50000.00")))))

(deftest bpa-at-phaseout-start
  (testing "Exactly $173,205 = top of bracket 3 → still full BPA"
    (is (≈ (cad "15705.00") (t1/federal-bpa (cad "173205.00"))))))

(deftest bpa-at-phaseout-end
  (testing "Exactly $246,752 = top of bracket 4 → minimum BPA $14,156"
    (is (≈ (cad "14156.00") (t1/federal-bpa (cad "246752.00"))))))

(deftest bpa-above-top-bracket
  (is (≈ (cad "14156.00") (t1/federal-bpa (cad "500000.00")))))

(deftest bpa-mid-phaseout
  (testing "$200,000: reduction = 1,549 × 26,795 / 73,547 = 564.34
            BPA = 15,705 - 564.34 = 15,140.66"
    (is (≈ (cad "15140.66") (t1/federal-bpa (cad "200000.00"))))))

;; ============================================================================
;; CPP base/enhanced split
;; ============================================================================

(deftest cpp-split-zero
  (let [[base enh] (t1/cpp-employee-split (cad "0.00"))]
    (is (≈ (cad "0.00") base))
    (is (≈ (cad "0.00") enh))))

(deftest cpp-split-30k-income
  (testing "T4 box 16 = $1,576.75 (CPP at $30k income) splits 1311.75 / 265.00"
    (let [[base enh] (t1/cpp-employee-split (cad "1576.75"))]
      (is (≈ (cad "1311.75") base))
      (is (≈ (cad "265.00") enh)))))

(deftest cpp-split-max
  (testing "Max CPP $3,867.50 splits 3,217.50 (base) / 650.00 (enhanced)"
    (let [[base enh] (t1/cpp-employee-split (cad "3867.50"))]
      (is (≈ (cad "3217.50") base))
      (is (≈ (cad "650.00") enh)))))

;; ============================================================================
;; Donation credit (S9)
;; ============================================================================

(deftest donation-credit-zero
  (is (≈ (cad "0.00")
         (s9/federal-donation-credit (cad "0.00") (cad "50000.00")))))

(deftest donation-credit-under-200
  (testing "$150 donation → 150 × 15% = 22.50"
    (is (≈ (cad "22.50")
           (s9/federal-donation-credit (cad "150.00") (cad "50000.00"))))))

(deftest donation-credit-200-exactly
  (testing "$200 donation → 200 × 15% = 30.00"
    (is (≈ (cad "30.00")
           (s9/federal-donation-credit (cad "200.00") (cad "50000.00"))))))

(deftest donation-credit-1000-low-income
  (testing "$1,000 donation, low income → 200×15% + 800×29% = 30 + 232 = 262"
    (is (≈ (cad "262.00")
           (s9/federal-donation-credit (cad "1000.00") (cad "50000.00"))))))

(deftest donation-credit-with-top-bracket-income
  (testing "$1,000 donation, taxable income $300,000.
            Top-bracket excess = 300,000 - 246,752 = 53,248.
            Excess donation = 800; all 800 falls in 33% (since 53k > 800).
              200 × 15%  =  30.00
              800 × 33%  = 264.00
            Total                = 294.00"
    (is (≈ (cad "294.00")
           (s9/federal-donation-credit (cad "1000.00") (cad "300000.00"))))))

;; ============================================================================
;; T1 end-to-end — $30k single-T4 case
;; ============================================================================

(deftest t1-low-income-employee-only
  (testing "Single T4: $30k income, max CPP $1576.75, EI $498, tax paid $3,000.

            Expected line-by-line:
              10100 = 30,000.00
              15000 = 30,000.00
              22215 =    265.00 (CPP enhancement deduction)
              23300 =    265.00 (no RRSP, no union)
              23600 = 29,735.00 (net income)
              26000 = 29,735.00 (taxable income)
              40400 =  4,460.25 (15% of 29,735)
              30000 = 15,705.00 (full BPA, well below 173k)
              30800 =  1,311.75 (CPP base credit)
              31200 =    498.00 (EI)
              31260 =  1,433.00 (Canada Employment Amount)
              33500 = 18,947.75
              33800 =  2,842.16 (.1625 → HALF_EVEN → .16)
              34900 =      0.00
              35000 =  2,842.16
              42000 =  1,618.09 (4,460.25 - 2,842.16)
            Balance: 1,618.09 - 3,000.00 = -1,381.91 → refund"
    (let [result (t1/compute
                  {:filer/province :BC
                   :filer/tax-year 2024
                   :t4s [{:t4/box-14 (cad "30000.00")
                          :t4/box-16 (cad "1576.75")
                          :t4/box-18 (cad "498.00")
                          :t4/box-22 (cad "3000.00")}]})
          L (:t1/lines result)]
      (is (≈ (cad "30000.00") (:10100 L)))
      (is (≈ (cad "30000.00") (:15000 L)))
      (is (≈ (cad "265.00")   (:22215 L)))
      (is (≈ (cad "265.00")   (:23300 L)))
      (is (≈ (cad "29735.00") (:23600 L)))
      (is (≈ (cad "29735.00") (:26000 L)))
      (is (≈ (cad "4460.25")  (:40400 L)))
      (is (≈ (cad "15705.00") (:30000 L)))
      (is (≈ (cad "1311.75")  (:30800 L)))
      (is (≈ (cad "498.00")   (:31200 L)))
      (is (≈ (cad "1433.00")  (:31260 L)))
      (is (≈ (cad "18947.75") (:33500 L)))
      (is (≈ (cad "2842.16")  (:33800 L)))
      (is (≈ (cad "2842.16")  (:35000 L)))
      (is (≈ (cad "1618.09")  (:42000 L)))
      (testing "BC tax: taxable 29,735 × 5.06% = 1,504.59;
                NRTC sub (12,580 + 1,311.75 + 498 = 14,389.75) × 5.06% = 728.12;
                BC tax = 1,504.59 - 728.12 = 776.47"
        (is (≈ (cad "776.47") (:t1/bc-tax result))))
      (testing "Total tax = 1,618.09 + 776.47 = 2,394.56;
                balance = 2,394.56 - 3,000 = -605.44 (refund)"
        (is (≈ (cad "2394.56") (:t1/total-tax result)))
        (is (≈ (cad "-605.44") (:t1/balance result))))
      (is (= :refund (:t1/outcome result))))))

(deftest t1-100k-with-rrsp-and-donations
  (testing "Single T4: $100k income, max CPP $3,867.50, max EI $1,049.12, tax $20,000.
            RRSP deduction $5,000.
            Donations $1,000.

            Expected key lines:
              10100  = 100,000.00
              15000  = 100,000.00
              20800  =   5,000.00 (RRSP)
              22215  =     650.00 (CPP enh)
              23300  =   5,650.00
              23600  =  94,350.00
              26000  =  94,350.00
              40400  =  16,269.06 (15%×55,867 + 20.5%×38,483)
              30000  =  15,705.00 (full BPA)
              30800  =   3,217.50 (CPP base)
              31200  =   1,049.12 (max EI)
              31260  =   1,433.00
              33500  =  21,404.62
              33800  =   3,210.69
              34900  =     262.00 (200×15% + 800×29%)
              35000  =   3,472.69
              42000  =  12,796.37 (16,269.06 - 3,472.69)
            Balance:   -7,203.63 → refund"
    (let [result (t1/compute
                  {:filer/province :BC
                   :filer/tax-year 2024
                   :t4s [{:t4/box-14 (cad "100000.00")
                          :t4/box-16 (cad "3867.50")
                          :t4/box-18 (cad "1049.12")
                          :t4/box-22 (cad "20000.00")}]
                   :rrsp-deduction (cad "5000.00")
                   :donations      (cad "1000.00")})
          L (:t1/lines result)]
      (is (≈ (cad "100000.00") (:10100 L)))
      (is (≈ (cad "5000.00")   (:20800 L)))
      (is (≈ (cad "650.00")    (:22215 L)))
      (is (≈ (cad "5650.00")   (:23300 L)))
      (is (≈ (cad "94350.00")  (:23600 L)))
      (is (≈ (cad "16269.06")  (:40400 L)))
      (is (≈ (cad "15705.00")  (:30000 L)))
      (is (≈ (cad "3217.50")   (:30800 L)))
      (is (≈ (cad "1049.12")   (:31200 L)))
      (is (≈ (cad "1433.00")   (:31260 L)))
      (is (≈ (cad "21404.62")  (:33500 L)))
      (is (≈ (cad "3210.69")   (:33800 L)))
      (is (≈ (cad "262.00")    (:34900 L)))
      (is (≈ (cad "3472.69")   (:35000 L)))
      (is (≈ (cad "12796.37")  (:42000 L)))
      (testing "BC tax on 94,350 taxable:
                bracket 1: 47,937 × 5.06% = 2,425.61
                bracket 2: 46,413 × 7.7%  = 3,573.80
                BC tax before NRTCs = 5,999.41
                BC NRTC sub = 12,580 + 3,217.50 + 1,049.12 = 16,846.62 × 5.06% = 852.44
                BC donation credit: 200×5.06% + 800×16.8% = 10.12 + 134.40 = 144.52
                BC NRTCs total = 996.96
                BC tax = 5,999.41 - 996.96 = 5,002.45"
        (is (≈ (cad "5002.45") (:t1/bc-tax result))))
      (testing "Total tax = 12,796.37 + 5,002.45 = 17,798.82;
                balance = 17,798.82 - 20,000 = -2,201.18 (refund)"
        (is (≈ (cad "17798.82") (:t1/total-tax result)))
        (is (≈ (cad "-2201.18") (:t1/balance result))))
      (is (= :refund (:t1/outcome result))))))

(deftest t1-outcome-payment
  (testing "Tax withheld too low → balance owing"
    (let [result (t1/compute
                  {:filer/province :BC
                   :filer/tax-year 2024
                   :t4s [{:t4/box-14 (cad "30000.00")
                          :t4/box-16 (cad "1576.75")
                          :t4/box-18 (cad "498.00")
                          :t4/box-22 (cad "100.00")}]})]
      (is (= :payment (:t1/outcome result)))
      (is (money/positive? (:t1/balance result))))))

(deftest t1-outcome-nil-return
  (testing "No income at all → all zeros, nil return"
    (let [result (t1/compute
                  {:filer/province :BC
                   :filer/tax-year 2024
                   :t4s []})]
      (is (= :nil-return (:t1/outcome result)))
      (is (≈ (cad "0.00") (:t1/federal-tax result))))))

;; ============================================================================
;; T1 + T2125 integration (BC, T4 + self-employment)
;; ============================================================================

(deftest t1-with-t2125-self-employment
  (testing "BC filer: $50k T4 employment + $10k SE income (T2125).

            T2125: gross 15,000 - expenses 5,000 = 10,000 net.
            S8: SE pensionable base = 10,000 (room remaining 18,500).
              line 31000 = 10,000 × 4.95% =   495.00 (NRTC base)
              line 22200 = 495 + 200      =   695.00 (deduction)
            Employment CPP $2,766.75:
              line 30800 = 2,301.75
              line 22215 =   465.00

            T1 income:
              10100 = 50,000.00
              13500 = 10,000.00
              15000 = 60,000.00
            T1 deductions:
              20800 =     0.00 (no RRSP)
              22200 =   695.00 (SE CPP)
              22215 =   465.00 (emp CPP enhanced)
              23300 = 1,160.00
            Net income (23600) = 60,000 - 1,160 = 58,840.00
            Taxable income (26000) = 58,840.00
            Federal tax (40400):
              15% × 55,867 = 8,380.05
              20.5% × (58,840 - 55,867) = 20.5% × 2,973 = 609.465 → 609.47
              Total: 8,989.52
            NRTCs:
              30000 = 15,705.00 (full BPA)
              30800 = 2,301.75
              31000 =   495.00
              31200 =   498.00 (EI)
              31260 = 1,433.00
              33500 = 20,432.75
              33800 = 20,432.75 × 15% = 3,064.9125 → HALF_EVEN .9125→.91
                                                     (since .9125<.9150, drop)
            Wait — let me re-check rounding:
              3064.9125: third decimal is 1, then 2 then 5.
              Cents = 91, sub-cents = .25 of a cent. .25 < .50, round DOWN.
              → 3,064.91
              35000 = 3,064.91 + 0 = 3,064.91
            l-42000 = 8,989.52 - 3,064.91 = 5,924.61"
    (let [result (t1/compute
                  {:filer/province :BC
                   :filer/tax-year 2024
                   :t4s [{:t4/box-14 (cad "50000.00")
                          :t4/box-16 (cad "2766.75")
                          :t4/box-18 (cad "498.00")
                          :t4/box-22 (cad "8000.00")}]
                   :t2125 {:t2125/gross-income (cad "15000.00")
                           :t2125/expenses     [(cad "5000.00")]}})
          L (:t1/lines result)]
      (is (≈ (cad "50000.00") (:10100 L)))
      (is (≈ (cad "10000.00") (:13500 L)))
      (is (≈ (cad "60000.00") (:15000 L)))
      (is (≈ (cad "695.00")   (:22200 L)))
      (is (≈ (cad "465.00")   (:22215 L)))
      (is (≈ (cad "1160.00")  (:23300 L)))
      (is (≈ (cad "58840.00") (:23600 L)))
      (is (≈ (cad "8989.52")  (:40400 L)))
      (is (≈ (cad "2301.75")  (:30800 L)))
      (is (≈ (cad "495.00")   (:31000 L)))
      (is (≈ (cad "20432.75") (:33500 L)))
      (is (≈ (cad "3064.91")  (:33800 L)))
      (is (≈ (cad "5924.61")  (:42000 L))))))

(deftest t1-self-employed-only
  (testing "SE-only filer: $30k SE income, no T4.
            T2125 net = 30,000.
            S8: SE pensionable base = 30,000 - 3,500 (exemption) = 26,500.
              line 31000 = 26,500 × 4.95% = 1,311.75
              line 22200 = 1,311.75 + 530 = 1,841.75
            T1:
              13500 = 30,000.00 ; 15000 = 30,000.00
              23300 = 1,841.75; 23600 = 28,158.25
              40400 = 28,158.25 × 15% = 4,223.7375 → 4,223.74
              30000 = 15,705 ; 31000 = 1,311.75 ; 31260 = 0 (no emp income)
              33500 = 17,016.75
              33800 = 17,016.75 × 15% = 2,552.5125 → HALF_EVEN .5125 → .51
              35000 = 2,552.51
              42000 = 4,223.74 - 2,552.51 = 1,671.23"
    (let [result (t1/compute
                  {:filer/province :BC
                   :filer/tax-year 2024
                   :t4s []
                   :t2125 {:t2125/gross-income (cad "30000.00")
                           :t2125/expenses     []}})
          L (:t1/lines result)]
      (is (≈ (cad "30000.00")  (:13500 L)))
      (is (≈ (cad "30000.00")  (:15000 L)))
      (is (≈ (cad "1841.75")   (:22200 L)))
      (is (≈ (cad "28158.25")  (:23600 L)))
      (is (≈ (cad "4223.74")   (:40400 L)))
      (is (≈ (cad "1311.75")   (:31000 L)))
      (is (≈ (cad "0.00")      (:31260 L)))
      (is (≈ (cad "17016.75")  (:33500 L)))
      (is (≈ (cad "2552.51")   (:33800 L)))
      (is (≈ (cad "1671.23")   (:42000 L))))))
