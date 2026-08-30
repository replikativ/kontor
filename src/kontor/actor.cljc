(ns kontor.actor
  "Actor identity on ledger writes — ADR-150.

   ## The defect this closes

   Every `…-uid` attribute in the kernel is a `:db.type/ref`, and until
   ADR-150 there was no entity type in kontor for those refs to point at.
   `:kontor.audit/write-uid` had zero writers and zero readers repo-wide;
   `:kontor.transaction/posted-by` had zero writers. Worse, the facade
   CLAUDE.md designates as \"start here for any new business write\"
   (`kontor.book`) rebuilt its transaction map from a fixed key list, so an
   actor could not be threaded through it *at all* — the option was not
   optional, it was absent.

   The consequences were not cosmetic. `:no-self-approval`
   (`kontor.workflow.status-machine`, ADR-038) compares the transition actor
   against the entity's `:kontor.audit/create-uid`; with either side nil it
   returned \"no violation\", so **four-eyes failed OPEN** exactly when the
   actor was unrecorded. And because the de-facto convention was an opaque
   STRING in a ref slot, `\"bob\"` in one transaction and `\"bob\"` in the
   next minted two DIFFERENT phantom entities — so even when both sides were
   populated, `(= creator actor)` was false and the rule never fired.
   `modules/expense`'s own test suite works around this by using
   `:kontor.partner` entities as \"stand-ins for `:kontor.audit/create-uid`\".

   ## Actor, not user

   `:kontor.actor/*` (see `kontor.schema`) is deliberately NOT
   `:kontor.user/*`. kontor does not authenticate and does not authorize —
   that boundary is explicit in the schema and stays intact. What an audit
   trail needs is narrower and different: the party an internal control
   attributes an action to. That party is often not a person (a bank
   importer, an ADR-032 schedule, an ADR-050 sweeper), which is why
   `:kontor.actor/kind` exists, and the consumer's identity store stays the
   source of truth behind `:kontor.actor/external-ref`.

   ## Using it

       (actor/register-actor! conn {:uid \"sarah\" :name \"Sarah Weber\"
                                    :kind :person
                                    :external-ref \"oidc|4f3a…\"})

       (book/sell! conn {:debit-account ar :credit-account rev
                         :amount 1000 :commodity :EUR
                         :actor \"sarah\"})

   `:actor` accepts an eid, a lookup-ref, a uuid, a keyword or the bare
   `:kontor.actor/uid` string; [[->ref]] coerces all of them to
   `[:kontor.actor/uid …]`. Because that attribute is
   `:db.unique/identity`, datahike REFUSES an unregistered actor rather than
   minting a phantom — the coercion is fail-closed by construction, and
   pure, so it works in the builders and in the browser.

   The stamp lands on three attributes (Odoo's create/write convention):
   `:kontor.transaction/posted-by` (who sealed it — GoBD *Bearbeiter*),
   `:kontor.audit/create-uid` (who created it — what `:no-self-approval`
   compares a later approver against) and `:kontor.audit/write-uid` (last
   logical writer; equal to create-uid at creation).

   ## Requiring it

   `(require-actor-on-posted! conn)` installs an ADR-038
   `:kontor.approval-policy/rule :requires-actor` row. From then on
   [[assert-actor-on-posted!]] — composed into the gate's
   `validate-and-apply` and into `kontor.governance/validate-report` —
   REFUSES any entry sealed without an actor.

   ## What is on by default, precisely

   `:requires-actor` is off by default: a kernel cannot decide a
   consumer's control environment, and a single-operator bookkeeping db
   legitimately has no actor concept. One call turns it on.

   **That is not true of the approval policies as a whole, and this
   docstring used to imply it was.** `kontor.core/install-schema!` seeds
   `:no-self-approval` on kernel COMPLIANCE edges in every kontor
   database — legal-hold release (`legal_hold.cljc`), audit-doc privilege
   waiver (`audit_doc.clj`) and DSAR fulfillment (`dsar.clj`). Those are
   on from the first write, without any opt-in. What is NOT seeded is the
   BUSINESS-module set: `kontor-hr` payroll approval, `kontor-asset`
   disposal, `kontor-lease` termination and `kontor-expense` report
   approval each seed their own policies when their module schema is
   installed, and `kontor-procurement` seeds none at all (its docstring
   shows the row a consumer must transact).

   The split matters because `:no-self-approval` fails CLOSED (see
   `kontor.workflow.status-machine/check-policy`): a seeded policy on an
   edge whose entity never records `:kontor.audit/create-uid` makes that
   edge permanently impassable. That is the ADR-153 defect, and it is why
   every builder behind a seeded policy now REQUIRES its creator rather
   than stamping one when the caller happens to pass it.

   ## Attribution cannot be un-recorded

   [[assert-attribution-preserved!]] (gate) and
   `kontor.governance/attribution-violations` (writer) refuse any write
   that retracts `:kontor.audit/create-uid` or `:kontor.actor/uid`. Not
   policy-gated — see that fn's docstring. ADR-153."
  (:require [datahike.api :as d]
            [kontor.actor.ref :as aref]))

;; ============================================================================
;; Coercion — re-exported from the zero-dependency pure half
;; (`kontor.actor.ref`) so the `*-tx-data` builders, which must not require
;; datahike, can coerce an `:actor` option without depending on this ns.
;; ============================================================================

(def canonical-kinds
  "See `kontor.actor.ref/canonical-kinds`."
  aref/canonical-kinds)

(def ->ref
  "See `kontor.actor.ref/->ref` — pure coercion of a friendly actor spec
   (string / keyword / uuid / eid / lookup-ref) to a datahike reference."
  aref/->ref)

(def actor-stamp-attrs
  "See `kontor.actor.ref/actor-stamp-attrs`."
  aref/actor-stamp-attrs)

(def stamp
  "See `kontor.actor.ref/stamp` — stamp an actor onto a transaction map."
  aref/stamp)

;; ============================================================================
;; Registration (ADR-068 — pure builder + `!` wrapper)
;; ============================================================================

(defn actor-tx-data
  "Pure tx-data for one `:kontor.actor` entity.

   Required: `:uid`. Optional: `:name`, `:kind` (see [[canonical-kinds]]),
   `:external-ref`, `:person` (ref), `:active`, `:tempid`.

   Idempotent by construction: `:kontor.actor/uid` is
   `:db.unique/identity`, so re-registering the same uid updates the
   existing actor instead of creating a second one."
  [{:keys [uid name kind external-ref person active tempid]}]
  (when-not (and (string? uid) (seq uid))
    (throw (ex-info "kontor.actor: :uid is required and must be a non-empty string"
                    {:type :kontor.actor/invalid-actor :uid uid})))
  [(cond-> {:db/id           (or tempid -1)
            :kontor.actor/uid uid}
     name         (assoc :kontor.actor/name name)
     kind         (assoc :kontor.actor/kind kind)
     external-ref (assoc :kontor.actor/external-ref external-ref)
     person       (assoc :kontor.actor/person person)
     (some? active) (assoc :kontor.actor/active active))])

(defn register-actor!
  "Register (or update) an actor. See [[actor-tx-data]] for the options.

   Plain `d/transact` rather than the gate: an actor carries no postings,
   so none of the gate's accounting validators have anything to say about
   it, and routing registration through the gate would make bootstrapping
   an empty db depend on the gate being wired."
  [conn opts]
  (d/transact conn (actor-tx-data opts)))

(defn register-actors!
  "Register several actors in one transaction."
  [conn specs]
  (d/transact conn (into [] (map-indexed (fn [i s] (first (actor-tx-data (assoc s :tempid (- -1 i))))))
                         specs)))

;; ============================================================================
;; Reads
;; ============================================================================

(defn resolve-actor
  "Entity id of the actor `spec` denotes, or nil when it resolves to
   nothing. Does NOT throw — for the throwing path, let datahike resolve
   the lookup-ref [[->ref]] produces."
  [db spec]
  (when-let [r (->ref spec)]
    (try (:db/id (d/entity db r))
         (catch #?(:clj Exception :cljs :default) _ nil))))

(defn actor
  "Pull the actor `spec` denotes as a plain map, or nil."
  [db spec]
  (when-let [eid (resolve-actor db spec)]
    (d/pull db [:db/id :kontor.actor/uid :kontor.actor/name :kontor.actor/kind
                :kontor.actor/external-ref :kontor.actor/active]
            eid)))

(defn actor-uid
  "The `:kontor.actor/uid` string for an actor ref — the value an audit
   report prints. nil when `spec` does not resolve to an actor (including
   the pre-ADR-150 phantom entities, which carry no attributes at all)."
  [db spec]
  (:kontor.actor/uid (actor db spec)))

(defn inactive?
  "True iff `spec` resolves to an actor whose `:kontor.actor/active` is
   explicitly false. An unset flag means active (absent = active), and an
   unresolvable spec is not 'inactive' — it is *unknown*, which
   [[assert-actor-on-posted!]] reports separately."
  [db spec]
  (false? (:kontor.actor/active (actor db spec))))

;; ============================================================================
;; Normalising the 296 — opaque `…-uid` strings become real actors
;; ============================================================================
;;
;; See `kontor.actor.ref` for the shape of the defect. The fix has to work
;; WITHOUT touching the 296 call sites, for two reasons: they are spread
;; across the kernel, eight companion modules and six l10n modules, and —
;; more importantly — a consumer's code is full of them too, and a kernel
;; upgrade must not require a consumer-wide sweep to stop corrupting its own
;; audit trail. So the normalisation lives in the gate, where every write
;; passes exactly once.

;; Forward-declared: the permissive/strict switch is the `:requires-actor`
;; policy, which is defined below with the rest of the policy surface —
;; keeping that surface in one block reads better than hoisting one predicate
;; above the normaliser that consults it.
(declare actor-required?)

(def unregistered-kind
  "`:kontor.actor/kind` stamped on an actor the gate provisioned from a bare
   `…-uid` string rather than an explicit [[register-actor!]].

   It is deliberately visible: an auditor asking \"who are the actors on this
   book?\" must be able to tell the ones somebody deliberately enrolled from
   the ones that appeared because code wrote a string. Not an error — a
   single-operator book legitimately never registers anybody — but not the
   same thing either."
  :unregistered)

(defn- tempid-for [uid] (str "kontor.actor/" uid))

(defn resolve-uid-refs
  "Re-point every opaque actor string in a `…-uid` slot of `tx-data` at a
   real `:kontor.actor` entity, returning the rewritten tx-data.

   Two modes, chosen by whether the book has installed the
   `:requires-actor` policy:

   - **Permissive** (no policy — the default). An unknown uid is
     PROVISIONED: the returned tx-data gains
     `{:db/id \"kontor.actor/sarah\" :kontor.actor/uid \"sarah\"
       :kontor.actor/kind :unregistered}` and the `-uid` slots point at that
     tempid. Because `:kontor.actor/uid` is `:db.unique/identity`, the
     tempid upserts — the SECOND write of `\"sarah\"` resolves to the SAME
     entity as the first. That is the whole point: it is what makes
     `:no-self-approval` able to fire at all, since the rule compares eids
     and two phantoms never compared equal.

   - **Strict** (`:requires-actor` installed). An unknown uid is REFUSED
     (`:kontor.actor/unknown-actor`) instead of provisioned. A book that has
     declared it cares who acts does not get to invent actors as a side
     effect of a typo — `\"sarah\"` and `\"sarha\"` must not both silently
     become people.

   Either way no phantom is left behind, which is the invariant: after this
   runs, every `…-uid` datom in the tx-data points at an entity that pulls
   to something an auditor can read.

   **Scope, stated plainly:** that invariant covers writes that pass through
   the gate (`kontor.gate/transact-with-validation`, and pg-datahike's
   `:tx-wrap`, which routes through `kontor.validation/validate-and-apply`).
   A bare `d/transact` bypasses it and can still mint a phantom. This is the
   same boundary every other kernel guarantee has — sealing, period locks
   and the sum-to-zero invariant are all gate-enforced — and it is the
   reason `kontor.book` / `kontor.posting` are the documented write path
   rather than one option among several."
  [db tx-data]
  (let [uids (aref/collect-uid-strings tx-data)]
    (if (empty? uids)
      tx-data
      (let [known?  (into {} (map (fn [u] [u (some? (resolve-actor db u))])) uids)
            unknown (remove known? uids)]
        (when (and (seq unknown) (actor-required? db))
          (throw (ex-info
                  (str "kontor.actor: unregistered actor(s) "
                       (pr-str (vec (sort unknown)))
                       " — this book has an active :kontor.approval-policy/rule "
                       ":requires-actor, so an actor must be enrolled with "
                       "kontor.actor/register-actor! before it can act. Provisioning "
                       "one from a bare string would let a typo become a person, and "
                       "an internal control cannot be built on actors that appear by "
                       "accident. (ADR-150)")
                  {:type :kontor.actor/unknown-actor :uids (vec (sort unknown))})))
        ;; A DEACTIVATED actor must not be able to act. This check has to live
        ;; here rather than in `assert-actor-on-posted!`: by the time that runs,
        ;; the normaliser has already rewritten the actor slot to a tempid
        ;; string, and a tempid is indistinguishable from the legitimate
        ;; create-the-actor-inline case — so the uid, which is the only thing
        ;; that can be looked up, is no longer visible there. Policy-gated for
        ;; the same reason as the requirement itself: deactivation is only
        ;; meaningful to a book that has declared it cares who acts.
        (when-let [dead (and (actor-required? db) (seq (filter #(inactive? db %) uids)))]
          (throw (ex-info
                  (str "kontor.actor: deactivated actor(s) " (pr-str (vec (sort dead)))
                       " cannot act. Deactivation is not deletion — the historical "
                       "refs keep resolving, which is what preserves the audit trail "
                       "— but a NEW action must not be attributed to an actor the "
                       "book has retired. Re-activate the actor, or attribute the "
                       "action to whoever is actually performing it. (ADR-150)")
                  {:type :kontor.actor/inactive-actor :uids (vec (sort dead))})))
        ;; A uid that ALREADY resolves becomes a plain lookup-ref; only an
        ;; unknown one gets a tempid plus a provisioning map.
        ;;
        ;; Do not "simplify" this by giving every uid a tempid and letting
        ;; `:db.unique/identity` upsert. It looks equivalent and is not:
        ;; datahike assigns the tempid a fresh eid before it discovers the
        ;; upsert target, and when the same tempid also sits in a ref slot the
        ;; two resolutions collide — `:transact/upsert`, "resolves both to 899
        ;; and 901". Found by a mid-life-import case: `:actor "importer"` with
        ;; a caller-supplied `:kontor.audit/create-uid "original-author"`,
        ;; where both actors were registered.
        ;;
        ;; It is also less churn: a registered actor's uid datom is not
        ;; re-asserted on every single write that mentions them.
        (into (aref/rewrite-uid-strings
               tx-data
               (fn [u] (if (known? u) [:kontor.actor/uid u] (tempid-for u))))
              (map (fn [u] {:db/id             (tempid-for u)
                            :kontor.actor/uid  u
                            :kontor.actor/kind unregistered-kind}))
              (sort unknown))))))          ; sorted → deterministic tx-data

;; ============================================================================
;; Backfilling attribution on an existing book — ADR-153
;; ============================================================================

(defn entities-missing-create-uid
  "Every entity in `db` that carries `probe-attr` but no
   `:kontor.audit/create-uid`. `probe-attr` is whatever attribute
   identifies the entity family you are backfilling
   (`:kontor.audit-doc/code`, `:kontor.dsar-request/external-id`,
   `:kontor.payroll-run/code`, …) — the kernel has no entity-type→attribute
   registry, and inventing one to serve a migration would be the tail
   wagging the dog."
  [db probe-attr]
  (vec
   (sort
    (d/q '[:find [?e ...]
           :in $ ?probe
           :where
           [?e ?probe _]
           [(missing? $ ?e :kontor.audit/create-uid)]]
         db probe-attr))))

(defn backfill-create-uid-tx-data
  "Pure tx-data for ONE auditable commit that stamps
   `:kontor.audit/create-uid` on entities that have none.

   Required `:uid` — the actor the backfilled attribution points at.
   Targets come from `:entities` (explicit eids) and/or `:probe-attrs`
   (each expanded through [[entities-missing-create-uid]]).

   The actor is provisioned with `:kontor.actor/kind :unregistered` when
   the uid is new, which is the whole point: an auditor asking \"who
   created this document?\" must be able to tell an answer that was
   RECORDED from one that was RECONSTRUCTED during a migration. Do not
   reuse a real person's uid here — that would launder a guess into an
   attribution. If the uid already exists the kind is left alone (the
   caller has deliberately enrolled it).

   Deliberately NOT a `:legacy` flag or a nil-creator exemption on
   `:no-self-approval`. Either of those puts the decision \"is this
   entity's creator knowable?\" inside the control, where it becomes a
   permanent bypass that any new write can also claim. A backfill is a
   one-off commit an auditor can see, diff and date."
  [db {:keys [uid name entities probe-attrs]}]
  (when-not (and (string? uid) (seq uid))
    (throw (ex-info "kontor.actor: :uid is required for a backfill"
                    {:type :kontor.actor/invalid-actor :uid uid})))
  (let [targets (into (vec entities)
                      (mapcat #(entities-missing-create-uid db %))
                      probe-attrs)
        targets (vec (distinct targets))
        known?  (some? (resolve-actor db uid))
        tempid  (tempid-for uid)]
    (if (empty? targets)
      []
      (into [(cond-> {:db/id tempid :kontor.actor/uid uid}
               name          (assoc :kontor.actor/name name)
               (not known?)  (assoc :kontor.actor/kind unregistered-kind))]
            (map (fn [e] [:db/add e :kontor.audit/create-uid tempid]))
            targets))))

(defn backfill-create-uid!
  "Transact what [[backfill-create-uid-tx-data]] builds — one commit, so
   the audit chain carries the whole migration as a single dated event.

   Plain `d/transact` for the same reason [[register-actor!]] uses it:
   the write carries no postings, so the gate's accounting validators have
   nothing to say about it, and a book being migrated should not need the
   gate wired to become approvable."
  [conn opts]
  (d/transact conn (backfill-create-uid-tx-data (d/db conn) opts)))

;; ============================================================================
;; The policy — "a posted entry must name its actor"
;; ============================================================================

(def requires-actor-policy
  "The ADR-038 `:approval-policy` row that turns the requirement on. Uses
   the existing policy substrate rather than a new switch entity so the
   requirement is data, is queryable next to every other control, and can
   be scoped per-org via `:kontor.approval-policy/applies-to-org`."
  {:kontor.approval-policy/entity-type      :transaction
   :kontor.approval-policy/facet            :kontor.transaction/state
   :kontor.approval-policy/transition-from  :draft
   :kontor.approval-policy/transition-to    :posted
   :kontor.approval-policy/rule             :requires-actor
   :kontor.approval-policy/active           true
   :kontor.approval-policy/note
   "Every entry sealed in this book must record the actor that sealed it
    (:kontor.transaction/posted-by). GoBD requires the Bearbeiter; SOX
    requires attributable postings. ADR-150."})

(defn require-actor-on-posted-tx-data
  "Pure tx-data installing [[requires-actor-policy]], optionally scoped to
   one `:kontor.entity` via `:org`."
  ([] (require-actor-on-posted-tx-data {}))
  ([{:keys [org]}]
   [(cond-> requires-actor-policy
      org (assoc :kontor.approval-policy/applies-to-org org))]))

(defn require-actor-on-posted!
  "Turn on the requirement that every sealed entry names its actor.
   Idempotent (`:kontor.approval-policy/identity` is a unique composite
   tuple). Returns the tx-report.

   Off by default: a kernel cannot decide a consumer's control
   environment, and a single-operator bookkeeping db legitimately has no
   actor concept. A company subject to GoBD / NF525 / SOX calls this once
   at install time."
  ([conn] (require-actor-on-posted! conn {}))
  ([conn opts] (d/transact conn (require-actor-on-posted-tx-data opts))))

(defn actor-required?
  "True iff an active `:requires-actor` policy row is installed."
  [db]
  (some? (d/q '[:find ?p .
                :where
                [?p :kontor.approval-policy/rule :requires-actor]
                [?p :kontor.approval-policy/active true]]
              db)))

;; ============================================================================
;; The validator (composed into the gate)
;; ============================================================================

(defn- seals-transaction?
  "True iff this entity-map asserts the seal on a transaction — either the
   `:kontor.transaction/posted-at` marker (what ADR-007 sealing keys on) or
   `:kontor.transaction/state :posted`."
  [m]
  (or (contains? m :kontor.transaction/posted-at)
      (= :posted (:kontor.transaction/state m))))

(defn sealed-without-actor
  "Every entity-map in `tx-data` that seals a transaction without naming an
   actor, plus every one naming an actor that does not resolve. Returns a
   vector of `{:tx <form> :reason <kw> :actor <spec>}`; empty when clean.
   Pure over (db, tx-data) — exposed so a caller can inspect rather than
   throw.

   Tuple-shaped seals (`[:db/add e :kontor.transaction/posted-at …]`, which
   is how a SQL client writing through pg-datahike expresses it) are checked
   against the db: the actor may already be on the entity from the draft.

   A string or negative-int actor slot is accepted as a tempid, because
   creating the actor inline in the same tx-data is legitimate — and, on the
   gate path, is what [[resolve-uid-refs]] has already rewritten every actor
   reference INTO. That is why the unregistered- and deactivated-actor
   refusals live in `resolve-uid-refs` and not here: this function can no
   longer see a uid to look up. Do not re-add them here on the assumption
   that it can."
  [db tx-data]
  (vec
   (concat
    (for [form  tx-data
          :when (and (map? form) (seals-transaction? form))
          :let  [spec (:kontor.transaction/posted-by form)]
          :let  [r (cond
                     (nil? spec)          {:reason :kontor.actor/no-actor-recorded}
                     ;; a tempid (string/negative int) means the actor entity is
                     ;; being created in this same tx-data — legitimate.
                     (or (string? spec)
                         (and (number? spec) (neg? spec))) nil
                     (nil? (resolve-actor db spec))
                     {:reason :kontor.actor/unknown-actor :actor spec}
                     (inactive? db spec)
                     {:reason :kontor.actor/inactive-actor :actor spec}
                     :else nil)]
          :when r]
      (assoc r :tx form))
    (for [form  tx-data
          :when (and (vector? form)
                     (= :db/add (first form))
                     (#{:kontor.transaction/posted-at} (nth form 2 nil)))
          :let  [eid (second form)]
          :when (and (integer? eid) (pos? eid)
                     (nil? (:kontor.transaction/posted-by
                            (d/pull db [:kontor.transaction/posted-by] eid))))]
      {:tx form :reason :kontor.actor/no-actor-recorded}))))

;; ============================================================================
;; Attribution is non-retractable — ADR-153
;;
;; `:no-self-approval` fails CLOSED on a nil creator (ADR-150), which makes
;; the creator datom a load-bearing part of an internal control rather than
;; a decoration. A fail-closed control has a denial-of-service dual: whoever
;; can make the creator nil can make the entity permanently unapprovable.
;;
;; That is reachable from an ORDINARY lifecycle event, not only from legacy
;; rows. `datahike.db.transaction/retract-entity` collects every ref datom
;; POINTING AT the entity and retracts them too, so a single
;; `[:db/retractEntity <actor>]` — "clean up the actor who left" — nils
;; `:kontor.audit/create-uid` on every entity that actor ever created. The
;; audit trail records a routine cleanup; the four-eyes control on all of
;; that actor's documents is gone. Under the PERMISSIVE reading of the rule
;; the same move defeats four-eyes outright (nil creator → no violation →
;; self-approval succeeds); under the fail-closed reading it strands the
;; documents instead. Neither is acceptable, and the fix is the same one:
;; the creator datom must not be retractable.
;;
;; Deactivation is the modelled path (`:kontor.actor/active false`, which
;; `resolve-uid-refs` already refuses to let act) — `actor-test`'s
;; "deactivation is not deletion" asserted this and nothing enforced it.
;; ============================================================================

(def ^:private whole-entity-destructive-ops
  "datahike tx-op keywords that destroy an entire existing entity (eid in
   slot 1). Mirrors `kontor.compliance.legal-hold/destructive-ops`."
  #{:db/purge :db.purge/entity :db/retractEntity :db.fn/retractEntity})

(def ^:private attr-destructive-ops
  "datahike tx-op keywords that destroy one attribute of an existing
   entity (eid in slot 1, attr in slot 2)."
  #{:db.purge/attribute :db/retract})

(def non-retractable-attrs
  "Attributes whose retraction destroys audit attribution and is therefore
   refused outright.

   `:kontor.audit/create-uid` is what `:no-self-approval` compares an
   approver against. `:kontor.actor/uid` is the actor's identity — losing it
   is how a `:db/retractEntity` on the actor presents, and it is also what
   makes every historical ref to that actor resolve to an attribute-less
   phantom again (the pre-ADR-150 defect).

   NOT included: `:kontor.audit/write-uid` (last-writer, advisory) and
   `:kontor.transaction/posted-by` (already covered for posted entries by
   the sealing guard, which refuses any retraction against a posted row).
   Scope is deliberately the attributes a CONTROL reads, not everything
   audit-shaped."
  #{:kontor.audit/create-uid :kontor.actor/uid})

(defn- actor-entity?
  "True iff `target` (an eid or lookup-ref) resolves to something carrying
   `:kontor.actor/uid` in `db`."
  [db target]
  (boolean
   (try (some? (:kontor.actor/uid (d/pull db [:kontor.actor/uid] target)))
        (catch #?(:clj Exception :cljs :default) _ false))))

(defn attribution-destroying-writes
  "Every form in `tx-data` that would destroy audit attribution. Returns a
   vector of `{:tx :eid :attr :reason}`; empty when clean. Pure over
   (db, tx-data) — exposed so a caller can inspect rather than throw.

   Covers datahike's whole purge + retract surface: the whole-entity ops
   (`:db/purge` / `:db.purge/entity` / `:db/retractEntity` /
   `:db.fn/retractEntity`) when the target is a `:kontor.actor`, the
   attribute-level ops (`:db/retract` / `:db.purge/attribute`) on a
   [[non-retractable-attrs]] member, and the entity-map nil-retract
   (`{:db/id e :kontor.audit/create-uid nil}`) that the ADR-140 red-team
   found is the shape most scans miss.

   Short-circuits on tx-data with no destructive form, so the ordinary
   append-only write pays one `some`."
  [db tx-data]
  (let [destructive? (fn [f]
                       (or (and (vector? f)
                                (or (whole-entity-destructive-ops (first f))
                                    (attr-destructive-ops (first f))))
                           (and (map? f)
                                (some (fn [a] (and (contains? f a) (nil? (get f a))))
                                      non-retractable-attrs))))]
    (if-not (some destructive? tx-data)
      []
      (vec
       (keep
        (fn [f]
          (cond
            (and (vector? f) (whole-entity-destructive-ops (first f)))
            (when (actor-entity? db (second f))
              {:tx f :eid (second f) :reason :kontor.actor/actor-deletion-refused})

            (and (vector? f) (attr-destructive-ops (first f))
                 (non-retractable-attrs (nth f 2 nil)))
            {:tx f :eid (second f) :attr (nth f 2 nil)
             :reason :kontor.actor/attribution-retraction-refused}

            (map? f)
            (when-let [a (some (fn [a] (when (and (contains? f a) (nil? (get f a))) a))
                               non-retractable-attrs)]
              {:tx f :eid (:db/id f) :attr a
               :reason :kontor.actor/attribution-retraction-refused})))
        tx-data)))))

(defn assert-attribution-preserved!
  "Throw when `tx-data` would destroy audit attribution — the
   pre-resolution half of the ADR-153 guard, composed into
   `kontor.validation/validate-and-apply`. The mandatory writer-side half
   is `kontor.governance/attribution-violations`.

   NOT policy-gated. `:requires-actor` is a statement about how a book
   WRITES; this is a statement about what a book may DESTROY, and the
   answer does not depend on whether the consumer has opted into actor
   discipline: a creator datom that exists was recorded for a reason, and a
   later transaction does not get to un-record it. (A book with no actor
   concept has no `:kontor.audit/create-uid` datoms to retract, so it never
   reaches this.)"
  [db tx-data]
  (when-let [v (seq (attribution-destroying-writes db tx-data))]
    (throw (ex-info
            (str "kontor.actor: refused — this write would destroy audit "
                 "attribution " (pr-str (mapv #(select-keys % [:reason :eid :attr]) v))
                 ". :kontor.audit/create-uid is what :no-self-approval compares an "
                 "approver against, and the rule fails CLOSED (ADR-150), so nilling "
                 "it does not loosen four-eyes — it makes the entity permanently "
                 "unapprovable. Note that :db/retractEntity on a :kontor.actor does "
                 "this to EVERY entity that actor created, because datahike retracts "
                 "the inbound ref datoms too. Deactivation is the modelled path: "
                 "(kontor.actor/register-actor! conn {:uid … :active false}) retires "
                 "an actor while every historical ref keeps resolving. (ADR-153)")
            {:type       :kontor.actor/attribution-destroyed
             :violations (vec v)}))))

(defn assert-actor-on-posted!
  "Throw when an entry is sealed without a resolvable, active actor AND an
   active `:requires-actor` policy is installed. No-op otherwise.

   Ordering matters for cost: this runs on EVERY gate write, so the cheap
   PURE scan of `tx-data` runs first and the policy query only happens once
   a seal is actually present. A db that never installs the policy pays one
   `contains?` per entity-map."
  [db tx-data]
  (when-let [v (seq (sealed-without-actor db tx-data))]
    (when (actor-required? db)
      (throw (ex-info
              (str "kontor.actor: an entry was sealed without a valid actor — "
                   (pr-str (mapv #(select-keys % [:reason :actor]) v))
                   ". An active :kontor.approval-policy/rule :requires-actor is "
                   "installed, so every posted entry must record who sealed it "
                   "(:kontor.transaction/posted-by). Pass :actor to the "
                   "kontor.book verb / kontor.posting/post-transaction!, and "
                   "register the actor first with "
                   "kontor.actor/register-actor!. (ADR-150)")
              {:type       :kontor.actor/actor-required
               :violations (vec v)}))))
  nil)
