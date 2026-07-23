(ns kontor.tax.fiscal-unit
  "Fiscal-unit substrate for tax-group consolidation regimes (Gap #8,
   ADR-113). Two regime shapes supported in v1:

   - `:single-base` — group treated as one taxpayer. The provider runs
     ONCE on the parent entity with `:fiscal-unit` in ctx; it
     marginalises member income via `kontor.reporting.report/marginalize`,
     applies group-level provisions, and returns ONE TaxReturnFacts.
     Used by DE Organschaft, FR intégration, US §1502, AT
     Gruppenbesteuerung, AU TCR, CN CCSV, MX RIGS.
   - `:per-member-with-netting` — each member computes its own base,
     then a post-pass loss-allocation provision settles inter-entity
     flows. Used by JP group-tsuusan (post-2022). Lands in v1.2.
   - `:loss-surrender` — UK group-relief shape. Lands in v2 (ADR-117).

   Election lifecycle (status-machine per ADR-034):
     :proposed → :elected → :active → :exiting → :exited

   Plus `:active → :voided-retro` for the DE retroactive-break case
   (tax authority retroactively voids the Organschaft; bitemporal
   restatement preserves the original :elected facts at their
   recorded valid-time).

   Many-to-many membership: an `:entity` can be a member of multiple
   fiscal-units simultaneously (income-tax group + trade-tax group +
   …). The :kontor.fiscal-unit-member join carries `:joined-on` /
   `:left-on` bitemporal windows.

   This namespace is thin (~150 LOC of orchestration + helpers); the
   heavy lifting is in `kontor.tax.period-tax-provider` (running the
   provider with `:fiscal-unit` in ctx) and `kontor.tax.statute`
   (composing the elected vs separate outcome via
   `compose-aggregate-of`)."
  (:require [datahike.api :as d]
            [kontor.tax.period-tax-provider :as ptp]
            [kontor.workflow.status-machine :as sm]))

;; ============================================================================
;; Allowed regimes + computation styles (closed-by-ADR enums)
;; ============================================================================

(def allowed-regimes
  "The 9-regime starter set per note 189 §3.2. Adding a regime
   requires an ADR addendum reviewing the cross-jurisdiction case."
  #{:de-organschaft
    :fr-integration
    :us-1502
    :jp-group-tsuusan
    :uk-group-relief
    :at-gruppenbesteuerung
    :au-tcr
    :cn-ccsv
    :mx-rigs})

(def allowed-computation-styles
  "The 3-shape closed enum per note 167 §1.3 + note 189 §3.2."
  #{:single-base
    :per-member-with-netting
    :loss-surrender})

;; ============================================================================
;; Status-transition + approval-policy seeds (ADR-034)
;; ============================================================================

(def status-transition-seeds
  "ADR-034 :status-transition rows for the :kontor.fiscal-unit/status
   facet. Five transitions cover the v1 lifecycle. Per note 189 §3.5,
   :active → :voided-retro is shipped in v1 (the DE retroactive-void
   test exercises it immediately)."
  [{:kontor.status-transition/entity-type :fiscal-unit
    :kontor.status-transition/facet :kontor.fiscal-unit/status
    :kontor.status-transition/from :nil
    :kontor.status-transition/to :proposed
    :kontor.status-transition/active true
    :kontor.status-transition/name "Propose fiscal-unit election"}
   {:kontor.status-transition/entity-type :fiscal-unit
    :kontor.status-transition/facet :kontor.fiscal-unit/status
    :kontor.status-transition/from :proposed
    :kontor.status-transition/to :elected
    :kontor.status-transition/active true
    :kontor.status-transition/name "Elect fiscal-unit (file election with authority)"}
   {:kontor.status-transition/entity-type :fiscal-unit
    :kontor.status-transition/facet :kontor.fiscal-unit/status
    :kontor.status-transition/from :elected
    :kontor.status-transition/to :active
    :kontor.status-transition/active true
    :kontor.status-transition/name "Activate fiscal-unit (election effective)"}
   {:kontor.status-transition/entity-type :fiscal-unit
    :kontor.status-transition/facet :kontor.fiscal-unit/status
    :kontor.status-transition/from :active
    :kontor.status-transition/to :exiting
    :kontor.status-transition/active true
    :kontor.status-transition/name "Begin fiscal-unit exit"}
   {:kontor.status-transition/entity-type :fiscal-unit
    :kontor.status-transition/facet :kontor.fiscal-unit/status
    :kontor.status-transition/from :exiting
    :kontor.status-transition/to :exited
    :kontor.status-transition/active true
    :kontor.status-transition/name "Fiscal-unit exit complete"}
   {:kontor.status-transition/entity-type :fiscal-unit
    :kontor.status-transition/facet :kontor.fiscal-unit/status
    :kontor.status-transition/from :active
    :kontor.status-transition/to :voided-retro
    :kontor.status-transition/active true
    :kontor.status-transition/name "Retroactively void fiscal-unit (tax-authority action)"}])

