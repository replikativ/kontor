(ns kontor.l10n-at.closing-test
  "Tests for kontor.l10n-at.closing — wrapping the kernel closer with
   the Einheitskontenrahmen / UGB §224 default account routing.

   Scenario: a small AT business with two 20% USt sales invoices
   and one expense posting. End of FY2025: P&L collapses to
   Bilanzgewinn (account 9460), all P&L accounts zero on the next-
   year balance, balance sheet still balanced."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.balance :as balance]
            [kontor.core :as core]
            [kontor.l10n-at.chart :as chart]
            [kontor.l10n-at.closing :as at-closing]
            [kontor.l10n-at.invoice :as at-invoice]
            [kontor.posting :as posting]
            [kontor.validation :as v]))

(def jan-1     #inst "2025-01-01T00:00:00Z")
(def feb-15    #inst "2025-02-15T00:00:00Z")
(def jun-1     #inst "2025-06-01T00:00:00Z")
(def dec-31    #inst "2025-12-31T23:59:59Z")
(def jan-1-26  #inst "2026-01-01T00:00:00Z")

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :account/code ?c]] db code))

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (chart/install! conn)
    (d/transact conn
                [{:journal/code "INV" :journal/name "Verkaufsrechnungen"
                  :journal/type :sale :journal/active true}
                 {:journal/code "EXP" :journal/name "Aufwendungen"
                  :journal/type :purchase :journal/active true}
                 {:period/start jan-1
                  :period/end   jan-1-26
                  :period/tag   :normal
                  :period/name  "FY2025"}])
    conn))

(defn- seed-fy2025!
  "Post two AT invoices and one office-expense entry inside FY2025."
  [conn]
  ;; First sales invoice: €1000 net @ 20% USt → €1200 gross on Feb 15.
  (at-invoice/post-at-invoice! conn
                               {:invoice/external-id "INV-2025-1"
                                :invoice/issue-date feb-15
                                :invoice/lines [{:invoice-line/quantity 1
                                                 :invoice-line/unit-price 1000M}]})
  ;; Office expense: €600 paid in cash on Feb 15.
  (let [db (d/db conn)
        eur (:db/id (d/entity db [:commodity/symbol "EUR"]))
        kassa  (ace db "2700")          ; Kassa
        office (ace db "7400")          ; Bürobedarf
        exp-jnl (:db/id (d/entity db [:journal/code "EXP"]))]
    (v/transact-with-validation
     conn
     (posting/build-transaction
      {:transaction
       {:transaction/external-id "EXP-2025-1"
        :transaction/journal exp-jnl
        :transaction/effective-date feb-15
        :transaction/narration "Bürobedarf Feb"
        :transaction/state :posted
        :transaction/posted-at feb-15}
       :postings
       [{:posting/account office :posting/amount 600M
         :posting/commodity eur :posting/posted-at feb-15}
        {:posting/account kassa :posting/amount -600M
         :posting/commodity eur :posting/posted-at feb-15}]})))
  ;; Second sales invoice: €500 net @ 20% USt → €600 gross on Jun 1.
  (at-invoice/post-at-invoice! conn
                               {:invoice/external-id "INV-2025-2"
                                :invoice/issue-date jun-1
                                :invoice/lines [{:invoice-line/quantity 1
                                                 :invoice-line/unit-price 500M}]})
  ;; Expected end-of-year (before close):
  ;;   Erlöse 20% (4000) = -1500 (credit balance)
  ;;   Bürobedarf (7400) = +600
  ;;   net P&L            = -1500 + 600 = -900 (profit 900)
  nil)

;; ============================================================================
;; Sanity: pre-close balances
;; ============================================================================

(deftest pre-close-pnl-balances
  (testing "FY2025 has revenue 1500 (Cr), Bürobedarf 600 (Dr)
            → net profit 900."
    (let [conn (bootstrap)]
      (seed-fy2025! conn)
      (let [db (d/db conn)
            eur (:db/id (d/entity db [:commodity/symbol "EUR"]))
            rev    (ace db "4000")
            office (ace db "7400")]
        (is (= -1500M (-> (balance/account-balance conn rev
                                                   {:as-of-valid dec-31})
                          (get eur) :amount)))
        (is (= 600M (-> (balance/account-balance conn office
                                                 {:as-of-valid dec-31})
                        (get eur) :amount)))))))

