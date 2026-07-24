(ns kontor.inventory.ops
  "kontor-inventory — receive / issue / transfer operations + GL
   integration + the negative-inventory policy (ADR-059).

   The defining guarantee: `receive!` / `issue!` write BOTH halves in
   ONE transaction — the valuation layer + GL postings (via
   `kontor.posting/plan-stock-move`, ADR-030) AND the physical
   `:inventory-item` / `:inventory-detail` — linked by
   `:kontor.inventory-detail/transaction`. The physical and financial views
   cannot drift, because they are written together and the GL is
   *only* ever touched through `plan-stock-move` — the discipline
   that makes subledger-vs-GL drift structurally hard.

   Negative inventory is a per-(facility, product) policy
   (`:kontor.facility-product/negative-allowed?`): an over-issue is refused
   by default, or — when allowed — creates an explicit negative-fill
   `:valuation-layer` so the issue still has a layer to consume, with
   `true-up-negative-fill!` reconciling the estimate to actual later.

   Transfers are two-phase (`transfer!` → `complete-transfer!`) and
   GL-free — a same-entity move is a pure quantity event;
   cross-entity transfers with a GL leg are a documented follow-up."
  (:require [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.provider.costing-provider :as costing]
            [kontor.posting :as posting]
            [kontor.workflow.process :as process]
            [kontor.validation :as validation]
            [kontor.provider.valuation :as valuation]
            [kontor.inventory.core :as inv])
  (:import [clojure.lang ExceptionInfo]
           [java.util Date]))

;; ============================================================================
;; Internals
;; ============================================================================

(defn provider-for-book
  "Resolve a `CostingProvider` from a valuation book's
   `:kontor.valuation-book/cost-method`. `:standard` books must pass
   `:provider` explicitly (it needs a standard-cost-fn)."
  [db book]
  (let [eid    (valuation/resolve-book db book)
        method (:kontor.valuation-book/cost-method
                (d/pull db [:kontor.valuation-book/cost-method] eid))]
    (costing/provider-for method)))

