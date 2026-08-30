(ns kontor.governance
  "Post-resolution validation for GOVERNED stores — the report-based realization
   of the transact gate (ADR-118's deferred fix, now un-deferred as the
   governed-store path).

   A GOVERNED store registers `validate-report` as a `datahike.tx-preds`
   transaction predicate (via [[govern!]]), so EVERY committed write — local or
   remote (kabel) — is validated in the writer, mandatorily, on the FULLY
   RESOLVED tx-report `{:db-before :db-after :tx-data}` (real eids + added/retract
   flags). Because it sees the resolved delta it catches the corruption vectors
   the pre-resolution gate misses and closes the whole red-team battery (research
   note 193 / exp9):

   - **balance** — re-sum every transaction TOUCHED by the delta from `db-after`,
     per (entity, ledger, commodity); non-zero rejects. Delta-scoped (only
     touched txs), so cost is O(delta), independent of ledger size — the
     inductive double-entry property.
   - **sealing** — any RETRACTED datom whose entity was `:kontor.posting/posted-at`
     in `db-before` rejects. Sees `db-before` + retract flags, so it guards
     `:db/retractEntity` and in-place edits of posted rows — which the
     assertion-only attr/entity preds structurally cannot.
   - **period locks** — a posting whose accounting date lands in a soft-closed
     or hard-sealed period rejects, as does any write against a sealed period
     entity (ADR-014).
   - **legal hold** — any retraction against an entity in an active hold's
     scope rejects (ADR-049).
   - **state machine** — `:kontor.transaction/state` may only move along
     `allowed-transitions`, and `:posted` requires `:posted-at` (ADR-034).
   - **analytic distributions** — an account naming
     `:kontor.account/required-analytic-plans` must be fully distributed, and
     any plan a posting distributes to must total exactly 100%
     (ADR-022 / ADR-140).
   - **invariants** — the registered datalog `:invariant/rule` / `:invariant/query`
     set, run against the resolved report sources (post-resolution, so the
     `$empty+txs`-reconstruction fragility of the pre-resolution path is gone).

   The period / legal-hold / state-machine families were gate-only until
   ADR-140: the MANDATORY guard was strictly weaker than the bypassable one it
   backstops, so a raw `d/transact` could post into a sealed period or purge a
   held entity while the writer waved it through.

   `validate-report` throws `ex-info` (NOT an `Error`/`assert` — an Error crashes
   the datahike writer) to reject. It is pure over the report (no conn), so it is
   testable standalone and runs identically JVM + cljs.

   This does NOT replace the existing `kontor.gate` for non-governed callers; it
   is the authoritative path for stores that opt into governance."
  (:require [clojure.edn :as edn]
            [datahike.api :as d]
            [datahike.core :as dc]
            [kontor.analytic :as analytic]
            [kontor.compliance.legal-hold :as legal-hold]
            [kontor.compliance.period :as period]
            [kontor.money :as money]
            [kontor.resource.validate :as resource]
            [kontor.workflow.state-machine :as state-machine]
            [kontor.invariant :as inv]))

;; ============================================================================
;; balance (sum-to-zero), post-resolution
;; ============================================================================

(defn- posting-attr? [a]
  (and (keyword? a) (= "kontor.posting" (namespace a))))

