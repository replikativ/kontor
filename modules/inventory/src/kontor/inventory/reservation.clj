(ns kontor.inventory.reservation
  "kontor-inventory — available-to-promise + the reservation bridge
   (ADR-058).

   `:inv-reservation` is `modules/sales`-owned schema; `reserve!`
   lives HERE because kontor-inventory owns the availability picture
   (research note 36 §3). A `reserve!` call walks the candidate
   `:inventory-item` buckets — `:pickloc` locations first, then
   `:bulk`, then no-location — sorted by `:reserve-order-enum`,
   drawing ATP from each. Each draw appends an `:inventory-detail`
   (`:atp-diff` negative, `:qoh-diff` 0 — the stock is still
   physically there, just promised) and an `:inv-reservation` row.

   Because the reservation's effect IS an `:atp-diff` detail,
   available-to-promise is a pure derivation over the same ledger
   `on-hand-qty` reads — `atp-raw` = `Σ :atp-diff`, with
   reservations already netted. No separate reservation-scan.

   v1 ATP netting (maintainer-confirmed, research note 36):
   `on-hand − reservations − safety-stock`. Scheduled receipts +
   in-transit transfers are a documented follow-up."
  (:require [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.inventory.core :as inv])
  (:import [java.math BigDecimal]
           [java.util Date]))

;; ============================================================================
;; available-to-promise
;; ============================================================================

(defn atp-raw
  "`Σ :inventory-detail/atp-diff` over a scope — the raw
   available-to-promise. Outstanding reservations are ALREADY netted
   (a reservation appends an `:atp-diff` negative detail), so this is
   `on-hand − reservations`. Bitemporal, same contract as
   `kontor.inventory.core/on-hand-qty`. `spec` is an `:inventory-item`
   eid or `{:product P :facility F?}`."
  (^BigDecimal [db spec] (atp-raw db spec {}))
  (^BigDecimal [db spec {:keys [as-of-valid as-of-tx]}]
   (let [db*   (if as-of-tx (d/as-of db as-of-tx) db)
         items (inv/resolve-scope db* spec)]
     (if (empty? items)
       0M
       (or (d/q '[:find (sum ?diff) .
                  :with ?d
                  :in $ [?item ...] ?cutoff
                  :where
                  [?d :inventory-detail/inventory-item ?item]
                  [?d :inventory-detail/atp-diff ?diff]
                  [?d :inventory-detail/effective-date ?ed]
                  [(.compareTo ^java.util.Date ?ed ?cutoff) ?cmp]
                  [(<= ?cmp 0)]]
                db* items (or as-of-valid kbt/forever))
           0M)))))

(defn- safety-stock-of
  "The `:facility-product/safety-stock` for a (facility, product)
   pair — 0M when no policy row exists."
  ^BigDecimal [db facility product]
  (or (d/q '[:find ?ss .
             :in $ ?f ?p
             :where
             [?fp :facility-product/facility ?f]
             [?fp :facility-product/product ?p]
             [?fp :facility-product/safety-stock ?ss]]
           db facility product)
      0M))

(defn available-to-promise
  "Available-to-promise for a `(product, facility)` scope:
   `atp-raw − safety-stock`. The maintainer-confirmed v1 netting
   (research note 36): `on-hand − reservations − safety-stock`.

   `spec` is `{:product P :facility F}` — both required (safety
   stock is a per-(facility, product) quantity; `:facility` may be a
   code or eid). Bitemporal opts as `atp-raw`."
  (^BigDecimal [db spec] (available-to-promise db spec {}))
  (^BigDecimal [db {:keys [product facility] :as spec} opts]
   (when-not (and product facility)
     (throw (ex-info "available-to-promise needs :product and :facility"
                     {:spec spec})))
   (let [f   (inv/resolve-facility db facility)
         raw (atp-raw db {:product product :facility f} opts)
         ss  (safety-stock-of db f product)]
     (.subtract raw ss))))

;; ============================================================================
;; The reservation walk
;; ============================================================================

(defn- location-rank
  "Sort rank for the reservation walk — :pickloc before :bulk before
   :staging before no-location."
  ^long [loc-type]
  (case loc-type :pickloc 0 :bulk 1 :staging 2 3))

