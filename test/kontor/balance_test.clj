(ns kontor.balance-test
  "balance.clj + ledger.clj — bitemporal account-balance and ledger
   queries. Exercises both axes:
     - tx-time (corrections made AFTER a query date are invisible)
     - valid-time (postings dated AFTER the query valid-date excluded)"
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.balance :as balance]
            [kontor.core :as core]
            [kontor.ledger :as ledger]
            [kontor.money :as money]
            [kontor.posting :as posting]
            [kontor.validation :as v]))

(def jan-15 #inst "2026-01-15T00:00:00Z")
(def feb-15 #inst "2026-02-15T00:00:00Z")
(def mar-15 #inst "2026-03-15T00:00:00Z")
(def far-future #inst "2099-01-01T00:00:00Z")

(defn- catalog! [conn]
  (d/transact
   conn
   [{:db/id -1 :kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
     :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "EUR"}
    {:db/id -2 :kontor.commodity/symbol "USD" :kontor.commodity/name "US Dollar"
     :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "USD"}
    {:db/id -3 :kontor.account/path "Assets:Receivable" :kontor.account/name "AR"
     :kontor.account/type :asset :kontor.account/active true}
    {:db/id -4 :kontor.account/path "Income:Sales" :kontor.account/name "Sales"
     :kontor.account/type :income :kontor.account/active true}
    {:db/id -5 :kontor.journal/code "INV" :kontor.journal/name "J"
     :kontor.journal/type :sale :kontor.journal/active true}])
  (let [db (d/db conn)]
    {:eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
     :usd (:db/id (d/entity db [:kontor.commodity/symbol "USD"]))
     :rec (:db/id (d/entity db [:kontor.account/path "Assets:Receivable"]))
     :rev (:db/id (d/entity db [:kontor.account/path "Income:Sales"]))
     :jnl (:db/id (d/entity db [:kontor.journal/code "INV"]))}))

(defn- post-pair!
  "Two-line balanced tx: AR debit + Sales credit, both EUR, both posted."
  [conn {:keys [eur rec rev jnl]} effective amount-debit external-id]
  (let [tx (-> (posting/build-transaction
                {:transaction
                 {:kontor.transaction/external-id    external-id
                  :kontor.transaction/journal        jnl
                  :kontor.transaction/effective-date effective
                  :kontor.transaction/narration      external-id
                  :kontor.transaction/state          :posted
                  :kontor.transaction/posted-at      effective}
                 :postings
                 [{:kontor.posting/account rec :kontor.posting/amount    amount-debit
                   :kontor.posting/commodity eur}
                  {:kontor.posting/account rev :kontor.posting/amount    (.negate ^java.math.BigDecimal amount-debit)
                   :kontor.posting/commodity eur}]})
               (->> (mapv (fn [m]
                            (cond-> m
                              (some? (:kontor.posting/account m))
                              (assoc :kontor.posting/posted-at effective))))))]
    (v/transact-with-validation conn tx)))

;; ============================================================================
;; account-balance — happy paths
;; ============================================================================

(deftest empty-account-has-empty-balance
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        {:keys [rec]} (catalog! conn)]
    (is (= {} (balance/account-balance conn rec))
        "An account with no postings has no balance entries.")))

(deftest single-posting-balance
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        {:keys [eur rec] :as cat} (catalog! conn)
        _ (post-pair! conn cat jan-15 100M "INV-1")
        bal (balance/account-balance conn rec)]
    (is (= #{eur} (set (keys bal))))
    (is (money/equiv? (money/money "100" eur) (get bal eur)))))

(deftest sum-of-multiple-postings
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        {:keys [eur rec] :as cat} (catalog! conn)
        _ (post-pair! conn cat jan-15 100M "INV-1")
        _ (post-pair! conn cat feb-15 250M "INV-2")
        _ (post-pair! conn cat mar-15  75M "INV-3")
        bal (balance/account-balance conn rec)]
    (is (money/equiv? (money/money "425" eur) (get bal eur)))))

;; ============================================================================
;; Bitemporal: as-of-valid filter
;; ============================================================================

