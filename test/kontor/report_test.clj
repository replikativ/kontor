(ns kontor.report-test
  "Stages 3a + 3b of research note 99 — the report engine as a family
   of quotient epimorphisms σ_E (ADR-096), and `:posting/dimensions`
   classification axes (ADR-097). `marginalize` is the σ_E primitive;
   the `run-engine` methods (`:account-codes`, `:tax-tags`, the new
   generic `:dimension`) are per-line views of it. Acceptance:
   `marginalize` over `:account-type` reproduces the balance sheet
   (the classes sum to zero — Ker σ — and each class is correct), the
   historical engines stay behaviour-identical, and a posting booked
   with `:posting/dimensions` aggregates over its custom axis."
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
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro" :kontor.commodity/precision 2}
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
                      :amount 1000 :commodity [:kontor.commodity/symbol "EUR"]
                      :effective-date d1})
    (book/pay! conn {:debit-account [:account/path "Expenses:Supplies"]
                     :credit-account [:account/path "Assets:Cash"]
                     :amount 300 :commodity [:kontor.commodity/symbol "EUR"]
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

;; ============================================================================
;; Stage 3b — :posting/dimensions classification axes (ADR-097)
;; ============================================================================

(defn- fresh-dimensioned-book
  "A conn with one 100-EUR expense entry split across two cost centres
   plus an undimensioned cash leg — booked via the verb facade."
  []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro" :kontor.commodity/precision 2}
                 {:journal/code "GEN" :journal/type :general}
                 {:account/path "Expenses:Travel"  :account/type :expense}
                 {:account/path "Expenses:Meals"   :account/type :expense}
                 {:account/path "Assets:Cash"      :account/type :asset}])
    (book/adjust! conn {:commodity [:kontor.commodity/symbol "EUR"]
                        :effective-date d1
                        :postings
                        [{:account [:account/path "Expenses:Travel"] :amount 60
                          :dimensions {:cost-center "CC-Sales" :project "PRJ-Alpha"}}
                         {:account [:account/path "Expenses:Meals"] :amount 40
                          :dimensions {:cost-center "CC-Ops"}}
                         {:account [:account/path "Assets:Cash"] :amount -100}]})
    conn))

(deftest marginalize-over-a-posting-dimension-axis
  (let [conn (fresh-dimensioned-book)
        m    (report/marginalize (report/report-postings conn) :cost-center {:sign :raw})]
    (testing "each cost-centre class sums its postings"
      (is (== 60M (amt (get m "CC-Sales"))))
      (is (== 40M (amt (get m "CC-Ops")))))
    (testing "the undimensioned cash leg is absent from a covering"
      (is (= #{"CC-Sales" "CC-Ops"} (set (keys m)))))))

(deftest dimension-engine-over-a-posting-dimension-axis
  (let [conn (fresh-dimensioned-book)
        rpt  {:report/name "by project + cost centre"
              :report/lines
              [{:line/code "PA" :line/label "Project Alpha"
                :line/expression {:engine :dimension :dimension :project
                                  :match "PRJ-Alpha"}}
               {:line/code "CCS" :line/label "Cost centre Sales"
                :line/expression {:engine :dimension :dimension :cost-center
                                  :match "CC-Sales"}}]}
        out  (report/compute-report conn rpt)]
    (is (== 60M (:amount (report/line-value out "PA"))))
    (is (== 60M (:amount (report/line-value out "CCS")))
        "the travel posting carries both axes")))

(deftest a-posting-carries-several-axes-independently
  (let [conn (fresh-dimensioned-book)
        ps   (report/report-postings conn)]
    (testing "marginalizing the same postings on a different axis repartitions them"
      (is (== 60M (amt (-> (report/marginalize ps :project {:sign :raw})
                           (get "PRJ-Alpha"))))))))

;; ============================================================================
;; F11 / I-10 regression — `:through` inclusive-end sugar
;;
;; Pre-fix, the off-by-one between intuitive ("FY ends Dec 31") and the
;; substrate's exclusive `:to` (`< 2026-12-31`) silently dropped any
;; posting effective on the last day of the period. `:through` is the
;; inclusive twin (note 160 §I-10).
;; ============================================================================

(deftest through-is-inclusive-end-of-window
  (let [conn (fresh-dimensioned-book)
        ;; `:through #inst "2026-12-31"` should be equivalent to
        ;; `:to #inst "2027-01-01"` (= day-after :through, exclusive).
        thr         #inst "2026-12-31"
        to+1        #inst "2027-01-01"
        via-through (count (report/report-postings conn {:through thr}))
        via-to+1    (count (report/report-postings conn {:to to+1}))]
    (is (= via-through via-to+1)
        ":through 2026-12-31 yields the same windowed postings as :to 2027-01-01")))

(deftest through-and-to-are-mutually-exclusive
  (let [conn (fresh-dimensioned-book)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"either :to .* or :through .*, not both"
                          (report/report-postings conn
                            {:to #inst "2027-01-01"
                             :through #inst "2026-12-31"})))))
