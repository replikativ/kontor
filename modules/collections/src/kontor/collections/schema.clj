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
     :invoice/collections-status  (facet on the kernel :invoice)

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
    :db/doc         "Ref to :create/uid. Per-case (not per-partner)
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

   {:db/ident       :payment-promise/recorded-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

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
   ;; :status-history/changed-at is opened-at; the row that
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
                     back to the :partner/credit-status scalar."}

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

   {:db/ident       :credit-hold/released-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Set when an actor releases the hold. The row
                     stays in the DB for audit; query filters on
                     :released-at being null/future to determine
                     'active'."}

   {:db/ident       :credit-hold/released-by-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

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

   {:db/ident       :dunning-pause/placed-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :dunning-pause/placed-by-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :dunning-pause/expires-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Auto-resume. Null = manual-only resume."}

   {:db/ident       :dunning-pause/released-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :dunning-pause/released-by-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :dunning-pause/notes
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :dunning-pause/supporting-doc
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; Invoice extensions — :invoice/collections-status facet
;; ============================================================================

(def ^:private invoice-extensions
  ;; ADR-043 + ADR-034 multi-facet: collections-status is independent
  ;; of procurement's :invoice/match-status.
  [{:db/ident       :invoice/collections-status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-043 facet. Sales/AR invoices flow through
                     this state machine; purchase invoices ignore
                     it. Distinct from procurement's :invoice/match-
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
   :invoice/collections-status."
  [;; --- :collection-case/state ------------------------------------
   {:status-transition/entity-type :collection-case
    :status-transition/facet :collection-case/state
    :status-transition/from :nil
    :status-transition/to :open
    :status-transition/active true
    :status-transition/name "Open Case"}
   {:status-transition/entity-type :collection-case
    :status-transition/facet :collection-case/state
    :status-transition/from :open
    :status-transition/to :dunning-l1
    :status-transition/active true
    :status-transition/name "First Dunning Level"}
   {:status-transition/entity-type :collection-case
    :status-transition/facet :collection-case/state
    :status-transition/from :dunning-l1
    :status-transition/to :dunning-l2
    :status-transition/active true
    :status-transition/name "Second Dunning Level"}
   {:status-transition/entity-type :collection-case
    :status-transition/facet :collection-case/state
    :status-transition/from :dunning-l2
    :status-transition/to :final-notice
    :status-transition/active true
    :status-transition/name "Final Notice"}
   {:status-transition/entity-type :collection-case
    :status-transition/facet :collection-case/state
    :status-transition/from :open
    :status-transition/to :promised
    :status-transition/active true
    :status-transition/name "PTP Accepted (suppresses dunning)"}
   {:status-transition/entity-type :collection-case
    :status-transition/facet :collection-case/state
    :status-transition/from :dunning-l1
    :status-transition/to :promised
    :status-transition/active true
    :status-transition/name "PTP Accepted from L1"}
   {:status-transition/entity-type :collection-case
    :status-transition/facet :collection-case/state
    :status-transition/from :dunning-l2
    :status-transition/to :promised
    :status-transition/active true
    :status-transition/name "PTP Accepted from L2"}
   {:status-transition/entity-type :collection-case
    :status-transition/facet :collection-case/state
    :status-transition/from :promised
    :status-transition/to :open
    :status-transition/active true
    :status-transition/name "PTP Broken — reopen case"}
   {:status-transition/entity-type :collection-case
    :status-transition/facet :collection-case/state
    :status-transition/from :promised
    :status-transition/to :paid
    :status-transition/active true
    :status-transition/name "PTP Kept — closed paid"}
   {:status-transition/entity-type :collection-case
    :status-transition/facet :collection-case/state
    :status-transition/from :open
    :status-transition/to :disputed
    :status-transition/active true
    :status-transition/name "Dispute Opened (suppresses dunning)"}
   {:status-transition/entity-type :collection-case
    :status-transition/facet :collection-case/state
    :status-transition/from :dunning-l1
    :status-transition/to :disputed
    :status-transition/active true
    :status-transition/name "Dispute Opened from L1"}
   {:status-transition/entity-type :collection-case
    :status-transition/facet :collection-case/state
    :status-transition/from :dunning-l2
    :status-transition/to :disputed
    :status-transition/active true
    :status-transition/name "Dispute Opened from L2"}
   {:status-transition/entity-type :collection-case
    :status-transition/facet :collection-case/state
    :status-transition/from :disputed
    :status-transition/to :open
    :status-transition/active true
    :status-transition/name "Dispute Resolved — reopen case"}
   {:status-transition/entity-type :collection-case
    :status-transition/facet :collection-case/state
    :status-transition/from :disputed
    :status-transition/to :paid
    :status-transition/active true
    :status-transition/name "Dispute Resolved — closed paid"}
   {:status-transition/entity-type :collection-case
    :status-transition/facet :collection-case/state
    :status-transition/from :final-notice
    :status-transition/to :legal
    :status-transition/active true
    :status-transition/name "Escalate to Legal (supporting-doc required)"}
   {:status-transition/entity-type :collection-case
    :status-transition/facet :collection-case/state
    :status-transition/from :legal
    :status-transition/to :written-off
    :status-transition/active true
    :status-transition/name "Write Off (supporting-doc required)"}
   {:status-transition/entity-type :collection-case
    :status-transition/facet :collection-case/state
    :status-transition/from :open
    :status-transition/to :paid
    :status-transition/active true
    :status-transition/name "Closed Paid"}
   {:status-transition/entity-type :collection-case
    :status-transition/facet :collection-case/state
    :status-transition/from :dunning-l1
    :status-transition/to :paid
    :status-transition/active true
    :status-transition/name "Closed Paid from L1"}
   {:status-transition/entity-type :collection-case
    :status-transition/facet :collection-case/state
    :status-transition/from :dunning-l2
    :status-transition/to :paid
    :status-transition/active true
    :status-transition/name "Closed Paid from L2"}
   {:status-transition/entity-type :collection-case
    :status-transition/facet :collection-case/state
    :status-transition/from :final-notice
    :status-transition/to :paid
    :status-transition/active true
    :status-transition/name "Closed Paid from Final Notice"}

   ;; --- :payment-promise/status -----------------------------------
   {:status-transition/entity-type :payment-promise
    :status-transition/facet :payment-promise/status
    :status-transition/from :nil
    :status-transition/to :open
    :status-transition/active true
    :status-transition/name "Record Promise"}
   {:status-transition/entity-type :payment-promise
    :status-transition/facet :payment-promise/status
    :status-transition/from :open
    :status-transition/to :kept
    :status-transition/active true
    :status-transition/name "Promise Kept (payment matched)"}
   {:status-transition/entity-type :payment-promise
    :status-transition/facet :payment-promise/status
    :status-transition/from :open
    :status-transition/to :broken
    :status-transition/active true
    :status-transition/name "Promise Broken (sweeper)"}
   {:status-transition/entity-type :payment-promise
    :status-transition/facet :payment-promise/status
    :status-transition/from :open
    :status-transition/to :renegotiated
    :status-transition/active true
    :status-transition/name "Renegotiated (replaced by new promise)"}
   {:status-transition/entity-type :payment-promise
    :status-transition/facet :payment-promise/status
    :status-transition/from :open
    :status-transition/to :cancelled
    :status-transition/active true
    :status-transition/name "Cancelled"}
   {:status-transition/entity-type :payment-promise
    :status-transition/facet :payment-promise/status
    :status-transition/from :broken
    :status-transition/to :renegotiated
    :status-transition/active true
    :status-transition/name "Broken then Renegotiated"}

   ;; --- :dispute/state --------------------------------------------
   {:status-transition/entity-type :dispute
    :status-transition/facet :dispute/state
    :status-transition/from :nil
    :status-transition/to :open
    :status-transition/active true
    :status-transition/name "Raise Dispute"}
   {:status-transition/entity-type :dispute
    :status-transition/facet :dispute/state
    :status-transition/from :open
    :status-transition/to :under-review
    :status-transition/active true
    :status-transition/name "Triage Dispute"}
   {:status-transition/entity-type :dispute
    :status-transition/facet :dispute/state
    :status-transition/from :under-review
    :status-transition/to :resolved
    :status-transition/active true
    :status-transition/name "Resolve Dispute"}
   {:status-transition/entity-type :dispute
    :status-transition/facet :dispute/state
    :status-transition/from :open
    :status-transition/to :resolved
    :status-transition/active true
    :status-transition/name "Fast Resolve (skip triage)"}
   {:status-transition/entity-type :dispute
    :status-transition/facet :dispute/state
    :status-transition/from :under-review
    :status-transition/to :escalated
    :status-transition/active true
    :status-transition/name "Escalate to Manager"}
   {:status-transition/entity-type :dispute
    :status-transition/facet :dispute/state
    :status-transition/from :escalated
    :status-transition/to :resolved
    :status-transition/active true
    :status-transition/name "Manager Resolution"}

   ;; --- :invoice/collections-status (sales-side facet) -----------
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/collections-status
    :status-transition/from :nil
    :status-transition/to :current
    :status-transition/active true
    :status-transition/name "Invoice Sent (current)"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/collections-status
    :status-transition/from :current
    :status-transition/to :overdue
    :status-transition/active true
    :status-transition/name "Past Grace — Now Overdue"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/collections-status
    :status-transition/from :overdue
    :status-transition/to :in-collection
    :status-transition/active true
    :status-transition/name "Routed to Collections (case opened)"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/collections-status
    :status-transition/from :current
    :status-transition/to :disputed
    :status-transition/active true
    :status-transition/name "Customer Disputes While Current"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/collections-status
    :status-transition/from :overdue
    :status-transition/to :disputed
    :status-transition/active true
    :status-transition/name "Customer Disputes While Overdue"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/collections-status
    :status-transition/from :in-collection
    :status-transition/to :disputed
    :status-transition/active true
    :status-transition/name "Customer Disputes In Collection"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/collections-status
    :status-transition/from :disputed
    :status-transition/to :overdue
    :status-transition/active true
    :status-transition/name "Dispute Resolved (back to overdue)"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/collections-status
    :status-transition/from :disputed
    :status-transition/to :paid
    :status-transition/active true
    :status-transition/name "Dispute Resolved (paid)"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/collections-status
    :status-transition/from :current
    :status-transition/to :paid
    :status-transition/active true
    :status-transition/name "Paid While Current"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/collections-status
    :status-transition/from :overdue
    :status-transition/to :paid
    :status-transition/active true
    :status-transition/name "Paid While Overdue"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/collections-status
    :status-transition/from :in-collection
    :status-transition/to :paid
    :status-transition/active true
    :status-transition/name "Paid From Collections"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/collections-status
    :status-transition/from :in-collection
    :status-transition/to :written-off
    :status-transition/active true
    :status-transition/name "Written Off From Collections"}])

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
