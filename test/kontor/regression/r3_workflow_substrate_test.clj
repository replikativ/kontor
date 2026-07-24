(ns kontor.regression.r3-workflow-substrate-test
  "Round-3 adversarial regression sweep of the WORKFLOW substrate:

     kontor.workflow.process           (run-process, multi-step atomic commit)
     kontor.workflow.side-effect        (:side-effect-intent state machine)
     kontor.workflow.side-effect.cross  (cross-DB saga drain! + step-id idempotency)
     kontor.workflow.event-bus          (register-handler! / commit-and-emit)
     kontor.workflow.schedule           (recurring occurrence log)

   Green tests confirm behaviour that is genuinely correct. Four tests are
   pinned `^:kaocha/pending` — each asserts the DESIRED behaviour and is
   expected to FAIL against the current substrate, documenting a real gap:

     A. drain! orphans a crashed :processing intent — no stuck-job recovery.
     B. schedule re-firing double-posts the underlying journal — only the
        occurrence-log row is idempotent, not the ledger effect.
     C. no cross-DB saga compensation — a partial multi-intent commit leaves
        the target DBs inconsistent with no rollback.
     D. commit-and-emit silently swallows a handler failure — no dead-letter,
        no way for the caller to learn the projection failed (at-most-once).

   Odoo references cite /home/christian-weilbach/Development/odoo/addons."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.posting :as posting]
            [kontor.reporting.balance :as balance]
            [kontor.validation :as v]
            [kontor.workflow.event-bus :as bus]
            [kontor.workflow.process :as p]
            [kontor.workflow.schedule :as schedule]
            [kontor.workflow.side-effect :as se]
            [kontor.workflow.side-effect.cross :as cross]))

