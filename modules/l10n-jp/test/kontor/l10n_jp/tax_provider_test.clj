(ns kontor.l10n-jp.tax-provider-test
  "Golden-fixture tests for the Japanese ADR-071 tax provider
   (research note 100 — JP Shape-B port, copying the AT pilot).
   Validates that `JpTaxRateProvider` + `JpTaxPostingBuilder` resolve
   JP JCT classes to the right `TaxFacts` and 仮受消費税 postings.
   The behaviour-identical regression of the *invoice* posting path
   is covered by `invoice_test.clj`."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-jp.tax-provider :as jtp]
            [kontor.tax-posting-builder :as tpb]
            [kontor.tax-rate-provider :as trp]))

(defn- fresh []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:commodity/symbol "JPY" :commodity/name "Japanese Yen"
                  :commodity/precision 0}
                 {:account/code "215100" :account/path "OutputJCT-10"
                  :account/type :liability}
                 {:account/code "215200" :account/path "OutputJCT-8"
                  :account/type :liability}])
    conn))

(def ^:private jpy [:commodity/symbol "JPY"])
(def ^:private d1 #inst "2026-03-01")

;; ============================================================================
;; JpTaxRateProvider — JCT class → TaxFacts
;; ============================================================================

(defn- jct-amount
  "First component's :amount from a (base, jct-class) rate-facts call."
  [prov base jc]
  (-> (trp/rate-facts prov {:base base :jct-class jc})
      :components first :amount))

(deftest rate-facts-maps-each-jct-class
  (let [prov (jtp/make-jp-tax-rate-provider)
        amt  (fn [jc] (jct-amount prov 100000M jc))]
    (testing "standard + reduced rates → an :output-vat component"
      (is (== 10000M (amt :standard)))
      (is (== 8000M  (amt :reduced)))
      (let [c (-> (trp/rate-facts prov {:base 100000M :jct-class :standard})
                  :components first)]
        (is (= :output-vat (:kind c)))
        (is (== 0.10M (:rate c)))
        (is (= :standard
               (get-in c [:jurisdiction-specific-codes :jp/jct-class])))))
    (testing "reduced rides the 8% rate + stashes its class"
      (let [c (-> (trp/rate-facts prov {:base 1000M :jct-class :reduced})
                  :components first)]
        (is (== 0.08M (:rate c)))
        (is (= :reduced
               (get-in c [:jurisdiction-specific-codes :jp/jct-class])))))
    (testing "the three zero kinds → no tax (nil facts)"
      (is (nil? (trp/rate-facts prov {:base 100000M :jct-class :non-taxable})))
      (is (nil? (trp/rate-facts prov {:base 100000M :jct-class :export-exempt})))
      (is (nil? (trp/rate-facts prov {:base 100000M :jct-class :out-of-scope}))))
    (testing "JPY rounds to whole yen — HALF-EVEN, 0 decimal places"
      ;; 333 × 0.10 = 33.3 → 33; 555 × 0.08 = 44.4 → 44.
      (is (== 33M (jct-amount prov 333M :standard)))
      (is (== 44M (jct-amount prov 555M :reduced))))
    (testing "the facts are structurally valid (closed-vocabulary check)"
      (is (trp/valid-tax-facts?
           (trp/rate-facts prov {:base 100000M :jct-class :standard}))))))

;; ============================================================================
;; JpTaxPostingBuilder — TaxFacts → 仮受消費税 postings
;; ============================================================================

(deftest builder-routes-jct-by-rate
  (let [conn (fresh)
        db   (d/db conn)
        prov (jtp/make-jp-tax-rate-provider)
        bld  (jtp/make-jp-tax-posting-builder)
        a215100 (d/q '[:find ?a . :where [?a :account/code "215100"]] db)
        a215200 (d/q '[:find ?a . :where [?a :account/code "215200"]] db)
        post  (fn [jc] (tpb/compute-tax-postings
                        prov bld
                        {:base 100000M :jct-class jc :commodity jpy}
                        {:db db :date d1}))]
    (testing "standard → one credit of 10,000 to 215100"
      (let [p (first (post :standard))]
        (is (= a215100 (:posting/account p)))
        (is (== -10000M (:posting/amount p)) "output JCT is a credit")
        (is (= :tax (:posting/display-type p)))))
    (testing "reduced → 8,000 to 215200"
      (let [p (first (post :reduced))]
        (is (= a215200 (:posting/account p)))
        (is (== -8000M (:posting/amount p)))))
    (testing "non-taxable → no leg"
      (is (= [] (post :non-taxable))))
    (testing "export-exempt → no leg"
      (is (= [] (post :export-exempt))))
    (testing "out-of-scope → no leg"
      (is (= [] (post :out-of-scope))))))

(deftest aggregate-collapses-same-rate-lines
  (let [conn (fresh)
        db   (d/db conn)
        prov (jtp/make-jp-tax-rate-provider)
        bld  (jtp/make-jp-tax-posting-builder)
        raw  (mapcat (fn [base]
                       (tpb/compute-tax-postings
                        prov bld
                        {:base base :jct-class :standard :commodity jpy}
                        {:db db :date d1}))
                     [10000M 20000M 30000M])
        agg  (tpb/aggregate-postings raw)]
    (is (= 3 (count raw)) "three lines → three raw JCT postings")
    (is (= 1 (count agg)) "aggregated to one 215100 posting")
    (is (== -6000M (:posting/amount (first agg))) "10% of 60,000")))

(deftest mixed-rate-lines-aggregate-per-account
  (testing "10% + 8% lines collapse to two postings, one per account"
    (let [conn (fresh)
          db   (d/db conn)
          prov (jtp/make-jp-tax-rate-provider)
          bld  (jtp/make-jp-tax-posting-builder)
          raw  (concat
                (tpb/compute-tax-postings
                 prov bld {:base 100000M :jct-class :standard :commodity jpy}
                 {:db db :date d1})
                (tpb/compute-tax-postings
                 prov bld {:base 50000M :jct-class :reduced :commodity jpy}
                 {:db db :date d1}))
          agg  (tpb/aggregate-postings raw)
          by-acct (into {} (map (juxt :posting/account :posting/amount)) agg)
          a215100 (d/q '[:find ?a . :where [?a :account/code "215100"]] db)
          a215200 (d/q '[:find ?a . :where [?a :account/code "215200"]] db)]
      (is (= 2 (count agg)))
      (is (== -10000M (get by-acct a215100)) "10% of 100,000")
      (is (== -4000M  (get by-acct a215200)) "8% of 50,000"))))
