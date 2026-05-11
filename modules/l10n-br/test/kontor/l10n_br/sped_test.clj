(ns kontor.l10n-br.sped-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kontor.l10n-br.sped :as sped]
            [kontor.money :as money]))

(defn- brl [s] (money/money (bigdec s) :BRL))

;; ============================================================================
;; Record framing
;; ============================================================================

(deftest record-framing
  (testing "Pipe-delimited record with leading + trailing |"
    (is (= "|0001|0|" (sped/record "0001" 0))))
  (testing "Empty field renders as bare ||"
    (is (= "|TEST|a||b|" (sped/record "TEST" "a" nil "b"))))
  (testing "Money emits with Brazilian decimal separator (,)"
    (is (str/includes? (sped/record "X" (brl "1234.56")) "1234,56"))))

(deftest date-formatting-ddmmyyyy
  (testing "LocalDate"
    (let [ld (java.time.LocalDate/of 2026 1 15)]
      (is (str/includes? (sped/record "X" ld) "15012026"))))
  (testing "java.util.Date"
    (is (str/includes? (sped/record "X" #inst "2026-01-15") "15012026"))))

;; ============================================================================
;; Bloco 0 records
;; ============================================================================

(deftest rec-0000-header
  (let [r (sped/rec-0000 {:period-start (java.time.LocalDate/of 2026 1 1)
                          :period-end   (java.time.LocalDate/of 2026 1 31)
                          :company-name "Acme Indústria Ltda"
                          :cnpj "12345678000195"
                          :state "SP"
                          :state-tax-id "1234567890"
                          :municipality-code 3550308})]
    (is (str/starts-with? r "|0000|"))
    (is (str/includes? r "Acme Indústria Ltda"))
    (is (str/includes? r "12345678000195"))
    (is (str/includes? r "01012026"))                  ; period start
    (is (str/includes? r "31012026")))                 ; period end
  (testing "Default COD_VER is \"020\" — Guia Prático EFD-ICMS/IPI v3.2.1
            (Ato COTEPE/ICMS 44/2025), mandatory for 2026 fiscal periods"
    (let [r (sped/rec-0000 {:period-start (java.time.LocalDate/of 2026 1 1)
                            :period-end   (java.time.LocalDate/of 2026 1 31)
                            :company-name "X" :cnpj "12345678000195"
                            :state "SP" :state-tax-id "0" :municipality-code 0})]
      (is (str/starts-with? r "|0000|020|")
          "Second field is COD_VER and defaults to 020")))
  (testing "Caller can override :version for retro-filings (2025 → \"019\")"
    (let [r (sped/rec-0000 {:version "019"
                            :period-start (java.time.LocalDate/of 2025 12 1)
                            :period-end   (java.time.LocalDate/of 2025 12 31)
                            :company-name "X" :cnpj "12345678000195"
                            :state "SP" :state-tax-id "0" :municipality-code 0})]
      (is (str/starts-with? r "|0000|019|")))))

(deftest rec-0001-empty
  (is (= "|0001|1|" (sped/rec-0001 false)))
  (is (= "|0001|0|" (sped/rec-0001 true))))

;; ============================================================================
;; Bloco C records
;; ============================================================================

(deftest rec-c100-header
  (let [r (sped/rec-c100
           {:partner-code "CUST-001"
            :series 1 :number 1
            :chave-de-acesso "35260112345678000100550010000000011234567890"
            :doc-date (java.time.LocalDate/of 2026 1 15)
            :in-out-date (java.time.LocalDate/of 2026 1 15)
            :total (brl "1399.75")
            :merch-value (brl "1000.00")
            :icms-base (brl "1100.00")
            :icms (brl "198.00")
            :ipi (brl "100.00")
            :pis (brl "18.15")
            :cofins (brl "83.60")})]
    (is (str/starts-with? r "|C100|"))
    (is (str/includes? r "35260112345678000100550010000000011234567890"))
    (is (str/includes? r "198,00"))
    (is (str/includes? r "1399,75"))))

(deftest rec-c170-line-item-with-tax-breakdown
  (let [r (sped/rec-c170
           {:item-no "1" :item-code "PROD-001"
            :description "Widget A"
            :quantity (brl "10.00") :unit "UN" :value (brl "1000.00")
            :cst-icms "00" :cfop "5102"
            :icms-base (brl "1100.00") :icms-rate (brl "18.00") :icms (brl "198.00")
            :cst-ipi "00" :enq "999"
            :ipi-base (brl "1000.00") :ipi-rate (brl "10.00") :ipi (brl "100.00")
            :cst-pis "01"
            :pis-base (brl "1100.00") :pis-rate-pct (brl "1.65") :pis (brl "18.15")
            :cst-cofins "01"
            :cofins-base (brl "1100.00") :cofins-rate-pct (brl "7.60") :cofins (brl "83.60")})]
    (is (str/starts-with? r "|C170|"))
    (testing "All four BR taxes' amounts present"
      (is (str/includes? r "198,00")
          "ICMS amount")
      (is (str/includes? r "100,00")
          "IPI amount")
      (is (str/includes? r "18,15")
          "PIS amount")
      (is (str/includes? r "83,60")
          "COFINS amount"))))

;; ============================================================================
;; File emit
;; ============================================================================

;; ============================================================================
;; Bloco 0 additional records
;; ============================================================================

(deftest rec-0150-partner-registry
  (let [r (sped/rec-0150 {:code "CUST-001"
                          :name "Beta Comércio Ltda"
                          :country-code "1058"
                          :cnpj "98765432000100"
                          :state-tax-id "9876543210"
                          :municipality-code "3550308"
                          :street "Av. Paulista"
                          :number "1000"
                          :neighborhood "Bela Vista"})]
    (is (str/starts-with? r "|0150|"))
    (is (str/includes? r "CUST-001"))
    (is (str/includes? r "98765432000100"))
    (is (str/includes? r "Beta Comércio Ltda"))))

(deftest rec-0190-unit-of-measure
  (let [r (sped/rec-0190 {:unit "UN" :description "Unidade"})]
    (is (= "|0190|UN|Unidade|" r))))

(deftest rec-0200-item-registry
  (let [r (sped/rec-0200 {:code "PROD-001"
                          :description "Widget A"
                          :inventory-unit "UN"
                          :ncm "84799090"
                          :icms-rate (brl "18.00")})]
    (is (str/starts-with? r "|0200|"))
    (is (str/includes? r "PROD-001"))
    (is (str/includes? r "84799090"))
    (is (str/includes? r "18,00"))))

;; ============================================================================
;; Bloco C analytical totals
;; ============================================================================

(deftest rec-c190-analytical-totals
  (testing "C190 aggregates by (CST_ICMS, CFOP, ALIQ_ICMS)"
    (let [r (sped/rec-c190 {:cst-icms "00"
                            :cfop "5102"
                            :icms-rate (brl "18.00")
                            :operation-value (brl "1000.00")
                            :icms-base (brl "1100.00")
                            :icms (brl "198.00")
                            :ipi (brl "100.00")})]
      (is (str/starts-with? r "|C190|"))
      (is (str/includes? r "5102"))
      (is (str/includes? r "1100,00"))
      (is (str/includes? r "198,00")))))

;; ============================================================================
;; Bloco E apuração (the monthly ICMS settlement record)
;; ============================================================================

(deftest rec-e001
  (is (= "|E001|0|" (sped/rec-e001 true)))
  (is (= "|E001|1|" (sped/rec-e001 false))))

(deftest rec-e100-period
  (let [r (sped/rec-e100 {:period-start (java.time.LocalDate/of 2026 1 1)
                          :period-end   (java.time.LocalDate/of 2026 1 31)})]
    (is (= "|E100|01012026|31012026|" r))))

(deftest rec-e110-apuracao-totals
  (testing "ICMS period apuração: 198 total debit, 70 total credit,
            balance 128, ICMS due 128"
    (let [r (sped/rec-e110 {:tot-debits (brl "198.00")
                            :tot-credits (brl "70.00")
                            :sld-apurado (brl "128.00")
                            :icms-recolher (brl "128.00")})]
      (is (str/starts-with? r "|E110|"))
      (is (str/includes? r "198,00") "Total debits")
      (is (str/includes? r "70,00")  "Total credits")
      (is (str/includes? r "128,00") "ICMS payable"))))

(deftest rec-e116-payment-obligation
  (let [r (sped/rec-e116 {:obligation-code "000"
                          :amount (brl "128.00")
                          :due-date (java.time.LocalDate/of 2026 2 15)
                          :receipt-code "1058"
                          :reference-month "012026"})]
    (is (str/starts-with? r "|E116|"))
    (is (str/includes? r "128,00"))
    (is (str/includes? r "15022026") "Due date 15/02/2026")))

(deftest rec-e990-closing
  (is (= "|E990|7|" (sped/rec-e990 7))))

;; ============================================================================
;; File emit
;; ============================================================================

(deftest emit-file-with-line-endings
  (let [lines [(sped/rec-0000 {:period-start (java.time.LocalDate/of 2026 1 1)
                               :period-end (java.time.LocalDate/of 2026 1 31)
                               :company-name "Acme" :cnpj "12345678000195"
                               :state "SP" :state-tax-id "1234567890"
                               :municipality-code 3550308})
               (sped/rec-0001 false)
               (sped/rec-9999 2)]
        f (sped/emit-file lines)]
    (is (str/includes? f "\r\n")
        "Canonical SPED uses \\r\\n")
    (is (str/includes? f "|0000|"))
    (is (str/includes? f "|0001|"))
    (is (str/includes? f "|9999|"))))
