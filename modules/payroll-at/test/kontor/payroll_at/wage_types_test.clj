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
  ;; Kontengruppen per KFS/BW 6: 350–359 Verbindlichkeiten aus Steuern
  ;; (LSt, USt, KomSt, DB/DZ), 360–369 Verbindlichkeiten im Rahmen der
  ;; sozialen Sicherheit (SV, both shares), 370–389 übrige. Note 194 §1 P0-4.
  (testing "employer-borne wage types have payable codes"
    (is (= "3600" (wt/payable-code-for :sv-arbeitgeber))
        "SV belongs to the soziale-Sicherheit group, not to Steuern")
    (is (= "3550" (wt/payable-code-for :dienstgeberbeitrag-fond)))
    (is (= "3545" (wt/payable-code-for :kommunalsteuer))
        "a Gemeinde levy is a Steuer (350–359), not soziale Sicherheit"))
  (testing "withholdings"
    (is (= "3540" (wt/account-code-for :lohnsteuer))
        "NOT 3500 — the l10n-at chart ships that as Umsatzsteuer 20 %")
    (is (= "3600" (wt/account-code-for :sv-arbeitnehmer)))))
