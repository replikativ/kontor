(ns kontor.reporting.report-test
  "Stages 3a + 3b of research note 99 — the report engine as a family
   of quotient epimorphisms σ_E (ADR-096), and `:kontor.posting/dimensions`
   classification axes (ADR-097). `marginalize` is the σ_E primitive;
   the `run-engine` methods (`:account-codes`, `:tax-tags`, the new
   generic `:dimension`) are per-line views of it. Acceptance:
   `marginalize` over `:account-type` reproduces the balance sheet
   (the classes sum to zero — Ker σ — and each class is correct), the
   historical engines stay behaviour-identical, and a posting booked
   with `:kontor.posting/dimensions` aggregates over its custom axis."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.core :as core]
            [kontor.reporting.report :as report]))

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
                 {:kontor.journal/code "SALE" :kontor.journal/type :sale}
                 {:kontor.journal/code "CASH" :kontor.journal/type :cash}
                 {:kontor.account-tag/name "revenue-box" :kontor.account-tag/applicability :account}
                 {:kontor.account/path "Assets:Cash"        :kontor.account/code "1000" :kontor.account/type :asset}
                 {:kontor.account/path "Assets:Receivable"  :kontor.account/code "1200" :kontor.account/type :asset}
                 {:kontor.account/path "Income:Sales"       :kontor.account/code "8000" :kontor.account/type :income
                  :kontor.account/tags [[:kontor.account-tag/name "revenue-box"]]}
                 {:kontor.account/path "Expenses:Supplies"  :kontor.account/code "6000" :kontor.account/type :expense}])
    (book/sell! conn {:debit-account [:kontor.account/path "Assets:Receivable"]
                      :credit-account [:kontor.account/path "Income:Sales"]
                      :amount 1000 :commodity [:kontor.commodity/symbol "EUR"]
                      :effective-date d1})
    (book/pay! conn {:debit-account [:kontor.account/path "Expenses:Supplies"]
                     :credit-account [:kontor.account/path "Assets:Cash"]
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
        m    (report/marginalize ps #(if (neg? (:kontor.posting/amount %)) :cr :dr)
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
        ar-eid (d/q '[:find ?a . :where [?a :kontor.account/path "Assets:Receivable"]]
                    (d/db conn))
        all    (report/report-postings conn)
        narrow (report/report-postings conn {:posting-filter [['?p :kontor.posting/account ar-eid]]})]
    (is (= 4 (count all)))
    (is (= 1 (count narrow)) "only the AR leg")
    (is (== 1000M (-> (report/marginalize narrow :account-type {:sign :raw})
                      :asset amt)))))

;; ============================================================================
;; Stage 3b — :kontor.posting/dimensions classification axes (ADR-097)
;; ============================================================================

(defn- fresh-dimensioned-book
  "A conn with one 100-EUR expense entry split across two cost centres
   plus an undimensioned cash leg — booked via the verb facade."
  []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro" :kontor.commodity/precision 2}
                 {:kontor.journal/code "GEN" :kontor.journal/type :general}
                 {:kontor.account/path "Expenses:Travel"  :kontor.account/type :expense}
                 {:kontor.account/path "Expenses:Meals"   :kontor.account/type :expense}
                 {:kontor.account/path "Assets:Cash"      :kontor.account/type :asset}])
    (book/adjust! conn {:commodity [:kontor.commodity/symbol "EUR"]
                        :effective-date d1
                        :postings
                        [{:account [:kontor.account/path "Expenses:Travel"] :amount 60
                          :dimensions {:cost-center "CC-Sales" :project "PRJ-Alpha"}}
                         {:account [:kontor.account/path "Expenses:Meals"] :amount 40
                          :dimensions {:cost-center "CC-Ops"}}
                         {:account [:kontor.account/path "Assets:Cash"] :amount -100}]})
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
;; inclusive twin.
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

;; ============================================================================
;; S1 P1.a — :strict-commodity? on marginalize / run-engine
;;
;; The kernel substrate for FX-on-tax-emission (ADR-099 Addendum 5) translates
;; non-functional-commodity inputs before computing. But the report engine
;; (the σ_E layer below it) historically summed posting amounts regardless of
;; commodity — silently wrong when the same account holds postings in mixed
;; commodities (a real scenario for cash-pooled MNEs). Note 168 §2 S1 P1.a:
;; opt-in `:strict-commodity?` lets a consumer say "I am asserting this slice
;; is monocommodity — fail loudly if it isn't" instead of receiving a
;; meaningless Money.
;;
;; Default is false to preserve back-compat (every existing caller knew its
;; postings were monocommodity); the opt is forward-pointed for tax + close +
;; consolidation flows that will start asserting it.
;; ============================================================================

(defn- mixed-commodity-book
  "A book with TWO commodities (EUR + USD) posted against the SAME account.
   Used to exercise the strict-commodity check; the sum (1000 + 500) is
   genuinely meaningless because the units differ, even though the
   substrate happily produces a single BigDecimal."
  []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro" :kontor.commodity/precision 2}
                 {:kontor.commodity/symbol "USD" :kontor.commodity/name "US Dollar" :kontor.commodity/precision 2}
                 {:kontor.journal/code "MIX" :kontor.journal/type :general}
                 {:kontor.account/path "Assets:Cash"      :kontor.account/code "1000" :kontor.account/type :asset}
                 {:kontor.account/path "Equity:Owner"     :kontor.account/code "3000" :kontor.account/type :equity}])
    (book/entry! conn {:debit-account [:kontor.account/path "Assets:Cash"]
                       :credit-account [:kontor.account/path "Equity:Owner"]
                       :amount 1000 :commodity [:kontor.commodity/symbol "EUR"]
                       :journal [:kontor.journal/code "MIX"]
                       :effective-date d1})
    (book/entry! conn {:debit-account [:kontor.account/path "Assets:Cash"]
                       :credit-account [:kontor.account/path "Equity:Owner"]
                       :amount 500 :commodity [:kontor.commodity/symbol "USD"]
                       :journal [:kontor.journal/code "MIX"]
                       :effective-date d1})
    conn))

(deftest strict-commodity-default-false-preserves-silent-sum
  (testing "Without :strict-commodity?, marginalize sums across commodities
            silently — the historical behaviour every caller currently depends on."
    (let [conn (mixed-commodity-book)
          ps   (report/report-postings conn)
          m    (report/marginalize ps :account-type {:sign :raw})]
      ;; cash side: EUR 1000 + USD 500 = (meaningless) 1500
      (is (== 1500M (amt (:asset m))))
      ;; equity side: -EUR 1000 + -USD 500 = -1500
      (is (== -1500M (amt (:equity m)))))))

(deftest strict-commodity-true-throws-on-mixed-input
  (testing ":strict-commodity? true raises :report/mixed-commodity when a
            class spans more than one commodity."
    (let [conn (mixed-commodity-book)
          ps   (report/report-postings conn)
          ex   (try (report/marginalize ps :account-type
                                        {:sign :raw :strict-commodity? true})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= :report/mixed-commodity (:type (ex-data ex))))
      (is (= 2 (count (:commodities (ex-data ex))))))))

(deftest strict-commodity-true-stays-quiet-when-classes-are-monocommodity
  (testing "When the postings happen to be monocommodity per class,
            :strict-commodity? true is a no-op — fresh-book is EUR-only."
    (let [conn (fresh-book)
          ps   (report/report-postings conn)
          m    (report/marginalize ps :account-type
                                   {:sign :raw :strict-commodity? true})]
      (is (== 700M  (amt (:asset m))))
      (is (== -1000M (amt (:income m))))
      (is (== 300M  (amt (:expense m)))))))

(deftest strict-commodity-threads-through-account-codes-engine
  (testing "The :account-codes run-engine method also accepts
            :strict-commodity? and raises on mixed input."
    (let [conn (mixed-commodity-book)
          ps   (report/report-postings conn)
          ex   (try (report/run-engine ps
                                       {:engine :account-codes
                                        :codes ["1000"]
                                        :strict-commodity? true}
                                       nil)
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= :report/mixed-commodity (:type (ex-data ex)))))))
