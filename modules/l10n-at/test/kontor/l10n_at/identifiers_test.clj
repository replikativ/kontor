(ns kontor.l10n-at.identifiers-test
  "Tests for Austrian UID + Steuernummer + Firmenbuchnummer validators.

   Test vectors are:
     - UID — `ATU13585627` is the BMF Verfahrenshandbuch worked
       example (check digit 7 derived from the published mod-10 +
       cross-sum + 4 algorithm). `ATU37675002` is OMV AG's publicly-
       listed corporate UID (check 2). Additional synthetic fixtures
       (`ATU12345675`, `ATU00000006`) are hand-computed via the same
       algorithm.
     - Steuernummer — structurally-valid synthetic shapes; no
       nationwide check-digit algorithm exists, so the validator is
       shape-only (same policy as DE).
     - Firmenbuchnummer — structurally-valid synthetic shapes; the
       Prüfbuchstabe algorithm is not openly published, so the
       validator is shape-only."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-at.identifiers :as id]))

;; ============================================================================
;; UID — Umsatzsteuer-Identifikationsnummer (BMF mod-10 check)
;; ============================================================================

(deftest uid-valid-canonical-bmf-example
  (testing "ATU13585627 — the BMF Verfahrenshandbuch worked-example
            value. First 7 digits (1358562) + check digit 7 must
            satisfy the BMF mod-10 + cross-sum + 4 algorithm."
    (is (id/valid-uid? "ATU13585627"))))

(deftest uid-valid-public-vat-id
  (testing "ATU37675002 — OMV AG (publicly listed in corporate
            imprint). Algorithm fixture only."
    (is (id/valid-uid? "ATU37675002"))))

(deftest uid-valid-synthetic-algorithm-fixtures
  (testing "Hand-computed via the BMF mod-10 algorithm"
    (is (id/valid-uid? "ATU12345675")
        "Base 1234567 → check 5")
    (is (id/valid-uid? "ATU00000006")
        "Base 0000000 → check 6 (the +4 constant lower-bounds the sum)")))

(deftest uid-valid-tolerates-whitespace
  (testing "Outer whitespace is trimmed (paste-friendliness)"
    (is (id/valid-uid? "  ATU13585627  "))
    (is (id/valid-uid? "\tATU13585627\n"))))

(deftest uid-invalid-wrong-check-digit
  (testing "Last digit must satisfy BMF mod-10"
    (is (not (id/valid-uid? "ATU13585628"))
        "Last digit 8 instead of correct 7")
    (is (not (id/valid-uid? "ATU13585620"))
        "Off-by-one in any direction must fail")
    (is (not (id/valid-uid? "ATU37675000"))
        "OMV's check is 2; 0 must not validate")))

(deftest uid-invalid-wrong-prefix
  (testing "Country prefix must be uppercase 'ATU'"
    (is (not (id/valid-uid? "DE13585627"))
        "German prefix")
    (is (not (id/valid-uid? "FR13585627"))
        "French prefix")
    (is (not (id/valid-uid? "atu13585627"))
        "Lowercase 'atu' is rejected — BMF spec requires uppercase")
    (is (not (id/valid-uid? "AT13585627"))
        "Missing 'U' — that's an AT-Steuernummer prefix, not UID")))

(deftest uid-invalid-wrong-length
  (testing "Length must be ATU + exactly 8 digits"
    (is (not (id/valid-uid? "ATU1234567"))
        "7 digits — too short")
    (is (not (id/valid-uid? "ATU123456789"))
        "9 digits — too long")
    (is (not (id/valid-uid? "ATU")))
    (is (not (id/valid-uid? "")))))

(deftest uid-invalid-non-digit
  (testing "Non-digit characters after 'ATU' are rejected"
    (is (not (id/valid-uid? "ATUABCDEFGH")))
    (is (not (id/valid-uid? "ATU1234567X")))
    (is (not (id/valid-uid? "ATU-13585627")))))

(deftest uid-invalid-nil-and-non-string
  (is (not (id/valid-uid? nil)))
  (is (not (id/valid-uid? 13585627)))
  (is (not (id/valid-uid? :ATU13585627))))

