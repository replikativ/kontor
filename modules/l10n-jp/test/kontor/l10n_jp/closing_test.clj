(ns kontor.l10n-jp.closing-test
  "Tests for kontor.l10n-jp.closing — wrapping the kernel closer with
   the J-GAAP-style starter chart's retained-earnings routing.

   Two scenarios:
     1. March-31 fiscal year (the most common JP pattern): FY2025
        ends 2026-03-31, with sales and operating expenses booked
        through the year. Close rolls P&L to `330000` (繰越利益剰余金).
     2. Calendar-year fiscal year (common for subsidiaries of foreign
        multinationals and SMEs): FY2025 ends 2025-12-31. Same close
        mechanics — only the period bounds differ. Demonstrates the
        wrapper does NOT enforce a March 31 ending."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.balance :as balance]
            [kontor.core :as core]
            [kontor.l10n-jp.chart :as chart]
            [kontor.l10n-jp.closing :as jp-closing]
            [kontor.posting :as posting]
            [kontor.validation :as v]))

;; March-31 FY2025
(def apr-1     #inst "2025-04-01T00:00:00Z")
(def may-15    #inst "2025-05-15T00:00:00Z")
(def sep-1     #inst "2025-09-01T00:00:00Z")
(def mar-31-26 #inst "2026-03-31T23:59:59Z")
(def apr-1-26  #inst "2026-04-01T00:00:00Z")

;; Calendar-year FY2025
(def jan-1     #inst "2025-01-01T00:00:00Z")
(def feb-15    #inst "2025-02-15T00:00:00Z")
(def jun-1     #inst "2025-06-01T00:00:00Z")
(def dec-31    #inst "2025-12-31T23:59:59Z")
(def jan-1-26  #inst "2026-01-01T00:00:00Z")

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :account/code ?c]] db code))

(defn- bootstrap
  "Install schema + JP chart + INV/EXP journals + named fiscal period."
  [{:keys [period-name period-start period-end]}]
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (chart/install! conn)
    (d/transact conn
                [{:journal/code "INV" :journal/name "Sales"
                  :journal/type :sale :journal/active true}
                 {:journal/code "EXP" :journal/name "Expenses"
                  :journal/type :purchase :journal/active true}
                 {:period/start period-start
                  :period/end   period-end
                  :period/tag   :normal
                  :period/name  period-name}])
    conn))

(defn- post-sale!
  "Standard-rate 10% sale (net + 10% JCT)."
  [conn external-id date net-amount]
  (let [db (d/db conn)
        jpy (:db/id (d/entity db [:kontor.commodity/symbol "JPY"]))
        rec (ace db "121000")       ; AR
        rev (ace db "411000")       ; Sales 10%
        out-tax (ace db "215100")   ; Output JCT 10%
        jnl (:db/id (d/entity db [:journal/code "INV"]))
        net (bigdec net-amount)
        tax (.setScale (.multiply net 0.10M) 0 java.math.RoundingMode/HALF_EVEN)
        gross (.add net tax)]
    (v/transact-with-validation
     conn
     (posting/build-transaction
      {:transaction
       {:transaction/external-id external-id
        :transaction/journal jnl
        :transaction/effective-date date
        :transaction/narration external-id
        :transaction/state :posted
        :transaction/posted-at date}
       :postings
       [{:posting/account rec :posting/amount gross
         :posting/commodity jpy :posting/posted-at date}
        {:posting/account rev :posting/amount (.negate net)
         :posting/commodity jpy :posting/posted-at date}
        {:posting/account out-tax :posting/amount (.negate tax)
         :posting/commodity jpy :posting/posted-at date}]}))))

(defn- post-expense!
  "Operating expense (no JCT for simplicity — exempt category)."
  [conn external-id date expense-code amount]
  (let [db (d/db conn)
        jpy (:db/id (d/entity db [:kontor.commodity/symbol "JPY"]))
        bank (ace db "110200")
        exp (ace db expense-code)
        jnl (:db/id (d/entity db [:journal/code "EXP"]))
        amt (bigdec amount)]
    (v/transact-with-validation
     conn
     (posting/build-transaction
      {:transaction
       {:transaction/external-id external-id
        :transaction/journal jnl
        :transaction/effective-date date
        :transaction/narration external-id
        :transaction/state :posted
        :transaction/posted-at date}
       :postings
       [{:posting/account exp :posting/amount amt
         :posting/commodity jpy :posting/posted-at date}
        {:posting/account bank :posting/amount (.negate amt)
         :posting/commodity jpy :posting/posted-at date}]}))))

;; ============================================================================
;; March-31 fiscal year — the common JP pattern
;; ============================================================================

(defn- seed-march31-fy! [conn]
  ;; Sales: 1,000,000 JPY net on May 15 and 500,000 on Sep 1.
  (post-sale! conn "INV-2025-1" may-15 1000000)
  (post-sale! conn "INV-2025-2" sep-1   500000)
  ;; Expenses: 600,000 salaries May 15, 400,000 rent Sep 1.
  (post-expense! conn "EXP-2025-1" may-15 "610000" 600000)  ; Salaries
  (post-expense! conn "EXP-2025-2" sep-1  "620000" 400000)  ; Rent
  ;; Net P&L: revenue -1,500,000 + 600,000 + 400,000 = -500,000 (profit 500k)
  nil)

(deftest pre-close-march31-pnl-balances
  (testing "March-31 fiscal year before close: revenue 1,500,000 (Cr),
            salaries 600,000 (Dr), rent 400,000 (Dr) → profit 500,000."
    (let [conn (bootstrap {:period-name "FY2025-Mar"
                           :period-start apr-1
                           :period-end   apr-1-26})]
      (seed-march31-fy! conn)
      (let [db (d/db conn)
            jpy (:db/id (d/entity db [:kontor.commodity/symbol "JPY"]))
            rev (ace db "411000")
            sal (ace db "610000")
            rent (ace db "620000")]
        (is (= -1500000M (-> (balance/account-balance conn rev
                                                      {:as-of-valid mar-31-26})
                             (get jpy) :amount)))
        (is (= 600000M (-> (balance/account-balance conn sal
                                                    {:as-of-valid mar-31-26})
                           (get jpy) :amount)))
        (is (= 400000M (-> (balance/account-balance conn rent
                                                    {:as-of-valid mar-31-26})
                           (get jpy) :amount)))))))

(deftest march31-close-rolls-pnl-to-330000
  (testing "Close FY2025 (March 31 fiscal-year-end): P&L accounts
            zero on Apr 1 2026, 繰越利益剰余金 (330000) carries the
            profit, balance sheet equation still holds."
    (let [conn (bootstrap {:period-name "FY2025-Mar"
                           :period-start apr-1
                           :period-end   apr-1-26})]
      (seed-march31-fy! conn)
      (let [period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025-Mar"]]
                            (d/db conn))
            {:keys [close-result period-close-tx-report]}
            (jp-closing/close-jp-fiscal-year!
             conn {:period-eid period-eid
                   :external-id "FY25-JP-CLOSE"})]
        (is (some? (:transaction-eid close-result))
            "Close emitted a closing transaction")
        (is (some? period-close-tx-report)
            "Period soft-closed after the closing tx")
        ;; Net P&L: profit 500,000 → net by commodity = -500,000.
        (let [db (d/db conn)
              jpy (:db/id (d/entity db [:kontor.commodity/symbol "JPY"]))
              net (get-in close-result [:net-by-commodity jpy :amount])]
          (is (= -500000M net)
              "Net P&L: revenue -1,500,000 + salaries 600,000 + rent
               400,000 = -500,000 (profit; sign convention: income
               credits are negative)"))
        ;; P&L accounts at the new year start: zero.
        (let [db (d/db conn)
              jpy (:db/id (d/entity db [:kontor.commodity/symbol "JPY"]))
              rev (ace db "411000")
              sal (ace db "610000")
              rent (ace db "620000")
              retained (ace db "330000")
              new-yr-bal (fn [eid]
                           (-> (balance/account-balance conn eid
                                                        {:as-of-valid apr-1-26})
                               (get jpy)
                               :amount))]
          (is (zero? (.compareTo ^java.math.BigDecimal (new-yr-bal rev) 0M))
              "Revenue zeroed")
          (is (zero? (.compareTo ^java.math.BigDecimal (new-yr-bal sal) 0M))
              "Salaries zeroed")
          (is (zero? (.compareTo ^java.math.BigDecimal (new-yr-bal rent) 0M))
              "Rent zeroed")
          (is (= -500000M (new-yr-bal retained))
              "繰越利益剰余金 carries the prior-year profit
               (negative on an equity account = credit balance =
               retained profit)"))))))

;; ============================================================================
;; Calendar-year fiscal year — the wrapper does NOT enforce March 31
;; ============================================================================

(defn- seed-calendar-year! [conn]
  ;; Sales: 800,000 JPY net on Feb 15 and 700,000 on Jun 1.
  (post-sale! conn "INV-2025-CY-1" feb-15 800000)
  (post-sale! conn "INV-2025-CY-2" jun-1  700000)
  ;; Expenses: 600,000 salaries Feb 15, 400,000 rent Jun 1.
  (post-expense! conn "EXP-2025-CY-1" feb-15 "610000" 600000)
  (post-expense! conn "EXP-2025-CY-2" jun-1  "620000" 400000)
  ;; Net P&L: revenue -1,500,000 + 600,000 + 400,000 = -500,000 (profit 500k)
  nil)

(deftest calendar-year-close-also-works
  (testing "December-31 fiscal-year-end variant — the wrapper does
            not enforce March 31 and handles any 12-month period
            the caller declares. Common for foreign-multinational JP
            subsidiaries and many SMEs."
    (let [conn (bootstrap {:period-name "FY2025-Cal"
                           :period-start jan-1
                           :period-end   jan-1-26})]
      (seed-calendar-year! conn)
      (let [period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025-Cal"]]
                            (d/db conn))
            {:keys [close-result]}
            (jp-closing/close-jp-fiscal-year!
             conn {:period-eid period-eid
                   :external-id "FY25-JP-CAL-CLOSE"})]
        (is (some? (:transaction-eid close-result)))
        (let [db (d/db conn)
              jpy (:db/id (d/entity db [:kontor.commodity/symbol "JPY"]))
              rev (ace db "411000")
              retained (ace db "330000")
              new-yr-bal (fn [eid]
                           (-> (balance/account-balance conn eid
                                                        {:as-of-valid jan-1-26})
                               (get jpy)
                               :amount))]
          (is (zero? (.compareTo ^java.math.BigDecimal (new-yr-bal rev) 0M))
              "Revenue zeroed at start of FY2026 (Jan 1)")
          (is (= -500000M (new-yr-bal retained))
              "繰越利益剰余金 holds the calendar-year profit"))))))

;; ============================================================================
;; Idempotency: cannot close twice
;; ============================================================================

(deftest cannot-close-period-twice
  (let [conn (bootstrap {:period-name "FY2025-Mar"
                         :period-start apr-1
                         :period-end   apr-1-26})]
    (seed-march31-fy! conn)
    (let [period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025-Mar"]]
                          (d/db conn))]
      (jp-closing/close-jp-fiscal-year!
       conn {:period-eid period-eid
             :external-id "FY25-JP-1"})
      (is (thrown? clojure.lang.ExceptionInfo
                   (jp-closing/close-jp-fiscal-year!
                    conn {:period-eid period-eid
                          :external-id "FY25-JP-2"}))
          "Second close attempt on the same period is rejected"))))

;; ============================================================================
;; Auto-create CLOSE journal if missing
;; ============================================================================

(deftest auto-creates-close-journal
  (testing "If CLOSE journal isn't pre-seeded, close-jp-fiscal-year!
            creates it on first call."
    (let [conn (core/create-test-db)
          _ (v/install-invariants! conn)
          _ (chart/install! conn)
          _ (d/transact conn [{:journal/code "INV" :journal/name "Sales"
                               :journal/type :sale :journal/active true}
                              {:journal/code "EXP" :journal/name "Expenses"
                               :journal/type :purchase :journal/active true}
                              {:period/start apr-1
                               :period/end apr-1-26
                               :period/tag :normal
                               :period/name "FY2025-Mar"}])
          _ (seed-march31-fy! conn)
          period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025-Mar"]]
                          (d/db conn))]
      (is (nil? (:db/id (d/entity (d/db conn) [:journal/code "CLOSE"])))
          "CLOSE journal not present before close")
      (jp-closing/close-jp-fiscal-year!
       conn {:period-eid period-eid
             :external-id "AUTO-J-JP-CLOSE"})
      (is (some? (:db/id (d/entity (d/db conn) [:journal/code "CLOSE"])))
          "CLOSE journal auto-created"))))

;; ============================================================================
;; Missing-account error
;; ============================================================================

(deftest missing-retained-earnings-throws
  (testing "If the JP chart isn't installed (no 330000), the planner
            throws a clear error."
    (let [conn (core/create-test-db)]
      (v/install-invariants! conn)
      (d/transact conn [{:period/start apr-1
                         :period/end apr-1-26
                         :period/tag :normal
                         :period/name "FY2025-Mar"}])
      (let [period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025-Mar"]]
                            (d/db conn))]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Retained-earnings account"
             (jp-closing/close-jp-fiscal-year!
              conn {:period-eid period-eid})))))))

;; ============================================================================
;; ADR-068 plan-* pure form
;; ============================================================================

(deftest plan-jp-fiscal-year-close-resolves-eids
  (let [conn (bootstrap {:period-name "FY2025-Mar"
                         :period-start apr-1
                         :period-end   apr-1-26})
        db (d/db conn)
        period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025-Mar"]] db)
        planned (jp-closing/plan-jp-fiscal-year-close-tx-data
                 db {:period-eid period-eid
                     :external-id "PLAN-JP-1"})]
    (is (= period-eid (:period-eid planned)))
    (is (some? (:retained-earnings-eid planned))
        "Retained-earnings eid resolved from the chart (330000)")
    (is (not (contains? planned :retained-code))
        ":retained-code stripped after resolution")
    (is (= "PLAN-JP-1" (:external-id planned))
        "Pass-throughs preserved")))

(deftest plan-without-period-eid-throws
  (let [conn (bootstrap {:period-name "FY2025-Mar"
                         :period-start apr-1
                         :period-end   apr-1-26})
        db (d/db conn)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (jp-closing/plan-jp-fiscal-year-close-tx-data db {})))))

;; ============================================================================
;; Sum-to-zero after close
;; ============================================================================

(deftest sum-to-zero-after-close
  (testing "Every posting in the DB sums to zero across all accounts
            and all commodities after the close — kernel invariant."
    (let [conn (bootstrap {:period-name "FY2025-Mar"
                           :period-start apr-1
                           :period-end   apr-1-26})]
      (seed-march31-fy! conn)
      (let [period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025-Mar"]]
                            (d/db conn))]
        (jp-closing/close-jp-fiscal-year!
         conn {:period-eid period-eid
               :external-id "FY25-JP-Z"}))
      (let [db (d/db conn)
            pairs (d/q '[:find ?p ?amt
                         :where [?p :posting/amount ?amt]] db)
            total (reduce (fn [^java.math.BigDecimal acc [_ ^java.math.BigDecimal x]]
                            (.add acc x))
                          0M pairs)]
        (is (zero? (.compareTo ^java.math.BigDecimal total 0M))
            (str "All postings must sum to zero, got " total))))))
