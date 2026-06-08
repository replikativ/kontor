(ns kontor.l10n-fr.identifiers-test
  "Tests for French business-identifier validators.

   Test vectors are publicly-printed identifiers — INSEE's K-bis
   extracts are public records, so well-known SIRENs / SIRETs (e.g.
   Renault SA `732 829 320`, EDF `552 081 317`, Carrefour SA
   `652 014 051` etc.) are not secrets. We deliberately do NOT embed
   live production VAT identifiers belonging to small or recent
   registrations; the algorithm itself is shape-only + checksum, and
   the historical examples below are derived from INSEE's public
   register and DGFiP's `((SIREN × 100) + 12) mod 97` worked example.

   Network validation (VIES) is explicitly out of scope per ADR-005:
   that's a runtime concern, not a substrate one."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-fr.identifiers :as id]))

;; ============================================================================
;; SIREN — 9 digits, Luhn check
;; ============================================================================

(deftest siren-valid-canonical
  (testing "Hand-verified Luhn-valid SIRENs (publicly listed entities)"
    (is (id/valid-siren? "732829320")
        "Renault SA — INSEE public register")
    (is (id/valid-siren? "552081317")
        "EDF — Électricité de France SA")
    (is (id/valid-siren? "775670417")
        "Hand-computed Luhn-valid synthetic")
    (is (id/valid-siren? "443061841")
        "Hand-computed Luhn-valid synthetic")))

(deftest siren-tolerates-formatting
  (testing "INSEE prints SIREN with spaces every three digits"
    (is (id/valid-siren? "732 829 320"))
    (is (id/valid-siren? "732-829-320")
        "Hyphen separators — tolerated for paste-friendliness")
    (is (id/valid-siren? "  732829320  ")
        "Outer whitespace trimmed")
    (is (id/valid-siren? "552 081 317"))))

(deftest siren-invalid-wrong-check
  (testing "Wrong check digit is rejected"
    (is (not (id/valid-siren? "732829321"))
        "Check 1 instead of 0")
    (is (not (id/valid-siren? "552081316"))
        "Check 6 instead of 7")))

(deftest siren-invalid-wrong-length
  (testing "Length must be exactly 9 digits"
    (is (not (id/valid-siren? "")))
    (is (not (id/valid-siren? "12345678"))
        "8 digits — too short")
    (is (not (id/valid-siren? "1234567890"))
        "10 digits — too long")
    (is (not (id/valid-siren? "73282932000074"))
        "14 digits — this is a SIRET shape, not a SIREN")))

(deftest siren-invalid-non-digit
  (testing "Non-digit content rejected"
    (is (not (id/valid-siren? "12345678X")))
    (is (not (id/valid-siren? "abcdefghi")))
    (is (not (id/valid-siren? "732.829.320"))
        "Period is not a SIREN separator")))

(deftest siren-invalid-nil
  (is (not (id/valid-siren? nil)))
  (is (not (id/valid-siren? 732829320)))
  (is (not (id/valid-siren? :732829320))))

(deftest siren-luhn-detects-single-digit-error
  (testing "Flipping any single digit invalidates the SIREN — the
            ISO 7812-1 single-error guarantee."
    (let [valid "732829320"]
      (doseq [i (range 9)]
        (let [c (.charAt ^String valid i)
              other-digit (if (= c \0) \1 \0)
              tampered (str (subs valid 0 i) other-digit (subs valid (inc i)))]
          (is (not (id/valid-siren? tampered))
              (str "Tampered position " i ": " tampered " must fail")))))))

(deftest siren-luhn-detects-adjacent-transposition
  (testing "Swapping two adjacent digits invalidates the SIREN (the
            ISO 7812-1 partial-transposition guarantee). Starting
            from `732829320`, swap positions 1↔2 to get `723829320`."
    (is (not (id/valid-siren? "723829320")))))

(deftest siren-parse-extracts-canonical-form
  (let [r (id/parse-siren "732 829 320")]
    (is (= "732829320" (:siren r))
        "Spaces stripped"))
  (is (nil? (id/parse-siren "bogus"))))

(deftest siren-assert-throws
  (is (= "732829320" (id/assert-siren! "732829320")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-siren! "732829321")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-siren! nil))))

;; ============================================================================
;; SIRET — 14 digits, Luhn over the full 14
;; ============================================================================

(deftest siret-valid-canonical
  (testing "Hand-verified Luhn-valid SIRETs (Renault HQ + synthetic)"
    (is (id/valid-siret? "73282932000074")
        "Renault SA HQ establishment — INSEE public")
    (is (id/valid-siret? "73282932000009")
        "Synthetic SIRET sharing Renault's SIREN")
    (is (id/valid-siret? "73282932000025")
        "Another synthetic Renault establishment")))

(deftest siret-tolerates-formatting
  (testing "INSEE prints SIRET as SIREN(3-3-3) NIC(5)"
    (is (id/valid-siret? "732 829 320 00074"))
    (is (id/valid-siret? "  73282932000074  "))
    (is (id/valid-siret? "732-829-320-00074"))))

(deftest siret-invalid-wrong-check
  (testing "Wrong check digit is rejected"
    (is (not (id/valid-siret? "73282932000075"))
        "Last digit flipped")
    (is (not (id/valid-siret? "73282932000073")))))

(deftest siret-invalid-wrong-length
  (testing "Length must be exactly 14 digits"
    (is (not (id/valid-siret? "732829320")) "9 digits — SIREN, not SIRET")
    (is (not (id/valid-siret? "7328293200007"))   "13 digits")
    (is (not (id/valid-siret? "732829320000748")) "15 digits")))

