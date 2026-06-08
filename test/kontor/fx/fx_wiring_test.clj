(ns kontor.fx.fx-wiring-test
  "Integration tests for ADR-072 FX wiring follow-ups:
   - `kontor.reporting.report/compute-report :translate-to` opt
   - `kontor.lease.posting/plan-fx-retranslation` provider-driven mode

   The report test installs a tiny chart, posts a 100-EUR sale, and
   verifies that running a tax-tags report against it both produces
   a per-line EUR value AND a translated USD value when a provider is
   wired up. The lease test verifies provider-mode :gain-loss
   computation matches the consumer-supplied path bit-for-bit on a
   shared rate."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.fx.fx-rate-provider :as fxp]
            [kontor.lease.posting :as lease-posting]
            [kontor.posting :as posting]
            [kontor.reporting.report :as report]
            [kontor.validation :as v]))

(def jan-2 #inst "2026-01-02T00:00:00Z")
(def jan-3 #inst "2026-01-03T00:00:00Z")

(defn- bootstrap! []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (d/transact conn [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                       :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "EUR"}
                      {:kontor.commodity/symbol "USD" :kontor.commodity/name "USD"
                       :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "USD"}])
    (fxp/save-rates! conn [{:from "EUR" :to "USD" :at-date jan-2
                            :rate 1.08M :rate-type :closing :source :test}])
    conn))

(deftest report-translate-to-adds-translated-value
  (testing ":translate-to + :fx-provider produces :line/value-translated
            alongside the original per-commodity :line/value."
    (let [conn (bootstrap!)
          ;; Install a minimal chart with one income account + an
          ;; account-tag so :tax-tags engine has something to sum.
          _ (d/transact conn
                        [{:db/id "tag" :kontor.account-tag/name "test-revenue"}
                         {:db/id "income"
                          :kontor.account/path "Income:Test"
                          :kontor.account/code "4000"
                          :kontor.account/name "Test revenue"
                          :kontor.account/type :income
                          :kontor.account/active true
                          :kontor.account/tags ["tag"]}
                         {:db/id "journal"
                          :kontor.journal/code "INV"
                          :kontor.journal/name "Invoices"
                          :kontor.journal/type :sale
                          :kontor.journal/active true}
                         {:db/id "cash"
                          :kontor.account/path "Assets:Cash"
                          :kontor.account/code "1200"
                          :kontor.account/name "Cash"
                          :kontor.account/type :asset
                          :kontor.account/active true}])
          db0 (d/db conn)
          eur (:db/id (d/entity db0 [:kontor.commodity/symbol "EUR"]))
          income-eid (:db/id (d/entity db0 [:kontor.account/path "Income:Test"]))
          cash-eid (:db/id (d/entity db0 [:kontor.account/path "Assets:Cash"]))
          journal-eid (:db/id (d/entity db0 [:kontor.journal/code "INV"]))
          ;; Post a balanced 100-EUR sale (debit cash, credit income)
          ;; via the canonical post-transaction! → with-vt sets the
          ;; tx's :db.valid/from from :kontor.transaction/effective-date, which
          ;; is what compute-report's :from / :to window filters on.
          _ (posting/post-transaction!
             conn
             {:transaction {:kontor.transaction/journal journal-eid
                            :kontor.transaction/effective-date jan-2}
              :postings    [{:kontor.posting/account cash-eid
                             :kontor.posting/commodity eur
                             :kontor.posting/amount 100M}
                            {:kontor.posting/account income-eid
                             :kontor.posting/commodity eur
                             :kontor.posting/amount -100M}]})
          provider (fxp/make-static-table-provider conn)
          rpt {:report/name "Sales (EUR)"
               :report/lines [{:line/code "S"
                               :line/label "Revenue tagged test-revenue"
                               :line/expression {:engine :tax-tags
                                                 :tags [:test-revenue]
                                                 :sign :inflow
                                                 :commodity "EUR"}}]}
          ;; Plain compute (no translation)
          plain (report/compute-report conn rpt {:from jan-2})
          plain-line (first (:report/lines plain))
          ;; With :translate-to
          translated (report/compute-report
                      conn rpt
                      {:from jan-2
                       :to jan-3
                       :translate-to "USD"
                       :fx-provider provider
                       :rate-type :closing})
          tr-line (first (:report/lines translated))]
      ;; Plain path: no :line/value-translated, just the EUR value.
      (is (nil? (:line/value-translated plain-line)))
      (is (= 100M (-> plain-line :line/value :amount))
          "100 EUR of revenue should sum :inflow as +100 on an :income account")
      (is (= "USD" (:report/translated-to translated))
          "the translated-to commodity is surfaced in the report header")
      ;; Translated path: original + translated both present.
      (is (= 100M (-> tr-line :line/value :amount))
          "the per-commodity :line/value is preserved unchanged")
      (is (= 108.00M (-> tr-line :line/value-translated :amount))
          "100 EUR × 1.08 closing-rate = 108.00 USD")
      (is (= "USD" (-> tr-line :line/value-translated :commodity))))))

