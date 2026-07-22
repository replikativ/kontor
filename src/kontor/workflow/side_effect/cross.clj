(ns kontor.workflow.side-effect.cross
  "Cross-DB side-effect execution — ADR-074. Generalizes
   [[kontor.workflow.side-effect]] (ADR-041) for the case where the side effect
   IS a tx-data commit against a *different* datahike conn — another
   kontor instance (intercompany), a stratum secondary index, a
   scriptum / proximum / yggdrasil sub-system, or any datahike DB the
   caller routes to.

   ## Idempotency

   The fundamental crash story this layer addresses: a drain worker
   has

     1. claimed the intent (`:processing`),
     2. transacted the target, and
     3. NOT YET marked the source intent `:done`.

   If the worker crashes after step 2 and another worker picks the
   intent back up, naively retrying step 2 double-commits the target.
   The fix: the worker writes a **deterministic step-id**
   (`:kontor.cross-tx/step-id`, schema in ADR-074) into the target tx; before
   transacting, it queries the target db for that step-id. If present,
   skip the transact + go straight to `mark-done!`. The step-id is
   derived from the source intent key + a content hash of the
   target tx-data, so workers running on different VMs converge on
   the same value.

   ## What this is NOT

   - Not 2PC, not XA, not a distributed transaction coordinator. This
     is a saga executor with content-hash idempotency. Research note
     71 §1 + §5.2 covers the choice rationale.
   - Not a workflow engine. `kontor.workflow.process` is the single-DB
     orchestrator; `kontor.workflow.side-effect.cross` is the cross-DB
     orchestrator. Bigger needs (Temporal, Step Functions, Camunda)
     are consumer-side.
   - Not generic to non-datahike backends. The target conn MUST
     support `(datahike.api/transact conn …)` and `(datahike.api/db
     conn)`. Heterogeneous backends (Kafka, an HTTP webhook, an S3
     PUT) are still handled by the parent [[kontor.workflow.side-effect]]
     dispatcher pattern: ship the intent, drain via a custom worker.

   ## Usage

   Write the cross-tx intent in the SAME source tx as the upstream
   status change:

     (validation/transact-with-validation
       source-conn
       [(...source-side tx-data...)
        (cross/cross-tx-intent-tx-data
          {:intent-key       \"inv-2026-0001-stratum-index\"
           :target-system-id :stratum-index
           :target-tx-data   [{:invoice-index/eid    invoice-eid
                               :invoice-index/total  1234.56M}]
           :source-history-eid history-eid})])

   Then in a background loop (or REPL):

     (cross/drain! source-conn router)

   `router` satisfies [[CrossTxRouter]]:

     (def my-router
       (reify cross/CrossTxRouter
         (resolve-conn [_ system-id]
           (case system-id
             :stratum-index   stratum-conn
             :scriptum-log    scriptum-conn
             :intercompany-de other-kontor-conn))))

   ## Source-side schema

   Reuses `:kontor.side-effect-intent/*` (ADR-041) verbatim. The intent
   `:type` is `:cross-tx-post`; the `:payload` is a pr-str'd EDN map
   with the keys documented on [[cross-tx-intent-tx-data]].

   ## Target-side schema

   Requires `:kontor.cross-tx/step-id` to be installed on the target conn
   (it is part of `kontor.schema/all`, so any kontor target works
   out of the box; non-kontor targets — scriptum/stratum/etc — must
   install it separately via the same attr def). When this schema is
   missing the drain worker raises a `:kontor.cross-tx/target-schema-missing`
   ex-info before transacting."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datahike.api :as d]
            [kontor.workflow.side-effect :as se]
            [kontor.validation :as validation])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util Base64]))

;; ============================================================================
;; CrossTxRouter — the consumer's system-id → conn mapping
;; ============================================================================

