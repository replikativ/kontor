(ns kontor.l10n-mx.closing-test
  "Tests for kontor.l10n-mx.closing — wrapping the kernel closer
   with the SAT-aligned account convention (Utilidades Retenidas
   305.01.001).

   Scenario: a small Mexican entity with one 16% sales invoice and
   two operating-expense entries in FY2025. End of FY: P&L collapses
   to Utilidades Retenidas, all P&L accounts zero on the next-year
   balance, balance sheet still balanced."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.balance :as balance]
            [kontor.core :as core]
            [kontor.l10n-mx.chart :as chart]
            [kontor.l10n-mx.closing :as mx-closing]
            [kontor.l10n-mx.invoice :as mx-invoice]
            [kontor.posting :as posting]
            [kontor.validation :as v]))

(def jan-1    #inst "2025-01-01T00:00:00Z")
(def feb-15   #inst "2025-02-15T00:00:00Z")
(def jun-1    #inst "2025-06-01T00:00:00Z")
(def dec-31   #inst "2025-12-31T23:59:59Z")
(def jan-1-26 #inst "2026-01-01T00:00:00Z")

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :account/code ?c]] db code))

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (chart/install! conn)
    (d/transact conn
                [{:journal/code "INV" :journal/name "Ventas"
                  :journal/type :sale :journal/active true}
                 {:journal/code "EXP" :journal/name "Gastos"
                  :journal/type :purchase :journal/active true}
                 {:period/start jan-1
                  :period/end   jan-1-26
                  :period/tag   :normal
                  :period/name  "FY2025"}])
    conn))

(defn- seed-fy2025!
  "Post one 16% cash-sale invoice + two operating expenses inside FY2025."
  [conn]
  ;; Cash sale: $1000 net @ 16% IVA = 1160 gross on Feb 15.
  ;; (Using cash-sale so IVA recognised immediately on 208.01.)
  (mx-invoice/post-mx-invoice! conn
                               {:invoice/external-id "INV-2025-1"
                                :invoice/issue-date feb-15
                                :invoice/cash-sale? true
                                :invoice/lines [{:invoice-line/quantity 1
                                                 :invoice-line/unit-price 1000M}]})
  ;; Office supplies expense: $300 paid from bank Feb 15.
  (let [db (d/db conn)
        mxn (:db/id (d/entity db [:kontor.commodity/symbol "MXN"]))
        bank (ace db chart/bank-code)
        cash (ace db chart/cash-code)
        office (ace db "601.08.001")    ; Papelería
        rent (ace db "601.03.001")      ; Renta
        exp-jnl (:db/id (d/entity db [:journal/code "EXP"]))]
    (v/transact-with-validation
     conn
     (posting/build-transaction
      {:transaction
       {:transaction/external-id "EXP-2025-1"
        :transaction/journal exp-jnl
        :transaction/effective-date feb-15
        :transaction/narration "Papelería febrero"
        :transaction/state :posted
        :transaction/posted-at feb-15}
       :postings
       [{:posting/account office :posting/amount 300M
         :posting/commodity mxn :posting/posted-at feb-15}
        {:posting/account cash :posting/amount -300M
         :posting/commodity mxn :posting/posted-at feb-15}]}))
    ;; Rent expense: $400 paid from bank on Jun 1.
    (v/transact-with-validation
     conn
     (posting/build-transaction
      {:transaction
       {:transaction/external-id "EXP-2025-2"
        :transaction/journal exp-jnl
        :transaction/effective-date jun-1
        :transaction/narration "Renta junio"
        :transaction/state :posted
        :transaction/posted-at jun-1}
       :postings
       [{:posting/account rent :posting/amount 400M
         :posting/commodity mxn :posting/posted-at jun-1}
        {:posting/account bank :posting/amount -400M
         :posting/commodity mxn :posting/posted-at jun-1}]})))
  ;; Net P&L = revenue -1000 + office 300 + rent 400 = -300 (profit 300)
  nil)

;; ============================================================================
;; Sanity: pre-close balances
;; ============================================================================

