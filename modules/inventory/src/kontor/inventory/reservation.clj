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
            [kontor.validation :as validation]
            [kontor.inventory.core :as inv])
  (:import [java.math BigDecimal]
           [java.util Date]))

;; ============================================================================
;; available-to-promise
;; ============================================================================

(defn atp-raw
  "`Σ :kontor.inventory-detail/atp-diff` over a scope — the raw
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
                  [?d :kontor.inventory-detail/inventory-item ?item]
                  [?d :kontor.inventory-detail/atp-diff ?diff]
                  [?d :kontor.inventory-detail/effective-date ?ed]
                  [(<= ?ed ?cutoff)]]
                db* items (or as-of-valid kbt/forever))
           0M)))))

(defn- safety-stock-of
  "The `:kontor.facility-product/safety-stock` for a (facility, product)
   pair — 0M when no policy row exists."
  ^BigDecimal [db facility product]
  (or (d/q '[:find ?ss .
             :in $ ?f ?p
             :where
             [?fp :kontor.facility-product/facility ?f]
             [?fp :kontor.facility-product/product ?p]
             [?fp :kontor.facility-product/safety-stock ?ss]]
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

   `:reserve-order-enum`: `:fifo-rec` / `:lifo-rec` order by the
   bucket's `:received-at`; `:fifo-exp` / `:lifo-exp` order by the
   lot's `:kontor.lot/expires-at` (ADR-060 — perishables; buckets with no
   lot expiry sort last)."
  [db product facility reserve-order-enum]
  (let [epoch (Date. 0)
        rows  (keep
               (fn [eid]
                 (let [it (d/pull db
                                  '[:kontor.inventory-item/status :kontor.inventory-item/kind
                                    :kontor.inventory-item/received-at
                                    {:kontor.inventory-item/location [:kontor.facility-location/type]}
                                    {:kontor.inventory-item/lot [:kontor.lot/expires-at]}]
                                  eid)]
                   (when (and (= :available (:kontor.inventory-item/status it))
                              (not= :serialized (:kontor.inventory-item/kind it)))
                     (let [atp (atp-raw db eid)]
                       (when (pos? (.signum atp))
                         {:item        eid
                          :atp         atp
                          :loc-rank    (location-rank
                                        (:kontor.facility-location/type
                                         (:kontor.inventory-item/location it)))
                          :received-at (or (:kontor.inventory-item/received-at it) epoch)
                          :expires-at  (:kontor.lot/expires-at (:kontor.inventory-item/lot it))})))))
               (inv/items-of db product facility))
        key-fn (case reserve-order-enum
                 :fifo-rec (fn [r] [(:loc-rank r) (.getTime ^Date (:received-at r))])
                 :lifo-rec (fn [r] [(:loc-rank r)
                                    (- (.getTime ^Date (:received-at r)))])
                 :fifo-exp (fn [r] [(:loc-rank r)
                                    (if-let [e (:expires-at r)]
                                      (.getTime ^Date e) Long/MAX_VALUE)])
                 :lifo-exp (fn [r] [(:loc-rank r)
                                    (if-let [e (:expires-at r)]
                                      (- (.getTime ^Date e)) Long/MIN_VALUE)])
                 (throw (ex-info "Unsupported :reserve-order-enum"
                                 {:type :inventory/unsupported-reserve-order
                                  :reserve-order-enum reserve-order-enum
                                  :supported #{:fifo-rec :lifo-rec :fifo-exp :lifo-exp}})))]
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

(declare reserve-tx-data)

(defn reserve!
  "Reserve `:quantity` of `:product` at `:facility` against an order
   line. Walks the candidate `:inventory-item` buckets (`:pickloc`
   first, sorted by `:reserve-order-enum`), drawing ATP from each;
   every draw appends an `:inventory-detail` (`:atp-diff` negative)
   and an `:inv-reservation` row, all in ONE transaction. Routes
   through the gate (ADR-068).

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
             :promised-date, :reserved-at (default now), :priority?,
             :vt-from / :vt-to (default :vt-from = :reserved-at).

   Returns {:reserved bigdec :backordered bigdec :draws [bigdec …]
            :tx-report report}.

   The pure tx-data builder is `reserve-tx-data` (ADR-068).
   Note: a pure back-order with NO existing bucket transparently
   calls `inv/find-or-create-inventory-item!` as a separate
   pre-transaction (also gated), keeping the main reserve commit
   single-shot. Folding that bucket creation into the main process
   is a future cleanup — option 1 from the ADR-068 reservation note."
  [conn {:keys [reserved-at vt-from vt-to] :as opts}]
  (let [reserved-at (or reserved-at (Date.))
        opts* (assoc opts :reserved-at reserved-at)
        {:keys [tx-data reserved backordered draws]}
        (reserve-tx-data conn opts*)
        report (when (seq tx-data)
                 (validation/transact-with-validation
                  conn (kbt/with-vt tx-data
                         (or vt-from reserved-at)
                         (or vt-to kbt/forever))))]
    {:reserved    reserved
     :backordered backordered
     :draws       draws
     :tx-report   report}))

(defn reserve-tx-data
  "Pure-ish tx-data builder for `reserve!` (ADR-068). Takes `conn`
   (not just `db`) because a pure back-order whose target bucket
   doesn't yet exist creates it via
   `inv/find-or-create-inventory-item!` as a separate pre-transaction
   — that helper is itself gated post-ADR-068, so the bucket-create
   path is correctly validated. The MAIN reservation tx-data returned
   here is then committed in one gated shot by the wrapper.

   Returns {:tx-data <vec or nil> :reserved bigdec :backordered bigdec
            :draws [bigdec …]}. `:tx-data` is nil iff there is nothing
   to write (quantity 0M was satisfied with no shortfall)."
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
          ;; when nothing was drawn — a freshly resolved bucket. The
          ;; find-or-create call below routes through the gate via
          ;; the ADR-068 wrapper in core.clj, so it's a separate
          ;; (but still gated) pre-transaction. See the docstring.
          bo-bucket (when backorder?
                      (or (:item (last draws))
                          (inv/find-or-create-inventory-item!
                           conn {:product product :facility f})))
          last-idx  (dec (count draws))
          reservation
          (fn [tempid item take qna?]
            (cond-> {:db/id tempid
                     :kontor.inv-reservation/order order
                     :kontor.inv-reservation/order-item order-item
                     :kontor.inv-reservation/ship-group ship-group
                     :kontor.inv-reservation/inventory-item item
                     :kontor.inv-reservation/quantity take
                     :kontor.inv-reservation/reserve-order-enum reserve-order-enum
                     :kontor.inv-reservation/reserved-datetime reserved-at}
              promised-date (assoc :kontor.inv-reservation/promised-datetime promised-date
                                   :kontor.inv-reservation/current-promised-date promised-date)
              priority?     (assoc :kontor.inv-reservation/priority? true)
              qna?          (assoc :kontor.inv-reservation/quantity-not-available shortfall)))
          atp-detail
          (fn [item ^BigDecimal amt res-tempid]
            {:kontor.inventory-detail/inventory-item item
             :kontor.inventory-detail/effective-date reserved-at
             :kontor.inventory-detail/qoh-diff 0M
             :kontor.inventory-detail/atp-diff (.negate amt)
             :kontor.inventory-detail/source res-tempid
             :kontor.inventory-detail/source-kind :reservation})
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
      {:tx-data     tx-data
       :reserved    (.subtract ^BigDecimal quantity ^BigDecimal shortfall)
       :backordered shortfall
       :draws       (mapv :take draws)})))

;; ============================================================================
;; release-reservation!
;; ============================================================================

(declare release-reservation-tx-data)

(defn release-reservation!
  "Release an `:inv-reservation` — append a compensating
   `:inventory-detail` (`:atp-diff` positive, restoring the ATP) and
   retract the reservation row. The restored quantity is
   `:quantity + :quantity-not-available` (a back-order consumed ATP
   too). `:effective-date` defaults to now. Routes through the gate
   (ADR-068). Returns the tx-report.

   Optional: :vt-from / :vt-to (default :vt-from = :effective-date).

   The pure tx-data builder is `release-reservation-tx-data` (ADR-068).
   The cancel-order! → release path research note 13 flagged as a P1."
  ([conn reservation-eid] (release-reservation! conn reservation-eid {}))
  ([conn reservation-eid {:keys [effective-date vt-from vt-to] :as opts}]
   (let [eff (or effective-date (Date.))]
     (validation/transact-with-validation
      conn (kbt/with-vt (release-reservation-tx-data
                         (d/db conn) reservation-eid
                         (assoc opts :effective-date eff))
                        (or vt-from eff) (or vt-to kbt/forever))))))

(defn release-reservation-tx-data
  "Pure tx-data builder for `release-reservation!` (ADR-068)."
  [db reservation-eid {:keys [effective-date]}]
  (let [r  (d/pull db [:kontor.inv-reservation/quantity
                       :kontor.inv-reservation/quantity-not-available
                       {:kontor.inv-reservation/inventory-item [:db/id]}]
                   reservation-eid)
        item (:db/id (:kontor.inv-reservation/inventory-item r))
        _ (when-not item
            (throw (ex-info "Reservation not found, or has no :inventory-item"
                            {:reservation reservation-eid})))
        released (.add ^BigDecimal (or (:kontor.inv-reservation/quantity r) 0M)
                       ^BigDecimal (or (:kontor.inv-reservation/quantity-not-available r) 0M))]
    [{:kontor.inventory-detail/inventory-item item
      :kontor.inventory-detail/effective-date (or effective-date (Date.))
      :kontor.inventory-detail/qoh-diff 0M
      :kontor.inventory-detail/atp-diff released
      :kontor.inventory-detail/source-kind :reservation
      :kontor.inventory-detail/description "Reservation released"}
     [:db/retractEntity reservation-eid]]))
