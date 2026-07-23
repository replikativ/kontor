(ns kontor.validation-test
  "Verify the invariant integration:
     - Schema and built-in invariants install cleanly.
     - account-active invariant fires on a posting against an inactive
       account.
     - account-active passes on postings against active accounts.
     - account-active passes when the account omits :kontor.account/active
       (treated as active by default — Odoo-compatible)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.posting :as posting]
            [kontor.validation :as v]))

(def some-date #inst "2026-05-09T00:00:00Z")

(defn- catalog!
  "Seed minimal accounts/journals/commodities. Returns a map of
   eids by lookup-ref so tests can build txs against them."
  [conn {:keys [active-receivable?]
         :or {active-receivable? true}}]
  (d/transact conn
              [{:db/id -1 :kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "EUR"}
               (cond-> {:db/id -2 :kontor.account/path "Assets:Receivable"
                        :kontor.account/name "Trade receivables"
                        :kontor.account/type :asset}
                 :always (assoc :kontor.account/active active-receivable?))
               {:db/id -3 :kontor.account/path "Income:Sales"
                :kontor.account/name "Sales revenue"
                :kontor.account/type :income :kontor.account/active true}
               ;; An account with no :kontor.account/active set at all (testing
               ;; that "absent" reads as "active" per Odoo convention).
               {:db/id -4 :kontor.account/path "Equity:Opening"
                :kontor.account/name "Opening balance"
                :kontor.account/type :equity}
               {:db/id -5 :kontor.journal/code "INV" :kontor.journal/name "Customer invoices"
                :kontor.journal/type :sale :kontor.journal/active true}])
  (let [db (d/db conn)]
    {:eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
     :rec (:db/id (d/entity db [:kontor.account/path "Assets:Receivable"]))
     :rev (:db/id (d/entity db [:kontor.account/path "Income:Sales"]))
     :opn (:db/id (d/entity db [:kontor.account/path "Equity:Opening"]))
     :jnl (:db/id (d/entity db [:kontor.journal/code "INV"]))}))

;; ============================================================================
;; Installation
;; ============================================================================

(deftest install-invariants-is-idempotent
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (v/install-invariants! conn)
    (let [count-rules (d/q '[:find (count ?e) .
                             :where [?e :invariant/rule _]]
                           (d/db conn))]
      (is (= (count v/kernel-invariants) count-rules)
          "Re-installing must not duplicate rule entries (each :invariant/rule
           is cardinality-one and stays at exactly one entry per kernel rule)."))))

;; ============================================================================
;; account-active
;; ============================================================================

(deftest account-active-passes-on-active-accounts
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        {:keys [eur rec rev jnl]} (catalog! conn {})
        tx-data (posting/build-transaction
                 {:transaction
                  {:kontor.transaction/external-id    "INV-2026-0001"
                   :kontor.transaction/journal        jnl
                   :kontor.transaction/effective-date some-date
                   :kontor.transaction/narration      "Active accounts only"}
                  :postings
                  [{:kontor.posting/account rec :kontor.posting/amount  100M :kontor.posting/commodity eur}
                   {:kontor.posting/account rev :kontor.posting/amount -100M :kontor.posting/commodity eur}]})]
    (is (some? (v/transact-with-validation conn tx-data))
        "Both accounts active → invariant holds, transact returns a report.")))

(deftest account-active-passes-when-active-attribute-absent
  (testing "Per ADR-011 / Odoo convention, an account without :kontor.account/active
            set is treated as active. The invariant filters on
            `:kontor.account/active false` explicitly."
    (let [conn (core/create-test-db)
          _ (v/install-invariants! conn)
          {:keys [eur opn rev jnl]} (catalog! conn {})
          tx-data (posting/build-transaction
                   {:transaction
                    {:kontor.transaction/external-id    "OPN-2026-0001"
                     :kontor.transaction/journal        jnl
                     :kontor.transaction/effective-date some-date
                     :kontor.transaction/narration      "Opening balance"}
                    :postings
                    [{:kontor.posting/account opn :kontor.posting/amount  500M :kontor.posting/commodity eur}
                     {:kontor.posting/account rev :kontor.posting/amount -500M :kontor.posting/commodity eur}]})]
      (is (some? (v/transact-with-validation conn tx-data))
          "Account 'opn' has no :kontor.account/active attribute; invariant passes."))))

(deftest account-active-rejects-posting-against-inactive-account
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        {:keys [eur rec rev jnl]} (catalog! conn {:active-receivable? false})
        tx-data (posting/build-transaction
                 {:transaction
                  {:kontor.transaction/external-id    "INV-2026-BAD"
                   :kontor.transaction/journal        jnl
                   :kontor.transaction/effective-date some-date
                   :kontor.transaction/narration      "Posting against inactive"}
                  :postings
                  [{:kontor.posting/account rec :kontor.posting/amount  100M :kontor.posting/commodity eur}
                   {:kontor.posting/account rev :kontor.posting/amount -100M :kontor.posting/commodity eur}]})]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Invariant violated"
         (v/transact-with-validation conn tx-data))
        "Receivable account is inactive — invariant fires.")))

;; ============================================================================
;; commodity-match
;; ============================================================================

