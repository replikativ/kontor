(ns kontor.l10n-br.taxes-test
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-br.taxes :as t]
            [kontor.money :as money]))

(defn- brl [s] (money/money (bigdec s) :BRL))
(defn- ≈ [a b] (money/equiv? a b))

;; ============================================================================
;; State sets cover all 27 Brazilian states + DF
;; ============================================================================

(deftest all-states-cover-26-plus-df
  (testing "27 federal units (26 states + Distrito Federal)"
    (is (= 27 (count t/all-states)))
    (is (contains? t/all-states "DF"))
    (is (contains? t/all-states "SP"))))

(deftest macro-region-partition
  (testing "S/SE has 7 states + N/NE/MW has 20 (incl. DF) = 27"
    (is (= 7 (count t/south-southeast-states)))
    (is (= 20 (count t/north-northeast-midwest-states)))
    (is (= 0 (count (clojure.set/intersection
                     t/south-southeast-states
                     t/north-northeast-midwest-states))))))

;; ============================================================================
;; Interstate routing
;; ============================================================================

(deftest icms-interstate-rate
  (testing "S/SE → N/NE/MW: 7% domestic-origin"
    (is (= 0.07M (t/icms-interstate-rate "SP" "BA"))))
  (testing "N/NE/MW → S/SE: 12%"
    (is (= 0.12M (t/icms-interstate-rate "BA" "SP"))))
  (testing "Within S/SE: 12%"
    (is (= 0.12M (t/icms-interstate-rate "SP" "RJ"))))
  (testing "Intra-state uses the state's modal rate (2025 values)"
    (is (= 0.18M  (t/icms-interstate-rate "SP" "SP")))
    (is (= 0.22M  (t/icms-interstate-rate "RJ" "RJ"))
        "RJ updated: 20% base + 2% FECP = 22%")
    (is (= 0.225M (t/icms-interstate-rate "PI" "PI"))
        "PI updated: 22.5% effective 2025-04-01")
    (is (= 0.23M  (t/icms-interstate-rate "MA" "MA"))
        "MA updated: 23% effective 2025-02-23")))

(deftest icms-interstate-imported-goods
  (testing "Res. SF 13/2012: imported goods MUST use 4% interstate
            regardless of macro-region pairing"
    (is (= 0.04M (t/icms-interstate-rate "SP" "BA" {:import-content? true})))
    (is (= 0.04M (t/icms-interstate-rate "BA" "SP" {:import-content? true})))
    (is (= 0.04M (t/icms-interstate-rate "SP" "RJ" {:import-content? true}))))
  (testing "Domestic origin still uses normal rates"
    (is (= 0.07M (t/icms-interstate-rate "SP" "BA" {:import-content? false}))))
  (testing ":ncm-import-content-pct > 40 triggers 4%"
    (is (= 0.04M (t/icms-interstate-rate "SP" "BA" {:ncm-import-content-pct 50})))
    (is (= 0.07M (t/icms-interstate-rate "SP" "BA" {:ncm-import-content-pct 30}))
        "<=40% import content uses normal domestic rate")))

;; ============================================================================
;; Compound base helpers
;; ============================================================================

(deftest icms-base-includes-ipi
  (testing "ICMS base = net + IPI (cálculo por dentro)"
    (let [net (brl "1000.00")
          ipi (brl "100.00")
          base (t/compute-icms-by-inside-base net ipi)]
      (is (≈ (brl "1100.00") base)))))

(deftest pis-cofins-base-excludes-icms
  (testing "Tese do Século (STF Tema 69, RE 574.706): ICMS destacado
            is EXCLUDED from the PIS/COFINS base since 15-03-2017.
            Base = net + IPI − ICMS-destacado."
    (let [net (brl "1000.00")
          ipi (brl "100.00")
          icms (brl "180.00")
          base (t/compute-pis-cofins-base net ipi icms)]
      (is (≈ (brl "920.00") base)
          "1000 + 100 - 180 = 920 (ICMS excluded)"))))

(deftest pis-cofins-base-legacy-2-arg-warns
  (testing "Legacy 2-arg form preserved for pre-2017 historical use;
            warns to encourage migration"
    (let [net (brl "1000.00")
          ipi (brl "100.00")
          base (binding [*err* (java.io.StringWriter.)]
                 (t/compute-pis-cofins-base net ipi))]
      (is (≈ (brl "1100.00") base)
          "2-arg form is net + IPI; pre-Tema-69 base"))))

;; ============================================================================
;; Rate sanity (verified numeric values)
;; ============================================================================

;; ============================================================================
;; DIFAL (Diferencial de Alíquota) — EC 87/2015 + LC 190/2022
;; ============================================================================

