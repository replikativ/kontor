(ns kontor.event-bus
  "In-process pub-sub for kontor transactions — ADR-092.

   ## What this is

   A *substrate seam* for McComb-style consumers who want every
   business event to publish to an event-bus. The kernel itself is
   event-shaped (a transaction = an event), but consumers building
   reactive UIs / cache projections / external-system mirrors need a
   *callback* into their code when a transaction commits.

   Today the only way to react to a kontor commit is to poll the
   tx-log or to mount a `kontor.process` orchestrator and inline the
   reaction. Neither composes well; consumers re-implement the same
   pub-sub pattern in every codebase.

   `register-handler!` + `emit-tx!` close that gap. The kernel exposes
   one hook; consumers subscribe with one fn; pure-data events are
   dispatched in-process after a commit, **outside** the
   `transact-with-validation` gate (i.e. handlers cannot reject the
   commit — by the time they fire, the commit is already durable).

   ## What this is NOT

   - Not Kafka / NATS / Redis Streams / RabbitMQ. A consumer wanting
     persistent / cross-process delivery writes an adapter that
     forwards events from the in-process bus to their broker of
     choice. We deliberately stay in-process to honor the single-
     runtime + single-dep ADR-001 stance.
   - Not transactional. Handlers fire **after** the d/transact
     returns; a handler crashing does not roll the commit back. The
     datahike commit is the durable event; the bus is the
     convenience.
   - Not ordered across handlers. Handler dispatch order is
     unspecified; consumers needing ordering chain handlers
     internally.
   - Not a back-pressure / queue / scheduler. Handlers run
     synchronously on the writer's thread by default. A consumer
     wanting async dispatch wraps their handler in a future.

   ## Event shape

   Each emitted event is a plain map:

       {:event/kind       — :kontor.transaction/committed (currently the only kind)
        :event/tx-report  — the datahike tx-report (carries :db-after,
                            :tx-data, :tempids)
        :event/conn       — the connection the commit happened on
        :event/transactions  — vec of pulled :transaction maps that
                            were created or mutated in this tx (empty
                            if no :kontor.transaction/* ops were present)
        :event/at         — java.util.Date of dispatch}

   Future kinds (`:kontor.status-history/changed`, `:audit-doc/created`,
   `:kontor.posting/posted`) compose orthogonally; consumers filter on
   `:event/kind` in their handler.

   ## Wiring

   `kontor.process/run-process` is the canonical entry point for the
   transactional shell — if a consumer wants every kontor process to
   publish, they pass `:commit` as `event-bus/commit-and-emit` (a
   helper that composes the kernel's validation gate with the bus
   dispatch). The bare `kontor.validation/transact-with-validation`
   path can also be wrapped by composition.

   `kontor.event-bus/dispatch` is exposed publicly so a consumer who
   wants emission on writes through their own path (not run-process,
   not transact-with-validation) can call it directly with a tx-
   report. ADR-092."
  (:require [datahike.api :as d]
            [kontor.validation :as validation])
  (:import [java.util Date]
           [java.util.concurrent.atomic AtomicLong]))

;; ============================================================================
;; Registry (process-local, thread-safe via atom swap)
;; ============================================================================

(defonce ^:private handlers
  ;; {handler-id {:fn handler-fn :filter pred-fn :tag any}}
  (atom {}))

(defonce ^:private id-counter (AtomicLong. 0))

(defn register-handler!
  "Subscribe `handler-fn` to bus events.

   `handler-fn` is `(fn [event] …)` where `event` is the map
   documented in the namespace docstring. The handler's return value
   is ignored; throwing logs at the bus level and does NOT propagate
   to the writer (the commit is already durable).

   Options:
     :filter   — predicate `(fn [event] -> bool)`; only events for
                 which this returns truthy are delivered. Default:
                 always true.
     :tag      — opaque value the consumer can use to identify their
                 handler at unregister time; defaults to a generated
                 long id. Returned to the caller for
                 `unregister-handler!`.

   Returns the handler-id (long). Use it to unregister.

   ADR-092."
  ([handler-fn] (register-handler! handler-fn {}))
  ([handler-fn {:keys [filter tag]}]
   (let [id (.incrementAndGet ^AtomicLong id-counter)]
     (swap! handlers assoc id
            {:fn     handler-fn
             :filter (or filter (constantly true))
             :tag    (or tag id)})
     id)))

(defn unregister-handler!
  "Remove the handler registered under `id` (from
   `register-handler!`'s return value). Idempotent — unregistering a
   missing id is a no-op."
  [id]
  (swap! handlers dissoc id)
  nil)

(defn registered-handlers
  "Snapshot of `{id tag}` for currently-registered handlers. For
   inspection / debugging; not part of the load-bearing API."
  []
  (into {} (map (fn [[id m]] [id (:tag m)])) @handlers))

(defn clear-handlers!
  "Remove all registered handlers. Intended for test isolation —
   production consumers should hold and unregister their own ids."
  []
  (reset! handlers {})
  nil)

;; ============================================================================
;; Event construction
;; ============================================================================

(defn- datom-attr [d]
  ;; Both datahike.datom.Datom and a plain [e a v tx added?] vec are
  ;; supported here — the tx-report from d/transact returns Datom
  ;; records; consumers building events from raw tx-data vectors get
  ;; vec form via destructuring.
  (cond
    (instance? datahike.datom.Datom d) (.-a ^datahike.datom.Datom d)
    (sequential? d)                     (nth d 1 nil)
    :else                               nil))

(defn- datom-entity [d]
  (cond
    (instance? datahike.datom.Datom d) (.-e ^datahike.datom.Datom d)
    (sequential? d)                     (nth d 0 nil)
    :else                               nil))

(defn- transactions-in-tx-data
  "Pull the :transaction entities affected by `tx-data` from `db-after`.
   Walks the tx-data datoms for any `:kontor.transaction/*` attribute touched
   (created or mutated) and pulls each distinct eid. Empty when no
   :kontor.transaction/* ops are present."
  [db-after tx-data]
  (let [eids
        (->> tx-data
             (keep (fn [op]
                     (let [a (datom-attr op)]
                       (when (and (keyword? a)
                                  (= "kontor.transaction" (namespace a)))
                         (datom-entity op)))))
             distinct
             vec)]
    (->> eids
         (keep (fn [eid]
                 (try
                   (d/pull db-after
                           [:db/id
                            :kontor.transaction/external-id
                            :kontor.transaction/journal
                            :kontor.transaction/effective-date
                            :kontor.transaction/narration
                            :kontor.transaction/partner
                            :kontor.transaction/state
                            :kontor.transaction/posted-at
                            :kontor.transaction/document-type
                            :kontor.transaction/clearance-token
                            :kontor.transaction/clearance-format]
                           eid)
                   (catch Throwable _ nil))))
         (filterv :db/id))))

(defn ->event
  "Build the event map dispatched to handlers. Pure / data — exposed
   so consumers writing their own dispatch path can build the same
   shape. ADR-092."
  [conn tx-report]
  {:event/kind         :kontor.transaction/committed
   :event/conn         conn
   :event/tx-report    tx-report
   :event/transactions (transactions-in-tx-data (:db-after tx-report)
                                                (:tx-data tx-report))
   :event/at           (Date.)})

;; ============================================================================
;; Dispatch
;; ============================================================================

(defn dispatch
  "Synchronously call every registered (and passing-`:filter`)
   handler with `event`. Returns the count of handlers invoked.
   Exceptions thrown by handlers are caught + accumulated under
   `:errors` on the metadata of the returned count; the bus does NOT
   re-throw — the commit is durable, the handler is best-effort.

   Exposed publicly so a consumer with their own commit path can fire
   the bus on their writes. ADR-092."
  [event]
  (let [snapshot @handlers
        errors   (atom [])
        called   (volatile! 0)]
    (doseq [[id {:keys [fn filter]}] snapshot]
      (try
        (when (filter event)
          (fn event)
          (vswap! called inc))
        (catch Throwable t
          (swap! errors conj {:handler-id id :ex t}))))
    (with-meta {:invoked @called}
      {:errors @errors})))

;; ============================================================================
;; Commit-and-emit composition
;; ============================================================================

(defn commit-and-emit
  "A `:commit` fn for `kontor.process/run-process` (and a drop-in
   replacement for any other commit fn). Runs the kernel's
   `transact-with-validation` gate; if it returns a tx-report,
   dispatches a `:kontor.transaction/committed` event to the bus.

   Use:

       (process/run-process conn
         {:steps   [...]
          :commit  event-bus/commit-and-emit})

   The bus dispatch runs OUTSIDE the d/transact call — i.e., a
   handler crashing does NOT roll back the commit. ADR-092."
  [conn tx-data]
  (let [report (validation/transact-with-validation conn tx-data)]
    (when report
      (dispatch (->event conn report)))
    report))