(deftest as-of-valid-excludes-future-postings
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        {:keys [eur rec] :as cat} (catalog! conn)
        _ (post-pair! conn cat jan-15 100M "INV-1")
        _ (post-pair! conn cat feb-15 250M "INV-2")
        _ (post-pair! conn cat mar-15  75M "INV-3")
        ;; "What was the AR balance at end of January?"
        jan-end-bal (balance/account-balance conn rec
                                             {:as-of-valid #inst "2026-01-31T23:59:59Z"})
        feb-end-bal (balance/account-balance conn rec
                                             {:as-of-valid #inst "2026-02-28T23:59:59Z"})]
    (is (money/equiv? (money/money "100" eur) (get jan-end-bal eur))
        "Jan only includes Jan-15 posting (100).")
    (is (money/equiv? (money/money "350" eur) (get feb-end-bal eur))
        "Feb-end includes Jan-15 + Feb-15 (100 + 250).")))

;; ============================================================================
;; Bitemporal: as-of-tx filter (tx-time slicing via d/as-of)
;; ============================================================================

(deftest as-of-tx-excludes-later-corrections
  (testing "ADR-008 invariant: 'what did the books show on filing date Y'
            requires tx-time slicing. Postings transacted *after* the
            cutoff must be invisible."
    (let [conn (core/create-test-db)
          _ (v/install-invariants! conn)
          {:keys [eur rec] :as cat} (catalog! conn)
          ;; Post Jan-15 invoice on day Jan-15
          _ (post-pair! conn cat jan-15 100M "INV-1")
          tx-time-snapshot (java.util.Date.)
          ;; Wait a bit so the next tx has a later :db/txInstant
          _ (Thread/sleep 5)
          ;; Now a correction lands later — backdated to Jan-15 but
          ;; entered (tx-time) AFTER our snapshot.
          _ (post-pair! conn cat jan-15 50M "CORR-1")
          ;; As-of tx-time-snapshot, only the original is visible.
          bal-as-known-at-snapshot (balance/account-balance
                                    conn rec {:as-of-tx tx-time-snapshot})
          ;; Today (default), both visible.
          bal-today (balance/account-balance conn rec)]
      (is (money/equiv? (money/money "100" eur) (get bal-as-known-at-snapshot eur))
          "Snapshot must not see the correction tx that landed later.")
      (is (money/equiv? (money/money "150" eur) (get bal-today eur))
          "Today's view sees both."))))

;; ============================================================================
;; Cancelled / draft excluded by default
;; ============================================================================

(deftest draft-postings-excluded-by-default
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        {:keys [eur rec] :as cat} (catalog! conn)
        _ (post-pair! conn cat jan-15 100M "POSTED-1")
        ;; A draft tx — no :kontor.transaction/state :posted, no posted-at
        draft-tx (posting/build-transaction
                  {:transaction
                   {:kontor.transaction/external-id    "DRAFT-1"
                    :kontor.transaction/journal        (:jnl cat)
                    :kontor.transaction/effective-date feb-15
                    :kontor.transaction/narration      "Draft"
                    :kontor.transaction/state          :draft}
                   :postings
                   [{:kontor.posting/account rec :kontor.posting/amount  900M :kontor.posting/commodity eur}
                    {:kontor.posting/account (:rev cat) :kontor.posting/amount -900M :kontor.posting/commodity eur}]})
        _ (v/transact-with-validation conn draft-tx)]
    (is (money/equiv? (money/money "100" eur) (get (balance/account-balance conn rec) eur))
        "Default include-states is #{:posted}; draft excluded.")
    (is (money/equiv? (money/money "1000" eur)
                      (get (balance/account-balance conn rec
                                                    {:include-states #{:posted :draft}})
                           eur))
        "Explicit include-states #{:posted :draft} brings the draft in.")))

;; ============================================================================
;; ledger
;; ============================================================================

(deftest postings-against-orders-by-valid-from
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        {:keys [rec] :as cat} (catalog! conn)
        _ (post-pair! conn cat mar-15 75M  "INV-3")        ;; posted out of order
        _ (post-pair! conn cat jan-15 100M "INV-1")
        _ (post-pair! conn cat feb-15 250M "INV-2")
        rows (ledger/postings-against conn rec)
        valid-froms (mapv :valid-from rows)]
    (is (= 3 (count rows)))
    (is (= [jan-15 feb-15 mar-15] valid-froms)
        "Default ordering is :asc on :valid-from.")
    (is (every? #(= :posted (:tx-state %)) rows))))

(deftest postings-against-can-reverse-order
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        {:keys [rec] :as cat} (catalog! conn)
        _ (post-pair! conn cat jan-15 100M "INV-1")
        _ (post-pair! conn cat feb-15 250M "INV-2")
        rows (ledger/postings-against conn rec {:order :desc})]
    (is (= [feb-15 jan-15] (mapv :valid-from rows)))))

(deftest running-balance-cumulative
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        {:keys [eur rec] :as cat} (catalog! conn)
        _ (post-pair! conn cat jan-15 100M "INV-1")
        _ (post-pair! conn cat feb-15 250M "INV-2")
        _ (post-pair! conn cat mar-15  50M "INV-3")
        rows (ledger/running-balance conn rec)]
    (is (= [(money/money "100" eur)
            (money/money "350" eur)
            (money/money "400" eur)]
           (mapv :running rows)))))
