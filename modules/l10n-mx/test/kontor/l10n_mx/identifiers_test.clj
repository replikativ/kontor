(ns kontor.l10n-mx.identifiers-test
  "Tests for RFC / CURP / CLABE validators.

   Mexican identifier algorithms are not as widely-published as e.g.
   the Indian GSTIN; we use the round-trip pattern (compute the
   check digit ourselves, append it, then validate the result) as
   the primary correctness test for algorithmic consistency. Where
   a public test vector exists with a known check digit (CLABE
   `002010077777777771` is the canonical one), we cross-check
   against it."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-mx.identifiers :as id]))

;; ============================================================================
;; RFC
;; ============================================================================

(deftest rfc-generic-accepted-by-name
  (testing "Generic SAT RFCs always validate (they bypass the
            check-digit rule)"
    (is (id/valid-rfc? "XAXX010101000")
        "Domestic anonymous / general public")
    (is (id/valid-rfc? "XEXX010101000")
        "Foreign receiver")))

(deftest rfc-invalid-by-structure
  (is (not (id/valid-rfc? "")))
  (is (not (id/valid-rfc? "TOOSHORT")))
  (is (not (id/valid-rfc? "1234567890123"))
      "All-digit doesn't match either PF or PM pattern")
  (is (not (id/valid-rfc? nil))))

(deftest rfc-roundtrip-persona-fisica
  (testing "Generate a PF RFC by computing its check digit, then
            validate the result — algorithmic consistency"
    (doseq [prefix-12 ["OEXJ680423AB"
                       "GORM850515DZ"
                       "ROCA790101AA"]]
      (let [check (@#'id/rfc-check-digit prefix-12)
            full (str prefix-12 check)]
        (is (id/valid-rfc? full)
            (str "Generated PF RFC must validate: " full))))))

(deftest rfc-roundtrip-persona-moral
  (testing "Generate a PM RFC (11 chars + check). The algorithm
            left-pads with a space internally."
    (doseq [prefix-11 ["AAA010101AB"
                       "BBB990505CD"
                       "GHI721130A1"]]
      (let [check (@#'id/rfc-check-digit prefix-11)
            full (str prefix-11 check)]
        (is (id/valid-rfc? full)
            (str "Generated PM RFC must validate: " full))))))

(deftest rfc-check-digit-detects-single-char-error
  (let [prefix-12 "OEXJ680423AB"
        check (@#'id/rfc-check-digit prefix-12)
        full (str prefix-12 check)
        ;; Flip the first char to break the check
        tampered (str "P" (subs full 1))]
    (is (id/valid-rfc? full))
    (is (not (id/valid-rfc? tampered)))))

;; ============================================================================
;; CURP
;; ============================================================================

(deftest curp-invalid-by-structure
  (is (not (id/valid-curp? "")))
  (is (not (id/valid-curp? "TOOSHORTCURP")))
  (is (not (id/valid-curp? "1234567890123456789"))
      "All-digit doesn't match the CURP pattern (needs name letters + sex letter)"))

(deftest curp-roundtrip
  (testing "Generate a CURP by computing its check digit, then validate"
    (doseq [prefix-17 ["GORM850515HDFRTR0"
                       "AABB901130MDFXXX1"
                       "ZACA721025HJCRMR3"]]
      (let [check (@#'id/curp-check-digit prefix-17)
            full (str prefix-17 check)]
        (is (id/valid-curp? full)
            (str "Generated CURP must validate: " full))))))

(deftest curp-check-detects-tampering
  (let [prefix-17 "GORM850515HDFRTR0"
        check (@#'id/curp-check-digit prefix-17)
        full (str prefix-17 check)
        tampered (str (subs full 0 16) "X" check)]
    (is (id/valid-curp? full))
    (is (not (id/valid-curp? tampered)))))

;; ============================================================================
;; CLABE
;; ============================================================================

(deftest clabe-canonical-vector
  (testing "002010077777777771 is the widely-published CLABE test
            vector — bank-code 002 (BANAMEX), branch 010, account
            07777777777, check digit 1"
    (is (id/valid-clabe? "002010077777777771"))
    (is (= "002" (id/clabe-bank-code "002010077777777771")))))

(deftest clabe-roundtrip
  (testing "Generate CLABEs by computing their check digit"
    (doseq [prefix-17 ["01218000800007777"
                       "00210400123456789"
                       "01419180012345678"]]
      (let [check (@#'id/clabe-check-digit prefix-17)
            full (str prefix-17 check)]
        (is (id/valid-clabe? full))))))

(deftest clabe-invalid-by-structure
  (is (not (id/valid-clabe? "")))
  (is (not (id/valid-clabe? "12345"))             "too short")
  (is (not (id/valid-clabe? "002010077777777770")) "wrong check digit")
  (is (not (id/valid-clabe? "00201007777777777X")) "non-digit"))

(deftest clabe-assert-throws
  (is (= "002010077777777771" (id/assert-clabe! "002010077777777771")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-clabe! "002010077777777770"))))
