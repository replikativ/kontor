(ns kontor.lease.schema
  "kontor-lease companion schema — ADR-062 (the :lease contract +
   the lifecycle).

   Lessee-side lease accounting under IFRS 16 and ASC 842. A thin
   companion — the substrate was built for it:
     - the Right-of-Use asset IS an :asset (`:asset/class` a ROU
       class) — reuse kontor-asset whole; no `:rou-asset` entity.
     - the liability unwind + ROU depreciation are each a :schedule
       (ADR-032).
     - IFRS-16 / ASC-842 / local-GAAP books are each a :ledger
       (ADR-021) — classification is per-(lease, ledger), so it
       lives on the :lease-liability book (ADR-063), NOT here.

   :lease carries framework-NEUTRAL contract facts only. The
   :lease-liability per-(lease, ledger) book + the LeaseProvider +
   the operating-lease ROU plug + the full `commence!` transactor
   are ADR-063; :lease-modification + remeasurements + variable
   payments + FX are ADR-064.

   Cohabits with the kernel + other companions per ADR-002."
  (:require [datahike.api :as d]))

;; ============================================================================
;; :lease — the contract master (framework-neutral facts)
;; ============================================================================

(def ^:private lease-attrs
  [{:db/ident       :lease/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "External identifier — 'LSE-2026-014'."}

   {:db/ident       :lease/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :lease/lessor
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :partner — the counterparty (lessor)."}

   {:db/ident       :lease/underlying-asset-desc
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "What is leased — free text (a property, a
                     vehicle fleet, equipment)."}

   {:db/ident       :lease/asset-class
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :asset-class — the ROU :asset-class the
                     commencement transactor uses when it `acquire!`s
                     the Right-of-Use :asset (ADR-063)."}

   {:db/ident       :lease/commencement-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "When the lessee gets the right to use the asset
                     — the valid-time anchor; the ROU asset's
                     :acquisition-date + :in-service-date."}

   {:db/ident       :lease/term-months
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "The lease term AS ASSESSED — the renewal /
                     termination-option judgement (IFRS 16.18-19 /
                     ASC 842-10-30-1 'reasonably certain') already
                     folded in by the consumer. A change in that
                     assessment is a :lease-modification (ADR-064)."}

   {:db/ident       :lease/payment-amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "The periodic fixed (or in-substance-fixed)
                     payment. An index-linked payment uses the index
                     at commencement; a later index change is a
                     :lease-modification :index-reset (ADR-064)."}

   {:db/ident       :lease/payment-frequency
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":monthly | :quarterly | :annual — the :schedule
                     frequency for both the liability and ROU books."}

   {:db/ident       :lease/payment-timing
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":in-advance (annuity-due — payment at the start
                     of the period) | :in-arrears (ordinary annuity).
                     Affects period-1 interest in the unwind."}

   {:db/ident       :lease/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :lease/discount-rate
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "The annual rate pinned at commencement — the
                     rate implicit in the lease, or (usually) the
                     lessee's incremental borrowing rate. NOT
                     kernel-computed: a consumer input, like a tax
                     rate. Re-discounted only on a :term-change /
                     :rate-reset modification (ADR-064)."}

   {:db/ident       :lease/initial-direct-costs
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Costs capitalised INTO the ROU asset cost.
                     Optional."}

   {:db/ident       :lease/prepaid-at-commencement
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Payments made at or before commencement — added
                     to the ROU asset cost. Optional."}

   {:db/ident       :lease/incentives-received
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Lease incentives received — REDUCE the ROU asset
                     cost. Optional."}

   {:db/ident       :lease/purchase-option-price
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "If the lessee is reasonably certain to exercise
                     a purchase option — included in the liability.
                     Optional."}

   {:db/ident       :lease/rou-asset
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to the Right-of-Use :asset — created by
                     `commence!` (ADR-063) via
                     `kontor.asset.asset/acquire!`. One ROU :asset
                     per lease; its per-ledger depreciation books
                     are `:asset-depreciation`."}

   {:db/ident       :lease/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional legal-entity scope (ADR-031)."}

   {:db/ident       :lease/origin-document
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :audit-doc — the signed lease contract.
                     Required (approval policy) for :draft → :active."}

   {:db/ident       :lease/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-034 lifecycle facet.
                     #{:draft :active :expired :terminated :purchased}."}

   {:db/ident       :lease/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; Aggregate
;; ============================================================================

(def all
  (vec lease-attrs))

;; ============================================================================
;; Status-transition + approval-policy seeds (ADR-034 / ADR-038)
;; ============================================================================

(def status-transition-seeds
  "ADR-034 :status-transition rows for the :lease/status lifecycle.
   `:draft` is the recorded-but-not-commenced state — `define-lease!`
   creates the lease at `:draft`; ADR-063's `commence!` does the
   balance-sheet recognition (`:draft → :active`)."
  (vec
   (for [[from to name]
         [[:nil     :draft       "Record (draft)"]
          [:draft   :active      "Commence (balance-sheet recognition)"]
          [:active  :expired     "Expire (end of term)"]
          [:active  :terminated  "Terminate early"]
          [:active  :purchased   "Purchase option exercised"]]]
     {:status-transition/entity-type :lease
      :status-transition/facet :lease/status
      :status-transition/from from
      :status-transition/to to
      :status-transition/active true
      :status-transition/name name})))

(def approval-policy-seeds
  "ADR-038 :approval-policy rows. Commencement (`:draft → :active`)
   requires the signed lease contract; early termination requires
   the termination agreement + separation of duties."
  [{:approval-policy/entity-type     :lease
    :approval-policy/facet           :lease/status
    :approval-policy/transition-from :draft
    :approval-policy/transition-to   :active
    :approval-policy/rule            :requires-supporting-doc
    :approval-policy/active          true}
   {:approval-policy/entity-type     :lease
    :approval-policy/facet           :lease/status
    :approval-policy/transition-from :active
    :approval-policy/transition-to   :terminated
    :approval-policy/rule            :requires-supporting-doc
    :approval-policy/active          true}
   {:approval-policy/entity-type     :lease
    :approval-policy/facet           :lease/status
    :approval-policy/transition-from :active
    :approval-policy/transition-to   :terminated
    :approval-policy/rule            :no-self-approval
    :approval-policy/active          true}])

;; ============================================================================
;; Installer
;; ============================================================================

(defn install!
  "Install the kontor-lease schema + status-transition + approval-
   policy seeds. Idempotent for the schema attrs; the seeds are
   guarded with a presence check (the composite-tuple-with-nil-in-
   tuple non-idempotency caveat).

   Run after kontor.core/install-schema! AND
   kontor.asset.schema/install! — kontor-lease references kernel
   attrs (:partner, :commodity, :ledger, :account, :entity,
   :audit-doc, :transaction, :status-transition) and kontor-asset
   attrs (:asset, :asset-class, :asset-depreciation)."
  [conn]
  (d/transact conn all)
  (let [db (d/db conn)
        already? (boolean
                  (d/q '[:find ?e .
                         :where [?e :status-transition/entity-type :lease]]
                       db))]
    (when-not already?
      (d/transact conn (vec (concat status-transition-seeds
                                    approval-policy-seeds))))))
