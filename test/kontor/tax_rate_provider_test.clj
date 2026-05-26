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
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro" :kontor.commodity/precision 2}
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

(def ^:private eur [:kontor.commodity/symbol "EUR"])
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

;; ============================================================================
;; G1 (reverse charge) + G4 (withholding) — ADR-071 addendum / research note 101
;; ============================================================================

(defn- acct [conn path]
  (d/q '[:find ?a . :in $ ?p :where [?a :account/path ?p]] (d/db conn) path))

(defn- fresh-mechanism-db
  "A db with reverse-charge taxes (country \"RC\", buyer + seller side)
   and a withholding tax (country \"WH\", seller side — the MX
   retención shape). Distinct country codes keep each `rate-facts`
   query to exactly one component."
  []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro" :kontor.commodity/precision 2}
                 {:account/path "Assets:Input-VAT"       :account/type :asset}
                 {:account/path "Liabilities:Output-VAT" :account/type :liability}
                 {:account/path "Assets:WH-Receivable"   :account/type :asset}
                 ;; reverse charge needs a :tax-group with BOTH accounts
                 {:db/id "rc-grp" :tax-group/name "RC VAT" :tax-group/country-code "RC"
                  :tax-group/payable-account    [:account/path "Liabilities:Output-VAT"]
                  :tax-group/receivable-account [:account/path "Assets:Input-VAT"]}
                 {:db/id "t-rc-pur" :tax/code "RC-19-PUR" :tax/name "RC 19% (purchase)"
                  :tax/country-code "RC" :tax/type-tax-use :purchase
                  :tax/amount-type :percent :tax/amount 0.19M
                  :tax/recoverable? true :tax/active true
                  :tax/mechanism :reverse-charge :tax/tax-group "rc-grp"}
                 {:db/id "t-rc-sale" :tax/code "RC-19-SALE" :tax/name "RC 19% (sale)"
                  :tax/country-code "RC" :tax/type-tax-use :sale
                  :tax/amount-type :percent :tax/amount 0.19M
                  :tax/recoverable? true :tax/active true
                  :tax/mechanism :reverse-charge :tax/tax-group "rc-grp"}
                 {:db/id "t-wh" :tax/code "WH-ISR-10" :tax/name "Withholding ISR 10%"
                  :tax/country-code "WH" :tax/type-tax-use :sale
                  :tax/amount-type :percent :tax/amount 0.10M
                  :tax/recoverable? false :tax/active true
                  :tax/mechanism :withholding}
                 {:tax-rep/tax "t-wh" :tax-rep/document-type :invoice
                  :tax-rep/repartition-type :tax :tax-rep/factor-percent 100M
                  :tax-rep/account [:account/path "Assets:WH-Receivable"]
                  :tax-rep/sequence 0}])
    conn))

;; --- G1 reverse charge -------------------------------------------------------

