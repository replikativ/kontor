(ns kontor.event-bus-test
  "Tests for `kontor.event-bus` (ADR-092) — McComb-aligned in-process
   pub-sub for transaction commits. Verifies:

   - `register-handler!` + `unregister-handler!` lifecycle.
   - `dispatch` fires registered handlers (and only filter-passing ones).
   - `commit-and-emit` integrates with `kontor.process/run-process`.
   - Handler exceptions do NOT propagate to the writer."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.event-bus :as bus]
            [kontor.ledger :as ledger]
            [kontor.posting :as posting]
            [kontor.process :as process]))

(def ^:private some-date #inst "2026-05-09T00:00:00Z")

(use-fixtures :each
  (fn [f]
    (bus/clear-handlers!)
    (try (f)
         (finally (bus/clear-handlers!)))))

;; ============================================================================
;; Registry lifecycle
;; ============================================================================

(deftest register-and-unregister-roundtrip
  (let [calls (atom [])
        id    (bus/register-handler! (fn [ev] (swap! calls conj ev)))]
    (is (contains? (bus/registered-handlers) id))
    (bus/unregister-handler! id)
    (is (not (contains? (bus/registered-handlers) id)))
    (is (= [] @calls))))

(deftest unregister-missing-id-is-a-no-op
  (is (nil? (bus/unregister-handler! 999999999))))

(deftest clear-handlers-empties-registry
  (bus/register-handler! (fn [_]))
  (bus/register-handler! (fn [_]))
  (is (= 2 (count (bus/registered-handlers))))
  (bus/clear-handlers!)
  (is (zero? (count (bus/registered-handlers)))))

;; ============================================================================
;; Dispatch + filtering
;; ============================================================================

(deftest dispatch-invokes-registered-handlers
  (let [calls (atom [])
        _id1  (bus/register-handler! (fn [ev] (swap! calls conj [:h1 (:event/kind ev)])))
        _id2  (bus/register-handler! (fn [ev] (swap! calls conj [:h2 (:event/kind ev)])))
        r     (bus/dispatch {:event/kind :transaction/committed})]
    (is (= 2 (:invoked r)))
    (is (= #{[:h1 :transaction/committed]
             [:h2 :transaction/committed]}
           (set @calls)))))

(deftest dispatch-respects-filter
  (let [calls (atom [])
        _yes  (bus/register-handler!
               (fn [ev] (swap! calls conj [:yes (:event/kind ev)]))
               {:filter (fn [ev] (= :transaction/committed (:event/kind ev)))})
        _no   (bus/register-handler!
               (fn [ev] (swap! calls conj [:no (:event/kind ev)]))
               {:filter (fn [ev] (= :status-history/changed (:event/kind ev)))})
        r     (bus/dispatch {:event/kind :transaction/committed})]
    (is (= 1 (:invoked r)))
    (is (= [[:yes :transaction/committed]] @calls))))

(deftest dispatch-collects-handler-exceptions
  (let [_ok    (bus/register-handler! (fn [_] :ok))
        _boom  (bus/register-handler! (fn [_] (throw (ex-info "boom" {:hook :x}))))
        r      (bus/dispatch {:event/kind :transaction/committed})
        errors (-> r meta :errors)]
    (testing "the OK handler still ran"
      (is (= 1 (:invoked r))))
    (testing "the boom handler is captured in :errors metadata"
      (is (= 1 (count errors)))
      (is (= :x (:hook (ex-data (:ex (first errors)))))))))

;; ============================================================================
;; commit-and-emit integration
;; ============================================================================

(defn- seed!
  [conn]
  (ledger/install-defaults! conn)
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

(deftest commit-and-emit-fires-event-on-successful-tx
  (let [conn  (core/create-test-db)
        _     (seed! conn)
        db    (d/db conn)
        eur   (:db/id (d/entity db [:commodity/symbol "EUR"]))
        rec   (:db/id (d/entity db [:account/path "Assets:Receivable"]))
        rev   (:db/id (d/entity db [:account/path "Income:Sales"]))
        jnl   (:db/id (d/entity db [:journal/code "INV"]))
        events (atom [])
        _     (bus/register-handler! (fn [ev] (swap! events conj ev)))
        ;; Run a single-step process that posts one invoice; tag the
        ;; commit fn so the bus fires.
        result
        (process/run-process
         conn
         {:steps  [(fn [_db _ctx]
                     (posting/post-transaction-tx-data
                      {:transaction
                       {:transaction/external-id    "INV-BUS-1"
                        :transaction/journal        jnl
                        :transaction/effective-date some-date
                        :transaction/narration      "Bus emission test"}
                       :postings
                       [{:posting/account rec :posting/amount  10.00M :posting/commodity eur}
                        {:posting/account rev :posting/amount -10.00M :posting/commodity eur}]}
                      {:vt-from some-date}))]
          :commit bus/commit-and-emit})]
    (testing "the process returned a tx-report"
      (is (some? result))
      (is (some? (:db-after result))))
    (testing "exactly one event was emitted"
      (is (= 1 (count @events))))
    (let [ev (first @events)]
      (testing "event shape"
        (is (= :transaction/committed (:event/kind ev)))
        (is (some? (:event/tx-report ev)))
        (is (= conn (:event/conn ev)))
        (is (some? (:event/at ev)))
        (is (vector? (:event/transactions ev))))
      (testing ":event/transactions carries the committed :transaction"
        (let [txs (:event/transactions ev)]
          (is (= 1 (count txs)))
          (is (= "INV-BUS-1" (-> txs first :transaction/external-id)))
          (is (= :posted (-> txs first :transaction/state))))))))

(deftest commit-and-emit-handler-crash-does-not-block-commit
  (let [conn  (core/create-test-db)
        _     (seed! conn)
        db    (d/db conn)
        eur   (:db/id (d/entity db [:commodity/symbol "EUR"]))
        rec   (:db/id (d/entity db [:account/path "Assets:Receivable"]))
        rev   (:db/id (d/entity db [:account/path "Income:Sales"]))
        jnl   (:db/id (d/entity db [:journal/code "INV"]))
        _     (bus/register-handler!
               (fn [_] (throw (ex-info "handler-boom" {}))))
        result
        (process/run-process
         conn
         {:steps  [(fn [_db _ctx]
                     (posting/post-transaction-tx-data
                      {:transaction
                       {:transaction/external-id    "INV-BUS-CRASH"
                        :transaction/journal        jnl
                        :transaction/effective-date some-date
                        :transaction/narration      "Bus-crash test"}
                       :postings
                       [{:posting/account rec :posting/amount  20.00M :posting/commodity eur}
                        {:posting/account rev :posting/amount -20.00M :posting/commodity eur}]}
                      {:vt-from some-date}))]
          :commit bus/commit-and-emit})]
    (testing "the commit still succeeded despite the handler exception"
      (is (some? result))
      (is (= :posted
             (-> (d/db conn)
                 (d/entity [:transaction/external-id "INV-BUS-CRASH"])
                 :transaction/state))))))

(deftest no-handlers-no-emission
  (testing "dispatch with no registered handlers is a no-op"
    (let [r (bus/dispatch {:event/kind :transaction/committed})]
      (is (= 0 (:invoked r))))))
