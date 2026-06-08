(ns kontor.l10n-us.tax-test
  "Tests for kontor.l10n-us.tax — provider-pluggable per-line sales-
   tax compute.

   Rate-table fixture covers 5 US jurisdictional flavours:
     - CA   destination-based for most local rates
     - NY   destination-based, with clothing zero-rated under $110
     - TX   origin-based for in-state sellers
     - WA   destination-based + SST member
     - OR   no sales tax at all"
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-us.tax :as tax]
            [kontor.money :as money]))

(defn- m [v] (money/money (bigdec v) :USD))

;; Hand-crafted rate table for tests. NOT representative of real
;; combined rates (which vary by ZIP+4 and product class). For
;; production use the rates come from Avalara / TaxJar / SST.
(def fixture-rates
  {[:CA :default]   0.0725M
   [:NY :default]   0.08875M    ; NYC combined (NY state 4% + NYC 4.5% + MCTD 0.375%)
   [:NY :clothing]  0.0M         ; Clothing under $110 zero-rated in NY
   [:TX :default]   0.0625M      ; TX state base — local 2% on top usually
   [:WA :default]   0.065M       ; WA state portion only
   [:OR :default]   0M})         ; Oregon has no sales tax

;; ============================================================================
;; combined-rate — sum decomposed authorities
;; ============================================================================

(deftest combined-rate-sums-components
  (testing "Sum the per-authority decomposition into one effective rate.
            Denver-style: CO state 2.9% + Denver 4.81% + RTD 1.1%
            = 8.81% combined."
    (is (= 0.0881M
           (tax/combined-rate {:state 0.029M
                               :city  0.0481M
                               :rtd   0.011M})))
    (is (= 0.0871M
           (tax/combined-rate {:state 0.029M
                               :city  0.0581M
                               :rtd   0M}))
        "Two-component sum")
    (is (= 0.0625M
           (tax/combined-rate {:state 0.0625M})))
    (is (= 0M
           (tax/combined-rate {})))))

;; ============================================================================
;; compute-tax — explicit :rate path
;; ============================================================================

(deftest compute-tax-explicit-rate
  (testing "Caller pre-resolved the rate (Avalara / TaxJar returned it)"
    (let [r (tax/compute-tax {:line 1000M :state :CA :rate 0.0725M})]
      (is (= 0.0725M (:tax-rate r)))
      (is (money/equiv? (m "72.50")   (:tax-amount r)))
      (is (money/equiv? (m "1000.00") (:net r)))
      (is (money/equiv? (m "1072.50") (:total-gross r)))
      (is (= :CA (:state r)))
      (is (= :taxable (:tax-status r))))))

(deftest compute-tax-zero-rate-no-state-tax
  (testing "Oregon: rate is 0M, total-gross equals net"
    (let [r (tax/compute-tax {:line 500M :state :OR :rate 0M})]
      (is (money/equiv? (m "0.00")   (:tax-amount r)))
      (is (money/equiv? (m "500.00") (:total-gross r))))))

(deftest compute-tax-with-jurisdictions-payload
  (testing "Caller-supplied per-authority breakdown is preserved
            on the return for downstream filing-side splits"
    (let [jur [{:authority :us-co-state  :rate 0.029M  :amount (m "29.00")}
               {:authority :us-co-denver :rate 0.0581M :amount (m "58.10")}]
          r (tax/compute-tax {:line 1000M :state :CO :rate 0.0881M
                              :jurisdictions jur})]
      (is (money/equiv? (m "88.10") (:tax-amount r)))
      (is (= jur (:tax-jurisdictions r))))))

;; ============================================================================
;; compute-tax — :rate-table path
;; ============================================================================

(deftest compute-tax-rate-table-default-class
  (testing "Lookup by [state :default] when no product-class provided"
    (let [r (tax/compute-tax {:line 1000M :state :CA
                              :rate-table fixture-rates})]
      (is (= 0.0725M (:tax-rate r)))
      (is (money/equiv? (m "72.50") (:tax-amount r))))))