(deftest reverse-charge-buyer-side-emits-both-legs
  (let [conn  (fresh-mechanism-db)
        prov  (trp/make-static-table-provider conn)
        bld   (tpb/make-static-table-posting-builder conn)
        facts (trp/rate-facts prov {:base 1000 :commodity eur
                                    :country-code "RC" :tax-use :purchase :at at})
        in-vat  (acct conn "Assets:Input-VAT")
        out-vat (acct conn "Liabilities:Output-VAT")]
    (is (= :reverse-charge (:kind (first (:components facts)))))
    (let [ps (tpb/tax-postings bld facts {})
          by-acct (fn [a] (some #(when (= a (:posting/account %)) %) ps))]
      (is (= 2 (count ps)) "buyer self-assesses both halves")
      (is (== 190M  (:posting/amount (by-acct in-vat)))  "Dr input-VAT")
      (is (== -190M (:posting/amount (by-acct out-vat))) "Cr output-VAT")
      (is (zero? (reduce + (map :posting/amount ps))) "the pair self-nets"))
    (is (== 0M (trp/net-tax-effect facts)) "reverse charge is cash-neutral")))

(deftest reverse-charge-seller-side-emits-no-tax-leg
  (let [conn  (fresh-mechanism-db)
        prov  (trp/make-static-table-provider conn)
        bld   (tpb/make-static-table-posting-builder conn)
        facts (trp/rate-facts prov {:base 1000 :commodity eur
                                    :country-code "RC" :tax-use :sale :at at})]
    (is (= :reverse-charge (:kind (first (:components facts)))))
    (is (= [] (tpb/tax-postings bld facts {}))
        "seller side: VAT-return marker only, no GL tax leg")))

;; --- G4 withholding ----------------------------------------------------------

(deftest withholding-posts-as-a-contra
  (let [conn  (fresh-mechanism-db)
        prov  (trp/make-static-table-provider conn)
        bld   (tpb/make-static-table-posting-builder conn)
        facts (trp/rate-facts prov {:base 1000 :commodity eur
                                    :country-code "WH" :tax-use :sale :at at})
        wh-recv (acct conn "Assets:WH-Receivable")]
    (is (= :withholding (:kind (first (:components facts)))))
    (let [ps (tpb/tax-postings bld facts {})]
      (is (= 1 (count ps)))
      (is (= wh-recv (:posting/account (first ps))))
      (is (== 100M (:posting/amount (first ps)))
          "sale → withholding is a debit to a receivable (+) — inverted from VAT"))
    (testing "withholding nets the cash leg DOWN"
      (is (== 100M  (trp/withheld-total facts)))
      (is (== 0M    (trp/additive-total facts)))
      (is (== -100M (trp/net-tax-effect facts))))))

(deftest withholding-invoice-balances-end-to-end
  ;; note 101 §5: a withholding sale must still land in Ker σ once the
  ;; consumer assembles base legs sized by net-tax-effect.
  (let [conn   (fresh-mechanism-db)
        prov   (trp/make-static-table-provider conn)
        bld    (tpb/make-static-table-posting-builder conn)
        net    1000M
        facts  (trp/rate-facts prov {:base net :commodity eur
                                     :country-code "WH" :tax-use :sale :at at})
        tax-ps (tpb/tax-postings bld facts {})
        ar-leg (+ net (trp/net-tax-effect facts))
        base-ps [{:posting/amount ar-leg}       ;; Dr Accounts-Receivable
                 {:posting/amount (- net)}]     ;; Cr Revenue
        all     (concat base-ps tax-ps)]
    (is (== 900M ar-leg) "AR = net 1000 − withholding 100")
    (is (zero? (reduce + (map :posting/amount all)))
        "base + tax legs sum to zero (Ker σ)")))

;; --- the netting helpers + the closed-vocabulary check -----------------------

(deftest netting-helpers-on-a-mixed-tax-facts
  ;; an MX-style retención sale: output VAT 160 (additive) + retención 106.67
  (let [facts (trp/tax-facts
               {:tax-use :sale :line-base 1000M :commodity eur
                :components [{:kind :output-vat  :amount 160M}
                             {:kind :withholding :amount 106.67M}]})]
    (is (== 160M    (trp/additive-total facts)))
    (is (== 106.67M (trp/withheld-total facts)))
    (is (== 53.33M  (trp/net-tax-effect facts)) "AR leg adjustment = net + 53.33")
    (is (== 266.67M (trp/total-tax facts)) "total-tax still sums the gross notional")))

(deftest valid-tax-facts-is-the-closed-vocabulary-check
  (is (true?  (trp/valid-tax-facts?
               (trp/tax-facts {:components [{:kind :output-vat :amount 19M}]}))))
  (is (false? (trp/valid-tax-facts?
               (trp/tax-facts {:components [{:kind :not-a-real-kind :amount 19M}]})))
      "a :kind outside the closed set fails — the signal to extend the enum by ADR")
  (is (false? (trp/valid-tax-facts?
               (trp/tax-facts {:components [{:kind :output-vat :amount 19.0}]})))
      "a non-BigDecimal :amount fails the structural check"))
