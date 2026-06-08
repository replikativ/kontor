(ns kontor.l10n-us.identifiers-test
  "Tests for US EIN validator.

   The IRS does not publish a check-digit algorithm — validity is
   prefix-table + structural shape only. Test vectors are
   hand-constructed EINs whose 2-digit prefix appears in the IRS
   valid-prefix table; bodies are synthetic 7-digit sequences and
   should NOT be misread as live production identifiers."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-us.identifiers :as id]))

;; ============================================================================
;; EIN
;; ============================================================================

(deftest ein-valid-canonical
  (testing "Hyphenated NN-NNNNNNN form with a recognised IRS prefix"
    (is (id/valid-ein? "12-3456789")
        "Prefix 12 — Cincinnati campus")
    (is (id/valid-ein? "04-2103594")
        "Prefix 04 — Andover/Atlanta campus")
    (is (id/valid-ein? "94-3000001")
        "Prefix 94 — Internet-assigned")
    (is (id/valid-ein? "47-1000001")
        "Prefix 47 — Internet-assigned")))

(deftest ein-valid-bare-form
  (testing "Bare 9-digit form (no hyphen) is accepted — many payroll
            systems strip the hyphen on export."
    (is (id/valid-ein? "123456789")
        "Prefix 12 with no hyphen")
    (is (id/valid-ein? "941234567")
        "Prefix 94 with no hyphen")
    (is (id/valid-ein? "041234567"))))

(deftest ein-tolerates-surrounding-whitespace
  (is (id/valid-ein? "  12-3456789  "))
  (is (id/valid-ein? "  123456789  ")))

(deftest ein-invalid-unassigned-prefix
  (testing "Prefixes the IRS has never assigned must be rejected.
            These are the substrate's only line of defence against
            an SSN being mis-keyed as an EIN."
    (is (not (id/valid-ein? "07-1234567"))
        "Prefix 07 — unassigned")
    (is (not (id/valid-ein? "08-1234567"))
        "Prefix 08 — unassigned")
    (is (not (id/valid-ein? "09-1234567"))
        "Prefix 09 — unassigned")
    (is (not (id/valid-ein? "17-1234567"))
        "Prefix 17 — unassigned")
    (is (not (id/valid-ein? "28-1234567"))
        "Prefix 28 — unassigned")
    (is (not (id/valid-ein? "49-1234567"))
        "Prefix 49 — unassigned")
    (is (not (id/valid-ein? "69-1234567"))
        "Prefix 69 — unassigned")
    (is (not (id/valid-ein? "70-1234567"))
        "Prefix 70 — unassigned")
    (is (not (id/valid-ein? "78-1234567"))
        "Prefix 78 — unassigned")
    (is (not (id/valid-ein? "89-1234567"))
        "Prefix 89 — unassigned")
    (is (not (id/valid-ein? "96-1234567"))
        "Prefix 96 — unassigned")
    (is (not (id/valid-ein? "97-1234567"))
        "Prefix 97 — unassigned")))

(deftest ein-invalid-wrong-length
  (testing "Length must be exactly 9 digits"
    (is (not (id/valid-ein? "")))
    (is (not (id/valid-ein? "12-345678"))
        "8 digits — too short")
    (is (not (id/valid-ein? "12-34567890"))
        "10 digits — too long")
    (is (not (id/valid-ein? "12345678"))
        "8 digits bare")
    (is (not (id/valid-ein? "1234567890"))
        "10 digits bare")))

(deftest ein-invalid-non-digit
  (testing "Non-digit characters (other than the canonical hyphen) reject"
    (is (not (id/valid-ein? "12-345678X")))
    (is (not (id/valid-ein? "1A-3456789")))
    (is (not (id/valid-ein? "12.3456789"))
        "Period is not an EIN separator")
    (is (not (id/valid-ein? "12 3456789"))
        "Space inside the value is not accepted — the IRS prints
         a hyphen only")))

(deftest ein-invalid-wrong-hyphen-position
  (testing "The hyphen must be after position 2; arbitrary hyphen
            placements are not the IRS format"
    (is (not (id/valid-ein? "1-23456789"))
        "Hyphen after 1 digit")
    (is (not (id/valid-ein? "123-456789"))
        "Hyphen after 3 digits")
    (is (not (id/valid-ein? "1234-56789"))
        "Hyphen after 4 digits")))

(deftest ein-invalid-nil-and-non-string
  (is (not (id/valid-ein? nil)))
  (is (not (id/valid-ein? 123456789)))
  (is (not (id/valid-ein? :123456789))))

(deftest parse-ein-decomposes
  (let [r (id/parse-ein "12-3456789")]
    (is (= "12"       (:prefix r)))
    (is (= "3456789"  (:body r)))
    (is (= "Cincinnati" (:campus r)))
    (is (= "12-3456789" (:formatted r))))
  (testing "Bare-digit input is canonically reformatted"
    (let [r (id/parse-ein "941234567")]
      (is (= "94-1234567" (:formatted r)))
      (is (= "Internet"   (:campus r)))))
  (testing "Invalid EIN → nil"
    (is (nil? (id/parse-ein "07-1234567"))
        "Unassigned prefix returns nil")
    (is (nil? (id/parse-ein "abc")))
    (is (nil? (id/parse-ein nil)))))

(deftest ein-assert-throws
  (is (= "12-3456789" (id/assert-ein! "12-3456789")))
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-ein! "07-1234567"))
      "Unassigned prefix throws")
  (is (thrown? clojure.lang.ExceptionInfo
               (id/assert-ein! nil))))

(deftest valid-ein-prefixes-table-shape
  (testing "The prefix table covers the full set of IRS-assigned
            campuses + Internet allocations. Sanity-check the size
            and absence of known-unassigned codes."
    (is (>= (count id/valid-ein-prefixes) 75)
        "At least 75 assigned prefixes — guards against accidental table
         truncation")
    (doseq [unassigned ["07" "08" "09" "17" "18" "19" "28" "29"
                        "49" "69" "70" "78" "79" "89" "96" "97"]]
      (is (not (contains? id/valid-ein-prefixes unassigned))
          (str "Prefix " unassigned " must NOT be in the assigned table"))))
  (testing "Every prefix in the table is a 2-character digit string"
    (doseq [[prefix campus] id/valid-ein-prefixes]
      (is (re-matches #"^\d{2}$" prefix)
          (str "Prefix " prefix " must be 2 digits"))
      (is (string? campus)
          (str "Campus name for prefix " prefix " must be a string")))))