(deftest uid-check-digit-detects-single-digit-error
  (testing "Flipping any single digit invalidates the UID — the
            mod-10 design guarantee for single-digit errors."
    (let [valid "ATU13585627"]
      (doseq [i (range 3 11)]
        (let [c (.charAt ^String valid i)
              other-digit (if (= c \0) \1 \0)
              tampered (str (subs valid 0 i) other-digit (subs valid (inc i)))]
          (is (not (id/valid-uid? tampered))
              (str "Tampered position " i ": " tampered " must fail")))))))

(deftest uid-parse-decomposes
  (let [r (id/parse-uid "ATU13585627")]
    (is (= "ATU13585627" (:uid r)))
    (is (= "1358562"     (:base r)))
    (is (= "7"           (:check r))))
  (testing "Parsing tolerates outer whitespace and produces the
            trimmed canonical form"
    (let [r (id/parse-uid "  ATU37675002  ")]
      (is (= "ATU37675002" (:uid r)))
      (is (= "2"           (:check r))))))

(deftest uid-parse-invalid-returns-nil
  (is (nil? (id/parse-uid "bogus")))
  (is (nil? (id/parse-uid nil)))
  (is (nil? (id/parse-uid "ATU13585628")))
  (is (nil? (id/parse-uid "DE13585627"))))

(deftest uid-assert-throws
  (is (= "ATU13585627" (id/assert-uid! "ATU13585627")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-uid! "ATU00000000")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-uid! nil))))

;; ============================================================================
;; Steuernummer — Austrian local tax number (shape only)
;; ============================================================================

(deftest steuernummer-valid-bare
  (testing "Bare 9-digit form"
    (is (id/valid-steuernummer? "123456789"))
    (is (id/valid-steuernummer? "000000000")
        "Structurally valid even if all-zero — shape-only validator")))

(deftest steuernummer-valid-slash-separated
  (testing "Conventional FF/NNNNNNN slash form"
    (is (id/valid-steuernummer? "12/3456789"))
    (is (id/valid-steuernummer? "99/9999999"))))

(deftest steuernummer-valid-tolerates-whitespace
  (testing "Outer whitespace is trimmed"
    (is (id/valid-steuernummer? "  12/3456789  "))
    (is (id/valid-steuernummer? "\t123456789\n"))))

(deftest steuernummer-invalid-wrong-length
  (testing "Length must be exactly 9 digits"
    (is (not (id/valid-steuernummer? "")))
    (is (not (id/valid-steuernummer? "12345678"))
        "8 digits — too short")
    (is (not (id/valid-steuernummer? "1234567890"))
        "10 digits — too long")
    (is (not (id/valid-steuernummer? "12/345678"))
        "Slash form with 8 digits — too short")
    (is (not (id/valid-steuernummer? "12/34567890"))
        "Slash form with 10 digits — too long")))

(deftest steuernummer-invalid-non-digit-content
  (testing "Non-digit, non-slash content is rejected"
    (is (not (id/valid-steuernummer? "abc")))
    (is (not (id/valid-steuernummer? "12-3456789"))
        "Hyphens are not a Steuernummer separator")
    (is (not (id/valid-steuernummer? "12 3456789"))
        "Spaces are not a Steuernummer separator")))

(deftest steuernummer-invalid-wrong-prefix-length
  (testing "Slash form requires exactly 2 prefix digits"
    (is (not (id/valid-steuernummer? "1/23456789"))
        "1-digit prefix")
    (is (not (id/valid-steuernummer? "123/456789"))
        "3-digit prefix")))

(deftest steuernummer-invalid-nil
  (is (not (id/valid-steuernummer? nil)))
  (is (not (id/valid-steuernummer? 123456789))
      "Non-string is rejected"))

(deftest steuernummer-parse-bare
  (let [r (id/parse-steuernummer "123456789")]
    (is (= "12"       (:finanzamt r)))
    (is (= "3456789"  (:sequence r)))
    (is (= "12/3456789" (:local r)))))

(deftest steuernummer-parse-slash
  (let [r (id/parse-steuernummer "99/9999999")]
    (is (= "99"       (:finanzamt r)))
    (is (= "9999999"  (:sequence r)))
    (is (= "99/9999999" (:local r)))))

(deftest steuernummer-parse-invalid-returns-nil
  (is (nil? (id/parse-steuernummer "abc")))
  (is (nil? (id/parse-steuernummer nil)))
  (is (nil? (id/parse-steuernummer "12-3456789"))))

(deftest steuernummer-assert-throws
  (is (= "12/3456789" (id/assert-steuernummer! "12/3456789")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-steuernummer! "bogus")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-steuernummer! nil))))

