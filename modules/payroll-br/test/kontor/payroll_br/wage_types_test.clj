(ns kontor.payroll-br.wage-types-test
  "Catalog membership + lookup tests for BR wage-types."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.payroll-br.wage-types :as wt]))

(deftest catalog-covers-load-bearing-rubricas
  (testing "Earnings kinds map to wages account tag"
    (doseq [k [:base-wage :overtime-50 :overtime-100 :commission
               :bonus :vacation-pay-paid-out :thirteenth-salary
               :ppr-profit-sharing :termination-pay
               :night-shift-addition :hazard-addition
               :unhealthy-addition]]
      (is (= :br-payroll-wages (wt/account-tag k))
          (str "Earnings kind " k " should route to :br-payroll-wages"))))
  (testing "Four canonical BR statutory buckets are distinct"
    (is (= :br-payroll-inss-employee (wt/account-tag :inss-employee)))
    (is (= :br-payroll-irrf (wt/account-tag :irrf-employee)))
    (is (= :br-payroll-er-inss (wt/account-tag :inss-employer)))
    (is (= :br-payroll-er-fgts (wt/account-tag :fgts-employer))))
  (testing "Employer-side flags"
    (is (wt/employer-side? :inss-employer))
    (is (wt/employer-side? :fgts-employer))
    (is (wt/employer-side? :sat-rat))
    (is (wt/employer-side? :ferias-accrual))
    (is (wt/employer-side? :thirteenth-salary-accrual))
    (is (wt/employer-side? :severance-fgts-accrual))
    (is (not (wt/employer-side? :inss-employee))))
  (testing "Payable-tag derives employer kind to its payable bucket"
    (is (= :br-payroll-inss-employer  (wt/payable-tag :inss-employer)))
    (is (= :br-payroll-fgts           (wt/payable-tag :fgts-employer)))
    (is (= :br-payroll-ferias-liability
           (wt/payable-tag :ferias-accrual)))
    (is (= :br-payroll-13th-liability
           (wt/payable-tag :thirteenth-salary-accrual)))
    (is (= :br-payroll-severance-liability
           (wt/payable-tag :severance-fgts-accrual))))
  (testing "Three load-bearing CPC 33 accruals are flagged"
    (is (wt/requires-cpc-33-accrual? :ferias-accrual))
    (is (wt/requires-cpc-33-accrual? :thirteenth-salary-accrual))
    (is (wt/requires-cpc-33-accrual? :severance-fgts-accrual))
    (is (not (wt/requires-cpc-33-accrual? :base-wage))))
  (testing "Carry-only kinds (no posting)"
    (is (not (wt/posts? :inss-base)))
    (is (not (wt/posts? :irrf-base)))
    (is (not (wt/posts? :fgts-base)))
    (is (not (wt/posts? :hours-worked))))
  (testing "Rubrica-hint mapping coverage"
    (is (= :salario-base (wt/rubrica-hint :base-wage)))
    (is (= :hora-extra-50 (wt/rubrica-hint :overtime-50)))
    (is (= :inss-empregado (wt/rubrica-hint :inss-employee)))
    (is (= :irrf (wt/rubrica-hint :irrf-employee)))
    (is (= :fgts-patronal (wt/rubrica-hint :fgts-employer)))
    (is (= :gratificacao-natalina
           (wt/rubrica-hint :thirteenth-salary)))))

(deftest extras-map-extends-without-clobbering
  (let [extras {:custom-bonus {:account-tag :my-custom
                               :rubrica-hint :premiacao-especial}}]
    (testing "Standard kinds still resolve"
      (is (= :br-payroll-wages (wt/account-tag :base-wage extras))))
    (testing "Custom kind resolves to user-supplied tag"
      (is (= :my-custom (wt/account-tag :custom-bonus extras))))
    (testing "Known kinds union"
      (is (contains? (wt/known-kinds extras) :custom-bonus))
      (is (contains? (wt/known-kinds extras) :base-wage)))))

(deftest unknown-kinds-detected
  (let [comps [{:kind :base-wage} {:kind :totally-fictional}]]
    (is (= #{:totally-fictional} (wt/unknown-kinds comps)))))

(deftest validate-catalog-accepts-well-formed
  (let [catalog {:catalog/version 1
                 :catalog/cnpj "11.222.333/0001-81"
                 :catalog/rubricas
                 {"R001" {:kind :base-wage :natureza "1000"}
                  "R200" {:kind :inss-employee :natureza "9201"}
                  "R210" {:kind :irrf-employee :natureza "9203"}
                  "R900" {:kind :inss-employer :natureza "9101"}
                  "R901" {:kind :fgts-employer :natureza "9102"}}}]
    (is (= catalog (wt/validate-catalog catalog)))))

(deftest validate-catalog-rejects-bad-shapes
  (testing "rejects unknown kind"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Unknown :kind"
         (wt/validate-catalog {:catalog/version 1
                               :catalog/cnpj "11.222.333/0001-81"
                               :catalog/rubricas
                               {"R001" {:kind :totally-fictional
                                        :natureza "1000"}}}))))
  (testing "rejects missing natureza"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"missing :natureza"
         (wt/validate-catalog {:catalog/version 1
                               :catalog/cnpj "11.222.333/0001-81"
                               :catalog/rubricas
                               {"R001" {:kind :base-wage}}}))))
  (testing "rejects missing cnpj"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #":catalog/cnpj required"
         (wt/validate-catalog {:catalog/version 1
                               :catalog/rubricas
                               {"R001" {:kind :base-wage
                                        :natureza "1000"}}}))))
  (testing "rejects empty rubricas"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #":catalog/rubricas"
         (wt/validate-catalog {:catalog/version 1
                               :catalog/cnpj "11.222.333/0001-81"
                               :catalog/rubricas {}}))))
  (testing "rejects unsupported version"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Unsupported catalog version"
         (wt/validate-catalog {:catalog/version 99
                               :catalog/cnpj "11.222.333/0001-81"
                               :catalog/rubricas
                               {"R001" {:kind :base-wage
                                        :natureza "1000"}}})))))

(deftest validate-catalog-allows-extra-kinds
  (let [catalog {:catalog/version 1
                 :catalog/cnpj "11.222.333/0001-81"
                 :catalog/rubricas
                 {"R999" {:kind :company-bonus :natureza "9999"}}}]
    (is (= catalog (wt/validate-catalog catalog
                                        :allow-extra-kinds
                                        #{:company-bonus})))))
