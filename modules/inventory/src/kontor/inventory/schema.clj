(ns kontor.inventory.schema
  "kontor-inventory companion schema — ADR-057 (facilities + the
   physical stock ledger).

   Entities (ADR-057 scope):
     :facility           — a node in the self-referential warehouse tree
     :facility-location  — a bin within a facility
     :facility-product   — the per-(facility, product) policy row
     :inventory-item     — the physical stock bucket (NO cost — cost
                           lives in :valuation-layer; the two are
                           joined by a shared :lot)
     :inventory-detail   — the append-only signed-quantity-delta
                           ledger; THE SPINE

   ADR-057 is the physical/operational layer ONLY. Costing stays in
   the kernel's :valuation-* family (ADR-027-030). Available-to-promise
   + the reservation bridge are ADR-058; receive/issue/transfer + GL
   integration are ADR-059; cycle counts + reconciliation are ADR-060.

   `:inventory-detail` is append-only BY TRANSACTOR CONVENTION —
   `kontor.inventory.core/record-detail!` is the only writer, and it
   only ever appends. This is not sealing-enforced (sealing, ADR-007,
   guards :posting entities; wiring a companion entity into kernel
   sealing would breach the anti-accretion contract — the same call
   as :asset-event in Stage L′).

   Cohabits with the kernel + other companions per ADR-002."
  (:require [datahike.api :as d]))

;; ============================================================================
;; :facility — the warehouse tree
;; ============================================================================

