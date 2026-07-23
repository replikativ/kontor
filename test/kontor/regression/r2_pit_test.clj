(ns kontor.regression.r2-pit-test
  "R2 regression — personal-income-tax realistic scenarios, exercised as
   an independent consumer of the shipped l10n PIT providers (US / AT /
   AU / CN). Every expected figure below is HAND-DERIVED from the
   published statutory bracket ladder cited inline — not captured from
   the provider — so a green test is an independent confirmation and a
   red/pending test is a genuine substrate defect.

   Providers exercised:
     - kontor.l10n-us.pit-provider  (IRC §1 / §63 / §24 — Form 1040)
     - kontor.l10n-at.pit-provider  (EStG §33 — Einkommensteuer)
     - kontor.l10n-au.pit-provider  (ITAA — resident individual)
     - kontor.l10n-cn.pit-provider  (IIT Law — comprehensive income)

   Each provider is driven through `ptp/period-tax-facts` exactly the
   way its own test-suite drives it (fresh in-memory DB + statute
   install)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.tax.period-tax-provider :as ptp]
            [kontor.l10n-us.pit-provider :as us-pit]
            [kontor.l10n-us.pit-statute :as us-statute]
            [kontor.l10n-at.pit-provider :as at-pit]
            [kontor.l10n-at.pit-statute :as at-statute]
            [kontor.l10n-au.pit-provider :as au-pit]
            [kontor.l10n-au.pit-statute :as au-statute]
            [kontor.l10n-cn.pit-provider :as cn-pit]
            [kontor.l10n-cn.pit-statute :as cn-statute]))

