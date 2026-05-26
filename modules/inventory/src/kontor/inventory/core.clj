(ns kontor.inventory.core
  "kontor-inventory — facilities + the physical stock ledger (ADR-057).

   The physical/operational inventory layer: where stock IS and how
   much. It carries NO cost — costing lives in the kernel's
   `:valuation-*` family, and the two halves are joined by a shared
   `:lot`. `:inventory-detail` is an append-only signed-quantity-delta
   ledger; `on-hand-qty` derives QOH from it bitemporally — there is
   no stored quantity cache.

   ADR-057 ships the data model + the low-level `record-detail!`
   writer + `place-opening-stock!` (initial load) + `on-hand-qty`.
   Available-to-promise + reservations are ADR-058; the atomic
   receive/issue/transfer operations + GL integration are ADR-059;
   cycle counts + reconciliation are ADR-060."
  (:require [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.validation :as validation])
  (:import [java.util Date]))

;; ============================================================================
;; Facility / location / policy config
;; ============================================================================

(defn facility-by-code
  "Resolve a :facility eid by :kontor.facility/code."
  [db code]
  (d/q '[:find ?e . :in $ ?c :where [?e :kontor.facility/code ?c]] db code))

(defn resolve-facility
  "Coerce `spec` to a :facility eid (string → by-code lookup)."
  [db spec]
  (cond
    (nil? spec)    nil
    (string? spec) (facility-by-code db spec)
    :else          spec))

(defn define-facility-tx-data
  "Pure tx-data builder for `define-facility!` (ADR-068)."
  [db {:keys [code name type parent owner-entity default-days-to-ship
              opened-at closed-at note]}]
  (when-not code (throw (ex-info ":code required" {})))
  (when-not name (throw (ex-info ":name required" {})))
  (when-not type (throw (ex-info ":type required" {})))
  [(cond-> {:kontor.facility/code code
            :kontor.facility/name name
            :kontor.facility/type type}
     parent              (assoc :kontor.facility/parent (resolve-facility db parent))
     owner-entity        (assoc :kontor.facility/owner-entity owner-entity)
     default-days-to-ship (assoc :kontor.facility/default-days-to-ship
                                 default-days-to-ship)
     opened-at           (assoc :kontor.facility/opened-at opened-at)
     closed-at           (assoc :kontor.facility/closed-at closed-at)
     note                (assoc :kontor.facility/note note))])

(defn define-facility!
  "Create (or upsert by :code) a :facility. Routes through the gate
   (ADR-068). Returns the tx-report.

   Required: :code, :name, :type (#{:warehouse :store :plant :transit
             :virtual}).
   Optional: :parent (code or eid), :owner-entity, :default-days-to-ship,
             :opened-at, :closed-at, :note.

   The pure tx-data builder is `define-facility-tx-data`."
  [conn opts]
  (validation/transact-with-validation
   conn (define-facility-tx-data (d/db conn) opts)))

(defn location-by
  "Resolve a :facility-location eid by (facility, seq-id)."
  [db facility-spec seq-id]
  (when-let [f (resolve-facility db facility-spec)]
    (d/q '[:find ?e . :in $ ?f ?s
           :where
           [?e :kontor.facility-location/facility ?f]
           [?e :kontor.facility-location/seq-id ?s]]
         db f seq-id)))

(defn define-location-tx-data
  "Pure tx-data builder for `define-location!` (ADR-068)."
  [db {:keys [facility seq-id type area aisle bin note]}]
  (when-not seq-id (throw (ex-info ":seq-id required" {})))
  (when-not type   (throw (ex-info ":type required" {})))
  (let [f (resolve-facility db facility)
        _ (when-not f (throw (ex-info "Facility not found" {:spec facility})))]
    [(cond-> {:kontor.facility-location/facility f
              :kontor.facility-location/seq-id seq-id
              :kontor.facility-location/type type}
       area  (assoc :kontor.facility-location/area area)
       aisle (assoc :kontor.facility-location/aisle aisle)
       bin   (assoc :kontor.facility-location/bin bin)
       note  (assoc :kontor.facility-location/note note))]))

