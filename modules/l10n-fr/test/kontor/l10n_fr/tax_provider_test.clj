(ns kontor.l10n-fr.tax-provider-test
  "Golden-fixture tests for the French ADR-071 tax provider (research
   note 100 — FR follows the AT pilot port). Validates that
   `FrTaxRateProvider` + `FrTaxPostingBuilder` resolve FR TVA classes
   to the right `TaxFacts` and TVA-collectée postings. The
   behaviour-identical regression of the *invoice* path is covered by
   `invoice_test.clj`."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-fr.tax-provider :as frp]
            [kontor.tax-posting-builder :as tpb]
            [kontor.tax-rate-provider :as trp]))

(defn- fresh []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro" :kontor.commodity/precision 2}
                 {:kontor.account/code "44571" :kontor.account/path "TVA-20" :kontor.account/type :liability}
                 {:kontor.account/code "44572" :kontor.account/path "TVA-10" :kontor.account/type :liability}
                 {:kontor.account/code "44573" :kontor.account/path "TVA-5.5" :kontor.account/type :liability}
                 {:kontor.account/code "44574" :kontor.account/path "TVA-2.1" :kontor.account/type :liability}])
    conn))

(def ^:private eur [:kontor.commodity/symbol "EUR"])
(def ^:private d1 #inst "2026-03-01")

;; ============================================================================
;; FrTaxRateProvider — TVA rate / status → TaxFacts
;; ============================================================================

(deftest rate-facts-maps-each-tva-rate
  (let [prov (frp/make-fr-tax-rate-provider)
        amt  (fn [rate] (-> (trp/rate-facts prov {:base 1000M :rate rate})
                            :components first :amount))]
    (testing "the four taxable rates → an :output-vat component"
      (is (== 200M (amt :std)))
      (is (== 100M (amt :inter)))
      (is (== 55M  (amt :red)))
      (is (== 21M  (amt :spec)))
      (let [c (-> (trp/rate-facts prov {:base 1000M :rate :std})
                  :components first)]
        (is (= :output-vat (:kind c)))
        (is (== 0.20M (:rate c)))
        (is (= :std (get-in c [:jurisdiction-specific-codes :fr/tva-rate])))
        (is (= :taxable
               (get-in c [:jurisdiction-specific-codes :fr/tax-status])))))
    (testing "rate :zero → no tax (nil facts)"
      (is (nil? (trp/rate-facts prov {:base 1000M :rate :zero}))))
    (testing "exempt / export status → no tax (nil facts)"
      (is (nil? (trp/rate-facts prov {:base 1000M :tax-status :exempt})))
      (is (nil? (trp/rate-facts prov {:base 1000M :tax-status :export}))))
    (testing "intra-EU B2B → a :reverse-charge component"
      (is (= :reverse-charge
             (-> (trp/rate-facts prov {:base 1000M :tax-status :intra-eu-b2b})
                 :components first :kind))))
    (testing "the facts are structurally valid (closed-vocabulary check)"
      (is (trp/valid-tax-facts?
           (trp/rate-facts prov {:base 1000M :rate :std}))))))

;; ============================================================================
;; FrTaxPostingBuilder — TaxFacts → TVA-collectée postings
;; ============================================================================

(deftest builder-routes-tva-by-rate
  (let [conn (fresh)
        db   (d/db conn)
        prov (frp/make-fr-tax-rate-provider)
        bld  (frp/make-fr-tax-posting-builder)
        a44571 (d/q '[:find ?a . :where [?a :kontor.account/code "44571"]] db)
        a44573 (d/q '[:find ?a . :where [?a :kontor.account/code "44573"]] db)
        post  (fn [opts] (tpb/compute-tax-postings
                          prov bld
                          (merge {:base 1000M :commodity eur} opts)
                          {:db db :date d1}))]
    (testing "taux normal 20% → one credit of 200 to 44571"
      (let [p (first (post {:rate :std}))]
        (is (= a44571 (:kontor.posting/account p)))
        (is (== -200M (:kontor.posting/amount p)) "output VAT is a credit")
        (is (= :tax (:kontor.posting/display-type p)))))
    (testing "taux réduit 5,5% → 55 to 44573"
      (let [p (first (post {:rate :red}))]
        (is (= a44573 (:kontor.posting/account p)))
        (is (== -55M (:kontor.posting/amount p)))))
    (testing "intra-EU B2B → no leg (FR seller-side reverse charge)"
      (is (= [] (post {:tax-status :intra-eu-b2b}))))
    (testing "zero / exempt / export → no leg"
      (is (= [] (post {:rate :zero})))
      (is (= [] (post {:tax-status :exempt})))
      (is (= [] (post {:tax-status :export}))))))

(deftest aggregate-collapses-same-rate-lines
  (let [conn (fresh)
        db   (d/db conn)
        prov (frp/make-fr-tax-rate-provider)
        bld  (frp/make-fr-tax-posting-builder)
        raw  (mapcat (fn [base]
                       (tpb/compute-tax-postings
                        prov bld
                        {:base base :rate :std :commodity eur}
                        {:db db :date d1}))
                     [100M 200M 300M])
        agg  (tpb/aggregate-postings raw)]
    (is (= 3 (count raw)) "three lines → three raw TVA postings")
    (is (= 1 (count agg)) "aggregated to one 44571 posting")
    (is (== -120M (:kontor.posting/amount (first agg))) "20% of 600")))
