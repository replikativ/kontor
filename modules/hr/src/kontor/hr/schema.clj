(ns kontor.hr.schema
  "kontor-hr companion schema — ADR-075 (Stage R substrate).

   Entities:
     :person                  — global human identity root (Worker
                                primitive; PII-bearing; effective-
                                dated via the bitemporal substrate)
     :employment              — the legal employment relationship to
                                one :entity. A :person may have N
                                concurrent :employment rows
                                (Workday-style multi-employment per
                                note 79 Call 2).
     :department              — recursive per-entity org tree.
     :compensation            — comp envelope for an :employment
                                (note 81 §9.6 refactor — lifted off
                                :employment to enable multi-cardinality
                                pay components like Weihnachtsgeld +
                                employer SI + VWL as distinct rows).
                                Effective-dated.
     :compensation-component  — individual pay component (multi per
                                :compensation), each with kind +
                                amount + period + account-hint.
     :pay-period              — payroll temporal axis (separate from
                                :period per note 79 Call 4; one
                                :pay-period per (entity, frequency)).
     :payroll-run             — one (pay-period × entity) execution
                                of a PayrollComputeProvider; carries
                                control totals + the frozen
                                PayrollFacts.

   The substrate is overwhelmingly already in place: :partner / the
   audit-doc machinery / status-machine / approval-policy / DSAR /
   legal-hold / retention / schedule / process / parallel-ledger / FX
   all ship in the kernel. kontor-hr adds the entities listed above
   plus three minor refinements from note 81 §9.7 (:person/kind for
   Worker subtyping, :employment/work-time-fraction for FTE,
   :employment/work-relationship-kind for DE Beamter / apprentice /
   working-student that :exempt-flag can't represent).

   Cohabits with the kernel + other companions per ADR-002. Per-
   country HR data (DE Sozialversicherungsnummer, US SSN,
   CA SIN, etc.) lives in `kontor-l10n-<cc>` modules attaching
   their own attrs to :person via the open-set convention (mirrors
   the per-country :partner/* extension pattern)."
  (:require [datahike.api :as d]))

;; ============================================================================
;; :person
;; ============================================================================

(def ^:private person-attrs
  [{:db/ident       :person/external-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Consumer-supplied opaque identifier. Stable
                     across employments and re-hires. Maps to
                     Workday Worker ID / SuccessFactors PerPerson /
                     Oracle PERSON_ID."}

   {:db/ident       :person/given-name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :person/family-name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :person/birth-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "PII (typical category :hr-personnel under the
                     :audit-doc/category axis). DSAR collector walks
                     this attr; per-jurisdiction :retention-policy
                     governs erasure."}

   {:db/ident       :person/citizenship
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/many
    :db/doc         "ISO-3166 alpha-2 country codes. Many — a person
                     may hold multiple citizenships."}

   {:db/ident       :person/national-id
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Ref to :audit-doc — the storage shape for
                     SSN / AHV / SV-Nummer / SIN / national-ID
                     scans. PII-bearing; lives behind :audit-doc so
                     the existing :audit-doc/privilege +
                     :audit-doc/category machinery applies."}

   ;; Note 81 §9.7 — Worker subtype enum (Workday/Deel pattern).
   ;; Open-set: :employee | :contingent | :applicant | :retiree |
   ;; :board-member | :intern | <consumer extensions>. Supports
   ;; contingent-worker / contractor payroll without forcing a
   ;; schema migration when C5+ lands.
   {:db/ident       :person/kind
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Worker subtype (note 81 §9.7). Open-set keyword;
                     nil = :employee. Workday-style classification
                     (Worker = Employee + Contingent Worker)."}

   {:db/ident       :person/state
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-034 status-machine facet.
                     #{:active :deceased :purged}. :purged is the
                     terminal GDPR Art. 17 state."}])

;; ============================================================================
;; :partner/person — kernel↔companion linker (note 79 Call 3)
;; ============================================================================

(def ^:private partner-person-link-attrs
  [{:db/ident       :partner/person
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :person, set when :partner/kind is
                     :employee (or :contingent under the note 81
                     §9.7 refinement). The kernel never sees
                     :person directly; everything pointing at a
                     partner (postings, expense reports, …) still
                     resolves the partner-identity hop, and the
                     consumer walks one step further to the human
                     when they need the PII surface."}])

;; ============================================================================
;; :employment — Workday-style relationship (note 79 Calls 1+2)
;; ============================================================================

(def ^:private employment-attrs
  [{:db/ident       :employment/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "External identifier — 'EMP-DE-2026-0042'.
                     Stable per (person, entity) pair; re-hire
                     gets a new code."}

   {:db/ident       :employment/person
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :person. A :person may have N concurrent
                     :employment rows, one per employing :entity
                     (note 79 Call 2: Workday-style multi-employment;
                     the trans-national pitch — an executive
                     employed by Acme-DE-GmbH AND seconded to
                     Acme-US-LLC — needs this)."}

   {:db/ident       :employment/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :entity (ADR-031). The legal employer.
                     A :person + :entity uniquely identifies an
                     active employment (re-hire reuses the person
                     with a new :employment row at a later
                     :start-date)."}

   {:db/ident       :employment/start-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :employment/end-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "nil = open-ended. Set by termination!."}

   {:db/ident       :employment/job-title
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Free text. :position / :job-profile separation
                     (Workday Job Profile vs Position) deferred to
                     C5+ per note 79 §9 + note 81 §9.5."}

   {:db/ident       :employment/department
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :department."}

   {:db/ident       :employment/manager
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :employment (NOT :person). A person's
                     manager-relationship is per their employment —
                     in multi-employment Jane reports to Bob in her
                     Acme-DE role and to Alice in her Acme-US role."}

   {:db/ident       :employment/exempt-flag
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "US FLSA exempt-vs-non-exempt classification.
                     For DE Beamter / apprentice / working-student
                     etc. — use :employment/work-relationship-kind
                     instead (note 81 §9.7)."}

   {:db/ident       :employment/fulltime-flag
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Boolean FT/PT shorthand. For continuous FTE,
                     prefer :employment/work-time-fraction
                     (note 81 §9.7)."}

   ;; Note 81 §9.7 — continuous FTE (Workday/SF/Oracle/Gusto all
   ;; carry this). Half-FTE part-time vs 80% reduced-hours secondment
   ;; matter for payroll math; :fulltime-flag is too coarse.
   {:db/ident       :employment/work-time-fraction
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Full-time-equivalent fraction (0.0–1.0). nil
                     interpreted as 1.0 (full-time). Note 81 §9.7."}

   ;; Note 81 §9.7 — extends past the US FLSA exempt/non-exempt
   ;; binary. Open-set: :standard | :secondment | :board-position |
   ;; :apprentice | :intern | :working-student | :civil-servant
   ;; (DE Beamter) | <consumer ext>.
   {:db/ident       :employment/work-relationship-kind
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Open-set keyword for the employment-relationship
                     archetype. nil = :standard. DE Beamter /
                     apprentice / working-student / intern need a
                     dedicated value (note 81 §9.7) so per-country
                     payroll providers can route correctly."}

   {:db/ident       :employment/contract-doc
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :audit-doc (the signed employment
                     contract). Typical :audit-doc/category
                     :hr-personnel."}

   {:db/ident       :employment/state
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-034 status-machine facet. #{:applicant
                     :offered :hired :active :on-leave :terminated
                     :rehired}."}

   {:db/ident       :employment/termination-reason
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Open-set per jurisdiction. #{:voluntary
                     :involuntary :reduction-in-force :retirement
                     :death :end-of-contract :mutual-agreement |
                     consumer-extends}."}])

;; ============================================================================
;; :department — recursive per-entity org tree
;; ============================================================================

(def ^:private department-attrs
  [{:db/ident       :department/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "External identifier — 'DE-ENG-BERLIN'."}

   {:db/ident       :department/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :department/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :entity (ADR-031). Departments are
                     per-entity; cross-entity org views compose at
                     the consumer level."}

   {:db/ident       :department/parent
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :department. nil = root department."}

   {:db/ident       :department/manager
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :employment (NOT :person). A
                     department's manager IS an employment — at
                     this entity, in this role — not the human."}])

;; ============================================================================
;; :compensation + :compensation-component (note 81 §9.6 refactor)
;; ============================================================================
;; Workday / SuccessFactors / Oracle Fusion / Gusto / Frappe HR all
;; model compensation as a SEPARATE entity with multi-cardinality
;; components — base wage + bonus target + employer pension + VWL +
;; housing allowance + RSU vest schedule are simultaneously active,
;; and one scalar attr can't represent them. The bitemporal axis
;; (:db.valid/from) gives "wage as of date" for the envelope; each
;; component has its own row so per-SKR04-account posting builders
;; have a structural target.

(def ^:private compensation-attrs
  [{:db/ident       :compensation/employment
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :employment. Many :compensation rows per
                     :employment; only one is :active at any (vt, tt)."}

   {:db/ident       :compensation/effective-from
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "When this compensation envelope becomes effective.
                     The valid-time axis on top of :db.valid/from for
                     cases where the consumer wants to record a
                     forward-dated pay change (raise on 2027-01-01)
                     without making the transaction itself happen at
                     a future time."}

   {:db/ident       :compensation/effective-to
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Exclusive upper bound; nil = open-ended (the
                     current compensation). Set when superseded."}

   {:db/ident       :compensation/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The pay commodity (EUR / USD / CAD / …).
                     Components inherit this unless they override."}

   {:db/ident       :compensation/state
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-034 facet. #{:proposed :active :superseded}.
                     :proposed → offered but not yet effective;
                     :active → currently in force;
                     :superseded → terminal once a successor lands."}])

(def ^:private compensation-component-attrs
  [{:db/ident       :compensation-component/compensation
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :compensation — the parent envelope."}

   {:db/ident       :compensation-component/kind
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Open-set per kontor.payroll-provider — common:
                     :base-wage | :bonus | :overtime | :imputed-income
                     | :employer-si | :employee-si | :employer-pension
                     | :employee-pension | :withholding-tax
                     | :garnishment | :voluntary-deduction
                     | :equity-vest | :vwl (DE) | :housing-allowance
                     | <consumer extends>."}

   {:db/ident       :compensation-component/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Signed BigDecimal. Sign convention:
                     positive = paid to/earned by employee;
                     negative = deducted from/withheld."}

   {:db/ident       :compensation-component/period
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Cadence: :hourly | :daily | :weekly |
                     :biweekly | :monthly | :annual | :one-time |
                     :on-event. Implies how PayrollComputeProvider
                     prorates per pay-period."}

   {:db/ident       :compensation-component/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Override the parent :compensation/commodity
                     when a component is denominated differently
                     (rare — RSU vest in equity vs cash base wage)."}

   {:db/ident       :compensation-component/account-hint
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Consumer-side CoA mapping hint. Typically the
                     same keyword as :kind, but a consumer can route
                     specific bonuses to a custom account (e.g.,
                     :weihnachtsgeld → SKR04 4128). Defaults to
                     :kind when nil. Read by the
                     PayrollPostingBuilder."}])

;; ============================================================================
;; :pay-period — payroll temporal axis (note 79 Call 4 sub-question)
;; ============================================================================

(def ^:private pay-period-attrs
  [{:db/ident       :pay-period/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "External identifier — 'DE-2026-05'."}

   {:db/ident       :pay-period/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :entity. A pay-period is per-entity;
                     DE-monthly + US-biweekly coexist within a
                     multi-entity group."}

   {:db/ident       :pay-period/start-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :pay-period/end-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :pay-period/frequency
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "#{:weekly :biweekly :semimonthly :monthly
                       :quarterly :annual}."}

   {:db/ident       :pay-period/fiscal-period
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :period (ADR-014). The fiscal-period
                     these payroll postings land in; used by
                     period-lock middleware to ensure a payroll-run
                     for a locked period is rejected."}

   {:db/ident       :pay-period/state
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-034 facet. #{:open :computed :approved
                     :posted :paid}."}])

;; ============================================================================
;; :payroll-run — one (pay-period × entity) execution
;; ============================================================================

(def ^:private payroll-run-attrs
  [{:db/ident       :payroll-run/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "External identifier — 'RUN-DE-2026-05-001'."}

   {:db/ident       :payroll-run/pay-period
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :payroll-run/provider-id
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "The PayrollComputeProvider keyword used —
                     :datev-lodas | :adp-gli | :wagepoint-api |
                     :static-table. Audit trail."}

   {:db/ident       :payroll-run/state
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-034 facet. #{:proposed :computed :approved
                     :posted :emitted :reconciled}."}

   {:db/ident       :payroll-run/control-total-gross
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Σ gross over all employees in the run. Cached
                     for reconciliation against the engine's
                     control total."}

   {:db/ident       :payroll-run/control-total-net
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Σ net over all employees in the run."}

   {:db/ident       :payroll-run/payroll-transaction
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :transaction — the GL entry produced by
                     the PayrollPostingBuilder + posted to the
                     ledger. Set on :computed → :posted transition."}

   {:db/ident       :payroll-run/emit-docs
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Refs to :audit-doc — the emissions produced by
                     the PayrollEmitProvider (DE LODAS Lohnimport,
                     UK FPS XML, etc.). Each typically carries
                     :audit-doc/category :tax-filing."}])

;; ============================================================================
;; Aggregate
;; ============================================================================

(def all
  (vec (concat person-attrs
               partner-person-link-attrs
               employment-attrs
               department-attrs
               compensation-attrs
               compensation-component-attrs
               pay-period-attrs
               payroll-run-attrs)))

;; ============================================================================
;; Status-transition seeds (ADR-034)
;; ============================================================================

(def status-transition-seeds
  "ADR-034 :status-transition rows for the kontor-hr facets."
  (vec
   (concat
    ;; :person/state — :active is the default; :deceased + :purged
    ;; are terminal.
    (for [[from to name]
          [[:nil       :active    "Create (active)"]
           [:active    :deceased  "Mark deceased"]
           [:active    :purged    "Purge (GDPR Art. 17)"]
           [:deceased  :purged    "Purge after death (retention floor met)"]]]
      {:status-transition/entity-type :person
       :status-transition/facet :person/state
       :status-transition/from from
       :status-transition/to to
       :status-transition/active true
       :status-transition/name name})

    ;; :employment/state — Workday-style lifecycle. :rehired creates
    ;; a NEW :employment row (per Call 2), but the prior row may
    ;; transition :terminated → :rehired as an audit pointer.
    (for [[from to name]
          [[:nil          :applicant   "Create as applicant"]
           [:nil          :hired       "Create as hired (direct)"]
           [:applicant    :offered     "Offer extended"]
           [:offered      :hired       "Offer accepted"]
           [:offered      :applicant   "Offer rescinded — reopen applicant"]
           [:hired        :active      "Start date reached — active"]
           [:active       :on-leave    "Begin leave (LOA)"]
           [:on-leave     :active      "Return from leave"]
           [:active       :terminated  "Terminate employment"]
           [:on-leave     :terminated  "Terminate during leave"]
           [:terminated   :rehired     "Re-hire (audit pointer)"]]]
      {:status-transition/entity-type :employment
       :status-transition/facet :employment/state
       :status-transition/from from
       :status-transition/to to
       :status-transition/active true
       :status-transition/name name})

    ;; :compensation/state — :proposed → :active → :superseded.
    (for [[from to name]
          [[:nil          :proposed    "Create (proposed)"]
           [:nil          :active      "Create (active)"]
           [:proposed     :active      "Activate"]
           [:active       :superseded  "Supersede with new envelope"]
           [:proposed     :superseded  "Discard (proposed)"]]]
      {:status-transition/entity-type :compensation
       :status-transition/facet :compensation/state
       :status-transition/from from
       :status-transition/to to
       :status-transition/active true
       :status-transition/name name})

    ;; :pay-period/state — :open → :computed → :approved → :posted → :paid.
    (for [[from to name]
          [[:nil          :open        "Create (open)"]
           [:open         :computed    "Compute payroll facts"]
           [:computed     :open        "Reopen (recompute)"]
           [:computed     :approved    "Approve computed run"]
           [:approved     :posted      "Post to GL"]
           [:posted       :paid        "Mark paid (bank settled)"]]]
      {:status-transition/entity-type :pay-period
       :status-transition/facet :pay-period/state
       :status-transition/from from
       :status-transition/to to
       :status-transition/active true
       :status-transition/name name})

    ;; :payroll-run/state.
    (for [[from to name]
          [[:nil          :proposed    "Create (proposed)"]
           [:proposed     :computed    "Compute facts"]
           [:computed     :proposed    "Recompute"]
           [:computed     :approved    "Approve"]
           [:approved     :posted      "Post to GL"]
           [:posted       :emitted     "Emit jurisdictional events"]
           [:emitted      :reconciled  "Reconcile against engine output"]
           [:posted       :reconciled  "Reconcile (no emit jurisdiction)"]]]
      {:status-transition/entity-type :payroll-run
       :status-transition/facet :payroll-run/state
       :status-transition/from from
       :status-transition/to to
       :status-transition/active true
       :status-transition/name name}))))

;; ============================================================================
;; Approval-policy seeds (ADR-038)
;; ============================================================================

(def approval-policy-seeds
  "ADR-038 approval-policy rows for kontor-hr — the consequential
   transitions. Payroll approve + post are the load-bearing edges:
   :approved → :posted writes real money; :no-self-approval prevents
   the same person from running and approving the same payroll."
  [{:approval-policy/entity-type     :payroll-run
    :approval-policy/facet           :payroll-run/state
    :approval-policy/transition-from :computed
    :approval-policy/transition-to   :approved
    :approval-policy/rule            :no-self-approval
    :approval-policy/active          true}

   ;; Terminating an employment requires written justification (a
   ;; supporting doc captures the termination letter / wrongful-
   ;; dismissal-review memo / mutual-agreement record).
   {:approval-policy/entity-type     :employment
    :approval-policy/facet           :employment/state
    :approval-policy/transition-from :active
    :approval-policy/transition-to   :terminated
    :approval-policy/rule            :requires-supporting-doc
    :approval-policy/active          true}
   {:approval-policy/entity-type     :employment
    :approval-policy/facet           :employment/state
    :approval-policy/transition-from :on-leave
    :approval-policy/transition-to   :terminated
    :approval-policy/rule            :requires-supporting-doc
    :approval-policy/active          true}

   ;; Marking a :person :purged (GDPR Art. 17 erasure) requires
   ;; both supporting doc (the DSAR :dsar-request reference + the
   ;; retention-policy clearance) AND non-empty reason note (the
   ;; legal basis). Two policies because the kernel supports one
   ;; rule per row.
   {:approval-policy/entity-type     :person
    :approval-policy/facet           :person/state
    :approval-policy/transition-from :active
    :approval-policy/transition-to   :purged
    :approval-policy/rule            :requires-supporting-doc
    :approval-policy/active          true}
   {:approval-policy/entity-type     :person
    :approval-policy/facet           :person/state
    :approval-policy/transition-from :active
    :approval-policy/transition-to   :purged
    :approval-policy/rule            :requires-non-empty-reason-note
    :approval-policy/active          true}])

;; ============================================================================
;; Installer
;; ============================================================================

(defn install!
  "Install the kontor-hr schema + status-transition + approval-policy
   seeds. Idempotent for the schema attrs; the seeds are guarded with
   a presence check.

   Run after kontor.core/install-schema! — kontor-hr references
   kernel attrs (:partner, :entity, :audit-doc, :period,
   :status-transition, :approval-policy)."
  [conn]
  (d/transact conn all)
  (let [db (d/db conn)
        already? (boolean
                  (d/q '[:find ?e .
                         :where [?e :status-transition/entity-type :person]]
                       db))]
    (when-not already?
      (d/transact conn (vec (concat status-transition-seeds
                                    approval-policy-seeds))))))