(defn install-seeds!
  "Idempotently transact the fiscal-unit status-transition seeds.
   Called from `kontor.core/install-schema!`. Guarded with a presence
   check (the composite-tuple-with-nil-in-tuple non-idempotency
   caveat that note 32 P1-3 surfaced)."
  [conn]
  (let [db (d/db conn)
        already? (boolean
                  (d/q '[:find ?e .
                         :where
                         [?e :kontor.status-transition/entity-type :fiscal-unit]]
                       db))]
    (when-not already?
      (d/transact conn status-transition-seeds))))

;; ============================================================================
;; Queries
;; ============================================================================

(defn ^java.util.Date now [] (java.util.Date.))

(defn- in-window?
  "True iff `as-of` falls in [joined-on, left-on). A nil left-on is
   open-ended (member still in the unit)."
  [^java.util.Date as-of ^java.util.Date joined ^java.util.Date left]
  (and (some? joined)
       (>= (.compareTo as-of joined) 0)
       (or (nil? left) (< (.compareTo as-of left) 0))))

(defn members
  "All `:kontor.fiscal-unit-member` rows for `fiscal-unit` at `:as-of`
   (default now). Honours `:joined-on` / `:left-on` windows. Optional
   `:role` filter (`:parent | :sub`)."
  [db fiscal-unit & {:keys [as-of role]}]
  (let [as-of (or as-of (now))
        rows  (d/q '[:find [(pull ?m [*]) ...]
                     :in $ ?fu
                     :where
                     [?m :kontor.fiscal-unit-member/fiscal-unit ?fu]]
                   db fiscal-unit)]
    (filterv (fn [m]
               (and (in-window? as-of
                                (:kontor.fiscal-unit-member/joined-on m)
                                (:kontor.fiscal-unit-member/left-on m))
                    (or (nil? role)
                        (= role (:kontor.fiscal-unit-member/role m)))))
             rows)))

(defn member-entities
  "Convenience over [[members]] — returns `:entity` eids only."
  [db fiscal-unit & opts]
  (mapv (comp :db/id :kontor.fiscal-unit-member/entity)
        (apply members db fiscal-unit opts)))

(defn fiscal-units-of
  "All `:kontor.fiscal-unit` rows that `entity` is a member of at
   `:as-of` (default now). Many-to-many: an entity may belong to
   several units (income-tax group + trade-tax group + …)."
  [db entity & {:keys [as-of]}]
  (let [as-of (or as-of (now))
        member-rows (d/q '[:find [(pull ?m [*]) ...]
                           :in $ ?ent
                           :where
                           [?m :kontor.fiscal-unit-member/entity ?ent]]
                         db entity)]
    (->> member-rows
         (filter (fn [m]
                   (in-window? as-of
                               (:kontor.fiscal-unit-member/joined-on m)
                               (:kontor.fiscal-unit-member/left-on m))))
         (mapv (fn [m]
                 (d/pull db '[*]
                         (:db/id (:kontor.fiscal-unit-member/fiscal-unit m))))))))

