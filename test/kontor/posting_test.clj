(ns kontor.posting-test
  "Structural validation of draft transactions:
     - sum-to-zero per commodity
     - missing required fields
     - 2+ balance-affecting postings
     - display-type whitelist
     - default fill-in (state, display-type, valid-from)
     - end-to-end transact against a fresh kernel DB."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.money :as m]
            [kontor.posting :as posting]))

(def some-date #inst "2026-05-09T00:00:00Z")

;; ============================================================================
;; Pure validation
;; ============================================================================

(defn- balanced-sample
  "A simple balanced 2-line transaction in EUR. Caller can override
   any field via merge-into."
  [& {:as overrides}]
  (merge
   {:transaction
    {:transaction/external-id    "INV-2026-0001"
     :transaction/journal        :journal/sales
     :transaction/effective-date some-date
     :transaction/narration      "Test invoice"}
    :postings
    [{:posting/account   :account/receivable
      :posting/amount    100.00M
      :posting/commodity :EUR}
     {:posting/account   :account/revenue
      :posting/amount    -100.00M
      :posting/commodity :EUR}]}
   overrides))

(deftest validate-balanced-passes
  (let [r (posting/validate (balanced-sample))]
    (is (:ok? r))
    (is (empty? (:errors r)))
    (is (empty? (:unbalanced r)))))

(deftest validate-unbalanced-fails
  (let [r (posting/validate
           (assoc-in (balanced-sample)
                     [:postings 1 :posting/amount]
                     -99.00M))]
    (is (not (:ok? r)))
    (is (some #(= :unbalanced (:error %)) (:errors r)))
    (is (= #{:EUR} (set (keys (:unbalanced r)))))
    ;; The 1 EUR shortfall surfaces as the residual
    (let [residual (get (:unbalanced r) :EUR)]
      (is (m/equiv? (m/money "1.00" :EUR) residual)))))

(deftest validate-multi-currency-balanced
  (testing "Each commodity must balance independently. A USD/EUR txn
            balances iff the USD leg sums to 0 AND the EUR leg sums
            to 0 — the kernel never silently FX-converts."
    (let [r (posting/validate
             {:transaction (-> (balanced-sample) :transaction)
              :postings
              [{:posting/account :account/usd-bank :posting/amount 50.00M  :posting/commodity :USD}
               {:posting/account :account/usd-bank :posting/amount -50.00M :posting/commodity :USD}
               {:posting/account :account/eur-bank :posting/amount 100.00M :posting/commodity :EUR}
               {:posting/account :account/eur-bank :posting/amount -100.00M :posting/commodity :EUR}]})]
      (is (:ok? r))
      (is (empty? (:unbalanced r))))))

(deftest validate-multi-currency-eur-leg-unbalanced
  (let [r (posting/validate
           {:transaction (-> (balanced-sample) :transaction)
            :postings
            [{:posting/account :account/usd-bank :posting/amount 50.00M  :posting/commodity :USD}
             {:posting/account :account/usd-bank :posting/amount -50.00M :posting/commodity :USD}
             {:posting/account :account/eur-bank :posting/amount 100.00M :posting/commodity :EUR}
             {:posting/account :account/eur-bank :posting/amount -99.00M :posting/commodity :EUR}]})]
    (is (not (:ok? r)))
    (is (= #{:EUR} (set (keys (:unbalanced r)))))))

(deftest validate-too-few-postings
  (let [r (posting/validate
           {:transaction (-> (balanced-sample) :transaction)
            :postings
            [{:posting/account :account/x :posting/amount 0.00M :posting/commodity :EUR}]})]
    (is (not (:ok? r)))
    (is (some #(= :too-few-postings (:error %)) (:errors r)))))

(deftest validate-section-and-note-lines-not-counted
  (testing "UI-only :section / :note lines do not satisfy the 2+
            balance-affecting posting requirement."
    (let [r (posting/validate
             {:transaction (-> (balanced-sample) :transaction)
              :postings
              [{:posting/display-type :section :posting/narration "Header"}
               {:posting/account :account/x :posting/amount 100.00M :posting/commodity :EUR}
               {:posting/display-type :note :posting/narration "Footer note"}
               {:posting/account :account/y :posting/amount -100.00M :posting/commodity :EUR}]})]
      (is (:ok? r) (str "errors=" (:errors r))))))

(deftest validate-rejects-unknown-display-type
  (let [r (posting/validate
           (assoc-in (balanced-sample)
                     [:postings 0 :posting/display-type]
                     :bogus))]
    (is (not (:ok? r)))
    (is (some #(= :invalid-display-type (:error %)) (:errors r)))))

(deftest validate-rejects-missing-fields-on-product-line
  (let [r (posting/validate
           {:transaction (-> (balanced-sample) :transaction)
            :postings
            [{:posting/amount 100.00M :posting/commodity :EUR}     ;; missing account
             {:posting/account :account/x :posting/commodity :EUR} ;; missing amount
             {:posting/account :account/y :posting/amount 1.00M}]}) ;; missing commodity
        codes (set (map :error (:errors r)))]
    (is (not (:ok? r)))
    (is (codes :missing-account))
    (is (codes :missing-amount))
    (is (codes :missing-commodity))))

(deftest validate-rejects-missing-header
  (let [r (posting/validate
           {:transaction {:transaction/external-id "X"}
            :postings (-> (balanced-sample) :postings)})
        codes (set (map :error (:errors r)))]
    (is (not (:ok? r)))
    (is (codes :missing-journal))
    (is (codes :missing-effective-date))))

;; ============================================================================
;; build-transaction tx-data shape
;; ============================================================================

(deftest build-transaction-shape
  (let [tx-data (posting/build-transaction (balanced-sample))]
    (is (vector? tx-data))
    (is (= 3 (count tx-data)))
    (let [[txn p1 p2] tx-data]
      (is (= -1 (:db/id txn)))
      (is (= :draft (:transaction/state txn))) ;; defaulted
      (is (= -1 (:posting/transaction p1)))
      (is (= -1 (:posting/transaction p2)))
      (is (= :product (:posting/display-type p1))) ;; defaulted
      (is (= some-date (:posting/valid-from p1)))) ;; inherited from txn
    ))

(deftest build-transaction-throws-on-unbalanced
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"failed structural validation"
       (posting/build-transaction
        (assoc-in (balanced-sample)
                  [:postings 1 :posting/amount]
                  -1.00M)))))

(deftest build-preserves-explicit-state
  (let [tx-data (posting/build-transaction
                 (-> (balanced-sample)
                     (assoc-in [:transaction :transaction/state] :posted)))]
    (is (= :posted (:transaction/state (first tx-data))))))

;; ============================================================================
;; End-to-end against a real datahike DB
;; ============================================================================

(defn- seed-catalog!
  "Plant minimal account+commodity+journal entities so the build-
   transaction tx-data resolves its refs cleanly."
  [conn]
  (d/transact conn
              [{:db/id -1 :commodity/symbol "EUR" :commodity/name "Euro"
                :commodity/precision 2 :commodity/iso-4217 "EUR"}
               {:db/id -2 :account/path "Assets:Receivable"
                :account/name "Trade receivables"
                :account/type :asset :account/active true}
               {:db/id -3 :account/path "Income:Sales"
                :account/name "Sales revenue"
                :account/type :income :account/active true}
               {:db/id -4 :journal/code "INV" :journal/name "Customer invoices"
                :journal/type :sale :journal/active true}])
  (d/db conn))

(deftest end-to-end-balanced-tx-transacts
  (let [conn (core/create-test-db)
        _ (seed-catalog! conn)
        eur (:db/id (d/entity (d/db conn) [:commodity/symbol "EUR"]))
        rec (:db/id (d/entity (d/db conn) [:account/path "Assets:Receivable"]))
        rev (:db/id (d/entity (d/db conn) [:account/path "Income:Sales"]))
        jnl (:db/id (d/entity (d/db conn) [:journal/code "INV"]))
        tx-data (posting/build-transaction
                 {:transaction
                  {:transaction/external-id    "INV-2026-0001"
                   :transaction/journal        jnl
                   :transaction/effective-date some-date
                   :transaction/narration      "ACME services"}
                  :postings
                  [{:posting/account rec :posting/amount  100.00M :posting/commodity eur}
                   {:posting/account rev :posting/amount -100.00M :posting/commodity eur}]})
        report (d/transact conn tx-data)
        db-after (:db-after report)]
    (is (some? report))
    (let [tx-eid (:db/id (d/entity db-after [:transaction/external-id "INV-2026-0001"]))]
      (is tx-eid)
      ;; Two postings reference it
      (let [posting-eids (d/q '[:find [?p ...]
                                :in $ ?tx
                                :where [?p :posting/transaction ?tx]]
                              db-after tx-eid)]
        (is (= 2 (count posting-eids)))
        ;; And they sum to zero in EUR
        (let [postings (mapv #(d/pull db-after '[:posting/amount :posting/commodity] %)
                             posting-eids)
              monies (mapv m/posting->money postings)]
          (is (m/zero? (m/sum monies eur))))))))

(deftest end-to-end-build-defaults-valid-from
  (let [conn (core/create-test-db)
        _ (seed-catalog! conn)
        eur (:db/id (d/entity (d/db conn) [:commodity/symbol "EUR"]))
        rec (:db/id (d/entity (d/db conn) [:account/path "Assets:Receivable"]))
        rev (:db/id (d/entity (d/db conn) [:account/path "Income:Sales"]))
        jnl (:db/id (d/entity (d/db conn) [:journal/code "INV"]))
        tx-data (posting/build-transaction
                 {:transaction
                  {:transaction/external-id    "INV-2026-0002"
                   :transaction/journal        jnl
                   :transaction/effective-date some-date
                   :transaction/narration      "Defaults check"}
                  :postings
                  ;; Note: no :posting/valid-from on either posting
                  [{:posting/account rec :posting/amount  50.00M :posting/commodity eur}
                   {:posting/account rev :posting/amount -50.00M :posting/commodity eur}]})
        _ (d/transact conn tx-data)
        db-after (d/db conn)
        tx-eid (:db/id (d/entity db-after [:transaction/external-id "INV-2026-0002"]))
        ;; Pull per-posting so we count BOTH rows even when they share
        ;; the same valid-from value. (`[:find [?vf ...]` would dedupe.)
        posting-rows (d/q '[:find ?p ?vf
                            :in $ ?tx
                            :where
                            [?p :posting/transaction ?tx]
                            [?p :posting/valid-from ?vf]]
                          db-after tx-eid)]
    (is (= 2 (count posting-rows))
        "Both postings must have a valid-from datom (ADR-008 default).")
    (is (every? #(= some-date (second %)) posting-rows)
        "valid-from must default to the transaction's effective-date.")))
