(ns kontor.incorporation-test
  "Tests for `kontor.incorporation` — the founder → corporation
   incorporation primitive per note 107 §2.3 / ADR-103."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.core :as core]
            [kontor.disposal :as disposal]
            [kontor.incorporation :as incorp]))

;; ============================================================================
;; Fixture — a founder entity, USD commodity, journals, chart skeleton
;; ============================================================================

(def ^:private usd [:kontor.commodity/symbol "USD"])

(defn- fresh []
  (let [conn (core/create-test-db)]
    (disposal/install! conn)
    (d/transact conn
                [{:kontor.commodity/symbol "USD" :kontor.commodity/name "US Dollar"
                  :kontor.commodity/precision 2}
                 ;; Founder entity (Sarah's individual books).
                 {:entity/code "SARAH"  :entity/name "Sarah Chen (Individual)"
                  :entity/kind :individual :entity/country "US"
                  :entity/functional-commodity usd}
                 ;; Journals.
                 {:journal/code "GEN"     :journal/type :general :journal/active true}
                 {:journal/code "CASH"    :journal/type :cash    :journal/active true}
                 ;; Founder-side chart.
                 {:account/path "Assets:Bank"               :account/type :asset
                  :account/commodity usd}
                 {:account/path "Assets:Equipment"          :account/type :asset
                  :account/commodity usd}
                 {:account/path "Assets:Investment-SARAHCO" :account/type :asset
                  :account/commodity usd}
                 ;; Corp-side chart (placeholders — corp shares the
                 ;; same chart in this test for simplicity; in real
                 ;; usage the corp has its own chart of accounts).
                 {:account/path "Corp:Assets:Bank"             :account/type :asset
                  :account/commodity usd}
                 {:account/path "Corp:Assets:Equipment"        :account/type :asset
                  :account/commodity usd}
                 {:account/path "Corp:Equity:Common-Stock"     :account/type :equity
                  :account/commodity usd}
                 {:account/path "Corp:Equity:APIC"             :account/type :equity
                  :account/commodity usd}
                 {:account/path "Corp:Equity:Retained-Earnings" :account/type :equity
                  :account/commodity usd}
                 {:account/path "Corp:Liabilities:Dividends-Payable"
                  :account/type :liability :account/commodity usd}
                 ;; Founder dividend-income account (used by the
                 ;; receive! step in the lifecycle test).
                 {:account/path "Income:Dividends"  :account/type :income
                  :account/commodity usd}])
    conn))

(def ^:private sarah [:entity/code "SARAH"])

;; ============================================================================
;; §1. Plumbing — incorporate-tx-data validation
;; ============================================================================

(deftest required-opts-trap
  (let [conn (fresh)
        db   (d/db conn)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":corp-spec required"
                          (incorp/incorporate-tx-data db {})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":contributions must be non-empty"
                          (incorp/incorporate-tx-data
                           db {:corp-spec {:code "X" :name "X"
                                           :functional-commodity usd}
                               :founder-entity sarah
                               :journal [:journal/code "GEN"]
                               :effective-date #inst "2026-01-01"})))))

;; ============================================================================
;; §2. Pure-cash contribution — basis = amount → NO disposal emitted
;; ============================================================================

(deftest incorporate-cash-only-creates-corp-entity-and-books-opening
  (testing "Sarah forms SARAHCO with $50k cash, 5000 shares @ $0.01 par"
    (let [conn (fresh)]
      (incorp/incorporate!
       conn
       {:corp-spec {:code "SARAHCO" :name "SarahCo LLC"
                    :functional-commodity usd
                    :legal-form "LLC"}
        :founder-entity sarah
        :journal [:journal/code "GEN"]
        :effective-date #inst "2026-01-01"
        :external-id "incorp-2026-01"
        :recorded-by-uid "sarah"
        :contributions
        [{:account [:account/path "Corp:Assets:Bank"]
          :amount  50000M
          :commodity usd
          :basis   50000M}]    ; basis = amount → no disposal
        :founder-contributions
        [{:account [:account/path "Assets:Bank"]
          :basis   50000M
          :commodity usd}]
        :common-stock-account [:account/path "Corp:Equity:Common-Stock"]
        :additional-paid-in-capital-account [:account/path "Corp:Equity:APIC"]
        :founder-investment-account [:account/path "Assets:Investment-SARAHCO"]
        :shares-issued {:par 0.01M :count 5000M}})

      (let [db (d/db conn)
            corp-eid (d/q '[:find ?e . :in $ ?c :where [?e :entity/code ?c]]
                          db "SARAHCO")]
        (testing "the new :entity is materialised"
          (is (some? corp-eid))
          (is (= "SarahCo LLC" (:entity/name (d/entity db corp-eid))))
          (is (= "LLC" (:entity/legal-form (d/entity db corp-eid)))))

        (testing "no disposal emitted (basis = amount)"
          (is (empty? (disposal/disposals-in-period
                       db {:from #inst "2026-01-01" :to #inst "2026-12-31"})))))))

  (testing "even with multiple cash contributions"
    (let [conn (fresh)]
      (incorp/incorporate!
       conn
       {:corp-spec {:code "DUO" :name "Duo LLC"
                    :functional-commodity usd :legal-form "LLC"}
        :founder-entity sarah
        :journal [:journal/code "GEN"]
        :effective-date #inst "2026-01-01"
        :external-id "incorp-duo"
        :recorded-by-uid "sarah"
        :contributions
        [{:account [:account/path "Corp:Assets:Bank"]
          :amount 30000M :commodity usd :basis 30000M}]
        :founder-contributions
        [{:account [:account/path "Assets:Bank"]
          :basis 30000M :commodity usd}]
        :common-stock-account [:account/path "Corp:Equity:Common-Stock"]
        :additional-paid-in-capital-account [:account/path "Corp:Equity:APIC"]
        :founder-investment-account [:account/path "Assets:Investment-SARAHCO"]
        :shares-issued {:par 1M :count 1000M}})
      (let [db (d/db conn)]
        (is (some? (d/q '[:find ?e . :in $ ?c :where [?e :entity/code ?c]]
                        db "DUO")))))))

;; ============================================================================
;; §3. Appreciated property — basis ≠ amount → disposal IS emitted
;; ============================================================================

(deftest incorporate-with-appreciated-property-emits-disposal
  (testing "Sarah contributes equipment FMV $20k / basis $12k → :disposal emitted"
    (let [conn (fresh)]
      (incorp/incorporate!
       conn
       {:corp-spec {:code "SARAHCO" :name "SarahCo LLC"
                    :functional-commodity usd
                    :legal-form "LLC"}
        :founder-entity sarah
        :journal [:journal/code "GEN"]
        :effective-date #inst "2026-01-01"
        :external-id "incorp-appreciated"
        :recorded-by-uid "sarah"
        :contributions
        [{:account [:account/path "Corp:Assets:Bank"]
          :amount  50000M :commodity usd :basis 50000M}    ; cash — no disposal
         {:account [:account/path "Corp:Assets:Equipment"]
          :amount  20000M :commodity usd :basis 12000M     ; FMV > basis → disposal
          :acquired-on #inst "2023-06-01"
          :elective-regime [:us-§351-incorporation-rollover]}]
        :founder-contributions
        [{:account [:account/path "Assets:Bank"]      :basis 50000M :commodity usd}
         {:account [:account/path "Assets:Equipment"] :basis 12000M :commodity usd}]
        :common-stock-account [:account/path "Corp:Equity:Common-Stock"]
        :additional-paid-in-capital-account [:account/path "Corp:Equity:APIC"]
        :founder-investment-account [:account/path "Assets:Investment-SARAHCO"]
        :shares-issued {:par 0.01M :count 5000M}})

      (let [db (d/db conn)
            disps (disposal/disposals-in-period
                   db {:from #inst "2026-01-01" :to #inst "2026-12-31"})]
        (is (= 1 (count disps))
            "only the appreciated equipment contribution emits a disposal")
        (let [d (first disps)]
          (is (= :incorporation-contribution (:disposal/kind d)))
          (is (== 20000M (:disposal/proceeds-amount d))
              "proceeds = FMV at contribution date")
          (is (== 12000M (:disposal/basis-amount d))
              "basis = founder's basis (carries via §351)")
          (is (= #{:us-§351-incorporation-rollover}
                 (set (:disposal/elective-regime d)))
              "§351 elective-regime flag preserved for the CGT provider"))))))

;; ============================================================================
;; §4. Dividend verbs — declare + distribute
;; ============================================================================

(deftest declare-and-distribute-dividend
  (testing "two-stage dividend: declare (accrual) then distribute (cash)"
    (let [conn (fresh)]
      ;; Stage 1: declare $5,000 dividend.
      (book/declare-dividend!
       conn {:debit-account  [:account/path "Corp:Equity:Retained-Earnings"]
             :credit-account [:account/path "Corp:Liabilities:Dividends-Payable"]
             :amount 5000M :commodity usd
             :effective-date #inst "2026-12-15"})
      ;; Stage 2: pay it.
      (book/distribute-dividend!
       conn {:debit-account  [:account/path "Corp:Liabilities:Dividends-Payable"]
             :credit-account [:account/path "Corp:Assets:Bank"]
             :amount 5000M :commodity usd
             :effective-date #inst "2026-12-31"})

      (let [db (d/db conn)
            balance-on (fn [acc-path]
                         (reduce + 0M
                                 (d/q '[:find [?amt ...] :in $ ?path
                                        :where [?a :account/path ?path]
                                        [?p :posting/account ?a]
                                        [?p :posting/amount ?amt]]
                                      db acc-path)))]
        ;; Retained Earnings: -5000 (debit-decreasing-equity)
        (is (== 5000M (balance-on "Corp:Equity:Retained-Earnings")))
        ;; Dividends Payable: declared +5000, paid -5000 → 0
        (is (== 0M (balance-on "Corp:Liabilities:Dividends-Payable")))
        ;; Bank: -5000 (cash out)
        (is (== -5000M (balance-on "Corp:Assets:Bank"))))))

  (testing "shareholder side: founder records the dividend receipt as income"
    (let [conn (fresh)]
      ;; Sarah receives the $5k dividend.
      (book/receive!
       conn {:debit-account  [:account/path "Assets:Bank"]
             :credit-account [:account/path "Income:Dividends"]
             :amount 5000M :commodity usd
             :effective-date #inst "2026-12-31"})
      (let [db (d/db conn)
            inc (reduce + 0M
                        (d/q '[:find [?amt ...]
                               :where [?a :account/path "Income:Dividends"]
                               [?p :posting/account ?a]
                               [?p :posting/amount ?amt]]
                             db))]
        ;; Income:Dividends credited → balance is negative (credit-positive
        ;; for income in the substrate's debit-positive convention).
        (is (== -5000M inc)
            "Income:Dividends carries the dividend; investment-income
             regime (DE Abgeltungsteuer / US qualified-dividend / FR PFU)
             then taxes it via the PIT provider")))))
