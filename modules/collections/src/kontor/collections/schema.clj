(ns kontor.collections.schema
  "kontor-collections companion schema — ADR-043.

   Entities (one section each):
     :collection-case      — workflow root, one open case per
                             (partner, entity)
     :payment-promise      — first-class PTP
     :dispute              — invoice or line-level dispute
     :credit-hold          — per-entity overlay over partner scalar
     :dunning-policy       — cadence + frequency-cap config
     :dunning-event        — one row per emission attempt
     :dunning-pause        — explicit pause with reason

   State machines (per ADR-034):
     :collection-case/state
     :payment-promise/status
     :dispute/state
     :credit-hold/state           (P0-5 review fix, 2026-05-13)
     :dunning-pause/state         (P0-5 review fix, 2026-05-13)
     :kontor.invoice/collections-status  (facet on the kernel :invoice)

   Idempotent: composite-tuple identities prevent duplicate seeds
   on re-install."
  (:require [datahike.api :as d]))

;; ============================================================================
;; :collection-case
;; ============================================================================

(def ^:private collection-case-attrs
  [{:db/ident       :collection-case/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   {:db/ident       :collection-case/partner
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :collection-case/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-031 entity scope. Multi-entity tenants
                     scope cases per legal-entity book."}

   {:db/ident       :collection-case/state
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-034 facet.
                     #{:open :dunning-l1 :dunning-l2 :final-notice
                       :promised :disputed :legal :paid :written-off
                       :resolved :closed}"}

   {:db/ident       :collection-case/opened-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :collection-case/closed-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :collection-case/opened-by-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :collection-case/assigned-collector
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :kontor.audit/create-uid. Per-case (not per-partner)
                     because collectors specialize per overdue bucket."}

   {:db/ident       :collection-case/collections-segment
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "#{:strategic :standard :small :external-collected
                       …} — extensible per tenant."}

   {:db/ident       :collection-case/strategy
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "#{:reminder-only :phone :legal :external-agency
                       …}"}

   {:db/ident       :collection-case/total-overdue
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Denormalized for fast filter; refreshed by the
                     nightly aging sweep."}

   {:db/ident       :collection-case/oldest-invoice
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Denormalized for fast filter — oldest open
                     invoice on this case."}

   {:db/ident       :collection-case/notes
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :collection-case/supporting-doc
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   ;; One open case per (partner, entity) — a tenant should close a
   ;; case before opening another for the same pair.
   {:db/ident       :collection-case/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:collection-case/partner
                     :collection-case/entity
                     :collection-case/opened-at]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; :payment-promise
;; ============================================================================

(def ^:private payment-promise-attrs
  [{:db/ident       :payment-promise/external-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   {:db/ident       :payment-promise/case
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :payment-promise/invoice
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional. When omitted, the promise is case-
                     level (covers all open invoices on the case)."}

   {:db/ident       :payment-promise/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :payment-promise/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :payment-promise/promised-by-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :payment-promise/captured-by-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :payment-promise/captured-via
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "#{:phone :email :portal :api}"}

   {:db/ident       :payment-promise/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-034 facet.
                     #{:open :kept :broken :renegotiated :cancelled}"}

   {:db/ident       :payment-promise/notes
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :payment-promise/supporting-doc
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; :dispute
;; ============================================================================

(def ^:private dispute-attrs
  [{:db/ident       :dispute/external-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   {:db/ident       :dispute/invoice
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :dispute/scope
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ref to :invoice-line for line-level
                     disputes. When omitted, the dispute covers the
                     full invoice."}

   {:db/ident       :dispute/disputed-amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Subset of invoice total (or line amount when
                     :scope is a line)."}

   {:db/ident       :dispute/reason-code
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "#{:pricing :short-ship :damaged :duplicate-bill
                       :tax :credit-misapplied :unauthorized :other}.
                     Extensible per l10n."}

   ;; NOTE: :dispute/opened-at / :resolved-at removed — resolvable
   ;; via (kbt/timeline db dispute :dispute/state) — the first row's
   ;; :kontor.status-history/changed-at is opened-at; the row that
   ;; transitioned to :resolved carries resolved-at.

   {:db/ident       :dispute/opened-by-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :dispute/sla-deadline
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Derived from segment + reason. SLAs vary."}

   {:db/ident       :dispute/resolved-by-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :dispute/resolution
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "#{:credit-issued :customer-conceded :written-off
                       :no-action}"}

   {:db/ident       :dispute/state
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-034 facet.
                     #{:open :under-review :resolved :escalated}"}

   {:db/ident       :dispute/notes
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :dispute/supporting-doc
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; :credit-hold  — per-entity overlay (ADR-043 design call)
;; ============================================================================

(def ^:private credit-hold-attrs
  [{:db/ident       :credit-hold/partner
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :credit-hold/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-031 scope. The overlay row only applies to
                     this (partner, entity) pair; other entities fall
                     back to the :kontor.partner/credit-status scalar."}

   {:db/ident       :credit-hold/reason-code
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "#{:overdue-threshold :dispute :insurer-decision
                       :manual :compliance :external-agency}"}

   {:db/ident       :credit-hold/placed-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :credit-hold/placed-by-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :credit-hold/state
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-034 facet (introduced 2026-05-13 P0-5 review
                     fix).
                     #{:placed :released :expired}.
                     Replaces the prior :released-at sentinel; release
                     metadata (who, when, why) is recorded on the
                     :status-history row driving the transition.
                     :placed-at remains in the schema as part of the
                     :credit-hold/identity tuple — deferred per
                     ADR-048 follow-up."}

   {:db/ident       :credit-hold/approver-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Manager who signed off. Distinct from :placed-
                     by-uid for SoD (the ADR-038 :no-self-approval
                     rule can be added at the policy layer)."}

   {:db/ident       :credit-hold/expires-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Auto-release boundary. Null = manual-only
                     release."}

   {:db/ident       :credit-hold/notes
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :credit-hold/supporting-doc
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   ;; One row per (partner, entity, placed-at) tuple. The :placed-at
   ;; in the identity makes audit-trail history natural: re-placing
   ;; after release creates a new row.
   {:db/ident       :credit-hold/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:credit-hold/partner
                     :credit-hold/entity
                     :credit-hold/placed-at]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; :dunning-policy
;; ============================================================================

(def ^:private dunning-policy-attrs
  [{:db/ident       :dunning-policy/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   {:db/ident       :dunning-policy/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :dunning-policy/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional. Per-(entity, segment) cadence; falls
                     through to tenant-wide rows when missing."}

   {:db/ident       :dunning-policy/applies-to-segment
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Matches :collection-case/collections-segment.
                     :default applies when no segment-specific row
                     matches."}

   {:db/ident       :dunning-policy/levels
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "EDN-encoded vec of
                     {:trigger-days N :template-ref kw
                      :late-fee-pct dec? :late-fee-fixed dec?}.
                     Stored as string for portability; helpers
                     read-string to decode."}

   {:db/ident       :dunning-policy/frequency-cap-window-days
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "US Reg-F default: 7."}

   {:db/ident       :dunning-policy/frequency-cap-max-events
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "US Reg-F default: 7."}

   {:db/ident       :dunning-policy/pause-on-dispute?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}

   {:db/ident       :dunning-policy/pause-on-open-promise?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}

   {:db/ident       :dunning-policy/pause-on-unapplied-cash?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}

   {:db/ident       :dunning-policy/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; :dunning-event
;; ============================================================================

(def ^:private dunning-event-attrs
  [{:db/ident       :dunning-event/case
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :dunning-event/invoice
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional. When omitted, the event is case-level
                     (rolled-up reminder for all open invoices)."}

   {:db/ident       :dunning-event/level
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Ordinal level number. No enum cap — policies
                     can ship 1..N levels."}

   {:db/ident       :dunning-event/scheduled-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :dunning-event/sent-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Null while still pending. Set when the side-
                     effect-intent completes."}

   {:db/ident       :dunning-event/channel
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "#{:email :letter :phone :portal}"}

   {:db/ident       :dunning-event/template-ref
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}

   {:db/ident       :dunning-event/locale
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "BCP-47 (e.g. \"de-DE\")."}

   {:db/ident       :dunning-event/audit-doc
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :audit-doc with :type :dunning-letter for
                     the rendered PDF/HTML. ADR-043 design call: no
                     first-class :dunning-letter entity."}

   {:db/ident       :dunning-event/side-effect-intent
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :side-effect-intent — the queue entry
                     for outgoing-email work."}

   {:db/ident       :dunning-event/skipped?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "True when the planned event was suppressed by a
                     policy gate (dispute, promise, unapplied-cash,
                     frequency-cap, credit-hold-released)."}

   {:db/ident       :dunning-event/skip-reason
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "#{:frequency-cap :open-dispute :open-promise
                       :unapplied-cash-pending :credit-hold-released}"}

   ;; Identity intentionally excludes :invoice — that attr is
   ;; optional (case-level emissions), and datahike's composite-tuple
   ;; identity does not upsert on nil-in-tuple. With (case, level,
   ;; scheduled-at) the planner's duplicate-emit-prevention works
   ;; uniformly for both invoice-scoped and case-level events.
   ;; Tenants who need to distinguish multiple invoice-scoped events
   ;; on the same (case, level, scheduled-at) tuple can vary
   ;; :scheduled-at by sub-second offsets.
   {:db/ident       :dunning-event/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:dunning-event/case
                     :dunning-event/level
                     :dunning-event/scheduled-at]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; :dunning-pause
;; ============================================================================

(def ^:private dunning-pause-attrs
  [{:db/ident       :dunning-pause/case
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :dunning-pause/reason-code
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "#{:dispute :ptp-active :holiday-freeze
                       :key-account-exception :legal-hold}"}

   {:db/ident       :dunning-pause/state
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-034 facet (introduced 2026-05-13 P0-5 review
                     fix). #{:placed :released :expired}. Visibility
                     date (placed-at-equivalent) derives from the
                     creating tx's :tx/valid-from per ADR-048; release
                     metadata is recorded on :status-history."}

   {:db/ident       :dunning-pause/placed-by-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :dunning-pause/expires-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Auto-resume. Null = manual-only resume."}

   {:db/ident       :dunning-pause/notes
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :dunning-pause/supporting-doc
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; Invoice extensions — :kontor.invoice/collections-status facet
;; ============================================================================

(def ^:private invoice-extensions
  ;; ADR-043 + ADR-034 multi-facet: collections-status is independent
  ;; of procurement's :kontor.invoice/match-status.
  [{:db/ident       :kontor.invoice/collections-status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-043 facet. Sales/AR invoices flow through
                     this state machine; purchase invoices ignore
                     it. Distinct from procurement's :kontor.invoice/match-
                     status.
                     #{:current :overdue :in-collection :disputed
                       :paid :written-off}"}])

;; ============================================================================
;; Aggregate + state-machine seeds
;; ============================================================================

(def all
  (vec (concat collection-case-attrs
               payment-promise-attrs
               dispute-attrs
               credit-hold-attrs
               dunning-policy-attrs
               dunning-event-attrs
               dunning-pause-attrs
               invoice-extensions)))

(def status-transition-seeds
  "ADR-034 :status-transition rows for all four state machines:
   :collection-case/state, :payment-promise/status, :dispute/state,
   :kontor.invoice/collections-status."
  [;; --- :collection-case/state ------------------------------------
   {:kontor.status-transition/entity-type :collection-case
    :kontor.status-transition/facet :collection-case/state
    :kontor.status-transition/from :nil
    :kontor.status-transition/to :open
    :kontor.status-transition/active true
    :kontor.status-transition/name "Open Case"}
   {:kontor.status-transition/entity-type :collection-case
    :kontor.status-transition/facet :collection-case/state
    :kontor.status-transition/from :open
    :kontor.status-transition/to :dunning-l1
    :kontor.status-transition/active true
    :kontor.status-transition/name "First Dunning Level"}
   {:kontor.status-transition/entity-type :collection-case
    :kontor.status-transition/facet :collection-case/state
    :kontor.status-transition/from :dunning-l1
    :kontor.status-transition/to :dunning-l2
    :kontor.status-transition/active true
    :kontor.status-transition/name "Second Dunning Level"}
   {:kontor.status-transition/entity-type :collection-case
    :kontor.status-transition/facet :collection-case/state
    :kontor.status-transition/from :dunning-l2
    :kontor.status-transition/to :final-notice
    :kontor.status-transition/active true
    :kontor.status-transition/name "Final Notice"}
   {:kontor.status-transition/entity-type :collection-case
    :kontor.status-transition/facet :collection-case/state
    :kontor.status-transition/from :open
    :kontor.status-transition/to :promised
    :kontor.status-transition/active true
    :kontor.status-transition/name "PTP Accepted (suppresses dunning)"}
   {:kontor.status-transition/entity-type :collection-case
    :kontor.status-transition/facet :collection-case/state
    :kontor.status-transition/from :dunning-l1
    :kontor.status-transition/to :promised
    :kontor.status-transition/active true
    :kontor.status-transition/name "PTP Accepted from L1"}
   {:kontor.status-transition/entity-type :collection-case
    :kontor.status-transition/facet :collection-case/state
    :kontor.status-transition/from :dunning-l2
    :kontor.status-transition/to :promised
    :kontor.status-transition/active true
    :kontor.status-transition/name "PTP Accepted from L2"}
   {:kontor.status-transition/entity-type :collection-case
    :kontor.status-transition/facet :collection-case/state
    :kontor.status-transition/from :promised
    :kontor.status-transition/to :open
    :kontor.status-transition/active true
    :kontor.status-transition/name "PTP Broken — reopen case"}
   {:kontor.status-transition/entity-type :collection-case
    :kontor.status-transition/facet :collection-case/state
    :kontor.status-transition/from :promised
    :kontor.status-transition/to :paid
    :kontor.status-transition/active true
    :kontor.status-transition/name "PTP Kept — closed paid"}
   {:kontor.status-transition/entity-type :collection-case
    :kontor.status-transition/facet :collection-case/state
    :kontor.status-transition/from :open
    :kontor.status-transition/to :disputed
    :kontor.status-transition/active true
    :kontor.status-transition/name "Dispute Opened (suppresses dunning)"}
   {:kontor.status-transition/entity-type :collection-case
    :kontor.status-transition/facet :collection-case/state
    :kontor.status-transition/from :dunning-l1
    :kontor.status-transition/to :disputed
    :kontor.status-transition/active true
    :kontor.status-transition/name "Dispute Opened from L1"}
   {:kontor.status-transition/entity-type :collection-case
    :kontor.status-transition/facet :collection-case/state
    :kontor.status-transition/from :dunning-l2
    :kontor.status-transition/to :disputed
    :kontor.status-transition/active true
    :kontor.status-transition/name "Dispute Opened from L2"}
   {:kontor.status-transition/entity-type :collection-case
    :kontor.status-transition/facet :collection-case/state
    :kontor.status-transition/from :disputed
    :kontor.status-transition/to :open
    :kontor.status-transition/active true
    :kontor.status-transition/name "Dispute Resolved — reopen case"}
   {:kontor.status-transition/entity-type :collection-case
    :kontor.status-transition/facet :collection-case/state
    :kontor.status-transition/from :disputed
    :kontor.status-transition/to :paid
    :kontor.status-transition/active true
    :kontor.status-transition/name "Dispute Resolved — closed paid"}
   {:kontor.status-transition/entity-type :collection-case
    :kontor.status-transition/facet :collection-case/state
    :kontor.status-transition/from :final-notice
    :kontor.status-transition/to :legal
    :kontor.status-transition/active true
    :kontor.status-transition/name "Escalate to Legal (supporting-doc required)"}
   {:kontor.status-transition/entity-type :collection-case
    :kontor.status-transition/facet :collection-case/state
    :kontor.status-transition/from :legal
    :kontor.status-transition/to :written-off
    :kontor.status-transition/active true
    :kontor.status-transition/name "Write Off (supporting-doc required)"}
   {:kontor.status-transition/entity-type :collection-case
    :kontor.status-transition/facet :collection-case/state
    :kontor.status-transition/from :open
    :kontor.status-transition/to :paid
    :kontor.status-transition/active true
    :kontor.status-transition/name "Closed Paid"}
   {:kontor.status-transition/entity-type :collection-case
    :kontor.status-transition/facet :collection-case/state
    :kontor.status-transition/from :dunning-l1
    :kontor.status-transition/to :paid
    :kontor.status-transition/active true
    :kontor.status-transition/name "Closed Paid from L1"}
   {:kontor.status-transition/entity-type :collection-case
    :kontor.status-transition/facet :collection-case/state
    :kontor.status-transition/from :dunning-l2
    :kontor.status-transition/to :paid
    :kontor.status-transition/active true
    :kontor.status-transition/name "Closed Paid from L2"}
   {:kontor.status-transition/entity-type :collection-case
    :kontor.status-transition/facet :collection-case/state
    :kontor.status-transition/from :final-notice
    :kontor.status-transition/to :paid
    :kontor.status-transition/active true
    :kontor.status-transition/name "Closed Paid from Final Notice"}

   ;; --- :payment-promise/status -----------------------------------
   {:kontor.status-transition/entity-type :payment-promise
    :kontor.status-transition/facet :payment-promise/status
    :kontor.status-transition/from :nil
    :kontor.status-transition/to :open
    :kontor.status-transition/active true
    :kontor.status-transition/name "Record Promise"}
   {:kontor.status-transition/entity-type :payment-promise
    :kontor.status-transition/facet :payment-promise/status
    :kontor.status-transition/from :open
    :kontor.status-transition/to :kept
    :kontor.status-transition/active true
    :kontor.status-transition/name "Promise Kept (payment matched)"}
   {:kontor.status-transition/entity-type :payment-promise
    :kontor.status-transition/facet :payment-promise/status
    :kontor.status-transition/from :open
    :kontor.status-transition/to :broken
    :kontor.status-transition/active true
    :kontor.status-transition/name "Promise Broken (sweeper)"}
   {:kontor.status-transition/entity-type :payment-promise
    :kontor.status-transition/facet :payment-promise/status
    :kontor.status-transition/from :open
    :kontor.status-transition/to :renegotiated
    :kontor.status-transition/active true
    :kontor.status-transition/name "Renegotiated (replaced by new promise)"}
   {:kontor.status-transition/entity-type :payment-promise
    :kontor.status-transition/facet :payment-promise/status
    :kontor.status-transition/from :open
    :kontor.status-transition/to :cancelled
    :kontor.status-transition/active true
    :kontor.status-transition/name "Cancelled"}
   {:kontor.status-transition/entity-type :payment-promise
    :kontor.status-transition/facet :payment-promise/status
    :kontor.status-transition/from :broken
    :kontor.status-transition/to :renegotiated
    :kontor.status-transition/active true
    :kontor.status-transition/name "Broken then Renegotiated"}

   ;; --- :dispute/state --------------------------------------------
   {:kontor.status-transition/entity-type :dispute
    :kontor.status-transition/facet :dispute/state
    :kontor.status-transition/from :nil
    :kontor.status-transition/to :open
    :kontor.status-transition/active true
    :kontor.status-transition/name "Raise Dispute"}
   {:kontor.status-transition/entity-type :dispute
    :kontor.status-transition/facet :dispute/state
    :kontor.status-transition/from :open
    :kontor.status-transition/to :under-review
    :kontor.status-transition/active true
    :kontor.status-transition/name "Triage Dispute"}
   {:kontor.status-transition/entity-type :dispute
    :kontor.status-transition/facet :dispute/state
    :kontor.status-transition/from :under-review
    :kontor.status-transition/to :resolved
    :kontor.status-transition/active true
    :kontor.status-transition/name "Resolve Dispute"}
   {:kontor.status-transition/entity-type :dispute
    :kontor.status-transition/facet :dispute/state
    :kontor.status-transition/from :open
    :kontor.status-transition/to :resolved
    :kontor.status-transition/active true
    :kontor.status-transition/name "Fast Resolve (skip triage)"}
   {:kontor.status-transition/entity-type :dispute
    :kontor.status-transition/facet :dispute/state
    :kontor.status-transition/from :under-review
    :kontor.status-transition/to :escalated
    :kontor.status-transition/active true
    :kontor.status-transition/name "Escalate to Manager"}
   {:kontor.status-transition/entity-type :dispute
    :kontor.status-transition/facet :dispute/state
    :kontor.status-transition/from :escalated
    :kontor.status-transition/to :resolved
    :kontor.status-transition/active true
    :kontor.status-transition/name "Manager Resolution"}

   ;; --- :kontor.invoice/collections-status (sales-side facet) -----------
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/collections-status
    :kontor.status-transition/from :nil
    :kontor.status-transition/to :current
    :kontor.status-transition/active true
    :kontor.status-transition/name "Invoice Sent (current)"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/collections-status
    :kontor.status-transition/from :current
    :kontor.status-transition/to :overdue
    :kontor.status-transition/active true
    :kontor.status-transition/name "Past Grace — Now Overdue"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/collections-status
    :kontor.status-transition/from :overdue
    :kontor.status-transition/to :in-collection
    :kontor.status-transition/active true
    :kontor.status-transition/name "Routed to Collections (case opened)"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/collections-status
    :kontor.status-transition/from :current
    :kontor.status-transition/to :disputed
    :kontor.status-transition/active true
    :kontor.status-transition/name "Customer Disputes While Current"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/collections-status
    :kontor.status-transition/from :overdue
    :kontor.status-transition/to :disputed
    :kontor.status-transition/active true
    :kontor.status-transition/name "Customer Disputes While Overdue"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/collections-status
    :kontor.status-transition/from :in-collection
    :kontor.status-transition/to :disputed
    :kontor.status-transition/active true
    :kontor.status-transition/name "Customer Disputes In Collection"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/collections-status
    :kontor.status-transition/from :disputed
    :kontor.status-transition/to :overdue
    :kontor.status-transition/active true
    :kontor.status-transition/name "Dispute Resolved (back to overdue)"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/collections-status
    :kontor.status-transition/from :disputed
    :kontor.status-transition/to :paid
    :kontor.status-transition/active true
    :kontor.status-transition/name "Dispute Resolved (paid)"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/collections-status
    :kontor.status-transition/from :current
    :kontor.status-transition/to :paid
    :kontor.status-transition/active true
    :kontor.status-transition/name "Paid While Current"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/collections-status
    :kontor.status-transition/from :overdue
    :kontor.status-transition/to :paid
    :kontor.status-transition/active true
    :kontor.status-transition/name "Paid While Overdue"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/collections-status
    :kontor.status-transition/from :in-collection
    :kontor.status-transition/to :paid
    :kontor.status-transition/active true
    :kontor.status-transition/name "Paid From Collections"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/collections-status
    :kontor.status-transition/from :in-collection
    :kontor.status-transition/to :written-off
    :kontor.status-transition/active true
    :kontor.status-transition/name "Written Off From Collections"}

   ;; --- :credit-hold/state (P0-5 review fix, 2026-05-13) ------------
   {:kontor.status-transition/entity-type :credit-hold
    :kontor.status-transition/facet :credit-hold/state
    :kontor.status-transition/from :nil
    :kontor.status-transition/to :placed
    :kontor.status-transition/active true
    :kontor.status-transition/name "Place Credit Hold"}
   {:kontor.status-transition/entity-type :credit-hold
    :kontor.status-transition/facet :credit-hold/state
    :kontor.status-transition/from :placed
    :kontor.status-transition/to :released
    :kontor.status-transition/active true
    :kontor.status-transition/name "Release Credit Hold"}
   {:kontor.status-transition/entity-type :credit-hold
    :kontor.status-transition/facet :credit-hold/state
    :kontor.status-transition/from :placed
    :kontor.status-transition/to :expired
    :kontor.status-transition/active true
    :kontor.status-transition/name "Credit Hold Expired"}

   ;; --- :dunning-pause/state (P0-5 review fix, 2026-05-13) ----------
   {:kontor.status-transition/entity-type :dunning-pause
    :kontor.status-transition/facet :dunning-pause/state
    :kontor.status-transition/from :nil
    :kontor.status-transition/to :placed
    :kontor.status-transition/active true
    :kontor.status-transition/name "Place Dunning Pause"}
   {:kontor.status-transition/entity-type :dunning-pause
    :kontor.status-transition/facet :dunning-pause/state
    :kontor.status-transition/from :placed
    :kontor.status-transition/to :released
    :kontor.status-transition/active true
    :kontor.status-transition/name "Release Dunning Pause"}
   {:kontor.status-transition/entity-type :dunning-pause
    :kontor.status-transition/facet :dunning-pause/state
    :kontor.status-transition/from :placed
    :kontor.status-transition/to :expired
    :kontor.status-transition/active true
    :kontor.status-transition/name "Dunning Pause Expired"}])

;; ============================================================================
;; Installer
;; ============================================================================

(defn install!
  "Install the kontor-collections schema + state-machine seeds.

   Idempotent for the schema attrs (datahike's d/transact treats
   re-issuing the same :db/ident as a no-op). The status-transition
   seeds share the kernel-wide composite-tuple-with-nil-in-tuple
   non-idempotency caveat documented in modules/procurement/.../
   schema_test.clj:253-258 — fine for one install per DB.

   Run after kontor.schema/install! and kontor.invoice.schema/install!
   so the kernel + invoice attrs the companion references already
   exist."
  [conn]
  (d/transact conn all)
  (d/transact conn status-transition-seeds))
