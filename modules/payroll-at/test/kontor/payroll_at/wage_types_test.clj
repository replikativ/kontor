(ns kontor.payroll-at.wage-types-test
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.payroll-at.wage-types :as wt]))

(deftest vocabulary-coverage
  (testing "every wage type has a default account code"
    (doseq [w wt/wage-types]
      (is (string? (wt/account-code-for w))
          (str "no default account for " w)))))

(deftest override-wins
  (testing "an override map takes precedence over default"
    (is (= "9999" (wt/account-code-for :grundgehalt {:grundgehalt "9999"})))
    (is (= "6000" (wt/account-code-for :grundgehalt {})))))

(deftest unknown-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (wt/account-code-for :not-a-wage-type))))

(deftest engine-code-maps
  (testing "BMD numeric codes map to known wage types"
    (is (= :grundgehalt (get wt/bmd-wage-code-map "0001")))
    (is (= :nettogehalt (get wt/bmd-wage-code-map "9000"))))
  (testing "RZL alpha codes map to known wage types"
    (is (= :grundgehalt (get wt/rzl-wage-code-map "GRU")))
    (is (= :nettogehalt (get wt/rzl-wage-code-map "NET")))))

(deftest payable-codes
  (testing "employer-borne wage types have payable codes"
    (is (= "3540" (wt/payable-code-for :sv-arbeitgeber)))
    (is (= "3550" (wt/payable-code-for :dienstgeberbeitrag-fond)))
    (is (= "3560" (wt/payable-code-for :kommunalsteuer)))))