(def ^:private some-date #inst "2026-05-09T00:00:00Z")

;; ============================================================================
;; Fixtures
;; ============================================================================

(defn- journal-cat!
  "Seed a commodity, two accounts, one general journal. Returns eids by key."
  [conn]
  (d/transact conn
              [{:db/id -1 :kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "EUR"}
               {:db/id -2 :kontor.account/path "Expense:Depreciation"
                :kontor.account/name "Depreciation" :kontor.account/type :expense
                :kontor.account/active true}
               {:db/id -3 :kontor.account/path "Asset:AccumDep"
                :kontor.account/name "Accumulated depreciation" :kontor.account/type :asset
                :kontor.account/active true}
               {:db/id -4 :kontor.journal/code "GEN" :kontor.journal/name "General"
                :kontor.journal/type :general :kontor.journal/active true}])
  (let [db (d/db conn)]
    {:eur     (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
     :expense (:db/id (d/entity db [:kontor.account/path "Expense:Depreciation"]))
     :accum   (:db/id (d/entity db [:kontor.account/path "Asset:AccumDep"]))
     :jnl     (:db/id (d/entity db [:kontor.journal/code "GEN"]))}))

(defn- posted-dep-tx-data
  "A balanced, POSTED depreciation journal entry (so it counts in a
   default `account-balance`). Fresh tempids each call — that is the point
   of the double-post pending test below."
  [{:keys [expense accum eur jnl]} amount date]
  [{:db/id "datomic.tx" :db.valid/from date
    :db.valid/to #inst "9999-12-31T23:59:59.999-00:00"}
   {:db/id -1
    :kontor.transaction/journal jnl
    :kontor.transaction/effective-date date
    :kontor.transaction/narration "dep"
    :kontor.transaction/state :posted
    :kontor.transaction/posted-at date}
   {:db/id -10 :kontor.posting/account expense :kontor.posting/amount amount
    :kontor.posting/commodity eur :kontor.posting/transaction -1
    :kontor.posting/display-type :product :kontor.posting/posted-at date}
   {:db/id -11 :kontor.posting/account accum :kontor.posting/amount (.negate ^java.math.BigDecimal amount)
    :kontor.posting/commodity eur :kontor.posting/transaction -1
    :kontor.posting/display-type :product :kontor.posting/posted-at date}])

(defn- account-eid [conn path]
  (:db/id (d/entity (d/db conn) [:kontor.account/path path])))

(defn- bootstrap-conn []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    conn))

(defn- mk-router [system->conn]
  (reify cross/CrossTxRouter
    (resolve-conn [_ system-id]
      (or (get system->conn system-id)
          (throw (ex-info "unknown system-id" {:system-id system-id}))))))

;; ============================================================================
;; GREEN — kontor.workflow.process atomicity under an adversarial step
;; ============================================================================

(deftest process-midstep-throw-aborts-atomically-green
  (testing "a raw exception thrown by step 3 (during assembly) commits
            NOTHING — earlier steps' fragments never land"
    (let [conn (bootstrap-conn)
          _    (journal-cat! conn)
          s1   (fn [_ _] [{:db/id "a" :kontor.account/path "WF:A" :kontor.account/name "A"
                           :kontor.account/type :asset}])
          s2   (fn [_ _] [{:db/id "b" :kontor.account/path "WF:B" :kontor.account/name "B"
                           :kontor.account/type :asset}])
          s3   (fn [_ _] (throw (RuntimeException. "step 3 blew up")))]
      (is (thrown? RuntimeException
                   (p/run-process conn {:steps [s1 s2 s3]})))
      (is (nil? (d/entity (d/db conn) [:kontor.account/path "WF:A"]))
          "step 1 fragment must not have committed")
      (is (nil? (d/entity (d/db conn) [:kontor.account/path "WF:B"]))
          "step 2 fragment must not have committed"))))

(deftest process-gate-violation-aborts-atomically-green
  (testing "step 3 emits an unbalanced fragment → the gate rejects the WHOLE
            process; earlier valid fragments do not partially land"
    (let [conn (bootstrap-conn)
          cat  (journal-cat! conn)
          s1   (fn [_ _] [{:db/id "o" :kontor.account/path "WF:Orphan" :kontor.account/name "O"
                           :kontor.account/type :asset}])
          bad  (fn [_ _]
                 (posting/build-transaction
                  {:transaction {:kontor.transaction/journal        (:jnl cat)
                                 :kontor.transaction/effective-date some-date
                                 :kontor.transaction/narration      "unbalanced"}
                   :postings    [{:kontor.posting/account (:expense cat) :kontor.posting/amount 100M
                                  :kontor.posting/commodity (:eur cat)}
                                 {:kontor.posting/account (:accum cat) :kontor.posting/amount -60M
                                  :kontor.posting/commodity (:eur cat)}]}))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (p/run-process conn {:steps [s1 bad]})))
      (is (nil? (d/entity (d/db conn) [:kontor.account/path "WF:Orphan"]))
          "the process is all-or-nothing"))))

;; ============================================================================
;; GREEN — event bus: a throwing handler does not starve its neighbours,
;; and the commit stays durable
;; ============================================================================