(def ^:private facility-attrs
  [{:db/ident       :kontor.facility/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "External identifier — 'WH-BERLIN', 'STORE-01'."}

   {:db/ident       :kontor.facility/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.facility/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "#{:warehouse :store :plant :transit :virtual}.
                     :transit / :virtual model in-transit stock and
                     other non-physical buckets (ADR-059)."}

   {:db/ident       :kontor.facility/parent
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Self-reference — the warehouse tree. Optional."}

   {:db/ident       :kontor.facility/owner-entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :entity (ADR-031) — the legal entity that
                     owns this facility. NOT a :partner. Optional."}

   {:db/ident       :kontor.facility/default-days-to-ship
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Fallback lead time for promise-date computation
                     (ADR-058) when :kontor.facility-product/days-to-ship is
                     absent."}

   {:db/ident       :kontor.facility/opened-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.facility/closed-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.facility/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; :facility-location — bins within a facility
;; ============================================================================

(def ^:private facility-location-attrs
  [{:db/ident       :kontor.facility-location/facility
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.facility-location/seq-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Bin identifier, unique within the facility."}

   {:db/ident       :kontor.facility-location/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.facility-location/facility :kontor.facility-location/seq-id]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "One location per (facility, seq-id). Both members
                     always present — no nil-in-tuple caveat."}

   {:db/ident       :kontor.facility-location/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "#{:pickloc :bulk :staging}. The ADR-058
                     reservation walk visits :pickloc before :bulk."}

   {:db/ident       :kontor.facility-location/area
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.facility-location/aisle
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.facility-location/bin
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.facility-location/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; :facility-product — per-(facility, product) policy
;; ============================================================================

(def ^:private facility-product-attrs
  [{:db/ident       :kontor.facility-product/facility
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.facility-product/product
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Generic ref — the consumer's product entity."}

   {:db/ident       :kontor.facility-product/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.facility-product/facility :kontor.facility-product/product]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "One policy row per (facility, product)."}

   {:db/ident       :kontor.facility-product/min-stock
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Reorder point — replenish when on-hand drops below."}

   {:db/ident       :kontor.facility-product/reorder-qty
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.facility-product/safety-stock
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Buffer held back from available-to-promise
                     (ADR-058): ATP = Σ atp-diff − safety-stock."}

   {:db/ident       :kontor.facility-product/negative-allowed?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-059 negative-inventory policy. When true,
                     `issue!` may over-issue — it creates an explicit
                     negative-fill :valuation-layer. When false/absent
                     (the default), an over-issue throws
                     :inventory/negative-not-allowed."}

   {:db/ident       :kontor.facility-product/days-to-ship
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.facility-product/replenish-method
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Free-form — :reorder-point | :make-to-order |
                     :drop-ship | … Consumers extend."}

   {:db/ident       :kontor.facility-product/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; :inventory-item — the physical stock bucket (NO cost)
;; ============================================================================

(def ^:private inventory-item-attrs
  [{:db/ident       :kontor.inventory-item/product
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Generic ref — the consumer's product entity."}

   {:db/ident       :kontor.inventory-item/facility
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.inventory-item/location
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :facility-location — the bin. Optional;
                     stock can be tracked facility-level."}

   {:db/ident       :kontor.inventory-item/lot
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to the kernel :lot. THE JOIN to the financial
                     half — :kontor.valuation-layer/lot points at the same
                     :lot. Optional (un-lotted stock)."}

   {:db/ident       :kontor.inventory-item/owner-entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :entity — the legal entity that owns this
                     stock. Optional; defaults to the facility owner.
                     ADR-059's consignment flag will diverge from
                     this."}

   {:db/ident       :kontor.inventory-item/kind
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Wired: :non-serial. RESERVED: :serialized — the
                     attr value exists, but the reservation walk
                     skips :serialized buckets and `issue!` has no
                     serialized special-casing yet (review-after
                     market-pain P1; the serial-as-qty-1-lot
                     ergonomics are a documented follow-up). Use
                     :non-serial until the serialized path ships."}

   {:db/ident       :kontor.inventory-item/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-034 status-machine facet.
                     #{:available :on-hold :defective :consumed}."}

   {:db/ident       :kontor.inventory-item/serial-number
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Serialized buckets only."}

   {:db/ident       :kontor.inventory-item/received-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.inventory-item/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; :inventory-detail — the append-only signed-quantity-delta ledger (THE SPINE)
;; ============================================================================

(def ^:private inventory-detail-attrs
  [{:db/ident       :kontor.inventory-detail/inventory-item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Required back-pointer to the :inventory-item."}

   {:db/ident       :kontor.inventory-detail/effective-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "The valid-time of this delta. A first-class
                     queryable field (following
                     :kontor.schedule-occurrence/scheduled-date's precedent)
                     — `on-hand-qty` filters on it for the
                     :as-of-valid axis. The :as-of-tx axis is
                     datahike's d/as-of."}

   {:db/ident       :kontor.inventory-detail/qoh-diff
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Signed quantity-on-hand delta: a receipt is +,
                     an issue −, a variance ±. Never an absolute."}

   {:db/ident       :kontor.inventory-detail/atp-diff
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Signed available-to-promise delta: a reservation
                     is − (QOH unchanged), a cancel +, and every
                     physical QOH event moves ATP equally. Defaults
                     to :qoh-diff when the caller omits it (a pure
                     physical move shifts both)."}

   {:db/ident       :kontor.inventory-detail/reason
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Movement / variance reason keyword. Free-form;
                     ADR-060 seeds the cycle-count vocabulary."}

   {:db/ident       :kontor.inventory-detail/description
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.inventory-detail/source-kind
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Discriminator for :source — #{:opening :receipt
                     :issuance :reservation :variance :transfer
                     :adjustment}."}

   {:db/ident       :kontor.inventory-detail/source
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Polymorphic ref — the entity that caused this
                     delta (a receipt, an :inv-reservation, an
                     :inventory-variance, an :inventory-transfer, …).
                     Interpreted via :source-kind. Optional."}

   {:db/ident       :kontor.inventory-detail/transaction
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-059 — ref to the kernel :transaction whose
                     `plan-stock-move` GL entry this physical delta
                     accompanies. Set by `receive!` / `issue!` so the
                     physical and financial halves are linked. A pure
                     quantity move (a transfer, a reservation) has
                     none."}])

;; ============================================================================
;; :inventory-transfer — two-phase stock move between facilities (ADR-059)
;; ============================================================================

(def ^:private inventory-transfer-attrs
  [{:db/ident       :kontor.inventory-transfer/inventory-item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The SOURCE :inventory-item bucket."}

   {:db/ident       :kontor.inventory-transfer/quantity
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.inventory-transfer/from-facility
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.inventory-transfer/from-location
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.inventory-transfer/to-facility
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.inventory-transfer/to-location
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.inventory-transfer/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-034 facet. #{:in-transit :complete :cancelled}.
                     The in-transit BALANCE (the period-close cutoff
                     exposure) is the Σ quantity of :in-transit rows."}

   {:db/ident       :kontor.inventory-transfer/send-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.inventory-transfer/receive-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.inventory-transfer/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; :negative-fill — the explicit estimated-cost layer for an over-issue (ADR-059)
;; ============================================================================

(def ^:private negative-fill-attrs
  [{:db/ident       :kontor.negative-fill/inventory-item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.negative-fill/valuation-layer
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The estimated-cost :valuation-layer `issue!`
                     created so the over-issue had a layer to
                     consume."}

   {:db/ident       :kontor.negative-fill/shortfall-qty
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.negative-fill/estimated-unit-cost
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.negative-fill/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.negative-fill/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "#{:open :trued-up}. `true-up-negative-fill!`
                     moves it to :trued-up."}

   {:db/ident       :kontor.negative-fill/true-up-adjustment
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Set on true-up — ref to the :layer-adjustment
                     reconciling estimated cost to actual."}

   {:db/ident       :kontor.negative-fill/origin-issue
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to the kernel :transaction of the `issue!`
                     that triggered this negative-fill — so a
                     corrected COGS figure traces back to the sale
                     (review-after market-pain P2)."}

   {:db/ident       :kontor.negative-fill/created-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; :physical-inventory + :inventory-variance — cycle counts (ADR-060)
;; ============================================================================

(def ^:private physical-inventory-attrs
  [{:db/ident       :kontor.physical-inventory/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "External identifier — 'CYCLE-2026-W12'."}

   {:db/ident       :kontor.physical-inventory/facility
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The facility being counted."}

   {:db/ident       :kontor.physical-inventory/count-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "The valid-time the count is taken AS-OF. The
                     freeze is a valid-time convention, not a DB lock
                     — concurrent picks at a later valid-time do not
                     corrupt the count (research note 36 §6)."}

   {:db/ident       :kontor.physical-inventory/counted-by
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :partner — who counted."}

   {:db/ident       :kontor.physical-inventory/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-034 facet. #{:open :counting :review :posted}."}

   {:db/ident       :kontor.physical-inventory/comments
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

(def ^:private inventory-variance-attrs
  [{:db/ident       :kontor.inventory-variance/physical-inventory
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.inventory-variance/inventory-item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.inventory-variance/expected-qty
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "The perpetual on-hand-qty at the count-date —
                     snapshotted when the line was recorded."}

   {:db/ident       :kontor.inventory-variance/counted-qty
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "The physically counted quantity."}

   {:db/ident       :kontor.inventory-variance/qoh-var
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "counted − expected. Posting the count emits an
                     :inventory-detail carrying this as :qoh-diff."}

   {:db/ident       :kontor.inventory-variance/reason
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "#{:shrinkage :damage :found :recount :uom
                     :mispick}. Reason codes are an audit requirement
                     (research note 36 §6/§9)."}

   {:db/ident       :kontor.inventory-variance/recount-of
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Self-ref — the variance line this recount
                     supersedes. An out-of-tolerance variance gets
                     recounted before posting."}

   {:db/ident       :kontor.inventory-variance/comments
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; Aggregate
;; ============================================================================

(def all
  (vec (concat facility-attrs facility-location-attrs facility-product-attrs
               inventory-item-attrs inventory-detail-attrs
               inventory-transfer-attrs negative-fill-attrs
               physical-inventory-attrs inventory-variance-attrs)))

;; ============================================================================
;; Status-transition seeds (ADR-034) — :kontor.inventory-item/status
;; ============================================================================

(def status-transition-seeds
  "ADR-034 :status-transition rows. `:kontor.inventory-item/status` — the
   lifecycle is light (the `:inventory-detail` ledger does the
   quantity work; `:status` governs whether a bucket is reservable).
   `:kontor.inventory-transfer/status` — the two-phase transfer lifecycle
   (ADR-059)."
  (vec
   (concat
    (for [[from to name]
          [[:nil       :available  "Stock available"]
           [:available :on-hold    "Place on hold"]
           [:on-hold   :available  "Release hold"]
           [:available :defective  "Flag defective"]
           [:defective :available  "Re-inspected OK"]
           [:available :consumed   "Fully consumed"]]]
      {:kontor.status-transition/entity-type :inventory-item
       :kontor.status-transition/facet :kontor.inventory-item/status
       :kontor.status-transition/from from
       :kontor.status-transition/to to
       :kontor.status-transition/active true
       :kontor.status-transition/name name})
    (for [[from to name]
          [[:nil         :in-transit "Send transfer"]
           [:in-transit  :complete   "Receive transfer"]
           [:in-transit  :cancelled  "Cancel transfer"]]]
      {:kontor.status-transition/entity-type :inventory-transfer
       :kontor.status-transition/facet :kontor.inventory-transfer/status
       :kontor.status-transition/from from
       :kontor.status-transition/to to
       :kontor.status-transition/active true
       :kontor.status-transition/name name})
    (for [[from to name]
          [[:nil       :open      "Open count"]
           [:open      :counting  "Begin counting"]
           [:counting  :review    "Submit for review"]
           [:review    :counting  "Send back for recount"]
           [:review    :posted    "Post count adjustments"]]]
      {:kontor.status-transition/entity-type :physical-inventory
       :kontor.status-transition/facet :kontor.physical-inventory/status
       :kontor.status-transition/from from
       :kontor.status-transition/to to
       :kontor.status-transition/active true
       :kontor.status-transition/name name}))))

;; ============================================================================
;; Installer
;; ============================================================================

(defn install!
  "Install the kontor-inventory schema + the :kontor.inventory-item/status
   status-transition seeds. Idempotent for the schema attrs; the
   seeds are guarded with a presence check (the composite-tuple-with-
   nil-in-tuple non-idempotency caveat).

   Run after kontor.core/install-schema! — kontor-inventory references
   kernel attrs (:lot, :entity, :status-transition)."
  [conn]
  (d/transact conn all)
  (let [db (d/db conn)
        already? (boolean
                  (d/q '[:find ?e .
                         :where [?e :kontor.status-transition/entity-type :inventory-item]]
                       db))]
    (when-not already?
      (d/transact conn status-transition-seeds))))
