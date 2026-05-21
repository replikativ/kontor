(ns kontor.tax-rate-provider-test
  "Stage 2 of research note 99 — the ADR-071 tax substrate trio:
   `TaxRateProvider` → `TaxFacts` → `TaxPostingBuilder`. Acceptance
   criterion: a `StaticTableProvider` resolves a real tax line off the
   `:tax/*` schema, and the trio composes end-to-end."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.tax-posting-builder :as tpb]
            [kontor.tax-rate-provider :as trp]))

;; ============================================================================
;; Fixture — a DE VAT chart: one sale tax, one purchase tax, one expired
;; ============================================================================

(defn- fresh-tax-db []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:commodity/symbol "EUR" :commodity/name "Euro" :commodity/precision 2}
                 {:account/path "Income:Sales"           :account/type :income}
                 {:account/path "Expenses:Supplies"      :account/type :expense}
                 {:account/path "Liabilities:VAT-Payable" :account/type :liability}
                 {:account/path "Assets:VAT-Receivable"  :account/type :asset}
                 {:db/id "grp" :tax-group/name "DE VAT" :tax-group/country-code "DE"}
                 ;; current sale tax — DE VAT 19%
                 {:db/id "t-sale" :tax/code "DE-VAT-19-SALE" :tax/name "DE VAT 19% (sale)"
                  :tax/country-code "DE" :tax/type-tax-use :sale
                  :tax/amount-type :percent :tax/amount 0.19M
                  :tax/recoverable? true :tax/active true :tax/tax-group "grp"}
                 {:tax-rep/tax "t-sale" :tax-rep/document-type :invoice
                  :tax-rep/repartition-type :tax :tax-rep/factor-percent 100M
                  :tax-rep/account [:account/path "Liabilities:VAT-Payable"]
                  :tax-rep/sequence 0}
                 ;; current purchase tax — DE VAT 19% input
                 {:db/id "t-pur" :tax/code "DE-VAT-19-PUR" :tax/name "DE VAT 19% (purchase)"
                  :tax/country-code "DE" :tax/type-tax-use :purchase
                  :tax/amount-type :percent :tax/amount 0.19M
                  :tax/recoverable? true :tax/active true :tax/tax-group "grp"}
                 {:tax-rep/tax "t-pur" :tax-rep/document-type :invoice
                  :tax-rep/repartition-type :tax :tax-rep/factor-percent 100M
                  :tax-rep/account [:account/path "Assets:VAT-Receivable"]
                  :tax-rep/sequence 0}
                 ;; expired sale tax — 16%, ended 2020 — must be filtered out
                 {:db/id "t-old" :tax/code "DE-VAT-16-OLD" :tax/name "DE VAT 16% (expired)"
                  :tax/country-code "DE" :tax/type-tax-use :sale
                  :tax/amount-type :percent :tax/amount 0.16M
                  :tax/recoverable? true :tax/active true :tax/tax-group "grp"
                  :tax/effective-until #inst "2020-01-01"}])
    conn))

