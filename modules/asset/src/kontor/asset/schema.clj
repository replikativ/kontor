(ns kontor.asset.schema
  "kontor-asset companion schema — ADR-053 (the register + lifecycle).

   Entities (ADR-053 scope):
     :asset         — one physical capitalised asset
     :asset-class   — the category (l10n ships the rows; e.g. a DE
                      class maps to an AfA-Tabelle row, a US class
                      to a MACRS recovery class)
     :asset-event   — an immutable mid-life-event fact (disposal,
                      impairment, revaluation, useful-life revision,
                      addition, transfer)

   State machine (per ADR-034):
     :asset/status  — :planned → :in-service → :fully-depreciated
                      / :disposed / :transferred

   GL-free: ADR-053 is the data model + lifecycle + governance. The
   per-(asset, ledger) depreciation books, the depreciation runner,
   and all GL postings are ADR-054.

   Componentisation is `:asset/parent` self-reference — a component
   is just an :asset whose parent points at the whole (IAS 16); no
   separate :asset-component entity.

   Cohabits with the kernel + other companions per ADR-002."
  (:require [datahike.api :as d]))

;; ============================================================================
;; :asset
;; ============================================================================

(def ^:private asset-attrs
  [{:db/ident       :asset/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "External identifier — 'MACH-001', 'VEH-2026-07'."}

   {:db/ident       :asset/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :asset/class
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :asset-class — the category carrying the
                     jurisdiction defaults (AfA-Tabelle / MACRS
                     recovery class)."}

   {:db/ident       :asset/acquisition-cost
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "The single acquisition cost ALL depreciation
                     books share (ADR-054)."}

   {:db/ident       :asset/acquisition-commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :asset/acquisition-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "When the asset was acquired. DRIVES effective-
                     dated depreciation-rule resolution (ADR-055):
                     the rule governing an asset is fixed at
                     acquisition for its whole life."}

   {:db/ident       :asset/in-service-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "When the depreciation clock starts — may differ
                     from :acquisition-date (DE: 'Anschaffung' vs
                     'betriebsbereit'; US/CA: 'placed in service' /
                     'available for use')."}

   {:db/ident       :asset/salvage-value
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Residual value. IAS 16: reviewed annually — may
                     change via an :asset-event :useful-life-revision.
                     Often 0."}

   {:db/ident       :asset/asset-account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "BS account carrying gross cost. Used by ADR-054's
                     posting helpers."}

   {:db/ident       :asset/accumulated-account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Contra-asset — kumulierte AfA / accumulated
                     depreciation."}

   {:db/ident       :asset/expense-account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Depreciation-expense account (P&L)."}

   {:db/ident       :asset/cost-center
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ref to :analytic-account — uses the
                     bootstrapped 'cost-center' plan (ADR-032)."}

   {:db/ident       :asset/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional legal-entity scope (ADR-031)."}

   {:db/ident       :asset/parent
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Componentisation (IAS 16): the 'whole' this
                     component rolls up to. A component is just an
                     :asset whose :parent points at the whole —
                     independent depreciation books, shared identity
                     for disposal. Optional."}

   {:db/ident       :asset/origin-transaction
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The capitalisation GL entry (ref to
                     :transaction). Caller-supplied in ADR-053;
                     ADR-054's posting helpers build it."}

   {:db/ident       :asset/origin-document
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :audit-doc — the acquisition invoice /
                     contract / board resolution (ADR-038)."}

   {:db/ident       :asset/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-034 lifecycle facet.
                     #{:planned :in-service :fully-depreciated
                       :disposed :transferred}."}

   {:db/ident       :asset/serial-number
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :asset/location
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :asset/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; :asset-class
;; ============================================================================

(def ^:private asset-class-attrs
  [{:db/ident       :asset-class/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "External identifier — 'machinery',
                     'office-equipment', 'buildings-commercial'."}

   {:db/ident       :asset-class/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :asset-class/parent
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Class hierarchy parent. Optional."}

   {:db/ident       :asset-class/default-useful-life-months
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Default useful life; overridable per
                     :asset-depreciation book (ADR-054)."}

   {:db/ident       :asset-class/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; :asset-event
;; ============================================================================

(def ^:private asset-event-attrs
  [{:db/ident       :asset-event/asset
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :asset-event/kind
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "#{:disposal :impairment :revaluation
                       :partial-disposal :useful-life-revision
                       :addition :transfer}."}

   {:db/ident       :asset-event/date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Valid-time of the event."}

   {:db/ident       :asset-event/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Impairment loss / revaluation delta / disposal
                     proceeds / addition cost — interpretation
                     depends on :kind."}

   {:db/ident       :asset-event/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :asset-event/new-useful-life-months
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "For :kind :useful-life-revision — the revised
                     remaining useful life."}

   {:db/ident       :asset-event/transaction
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The GL entry this event posted (ref to
                     :transaction). Caller-supplied in ADR-053;
                     ADR-054's posting helpers build it."}

   {:db/ident       :asset-event/justification
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :audit-doc — the impairment-test memo,
                     disposal authorisation, or valuation report.
                     Required (inline guard) for :impairment /
                     :revaluation / :disposal events."}

   {:db/ident       :asset-event/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; Aggregate
;; ============================================================================

(def all
  (vec (concat asset-attrs asset-class-attrs asset-event-attrs)))

;; ============================================================================
;; Status-transition + approval-policy seeds (ADR-034 / ADR-038)
;; ============================================================================

(def status-transition-seeds
  "ADR-034 :status-transition rows for the :asset/status lifecycle."
  [{:status-transition/entity-type :asset
    :status-transition/facet :asset/status
    :status-transition/from :nil :status-transition/to :planned
    :status-transition/active true :status-transition/name "Acquire (Planned)"}
   {:status-transition/entity-type :asset
    :status-transition/facet :asset/status
    :status-transition/from :nil :status-transition/to :in-service
    :status-transition/active true :status-transition/name "Acquire In-Service"}
   {:status-transition/entity-type :asset
    :status-transition/facet :asset/status
    :status-transition/from :planned :status-transition/to :in-service
    :status-transition/active true :status-transition/name "Place In Service"}
   {:status-transition/entity-type :asset
    :status-transition/facet :asset/status
    :status-transition/from :in-service :status-transition/to :fully-depreciated
    :status-transition/active true :status-transition/name "Fully Depreciated"}
   {:status-transition/entity-type :asset
    :status-transition/facet :asset/status
    :status-transition/from :in-service :status-transition/to :disposed
    :status-transition/active true :status-transition/name "Dispose"}
   {:status-transition/entity-type :asset
    :status-transition/facet :asset/status
    :status-transition/from :fully-depreciated :status-transition/to :disposed
    :status-transition/active true :status-transition/name "Scrap (Fully Depreciated)"}
   {:status-transition/entity-type :asset
    :status-transition/facet :asset/status
    :status-transition/from :in-service :status-transition/to :transferred
    :status-transition/active true :status-transition/name "Transfer To Another Entity"}])

(def approval-policy-seeds
  "ADR-038 :approval-policy rows. Disposal is the consequential
   status transition — it ends the asset's life and triggers the
   gain/loss recognition (ADR-054). It requires the disposal
   authorisation document and separation of duties."
  [{:approval-policy/entity-type     :asset
    :approval-policy/facet           :asset/status
    :approval-policy/transition-from :in-service
    :approval-policy/transition-to   :disposed
    :approval-policy/rule            :requires-supporting-doc
    :approval-policy/active          true}
   {:approval-policy/entity-type     :asset
    :approval-policy/facet           :asset/status
    :approval-policy/transition-from :in-service
    :approval-policy/transition-to   :disposed
    :approval-policy/rule            :no-self-approval
    :approval-policy/active          true}
   {:approval-policy/entity-type     :asset
    :approval-policy/facet           :asset/status
    :approval-policy/transition-from :fully-depreciated
    :approval-policy/transition-to   :disposed
    :approval-policy/rule            :requires-supporting-doc
    :approval-policy/active          true}])

;; ============================================================================
;; Installer
;; ============================================================================

(defn install!
  "Install the kontor-asset schema + status-transition + approval-
   policy seeds. Idempotent for the schema attrs; the seeds are
   guarded with a presence check (the composite-tuple-with-nil-in-
   tuple non-idempotency caveat).

   Run after kontor.core/install-schema! — kontor-asset references
   kernel attrs (:account, :commodity, :analytic-account, :entity,
   :transaction, :audit-doc)."
  [conn]
  (d/transact conn all)
  (let [db (d/db conn)
        already? (boolean
                  (d/q '[:find ?e .
                         :where [?e :status-transition/entity-type :asset]]
                       db))]
    (when-not already?
      (d/transact conn (vec (concat status-transition-seeds
                                    approval-policy-seeds))))))
