(ns kontor.l10n-fr.investment-income-provider-test
  "Tests for the FR investment-income providers (personal + corporate)
   over the ADR-101 statute-as-data substrate. Reproduces the worked
   examples from research note 149 §2 to the cent."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-fr.cgt-statute :as cgt-statute]
            [kontor.l10n-fr.investment-income-provider :as fr-inv]
            [kontor.l10n-fr.investment-income-statute :as inv-statute]
            [kontor.period-tax-provider :as ptp]))

;; ============================================================================
;; Fixture — FR CGT statute (for PS placement-rate + PFU IR-rate)
;;            + FR investment-income statute (for abattement + QPFC)
;; ============================================================================

(defn- fresh
  "Fresh DB with both statutes installed."
  []
  (let [conn (core/create-test-db)]
    (cgt-statute/install! conn)
    (inv-statute/install! conn)
    (d/transact conn [{:commodity/symbol "EUR" :commodity/name "Euro"
                       :commodity/precision 2}])
    conn))

(def ^:private p2026 {:from #inst "2026-01-01" :to #inst "2027-01-01"})

(def ^:private empty-bases
  {:fr-investment-income/dividende-fr-ue-eligible-abattement-40 0M
   :fr-investment-income/dividende-hors-abattement 0M
   :fr-investment-income/dividende-mere-fille-eligible 0M
   :fr-investment-income/interets-prfix-fr 0M
   :fr-investment-income/interets-livrets-exoneres 0M
   :fr-investment-income/assurance-vie-rachat-gain 0M
   :fr-investment-income/pea-retrait-gain 0M})

(defn- run-personal
  [conn bases & [extra-ctx]]
  (let [provider (fr-inv/fr-personal-investment-income-provider {})]
    (ptp/period-tax-facts
     provider
     (merge {:db (d/db conn)
             :entity nil
             :period p2026
             :inputs {:investment-income-bases (merge empty-bases bases)}}
            extra-ctx))))

(defn- run-corporate
  [conn bases & [extra-ctx]]
  (let [provider (fr-inv/fr-corporate-investment-income-provider {})]
    (ptp/period-tax-facts
     provider
     (merge {:db (d/db conn)
             :entity nil
             :period p2026
             :inputs {:investment-income-bases (merge empty-bases bases)}}
            extra-ctx))))

(defn- by-lane [facts lane]
  (->> (:components facts)
       (filter #(= lane (get-in % [:jurisdiction-specific-codes :lane])))
       first))

(defn- amt
  ^java.math.BigDecimal [money]
  (some-> money :amount))

;; ============================================================================
;; §1. PFU default — 31.4 % on €100k dividend
;; ============================================================================

(deftest pfu-default-31p4-on-100k-dividend
  (testing "default PFU: 12.8 % IR + 18.6 % PS = 31.4 % on €100k = €31,400"
    (let [conn (fresh)
          facts (run-personal conn
                              {:fr-investment-income/dividende-fr-ue-eligible-abattement-40 100000M})
          div   (by-lane facts :fr-dividendes)]
      (is (some? div))
      (is (= :fr-pfu (:regime div)))
      ;; IR base under PFU = gross
      (is (== 100000M (amt (:base div))))
      ;; gross-liability = 12.8 % IR + 18.6 % PS = 12800 + 18600 = 31400
      (is (== 31400M (amt (:gross-liability div))))
      (is (== 31400M (amt (:liability div))))
      ;; PIT-base-additions NOT set under PFU
      (is (nil? (get-in div [:jurisdiction-specific-codes :pit-base-additions]))))))

;; ============================================================================
;; §2. Barème election + 40 % abattement on €20k dividend (note 149 §2.1)
;; ============================================================================

(deftest bareme-election-with-40pct-abattement
  (testing "note 149 §2.1 — €20k dividend under barème: 40% abattement → 60% base"
    (let [conn (fresh)
          facts (run-personal conn
                              {:fr-investment-income/dividende-fr-ue-eligible-abattement-40 20000M}
                              {:tax-unit {:pfu-or-bareme :bareme}})
          div   (by-lane facts :fr-dividendes)]
      (is (some? div))
      (is (= :fr-bareme (:regime div)))
      ;; IR base = 60 % × 20000 = 12000 (the 40 % abattement kicks in)
      (is (== 12000M (amt (:base div))))
      ;; IR tax = 0 (folds into PIT base via :pit-base-additions)
      ;; Surtaxe PS = 18.6 % × 20000 = 3720 (PS on GROSS — Art. 150-0 D 4°)
      (is (== 3720M (amt (:gross-liability div)))
          "under barème, gross-liability is the PS only (IR=0)")
      (is (== 3720M (amt (:liability div))))
      ;; pit-base-additions = [12000]
      (is (= [12000M] (get-in div [:jurisdiction-specific-codes :pit-base-additions])))
      ;; CSG déductible 6.8 pp × 20000 = 1360
      (is (== 1360M (get-in div [:jurisdiction-specific-codes :csg-deductible-carry])))
      ;; PS line is 18.6 %
      (let [ps (first (:surtaxes div))]
        (is (= :ps (:code ps)))
        (is (== 3720M (amt (:amount ps))))))))

;; ============================================================================
;; §3. PFU with PAS prepayment (note 149 §2.1 track 1)
;; ============================================================================

(deftest pfu-with-pas-prepayment
  (testing "12.8 % PAS already withheld at payment → credits against year-end IR+PS"
    (let [conn (fresh)
          ;; €20k dividend under PFU. PAS €2,560 (12.8 %) withheld at payment.
          facts (run-personal conn
                              {:fr-investment-income/dividende-fr-ue-eligible-abattement-40 20000M}
                              {:inputs {:investment-income-bases
                                        (assoc empty-bases
                                               :fr-investment-income/dividende-fr-ue-eligible-abattement-40
                                               20000M)
                                        :prepaid-pas {:fr-pas-117-quater 2560M}}})
          div   (by-lane facts :fr-dividendes)]
      (is (some? div))
      ;; gross-liability = 12.8 % × 20000 + 18.6 % × 20000 = 2560 + 3720 = 6280
      (is (== 6280M (amt (:gross-liability div))))
      ;; prepaid = 2560 (PAS credit)
      (is (== 2560M (amt (:prepaid div))))
      ;; net liability = 6280 - 2560 = 3720
      (is (== 3720M (amt (:liability div)))))))

;; ============================================================================
;; §4. PEA — IR exempt, PS still due (note 149 §1.2)
;; ============================================================================

(deftest pea-ir-exempt-ps-due
  (testing "PEA disposal: no IR, PS at 18.6 % on gross"
    (let [conn (fresh)
          facts (run-personal conn
                              {:fr-investment-income/pea-retrait-gain 5000M})
          div   (by-lane facts :fr-dividendes)]
      (is (some? div))
      ;; non-PEA = 0 → IR = 0; PS on the PEA gross = 18.6 % × 5000 = 930
      (is (== 0M (amt (:base div))))
      (is (== 930M (amt (:gross-liability div))))
      (is (== 930M (amt (:liability div)))))))

(deftest pea-plus-regular-dividend-pfu
  (testing "PEA plus regular dividend under PFU — IR only on the non-PEA slice"
    (let [conn (fresh)
          facts (run-personal conn
                              {:fr-investment-income/dividende-fr-ue-eligible-abattement-40 10000M
                               :fr-investment-income/pea-retrait-gain 5000M})
          div   (by-lane facts :fr-dividendes)]
      (is (some? div))
      ;; IR (PFU) on non-PEA = 12.8 % × 10000 = 1280
      ;; PS on TOTAL = 18.6 % × 15000 = 2790
      ;; gross-liability = 1280 + 2790 = 4070
      (is (== 4070M (amt (:gross-liability div)))))))

;; ============================================================================
;; §5. Intérêts — PFU + low-RFR dispense (note 149 §2.2)
;; ============================================================================

(deftest interets-pfu-no-pas
  (testing "note 149 §2.2 — €800 interest, PFU default (no PAS dispense)"
    (let [conn (fresh)
          facts (run-personal conn
                              {:fr-investment-income/interets-prfix-fr 800M})
          int*  (by-lane facts :fr-interets)]
      (is (some? int*))
      ;; PFU: IR 12.8 % × 800 = 102.40 ; PS 18.6 % × 800 = 148.80
      ;; gross = 251.20
      (is (== 102.4M (* 800M 0.128M)))
      (is (== 148.8M (* 800M 0.186M)))
      (is (== 251.2M (amt (:gross-liability int*)))))))

(deftest interets-bareme-folds-to-pit
  (testing "barème election on €800 interest — IR folds via :pit-base-additions; PS still due"
    (let [conn (fresh)
          facts (run-personal conn
                              {:fr-investment-income/interets-prfix-fr 800M}
                              {:tax-unit {:pfu-or-bareme :bareme}})
          int*  (by-lane facts :fr-interets)]
      (is (some? int*))
      (is (= :fr-bareme (:regime int*)))
      ;; IR folds → 0 lift here; PS 18.6 % × 800 = 148.80
      (is (== 148.8M (amt (:gross-liability int*))))
      (is (= [800M] (get-in int* [:jurisdiction-specific-codes :pit-base-additions])))
      ;; CSG carry = 6.8 % × 800 = 54.40
      (is (== 54.4M (get-in int* [:jurisdiction-specific-codes :csg-deductible-carry]))))))

;; ============================================================================
;; §6. Assurance-vie — PS carve-out at 17.2 %
;; ============================================================================

(deftest assurance-vie-uses-17p2-ps-carve-out
  (testing "assurance-vie PS stays at 17.2 % even post-LFSS-2026"
    (let [conn (fresh)
          facts (run-personal conn
                              {:fr-investment-income/assurance-vie-rachat-gain 10000M})
          av    (by-lane facts :fr-assurance-vie)]
      (is (some? av))
      ;; PS = 17.2 % × 10000 = 1720 (NOT 18.6 %)
      ;; IR default = 12.8 % × 10000 = 1280
      ;; gross-liability = 1280 + 1720 = 3000
      (is (== 3000M (amt (:gross-liability av))))
      ;; The PS surtaxe line carries 1720, not 1860
      (let [ps (first (:surtaxes av))]
        (is (= :ps-av (:code ps)))
        (is (== 1720M (amt (:amount ps))))))))

(deftest assurance-vie-with-consumer-supplied-ir
  (testing "consumer supplies pre-computed IR (the §125-0 A ladder) → provider uses it"
    (let [conn (fresh)
          ;; €4 600 abattement, gain €6 000 → taxable gain = €1 400 @ 7.5 %
          ;; → IR = 105. PS 17.2 % × 6000 = 1032.
          facts (run-personal conn
                              {:fr-investment-income/assurance-vie-rachat-gain 6000M}
                              {:inputs {:investment-income-bases
                                        (assoc empty-bases
                                               :fr-investment-income/assurance-vie-rachat-gain 6000M)
                                        :assurance-vie {:ir 105M}}})
          av    (by-lane facts :fr-assurance-vie)]
      (is (some? av))
      (is (== 1137M (amt (:gross-liability av)))
          "consumer-supplied IR (€105) + 17.2% PS on €6000 = €1032 → total €1137"))))

;; ============================================================================
;; §7. Mère-fille corporate (note 149 §2.3)
;; ============================================================================

(deftest mere-fille-95pct-exemption-5pct-qpfc
  (testing "note 149 §2.3 — €500k dividend mère-fille: €25k QPFC (5 %) → CIT base addition"
    (let [conn (fresh)
          facts (run-corporate conn
                               {:fr-investment-income/dividende-mere-fille-eligible 500000M}
                               {:inputs {:investment-income-bases
                                         (assoc empty-bases
                                                :fr-investment-income/dividende-mere-fille-eligible
                                                500000M)
                                         :mere-fille {:holding-fraction 0.30M}}})
          mf    (by-lane facts :fr-mere-fille)]
      (is (some? mf))
      (is (= :fr-mere-fille (:regime mf)))
      ;; Standalone component liability = 0 (the IS lift happens at CIT)
      (is (== 0M (amt (:liability mf))))
      ;; QPFC threaded into :cit-base-additions
      (is (= [25000M] (get-in mf [:jurisdiction-specific-codes :cit-base-additions])))
      (is (true? (get-in mf [:jurisdiction-specific-codes :eligible?]))))))

(deftest mere-fille-ineligible-below-5pct-stake
  (testing "stake below 5 % → full inclusion via :cit-base-additions (not 5 % QPFC)"
    (let [conn (fresh)
          facts (run-corporate conn
                               {:fr-investment-income/dividende-mere-fille-eligible 500000M}
                               {:inputs {:investment-income-bases
                                         (assoc empty-bases
                                                :fr-investment-income/dividende-mere-fille-eligible
                                                500000M)
                                         :mere-fille {:holding-fraction 0.03M}}})
          mf    (by-lane facts :fr-mere-fille)]
      (is (some? mf))
      (is (= :fr-non-eligible (:regime mf)))
      ;; Full dividend folds — €500k into :cit-base-additions, no QPFC slice
      (is (= [500000M] (get-in mf [:jurisdiction-specific-codes :cit-base-additions])))
      (is (false? (get-in mf [:jurisdiction-specific-codes :eligible?]))))))

(deftest mere-fille-integration-fiscale-1pct-qpfc
  (testing "integration-fiscale flag → 1 % QPFC instead of 5 %"
    (let [conn (fresh)
          facts (run-corporate conn
                               {:fr-investment-income/dividende-mere-fille-eligible 500000M}
                               {:inputs {:investment-income-bases
                                         (assoc empty-bases
                                                :fr-investment-income/dividende-mere-fille-eligible
                                                500000M)
                                         :mere-fille {:holding-fraction 1M
                                                      :integration-fiscale? true}}})
          mf    (by-lane facts :fr-mere-fille)]
      (is (some? mf))
      (is (= :fr-integration-fiscale (:regime mf)))
      ;; 1 % × 500000 = 5000
      (is (= [5000M] (get-in mf [:jurisdiction-specific-codes :cit-base-additions]))))))

;; ============================================================================
;; §8. Component kind discipline
;; ============================================================================

(deftest all-components-use-investment-income-tax-kind
  (testing "personal provider"
    (let [conn (fresh)
          facts (run-personal conn
                              {:fr-investment-income/dividende-fr-ue-eligible-abattement-40 10000M
                               :fr-investment-income/interets-prfix-fr 1000M
                               :fr-investment-income/assurance-vie-rachat-gain 2000M})]
      (is (every? #(= :investment-income-tax (:kind %)) (:components facts))
          "all personal components carry :investment-income-tax kind")
      (is (= 3 (count (:components facts)))
          "three components fire: dividendes, intérêts, assurance-vie")))
  (testing "corporate provider"
    (let [conn (fresh)
          facts (run-corporate conn
                               {:fr-investment-income/dividende-mere-fille-eligible 100000M})]
      (is (every? #(= :investment-income-tax (:kind %)) (:components facts))
          "corporate mère-fille component carries :investment-income-tax kind"))))

;; ============================================================================
;; §9. Empty source — no investment income → empty components
;; ============================================================================

(deftest empty-source-yields-empty-components
  (testing "personal provider — no income → empty :components"
    (let [conn  (fresh)
          facts (run-personal conn {})]
      (is (= [] (:components facts)))))
  (testing "corporate provider — no income → empty :components"
    (let [conn  (fresh)
          facts (run-corporate conn {})]
      (is (= [] (:components facts))))))

;; ============================================================================
;; §10. Bitemporal — pre-LFSS-2026 PS rate (17.2 %) vs post (18.6 %)
;; ============================================================================

(deftest bitemporal-ps-rate-respects-effective-date
  (testing "period straddling pre-LFSS: PS placement-rate honours :as-of"
    (let [conn (fresh)
          ;; Force pre-LFSS 2026 by setting :as-of to 2025-06-01
          facts (run-personal conn
                              {:fr-investment-income/dividende-fr-ue-eligible-abattement-40 100000M}
                              {:as-of #inst "2025-06-01"})
          div   (by-lane facts :fr-dividendes)]
      (is (some? div))
      ;; IR 12.8 % + PS 17.2 % (pre-LFSS) = 30 %
      ;; gross = 12800 + 17200 = 30000
      (is (== 30000M (amt (:gross-liability div))))))
  (testing "post-LFSS-2026 PS = 18.6 %"
    (let [conn (fresh)
          facts (run-personal conn
                              {:fr-investment-income/dividende-fr-ue-eligible-abattement-40 100000M})
          div   (by-lane facts :fr-dividendes)]
      ;; gross = 12800 + 18600 = 31400
      (is (== 31400M (amt (:gross-liability div)))))))
