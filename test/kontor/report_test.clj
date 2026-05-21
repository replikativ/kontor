(ns kontor.report-test
  "Stage 3a of research note 99 — the report engine as a family of
   quotient epimorphisms σ_E (ADR-096). `marginalize` is the σ_E
   primitive; the `run-engine` methods (`:account-codes`, `:tax-tags`,
   the new generic `:dimension`) are per-line views of it. Acceptance:
   `marginalize` over `:account-type` reproduces the balance sheet
   (the classes sum to zero — Ker σ — and each class is correct), and
   the historical engines stay behaviour-identical."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.core :as core]
            [kontor.report :as report]))

;; ============================================================================
;; Fixture — a tiny posted book, built through the Stage-1 verb facade
;; ============================================================================

(def ^:private d1 #inst "2026-03-15")

(defn- fresh-book
  "A schema-loaded conn with a coded chart + journals, then two posted
   transactions: a 1000 sale on account and a 300 cash expense."
  []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:commodity/symbol "EUR" :commodity/name "Euro" :commodity/precision 2}
                 {:journal/code "SALE" :journal/type :sale}
                 {:journal/code "CASH" :journal/type :cash}
                 {:account-tag/name "revenue-box" :account-tag/applicability :account}
                 {:account/path "Assets:Cash"        :account/code "1000" :account/type :asset}
                 {:account/path "Assets:Receivable"  :account/code "1200" :account/type :asset}
                 {:account/path "Income:Sales"       :account/code "8000" :account/type :income
                  :account/tags [[:account-tag/name "revenue-box"]]}
                 {:account/path "Expenses:Supplies"  :account/code "6000" :account/type :expense}])
    (book/sell! conn {:debit-account [:account/path "Assets:Receivable"]
                      :credit-account [:account/path "Income:Sales"]
                      :amount 1000 :commodity [:commodity/symbol "EUR"]
                      :effective-date d1})
    (book/pay! conn {:debit-account [:account/path "Expenses:Supplies"]
                     :credit-account [:account/path "Assets:Cash"]
                     :amount 300 :commodity [:commodity/symbol "EUR"]
                     :effective-date d1})
    conn))

(defn- amt [class-result] (:amount (:value class-result)))

;; ============================================================================
;; marginalize — the σ_E primitive
;; ============================================================================

(deftest marginalize-over-account-type-reproduces-the-balance-sheet
  (let [conn (fresh-book)
        ps   (report/report-postings conn)
        m    (report/marginalize ps :account-type {:sign :raw})]
    (testing "each account-type class nets correctly"
      (is (== 700M  (amt (:asset m)))   "AR +1000, Cash -300")
      (is (== -1000M (amt (:income m))) "revenue is credit-natural")
      (is (== 300M  (amt (:expense m))) "expense is debit-natural"))
    (testing "the partition is exhaustive — classes sum to zero (Ker σ)"
      (is (== 0M (reduce + 0M (map amt (vals m))))))
    (testing "every posting lands in exactly one class (true partition)"
      (is (= 4 (reduce + (map (comp count :postings) (vals m))))))))

(deftest marginalize-accepts-a-custom-dimension-fn
  (let [conn (fresh-book)
        ps   (report/report-postings conn)
        ;; partition by sign of the stored amount
        m    (report/marginalize ps #(if (neg? (:posting/amount %)) :cr :dr)
                                 {:sign :raw})]
    (is (== 1300M  (amt (:dr m))) "AR 1000 + Expense 300")
    (is (== -1300M (amt (:cr m))) "Revenue -1000 + Cash -300")))

;; ============================================================================
;; The :dimension engine — σ_E for one class, through compute-report
;; ============================================================================

(deftest dimension-engine-sums-one-account-type-class
  (let [conn (fresh-book)
        rpt  {:report/name "by type"
              :report/lines
              [{:line/code "INC" :line/label "Income"
                :line/expression {:engine :dimension :dimension :account-type
                                  :match :income}}
               {:line/code "EXP" :line/label "Expense"
                :line/expression {:engine :dimension :dimension :account-type
                                  :match :expense}}]}
        out  (report/compute-report conn rpt)]
    (is (== -1000M (:amount (report/line-value out "INC"))))
    (is (== 300M   (:amount (report/line-value out "EXP"))))))

(deftest dimension-engine-handles-the-set-valued-account-tags-axis
  (let [conn (fresh-book)
        rpt  {:report/name "tagged"
              :report/lines
              [{:line/code "R" :line/label "Revenue box"
                :line/expression {:engine :dimension :dimension :account-tags
                                  :match :revenue-box}}]}
        out  (report/compute-report conn rpt)]
    (is (== -1000M (:amount (report/line-value out "R")))
        "the revenue posting carries the account's :revenue-box tag")))

;; ============================================================================
;; The historical engines stay behaviour-identical
;; ============================================================================

(deftest account-codes-engine-unchanged
  (let [conn (fresh-book)
        rpt  {:report/name "codes"
              :report/lines
              [{:line/code "A" :line/label "All assets (1xxx)"
                :line/expression {:engine :account-codes :codes ["1%"]}}]}
        out  (report/compute-report conn rpt)]
    (is (== 700M (:amount (report/line-value out "A")))
        "Cash 1000 (-300) + Receivable 1200 (+1000)")))

(deftest tax-tags-engine-unchanged
  (let [conn (fresh-book)
        rpt  {:report/name "tags"
              :report/lines
              [{:line/code "T" :line/label "Revenue via tag"
                :line/expression {:engine :tax-tags :tags [:revenue-box]}}]}
        out  (report/compute-report conn rpt)]
    (is (== -1000M (:amount (report/line-value out "T"))))))

;; ============================================================================
;; :posting-filter narrows the candidate scan
;; ============================================================================

(deftest posting-filter-narrows-the-scan
  (let [conn   (fresh-book)
        ar-eid (d/q '[:find ?a . :where [?a :account/path "Assets:Receivable"]]
                    (d/db conn))
        all    (report/report-postings conn)
        narrow (report/report-postings conn {:posting-filter [['?p :posting/account ar-eid]]})]
    (is (= 4 (count all)))
    (is (= 1 (count narrow)) "only the AR leg")
    (is (== 1000M (-> (report/marginalize narrow :account-type {:sign :raw})
                      :asset amt)))))