(defn- catalog-with-typed-accounts!
  "Variant of `catalog!` that types both accounts to EUR and adds a
   USD commodity for the cross-commodity test cases."
  [conn]
  (d/transact
   conn
   [{:db/id -1 :kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
     :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "EUR"}
    {:db/id -2 :kontor.commodity/symbol "USD" :kontor.commodity/name "US Dollar"
     :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "USD"}
    {:db/id -3 :kontor.account/path "Assets:Receivable" :kontor.account/name "AR"
     :kontor.account/type :asset :kontor.account/active true
     :kontor.account/commodity -1}                              ;; EUR-typed
    {:db/id -4 :kontor.account/path "Income:Sales" :kontor.account/name "Sales"
     :kontor.account/type :income :kontor.account/active true
     :kontor.account/commodity -1}                              ;; EUR-typed
    {:db/id -5 :kontor.account/path "Equity:Polymorphic" :kontor.account/name "Suspense"
     :kontor.account/type :equity :kontor.account/active true}         ;; no commodity
    {:db/id -6 :kontor.journal/code "INV" :kontor.journal/name "J"
     :kontor.journal/type :sale :kontor.journal/active true}])
  (let [db (d/db conn)]
    {:eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
     :usd (:db/id (d/entity db [:kontor.commodity/symbol "USD"]))
     :rec (:db/id (d/entity db [:kontor.account/path "Assets:Receivable"]))
     :rev (:db/id (d/entity db [:kontor.account/path "Income:Sales"]))
     :sus (:db/id (d/entity db [:kontor.account/path "Equity:Polymorphic"]))
     :jnl (:db/id (d/entity db [:kontor.journal/code "INV"]))}))

(deftest commodity-match-passes-when-postings-match-account-commodity
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        {:keys [eur rec rev jnl]} (catalog-with-typed-accounts! conn)
        tx-data (posting/build-transaction
                 {:transaction
                  {:kontor.transaction/external-id    "OK-EUR"
                   :kontor.transaction/journal        jnl
                   :kontor.transaction/effective-date some-date
                   :kontor.transaction/narration      "EUR posting against EUR-typed accounts"}
                  :postings
                  [{:kontor.posting/account rec :kontor.posting/amount  100M :kontor.posting/commodity eur}
                   {:kontor.posting/account rev :kontor.posting/amount -100M :kontor.posting/commodity eur}]})]
    (is (some? (v/transact-with-validation conn tx-data)))))

(deftest commodity-match-rejects-mismatch
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        {:keys [usd rec rev jnl]} (catalog-with-typed-accounts! conn)
        tx-data (posting/build-transaction
                 {:transaction
                  {:kontor.transaction/external-id    "BAD-USD"
                   :kontor.transaction/journal        jnl
                   :kontor.transaction/effective-date some-date
                   :kontor.transaction/narration      "USD posting against EUR-typed accounts"}
                  :postings
                  ;; Both postings are USD, both accounts demand EUR
                  [{:kontor.posting/account rec :kontor.posting/amount  100M :kontor.posting/commodity usd}
                   {:kontor.posting/account rev :kontor.posting/amount -100M :kontor.posting/commodity usd}]})]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Invariant violated"
         (v/transact-with-validation conn tx-data))
        "Posting commodity USD vs account commodity EUR — invariant fires.")))

(deftest commodity-match-passes-on-polymorphic-account
  (testing "An account with no :kontor.account/commodity (polymorphic / suspense)
            accepts postings of any commodity."
    (let [conn (core/create-test-db)
          _ (v/install-invariants! conn)
          {:keys [usd sus rec eur jnl]} (catalog-with-typed-accounts! conn)
          ;; Sale: EUR side to receivable (typed), USD side to suspense (poly)
          ;; — the kernel doesn't FX-convert; we'd never write this in real
          ;; books, but the invariant must allow it on the polymorphic side.
          tx-data (posting/build-transaction
                   {:transaction
                    {:kontor.transaction/external-id    "POLY"
                     :kontor.transaction/journal        jnl
                     :kontor.transaction/effective-date some-date
                     :kontor.transaction/narration      "Polymorphic suspense"}
                    :postings
                    ;; All same-currency to keep sum-to-zero satisfied;
                    ;; the polymorphic account accepts USD, the receivable
                    ;; demands EUR — we use EUR for both to stay valid.
                    [{:kontor.posting/account rec :kontor.posting/amount  100M :kontor.posting/commodity eur}
                     {:kontor.posting/account sus :kontor.posting/amount -100M :kontor.posting/commodity eur}]})
          tx-data-usd (posting/build-transaction
                       {:transaction
                        {:kontor.transaction/external-id    "POLY-USD"
                         :kontor.transaction/journal        jnl
                         :kontor.transaction/effective-date some-date
                         :kontor.transaction/narration      "Polymorphic accepts USD"}
                        :postings
                        ;; This would fail on the receivable side (EUR
                        ;; demanded vs USD posted) — so use TWO polymorphic
                        ;; postings. We only have one polymorphic account
                        ;; in the fixture, so use it on both sides via
                        ;; opposite signs (an unusual but legal book).
                        [{:kontor.posting/account sus :kontor.posting/amount  50M :kontor.posting/commodity usd}
                         {:kontor.posting/account sus :kontor.posting/amount -50M :kontor.posting/commodity usd}]})]
      (is (some? (v/transact-with-validation conn tx-data))
          "EUR posting on EUR-typed receivable + EUR-polymorphic suspense passes.")
      (is (some? (v/transact-with-validation conn tx-data-usd))
          "USD postings on the polymorphic account pass — invariant doesn't fire."))))
