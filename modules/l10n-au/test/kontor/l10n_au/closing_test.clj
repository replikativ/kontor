(ns kontor.l10n-au.closing-test
  "Tests for kontor.l10n-au.closing — wrapping the kernel closer with
   AU CoA defaults (31200 Retained earnings, CLOSE journal) and the
   AU financial-year (1 July – 30 June) period convention.

   Scenario: a small AU business with one taxable sales invoice and
   two expense postings inside FY2026 (1 Jul 2025 → 30 Jun 2026).
   End of FY: P&L collapses to retained earnings, all P&L accounts
   zero on the next-year balance, balance sheet still balanced."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.reporting.balance :as balance]
            [kontor.core :as core]
            [kontor.l10n-au.chart :as chart]
            [kontor.l10n-au.closing :as au-closing]
            [kontor.l10n-au.invoice :as au-invoice]
            [kontor.posting :as posting]
            [kontor.validation :as v]))

;; AU FY2026 = 1 Jul 2025 → 1 Jul 2026 (period/end is exclusive).
(def fy26-start #inst "2025-07-01T00:00:00Z")
(def fy26-end   #inst "2026-07-01T00:00:00Z")
(def aug-15-2025 #inst "2025-08-15T00:00:00Z")
(def feb-15-2026 #inst "2026-02-15T00:00:00Z")
(def jun-30-2026 #inst "2026-06-30T23:59:59Z")
(def jul-1-2026  #inst "2026-07-01T00:00:00Z")

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
                 {:kontor.period/start fy26-start
                  :kontor.period/end   fy26-end
                  :kontor.period/tag   :normal
                  :kontor.period/name  "FY2026"}])
    conn))

(defn- seed-fy2026!
  "Post one taxable invoice and two operating-expense entries inside
   FY2026."
  [conn]
  ;; Sales invoice: A$1000 net @ 10% GST → A$1100 gross on Aug 15 2025.
  (au-invoice/post-au-invoice! conn
                               {:kontor.invoice/external-id "INV-2026-1"
                                :kontor.invoice/issue-date aug-15-2025
                                :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                                                 :kontor.invoice-line/unit-price 1000M}]})
  (let [db (d/db conn)
        aud (:db/id (d/entity db [:kontor.commodity/symbol "AUD"]))
        bank (ace db "11100")
        rent (ace db "61500")
        telecom (ace db "61700")
        exp-jnl (:db/id (d/entity db [:kontor.journal/code "EXP"]))]
    ;; Expense: A$300 rent paid from bank on Aug 15 2025.
    (v/transact-with-validation
     conn
     (posting/build-transaction
      {:transaction
       {:kontor.transaction/external-id "EXP-2026-1"
        :kontor.transaction/journal exp-jnl
        :kontor.transaction/effective-date aug-15-2025
        :kontor.transaction/narration "Rent Aug 2025"
        :kontor.transaction/state :posted
        :kontor.transaction/posted-at aug-15-2025}
       :postings
       [{:kontor.posting/account rent :kontor.posting/amount 300M
         :kontor.posting/commodity aud :kontor.posting/posted-at aug-15-2025}
        {:kontor.posting/account bank :kontor.posting/amount -300M
         :kontor.posting/commodity aud :kontor.posting/posted-at aug-15-2025}]}))
    ;; Expense: A$200 phone on Feb 15 2026.
    (v/transact-with-validation
     conn
     (posting/build-transaction
      {:transaction
       {:kontor.transaction/external-id "EXP-2026-2"
        :kontor.transaction/journal exp-jnl
        :kontor.transaction/effective-date feb-15-2026
        :kontor.transaction/narration "Telephone Feb"
        :kontor.transaction/state :posted
        :kontor.transaction/posted-at feb-15-2026}
       :postings
       [{:kontor.posting/account telecom :kontor.posting/amount 200M
         :kontor.posting/commodity aud :kontor.posting/posted-at feb-15-2026}
        {:kontor.posting/account bank :kontor.posting/amount -200M
         :kontor.posting/commodity aud :kontor.posting/posted-at feb-15-2026}]})))
  ;; Add a second invoice in Feb to push revenue past expenses.
  (au-invoice/post-au-invoice! conn
                               {:kontor.invoice/external-id "INV-2026-2"
                                :kontor.invoice/issue-date feb-15-2026
                                :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                                                 :kontor.invoice-line/unit-price 500M}]})
  ;; New P&L: revenue -1500 (Cr), rent +300, telecom +200 → net -1000 (profit 1000)
  nil)