(def ^:private eur [:commodity/symbol "EUR"])
(def ^:private at  #inst "2026-03-15")

;; ============================================================================
;; TaxRateProvider — StaticTableProvider resolves a real tax line
;; ============================================================================

(deftest static-provider-resolves-a-sale-line
  (let [conn (fresh-tax-db)
        prov (trp/make-static-table-provider conn)
        facts (trp/rate-facts prov {:base 1000 :commodity eur
                                    :country-code "DE" :tax-use :sale :at at})]
    (is (trp/taxable? facts))
    (is (= :sale (:tax-use facts)))
    (testing "the expired 16% tax is filtered out — exactly one component"
      (is (= 1 (count (:components facts)))))
    (let [c (first (:components facts))]
      (is (= :output-vat (:kind c)))
      (is (= "DE-VAT-19-SALE" (:tax-code c)))
      (is (== 0.19M (:rate c)))
      (is (== 190M (:amount c)) "1000 base × 19%")
      (is (true? (:recoverable? c)))
      (is (= :static-table (get-in c [:provenance :provider-id]))))
    (is (== 190M (trp/total-tax facts)))))

(deftest static-provider-resolves-a-purchase-line
  (let [conn (fresh-tax-db)
        prov (trp/make-static-table-provider conn)
        facts (trp/rate-facts prov {:base 500 :commodity eur
                                    :country-code "DE" :tax-use :purchase :at at})]
    (is (trp/taxable? facts))
    (let [c (first (:components facts))]
      (is (= :input-vat (:kind c)))
      (is (== 95M (:amount c)) "500 base × 19%"))))

(deftest static-provider-returns-nil-when-no-tax-applies
  (let [conn (fresh-tax-db)
        prov (trp/make-static-table-provider conn)]
    (testing "a country with no :tax rows"
      (is (nil? (trp/rate-facts prov {:base 1000 :commodity eur
                                      :country-code "US" :tax-use :sale :at at}))))
    (testing ":tax-use :none"
      (is (nil? (trp/rate-facts prov {:base 1000 :commodity eur
                                      :country-code "DE" :tax-use :none :at at}))))))

(deftest provider-id-and-scaffolds
  (let [conn (fresh-tax-db)]
    (is (= :static-table (trp/provider-id (trp/make-static-table-provider conn))))
    (testing "the paid-API scaffolds throw a clear, key-free error"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"API key"
                            (trp/rate-facts (trp/->AvalaraProvider nil {}) {}))))))

;; ============================================================================
;; TaxPostingBuilder — TaxFacts → GL postings, with the right sign
;; ============================================================================

(deftest posting-builder-materializes-sale-tax-as-a-credit
  (let [conn  (fresh-tax-db)
        prov  (trp/make-static-table-provider conn)
        bld   (tpb/make-static-table-posting-builder conn)
        facts (trp/rate-facts prov {:base 1000 :commodity eur
                                    :country-code "DE" :tax-use :sale :at at})
        ps    (tpb/tax-postings bld facts {})
        vat-payable (d/q '[:find ?a . :where
                           [?a :account/path "Liabilities:VAT-Payable"]]
                         (d/db conn))]
    (is (= 1 (count ps)))
    (let [p (first ps)]
      (is (= vat-payable (:posting/account p)))
      (is (== -190M (:posting/amount p)) "sale → output VAT is a credit (negative)")
      (is (= :tax (:posting/display-type p)))
      (is (= eur (:posting/commodity p)))
      (is (== 1000M (:posting/tax-base p))))))

(deftest posting-builder-materializes-purchase-tax-as-a-debit
  (let [conn  (fresh-tax-db)
        prov  (trp/make-static-table-provider conn)
        bld   (tpb/make-static-table-posting-builder conn)
        facts (trp/rate-facts prov {:base 500 :commodity eur
                                    :country-code "DE" :tax-use :purchase :at at})
        ps    (tpb/tax-postings bld facts {})]
    (is (== 95M (:posting/amount (first ps)))
        "purchase → recoverable input VAT is a debit (positive)")))

;; ============================================================================
;; The trio composes end-to-end
;; ============================================================================

(deftest compute-tax-postings-runs-the-whole-trio
  (let [conn (fresh-tax-db)
        prov (trp/make-static-table-provider conn)
        bld  (tpb/make-static-table-posting-builder conn)]
    (testing "a taxable line yields the tax postings"
      (let [ps (tpb/compute-tax-postings prov bld
                                         {:base 1000 :commodity eur
                                          :country-code "DE" :tax-use :sale :at at})]
        (is (= 1 (count ps)))
        (is (== -190M (:posting/amount (first ps))))))
    (testing "a non-taxable line yields []"
      (is (= [] (tpb/compute-tax-postings prov bld
                                          {:base 1000 :commodity eur
                                           :country-code "US" :tax-use :sale :at at}))))))
