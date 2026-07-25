(ns kontor.analytic
  "Analytic distributions — the db-dependent half of the ADR-022 sum-to-100
   enforcement (ADR-140).

   `kontor.posting.validate` judges what a posting CARRIES (percents total
   100 per plan, each in [0,100]) with no db access, so it runs client-side.
   Two questions need the db and live here:

     1. **Is a plan required at all?** `:kontor.account/required-analytic-plans`
        names the plans a posting against that account MUST populate. Its
        `:db/doc` claimed \"the posting validator enforces a sum-to-100
        invariant per named plan\" while nothing in the kernel read the
        attribute — a consumer could mark an account `required-analytic-plans
        #{cost-centre}`, post against it with no distribution at all, and get
        a clean commit plus a cost-centre report that silently omitted the
        line. This namespace is that reader.

     2. **Which plan is which?** A distribution's `:.../plan` may be an eid, a
        lookup-ref, or an ident. Comparing it against the account's required
        set means resolving both to eids.

   Two entry points, deliberately at different strengths:

     - [[assert-required-analytic-plans!]] runs pre-resolution inside
       `kontor.validation/validate-and-apply`, i.e. in the gate. It reads the
       INLINE distribution maps the kernel builders emit, so it gives an
       early, well-addressed error — but a caller who writes distributions as
       separate entities in the same tx can slip past it, and a raw
       `d/transact` skips the gate entirely.
     - `kontor.governance/analytic-violations` runs post-resolution in the
       datahike writer on EVERY committed write. It reads plan + percent as
       resolved datoms, so nothing about the caller's tx-data shape can evade
       it. That is the authoritative check; this one is the good error
       message.

   Odoo's analogue is `addons/analytic/models/analytic_mixin.py`
   `_validate_distribution` (`analytic_plan.applicability == 'mandatory'`)."
  (:require [datahike.api :as d]
            [datahike.db.interface :as dbi]
            [kontor.money :as money]
            [kontor.posting.validate :as pv]))

;; ============================================================================
;; Partial-schema safety
;;
;; kontor's schema is a MENU, not a monolith. ADR-002 has the kernel cohabiting
;; with consumer apps in one connection, `install-schema!` installs attribute
;; groups, and a consumer that does no cost accounting has every right to a db
;; with no `:kontor.analytic-*` attributes at all. The cljs `node-test` lane
;; does exactly that on purpose — a hand-written 7-attribute schema — which is
;; how this was caught.
;;
;; That matters here because these checks run on EVERY write (the governor is a
;; datahike `tx-pred`), and `d/pull` on an attribute absent from the db's
;; schema does not return nil — it THROWS `:error :transact/schema` from
;; `datahike.db.utils/validate-attr-ident`. So an unconditional pull turns the
;; analytic invariant into "no db without the analytic schema can transact
;; anything at all", which is the exact hazard ADR-140 named one layer further
;; out: an enforcement that fires on correct usage is worse than the defect it
;; fixes. The guard scopes the check; it does not weaken it. Where the
;; attributes ARE installed, nothing below changes.
;;
;; The lookup goes through `datahike.db.interface/-schema`, the protocol method,
;; and deliberately NOT through either of the two obvious alternatives:
;;
;;   - `(:schema db)` — a raw field read, which is what
;;     `kontor.invariant/sanitize-schema` uses. It works on a plain db and
;;     returns **nil on a wrapper**: `d/as-of` yields a `datahike.db.AsOfDB`
;;     with no `:schema` field, so `(:schema (d/as-of db t))` is nil on BOTH
;;     platforms. Since `kontor.reporting.report/report-postings` always reads
;;     through an as-of db, a field read there would silently report "no
;;     analytic schema" on a db that has it — turning the guard into a
;;     permanent disable of the feature it is scoping.
;;   - `d/schema` — correct through wrappers, but it `reduce-kv`s the entire
;;     schema into a fresh map (~1200 entries for the kernel) on every call.
;;     Far too expensive for a predicate that runs per-transact.
;;
;; `-schema` is correct through wrappers AND ~60ns, measured identical for
;; plain and as-of dbs.
;; ============================================================================

(def distributions-attr :kontor.posting/analytic-distributions)
(def required-plans-attr :kontor.account/required-analytic-plans)

(defn attr-installed?
  "True iff `attr` is declared in `db`'s schema. The prerequisite for pulling
   it — see the namespace comment above on why a pull of an undeclared
   attribute throws rather than returning nil, and why this reads the schema
   through the protocol method rather than the `:schema` field."
  [db attr]
  (contains? (dbi/-schema db) attr))

(defn distributions-installed?
  "True iff a posting in `db` could carry `:kontor.posting/analytic-distributions`
   at all. When false there is nothing to sum, so every db-side analytic check
   is vacuous."
  [db]
  (attr-installed? db distributions-attr))

(defn required-plans-installed?
  "True iff an account in `db` could carry
   `:kontor.account/required-analytic-plans`. Checked SEPARATELY from
   [[distributions-installed?]] because the two are independent: a consumer may
   legitimately use distributions without ever marking an account as requiring
   one, and the sum-to-100 half must still run for them."
  [db]
  (attr-installed? db required-plans-attr))

;; ============================================================================
;; Reference resolution
;; ============================================================================

