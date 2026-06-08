(ns kontor.asset.depreciation-run-test
  "ADR-055: the DepreciationProvider protocol + built-ins + the runner.

   Covers:
   - StraightLineProvider/plan-schedule: equal periods, Σ = base.
   - run-depreciation!: fires every due occurrence, posts the GL
     entries (sealed), drives :kontor.asset/status → :fully-depreciated on
     completion, idempotent on re-run.
   - revise-book! + re-plan: a useful-life revision re-spreads only
     the un-fired tail; fired occurrences keep their amounts.
   - DecliningBalanceProvider: declining amounts, Σ = base, optional
     switch-to-straight-line.
   - SumOfYearsDigitsProvider: accelerated, Σ = base.
   - UnitsOfProductionProvider + the runner's :units seam."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.asset.asset :as asset]
            [kontor.asset.depreciation :as dep]
            [kontor.asset.depreciation-provider :as dp]
            [kontor.asset.runner :as runner]
            [kontor.asset.schema :as asset-schema]
            [kontor.core :as core]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (asset-schema/install! conn)
    (d/transact conn
                [{:db/id "eur" :kontor.commodity/symbol "EUR" :kontor.commodity/precision 2}
                 {:kontor.partner/external-id "U-buyer" :kontor.partner/name "Asset Buyer"}
                 {:db/id "acct-machinery"
                  :kontor.account/code "0210" :kontor.account/name "Machinery"
                  :kontor.account/type :asset :kontor.account/active true}
                 {:db/id "acct-accum"
                  :kontor.account/code "0299" :kontor.account/name "Accumulated Depreciation"
                  :kontor.account/type :asset :kontor.account/active true}
                 {:db/id "acct-dep-expense"
                  :kontor.account/code "6220" :kontor.account/name "Depreciation Expense"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:db/id "ledger-hgb"
                  :kontor.ledger/code "hgb" :kontor.ledger/name "Handelsbilanz"
                  :kontor.ledger/type :primary :kontor.ledger/framework :HGB
                  :kontor.ledger/active true}
                 {:db/id "journal-gen"
                  :kontor.journal/code "GEN" :kontor.journal/name "General"
                  :kontor.journal/type :general}
                 {:db/id "class-machinery"
                  :kontor.asset-class/code "machinery"
                  :kontor.asset-class/name "Machinery & Equipment"
                  :kontor.asset-class/default-useful-life-months 120}])
    conn))

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

(defn- uid       [db] (ref-eid db :kontor.partner/external-id "U-buyer"))
(defn- commodity [db] (ref-eid db :kontor.commodity/symbol "EUR"))
(defn- acct      [db code] (ref-eid db :kontor.account/code code))
(defn- hgb       [db] (ref-eid db :kontor.ledger/code "hgb"))
(defn- journal   [db] (ref-eid db :kontor.journal/code "GEN"))
(defn- class-eid [db] (ref-eid db :kontor.asset-class/code "machinery"))

(defn- acquire-machine!
  ([conn code] (acquire-machine! conn code 120000.00M))
  ([conn code cost]
   (let [db (d/db conn)]
     (asset/acquire! conn
                     {:code code
                      :name (str "Machine " code)
                      :class (class-eid db)
                      :acquisition-cost cost
                      :acquisition-commodity (commodity db)
                      :acquisition-date #inst "2026-01-15"
                      :in-service? true
                      :salvage-value 0M
                      :asset-account (acct db "0210")
                      :accumulated-account (acct db "0299")
                      :expense-account (acct db "6220")
                      :changed-by-uid (uid db)})
     (asset/by-code (d/db conn) code))))