;; ============================================================================
;; close-at-fiscal-year! — end-to-end
;; ============================================================================

(deftest fiscal-year-close-rolls-pnl-to-9460
  (testing "Close FY2025 → P&L accounts zero on Jan 1 2026,
            Bilanzgewinn (9460) carries the profit, balance sheet
            equation still holds."
    (let [conn (bootstrap)]
      (seed-fy2025! conn)
      (let [db (d/db conn)
            period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025"]] db)
            {:keys [close-result period-close-tx-report]}
            (at-closing/close-at-fiscal-year! conn
                                              {:period-eid period-eid
                                               :external-id "FY25-AT-CLOSE"})]
        (is (some? (:transaction-eid close-result))
            "Close emitted a closing transaction")
        (is (some? period-close-tx-report)
            "Period soft-closed after the closing tx")
        ;; net-by-commodity should reflect the net P&L = -900 (profit).
        (let [db (d/db conn)
              eur (:db/id (d/entity db [:commodity/symbol "EUR"]))
              net (get-in close-result [:net-by-commodity eur :amount])]
          (is (= -900M net)
              "Net P&L: revenue -1500 + Bürobedarf 600 = -900
               (sign convention: income credits are negative)"))
        ;; P&L accounts at the new year start: should be zero.
        (let [db (d/db conn)
              eur (:db/id (d/entity db [:commodity/symbol "EUR"]))
              rev      (ace db "4000")
              office   (ace db "7400")
              retained (ace db "9460")
              new-yr-bal (fn [eid]
                           (-> (balance/account-balance conn eid
                                                        {:as-of-valid jan-1-26})
                               (get eur)
                               :amount))]
          (is (zero? (.compareTo ^java.math.BigDecimal (new-yr-bal rev) 0M))
              "Erlöse 20% zeroed")
          (is (zero? (.compareTo ^java.math.BigDecimal (new-yr-bal office) 0M))
              "Bürobedarf zeroed")
          ;; Bilanzgewinn: net P&L was -900 (profit); the close
          ;; posts the negation of each P&L line + a counter-posting
          ;; equal to the SUM of P&L balances. Sum is -900, so Bilanz-
          ;; gewinn gets -900 (negative on an equity account = credit
          ;; balance = profit retained).
          (is (= -900M (new-yr-bal retained))
              "Bilanzgewinn 9460 carries the prior-year profit"))))))

;; ============================================================================
;; Idempotency: cannot close twice
;; ============================================================================

(deftest cannot-close-period-twice
  (let [conn (bootstrap)]
    (seed-fy2025! conn)
    (let [period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025"]] (d/db conn))]
      (at-closing/close-at-fiscal-year! conn
                                        {:period-eid period-eid
                                         :external-id "FY25-AT-CLOSE-1"})
      (is (thrown? clojure.lang.ExceptionInfo
                   (at-closing/close-at-fiscal-year!
                    conn
                    {:period-eid period-eid
                     :external-id "FY25-AT-CLOSE-2"}))
          "Second close attempt on the same period is rejected"))))

;; ============================================================================
;; Auto-create CLOSE journal if missing
;; ============================================================================

(deftest auto-creates-close-journal
  (testing "If the CLOSE journal isn't pre-seeded, close-at-fiscal-
            year! creates it on first call."
    (let [conn (core/create-test-db)
          _ (v/install-invariants! conn)
          _ (chart/install! conn)
          ;; INV + EXP for seeding; deliberately no CLOSE journal
          _ (d/transact conn [{:journal/code "INV" :journal/name "Verkaufsrechnungen"
                               :journal/type :sale :journal/active true}
                              {:journal/code "EXP" :journal/name "Aufwendungen"
                               :journal/type :purchase :journal/active true}
                              {:period/start jan-1
                               :period/end jan-1-26
                               :period/tag :normal
                               :period/name "FY2025"}])
          _ (seed-fy2025! conn)
          period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025"]] (d/db conn))]
      (is (nil? (:db/id (d/entity (d/db conn) [:journal/code "CLOSE"])))
          "CLOSE journal not present before close")
      (at-closing/close-at-fiscal-year! conn
                                        {:period-eid period-eid
                                         :external-id "AUTO-J-CLOSE"})
      (is (some? (:db/id (d/entity (d/db conn) [:journal/code "CLOSE"])))
          "CLOSE journal auto-created"))))

;; ============================================================================
;; Missing-account error
;; ============================================================================

(deftest missing-retained-earnings-throws
  (testing "If the AT chart isn't installed (no 9460), the planner
            throws a clear error."
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
             (at-closing/close-at-fiscal-year!
              conn {:period-eid period-eid})))))))

