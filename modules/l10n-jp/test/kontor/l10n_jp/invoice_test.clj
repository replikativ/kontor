(ns kontor.l10n-jp.invoice-test
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-jp.invoice :as inv]))

(deftest registration-number-validation
  (testing "Valid: T followed by exactly 13 digits"
    (is (inv/registration-number-valid? "T1234567890123"))
    (is (inv/registration-number-valid? "T0000000000001")))
  (testing "Invalid formats"
    (is (not (inv/registration-number-valid? "T123")))           ; too short
    (is (not (inv/registration-number-valid? "T12345678901234")))  ; too long
    (is (not (inv/registration-number-valid? "X1234567890123")))   ; wrong prefix
    (is (not (inv/registration-number-valid? "1234567890123")))    ; missing T
    (is (not (inv/registration-number-valid? "T123456789012A")))   ; non-digit
    (is (not (inv/registration-number-valid? nil)))
    (is (not (inv/registration-number-valid? "")))))

(deftest assert-throws-on-invalid
  (is (thrown? clojure.lang.ExceptionInfo
               (inv/assert-registration-number! "not-a-number")))
  (is (= "T1234567890123" (inv/assert-registration-number! "T1234567890123"))))

(deftest qis-field-validation-empty
  (testing "Empty invoice → all required fields missing"
    (let [missing (inv/validate-qis-fields {})]
      (is (= (count missing) (count inv/required-fields)))
      (is (every? #(= :missing-or-blank (:issue %)) missing)))))

(deftest qis-field-validation-complete
  (testing "All required fields present → no complaints"
    (let [complete {:issuer/name "Acme KK"
                    :issuer/registration-number "T1234567890123"
                    :transaction/date #inst "2026-01-15"
                    :buyer/name "Beta KK"
                    :line-items/by-rate [{:rate :10pct :amount 1000}]
                    :totals/taxable-amount-by-rate {:10pct 1000}
                    :totals/tax-amount-by-rate {:10pct 100}}]
      (is (empty? (inv/validate-qis-fields complete))))))