(deftest pre-close-pnl-balances
  (testing "FY2025 has revenue 1000 (Cr), papelería 300 (Dr),
            renta 400 (Dr) → net profit 300."
    (let [conn (bootstrap)]
      (seed-fy2025! conn)
      (let [db (d/db conn)
            mxn (:db/id (d/entity db [:kontor.commodity/symbol "MXN"]))
            rev    (ace db chart/sales-domestic-16-code)
            office (ace db "601.08.001")
            rent   (ace db "601.03.001")]
        (is (= -1000M (-> (balance/account-balance conn rev
                                                   {:as-of-valid dec-31})
                          (get mxn) :amount)))
        (is (= 300M (-> (balance/account-balance conn office
                                                 {:as-of-valid dec-31})
                        (get mxn) :amount)))
        (is (= 400M (-> (balance/account-balance conn rent
                                                 {:as-of-valid dec-31})
                        (get mxn) :amount)))))))

;; ============================================================================
;; close-mx-fiscal-year! — end-to-end
;; ============================================================================

(deftest fiscal-year-close-rolls-pnl-to-utilidades-retenidas
  (testing "Close FY2025 → P&L accounts zero on Jan 1 2026,
              Utilidades Retenidas (305.01.001) carries the profit,
              balance sheet equation still holds."
    (let [conn (bootstrap)]
      (seed-fy2025! conn)
      (let [db (d/db conn)
            period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025"]] db)
            {:keys [close-result period-close-tx-report]}
            (mx-closing/close-mx-fiscal-year!
             conn {:period-eid period-eid
                   :external-id "FY25-MX-CLOSE"})]
        (is (some? (:transaction-eid close-result))
            "Close emitted a closing transaction")
        (is (some? period-close-tx-report)
            "Period soft-closed after the closing tx")
        ;; Net P&L = -300 (profit; income credits are negative).
        (let [db (d/db conn)
              mxn (:db/id (d/entity db [:kontor.commodity/symbol "MXN"]))
              net (get-in close-result [:net-by-commodity mxn :amount])]
          (is (= -300M net)
              "Net P&L: revenue -1000 + office 300 + rent 400 = -300"))
        ;; P&L accounts zero at the new year start.
        (let [db (d/db conn)
              mxn (:db/id (d/entity db [:kontor.commodity/symbol "MXN"]))
              rev    (ace db chart/sales-domestic-16-code)
              office (ace db "601.08.001")
              rent   (ace db "601.03.001")
              retained (ace db chart/utilidades-retenidas-code)
              new-yr-bal (fn [eid]
                           (-> (balance/account-balance conn eid
                                                        {:as-of-valid jan-1-26})
                               (get mxn)
                               :amount))]
          (is (zero? (.compareTo ^java.math.BigDecimal (new-yr-bal rev) 0M))
              "Revenue zeroed")
          (is (zero? (.compareTo ^java.math.BigDecimal (new-yr-bal office) 0M))
              "Papelería expense zeroed")
          (is (zero? (.compareTo ^java.math.BigDecimal (new-yr-bal rent) 0M))
              "Renta expense zeroed")
          (is (= -300M (new-yr-bal retained))
              "Utilidades Retenidas carries the prior-year profit
               (negative on an equity account = credit balance =
               profit retained)"))))))

;; ============================================================================
;; Idempotency: cannot close twice
;; ============================================================================

(deftest cannot-close-period-twice
  (let [conn (bootstrap)]
    (seed-fy2025! conn)
    (let [period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025"]] (d/db conn))]
      (mx-closing/close-mx-fiscal-year!
       conn {:period-eid period-eid
             :external-id "FY25-MX-CLOSE-1"})
      (is (thrown? clojure.lang.ExceptionInfo
                   (mx-closing/close-mx-fiscal-year!
                    conn {:period-eid period-eid
                          :external-id "FY25-MX-CLOSE-2"}))
          "Second close attempt on the same period is rejected"))))

;; ============================================================================
;; Auto-create CLOSE journal
;; ============================================================================

(deftest auto-creates-close-journal
  (testing "If the CLOSE journal isn't pre-seeded,
              close-mx-fiscal-year! creates it on first call. INV +
              EXP journals are still required for seed activity."
    (let [conn (core/create-test-db)
          _ (v/install-invariants! conn)
          _ (chart/install! conn)
          _ (d/transact conn [{:journal/code "INV" :journal/name "Ventas"
                               :journal/type :sale :journal/active true}
                              {:journal/code "EXP" :journal/name "Gastos"
                               :journal/type :purchase :journal/active true}
                              {:period/start jan-1
                               :period/end jan-1-26
                               :period/tag :normal
                               :period/name "FY2025"}])
          _ (seed-fy2025! conn)
          period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025"]] (d/db conn))]
      (is (nil? (:db/id (d/entity (d/db conn) [:journal/code "CLOSE"])))
          "CLOSE journal not present before close")
      (mx-closing/close-mx-fiscal-year!
       conn {:period-eid period-eid
             :external-id "AUTO-J-CLOSE"})
      (is (some? (:db/id (d/entity (d/db conn) [:journal/code "CLOSE"])))
          "CLOSE journal auto-created"))))

;; ============================================================================
;; Missing retained-earnings account
;; ============================================================================

(deftest missing-retained-earnings-throws
  (testing "If the MX chart isn't installed (no 305.01.001), the
              planner throws a clear error."
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
             (mx-closing/close-mx-fiscal-year!
              conn {:period-eid period-eid})))))))

;; ============================================================================
;; ADR-068 plan-* pure form
;; ============================================================================

(deftest plan-mx-fiscal-year-close-resolves-eids
  (let [conn (bootstrap)
        db (d/db conn)
        period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025"]] db)
        planned (mx-closing/plan-mx-fiscal-year-close-tx-data
                 db {:period-eid period-eid
                     :external-id "PLAN-1"})]
    (is (= period-eid (:period-eid planned)))
    (is (some? (:retained-earnings-eid planned))
        "Utilidades Retenidas eid resolved from the chart")
    (is (not (contains? planned :retained-code))
        ":retained-code stripped after resolution")
    (is (= "PLAN-1" (:external-id planned))
        "Pass-throughs preserved")))

(deftest plan-without-period-eid-throws
  (let [conn (bootstrap)
        db (d/db conn)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (mx-closing/plan-mx-fiscal-year-close-tx-data db {})))))

(deftest plan-resolves-utilidades-retenidas-by-default
  (testing "Default :retained-code is 305.01.001 (Utilidades
              Retenidas) per the SAT Código Agrupador."
    (let [conn (bootstrap)
          db (d/db conn)
          period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025"]] db)
          planned (mx-closing/plan-mx-fiscal-year-close-tx-data
                   db {:period-eid period-eid})
          expected (d/q '[:find ?a . :in $ ?c
                          :where [?a :account/code ?c]]
                        db chart/utilidades-retenidas-code)]
      (is (= expected (:retained-earnings-eid planned))))))

(deftest plan-respects-custom-retained-code
  (testing "Customer can override :retained-code (e.g. route losses
              to 306 Pérdidas de Ejercicios Anteriores instead)."
    (let [conn (bootstrap)
          db (d/db conn)
          period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025"]] db)
          planned (mx-closing/plan-mx-fiscal-year-close-tx-data
                   db {:period-eid period-eid
                       :retained-code "306.01.001"})
          expected (d/q '[:find ?a . :in $ ?c
                          :where [?a :account/code ?c]]
                        db "306.01.001")]
      (is (= expected (:retained-earnings-eid planned))))))

;; ============================================================================
;; Sum-to-zero after close
;; ============================================================================

(deftest sum-to-zero-after-close
  (testing "Every posting in the DB sums to zero across all accounts
              and all commodities after the close — kernel invariant."
    (let [conn (bootstrap)]
      (seed-fy2025! conn)
      (let [period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025"]] (d/db conn))]
        (mx-closing/close-mx-fiscal-year!
         conn {:period-eid period-eid :external-id "FY25-Z"}))
      (let [db (d/db conn)
            pairs (d/q '[:find ?p ?amt
                         :where [?p :posting/amount ?amt]] db)
            total (reduce (fn [^java.math.BigDecimal acc [_ ^java.math.BigDecimal x]]
                            (.add acc x))
                          0M pairs)]
        (is (zero? (.compareTo ^java.math.BigDecimal total 0M))
            (str "All postings must sum to zero, got " total))))))
