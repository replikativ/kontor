(ns kontor.closing-test
  "Year-end (and any-period) closing — verifies that:

   - all P&L accounts have non-zero period-end balances,
   - close-period! emits a single closing tx that negates each P&L
     line into retained earnings,
   - post-close, P&L balances at year-end are zero AND retained
     earnings carries the net result,
   - the closing tx is unique per period (`:kontor.transaction/closes-period`
     is :db.unique/identity),
   - the kernel's sum-to-zero invariant is preserved end-to-end.

   Uses the SKR04 chart so we exercise the DE wrapper too."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.balance :as balance]
            [kontor.closing :as closing]
            [kontor.core :as core]
            [kontor.l10n-de.chart :as chart]
            [kontor.l10n-de.closing :as de-closing]
            [kontor.posting :as posting]))

(def jan-1   #inst "2025-01-01T00:00:00Z")
(def feb-15  #inst "2025-02-15T00:00:00Z")
(def jun-1   #inst "2025-06-01T00:00:00Z")
(def dec-31  #inst "2025-12-31T23:59:59Z")
(def jan-1-26 #inst "2026-01-01T00:00:00Z")

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (chart/install! conn)
    (d/transact conn
                [{:kontor.journal/code "INV"  :kontor.journal/name "Sales invoices"
                  :kontor.journal/type :sale  :kontor.journal/active true}
                 {:kontor.journal/code "EXP"  :kontor.journal/name "Expense bookings"
                  :kontor.journal/type :purchase :kontor.journal/active true}
                 {:kontor.period/start jan-1
                  :kontor.period/end   jan-1-26
                  :kontor.period/tag   :normal
                  :kontor.period/name  "FY2025"}])
    conn))

;; Helper: post a couple of revenue + expense entries inside FY2025.
(defn- seed-pnl-activity! [conn]
  (let [db (d/db conn)
        eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
        recv (ace db "1400")          ; AR
        rev19 (ace db "4400")         ; revenue 19%
        ust19 (ace db "3801")         ; output VAT
        ;; A pretend cash account for the expense — bank current.
        bank (ace db "1200")
        rent (ace db "6300")          ; Miete (expense)
        sw   (ace db "6815")          ; Software (expense)
        inv-jnl (:db/id (d/entity db [:kontor.journal/code "INV"]))
        exp-jnl (:db/id (d/entity db [:kontor.journal/code "EXP"]))]
    ;; A sales invoice: gross 1190, net 1000, VAT 190 — fully on Feb 15.
    (d/transact conn
                (posting/build-transaction
                 {:transaction
                  {:kontor.transaction/external-id "FY25-INV-1"
                   :kontor.transaction/journal inv-jnl
                   :kontor.transaction/effective-date feb-15
                   :kontor.transaction/narration "Sales invoice 1"
                   :kontor.transaction/state :posted
                   :kontor.transaction/posted-at feb-15}
                  :postings
                  [{:kontor.posting/account recv  :kontor.posting/amount 1190M
                    :kontor.posting/commodity eur :kontor.posting/posted-at feb-15}
                   {:kontor.posting/account rev19 :kontor.posting/amount -1000M
                    :kontor.posting/commodity eur :kontor.posting/posted-at feb-15}
                   {:kontor.posting/account ust19 :kontor.posting/amount -190M
                    :kontor.posting/commodity eur :kontor.posting/posted-at feb-15}]}))
    ;; A second invoice in June for 2380 gross.
    (d/transact conn
                (posting/build-transaction
                 {:transaction
                  {:kontor.transaction/external-id "FY25-INV-2"
                   :kontor.transaction/journal inv-jnl
                   :kontor.transaction/effective-date jun-1
                   :kontor.transaction/narration "Sales invoice 2"
                   :kontor.transaction/state :posted
                   :kontor.transaction/posted-at jun-1}
                  :postings
                  [{:kontor.posting/account recv  :kontor.posting/amount 2380M
                    :kontor.posting/commodity eur :kontor.posting/posted-at jun-1}
                   {:kontor.posting/account rev19 :kontor.posting/amount -2000M
                    :kontor.posting/commodity eur :kontor.posting/posted-at jun-1}
                   {:kontor.posting/account ust19 :kontor.posting/amount -380M
                    :kontor.posting/commodity eur :kontor.posting/posted-at jun-1}]}))
    ;; Expenses paid out of bank: rent 600, software 100.
    (d/transact conn
                (posting/build-transaction
                 {:transaction
                  {:kontor.transaction/external-id "FY25-EXP-1"
                   :kontor.transaction/journal exp-jnl
                   :kontor.transaction/effective-date feb-15
                   :kontor.transaction/narration "Office rent Feb"
                   :kontor.transaction/state :posted
                   :kontor.transaction/posted-at feb-15}
                  :postings
                  [{:kontor.posting/account rent :kontor.posting/amount 600M
                    :kontor.posting/commodity eur :kontor.posting/posted-at feb-15}
                   {:kontor.posting/account bank :kontor.posting/amount -600M
                    :kontor.posting/commodity eur :kontor.posting/posted-at feb-15}]}))
    (d/transact conn
                (posting/build-transaction
                 {:transaction
                  {:kontor.transaction/external-id "FY25-EXP-2"
                   :kontor.transaction/journal exp-jnl
                   :kontor.transaction/effective-date jun-1
                   :kontor.transaction/narration "Software license"
                   :kontor.transaction/state :posted
                   :kontor.transaction/posted-at jun-1}
                  :postings
                  [{:kontor.posting/account sw   :kontor.posting/amount 100M
                    :kontor.posting/commodity eur :kontor.posting/posted-at jun-1}
                   {:kontor.posting/account bank :kontor.posting/amount -100M
                    :kontor.posting/commodity eur :kontor.posting/posted-at jun-1}]}))
    ;; Expected end-of-year (before close):
    ;;   revenue 19%      = -3000  (credit balance, natural for income)
    ;;   rent expense     =   600
    ;;   software expense =   100
    ;;   net P&L          =   -3000 + 600 + 100 = -2300
    ;;     ⇒ profit of 2300 → retained-earnings posting +2300 (credit)
    nil))

;; ============================================================================
;; Pre-close sanity
;; ============================================================================

(deftest pnl-balances-non-zero-before-close
  (let [conn (bootstrap)]
    (seed-pnl-activity! conn)
    (let [db (d/db conn)
          eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
          rev (ace db "4400")
          rent (ace db "6300")]
      (is (= -3000M (-> (balance/account-balance conn rev
                                                 {:as-of-valid dec-31})
                        (get eur)
                        :amount)))
      (is (= 600M (-> (balance/account-balance conn rent
                                               {:as-of-valid dec-31})
                      (get eur)
                      :amount))))))

;; ============================================================================
;; Kernel close-period!
;; ============================================================================

(deftest close-period-zeros-pnl-and-credits-retained
  (let [conn (bootstrap)]
    (seed-pnl-activity! conn)
    (let [db (d/db conn)
          period-eid (d/q '[:find ?p . :where [?p :kontor.period/name "FY2025"]] db)
          retained (ace db "2900")
          inv-jnl (:db/id (d/entity db [:kontor.journal/code "INV"]))
          {:keys [transaction-eid postings-count net-by-commodity]}
          (closing/close-period! conn
                                 {:period-eid period-eid
                                  :retained-earnings-eid retained
                                  :journal-eid inv-jnl
                                  :external-id "FY25-CLOSE"})
          db (d/db conn)
          eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))]
      (is (some? transaction-eid))
      ;; 3 P&L accounts had non-zero balance + 1 retained-earnings = 4 postings
      (is (= 4 postings-count))
      ;; Net P&L: revenue -3000 + rent 600 + sw 100 = -2300 (profit)
      (is (= -2300M (-> net-by-commodity (get eur) :amount)))
      ;; Verify P&L accounts are now ZERO going into the new period.
      ;; Query at the new year's start (= the closing tx's valid-from
      ;; is the period's last instant, which is just before this).
      (let [rev-bal  (-> (balance/account-balance conn (ace db "4400")
                                                  {:as-of-valid jan-1-26})
                         (get eur))
            rent-bal (-> (balance/account-balance conn (ace db "6300")
                                                  {:as-of-valid jan-1-26})
                         (get eur))
            sw-bal   (-> (balance/account-balance conn (ace db "6815")
                                                  {:as-of-valid jan-1-26})
                         (get eur))
            ret-bal  (-> (balance/account-balance conn retained
                                                  {:as-of-valid jan-1-26})
                         (get eur))]
        (is (= 0M (:amount rev-bal)))
        (is (= 0M (:amount rent-bal)))
        (is (= 0M (:amount sw-bal)))
        ;; Retained earnings carries the net (income is negative ⇒
        ;; the closing posts +(-(-3000)) = +3000 to retained, then
        ;; +(-(600)) +(-(100)) … which net to -(-2300) = +2300 reversed.
        ;; Our closing posts the negation of each P&L balance to that
        ;; account, then a counter-posting on retained equal to the
        ;; SUM of P&L balances. Sum is -2300, so retained gets -2300.
        ;; (Equity: a credit balance is negative in our sign convention,
        ;; so a profit of 2300 lands as -2300 on the equity account.)
        (is (= -2300M (:amount ret-bal)))))))

;; ============================================================================
;; Idempotency (refuses second close)
;; ============================================================================

(deftest close-period-refuses-second-close
  (let [conn (bootstrap)]
    (seed-pnl-activity! conn)
    (let [db (d/db conn)
          period-eid (d/q '[:find ?p . :where [?p :kontor.period/name "FY2025"]] db)
          retained (ace db "2900")
          inv-jnl (:db/id (d/entity db [:kontor.journal/code "INV"]))]
      (closing/close-period! conn
                             {:period-eid period-eid
                              :retained-earnings-eid retained
                              :journal-eid inv-jnl})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already has a closing"
                            (closing/close-period! conn
                                                   {:period-eid period-eid
                                                    :retained-earnings-eid retained
                                                    :journal-eid inv-jnl}))))))

(deftest close-period-noop-when-no-pnl-activity
  (let [conn (bootstrap)
        db (d/db conn)
        period-eid (d/q '[:find ?p . :where [?p :kontor.period/name "FY2025"]] db)
        retained (ace db "2900")
        inv-jnl (:db/id (d/entity db [:kontor.journal/code "INV"]))
        result (closing/close-period! conn
                                      {:period-eid period-eid
                                       :retained-earnings-eid retained
                                       :journal-eid inv-jnl})]
    (is (nil? (:transaction-eid result)))
    (is (= :no-pnl-activity (:note result)))))

;; ============================================================================
;; DE wrapper
;; ============================================================================

(deftest de-wrapper-routes-to-skr04-2900-and-soft-closes
  (let [conn (bootstrap)]
    (seed-pnl-activity! conn)
    (let [db (d/db conn)
          period-eid (d/q '[:find ?p . :where [?p :kontor.period/name "FY2025"]] db)
          {:keys [close-result period-close-tx-report]}
          (de-closing/close-fiscal-year! conn {:period-eid period-eid})
          db (d/db conn)
          retained (ace db "2900")
          eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
          ret-bal (-> (balance/account-balance conn retained
                                               {:as-of-valid jan-1-26})
                      (get eur))
          period (d/pull db [:kontor.period/locked-at] period-eid)]
      (is (some? (:transaction-eid close-result)))
      (is (= -2300M (:amount ret-bal)))
      (is (some? (:kontor.period/locked-at period))
          "DE wrapper soft-closes the period after posting the close tx")
      (is (some? period-close-tx-report)))))
