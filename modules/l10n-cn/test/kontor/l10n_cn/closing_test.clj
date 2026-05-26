(ns kontor.l10n-cn.closing-test
  "Tests for kontor.l10n-cn.closing — wrapping the kernel closer with
   ASBE-default account routing (利润分配 — Retained earnings 3104,
   calendar fiscal year).

   Scenario: a small CN business with one CNY 13% manufacturing sale
   and two operating-expense postings inside FY2025. End of FY2025:
   P&L collapses to retained earnings (3104), all P&L accounts zero
   on the next-year balance, balance-sheet equation still holds."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.balance :as balance]
            [kontor.core :as core]
            [kontor.l10n-cn.chart :as chart]
            [kontor.l10n-cn.closing :as cn-closing]
            [kontor.l10n-cn.invoice :as cn-invoice]
            [kontor.posting :as posting]
            [kontor.validation :as v]))

;; Calendar-year fiscal period per PRC Accounting Law Art. 6.
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
                [{:journal/code "INV" :journal/name "Sales"
                  :journal/type :sale :journal/active true}
                 {:journal/code "EXP" :journal/name "Expenses"
                  :journal/type :purchase :journal/active true}
                 {:period/start jan-1
                  :period/end   jan-1-26
                  :period/tag   :normal
                  :period/name  "FY2025"}])
    conn))

(defn- seed-fy2025!
  "Post one CNY 13% sales invoice and two operating-expense entries
   inside FY2025."
  [conn]
  ;; Sales invoice — CNY 10,000 net @ 13% → CNY 11,300 gross on Feb 15.
  (cn-invoice/post-cn-invoice! conn
                               {:invoice/external-id "INV-CN-2025-1"
                                :invoice/issue-date feb-15
                                :invoice/lines [{:invoice-line/quantity 1
                                                 :invoice-line/unit-price 10000M}]})
  ;; Selling expense — CNY 6,000 paid from bank on Feb 15.
  (let [db (d/db conn)
        cny (:db/id (d/entity db [:kontor.commodity/symbol "CNY"]))
        bank (ace db "1002")
        selling (ace db "5602")     ; 销售费用
        admin (ace db "5603")       ; 管理费用
        exp-jnl (:db/id (d/entity db [:journal/code "EXP"]))]
    (v/transact-with-validation
     conn
     (posting/build-transaction
      {:transaction
       {:transaction/external-id "EXP-CN-2025-1"
        :transaction/journal exp-jnl
        :transaction/effective-date feb-15
        :transaction/narration "Selling expenses Feb / 销售费用"
        :transaction/state :posted
        :transaction/posted-at feb-15}
       :postings
       [{:posting/account selling :posting/amount 6000M
         :posting/commodity cny :posting/posted-at feb-15}
        {:posting/account bank :posting/amount -6000M
         :posting/commodity cny :posting/posted-at feb-15}]}))
    ;; Admin expense — CNY 4,000 on Jun 1.
    (v/transact-with-validation
     conn
     (posting/build-transaction
      {:transaction
       {:transaction/external-id "EXP-CN-2025-2"
        :transaction/journal exp-jnl
        :transaction/effective-date jun-1
        :transaction/narration "Administrative expenses Jun / 管理费用"
        :transaction/state :posted
        :transaction/posted-at jun-1}
       :postings
       [{:posting/account admin :posting/amount 4000M
         :posting/commodity cny :posting/posted-at jun-1}
        {:posting/account bank :posting/amount -4000M
         :posting/commodity cny :posting/posted-at jun-1}]})))
  ;; Add a second sales invoice to push net P&L away from break-even.
  (cn-invoice/post-cn-invoice! conn
                               {:invoice/external-id "INV-CN-2025-2"
                                :invoice/issue-date jun-1
                                :invoice/lines [{:invoice-line/quantity 1
                                                 :invoice-line/unit-price 5000M}]})
  ;; Expected end-of-year (before close):
  ;;   revenue 5001.13   = -15,000 (credit; income natural)
  ;;   selling 5602      =  +6,000
  ;;   admin   5603      =  +4,000
  ;;   net P&L           = -15,000 + 6,000 + 4,000 = -5,000 (profit 5,000)
  nil)