(defn- candidate-buckets
  "Resolve + sort the `:inventory-item` buckets to draw from for a
   `(product, facility)` reservation. Keeps `:available`,
   `:non-serial` buckets with `atp-raw > 0`; sorts `:pickloc`
   locations first, then by `reserve-order-enum`. Returns
   `[{:item eid :atp bigdec} …]`.

   v1 supports `:fifo-rec` / `:lifo-rec` (by `:received-at`). The
   expiry-driven `:fifo-exp` / `:lifo-exp` need `:lot/expires-at`,
   which ADR-060 ships — an unknown enum throws until then."
  [db product facility reserve-order-enum]
  (let [epoch (Date. 0)
        rows  (keep
               (fn [eid]
                 (let [it (d/pull db
                                  '[:inventory-item/status :inventory-item/kind
                                    :inventory-item/received-at
                                    {:inventory-item/location [:facility-location/type]}]
                                  eid)]
                   (when (and (= :available (:inventory-item/status it))
                              (not= :serialized (:inventory-item/kind it)))
                     (let [atp (atp-raw db eid)]
                       (when (pos? (.signum atp))
                         {:item        eid
                          :atp         atp
                          :loc-rank    (location-rank
                                        (:facility-location/type
                                         (:inventory-item/location it)))
                          :received-at (or (:inventory-item/received-at it) epoch)})))))
               (inv/items-of db product facility))
        key-fn (case reserve-order-enum
                 :fifo-rec (fn [r] [(:loc-rank r) (.getTime ^Date (:received-at r))])
                 :lifo-rec (fn [r] [(:loc-rank r)
                                    (- (.getTime ^Date (:received-at r)))])
                 (throw (ex-info "Unsupported :reserve-order-enum — v1 supports :fifo-rec / :lifo-rec; :fifo-exp / :lifo-exp arrive with ADR-060"
                                 {:type :inventory/unsupported-reserve-order
                                  :reserve-order-enum reserve-order-enum})))]
    (sort-by key-fn rows)))

(defn- walk-draws
  "Walk the sorted buckets, drawing `take = min(remaining, bucket-atp)`
   from each until `quantity` is satisfied or the buckets run out.
   Returns `{:draws [{:item :take}] :shortfall bigdec}`."
  [buckets ^BigDecimal quantity]
  (loop [[{:keys [item atp]} & more] buckets
         remaining quantity
         draws     []]
    (cond
      (not (pos? (.signum ^BigDecimal remaining)))
      {:draws draws :shortfall 0M}

      (nil? item)
      {:draws draws :shortfall remaining}

      :else
      (let [take (if (<= (.compareTo ^BigDecimal remaining ^BigDecimal atp) 0)
                   remaining atp)]
        (recur more
               (.subtract ^BigDecimal remaining ^BigDecimal take)
               (conj draws {:item item :take take}))))))

