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
            [kontor.bitemporal :as kbt])
  (:import [java.util Date]))

;; ============================================================================
;; Facility / location / policy config
;; ============================================================================

(defn facility-by-code
  "Resolve a :facility eid by :facility/code."
  [db code]
  (d/q '[:find ?e . :in $ ?c :where [?e :facility/code ?c]] db code))

(defn resolve-facility
  "Coerce `spec` to a :facility eid (string → by-code lookup)."
  [db spec]
  (cond
    (nil? spec)    nil
    (string? spec) (facility-by-code db spec)
    :else          spec))

(defn define-facility!
  "Create (or upsert by :code) a :facility. Returns the tx-report.

   Required: :code, :name, :type (#{:warehouse :store :plant :transit
             :virtual}).
   Optional: :parent (code or eid), :owner-entity, :default-days-to-ship,
             :opened-at, :closed-at, :note."
  [conn {:keys [code name type parent owner-entity default-days-to-ship
                opened-at closed-at note]}]
  (when-not code (throw (ex-info ":code required" {})))
  (when-not name (throw (ex-info ":name required" {})))
  (when-not type (throw (ex-info ":type required" {})))
  (let [db (d/db conn)
        row (cond-> {:facility/code code
                     :facility/name name
                     :facility/type type}
              parent              (assoc :facility/parent (resolve-facility db parent))
              owner-entity        (assoc :facility/owner-entity owner-entity)
              default-days-to-ship (assoc :facility/default-days-to-ship
                                          default-days-to-ship)
              opened-at           (assoc :facility/opened-at opened-at)
              closed-at           (assoc :facility/closed-at closed-at)
              note                (assoc :facility/note note))]
    (d/transact conn [row])))

(defn location-by
  "Resolve a :facility-location eid by (facility, seq-id)."
  [db facility-spec seq-id]
  (when-let [f (resolve-facility db facility-spec)]
    (d/q '[:find ?e . :in $ ?f ?s
           :where
           [?e :facility-location/facility ?f]
           [?e :facility-location/seq-id ?s]]
         db f seq-id)))

(defn define-location!
  "Create a :facility-location (a bin) within a facility.

   Required: :facility (code or eid), :seq-id, :type (#{:pickloc
             :bulk :staging}).
   Optional: :area, :aisle, :bin, :note."
  [conn {:keys [facility seq-id type area aisle bin note]}]
  (when-not seq-id (throw (ex-info ":seq-id required" {})))
  (when-not type   (throw (ex-info ":type required" {})))
  (let [db (d/db conn)
        f (resolve-facility db facility)
        _ (when-not f (throw (ex-info "Facility not found" {:spec facility})))
        row (cond-> {:facility-location/facility f
                     :facility-location/seq-id seq-id
                     :facility-location/type type}
              area  (assoc :facility-location/area area)
              aisle (assoc :facility-location/aisle aisle)
              bin   (assoc :facility-location/bin bin)
              note  (assoc :facility-location/note note))]
    (d/transact conn [row])))