;; ============================================================================
;; Pre-close sanity
;; ============================================================================

(deftest pre-close-pnl-balances
  (testing "FY2025: revenue 15,000 Cr, selling 6,000 Dr, admin 4,000 Dr
            → profit 5,000."
    (let [conn (bootstrap)]
      (seed-fy2025! conn)
      (let [db (d/db conn)
            cny (:db/id (d/entity db [:kontor.commodity/symbol "CNY"]))
            rev (ace db "5001.13")
            sell (ace db "5602")
            admin (ace db "5603")]
        (is (= -15000M (-> (balance/account-balance conn rev
                                                    {:as-of-valid dec-31})
                           (get cny) :amount)))
        (is (= 6000M (-> (balance/account-balance conn sell
                                                  {:as-of-valid dec-31})
                         (get cny) :amount)))
        (is (= 4000M (-> (balance/account-balance conn admin
                                                  {:as-of-valid dec-31})
                         (get cny) :amount)))))))

;; ============================================================================
;; close-cn-fiscal-year! — end-to-end
;; ============================================================================

(deftest fiscal-year-close-rolls-pnl-to-3104
  (testing "Close FY2025 → P&L accounts zero on Jan 1 2026,
            retained earnings (3104 利润分配) carries the profit,
            balance-sheet equation still holds."
    (let [conn (bootstrap)]
      (seed-fy2025! conn)
      (let [db (d/db conn)
            period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025"]] db)
            {:keys [close-result period-close-tx-report]}
            (cn-closing/close-cn-fiscal-year! conn
                                              {:period-eid period-eid
                                               :external-id "FY25-CN-CLOSE"})]
        (is (some? (:transaction-eid close-result))
            "Close emitted a closing transaction")
        (is (some? period-close-tx-report)
            "Period soft-closed after the closing tx")
        ;; net-by-commodity should reflect the net P&L = -5000 (profit).
        (let [db (d/db conn)
              cny (:db/id (d/entity db [:kontor.commodity/symbol "CNY"]))
              net (get-in close-result [:net-by-commodity cny :amount])]
          (is (= -5000M net)
              "Net P&L: revenue -15,000 + selling 6,000 + admin 4,000 = -5,000
               (sign convention: income credits are negative)"))
        ;; P&L accounts at the new year start: should be zero.
        (let [db (d/db conn)
              cny (:db/id (d/entity db [:kontor.commodity/symbol "CNY"]))
              rev   (ace db "5001.13")
              sell  (ace db "5602")
              admin (ace db "5603")
              retained (ace db "3104")
              new-yr-bal (fn [eid]
                           (-> (balance/account-balance conn eid
                                                        {:as-of-valid jan-1-26})
                               (get cny)
                               :amount))]
          (is (zero? (.compareTo ^java.math.BigDecimal (new-yr-bal rev) 0M))
              "Revenue zeroed")
          (is (zero? (.compareTo ^java.math.BigDecimal (new-yr-bal sell) 0M))
              "Selling expense zeroed")
          (is (zero? (.compareTo ^java.math.BigDecimal (new-yr-bal admin) 0M))
              "Admin expense zeroed")
          (is (= -5000M (new-yr-bal retained))
              "Retained earnings (3104 利润分配) carries the prior-year profit"))))))

;; ============================================================================
;; Idempotency
;; ============================================================================

(deftest cannot-close-period-twice
  (let [conn (bootstrap)]
    (seed-fy2025! conn)
    (let [period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025"]] (d/db conn))]
      (cn-closing/close-cn-fiscal-year! conn
                                        {:period-eid period-eid
                                         :external-id "FY25-CN-1"})
      (is (thrown? clojure.lang.ExceptionInfo
                   (cn-closing/close-cn-fiscal-year!
                    conn
                    {:period-eid period-eid
                     :external-id "FY25-CN-2"}))
          "Second close attempt on the same period is rejected"))))

;; ============================================================================
;; Auto-create CLOSE journal
;; ============================================================================

(deftest auto-creates-close-journal
  (testing "If the CLOSE journal isn't pre-seeded, close-cn-fiscal-
            year! creates it on first call."
    (let [conn (core/create-test-db)
          _ (v/install-invariants! conn)
          _ (chart/install! conn)
          ;; INV + EXP for seeding; deliberately no CLOSE journal.
          _ (d/transact conn [{:journal/code "INV" :journal/name "Sales"
                               :journal/type :sale :journal/active true}
                              {:journal/code "EXP" :journal/name "Expenses"
                               :journal/type :purchase :journal/active true}
                              {:period/start jan-1
                               :period/end   jan-1-26
                               :period/tag   :normal
                               :period/name  "FY2025"}])
          _ (seed-fy2025! conn)
          period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025"]] (d/db conn))]
      (is (nil? (:db/id (d/entity (d/db conn) [:journal/code "CLOSE"])))
          "CLOSE journal not present before close")
      (cn-closing/close-cn-fiscal-year! conn
                                        {:period-eid period-eid
                                         :external-id "AUTO-J-CLOSE-CN"})
      (is (some? (:db/id (d/entity (d/db conn) [:journal/code "CLOSE"])))
          "CLOSE journal auto-created"))))

;; ============================================================================
;; Missing retained-earnings account
;; ============================================================================

(deftest missing-retained-earnings-throws
  (testing "If the CN chart isn't installed (no 3104), the planner throws."
    (let [conn (core/create-test-db)]
      (v/install-invariants! conn)
      (d/transact conn [{:period/start jan-1
                         :period/end jan-1-26
                         :period/tag :normal
                         :period/name "FY2025"}])
      (let [period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025"]] (d/db conn))]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Retained-earnings account"
             (cn-closing/close-cn-fiscal-year!
              conn {:period-eid period-eid})))))))

;; ============================================================================
;; plan-* (ADR-068 pure form)
;; ============================================================================

(deftest plan-cn-fiscal-year-close-resolves-eids
  (let [conn (bootstrap)
        db (d/db conn)
        period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025"]] db)
        planned (cn-closing/plan-cn-fiscal-year-close-tx-data
                 db {:period-eid period-eid
                     :external-id "PLAN-CN-1"})]
    (is (= period-eid (:period-eid planned)))
    (is (some? (:retained-earnings-eid planned))
        "Retained-earnings eid resolved from the chart (3104)")
    (is (not (contains? planned :retained-code))
        ":retained-code stripped after resolution")
    (is (= "PLAN-CN-1" (:external-id planned))
        "Pass-throughs preserved")))

(deftest plan-without-period-eid-throws
  (let [conn (bootstrap)
        db (d/db conn)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (cn-closing/plan-cn-fiscal-year-close-tx-data db {})))))

;; ============================================================================
;; Sum-to-zero after close
;; ============================================================================

(deftest sum-to-zero-after-close
  (testing "Every posting in the DB sums to zero across all accounts
            and all commodities after the close — kernel invariant."
    (let [conn (bootstrap)]
      (seed-fy2025! conn)
      (let [period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025"]] (d/db conn))]
        (cn-closing/close-cn-fiscal-year! conn
                                          {:period-eid period-eid
                                           :external-id "FY25-CN-Z"}))
      (let [db (d/db conn)
            pairs (d/q '[:find ?p ?amt
                         :where [?p :posting/amount ?amt]] db)
            total (reduce (fn [^java.math.BigDecimal acc [_ ^java.math.BigDecimal x]]
                            (.add acc x))
                          0M pairs)]
        (is (zero? (.compareTo ^java.math.BigDecimal total 0M))
            (str "All postings must sum to zero, got " total))))))
