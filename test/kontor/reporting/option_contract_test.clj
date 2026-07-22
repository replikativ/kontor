(ns kontor.reporting.option-contract-test
  "The read side's option contract, asserted STRUCTURALLY.

   Background: `compute-statement` used to rebuild its option map from an
   allowlist —

       (cond-> {} from (assoc :from from), to (assoc :to to), …)

   — which silently discarded every key it was not written to know about.
   `:through`, the inclusive window bound that `compute-report` documents
   and recommends, was one of them: a statement scoped
   `:through #inst \"2026-12-31\"` fell back to NO upper bound and quietly
   summed later fiscal years into the period. `:posting-filter`,
   `:translate-to`, `:fx-provider` and `:rate-type` went the same way.

   A test per dropped option would only ever cover the options someone
   already thought about. So the contract is asserted structurally
   instead:

     1. `report/check-options!` rejects any key outside `known-options`.
     2. Wrappers FORWARD their option map (dissoc'ing only their own keys)
        rather than rebuilding it.

   Together those make (1) observable through (2): a wrapper that drops
   unknown keys cannot propagate the error, so `unknown-option-*` below
   fails for ANY entry point that goes back to allowlist-rebuilding —
   including one written in the future for an option nobody has invented
   yet. That is the property worth owning; the per-option semantic checks
   further down are the belt to its braces."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.book :as book]
            [kontor.core :as core]
            [kontor.gate :as gate]
            [kontor.reporting.financial-statements :as fs]
            [kontor.reporting.report :as report]))

(def ^:private eur [:kontor.commodity/symbol "EUR"])

