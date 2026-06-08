(ns kontor.workflow.process
  "Multi-step transactional processes — ADR-067.

   A *process* is a sequence of pure **step** fns. `run-process`
   threads them against a single start-snapshot, accumulates one
   tx-data vector, and commits it as one atomic transaction through
   the kernel's validation gate. The companions' multi-`d/transact`
   orchestrators (`commence!`, `run-depreciation!`, `run-lease!`,
   the modification transactors, `allocate-fifo!`, the inventory
   flows, `close-fiscal-year!`) become processes — one atomic,
   validated commit instead of N unguarded ones.

   ## Step contract

   A step is `(db, ctx) -> result` where `db` is the speculative db
   reflecting every prior step's tx-data and `result` is one of:

       nil                                  ; no-op
       tx-data                              ; a vector — a tx fragment
       {:tx-data tx-data}                   ; fragment, explicit
       {:tx-data tx-data :ctx ctx'}         ; fragment + threaded ctx
       {:steps [step ...]}                  ; sub-process — splices in
       {:tx-data ... :steps [...] :ctx ...} ; any combination

   `{:steps ...}` is the monadic flatten: a step may return *more
   steps*, which run before the remainder of the queue, each seeing
   the db with all prior fragments applied. Sub-transactors are thus
   not 'called' — a sub-process is a step-list (or a step returning
   `{:steps ...}`) that splices in.

   ## Invariants steps must respect

   - **Reference cross-step entities by string tempid, not by
     querying the speculative db for an eid.** The speculative db
     resolves tempids so a later step *reads consistently*, but the
     final commit re-resolves them — an eid captured off the
     speculative db is an artifact, not the committed identity. Read
     the speculative db for committed data and prior-step *facts*
     (\"has a book been opened?\"); thread *identity* via string
     tempids, which resolve consistently across fragments in the one
     final transaction.
   - **Do not emit tx-meta.** Valid-time is owned by `run-process`:
     every fragment is `strip-tx-meta`'d as it accumulates and one
     outer `with-vt` is applied to the whole process (research note
     46, problem E). A step emitting a `{:db/id \"datomic.tx\"}` map
     has it silently stripped.

   ## Concurrency

   `run-process` serializes on `conn`: the `(d/db conn)` snapshot,
   the step threading, and the commit are atomic w.r.t. other
   `run-process` calls on the same conn — the structural guarantee
   against the snapshot-vs-commit race. Because
   the step reads run *outside* `d/transact` (only the existing
   `validate-and-apply` `:db.fn/call` runs in the writer), an
   expensive in-process read never blocks the writer.

   datahike's `:db.fn/cas` remains the lock-free single-datom escape
   hatch for a genuinely-hot single-datom transition.

   The `:db.fn/call`-the-whole-process variant — the chain emitting
   its own follow-up `:db.fn/call`s inside the transactor — was
   considered and rejected for v1: it cannot reuse the kernel's
   `transact-with-validation` gate (the datalog-invariant pass needs
   `conn` + the complete tx-data from *outside* `d/transact`), it
   runs the step reads inside the writer, and `:dry-run?` would need
   a second code path. See ADR-067."
  (:require [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.validation :as validation]))

(defn- normalize
  "Coerce a step's return value into a canonical
   `{:tx-data <vector> :ctx <ctx> :steps <seq>}` map."
  [result ctx]
  (cond
    (nil? result)    {:tx-data [] :ctx ctx :steps nil}
    (vector? result) {:tx-data result :ctx ctx :steps nil}
    (map? result)    {:tx-data (vec (:tx-data result))
                      :ctx     (get result :ctx ctx)
                      :steps   (:steps result)}
    :else
    (throw (ex-info "process step must return nil, a tx-data vector, or a map"
                    {:type :process/bad-step-return :returned result}))))

(defn run-steps
  "The process engine. Threads `steps` against `db0`; each step sees
   the speculative db with all prior fragments applied, and
   `{:steps ...}` returns splice in at the front of the queue (the
   monadic flatten). Pure — assembles, does not commit.

   The speculative db is `(d/db-with db0 acc)` over the *whole*
   accumulated tx-data each step — faithful tempid resolution across
   fragments, at O(steps^2) `d/db-with` calls. kontor's processes
   are short (O(periods)); the cost is negligible and the
   faithfulness avoids a duplicate-tempid-entity footgun.

   Returns `{:db <speculative-db> :tx-data <accumulated> :ctx <ctx>}`."
  [db0 ctx steps]
  (loop [ctx ctx, acc [], queue (vec steps)]
    (let [speculative (if (seq acc) (d/db-with db0 acc) db0)]
      (if (empty? queue)
        {:db speculative :tx-data acc :ctx ctx}
        (let [r    (normalize ((first queue) speculative ctx) ctx)
              frag (kbt/strip-tx-meta (:tx-data r))]
          (recur (:ctx r)
                 (into acc frag)
                 (into (vec (:steps r)) (rest queue))))))))

(defn run-process
  "Run a process as one atomic, validated transaction.

   `opts`:
     :steps     — sequence of step fns (required)
     :ctx       — initial context map threaded through steps (default {})
     :vt-from   — process valid-from (optional; one outer `with-vt`)
     :vt-to     — process valid-to   (optional; requires :vt-from)
     :dry-run?  — assemble + return `{:db :tx-data}` without committing
     :commit    — `(fn [conn tx-data])` commit fn; default
                  `kontor.validation/transact-with-validation`
                  (datalog invariants + the structural `:db.fn/call`
                  gate). Override for tests or to bypass the gate.

   Serializes on `conn`. Returns the commit fn's value (a tx-report),
   `nil` for an empty process, or — under `:dry-run?` —
   `{:db <speculative-db> :tx-data <assembled-tx-data>}`."
  [conn {:keys [steps ctx vt-from vt-to dry-run? commit]
         :or   {ctx {} commit validation/transact-with-validation}}]
  (locking conn
    (let [{:keys [db tx-data]} (run-steps (d/db conn) ctx steps)
          tx-data (cond
                    (and vt-from vt-to) (kbt/with-vt tx-data vt-from vt-to)
                    vt-from             (kbt/with-vt tx-data vt-from)
                    :else               tx-data)]
      (cond
        dry-run?            {:db db :tx-data tx-data}
        (empty? tx-data)    nil
        :else               (commit conn tx-data)))))