(defn resolve-eid
  "Resolve `ref` (eid, lookup-ref, ident, or a pulled `{:db/id …}` map) to an
   eid in `db`, or nil when it does not resolve. Never throws — an
   unresolvable ref is somebody else's error (datahike's, at commit), not an
   analytic-distribution violation."
  [db ref]
  (cond
    (nil? ref) nil
    (integer? ref) (when (pos? ref) ref)
    (map? ref) (:db/id ref)
    :else (try (:db/id (d/pull db [:db/id] ref))
               (catch #?(:clj Exception :cljs :default) _ nil))))

(defn required-plans
  "Set of `:kontor.analytic-plan` eids that postings against `account-ref`
   must populate — `:kontor.account/required-analytic-plans` resolved to
   eids. Empty set when the account names none (the overwhelmingly common
   case, and the reason this is a cheap short-circuit), and empty set when the
   attribute is not in `db`'s schema at all — no account CAN require a plan
   then, so there is nothing to enforce (see the partial-schema comment above)."
  [db account-ref]
  (if-not (required-plans-installed? db)
    #{}
    (if-let [a (resolve-eid db account-ref)]
      (into #{}
            (keep :db/id)
            (required-plans-attr
             (d/pull db [{required-plans-attr [:db/id]}] a)))
      #{})))

;; ============================================================================
;; Pure core: given resolved {plan-eid total} vs required plan-eids
;; ============================================================================

(defn missing-or-short-plans
  "The required plans a posting fails: `{plan-eid <total-or-nil>}` for every
   plan in `required` whose entry in `totals` is absent (nil) or not exactly
   100. `totals` is `{plan-eid <summed percent>}`.

   Split out and pure so both the gate and the writer-side governor decide
   the same way from the same two inputs — the Odoo onchange↔constrains
   discipline the gate already follows for balance."
  [required totals]
  (into {}
        (keep (fn [plan]
                (let [t (get totals plan)]
                  (when (or (nil? t)
                            (not (zero? (money/compare-amounts t pv/analytic-total))))
                    [plan t]))))
        required))

;; ============================================================================
;; Pre-resolution (gate) pass
;; ============================================================================

(defn- proposed-postings
  [tx-data]
  (filter (fn [e] (and (map? e) (contains? e :kontor.posting/account))) tx-data))

(defn- inline-totals
  "`{plan-eid total}` for a proposed posting's distributions, resolving each
   `:.../plan` reference through `db`. Distribution entries that are not
   inline maps (a bare eid / tempid pointing at a sibling entity) are read
   from `db` when they already exist and otherwise skipped — the governor
   catches those post-resolution.

   The by-reference read is additionally gated on the distribution attrs being
   in `db`'s schema: with a partial schema that declares
   `:kontor.account/required-analytic-plans` but not
   `:kontor.analytic-distribution/*`, pulling them would throw
   `:transact/schema` instead of reporting a violation."
  [db posting]
  (let [ref-readable? (attr-installed? db :kontor.analytic-distribution/plan)]
    (reduce
     (fn [acc d]
       (let [dist (if (map? d)
                    d
                    (when-let [e (and ref-readable? (resolve-eid db d))]
                      (d/pull db [{:kontor.analytic-distribution/plan [:db/id]}
                                  :kontor.analytic-distribution/percent] e)))
             plan (resolve-eid db (:kontor.analytic-distribution/plan dist))
             pct  (some-> (:kontor.analytic-distribution/percent dist) money/->amount)]
         (if (and plan pct)
           (update acc plan (fnil #(money/add-amount % pct) (money/zero-amount)))
           acc)))
     {}
     (:kontor.posting/analytic-distributions posting))))

(defn find-violations
  "Vector of `{:posting :account :missing-plans}` for every proposed posting
   in `tx-data` whose account names `:kontor.account/required-analytic-plans`
   that the posting does not satisfy. `:missing-plans` is
   `{plan-eid <total-or-nil>}` — nil meaning \"no distribution at all\".

   Short-circuits on the account having no required plans, so the ordinary
   book pays one `d/pull` per posting — and short-circuits to `[]` entirely on
   a db whose schema has no `:kontor.account/required-analytic-plans`, where no
   account can require a plan in the first place."
  [db tx-data]
  (if-not (required-plans-installed? db)
    []
    (vec
     (keep (fn [posting]
             (let [acct (:kontor.posting/account posting)
                   req  (required-plans db acct)]
               (when (seq req)
                 (let [bad (missing-or-short-plans req (inline-totals db posting))]
                   (when (seq bad)
                     {:posting posting
                      :account (resolve-eid db acct)
                      :missing-plans bad})))))
           (proposed-postings tx-data)))))

(defn assert-required-analytic-plans!
  "Throw `:type :kontor.analytic/required-plan-unsatisfied` if any proposed
   posting in `tx-data` fails its account's
   `:kontor.account/required-analytic-plans`. Called from
   `kontor.validation/validate-and-apply`."
  [db tx-data]
  (let [violations (find-violations db tx-data)]
    (when (seq violations)
      (throw (ex-info "Analytic distribution missing or not 100% for a required plan"
                      {:type :kontor.analytic/required-plan-unsatisfied
                       :violations violations
                       :remediation
                       "Each listed posting hits an account whose
                        :kontor.account/required-analytic-plans names a plan
                        the posting does not fully distribute (a nil total
                        means no distribution at all; any other total is a
                        partial split). Add
                        :kontor.posting/analytic-distributions summing to
                        exactly 100% per named plan, or drop the plan from
                        the account's required set. A partial split is
                        refused rather than accepted because the missing
                        percentage would silently vanish from every report
                        that marginalizes over the plan."}))))
  nil)
