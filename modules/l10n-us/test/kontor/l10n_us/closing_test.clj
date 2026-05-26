(ns kontor.l10n-us.closing-test
  "Tests for kontor.l10n-us.closing — wrapping the kernel closer with
   QuickBooks-Online-default account routing.

   Scenario: a small US business in CA with one CA sales invoice and
   two expense postings. End of FY2025 (calendar year): P&L collapses
   to retained earnings (account 3100), all P&L accounts zero on the
   next-year balance, balance sheet still balanced."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.balance :as balance]
            [kontor.core :as core]
            [kontor.l10n-us.chart :as chart]
            [kontor.l10n-us.closing :as us-closing]
            [kontor.l10n-us.invoice :as us-invoice]
            [kontor.posting :as posting]
            [kontor.validation :as v]))

(def jan-1     #inst "2025-01-01T00:00:00Z")
(def feb-15    #inst "2025-02-15T00:00:00Z")
(def jun-1     #inst "2025-06-01T00:00:00Z")
(def dec-31    #inst "2025-12-31T23:59:59Z")
(def jan-1-26  #inst "2026-01-01T00:00:00Z")

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (chart/install! conn)
    (d/transact conn
                [{:kontor.journal/code "INV" :kontor.journal/name "Sales"
                  :kontor.journal/type :sale :kontor.journal/active true}
                 {:kontor.journal/code "EXP" :kontor.journal/name "Expenses"
                  :kontor.journal/type :purchase :kontor.journal/active true}
                 {:kontor.period/start jan-1
                  :kontor.period/end   jan-1-26
                  :kontor.period/tag   :normal
                  :kontor.period/name  "FY2025"}])
    conn))

(defn- seed-fy2025!
  "Post two CA sales invoices and two operating-expense entries
   inside FY2025."
  [conn]
  ;; Sales invoice: $1000 net @ 7.25% (CA) → $1072.50 gross on Feb 15.
  (us-invoice/post-us-invoice! conn
                               {:kontor.invoice/external-id "INV-2025-1"
                                :kontor.invoice/issue-date feb-15
                                :kontor.invoice/ship-to-state :CA
                                :kontor.invoice/rate 0.0725M
                                :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                                                 :kontor.invoice-line/unit-price 1000M}]})
  ;; Expense: $600 office expense paid from bank on Feb 15.
  (let [db (d/db conn)
        usd (:db/id (d/entity db [:kontor.commodity/symbol "USD"]))
        bank (ace db "1100")
        office (ace db "6000")    ; Expenses:Office
        rent (ace db "6100")      ; Expenses:Rent
        exp-jnl (:db/id (d/entity db [:kontor.journal/code "EXP"]))]
    (v/transact-with-validation
     conn
     (posting/build-transaction
      {:transaction
       {:kontor.transaction/external-id "EXP-2025-1"
        :kontor.transaction/journal exp-jnl
        :kontor.transaction/effective-date feb-15
        :kontor.transaction/narration "Office supplies Feb"
        :kontor.transaction/state :posted
        :kontor.transaction/posted-at feb-15}
       :postings
       [{:kontor.posting/account office :kontor.posting/amount 600M
         :kontor.posting/commodity usd :kontor.posting/posted-at feb-15}
        {:kontor.posting/account bank   :kontor.posting/amount -600M
         :kontor.posting/commodity usd :kontor.posting/posted-at feb-15}]}))
    ;; Expense: $400 rent paid from bank on Jun 1.
    (v/transact-with-validation
     conn
     (posting/build-transaction
      {:transaction
       {:kontor.transaction/external-id "EXP-2025-2"
        :kontor.transaction/journal exp-jnl
        :kontor.transaction/effective-date jun-1
        :kontor.transaction/narration "Office rent Jun"
        :kontor.transaction/state :posted
        :kontor.transaction/posted-at jun-1}
       :postings
       [{:kontor.posting/account rent :kontor.posting/amount 400M
         :kontor.posting/commodity usd :kontor.posting/posted-at jun-1}
        {:kontor.posting/account bank :kontor.posting/amount -400M
         :kontor.posting/commodity usd :kontor.posting/posted-at jun-1}]})))
  ;; Second sales invoice to push revenue past expenses ($500 net @ 7.25%).
  (us-invoice/post-us-invoice! conn
                               {:kontor.invoice/external-id "INV-2025-2"
                                :kontor.invoice/issue-date jun-1
                                :kontor.invoice/ship-to-state :CA
                                :kontor.invoice/rate 0.0725M
                                :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                                                 :kontor.invoice-line/unit-price 500M}]})
  ;; Net P&L: revenue -1500 (Cr), office +600 (Dr), rent +400 (Dr)
  ;;         → -500 (profit of 500)
  nil)

;; ============================================================================
;; Sanity: pre-close balances
;; ============================================================================

