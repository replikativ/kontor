(ns kontor.l10n-cn.fapiao-test
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.provider.einvoice-provider :as einvoice]
            [kontor.l10n-cn.fapiao :as fapiao]))

(deftest fapiao-number-formats
  (testing "8-digit legacy fapiao number accepted"
    (is (fapiao/fapiao-number-valid? "12345678")))
  (testing "18-digit combined special-VAT (10+8) accepted"
    (is (fapiao/fapiao-number-valid? "123456789012345678")))
  (testing "20-digit fully-digital identifier accepted (also 12+8 general)"
    (is (fapiao/fapiao-number-valid? "12345678901234567890")))
  (testing "32-char string rejected (was wrongly accepted before
            verification — not a regulated identifier)"
    (is (not (fapiao/fapiao-number-valid? "ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"))))
  (testing "Invalid: wrong length / non-numeric"
    (is (not (fapiao/fapiao-number-valid? "1234567")))          ; 7 digits
    (is (not (fapiao/fapiao-number-valid? "abcdefgh")))          ; non-digit
    (is (not (fapiao/fapiao-number-valid? "")))
    (is (not (fapiao/fapiao-number-valid? nil)))))

(deftest assert-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (fapiao/assert-fapiao-number! "garbage")))
  (is (= "12345678" (fapiao/assert-fapiao-number! "12345678"))))

(deftest provider-emits-draft-xml
  (let [p (fapiao/provider)
        inv {:kontor.invoice/external-id "INV-2026-0001"
             :kontor.invoice/issue-date #inst "2026-01-15"
             :kontor.invoice/currency "CNY"
             :kontor.invoice/total-gross "11300.00"}
        result (einvoice/emit p inv)]
    (is (= :cn/draft-fapiao (:einvoice/format result)))
    (is (= :keep-on-file (:einvoice/intended-for result)))
    (is (string? (:einvoice/payload result)))
    (is (re-find #"INV-2026-0001" (:einvoice/payload result)))))

(deftest provider-does-not-transmit
  (let [p (fapiao/provider)
        result (einvoice/transmit! p {} "payload")]
    (is (false? (:einvoice/transmitted? result))
        "Kernel-side provider never transmits to STA; partner does")))
