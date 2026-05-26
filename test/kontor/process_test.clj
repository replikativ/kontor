(ns kontor.process-test
  "Verify the `kontor.process` facility — ADR-067:
     - run-steps threads steps against the speculative db
     - {:steps ...} returns splice in (the monadic flatten)
     - cross-step string tempids resolve consistently in the commit
     - run-process owns valid-time (strips step tx-meta, one with-vt)
     - :ctx threads through steps
     - :dry-run? assembles without committing
     - the commit routes through the validation gate atomically
     - empty processes and bad step returns behave"
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.posting :as posting]
            [kontor.process :as p]
            [kontor.validation :as v]))

(def some-date #inst "2026-05-09T00:00:00Z")

(defn- catalog!
  "Seed minimal accounts/journal/commodity. Returns eids by key."
  [conn]
  (d/transact conn
              [{:db/id -1 :kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "EUR"}
               {:db/id -2 :kontor.account/path "Assets:Cash" :kontor.account/name "Cash"
                :kontor.account/type :asset :kontor.account/active true}
               {:db/id -3 :kontor.account/path "Income:Sales" :kontor.account/name "Sales"
                :kontor.account/type :income :kontor.account/active true}
               {:db/id -4 :kontor.journal/code "GEN" :kontor.journal/name "General"
                :kontor.journal/type :general :kontor.journal/active true}])
  (let [db (d/db conn)]
    {:eur  (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
     :cash (:db/id (d/entity db [:kontor.account/path "Assets:Cash"]))
     :rev  (:db/id (d/entity db [:kontor.account/path "Income:Sales"]))
     :jnl  (:db/id (d/entity db [:kontor.journal/code "GEN"]))}))

(defn- balanced-txn-step
  "A step that builds one balanced cash↔revenue transaction."
  [{:keys [eur cash rev jnl]} amount]
  (fn [_db _ctx]
    (posting/build-transaction
     {:transaction {:kontor.transaction/journal        jnl
                    :kontor.transaction/effective-date some-date
                    :kontor.transaction/narration      "process step"}
      :postings    [{:kontor.posting/account cash :kontor.posting/amount amount       :kontor.posting/commodity eur}
                    {:kontor.posting/account rev  :kontor.posting/amount (- amount)    :kontor.posting/commodity eur}]})))

;; ============================================================================
;; run-steps — the pure engine
;; ============================================================================

(deftest run-steps-threads-speculative-db
  (testing "each step sees a db reflecting every prior step's fragment"
    (let [conn (core/create-test-db)
          mk   (fn [_db _ctx] [{:db/id "a" :kontor.account/path "X" :kontor.account/name "X"
                                :kontor.account/type :asset}])
          see  (fn [db ctx]
                 {:ctx (assoc ctx :seen
                              (d/q '[:find ?n . :where
                                     [?e :kontor.account/path "X"] [?e :kontor.account/name ?n]]
                                   db))})
          {:keys [tx-data ctx]} (p/run-steps (d/db conn) {} [mk see])]
      (is (= 1 (count tx-data)) "the fragment accumulated")
      (is (= "X" (:seen ctx)) "step 2 read step 1's write off the speculative db"))))

(deftest run-steps-flattens-substep-returns
  (testing "{:steps ...} splices in front of the remaining queue (monadic flatten)"
    (let [conn  (core/create-test-db)
          order (atom [])
          tick  (fn [k] (fn [_ _] (swap! order conj k) nil))
          sub   (fn [_ _] (swap! order conj :parent)
                  {:steps [(tick :child-1) (tick :child-2)]})]
      (p/run-steps (d/db conn) {} [sub (tick :tail)])
      (is (= [:parent :child-1 :child-2 :tail] @order)
          "sub-steps run before the rest of the queue, each in order"))))

(deftest run-steps-rejects-bad-step-return
  (let [conn (core/create-test-db)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"process step must return"
         (p/run-steps (d/db conn) {} [(fn [_ _] 42)])))))

;; ============================================================================
;; run-process — assembly + commit
;; ============================================================================

(deftest dry-run-assembles-without-committing
  (let [conn (core/create-test-db)
        cat  (catalog! conn)
        _    (v/install-invariants! conn)
        eid-count (fn [] (d/q '[:find (count ?e) . :where [?e :kontor.transaction/narration _]]
                              (d/db conn)))
        before    (eid-count)
        {:keys [db tx-data]} (p/run-process
                              conn {:dry-run? true
                                    :steps    [(balanced-txn-step cat 100M)]})]
    (is (seq tx-data) "tx-data assembled")
    (is (some? db) "speculative db returned")
    (is (= before (eid-count)) "nothing committed under :dry-run?")))

(deftest run-process-commits-through-the-gate
  (testing "a balanced process commits via transact-with-validation"
    (let [conn (core/create-test-db)
          cat  (catalog! conn)
          _    (v/install-invariants! conn)
          rep  (p/run-process conn {:steps [(balanced-txn-step cat 100M)]})]
      (is (some? (:db-after rep)) "returns a tx-report")
      (is (= 1 (d/q '[:find (count ?e) . :where [?e :kontor.transaction/narration "process step"]]
                    (d/db conn)))
          "the transaction is in the db"))))

