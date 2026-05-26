(ns kontor.l10n-in.closing-test
  "Tests for kontor.l10n-in.closing — wrapping the kernel closer
   with the Indian Schedule III / Ind AS retained-earnings routing.

   Scenario: a small Indian company runs FY24-25 (1 Apr 2024 –
   31 Mar 2025) with an intra-state sales invoice and two operating
   expenses, ending with a profit. End-of-year close rolls P&L
   into Reserves and Surplus (220900), zeros the P&L accounts on
   1 Apr 2025, and the balance sheet equation still holds."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.balance :as balance]
            [kontor.core :as core]
            [kontor.l10n-in.chart :as chart]
            [kontor.l10n-in.closing :as in-closing]
            [kontor.l10n-in.invoice :as in-invoice]
            [kontor.posting :as posting]
            [kontor.validation :as v]))

;; ============================================================================
;; FY24-25 period bounds (1 Apr 2024 – 31 Mar 2025 inclusive)
;; ============================================================================

(def apr-1-2024  #inst "2024-04-01T00:00:00Z")
(def jun-15-2024 #inst "2024-06-15T00:00:00Z")
(def sep-1-2024  #inst "2024-09-01T00:00:00Z")
(def dec-15-2024 #inst "2024-12-15T00:00:00Z")
(def mar-31-2025 #inst "2025-03-31T23:59:59Z")
(def apr-1-2025  #inst "2025-04-01T00:00:00Z")

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :account/code ?c]] db code))

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (chart/install! conn)
    (d/transact conn
                [{:journal/code "INV" :journal/name "Sales"
                  :journal/type :sale :journal/active true}
                 {:journal/code "EXP" :journal/name "Expenses"
                  :journal/type :purchase :journal/active true}
                 {:period/start apr-1-2024
                  :period/end   apr-1-2025
                  :period/tag   :normal
                  :period/name  "FY24-25"}])
    conn))

