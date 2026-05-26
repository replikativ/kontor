(ns kontor.l10n-br.closing-test
  "Tests for kontor.l10n-br.closing — wrapping the kernel closer with
   Plano de Contas Referencial-aligned account routing.

   Scenario: a Brazilian SA with one SP-intra-state goods invoice and
   two expense postings. End of FY2025: P&L collapses to Lucros
   Acumulados (account 2.03.04.01.01), all P&L accounts zero on the
   next-year balance, balance sheet still balanced."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.balance :as balance]
            [kontor.core :as core]
            [kontor.l10n-br.chart :as chart]
            [kontor.l10n-br.closing :as br-closing]
            [kontor.l10n-br.invoice :as br-invoice]
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
  "Post one SP-intra-state goods invoice and two operating-expense
   entries inside FY2025."
  [conn]
  ;; Sales invoice: R$1000 net @ SP intra-state.
  ;;   ICMS 180, PIS 13.53, COFINS 62.32. AR debit 1255.85.
  ;;   Revenue (Receita Bruta Mercadorias) credit 1000.
  (br-invoice/post-br-invoice!
   conn
   {:invoice/external-id "INV-2025-1"
    :invoice/issue-date feb-15
    :invoice/from-state "SP"
    :invoice/to-state "SP"
    :invoice/lines [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M
                     :invoice-line/tax-classification :goods}]})
  ;; Expense: R$600 rent paid from bank on Feb 15.
  (let [db (d/db conn)
        brl (:db/id (d/entity db [:kontor.commodity/symbol "BRL"]))
        bank   (ace db "1.01.01.02.01")   ; Bancos – No País
        rent   (ace db "3.04.02.01.01")   ; Aluguéis
        other  (ace db "3.04.99.01.01")   ; Outras Despesas Operacionais
        exp-jnl (:db/id (d/entity db [:journal/code "EXP"]))]
    (v/transact-with-validation
     conn
     (posting/build-transaction
      {:transaction
       {:transaction/external-id "EXP-2025-1"
        :transaction/journal exp-jnl
        :transaction/effective-date feb-15
        :transaction/narration "Aluguel Fev"
        :transaction/state :posted
        :transaction/posted-at feb-15}
       :postings
       [{:posting/account rent :posting/amount 600M
         :posting/commodity brl :posting/posted-at feb-15}
        {:posting/account bank :posting/amount -600M
         :posting/commodity brl :posting/posted-at feb-15}]}))
    ;; Expense: R$400 other operating expense paid Jun 1.
    (v/transact-with-validation
     conn
     (posting/build-transaction
      {:transaction
       {:transaction/external-id "EXP-2025-2"
        :transaction/journal exp-jnl
        :transaction/effective-date jun-1
        :transaction/narration "Outras Despesas Jun"
        :transaction/state :posted
        :transaction/posted-at jun-1}
       :postings
       [{:posting/account other :posting/amount 400M
         :posting/commodity brl :posting/posted-at jun-1}
        {:posting/account bank  :posting/amount -400M
         :posting/commodity brl :posting/posted-at jun-1}]})))
  ;; Add a second sales invoice for a real net-profit close (the
  ;; first invoice's revenue 1000 vs expenses 1000 would zero-out).
  (br-invoice/post-br-invoice!
   conn
   {:invoice/external-id "INV-2025-2"
    :invoice/issue-date jun-1
    :invoice/from-state "SP"
    :invoice/to-state "SP"
    :invoice/lines [{:invoice-line/quantity 1
                     :invoice-line/unit-price 500M
                     :invoice-line/tax-classification :goods}]})
  ;; Net P&L (P&L accounts only, ignoring balance-sheet tax payables):
  ;;   Receita Bruta Mercadorias    = -1500 (credit-natural)
  ;;   Aluguéis                     = +600
  ;;   Outras Despesas Operacionais = +400
  ;;   net P&L                      = -500 → profit 500
  nil)

;; ============================================================================
;; Sanity: pre-close P&L balances
;; ============================================================================