;; ============================================================================
;; ADR-068 plan-* pure form
;; ============================================================================

(deftest plan-at-fiscal-year-close-resolves-eids
  (let [conn (bootstrap)
        db (d/db conn)
        period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025"]] db)
        planned (at-closing/plan-at-fiscal-year-close-tx-data
                 db {:period-eid period-eid
                     :external-id "PLAN-1"})]
    (is (= period-eid (:period-eid planned)))
    (is (some? (:retained-earnings-eid planned))
        "Bilanzgewinn (9460) eid resolved from the chart")
    (is (not (contains? planned :retained-code))
        ":retained-code stripped after resolution")
    (is (= "PLAN-1" (:external-id planned))
        "Pass-throughs preserved")))

(deftest plan-without-period-eid-throws
  (let [conn (bootstrap)
        db (d/db conn)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (at-closing/plan-at-fiscal-year-close-tx-data db {})))))

;; ============================================================================
;; Override retained-code routing
;; ============================================================================

(deftest override-retained-code-routes-elsewhere
  (testing "A Kapitalgesellschaft that wants the carryover to land
            on Gewinnrücklage (9200) instead of Bilanzgewinn (9460)
            can override :retained-code."
    (let [conn (bootstrap)]
      (seed-fy2025! conn)
      (let [period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025"]] (d/db conn))]
        (at-closing/close-at-fiscal-year! conn
                                          {:period-eid period-eid
                                           :retained-code "9200"
                                           :external-id "FY25-9200"})
        (let [db (d/db conn)
              eur (:db/id (d/entity db [:commodity/symbol "EUR"]))
              rueckl (ace db "9200")
              bilanz (ace db "9460")
              bal (fn [eid] (-> (balance/account-balance conn eid
                                                         {:as-of-valid jan-1-26})
                                (get eur) :amount))]
          (is (= -900M (bal rueckl))
              "Profit routed to Gewinnrücklage 9200")
          (is (zero? (.compareTo ^java.math.BigDecimal
                      (or (bal bilanz) 0M) 0M))
              "Default Bilanzgewinn 9460 untouched"))))))

;; ============================================================================
;; Sum-to-zero after close
;; ============================================================================

(deftest sum-to-zero-after-close
  (testing "Every posting in the DB sums to zero across all accounts
            and all commodities after the close — kernel invariant."
    (let [conn (bootstrap)]
      (seed-fy2025! conn)
      (let [period-eid (d/q '[:find ?p . :where [?p :period/name "FY2025"]] (d/db conn))]
        (at-closing/close-at-fiscal-year! conn
                                          {:period-eid period-eid
                                           :external-id "FY25-Z"}))
      ;; Pull per-posting tuples so duplicate amounts don't collapse
      ;; under datalog's set semantics.
      (let [db (d/db conn)
            pairs (d/q '[:find ?p ?amt
                         :where [?p :posting/amount ?amt]] db)
            total (reduce (fn [^java.math.BigDecimal acc [_ ^java.math.BigDecimal x]]
                            (.add acc x))
                          0M pairs)]
        (is (zero? (.compareTo ^java.math.BigDecimal total 0M))
            (str "All postings must sum to zero, got " total))))))
