(ns kontor.l10n-fr.closing-test
  "Tests for kontor.l10n-fr.closing — wrapping the kernel closer with
   PCG retained-earnings routing (110 Report à nouveau créditeur).

   Scenario: a small FR business with one 20% TVA invoice and two
   expense postings. End of FY2025: P&L collapses to retained
   earnings (account 110), all P&L accounts zero on the next-year
   balance, balance sheet still balanced."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.reporting.balance :as balance]
            [kontor.core :as core]
            [kontor.l10n-fr.chart :as chart]
            [kontor.l10n-fr.closing :as fr-closing]
            [kontor.l10n-fr.invoice :as fr-invoice]
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
                [{:kontor.journal/code "VTE" :kontor.journal/name "Journal des ventes"
                  :kontor.journal/type :sale :kontor.journal/active true}
                 {:kontor.journal/code "ACH" :kontor.journal/name "Journal des achats"
                  :kontor.journal/type :purchase :kontor.journal/active true}
                 {:kontor.period/start jan-1
                  :kontor.period/end   jan-1-26
                  :kontor.period/tag   :normal
                  :kontor.period/name  "FY2025"}])
    conn))

(defn- seed-fy2025!
  "Post one 20% TVA invoice and two operating-expense entries inside
   FY2025."
  [conn]
  ;; Sales invoice 1: €1000 net @ 20% TVA → €1200 gross on Feb 15.
  (fr-invoice/post-fr-invoice! conn
                               {:kontor.invoice/external-id "INV-2025-1"
                                :kontor.invoice/issue-date feb-15
                                :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                                                 :kontor.invoice-line/unit-price 1000M}]})
  ;; Expense: €600 office supplies paid from bank on Feb 15.
  (let [db (d/db conn)
        eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
        bank (ace db "5121")
        office (ace db "606")    ; Achats non stockés (fournitures)
        rent (ace db "613")      ; Locations
        ach-jnl (:db/id (d/entity db [:kontor.journal/code "ACH"]))]
    (v/transact-with-validation
     conn
     (posting/build-transaction
      {:transaction
       {:kontor.transaction/external-id "EXP-2025-1"
        :kontor.transaction/journal ach-jnl
        :kontor.transaction/effective-date feb-15
        :kontor.transaction/narration "Fournitures de bureau Feb"
        :kontor.transaction/state :posted
        :kontor.transaction/posted-at feb-15}
       :postings
       [{:kontor.posting/account office :kontor.posting/amount 600M
         :kontor.posting/commodity eur :kontor.posting/posted-at feb-15}
        {:kontor.posting/account bank   :kontor.posting/amount -600M
         :kontor.posting/commodity eur :kontor.posting/posted-at feb-15}]}))
    ;; Expense: €400 rent paid from bank on Jun 1.
    (v/transact-with-validation
     conn
     (posting/build-transaction
      {:transaction
       {:kontor.transaction/external-id "EXP-2025-2"
        :kontor.transaction/journal ach-jnl
        :kontor.transaction/effective-date jun-1
        :kontor.transaction/narration "Loyer juin"
        :kontor.transaction/state :posted
        :kontor.transaction/posted-at jun-1}
       :postings
       [{:kontor.posting/account rent :kontor.posting/amount 400M
         :kontor.posting/commodity eur :kontor.posting/posted-at jun-1}
        {:kontor.posting/account bank :kontor.posting/amount -400M
         :kontor.posting/commodity eur :kontor.posting/posted-at jun-1}]})))
  ;; Add a second sales invoice to push revenue past expenses
  ;; (we want a meaningful non-zero close).
  (fr-invoice/post-fr-invoice! conn
                               {:kontor.invoice/external-id "INV-2025-2"
                                :kontor.invoice/issue-date jun-1
                                :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                                                 :kontor.invoice-line/unit-price 500M}]})
  ;; Final P&L: revenue 706 = -1500, office 606 = +600, rent 613 = +400
  ;; → net P&L = -500 (i.e. €500 profit, credit balance)
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
            eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
            rev    (ace db "706")
            office (ace db "606")
            rent   (ace db "613")]
        (is (= -1500M (-> (balance/account-balance conn rev
                                                   {:as-of-valid dec-31})
                          (get eur) :amount)))
        (is (= 600M (-> (balance/account-balance conn office
                                                 {:as-of-valid dec-31})
                        (get eur) :amount)))
        (is (= 400M (-> (balance/account-balance conn rent
                                                 {:as-of-valid dec-31})
                        (get eur) :amount)))))))

