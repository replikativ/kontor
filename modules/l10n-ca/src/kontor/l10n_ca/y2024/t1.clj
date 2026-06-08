(ns kontor.l10n-ca.y2024.t1
  "T1 General — federal core for tax year 2024.

   Scope of this slice (per Phase 4-CA-depth, slice 1):
     - Total income (line 15000) from T4 employment slips.
     - Deductions: RRSP, union dues, enhanced CPP.
     - Net income (line 23600) and taxable income (line 26000).
     - Federal tax via progressive brackets (line 40400).
     - Non-refundable tax credits: BPA (with phase-out), CPP base credit,
       EI premium credit, Canada Employment Amount, tuition (S11),
       donations (S9 — tiered 15/29/33%).
     - Federal dividend tax credit (line 40425).
     - Net federal tax (line 42000).

   Skipped intermediate lines (we don't model these):
     - Line 40424 (TOSI / federal tax on split income — T1206)
     - Line 40500 (federal foreign tax credit)
     - Line 42900 (basic federal tax), 40600 (federal tax) — these are
       intermediate computational lines that collapse to 42000 in the
       absence of TOSI, FFTC, and special taxes.
     - Balance owing vs. refund vs. nil return.

   Out of scope for slice 1 (covered in later slices):
     - Self-employment (T2125) — slice 2.
     - Capital gains (S3) and investment income (S4) — slice 3.
     - Provincial tax (BC428 + BC479) — slice 3.
     - Spouse/dependant/disability/medical credits — post-MVP.
     - AMT (T691) — post-MVP, rarely triggers for this profile.
     - Federal Dividend Tax Credit — slice 3.
     - Foreign Tax Credit — post-MVP.

   T1 line numbers used here follow the 2024 form. Where the form has
   shifted line numbers across years (it has, several times since 2019),
   we use the modern numbering. The PDF renderer (slice 5) maps these
   to the actual AcroForm field names.

   Per ADR-015, this namespace is *immutable once tax year 2024 is
   filed*. Backports require preserving the assessed numbers."
  (:require [kontor.l10n-ca.y2024.constants :as k]
            [kontor.l10n-ca.y2024.s3 :as s3]
            [kontor.l10n-ca.y2024.s4 :as s4]
            [kontor.l10n-ca.y2024.s8 :as s8]
            [kontor.l10n-ca.y2024.s9 :as s9]
            [kontor.l10n-ca.y2024.s11 :as s11]
            [kontor.l10n-ca.y2024.t2125 :as t2125]
            [kontor.money :as money]))

(declare bc428-compute)
(defn- maybe-bc428 [opts]
  ;; Resolved at call time to avoid a circular module dependency.
  ((requiring-resolve 'kontor.l10n-ca.y2024.bc428/compute) opts))

;; ============================================================================
;; Small helpers
;; ============================================================================

(defn- bd ^java.math.BigDecimal [m]
  (:amount m))

(defn- m-zero [] (money/zero :CAD))

(defn- m-sum [monies]
  (reduce money/add (m-zero) monies))

(defn- m-min [a b]
  (if (not (pos? (.compareTo (bd a) (bd b)))) a b))

(defn- m-cents
  "Round a Money to cents (HALF_EVEN per ADR-013)."
  [m]
  (money/money
   (.setScale (bd m) 2 java.math.RoundingMode/HALF_EVEN)
   :CAD))

;; ============================================================================
;; Federal tax brackets
;; ============================================================================

(defn apply-brackets
  "Apply progressive tax brackets to taxable income (Money :CAD).

   Returns Money :CAD, rounded to cents (HALF_EVEN).

   Brackets are a vector of {:rate BigDecimal :upper BigDecimal-or-nil}
   ordered from lowest. The last entry's :upper is nil (open-ended top
   bracket)."
  [taxable brackets]
  (let [ti (bd taxable)
        zero java.math.BigDecimal/ZERO
        result
        (loop [remaining ti
               prev zero
               brks brackets
               tax zero]
          (cond
            (not (pos? (.signum remaining))) tax
            (empty? brks) tax
            :else
            (let [{:keys [rate upper]} (first brks)
                  bracket-size (if upper (.subtract upper prev) remaining)
                  in-this (if (pos? (.compareTo remaining bracket-size))
                            bracket-size
                            remaining)
                  this-tax (.multiply in-this rate)]
              (recur (.subtract remaining in-this)
                     (or upper prev)
                     (rest brks)
                     (.add tax this-tax)))))]
    (m-cents (money/money result :CAD))))

;; ============================================================================
;; Basic Personal Amount (federal, phased)
;; ============================================================================

(defn federal-bpa
  "Federal Basic Personal Amount given net income (Money :CAD).

     net-income ≤ 173,205         → max BPA  $15,705
     net-income > 246,752         → min BPA  $14,156
     in between                    → linear interpolation

   Returns Money :CAD."
  [net-income]
  (let [ni  (bd net-income)
        max-bpa k/federal-bpa-max
        min-bpa k/federal-bpa-min
        lo k/federal-bpa-phaseout-low
        hi k/federal-bpa-phaseout-high]
    (cond
      (not (pos? (.compareTo ni lo)))
      (money/money max-bpa :CAD)

      (pos? (.compareTo ni hi))
      (money/money min-bpa :CAD)

      :else
      (let [reduction-range (.subtract max-bpa min-bpa)
            income-range    (.subtract hi lo)
            income-over     (.subtract ni lo)
            reduction (.divide (.multiply reduction-range income-over)
                               income-range
                               2 java.math.RoundingMode/HALF_EVEN)]
        (money/money (.subtract max-bpa reduction) :CAD)))))

;; ============================================================================
;; CPP base/enhanced split (from T4 box 16)
;; ============================================================================

(defn cpp-employee-split
  "Given total employee CPP contributions for the year (Money :CAD, T4
   box 16 sum), return [base-portion enhanced-portion].

   base = total × (4.95 / 5.95)        → line 30800 credit
   enhanced = total - base             → line 22215 deduction

   Both returned as Money :CAD rounded to cents."
  [total]
  (let [t (bd total)
        rate-ratio (.divide k/cpp-rate-base k/cpp-rate-total
                            10 java.math.RoundingMode/HALF_EVEN)
        base-raw (.multiply t rate-ratio)
        base (.setScale base-raw 2 java.math.RoundingMode/HALF_EVEN)
        enhanced (.subtract t base)]
    [(money/money base :CAD)
     (money/money enhanced :CAD)]))

;; ============================================================================
;; T1 compute
;; ============================================================================

(defn compute
  "Compute a T1 General return for tax year 2024.

   Input shape (all amounts are Money :CAD; missing keys default to zero):

     {:filer/province     :BC
      :filer/tax-year     2024
      :t4s                [{:t4/box-14 …      ; employment income
                            :t4/box-16 …      ; CPP contributions
                            :t4/box-18 …      ; EI premiums
                            :t4/box-22 …      ; income tax deducted
                            :t4/box-44 …      ; union dues
                            :t4/box-46 …}]    ; charitable donations
      :t2125              {…}                ; optional self-employment input
      :s3                 {…}                ; optional capital-gains input
      :s4                 {…}                ; optional investment-income input
      :rrsp-deduction     …
      :union-dues-extra   …                  ; outside T4 box 44
      :donations          …                  ; eligible Schedule 9 donations
      :tuition            …}                 ; eligible Schedule 11 tuition

   Returns the input map enriched with:

     :t1/lines           — map of line# (keyword) → Money :CAD
     :t1/income-tax-paid — sum of T4 box 22 (Money :CAD)
     :t1/federal-tax     — line 42000 (Money :CAD)
     :t1/balance         — net federal tax minus income tax paid
                           (positive = owe; negative = refund)
     :t1/outcome         — :payment | :refund | :nil-return"
  [{:keys [filer/province t4s t2125 s3 s4
           rrsp-deduction union-dues-extra donations tuition]
    :or   {province         nil
           t4s              []
           t2125            nil
           s3               nil
           s4               nil
           rrsp-deduction   (money/zero :CAD)
           union-dues-extra (money/zero :CAD)
           donations        (money/zero :CAD)
           tuition          (money/zero :CAD)}
    :as t1}]
  (let [sum-box (fn [box] (m-sum (map #(get % box (money/zero :CAD)) t4s)))
        emp-income       (sum-box :t4/box-14)
        cpp-contributed  (sum-box :t4/box-16)
        ei-premiums      (sum-box :t4/box-18)
        income-tax-paid  (sum-box :t4/box-22)
        union-dues-t4    (sum-box :t4/box-44)

        ;; ---- T2125 (self-employment) ----
        t2125-result (when t2125 (t2125/compute t2125))
        se-income (or (:t2125/net-income t2125-result) (money/zero :CAD))

        ;; ---- S3 (capital gains) ----
        s3r (when s3 (s3/compute s3))
        l-12700 (or (:s3/taxable-capital-gains s3r) (money/zero :CAD))

        ;; ---- S4 (investment income) ----
        s4r (when s4 (s4/compute s4))
        l-12000 (or (:s4/line-12000 s4r) (money/zero :CAD))
        l-12100 (or (:s4/line-12100 s4r) (money/zero :CAD))
        federal-dtc (or (:s4/federal-dtc s4r) (money/zero :CAD))
        bc-dtc      (or (:s4/bc-dtc s4r) (money/zero :CAD))

        ;; ---- Schedule 8 (CPP allocations) ----
        s8r (s8/compute {:employment-income emp-income
                         :employment-cpp    cpp-contributed
                         :se-income         se-income})

        ;; ---- Income side ----
        l-10100 emp-income
        l-12000* l-12000
        l-12100* l-12100
        l-12700* l-12700
        l-13500 se-income                        ; business income
        l-15000 (m-sum [emp-income se-income
                        l-12000* l-12100* l-12700*])

        ;; ---- Deductions side ----
        l-20800 rrsp-deduction
        l-21200 (money/add union-dues-t4 union-dues-extra)
        l-22200 (:s8/line-22200 s8r)             ; SE CPP deduction
        l-22215 (:s8/line-22215 s8r)             ; employment CPP enhanced
        l-23300 (m-sum [l-20800 l-21200 l-22200 l-22215])
        l-23600 (money/sub l-15000 l-23300)

        ;; ---- Taxable income ----
        l-26000 l-23600                          ; slice 1-2: no further reductions

        ;; ---- Federal tax on taxable income — brackets (line 40400) ----
        l-40400 (apply-brackets l-26000 k/federal-brackets)

        ;; ---- NRTCs (Step 5 Part B) ----
        l-30000 (federal-bpa l-23600)
        l-30800 (:s8/line-30800 s8r)             ; employment CPP base credit
        l-31000 (:s8/line-31000 s8r)             ; SE CPP base credit
        l-31200 ei-premiums
        l-31260 (m-min emp-income (money/money k/employment-amount-max :CAD))
        l-32300 (s11/eligible-tuition-amount tuition)
        l-33500 (m-sum [l-30000 l-30800 l-31000 l-31200 l-31260 l-32300])
        l-33800 (m-cents (money/mul-scalar l-33500 k/federal-nrtc-rate))
        l-34900 (m-cents (s9/federal-donation-credit donations l-26000))
        l-35000 (money/add l-33800 l-34900)

        ;; ---- Federal Dividend Tax Credit (line 40425) ----
        l-40425 federal-dtc

        ;; ---- Net federal tax (line 42000) ----
        ;; In the absence of TOSI (40424), FFTC (40500), and special
        ;; taxes, intermediate lines 42900/40600 collapse to:
        ;;   42000 = max(0, 40400 - 35000 - 40425)
        l-42000-raw (-> l-40400
                        (money/sub l-35000)
                        (money/sub l-40425))
        l-42000 (if (money/negative? l-42000-raw) (money/zero :CAD) l-42000-raw)

        ;; ---- Provincial (BC428) ----
        bc-result (when (= :BC province)
                    (maybe-bc428 {:taxable-income    l-26000
                                  :cpp-base-employed l-30800
                                  :cpp-base-se       l-31000
                                  :ei-premiums       l-31200
                                  :tuition           l-32300
                                  :donations         donations}))
        bc-tax-pre-dtc (or (:bc428/bc-tax bc-result) (money/zero :CAD))
        bc-tax-raw (money/sub bc-tax-pre-dtc bc-dtc)
        bc-tax (if (money/negative? bc-tax-raw) (money/zero :CAD) bc-tax-raw)

        ;; ---- Total tax + balance ----
        total-tax (money/add l-42000 bc-tax)
        balance (money/sub total-tax income-tax-paid)
        outcome (cond
                  (money/negative? balance) :refund
                  (money/positive? balance) :payment
                  :else                     :nil-return)]
    (assoc t1
           :t1/t2125-result    t2125-result
           :t1/s3              s3r
           :t1/s4              s4r
           :t1/s8              s8r
           :t1/bc428           bc-result
           :t1/lines           {:10100 (m-cents l-10100)
                                :12000 (m-cents l-12000*)
                                :12100 (m-cents l-12100*)
                                :12700 (m-cents l-12700*)
                                :13500 (m-cents l-13500)
                                :15000 (m-cents l-15000)
                                :20800 (m-cents l-20800)
                                :21200 (m-cents l-21200)
                                :22200 (m-cents l-22200)
                                :22215 (m-cents l-22215)
                                :23300 (m-cents l-23300)
                                :23600 (m-cents l-23600)
                                :26000 (m-cents l-26000)
                                :30000 (m-cents l-30000)
                                :30800 (m-cents l-30800)
                                :31000 (m-cents l-31000)
                                :31200 (m-cents l-31200)
                                :31260 (m-cents l-31260)
                                :32300 (m-cents l-32300)
                                :33500 (m-cents l-33500)
                                :33800 l-33800
                                :34900 l-34900
                                :35000 (m-cents l-35000)
                                :40400 l-40400
                                :40425 (m-cents l-40425)
                                :42000 (m-cents l-42000)}
           :t1/income-tax-paid (m-cents income-tax-paid)
           :t1/federal-tax     (m-cents l-42000)
           :t1/bc-tax          (m-cents bc-tax)
           :t1/total-tax       (m-cents total-tax)
           :t1/balance         (m-cents balance)
           :t1/outcome         outcome)))
