(ns kontor.payroll-fr.wage-types-test
  "Tests for the FR wage-types catalog (ADR-079)."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.payroll-fr.wage-types :as wt]))

(deftest catalog-membership
  (testing "Catalog contains every load-bearing FR component kind"
    (is (contains? (wt/known-kinds) :base-salary))
    (is (contains? (wt/known-kinds) :overtime))
    (is (contains? (wt/known-kinds) :13e-mois))
    (is (contains? (wt/known-kinds) :csg-deductible))
    (is (contains? (wt/known-kinds) :csg-non-deductible))
    (is (contains? (wt/known-kinds) :crds))
    (is (contains? (wt/known-kinds) :cotisation-urssaf))
    (is (contains? (wt/known-kinds) :cotisation-arrco-agirc))
    (is (contains? (wt/known-kinds) :cotisation-pole-emploi))
    (is (contains? (wt/known-kinds) :cotisation-prevoyance))
    (is (contains? (wt/known-kinds) :medical-mutuelle))
    (is (contains? (wt/known-kinds) :pas-withholding))
    (is (contains? (wt/known-kinds) :employer-urssaf))
    (is (contains? (wt/known-kinds) :employer-arrco-agirc))
    (is (contains? (wt/known-kinds) :tickets-restaurant))
    (is (contains? (wt/known-kinds) :participation))
    (is (contains? (wt/known-kinds) :interessement))
    (is (contains? (wt/known-kinds) :plan-epargne-entreprise))
    (is (contains? (wt/known-kinds) :conges-payes-accrual))))

(deftest pcg-tag-routing
  (testing "Base-salary → 641 (Rémunérations du personnel)"
    (is (= :fr-payroll-salaires (wt/account-tag :base-salary))))
  (testing "Cotisation URSSAF → 431 (URSSAF payable)"
    (is (= :fr-payroll-urssaf (wt/account-tag :cotisation-urssaf))))
  (testing "Cotisation ARRCO/AGIRC → 4371 (Retraite payable)"
    (is (= :fr-payroll-retraite (wt/account-tag :cotisation-arrco-agirc))))
  (testing "PAS withholding → 4421 (État PAS)"
    (is (= :fr-payroll-pas (wt/account-tag :pas-withholding))))
  (testing "Employer URSSAF: expense 6451, payable 431"
    (is (= :fr-payroll-er-urssaf (wt/account-tag :employer-urssaf)))
    (is (= :fr-payroll-urssaf (wt/payable-tag :employer-urssaf))))
  (testing "Vacation-payable accrual: 6412 expense, 4282 liability"
    (is (= :fr-payroll-conges-accrual (wt/account-tag :conges-payes-accrual)))
    (is (= :fr-payroll-conges-liability (wt/payable-tag :conges-payes-accrual)))))

(deftest employer-side-flag
  (testing "Employee-side kinds are NOT marked employer-side?"
    (is (false? (wt/employer-side? :base-salary)))
    (is (false? (wt/employer-side? :csg-deductible)))
    (is (false? (wt/employer-side? :pas-withholding))))
  (testing "Employer-side kinds ARE marked employer-side?"
    (is (true? (wt/employer-side? :employer-urssaf)))
    (is (true? (wt/employer-side? :employer-arrco-agirc)))
    (is (true? (wt/employer-side? :employer-pole-emploi)))
    (is (true? (wt/employer-side? :employer-prevoyance)))
    (is (true? (wt/employer-side? :conges-payes-accrual)))))

(deftest posts-flag
  (testing "Carry-only kinds (DSN base / plafond data) are :posts? false"
    (is (false? (wt/posts? :base-soumise-urssaf)))
    (is (false? (wt/posts? :base-soumise-csg)))
    (is (false? (wt/posts? :plafond-secu)))
    (is (false? (wt/posts? :tranche-a)))
    (is (false? (wt/posts? :tranche-b)))
    (is (false? (wt/posts? :tranche-c)))
    (is (false? (wt/posts? :smic-mensuel)))
    (is (false? (wt/posts? :heures-travaillees))))
  (testing "Earnings + deductions DO post"
    (is (true? (wt/posts? :base-salary)))
    (is (true? (wt/posts? :csg-deductible)))
    (is (true? (wt/posts? :employer-urssaf)))
    (is (true? (wt/posts? :conges-payes-accrual)))))

(deftest dsn-rubrique-mapping
  (testing "Pay-element kinds carry the right DSN rubrique"
    (is (= :s21-g00-51-001 (wt/dsn-rubrique :base-salary)))
    (is (= :s21-g00-51-017 (wt/dsn-rubrique :overtime)))
    (is (= :s21-g00-51-002 (wt/dsn-rubrique :13e-mois)))
    (is (= :s21-g00-51-003 (wt/dsn-rubrique :indemnite-conges-payes))))
  (testing "Cotisations carry the S21.G00.81 individuelle rubrique"
    (is (= :s21-g00-81-cotisation-urssaf (wt/dsn-rubrique :cotisation-urssaf)))
    (is (= :s21-g00-81-csg-deductible (wt/dsn-rubrique :csg-deductible)))
    (is (= :s21-g00-81-arrco-agirc (wt/dsn-rubrique :cotisation-arrco-agirc))))
  (testing "Carry-only DSN bases use S21.G00.78"
    (is (= :s21-g00-78-base-urssaf (wt/dsn-rubrique :base-soumise-urssaf)))
    (is (= :s21-g00-78-plafond (wt/dsn-rubrique :plafond-secu)))))

(deftest extras-map-extension
  (testing "Consumer can extend the catalog with bespoke kinds"
    (let [extras {:prime-anciennete {:account-tag :fr-payroll-primes
                                     :dsn-rubrique :s21-g00-51-002}}]
      (is (= :fr-payroll-primes (wt/account-tag :prime-anciennete extras)))
      (is (= :s21-g00-51-002 (wt/dsn-rubrique :prime-anciennete extras)))
      (is (true? (wt/posts? :prime-anciennete extras)))
      (is (contains? (wt/known-kinds extras) :prime-anciennete))))
  (testing "Consumer can override a standard kind"
    (let [extras {:base-salary {:account-tag :custom-wages-account}}]
      (is (= :custom-wages-account (wt/account-tag :base-salary extras))))))

(deftest unknown-kinds-detection
  (testing "Unknown kinds surface — used by the posting builder to fail loud"
    (let [comps [{:kind :base-salary :amount 100M}
                 {:kind :unknown-rubrique :amount 50M}
                 {:kind :another-mystery :amount 25M}]]
      (is (= #{:unknown-rubrique :another-mystery}
             (wt/unknown-kinds comps))))))
