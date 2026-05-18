(ns kontor.l10n-au.identifiers-test
  "Tests for ABR (ABN) + ASIC (ACN) validators.

   Test vectors are either (a) the regulator-published example values
   from the public ABN-format / ACN-format documentation, or (b) hand-
   computed synthetic fixtures whose trailing check digit was derived
   independently from the published mathematical algorithm. We do NOT
   embed live production identifiers; the algorithms themselves are
   shape + checksum, so synthetic fixtures suffice for coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-au.identifiers :as id]))

;; ============================================================================
;; ABN — Australian Business Number
;; ============================================================================
;;
;; Canonical published example from the ABR documentation:
;;   ABN 51 824 753 556 — the worked example in
;;   https://abr.business.gov.au/Help/AbnFormat
;;
;; Synthetic-valid fixtures (mathematically valid, no real entity):
;;   12345678012, 99999999060, 11111111025 — derived by searching for
;;   trailing 3 digits that satisfy the mod-89 check on a chosen prefix.

(deftest abn-valid-published-example
  (testing "ABR-published worked example validates"
    (is (id/valid-abn? "51824753556"))))

(deftest abn-valid-synthetic-fixtures
  (testing "Hand-computed mathematically-valid ABNs"
    (is (id/valid-abn? "12345678012"))
    (is (id/valid-abn? "99999999060"))
    (is (id/valid-abn? "11111111025"))))

(deftest abn-tolerates-formatting
  (testing "ABR canonical print form is XX XXX XXX XXX (spaced)"
    (is (id/valid-abn? "51 824 753 556"))
    (is (id/valid-abn? "51-824-753-556"))
    (is (id/valid-abn? "  51824753556  "))))

(deftest abn-rejects-wrong-check
  (testing "Tampering the final digit fails the mod-89 check"
    (is (not (id/valid-abn? "51824753557")))
    (is (not (id/valid-abn? "51824753550")))))

(deftest abn-rejects-wrong-length
  (testing "Must be exactly 11 digits"
    (is (not (id/valid-abn? "")))
    (is (not (id/valid-abn? "5182475355"))
        "10 digits — too short")
    (is (not (id/valid-abn? "518247535561"))
        "12 digits — too long")))

(deftest abn-rejects-non-digit
  (testing "Non-digit characters (other than allowed formatting) reject"
    (is (not (id/valid-abn? "5182475355X")))
    (is (not (id/valid-abn? "abcdefghijk")))
    (is (not (id/valid-abn? "5182.475.3556")))))

(deftest abn-rejects-leading-zero
  (testing "ABR spec implies first digit must be >= 1 (the -1 step
            otherwise produces a negative weighted contribution)."
    (is (not (id/valid-abn? "01234567890")))))

(deftest abn-rejects-nil-and-non-string
  (is (not (id/valid-abn? nil)))
  (is (not (id/valid-abn? 51824753556)))
  (is (not (id/valid-abn? :51824753556))))

(deftest abn-detects-single-digit-error
  (testing "Flipping any single digit invalidates the ABN (mod-89
            partial single-digit guarantee)."
    (let [valid "51824753556"]
      (doseq [i (range 11)]
        (let [c (.charAt ^String valid i)
              other-digit (if (= c \0) \1 \0)
              tampered (str (subs valid 0 i) other-digit (subs valid (inc i)))]
          (is (not (id/valid-abn? tampered))
              (str "Tampered position " i ": " tampered " must fail")))))))

(deftest abn-detects-adjacent-transposition
  (testing "Swapping the first two digits of 51824753556 (5↔1) gives
            15824753556 — must fail the check."
    (is (not (id/valid-abn? "15824753556")))))

(deftest abn-assert-throws
  (is (= "51824753556" (id/assert-abn! "51824753556")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-abn! "51824753557")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-abn! nil))))

