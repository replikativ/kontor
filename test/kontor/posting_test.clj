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
            [kontor.bitemporal]
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
    ;; Postings carry no :posting/ledger, so they group under nil.
    ;; (build-transaction would default to [:ledger/code "primary"].)
    (is (= #{nil} (set (keys (:unbalanced r)))))
    (is (= #{:EUR} (set (keys (get (:unbalanced r) nil)))))
    ;; The 1 EUR shortfall surfaces as the residual in the nil ledger group.
    (let [residual (get-in (:unbalanced r) [nil :EUR])]
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
    ;; Only EUR is unbalanced in the nil-ledger group; USD nets to zero.
    (is (= #{nil} (set (keys (:unbalanced r)))))
    (is (= #{:EUR} (set (keys (get (:unbalanced r) nil)))))))

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
;; Parallel-ledger sum-to-zero (ADR-021)
;; ============================================================================

(deftest validate-balances-per-ledger
  (testing "Each ledger is its own self-balancing book. A transaction
            posting 100/-100 to IFRS and 100/-100 to HGB is valid; a
            transaction posting 100/-100 to IFRS and 100/-99 to HGB
            fails because HGB is unbalanced even though the aggregate
            sums to zero."
    (let [ifrs-ref [:ledger/code "ifrs"]
          hgb-ref  [:ledger/code "hgb"]
          ok (posting/validate
              {:transaction (-> (balanced-sample) :transaction)
               :postings
               [{:posting/account :a :posting/amount  100.00M :posting/commodity :EUR :posting/ledger ifrs-ref}
                {:posting/account :b :posting/amount -100.00M :posting/commodity :EUR :posting/ledger ifrs-ref}
                {:posting/account :a :posting/amount  100.00M :posting/commodity :EUR :posting/ledger hgb-ref}
                {:posting/account :b :posting/amount -100.00M :posting/commodity :EUR :posting/ledger hgb-ref}]})
          bad (posting/validate
               {:transaction (-> (balanced-sample) :transaction)
                :postings
                [{:posting/account :a :posting/amount  100.00M :posting/commodity :EUR :posting/ledger ifrs-ref}
                 {:posting/account :b :posting/amount -100.00M :posting/commodity :EUR :posting/ledger ifrs-ref}
                 {:posting/account :a :posting/amount  100.00M :posting/commodity :EUR :posting/ledger hgb-ref}
                 {:posting/account :b :posting/amount  -99.00M :posting/commodity :EUR :posting/ledger hgb-ref}]})]
      (is (:ok? ok))
      (is (empty? (:unbalanced ok)))
      (is (not (:ok? bad)))
      (is (= #{hgb-ref} (set (keys (:unbalanced bad))))
          "Only the HGB ledger is unbalanced; IFRS sums to zero cleanly")
      (is (m/equiv? (m/money "1.00" :EUR)
                    (get-in (:unbalanced bad) [hgb-ref :EUR]))))))

(deftest validate-cross-ledger-cancellation-does-not-balance
  (testing "Per ADR-021, a 5 EUR debit on IFRS does NOT net against
            a 5 EUR credit on HGB — each ledger must self-balance."
    (let [ifrs-ref [:ledger/code "ifrs"]
          hgb-ref  [:ledger/code "hgb"]
          r (posting/validate
             {:transaction (-> (balanced-sample) :transaction)
              :postings
              [{:posting/account :a :posting/amount    5.00M :posting/commodity :EUR :posting/ledger ifrs-ref}
               {:posting/account :b :posting/amount   -5.00M :posting/commodity :EUR :posting/ledger hgb-ref}]})]
      (is (not (:ok? r)))
      (is (= #{ifrs-ref hgb-ref} (set (keys (:unbalanced r))))
          "Both ledgers must be flagged — neither one self-balances."))))

(deftest build-transaction-leaves-ledger-absent-by-default
  (testing "Per ADR-021 revised, :posting/ledger stays absent unless
            the caller sets it. Absent means 'primary book' at read
            time; we don't inject a lookup-ref because invariant
            speculative-applies run against an empty schema-only DB
            that cannot resolve unique-identity lookups for
            non-schema entities."
    (let [tx-data (posting/build-transaction (balanced-sample))
          posting-entities (filter :posting/account tx-data)]
      (is (every? #(nil? (:posting/ledger %)) posting-entities)
          "No :posting/ledger should be auto-injected"))))

(deftest build-transaction-preserves-explicit-ledger
  (testing "An explicitly-set :posting/ledger is not overwritten."
    (let [tx-data (posting/build-transaction
                   {:transaction (-> (balanced-sample) :transaction)
                    :postings
                    [{:posting/account :a :posting/amount  10.00M
                      :posting/commodity :EUR :posting/ledger [:ledger/code "ifrs"]}
                     {:posting/account :b :posting/amount -10.00M
                      :posting/commodity :EUR :posting/ledger [:ledger/code "ifrs"]}]})
          posting-entities (filter :posting/account tx-data)]
      (is (every? #(= [:ledger/code "ifrs"] (:posting/ledger %))
                  posting-entities)))))

;; ============================================================================
;; expand-distribution (ADR-022 split-line strategy)
;; ============================================================================

(def ^:private cc-plan [:analytic-plan/code "COST-CENTER"])
(def ^:private proj-plan [:analytic-plan/code "PROJECT"])

(defn- cc [name pct]
  {:analytic-distribution/plan    cc-plan
   :analytic-distribution/account [:analytic-account/path (str "COST-CENTER:" name)]
   :analytic-distribution/percent pct})

(defn- proj [name pct]
  {:analytic-distribution/plan    proj-plan
   :analytic-distribution/account [:analytic-account/path (str "PROJECT:" name)]
   :analytic-distribution/percent pct})

(def ^:private one-posting-with-cc-60-40
  {:posting/account   :account/cogs
   :posting/amount    100.00M
   :posting/commodity :EUR
   :posting/analytic-distributions [(cc "Eng" 60M) (cc "Sales" 40M)]})

(deftest expand-splits-amount-per-percent
  (let [children (posting/expand-distribution one-posting-with-cc-60-40 cc-plan)]
    (is (= 2 (count children)))
    (is (= 60.00M (:posting/amount (nth children 0))))
    (is (= 40.00M (:posting/amount (nth children 1))))))

(deftest expand-preserves-inherited-fields
  (let [parent (assoc one-posting-with-cc-60-40
                      :posting/partner    :p/acme
                      :posting/narration  "ACME services"
                      :posting/ledger     [:ledger/code "ifrs"])
        children (posting/expand-distribution parent cc-plan)]
    (is (every? #(= :p/acme (:posting/partner %)) children))
    (is (every? #(= "ACME services" (:posting/narration %)) children))
    (is (every? #(= [:ledger/code "ifrs"] (:posting/ledger %)) children))
    (is (every? #(= :account/cogs (:posting/account %)) children))))

(deftest expand-each-child-carries-single-distribution-at-100
  (let [children (posting/expand-distribution one-posting-with-cc-60-40 cc-plan)]
    (doseq [c children]
      (let [dists (:posting/analytic-distributions c)]
        (is (= 1 (count dists)))
        (is (= 100M (:analytic-distribution/percent (first dists))))
        (is (= cc-plan (:analytic-distribution/plan (first dists))))))))

(deftest expand-rides-other-plans-unchanged
  (testing "Distributions in plans other than the expansion target
            ride along on each child unchanged (per-plan default)."
    (let [parent (assoc one-posting-with-cc-60-40
                        :posting/analytic-distributions
                        [(cc "Eng" 60M) (cc "Sales" 40M)
                         (proj "Alpha" 70M) (proj "Beta" 30M)])
          children (posting/expand-distribution parent cc-plan)]
      (is (= 2 (count children)) "Only the cost-center plan splits")
      (doseq [c children]
        (let [proj-dists (filterv #(= proj-plan (:analytic-distribution/plan %))
                                  (:posting/analytic-distributions c))]
          (is (= 2 (count proj-dists)))
          (is (= #{70M 30M}
                 (set (map :analytic-distribution/percent proj-dists)))))))))

(deftest expand-largest-remainder-on-thirds
  (testing "100.00 EUR split 33.333333 / 33.333333 / 33.333334 → sum
            bit-exact to 100.00"
    (let [parent (assoc one-posting-with-cc-60-40
                        :posting/analytic-distributions
                        [(cc "A" 33.333333M) (cc "B" 33.333333M) (cc "C" 33.333334M)])
          children (posting/expand-distribution parent cc-plan)
          sum-bd (reduce #(.add ^java.math.BigDecimal %1 ^java.math.BigDecimal %2)
                         java.math.BigDecimal/ZERO
                         (map :posting/amount children))]
      (is (= 3 (count children)))
      (is (= 0 (.compareTo (bigdec "100.00") sum-bd))
          "Sum of children must be bit-exact to parent"))))

(deftest expand-drops-zero-percent
  (let [parent (assoc one-posting-with-cc-60-40
                      :posting/analytic-distributions
                      [(cc "A" 60M) (cc "B" 0M) (cc "C" 40M)])
        children (posting/expand-distribution parent cc-plan)]
    (is (= 2 (count children))
        "Zero-percent slot must not produce a zero-amount child")
    (is (= #{60.00M 40.00M} (set (map :posting/amount children))))))

(deftest expand-no-matching-plan-returns-input
  (testing "Posting without distributions in the named plan is
            returned unchanged as a single-element vector"
    (let [parent (assoc one-posting-with-cc-60-40
                        :posting/analytic-distributions
                        [(proj "Alpha" 100M)])
          children (posting/expand-distribution parent cc-plan)]
      (is (= [parent] children)))))

(deftest expand-negative-amount-symmetric
  (testing "Negative parent amount splits symmetrically with bit-exact total"
    (let [parent (assoc one-posting-with-cc-60-40
                        :posting/amount -100.00M
                        :posting/analytic-distributions
                        [(cc "A" 33.333333M) (cc "B" 33.333333M) (cc "C" 33.333334M)])
          children (posting/expand-distribution parent cc-plan)
          sum-bd (reduce #(.add ^java.math.BigDecimal %1 ^java.math.BigDecimal %2)
                         java.math.BigDecimal/ZERO
                         (map :posting/amount children))]
      (is (= 0 (.compareTo (bigdec "-100.00") sum-bd))))))

(deftest expand-cartesian-not-yet-implemented
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"cartesian"
       (posting/expand-distribution one-posting-with-cc-60-40 cc-plan
                                    {:strategy :cartesian}))))

;; ============================================================================
;; build-transaction tx-data shape
;; ============================================================================

(deftest build-transaction-shape
  (let [tx-data (posting/build-transaction (balanced-sample))]
    (is (vector? tx-data))
    ;; tx-data is [txn p1 p2 tx-meta] after kbt/with-vt appends the
    ;; "datomic.tx" map carrying :db.valid/from.
    (is (= 4 (count tx-data)))
    (let [[txn p1 p2 tx-meta] tx-data]
      (is (= -1 (:db/id txn)))
      (is (= :draft (:transaction/state txn))) ;; defaulted
      (is (= -1 (:posting/transaction p1)))
      (is (= -1 (:posting/transaction p2)))
      (is (= :product (:posting/display-type p1))) ;; defaulted
      (is (= "datomic.tx" (:db/id tx-meta)))
      (is (= some-date (:db.valid/from tx-meta)))))) ;; from :transaction/effective-date

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
                  [{:posting/account rec :posting/amount  50.00M :posting/commodity eur}
                   {:posting/account rev :posting/amount -50.00M :posting/commodity eur}]})
        _ (d/transact conn tx-data)
        db-after (d/db conn)
        tx-eid (:db/id (d/entity db-after [:transaction/external-id "INV-2026-0002"]))
        ;; ADR-048: valid-from lives on the writing tx, not per-posting.
        ;; Both postings derive their vf from the same :db.valid/from
        ;; (upstream datahike).
        posting-rows (d/q '[:find ?p ?vf
                            :in $ ?tx
                            :where
                            [?p :posting/transaction ?tx]
                            [?p :posting/transaction _ ?ptx]
                            [?ptx :db/txInstant ?ti]
                            [(get-else $ ?ptx :db.valid/from ?ti) ?vf]]
                          db-after tx-eid)]
    (is (= 2 (count posting-rows))
        "Both postings resolve a valid-from via :db.valid/from.")
    (is (every? #(= some-date (second %)) posting-rows)
        "valid-from must default to the transaction's effective-date.")))

;; ============================================================================
;; post-transaction! — build + seal in one move (P1-5 review fix)
;; ============================================================================

(deftest post-transaction-seals-parent-and-propagates-to-postings
  (let [conn (core/create-test-db)
        _ (seed-catalog! conn)
        eur (:db/id (d/entity (d/db conn) [:commodity/symbol "EUR"]))
        rec (:db/id (d/entity (d/db conn) [:account/path "Assets:Receivable"]))
        rev (:db/id (d/entity (d/db conn) [:account/path "Income:Sales"]))
        jnl (:db/id (d/entity (d/db conn) [:journal/code "INV"]))
        posted-at #inst "2026-05-13T10:00:00Z"
        _ (posting/post-transaction!
           conn
           {:transaction {:transaction/external-id     "INV-POST-1"
                          :transaction/journal         jnl
                          :transaction/effective-date  some-date
                          :transaction/narration       "post-transaction! roundtrip"}
            :postings    [{:posting/account rec :posting/amount  50.00M :posting/commodity eur}
                          {:posting/account rev :posting/amount -50.00M :posting/commodity eur}]}
           {:posted-at posted-at})
        db (d/db conn)
        tx-eid (:db/id (d/entity db [:transaction/external-id "INV-POST-1"]))
        tx (d/pull db [:transaction/state :transaction/posted-at] tx-eid)
        post-pa (d/q '[:find ?p ?pa
                       :in $ ?tx
                       :where
                       [?p :posting/transaction ?tx]
                       [?p :posting/posted-at ?pa]]
                     db tx-eid)]
    (is (= :posted (:transaction/state tx)))
    (is (= posted-at (:transaction/posted-at tx)))
    (is (= 2 (count post-pa))
        "Both postings carry :posting/posted-at after post-transaction!")
    (is (every? #(= posted-at (second %)) post-pa)
        "Children inherit parent's :posted-at (sealing invariant).")))
