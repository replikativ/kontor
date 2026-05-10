(ns datahike-accounting.sealing
  "Sealing: refuse silent retraction of `:posting/posted-at`-marked
   datoms. Per ADR-007 in doc/decisions.md:

     - A posting transitions from draft to posted by setting
       `:posting/posted-at`.
     - Once posted, the data may NOT be silently changed: a
       `[:db/retract eid attr v]` on any attribute of a posted entity
       is rejected.
     - The data MAY be deleted via an explicit `[:db/purge eid attr v]`
       (or full-entity purge) — the purge IS itself a recorded commit
       in datahike's commit DAG, so the chain self-documents the
       deletion. Right-to-erasure (GDPR Art. 17) is satisfiable
       without breaking the audit story.

   This is the *behavior* half of ADR-011's hybrid validation model;
   the *state* half lives in `validation.clj` (via the invariant lib).

   Implementation note: we inspect the proposed `tx-data` BEFORE
   handing it to `d/transact`. We treat any `[:db/retract eid attr v]`
   tuple where the entity has `:posting/posted-at` set as a violation.
   We do NOT inspect entity-map updates that *retract* by re-asserting
   a different value — datahike does not support that shape against
   a unique attribute, and our posted lifecycle uses tuple-form retracts
   when consumers actually mean to retract."
  (:require [datahike.api :as d]))

(defn- retract-tuple?
  "True iff tx is a `[:db/retract eid attr v]` 4-tuple."
  [tx]
  (and (vector? tx)
       (#{:db/retract :db.fn/retractAttribute :db.fn/retractEntity} (first tx))))

(defn- retracted-eid
  "Extract the entity-id from a retract tx tuple. Returns nil for
   shapes we don't recognize (entity-map retracts, db.fn/calls, etc.)."
  [tx]
  (when (retract-tuple? tx)
    (second tx)))

(defn- posted? [db eid]
  (some? (:posting/posted-at (d/pull db [:posting/posted-at] eid))))

(defn find-silent-retracts
  "Return a vector of {:tx <tx-form> :eid <eid>} for every retract in
   `tx-data` that targets a posted-at-marked entity in `db`. Empty if
   none — the happy case for `transact-with-validation`."
  [db tx-data]
  (vec
   (keep (fn [tx]
           (when-let [eid (retracted-eid tx)]
             (when (and (integer? eid) (posted? db eid))
               {:tx tx :eid eid})))
         tx-data)))

(defn assert-no-silent-retracts!
  "Throws ex-info with type :sealing/silent-retract-of-posted if
   `tx-data` includes a retract against any posted entity. Otherwise
   returns nil."
  [db tx-data]
  (let [violations (find-silent-retracts db tx-data)]
    (when (seq violations)
      (throw (ex-info "Sealing violation: silent retract of posted entries"
                      {:type        :sealing/silent-retract-of-posted
                       :violations  violations
                       :remediation
                       "Posted entries cannot be silently retracted. To
                        legitimately delete posted data (e.g. for a
                        right-to-erasure request), use `[:db/purge
                        eid attr v]` — that purge is itself a recorded
                        commit in datahike's commit DAG, so the audit
                        chain documents the deletion (ADR-007). To
                        correct an erroneous posting, write a reversing
                        transaction (a new transaction with
                        :transaction/reverses pointing back at the
                        original) — never an in-place edit."}))))
  nil)