(defn reserve!
  "Reserve `:quantity` of `:product` at `:facility` against an order
   line. Walks the candidate `:inventory-item` buckets (`:pickloc`
   first, sorted by `:reserve-order-enum`), drawing ATP from each;
   every draw appends an `:inventory-detail` (`:atp-diff` negative)
   and an `:inv-reservation` row, all in ONE transaction.

   A shortfall — when `:require-inventory?` is false (the default) —
   is back-ordered: the last drawn reservation row carries
   `:quantity-not-available`, and an extra negative-`:atp-diff`
   detail drives that bucket's ATP negative. When nothing could be
   drawn at all, one back-order reservation is created against a
   resolved bucket. With `:require-inventory? true`, a shortfall
   throws `:inventory/insufficient-atp` and writes nothing.

   Required opts: :product, :facility, :quantity, :order,
                  :order-item, :ship-group.
   Optional: :reserve-order-enum (default :fifo-rec),
             :require-inventory? (default false),
             :promised-date, :reserved-at (default now), :priority?.

   Returns {:reserved bigdec :backordered bigdec :draws [bigdec …]
            :tx-report report}."
  [conn {:keys [product facility quantity order order-item ship-group
                reserve-order-enum require-inventory? promised-date
                reserved-at priority?]
         :or {reserve-order-enum :fifo-rec require-inventory? false}}]
  (when-not product     (throw (ex-info ":product required" {})))
  (when-not facility    (throw (ex-info ":facility required" {})))
  (when (nil? quantity) (throw (ex-info ":quantity required" {})))
  (when-not order       (throw (ex-info ":order required" {})))
  (when-not order-item  (throw (ex-info ":order-item required" {})))
  (when-not ship-group  (throw (ex-info ":ship-group required" {})))
  (let [db (d/db conn)
        f  (inv/resolve-facility db facility)
        _  (when-not f (throw (ex-info "Facility not found" {:spec facility})))
        reserved-at (or reserved-at (Date.))
        buckets (candidate-buckets db product f reserve-order-enum)
        {:keys [draws shortfall]} (walk-draws buckets quantity)
        backorder? (pos? (.signum ^BigDecimal shortfall))]
    (when (and require-inventory? backorder?)
      (throw (ex-info "Insufficient ATP for a require-inventory reservation"
                      {:type      :inventory/insufficient-atp
                       :product   product :facility f
                       :requested quantity :shortfall shortfall})))
    (let [;; Where the back-order lands: the last drawn bucket, or —
          ;; when nothing was drawn — a freshly resolved bucket.
          bo-bucket (when backorder?
                      (or (:item (last draws))
                          (inv/find-or-create-inventory-item!
                           conn {:product product :facility f})))
          last-idx  (dec (count draws))
          reservation
          (fn [tempid item take qna?]
            (cond-> {:db/id tempid
                     :inv-reservation/order order
                     :inv-reservation/order-item order-item
                     :inv-reservation/ship-group ship-group
                     :inv-reservation/inventory-item item
                     :inv-reservation/quantity take
                     :inv-reservation/reserve-order-enum reserve-order-enum
                     :inv-reservation/reserved-datetime reserved-at}
              promised-date (assoc :inv-reservation/promised-datetime promised-date
                                   :inv-reservation/current-promised-date promised-date)
              priority?     (assoc :inv-reservation/priority? true)
              qna?          (assoc :inv-reservation/quantity-not-available shortfall)))
          atp-detail
          (fn [item ^BigDecimal amt res-tempid]
            {:inventory-detail/inventory-item item
             :inventory-detail/effective-date reserved-at
             :inventory-detail/qoh-diff 0M
             :inventory-detail/atp-diff (.negate amt)
             :inventory-detail/source res-tempid
             :inventory-detail/source-kind :reservation})
          tx-data
          (if (empty? draws)
            ;; Nothing on hand — a pure back-order against bo-bucket.
            (when backorder?
              [(reservation "res-bo" bo-bucket 0M true)
               (atp-detail bo-bucket shortfall "res-bo")])
            ;; One reservation + atp-detail per draw; the last draw's
            ;; row carries the back-order remainder (no tuple
            ;; collision — same entity, extra attr + extra detail).
            (vec
             (mapcat
              (fn [i {:keys [item take]}]
                (let [res-tempid (str "res-" i)
                      last? (= i last-idx)
                      qna?  (and last? backorder?)]
                  (cond-> [(reservation res-tempid item take qna?)
                           (atp-detail item take res-tempid)]
                    qna? (conj (atp-detail item shortfall res-tempid)))))
              (range)
              draws)))]
      {:reserved    (.subtract ^BigDecimal quantity ^BigDecimal shortfall)
       :backordered shortfall
       :draws       (mapv :take draws)
       :tx-report   (when (seq tx-data) (d/transact conn tx-data))})))

;; ============================================================================
;; release-reservation!
;; ============================================================================

(defn release-reservation!
  "Release an `:inv-reservation` — append a compensating
   `:inventory-detail` (`:atp-diff` positive, restoring the ATP) and
   retract the reservation row. The restored quantity is
   `:quantity + :quantity-not-available` (a back-order consumed ATP
   too). Returns the tx-report. The cancel-order! → release path
   research note 13 flagged as a P1."
  [conn reservation-eid]
  (let [db (d/db conn)
        r  (d/pull db [:inv-reservation/quantity
                       :inv-reservation/quantity-not-available
                       {:inv-reservation/inventory-item [:db/id]}]
                   reservation-eid)
        item (:db/id (:inv-reservation/inventory-item r))
        _ (when-not item
            (throw (ex-info "Reservation not found, or has no :inventory-item"
                            {:reservation reservation-eid})))
        released (.add ^BigDecimal (or (:inv-reservation/quantity r) 0M)
                       ^BigDecimal (or (:inv-reservation/quantity-not-available r) 0M))]
    (d/transact conn
                [{:inventory-detail/inventory-item item
                  :inventory-detail/effective-date (Date.)
                  :inventory-detail/qoh-diff 0M
                  :inventory-detail/atp-diff released
                  :inventory-detail/source-kind :reservation
                  :inventory-detail/description "Reservation released"}
                 [:db/retractEntity reservation-eid]])))