(defn- insufficient-stock?
  "True iff `e` is the `plan-stock-move` underflow exception."
  [e]
  (and (instance? ExceptionInfo e)
       (re-find #"insufficient stock" (or (.getMessage ^ExceptionInfo e) ""))))

(defn seal-stock-move
  "Stamp a `plan-stock-move` tx-data vector as posted + sealed: the
   transaction gets `:kontor.transaction/state :posted` + `:posted-at`, and
   every posting gets `:kontor.posting/posted-at` — the propagation the
   `kontor.compliance.sealing` model expects. A goods receipt / issue / count
   adjustment is a real posted GL event, not a draft."
  [tx-data ^Date posted-at]
  (mapv (fn [m]
          (cond
            (not (map? m)) m
            (contains? m :kontor.transaction/journal)
            (assoc m :kontor.transaction/state :posted
                   :kontor.transaction/posted-at posted-at)
            (contains? m :kontor.posting/account)
            (assoc m :kontor.posting/posted-at posted-at)
            :else m))
        tx-data))

;; ============================================================================
;; receive!
;; ============================================================================

(declare receive-tx-data)

(defn receive!
  "Receive goods. Appends BOTH halves in ONE transaction: the
   valuation layer + GL postings (`plan-stock-move :direction :in`)
   AND the physical `:inventory-detail` against the
   `(product, facility, location, lot, owner)` bucket — linked by
   `:kontor.inventory-detail/transaction`. Routes through the gate (ADR-068).

   Required: :product, :facility, :book (valuation-book), :qty,
             :unit-cost, :commodity, :journal, :account-fn.
   Optional: :location, :lot, :owner-entity, :provider (default:
             resolved from the book's :cost-method), :effective-date
             (default now), :ledger, :narration, :reason,
             :vt-from / :vt-to (default :vt-from = :effective-date).

   Returns {:inventory-item eid :transaction eid :tx-report report}.

   The pure tx-data builder is `receive-tx-data` (ADR-068)."
  [conn {:keys [effective-date vt-from vt-to] :as opts}]
  (let [db  (d/db conn)
        eff (or effective-date (Date.))
        opts* (assoc opts :effective-date eff)
        {:keys [tx-data existing-item-id]}
        (receive-tx-data db opts*)
        report (validation/transact-with-validation
                conn (kbt/with-vt tx-data
                       (or vt-from eff) (or vt-to kbt/forever)))]
    {:inventory-item (or existing-item-id (get-in report [:tempids "inv-item"]))
     :transaction    (get-in report [:tempids -1])
     :tx-report      report}))

(defn receive-tx-data
  "Pure tx-data builder for `receive!` (ADR-068). Returns
   `{:tx-data <vec> :existing-item-id <eid-or-nil>}` — `:existing-item-id`
   is the resolved bucket eid when it pre-existed, nil when the
   builder created it via tempid `\"inv-item\"`. The GL transaction
   tempid is `-1` (`plan-stock-move`'s convention).

   `:effective-date` (default `(Date.)`) is required by the builder
   contract — the wrapper threads it for determinism. Optional
   `:tempid` (default `\"inv-item\"`) overrides the bucket tempid for
   composition."
  [db {:keys [product facility location lot owner-entity book qty
              unit-cost commodity journal account-fn provider
              effective-date ledger narration reason tempid]
       :or   {tempid "inv-item"}}]
  (when-not product   (throw (ex-info ":product required" {})))
  (when-not facility  (throw (ex-info ":facility required" {})))
  (when-not book      (throw (ex-info ":book required" {})))
  (when (nil? qty)    (throw (ex-info ":qty required" {})))
  (when (nil? unit-cost) (throw (ex-info ":unit-cost required" {})))
  (when-not commodity (throw (ex-info ":commodity required" {})))
  (when-not journal   (throw (ex-info ":journal required" {})))
  (when-not account-fn (throw (ex-info ":account-fn required" {})))
  (let [f    (inv/resolve-facility db facility)
        _    (when-not f (throw (ex-info "Facility not found" {:spec facility})))
        eff  (or effective-date (Date.))
        prov (or provider (provider-for-book db book))
        ;; Plan FIRST — `plan-stock-move` is pure and throws on a
        ;; config error before anything is created, so a failure
        ;; leaves no orphan :inventory-item bucket (review-fix CR).
        move-tx (posting/plan-stock-move
                 db
                 (cond-> {:direction :in :book book :item product :qty qty
                          :commodity commodity :lot lot :journal journal
                          :effective-date eff :unit-cost unit-cost
                          :provider prov :account-fn account-fn}
                   ledger    (assoc :ledger ledger)
                   narration (assoc :narration narration)))
        bucket-spec {:product product :facility f :location location
                     :lot lot :owner-entity owner-entity :received-at eff}
        existing (inv/find-inventory-item db bucket-spec)
        item-id  (or existing tempid)
        item-entity (when-not existing
                      (inv/inventory-item-entity tempid bucket-spec))
        detail (cond-> {:kontor.inventory-detail/inventory-item item-id
                        :kontor.inventory-detail/effective-date eff
                        :kontor.inventory-detail/qoh-diff qty
                        :kontor.inventory-detail/atp-diff qty
                        :kontor.inventory-detail/source-kind :receipt
                        ;; -1 is plan-stock-move's transaction tempid.
                        :kontor.inventory-detail/transaction -1}
                 reason (assoc :kontor.inventory-detail/reason reason))]
    {:tx-data (cond-> (conj (vec (seal-stock-move move-tx eff)) detail)
                item-entity (conj item-entity))
     :existing-item-id existing}))

;; ============================================================================
;; Negative-fill
;; ============================================================================

(defn- negative-allowed?
  "The `:kontor.facility-product/negative-allowed?` flag for a
   (facility, product) pair — false when no policy row exists."
  [db facility product]
  (boolean
   (d/q '[:find ?na .
          :in $ ?f ?p
          :where
          [?fp :kontor.facility-product/facility ?f]
          [?fp :kontor.facility-product/product ?p]
          [?fp :kontor.facility-product/negative-allowed? ?na]]
        db facility product)))

(defn- negative-fill-tx-data
  "Pure tx-data builder for the negative-fill fragment (ADR-067) —
   the posting-less origin `:transaction`, the negative-fill
   `:valuation-layer` (at `estimated-unit-cost`), and the
   `:negative-fill` record. When `:inventory-item` is nil the
   physical bucket does not exist yet, so its entity is included in
   the SAME fragment (no orphan bucket). All tempids are strings so
   the consuming step can reference the bucket by `\"nf-item\"`.

   Tempids emitted: `\"nf-tx\"`, `\"nf-layer\"`, `\"nf\"`, and
   `\"nf-item\"` when the bucket is created here.

   The just-created layer's speculative-db eid is order-stable with
   the final commit (datahike's tempid resolution is deterministic
   from `max-eid + 1` in encounter order), so
   `plan-stock-move` reading the speculative db in the next step gets
   the eid that the consumption fragment can reference safely."
  [db {:keys [product facility book commodity estimated-unit-cost lot
              location owner-entity inventory-item journal effective-date]}
   shortfall]
  (when (nil? estimated-unit-cost)
    (throw (ex-info ":estimated-unit-cost required for a negative-fill over-issue"
                    {:type :inventory/estimated-cost-required})))
  (when-not journal
    (throw (ex-info ":journal required for a negative-fill over-issue" {})))
  (let [book-eid (valuation/resolve-book db book)
        eff (or effective-date (Date.))
        item-id (or inventory-item "nf-item")
        item-entity (when-not inventory-item
                      (inv/inventory-item-entity
                       "nf-item"
                       {:product product :facility facility :location location
                        :lot lot :owner-entity owner-entity :received-at eff}))
        origin-tx {:db/id "nf-tx"
                   :kontor.transaction/journal journal
                   :kontor.transaction/effective-date eff
                   :kontor.transaction/state :posted
                   :kontor.transaction/posted-at eff
                   :kontor.transaction/narration "Negative-fill layer (estimated cost)"}
        layer (cond-> {:db/id "nf-layer"
                       :kontor.valuation-layer/book book-eid
                       :kontor.valuation-layer/item product
                       :kontor.valuation-layer/origin-transaction "nf-tx"
                       :kontor.valuation-layer/qty-original shortfall
                       :kontor.valuation-layer/unit-cost-original estimated-unit-cost
                       :kontor.valuation-layer/commodity commodity
                       :kontor.valuation-layer/received-at eff
                       :kontor.valuation-layer/note "negative-fill (estimated cost)"}
                lot (assoc :kontor.valuation-layer/lot lot))
        nf {:db/id "nf"
            :kontor.negative-fill/inventory-item item-id
            :kontor.negative-fill/valuation-layer "nf-layer"
            :kontor.negative-fill/shortfall-qty shortfall
            :kontor.negative-fill/estimated-unit-cost estimated-unit-cost
            :kontor.negative-fill/commodity commodity
            :kontor.negative-fill/status :open
            :kontor.negative-fill/created-at eff}]
    (cond-> [origin-tx layer nf]
      item-entity (conj item-entity))))

;; ============================================================================
;; issue!
;; ============================================================================

(defn- resolve-issue-bucket
  "Resolve the EXISTING `:inventory-item` bucket for an issue, or nil.
   An explicit `:inventory-item` or the `:reservation`'s bucket are
   existing eids; otherwise FIND (not create) by the (product,
   facility, location, lot, owner) key. A nil result means the
   bucket must be created inside the issue's atomic tx — or, for an
   over-issue, by `create-negative-fill!` (review-fix CR)."
  [db {:keys [inventory-item reservation product facility location
              lot owner-entity]}]
  (cond
    inventory-item inventory-item
    reservation    (:db/id (:kontor.inv-reservation/inventory-item
                            (d/pull db [{:kontor.inv-reservation/inventory-item [:db/id]}]
                                    reservation)))
    :else (inv/find-inventory-item db {:product product :facility facility
                                       :location location :lot lot
                                       :owner-entity owner-entity})))

(defn issue!
  "Issue goods. Appends BOTH halves in ONE transaction: the
   layer-consumption + GL postings (`plan-stock-move :direction
   :out`) AND the physical `:inventory-detail`.

   When `:reservation` (an `:inv-reservation` eid) is supplied the
   reservation is *realized*: it is retracted and the physical detail
   carries `:atp-diff 0` (the reservation already dropped ATP). A
   plain issue carries `:atp-diff -qty`.

   Negative-inventory policy: if the issue over-draws and the
   `(facility, product)` `:kontor.facility-product/negative-allowed?` is
   false (default), throws `:inventory/negative-not-allowed`. When
   true, a negative-fill `:valuation-layer` is created first (at
   `:estimated-unit-cost`) + a `:negative-fill` record — so the issue
   has a layer to consume — and the issue proceeds (TWO transactions
   in that case: the negative-fill, then the issue). See
   `true-up-negative-fill!`.

   Required: :product, :facility, :book, :qty, :commodity, :journal,
             :account-fn.
   Optional: :inventory-item, :reservation, :lot, :location,
             :owner-entity, :provider, :estimated-unit-cost (required
             iff a negative-fill happens), :effective-date, :ledger,
             :narration, :reason.

   Returns {:inventory-item eid :transaction eid :negative-fill eid?
            :tx-report report}."
  [conn {:keys [product facility book qty commodity journal account-fn
                provider reservation effective-date ledger narration reason]
         :as spec}]
  (when-not product   (throw (ex-info ":product required" {})))
  (when-not facility  (throw (ex-info ":facility required" {})))
  (when-not book      (throw (ex-info ":book required" {})))
  (when (nil? qty)    (throw (ex-info ":qty required" {})))
  (when-not commodity (throw (ex-info ":commodity required" {})))
  (when-not journal   (throw (ex-info ":journal required" {})))
  (when-not account-fn (throw (ex-info ":account-fn required" {})))
  (let [db   (d/db conn)
        f    (inv/resolve-facility db facility)
        _    (when-not f (throw (ex-info "Facility not found" {:spec facility})))
        eff  (or effective-date (Date.))
        existing (resolve-issue-bucket db (assoc spec :facility f))
        prov (or provider (provider-for-book db book))
        move-spec (cond-> {:direction :out :book book :item product :qty qty
                           :commodity commodity :journal journal
                           :effective-date eff :provider prov
                           :account-fn account-fn}
                    ledger    (assoc :ledger ledger)
                    narration (assoc :narration narration))
        ;; Trial on db0: does plan-stock-move have enough committed
        ;; stock? If not + policy allows, plan a negative-fill step.
        underflow (try
                    (posting/plan-stock-move db move-spec)
                    nil
                    (catch ExceptionInfo e
                      (if (insufficient-stock? e)
                        (:underflow (ex-data e))
                        (throw e))))
        need-neg-fill? (some? underflow)
        _ (when need-neg-fill?
            (when-not (negative-allowed? db f product)
              (throw (ex-info "Issue would over-draw and negative inventory is not allowed for this (facility, product)"
                              {:type :inventory/negative-not-allowed
                               :product product :facility f
                               :underflow underflow}))))
        ;; Bucket-id: either existing eid, or the tempid of the entity
        ;; we will create in the same process (nf-item in step 1 if
        ;; need-neg-fill?, issue-item in step 2 otherwise).
        item-id (cond
                  existing existing
                  need-neg-fill? "nf-item"
                  :else "issue-item")
        ;; Step 1 (conditional): the negative-fill fragment. The layer
        ;; gets a stable speculative-db eid so step 2's
        ;; plan-stock-move output references it correctly.
        neg-fill-step
        (when need-neg-fill?
          (fn [sdb _ctx]
            (negative-fill-tx-data
             sdb (assoc spec :facility f :inventory-item existing
                        :effective-date eff)
             underflow)))
        ;; Step 2: plan-stock-move against the speculative db (which
        ;; has step 1's frag applied) — for the underflow case
        ;; plan-consumption now sees the negative-fill layer and
        ;; consumes it. Emits the issue's :transaction (-1) +
        ;; postings + consumption-entities, then we append the
        ;; physical inventory-detail + optional new-bucket entity +
        ;; back-ref to nf + reservation retraction.
        issue-step
        (fn [sdb _ctx]
          (let [move-tx (posting/plan-stock-move sdb move-spec)
                bucket-entity (when (and (nil? existing) (not need-neg-fill?))
                                (inv/inventory-item-entity
                                 "issue-item"
                                 {:product product :facility f
                                  :location (:location spec)
                                  :lot (:lot spec)
                                  :owner-entity (:owner-entity spec)
                                  :received-at eff}))
                detail (cond-> {:kontor.inventory-detail/inventory-item item-id
                                :kontor.inventory-detail/effective-date eff
                                :kontor.inventory-detail/qoh-diff
                                (.negate ^java.math.BigDecimal qty)
                                ;; A reservation already dropped ATP —
                                ;; realizing it moves QOH only. A plain
                                ;; issue moves both.
                                :kontor.inventory-detail/atp-diff
                                (if reservation 0M
                                    (.negate ^java.math.BigDecimal qty))
                                :kontor.inventory-detail/source-kind :issuance
                                :kontor.inventory-detail/transaction -1}
                         reason (assoc :kontor.inventory-detail/reason reason))]
            (cond-> (conj (vec (seal-stock-move move-tx eff)) detail)
              bucket-entity (conj bucket-entity)
              ;; Link the negative-fill back to the originating issue tx.
              need-neg-fill? (conj {:db/id "nf"
                                    :kontor.negative-fill/origin-issue -1})
              ;; Realize (consume) the reservation — retract it.
              reservation (conj [:db/retractEntity reservation]))))
        steps (cond-> []
                neg-fill-step (conj neg-fill-step)
                :always       (conj issue-step))
        report (process/run-process conn
                                    {:steps steps
                                     :vt-from eff :vt-to nil})
        tempids (:tempids report)]
    (cond-> {:inventory-item (or existing
                                 (get tempids "nf-item")
                                 (get tempids "issue-item"))
             :transaction    (get tempids -1)
             :tx-report      report}
      need-neg-fill? (assoc :negative-fill (get tempids "nf")))))

;; ============================================================================
;; true-up-negative-fill!
;; ============================================================================

(declare true-up-negative-fill-tx-data)

(defn true-up-negative-fill!
  "Reconcile an `:open` `:negative-fill` to actual cost. Emits a
   `:layer-adjustment` on the negative-fill layer for the cost delta
   `(actual − estimated) × shortfall-qty` + a balanced GL correction
   (`Dr :variance-account / Cr :inventory-account` for a positive
   delta), links the adjustment back via
   `:kontor.negative-fill/true-up-adjustment`, and marks the
   `:negative-fill` `:trued-up`. Routes through the gate (ADR-068).

   Note the negative-fill layer was already fully consumed by the
   issue retry — so the `:layer-adjustment` carries NO remaining
   costing effect; it is an audit marker, and the GL correction
   posting is what actually moves COGS↔inventory. The exact account
   routing of that correction is a simplification — a consumer /
   l10n module with stricter requirements posts its own correction
   and just stamps the `:layer-adjustment`.

   Required: :negative-fill (eid), :actual-unit-cost, :journal,
             :inventory-account, :variance-account.
   Optional: :effective-date (default now),
             :vt-from / :vt-to (default :vt-from = :effective-date).
   Returns the tx-report.

   The pure tx-data builder is `true-up-negative-fill-tx-data` (ADR-068)."
  [conn {:keys [effective-date vt-from vt-to] :as opts}]
  (let [eff (or effective-date (Date.))]
    (validation/transact-with-validation
     conn (kbt/with-vt (true-up-negative-fill-tx-data
                        (d/db conn) (assoc opts :effective-date eff))
                       (or vt-from eff) (or vt-to kbt/forever)))))

(defn true-up-negative-fill-tx-data
  "Pure tx-data builder for `true-up-negative-fill!` (ADR-068)."
  [db {:keys [negative-fill actual-unit-cost journal inventory-account
              variance-account effective-date]}]
  (when-not negative-fill   (throw (ex-info ":negative-fill required" {})))
  (when (nil? actual-unit-cost) (throw (ex-info ":actual-unit-cost required" {})))
  (when-not journal         (throw (ex-info ":journal required" {})))
  (when-not inventory-account (throw (ex-info ":inventory-account required" {})))
  (when-not variance-account  (throw (ex-info ":variance-account required" {})))
  (let [nf (d/pull db [:kontor.negative-fill/status
                       :kontor.negative-fill/shortfall-qty
                       :kontor.negative-fill/estimated-unit-cost
                       {:kontor.negative-fill/commodity [:db/id]}
                       {:kontor.negative-fill/valuation-layer [:db/id]}]
                   negative-fill)
        _ (when-not (= :open (:kontor.negative-fill/status nf))
            (throw (ex-info "Negative-fill is not :open"
                            {:type :inventory/negative-fill-not-open
                             :negative-fill negative-fill
                             :status (:kontor.negative-fill/status nf)})))
        eff (or effective-date (Date.))
        shortfall (:kontor.negative-fill/shortfall-qty nf)
        delta-unit (.subtract ^java.math.BigDecimal actual-unit-cost
                              ^java.math.BigDecimal (:kontor.negative-fill/estimated-unit-cost nf))
        delta-total (.multiply ^java.math.BigDecimal delta-unit
                               ^java.math.BigDecimal shortfall)
        commodity (:db/id (:kontor.negative-fill/commodity nf))
        layer (:db/id (:kontor.negative-fill/valuation-layer nf))
        ;; GL correction — Dr variance / Cr inventory for a positive
        ;; delta (the estimate under-stated cost); reversed when
        ;; negative. build-transaction enforces sum-to-zero.
        gl (posting/build-transaction
            {:transaction {:kontor.transaction/journal journal
                           :kontor.transaction/effective-date eff
                           :kontor.transaction/narration "Negative-fill true-up"}
             :postings [{:kontor.posting/account variance-account
                         :kontor.posting/amount delta-total
                         :kontor.posting/commodity commodity}
                        {:kontor.posting/account inventory-account
                         :kontor.posting/amount (.negate ^java.math.BigDecimal delta-total)
                         :kontor.posting/commodity commodity}]})
        adjustment {:db/id "adj"
                    :kontor.layer-adjustment/layer layer
                    :kontor.layer-adjustment/amount delta-total
                    :kontor.layer-adjustment/reason :correction
                    :kontor.layer-adjustment/origin-transaction -1
                    :kontor.layer-adjustment/applied-at eff
                    :kontor.layer-adjustment/note "Negative-fill estimate → actual"}]
    (into (vec gl)
          [adjustment
           {:db/id negative-fill
            :kontor.negative-fill/status :trued-up
            :kontor.negative-fill/true-up-adjustment "adj"}])))

;; ============================================================================
;; Transfers — two-phase, GL-free (same-entity quantity moves)
;; ============================================================================

(declare transfer-tx-data)

(defn transfer!
  "Begin a two-phase transfer — send `:quantity` of an
   `:inventory-item` toward another facility/location. Creates an
   `:inventory-transfer` (`:status :in-transit`) and appends an
   `:inventory-detail` (`:qoh-diff -qty`, `:atp-diff -qty`) on the
   SOURCE bucket: the stock is 'on the truck' — off the source, not
   yet at the destination. GL-free (a same-entity move is a pure
   quantity event; cross-entity transfers with a GL leg are a
   documented follow-up). Routes through the gate (ADR-068).

   Required: :inventory-item, :quantity, :to-facility.
   Optional: :to-location, :send-date (default now), :note,
             :vt-from / :vt-to (default :vt-from = :send-date).
   Returns {:transfer eid :tx-report report}.

   The pure tx-data builder is `transfer-tx-data` (ADR-068)."
  [conn {:keys [send-date vt-from vt-to] :as opts}]
  (let [sent (or send-date (Date.))
        report (validation/transact-with-validation
                conn (kbt/with-vt (transfer-tx-data
                                   (d/db conn) (assoc opts :send-date sent))
                                  (or vt-from sent) (or vt-to kbt/forever)))]
    {:transfer (get-in report [:tempids "xfer"])
     :tx-report report}))

(defn transfer-tx-data
  "Pure tx-data builder for `transfer!` (ADR-068). Emits the transfer
   row at tempid `\"xfer\"` (override via `:tempid`)."
  [db {:keys [inventory-item quantity to-facility to-location send-date note
              tempid]
       :or   {tempid "xfer"}}]
  (when-not inventory-item (throw (ex-info ":inventory-item required" {})))
  (when (nil? quantity)    (throw (ex-info ":quantity required" {})))
  (when-not to-facility    (throw (ex-info ":to-facility required" {})))
  (let [src (d/pull db [{:kontor.inventory-item/facility [:db/id]}
                        {:kontor.inventory-item/location [:db/id]}]
                    inventory-item)
        to-f (inv/resolve-facility db to-facility)
        _ (when-not to-f (throw (ex-info "Destination facility not found"
                                         {:spec to-facility})))
        sent (or send-date (Date.))
        transfer (cond-> {:db/id tempid
                          :kontor.inventory-transfer/inventory-item inventory-item
                          :kontor.inventory-transfer/quantity quantity
                          :kontor.inventory-transfer/from-facility
                          (:db/id (:kontor.inventory-item/facility src))
                          :kontor.inventory-transfer/to-facility to-f
                          :kontor.inventory-transfer/status :in-transit
                          :kontor.inventory-transfer/send-date sent}
                   (:kontor.inventory-item/location src)
                   (assoc :kontor.inventory-transfer/from-location
                          (:db/id (:kontor.inventory-item/location src)))
                   to-location (assoc :kontor.inventory-transfer/to-location to-location)
                   note        (assoc :kontor.inventory-transfer/note note))
        detail {:kontor.inventory-detail/inventory-item inventory-item
                :kontor.inventory-detail/effective-date sent
                :kontor.inventory-detail/qoh-diff (.negate ^java.math.BigDecimal quantity)
                :kontor.inventory-detail/atp-diff (.negate ^java.math.BigDecimal quantity)
                :kontor.inventory-detail/source-kind :transfer
                :kontor.inventory-detail/source tempid}]
    [transfer detail]))

(defn complete-transfer!
  "Complete an `:in-transit` transfer — find-or-create the
   destination bucket (same product/lot/owner as the source, at the
   destination facility/location), append an `:inventory-detail`
   (`:qoh-diff +qty`, `:atp-diff +qty`) there, and set the transfer
   `:status :complete` + `:receive-date`.

   Required: :transfer (eid). Optional: :receive-date (default now).
   Returns {:to-inventory-item eid :tx-report report}."
  [conn {:keys [transfer receive-date]}]
  (when-not transfer (throw (ex-info ":transfer required" {})))
  (let [db (d/db conn)
        t (d/pull db [:kontor.inventory-transfer/quantity
                      :kontor.inventory-transfer/status
                      {:kontor.inventory-transfer/to-facility [:db/id]}
                      {:kontor.inventory-transfer/to-location [:db/id]}
                      {:kontor.inventory-transfer/inventory-item
                       [{:kontor.inventory-item/product [:db/id]}
                        {:kontor.inventory-item/lot [:db/id]}
                        {:kontor.inventory-item/owner-entity [:db/id]}]}]
                  transfer)
        _ (when-not (= :in-transit (:kontor.inventory-transfer/status t))
            (throw (ex-info "Transfer is not :in-transit"
                            {:type :inventory/transfer-not-in-transit
                             :transfer transfer
                             :status (:kontor.inventory-transfer/status t)})))
        src (:kontor.inventory-transfer/inventory-item t)
        rcv (or receive-date (Date.))
        dest-spec {:product (:db/id (:kontor.inventory-item/product src))
                   :facility (:db/id (:kontor.inventory-transfer/to-facility t))
                   :location (:db/id (:kontor.inventory-transfer/to-location t))
                   :lot (:db/id (:kontor.inventory-item/lot src))
                   :owner-entity (:db/id (:kontor.inventory-item/owner-entity src))
                   :received-at rcv}
        ;; ONE atomic, gated process (ADR-067). Find-or-create the
        ;; destination bucket against the speculative db; if created,
        ;; its tempid is "dest-item" and the detail references it by
        ;; tempid (tempid resolution is order-stable so the
        ;; speculative eid round-trips through the final commit).
        step (fn [sdb _ctx]
               (let [existing (inv/find-inventory-item sdb dest-spec)
                     dest-id (or existing "dest-item")
                     bucket-entity (when-not existing
                                     (inv/inventory-item-entity
                                      "dest-item" dest-spec))
                     detail {:kontor.inventory-detail/inventory-item dest-id
                             :kontor.inventory-detail/effective-date rcv
                             :kontor.inventory-detail/qoh-diff
                             (:kontor.inventory-transfer/quantity t)
                             :kontor.inventory-detail/atp-diff
                             (:kontor.inventory-transfer/quantity t)
                             :kontor.inventory-detail/source-kind :transfer
                             :kontor.inventory-detail/source transfer}]
                 (cond-> [detail
                          {:db/id transfer
                           :kontor.inventory-transfer/status :complete
                           :kontor.inventory-transfer/receive-date rcv}]
                   bucket-entity (conj bucket-entity))))
        report (process/run-process conn {:steps [step] :vt-from rcv})
        tempids (:tempids report)]
    {:to-inventory-item (or (inv/find-inventory-item (d/db conn) dest-spec)
                            (get tempids "dest-item"))}))

(declare cancel-transfer-tx-data)

(defn cancel-transfer!
  "Cancel an `:in-transit` transfer — append an `:inventory-detail`
   (`:qoh-diff +qty`, `:atp-diff +qty`) back on the SOURCE bucket and
   set `:status :cancelled`. `:effective-date` defaults to now (a
   backdated cancel passes it explicitly). Routes through the gate
   (ADR-068). Returns the tx-report.

   Optional: :vt-from / :vt-to (default :vt-from = :effective-date).

   The pure tx-data builder is `cancel-transfer-tx-data` (ADR-068)."
  ([conn transfer-eid] (cancel-transfer! conn transfer-eid {}))
  ([conn transfer-eid {:keys [effective-date vt-from vt-to] :as opts}]
   (let [eff (or effective-date (Date.))]
     (validation/transact-with-validation
      conn (kbt/with-vt (cancel-transfer-tx-data
                         (d/db conn) transfer-eid
                         (assoc opts :effective-date eff))
                        (or vt-from eff) (or vt-to kbt/forever))))))

(defn cancel-transfer-tx-data
  "Pure tx-data builder for `cancel-transfer!` (ADR-068)."
  [db transfer-eid {:keys [effective-date]}]
  (let [t (d/pull db [:kontor.inventory-transfer/quantity
                      :kontor.inventory-transfer/status
                      {:kontor.inventory-transfer/inventory-item [:db/id]}]
                  transfer-eid)
        _ (when-not (= :in-transit (:kontor.inventory-transfer/status t))
            (throw (ex-info "Transfer is not :in-transit"
                            {:type :inventory/transfer-not-in-transit
                             :transfer transfer-eid
                             :status (:kontor.inventory-transfer/status t)})))]
    [{:kontor.inventory-detail/inventory-item
      (:db/id (:kontor.inventory-transfer/inventory-item t))
      :kontor.inventory-detail/effective-date (or effective-date (Date.))
      :kontor.inventory-detail/qoh-diff (:kontor.inventory-transfer/quantity t)
      :kontor.inventory-detail/atp-diff (:kontor.inventory-transfer/quantity t)
      :kontor.inventory-detail/source-kind :transfer
      :kontor.inventory-detail/source transfer-eid
      :kontor.inventory-detail/description "Transfer cancelled"}
     {:db/id transfer-eid :kontor.inventory-transfer/status :cancelled}]))

;; ============================================================================
;; Value-only verbs — landed cost / NRV write-down / GR-IR true-up
;; (note 198 R3-INV-5, R3-INV-6, R3-INV-7)
;;
;; All three ride `kontor.posting/plan-adjustment-move`: they differ only in
;; how they SPLIT a value across layers and which role absorbs the contra.
;; ============================================================================

(defn- ^java.math.BigDecimal money [bd]
  (.setScale ^java.math.BigDecimal bd 2 java.math.RoundingMode/HALF_EVEN))

(defn- allocate-residue
  "Distribute `total` across `weights` (a vector of BigDecimal) proportionally,
   rounding to 2dp and giving the LAST entry the residue so the parts sum to
   `total` EXACTLY. A freight voucher that allocates to 299.99 of 300.00 leaves
   a cent stranded in the clearing account forever."
  [^java.math.BigDecimal total weights]
  (let [w-total (reduce (fn [^java.math.BigDecimal a ^java.math.BigDecimal b] (.add a b))
                        0M weights)
        n       (count weights)]
    (if (or (zero? n) (zero? (.signum w-total)))
      []
      (let [heads (mapv (fn [^java.math.BigDecimal w]
                          (money (.divide (.multiply total w) w-total
                                          6 java.math.RoundingMode/HALF_EVEN)))
                        (butlast weights))
            used  (reduce (fn [^java.math.BigDecimal a ^java.math.BigDecimal b] (.add a b))
                          0M heads)]
        (conj heads (.subtract total used))))))

(def split-methods
  "How a landed cost is spread over the receipt layers it applies to.
   Odoo's stock.landed.cost SPLIT_METHOD, minus the ones that need physical
   master data kontor does not own (by_weight / by_volume — a consumer with
   those attributes passes explicit `:allocations` instead)."
  #{:by-quantity :by-value :equal})

(defn apply-landed-cost-tx-data
  "Pure tx-data builder for `apply-landed-cost!` (ADR-068)."
  [db {:keys [product book commodity journal account-fn amount split-method
              layers allocations effective-date narration note ledger]
       :or   {split-method :by-quantity}}]
  (when-not journal    (throw (ex-info ":journal required" {})))
  (when-not account-fn (throw (ex-info ":account-fn required" {})))
  (let [eff  (or effective-date (Date.))
        allocs
        (or allocations
            (let [_ (when (nil? amount) (throw (ex-info ":amount required" {})))
                  _ (when-not (contains? split-methods split-method)
                      (throw (ex-info "Unknown :split-method"
                                      {:split-method split-method :known split-methods})))
                  bk   (valuation/resolve-book db book)
                  ls   (or layers (valuation/available-layers db bk product))
                  _    (when (empty? ls)
                         (throw (ex-info "apply-landed-cost!: no layers to allocate across"
                                         {:type :inventory/no-layers :product product :book bk})))
                  opts {}
                  weights (mapv (fn [l]
                                  (case split-method
                                    :by-quantity (valuation/qty-remaining db l opts)
                                    :by-value    (valuation/layer-value-remaining db l opts)
                                    :equal       1M))
                                ls)]
              (mapv (fn [l a] {:layer l :amount a})
                    ls (allocate-residue (money (bigdec amount)) weights))))]
    (seal-stock-move
     (posting/plan-adjustment-move
      db {:journal journal :effective-date eff :commodity commodity
          :account-fn account-fn :ledger ledger
          :allocations allocs
          :contra-role :landed-cost-clearing
          :reason :landed-cost
          :note (or note "Landed cost")
          :narration (or narration "Landed cost allocation")})
     eff)))

(defn apply-landed-cost!
  "Capitalise a freight / duty / insurance voucher onto the receipt layers it
   belongs to — the cost of getting the goods where they are is part of what
   they cost (IAS 2.11).

   Required: :product :book :commodity :journal :account-fn and either
             :amount (+ optional :split-method, default :by-quantity) or an
             explicit :allocations vector.
   Optional: :layers (restrict the allocation set), :effective-date,
             :narration, :note, :ledger, :vt-from / :vt-to.

   The voucher's total lands on the layers exactly — the last layer absorbs
   the rounding residue — and the contra is `:landed-cost-clearing`, which the
   vendor bill later clears.

   The pure tx-data builder is `apply-landed-cost-tx-data` (ADR-068)."
  [conn {:keys [effective-date vt-from vt-to] :as opts}]
  (let [eff (or effective-date (Date.))]
    (validation/transact-with-validation
     conn (kbt/with-vt (apply-landed-cost-tx-data (d/db conn)
                                                  (assoc opts :effective-date eff))
            (or vt-from eff) (or vt-to kbt/forever)))))

(defn write-down-to-nrv-tx-data
  "Pure tx-data builder for `write-down-to-nrv!` (ADR-068)."
  [db {:keys [product book commodity journal account-fn nrv-unit-cost
              effective-date narration note ledger]}]
  (when-not journal    (throw (ex-info ":journal required" {})))
  (when-not account-fn (throw (ex-info ":account-fn required" {})))
  (when (nil? nrv-unit-cost) (throw (ex-info ":nrv-unit-cost required" {})))
  (let [eff   (or effective-date (Date.))
        bk    (valuation/resolve-book db book)
        ls    (valuation/available-layers db bk product)
        qty   (valuation/on-hand-qty db bk product)
        carry (valuation/on-hand-value db bk product)
        target (money (.multiply ^java.math.BigDecimal qty
                                 ^java.math.BigDecimal (bigdec nrv-unit-cost)))
        delta  (.subtract target (money carry))]
    (when (empty? ls)
      (throw (ex-info "write-down-to-nrv!: nothing on hand to write down"
                      {:type :inventory/no-layers :product product :book bk})))
    ;; IAS 2.9 is lower-of-cost-and-NRV: a recovery above carrying cost is NOT
    ;; a write-up. IAS 2.33 allows REVERSING a previous write-down up to the
    ;; original cost, which needs the write-down history this verb does not
    ;; read — refuse rather than silently book an unsupported gain.
    (when-not (neg? (.signum delta))
      (throw (ex-info "write-down-to-nrv!: NRV is at or above carrying value — IAS 2.9 permits no write-up"
                      {:type :inventory/nrv-above-cost
                       :carrying-value carry :nrv-value target})))
    (let [weights (mapv #(valuation/layer-value-remaining db %) ls)]
      (seal-stock-move
       (posting/plan-adjustment-move
        db {:journal journal :effective-date eff :commodity commodity
            :account-fn account-fn :ledger ledger
            :allocations (mapv (fn [l a] {:layer l :amount a})
                               ls (allocate-residue delta weights))
            :contra-role :write-down-expense
            :reason :write-down
            :note (or note "NRV write-down")
            :narration (or narration "Inventory write-down to net realisable value")})
       eff))))

(defn write-down-to-nrv!
  "Write on-hand stock down to net realisable value (IAS 2.9 lower of cost and
   NRV), allocating the write-down across layers pro-rata to their carrying
   value. Books Dr `:write-down-expense` / Cr `:inventory` in the GL and the
   matching `:layer-adjustment`s in the subledger, in ONE transaction — so the
   two views move together the way `receive!` / `issue!` already guarantee.

   Required: :product :book :commodity :journal :account-fn :nrv-unit-cost.
   Optional: :effective-date, :narration, :note, :ledger, :vt-from / :vt-to.

   Refuses when NRV is at or above carrying value.

   The pure tx-data builder is `write-down-to-nrv-tx-data` (ADR-068)."
  [conn {:keys [effective-date vt-from vt-to] :as opts}]
  (let [eff (or effective-date (Date.))]
    (validation/transact-with-validation
     conn (kbt/with-vt (write-down-to-nrv-tx-data (d/db conn)
                                                  (assoc opts :effective-date eff))
            (or vt-from eff) (or vt-to kbt/forever)))))

(defn true-up-gr-ir-tx-data
  "Pure tx-data builder for `true-up-gr-ir!` (ADR-068)."
  [db {:keys [layer billed-unit-cost commodity journal account-fn
              effective-date narration note ledger]}]
  (when-not layer      (throw (ex-info ":layer required" {})))
  (when-not journal    (throw (ex-info ":journal required" {})))
  (when-not account-fn (throw (ex-info ":account-fn required" {})))
  (when (nil? billed-unit-cost) (throw (ex-info ":billed-unit-cost required" {})))
  (let [eff    (or effective-date (Date.))
        pulled (d/pull db [:kontor.valuation-layer/qty-original
                           :kontor.valuation-layer/unit-cost-original]
                       layer)
        qty-orig  ^java.math.BigDecimal (:kontor.valuation-layer/qty-original pulled)
        unit-orig ^java.math.BigDecimal (:kontor.valuation-layer/unit-cost-original pulled)]
    (when (nil? qty-orig)
      (throw (ex-info "true-up-gr-ir!: :layer is not a valuation layer" {:layer layer})))
    (let [remaining (valuation/qty-remaining db layer)
          delta     (money (.multiply (.subtract ^java.math.BigDecimal (bigdec billed-unit-cost)
                                                 unit-orig)
                                      qty-orig))
          ;; Split by WHERE THE GOODS ARE NOW. The share still on hand
          ;; revalues the stock; the share already issued is a period cost —
          ;; capitalising it onto stock that no longer exists would overstate
          ;; inventory and understate COGS indefinitely.
          on-hand   (money (.divide (.multiply delta remaining) qty-orig
                                    6 java.math.RoundingMode/HALF_EVEN))
          consumed  (.subtract delta on-hand)]
      (seal-stock-move
       (posting/plan-adjustment-move
        db (cond-> {:journal journal :effective-date eff :commodity commodity
                    :account-fn account-fn :ledger ledger
                    :allocations (if (zero? (.signum on-hand))
                                   []
                                   [{:layer layer :amount on-hand}])
                    :contra-role :gr-ir-clearing
                    :reason :correction
                    :note (or note "GR-IR price difference")
                    :narration (or narration "Vendor bill / receipt price true-up")}
             (not (zero? (.signum consumed)))
             (assoc :expense-legs [{:role :cogs :amount consumed}])))
       eff))))

(defn true-up-gr-ir!
  "Reconcile a vendor bill against the receipt it settles when the billed
   price differs from the received price, SPLITTING the variance by where the
   goods are now: the still-on-hand share revalues the layer, the already-
   issued share lands in COGS.

   `true-up-negative-fill!` handles a different case — a negative-fill layer
   whose ESTIMATED cost is being replaced by the actual — and books the whole
   delta to one inventory/variance pair with no split.

   Required: :layer :billed-unit-cost :commodity :journal :account-fn.
   Optional: :effective-date, :narration, :note, :ledger, :vt-from / :vt-to.

   The pure tx-data builder is `true-up-gr-ir-tx-data` (ADR-068)."
  [conn {:keys [effective-date vt-from vt-to] :as opts}]
  (let [eff (or effective-date (Date.))]
    (validation/transact-with-validation
     conn (kbt/with-vt (true-up-gr-ir-tx-data (d/db conn)
                                              (assoc opts :effective-date eff))
            (or vt-from eff) (or vt-to kbt/forever)))))
