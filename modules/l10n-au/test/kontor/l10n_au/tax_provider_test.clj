(ns kontor.l10n-au.tax-provider-test
  "Golden-fixture tests for the Australian ADR-071 tax provider
   (research note 100 — AU mirrors the AT pilot port). Validates that
   `AuTaxRateProvider` + `AuTaxPostingBuilder` resolve AU GST statuses
   to the right `TaxFacts` and GST postings. The behaviour-identical
   regression of the *invoice* path is covered by `invoice_test.clj`."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-au.tax-provider :as aup]
            [kontor.tax-posting-builder :as tpb]
            [kontor.tax-rate-provider :as trp]))

(defn- fresh []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:commodity/symbol "AUD" :commodity/name "Australian Dollar"
                  :commodity/precision 2}
                 {:account/code "21500" :account/path "GST-payable"
                  :account/type :liability}])
    conn))

(def ^:private aud [:commodity/symbol "AUD"])
(def ^:private d1 #inst "2026-03-01")

;; ============================================================================
;; AuTaxRateProvider — tax-status → TaxFacts
;; ============================================================================

(deftest rate-facts-maps-each-tax-status
  (let [prov (aup/make-au-tax-rate-provider)]
    (testing "taxable → an :output-vat component at 10%"
      (let [c (-> (trp/rate-facts prov {:base 1000M :tax-status :taxable})
                  :components first)]
        (is (= :output-vat (:kind c)))
        (is (== 100M (:amount c)) "10% of 1000")
        (is (== 0.10M (:rate c)))
        (is (= :taxable (get-in c [:jurisdiction-specific-codes
                                   :au/tax-status])))))
    (testing "default status is :taxable when omitted"
      (let [c (-> (trp/rate-facts prov {:base 500M}) :components first)]
        (is (= :output-vat (:kind c)))
        (is (== 50M (:amount c)))))
    (testing "gst-free / input-taxed → no tax (nil facts)"
      (is (nil? (trp/rate-facts prov {:base 1000M :tax-status :gst-free})))
      (is (nil? (trp/rate-facts prov {:base 1000M :tax-status :input-taxed}))))
    (testing "the facts are structurally valid (closed-vocabulary check)"
      (is (trp/valid-tax-facts?
           (trp/rate-facts prov {:base 1000M :tax-status :taxable}))))
    (testing ":tax-use is :sale (output GST)"
      (is (= :sale (:tax-use (trp/rate-facts prov {:base 1000M})))))))

;; ============================================================================
;; AuTaxPostingBuilder — TaxFacts → GST postings
;; ============================================================================

(deftest builder-routes-gst-to-payable-account
  (let [conn  (fresh)
        db    (d/db conn)
        prov  (aup/make-au-tax-rate-provider)
        bld   (aup/make-au-tax-posting-builder)
        a21500 (d/q '[:find ?a . :where [?a :account/code "21500"]] db)
        post  (fn [ts] (tpb/compute-tax-postings
                        prov bld
                        {:base 1000M :tax-status ts :commodity aud}
                        {:db db :date d1}))]
    (testing "taxable → one credit of 100 to 21500"
      (let [p (first (post :taxable))]
        (is (= a21500 (:posting/account p)))
        (is (== -100M (:posting/amount p)) "output GST is a credit")
        (is (= :tax (:posting/display-type p)))
        (is (= aud (:posting/commodity p)))))
    (testing "gst-free → no leg"
      (is (= [] (post :gst-free))))
    (testing "input-taxed → no leg"
      (is (= [] (post :input-taxed))))))

(deftest aggregate-collapses-same-rate-lines
  (let [conn (fresh)
        db   (d/db conn)
        prov (aup/make-au-tax-rate-provider)
        bld  (aup/make-au-tax-posting-builder)
        raw  (mapcat (fn [base]
                       (tpb/compute-tax-postings
                        prov bld
                        {:base base :tax-status :taxable :commodity aud}
                        {:db db :date d1}))
                     [100M 200M 300M])
        agg  (tpb/aggregate-postings raw)]
    (is (= 3 (count raw)) "three lines → three raw GST postings")
    (is (= 1 (count agg)) "aggregated to one 21500 posting")
    (is (== -60M (:posting/amount (first agg))) "10% of 600")))

(deftest builder-honours-gst-payable-code-override
  (let [conn (fresh)
        db   (d/db conn)
        prov (aup/make-au-tax-rate-provider)]
    (testing "a missing override account → no leg (account-by-code nil)"
      (let [bld (aup/make-au-tax-posting-builder {:gst-payable-code "99999"})]
        (is (= [] (tpb/compute-tax-postings
                   prov bld
                   {:base 1000M :tax-status :taxable :commodity aud}
                   {:db db :date d1})))))
    (testing "nil override falls back to the chart default 21500"
      (let [bld (aup/make-au-tax-posting-builder {:gst-payable-code nil})
            p   (first (tpb/compute-tax-postings
                        prov bld
                        {:base 1000M :tax-status :taxable :commodity aud}
                        {:db db :date d1}))]
        (is (== -100M (:posting/amount p)))))))
