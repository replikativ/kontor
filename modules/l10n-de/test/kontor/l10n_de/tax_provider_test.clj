(ns kontor.l10n-de.tax-provider-test
  "Golden-fixture tests for the German ADR-071 tax provider (research
   note 100 — DE migrated onto the tax abstraction after the AT pilot).
   Validates that `DeTaxRateProvider` + `DeTaxPostingBuilder` resolve DE
   USt rates to the right `TaxFacts` and USt postings. The
   behaviour-identical regression of the *invoice* path is covered by
   the kernel `kontor.invoice-test` end-to-end test."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-de.tax-provider :as dtp]
            [kontor.tax-posting-builder :as tpb]
            [kontor.tax-rate-provider :as trp]))

(defn- fresh []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro" :kontor.commodity/precision 2}
                 {:kontor.account/code "3801" :kontor.account/path "USt-19" :kontor.account/type :liability}
                 {:kontor.account/code "3806" :kontor.account/path "USt-7" :kontor.account/type :liability}])
    conn))

(def ^:private eur [:kontor.commodity/symbol "EUR"])
(def ^:private d1 #inst "2026-03-01")

;; ============================================================================
;; DeTaxRateProvider — VAT rate → TaxFacts
;; ============================================================================

(deftest rate-facts-maps-each-vat-rate
  (let [prov (dtp/make-de-tax-rate-provider)
        amt  (fn [vr] (-> (trp/rate-facts prov {:base 1000M :vat-rate vr})
                          :components first :amount))]
    (testing "19% / 7% standard + reduced rates → an :output-vat component"
      (is (== 190M (amt 19.0M)))
      (is (== 70M  (amt 7.0M)))
      (let [c (-> (trp/rate-facts prov {:base 1000M :vat-rate 19.0M})
                  :components first)]
        (is (= :output-vat (:kind c)))
        (is (== 0.19M (:rate c)))
        (is (= :standard (get-in c [:jurisdiction-specific-codes :de/ust-class])))
        (is (== 19.0M (get-in c [:jurisdiction-specific-codes :de/vat-rate])))))
    (testing "7% carries the :reduced class"
      (is (= :reduced
             (-> (trp/rate-facts prov {:base 1000M :vat-rate 7.0M})
                 :components first
                 (get-in [:jurisdiction-specific-codes :de/ust-class])))))
    (testing "0% steuerfrei → no tax (nil facts)"
      (is (nil? (trp/rate-facts prov {:base 1000M :vat-rate 0.0M}))))
    (testing "default vat-rate is 19%"
      (is (== 190M (-> (trp/rate-facts prov {:base 1000M})
                       :components first :amount))))
    (testing "bucket-level HALF-EVEN rounding (1589.50 × 19% → 302.00)"
      (is (== 302.00M
              (-> (trp/rate-facts prov {:base 1589.50M :vat-rate 19.0M})
                  :components first :amount))))
    (testing "the facts are structurally valid (closed-vocabulary check)"
      (is (trp/valid-tax-facts?
           (trp/rate-facts prov {:base 1000M :vat-rate 19.0M}))))))

;; ============================================================================
;; DeTaxPostingBuilder — TaxFacts → USt postings
;; ============================================================================

(deftest builder-routes-ust-by-rate
  (let [conn (fresh)
        db   (d/db conn)
        prov (dtp/make-de-tax-rate-provider)
        bld  (dtp/make-de-tax-posting-builder)
        a3801 (d/q '[:find ?a . :where [?a :kontor.account/code "3801"]] db)
        a3806 (d/q '[:find ?a . :where [?a :kontor.account/code "3806"]] db)
        post  (fn [vr] (tpb/compute-tax-postings
                        prov bld
                        {:base 1000M :vat-rate vr :commodity eur}
                        {:db db :date d1}))]
    (testing "19% → one credit of 190 to 3801"
      (let [p (first (post 19.0M))]
        (is (= a3801 (:posting/account p)))
        (is (== -190M (:posting/amount p)) "output VAT is a credit")
        (is (= :tax (:posting/display-type p)))))
    (testing "7% → 70 to 3806"
      (let [p (first (post 7.0M))]
        (is (= a3806 (:posting/account p)))
        (is (== -70M (:posting/amount p)))))
    (testing "0% steuerfrei → no leg"
      (is (= [] (post 0.0M))))))

(deftest aggregate-collapses-same-rate-lines
  (let [conn (fresh)
        db   (d/db conn)
        prov (dtp/make-de-tax-rate-provider)
        bld  (dtp/make-de-tax-posting-builder)
        raw  (mapcat (fn [base]
                       (tpb/compute-tax-postings
                        prov bld
                        {:base base :vat-rate 19.0M :commodity eur}
                        {:db db :date d1}))
                     [100M 200M 300M])
        agg  (tpb/aggregate-postings raw)]
    (is (= 3 (count raw)) "three lines → three raw USt postings")
    (is (= 1 (count agg)) "aggregated to one 3801 posting")
    (is (== -114M (:posting/amount (first agg))) "19% of 600")))