(deftest pre-close-pnl-balances
  (testing "FY2025 has Receita Bruta Mercadorias -1500 (Cr),
            Aluguéis +600 (Dr), Outras Despesas +400 (Dr) → net -500."
    (let [conn (bootstrap)]
      (seed-fy2025! conn)
      (let [db (d/db conn)
            brl (:db/id (d/entity db [:kontor.commodity/symbol "BRL"]))
            rev   (ace db "3.01.01.01.01")
            rent  (ace db "3.04.02.01.01")
            other (ace db "3.04.99.01.01")]
        (is (= -1500M (-> (balance/account-balance conn rev
                                                   {:as-of-valid dec-31})
                          (get brl) :amount)))
        (is (= 600M (-> (balance/account-balance conn rent
                                                 {:as-of-valid dec-31})
                        (get brl) :amount)))
        (is (= 400M (-> (balance/account-balance conn other
                                                 {:as-of-valid dec-31})
                        (get brl) :amount)))))))

;; ============================================================================
;; close-br-fiscal-year! — end-to-end
;; ============================================================================

(deftest fiscal-year-close-rolls-pnl-to-lucros-acumulados
  (testing "Close FY2025 → P&L accounts zero on Jan 1 2026,
            Lucros Acumulados (2.03.04.01.01) carries the profit,
            balance sheet still balances."
    (let [conn (bootstrap)]
      (seed-fy2025! conn)
      (let [db (d/db conn)
            period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025"]] db)
            {:keys [close-result period-close-tx-report]}
            (br-closing/close-br-fiscal-year! conn
                                              {:period-eid period-eid
                                               :external-id "FY25-BR-CLOSE"})]
        (is (some? (:transaction-eid close-result))
            "Close emitted a closing transaction")
        (is (some? period-close-tx-report)
            "Period soft-closed after the closing tx")
        ;; net-by-commodity should reflect net P&L = -500.
        ;; (note: tax-payable accounts are LIABILITIES (balance-sheet),
        ;; not P&L — they don't participate in the closing entry, which
        ;; is what we want).
        (let [db (d/db conn)
              brl (:db/id (d/entity db [:kontor.commodity/symbol "BRL"]))
              net (get-in close-result [:net-by-commodity brl :amount])]
          (is (= -500M net)
              "Net P&L: revenue -1500 + rent 600 + other 400 = -500
               (sign convention: income credits are negative)"))
        ;; P&L accounts at the new year start: should be zero.
        (let [db (d/db conn)
              brl (:db/id (d/entity db [:kontor.commodity/symbol "BRL"]))
              rev   (ace db "3.01.01.01.01")
              rent  (ace db "3.04.02.01.01")
              other (ace db "3.04.99.01.01")
              retained (ace db "2.03.04.01.01")
              new-yr-bal (fn [eid]
                           (-> (balance/account-balance conn eid
                                                        {:as-of-valid jan-1-26})
                               (get brl)
                               :amount))]
          (is (zero? (.compareTo ^java.math.BigDecimal (new-yr-bal rev) 0M))
              "Receita zeroed")
          (is (zero? (.compareTo ^java.math.BigDecimal (new-yr-bal rent) 0M))
              "Aluguéis zeroed")
          (is (zero? (.compareTo ^java.math.BigDecimal (new-yr-bal other) 0M))
              "Outras Despesas zeroed")
          (is (= -500M (new-yr-bal retained))
              "Lucros Acumulados carries the prior-year profit
               (-500 on an equity account = credit balance = profit
               retained)"))))))

;; ============================================================================
;; Idempotency: cannot close twice
;; ============================================================================

(deftest cannot-close-period-twice
  (let [conn (bootstrap)]
    (seed-fy2025! conn)
    (let [period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025"]] (d/db conn))]
      (br-closing/close-br-fiscal-year! conn
                                        {:period-eid period-eid
                                         :external-id "FY25-BR-CLOSE-1"})
      (is (thrown? clojure.lang.ExceptionInfo
                   (br-closing/close-br-fiscal-year!
                    conn
                    {:period-eid period-eid
                     :external-id "FY25-BR-CLOSE-2"}))
          "Second close attempt on the same period is rejected"))))

;; ============================================================================
;; Auto-create CLOSE journal if missing
;; ============================================================================

(deftest auto-creates-close-journal
  (testing "If the CLOSE journal isn't pre-seeded, close-br-fiscal-
            year! creates it on first call. INV + EXP journals are
            still required because the seed activity uses them."
    (let [conn (core/create-test-db)
          _ (v/install-invariants! conn)
          _ (chart/install! conn)
          _ (d/transact conn [{:journal/code "INV" :journal/name "Sales"
                               :journal/type :sale :journal/active true}
                              {:journal/code "EXP" :journal/name "Expenses"
                               :journal/type :purchase :journal/active true}
                              {:period/start jan-1
                               :period/end jan-1-26
                               :period/tag :normal
                               :period/name "FY2025"}])
          _ (seed-fy2025! conn)
          period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025"]] (d/db conn))]
      (is (nil? (:db/id (d/entity (d/db conn) [:journal/code "CLOSE"])))
          "CLOSE journal not present before close")
      (br-closing/close-br-fiscal-year! conn
                                        {:period-eid period-eid
                                         :external-id "AUTO-J-CLOSE"})
      (is (some? (:db/id (d/entity (d/db conn) [:journal/code "CLOSE"])))
          "CLOSE journal auto-created"))))

;; ============================================================================
;; Missing-account error
;; ============================================================================

(deftest missing-retained-earnings-throws
  (testing "If the BR chart isn't installed (no 2.03.04.01.01),
            the planner throws a clear error."
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
             (br-closing/close-br-fiscal-year!
              conn {:period-eid period-eid})))))))

;; ============================================================================
;; ADR-068 plan-* pure form
;; ============================================================================

(deftest plan-br-fiscal-year-close-resolves-eids
  (let [conn (bootstrap)
        db (d/db conn)
        period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025"]] db)
        planned (br-closing/plan-br-fiscal-year-close-tx-data
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
                 (br-closing/plan-br-fiscal-year-close-tx-data db {})))))

;; ============================================================================
;; Override the retained-earnings code
;; ============================================================================

(deftest can-override-retained-code
  (testing "Pass :retained-code to route the close to a non-default
              equity account (e.g. a customer's Reserva de Lucros
              sub-account). We re-use Capital Social (2.03.01.01.01)
              from the starter chart as a stand-in here — the test
              only verifies the routing mechanism."
    (let [conn (bootstrap)]
      (seed-fy2025! conn)
      (let [db (d/db conn)
            period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025"]] db)
            brl (:db/id (d/entity db [:kontor.commodity/symbol "BRL"]))
            override "2.03.01.01.01"]
        (br-closing/close-br-fiscal-year! conn
                                          {:period-eid period-eid
                                           :external-id "OVR"
                                           :retained-code override})
        (let [override-eid (ace (d/db conn) override)
              bal (-> (balance/account-balance conn override-eid
                                               {:as-of-valid jan-1-26})
                      (get brl) :amount)]
          (is (= -500M bal)
              "Override account received the closing roll-up"))))))

;; ============================================================================
;; Sum-to-zero after close
;; ============================================================================

(deftest sum-to-zero-after-close
  (testing "Every posting in the DB sums to zero across all accounts
            and all commodities after the close — kernel invariant."
    (let [conn (bootstrap)]
      (seed-fy2025! conn)
      (let [period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025"]] (d/db conn))]
        (br-closing/close-br-fiscal-year! conn
                                          {:period-eid period-eid
                                           :external-id "FY25-BR-Z"}))
      (let [db (d/db conn)
            pairs (d/q '[:find ?p ?amt
                         :where [?p :posting/amount ?amt]] db)
            total (reduce (fn [^java.math.BigDecimal acc [_ ^java.math.BigDecimal x]]
                            (.add acc x))
                          0M pairs)]
        (is (zero? (.compareTo ^java.math.BigDecimal total 0M))
            (str "All postings must sum to zero, got " total))))))
