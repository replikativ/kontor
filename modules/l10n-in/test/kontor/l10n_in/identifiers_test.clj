(ns kontor.l10n-in.identifiers-test
  "Tests for PAN / TAN / GSTIN validators.

   Test vectors are structurally-valid examples with mathematically-correct
   GSTIN checksums (no live CBIC status check — that's a separate API).

   GSTIN check-digit ground truth derived from CBIC + multiple
   independent open-source validators including Devil7-Softwares/
   GSTIN-Validator. Algorithm cross-verified with worked example
   '27AAPFU0939F1ZV' (Maharashtra)."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-in.identifiers :as id]))

;; ============================================================================
;; PAN
;; ============================================================================

(deftest pan-valid
  (testing "Structurally valid PANs with recognized entity-type at pos 4"
    (is (id/valid-pan? "AAPFU0939F"))      ; F = Firm
    (is (id/valid-pan? "AAACR4849R"))      ; C = Company
    (is (id/valid-pan? "AAATM2356Q"))      ; T = Trust
    (is (id/valid-pan? "ABCPK1234H"))))    ; P = Individual

(deftest pan-invalid
  (testing "Wrong length"
    (is (not (id/valid-pan? "")))
    (is (not (id/valid-pan? "AAPFU0939")))
    (is (not (id/valid-pan? "AAPFU0939FX"))))
  (testing "Wrong character class"
    (is (not (id/valid-pan? "AAPFU09F9F")))  ; non-digit in digit region
    (is (not (id/valid-pan? "AAP1U0939F")))) ; digit in letter region
  (testing "Unknown entity-type at position 4"
    (is (not (id/valid-pan? "AAAXU0939F"))   ; 'X' not in entity-type set
        "Position-4 must be one of {P C H F A T B L J G}"))
  (testing "Nil / non-string"
    (is (not (id/valid-pan? nil)))))

(deftest pan-entity-type-helper
  (is (= "Firm / LLP"     (id/pan-entity-type "AAPFU0939F")))
  (is (= "Company"        (id/pan-entity-type "AAACR4849R")))
  (is (= "Trust"          (id/pan-entity-type "AAATM2356Q")))
  (is (= "Individual"     (id/pan-entity-type "ABCPK1234H")))
  (is (nil? (id/pan-entity-type "AAAXU0939F"))))

(deftest pan-assert-throws
  (is (= "AAPFU0939F" (id/assert-pan! "AAPFU0939F")))
  (is (thrown? clojure.lang.ExceptionInfo (id/assert-pan! "AAAXU0939F"))))

;; ============================================================================
;; TAN
;; ============================================================================

(deftest tan-valid
  (is (id/valid-tan? "MUMR12345B"))
  (is (id/valid-tan? "DELE99999Z")))

(deftest tan-invalid
  (is (not (id/valid-tan? "")))
  (is (not (id/valid-tan? "MUMR1234B")))   ; 4 digits, not 5
  (is (not (id/valid-tan? "MUMR12345"))))  ; missing last letter

;; ============================================================================
;; GSTIN
;; ============================================================================

(deftest gstin-check-char-worked-example
  (testing "27AAPFU0939F1ZV — Maharashtra (state 27), Firm, check 'V'.
            This is the canonical algorithm test vector documented in
            multiple public GSTIN-validator implementations."
    (is (= \V (id/gstin-check-char "27AAPFU0939F1Z")))))

(deftest gstin-valid-known-vector
  (testing "Hand-verified check character: 27AAPFU0939F1ZV (MH / Firm)"
    (is (id/valid-gstin? "27AAPFU0939F1ZV"))))

(deftest gstin-check-char-detects-single-char-error
  (testing "Tampering with the check char breaks validation"
    (let [valid "27AAPFU0939F1ZV"
          tampered (str (subs valid 0 14) "X")]
      (is (not (id/valid-gstin? tampered))))))

(deftest gstin-state-pan-entity-number-helpers
  (let [g "27AAPFU0939F1ZV"]
    (is (= "27"         (id/gstin-state-code g)))
    (is (= "AAPFU0939F" (id/gstin-pan g)))
    (is (= "1"          (id/gstin-entity-number g)))))

(deftest gstin-invalid-shapes
  (testing "Wrong length"
    (is (not (id/valid-gstin? "27AAPFU0939F1Z")))    ; 14 chars
    (is (not (id/valid-gstin? "27AAPFU0939F1ZVV")))) ; 16 chars
  (testing "Position 14 must be 'Z'"
    (is (not (id/valid-gstin? "27AAPFU0939F1XV"))))
  (testing "Position 13 cannot be '0' (must be 1-9 or A-Z)"
    (is (not (id/valid-gstin? "27AAPFU0939F0ZV")))))

(deftest gstin-assert-throws
  (is (= "27AAPFU0939F1ZV" (id/assert-gstin! "27AAPFU0939F1ZV")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-gstin! "27AAPFU0939F1Z0"))))

(deftest gstin-checksum-roundtrip-on-all-states
  (testing "Generate a valid GSTIN for each state code 01-37 and
            confirm round-trip: compute the check char, build the
            full string, validate it."
    (doseq [state-code (range 1 38)]
      (let [ss     (format "%02d" state-code)
            prefix (str ss "AAACR4849R1Z")
            check  (id/gstin-check-char prefix)
            full   (str prefix check)]
        (is (id/valid-gstin? full)
            (str "Generated GSTIN must validate: " full))))))
