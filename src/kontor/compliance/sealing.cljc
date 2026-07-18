(ns kontor.compliance.sealing
  "Sealing: refuse silent retraction of `:kontor.posting/posted-at`-marked
   datoms. Per ADR-007 in doc/decisions.md:

     - A posting transitions from draft to posted by setting
       `:kontor.posting/posted-at`.
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
   handing it to `d/transact`. Two silent-mutation shapes are rejected
   against a posted entity:

     1. RETRACTS — any `[:db/retract …]`, `[:db/retractEntity eid]`,
        `[:db.fn/retractEntity eid]`, or `[:db.fn/retractAttribute …]`
        tuple targeting the entity. (Both the `:db/retractEntity` and
        the `:db.fn/retractEntity` op spellings; datahike accepts both,
        and missing the former let a posted posting be silently deleted
        — the A2 corruption vector, note on invariant red-teaming.)

     2. IN-PLACE EDITS — an entity-map `{:db/id <eid> attr v …}` that
        *changes* an already-present attribute value on a posted entity.
        datahike upserts cardinality-one attrs (internally retract+add),
        so `{:db/id p :kontor.posting/amount 9999M}` silently rewrites a
        sealed amount — the A4 corruption vector. Re-asserting the SAME
        value is a no-op and allowed; the draft→posted transition itself
        is not blocked because the entity is not yet posted when
        `:kontor.posting/posted-at` is first set."
  (:require [datahike.api :as d]))

(defn- retract-tuple?
  "True iff tx is a retract/retract-entity tuple (either op spelling)."
  [tx]
  (and (vector? tx)
       (#{:db/retract :db/retractEntity
          :db.fn/retractAttribute :db.fn/retractEntity} (first tx))))

(defn- retracted-eid
  "Extract the entity-id from a retract tx tuple. Returns nil for
   shapes we don't recognize (entity-map retracts, db.fn/calls, etc.)."
  [tx]
  (when (retract-tuple? tx)
    (second tx)))

(defn- posted? [db eid]
  (some? (:kontor.posting/posted-at (d/pull db [:kontor.posting/posted-at] eid))))

(defn find-silent-retracts
  "Return a vector of {:tx <tx-form> :eid <eid>} for every retract in
   `tx-data` that targets a posted-at-marked entity in `db`. Empty if
   none — the happy case for `transact-with-validation`."
  [db tx-data]
  (vec
   (keep (fn [tx]
           (when-let [eid (retracted-eid tx)]
             (when (and (integer? eid) (pos? eid) (posted? db eid))
               {:tx tx :eid eid})))
         tx-data)))

(defn- entity-map?
  "True iff tx is an entity-map with a :db/id (upsert/assert shape)."
  [tx]
  (and (map? tx) (contains? tx :db/id)))

(defn- ->eid
  "Normalize a ref-shaped value to a concrete eid where possible, so a
   ref re-asserted in a different shape (eid vs lookup-ref vs pulled
   `{:db/id n}`) doesn't read as a change. Scalars pass through."
  [db v]
  (cond
    (and (map? v) (contains? v :db/id)) (:db/id v)          ; pulled ref
    (vector? v)                         (or (:db/id (d/entity db v)) v) ; lookup-ref
    :else                               v))

(defn- resolvable-eid
  "Concrete eid for a `:db/id` that could reference an EXISTING entity
   (positive eid or lookup-ref). nil for tempids (negative int / string),
   new entities, or anything unresolvable — none of which can be posted."
  [db id]
  (when (or (and (integer? id) (pos? id))
            (and (vector? id) (keyword? (first id))))
    (try (:db/id (d/entity db id))
         (catch #?(:clj Exception :cljs :default) _ nil))))

(defn find-silent-modifications
  "Return {:tx :eid :attr :old :new} for every entity-map assertion in
   `tx-data` that CHANGES an already-present attribute value on a posted
   entity (a silent in-place edit of sealed data). Re-asserting the same
   value is a no-op and not reported."
  [db tx-data]
  (vec
   (for [tx tx-data
         :when (entity-map? tx)
         :let  [eid (resolvable-eid db (:db/id tx))]        ; nil for tempids/new entities
         :when (and eid (posted? db eid))
         [attr new-val] tx
         :when (not= attr :db/id)
         :let  [cur (get (d/pull db [attr] eid) attr)]
         :when (and (some? cur)
                    (not= (->eid db cur) (->eid db new-val)))]
     {:tx tx :eid eid :attr attr :old cur :new new-val})))

(defn assert-no-silent-retracts!
  "Throws ex-info with type :sealing/silent-retract-of-posted if
   `tx-data` includes a retract OR an in-place edit against any posted
   entity. Otherwise returns nil."
  [db tx-data]
  (let [violations (into (find-silent-retracts db tx-data)
                         (find-silent-modifications db tx-data))]
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
                        :kontor.transaction/reverses pointing back at the
                        original) — never an in-place edit."}))))
  nil)