(defn define-location!
  "Create a :facility-location (a bin) within a facility. Routes
   through the gate (ADR-068).

   Required: :facility (code or eid), :seq-id, :type (#{:pickloc
             :bulk :staging}).
   Optional: :area, :aisle, :bin, :note.

   The pure tx-data builder is `define-location-tx-data`."
  [conn opts]
  (validation/transact-with-validation
   conn (define-location-tx-data (d/db conn) opts)))

(defn define-facility-product-tx-data
  "Pure tx-data builder for `define-facility-product!` (ADR-068)."
  [db {:keys [facility product min-stock reorder-qty safety-stock
              negative-allowed? days-to-ship replenish-method note]}]
  (when-not product (throw (ex-info ":product required" {})))
  (let [f (resolve-facility db facility)
        _ (when-not f (throw (ex-info "Facility not found" {:spec facility})))]
    [(cond-> {:kontor.facility-product/facility f
              :kontor.facility-product/product product}
       min-stock        (assoc :kontor.facility-product/min-stock min-stock)
       reorder-qty      (assoc :kontor.facility-product/reorder-qty reorder-qty)
       safety-stock     (assoc :kontor.facility-product/safety-stock safety-stock)
       (some? negative-allowed?)
       (assoc :kontor.facility-product/negative-allowed? negative-allowed?)
       days-to-ship     (assoc :kontor.facility-product/days-to-ship days-to-ship)
       replenish-method (assoc :kontor.facility-product/replenish-method
                               replenish-method)
       note             (assoc :kontor.facility-product/note note))]))

(defn define-facility-product!
  "Create (or upsert by the (facility, product) identity tuple) a
   :facility-product policy row. Routes through the gate (ADR-068).

   Required: :facility (code or eid), :product (eid).
   Optional: :min-stock, :reorder-qty, :safety-stock,
             :negative-allowed?, :days-to-ship, :replenish-method,
             :note.

   The pure tx-data builder is `define-facility-product-tx-data`."
  [conn opts]
  (validation/transact-with-validation
   conn (define-facility-product-tx-data (d/db conn) opts)))

;; ============================================================================
;; :inventory-item — bucket resolution
;; ============================================================================

(defn find-inventory-item
  "Find the :inventory-item bucket eid matching the (product,
   facility, location, lot, owner-entity, serial-number) key, or nil.

   Buckets are resolved by query rather than a unique-identity tuple:
   the natural key has nilable members (location/lot/owner), and a
   composite tuple with nils is non-idempotent. `:product` and
   `:facility` are required; the rest are matched (including nil ≡
   attribute-absent) against the small per-(product, facility)
   candidate set."
  [db {:keys [product facility location lot owner-entity serial-number]}]
  (let [candidates (d/q '[:find [?e ...]
                          :in $ ?p ?f
                          :where
                          [?e :kontor.inventory-item/product ?p]
                          [?e :kontor.inventory-item/facility ?f]]
                        db product facility)]
    (first
     (filter (fn [eid]
               (let [it (d/pull db [{:kontor.inventory-item/location [:db/id]}
                                    {:kontor.inventory-item/lot [:db/id]}
                                    {:kontor.inventory-item/owner-entity [:db/id]}
                                    :kontor.inventory-item/serial-number]
                                eid)]
                 (and (= location (:db/id (:kontor.inventory-item/location it)))
                      (= lot (:db/id (:kontor.inventory-item/lot it)))
                      (= owner-entity (:db/id (:kontor.inventory-item/owner-entity it)))
                      (= serial-number (:kontor.inventory-item/serial-number it)))))
             candidates))))

(defn inventory-item-entity
  "Build the :inventory-item entity map for a bucket spec, at the
   given `tempid`. Public so transactors that need the bucket
   created INSIDE their own atomic tx (rather than a prior tx) can
   inline it — see `kontor.inventory.ops/receive!` (ADR-059
   review-fix: no orphan bucket on a plan-stock-move failure)."
  [tempid {:keys [product facility location lot owner-entity kind status
                  serial-number received-at note]
           :or {kind :non-serial status :available}}]
  (cond-> {:db/id tempid
           :kontor.inventory-item/product product
           :kontor.inventory-item/facility facility
           :kontor.inventory-item/kind kind
           :kontor.inventory-item/status status}
    location      (assoc :kontor.inventory-item/location location)
    lot           (assoc :kontor.inventory-item/lot lot)
    owner-entity  (assoc :kontor.inventory-item/owner-entity owner-entity)
    serial-number (assoc :kontor.inventory-item/serial-number serial-number)
    received-at   (assoc :kontor.inventory-item/received-at received-at)
    note          (assoc :kontor.inventory-item/note note)))

