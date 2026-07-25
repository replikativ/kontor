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
            [kontor.money :as money]
            [kontor.posting.validate :as pv]))

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
   case, and the reason this is a cheap short-circuit)."
  [db account-ref]
  (if-let [a (resolve-eid db account-ref)]
    (into #{}
          (keep :db/id)
          (:kontor.account/required-analytic-plans
           (d/pull db [{:kontor.account/required-analytic-plans [:db/id]}] a)))
    #{}))

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
   catches those post-resolution."
  [db posting]
  (reduce
   (fn [acc d]
     (let [dist (if (map? d)
                  d
                  (when-let [e (resolve-eid db d)]
                    (d/pull db [{:kontor.analytic-distribution/plan [:db/id]}
                                :kontor.analytic-distribution/percent] e)))
           plan (resolve-eid db (:kontor.analytic-distribution/plan dist))
           pct  (some-> (:kontor.analytic-distribution/percent dist) money/->amount)]
       (if (and plan pct)
         (update acc plan (fnil #(money/add-amount % pct) (money/zero-amount)))
         acc)))
   {}
   (:kontor.posting/analytic-distributions posting)))

(defn find-violations
  "Vector of `{:posting :account :missing-plans}` for every proposed posting
   in `tx-data` whose account names `:kontor.account/required-analytic-plans`
   that the posting does not satisfy. `:missing-plans` is
   `{plan-eid <total-or-nil>}` — nil meaning \"no distribution at all\".

   Short-circuits on the account having no required plans, so the ordinary
   book pays one `d/pull` per posting."
  [db tx-data]
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
         (proposed-postings tx-data))))

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