(defn- touched-tx-eids
  "Transaction eids touched by the resolved report: for every posting eid that
   appears in the report's datoms, resolve its `:kontor.posting/transaction`
   from `db-after` (still present) or `db-before` (was retracted). O(delta)."
  [{:keys [db-before db-after tx-data]}]
  (let [posting-eids (into #{}
                           (comp (filter (fn [d] (posting-attr? (:a d)))) (map :e))
                           tx-data)]
    (into #{}
          (keep (fn [e]
                  (or (d/q '[:find ?t . :in $ ?p :where [?p :kontor.posting/transaction ?t]] db-after e)
                      (d/q '[:find ?t . :in $ ?p :where [?p :kontor.posting/transaction ?t]] db-before e))))
          posting-eids)))

(def ^:private non-balance-affecting-display-types
  "`:section` / `:note` postings are UI-only and carry no balance effect — the
   build-time validator excludes them from the sum
   (`kontor.posting.validate/balance-affecting?`). The governor must use the
   SAME rule or the two disagree about what balances: a well-formed entry
   carrying a `:note` line with a non-nil amount would commit through the gate
   and then be rejected by the writer."
  #{:section :note})

(defn transaction-postings
  "Postings of transaction `tx` as read from `db`, one map per posting:
   `{:posting :entity :ledger :commodity :amount :display-type}`. `:entity` and
   `:ledger` are nil when the attribute is absent — a nil ledger IS the primary
   book (ADR-021) and a nil entity IS the single-entity book (ADR-031), so the
   nil group is a real group, not a missing one.

   Binds `?p` so equal amounts on distinct postings are not collapsed by
   `:find`'s set semantics, and uses `get-else` rather than separate queries so
   a posting missing an optional attr is not dropped from the fold."
  [db tx]
  (->> (d/q '[:find ?p ?e ?l ?c ?amt ?dt :in $ ?tx :where
              [?p :kontor.posting/transaction ?tx]
              [?p :kontor.posting/commodity ?c]
              [?p :kontor.posting/amount ?amt]
              [(get-else $ ?p :kontor.posting/entity :__none) ?e]
              [(get-else $ ?p :kontor.posting/ledger :__none) ?l]
              [(get-else $ ?p :kontor.posting/display-type :product) ?dt]]
            db tx)
       (mapv (fn [[p e l c amt dt]]
               {:posting p
                :entity (when (not= e :__none) e)
                :ledger (when (not= l :__none) l)
                :commodity c :amount amt :display-type dt}))))

(defn balance-violations
  "Re-sum each delta-touched transaction from `db-after`, per
   **(entity, ledger, commodity)**. Returns a vector of
   `{:transaction :entity :ledger :commodity :sum}` for every non-zero group
   (empty = balanced).

   The triple is the invariant ADR-021 + ADR-031 actually state, and what the
   build-time validator has always enforced
   (`kontor.posting.validate/unbalanced-entity-ledger-commodities`). This
   predicate used to group by COMMODITY ALONE, which made the MANDATORY guard
   weaker than the bypassable one it exists to backstop: a raw `d/transact` of
   `+100 EUR` on the primary ledger against `−100 EUR` on the IFRS ledger
   (or against a different entity) netted to zero here and committed, leaving
   two parallel books each off by 100 — exactly the silently-wrong-numbers
   shape a governed store is registered to prevent. Note that grouping by the
   triple is strictly a REFINEMENT: any tx that balanced per triple also
   balanced per commodity, so nothing that passed before fails now.

   `:section` / `:note` display types are excluded, mirroring the build
   validator.

   Sums via `money/add-amount` (the cljc bigdec-aware fold) rather than
   datahike's `(sum ?amt)` aggregate: the aggregate adds with core `+`, which
   does not add cljs fress `Bigdec` values, so the datalog aggregate would make
   this validator JVM-only."
  [{:keys [db-after] :as report}]
  (vec
   (for [tx (touched-tx-eids report)
         [[e l c] rows] (->> (transaction-postings db-after tx)
                             (remove (comp non-balance-affecting-display-types
                                           :display-type))
                             (group-by (juxt :entity :ledger :commodity)))
         :let [s (reduce money/add-amount (money/zero-amount) (map :amount rows))]
         :when (not (money/amount-zero? s))]
     {:transaction tx :entity e :ledger l :commodity c :sum s})))

;; ============================================================================
;; sealing, post-resolution
;; ============================================================================

(defn sealing-violations
  "Every RETRACTED datom in the report whose entity had `:kontor.posting/posted-at`
   in `db-before`. Catches `:db/retract`, `:db/retractEntity`, and the destructive
   half of an in-place edit uniformly. Returns `{:eid :attr}` vector."
  [{:keys [db-before tx-data]}]
  (vec
   (for [dd tx-data
         :when (and (false? (:added dd))
                    (d/q '[:find ?x . :in $ ?e :where [?e :kontor.posting/posted-at ?x]]
                         db-before (:e dd)))]
     {:eid (:e dd) :attr (:a dd)})))

;; ============================================================================
;; Delta helpers shared by the report-based mirrors below
;; ============================================================================

(defn- delta-posting-eids
  "Posting eids ASSERTED by the delta (any `:kontor.posting/*` attribute).
   Post-resolution these are real eids in `db-after`."
  [{:keys [tx-data]}]
  (into #{} (comp (filter (fn [d] (and (:added d) (posting-attr? (:a d)))))
                  (map :e))
        tx-data))

(defn- retracted-eids
  [{:keys [tx-data]}]
  (into #{} (comp (filter (fn [d] (false? (:added d)))) (map :e)) tx-data))

;; ============================================================================
;; period locks, post-resolution
;;
;; These three families (period / legal-hold / state-machine) lived ONLY in
;; `kontor.validation/validate-and-apply` — the bypassable gate. A raw
;; `d/transact`, or any SQL/remote client not routed through the gate, could
;; post into a SEALED period, purge an entity under an active legal hold, or
;; drive `:kontor.transaction/state` :cancelled → :posted, and the mandatory
;; writer-side governor had nothing to say about it. Mirrored here so the
;; un-bypassable seam covers the same ground (ADR-140).
;; ============================================================================

(defn period-violations
  "Postings asserted by the delta whose accounting date falls inside a
   soft-closed or hard-sealed period. Returns
   `{:posting :transaction :valid-from :valid-from-source :periods}` rows.

   The anchor mirrors `kontor.compliance.period/find-violations`: the tx-meta
   `:db.valid/from` asserted in this same delta if present, otherwise the
   transaction's `:kontor.transaction/effective-date`. Periods are read from
   `db-before` so that a transaction which *closes* a period in the same write
   is judged against the state before its own lock — closing December must not
   reject the lock event itself.

   Deliberately STRONGER than the gate's pre-resolution scan, which keys on
   `:kontor.posting/account` appearing in a tx-data map and so lets an EDIT of
   an existing posting (adding a partner, a narration) inside a closed period
   through. Any `:kontor.posting/*` assertion counts here, which is what Odoo
   does — `_check_fiscal_lock_dates` fires on a write to any protected field of
   an existing move line, not only on creation."
  [{:keys [db-before db-after tx-data] :as report}]
  (let [meta-vf (some (fn [d] (when (and (:added d) (= :db.valid/from (:a d))) (:v d)))
                      tx-data)]
    (vec
     (for [p (delta-posting-eids report)
           :let [tx (d/q '[:find ?t . :in $ ?p :where [?p :kontor.posting/transaction ?t]]
                         db-after p)
                 eff (when tx
                       (d/q '[:find ?e . :in $ ?t :where
                              [?t :kontor.transaction/effective-date ?e]]
                            db-after tx))
                 vf  (or meta-vf eff)
                 j   (when tx
                       (d/q '[:find ?j . :in $ ?t :where
                              [?t :kontor.transaction/journal ?j]] db-after tx))
                 tag (or (d/q '[:find ?g . :in $ ?p :where
                                [?p :kontor.posting/period-tag ?g]] db-after p)
                         period/default-period-tag)
                 periods (when vf (period/closed-periods-covering db-before vf j tag))]
           :when (seq periods)]
       {:posting p :transaction tx :valid-from vf
        :valid-from-source (if meta-vf :tx-meta :effective-date)
        :periods periods}))))

(defn sealed-period-violations
  "Any datom in the delta — assertion OR retraction — whose entity was a
   `:kontor.period/sealed-at`-marked period in `db-before`. A sealed period is
   immutable, so even an assertion against it is refused (the pre-resolution
   `assert-no-write-on-sealed!` makes the same call)."
  [{:keys [db-before tx-data]}]
  (vec
   (distinct
    (for [dd tx-data
          :when (d/q '[:find ?x . :in $ ?e :where [?e :kontor.period/sealed-at ?x]]
                     db-before (:e dd))]
      {:eid (:e dd) :attr (:a dd)}))))

;; ============================================================================
;; legal hold, post-resolution
;; ============================================================================

(defn hold-violations
  "Every RETRACTED datom in the delta whose entity is in an active legal
   hold's scope (ADR-049). Post-resolution this is strictly broader than the
   pre-resolution `destructive-targets` scan, which had to enumerate datahike
   tx-op keywords (`:db/purge`, `:db/retractEntity`, …) and explicitly gave up
   on entity-map nil-retracts: a retraction is a retraction here, whatever
   syntax produced it. Short-circuits on a delta with no retractions, so the
   ordinary append-only write pays one `filter`."
  [{:keys [db-before] :as report}]
  (let [gone (retracted-eids report)]
    (if (empty? gone)
      []
      (let [held (legal-hold/entities-held? db-before gone)]
        (vec (for [e held]
               {:eid e :holds (legal-hold/holds-covering db-before e)}))))))

;; ============================================================================
;; transaction state machine, post-resolution
;; ============================================================================

(defn state-machine-violations
  "Illegal `:kontor.transaction/state` transitions in the delta, judged
   `db-before` → asserted value against
   `kontor.workflow.state-machine/allowed-transitions`, plus the
   `:posted`-requires-`:posted-at` rule (checked against `db-after`, so it
   holds however the caller ordered the datoms).

   Re-asserting the SAME state produces no datom, so an idempotent write is
   not a self-transition violation."
  [{:keys [db-before db-after tx-data]}]
  (vec
   (for [dd tx-data
         :when (and (:added dd) (= :kontor.transaction/state (:a dd)))
         :let [from (d/q '[:find ?s . :in $ ?e :where [?e :kontor.transaction/state ?s]]
                         db-before (:e dd))
               to   (:v dd)
               posted-at (when (= to :posted)
                           (d/q '[:find ?t . :in $ ?e :where
                                  [?e :kontor.transaction/posted-at ?t]]
                                db-after (:e dd)))]
         :let [reason (cond
                        (not (state-machine/transition-allowed? from to))
                        :state-machine/illegal-transition
                        (and (= to :posted) (nil? posted-at))
                        :state-machine/missing-posted-at)]
         :when reason]
     {:transaction (:e dd) :from from :to to :reason reason})))

;; ============================================================================
;; analytic distributions, post-resolution — ADR-022 / ADR-140
;; ============================================================================

(defn analytic-violations
  "Postings asserted by the delta that fail their account's
   `:kontor.account/required-analytic-plans`, or that carry a plan whose
   percents do not total exactly 100.

   This is the AUTHORITATIVE analytic check. `kontor.posting.validate` judges
   the draft the caller hands the gate and `kontor.analytic` reads inline
   distribution maps pre-resolution — both can be routed around. Here plan and
   percent are resolved datoms in `db-after`, so no tx-data shape evades it.

   Returns `{:posting :account :missing-plans :bad-totals}` rows:
   `:missing-plans` are the REQUIRED plans not satisfied (nil total = no
   distribution at all); `:bad-totals` are plans the posting distributes to at
   all whose percents miss 100, required or not.

   **Scoped to the schema actually installed.** The two halves are gated
   independently on their attributes being present in `db-after`'s schema, and
   the whole pass short-circuits to `[]` when neither is. This is not a
   weakening: on a db that HAS the analytic schema nothing here changes. It is
   required because `d/pull` of an attribute absent from the schema THROWS
   `:error :transact/schema` rather than returning nil, and this predicate runs
   in the writer on every commit — so an unconditional pull meant no db without
   the analytic attributes could transact at all. kontor's schema is a menu
   (ADR-002 cohabitation, group-wise `install-schema!`), a consumer doing no
   cost accounting legitimately has none of these attrs, and the cljs
   `node-test` lane runs a hand-written 7-attribute schema that caught exactly
   this. See `kontor.analytic`'s partial-schema comment."
  [{:keys [db-after] :as report}]
  (let [req?   (analytic/required-plans-installed? db-after)
        dists? (analytic/distributions-installed? db-after)]
    (if-not (or req? dists?)
      []
      (vec
       (keep
        (fn [p]
          (let [pulled (d/pull db-after
                               (cond-> []
                                 req?   (conj {:kontor.posting/account
                                               [:db/id {analytic/required-plans-attr [:db/id]}]})
                                 dists? (conj {analytic/distributions-attr
                                               [:kontor.analytic-distribution/percent
                                                {:kontor.analytic-distribution/plan [:db/id]}]}))
                               p)
                acct (:kontor.posting/account pulled)
                req  (into #{} (keep :db/id)
                           (analytic/required-plans-attr acct))
                totals (reduce (fn [acc d]
                                 (let [pl (:db/id (:kontor.analytic-distribution/plan d))
                                       pct (some-> (:kontor.analytic-distribution/percent d)
                                                   money/->amount)]
                                   (if (and pl pct)
                                     (update acc pl (fnil #(money/add-amount % pct)
                                                          (money/zero-amount)))
                                     acc)))
                               {}
                               (analytic/distributions-attr pulled))
                missing (analytic/missing-or-short-plans req totals)
                bad     (analytic/missing-or-short-plans (set (keys totals)) totals)]
            (when (or (seq missing) (seq bad))
              {:posting p :account (:db/id acct)
               :missing-plans missing :bad-totals bad})))
        (delta-posting-eids report))))))

;; ============================================================================
;; datalog invariants, post-resolution
;; ============================================================================

(defn- sanitize-schema
  "Keep only the user-attribute specs datahike's `empty-db` `:write` validator
   accepts: keyword idents whose spec carries both `:db/valueType` and
   `:db/cardinality` (drops the integer reverse-lookup entries and the partial
   system/bootstrap specs). Mirrors `kontor.invariant`'s helper, inlined so this
   ns needs no private-var access (fragile in cljs)."
  [schema]
  (when schema
    (into {}
          (filter (fn [[k v]]
                    (and (keyword? k) (map? v)
                         (contains? v :db/valueType)
                         (contains? v :db/cardinality))))
          schema)))

(defn- report-empty+txs
  "Reconstruct the `$empty+txs` source from the RESOLVED report: an empty db (of
   db-after's schema) with the delta's asserted datoms applied. Post-resolution,
   so eids match `db-after` and no lookup-ref seeding is needed (unlike the
   pre-resolution `kontor.invariant` reconstruction)."
  [{:keys [db-after tx-data]}]
  (let [schema (sanitize-schema (:schema db-after))
        flex   (or (:schema-flexibility db-after)
                   (get-in db-after [:config :schema-flexibility]) :write)
        adds   (into [] (comp (filter :added)
                              (map (fn [d] [:db/add (:e d) (:a d) (:v d)])))
                     tx-data)
        edb    (dc/empty-db schema {:schema-flexibility flex})]
    (if (seq adds) (dc/db-with edb adds) edb)))

(defn invariant-violations
  "Run every registered datalog invariant whose keyed attribute is asserted in
   the delta, against the resolved report sources ($before $after $empty+txs
   $txs). Returns `{:attribute :invariant}` for each that does not hold.

   Resolves WHICH invariants apply before building the sources they need. That
   ordering is the whole cost model: `report-empty+txs` builds an empty db over
   the store's full schema and replays the delta into it, so it scales with the
   SCHEMA, not with the delta. On a store carrying 574 user attributes it costs
   1.14 ms — 72% of the 1.57 ms the whole call used to take for a 4-attribute
   delta — while deciding that no invariant is keyed costs 0.05 ms. Since this
   predicate runs in the writer on EVERY committed transaction, a store that
   installs kontor's schema but registers no invariant over the attributes
   actually being written — a room whose chat and wiki share the book's store —
   used to pay that on every message. Now it pays only the relevance check.

   The short-circuit is a contract, not an incidental optimization; it is
   pinned by `short-circuits-source-construction-when-nothing-is-keyed` in
   `kontor.governance-test`."
  [{:keys [db-before db-after tx-data] :as report}]
  (let [attrs (into #{} (comp (filter :added) (map :a)) tx-data)
        ;; Parse each invariant once here rather than twice per hit below.
        keyed (into [] (keep (fn [a]
                               (when-let [q (d/q inv/invariant-query db-after a)]
                                 [a (edn/read-string q)])))
                    attrs)]
    (if (empty? keyed)
      []
      (let [e+t (report-empty+txs report)
            txs (into [] (comp (filter :added)
                               (map (fn [d] [:db/add (:e d) (:a d) (:v d)])))
                      tx-data)]
        (vec
         (for [[a invariant] keyed
               :when (not (d/q invariant db-before db-after e+t txs))]
           {:attribute a :invariant invariant}))))))

;; ============================================================================
;; The governor
;; ============================================================================

(defn validate-report
  "The kontor tx-pred: validate a RESOLVED datahike tx-report and throw
   `ex-info` to reject. Returns nil on success. Pure over the report.

   Order mirrors `kontor.validation/validate-and-apply` so the two seams
   produce the SAME error for the same bad write: legal-hold before sealing
   (the more-specific \"blocked by hold X\" wins over the generic
   \"silent retract of posted\"), then sealing, then the period locks, then
   the state machine, then balance, then analytic distributions, then the
   datalog invariants.

   Every FAMILY here must also exist in the gate and vice versa; a family that
   lives on only one side means the mandatory and the advisory seam disagree,
   which is how a store ends up rejecting at commit what it accepted at
   form-validation time (or, worse, the reverse). Individual predicates may
   still be strictly STRONGER here — post-resolution sees retract flags and
   resolved refs that no pre-resolution scan can — and where they are, the
   predicate's own docstring says so."
  [report]
  (when-let [v (seq (hold-violations report))]
    (throw (ex-info "Refused: destructive write blocked by active legal hold"
                    {:type :kontor.legal-hold/purge-blocked :violations (vec v)})))
  (when-let [v (seq (sealing-violations report))]
    (throw (ex-info "Sealing violation: destructive write against a posted entity"
                    {:type :sealing/silent-retract-of-posted :violations (vec v)})))
  (when-let [v (seq (sealed-period-violations report))]
    (throw (ex-info "Sealing violation: write/retract on sealed period"
                    {:type :kontor.period/sealed-write-attempt :violations (vec v)})))
  (when-let [v (seq (period-violations report))]
    (throw (ex-info "Period violation: posting falls in a closed period"
                    {:type :kontor.period/locked-period-violation :violations (vec v)})))
  (when-let [v (seq (state-machine-violations report))]
    (throw (ex-info "Transaction state-machine violation"
                    {:type :state-machine/violation :violations (vec v)})))
  (when-let [v (seq (balance-violations report))]
    (throw (ex-info "Postings do not sum to zero per (entity, ledger, commodity)"
                    {:type :validation/sum-to-zero :violations (vec v)})))
  (resource/assert-report! report)
  (when-let [v (seq (analytic-violations report))]
    (throw (ex-info "Analytic distribution missing or not 100% for a required plan"
                    {:type :kontor.analytic/required-plan-unsatisfied
                     :violations (vec v)})))
  (when-let [v (seq (invariant-violations report))]
    (throw (ex-info "Invariant mismatch"
                    {:type :invariant/invariant-mismatch :violations (vec v)})))
  nil)

(defn- store-id [conn]
  (get-in @conn [:config :store :id]))

;; Registration is a server/writer-side (JVM) concern — the writer that runs the
;; tx-pred lives on the authoritative node; cljs clients run `validate-report`
;; for optimistic pre-checks but do not register governors. `requiring-resolve`
;; keeps `datahike.tx-preds` a soft dependency so kontor loads on datahike
;; versions that predate it (PR #861); once merged this can become a direct
;; `(:require [datahike.tx-preds …])` and the guard can drop.
#?(:clj
   (defn govern!
     "Register [[validate-report]] as a `datahike.tx-preds` transaction predicate
      on `conn`'s store, so every committed write is validated post-resolution in
      the writer. Idempotent per store-id. Returns the store-id."
     [conn]
     ((requiring-resolve 'datahike.tx-preds/register-tx-pred!)
      (store-id conn) validate-report)))

#?(:clj
   (defn ungovern!
     "Remove the kontor governor from `conn`'s store."
     [conn]
     ((requiring-resolve 'datahike.tx-preds/unregister-tx-pred!)
      (store-id conn))))
