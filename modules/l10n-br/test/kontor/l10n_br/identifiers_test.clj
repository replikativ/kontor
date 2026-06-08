(ns kontor.l10n-br.identifiers-test
  "Tests for CPF/CNPJ checksums.

   Known-good test values come from RFB's public examples + commonly-
   verifiable CPFs in third-party tax-firm guides. Each is a real
   mathematical-checksum-correct value (no live tax-status check —
   that's a separate RFB API)."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-br.identifiers :as id]))

;; ============================================================================
;; CPF
;; ============================================================================

(deftest cpf-valid
  (testing "Known-valid CPFs (mathematically correct checksums)"
    (is (id/valid-cpf? "11144477735"))
    (is (id/valid-cpf? "12345678909"))
    (is (id/valid-cpf? "111.444.777-35")
        "Accepts formatted input")
    (is (id/valid-cpf? "111.444.777/35")
        "Formatting tolerated (any non-digit ignored)")))

(deftest cpf-invalid
  (testing "Wrong length"
    (is (not (id/valid-cpf? "")))
    (is (not (id/valid-cpf? "12345")))
    (is (not (id/valid-cpf? "123456789012")))
    (is (not (id/valid-cpf? nil))))
  (testing "Blacklisted same-digit strings"
    (is (not (id/valid-cpf? "00000000000")))
    (is (not (id/valid-cpf? "11111111111")))
    (is (not (id/valid-cpf? "22222222222")))
    (is (not (id/valid-cpf? "99999999999"))))
  (testing "Wrong checksum digits"
    (is (not (id/valid-cpf? "11144477736")) "Last digit off by 1")
    (is (not (id/valid-cpf? "11144477745")) "Second-to-last digit off by 1")))

(deftest cpf-format
  (is (= "111.444.777-35" (id/format-cpf "11144477735")))
  (is (= "111.444.777-35" (id/format-cpf "111.444.777-35")))
  (is (nil? (id/format-cpf "12345"))))

(deftest cpf-assert-throws
  (is (= "11144477735" (id/assert-cpf! "11144477735")))
  (is (thrown? clojure.lang.ExceptionInfo (id/assert-cpf! "12345678900"))))

;; ============================================================================
;; CNPJ
;; ============================================================================

(deftest cnpj-valid
  (testing "Known-valid CNPJs"
    (is (id/valid-cnpj? "11222333000181"))
    (is (id/valid-cnpj? "11.222.333/0001-81")
        "Accepts formatted input")
    (is (id/valid-cnpj? "12345678000195"))
    (is (id/valid-cnpj? "12.345.678/0001-95"))))

(deftest cnpj-invalid
  (testing "Wrong length"
    (is (not (id/valid-cnpj? "")))
    (is (not (id/valid-cnpj? "12345"))))
  (testing "Blacklisted same-digit strings"
    (is (not (id/valid-cnpj? "00000000000000")))
    (is (not (id/valid-cnpj? "11111111111111")))
    (is (not (id/valid-cnpj? "99999999999999"))))
  (testing "Wrong checksum digits"
    (is (not (id/valid-cnpj? "11222333000182")) "Last digit wrong")
    (is (not (id/valid-cnpj? "12345678000196")) "Last digit wrong")))

(deftest cnpj-format
  (is (= "12.345.678/0001-95" (id/format-cnpj "12345678000195")))
  (is (= "11.222.333/0001-81" (id/format-cnpj "11.222.333/0001-81")))
  (is (nil? (id/format-cnpj "12345"))))

(deftest cnpj-assert-throws
  (is (= "12345678000195" (id/assert-cnpj! "12345678000195")))
  (is (thrown? clojure.lang.ExceptionInfo (id/assert-cnpj! "12345678000100"))))

;; ============================================================================
;; CNPJ alfanumérico (Res. RFB 2.229/2024, effective 2026-07)
;;
;; Test values computed in-house from the RFB-NT 49/2024 per-char-value
;; mod-11 formula (ord(c) - ord('0')). Each base + check pair was
;; derived in the REPL and is reproducible from `cnpj-weights-1/2`
;; in identifiers.clj.
;; ============================================================================

(deftest cnpj-alphanumeric-valid
  (testing "Mixed alphanumeric base — check digits remain numeric"
    (is (id/valid-cnpj? "12ABC34DE56752"))
    (is (id/valid-cnpj? "ABCDEFGH000195")))
  (testing "Lowercase input is up-cased before validation"
    (is (id/valid-cnpj? "abcdefgh000195"))
    (is (id/valid-cnpj? "12abc34de56752")))
  (testing "Formatted alphanumeric"
    (is (id/valid-cnpj? "12.ABC.34D/E567-52"))))

(deftest cnpj-alphanumeric-invalid
  (testing "Check digits in the trailing two positions must be numeric"
    (is (not (id/valid-cnpj? "12ABC34DE567AB"))
        "Letters in the check-digit slot are not allowed"))
  (testing "Wrong checksum"
    (is (not (id/valid-cnpj? "12ABC34DE56700"))))
  (testing "All-same-character is blacklisted (extended to letters)"
    (is (not (id/valid-cnpj? "AAAAAAAAAAAAAA")))))

(deftest cnpj-alphanumeric-format
  (is (= "12.ABC.34D/E567-52" (id/format-cnpj "12ABC34DE56752")))
  (is (= "12.ABC.34D/E567-52" (id/format-cnpj "12abc34de56752"))
      "Formatting normalizes to uppercase"))