(deftest pre-close-pnl-balances
  (testing "FY2025 has revenue 1500 (Cr), office 600 (Dr), rent 400 (Dr)
            → net profit 500."
    (let [conn (bootstrap)]
      (seed-fy2025! conn)
      (let [db (d/db conn)
            usd (:db/id (d/entity db [:kontor.commodity/symbol "USD"]))
            rev   (ace db "4000")
            office (ace db "6000")
            rent  (ace db "6100")]
        (is (= -1500M (-> (balance/account-balance conn rev
                                                   {:as-of-valid dec-31})
                          (get usd) :amount)))
        (is (= 600M (-> (balance/account-balance conn office
                                                 {:as-of-valid dec-31})
                        (get usd) :amount)))
        (is (= 400M (-> (balance/account-balance conn rent
                                                 {:as-of-valid dec-31})
                        (get usd) :amount)))))))

;; ============================================================================
;; close-us-fiscal-year! — end-to-end
;; ============================================================================

(deftest fiscal-year-close-rolls-pnl-to-3100
  (testing "Close FY2025 → P&L accounts zero on Jan 1 2026,
            retained earnings (3100) carries the profit, balance
            sheet equation still holds."
    (let [conn (bootstrap)]
      (seed-fy2025! conn)
      (let [db (d/db conn)
            period-eid (d/q '[:find ?p . :where [?p :kontor.period/name "FY2025"]] db)
            {:keys [close-result period-close-tx-report]}
            (us-closing/close-us-fiscal-year! conn
                                              {:period-eid period-eid
                                               :external-id "FY25-US-CLOSE"})]
        (is (some? (:transaction-eid close-result))
            "Close emitted a closing transaction")
        (is (some? period-close-tx-report)
            "Period soft-closed after the closing tx")
        ;; net-by-commodity should reflect the net P&L = -500 (profit).
        (let [db (d/db conn)
              usd (:db/id (d/entity db [:kontor.commodity/symbol "USD"]))
              net (get-in close-result [:net-by-commodity usd :amount])]
          (is (= -500M net)
              "Net P&L: revenue -1500 + office 600 + rent 400 = -500
               (sign convention: income credits are negative)"))
        ;; P&L accounts at the new year start: should be zero.
        (let [db (d/db conn)
              usd (:db/id (d/entity db [:kontor.commodity/symbol "USD"]))
              rev    (ace db "4000")
              office (ace db "6000")
              rent   (ace db "6100")
              retained (ace db "3100")
              new-yr-bal (fn [eid]
                           (-> (balance/account-balance conn eid
                                                        {:as-of-valid jan-1-26})
                               (get usd)
                               :amount))]
          (is (zero? (.compareTo ^java.math.BigDecimal (new-yr-bal rev) 0M))
              "Revenue zeroed")
          (is (zero? (.compareTo ^java.math.BigDecimal (new-yr-bal office) 0M))
              "Office expense zeroed")
          (is (zero? (.compareTo ^java.math.BigDecimal (new-yr-bal rent) 0M))
              "Rent expense zeroed")
          ;; Retained earnings carries the prior-year profit. Sign:
          ;; income credits are negative; the closer posts the negation
          ;; of each P&L line and lands the sum on retained earnings.
          (is (= -500M (new-yr-bal retained))
              "Retained earnings carries the prior-year profit"))))))

;; ============================================================================
;; Idempotency: cannot close twice
;; ============================================================================

(deftest cannot-close-period-twice
  (let [conn (bootstrap)]
    (seed-fy2025! conn)
    (let [period-eid (d/q '[:find ?p . :where [?p :kontor.period/name "FY2025"]] (d/db conn))]
      (us-closing/close-us-fiscal-year! conn
                                        {:period-eid period-eid
                                         :external-id "FY25-US-CLOSE-1"})
      (is (thrown? clojure.lang.ExceptionInfo
                   (us-closing/close-us-fiscal-year!
                    conn
                    {:period-eid period-eid
                     :external-id "FY25-US-CLOSE-2"}))
          "Second close attempt on the same period is rejected"))))

;; ============================================================================
;; Auto-create CLOSE journal if missing
;; ============================================================================

(deftest auto-creates-close-journal
  (testing "If the CLOSE journal isn't pre-seeded, close-us-fiscal-
            year! creates it on first call. INV + EXP journals are
            still required because the seed activity uses them."
    (let [conn (core/create-test-db)
          _ (v/install-invariants! conn)
          _ (chart/install! conn)
          _ (d/transact conn [{:kontor.journal/code "INV" :kontor.journal/name "Sales"
                               :kontor.journal/type :sale :kontor.journal/active true}
                              {:kontor.journal/code "EXP" :kontor.journal/name "Expenses"
                               :kontor.journal/type :purchase :kontor.journal/active true}
                              {:kontor.period/start jan-1
                               :kontor.period/end jan-1-26
                               :kontor.period/tag :normal
                               :kontor.period/name "FY2025"}])
          _ (seed-fy2025! conn)
          period-eid (d/q '[:find ?p . :where [?p :kontor.period/name "FY2025"]] (d/db conn))]
      (is (nil? (:db/id (d/entity (d/db conn) [:kontor.journal/code "CLOSE"])))
          "CLOSE journal not present before close")
      (us-closing/close-us-fiscal-year! conn
                                        {:period-eid period-eid
                                         :external-id "AUTO-J-CLOSE"})
      (is (some? (:db/id (d/entity (d/db conn) [:kontor.journal/code "CLOSE"])))
          "CLOSE journal auto-created"))))

;; ============================================================================
;; Missing-account error
;; ============================================================================

(deftest missing-retained-earnings-throws
  (testing "If the US chart isn't installed (no 3100), the planner
            throws a clear error."
    (let [conn (core/create-test-db)]
      (v/install-invariants! conn)
      (d/transact conn [{:kontor.period/start jan-1
                         :kontor.period/end jan-1-26
                         :kontor.period/tag :normal
                         :kontor.period/name "FY2025"}])
      (let [period-eid (d/q '[:find ?p . :where [?p :kontor.period/name "FY2025"]] (d/db conn))]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Retained-earnings account"
             (us-closing/close-us-fiscal-year!
              conn {:period-eid period-eid})))))))

;; ============================================================================
;; Override the retained-earnings code (LLC / partnership variant)
;; ============================================================================

(deftest custom-retained-code-routes-elsewhere
  (testing "Caller can override :retained-code to a chart account
            other than 3100 — useful for LLCs that prefer Members'
            Equity, or partnerships preferring Partners' Capital.
            Here we redirect the close to the existing 3000 (Owner's
            equity) account."
    (let [conn (bootstrap)]
      (seed-fy2025! conn)
      (let [period-eid (d/q '[:find ?p . :where [?p :kontor.period/name "FY2025"]] (d/db conn))]
        (us-closing/close-us-fiscal-year! conn
                                          {:period-eid period-eid
                                           :retained-code "3000"
                                           :external-id "FY25-OWNER-CLOSE"})
        (let [db (d/db conn)
              usd (:db/id (d/entity db [:kontor.commodity/symbol "USD"]))
              owner (ace db "3000")
              retained (ace db "3100")
              bal (fn [eid] (-> (balance/account-balance conn eid
                                                         {:as-of-valid jan-1-26})
                                (get usd) :amount))]
          (is (= -500M (bal owner))
              "Profit landed on Owner's equity (3000)")
          ;; bal retained may be nil (no postings on 3100) — treat that
          ;; as the untouched state.
          (is (or (nil? (bal retained))
                  (zero? (.compareTo ^java.math.BigDecimal (bal retained) 0M)))
              "3100 untouched"))))))

;; ============================================================================
;; ADR-068 plan-* pure form
;; ============================================================================

(deftest plan-us-fiscal-year-close-resolves-eids
  (let [conn (bootstrap)
        db (d/db conn)
        period-eid (d/q '[:find ?p . :where [?p :kontor.period/name "FY2025"]] db)
        planned (us-closing/plan-us-fiscal-year-close-tx-data
                 db {:period-eid period-eid
                     :external-id "PLAN-1"})]
    (is (= period-eid (:period-eid planned)))
    (is (some? (:retained-earnings-eid planned))
        "Retained-earnings eid resolved from the chart")
    (is (not (contains? planned :retained-code))
        ":retained-code stripped after resolution")
    (is (= "PLAN-1" (:external-id planned))
        "Pass-throughs preserved")))

(deftest plan-without-period-eid-throws
  (let [conn (bootstrap)
        db (d/db conn)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (us-closing/plan-us-fiscal-year-close-tx-data db {})))))

;; ============================================================================
;; Sum-to-zero after close
;; ============================================================================

(deftest sum-to-zero-after-close
  (testing "Every posting in the DB sums to zero across all accounts
            and all commodities after the close — kernel invariant."
    (let [conn (bootstrap)]
      (seed-fy2025! conn)
      (let [period-eid (d/q '[:find ?p . :where [?p :kontor.period/name "FY2025"]] (d/db conn))]
        (us-closing/close-us-fiscal-year! conn
                                          {:period-eid period-eid
                                           :external-id "FY25-Z"}))
      (let [db (d/db conn)
            pairs (d/q '[:find ?p ?amt
                         :where [?p :kontor.posting/amount ?amt]] db)
            total (reduce (fn [^java.math.BigDecimal acc [_ ^java.math.BigDecimal x]]
                            (.add acc x))
                          0M pairs)]
        (is (zero? (.compareTo ^java.math.BigDecimal total 0M))
            (str "All postings must sum to zero, got " total))))))