(defn find-or-create-inventory-item-tx-data
  "Pure builder for `find-or-create-inventory-item!` (ADR-068). Returns
   `{:tx-data <[] or [entity]> :item-id <eid-or-tempid>}` — when the
   bucket already exists, `:tx-data` is `[]` and `:item-id` is the
   existing eid; otherwise `:tx-data` is `[(inventory-item-entity …)]`
   with a tempid (default `\"inv-item\"`, override via `:tempid`) that
   the caller routes through commit. Lets composing callers either
   commit nothing (existing bucket) or fold the bucket entity into a
   larger process tx-data."
  [db {:keys [tempid] :or {tempid "inv-item"} :as spec}]
  (when-not (:product spec)  (throw (ex-info ":product required" {})))
  (when-not (:facility spec) (throw (ex-info ":facility required" {})))
  (if-let [existing (find-inventory-item db spec)]
    {:tx-data [] :item-id existing}
    {:tx-data [(inventory-item-entity tempid spec)] :item-id tempid}))

(defn find-or-create-inventory-item!
  "Resolve the :inventory-item bucket for `spec`, creating it if it
   does not exist. Returns the eid. Routes the create through the
   gate (ADR-068) via the pure `find-or-create-inventory-item-tx-data`
   builder.

   Required: :product, :facility. Optional: :location, :lot,
   :owner-entity, :kind (default :non-serial), :status (default
   :available), :serial-number, :received-at, :note."
  [conn spec]
  (let [{:keys [tx-data item-id]}
        (find-or-create-inventory-item-tx-data (d/db conn) spec)]
    (if (empty? tx-data)
      item-id
      (get-in (validation/transact-with-validation conn tx-data)
              [:tempids item-id]))))

(defn pull-inventory-item
  "Pull an :inventory-item with its facility / location / lot."
  [db eid]
  (d/pull db
          '[* {:kontor.inventory-item/facility [:kontor.facility/code :kontor.facility/name]
               :kontor.inventory-item/location [:kontor.facility-location/seq-id
                                         :kontor.facility-location/type]
               :kontor.inventory-item/lot [:kontor.lot/label]}]
          eid))

(defn items-of
  "All :inventory-item eids for a product, optionally facility-scoped."
  ([db product] (items-of db product nil))
  ([db product facility-spec]
   (let [f (when facility-spec (resolve-facility db facility-spec))]
     (set
      (if f
        (d/q '[:find [?e ...] :in $ ?p ?f
               :where
               [?e :kontor.inventory-item/product ?p]
               [?e :kontor.inventory-item/facility ?f]]
             db product f)
        (d/q '[:find [?e ...] :in $ ?p
               :where [?e :kontor.inventory-item/product ?p]]
             db product))))))

;; ============================================================================
;; :inventory-detail — the append-only ledger
;; ============================================================================

(defn record-detail!
  "Append one :inventory-detail row — the single low-level writer of
   the quantity ledger. Returns the tx-report.

   Required:
     :inventory-item   eid of the bucket
     :qoh-diff         signed bigdec — the quantity-on-hand delta
   Optional:
     :atp-diff         signed bigdec (default = :qoh-diff — a pure
                       physical move shifts both)
     :effective-date   instant (default now) — the valid-time
     :reason           keyword movement/variance reason
     :description      string
     :source           ref to the causing entity
     :source-kind      keyword discriminator for :source"
  [conn {:keys [inventory-item qoh-diff atp-diff effective-date
                reason description source source-kind]}]
  (when-not inventory-item (throw (ex-info ":inventory-item required" {})))
  (when (nil? qoh-diff)    (throw (ex-info ":qoh-diff required" {})))
  (let [detail (cond-> {:kontor.inventory-detail/inventory-item inventory-item
                        :kontor.inventory-detail/effective-date (or effective-date (Date.))
                        :kontor.inventory-detail/qoh-diff qoh-diff
                        :kontor.inventory-detail/atp-diff (if (nil? atp-diff)
                                                     qoh-diff atp-diff)}
                 reason      (assoc :kontor.inventory-detail/reason reason)
                 description (assoc :kontor.inventory-detail/description description)
                 source      (assoc :kontor.inventory-detail/source source)
                 source-kind (assoc :kontor.inventory-detail/source-kind source-kind))]
    (validation/transact-with-validation conn [detail])))