(defn- book
  "A two-account book with postings in 2026 and 2027, so a window bound
   that is silently dropped shows up as a different number rather than as
   a coincidence. Deliberately not built on an l10n chart — this is a
   kernel contract."
  []
  (let [conn (core/create-test-db)]
    (gate/transact-with-validation
     conn [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
            :kontor.commodity/precision 2}
           {:kontor.journal/code "GJ" :kontor.journal/type :general}
           {:kontor.account/path "Assets:Cash" :kontor.account/code "1000"
            :kontor.account/type :asset :kontor.account/active true}
           {:kontor.account/path "Income:Sales" :kontor.account/code "4000"
            :kontor.account/type :income :kontor.account/active true}])
    (doseq [[date amount] [[#inst "2026-06-01" 1000M]
                           ;; ON the year-end boundary — the entry an
                           ;; exclusive :to #inst "2026-12-31" would drop
                           [#inst "2026-12-31" 400M]
                           [#inst "2027-06-01" 7000M]]]
      (book/entry! conn {:debit-account  [:kontor.account/path "Assets:Cash"]
                         :credit-account [:kontor.account/path "Income:Sales"]
                         :amount         amount
                         :commodity      eur
                         :journal        [:kontor.journal/code "GJ"]
                         :effective-date date}))
    conn))

(def ^:private statement
  {:statement/name "T" :statement/country "DE"
   :statement/sections
   [{:section/code "1" :section/label "Revenue"
     :section/lines [{:line/code "1.1" :line/label "Sales" :line/codes ["4%"]}]}]})

(def ^:private equity-statement
  {:statement/name "T" :statement/country "DE"
   :statement/components
   [{:component/code "c" :component/label "c" :component/codes ["4%"]
     :component/movements [{:movement/code "m" :movement/label "m"
                            :movement/codes ["4%"]}]}]})

;; ---------------------------------------------------------------------------
;; 1. The engine rejects what it does not know
;; ---------------------------------------------------------------------------

(deftest unknown-option-is-rejected-by-the-engine
  (let [conn (book)]
    (testing "compute-report"
      (is (thrown? clojure.lang.ExceptionInfo
                   (report/compute-report conn {:report/name "r" :report/lines []}
                                          {:not-an-option 1}))))
    (testing "report-postings"
      (is (thrown? clojure.lang.ExceptionInfo
                   (report/report-postings conn {:not-an-option 1}))))
    (testing "the error names the offending key and the accepted set"
      (let [d (try (report/report-postings conn {:tho #inst "2026-12-31"})
                   (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :report/unknown-option (:type d)))
        (is (= #{:tho} (:unknown d)))
        (is (contains? (:known d) :through) "a near-miss typo can be diagnosed")))
    (testing "every documented option is accepted"
      ;; guards the inverse failure: check-options! must not reject a
      ;; legitimate key. :translate-to needs :fx-provider, so it is
      ;; covered by the translate test rather than here.
      (doseq [k (disj report/known-options :translate-to :fx-provider :rate-type)]
        (is (some? (report/report-postings conn {k nil}))
            (str k " must be an accepted option"))))))

;; ---------------------------------------------------------------------------
;; 2. THE structural property: every wrapper forwards rather than rebuilds
;; ---------------------------------------------------------------------------

(deftest wrappers-forward-their-option-map
  ;; If a wrapper rebuilds its opts from an allowlist, the bogus key never
  ;; reaches check-options! and nothing throws — so these assertions fail
  ;; precisely when a layer starts silently swallowing options again.
  (let [conn (book)
        window {:from #inst "2026-01-01" :through #inst "2026-12-31"}]
    (testing "compute-statement"
      (is (thrown? clojure.lang.ExceptionInfo
                   (fs/compute-statement conn statement {:not-an-option 1}))))
    (testing "compute-cash-flow"
      (is (thrown? clojure.lang.ExceptionInfo
                   (fs/compute-cash-flow conn statement
                                         (assoc window :not-an-option 1)))))
    (testing "compute-equity-changes"
      (is (thrown? clojure.lang.ExceptionInfo
                   (fs/compute-equity-changes conn equity-statement
                                              (assoc window :not-an-option 1)))))
    (testing "each wrapper still accepts its OWN option"
      (is (some? (fs/compute-statement conn statement
                                       (assoc window :total-sign-map {"1" :+}))))
      (is (some? (fs/compute-cash-flow conn statement
                                       (assoc window :reconcile-codes ["1%"])))))))

;; ---------------------------------------------------------------------------
;; 3. Semantics: the forwarded options actually take effect
;; ---------------------------------------------------------------------------

(deftest through-is-honoured-by-every-statement-entry-point
  ;; The concrete regression. 1000 + 400 in 2026, 7000 in 2027.
  (let [conn (book)
        total #(:amount (fs/section-subtotal % "1"))]
    (testing ":through is inclusive of its own date and excludes later years"
      (is (= 1400M (total (fs/compute-statement
                           conn statement {:from #inst "2026-01-01"
                                           :through #inst "2026-12-31"})))))
    (testing "the equivalent exclusive :to agrees"
      (is (= 1400M (total (fs/compute-statement
                           conn statement {:from #inst "2026-01-01"
                                           :to #inst "2027-01-01"})))))
    (testing "an exclusive :to on Dec 31 drops the Dec-31 entry"
      (is (= 1000M (total (fs/compute-statement
                           conn statement {:from #inst "2026-01-01"
                                           :to #inst "2026-12-31"})))))
    (testing "unbounded sees every year — the value a dropped :through used to yield"
      (is (= 8400M (total (fs/compute-statement
                           conn statement {:from #inst "2026-01-01"})))))
    (testing ":to and :through together is an error, not a silent precedence"
      (is (thrown? clojure.lang.ExceptionInfo
                   (fs/compute-statement conn statement
                                         {:to #inst "2027-01-01"
                                          :through #inst "2026-12-31"}))))))

(deftest resolved-window-is-reported-back
  ;; :statement/window must show the bounds actually applied, not the raw
  ;; request — otherwise a caller who passed :through cannot tell what was
  ;; computed.
  (let [computed (fs/compute-statement (book) statement
                                       {:from #inst "2026-01-01"
                                        :through #inst "2026-12-31"})]
    (is (= #inst "2027-01-01" (:to (:statement/window computed)))
        ":through is reported back as the canonical exclusive :to")
    (is (= #inst "2026-01-01" (:from (:statement/window computed))))))

(deftest posting-filter-is-honoured-by-compute-statement
  ;; A second forwarded option, to show the fix is general rather than a
  ;; special case for :through.
  (let [conn (book)
        all  (fs/compute-statement conn statement {})
        none (fs/compute-statement conn statement
                                   {:posting-filter
                                    [['?p :kontor.posting/amount 999999M]]})]
    (is (= 8400M (:amount (fs/section-subtotal all "1"))))
    (is (= 0M (:amount (fs/section-subtotal none "1")))
        ":posting-filter reaches the engine instead of being dropped")))