(deftest run-process-aborts-atomically-on-gate-violation
  (testing "an unbalanced fragment aborts the WHOLE process — no earlier
            step's fragment lands"
    (let [conn (core/create-test-db)
          cat  (catalog! conn)
          _    (v/install-invariants! conn)
          ;; step 1: a valid stand-alone account.  step 2: an unbalanced txn.
          mk-account (fn [_ _] [{:db/id "orphan" :kontor.account/path "Assets:Orphan"
                                 :kontor.account/name "Orphan" :kontor.account/type :asset}])
          unbalanced (fn [_ _]
                       (posting/build-transaction
                        {:transaction {:kontor.transaction/journal        (:jnl cat)
                                       :kontor.transaction/effective-date some-date
                                       :kontor.transaction/narration      "bad"}
                         :postings    [{:kontor.posting/account (:cash cat) :kontor.posting/amount 100M
                                        :kontor.posting/commodity (:eur cat)}
                                       {:kontor.posting/account (:rev cat) :kontor.posting/amount -60M
                                        :kontor.posting/commodity (:eur cat)}]}))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (p/run-process conn {:steps [mk-account unbalanced]})))
      (is (nil? (d/entity (d/db conn) [:kontor.account/path "Assets:Orphan"]))
          "step 1's account was NOT committed — the process is atomic"))))

(deftest run-process-owns-valid-time
  (testing "step-emitted tx-meta is stripped; one outer with-vt wins"
    (let [conn (core/create-test-db)
          _    (v/install-invariants! conn)
          step (fn [_ _] [{:db/id "a" :kontor.account/path "VT" :kontor.account/name "VT"
                           :kontor.account/type :asset}
                          ;; a rogue step trying to set its own valid-time
                          {:db/id "datomic.tx" :db.valid/from #inst "1999-01-01"}])
          _    (p/run-process conn {:steps [step] :vt-from #inst "2020-06-01"})
          vf   (d/q '[:find ?vf . :where
                      [?e :kontor.account/path "VT"] [?e _ _ ?tx] [?tx :db.valid/from ?vf]]
                    (d/db conn))]
      (is (= #inst "2020-06-01" vf)
          "the run-process :vt-from won; the step's tx-meta was stripped"))))

(deftest run-process-threads-cross-step-tempids
  (testing "a string tempid created by one step resolves consistently when a
            later step references it — one entity in the commit"
    (let [conn   (core/create-test-db)
          _      (v/install-invariants! conn)
          parent (fn [_ _] [{:db/id "p" :kontor.account/path "Assets" :kontor.account/name "Assets"
                             :kontor.account/type :asset}])
          child  (fn [_ _] [{:db/id "c" :kontor.account/path "Assets:Sub" :kontor.account/name "Sub"
                             :kontor.account/type :asset :kontor.account/parent "p"}])
          _      (p/run-process conn {:steps [parent child]})
          linked (d/q '[:find ?pp . :where
                        [?c :kontor.account/path "Assets:Sub"] [?c :kontor.account/parent ?p]
                        [?p :kontor.account/path ?pp]]
                      (d/db conn))]
      (is (= "Assets" linked) "child's parent resolved to the entity step 1 created"))))

(deftest empty-process-is-a-no-op
  (let [conn (core/create-test-db)]
    (is (nil? (p/run-process conn {:steps []}))
        "an empty process commits nothing and returns nil")
    (is (nil? (p/run-process conn {:steps [(fn [_ _] nil)]}))
        "a process of pure no-op steps commits nothing")))

(deftest speculative-db-eid-round-trips-to-final-commit
  ;; Regression guard for note 47 §"Composition" / note 48 P1-1:
  ;; the inventory issue! merger relies on datahike's tempid resolver
  ;; assigning the SAME numeric eid to a string tempid in BOTH the
  ;; speculative `d/db-with db0 frag1` and the final
  ;; `d/transact conn (frag1++frag2)`. If a future datahike upgrade
  ;; changed the allocator (e.g. parallel db-with, reservation-pool
  ;; reordering, randomized eids), the inventory subsystem would
  ;; silently mis-link `:layer-consumption/layer` against a stale
  ;; speculative eid. This test fails loudly if that invariant breaks.
  (let [conn (core/create-test-db)
        db0 (d/db conn)
        frag1 [{:db/id "round-trip-x" :kontor.account/path "RT-X" :kontor.account/name "RT"
                :kontor.account/type :asset}]
        spec-db (d/db-with db0 frag1)
        spec-eid (d/q '[:find ?e . :where [?e :kontor.account/path "RT-X"]] spec-db)
        ;; Append a second fragment that references the speculative eid
        ;; as a literal long — the inventory pattern. If the round-trip
        ;; holds, the final commit's tempid "round-trip-x" resolves to
        ;; the SAME spec-eid and the cross-fragment ref is consistent.
        frag2 [{:db/id "round-trip-y" :kontor.account/path "RT-Y" :kontor.account/name "RT"
                :kontor.account/type :asset :kontor.account/parent spec-eid}]
        report (d/transact conn (into (vec frag1) frag2))
        final-eid (get (:tempids report) "round-trip-x")
        parent-of-y (d/q '[:find ?p . :where
                           [?y :kontor.account/path "RT-Y"] [?y :kontor.account/parent ?p]]
                         (:db-after report))]
    (is (= spec-eid final-eid)
        "string tempid in frag1 must round-trip to the SAME numeric eid
         from `d/db-with` to `d/transact` of (frag1 ++ frag2) — note 47.")
    (is (= spec-eid parent-of-y)
        "the cross-fragment reference (frag2 → frag1 by literal eid)
         resolves to the same entity the tempid commits as.")))

(deftest commit-fn-is-overridable
  (testing ":commit lets a caller bypass the gate (e.g. for tests)"
    (let [conn (core/create-test-db)
          seen (atom nil)
          step (fn [_ _] [{:db/id "a" :kontor.account/path "Q" :kontor.account/name "Q"
                           :kontor.account/type :asset}])]
      (p/run-process conn {:steps  [step]
                           :commit (fn [_conn tx-data] (reset! seen tx-data) :ok)})
      (is (seq @seen) "the override saw the assembled tx-data")
      (is (nil? (d/entity (d/db conn) [:kontor.account/path "Q"]))
          "the override did not actually transact"))))
