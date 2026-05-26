(ns kontor.inventory.ops
  "kontor-inventory — receive / issue / transfer operations + GL
   integration + the negative-inventory policy (ADR-059).

   The defining guarantee: `receive!` / `issue!` write BOTH halves in
   ONE transaction — the valuation layer + GL postings (via
   `kontor.posting/plan-stock-move`, ADR-030) AND the physical
   `:inventory-item` / `:inventory-detail` — linked by
   `:inventory-detail/transaction`. The physical and financial views
   cannot drift, because they are written together and the GL is
   *only* ever touched through `plan-stock-move` (research note 36
   §2 — the discipline that makes subledger-vs-GL drift structurally
   hard).

   Negative inventory is a per-(facility, product) policy
   (`:facility-product/negative-allowed?`): an over-issue is refused
   by default, or — when allowed — creates an explicit negative-fill
   `:valuation-layer` so the issue still has a layer to consume, with
   `true-up-negative-fill!` reconciling the estimate to actual later.

   Transfers are two-phase (`transfer!` → `complete-transfer!`) and
   GL-free — a same-entity move is a pure quantity event;
   cross-entity transfers with a GL leg are a documented follow-up."
  (:require [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.costing-provider :as costing]
            [kontor.posting :as posting]
            [kontor.process :as process]
            [kontor.validation :as validation]
            [kontor.valuation :as valuation]
            [kontor.inventory.core :as inv])
  (:import [clojure.lang ExceptionInfo]
           [java.util Date]))

;; ============================================================================
;; Internals
;; ============================================================================

(defn provider-for-book
  "Resolve a `CostingProvider` from a valuation book's
   `:valuation-book/cost-method`. `:standard` books must pass
   `:provider` explicitly (it needs a standard-cost-fn)."
  [db book]
  (let [eid    (valuation/resolve-book db book)
        method (:valuation-book/cost-method
                (d/pull db [:valuation-book/cost-method] eid))]
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
   `kontor.sealing` model expects. A goods receipt / issue / count
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
   `:inventory-detail/transaction`. Routes through the gate (ADR-068).

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
        ;; leaves no orphan :inventory-item bucket (review-fix CR P1-2).
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
        detail (cond-> {:inventory-detail/inventory-item item-id
                        :inventory-detail/effective-date eff
                        :inventory-detail/qoh-diff qty
                        :inventory-detail/atp-diff qty
                        :inventory-detail/source-kind :receipt
                        ;; -1 is plan-stock-move's transaction tempid.
                        :inventory-detail/transaction -1}
                 reason (assoc :inventory-detail/reason reason))]
    {:tx-data (cond-> (conj (vec (seal-stock-move move-tx eff)) detail)
                item-entity (conj item-entity))
     :existing-item-id existing}))

;; ============================================================================
;; Negative-fill
;; ============================================================================