;; ============================================================================
;; Firmenbuchnummer — commercial-register number (shape only)
;; ============================================================================

(deftest firmenbuchnummer-valid-bare
  (testing "Bare digits + check letter form"
    (is (id/valid-firmenbuchnummer? "188776h"))
    (is (id/valid-firmenbuchnummer? "1a")
        "Minimum length — 1 digit + check letter")
    (is (id/valid-firmenbuchnummer? "999999z")
        "Maximum digit length — 6 digits + check letter")))

(deftest firmenbuchnummer-valid-fn-prefixed
  (testing "Conventional 'FN '-prefixed form (Firmenbuch correspondence)"
    (is (id/valid-firmenbuchnummer? "FN 188776h"))
    (is (id/valid-firmenbuchnummer? "FN  188776h")
        "Multiple spaces between FN and the body tolerated")
    (is (id/valid-firmenbuchnummer? "FN\t188776h")
        "Tab as separator tolerated")))

(deftest firmenbuchnummer-valid-tolerates-whitespace
  (testing "Outer whitespace is trimmed"
    (is (id/valid-firmenbuchnummer? "  188776h  "))
    (is (id/valid-firmenbuchnummer? "  FN 188776h  "))))

(deftest firmenbuchnummer-invalid-uppercase-check-letter
  (testing "Check letter must be lowercase (per Firmenbuch render)"
    (is (not (id/valid-firmenbuchnummer? "188776H"))
        "Uppercase rejected for unambiguous parsing")))

(deftest firmenbuchnummer-invalid-missing-check
  (testing "Missing check letter is rejected"
    (is (not (id/valid-firmenbuchnummer? "188776"))
        "Digits only — no Prüfbuchstabe")
    (is (not (id/valid-firmenbuchnummer? "")))))

(deftest firmenbuchnummer-invalid-too-many-digits
  (testing "More than 6 digits is rejected"
    (is (not (id/valid-firmenbuchnummer? "1234567h"))
        "7 digits — too long")
    (is (not (id/valid-firmenbuchnummer? "FN 1234567h")))))

(deftest firmenbuchnummer-invalid-too-many-letters
  (testing "Multiple check letters are rejected"
    (is (not (id/valid-firmenbuchnummer? "188776ha")))
    (is (not (id/valid-firmenbuchnummer? "188776hb")))))

(deftest firmenbuchnummer-invalid-digit-in-check-slot
  (testing "Check slot must be a letter — a digit there is rejected"
    (is (not (id/valid-firmenbuchnummer? "1887760")))
    (is (not (id/valid-firmenbuchnummer? "1234567")))))

(deftest firmenbuchnummer-invalid-non-digit-in-digit-slot
  (testing "Digit portion must be all digits"
    (is (not (id/valid-firmenbuchnummer? "abcdefh")))
    (is (not (id/valid-firmenbuchnummer? "1a8776h")))))

(deftest firmenbuchnummer-invalid-nil-and-non-string
  (is (not (id/valid-firmenbuchnummer? nil)))
  (is (not (id/valid-firmenbuchnummer? 188776)))
  (is (not (id/valid-firmenbuchnummer? :188776h))))

(deftest firmenbuchnummer-parse-bare
  (let [r (id/parse-firmenbuchnummer "188776h")]
    (is (= "188776"      (:digits r)))
    (is (= "h"           (:check r)))
    (is (= "FN 188776h"  (:canonical r))
        "Canonical render includes the 'FN ' prefix")))

(deftest firmenbuchnummer-parse-prefixed
  (let [r (id/parse-firmenbuchnummer "FN 188776h")]
    (is (= "188776"      (:digits r)))
    (is (= "h"           (:check r)))
    (is (= "FN 188776h"  (:canonical r)))))

(deftest firmenbuchnummer-parse-invalid-returns-nil
  (is (nil? (id/parse-firmenbuchnummer "bogus")))
  (is (nil? (id/parse-firmenbuchnummer nil)))
  (is (nil? (id/parse-firmenbuchnummer "188776H"))))

(deftest firmenbuchnummer-assert-throws
  (is (= "188776h" (id/assert-firmenbuchnummer! "188776h")))
  (is (= "FN 188776h" (id/assert-firmenbuchnummer! "FN 188776h")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-firmenbuchnummer! "bogus")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-firmenbuchnummer! nil))))