(deftest event-bus-throwing-handler-does-not-starve-neighbours-green
  (testing "with [good, throwing, good] handlers, BOTH good handlers fire and
            the commit is durable — the throwing handler is caught, not
            allowed to abort dispatch"
    (bus/clear-handlers!)
    (try
      (let [conn  (bootstrap-conn)
            cat   (journal-cat! conn)
            seen  (atom #{})
            _g1   (bus/register-handler! (fn [_] (swap! seen conj :g1)))
            _boom (bus/register-handler! (fn [_] (throw (ex-info "boom" {}))))
            _g2   (bus/register-handler! (fn [_] (swap! seen conj :g2)))
            report
            (p/run-process
             conn
             {:commit bus/commit-and-emit
              :steps  [(fn [_ _]
                         (posting/post-transaction-tx-data
                          {:transaction {:kontor.transaction/external-id    "WF-BUS-1"
                                         :kontor.transaction/journal        (:jnl cat)
                                         :kontor.transaction/effective-date some-date
                                         :kontor.transaction/narration      "bus test"}
                           :postings    [{:kontor.posting/account (:expense cat) :kontor.posting/amount 10M
                                          :kontor.posting/commodity (:eur cat)}
                                         {:kontor.posting/account (:accum cat) :kontor.posting/amount -10M
                                          :kontor.posting/commodity (:eur cat)}]}
                          {:vt-from some-date}))]})]
        (is (some? (:db-after report)) "process returned a tx-report")
        (is (= #{:g1 :g2} @seen) "both non-throwing handlers were delivered")
        (is (= :posted
               (-> (d/db conn) (d/entity [:kontor.transaction/external-id "WF-BUS-1"])
                   :kontor.transaction/state))
            "the commit is durable despite the throwing handler"))
      (finally (bus/clear-handlers!)))))

(deftest event-bus-dispatch-captures-error-metadata-green
  (testing "dispatch returns a count with :errors accumulated in metadata —
            the substrate's own record of a swallowed handler failure"
    (bus/clear-handlers!)
    (try
      (let [_ok   (bus/register-handler! (fn [_] :ok))
            _boom (bus/register-handler! (fn [_] (throw (ex-info "x" {:tag :boom}))))
            r     (bus/dispatch {:event/kind :kontor.transaction/committed})]
        (is (= 1 (:invoked r)) "the OK handler still counted")
        (is (= 1 (count (-> r meta :errors))) "the throwing handler is captured")
        (is (= :boom (:tag (ex-data (:ex (first (-> r meta :errors))))))))
      (finally (bus/clear-handlers!)))))

;; ============================================================================
;; GREEN — cross-tx saga: content-hash step-id makes a datahike target
;; exactly-once even under at-least-once message re-delivery
;; ============================================================================

(deftest cross-drain-content-hash-idempotent-on-redelivery-green
  (testing "draining the SAME intent twice (simulating at-least-once
            re-delivery) commits the target only once — step-id dedup"
    (let [src (bootstrap-conn)
          tgt (bootstrap-conn)
          router (mk-router {:tgt tgt})
          target-tx [{:kontor.commodity/symbol "ZZZ" :kontor.commodity/precision 2}]
          intent (cross/cross-tx-intent-tx-data
                  {:intent-key "redeliver-1" :target-system-id :tgt
                   :target-tx-data target-tx})
          _ (v/transact-with-validation src [intent])
          intent-eid (d/q '[:find ?e . :where [?e :kontor.side-effect-intent/key "redeliver-1"]]
                          (d/db src))
          s1 (cross/drain! src router)
          ;; Simulate the broker re-delivering the same message: reset the
          ;; source intent back to :pending and drain again.
          _  (d/transact src [{:db/id intent-eid
                               :kontor.side-effect-intent/status :pending}])
          s2 (cross/drain! src router)]
      (is (= 1 (:done s1)) "first drain committed")
      (is (= 1 (:done s2)) "second drain also resolves :done (idempotently)")
      (is (= 1 (count (d/q '[:find [?e ...] :where [?e :kontor.commodity/symbol "ZZZ"]]
                           (d/db tgt))))
          "target holds exactly ONE ZZZ commodity — no double commit"))))

;; ============================================================================
;; GREEN — schedule occurrence log math + composite-identity dedup
;; ============================================================================

(deftest schedule-occurrence-identity-collapses-green
  (testing "the [schedule,sequence] composite identity keeps exactly one
            occurrence-log row even across a re-fire"
    (let [conn (bootstrap-conn)
          cat  (journal-cat! conn)
          _ (d/transact conn [{:kontor.schedule/code "sub-monthly"
                               :kontor.schedule/name "Subscription"
                               :kontor.schedule/kind :recognition
                               :kontor.schedule/start-date #inst "2026-01-01"
                               :kontor.schedule/frequency :monthly
                               :kontor.schedule/state :active
                               :kontor.schedule/active true}])
          date #inst "2026-01-31"
          _ (schedule/record-occurrence! conn "sub-monthly" 1 date 100.00M (:eur cat)
                                         (posted-dep-tx-data cat 100.00M date))
          _ (schedule/record-occurrence! conn "sub-monthly" 1 date 100.00M (:eur cat)
                                         (posted-dep-tx-data cat 100.00M date))]
      (is (= 1 (d/q '[:find (count ?o) . :where [?o :kontor.schedule-occurrence/sequence 1]]
                    (d/db conn)))
          "composite identity collapses the occurrence row"))))

(deftest schedule-pending-occurrences-correct-sequence-green
  (testing "pending-occurrences computes the right due sequence given
            start-date + monthly frequency + already-fired rows"
    (let [conn (bootstrap-conn)
          cat  (journal-cat! conn)
          _ (d/transact conn [{:kontor.schedule/code "rec"
                               :kontor.schedule/name "Recurring"
                               :kontor.schedule/kind :recognition
                               :kontor.schedule/start-date #inst "2026-06-01"
                               :kontor.schedule/frequency :monthly
                               :kontor.schedule/state :active
                               :kontor.schedule/active true}])
          sched (schedule/by-code (d/db conn) "rec")
          ;; fire 1..3
          _ (doseq [n [1 2 3]]
              (let [dt (schedule/date-of-occurrence #inst "2026-06-01" :monthly n)]
                (schedule/record-occurrence! conn "rec" n dt 100.00M (:eur cat)
                                             (posted-dep-tx-data cat 100.00M dt))))
          db (d/db conn)]
      (is (= #{1 2 3} (schedule/fired-sequences db sched)))
      (is (= 4 (schedule/next-pending-sequence db sched)))
      ;; As of 2026-09-15 only Sept (seq 4) is due (start Jun → seq n is month Jun+(n-1)).
      (is (= [4] (mapv :sequence (schedule/pending-occurrences db sched #inst "2026-09-15"))))
      (is (= [#inst "2026-09-01"]
             (mapv :date (schedule/pending-occurrences db sched #inst "2026-09-15")))))))

;; ============================================================================
;; PENDING (A) — drain! orphans a crashed :processing intent
;; ============================================================================
;;
;; PENDING(NEW): `cross/drain!` only scans intents in `:pending`
;; (kontor.workflow.side-effect/pending filters :kontor.side-effect-intent/status
;; :pending — side_effect.clj:38-57). `execute-one!` claims the intent to
;; `:processing` BEFORE it transacts the target and BEFORE it marks the source
;; `:done`. If a worker crashes in that window (claimed, not yet committed),
;; the intent is stuck in `:processing` forever: the next `drain!` never sees it
;; again, and there is no sweeper/requeue for stuck jobs. This is a saga
;; liveness hole — a claimed-but-uncompleted side effect silently never fires.
;;
;; Odoo's cron/queue substrate recovers stuck work explicitly: ir_cron tracks a
;; `first_failure_date` and a failure counter, and re-picks up / times out
;; stuck jobs rather than abandoning a row mid-flight
;;   odoo/addons/base/models/ir_cron.py:122 (first_failure_date)
;;   odoo/addons/base/models/ir_cron.py:64  (FAILED state) + :430 (timeout requeue)
;;
;; This test claims the intent (simulating the pre-crash state) and asserts the
;; DESIRED recovery. It fails today because drain! ignores :processing rows.
(deftest ^:kaocha/pending drain-orphans-crashed-processing-intent
  (testing "an intent claimed to :processing by a since-crashed worker is
            recovered on the next drain! (DESIRED — fails today)"
    (let [src (bootstrap-conn)
          tgt (bootstrap-conn)
          router (mk-router {:tgt tgt})
          target-tx [{:kontor.commodity/symbol "ORPH" :kontor.commodity/precision 2}]
          intent (cross/cross-tx-intent-tx-data
                  {:intent-key "orphan-1" :target-system-id :tgt
                   :target-tx-data target-tx})
          _ (v/transact-with-validation src [intent])
          intent-eid (d/q '[:find ?e . :where [?e :kontor.side-effect-intent/key "orphan-1"]]
                          (d/db src))
          ;; Worker claimed it (→ :processing) then "crashed" before committing.
          _ (se/claim! src intent-eid)
          summary (cross/drain! src router)
          status (-> (d/pull (d/db src) [:kontor.side-effect-intent/status] intent-eid)
                     :kontor.side-effect-intent/status)]
      ;; DESIRED: drain! recovers the stuck intent and completes the saga.
      (is (= 1 (:done summary))
          "drain! should recover the orphaned :processing intent")
      (is (= :done status) "the stuck intent should reach :done")
      (is (some? (d/q '[:find ?e . :where [?e :kontor.commodity/symbol "ORPH"]]
                      (d/db tgt)))
          "the target side effect should eventually land"))))

;; ============================================================================
;; PENDING (B) — schedule re-fire double-posts the underlying journal
;; ============================================================================
;;
;; PENDING(NEW): only the OCCURRENCE-LOG row is idempotent. The
;; `[schedule,sequence]` composite identity (schema.cljc:3989) collapses the
;; :schedule-occurrence row on a re-fire, but `record-occurrence!` transacts the
;; caller-supplied journal tx-data (fresh tempid -1 + fresh postings) in the
;; SAME tx (schedule.clj:163-211). On the second call those postings are BRAND
;; NEW entities, so the ledger is DOUBLE-POSTED while the occurrence log still
;; shows one firing. `record-occurrence-is-idempotent` in the existing suite
;; only asserts the row count (=1); it never checks the account balance, so the
;; double-post is invisible there.
;;
;; Odoo ties a generated recurring move to its schedule and guards against
;; re-generating an already-generated period, so the ledger effect is
;; idempotent, not just a log row:
;;   addons/account/models/account_move.py  (auto_post recurring; the move keeps
;;   its :recurring_ref / next-date guard so a period is generated once).
;;
;; This test re-fires sequence 1 and asserts the DESIRED single ledger effect.
;; It fails today because the expense balance is 200, not 100.
(deftest ^:kaocha/pending schedule-refire-double-posts-journal
  (testing "re-firing an occurrence must not double-post the journal
            (DESIRED — fails today: the ledger effect is not idempotent)"
    (let [conn (bootstrap-conn)
          cat  (journal-cat! conn)
          _ (d/transact conn [{:kontor.schedule/code "dep"
                               :kontor.schedule/name "Dep"
                               :kontor.schedule/kind :depreciation
                               :kontor.schedule/start-date #inst "2026-01-01"
                               :kontor.schedule/frequency :monthly
                               :kontor.schedule/state :active
                               :kontor.schedule/active true}])
          date #inst "2026-01-31"
          _ (schedule/record-occurrence! conn "dep" 1 date 100.00M (:eur cat)
                                         (posted-dep-tx-data cat 100.00M date))
          bal1 (balance/account-balance-single-commodity
                conn (account-eid conn "Expense:Depreciation") (:eur cat))
          ;; The "idempotent" re-fire (same schedule+sequence).
          _ (schedule/record-occurrence! conn "dep" 1 date 100.00M (:eur cat)
                                         (posted-dep-tx-data cat 100.00M date))
          bal2 (balance/account-balance-single-commodity
                conn (account-eid conn "Expense:Depreciation") (:eur cat))]
      (is (zero? (.compareTo 100.00M (:amount bal1)))
          "first firing posts 100 to the expense account")
      ;; occurrence log stayed at one row (that part IS idempotent)...
      (is (= 1 (d/q '[:find (count ?o) . :where [?o :kontor.schedule-occurrence/sequence 1]]
                    (d/db conn))))
      ;; ...but the ledger should ALSO be idempotent. It is not: bal2 = 200.
      (is (zero? (.compareTo 100.00M (:amount bal2)))
          "re-firing must NOT double the ledger effect (DESIRED)"))))

;; ============================================================================
;; PENDING (C) — no cross-DB saga compensation on a partial commit
;; ============================================================================
;;
;; PENDING(NEW): `drain!` processes each :cross-tx-post intent INDEPENDENTLY and
;; in isolation (cross.clj:264-289). There is no notion of a saga GROUP: if two
;; intents form one logical atomic cross-DB operation and the first commits to
;; target A while the second fails against target B, target A is NOT rolled
;; back / compensated. The substrate ships step-id idempotency (exactly-once per
;; intent) but no compensating-action primitive, so a partial failure leaves the
;; participating DBs mutually inconsistent with no recovery path. (Documented as
;; "not 2PC" in the ns docstring — but the ABSENCE of any compensation hook is
;; the gap: a consumer cannot express "undo A if B fails".)
;;
;; Odoo performs a multi-record write inside a single DB transaction, so a
;; failure rolls the whole unit back atomically (the ORM/cr.commit boundary) —
;; there is no half-applied multi-record write.
;;
;; This test drives A (valid) + B (router throws) and asserts the DESIRED
;; atomic outcome (neither lands). It fails today: A is committed, B failed.
(deftest ^:kaocha/pending cross-saga-no-compensation-on-partial-commit
  (testing "two intents forming one logical unit — B fails, so A should be
            compensated (DESIRED — fails today: A stays committed)"
    (let [src  (bootstrap-conn)
          tgtA (bootstrap-conn)
          ;; router knows :a but NOT :b → intent B fails.
          router (mk-router {:a tgtA})
          intent-a (cross/cross-tx-intent-tx-data
                    {:intent-key "saga-A" :target-system-id :a
                     :target-tx-data [{:kontor.commodity/symbol "AAA" :kontor.commodity/precision 2}]})
          intent-b (cross/cross-tx-intent-tx-data
                    {:intent-key "saga-B" :target-system-id :b
                     :target-tx-data [{:kontor.commodity/symbol "BBB" :kontor.commodity/precision 2}]})
          _ (v/transact-with-validation src [intent-a])
          _ (v/transact-with-validation src [intent-b])
          summary (cross/drain! src router)]
      (is (= 1 (:done summary)) "A drained :done")
      (is (= 1 (:failed summary)) "B failed (unknown target system)")
      ;; DESIRED: because the logical unit failed, A must have been compensated.
      (is (nil? (d/q '[:find ?e . :where [?e :kontor.commodity/symbol "AAA"]]
                     (d/db tgtA)))
          "target A must NOT retain its commit when sibling B failed (DESIRED)"))))

;; ============================================================================
;; PENDING (D) — commit-and-emit silently swallows a handler failure
;; ============================================================================
;;
;; PENDING(NEW): `dispatch` accumulates handler exceptions into the RETURN
;; value's metadata (event_bus.clj:206-227: `(with-meta {:invoked n} {:errors
;; [...]})`), but `commit-and-emit` DISCARDS that entirely — it calls
;; `(dispatch ...)` for effect and returns only the datahike tx-report
;; (event_bus.clj:247-251). So a handler that throws during a real commit path
;; leaves NO durable record and NO signal to the caller: no dead-letter row, no
;; retry, no error surfaced on the returned report. Handler delivery is
;; at-most-once with silent loss — a projection / external mirror that failed to
;; update is indistinguishable from one that succeeded.
;;
;; Odoo's mail/queue side effects persist a failed row with its exception so it
;; is visible and retryable, rather than dropping the failure on the floor:
;;   addons/mail/models/mail_mail.py  (state 'exception' + failure_reason on send
;;   failure — the failure is a durable, queryable, retryable record).
;;
;; This test asserts the DESIRED: after a throwing handler runs under
;; commit-and-emit, the caller can discover the failure. It fails today —
;; nothing surfaces it.
(deftest ^:kaocha/pending commit-and-emit-swallows-handler-failure
  (testing "a handler that throws under commit-and-emit must leave a
            discoverable failure record (DESIRED — fails today: swallowed)"
    (bus/clear-handlers!)
    (try
      (let [conn (bootstrap-conn)
            cat  (journal-cat! conn)
            _    (bus/register-handler! (fn [_] (throw (ex-info "projection failed" {}))))
            report
            (bus/commit-and-emit
             conn
             (posting/post-transaction-tx-data
              {:transaction {:kontor.transaction/external-id    "WF-DLQ-1"
                             :kontor.transaction/journal        (:jnl cat)
                             :kontor.transaction/effective-date some-date
                             :kontor.transaction/narration      "dlq test"}
               :postings    [{:kontor.posting/account (:expense cat) :kontor.posting/amount 5M
                              :kontor.posting/commodity (:eur cat)}
                             {:kontor.posting/account (:accum cat) :kontor.posting/amount -5M
                              :kontor.posting/commodity (:eur cat)}]}
              {:vt-from some-date}))]
        (is (some? (:db-after report)) "the commit itself is durable")
        ;; DESIRED: the caller learns the handler failed — commit-and-emit
        ;; should surface the dispatch errors (or write a dead-letter row).
        ;; Today it returns the bare tx-report with no error channel.
        (is (seq (-> report meta :errors))
            "commit-and-emit must surface swallowed handler errors (DESIRED)"))
      (finally (bus/clear-handlers!)))))