(deftest compute-tax-rate-table-product-class
  (testing "NY clothing has a 0% rate for items under $110.
            The lookup picks [:NY :clothing] over [:NY :default]."
    (let [r (tax/compute-tax {:line 100M :state :NY
                              :rate-table fixture-rates
                              :product-class :clothing})]
      (is (= 0M (:tax-rate r)))
      (is (money/equiv? (m "0.00") (:tax-amount r))))
    (testing "but :default (non-clothing) still gets the combined rate"
      (let [r (tax/compute-tax {:line 100M :state :NY
                                :rate-table fixture-rates})]
        (is (= 0.08875M (:tax-rate r)))
        (is (money/equiv? (m "8.88") (:tax-amount r)))))))

(deftest compute-tax-rate-table-fallback-to-default
  (testing "Unknown product-class falls back to [:state :default]"
    (let [r (tax/compute-tax {:line 1000M :state :TX
                              :rate-table fixture-rates
                              :product-class :something-unmapped})]
      (is (= 0.0625M (:tax-rate r)))
      (is (money/equiv? (m "62.50") (:tax-amount r))))))

(deftest compute-tax-rate-table-missing-state
  (testing "State with no rate-table entry throws — no surprise zeroes"
    (is (thrown? clojure.lang.ExceptionInfo
                 (tax/compute-tax {:line 100M :state :IL
                                   :rate-table fixture-rates})))))

(deftest compute-tax-no-rate-source-throws
  (testing "Neither :rate nor :rate-table → explicit error"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #":rate or :rate-table"
         (tax/compute-tax {:line 100M :state :CA})))))

;; ============================================================================
;; compute-tax — tax-status overrides
;; ============================================================================

(deftest compute-tax-resale-certificate
  (testing "B2B reseller presents a resale certificate — no tax
            regardless of the resolved rate. The reseller will collect
            and remit when they sell to the end consumer."
    (let [r (tax/compute-tax {:line 1000M :state :CA :rate 0.0725M
                              :tax-status :resale})]
      (is (= 0M (:tax-rate r)))
      (is (money/equiv? (m "0.00")   (:tax-amount r)))
      (is (money/equiv? (m "1000.00") (:total-gross r)))
      (is (= :resale (:tax-status r))))))

(deftest compute-tax-exempt-non-profit
  (testing "Tax-exempt entity (501(c)(3), government, school district)
            — no tax. Different reason from :resale but same posting
            shape."
    (let [r (tax/compute-tax {:line 1000M :state :NY :rate 0.08875M
                              :tax-status :exempt})]
      (is (money/equiv? (m "0.00") (:tax-amount r)))
      (is (= :exempt (:tax-status r))))))

(deftest compute-tax-non-taxable-product
  (testing "Per-product carve-out (unprepared food in many states)"
    (let [r (tax/compute-tax {:line 50M :state :CA :rate 0.0725M
                              :tax-status :non-taxable-product})]
      (is (money/equiv? (m "0.00") (:tax-amount r)))
      (is (= :non-taxable-product (:tax-status r))))))

;; ============================================================================
;; Input coercion + validation
;; ============================================================================

(deftest compute-tax-accepts-money-and-number-line
  (let [a (tax/compute-tax {:line 100M     :state :CA :rate 0.0725M})
        b (tax/compute-tax {:line 100      :state :CA :rate 0.0725M})
        c (tax/compute-tax {:line (m "100") :state :CA :rate 0.0725M})]
    (is (money/equiv? (m "7.25") (:tax-amount a)))
    (is (money/equiv? (m "7.25") (:tax-amount b)))
    (is (money/equiv? (m "7.25") (:tax-amount c)))))

(deftest compute-tax-rejects-non-keyword-state
  (is (thrown? clojure.lang.ExceptionInfo
               (tax/compute-tax {:line 100 :state "CA" :rate 0.0725M}))
      "String state code rejected — must be a keyword"))