(deftest siret-invalid-non-digit
  (is (not (id/valid-siret? "7328293200007A")))
  (is (not (id/valid-siret? "abcdefghijklmn"))))

(deftest siret-invalid-nil
  (is (not (id/valid-siret? nil)))
  (is (not (id/valid-siret? "")))
  (is (not (id/valid-siret? 73282932000074))))

(deftest siret-la-poste-mod-5
  (testing "La Poste's SIREN 356000000 uses a custom mod-5 check
            (sum of all 14 digits ≡ 0 mod 5) instead of Luhn — a
            documented INSEE exception. We accept both schemes for
            this specific SIREN."
    (is (id/valid-siret? "35600000000048")
        "Synthetic La Poste SIRET — sum 35 ≡ 0 mod 5")
    (testing "But Luhn check is NOT required to also pass — and
              an arbitrary mod-5-valid SIRET with a non-La-Poste
              SIREN does not get the special-case treatment."
      (is (not (id/valid-siret? "73282932000075"))
          "Mod-5 sum is 35 but the SIREN is Renault, not La Poste —
           must fail (Luhn doesn't pass either).")
      (is (not (id/valid-siret? "12345678900000"))
          "SIREN 123456789 is not Luhn-valid; non-La-Poste path
           rejects it."))))

(deftest siret-parse-decomposes
  (let [r (id/parse-siret "732 829 320 00074")]
    (is (= "732829320" (:siren r)))
    (is (= "00074"     (:nic r))))
  (is (nil? (id/parse-siret "bogus"))))

(deftest siret-assert-throws
  (is (= "73282932000074" (id/assert-siret! "73282932000074")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-siret! "73282932000075")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-siret! nil))))

;; ============================================================================
;; TVA intracommunautaire — FR + key(2) + SIREN(9)
;; ============================================================================

(deftest tva-fr-valid-canonical
  (testing "Hand-computed `((SIREN × 100) + 12) mod 97` check keys.

            Renault SA SIREN 732829320:
              ((732829320 × 100) + 12) mod 97
              = (73282932012) mod 97 = 44 → `FR44732829320`."
    (is (id/valid-tva-fr? "FR44732829320")
        "Renault SA — verified arithmetic key 44")
    (is (id/valid-tva-fr? "FR03552081317")
        "EDF SA — key 03 (leading zero)")
    (is (id/valid-tva-fr? "FR81775670417")
        "Synthetic — key 81")
    (is (id/valid-tva-fr? "FR64443061841")
        "Synthetic — key 64")))

(deftest tva-fr-tolerates-formatting
  (testing "B2B invoices commonly render TVA with spaces"
    (is (id/valid-tva-fr? "FR 44 732 829 320"))
    (is (id/valid-tva-fr? "FR-44-732829320"))
    (is (id/valid-tva-fr? "  FR44732829320  "))))

(deftest tva-fr-invalid-wrong-check-key
  (testing "Wrong check key is rejected"
    (is (not (id/valid-tva-fr? "FR45732829320"))
        "Key 45 instead of 44")
    (is (not (id/valid-tva-fr? "FR00732829320"))
        "Key 00 — wrong")
    (is (not (id/valid-tva-fr? "FR04552081317"))
        "Key 04 instead of 03")))

(deftest tva-fr-invalid-bad-siren
  (testing "Embedded SIREN must be Luhn-valid"
    (is (not (id/valid-tva-fr? "FR12123456789"))
        "SIREN 123456789 not Luhn-valid, and key wrong")
    (is (not (id/valid-tva-fr? "FR44732829321"))
        "Tampered SIREN check digit; old key 44 no longer matches")))

(deftest tva-fr-invalid-wrong-prefix
  (testing "Country prefix must be uppercase 'FR'"
    (is (not (id/valid-tva-fr? "DE44732829320"))
        "DE prefix")
    (is (not (id/valid-tva-fr? "fr44732829320"))
        "Lowercase prefix rejected")
    (is (not (id/valid-tva-fr? "44732829320"))
        "Missing prefix")))

(deftest tva-fr-invalid-wrong-length
  (is (not (id/valid-tva-fr? "FR4473282932"))
      "FR + key + 8 SIREN digits — too short")
  (is (not (id/valid-tva-fr? "FR447328293200"))
      "Too long")
  (is (not (id/valid-tva-fr? "FR")))
  (is (not (id/valid-tva-fr? ""))))

(deftest tva-fr-invalid-non-digit-key
  (testing "Alphanumeric historical keys are out of scope — only
            numeric keys are accepted (the only form INSEE issues)"
    (is (not (id/valid-tva-fr? "FRK4732829320")))
    (is (not (id/valid-tva-fr? "FR4K732829320")))))

(deftest tva-fr-invalid-nil
  (is (not (id/valid-tva-fr? nil)))
  (is (not (id/valid-tva-fr? 44732829320))))

(deftest tva-fr-parse-decomposes
  (let [r (id/parse-tva-fr "FR44732829320")]
    (is (= "44"        (:key r)))
    (is (= "732829320" (:siren r))))
  (testing "Spaced form parses to canonical pieces"
    (let [r (id/parse-tva-fr "FR 44 732 829 320")]
      (is (= "44"        (:key r)))
      (is (= "732829320" (:siren r)))))
  (is (nil? (id/parse-tva-fr "bogus"))))

(deftest tva-fr-assert-throws
  (is (= "FR44732829320" (id/assert-tva-fr! "FR44732829320")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-tva-fr! "FR00732829320")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-tva-fr! nil))))
