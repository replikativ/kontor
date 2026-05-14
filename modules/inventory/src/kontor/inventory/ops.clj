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
            [kontor.costing-provider :as costing]
            [kontor.posting :as posting]
            [kontor.valuation :as valuation]
            [kontor.inventory.core :as inv])
  (:import [clojure.lang ExceptionInfo]
           [java.util Date]))

;; ============================================================================
;; Internals
;; ============================================================================

(defn- provider-for-book
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

;; ============================================================================
;; receive!
;; ============================================================================

(defn receive!
  "Receive goods. Appends BOTH halves in ONE transaction: the
   valuation layer + GL postings (`plan-stock-move :direction :in`)
   AND the physical `:inventory-detail` against the
   `(product, facility, location, lot, owner)` bucket — linked by
   `:inventory-detail/transaction`.

   Required: :product, :facility, :book (valuation-book), :qty,
             :unit-cost, :commodity, :journal, :account-fn.
   Optional: :location, :lot, :owner-entity, :provider (default:
             resolved from the book's :cost-method), :effective-date
             (default now), :ledger, :narration, :reason.

   Returns {:inventory-item eid :transaction eid :tx-report report}."
  [conn {:keys [product facility location lot owner-entity book qty
                unit-cost commodity journal account-fn provider
                effective-date ledger narration reason]}]
  (when-not product   (throw (ex-info ":product required" {})))
  (when-not facility  (throw (ex-info ":facility required" {})))
  (when-not book      (throw (ex-info ":book required" {})))
  (when (nil? qty)    (throw (ex-info ":qty required" {})))
  (when (nil? unit-cost) (throw (ex-info ":unit-cost required" {})))
  (when-not commodity (throw (ex-info ":commodity required" {})))
  (when-not journal   (throw (ex-info ":journal required" {})))
  (when-not account-fn (throw (ex-info ":account-fn required" {})))
  (let [db   (d/db conn)
        f    (inv/resolve-facility db facility)
        _    (when-not f (throw (ex-info "Facility not found" {:spec facility})))
        eff  (or effective-date (Date.))
        item (inv/find-or-create-inventory-item!
              conn {:product product :facility f :location location
                    :lot lot :owner-entity owner-entity :received-at eff})
        prov (or provider (provider-for-book (d/db conn) book))
        move-tx (posting/plan-stock-move
                 (d/db conn)
                 (cond-> {:direction :in :book book :item product :qty qty
                          :commodity commodity :lot lot :journal journal
                          :effective-date eff :unit-cost unit-cost
                          :provider prov :account-fn account-fn}
                   ledger    (assoc :ledger ledger)
                   narration (assoc :narration narration)))
        detail (cond-> {:inventory-detail/inventory-item item
                        :inventory-detail/effective-date eff
                        :inventory-detail/qoh-diff qty
                        :inventory-detail/atp-diff qty
                        :inventory-detail/source-kind :receipt
                        ;; -1 is plan-stock-move's transaction tempid.
                        :inventory-detail/transaction -1}
                 reason (assoc :inventory-detail/reason reason))
        report (d/transact conn (conj (vec move-tx) detail))]
    {:inventory-item item
     :transaction    (get-in report [:tempids -1])
     :tx-report      report}))

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

(defn- create-negative-fill!
  "Create the explicit negative-fill `:valuation-layer` (qty =
   `shortfall` at `estimated-unit-cost`) — with a minimal posting-less
   `:transaction` as its `:origin-transaction` so `available-layers`
   recognises it — plus the `:negative-fill` record, in one tx.
   Returns the `:negative-fill` eid. The layer gives the over-issue a
   real layer for `plan-stock-move` to consume on the retry."
  [conn {:keys [product book commodity estimated-unit-cost lot
                inventory-item journal effective-date]}
   shortfall]
  (when (nil? estimated-unit-cost)
    (throw (ex-info ":estimated-unit-cost required for a negative-fill over-issue"
                    {:type :inventory/estimated-cost-required})))
  (when-not journal
    (throw (ex-info ":journal required for a negative-fill over-issue" {})))
  (let [book-eid (valuation/resolve-book (d/db conn) book)
        eff (or effective-date (Date.))
        origin-tx {:db/id "neg-fill-tx"
                   :transaction/journal journal
                   :transaction/effective-date eff
                   :transaction/state :posted
                   :transaction/narration "Negative-fill layer (estimated cost)"}
        layer (cond-> {:db/id "neg-fill-layer"
                       :valuation-layer/book book-eid
                       :valuation-layer/item product
                       :valuation-layer/origin-transaction "neg-fill-tx"
                       :valuation-layer/qty-original shortfall
                       :valuation-layer/unit-cost-original estimated-unit-cost
                       :valuation-layer/commodity commodity
                       :valuation-layer/received-at eff
                       :valuation-layer/note "negative-fill (estimated cost)"}
                lot (assoc :valuation-layer/lot lot))
        nf {:db/id "neg-fill"
            :negative-fill/inventory-item inventory-item
            :negative-fill/valuation-layer "neg-fill-layer"
            :negative-fill/shortfall-qty shortfall
            :negative-fill/estimated-unit-cost estimated-unit-cost
            :negative-fill/commodity commodity
            :negative-fill/status :open
            :negative-fill/created-at eff}
        report (d/transact conn [origin-tx layer nf])]
    (get-in report [:tempids "neg-fill"])))

;; ============================================================================
;; issue!
;; ============================================================================

(defn- resolve-issue-bucket
  "Resolve the :inventory-item bucket an issue draws from — explicit
   `:inventory-item`, the `:reservation`'s bucket, or find-or-create
   from the (product, facility, location, lot, owner) key."
  [conn db {:keys [inventory-item reservation product facility location
                   lot owner-entity]}]
  (cond
    inventory-item inventory-item
    reservation    (:db/id (:inv-reservation/inventory-item
                            (d/pull db [{:inv-reservation/inventory-item [:db/id]}]
                                    reservation)))
    :else (inv/find-or-create-inventory-item!
           conn {:product product :facility (inv/resolve-facility db facility)
                 :location location :lot lot :owner-entity owner-entity})))

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
  (let [f    (inv/resolve-facility (d/db conn) facility)
        _    (when-not f (throw (ex-info "Facility not found" {:spec facility})))
        eff  (or effective-date (Date.))
        item (resolve-issue-bucket conn (d/db conn) (assoc spec :facility f))
        prov (or provider (provider-for-book (d/db conn) book))
        move-spec (cond-> {:direction :out :book book :item product :qty qty
                           :commodity commodity :journal journal
                           :effective-date eff :provider prov
                           :account-fn account-fn}
                    ledger    (assoc :ledger ledger)
                    narration (assoc :narration narration))
        plan-move (fn [] (posting/plan-stock-move (d/db conn) move-spec))
        ;; Try the move; on underflow, apply the negative-fill policy.
        [move-tx neg-fill]
        (try
          [(plan-move) nil]
          (catch ExceptionInfo e
            (if (insufficient-stock? e)
              (if (negative-allowed? (d/db conn) f product)
                (let [shortfall (:underflow (ex-data e))
                      nf (create-negative-fill!
                          conn (assoc spec :facility f :inventory-item item
                                      :effective-date eff)
                          shortfall)]
                  [(plan-move) nf])
                (throw (ex-info "Issue would over-draw and negative inventory is not allowed for this (facility, product)"
                                {:type :inventory/negative-not-allowed
                                 :product product :facility f
                                 :underflow (:underflow (ex-data e))}
                                e)))
              (throw e))))
        detail (cond-> {:inventory-detail/inventory-item item
                        :inventory-detail/effective-date eff
                        :inventory-detail/qoh-diff (.negate ^java.math.BigDecimal qty)
                        ;; A reservation already dropped ATP — realizing
                        ;; it moves QOH only. A plain issue moves both.
                        :inventory-detail/atp-diff (if reservation
                                                     0M
                                                     (.negate ^java.math.BigDecimal qty))
                        :inventory-detail/source-kind :issuance
                        :inventory-detail/transaction -1}
                 reason (assoc :inventory-detail/reason reason))
        tx-data (cond-> (conj (vec move-tx) detail)
                  ;; Realize (consume) the reservation — retract it.
                  reservation (conj [:db/retractEntity reservation]))
        report (d/transact conn tx-data)]
    (cond-> {:inventory-item item
             :transaction    (get-in report [:tempids -1])
             :tx-report      report}
      neg-fill (assoc :negative-fill neg-fill))))