(defn place-opening-stock!
  "Initial-load / migration convenience: ensure the (product,
   facility, location, lot, owner) bucket exists and append an
   `:opening` :inventory-detail for `:qty`. Bucket + detail land in
   ONE transaction. Returns {:inventory-item eid :tx-report report}.

   Required: :product, :facility, :qty.
   Optional: :location, :lot, :owner-entity, :kind, :status,
             :serial-number, :received-at, :effective-date, :reason,
             :note."
  [conn {:keys [product facility qty effective-date received-at reason]
         :as spec}]
  (when-not product  (throw (ex-info ":product required" {})))
  (when-not facility (throw (ex-info ":facility required" {})))
  (when (nil? qty)   (throw (ex-info ":qty required" {})))
  (let [db (d/db conn)
        existing (find-inventory-item db spec)
        item-id (or existing "inv-item")
        eff (or effective-date received-at (Date.))
        detail (cond-> {:kontor.inventory-detail/inventory-item item-id
                        :kontor.inventory-detail/effective-date eff
                        :kontor.inventory-detail/qoh-diff qty
                        :kontor.inventory-detail/atp-diff qty
                        :kontor.inventory-detail/source-kind :opening}
                 reason (assoc :kontor.inventory-detail/reason reason))
        tx-data (cond-> [detail]
                  (not existing) (conj (inventory-item-entity "inv-item" spec)))
        report (validation/transact-with-validation conn tx-data)]
    {:inventory-item (or existing (get-in report [:tempids "inv-item"]))
     :tx-report report}))

(defn details-of
  "All :inventory-detail rows for an :inventory-item, ordered by
   :effective-date. Optionally bitemporal: :as-of-tx is a datahike
   snapshot."
  ([db inventory-item] (details-of db inventory-item {}))
  ([db inventory-item {:keys [as-of-tx]}]
   (let [db* (if as-of-tx (d/as-of db as-of-tx) db)]
     (->> (d/q '[:find [?d ...]
                 :in $ ?item
                 :where [?d :kontor.inventory-detail/inventory-item ?item]]
               db* inventory-item)
          (map #(d/pull db* '[*] %))
          (sort-by :kontor.inventory-detail/effective-date)
          vec))))

;; ============================================================================
;; on-hand-qty — derived, bitemporal
;; ============================================================================

(defn resolve-scope
  "Resolve a quantity-query scope spec to a set of :inventory-item
   eids. `spec` is an eid, a `{:product … :facility? …}` map, or a
   collection of eids. Shared by `on-hand-qty` and the ADR-058
   available-to-promise helpers."
  [db spec]
  (cond
    (integer? spec) #{spec}
    (map? spec)     (items-of db (:product spec) (:facility spec))
    (set? spec)     spec
    (coll? spec)    (set spec)
    :else           #{spec}))

(defn on-hand-qty
  "Quantity-on-hand for a scope — `Σ :kontor.inventory-detail/qoh-diff`,
   derived from the append-only ledger. Returns a bigdec (0M when
   nothing matches).

   `spec` is an :inventory-item eid, or `{:product P :facility F}`
   (F optional — sums across every bucket of the product).

   Bitemporal opts (ADR-008):
     :as-of-valid  — include only details with :effective-date ≤ this
                     (default = no upper bound)
     :as-of-tx     — datahike snapshot (tx-time axis)"
  ([db spec] (on-hand-qty db spec {}))
  ([db spec {:keys [as-of-valid as-of-tx]}]
   (let [db*   (if as-of-tx (d/as-of db as-of-tx) db)
         items (resolve-scope db* spec)]
     (if (empty? items)
       0M
       (or (d/q '[:find (sum ?diff) .
                  :with ?d
                  :in $ [?item ...] ?cutoff
                  :where
                  [?d :kontor.inventory-detail/inventory-item ?item]
                  [?d :kontor.inventory-detail/qoh-diff ?diff]
                  [?d :kontor.inventory-detail/effective-date ?ed]
                  [(<= ?ed ?cutoff)]]
                db* items (or as-of-valid kbt/forever))
           0M)))))
