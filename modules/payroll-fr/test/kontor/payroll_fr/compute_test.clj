(ns kontor.payroll-fr.compute-test
  "Tests for the FR compute providers (Silae + Sage CSV)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kontor.payroll-fr.compute :as c]
            [kontor.provider.payroll-provider :as pp]))

;; ============================================================================
;; Reference pay-element-codes map (engine rubrique → kontor kind)
;; ============================================================================
;; The consumer's expert-comptable supplies this map at provider
;; construction time. The :__skip-balancer kind signals a mirror row
;; that the parser drops (the kontor posting-builder derives the
;; payable mirror from the employer-side expense components on its
;; own).

(def silae-pay-element-codes
  {"SAL_BASE"          :base-salary
   "HRS_SUP"           :overtime
   "PRIME_13"          :13e-mois
   "PRIME_FA"          :prime-de-fin-d-annee
   "ICP"               :indemnite-conges-payes
   "TR_PAT"            {:kind :employer-tickets-restaurant
                        :employer-side? true}
   "TR_SAL"            :ticket-restaurant-part-salariale
   "AVNAT_VEH"         :avantage-nature-vehicule
   ;; employee deductions
   "COT_URSS_SAL"      :cotisation-urssaf
   "CSG_DED"           :csg-deductible
   "CSG_NDED"          :csg-non-deductible
   "CRDS"              :crds
   "COT_ARRCO_SAL"     :cotisation-arrco-agirc
   "COT_POLE_SAL"      :cotisation-pole-emploi
   "COT_MUT_SAL"       :medical-mutuelle
   "PAS"               :pas-withholding
   ;; employer charges (the expense leg)
   "COT_URSS_PAT"      {:kind :employer-urssaf :employer-side? true}
   "COT_ARRCO_PAT"     {:kind :employer-arrco-agirc :employer-side? true}
   "COT_POLE_PAT"      {:kind :employer-pole-emploi :employer-side? true}
   "COT_PREV_PAT"      {:kind :employer-prevoyance :employer-side? true}
   "COT_MUT_PAT"       {:kind :employer-mutuelle :employer-side? true}
   ;; employer-side mirror rows (the payable credit half) — drop them
   "COT_URSS_PAT_DUE"  :__skip-balancer
   "COT_ARRCO_PAT_DUE" :__skip-balancer
   "COT_POLE_PAT_DUE"  :__skip-balancer
   "COT_PREV_PAT_DUE"  :__skip-balancer
   "COT_MUT_PAT_DUE"   :__skip-balancer})

;; ============================================================================
;; Parse-level tests
;; ============================================================================

