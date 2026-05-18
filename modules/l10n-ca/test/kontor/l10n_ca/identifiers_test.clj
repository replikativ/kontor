(ns kontor.l10n-ca.identifiers-test
  "Tests for CRA Business Number + program-account validators.

   Test vectors are hand-constructed BN9 values whose 9th digit was
   independently computed via the Luhn algorithm (ISO/IEC 7812-1).
   We do not embed live production BN values; the algorithm itself
   is shape-only + checksum, and `123456782` / `773592878` /
   `799273982` are mathematically-valid synthetic fixtures derived
   from textbook Luhn worked examples (Wikipedia's `7992739871` →
   check 3 is the canonical reference)."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-ca.identifiers :as id]))

;; ============================================================================
;; Business Number (BN9) — Luhn check digit
;; ============================================================================

(deftest bn-valid-canonical
  (testing "Hand-computed valid BN9 fixtures (Luhn check verified)"
    (is (id/valid-business-number? "123456782")
        "Base 12345678 → Luhn 2")
    (is (id/valid-business-number? "773592878")
        "Base 77359287 → Luhn 8")
    (is (id/valid-business-number? "799273982")
        "Base 79927398 → Luhn 2 (derived from Wikipedia's canonical
         Luhn fixture 7992739871 → 3)")
    (is (id/valid-business-number? "000000000")
        "All-zero base → check 0; structurally valid by the Luhn
         spec (no all-same-digit blacklist for BN9)")))

(deftest bn-tolerates-formatting
  (testing "CRA prints BN with spaces and (occasionally) hyphens"
    (is (id/valid-business-number? "123 456 782"))
    (is (id/valid-business-number? "123-456-782"))
    (is (id/valid-business-number? "  123456782  "))
    (is (id/valid-business-number? "773 592 878"))))

(deftest bn-invalid-wrong-check
  (testing "Wrong check digit is rejected"
    (is (not (id/valid-business-number? "123456789"))
        "Check 9 instead of 2")
    (is (not (id/valid-business-number? "123456780"))
        "Check 0 instead of 2")
    (is (not (id/valid-business-number? "773592870"))
        "Check 0 instead of 8 — off by 8")))

(deftest bn-invalid-wrong-length
  (testing "Length must be exactly 9 digits"
    (is (not (id/valid-business-number? "")))
    (is (not (id/valid-business-number? "12345678"))
        "8 digits — too short")
    (is (not (id/valid-business-number? "1234567890"))
        "10 digits — too long")
    (is (not (id/valid-business-number? "12345678201234"))
        "14 digits — could be misread as a BN15 fragment")))

(deftest bn-invalid-non-digit
  (testing "Non-digit characters (other than allowed formatting) reject"
    (is (not (id/valid-business-number? "12345678X")))
    (is (not (id/valid-business-number? "abcdefghi")))
    (is (not (id/valid-business-number? "12345.6782"))
        "Period is not a BN separator")))

(deftest bn-invalid-nil-and-non-string
  (is (not (id/valid-business-number? nil)))
  (is (not (id/valid-business-number? 123456782)))
  (is (not (id/valid-business-number? :123456782))))

(deftest bn-luhn-detects-single-digit-error
  (testing "Flipping any single digit invalidates the BN — the
            ISO 7812-1 single-error guarantee."
    (let [valid "773592878"]
      (doseq [i (range 9)]
        (let [c (.charAt ^String valid i)
              other-digit (if (= c \0) \1 \0)
              tampered (str (subs valid 0 i) other-digit (subs valid (inc i)))]
          (is (not (id/valid-business-number? tampered))
              (str "Tampered position " i ": " tampered " must fail")))))))

(deftest bn-luhn-detects-adjacent-transposition
  (testing "Swapping two adjacent digits invalidates the BN (the
            ISO 7812-1 partial-transposition guarantee — catches all
            single transpositions except the 09↔90 pair).

            Starting from valid base 123456782, we swap positions
            3↔4 (4↔5 in the digits) to get 124356782 — an asymmetric
            transposition that the Luhn algorithm must catch."
    (is (not (id/valid-business-number? "124356782")))))

(deftest bn-assert-throws
  (is (= "123456782" (id/assert-business-number! "123456782")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-business-number! "123456789")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-business-number! nil))))

;; ============================================================================
;; Program account (BN15)
;; ============================================================================

(deftest program-account-valid
  (testing "BN15 = BN9 + 2-letter program + 4-digit reference"
    (is (id/valid-program-account? "123456782RT0001")
        "GST/HST first account")
    (is (id/valid-program-account? "773592878RP0001")
        "Payroll first account")
    (is (id/valid-program-account? "773592878RC0001")
        "Corporate income tax first account")
    (is (id/valid-program-account? "773592878RT0002")
        "Second GST/HST account (multi-division business)")))

(deftest program-account-tolerates-formatting
  (testing "CRA correspondence often spaces or hyphenates BN15"
    (is (id/valid-program-account? "123456782 RT 0001"))
    (is (id/valid-program-account? "123456782-RT-0001"))
    (is (id/valid-program-account? "  123456782RT0001  "))))

(deftest program-account-invalid-bad-bn
  (testing "Embedded BN9 must itself be Luhn-valid"
    (is (not (id/valid-program-account? "123456789RT0001"))
        "BN check digit 9 (invalid) → whole account invalid")
    (is (not (id/valid-program-account? "000000001RT0001"))
        "BN with wrong check")))

(deftest program-account-invalid-bad-program
  (testing "Program identifier must be exactly 2 uppercase letters"
    (is (not (id/valid-program-account? "123456782rt0001"))
        "Lowercase — rejected for unambiguous parsing")
    (is (not (id/valid-program-account? "123456782R10001"))
        "Digit in program slot")
    (is (not (id/valid-program-account? "123456782RTT001"))
        "3-letter program — misaligns reference")))

(deftest program-account-invalid-bad-reference
  (testing "Reference must be exactly 4 digits"
    (is (not (id/valid-program-account? "123456782RT001"))
        "3-digit reference — total length 14")
    (is (not (id/valid-program-account? "123456782RT00001"))
        "5-digit reference — total length 16")
    (is (not (id/valid-program-account? "123456782RT000A"))
        "Letter in reference slot")))

(deftest program-account-invalid-nil
  (is (not (id/valid-program-account? nil)))
  (is (not (id/valid-program-account? "")))
  (is (not (id/valid-program-account? 123456782))))

(deftest gst-hst-number-validates
  (testing "GST/HST number is BN15 with program = RT"
    (is (id/valid-gst-hst-number? "123456782RT0001"))
    (is (id/valid-gst-hst-number? "773592878RT0042"))
    (is (id/valid-gst-hst-number? "123456782 RT 0001")
        "Spaced form (the typical invoice rendering)")
    (testing "Non-RT programs are valid BN15 but NOT GST/HST numbers"
      (is (not (id/valid-gst-hst-number? "773592878RP0001"))
          "Payroll — not GST/HST")
      (is (not (id/valid-gst-hst-number? "773592878RC0001"))
          "Corp tax — not GST/HST")
      (is (not (id/valid-gst-hst-number? "773592878RM0001"))
          "Customs — not GST/HST"))))

(deftest parse-program-account-decomposes
  (let [r (id/parse-program-account "123456782RT0001")]
    (is (= "123456782" (:bn r)))
    (is (= "RT"        (:program r)))
    (is (= "GST/HST"   (:program-name r)))
    (is (= "0001"      (:reference r))))
  (testing "Known programs are name-resolved"
    (is (= "Payroll"
           (:program-name (id/parse-program-account "773592878RP0001"))))
    (is (= "Corporation income tax"
           (:program-name (id/parse-program-account "773592878RC0001")))))
  (testing "Unknown program letters still parse; name is nil"
    (let [r (id/parse-program-account "123456782ZZ0001")]
      (is (= "ZZ" (:program r)))
      (is (nil? (:program-name r))))))

(deftest parse-program-account-invalid-returns-nil
  (is (nil? (id/parse-program-account "abc")))
  (is (nil? (id/parse-program-account nil)))
  (is (nil? (id/parse-program-account "123456789RT0001"))
      "Invalid BN9 — whole thing is invalid"))

(deftest assert-program-account-throws
  (is (= "123456782RT0001"
         (id/assert-program-account! "123456782RT0001")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-program-account! "bogus")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-program-account! "123456789RT0001"))))
