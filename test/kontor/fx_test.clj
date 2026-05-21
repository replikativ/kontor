(ns kontor.fx-test
  "Tests for kontor.fx-rate-provider + kontor.fx.

   The substrate ships no rates; these tests install a small in-DB
   sample set (EUR/USD/GBP at a handful of dates) and exercise:
     - identity short-circuit
     - exact date hit
     - last-on-or-before fallback
     - inverse derivation
     - triangulation via a base commodity
     - period-rates bulk fetch
     - Money-level convert + translate-amounts-by-commodity
     - functional-currency rebase
     - ChainedProvider composition
     - error semantics on missing rate"
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.fx :as fx]
            [kontor.fx-rate-provider :as fxp]
            [kontor.money :as money]))

(def jan-1  #inst "2026-01-01T00:00:00Z")
(def jan-2  #inst "2026-01-02T00:00:00Z")
(def jan-5  #inst "2026-01-05T00:00:00Z")
(def jan-15 #inst "2026-01-15T00:00:00Z")
(def jan-31 #inst "2026-01-31T00:00:00Z")
(def feb-1  #inst "2026-02-01T00:00:00Z")

(defn- bootstrap-commodities! [conn]
  (d/transact conn [{:commodity/symbol "EUR" :commodity/name "Euro"
                     :commodity/precision 2 :commodity/iso-4217 "EUR"}
                    {:commodity/symbol "USD" :commodity/name "US Dollar"
                     :commodity/precision 2 :commodity/iso-4217 "USD"}
                    {:commodity/symbol "GBP" :commodity/name "Pound Sterling"
                     :commodity/precision 2 :commodity/iso-4217 "GBP"}
                    {:commodity/symbol "JPY" :commodity/name "Yen"
                     :commodity/precision 0 :commodity/iso-4217 "JPY"}])
  conn)

(defn- seed-rates! [conn]
  (fxp/save-rates!
   conn
   [;; EUR → USD direct samples on jan-2 and jan-15
    {:from "EUR" :to "USD" :at-date jan-2  :rate 1.08M :source :test}
    {:from "EUR" :to "USD" :at-date jan-15 :rate 1.10M :source :test}
    ;; EUR → GBP only on jan-2 — used to test triangulation USD→EUR→GBP
    {:from "EUR" :to "GBP" :at-date jan-2  :rate 0.85M :source :test}
    {:from "EUR" :to "GBP" :at-date jan-15 :rate 0.86M :source :test}
    ;; closing-rate sample for IAS-21 BS items
    {:from "EUR" :to "USD" :at-date jan-31 :rate 1.12M
     :rate-type :closing :source :test}])
  conn)

(defn- setup []
  (-> (core/create-test-db)
      bootstrap-commodities!
      seed-rates!))

;; ============================================================================
;; FxRateProvider — StaticTableProvider
;; ============================================================================

(deftest static-table-identity
  (testing "from = to short-circuits to 1M without any lookup"
    (let [conn (setup)
          p    (fxp/make-static-table-provider conn)]
      (is (= 1M (fxp/resolve-rate p {:from-commodity "EUR" :to-commodity "EUR"
                                     :at-date jan-2}))))))

(deftest static-table-exact-hit
  (testing "Direct (from, to, date, type) lookup returns the sample"
    (let [conn (setup)
          p    (fxp/make-static-table-provider conn)]
      (is (= 1.08M (fxp/resolve-rate p {:from-commodity "EUR" :to-commodity "USD"
                                        :at-date jan-2})))
      (is (= 1.10M (fxp/resolve-rate p {:from-commodity "EUR" :to-commodity "USD"
                                        :at-date jan-15}))))))

(deftest static-table-fallback-on-or-before
  (testing "Asking for a date with no exact sample returns the most
            recent sample with date ≤ the asked date."
    (let [conn (setup)
          p    (fxp/make-static-table-provider conn)]
      (is (= 1.08M (fxp/resolve-rate p {:from-commodity "EUR" :to-commodity "USD"
                                        :at-date jan-5}))   ;; falls back to jan-2
          "jan-5 has no sample — should return jan-2's 1.08")
      (is (= 1.10M (fxp/resolve-rate p {:from-commodity "EUR" :to-commodity "USD"
                                        :at-date feb-1}))
          "feb-1 has no sample — should return jan-15's 1.10"))))

(deftest static-table-strict-mode-no-fallback
  (testing "When :fallback-on-or-before? is false, only exact hits resolve."
    (let [conn (setup)
          strict (fxp/make-static-table-provider
                  conn {:fallback-on-or-before? false :allow-inverse? false})]
      (is (= 1.08M (fxp/resolve-rate strict {:from-commodity "EUR"
                                             :to-commodity   "USD"
                                             :at-date        jan-2})))
      (is (nil? (fxp/resolve-rate strict {:from-commodity "EUR"
                                          :to-commodity   "USD"
                                          :at-date        jan-5}))))))

(deftest static-table-inverse
  (testing "USD→EUR derives from the EUR→USD sample as 1/rate (12-digit half-even)"
    (let [conn (setup)
          p    (fxp/make-static-table-provider conn)
          inv  (fxp/resolve-rate p {:from-commodity "USD" :to-commodity "EUR"
                                    :at-date jan-2})]
      ;; 1 / 1.08 = 0.925925925926 at 12 digits half-even
      (is (some? inv))
      (is (= 12 (.scale ^java.math.BigDecimal inv))
          "12-digit precision to avoid rounding noise in chained ops")
      (is (= (.divide java.math.BigDecimal/ONE 1.08M 12
                      java.math.RoundingMode/HALF_EVEN)
             inv)))))

(deftest static-table-disable-inverse
  (testing "With :allow-inverse? false, USD→EUR is nil if no direct sample"
    (let [conn (setup)
          p    (fxp/make-static-table-provider
                conn {:allow-inverse? false :fallback-on-or-before? false})]
      (is (nil? (fxp/resolve-rate p {:from-commodity "USD" :to-commodity "EUR"
                                     :at-date jan-2}))))))

(deftest static-table-triangulation-explicit-via
  (testing "USD → EUR → GBP with explicit :via 'EUR'"
    (let [conn (setup)
          p    (fxp/make-static-table-provider
                conn {:allow-inverse? false :fallback-on-or-before? false})
          ;; To compute USD→GBP via EUR we need USD→EUR (inverse of EUR→USD)
          ;; and EUR→GBP — but we disabled inverse in this provider so
          ;; triangulation should still go from->via then via->to (both direct).
          ;; The seeded data only has EUR→USD and EUR→GBP, so
          ;; USD→EUR requires inverse to be on. Use a provider that allows it:
          p2   (fxp/make-static-table-provider
                conn {:fallback-on-or-before? false})
          r    (fxp/resolve-rate
                p2 {:from-commodity "USD" :to-commodity "GBP"
                    :at-date jan-2 :via "EUR"})]
      ;; (1/1.08) * 0.85 ≈ 0.7870370370... — assert non-nil + ball-park
      (is (some? r))
      (is (< (- 0.7870 (double r)) 0.001)))))

(deftest static-table-rate-type-discrimination
  (testing ":closing and :spot are independent samples on the same date"
    (let [conn (setup)
          p    (fxp/make-static-table-provider
                conn {:fallback-on-or-before? false})]
      ;; jan-31 has only a :closing sample
      (is (nil? (fxp/resolve-rate p {:from-commodity "EUR" :to-commodity "USD"
                                     :at-date jan-31 :rate-type :spot})))
      (is (= 1.12M (fxp/resolve-rate p {:from-commodity "EUR" :to-commodity "USD"
                                        :at-date jan-31 :rate-type :closing}))))))

(deftest static-table-period-rates
  (testing "resolve-period-rates returns date-sorted samples in window"
    (let [conn (setup)
          p    (fxp/make-static-table-provider conn)
          rs   (fxp/resolve-period-rates
                p {:from-commodity "EUR" :to-commodity "USD"
                   :from-date jan-1 :to-date jan-31
                   :rate-type :spot})]
      (is (= 2 (count rs)))
      (is (= [jan-2 jan-15] (mapv :at-date rs))
          "ascending by date")
      (is (= [1.08M 1.10M] (mapv :rate rs))))))

(deftest static-table-upsert-via-identity-tuple
  (testing "Re-transacting the same (from, to, date, type) replaces :rate"
    (let [conn (setup)
          p    (fxp/make-static-table-provider conn)]
      (is (= 1.08M (fxp/resolve-rate p {:from-commodity "EUR" :to-commodity "USD"
                                        :at-date jan-2})))
      (fxp/save-rates! conn [{:from "EUR" :to "USD" :at-date jan-2
                              :rate 1.085M :source :corrected}])
      (is (= 1.085M (fxp/resolve-rate p {:from-commodity "EUR" :to-commodity "USD"
                                         :at-date jan-2}))))))

(deftest static-table-unknown-commodity-errors
  (let [conn (setup)
        p    (fxp/make-static-table-provider conn)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown commodity"
                          (fxp/resolve-rate p {:from-commodity "ZZZ"
                                               :to-commodity   "EUR"
                                               :at-date        jan-2})))))

;; ============================================================================
;; ChainedProvider
;; ============================================================================

(defrecord NilProvider []
  fxp/FxRateProvider
  (provider-id [_] :nil)
  (resolve-rate [_ _] nil)
  (resolve-period-rates [_ _] []))

(defrecord ConstantProvider [r]
  fxp/FxRateProvider
  (provider-id [_] :const)
  (resolve-rate [_ _] r)
  (resolve-period-rates [_ _] []))

(deftest chained-first-non-nil-wins
  (let [chained (fxp/chain (->NilProvider) (->ConstantProvider 2.5M))]
    (is (= 2.5M (fxp/resolve-rate chained {:from-commodity "EUR"
                                           :to-commodity   "USD"
                                           :at-date        jan-2})))))

(deftest chained-all-nil-returns-nil
  (let [chained (fxp/chain (->NilProvider) (->NilProvider))]
    (is (nil? (fxp/resolve-rate chained {:from-commodity "EUR"
                                         :to-commodity   "USD"
                                         :at-date        jan-2})))))

;; ============================================================================
;; ECB provider — adapter shape (no live network)
;; ============================================================================

(deftest ecb-provider-uses-eur-as-default-via
  (testing "ECB provider triangulates through EUR by default"
    (let [conn (setup)
          p    (fxp/make-ecb-reference-rates-provider
                conn {:fallback-on-or-before? false})
          r    (fxp/resolve-rate p {:from-commodity "USD" :to-commodity "GBP"
                                    :at-date jan-2})]
      (is (some? r) "should triangulate USD→EUR→GBP automatically"))))

(deftest ecb-ingest-rows-populates-forward-only
  (testing "Per ADR-072 review P1-72-1, ingest stores ONLY the forward
            EUR→ccy direction. The reverse comes from the provider's
            :allow-inverse? machinery (default true) at lookup time —
            so corrections to the forward rate don't leave the reverse
            stale."
    (let [conn (-> (core/create-test-db)
                   bootstrap-commodities!)]
      (fxp/ingest-ecb-csv-rows!
       conn [{:at-date jan-2
              :rates {"USD" 1.08M "GBP" 0.85M "JPY" 160.5M}}])
      ;; With :allow-inverse? false, USD→EUR returns nil — the reverse
      ;; direction is NOT persisted as its own sample anymore.
      (let [p-strict (fxp/make-ecb-reference-rates-provider
                      conn {:fallback-on-or-before? false :allow-inverse? false})]
        (is (= 1.08M (fxp/resolve-rate p-strict {:from-commodity "EUR" :to-commodity "USD"
                                                 :at-date jan-2}))
            "EUR→USD forward direction is persisted")
        (is (nil? (fxp/resolve-rate p-strict {:from-commodity "USD" :to-commodity "EUR"
                                              :at-date jan-2}))
            "USD→EUR is NOT persisted (came-via-inverse is opt-in)"))
      ;; With :allow-inverse? true (the default), USD→EUR derives from
      ;; the forward sample on demand.
      (let [p (fxp/make-ecb-reference-rates-provider conn)]
        (is (some? (fxp/resolve-rate p {:from-commodity "USD" :to-commodity "EUR"
                                        :at-date jan-2}))
            "USD→EUR derived via :allow-inverse? at lookup time")))))

(deftest p1-72-1-inverse-stays-fresh-after-forward-rate-correction
  (testing "Correcting the forward rate (via save-rates!) immediately
            updates the inverse — the inverse is NOT a stored sample
            that could go stale. Regression for ADR-072 review P1-72-1."
    (let [conn (-> (core/create-test-db) bootstrap-commodities!)]
      (fxp/ingest-ecb-csv-rows!
       conn [{:at-date jan-2 :rates {"USD" 1.08M}}])
      ;; First inverse — should be 1/1.08
      (let [p (fxp/make-ecb-reference-rates-provider conn)
            inv-before (fxp/resolve-rate p {:from-commodity "USD" :to-commodity "EUR"
                                            :at-date jan-2})]
        (is (= (.divide java.math.BigDecimal/ONE 1.08M 12
                        java.math.RoundingMode/HALF_EVEN)
               inv-before)
            "initial inverse = 1/1.08"))
      ;; Customer corrects the forward rate
      (fxp/save-rates! conn [{:from "EUR" :to "USD" :at-date jan-2
                              :rate 1.085M :source :corrected}])
      ;; The inverse must reflect the correction immediately
      (let [p (fxp/make-ecb-reference-rates-provider conn)
            inv-after (fxp/resolve-rate p {:from-commodity "USD" :to-commodity "EUR"
                                           :at-date jan-2})]
        (is (= (.divide java.math.BigDecimal/ONE 1.085M 12
                        java.math.RoundingMode/HALF_EVEN)
               inv-after)
            "after correction, inverse = 1/1.085 (was 1/1.08 before fix)")))))

;; ============================================================================
;; kontor.fx — Money-level operations
;; ============================================================================

(deftest convert-identity-returns-input
  (let [conn (setup)
        p    (fxp/make-static-table-provider conn)
        eur  (money/money 100M "EUR")]
    (is (identical? eur (fx/convert eur p {:to "EUR" :at-date jan-2})))))

(deftest convert-applies-rate-and-rounds-to-2dp
  (let [conn   (setup)
        p      (fxp/make-static-table-provider conn)
        in     (money/money 100M "EUR")
        out    (fx/convert in p {:to "USD" :at-date jan-2})]
    (is (= "USD" (:commodity out)))
    (is (= 108.00M (:amount out)))))

(deftest convert-uses-fallback-when-no-exact-date
  (let [conn (setup)
        p    (fxp/make-static-table-provider conn)
        in   (money/money 100M "EUR")
        out  (fx/convert in p {:to "USD" :at-date jan-5})]
    (is (= 108.00M (:amount out))
        "jan-5 falls back to jan-2's 1.08 rate")))

(deftest convert-with-rate-type-closing
  (let [conn (setup)
        p    (fxp/make-static-table-provider conn)
        out  (fx/convert (money/money 100M "EUR") p
                         {:to "USD" :at-date jan-31 :rate-type :closing})]
    (is (= 112.00M (:amount out)))))

(deftest convert-no-rate-throws
  (let [conn (-> (core/create-test-db) bootstrap-commodities!)
        p    (fxp/make-static-table-provider
              conn {:fallback-on-or-before? false :allow-inverse? false})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"convert: provider returned no rate"
                          (fx/convert (money/money 100M "EUR") p
                                      {:to "USD" :at-date jan-2})))))

(deftest convert-skip-rounding-when-precision-nil
  (let [conn (setup)
        p    (fxp/make-static-table-provider conn)
        out  (fx/convert (money/money 100M "EUR") p
                         {:to "USD" :at-date jan-2 :precision nil})]
    ;; 100 × 1.08 = 108.00 at full precision (no scale change)
    (is (= 108.00M (:amount out)))))

(deftest convert-jpy-precision-zero
  (let [conn  (setup)
        p     (fxp/make-static-table-provider conn)]
    (fxp/save-rates! conn [{:from "EUR" :to "JPY" :at-date jan-2
                            :rate 162.45M :source :test}])
    (let [out (fx/convert (money/money 100M "EUR") p
                          {:to "JPY" :at-date jan-2 :precision 0})]
      (is (= 0 (.scale ^java.math.BigDecimal (:amount out))))
      ;; 100 × 162.45 = 16245
      (is (= 16245M (:amount out))))))

(deftest translate-amounts-by-commodity-sums-to-one-commodity
  (let [conn (setup)
        p    (fxp/make-static-table-provider conn)
        amts {"EUR" 100M
              "USD" 54M}     ;; ≈ 50 EUR at 1.08, so total ≈ 162.00 USD
        total (fx/translate-amounts-by-commodity
               amts p {:to "USD" :at-date jan-2})]
    (is (= "USD" (:commodity total)))
    ;; 100 EUR × 1.08 = 108.00 USD; plus 54 USD = 162.00 USD
    (is (= 162.00M (:amount total)))))

(deftest translate-amounts-empty-returns-zero
  (let [conn (setup)
        p    (fxp/make-static-table-provider conn)
        total (fx/translate-amounts-by-commodity {} p
                                                 {:to "USD" :at-date jan-2})]
    (is (money/zero? total))
    (is (= "USD" (:commodity total)))))

(deftest translate-money-seq-mixed-commodities
  (let [conn (setup)
        ;; Use the ECB-flavoured provider so GBP→USD triangulates via EUR.
        p    (fxp/make-ecb-reference-rates-provider conn)
        ms   [(money/money 100M "EUR")
              (money/money 50M "USD")
              (money/money 0M "GBP")]
        total (fx/translate-money-seq ms p {:to "USD" :at-date jan-2})]
    ;; 108 + 50 + 0 = 158.00
    (is (= 158.00M (:amount total)))))

;; ============================================================================
;; to-functional-currency
;; ============================================================================

(deftest functional-currency-rebase
  (let [conn   (setup)
        ;; A US LLC subsidiary whose functional commodity is USD,
        ;; receiving a EUR-denominated invoice from its DE parent.
        p      (fxp/make-static-table-provider conn)
        entity {:entity/functional-commodity "USD"}
        in     (money/money 100M "EUR")
        out    (fx/to-functional-currency in entity p {:at-date jan-2})]
    (is (= "USD" (:commodity out)))
    (is (= 108.00M (:amount out)))))

(deftest functional-currency-no-pref-passthrough
  (let [conn (setup)
        p    (fxp/make-static-table-provider conn)
        in   (money/money 100M "EUR")]
    (is (identical? in (fx/to-functional-currency in {} p {:at-date jan-2})))))

(deftest functional-currency-already-in-functional-passthrough
  (let [conn (setup)
        p    (fxp/make-static-table-provider conn)
        in   (money/money 100M "USD")
        out  (fx/to-functional-currency in {:entity/functional-commodity "USD"}
                                        p {:at-date jan-2})]
    (is (identical? in out))))
