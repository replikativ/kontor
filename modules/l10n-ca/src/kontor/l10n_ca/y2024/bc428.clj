(ns kontor.l10n-ca.y2024.bc428
  "BC428 — British Columbia tax (TY2024).

   Computes provincial tax for a BC resident, mirroring the federal
   structure: progressive brackets applied to the same taxable income
   (T1 line 26000), then BC non-refundable credits at the lowest BC
   rate (5.06%), with a separately-tiered BC donation credit.

   BC NRTCs that we model (sufficient for the BC + T4 + SE profile):
     5804  BC Basic Personal Amount  ($12,580 in 2024)
     5824  CPP/QPP base contributions through employment (= fed 30800)
     5828  CPP/QPP base contributions on SE              (= fed 31000)
     5832  EI premiums                                    (= fed 31200)
     5856  Tuition amount                                 (federal 32300)
     5896  Donations and gifts (tiered 5.06% / 16.8%)

   Out of scope for slice 3:
     - Age amount, spousal, eligible dependant, caregiver
     - Volunteer firefighter / search and rescue
     - BC mining flow-through, BC training credit
     - Disability amount
     - Pension income amount
     - Interest on student loans
     - Medical expenses

   Source: BC428 2024 form + BC Ministry of Finance bracket announcement.

   Per ADR-015 this namespace is immutable once tax year 2024 is filed."
  (:require [kontor.l10n-ca.y2024.constants :as k]
            [kontor.l10n-ca.y2024.s9 :as s9]
            [kontor.l10n-ca.y2024.t1 :as t1]
            [kontor.money :as money]))

(defn- bd ^java.math.BigDecimal [m] (:amount m))
(defn- m-zero [] (money/zero :CAD))
(defn- m-sum [monies] (reduce money/add (m-zero) monies))
(defn- m-cents [m]
  (money/money
   (.setScale (bd m) 2 java.math.RoundingMode/HALF_EVEN)
   :CAD))

(defn compute
  "Compute the BC428 provincial tax for a BC filer.

   Input:
     {:taxable-income      Money :CAD     ; T1 line 26000
      :cpp-base-employed   Money :CAD     ; T1 line 30800 (mirror)
      :cpp-base-se         Money :CAD     ; T1 line 31000 (mirror)
      :ei-premiums         Money :CAD     ; T1 line 31200 (mirror)
      :tuition             Money :CAD     ; eligible tuition
      :donations           Money :CAD}    ; eligible donations

   Returns:
     {:bc428/lines        map of line# → Money
      :bc428/bc-tax       Money :CAD     ; final BC tax payable}"
  [{:keys [taxable-income cpp-base-employed cpp-base-se
           ei-premiums tuition donations]
    :or   {taxable-income   (m-zero)
           cpp-base-employed (m-zero)
           cpp-base-se      (m-zero)
           ei-premiums      (m-zero)
           tuition          (m-zero)
           donations        (m-zero)}}]
  (let [bc-tax-before (t1/apply-brackets taxable-income k/bc-brackets)
        l-5804 (money/money k/bc-bpa :CAD)
        l-5824 cpp-base-employed
        l-5828 cpp-base-se
        l-5832 ei-premiums
        l-5856 tuition
        ;; Sub-total of NRTC bases (gets multiplied by 5.06%)
        nrtc-subtotal (m-sum [l-5804 l-5824 l-5828 l-5832 l-5856])
        nrtc-at-rate (m-cents (money/mul-scalar nrtc-subtotal k/bc-nrtc-rate))
        ;; Donation credit (tiered: 5.06% on first $200, 16.8% on excess)
        bc-donation (m-cents (s9/bc-donation-credit donations))
        bc-nrtcs (money/add nrtc-at-rate bc-donation)
        bc-tax-raw (money/sub bc-tax-before bc-nrtcs)
        bc-tax (if (money/negative? bc-tax-raw) (m-zero) bc-tax-raw)]
    {:bc428/lines    {:5804  (m-cents l-5804)
                      :5824  (m-cents l-5824)
                      :5828  (m-cents l-5828)
                      :5832  (m-cents l-5832)
                      :5856  (m-cents l-5856)
                      :5896  (m-cents donations)
                      :bc-tax-before-credits bc-tax-before
                      :bc-nrtc-subtotal      (m-cents nrtc-subtotal)
                      :bc-nrtc-at-rate       nrtc-at-rate
                      :bc-donation-credit    bc-donation
                      :bc-total-nrtcs        (m-cents bc-nrtcs)}
     :bc428/bc-tax   (m-cents bc-tax)}))