(deftest parse-abn-decomposes
  (testing "parse-abn returns canonical bare form + embedded-ACN hint"
    (let [r (id/parse-abn "51824753556")]
      (is (= "51824753556" (:abn r))))
    (testing "When digits 3..11 happen to form a valid ACN, surfaced as :acn"
      (let [;; '53004085616' embeds ASIC's documented example ACN
            ;; 004085616. The ABR allows companies to use this form.
            r (id/parse-abn "53004085616")]
        (is (= "53004085616" (:abn r)))
        (is (= "004085616"   (:acn r))
            "Embedded 9-digit tail matches the ACN check")))
    (testing "Spaced input is canonicalised in :abn"
      (let [r (id/parse-abn "51 824 753 556")]
        (is (= "51824753556" (:abn r)))))))

(deftest parse-abn-returns-nil-on-invalid
  (is (nil? (id/parse-abn nil)))
  (is (nil? (id/parse-abn "")))
  (is (nil? (id/parse-abn "51824753557"))))

;; ============================================================================
;; ACN — Australian Company Number
;; ============================================================================
;;
;; Canonical published example from ASIC documentation:
;;   ACN 004 085 616 — the worked example used in ASIC's Form 410
;;   instructions and the BHP-Billiton historical ACN reference.
;;
;; Synthetic-valid fixtures (mathematically valid, no real entity):
;;   123456780, 777777778, 999999996, 000000000 — derived by computing
;;   the trailing check digit from a chosen 8-digit base.

(deftest acn-valid-published-example
  (testing "ASIC-published worked example validates"
    (is (id/valid-acn? "004085616"))))

(deftest acn-valid-synthetic-fixtures
  (testing "Hand-computed mathematically-valid ACNs"
    (is (id/valid-acn? "123456780"))
    (is (id/valid-acn? "777777778"))
    (is (id/valid-acn? "999999996"))
    (is (id/valid-acn? "000000000")
        "All-zero base → check 0; structurally valid")))

(deftest acn-tolerates-formatting
  (testing "ASIC canonical print form is XXX XXX XXX (spaced)"
    (is (id/valid-acn? "004 085 616"))
    (is (id/valid-acn? "004-085-616"))
    (is (id/valid-acn? "  004085616  "))))

(deftest acn-rejects-wrong-check
  (testing "Tampering the trailing digit fails the mod-10 check"
    (is (not (id/valid-acn? "004085617")))
    (is (not (id/valid-acn? "123456789")))))

(deftest acn-rejects-wrong-length
  (testing "Must be exactly 9 digits"
    (is (not (id/valid-acn? "")))
    (is (not (id/valid-acn? "12345678"))
        "8 digits — too short")
    (is (not (id/valid-acn? "1234567890"))
        "10 digits — too long")))

(deftest acn-rejects-non-digit
  (testing "Non-digit characters (other than allowed formatting) reject"
    (is (not (id/valid-acn? "00408561X")))
    (is (not (id/valid-acn? "abcdefghi")))))

(deftest acn-rejects-nil-and-non-string
  (is (not (id/valid-acn? nil)))
  (is (not (id/valid-acn? 4085616)))
  (is (not (id/valid-acn? :004085616))))

(deftest acn-detects-single-digit-error
  (testing "Flipping any single digit invalidates the ACN."
    (let [valid "777777778"]
      (doseq [i (range 9)]
        (let [c (.charAt ^String valid i)
              other-digit (if (= c \0) \1 \0)
              tampered (str (subs valid 0 i) other-digit (subs valid (inc i)))]
          (is (not (id/valid-acn? tampered))
              (str "Tampered position " i ": " tampered " must fail")))))))

(deftest acn-assert-throws
  (is (= "004085616" (id/assert-acn! "004085616")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-acn! "123456789")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-acn! nil))))

(deftest parse-acn-decomposes
  (testing "parse-acn returns canonical bare form"
    (let [r (id/parse-acn "004085616")]
      (is (= "004085616" (:acn r))))
    (testing "Spaced input is canonicalised"
      (let [r (id/parse-acn "004 085 616")]
        (is (= "004085616" (:acn r)))))))

(deftest parse-acn-returns-nil-on-invalid
  (is (nil? (id/parse-acn nil)))
  (is (nil? (id/parse-acn "")))
  (is (nil? (id/parse-acn "123456789"))))
