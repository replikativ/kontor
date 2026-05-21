(ns kontor.l10n-at.tax-provider-test
  "Golden-fixture tests for the Austrian ADR-071 tax provider
   (research note 100 — AT is the pilot port). Validates that
   `AtTaxRateProvider` + `AtTaxPostingBuilder` resolve AT VAT classes
   to the right `TaxFacts` and USt postings. The behaviour-identical
   regression of the *invoice* path is covered by `invoice_test.clj`."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-at.tax-provider :as atp]
            [kontor.tax-posting-builder :as tpb]
            [kontor.tax-rate-provider :as trp]))

(defn- fresh []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:commodity/symbol "EUR" :commodity/name "Euro" :commodity/precision 2}
                 {:account/code "3500" :account/path "USt-20" :account/type :liability}
                 {:account/code "3510" :account/path "USt-13" :account/type :liability}
                 {:account/code "3520" :account/path "USt-10" :account/type :liability}])
    conn))

(def ^:private eur [:commodity/symbol "EUR"])
(def ^:private d1 #inst "2026-03-01")

;; ============================================================================
;; AtTaxRateProvider — VAT class → TaxFacts
;; ============================================================================

(deftest rate-facts-maps-each-vat-class
  (let [prov (atp/make-at-tax-rate-provider)
        amt  (fn [vc] (-> (trp/rate-facts prov {:base 1000M :vat-class vc})
                          :components first :amount))]
    (testing "standard + reduced rates → an :output-vat component"
      (is (== 200M (amt :standard)))
      (is (== 130M (amt :reduced-13)))
      (is (== 100M (amt :reduced-10)))
      (let [c (-> (trp/rate-facts prov {:base 1000M :vat-class :standard})
                  :components first)]
        (is (= :output-vat (:kind c)))
        (is (== 0.20M (:rate c)))
        (is (= :standard (get-in c [:jurisdiction-specific-codes :at/vat-class])))))
    (testing "zero / exempt → no tax (nil facts)"
      (is (nil? (trp/rate-facts prov {:base 1000M :vat-class :zero})))
      (is (nil? (trp/rate-facts prov {:base 1000M :vat-class :exempt}))))
    (testing "reverse-charge → a :reverse-charge component"
      (is (= :reverse-charge
             (-> (trp/rate-facts prov {:base 1000M :vat-class :reverse-charge})
                 :components first :kind))))
    (testing "the facts are structurally valid (closed-vocabulary check)"
      (is (trp/valid-tax-facts?
           (trp/rate-facts prov {:base 1000M :vat-class :standard}))))))

;; ============================================================================
;; AtTaxPostingBuilder — TaxFacts → USt postings
;; ============================================================================

(deftest builder-routes-ust-by-rate
  (let [conn (fresh)
        db   (d/db conn)
        prov (atp/make-at-tax-rate-provider)
        bld  (atp/make-at-tax-posting-builder)
        a3500 (d/q '[:find ?a . :where [?a :account/code "3500"]] db)
        a3520 (d/q '[:find ?a . :where [?a :account/code "3520"]] db)
        post  (fn [vc] (tpb/compute-tax-postings
                        prov bld
                        {:base 1000M :vat-class vc :commodity eur}
                        {:db db :date d1}))]
    (testing "standard → one credit of 200 to 3500"
      (let [p (first (post :standard))]
        (is (= a3500 (:posting/account p)))
        (is (== -200M (:posting/amount p)) "output VAT is a credit")
        (is (= :tax (:posting/display-type p)))))
    (testing "reduced-10 → 100 to 3520"
      (let [p (first (post :reduced-10))]
        (is (= a3520 (:posting/account p)))
        (is (== -100M (:posting/amount p)))))
    (testing "reverse-charge → no leg (AT seller-side)"
      (is (= [] (post :reverse-charge))))
    (testing "zero / exempt → no leg"
      (is (= [] (post :zero)))
      (is (= [] (post :exempt))))))

(deftest aggregate-collapses-same-rate-lines
  (let [conn (fresh)
        db   (d/db conn)
        prov (atp/make-at-tax-rate-provider)
        bld  (atp/make-at-tax-posting-builder)
        raw  (mapcat (fn [base]
                       (tpb/compute-tax-postings
                        prov bld
                        {:base base :vat-class :standard :commodity eur}
                        {:db db :date d1}))
                     [100M 200M 300M])
        agg  (tpb/aggregate-postings raw)]
    (is (= 3 (count raw)) "three lines → three raw USt postings")
    (is (= 1 (count agg)) "aggregated to one 3500 posting")
    (is (== -120M (:posting/amount (first agg))) "20% of 600")))