(deftest difal-b2c-interstate
  (testing "B2C sale SP→BA, base 1000.
            rate_dest (BA) = 20.5%, rate_orig (interstate SP→BA) = 7%.
            DIFAL = 1000 × (0.205 - 0.07) = 1000 × 0.135 = 135.00"
    (let [r (t/difal-due (brl "1000.00") "SP" "BA" {:b2c? true})]
      (is (≈ (brl "135.00") (:difal r)))
      (is (≈ (brl "0.00")   (:fcp r)))
      (is (≈ (brl "135.00") (:total r))))))

(deftest difal-with-fcp
  (testing "B2C sale SP→BA with FCP 2%: DIFAL 135 + FCP 20 = 155"
    (let [r (t/difal-due (brl "1000.00") "SP" "BA"
                          {:b2c? true :fcp-rate 0.02M})]
      (is (≈ (brl "135.00") (:difal r)))
      (is (≈ (brl "20.00")  (:fcp r)))
      (is (≈ (brl "155.00") (:total r))))))

(deftest difal-imported-uses-4pct
  (testing "B2C SP→BA with imported goods: rate_orig = 4% (not 7%).
            DIFAL = 1000 × (0.205 - 0.04) = 165.00"
    (let [r (t/difal-due (brl "1000.00") "SP" "BA"
                          {:b2c? true :imported? true})]
      (is (≈ (brl "165.00") (:difal r))))))

(deftest difal-b2b-returns-nil
  (testing "B2B sale (non-B2C) → DIFAL doesn't apply"
    (is (nil? (t/difal-due (brl "1000.00") "SP" "BA" {:b2c? false})))))

;; ============================================================================
;; ICMS-ST (Substituição Tributária)
;; ============================================================================

(deftest icms-st-basic
  (testing "Base 1000, IPI 100, MVA 40%, rate dest 18%, ICMS normal 70.
            Base_ST = (1000 + 100) × 1.40 = 1540
            ICMS_ST_full = 1540 × 0.18 = 277.20
            ICMS_ST_due = 277.20 − 70 = 207.20"
    (let [r (t/icms-st {:base (brl "1000.00")
                        :ipi-amount (brl "100.00")
                        :mva-pct 0.40M
                        :rate-dest 0.18M
                        :icms-normal (brl "70.00")})]
      (is (≈ (brl "1540.00") (:icms-st-base r)))
      (is (≈ (brl "207.20")  (:icms-st-due r))))))

(deftest icms-st-with-freight
  (testing "Additional (freight+insurance) included in base.
            Base 1000, IPI 100, additional 50, MVA 40%.
            Base_ST = (1000 + 100 + 50) × 1.40 = 1610"
    (let [r (t/icms-st {:base (brl "1000.00")
                        :ipi-amount (brl "100.00")
                        :additional (brl "50.00")
                        :mva-pct 0.40M
                        :rate-dest 0.18M
                        :icms-normal (brl "70.00")})]
      (is (≈ (brl "1610.00") (:icms-st-base r))))))

;; ============================================================================
;; MvaProvider
;; ============================================================================

(deftest static-mva-provider
  (testing "StaticMvaProvider looks up MVA% by (NCM, state)"
    (let [p (t/static-mva-provider
             {"22030000" {"SP" 0.40M  ; beer in SP: 40%
                          "RJ" 0.50M} ; beer in RJ: 50%
              "84713012" {"SP" 0.30M}})]
      (is (= 0.40M (t/mva-for p "22030000" "SP")))
      (is (= 0.50M (t/mva-for p "22030000" "RJ")))
      (is (= 0.30M (t/mva-for p "84713012" "SP")))
      (is (nil? (t/mva-for p "99999999" "SP"))
          "Unknown NCM → nil → no ST applies"))))

;; ============================================================================
;; FCP
;; ============================================================================

(deftest fcp-amount
  (testing "FCP = base × FCP rate"
    (is (≈ (brl "20.00") (t/fcp-amount (brl "1000.00") 0.02M)))
    (is (≈ (brl "40.00") (t/fcp-amount (brl "1000.00") 0.04M)))))

(deftest rate-constants
  (testing "Legacy stack"
    (is (= 0.0165M t/pis-non-cumulative-rate))
    (is (= 0.076M  t/cofins-non-cumulative-rate))
    (is (= 0.15M   t/irpj-base-rate))
    (is (= 240000M t/irpj-surtax-threshold))
    (is (= 0.09M   t/csll-rate)))
  (testing "Tax-reform scaffold rates"
    (is (= 0.009M  t/cbs-rate-2026-pilot))
    (is (= 0.088M  t/cbs-rate-final))
    (is (= 0.177M  t/ibs-rate-final))))