;; ============================================================================
;; Builders (ADR-068 *-tx-data + ! convention)
;; ============================================================================

(defn elect-tx-data
  "Pure tx-data builder for a new fiscal-unit election. The unit
   starts in `:proposed` state — callers walk it through the
   status-machine to `:elected` then `:active` via
   `kontor.workflow.status-machine/record-status-change!`.

   Required: :code :name :parent-entity :regime :computation-style
             :elected-from :anchor-document :members
   Optional: :minimum-term-ends :elected-until :tempid

   `:members` is a vec of `{:entity <eid> :role :parent|:sub
   :ownership-fraction <bigdec> :joined-on <inst>}` — the unit
   carries them as `:kontor.fiscal-unit-member` rows.

   Returns: vec of datahike tx-data maps; the fiscal-unit takes
   the supplied `:tempid` (or `\"fiscal-unit\"` by default) so the
   caller can extract its eid from the resulting tx-report's
   `:tempids`."
  [{:keys [code name parent-entity regime computation-style
           elected-from elected-until minimum-term-ends
           anchor-document members tempid]
    :or {tempid "fiscal-unit"}}]
  (when-not code             (throw (ex-info ":code required" {})))
  (when-not parent-entity    (throw (ex-info ":parent-entity required" {})))
  (when-not (allowed-regimes regime)
    (throw (ex-info (str ":regime must be one of " allowed-regimes)
                    {:regime regime :allowed allowed-regimes})))
  (when-not (allowed-computation-styles computation-style)
    (throw (ex-info (str ":computation-style must be one of " allowed-computation-styles)
                    {:computation-style computation-style
                     :allowed allowed-computation-styles})))
  (when-not elected-from     (throw (ex-info ":elected-from required" {})))
  (when-not (seq members)    (throw (ex-info ":members required (vec)" {})))
  (let [member-tx (mapv
                   (fn [{:keys [entity role ownership-fraction joined-on]
                         :or {joined-on elected-from}}]
                     (when-not entity (throw (ex-info "member :entity required" {})))
                     (when-not (#{:parent :sub} role)
                       (throw (ex-info "member :role must be :parent|:sub" {:role role})))
                     {:kontor.fiscal-unit-member/fiscal-unit tempid
                      :kontor.fiscal-unit-member/entity entity
                      :kontor.fiscal-unit-member/role role
                      :kontor.fiscal-unit-member/ownership-fraction
                      (or ownership-fraction 1M)
                      :kontor.fiscal-unit-member/joined-on joined-on})
                   members)]
    (into
     [(cond-> {:db/id tempid
               :kontor.fiscal-unit/code code
               :kontor.fiscal-unit/parent-entity parent-entity
               :kontor.fiscal-unit/regime regime
               :kontor.fiscal-unit/computation-style computation-style
               :kontor.fiscal-unit/elected-from elected-from
               :kontor.fiscal-unit/active false
               :kontor.fiscal-unit/status :proposed}
        name              (assoc :kontor.fiscal-unit/name name)
        elected-until     (assoc :kontor.fiscal-unit/elected-until elected-until)
        minimum-term-ends (assoc :kontor.fiscal-unit/minimum-term-ends minimum-term-ends)
        anchor-document   (assoc :kontor.fiscal-unit/anchor-document anchor-document))]
     member-tx)))

(defn elect!
  "ADR-068 ! wrapper — transacts the election. The caller advances
   the unit through the status-machine separately to `:elected` then
   `:active` (via `kontor.workflow.status-machine/record-status-
   change!`); this fn just lays down the substrate row.

   Returns the tx-report."
  [conn opts]
  (d/transact conn (elect-tx-data opts)))

(defn activate!
  "Bring an elected fiscal unit INTO FORCE: advance the ADR-034 status
   `:proposed → :elected → :active` and set `:kontor.fiscal-unit/active true`.
   `elect!` only lays down the `:proposed` row; a group return may not be run
   until the election is in force (`run-group-tax!` enforces this) — a
   consolidated/group filing requires a valid in-force election/consent for the
   period (26 CFR §1.1502-75 consent; §14 KStG a valid Gewinnabführungsvertrag
   in force). note 197.

   `fiscal-unit` is the unit eid. Optional opts thread ADR-038 audit metadata
   (`:changed-by-uid` / `:reason` / `:supporting-doc`) onto both transitions.
   Throws `:status-machine/illegal-transition` if the unit is not currently
   `:proposed`. Returns the tx-report of the activation."
  ([conn fiscal-unit] (activate! conn fiscal-unit {}))
  ([conn fiscal-unit opts]
   (let [base (merge {:entity fiscal-unit
                      :entity-type :fiscal-unit
                      :facet :kontor.fiscal-unit/status}
                     opts)]
     (sm/record-status-change! conn (assoc base :to :elected))
     (sm/record-status-change! conn (assoc base :to :active))
     (d/transact conn [[:db/add fiscal-unit :kontor.fiscal-unit/active true]]))))

(defn exit-tx-data
  "Pure tx-data builder for exiting a fiscal-unit member. Sets
   `:left-on` on the member row at the given date. Does NOT walk
   the unit's own `:status` — exiting one member doesn't terminate
   the unit (most regimes survive a single-member exit). Use a
   separate status-change to drive the unit's status to `:exiting`
   if all subs leave.

   Required: :fiscal-unit-member :exit-date"
  [{:keys [fiscal-unit-member exit-date]}]
  (when-not fiscal-unit-member (throw (ex-info ":fiscal-unit-member required" {})))
  (when-not exit-date          (throw (ex-info ":exit-date required" {})))
  [[:db/add fiscal-unit-member :kontor.fiscal-unit-member/left-on exit-date]])

(defn exit!
  [conn opts]
  (d/transact conn (exit-tx-data opts)))

;; ============================================================================
;; Group-tax orchestrator (stub)
;;
;; v1 ships the substrate + the dispatch shape; the full implementation
;; lands with the DE Organschaft pilot in a follow-up commit. The
;; DE pilot will plumb `kontor.tax.period-tax-provider/period-tax-
;; facts` with `:fiscal-unit` in ctx + run the elected/separate
;; comparison via `kontor.tax.statute/compose-aggregate-of`.
;; ============================================================================

(defn run-group-tax!
  "Orchestrate a group-tax computation against `fiscal-unit`.

   Required ctx:
     :fiscal-unit — eid of the :kontor.fiscal-unit
     :period      — {:from <inst> :to <inst>}
     :provider    — a PeriodTaxProvider satisfying ptp/PeriodTaxProvider

   Optional ctx:
     :tax-unit    — per-jurisdiction facts (forwarded to the provider)
     :inputs      — per-period facts (forwarded to the provider)
     :as-of       — bitemporal valid-time (default: :to of :period)

   Dispatch on `:kontor.fiscal-unit/computation-style`:

   - `:single-base` (ADR-113 v1) — call the provider ONCE with
     `:fiscal-unit`, `:parent-entity` (the Organträger / tête de
     groupe / common parent) and the full ctx. The provider is
     responsible for summing member contributions into the
     consolidated base; the orchestrator just supplies the unit
     metadata.

   - `:per-member-with-netting` (ADR-115, NOT YET IMPLEMENTED) —
     call the provider PER MEMBER, then run a post-pass loss-
     allocation provision that settles inter-entity flows. The
     orchestrator aggregates results and emits
     `:intra-group-settlements`.

   - `:loss-surrender` (ADR-117, NOT YET IMPLEMENTED) — UK group-
     relief shape; independent computation + loss-claim transfer.

   Returns `{:filings [<TaxReturnFacts> …] :settlements
   [<{...}> …]}`. For `:single-base` the `:filings` vector has one
   entry; `:settlements` is empty."
  [conn {:keys [fiscal-unit period provider tax-unit inputs as-of]
         :as _ctx}]
  (when-not fiscal-unit (throw (ex-info ":fiscal-unit required" {})))
  (when-not period      (throw (ex-info ":period required" {})))
  (when-not provider    (throw (ex-info ":provider required" {})))
  (let [db (d/db conn)
        unit (d/pull db
                     '[:db/id :kontor.fiscal-unit/code
                       :kontor.fiscal-unit/computation-style
                       :kontor.fiscal-unit/regime
                       :kontor.fiscal-unit/status
                       :kontor.fiscal-unit/active
                       :kontor.fiscal-unit/elected-from
                       :kontor.fiscal-unit/elected-until
                       {:kontor.fiscal-unit/parent-entity [:db/id]}]
                     fiscal-unit)
        _ (when-not (:kontor.fiscal-unit/computation-style unit)
            (throw (ex-info ":fiscal-unit not found or missing :computation-style"
                            {:fiscal-unit fiscal-unit})))
        ;; Election-in-force gate (note 197): a group return may only be filed
        ;; for an ACTIVE election. Reject :proposed (never activated), :exited,
        ;; and :voided-retro (the authority retroactively broke the group) — a
        ;; group filing for an election not in force is a materially wrong tax
        ;; result. 26 CFR §1.1502-75 (consent); §14 KStG (valid GAV in force).
        _ (when-not (and (= :active (:kontor.fiscal-unit/status unit))
                         (true? (:kontor.fiscal-unit/active unit)))
            (throw (ex-info (str "run-group-tax!: fiscal-unit election is not in force "
                                 "(status " (:kontor.fiscal-unit/status unit)
                                 ", active " (:kontor.fiscal-unit/active unit)
                                 ") — activate! it first, or file the members separately")
                            {:type        :kontor.fiscal-unit/election-not-in-force
                             :fiscal-unit fiscal-unit
                             :status      (:kontor.fiscal-unit/status unit)
                             :active      (:kontor.fiscal-unit/active unit)})))
        ;; Bitemporal window: the election must cover the requested period.
        elected-from  (:kontor.fiscal-unit/elected-from unit)
        elected-until (:kontor.fiscal-unit/elected-until unit)
        _ (when (or (and elected-from  (.after ^java.util.Date elected-from  ^java.util.Date (:from period)))
                    (and elected-until (.before ^java.util.Date elected-until ^java.util.Date (:to period))))
            (throw (ex-info "run-group-tax!: election window does not cover the requested period"
                            {:type          :kontor.fiscal-unit/election-not-in-force
                             :fiscal-unit   fiscal-unit
                             :elected-from  elected-from
                             :elected-until elected-until
                             :period        period})))
        style (:kontor.fiscal-unit/computation-style unit)
        parent (:db/id (:kontor.fiscal-unit/parent-entity unit))
        as-of (or as-of (:to period))]
    (case style
      :single-base
      (let [provider-ctx (cond-> {:db            db
                                  :fiscal-unit   fiscal-unit
                                  :parent-entity parent
                                  :entity        parent
                                  :period        period
                                  :as-of         as-of}
                           tax-unit (assoc :tax-unit tax-unit)
                           inputs   (assoc :inputs inputs))
            facts (ptp/period-tax-facts provider provider-ctx)]
        {:filings     [facts]
         :settlements []})

      :per-member-with-netting
      (throw (ex-info "run-group-tax! :per-member-with-netting — not yet implemented (ADR-115)"
                      {:type :kontor.fiscal-unit/computation-style-not-implemented
                       :computation-style style}))

      :loss-surrender
      (throw (ex-info "run-group-tax! :loss-surrender — not yet implemented (ADR-117)"
                      {:type :kontor.fiscal-unit/computation-style-not-implemented
                       :computation-style style}))

      (throw (ex-info "run-group-tax! — unknown :computation-style"
                      {:computation-style style
                       :allowed allowed-computation-styles})))))
