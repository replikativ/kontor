(ns kontor.validation-test
  "Verify the invariant integration:
     - Schema and built-in invariants install cleanly.
     - account-active invariant fires on a posting against an inactive
       account.
     - account-active passes on postings against active accounts.
     - account-active passes when the account omits :account/active
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
              [{:db/id -1 :commodity/symbol "EUR" :commodity/name "Euro"
                :commodity/precision 2 :commodity/iso-4217 "EUR"}
               (cond-> {:db/id -2 :account/path "Assets:Receivable"
                        :account/name "Trade receivables"
                        :account/type :asset}
                 :always (assoc :account/active active-receivable?))
               {:db/id -3 :account/path "Income:Sales"
                :account/name "Sales revenue"
                :account/type :income :account/active true}
               ;; An account with no :account/active set at all (testing
               ;; that "absent" reads as "active" per Odoo convention).
               {:db/id -4 :account/path "Equity:Opening"
                :account/name "Opening balance"
                :account/type :equity}
               {:db/id -5 :journal/code "INV" :journal/name "Customer invoices"
                :journal/type :sale :journal/active true}])
  (let [db (d/db conn)]
    {:eur (:db/id (d/entity db [:commodity/symbol "EUR"]))
     :rec (:db/id (d/entity db [:account/path "Assets:Receivable"]))
     :rev (:db/id (d/entity db [:account/path "Income:Sales"]))
     :opn (:db/id (d/entity db [:account/path "Equity:Opening"]))
     :jnl (:db/id (d/entity db [:journal/code "INV"]))}))

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
                  {:transaction/external-id    "INV-2026-0001"
                   :transaction/journal        jnl
                   :transaction/effective-date some-date
                   :transaction/narration      "Active accounts only"}
                  :postings
                  [{:posting/account rec :posting/amount  100M :posting/commodity eur}
                   {:posting/account rev :posting/amount -100M :posting/commodity eur}]})]
    (is (some? (v/transact-with-validation conn tx-data))
        "Both accounts active → invariant holds, transact returns a report.")))

(deftest account-active-passes-when-active-attribute-absent
  (testing "Per ADR-011 / Odoo convention, an account without :account/active
            set is treated as active. The invariant filters on
            `:account/active false` explicitly."
    (let [conn (core/create-test-db)
          _ (v/install-invariants! conn)
          {:keys [eur opn rev jnl]} (catalog! conn {})
          tx-data (posting/build-transaction
                   {:transaction
                    {:transaction/external-id    "OPN-2026-0001"
                     :transaction/journal        jnl
                     :transaction/effective-date some-date
                     :transaction/narration      "Opening balance"}
                    :postings
                    [{:posting/account opn :posting/amount  500M :posting/commodity eur}
                     {:posting/account rev :posting/amount -500M :posting/commodity eur}]})]
      (is (some? (v/transact-with-validation conn tx-data))
          "Account 'opn' has no :account/active attribute; invariant passes."))))

(deftest account-active-rejects-posting-against-inactive-account
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        {:keys [eur rec rev jnl]} (catalog! conn {:active-receivable? false})
        tx-data (posting/build-transaction
                 {:transaction
                  {:transaction/external-id    "INV-2026-BAD"
                   :transaction/journal        jnl
                   :transaction/effective-date some-date
                   :transaction/narration      "Posting against inactive"}
                  :postings
                  [{:posting/account rec :posting/amount  100M :posting/commodity eur}
                   {:posting/account rev :posting/amount -100M :posting/commodity eur}]})]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Invariant mismatch"
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
   [{:db/id -1 :commodity/symbol "EUR" :commodity/name "Euro"
     :commodity/precision 2 :commodity/iso-4217 "EUR"}
    {:db/id -2 :commodity/symbol "USD" :commodity/name "US Dollar"
     :commodity/precision 2 :commodity/iso-4217 "USD"}
    {:db/id -3 :account/path "Assets:Receivable" :account/name "AR"
     :account/type :asset :account/active true
     :account/commodity -1}                              ;; EUR-typed
    {:db/id -4 :account/path "Income:Sales" :account/name "Sales"
     :account/type :income :account/active true
     :account/commodity -1}                              ;; EUR-typed
    {:db/id -5 :account/path "Equity:Polymorphic" :account/name "Suspense"
     :account/type :equity :account/active true}         ;; no commodity
    {:db/id -6 :journal/code "INV" :journal/name "J"
     :journal/type :sale :journal/active true}])
  (let [db (d/db conn)]
    {:eur (:db/id (d/entity db [:commodity/symbol "EUR"]))
     :usd (:db/id (d/entity db [:commodity/symbol "USD"]))
     :rec (:db/id (d/entity db [:account/path "Assets:Receivable"]))
     :rev (:db/id (d/entity db [:account/path "Income:Sales"]))
     :sus (:db/id (d/entity db [:account/path "Equity:Polymorphic"]))
     :jnl (:db/id (d/entity db [:journal/code "INV"]))}))