(deftest parse-silae-csv-basic
  (testing "Parses Silae CSV with semicolon separator + French decimals"
    (let [parsed (c/parse-gl-csv
                  (io/reader (io/resource
                              "kontor/payroll_fr/fixtures/silae_sample.csv"))
                  {:pay-element-codes silae-pay-element-codes})]
      (is (seq parsed))
      ;; Dupont base salary debit 3500
      (let [base (first (filter #(and (= "M-DUPONT" (:employee-external-id %))
                                      (= :base-salary (:kind %))) parsed))]
        (is (= 3500M (:amount base)))
        (is (false? (:employer-side? base))))
      ;; Dupont URSSAF employee credit: -284.80
      (let [urssaf (first (filter #(and (= "M-DUPONT" (:employee-external-id %))
                                        (= :cotisation-urssaf (:kind %)))
                                  parsed))]
        (is (= -284.80M (:amount urssaf))))
      ;; Dupont employer URSSAF debit (positive, employer-side)
      (let [er-urssaf (first (filter #(and (= "M-DUPONT" (:employee-external-id %))
                                           (= :employer-urssaf (:kind %)))
                                     parsed))]
        (is (= 1110M (:amount er-urssaf)))
        (is (true? (:employer-side? er-urssaf))))))
  (testing "Mirror rows mapped to :__skip-balancer are dropped"
    (let [parsed (c/parse-gl-csv
                  (io/reader (io/resource
                              "kontor/payroll_fr/fixtures/silae_sample.csv"))
                  {:pay-element-codes silae-pay-element-codes})]
      (is (empty? (filter #(= :__skip-balancer (:kind %)) parsed))))))

(deftest unknown-rubrique-fails-loud
  (testing "Unknown rubrique throws with the rubrique code in ex-info"
    (let [bad-csv (str "matricule;rubrique;libelle;debit;credit;compte;date\r\n"
                       "M-X;UNKNOWN_CODE;Boom;100,00;0,00;6411;2026-05-31\r\n")]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"Unknown FR pay-element"
           (c/parse-gl-csv (java.io.StringReader. bad-csv)
                           {:pay-element-codes silae-pay-element-codes}))))))

(deftest french-decimal-comma-coercion
  (testing "Parser handles French decimal commas (3500,00 → 3500M)"
    (let [csv (str "matricule;rubrique;libelle;debit;credit;compte;date\r\n"
                   "M-X;SAL_BASE;Base;3500,00;0,00;6411;2026-05-31\r\n")
          parsed (c/parse-gl-csv (java.io.StringReader. csv)
                                 {:pay-element-codes silae-pay-element-codes})]
      (is (= 1 (count parsed)))
      (is (= 3500M (:amount (first parsed))))))
  (testing "Parser handles European thousand-separator + decimal-comma"
    (let [csv (str "matricule;rubrique;libelle;debit;credit;compte;date\r\n"
                   "M-X;SAL_BASE;Base;3.500,75;0,00;6411;2026-05-31\r\n")
          parsed (c/parse-gl-csv (java.io.StringReader. csv)
                                 {:pay-element-codes silae-pay-element-codes})]
      (is (= 3500.75M (:amount (first parsed))))))
  (testing "Parser handles non-breaking-space thousand separator"
    (let [csv (str "matricule;rubrique;libelle;debit;credit;compte;date\r\n"
                   "M-X;SAL_BASE;Base;3 500,00;0,00;6411;2026-05-31\r\n")
          parsed (c/parse-gl-csv (java.io.StringReader. csv)
                                 {:pay-element-codes silae-pay-element-codes})]
      (is (= 3500M (:amount (first parsed)))))))

;; ============================================================================
;; Fact-assembly tests
;; ============================================================================

(deftest assemble-facts-grouping
  (testing "assemble-facts groups by matricule + computes gross/net/components"
    (let [parsed (c/parse-gl-csv
                  (io/reader (io/resource
                              "kontor/payroll_fr/fixtures/silae_sample.csv"))
                  {:pay-element-codes silae-pay-element-codes})
          facts (c/assemble-facts
                 parsed
                 {:external-id->eid {"M-DUPONT" 101 "M-MARTIN" 102}
                  :pay-period-eid 999
                  :commodity-eid 42
                  :engine :silae})]
      (is (= 2 (count facts)))
      ;; Dupont: gross = 3500 + 200 = 3700, net = 3700 - 284.80 - 240.87
      ;; - 108.39 - 22.58 - 148 - 45 - 284 = 2566.36
      (let [dupont (first (filter #(= 101 (:employment %)) facts))]
        (is (= 3700M (:gross dupont)))
        (is (= 2566.36M (:net dupont)))
        (is (= 999 (:pay-period dupont)))
        (is (= 42 (:commodity dupont)))
        (is (= :silae (:engine (:jurisdiction-specific-codes dupont))))
        (is (= "M-DUPONT" (:matricule (:jurisdiction-specific-codes dupont)))))
      ;; Martin: gross = 2800 (TR_PAT is employer-side so it's NOT in gross),
      ;; net = 2800 - 227.84 - 200.72 - 90.32 - 18.82 - 120 = 2142.30
      (let [martin (first (filter #(= 102 (:employment %)) facts))]
        (is (= 2800M (:gross martin)))
        (is (= 2142.30M (:net martin)))))))

(deftest assemble-facts-unknown-matricule-throws
  (testing "Unknown matricule throws"
    (let [parsed [{:employee-external-id "M-UNKNOWN" :kind :base-salary
                   :amount 100M :employer-side? false}]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"Unknown employee matricule"
           (c/assemble-facts
            parsed
            {:external-id->eid (constantly nil)}))))))

;; ============================================================================
;; SilaeGlProvider as a PayrollComputeProvider
;; ============================================================================

(deftest silae-provider-protocol
  (testing "SilaeGlProvider satisfies PayrollComputeProvider"
    (let [provider (c/->SilaeGlProvider
                    {:csv-source (io/reader
                                  (io/resource
                                   "kontor/payroll_fr/fixtures/silae_sample.csv"))
                     :pay-element-codes silae-pay-element-codes
                     :external-id->eid {"M-DUPONT" 101 "M-MARTIN" 102}
                     :commodity-eid 42})]
      (is (= :silae (pp/provider-id provider)))
      (let [facts (pp/compute-payroll provider
                                      {:pay-period-eid 999
                                       :entity-eid 7
                                       :employment-eids [101 102]})]
        (is (= 2 (count facts)))
        (is (= 3700M (-> (filter #(= 101 (:employment %)) facts)
                         first :gross)))))))

(deftest silae-provider-missing-opts
  (testing "Missing :csv-source throws"
    (let [provider (c/->SilaeGlProvider {})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (pp/compute-payroll provider {:pay-period-eid 1
                                                 :entity-eid 1
                                                 :employment-eids [1]}))))))

;; ============================================================================
;; SageGlProvider
;; ============================================================================

(deftest sage-provider-default-columns
  (testing "SageGlProvider uses sage-default-column-mapping out of the box"
    (let [csv (str "matricule;code-rubrique;libelle;debit;credit\r\n"
                   "M-X;SAL_BASE;Base;3000,00;0,00\r\n"
                   "M-X;COT_URSS_SAL;URSSAF;0,00;240,00\r\n"
                   "M-X;PAS;PAS;0,00;200,00\r\n")
          provider (c/->SageGlProvider
                    {:csv-source (java.io.StringReader. csv)
                     :pay-element-codes silae-pay-element-codes
                     :external-id->eid {"M-X" 1}})]
      (is (= :sage-paie (pp/provider-id provider)))
      (let [facts (pp/compute-payroll provider
                                      {:pay-period-eid 999
                                       :entity-eid 1
                                       :employment-eids [1]})]
        (is (= 1 (count facts)))
        (is (= 3000M (-> facts first :gross)))
        (is (= 2560M (-> facts first :net)))))))

;; ============================================================================
;; CegidApiProvider skeleton
;; ============================================================================

(deftest cegid-skeleton-throws
  (testing "CegidApiProvider correctly identifies as a skeleton"
    (let [provider (c/->CegidApiProvider {})]
      (is (= :cegid-paie (pp/provider-id provider)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"skeleton"
                            (pp/compute-payroll provider {}))))))