(defprotocol CrossTxRouter
  "Maps a `system-id` keyword to a datahike connection. Implemented
   by the consumer at boot — typically a (reify CrossTxRouter ...)
   that closes over the live conns the consumer manages."
  (resolve-conn [this system-id]
    "Return the datahike connection for `system-id`, or throw if the
     system-id is unknown. Workers will surface the thrown ex-info
     into the intent's :kontor.side-effect-intent/last-error."))

;; ============================================================================
;; step-id derivation
;; ============================================================================

(defn- canonical-edn
  "Print a clojure value in a deterministic order-stable form. The
   step-id depends on this, so different VMs must agree. We rely on
   `pr-str` of *sorted* keys for maps; vectors keep their order; sets
   are sorted into vectors for determinism."
  [x]
  (cond
    (map? x)    (str "{"
                     (str/join
                      " "
                      (mapv (fn [[k v]]
                              (str (canonical-edn k) " " (canonical-edn v)))
                            (sort-by (comp pr-str key) x)))
                     "}")
    (set? x)    (str "#{" (str/join " "
                                    (mapv canonical-edn
                                          (sort-by pr-str x)))
                     "}")
    (sequential? x) (str "[" (str/join " " (mapv canonical-edn x)) "]")
    :else       (pr-str x)))

(defn step-id
  "Deterministically derive a step-id from the intent-key + the
   canonical EDN form of the target tx-data. SHA-256, Base64-URL,
   no padding — 43 chars, fits cleanly in :kontor.cross-tx/step-id without
   character-class headaches.

   Pure — must agree across JVMs / restarts / re-claims."
  ^String [intent-key target-tx-data]
  (let [canon (str intent-key "|" (canonical-edn target-tx-data))
        bytes (.getBytes ^String canon StandardCharsets/UTF_8)
        md    (MessageDigest/getInstance "SHA-256")
        hash  (.digest md bytes)
        enc   (.encodeToString (Base64/getUrlEncoder) hash)]
    ;; strip padding (Base64/getUrlEncoder.withoutPadding requires JDK 11+)
    (str/replace enc #"=+$" "")))

;; ============================================================================
;; Source-side: build the intent
;; ============================================================================

(defn cross-tx-intent-tx-data
  "Build a :side-effect-intent map for a cross-tx-post side effect.
   Caller stitches this into the SAME tx as the upstream status
   change (per ADR-041) — so the intent + the upstream change commit
   atomically on the source side.

   Inputs (map):
     :intent-key          REQUIRED — globally unique idempotency key
                           (convention: hash of source-eid + transition
                           + attempt + a stable seed). Caller produces.
     :target-system-id    REQUIRED — keyword the router knows about.
     :target-tx-data      REQUIRED — vector of maps / vectors, ready
                           for `(d/transact target-conn ...)` after
                           the worker injects the :kontor.cross-tx/step-id.
     :source-history-eid  optional — ref to the :status-history row
                           that produced this intent (back-link for
                           auditors).
     :max-retries         optional — default 5.

   Returns a single map suitable for inclusion in a tx-data vector."
  [{:keys [intent-key target-system-id target-tx-data
           source-history-eid max-retries]
    :or   {max-retries 5}}]
  (when-not intent-key       (throw (ex-info ":intent-key required" {})))
  (when-not target-system-id (throw (ex-info ":target-system-id required" {})))
  (when-not target-tx-data   (throw (ex-info ":target-tx-data required" {})))
  (let [sid (step-id intent-key target-tx-data)
        payload (pr-str {:target/system-id target-system-id
                         :target/tx-data   target-tx-data
                         :step-id          sid})]
    (cond-> {:kontor.side-effect-intent/key         intent-key
             :kontor.side-effect-intent/type        :cross-tx-post
             :kontor.side-effect-intent/payload     payload
             :kontor.side-effect-intent/status      :pending
             :kontor.side-effect-intent/created-at  (java.util.Date.)
             :kontor.side-effect-intent/retry-count 0
             :kontor.side-effect-intent/max-retries max-retries}
      source-history-eid
      (assoc :kontor.side-effect-intent/origin-history source-history-eid))))

;; ============================================================================
;; Drain — the worker
;; ============================================================================

(defn- target-has-step?
  "Returns true iff the target conn already holds a tx with this
   step-id. The query is cheap (`:kontor.cross-tx/step-id` is identity-
   unique, so it's an index hit)."
  [target-conn step-id]
  (boolean
   (d/q '[:find ?t .
          :in $ ?sid
          :where [?t :kontor.cross-tx/step-id ?sid]]
        (d/db target-conn) step-id)))

(defn- assert-target-schema!
  [target-conn]
  (when-not (d/q '[:find ?e .
                   :where [?e :db/ident :kontor.cross-tx/step-id]]
                 (d/db target-conn))
    (throw (ex-info ":kontor.cross-tx/target-schema-missing — :kontor.cross-tx/step-id not installed on target conn"
                    {:type :kontor.cross-tx/target-schema-missing
                     :remediation "Install the :kontor.cross-tx/step-id attr — it ships in kontor.schema/all; for non-kontor targets see ADR-074."}))))

(defn execute-one!
  "Execute a single :cross-tx-post intent (already in :pending or
   :failed status). Steps:

     1. Parse the payload.
     2. Resolve the target conn via the router.
     3. Assert the target has the :kontor.cross-tx/step-id schema.
     4. Claim the intent (transition to :processing on the source).
     5. Check if the target already holds the step-id — if yes, skip
        the target transact and go straight to mark-done.
     6. Otherwise: inject the :kontor.cross-tx/step-id onto a tx-meta map
        and transact the augmented tx-data against the target.
     7. Mark the source intent :done.
     8. On any exception in steps 5-6, mark the source intent
        :failed (or :abandoned past max-retries) with the message.

   Returns one of:
     :done       — committed (or was idempotently-already-committed)
     :failed     — error, eligible for re-claim
     :abandoned  — terminal error or retry budget exhausted"
  [source-conn router intent-eid]
  (let [intent (d/pull (d/db source-conn) '[*] intent-eid)
        payload (edn/read-string (:kontor.side-effect-intent/payload intent))
        {target-system-id :target/system-id
         target-tx-data   :target/tx-data
         sid              :step-id} payload]
    (try
      (let [target-conn (resolve-conn router target-system-id)
            _ (assert-target-schema! target-conn)]
        (se/claim! source-conn intent-eid)
        (when-not (target-has-step? target-conn sid)
          (let [augmented (conj (vec target-tx-data)
                                {:db/id "datomic.tx"
                                 :kontor.cross-tx/step-id sid})]
            (validation/transact-with-validation target-conn augmented)))
        (se/mark-done! source-conn intent-eid)
        :done)
      (catch Exception e
        (se/mark-failed! source-conn intent-eid (.getMessage e))
        (let [retry (or (:kontor.side-effect-intent/retry-count
                         (d/pull (d/db source-conn)
                                 [:kontor.side-effect-intent/retry-count]
                                 intent-eid))
                        0)
              max-r (or (:kontor.side-effect-intent/max-retries intent) 5)]
          (if (>= retry max-r) :abandoned :failed))))))

(defn drain!
  "Execute every pending :cross-tx-post intent against the configured
   router. Returns a summary map:
     {:processed N
      :done      N
      :failed    N
      :abandoned N
      :errors    [<ex-info ...>]}

   Note: this is a *synchronous* drain — callers typically wrap it in
   a scheduled job or a `kontor.workflow.schedule` periodic. The single-pass
   semantics make it easy to test and reason about; the re-claim
   loop on :failed intents is the caller's responsibility (call
   `drain!` again after a back-off)."
  [source-conn router]
  (let [db (d/db source-conn)
        pending-rows (se/pending db {:type :cross-tx-post})]
    (reduce
     (fn [acc intent]
       (let [eid (:db/id intent)
             result (execute-one! source-conn router eid)]
         (-> acc
             (update :processed inc)
             (update result inc))))
     {:processed 0 :done 0 :failed 0 :abandoned 0}
     pending-rows)))