(deftest commodity-match-passes-when-postings-match-account-commodity
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        {:keys [eur rec rev jnl]} (catalog-with-typed-accounts! conn)
        tx-data (posting/build-transaction
                 {:transaction
                  {:transaction/external-id    "OK-EUR"
                   :transaction/journal        jnl
                   :transaction/effective-date some-date
                   :transaction/narration      "EUR posting against EUR-typed accounts"}
                  :postings
                  [{:posting/account rec :posting/amount  100M :posting/commodity eur}
                   {:posting/account rev :posting/amount -100M :posting/commodity eur}]})]
    (is (some? (v/transact-with-validation conn tx-data)))))

(deftest commodity-match-rejects-mismatch
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        {:keys [usd rec rev jnl]} (catalog-with-typed-accounts! conn)
        tx-data (posting/build-transaction
                 {:transaction
                  {:transaction/external-id    "BAD-USD"
                   :transaction/journal        jnl
                   :transaction/effective-date some-date
                   :transaction/narration      "USD posting against EUR-typed accounts"}
                  :postings
                  ;; Both postings are USD, both accounts demand EUR
                  [{:posting/account rec :posting/amount  100M :posting/commodity usd}
                   {:posting/account rev :posting/amount -100M :posting/commodity usd}]})]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Invariant mismatch"
         (v/transact-with-validation conn tx-data))
        "Posting commodity USD vs account commodity EUR — invariant fires.")))

(deftest commodity-match-passes-on-polymorphic-account
  (testing "An account with no :account/commodity (polymorphic / suspense)
            accepts postings of any commodity."
    (let [conn (core/create-test-db)
          _ (v/install-invariants! conn)
          {:keys [usd sus rec eur jnl]} (catalog-with-typed-accounts! conn)
          ;; Sale: EUR side to receivable (typed), USD side to suspense (poly)
          ;; — the kernel doesn't FX-convert; we'd never write this in real
          ;; books, but the invariant must allow it on the polymorphic side.
          tx-data (posting/build-transaction
                   {:transaction
                    {:transaction/external-id    "POLY"
                     :transaction/journal        jnl
                     :transaction/effective-date some-date
                     :transaction/narration      "Polymorphic suspense"}
                    :postings
                    ;; All same-currency to keep sum-to-zero satisfied;
                    ;; the polymorphic account accepts USD, the receivable
                    ;; demands EUR — we use EUR for both to stay valid.
                    [{:posting/account rec :posting/amount  100M :posting/commodity eur}
                     {:posting/account sus :posting/amount -100M :posting/commodity eur}]})
          tx-data-usd (posting/build-transaction
                       {:transaction
                        {:transaction/external-id    "POLY-USD"
                         :transaction/journal        jnl
                         :transaction/effective-date some-date
                         :transaction/narration      "Polymorphic accepts USD"}
                        :postings
                        ;; This would fail on the receivable side (EUR
                        ;; demanded vs USD posted) — so use TWO polymorphic
                        ;; postings. We only have one polymorphic account
                        ;; in the fixture, so use it on both sides via
                        ;; opposite signs (an unusual but legal book).
                        [{:posting/account sus :posting/amount  50M :posting/commodity usd}
                         {:posting/account sus :posting/amount -50M :posting/commodity usd}]})]
      (is (some? (v/transact-with-validation conn tx-data))
          "EUR posting on EUR-typed receivable + EUR-polymorphic suspense passes.")
      (is (some? (v/transact-with-validation conn tx-data-usd))
          "USD postings on the polymorphic account pass — invariant doesn't fire."))))