;; ============================================================================
;; Sanity: pre-close balances
;; ============================================================================

(deftest pre-close-pnl-balances
  (testing "FY2026 has revenue 1500 (Cr), rent 300 (Dr), telecom 200 (Dr)
            → net profit 1000."
    (let [conn (bootstrap)]
      (seed-fy2026! conn)
      (let [db (d/db conn)
            aud (:db/id (d/entity db [:kontor.commodity/symbol "AUD"]))
            rev    (ace db "41100")
            rent   (ace db "61500")
            telec  (ace db "61700")]
        (is (= -1500M (-> (balance/account-balance conn rev
                                                   {:as-of-valid jun-30-2026})
                          (get aud) :amount)))
        (is (= 300M (-> (balance/account-balance conn rent
                                                 {:as-of-valid jun-30-2026})
                        (get aud) :amount)))
        (is (= 200M (-> (balance/account-balance conn telec
                                                 {:as-of-valid jun-30-2026})
                        (get aud) :amount)))))))

;; ============================================================================
;; close-au-fiscal-year! — end-to-end
;; ============================================================================

(deftest fiscal-year-close-rolls-pnl-to-31200
  (testing "Close FY2026 → P&L accounts zero on 1 Jul 2026,
            retained earnings (31200) carries the profit, balance
            sheet equation still holds."
    (let [conn (bootstrap)]
      (seed-fy2026! conn)
      (let [db (d/db conn)
            period-eid (d/q '[:find ?p . :where [?p :kontor.period/name "FY2026"]] db)
            {:keys [close-result period-close-tx-report]}
            (au-closing/close-au-fiscal-year! conn
                                              {:period-eid period-eid
                                               :external-id "FY26-AU-CLOSE"})]
        (is (some? (:transaction-eid close-result))
            "Close emitted a closing transaction")
        (is (some? period-close-tx-report)
            "Period soft-closed after the closing tx")
        ;; Net = revenue -1500 + rent 300 + telecom 200 = -1000 (profit)
        (let [db (d/db conn)
              aud (:db/id (d/entity db [:kontor.commodity/symbol "AUD"]))
              net (get-in close-result [:net-by-commodity aud :amount])]
          (is (= -1000M net)
              "Net P&L: revenue -1500 + 300 + 200 = -1000 (profit)"))
        (let [db (d/db conn)
              aud (:db/id (d/entity db [:kontor.commodity/symbol "AUD"]))
              rev    (ace db "41100")
              rent   (ace db "61500")
              telec  (ace db "61700")
              retained (ace db "31200")
              new-yr-bal (fn [eid]
                           (-> (balance/account-balance conn eid
                                                        {:as-of-valid jul-1-2026})
                               (get aud)
                               :amount))]
          (is (zero? (.compareTo ^java.math.BigDecimal (new-yr-bal rev) 0M))
              "Revenue zeroed at start of FY27")
          (is (zero? (.compareTo ^java.math.BigDecimal (new-yr-bal rent) 0M))
              "Rent zeroed")
          (is (zero? (.compareTo ^java.math.BigDecimal (new-yr-bal telec) 0M))
              "Telecom zeroed")
          (is (= -1000M (new-yr-bal retained))
              "Retained earnings carries the prior-year profit
               (-1000 = Cr balance = profit on an equity account)"))))))

;; ============================================================================
;; Idempotency: cannot close twice
;; ============================================================================

(deftest cannot-close-period-twice
  (let [conn (bootstrap)]
    (seed-fy2026! conn)
    (let [period-eid (d/q '[:find ?p . :where [?p :kontor.period/name "FY2026"]] (d/db conn))]
      (au-closing/close-au-fiscal-year! conn
                                        {:period-eid period-eid
                                         :external-id "FY26-AU-CLOSE-1"})
      (is (thrown? clojure.lang.ExceptionInfo
                   (au-closing/close-au-fiscal-year!
                    conn
                    {:period-eid period-eid
                     :external-id "FY26-AU-CLOSE-2"}))
          "Second close attempt on the same period is rejected"))))

;; ============================================================================
;; Auto-create CLOSE journal if missing
;; ============================================================================

(deftest auto-creates-close-journal
  (testing "If the CLOSE journal isn't pre-seeded, close-au-fiscal-
            year! creates it on first call."
    (let [conn (core/create-test-db)
          _ (v/install-invariants! conn)
          _ (chart/install! conn)
          ;; INV + EXP for seeding; deliberately no CLOSE journal
          _ (d/transact conn [{:kontor.journal/code "INV" :kontor.journal/name "Sales"
                               :kontor.journal/type :sale :kontor.journal/active true}
                              {:kontor.journal/code "EXP" :kontor.journal/name "Expenses"
                               :kontor.journal/type :purchase :kontor.journal/active true}
                              {:kontor.period/start fy26-start
                               :kontor.period/end fy26-end
                               :kontor.period/tag :normal
                               :kontor.period/name "FY2026"}])
          _ (seed-fy2026! conn)
          period-eid (d/q '[:find ?p . :where [?p :kontor.period/name "FY2026"]] (d/db conn))]
      (is (nil? (:db/id (d/entity (d/db conn) [:kontor.journal/code "CLOSE"])))
          "CLOSE journal not present before close")
      (au-closing/close-au-fiscal-year! conn
                                        {:period-eid period-eid
                                         :external-id "AUTO-J-CLOSE"})
      (is (some? (:db/id (d/entity (d/db conn) [:kontor.journal/code "CLOSE"])))
          "CLOSE journal auto-created"))))

;; ============================================================================
;; Missing-account error
;; ============================================================================

(deftest missing-retained-earnings-throws
  (testing "If the AU chart isn't installed (no 31200), the planner
            throws a clear error."
    (let [conn (core/create-test-db)]
      (v/install-invariants! conn)
      (d/transact conn [{:kontor.period/start fy26-start
                         :kontor.period/end fy26-end
                         :kontor.period/tag :normal
                         :kontor.period/name "FY2026"}])
      (let [period-eid (d/q '[:find ?p . :where [?p :kontor.period/name "FY2026"]] (d/db conn))]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Retained-earnings account"
             (au-closing/close-au-fiscal-year!
              conn {:period-eid period-eid})))))))

;; ============================================================================
;; ADR-068 plan-* pure form
;; ============================================================================

(deftest plan-au-fiscal-year-close-resolves-eids
  (let [conn (bootstrap)
        db (d/db conn)
        period-eid (d/q '[:find ?p . :where [?p :kontor.period/name "FY2026"]] db)
        planned (au-closing/plan-au-fiscal-year-close-tx-data
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
                 (au-closing/plan-au-fiscal-year-close-tx-data db {})))))

;; ============================================================================
;; AU FY bounds — the country-specific quirk
;; ============================================================================

(deftest au-fy-bounds-1-jul-to-30-jun
  (testing "Period for AU FY runs 1 Jul → 1 Jul exclusive (i.e. 30 Jun
            inclusive as the last operational day)."
    (let [conn (bootstrap)
          db (d/db conn)
          period (d/pull db [:kontor.period/start :kontor.period/end :kontor.period/name]
                         (d/q '[:find ?p . :where [?p :kontor.period/name "FY2026"]] db))]
      (is (= "FY2026" (:kontor.period/name period)))
      (is (= fy26-start (:kontor.period/start period)))
      (is (= fy26-end   (:kontor.period/end period)))
      (is (not= (java.time.LocalDate/of 2026 1 1)
                (.toLocalDate
                 (.atZone (.toInstant ^java.util.Date (:kontor.period/start period))
                          java.time.ZoneOffset/UTC)))
          "AU FY starts 1 July, NOT 1 January (the calendar-year quirk)"))))

;; ============================================================================
;; Sum-to-zero after close
;; ============================================================================

(deftest sum-to-zero-after-close
  (testing "Every posting in the DB sums to zero after the close —
            kernel invariant."
    (let [conn (bootstrap)]
      (seed-fy2026! conn)
      (let [period-eid (d/q '[:find ?p . :where [?p :kontor.period/name "FY2026"]] (d/db conn))]
        (au-closing/close-au-fiscal-year! conn
                                          {:period-eid period-eid
                                           :external-id "FY26-Z"}))
      (let [db (d/db conn)
            pairs (d/q '[:find ?p ?amt
                         :where [?p :kontor.posting/amount ?amt]] db)
            total (reduce (fn [^java.math.BigDecimal acc [_ ^java.math.BigDecimal x]]
                            (.add acc x))
                          0M pairs)]
        (is (zero? (.compareTo ^java.math.BigDecimal total 0M))
            (str "All postings must sum to zero, got " total))))))