(defn define-facility-product!
  "Create (or upsert by the (facility, product) identity tuple) a
   :facility-product policy row.

   Required: :facility (code or eid), :product (eid).
   Optional: :min-stock, :reorder-qty, :safety-stock,
             :negative-allowed?, :days-to-ship, :replenish-method,
             :note."
  [conn {:keys [facility product min-stock reorder-qty safety-stock
                negative-allowed? days-to-ship replenish-method note]}]
  (when-not product (throw (ex-info ":product required" {})))
  (let [db (d/db conn)
        f (resolve-facility db facility)
        _ (when-not f (throw (ex-info "Facility not found" {:spec facility})))
        row (cond-> {:facility-product/facility f
                     :facility-product/product product}
              min-stock        (assoc :facility-product/min-stock min-stock)
              reorder-qty      (assoc :facility-product/reorder-qty reorder-qty)
              safety-stock     (assoc :facility-product/safety-stock safety-stock)
              (some? negative-allowed?)
              (assoc :facility-product/negative-allowed? negative-allowed?)
              days-to-ship     (assoc :facility-product/days-to-ship days-to-ship)
              replenish-method (assoc :facility-product/replenish-method
                                      replenish-method)
              note             (assoc :facility-product/note note))]
    (d/transact conn [row])))

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
                          [?e :inventory-item/product ?p]
                          [?e :inventory-item/facility ?f]]
                        db product facility)]
    (first
     (filter (fn [eid]
               (let [it (d/pull db [{:inventory-item/location [:db/id]}
                                    {:inventory-item/lot [:db/id]}
                                    {:inventory-item/owner-entity [:db/id]}
                                    :inventory-item/serial-number]
                                eid)]
                 (and (= location (:db/id (:inventory-item/location it)))
                      (= lot (:db/id (:inventory-item/lot it)))
                      (= owner-entity (:db/id (:inventory-item/owner-entity it)))
                      (= serial-number (:inventory-item/serial-number it)))))
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
           :inventory-item/product product
           :inventory-item/facility facility
           :inventory-item/kind kind
           :inventory-item/status status}
    location      (assoc :inventory-item/location location)
    lot           (assoc :inventory-item/lot lot)
    owner-entity  (assoc :inventory-item/owner-entity owner-entity)
    serial-number (assoc :inventory-item/serial-number serial-number)
    received-at   (assoc :inventory-item/received-at received-at)
    note          (assoc :inventory-item/note note)))

(defn find-or-create-inventory-item!
  "Resolve the :inventory-item bucket for `spec`, creating it if it
   does not exist. Returns the eid.

   Required: :product, :facility. Optional: :location, :lot,
   :owner-entity, :kind (default :non-serial), :status (default
   :available), :serial-number, :received-at, :note."
  [conn spec]
  (when-not (:product spec)  (throw (ex-info ":product required" {})))
  (when-not (:facility spec) (throw (ex-info ":facility required" {})))
  (let [db (d/db conn)]
    (or (find-inventory-item db spec)
        (get-in (d/transact conn [(inventory-item-entity "inv-item" spec)])
                [:tempids "inv-item"]))))

(defn pull-inventory-item
  "Pull an :inventory-item with its facility / location / lot."
  [db eid]
  (d/pull db
          '[* {:inventory-item/facility [:facility/code :facility/name]
               :inventory-item/location [:facility-location/seq-id
                                         :facility-location/type]
               :inventory-item/lot [:lot/label]}]
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
               [?e :inventory-item/product ?p]
               [?e :inventory-item/facility ?f]]
             db product f)
        (d/q '[:find [?e ...] :in $ ?p
               :where [?e :inventory-item/product ?p]]
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
  (let [detail (cond-> {:inventory-detail/inventory-item inventory-item
                        :inventory-detail/effective-date (or effective-date (Date.))
                        :inventory-detail/qoh-diff qoh-diff
                        :inventory-detail/atp-diff (if (nil? atp-diff)
                                                     qoh-diff atp-diff)}
                 reason      (assoc :inventory-detail/reason reason)
                 description (assoc :inventory-detail/description description)
                 source      (assoc :inventory-detail/source source)
                 source-kind (assoc :inventory-detail/source-kind source-kind))]
    (d/transact conn [detail])))

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
        detail (cond-> {:inventory-detail/inventory-item item-id
                        :inventory-detail/effective-date eff
                        :inventory-detail/qoh-diff qty
                        :inventory-detail/atp-diff qty
                        :inventory-detail/source-kind :opening}
                 reason (assoc :inventory-detail/reason reason))
        tx-data (cond-> [detail]
                  (not existing) (conj (inventory-item-entity "inv-item" spec)))
        report (d/transact conn tx-data)]
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
                 :where [?d :inventory-detail/inventory-item ?item]]
               db* inventory-item)
          (map #(d/pull db* '[*] %))
          (sort-by :inventory-detail/effective-date)
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
  "Quantity-on-hand for a scope — `Σ :inventory-detail/qoh-diff`,
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
                  [?d :inventory-detail/inventory-item ?item]
                  [?d :inventory-detail/qoh-diff ?diff]
                  [?d :inventory-detail/effective-date ?ed]
                  [(.compareTo ^java.util.Date ?ed ?cutoff) ?cmp]
                  [(<= ?cmp 0)]]
                db* items (or as-of-valid kbt/forever))
           0M)))))