;; ============================================================================
;; true-up-negative-fill!
;; ============================================================================

(defn true-up-negative-fill!
  "Reconcile an `:open` `:negative-fill` to actual cost. Emits a
   `:layer-adjustment` on the negative-fill layer for the cost delta
   `(actual − estimated) × shortfall-qty` + a balanced GL correction
   (`Dr :variance-account / Cr :inventory-account` for a positive
   delta), links the adjustment back via
   `:negative-fill/true-up-adjustment`, and marks the
   `:negative-fill` `:trued-up`.

   The exact account routing of the correction is a simplification —
   a consumer / l10n module with stricter requirements posts its own
   correction and just stamps the `:layer-adjustment`.

   Required: :negative-fill (eid), :actual-unit-cost, :journal,
             :inventory-account, :variance-account.
   Optional: :effective-date (default now).
   Returns the tx-report."
  [conn {:keys [negative-fill actual-unit-cost journal inventory-account
                variance-account effective-date]}]
  (when-not negative-fill   (throw (ex-info ":negative-fill required" {})))
  (when (nil? actual-unit-cost) (throw (ex-info ":actual-unit-cost required" {})))
  (when-not journal         (throw (ex-info ":journal required" {})))
  (when-not inventory-account (throw (ex-info ":inventory-account required" {})))
  (when-not variance-account  (throw (ex-info ":variance-account required" {})))
  (let [db (d/db conn)
        nf (d/pull db [:negative-fill/status
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
            {:transaction {:transaction/journal journal
                           :transaction/effective-date eff
                           :transaction/narration "Negative-fill true-up"}
             :postings [{:posting/account variance-account
                         :posting/amount delta-total
                         :posting/commodity commodity}
                        {:posting/account inventory-account
                         :posting/amount (.negate ^java.math.BigDecimal delta-total)
                         :posting/commodity commodity}]})
        adjustment {:db/id "adj"
                    :layer-adjustment/layer layer
                    :layer-adjustment/amount delta-total
                    :layer-adjustment/reason :correction
                    :layer-adjustment/origin-transaction -1
                    :layer-adjustment/applied-at eff
                    :layer-adjustment/note "Negative-fill estimate → actual"}]
    (d/transact conn (into (vec gl)
                           [adjustment
                            {:db/id negative-fill
                             :negative-fill/status :trued-up
                             :negative-fill/true-up-adjustment "adj"}]))))

;; ============================================================================
;; Transfers — two-phase, GL-free (same-entity quantity moves)
;; ============================================================================

(defn transfer!
  "Begin a two-phase transfer — send `:quantity` of an
   `:inventory-item` toward another facility/location. Creates an
   `:inventory-transfer` (`:status :in-transit`) and appends an
   `:inventory-detail` (`:qoh-diff -qty`, `:atp-diff -qty`) on the
   SOURCE bucket: the stock is 'on the truck' — off the source, not
   yet at the destination. GL-free (a same-entity move is a pure
   quantity event; cross-entity transfers with a GL leg are a
   documented follow-up).

   Required: :inventory-item, :quantity, :to-facility.
   Optional: :to-location, :send-date (default now), :note.
   Returns {:transfer eid :tx-report report}."
  [conn {:keys [inventory-item quantity to-facility to-location send-date note]}]
  (when-not inventory-item (throw (ex-info ":inventory-item required" {})))
  (when (nil? quantity)    (throw (ex-info ":quantity required" {})))
  (when-not to-facility    (throw (ex-info ":to-facility required" {})))
  (let [db (d/db conn)
        src (d/pull db [{:inventory-item/facility [:db/id]}
                        {:inventory-item/location [:db/id]}]
                    inventory-item)
        to-f (inv/resolve-facility db to-facility)
        _ (when-not to-f (throw (ex-info "Destination facility not found"
                                         {:spec to-facility})))
        sent (or send-date (Date.))
        transfer (cond-> {:db/id "xfer"
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
                :inventory-detail/source "xfer"}
        report (d/transact conn [transfer detail])]
    {:transfer (get-in report [:tempids "xfer"])
     :tx-report report}))

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
        dest (inv/find-or-create-inventory-item!
              conn {:product (:db/id (:inventory-item/product src))
                    :facility (:db/id (:inventory-transfer/to-facility t))
                    :location (:db/id (:inventory-transfer/to-location t))
                    :lot (:db/id (:inventory-item/lot src))
                    :owner-entity (:db/id (:inventory-item/owner-entity src))
                    :received-at rcv})
        detail {:inventory-detail/inventory-item dest
                :inventory-detail/effective-date rcv
                :inventory-detail/qoh-diff (:inventory-transfer/quantity t)
                :inventory-detail/atp-diff (:inventory-transfer/quantity t)
                :inventory-detail/source-kind :transfer
                :inventory-detail/source transfer}]
    (d/transact conn [detail
                      {:db/id transfer
                       :inventory-transfer/status :complete
                       :inventory-transfer/receive-date rcv}])
    {:to-inventory-item dest}))

