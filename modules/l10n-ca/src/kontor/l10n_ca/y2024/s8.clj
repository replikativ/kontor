(ns kontor.l10n-ca.y2024.s8
  "Schedule 8 — CPP contributions on employment and self-employment.

   Handles the cross-form interaction: an employee filer who is also
   self-employed must reconcile CPP across both income sources, using
   the year's pensionable cap (YMPE + basic exemption) and the new
   (2024+) CPP2 second tier (YAMPE band).

   Allocations into T1 lines:

     line 30800  Employment CPP base (NRTC)      = T4 box 16 × (4.95 / 5.95)
                                                   (computed in T1 directly
                                                   from T4 box 16 sum — S8
                                                   confirms; we surface it
                                                   for completeness.)
     line 22215  Employment CPP enhanced (deduction)
                                                = T4 box 16 - line 30800

     line 31000  SE CPP base credit (NRTC, employee-half)
                                                = SE-pensionable × 4.95%
     line 22200  SE CPP deduction (other half + enhanced + CPP2)
                                                = SE-pensionable × 4.95%
                                                + SE-pensionable × 2.0%
                                                + SE-CPP2-pensionable × 8.0%

   Where SE-pensionable is capped so that:
     employment-pensionable + SE-pensionable ≤ YMPE - basic-exemption

   And SE-CPP2-pensionable is the portion of total income falling between
   YMPE and YAMPE that's attributable to the SE side (simplification:
   we allocate any over-YMPE income to SE first if employment is already
   capped, then to CPP2 band, capped at YAMPE).

   Scope cuts:
     - QPP filers (Quebec): out of scope — Quebec is deferred per
       ADR-015 entirely.
     - Multi-employer optimization (over-contribution refund) — the
       T1 form has Form RC381 for the multi-jurisdiction case; we
       defer.
     - January-of-following-year CPP adjustments — out of scope."
  (:require [kontor.l10n-ca.y2024.constants :as k]
            [kontor.money :as money]))

(defn- bd ^java.math.BigDecimal [m] (:amount m))
(defn- m-zero [] (money/zero :CAD))
(defn- cad [bd] (money/money bd :CAD))
(defn- bd-min [^java.math.BigDecimal a ^java.math.BigDecimal b] (.min a b))
(defn- bd-max [^java.math.BigDecimal a ^java.math.BigDecimal b] (.max a b))
(defn- cents ^java.math.BigDecimal [^java.math.BigDecimal x]
  (.setScale x 2 java.math.RoundingMode/HALF_EVEN))

(defn compute
  "Compute CPP allocations.

   Input:
     {:employment-income  Money :CAD     ; sum of T4 box 14 / box 26
      :employment-cpp     Money :CAD     ; sum of T4 box 16
      :se-income          Money :CAD}    ; net SE income from T2125

   Returns:
     {:s8/employment-pensionable Money     ; for reference
      :s8/se-pensionable-base    Money
      :s8/se-pensionable-cpp2    Money
      :s8/line-30800             Money     ; employment CPP base credit
      :s8/line-22215             Money     ; employment CPP enhanced deduction
      :s8/line-31000             Money     ; SE CPP base credit
      :s8/line-22200             Money}    ; SE CPP deduction"
  [{:keys [employment-income employment-cpp se-income]
    :or   {employment-income (m-zero)
           employment-cpp    (m-zero)
           se-income         (m-zero)}}]
  (let [ympe k/cpp-ympe
        yampe k/cpp-yampe
        exemption k/cpp-basic-exemption
        pensionable-cap (.subtract ympe exemption)   ; 65,000 in 2024
        cpp2-cap (.subtract yampe ympe)              ;  4,700 in 2024

        emp-income (bd employment-income)
        emp-cpp (bd employment-cpp)
        se (bd-max java.math.BigDecimal/ZERO (bd se-income))

        ;; Basic exemption is applied to employment first; whatever's
        ;; left over carries through to SE. This matches the S8 form:
        ;; the exemption appears exactly once per filer per year.
        emp-capped (bd-min ympe emp-income)
        exemption-used-by-emp (bd-min exemption emp-capped)
        exemption-remaining (.subtract exemption exemption-used-by-emp)

        ;; Employment pensionable: capped at YMPE - exemption (= 65,000)
        emp-pensionable (bd-min pensionable-cap
                                (bd-max java.math.BigDecimal/ZERO
                                        (.subtract emp-capped exemption-used-by-emp)))

        ;; SE base-pensionable: SE income net of remaining exemption,
        ;; further capped by remaining YMPE room.
        se-net-of-exempt (bd-max java.math.BigDecimal/ZERO
                                 (.subtract se exemption-remaining))
        se-room (bd-max java.math.BigDecimal/ZERO
                        (.subtract pensionable-cap emp-pensionable))
        se-pensionable-base (bd-min se-net-of-exempt se-room)

        ;; SE CPP2-pensionable: income above YMPE up to YAMPE
        ;; Total over-YMPE = total income - YMPE, capped at YAMPE band
        total-income (.add emp-income se)
        over-ympe (bd-max java.math.BigDecimal/ZERO
                          (.subtract total-income ympe))
        ;; Employment over YMPE (already covered by employer CPP2)
        emp-over-ympe (bd-max java.math.BigDecimal/ZERO
                              (.subtract emp-income ympe))
        ;; SE share of over-YMPE band (the part NOT already on employer side)
        se-over-ympe (bd-max java.math.BigDecimal/ZERO
                             (.subtract over-ympe emp-over-ympe))
        se-pensionable-cpp2 (bd-min cpp2-cap se-over-ympe)

        ;; Employment CPP split (mirror T1's calc; for reference)
        rate-ratio (.divide k/cpp-rate-base k/cpp-rate-total
                            10 java.math.RoundingMode/HALF_EVEN)
        l-30800 (cents (.multiply emp-cpp rate-ratio))
        l-22215 (cents (.subtract emp-cpp l-30800))

        ;; SE base portion (split half credit / half deduction)
        se-base-half-credit  (cents (.multiply se-pensionable-base
                                               k/cpp-rate-base))
        se-base-half-deduct  se-base-half-credit
        ;; SE enhanced portion (both halves to deduction)
        se-enh-both          (cents (.multiply se-pensionable-base
                                                (.add k/cpp-rate-enhanced
                                                      k/cpp-rate-enhanced)))
        ;; SE CPP2 (both halves to deduction)
        se-cpp2-both         (cents (.multiply se-pensionable-cpp2
                                                (.add k/cpp2-rate-employee
                                                      k/cpp2-rate-employee)))
        l-31000 se-base-half-credit
        l-22200 (cents (.add (.add se-base-half-deduct se-enh-both)
                             se-cpp2-both))]
    {:s8/employment-pensionable (cad emp-pensionable)
     :s8/se-pensionable-base    (cad se-pensionable-base)
     :s8/se-pensionable-cpp2    (cad se-pensionable-cpp2)
     :s8/line-30800             (cad l-30800)
     :s8/line-22215             (cad l-22215)
     :s8/line-31000             (cad l-31000)
     :s8/line-22200             (cad l-22200)}))
