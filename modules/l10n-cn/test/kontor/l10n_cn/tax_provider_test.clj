(ns kontor.l10n-cn.tax-provider-test
  "Golden-fixture tests for the Chinese ADR-071 tax provider.
   Validates
   that `CnTaxRateProvider` + `CnTaxPostingBuilder` resolve CN VAT
   rates / taxpayer-statuses to the right `TaxFacts` and output-VAT
   postings. The behaviour-identical regression of the *invoice* path
   is covered by `invoice_test.clj`."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-cn.tax-provider :as cnp]
            [kontor.tax.tax-posting-builder :as tpb]
            [kontor.tax.tax-rate-provider :as trp]))

(defn- fresh []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:kontor.commodity/symbol "CNY" :kontor.commodity/name "Renminbi"
                  :kontor.commodity/precision 2}
                 {:kontor.account/code "2221.01.01" :kontor.account/path "Output-VAT"
                  :kontor.account/type :liability}])
    conn))

(def ^:private cny [:kontor.commodity/symbol "CNY"])
(def ^:private d1 #inst "2026-03-01")

;; ============================================================================
;; CnTaxRateProvider — rate / taxpayer-status → TaxFacts
;; ============================================================================

(deftest rate-facts-maps-general-taxpayer-rates
  (let [prov (cnp/make-cn-tax-rate-provider)
        amt  (fn [rate]
               (-> (trp/rate-facts prov {:base 1000M :rate rate})
                   :components first :amount))]
    (testing "the 13/9/6 general-taxpayer ladder → an :output-vat component"
      (is (== 130M (amt 0.13M)))
      (is (== 90M  (amt 0.09M)))
      (is (== 60M  (amt 0.06M)))
      (let [c (-> (trp/rate-facts prov {:base 1000M :rate 0.13M})
                  :components first)]
        (is (= :output-vat (:kind c)))
        (is (== 0.13M (:rate c)))
        (is (== 0.13M (get-in c [:jurisdiction-specific-codes :cn/vat-rate])))
        (is (= :general
               (get-in c [:jurisdiction-specific-codes :cn/taxpayer-status])))))
    (testing "default rate (no :rate) → 13% standard goods rate"
      (is (== 130M (amt nil))))
    (testing "the facts are structurally valid (closed-vocabulary check)"
      (is (trp/valid-tax-facts?
           (trp/rate-facts prov {:base 1000M :rate 0.13M}))))))

(deftest rate-facts-maps-small-scale-rates
  (let [prov (cnp/make-cn-tax-rate-provider)
        amt  (fn [rate]
               (-> (trp/rate-facts prov {:base 1000M :rate rate
                                         :taxpayer-status :small-scale})
                   :components first :amount))]
    (testing "the 1/3/5 small-scale ladder → an :output-vat component"
      (is (== 10M (amt 0.01M)))
      (is (== 30M (amt 0.03M)))
      (is (== 50M (amt 0.05M))))
    (testing "default rate for small-scale → 1% preferential"
      (let [c (-> (trp/rate-facts prov {:base 1000M
                                        :taxpayer-status :small-scale})
                  :components first)]
        (is (== 10M (:amount c)))
        (is (= :small-scale
               (get-in c [:jurisdiction-specific-codes :cn/taxpayer-status])))))))

(deftest rate-facts-zero-and-exempt-are-nil
  (let [prov (cnp/make-cn-tax-rate-provider)]
    (testing "zero-rated / exempt → no tax (nil facts)"
      (is (nil? (trp/rate-facts prov {:base 1000M :tax-status :zero-rated})))
      (is (nil? (trp/rate-facts prov {:base 1000M :tax-status :exempt}))))
    (testing "explicit 0% rate → no tax (nil facts)"
      (is (nil? (trp/rate-facts prov {:base 1000M :rate 0M}))))))

;; ============================================================================
;; CnTaxPostingBuilder — TaxFacts → output-VAT postings
;; ============================================================================

(deftest builder-routes-all-output-vat-to-single-account
  (let [conn (fresh)
        db   (d/db conn)
        prov (cnp/make-cn-tax-rate-provider)
        bld  (cnp/make-cn-tax-posting-builder)
        a2221 (d/q '[:find ?a . :where [?a :kontor.account/code "2221.01.01"]] db)
        post  (fn [ctx] (tpb/compute-tax-postings
                         prov bld
                         (merge {:base 1000M :commodity cny} ctx)
                         {:db db :date d1}))]
    (testing "13% → one credit of 130 to 2221.01.01"
      (let [p (first (post {:rate 0.13M}))]
        (is (= a2221 (:kontor.posting/account p)))
        (is (== -130M (:kontor.posting/amount p)) "output VAT is a credit")
        (is (= :tax (:kontor.posting/display-type p)))))
    (testing "9% and 6% also route to the SAME 2221.01.01 (MOF-canonical)"
      (is (= a2221 (:kontor.posting/account (first (post {:rate 0.09M})))))
      (is (== -90M (:kontor.posting/amount (first (post {:rate 0.09M})))))
      (is (= a2221 (:kontor.posting/account (first (post {:rate 0.06M})))))
      (is (== -60M (:kontor.posting/amount (first (post {:rate 0.06M}))))))
    (testing "small-scale 1% → 10 to 2221.01.01"
      (let [p (first (post {:rate 0.01M :taxpayer-status :small-scale}))]
        (is (= a2221 (:kontor.posting/account p)))
        (is (== -10M (:kontor.posting/amount p)))))
    (testing "zero-rated / exempt / 0% → no leg"
      (is (= [] (post {:tax-status :zero-rated})))
      (is (= [] (post {:tax-status :exempt})))
      (is (= [] (post {:rate 0M}))))))

(deftest aggregate-collapses-all-rates-onto-one-account
  (let [conn (fresh)
        db   (d/db conn)
        prov (cnp/make-cn-tax-rate-provider)
        bld  (cnp/make-cn-tax-posting-builder)
        ;; A mixed-rate invoice — 13/9/6 — collapses to ONE 2221.01.01
        ;; posting (CN is single-account, unlike AT's per-rate USt).
        raw  (mapcat (fn [[base rate]]
                       (tpb/compute-tax-postings
                        prov bld
                        {:base base :rate rate :commodity cny}
                        {:db db :date d1}))
                     [[5000M 0.13M] [3000M 0.09M] [2000M 0.06M]])
        agg  (tpb/aggregate-postings raw)]
    (is (= 3 (count raw)) "three lines → three raw output-VAT postings")
    (is (= 1 (count agg)) "aggregated to one 2221.01.01 posting")
    ;; 650 + 270 + 120 = 1040
    (is (== -1040M (:kontor.posting/amount (first agg)))
        "all rates consolidated on the single MOF account")))

(deftest builder-honours-output-vat-code-override
  (let [conn (fresh)]
    (d/transact conn [{:kontor.account/code "2221.99" :kontor.account/path "Output-VAT-alt"
                       :kontor.account/type :liability}])
    (let [db   (d/db conn)
          prov (cnp/make-cn-tax-rate-provider)
          bld  (cnp/make-cn-tax-posting-builder {:output-vat-code "2221.99"})
          a99  (d/q '[:find ?a . :where [?a :kontor.account/code "2221.99"]] db)
          p    (first (tpb/compute-tax-postings
                       prov bld
                       {:base 1000M :rate 0.13M :commodity cny}
                       {:db db :date d1}))]
      (is (= a99 (:kontor.posting/account p)) "routes to the overridden account")
      (is (== -130M (:kontor.posting/amount p))))))

(deftest builder-throws-on-missing-account
  (let [conn (core/create-test-db)
        db   (d/db conn)
        prov (cnp/make-cn-tax-rate-provider)
        bld  (cnp/make-cn-tax-posting-builder)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (tpb/compute-tax-postings
                  prov bld
                  {:base 1000M :rate 0.13M :commodity nil}
                  {:db db :date d1})))))