(defn cancel-transfer!
  "Cancel an `:in-transit` transfer — append an `:inventory-detail`
   (`:qoh-diff +qty`, `:atp-diff +qty`) back on the SOURCE bucket and
   set `:status :cancelled`. Returns the tx-report."
  [conn transfer-eid]
  (let [db (d/db conn)
        t (d/pull db [:inventory-transfer/quantity
                      :inventory-transfer/status
                      {:inventory-transfer/inventory-item [:db/id]}]
                  transfer-eid)
        _ (when-not (= :in-transit (:inventory-transfer/status t))
            (throw (ex-info "Transfer is not :in-transit"
                            {:type :inventory/transfer-not-in-transit
                             :transfer transfer-eid
                             :status (:inventory-transfer/status t)})))]
    (d/transact conn
                [{:inventory-detail/inventory-item
                  (:db/id (:inventory-transfer/inventory-item t))
                  :inventory-detail/effective-date (Date.)
                  :inventory-detail/qoh-diff (:inventory-transfer/quantity t)
                  :inventory-detail/atp-diff (:inventory-transfer/quantity t)
                  :inventory-detail/source-kind :transfer
                  :inventory-detail/source transfer-eid
                  :inventory-detail/description "Transfer cancelled"}
                 {:db/id transfer-eid :inventory-transfer/status :cancelled}])))
