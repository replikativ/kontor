(ns kontor.l10n-de.identifiers-test
  "Tests for German Steuernummer + USt-IdNr validators.

   Test vectors are:
     - USt-IdNr — public canonical examples documented in BZSt + ISO
       7064 worked examples (`DE136695976` is the textbook BZSt
       walkthrough; `DE123456788` is the standard algorithm test
       fixture). Mathematically-correct checksums only — no live
       VIES status check (that's an out-of-scope runtime concern).
     - Steuernummer — structurally-valid synthetic shapes; we do not
       embed real production Steuernummern, and the algorithm itself
       is shape-only (per the BMF, no nationwide check-digit scheme
       exists; only Bayern publishes a Land-specific one)."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-de.identifiers :as id]))

;; ============================================================================
;; Steuernummer — structural validation
;; ============================================================================

(deftest steuernummer-valid-local-10
  (testing "10-digit local form (FF/BBB/UUUUP)"
    (is (id/valid-steuernummer? "21/815/08150"))
    (is (id/valid-steuernummer? "2181508150")
        "Bare digits without slashes are accepted")
    (is (id/valid-steuernummer? "  21/815/08150  ")
        "Leading/trailing whitespace tolerated")))

(deftest steuernummer-valid-local-11
  (testing "11-digit local form used by Bayern, Berlin, Bremen,
            Hamburg, Niedersachsen, Nordrhein-Westfalen,
            Sachsen-Anhalt, Schleswig-Holstein, Thüringen
            (FFF/BBB/UUUUP)"
    (is (id/valid-steuernummer? "181/815/08155"))
    (is (id/valid-steuernummer? "18181508155")
        "Bare 11-digit form")))

(deftest steuernummer-valid-unified-13
  (testing "Unified 13-digit form (BBBB0FFFUUUUP) for ELSTER"
    (is (id/valid-steuernummer? "9181081508150"))
    (is (id/valid-steuernummer? "2061081508150")
        "Bayern prefix (2061)")
    (is (id/valid-steuernummer? "1011081508150")
        "Berlin prefix (1011)")))

(deftest steuernummer-invalid-unified-without-middle-zero
  (testing "Unified 13-digit form requires literal '0' at position 5"
    (is (not (id/valid-steuernummer? "9181181508150"))
        "Position 5 is '1' — must be '0' in unified form")
    (is (not (id/valid-steuernummer? "2061981508150"))
        "Position 5 is '9' — invalid")))

(deftest steuernummer-invalid-wrong-length
  (testing "Wrong total length is rejected"
    (is (not (id/valid-steuernummer? "")))
    (is (not (id/valid-steuernummer? "123")))
    (is (not (id/valid-steuernummer? "123456789"))
        "9 digits — too short")
    (is (not (id/valid-steuernummer? "123456789012"))
        "12 digits — neither local nor unified")
    (is (not (id/valid-steuernummer? "12345678901234"))
        "14 digits — too long")))

(deftest steuernummer-invalid-non-digit-content
  (testing "Non-digit, non-slash content is rejected"
    (is (not (id/valid-steuernummer? "abc")))
    (is (not (id/valid-steuernummer? "21-815-08150"))
        "Hyphens are not a Steuernummer separator")
    (is (not (id/valid-steuernummer? "21 815 08150"))
        "Internal spaces are not a Steuernummer separator")))

(deftest steuernummer-invalid-nil
  (is (not (id/valid-steuernummer? nil)))
  (is (not (id/valid-steuernummer? 123)) "Non-string is rejected"))

(deftest steuernummer-parse-local-10
  (let [r (id/parse-steuernummer "21/815/08150")]
    (is (= :local       (:form r)))
    (is (nil?           (:land r)))
    (is (= "21"         (:finanzamt r)))
    (is (= "815"        (:district r)))
    (is (= "0815"       (:sequence r)))
    (is (= "0"          (:check r)))
    (is (= "21/815/08150" (:local r)))))

(deftest steuernummer-parse-local-11
  (let [r (id/parse-steuernummer "181/815/08155")]
    (is (= :local        (:form r)))
    (is (= "181"         (:finanzamt r)))
    (is (= "815"         (:district r)))
    (is (= "0815"        (:sequence r)))
    (is (= "5"           (:check r)))
    (is (= "181/815/08155" (:local r)))))

(deftest steuernummer-parse-unified
  (let [r (id/parse-steuernummer "2061081508150")]
    (is (= :unified  (:form r)))
    (is (= "Bayern"  (:land r)))
    (is (= "2061"    (:land-code r)))
    (is (= "815"     (:finanzamt r)))
    (is (= "0815"    (:sequence r)))
    (is (= "0"       (:check r))))
  (testing "Unknown Bundesland prefix still parses; :land is nil"
    (let [r (id/parse-steuernummer "9999081508150")]
      (is (= :unified (:form r)))
      (is (nil?       (:land r)))
      (is (= "9999"   (:land-code r))))))

(deftest steuernummer-parse-invalid-returns-nil
  (is (nil? (id/parse-steuernummer "abc")))
  (is (nil? (id/parse-steuernummer nil)))
  (is (nil? (id/parse-steuernummer "21-815-08150"))))

(deftest steuernummer-assert-throws
  (is (= "21/815/08150" (id/assert-steuernummer! "21/815/08150")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-steuernummer! "bogus"))))

;; ============================================================================
;; USt-IdNr — ISO 7064 MOD 11,10 check digit
;; ============================================================================

(deftest ust-idnr-valid-canonical-bzst-example
  (testing "DE136695976 — the BZSt-documented worked-example value.
            First 8 digits (13669597) + check digit 6 must satisfy
            ISO 7064 MOD 11,10."
    (is (id/valid-ust-idnr? "DE136695976"))))

(deftest ust-idnr-valid-public-vat-ids
  (testing "Publicly-listed corporate VAT IDs (printed on imprints /
            websites). Used here purely as algorithm fixtures."
    (is (id/valid-ust-idnr? "DE811569869")
        "SAP SE — publicly listed in their site imprint")
    (is (id/valid-ust-idnr? "DE129273398")
        "Deutsche Bank — publicly listed in their imprint")
    (is (id/valid-ust-idnr? "DE123456788")
        "Canonical algorithm-test fixture documented in multiple
         independent open-source VAT-ID validators")))

(deftest ust-idnr-valid-tolerates-whitespace
  (testing "Outer whitespace is trimmed (paste-friendliness)"
    (is (id/valid-ust-idnr? "  DE136695976  "))
    (is (id/valid-ust-idnr? "\tDE136695976\n"))))

(deftest ust-idnr-invalid-wrong-check-digit
  (testing "Last digit must satisfy MOD 11,10"
    (is (not (id/valid-ust-idnr? "DE136695975"))
        "Last digit 5 instead of the correct 6")
    (is (not (id/valid-ust-idnr? "DE123456789"))
        "Last digit 9 instead of correct 8 — off-by-one")
    (is (not (id/valid-ust-idnr? "DE811569860"))
        "SAP's check digit is 9; 0 must not validate")))

(deftest ust-idnr-invalid-wrong-prefix
  (testing "Country prefix must be uppercase 'DE'"
    (is (not (id/valid-ust-idnr? "FR136695976"))
        "French prefix")
    (is (not (id/valid-ust-idnr? "AT136695976"))
        "Austrian prefix")
    (is (not (id/valid-ust-idnr? "de136695976"))
        "Lowercase 'de' is rejected — DIN-spec requires uppercase")))

(deftest ust-idnr-invalid-wrong-length
  (testing "Length must be DE + exactly 9 digits"
    (is (not (id/valid-ust-idnr? "DE12345678"))
        "8 digits — too short")
    (is (not (id/valid-ust-idnr? "DE1234567890"))
        "10 digits — too long")
    (is (not (id/valid-ust-idnr? "DE")))
    (is (not (id/valid-ust-idnr? "")))))

(deftest ust-idnr-invalid-non-digit
  (testing "Non-digit characters after 'DE' are rejected"
    (is (not (id/valid-ust-idnr? "DEABCDEFGHI")))
    (is (not (id/valid-ust-idnr? "DE12345678X")))
    (is (not (id/valid-ust-idnr? "DE-12345678")))))

(deftest ust-idnr-invalid-nil-and-non-string
  (is (not (id/valid-ust-idnr? nil)))
  (is (not (id/valid-ust-idnr? 136695976)))
  (is (not (id/valid-ust-idnr? :DE136695976))))

(deftest ust-idnr-check-digit-detects-single-digit-error
  (testing "Flipping any single digit invalidates the number — this
            is the MOD-11,10 design guarantee."
    (let [valid "DE136695976"]
      (doseq [i (range 2 11)]
        (let [c (.charAt ^String valid i)
              other-digit (if (= c \0) \1 \0)
              tampered (str (subs valid 0 i) other-digit (subs valid (inc i)))]
          (is (not (id/valid-ust-idnr? tampered))
              (str "Tampered position " i ": " tampered " must fail")))))))

(deftest ust-idnr-assert-throws
  (is (= "DE136695976" (id/assert-ust-idnr! "DE136695976")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-ust-idnr! "DE000000000")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-ust-idnr! nil))))