(defn- seed-fy-24-25!
  "Post one intra-state invoice and two operating-expense entries
   inside FY24-25."
  [conn]
  ;; Sales: ₹10,000 net @ 18% intra-state on Jun 15 2024
  (in-invoice/post-in-invoice!
   conn
   {:invoice/external-id "INV-FY25-1"
    :invoice/issue-date jun-15-2024
    :invoice/supplier-state "MH"
    :invoice/place-of-supply "MH"
    :invoice/lines [{:invoice-line/quantity 1
                     :invoice-line/unit-price 10000M
                     :invoice-line/tax-rate 0.18M}]})
  ;; Expense: ₹6,000 office expense paid from bank, Sep 1
  (let [db (d/db conn)
        inr (:db/id (d/entity db [:kontor.commodity/symbol "INR"]))
        bank (ace db chart/bank-code)
        office (ace db "550800")
        rent   (ace db "550100")
        exp-jnl (:db/id (d/entity db [:journal/code "EXP"]))]
    (v/transact-with-validation
     conn
     (posting/build-transaction
      {:transaction {:transaction/external-id "EXP-FY25-1"
                     :transaction/journal exp-jnl
                     :transaction/effective-date sep-1-2024
                     :transaction/narration "Office Sep"
                     :transaction/state :posted
                     :transaction/posted-at sep-1-2024}
       :postings [{:posting/account office :posting/amount 6000M
                   :posting/commodity inr :posting/posted-at sep-1-2024}
                  {:posting/account bank :posting/amount -6000M
                   :posting/commodity inr :posting/posted-at sep-1-2024}]}))
    ;; Rent ₹2,000 paid from bank, Dec 15
    (v/transact-with-validation
     conn
     (posting/build-transaction
      {:transaction {:transaction/external-id "EXP-FY25-2"
                     :transaction/journal exp-jnl
                     :transaction/effective-date dec-15-2024
                     :transaction/narration "Rent Dec"
                     :transaction/state :posted
                     :transaction/posted-at dec-15-2024}
       :postings [{:posting/account rent :posting/amount 2000M
                   :posting/commodity inr :posting/posted-at dec-15-2024}
                  {:posting/account bank :posting/amount -2000M
                   :posting/commodity inr :posting/posted-at dec-15-2024}]})))
  ;; Expected end-of-year P&L (intra-state taxes don't show up here —
  ;; they're liabilities, not P&L):
  ;;   revenue 410000  = -10,000  (credit balance)
  ;;   office 550800   =  +6,000
  ;;   rent 550100     =  +2,000
  ;;   net P&L         = -10,000 + 6,000 + 2,000 = -2,000 (profit ₹2,000)
  nil)

;; ============================================================================
;; Sanity: pre-close P&L balances
;; ============================================================================

(deftest pre-close-pnl-balances
  (testing "FY24-25 has revenue 10,000 (Cr), office 6,000 (Dr),
            rent 2,000 (Dr) → net profit 2,000."
    (let [conn (bootstrap)]
      (seed-fy-24-25! conn)
      (let [db (d/db conn)
            inr (:db/id (d/entity db [:kontor.commodity/symbol "INR"]))
            rev    (ace db chart/sales-domestic-code)
            office (ace db "550800")
            rent   (ace db "550100")]
        (is (= -10000M (-> (balance/account-balance conn rev
                                                    {:as-of-valid mar-31-2025})
                           (get inr) :amount)))
        (is (= 6000M (-> (balance/account-balance conn office
                                                  {:as-of-valid mar-31-2025})
                         (get inr) :amount)))
        (is (= 2000M (-> (balance/account-balance conn rent
                                                  {:as-of-valid mar-31-2025})
                         (get inr) :amount)))))))

;; ============================================================================
;; close-in-fiscal-year! — end-to-end
;; ============================================================================

(deftest fiscal-year-close-rolls-pnl-to-220900
  (testing "Close FY24-25 → P&L accounts zero on Apr 1 2025,
            Reserves & Surplus (220900) carries the profit, balance
            sheet equation still holds."
    (let [conn (bootstrap)]
      (seed-fy-24-25! conn)
      (let [db (d/db conn)
            period-eid (d/q '[:find ?p . :where [?p :period/name "FY24-25"]] db)
            {:keys [close-result period-close-tx-report]}
            (in-closing/close-in-fiscal-year! conn
                                              {:period-eid period-eid
                                               :external-id "FY25-IN-CLOSE"})]
        (is (some? (:transaction-eid close-result))
            "Close emitted a closing transaction")
        (is (some? period-close-tx-report)
            "Period soft-closed after the closing tx")
        ;; net-by-commodity reflects net P&L = -2000 (profit)
        (let [db (d/db conn)
              inr (:db/id (d/entity db [:kontor.commodity/symbol "INR"]))
              net (get-in close-result [:net-by-commodity inr :amount])]
          (is (= -2000M net)
              "Net P&L: revenue -10,000 + office 6,000 + rent 2,000 = -2,000
               (sign convention: income credits are negative)"))
        ;; P&L accounts at the new year start: should be zero
        (let [db (d/db conn)
              inr (:db/id (d/entity db [:kontor.commodity/symbol "INR"]))
              rev      (ace db chart/sales-domestic-code)
              office   (ace db "550800")
              rent     (ace db "550100")
              retained (ace db chart/retained-earnings-code)
              new-yr-bal (fn [eid]
                           (-> (balance/account-balance conn eid
                                                        {:as-of-valid apr-1-2025})
                               (get inr)
                               :amount))]
          (is (zero? (.compareTo ^java.math.BigDecimal (new-yr-bal rev) 0M))
              "Revenue zeroed")
          (is (zero? (.compareTo ^java.math.BigDecimal (new-yr-bal office) 0M))
              "Office expense zeroed")
          (is (zero? (.compareTo ^java.math.BigDecimal (new-yr-bal rent) 0M))
              "Rent expense zeroed")
          ;; Retained earnings credit balance — equity grows on the credit
          ;; side, so a profit shows as negative under the kernel's
          ;; signed-amount convention.
          (is (= -2000M (new-yr-bal retained))
              "Reserves & Surplus carries the prior-year profit"))))))

;; ============================================================================
;; Idempotency: cannot close twice
;; ============================================================================

(deftest cannot-close-period-twice
  (let [conn (bootstrap)]
    (seed-fy-24-25! conn)
    (let [period-eid (d/q '[:find ?p . :where [?p :period/name "FY24-25"]] (d/db conn))]
      (in-closing/close-in-fiscal-year! conn
                                        {:period-eid period-eid
                                         :external-id "FY25-IN-CLOSE-1"})
      (is (thrown? clojure.lang.ExceptionInfo
                   (in-closing/close-in-fiscal-year!
                    conn {:period-eid period-eid
                          :external-id "FY25-IN-CLOSE-2"}))
          "Second close attempt on the same period is rejected"))))

;; ============================================================================
;; Auto-create CLOSE journal if missing
;; ============================================================================

(deftest auto-creates-close-journal
  (testing "If the CLOSE journal isn't pre-seeded, close-in-fiscal-
            year! creates it on first call."
    (let [conn (core/create-test-db)
          _ (v/install-invariants! conn)
          _ (chart/install! conn)
          ;; Pre-seed INV + EXP for the test activity, leave CLOSE out
          _ (d/transact conn [{:journal/code "INV" :journal/name "Sales"
                               :journal/type :sale :journal/active true}
                              {:journal/code "EXP" :journal/name "Expenses"
                               :journal/type :purchase :journal/active true}
                              {:period/start apr-1-2024
                               :period/end   apr-1-2025
                               :period/tag   :normal
                               :period/name  "FY24-25"}])
          _ (seed-fy-24-25! conn)
          period-eid (d/q '[:find ?p . :where [?p :period/name "FY24-25"]] (d/db conn))]
      (is (nil? (:db/id (d/entity (d/db conn) [:journal/code "CLOSE"])))
          "CLOSE journal not present before close")
      (in-closing/close-in-fiscal-year! conn
                                        {:period-eid period-eid
                                         :external-id "AUTO-J-CLOSE"})
      (is (some? (:db/id (d/entity (d/db conn) [:journal/code "CLOSE"])))
          "CLOSE journal auto-created"))))

;; ============================================================================
;; Missing retained-earnings throws
;; ============================================================================

(deftest missing-retained-earnings-throws
  (testing "If the IN chart isn't installed (no 220900), the planner
            throws a clear error."
    (let [conn (core/create-test-db)]
      (v/install-invariants! conn)
      (d/transact conn [{:period/start apr-1-2024
                         :period/end apr-1-2025
                         :period/tag :normal
                         :period/name "FY24-25"}])
      (let [period-eid (d/q '[:find ?p . :where [?p :period/name "FY24-25"]] (d/db conn))]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Retained-earnings account"
             (in-closing/close-in-fiscal-year!
              conn {:period-eid period-eid})))))))

;; ============================================================================
;; ADR-068 plan-* pure form
;; ============================================================================

(deftest plan-in-fiscal-year-close-resolves-eids
  (let [conn (bootstrap)
        db (d/db conn)
        period-eid (d/q '[:find ?p . :where [?p :period/name "FY24-25"]] db)
        planned (in-closing/plan-in-fiscal-year-close-tx-data
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
                 (in-closing/plan-in-fiscal-year-close-tx-data db {})))))

;; ============================================================================
;; Sum-to-zero after close
;; ============================================================================

(deftest sum-to-zero-after-close
  (testing "Every posting in the DB sums to zero across all accounts
            after the FY24-25 close — kernel invariant."
    (let [conn (bootstrap)]
      (seed-fy-24-25! conn)
      (let [period-eid (d/q '[:find ?p . :where [?p :period/name "FY24-25"]] (d/db conn))]
        (in-closing/close-in-fiscal-year! conn
                                          {:period-eid period-eid
                                           :external-id "FY25-Z"}))
      (let [db (d/db conn)
            pairs (d/q '[:find ?p ?amt
                         :where [?p :posting/amount ?amt]] db)
            total (reduce (fn [^java.math.BigDecimal acc [_ ^java.math.BigDecimal x]]
                            (.add acc x))
                          0M pairs)]
        (is (zero? (.compareTo ^java.math.BigDecimal total 0M))
            (str "All postings must sum to zero, got " total))))))

;; ============================================================================
;; Custom retained-code (e.g. route to General Reserve instead)
;; ============================================================================

(deftest custom-retained-code-routes-to-different-equity-account
  (testing "Override :retained-code to land net P&L in 220200
            General Reserve instead of 220900 Retained Earnings."
    (let [conn (bootstrap)]
      (seed-fy-24-25! conn)
      (let [db (d/db conn)
            period-eid (d/q '[:find ?p . :where [?p :period/name "FY24-25"]] db)]
        (in-closing/close-in-fiscal-year!
         conn {:period-eid period-eid
               :external-id "FY25-GR"
               :retained-code "220200"}))
      (let [db (d/db conn)
            inr (:db/id (d/entity db [:kontor.commodity/symbol "INR"]))
            gen-reserve (ace db "220200")
            retained (ace db chart/retained-earnings-code)
            bal-of (fn [eid]
                     (or (-> (balance/account-balance conn eid
                                                      {:as-of-valid apr-1-2025})
                             (get inr) :amount)
                         0M))]
        (is (= -2000M (bal-of gen-reserve))
            "Profit lands in General Reserve")
        (is (zero? (.compareTo ^java.math.BigDecimal (bal-of retained) 0M))
            "Default Retained Earnings is untouched")))))