(deftest report-translate-to-requires-fx-provider
  (let [conn (bootstrap!)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":translate-to requires :fx-provider"
                          (report/compute-report
                           conn
                           {:report/name "X"
                            :report/lines []}
                           {:translate-to "USD"})))))

;; ============================================================================
;; lease/posting/plan-fx-retranslation provider mode
;; ============================================================================

(deftest plan-fx-retranslation-provider-mode-matches-manual
  (testing "Provider-driven mode computes the same :gain-loss as the
            consumer-supplied path when given the equivalent inputs."
    (let [conn (bootstrap!)
          provider (fxp/make-static-table-provider conn)
          ;; Lease liability denominated in EUR with 100,000 EUR book
          ;; balance, previously translated to 105,000 USD. Now revalue
          ;; at jan-2 closing rate of 1.08 → 108,000 USD. FX LOSS of
          ;; 3,000 USD (debit P&L, credit liability).
          provider-tx (lease-posting/plan-fx-retranslation
                       {:liability-account 999
                        :fx-account        998
                        :fx-provider       provider
                        :book-balance      100000M
                        :prior-rc-carrying 105000M
                        :rc-commodity      "USD"
                        :commodity         "EUR"
                        :rate-type         :closing
                        :journal           997
                        :date              jan-2})
          manual-tx (lease-posting/plan-fx-retranslation
                     {:liability-account 999
                      :fx-account        998
                      :gain-loss         3000M
                      :commodity         "EUR"
                      :journal           997
                      :date              jan-2})
          ;; Extract just the posting amounts for comparison — the
          ;; tempids differ.
          amounts (fn [tx-data]
                    (->> tx-data
                         (filter map?)
                         (keep :kontor.posting/amount)
                         set))]
      (is (= (amounts provider-tx) (amounts manual-tx))
          "provider-mode :gain-loss = manual :gain-loss when math agrees")
      (is (contains? (amounts provider-tx) 3000M)
          "FX LOSS posts +3000 to fx-account")
      (is (contains? (amounts provider-tx) -3000M)
          "and -3000 to liability-account"))))

(deftest plan-fx-retranslation-requires-one-of-the-input-paths
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #":gain-loss OR \(:fx-provider \+ :book-balance \+ :prior-rc-carrying \+ :rc-commodity\) required"
       (lease-posting/plan-fx-retranslation
        {:liability-account 999
         :fx-account        998
         :commodity         "EUR"
         :journal           997
         :date              jan-2}))))

(deftest plan-fx-retranslation-provider-mode-negative-gain
  (testing "When the closing rate strengthens the book commodity, the
            liability in reporting terms SHRINKS — that's an FX GAIN
            (negative :gain-loss; credit P&L, debit liability)."
    (let [conn (bootstrap!)
          ;; Override the test rate: EUR→USD now 1.00 (book weakened).
          _ (fxp/save-rates! conn [{:from "EUR" :to "USD" :at-date jan-2
                                    :rate 1.00M :rate-type :closing
                                    :source :test}])
          provider (fxp/make-static-table-provider conn)
          tx (lease-posting/plan-fx-retranslation
              {:liability-account 999
               :fx-account        998
               :fx-provider       provider
               :book-balance      100000M
               :prior-rc-carrying 105000M
               :rc-commodity      "USD"
               :commodity         "EUR"
               :rate-type         :closing
               :journal           997
               :date              jan-2})
          amounts (->> tx (filter map?) (keep :kontor.posting/amount) set)]
      ;; new-rc = 100,000 × 1.00 = 100,000; gain-loss = 100,000 - 105,000 = -5,000
      (is (contains? amounts -5000M) "FX GAIN posts -5000 to fx-account")
      (is (contains? amounts 5000M)  "and +5000 to liability-account"))))