(defn- negative-allowed?
  "The `:facility-product/negative-allowed?` flag for a
   (facility, product) pair — false when no policy row exists."
  [db facility product]
  (boolean
   (d/q '[:find ?na .
          :in $ ?f ?p
          :where
          [?fp :facility-product/facility ?f]
          [?fp :facility-product/product ?p]
          [?fp :facility-product/negative-allowed? ?na]]
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
   from `max-eid + 1` in encounter order; research note 47), so
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
                       :valuation-layer/book book-eid
                       :valuation-layer/item product
                       :valuation-layer/origin-transaction "nf-tx"
                       :valuation-layer/qty-original shortfall
                       :valuation-layer/unit-cost-original estimated-unit-cost
                       :valuation-layer/commodity commodity
                       :valuation-layer/received-at eff
                       :valuation-layer/note "negative-fill (estimated cost)"}
                lot (assoc :valuation-layer/lot lot))
        nf {:db/id "nf"
            :negative-fill/inventory-item item-id
            :negative-fill/valuation-layer "nf-layer"
            :negative-fill/shortfall-qty shortfall
            :negative-fill/estimated-unit-cost estimated-unit-cost
            :negative-fill/commodity commodity
            :negative-fill/status :open
            :negative-fill/created-at eff}]
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
   over-issue, by `create-negative-fill!` (review-fix CR P1-2)."
  [db {:keys [inventory-item reservation product facility location
              lot owner-entity]}]
  (cond
    inventory-item inventory-item
    reservation    (:db/id (:inv-reservation/inventory-item
                            (d/pull db [{:inv-reservation/inventory-item [:db/id]}]
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
   `(facility, product)` `:facility-product/negative-allowed?` is
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
        ;; plan-stock-move output references it correctly (research
        ;; note 47).
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
                detail (cond-> {:inventory-detail/inventory-item item-id
                                :inventory-detail/effective-date eff
                                :inventory-detail/qoh-diff
                                (.negate ^java.math.BigDecimal qty)
                                ;; A reservation already dropped ATP —
                                ;; realizing it moves QOH only. A plain
                                ;; issue moves both.
                                :inventory-detail/atp-diff
                                (if reservation 0M
                                    (.negate ^java.math.BigDecimal qty))
                                :inventory-detail/source-kind :issuance
                                :inventory-detail/transaction -1}
                         reason (assoc :inventory-detail/reason reason))]
            (cond-> (conj (vec (seal-stock-move move-tx eff)) detail)
              bucket-entity (conj bucket-entity)
              ;; Link the negative-fill back to the originating issue tx.
              need-neg-fill? (conj {:db/id "nf"
                                    :negative-fill/origin-issue -1})
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
   `:negative-fill/true-up-adjustment`, and marks the
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
  (let [nf (d/pull db [:negative-fill/status
                       :negative-fill/shortfall-qty
                       :negative-fill/estimated-unit-cost
                       {:negative-fill/commodity [:db/id]}
                       {:negative-fill/valuation-layer [:db/id]}]
                   negative-fill)
        _ (when-not (= :open (:negative-fill/status nf))
            (throw (ex-info "Negative-fill is not :open"
                            {:type :inventory/negative-fill-not-open
                             :negative-fill negative-fill
                             :status (:negative-fill/status nf)})))
        eff (or effective-date (Date.))
        shortfall (:negative-fill/shortfall-qty nf)
        delta-unit (.subtract ^java.math.BigDecimal actual-unit-cost
                              ^java.math.BigDecimal (:negative-fill/estimated-unit-cost nf))
        delta-total (.multiply ^java.math.BigDecimal delta-unit
                               ^java.math.BigDecimal shortfall)
        commodity (:db/id (:negative-fill/commodity nf))
        layer (:db/id (:negative-fill/valuation-layer nf))
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
                    :layer-adjustment/layer layer
                    :layer-adjustment/amount delta-total
                    :layer-adjustment/reason :correction
                    :layer-adjustment/origin-transaction -1
                    :layer-adjustment/applied-at eff
                    :layer-adjustment/note "Negative-fill estimate → actual"}]
    (into (vec gl)
          [adjustment
           {:db/id negative-fill
            :negative-fill/status :trued-up
            :negative-fill/true-up-adjustment "adj"}])))

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
  (let [src (d/pull db [{:inventory-item/facility [:db/id]}
                        {:inventory-item/location [:db/id]}]
                    inventory-item)
        to-f (inv/resolve-facility db to-facility)
        _ (when-not to-f (throw (ex-info "Destination facility not found"
                                         {:spec to-facility})))
        sent (or send-date (Date.))
        transfer (cond-> {:db/id tempid
                          :inventory-transfer/inventory-item inventory-item
                          :inventory-transfer/quantity quantity
                          :inventory-transfer/from-facility
                          (:db/id (:inventory-item/facility src))
                          :inventory-transfer/to-facility to-f
                          :inventory-transfer/status :in-transit
                          :inventory-transfer/send-date sent}
                   (:inventory-item/location src)
                   (assoc :inventory-transfer/from-location
                          (:db/id (:inventory-item/location src)))
                   to-location (assoc :inventory-transfer/to-location to-location)
                   note        (assoc :inventory-transfer/note note))
        detail {:inventory-detail/inventory-item inventory-item
                :inventory-detail/effective-date sent
                :inventory-detail/qoh-diff (.negate ^java.math.BigDecimal quantity)
                :inventory-detail/atp-diff (.negate ^java.math.BigDecimal quantity)
                :inventory-detail/source-kind :transfer
                :inventory-detail/source tempid}]
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
        t (d/pull db [:inventory-transfer/quantity
                      :inventory-transfer/status
                      {:inventory-transfer/to-facility [:db/id]}
                      {:inventory-transfer/to-location [:db/id]}
                      {:inventory-transfer/inventory-item
                       [{:inventory-item/product [:db/id]}
                        {:inventory-item/lot [:db/id]}
                        {:inventory-item/owner-entity [:db/id]}]}]
                  transfer)
        _ (when-not (= :in-transit (:inventory-transfer/status t))
            (throw (ex-info "Transfer is not :in-transit"
                            {:type :inventory/transfer-not-in-transit
                             :transfer transfer
                             :status (:inventory-transfer/status t)})))
        src (:inventory-transfer/inventory-item t)
        rcv (or receive-date (Date.))
        dest-spec {:product (:db/id (:inventory-item/product src))
                   :facility (:db/id (:inventory-transfer/to-facility t))
                   :location (:db/id (:inventory-transfer/to-location t))
                   :lot (:db/id (:inventory-item/lot src))
                   :owner-entity (:db/id (:inventory-item/owner-entity src))
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
                     detail {:inventory-detail/inventory-item dest-id
                             :inventory-detail/effective-date rcv
                             :inventory-detail/qoh-diff
                             (:inventory-transfer/quantity t)
                             :inventory-detail/atp-diff
                             (:inventory-transfer/quantity t)
                             :inventory-detail/source-kind :transfer
                             :inventory-detail/source transfer}]
                 (cond-> [detail
                          {:db/id transfer
                           :inventory-transfer/status :complete
                           :inventory-transfer/receive-date rcv}]
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
  (let [t (d/pull db [:inventory-transfer/quantity
                      :inventory-transfer/status
                      {:inventory-transfer/inventory-item [:db/id]}]
                  transfer-eid)
        _ (when-not (= :in-transit (:inventory-transfer/status t))
            (throw (ex-info "Transfer is not :in-transit"
                            {:type :inventory/transfer-not-in-transit
                             :transfer transfer-eid
                             :status (:inventory-transfer/status t)})))]
    [{:inventory-detail/inventory-item
      (:db/id (:inventory-transfer/inventory-item t))
      :inventory-detail/effective-date (or effective-date (Date.))
      :inventory-detail/qoh-diff (:inventory-transfer/quantity t)
      :inventory-detail/atp-diff (:inventory-transfer/quantity t)
      :inventory-detail/source-kind :transfer
      :inventory-detail/source transfer-eid
      :inventory-detail/description "Transfer cancelled"}
     {:db/id transfer-eid :inventory-transfer/status :cancelled}]))
