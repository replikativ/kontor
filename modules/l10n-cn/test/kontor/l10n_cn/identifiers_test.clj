(ns kontor.l10n-cn.identifiers-test
  "Tests for the GB 32100-2015 Unified Social Credit Code validator.

   Test vectors are hand-constructed USCC values whose 18th character
   was independently computed via the GB 32100-2015 base-31 weighted-
   sum algorithm. We do not embed live production USCCs; the values
   below are mathematically-valid synthetic fixtures derived from the
   public algorithm.

     - `91110000710935732K` — a SAMR-registered enterprise in
       Beijing, administrative-division code `110000` (北京市本级).
       This value circulates publicly as the canonical GB 32100-2015
       worked-example USCC and verifies correctly under our
       implementation.

     - `911101055889012340` — independently computed: authority 9,
       category 1, division 110105 (北京朝阳区), org-code 588901234,
       check '0'.

     - `91440300MA5DJX5W6T` — independently computed: authority 9,
       category 1, division 440300 (深圳市), org-code MA5DJX5W6
       (a SAMR 'MA'-prefixed post-reform organization code),
       check 'T'.

     - `91320100MA1MAQEN2U` — independently computed: authority 9,
       category 1, division 320100 (南京市), org-code MA1MAQEN2,
       check 'U'."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-cn.identifiers :as id]))

;; ============================================================================
;; valid-uscc?
;; ============================================================================

(deftest uscc-valid-canonical
  (testing "Hand-computed valid USCC fixtures (GB 32100-2015 check verified)"
    (is (id/valid-uscc? "91110000710935732K")
        "SAMR-published canonical worked-example")
    (is (id/valid-uscc? "911101055889012340")
        "All-digit base + check 0 — algorithm produces 0 when
         (31 - sum mod 31) ≡ 0 (mod 31)")
    (is (id/valid-uscc? "91440300MA5DJX5W6T")
        "Modern 'MA'-prefixed organization code (post-2015 reform
         assignment for entities with no legacy 组织机构代码)")
    (is (id/valid-uscc? "91320100MA1MAQEN2U"))))

(deftest uscc-tolerates-formatting
  (testing "Whitespace at either end is stripped; case is normalised"
    (is (id/valid-uscc? "  91110000710935732K  "))
    (is (id/valid-uscc? "91440300ma5djx5w6t")
        "Lowercase tolerated — Chinese systems print uppercase but
         hand-entered forms vary")))

(deftest uscc-invalid-wrong-check
  (testing "Wrong check digit is rejected"
    (is (not (id/valid-uscc? "91110000710935732L"))
        "Off-by-one check character")
    (is (not (id/valid-uscc? "91110000710935732A"))
        "Different alphabet character at check position")
    (is (not (id/valid-uscc? "91440300MA5DJX5W6Y"))
        "Wrong check char for the MA5DJX5W6 base")))

(deftest uscc-invalid-wrong-length
  (testing "Length must be exactly 18 characters"
    (is (not (id/valid-uscc? "")))
    (is (not (id/valid-uscc? "9111000071093573")) "16 — too short")
    (is (not (id/valid-uscc? "91110000710935732")) "17 — too short")
    (is (not (id/valid-uscc? "91110000710935732KK")) "19 — too long")))

(deftest uscc-invalid-excluded-letters
  (testing "Letters I / O / S / V / Z are excluded from the GB 32100
            alphabet (to disambiguate from digits 1 / 0 / 5 / U / 2)"
    (is (not (id/valid-uscc? "I1110000710935732K"))
        "I at position 1 — not in alphabet")
    (is (not (id/valid-uscc? "91O10000710935732K"))
        "O at position 3 — not in alphabet")
    (is (not (id/valid-uscc? "91110000710935732S"))
        "S at check position — not in alphabet")
    (is (not (id/valid-uscc? "91440300VA5DJX5W6T"))
        "V at organization-code position — not in alphabet")
    (is (not (id/valid-uscc? "91440300ZA5DJX5W6T"))
        "Z at organization-code position — not in alphabet")))

(deftest uscc-invalid-other
  (testing "Non-alphabet characters reject"
    (is (not (id/valid-uscc? "91110000710935732 ")) "trailing space inside the 18")
    (is (not (id/valid-uscc? "91-1000071093573-2K")) "hyphen — not a USCC separator")
    (is (not (id/valid-uscc? "91/110000710935732K")))))

(deftest uscc-invalid-nil-and-non-string
  (is (not (id/valid-uscc? nil)))
  (is (not (id/valid-uscc? 91110000710935732)))
  (is (not (id/valid-uscc? :91110000710935732K))))

(deftest uscc-single-character-error-detected
  (testing "Flipping any single character invalidates the USCC — the
            GB 32100-2015 single-error-detection guarantee."
    (let [valid "91110000710935732K"]
      (doseq [i (range 18)]
        (let [c (.charAt ^String valid i)
              ;; Choose any alphabet character distinct from c.
              other (first (remove #(= % c) "0123456789ABC"))
              tampered (str (subs valid 0 i) other (subs valid (inc i)))]
          (is (not (id/valid-uscc? tampered))
              (str "Tampered position " i ": " tampered " must fail")))))))

;; ============================================================================
;; parse-uscc
;; ============================================================================

(deftest parse-uscc-decomposes
  (let [r (id/parse-uscc "91110000710935732K")]
    (is (= \9       (:registration-authority r)))
    (is (= \1       (:organization-category r)))
    (is (= "110000" (:administrative-division r)))
    (is (= "710935732" (:organization-code r)))
    (is (= \K       (:check r)))
    (is (= "91110000710935732K" (:normalized r))))
  (testing "Lowercase input normalised to upper in :normalized"
    (let [r (id/parse-uscc "91440300ma5djx5w6t")]
      (is (= "91440300MA5DJX5W6T" (:normalized r)))
      (is (= "MA5DJX5W6" (:organization-code r))))))

(deftest parse-uscc-resolves-authority-and-category
  (testing "Authority + category labels are resolved when known"
    (let [r (id/parse-uscc "91110000710935732K")]
      (is (re-find #"SAMR" (:registration-authority-name r)))
      (is (re-find #"Enterprise" (:organization-category-name r))))))

(deftest parse-uscc-invalid-returns-nil
  (is (nil? (id/parse-uscc "garbage")))
  (is (nil? (id/parse-uscc nil)))
  (is (nil? (id/parse-uscc "91110000710935732L"))
      "Invalid check digit → whole thing is invalid"))

;; ============================================================================
;; assert-uscc!
;; ============================================================================

(deftest assert-uscc-throws
  (is (= "91110000710935732K" (id/assert-uscc! "91110000710935732K")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-uscc! "91110000710935732L")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-uscc! nil)))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-uscc! "tooshort"))))