;; ---------------------------------------------------------------------------
;; helpers — one per jurisdiction (mirrors each provider's own suite)
;; ---------------------------------------------------------------------------

(defn- run-provider
  [install! provider period tax-unit inputs as-of]
  (let [conn (core/create-test-db)]
    (install! conn)
    (ptp/period-tax-facts
     provider
     {:entity   :individual
      :period   period
      :db       (d/db conn)
      :as-of    as-of
      :tax-unit tax-unit
      :inputs   inputs})))

(def ^:private us-period {:from #inst "2025-01-01" :to #inst "2026-01-01"})
(def ^:private cn-period us-period)
(def ^:private at-period us-period)
(def ^:private au-period {:from #inst "2024-07-01" :to #inst "2025-07-01"})

(defn- us [tax-unit inputs]
  (run-provider us-statute/install! (us-pit/us-pit-provider {})
                us-period tax-unit inputs #inst "2025-12-31"))
(defn- at [tax-unit inputs]
  (run-provider at-statute/install! (at-pit/at-pit-provider {})
                at-period tax-unit inputs #inst "2025-12-31"))
(defn- au [tax-unit inputs]
  (run-provider au-statute/install! (au-pit/au-pit-provider {})
                au-period tax-unit inputs #inst "2025-06-30"))
(defn- cn [tax-unit inputs]
  (run-provider cn-statute/install! (cn-pit/cn-pit-provider {})
                cn-period tax-unit inputs #inst "2025-12-31"))

(defn- component [facts] (-> facts :components first))
(defn- gross [facts]     (-> facts component :gross-liability :amount))
(defn- liability [facts] (-> facts component :liability :amount))
(defn- base [facts]      (-> facts component :base :amount))

;; ===========================================================================
;; US — IRC §1 / §63 / §24  (TY 2025, Rev. Proc. 2024-40)
;; ===========================================================================
;; 2025 Single bracket ladder (Rev. Proc. 2024-40 §3.01 Table 3):
;;   10% $0–$11,925 · 12% –$48,475 · 22% –$103,350 · 24% –$197,300
;;   32% –$250,525 · 35% –$626,350 · 37% over
;; 2025 std deduction Single = $15,000 (§3.16).

(deftest us-single-60k-standard-deduction
  (testing "Single, AGI $60,000, std deduction $15,000 → taxable $45,000"
    (let [f (us {:filing-status :single} {:gross-income 60000M})]
      ;; taxable 45,000:
      ;;   11,925 × 10%              = 1,192.50
      ;;   (45,000-11,925) × 12%     = 33,075 × 0.12 = 3,969.00
      ;;   total = 5,161.50
      (is (== 45000M (base f)))
      (is (== 5161.50M (gross f)))
      (is (== 5161.50M (liability f))))))

;; 2025 MFJ ladder (Table 1): 10% –$23,850 · 12% –$96,950 · 22% –$206,700
;;   24% –$394,600 · 32% –$501,050 · 35% –$751,600 · 37% over.
;; 2025 std deduction MFJ = $30,000.  §24 CTC = $2,000/child (non-ref).

(deftest us-mfj-120k-two-children-full-ctc
  (testing "MFJ, AGI $120,000, 2 kids, std deduction — full CTC (below phase-out)"
    (let [f (us {:filing-status :mfj :qualifying-children-under-17 2}
                {:gross-income 120000M :earned-income 120000M})]
      ;; taxable = 120,000 - 30,000 = 90,000
      ;;   23,850 × 10%           = 2,385.00
      ;;   (90,000-23,850) × 12%  = 66,150 × 0.12 = 7,938.00
      ;;   gross                  = 10,323.00
      ;; CTC 2×$2,000 = $4,000, MAGI $120k < $400k → no phase-out
      ;;   liability = 10,323.00 - 4,000 = 6,323.00
      (is (== 90000M (base f)))
      (is (== 10323.00M (gross f)))
      (is (== 6323.00M (liability f))))))

;; §24(b): CTC reduced by $50 for each $1,000 (or fraction) of MAGI above
;; $400,000 (MFJ). MAGI $500,000 → excess $100,000 → 100 × $50 = $5,000
;; reduction ≥ $4,000 potential → CTC fully phased out to $0.
;; Authority: IRC §24(b), law.cornell.edu/uscode/text/26/24.
(deftest us-mfj-500k-two-children-ctc-phaseout
  ;; FIXED (note 197): the US PIT provider now applies the §24(b) MAGI phase-out
  ;; — the CTC potential is cut $50 per $1,000 (or fraction) of MAGI over
  ;; $400,000 MFJ / $200,000 other (§24(h)(3)), floored at $0, before both the
  ;; non-refundable cap and the refundable ACTC residual. A MFJ filer at $500k
  ;; MAGI (excess $100k → $5,000 reduction ≥ $4,000 potential) gets CTC $0, so
  ;; liability = gross. Authority: 26 USC §24(b)(1) / §24(h)(3) (Cornell LII).
  (testing "MFJ, AGI $500,000, 2 kids — §24(b) fully phases out the CTC"
    (let [f (us {:filing-status :mfj :qualifying-children-under-17 2}
                {:gross-income 500000M :earned-income 500000M})]
      ;; taxable = 500,000 - 30,000 = 470,000
      ;;   23,850 × 10%                = 2,385.00
      ;;   (96,950-23,850)  × 12%      = 8,772.00
      ;;   (206,700-96,950) × 22%      = 24,145.00
      ;;   (394,600-206,700)× 24%      = 45,096.00
      ;;   (470,000-394,600)× 32%      = 24,128.00
      ;;   gross                       = 104,526.00
      (is (== 470000M (base f)))
      (is (== 104526.00M (gross f)))
      ;; CTC phased out to $0 → liability == gross
      (is (== 104526.00M (liability f))
          "§24(b) phase-out: CTC should be $0 at $500k MAGI, liability = gross"))))

;; ===========================================================================
;; AT — EStG §33  (Einkommensteuer, Tarif 2025)
;; ===========================================================================
;; 2025 Tarifstufen (§33 Abs 1 EStG, Progressionsabgeltungsgesetz 2025):
;;   0%  up to €13,308 · 20% –€21,617 · 30% –€35,836 · 40% –€69,166
;;   48% –€103,072 · 50% –€1,000,000 · 55% over
;; Verkehrsabsetzbetrag (§33 Abs 5) 2025 = €487 (active employee, flat —
;; no high-income Einschleifung on the base amount).

(deftest at-single-employee-60k-2025
  (testing "Single active employee, €60,000 taxable, 2025 Tarif"
    (let [f (at {:employment-relationship? true} {:gross-income 60000M})]
      ;; bracket fold on 60,000:
      ;;   (21,617-13,308) × 20% = 8,309  × 0.20 = 1,661.80
      ;;   (35,836-21,617) × 30% = 14,219 × 0.30 = 4,265.70
      ;;   (60,000-35,836) × 40% = 24,164 × 0.40 = 9,665.60
      ;;   gross = 15,593.10
      ;; less Verkehrsabsetzbetrag €487 → 15,106.10
      (is (== 60000M (base f)))
      (is (== 15593.10M (gross f)))
      (is (== 15106.10M (liability f))))))

;; ===========================================================================
;; AU — ITAA resident individual  (FY 2024-25, post-Stage-3)
;; ===========================================================================
;; FY 2024-25 resident ladder (ATO individual-income-tax-rates):
;;   0 –$18,200 · 16% –$45,000 · 30% –$135,000 · 37% –$190,000 · 45% over
;; Medicare Levy 2% flat above the shade-in zone. LITO nil above $66,667.

(deftest au-resident-135k-top-of-30pct-band
  (testing "Resident, TI $135,000 (top of the 30% band), FY 2024-25"
    (let [f (au {} {:gross-income 135000M})]
      ;;   (45,000-18,200)  × 16% = 26,800 × 0.16 = 4,288
      ;;   (135,000-45,000) × 30% = 90,000 × 0.30 = 27,000
      ;;   bracket = 31,288
      ;; Medicare 2% × 135,000 = 2,700 ; LITO 0 (TI > $66,667)
      ;;   liability = 33,988
      (is (== 135000M (base f)))
      (is (== 31288M (gross f)))
      (is (== 33988M (liability f))))))

(deftest au-resident-190k-top-of-37pct-band
  (testing "Resident, TI $190,000 (top of the 37% band), FY 2024-25"
    (let [f (au {} {:gross-income 190000M})]
      ;;   (45,000-18,200)   × 16% = 4,288
      ;;   (135,000-45,000)  × 30% = 27,000
      ;;   (190,000-135,000) × 37% = 55,000 × 0.37 = 20,350
      ;;   bracket = 51,638
      ;; Medicare 2% × 190,000 = 3,800 ; LITO 0
      ;;   liability = 55,438
      (is (== 51638M (gross f)))
      (is (== 55438M (liability f))))))

;; ===========================================================================
;; CN — IIT Law comprehensive income  (annual, 2025)
;; ===========================================================================
;; Annual comprehensive-income ladder (IIT Law §3/§6):
;;   3% –¥36,000 · 10% –¥144,000 · 20% –¥300,000 · 25% –¥420,000
;;   30% –¥660,000 · 35% –¥960,000 · 45% over
;; Basic deduction ¥60,000/yr applied automatically by the provider.

(deftest cn-comprehensive-300k-with-statutory
  (testing "Gross ¥300,000 − ¥60,000 basic − ¥30,000 statutory → taxable ¥210,000"
    (let [f (cn {} {:gross-comprehensive-income 300000M
                    :pit-base-deductions-statutory 30000M})]
      ;; base = 300,000 - 60,000 - 30,000 = 210,000
      ;;   36,000            × 3%  = 1,080
      ;;   (144,000-36,000)  × 10% = 108,000 × 0.10 = 10,800
      ;;   (210,000-144,000) × 20% = 66,000  × 0.20 = 13,200
      ;;   tax = 25,080   (quick-deduction check: 210,000×20% − 16,920 = 25,080)
      (is (== 210000M (base f)))
      (is (== 25080M (liability f))))))