;; ============================================================================
;; close-fr-fiscal-year! — end-to-end
;; ============================================================================

(deftest fiscal-year-close-rolls-pnl-to-110
  (testing "Close FY2025 → P&L accounts zero on Jan 1 2026,
            Report à nouveau (110) carries the profit, balance sheet
            still balanced."
    (let [conn (bootstrap)]
      (seed-fy2025! conn)
      (let [db (d/db conn)
            period-eid (d/q '[:find ?p . :where [?p :kontor.period/name "FY2025"]] db)
            {:keys [close-result period-close-tx-report]}
            (fr-closing/close-fr-fiscal-year! conn
                                              {:period-eid period-eid
                                               :external-id "FY25-FR-CLOSE"})]
        (is (some? (:transaction-eid close-result))
            "Close emitted a closing transaction")
        (is (some? period-close-tx-report)
            "Period soft-closed after the closing tx")
        ;; net-by-commodity should reflect the net P&L = -500 (profit).
        (let [db (d/db conn)
              eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
              net (get-in close-result [:net-by-commodity eur :amount])]
          (is (= -500M net)
              "Net P&L: revenue -1500 + office 600 + rent 400 = -500
               (sign convention: income credits are negative)"))
        ;; P&L accounts at the new year start: should be zero.
        (let [db (d/db conn)
              eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
              rev      (ace db "706")
              office   (ace db "606")
              rent     (ace db "613")
              retained (ace db "110")
              new-yr-bal (fn [eid]
                           (-> (balance/account-balance conn eid
                                                        {:as-of-valid jan-1-26})
                               (get eur)
                               :amount))]
          (is (zero? (.compareTo ^java.math.BigDecimal (new-yr-bal rev) 0M))
              "Revenue (706) zeroed")
          (is (zero? (.compareTo ^java.math.BigDecimal (new-yr-bal office) 0M))
              "Office (606) zeroed")
          (is (zero? (.compareTo ^java.math.BigDecimal (new-yr-bal rent) 0M))
              "Rent (613) zeroed")
          ;; Retained earnings: net P&L was -500 (profit); the close
          ;; posts the negation of each P&L line + a counter-posting
          ;; equal to the SUM of P&L balances. Sum is -500, so retained
          ;; gets -500 (negative on an equity account = credit balance
          ;; = profit retained).
          (is (= -500M (new-yr-bal retained))
              "Report à nouveau (110) carries the prior-year profit"))))))

;; ============================================================================
;; Override to 120 — the two-step PCG flow
;; ============================================================================

(deftest fiscal-year-close-to-120-resultat-exercice
  (testing "Override :retained-code \"120\" → close routes P&L to
              Résultat de l'exercice instead of Report à nouveau.
              Caller then does a post-AG reclassification 120 → 110."
    (let [conn (bootstrap)]
      (seed-fy2025! conn)
      (let [period-eid (d/q '[:find ?p . :where [?p :kontor.period/name "FY2025"]]
                            (d/db conn))]
        (fr-closing/close-fr-fiscal-year! conn
                                          {:period-eid period-eid
                                           :external-id "FY25-FR-120"
                                           :retained-code "120"})
        (let [db (d/db conn)
              eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
              resultat (ace db "120")
              report-an (ace db "110")
              new-yr-bal (fn [eid]
                           (or (-> (balance/account-balance conn eid
                                                            {:as-of-valid jan-1-26})
                                   (get eur)
                                   :amount)
                               0M))]
          (is (= -500M (new-yr-bal resultat))
              "120 Résultat de l'exercice (bénéfice) carries the profit")
          (is (zero? (.compareTo ^java.math.BigDecimal (new-yr-bal report-an) 0M))
              "110 Report à nouveau untouched (post-AG step is manual)"))))))

;; ============================================================================
;; Idempotency: cannot close twice
;; ============================================================================

(deftest cannot-close-period-twice
  (let [conn (bootstrap)]
    (seed-fy2025! conn)
    (let [period-eid (d/q '[:find ?p . :where [?p :kontor.period/name "FY2025"]] (d/db conn))]
      (fr-closing/close-fr-fiscal-year! conn
                                        {:period-eid period-eid
                                         :external-id "FY25-FR-CLOSE-1"})
      (is (thrown? clojure.lang.ExceptionInfo
                   (fr-closing/close-fr-fiscal-year!
                    conn
                    {:period-eid period-eid
                     :external-id "FY25-FR-CLOSE-2"}))
          "Second close attempt on the same period is rejected"))))

;; ============================================================================
;; Auto-create CLOSE journal if missing
;; ============================================================================

(deftest auto-creates-close-journal
  (testing "If the CLOSE journal isn't pre-seeded, close-fr-fiscal-
            year! creates it on first call. VTE + ACH journals are
            still required because the seed activity uses them."
    (let [conn (core/create-test-db)
          _ (v/install-invariants! conn)
          _ (chart/install! conn)
          _ (d/transact conn [{:kontor.journal/code "VTE" :kontor.journal/name "Sales"
                               :kontor.journal/type :sale :kontor.journal/active true}
                              {:kontor.journal/code "ACH" :kontor.journal/name "Purchases"
                               :kontor.journal/type :purchase :kontor.journal/active true}
                              {:kontor.period/start jan-1
                               :kontor.period/end jan-1-26
                               :kontor.period/tag :normal
                               :kontor.period/name "FY2025"}])
          _ (seed-fy2025! conn)
          period-eid (d/q '[:find ?p . :where [?p :kontor.period/name "FY2025"]] (d/db conn))]
      (is (nil? (:db/id (d/entity (d/db conn) [:kontor.journal/code "CLOSE"])))
          "CLOSE journal not present before close")
      (fr-closing/close-fr-fiscal-year! conn
                                        {:period-eid period-eid
                                         :external-id "AUTO-J-CLOSE"})
      (is (some? (:db/id (d/entity (d/db conn) [:kontor.journal/code "CLOSE"])))
          "CLOSE journal auto-created"))))

;; ============================================================================
;; Missing-account error
;; ============================================================================

(deftest missing-retained-earnings-throws
  (testing "If the FR chart isn't installed (no 110), the planner
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
             (fr-closing/close-fr-fiscal-year!
              conn {:period-eid period-eid})))))))

;; ============================================================================
;; ADR-068 plan-* pure form
;; ============================================================================

(deftest plan-fr-fiscal-year-close-resolves-eids
  (let [conn (bootstrap)
        db (d/db conn)
        period-eid (d/q '[:find ?p . :where [?p :kontor.period/name "FY2025"]] db)
        planned (fr-closing/plan-fr-fiscal-year-close-tx-data
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
                 (fr-closing/plan-fr-fiscal-year-close-tx-data db {})))))

;; ============================================================================
;; Sum-to-zero after close
;; ============================================================================

(deftest sum-to-zero-after-close
  (testing "Every posting in the DB sums to zero across all accounts
            and all commodities after the close — kernel invariant."
    (let [conn (bootstrap)]
      (seed-fy2025! conn)
      (let [period-eid (d/q '[:find ?p . :where [?p :kontor.period/name "FY2025"]] (d/db conn))]
        (fr-closing/close-fr-fiscal-year! conn
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