(deftest compute-tax-rejects-bad-status
  (is (thrown? clojure.lang.ExceptionInfo
               (tax/compute-tax {:line 100 :state :CA :rate 0.0725M
                                 :tax-status :something-else}))))

;; ============================================================================
;; compute-invoice-tax — multi-line aggregation
;; ============================================================================

(deftest compute-invoice-tax-aggregates
  (testing "Multi-line invoice: one taxable + one resale. Aggregate
            equals sum of per-line rounded values."
    (let [r (tax/compute-invoice-tax
             {:state :CA
              :rate-table fixture-rates
              :lines [{:line 1000M}                       ; taxable
                      {:line 500M :tax-status :resale}]}) ; resale-cert
          ]
      (is (money/equiv? (m "72.50")   (:tax-amount r)))
      (is (money/equiv? (m "1500.00") (:net r)))
      (is (money/equiv? (m "1572.50") (:total-gross r)))
      (is (= :CA (:state r)))
      (is (= 2 (count (:per-line r)))))))

(deftest compute-invoice-tax-mixed-product-classes
  (testing "NY invoice: clothing line is zero-rated, default-class
            line picks up the combined rate"
    (let [r (tax/compute-invoice-tax
             {:state :NY
              :rate-table fixture-rates
              :lines [{:line 100M :product-class :clothing}
                      {:line 100M}]})]
      (is (money/equiv? (m "8.88")   (:tax-amount r))
          "Only the non-clothing line accrues tax (100 × 0.08875 = 8.875 → 8.88 HALF-EVEN)")
      (is (money/equiv? (m "200.00") (:net r))))))

(deftest compute-invoice-tax-per-line-rate-overrides-table
  (testing "Per-line :rate wins over the top-level :rate-table"
    (let [r (tax/compute-invoice-tax
             {:state :CA
              :rate-table fixture-rates
              :lines [{:line 1000M :rate 0.09M}]})]   ; line says 9%
      (is (= 0.09M (-> r :per-line first :tax-rate)))
      (is (money/equiv? (m "90.00") (:tax-amount r))))))

(deftest compute-invoice-tax-multi-jurisdiction-via-combined-rate
  (testing "Caller decomposes the combined rate into authorities and
            passes the combined number on a per-line basis. The
            substrate aggregates without inspecting decomposition.
            (Denver-style: CO state 2.9% + Denver 4.81% + RTD 1.1%
            = 8.81%.)"
    (let [denver-combined (tax/combined-rate {:state 0.029M
                                              :city  0.0481M
                                              :rtd   0.011M})
          r (tax/compute-invoice-tax
             {:state :CO
              :lines [{:line 1000M :rate denver-combined}]})]
      (is (= 0.0881M denver-combined))
      ;; 1000 × 0.0881 = 88.10
      (is (money/equiv? (m "88.10") (:tax-amount r))))))

;; ============================================================================
;; SST member set + NOMAD set
;; ============================================================================

(deftest sst-states-shape
  (testing "SST set has ~24 members (SSUTA Article II)"
    (is (>= (count tax/sst-states) 22))
    (is (<= (count tax/sst-states) 26))
    (testing "Representative member checks"
      (is (contains? tax/sst-states :WA) "WA is full member")
      (is (contains? tax/sst-states :OH) "OH is full member")
      (is (contains? tax/sst-states :MI) "MI is full member"))
    (testing "Non-member states excluded"
      (is (not (contains? tax/sst-states :CA)))
      (is (not (contains? tax/sst-states :NY)))
      (is (not (contains? tax/sst-states :TX))))))

(deftest states-without-state-sales-tax-shape
  (testing "The five NOMAD-extended states (AK with local-only,
            DE, MT, NH, OR)"
    (is (= #{:AK :DE :MT :NH :OR} tax/states-without-state-sales-tax))))
