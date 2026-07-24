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
  ;; A POSTING is sealed by :kontor.posting/posted-at; the containing
  ;; TRANSACTION is sealed by :kontor.transaction/posted-at. Both must be
  ;; protected — an upsert that reuses a posted transaction's identity (its
  ;; unique :external-id) would otherwise silently pollute the sealed entry
  ;; with extra postings + a rewritten narration (note 198 R3-REVNUM-4).
  (let [e (d/pull db [:kontor.posting/posted-at :kontor.transaction/posted-at] eid)]
    (or (some? (:kontor.posting/posted-at e))
        (some? (:kontor.transaction/posted-at e)))))

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

(def post-posting-mutable-attrs
  "Attributes that may legitimately be written to an ALREADY-POSTED entity.

   Sealing (ADR-007) freezes what a posted entry SAYS — its amount, account,
   commodity, entity, partner, dates. Reconciliation state is different: it
   records how a line has since been MATCHED against other lines, and it
   necessarily changes after posting. Odoo draws the same line — posted move
   lines are immutable for accounting content, while `amount_residual` /
   `full_reconcile_id` / `matching_number` are reconciliation fields updated by
   `account.partial.reconcile` long after the move is posted, and are excluded
   from the inalterability hash.

   Writing one of these does not alter what was booked, so it is not a sealing
   violation. Everything else on a posted entity — including AUGMENTING it with
   a previously-absent accounting attribute — remains refused (note 198 G1).

   note 198 Tier 2."
  #{:kontor.posting/amount-residual
    :kontor.posting/full-reconcile
    :kontor.posting/matching-number})

(defn- unique-identity-attrs
  "The set of attributes declared `:db.unique/identity` in the live schema —
   datahike UPSERTS an entity-map carrying one of these onto the existing
   entity that already holds that value, even when the map's `:db/id` is a
   fresh tempid."
  [db]
  (into #{}
        (keep (fn [[a spec]]
                (when (and (keyword? a) (map? spec)
                           (= :db.unique/identity (:db/unique spec)))
                  a)))
        (d/schema db)))

(defn- effective-target-eid
  "The EXISTING entity an entity-map actually writes to: its resolvable
   `:db/id`, or — for a tempid/new `:db/id` — the entity a `:db.unique/identity`
   attribute in the map upserts onto. nil for a genuinely new entity."
  [db uid-attrs tx]
  (or (resolvable-eid db (:db/id tx))
      (some (fn [[a v]]
              (when (and (not= a :db/id) (contains? uid-attrs a))
                (try (:db/id (d/entity db [a v]))
                     (catch #?(:clj Exception :cljs :default) _ nil))))
            tx)))

(defn find-silent-modifications
  "Return {:tx :eid :attr :old :new} for every entity-map assertion in
   `tx-data` that would silently mutate a posted entity: either CHANGING an
   already-present attribute value (an in-place edit of sealed data) OR
   AUGMENTING the entity with a previously-ABSENT attribute (which still
   rewrites the sealed entry's audit meaning — note 198 G1). Re-asserting the
   SAME value is a no-op and not reported. The target is resolved through
   `:db.unique/identity` upserts, not only an explicit eid (note 198
   R3-REVNUM-4)."
  [db tx-data]
  (let [uid-attrs (unique-identity-attrs db)]
    (vec
     (for [tx tx-data
           :when (entity-map? tx)
           :let  [eid (effective-target-eid db uid-attrs tx)] ; nil for genuinely-new entities
           :when (and eid (posted? db eid))
           [attr new-val] tx
           :when (and (not= attr :db/id)
                      ;; reconciliation state legitimately changes post-posting
                      (not (contains? post-posting-mutable-attrs attr)))
           :let  [cur (get (d/pull db [attr] eid) attr)]
           ;; report a CHANGE (cur present + differs) OR an AUGMENTATION
           ;; (cur absent); a same-value re-assert is (not= v v) = false.
           :when (not= (->eid db cur) (->eid db new-val))]
       {:tx tx :eid eid :attr attr :old cur :new new-val}))))

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