(def ^:private far-future #inst "2060-01-01")

;; ============================================================================
;; Straight-line provider + the runner
;; ============================================================================

(deftest straight-line-plan-is-equal-periods
  (let [conn (bootstrap)
        _ (acquire-machine! conn "SL-1")
        _ (dep/open-book! conn {:asset "SL-1" :ledger (hgb (d/db conn))
                                :provider-id :straight-line
                                :useful-life-months 120})
        book (dep/book-for (d/db conn) "SL-1" (hgb (d/db conn)))
        plan (dp/plan-schedule (dp/provider-for :straight-line) {:db (d/db conn) :book book})]
    (is (= 120 (count (:periods plan))))
    (is (= 120000.00M (:total plan)))
    (is (= #{1000.00M} (set (map :amount (:periods plan))))
        "120 equal €1,000 periods")
    (is (= :straight-line (:provider-id plan)))))

(deftest runner-fires-posts-and-completes
  (let [conn (bootstrap)
        _ (acquire-machine! conn "SL-RUN")
        _ (dep/open-book! conn {:asset "SL-RUN" :ledger (hgb (d/db conn))
                                :provider-id :straight-line
                                :useful-life-months 120})
        book (dep/book-for (d/db conn) "SL-RUN" (hgb (d/db conn)))
        result (runner/run-depreciation! conn book
                                         {:journal (journal (d/db conn))
                                          :as-of far-future
                                          :changed-by-uid (uid (d/db conn))})]
    (testing "every occurrence fires; the run reports the total"
      (is (= 120 (:count result)))
      (is (= 120000.00M (:total result)))
      (is (true? (:completed? result))))
    (testing "accumulated depreciation tracks the occurrence log"
      (is (= 120000.00M (dep/accumulated-depreciation (d/db conn) book)))
      (is (= 0M (dep/net-book-value (d/db conn) book))))
    (testing "the asset is driven to :fully-depreciated"
      (is (= :fully-depreciated
             (:kontor.asset/status (asset/pull-asset (d/db conn) "SL-RUN")))))
    (testing "the GL entries are posted (sealed) and tagged with the book's ledger"
      (let [db (d/db conn)
            posted (d/q '[:find [?p ...]
                          :in $ ?acct
                          :where
                          [?p :kontor.posting/account ?acct]
                          [?p :kontor.posting/posted-at _]]
                        db (acct db "6220"))]
        (is (= 120 (count posted)) "120 sealed depreciation-expense postings")
        (is (every? #(= (hgb db) (:db/id (:kontor.posting/ledger (d/pull db [:kontor.posting/ledger] %))))
                    posted))))
    (testing "re-running fires nothing — record-occurrence! is idempotent"
      (let [again (runner/run-depreciation! conn book
                                            {:journal (journal (d/db conn))
                                             :as-of far-future})]
        (is (= 0 (:count again)))))))

(deftest revise-book-re-plans-only-the-unfired-tail
  (let [conn (bootstrap)
        _ (acquire-machine! conn "SL-REV")
        _ (dep/open-book! conn {:asset "SL-REV" :ledger (hgb (d/db conn))
                                :provider-id :straight-line
                                :useful-life-months 120})
        book (dep/book-for (d/db conn) "SL-REV" (hgb (d/db conn)))
        ;; Fire the first 24 months (2026-01 … 2027-12).
        run1 (runner/run-depreciation! conn book
                                       {:journal (journal (d/db conn))
                                        :as-of #inst "2028-01-01"})]
    (testing "24 months fired @ €1,000"
      (is (= 24 (:count run1)))
      (is (= 24000.00M (dep/accumulated-depreciation (d/db conn) book))))
    (testing "revise total useful life 120 → 60 months"
      (dep/revise-book! conn {:book book :new-useful-life-months 60
                              :note "IAS 16 review — life shortened"})
      ;; The forward plan now spreads the remaining €96,000 over the
      ;; un-fired 36 periods; fired periods keep their €1,000.
      (let [plan (dp/plan-schedule (dp/provider-for :straight-line) {:db (d/db conn) :book book})
            by-seq (into {} (map (juxt :sequence :amount)) (:periods plan))]
        (is (= 60 (count (:periods plan))))
        (is (= 1000.00M (by-seq 24)) "a fired period keeps its amount")
        (is (= 2666.67M (by-seq 25)) "the tail re-spreads (96,000 / 36)")
        (is (= 120000.00M (:total plan)))))
    (testing "running the rest fires exactly the 36 re-planned periods"
      (let [run2 (runner/run-depreciation! conn book
                                           {:journal (journal (d/db conn))
                                            :as-of far-future
                                            :changed-by-uid (uid (d/db conn))})]
        (is (= 36 (:count run2)))
        (is (true? (:completed? run2)))
        (is (= 120000.00M (dep/accumulated-depreciation (d/db conn) book)))))))

;; ============================================================================
;; Declining-balance
;; ============================================================================

(deftest declining-balance-declines-and-sums-to-base
  (let [conn (bootstrap)
        _ (acquire-machine! conn "DB-1")
        _ (dep/open-book! conn {:asset "DB-1" :ledger (hgb (d/db conn))
                                :provider-id :declining-balance
                                :useful-life-months 60
                                :method-params {:kontor.asset-method-params/rate-multiple 2M}})
        book (dep/book-for (d/db conn) "DB-1" (hgb (d/db conn)))
        plan (dp/plan-schedule (dp/provider-for :declining-balance) {:db (d/db conn) :book book})
        amts (mapv :amount (:periods plan))]
    (is (= 60 (count amts)))
    (is (= 120000.00M (:total plan)) "Σ = depreciable-base exactly")
    (is (> (first amts) (nth amts 1)) "period 1 > period 2 — declining")
    (is (> (nth amts 1) (nth amts 10)) "still declining further out")
    (is (= 0M (:basis-remaining (last (:periods plan))))
        "the final period drives book value to salvage")))

(deftest declining-balance-switch-to-straight-line
  (let [conn (bootstrap)
        _ (acquire-machine! conn "DB-SW")
        _ (dep/open-book! conn
                          {:asset "DB-SW" :ledger (hgb (d/db conn))
                           :provider-id :declining-balance
                           :useful-life-months 60
                           :method-params {:kontor.asset-method-params/rate-multiple 2M
                                           :kontor.asset-method-params/switch-to-straight-line true}})
        book (dep/book-for (d/db conn) "DB-SW" (hgb (d/db conn)))
        plan (dp/plan-schedule (dp/provider-for :declining-balance) {:db (d/db conn) :book book})
        amts (mapv :amount (:periods plan))]
    (is (= 120000.00M (:total plan)))
    (testing "after the switch the tail is flat — equal consecutive amounts"
      (is (some (fn [[a b]] (= a b))
                (map vector (butlast (rest amts)) (rest (rest amts))))
          "at least one pair of equal consecutive interior periods"))))

;; ============================================================================
;; Sum-of-years'-digits
;; ============================================================================

(deftest sum-of-years-digits-is-accelerated
  (let [conn (bootstrap)
        _ (acquire-machine! conn "SYD-1")
        _ (dep/open-book! conn {:asset "SYD-1" :ledger (hgb (d/db conn))
                                :provider-id :sum-of-years-digits
                                :useful-life-months 60})
        book (dep/book-for (d/db conn) "SYD-1" (hgb (d/db conn)))
        plan (dp/plan-schedule (dp/provider-for :sum-of-years-digits) {:db (d/db conn) :book book})
        amts (mapv :amount (:periods plan))]
    (is (= 60 (count amts)))
    (is (= 120000.00M (:total plan)) "Σ = depreciable-base")
    (is (> (first amts) (last amts)) "front-loaded — accelerated")
    (is (apply >= amts) "monotonically non-increasing")))

;; ============================================================================
;; Units-of-production
;; ============================================================================

(deftest units-of-production-runner-uses-the-units-seam
  (let [conn (bootstrap)
        _ (acquire-machine! conn "UOP-1")
        _ (dep/open-book! conn
                          {:asset "UOP-1" :ledger (hgb (d/db conn))
                           :provider-id :units-of-production
                           :useful-life-months 60
                           :method-params {:kontor.asset-method-params/total-units 100000M}})
        book (dep/book-for (d/db conn) "UOP-1" (hgb (d/db conn)))
        plan (dp/plan-schedule (dp/provider-for :units-of-production) {:db (d/db conn) :book book})]
    (testing "the plan is unit-rate-driven, not fully forward-computable"
      (is (true? (:requires-units plan)))
      (is (= 1.2M (:rate-per-unit plan)) "120,000 base / 100,000 units"))
    (testing "the runner consumes per-occurrence :units actuals"
      (let [result (runner/run-depreciation!
                    conn book
                    {:journal (journal (d/db conn))
                     :as-of #inst "2026-04-01"
                     :units {1 1000M 2 2000M 3 1500M}})]
        (is (= 3 (:count result)))
        ;; 1.2 × (1000 + 2000 + 1500) = 5,400
        (is (= 5400.00M (:total result)))
        (is (= 5400.00M (dep/accumulated-depreciation (d/db conn) book)))))
    (testing "a units-of-production run with no :units for a due period throws"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"no :units supplied"
           (runner/run-depreciation! conn book
                                     {:journal (journal (d/db conn))
                                      :as-of #inst "2026-06-01"}))))))

;; ============================================================================
;; Review-after fixes
;; ============================================================================

(deftest declining-balance-honours-explicit-depreciable-base
  ;; Code-review: a tax book with a bonus-reduced base must
  ;; depreciate exactly :depreciable-base, not acquisition-cost.
  (let [conn (bootstrap)
        _ (acquire-machine! conn "DB-BASE" 100000.00M)
        _ (dep/open-book! conn {:asset "DB-BASE" :ledger (hgb (d/db conn))
                                :provider-id :declining-balance
                                :useful-life-months 60
                                :depreciable-base 70000.00M
                                :method-params {:kontor.asset-method-params/rate-multiple 2M}})
        book (dep/book-for (d/db conn) "DB-BASE" (hgb (d/db conn)))
        plan (dp/plan-schedule (dp/provider-for :declining-balance) {:db (d/db conn) :book book})]
    (is (= 70000.00M (:total plan))
        "Σ = the explicit :depreciable-base, not the €100,000 cost")))

(deftest declining-balance-ceiling-rate-caps-the-rate
  (let [conn (bootstrap)
        _ (acquire-machine! conn "DB-CAP" 100000.00M)
        _ (acquire-machine! conn "DB-UNCAP" 100000.00M)
        ;; 24-month life, 2.5× — uncapped per-period rate ≈ 0.104;
        ;; an annual 0.25 ceiling → per-period cap ≈ 0.0208, which binds.
        _ (dep/open-book! conn {:asset "DB-CAP" :ledger (hgb (d/db conn))
                                :provider-id :declining-balance
                                :useful-life-months 24
                                :method-params {:kontor.asset-method-params/rate-multiple 2.5M
                                                :kontor.asset-method-params/ceiling-rate 0.25M}})
        _ (dep/open-book! conn {:asset "DB-UNCAP" :ledger (hgb (d/db conn))
                                :provider-id :declining-balance
                                :useful-life-months 24
                                :method-params {:kontor.asset-method-params/rate-multiple 2.5M}})
        p1 (fn [code]
             (-> (dp/plan-schedule (dp/provider-for :declining-balance)
                                   {:db (d/db conn)
                                    :book (dep/book-for (d/db conn) code (hgb (d/db conn)))})
                 :periods first :amount))]
    (is (< (p1 "DB-CAP") (p1 "DB-UNCAP"))
        "the annual ceiling caps the first period below the uncapped rate")))

(deftest builtins-reject-non-full-convention
  (let [conn (bootstrap)
        _ (acquire-machine! conn "CONV-1" 120000.00M)
        _ (dep/open-book! conn {:asset "CONV-1" :ledger (hgb (d/db conn))
                                :provider-id :straight-line
                                :useful-life-months 120
                                :convention :half-year})
        book (dep/book-for (d/db conn) "CONV-1" (hgb (d/db conn)))]
    (testing "a built-in fails loud on a non-:full convention rather than silently computing :full"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"(?i):full convention only"
           (dp/plan-schedule (dp/provider-for :straight-line) {:db (d/db conn) :book book}))))))

(deftest runner-stops-at-disposal
  (let [conn (bootstrap)
        _ (acquire-machine! conn "RUN-DISP" 120000.00M)
        _ (dep/open-book! conn {:asset "RUN-DISP" :ledger (hgb (d/db conn))
                                :provider-id :straight-line
                                :useful-life-months 120})
        book (dep/book-for (d/db conn) "RUN-DISP" (hgb (d/db conn)))
        ;; Record a disposal event 6 months in (raw transact — keeps
        ;; the test off the approval machinery, exercised elsewhere).
        _ (d/transact conn [{:kontor.asset-event/asset (asset/by-code (d/db conn) "RUN-DISP")
                             :kontor.asset-event/kind :disposal
                             :kontor.asset-event/date #inst "2026-07-15"}])
        result (runner/run-depreciation! conn book
                                         {:journal (journal (d/db conn))
                                          :as-of far-future})]
    (testing "the runner fires only the occurrences before the disposal date"
      ;; 2026-01-15 … 2026-06-15 = 6 occurrences strictly before 2026-07-15.
      (is (= 6 (:count result)))
      (is (= #inst "2026-07-15" (:disposal-date result))))))

(deftest runner-refuses-locked-period
  (let [conn (bootstrap)
        _ (acquire-machine! conn "RUN-LOCK" 120000.00M)
        _ (dep/open-book! conn {:asset "RUN-LOCK" :ledger (hgb (d/db conn))
                                :provider-id :straight-line
                                :useful-life-months 120})
        book (dep/book-for (d/db conn) "RUN-LOCK" (hgb (d/db conn)))
        ;; A soft-closed period covering all of 2026, no journal scope.
        _ (d/transact conn [{:kontor.period/start #inst "2026-01-01"
                             :kontor.period/end #inst "2027-01-01"
                             :kontor.period/locked-at #inst "2027-01-15"}])]
    (testing "firing a depreciation charge into a soft-closed period throws"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"closed period"
           (runner/run-depreciation! conn book
                                     {:journal (journal (d/db conn))
                                      :as-of far-future}))))))
