(ns kontor.inventory.count
  "Cycle counts / physical inventory — ADR-060.

   A `:physical-inventory` is a count event; each `:inventory-variance`
   is a counted line (expected vs counted vs variance, reason-coded).
   The freeze is a *valid-time convention*, not a DB lock: a line
   snapshots the perpetual `on-hand-qty` AS-OF the count's
   `:count-date`, so concurrent picks at a later valid-time do not
   corrupt the count (research note 36 §6).

   `post-count!` routes every non-zero variance through
   `kontor.posting/plan-stock-move` — a count adjustment IS a GL
   event (shrinkage is an expense; found stock is a gain), and
   routing it through `plan-stock-move` is what keeps the subledger
   and the GL from drifting (research note 36 §2). It also appends
   the physical `:inventory-detail`, linked to both the
   `:inventory-variance` (the audit document) and the GL
   `:transaction`."
  (:require [datahike.api :as d]
            [kontor.posting :as posting]
            [kontor.process :as process]
            [kontor.validation :as validation]
            [kontor.inventory.core :as inv]
            [kontor.inventory.ops :as ops])
  (:import [java.math BigDecimal]))

;; ============================================================================
;; start-count! / record-count-line!
;; ============================================================================

(defn start-count!
  "Open a `:physical-inventory` count header.

   Required: :facility (code or eid), :count-date.
   Optional: :code, :counted-by (ref :partner), :status (default
             :counting), :comments.
   Returns {:physical-inventory eid :tx-report report}."
  [conn {:keys [code facility count-date counted-by status comments]
         :or {status :counting}}]
  (when-not facility   (throw (ex-info ":facility required" {})))
  (when-not count-date (throw (ex-info ":count-date required" {})))
  (let [db (d/db conn)
        f  (inv/resolve-facility db facility)
        _  (when-not f (throw (ex-info "Facility not found" {:spec facility})))
        row (cond-> {:db/id "count"
                     :kontor.physical-inventory/facility f
                     :kontor.physical-inventory/count-date count-date
                     :kontor.physical-inventory/status status}
              code       (assoc :kontor.physical-inventory/code code)
              counted-by (assoc :kontor.physical-inventory/counted-by counted-by)
              comments   (assoc :kontor.physical-inventory/comments comments))
        report (validation/transact-with-validation conn [row])]
    {:physical-inventory (get-in report [:tempids "count"])
     :tx-report report}))

(defn record-count-line!
  "Record one `:inventory-variance` line. Snapshots the perpetual
   `on-hand-qty` (AS-OF the count's `:count-date`) as `:expected-qty`,
   takes `:counted-qty`, computes `:qoh-var = counted − expected`.

   Required: :physical-inventory, :inventory-item, :counted-qty.
   Optional: :reason (#{:shrinkage :damage :found :recount :uom
             :mispick}), :recount-of (a variance eid this supersedes),
             :comments.
   Returns {:variance eid :expected-qty bigdec :counted-qty bigdec
            :qoh-var bigdec :tx-report report}."
  [conn {:keys [physical-inventory inventory-item counted-qty reason
                recount-of comments]}]
  (when-not physical-inventory (throw (ex-info ":physical-inventory required" {})))
  (when-not inventory-item     (throw (ex-info ":inventory-item required" {})))
  (when (nil? counted-qty)     (throw (ex-info ":counted-qty required" {})))
  (let [db (d/db conn)
        count-date (:kontor.physical-inventory/count-date
                    (d/pull db [:kontor.physical-inventory/count-date] physical-inventory))
        expected (inv/on-hand-qty db inventory-item {:as-of-valid count-date})
        qoh-var (.subtract ^BigDecimal counted-qty ^BigDecimal expected)
        row (cond-> {:db/id "var"
                     :kontor.inventory-variance/physical-inventory physical-inventory
                     :kontor.inventory-variance/inventory-item inventory-item
                     :kontor.inventory-variance/expected-qty expected
                     :kontor.inventory-variance/counted-qty counted-qty
                     :kontor.inventory-variance/qoh-var qoh-var}
              reason     (assoc :kontor.inventory-variance/reason reason)
              recount-of (assoc :kontor.inventory-variance/recount-of recount-of)
              comments   (assoc :kontor.inventory-variance/comments comments))
        report (validation/transact-with-validation conn [row])]
    {:variance      (get-in report [:tempids "var"])
     :expected-qty  expected
     :counted-qty   counted-qty
     :qoh-var       qoh-var
     :tx-report     report}))

;; ============================================================================
;; post-count!
;; ============================================================================

(defn- current-variance-lines
  "The variance lines of a count NOT superseded by a later recount."
  [db physical-inventory]
  (let [lines (d/q '[:find [?v ...]
                     :in $ ?pi
                     :where [?v :kontor.inventory-variance/physical-inventory ?pi]]
                   db physical-inventory)
        superseded (set (keep (fn [v]
                                (:db/id (:kontor.inventory-variance/recount-of
                                         (d/pull db [{:kontor.inventory-variance/recount-of
                                                      [:db/id]}]
                                                 v))))
                              lines))]
    (remove superseded lines)))

(defn- already-posted?
  "True iff a variance line already has its `:source`-linked
   `:inventory-detail` — makes `post-count!` safe to re-run after a
   partial failure (it skips lines already posted)."
  [db variance]
  (boolean
   (d/q '[:find ?d .
          :in $ ?v
          :where
          [?d :kontor.inventory-detail/source ?v]
          [?d :kontor.inventory-detail/source-kind :variance]]
        db variance)))

(defn post-count!
  "Post a `:physical-inventory`'s count adjustments. For each CURRENT
   (non-superseded) variance line with a non-zero `:qoh-var`:
   routes a `plan-stock-move` through the GL — a negative variance
   (shrinkage) is `:direction :out` (the loss leg routed by
   `:account-fn`); a positive variance (found stock) is
   `:direction :in` at `:found-unit-cost` — AND appends the physical
   `:inventory-detail` (`:source-kind :variance`, `:source` → the
   variance, `:transaction` → the GL tx). Then sets the count
   `:status :posted`. Each line is its own transaction.

   It is safe to re-run after a partial failure — a variance line
   that already has its `:source`-linked `:inventory-detail` is
   skipped. And a missing `:found-unit-cost` is caught *up front*
   (pre-flight over all lines) so it cannot leave the count
   half-posted.

   `post-count!` assumes the subledger and the perpetual record
   agree (the normal state) — a negative variance whose
   `plan-stock-move :out` underflows means valuation already drifted
   *below* physical; that must be reconciled first (a follow-up).

   Required: :physical-inventory, :book, :journal, :account-fn,
             :commodity.
   Optional: :found-unit-cost (required iff a positive variance
             exists), :provider (default: resolved from the book),
             :effective-date (default: the count's :count-date).
   Returns {:posted [variance-eids] :count n :physical-inventory eid}."
  [conn {:keys [physical-inventory book journal account-fn commodity
                provider found-unit-cost effective-date]}]
  (when-not physical-inventory (throw (ex-info ":physical-inventory required" {})))
  (when-not book               (throw (ex-info ":book required" {})))
  (when-not journal            (throw (ex-info ":journal required" {})))
  (when-not account-fn         (throw (ex-info ":account-fn required" {})))
  (when-not commodity          (throw (ex-info ":commodity required" {})))
  (let [db0 (d/db conn)
        count-date (:kontor.physical-inventory/count-date
                    (d/pull db0 [:kontor.physical-inventory/count-date] physical-inventory))
        eff  (or effective-date count-date)
        prov (or provider (ops/provider-for-book db0 book))
        lines (current-variance-lines db0 physical-inventory)
        ;; Pre-flight: a positive (found-stock) variance needs
        ;; :found-unit-cost — validate ALL lines before posting ANY,
        ;; so a missing cost can't leave the count half-posted
        ;; (review-fix CR P1-3).
        _ (when (and (nil? found-unit-cost)
                     (some (fn [v]
                             (pos? (.signum
                                    ^BigDecimal
                                    (:kontor.inventory-variance/qoh-var
                                     (d/pull db0 [:kontor.inventory-variance/qoh-var] v)))))
                           lines))
            (throw (ex-info ":found-unit-cost required — the count has a positive (found-stock) variance"
                            {:type :inventory/found-cost-required
                             :physical-inventory physical-inventory})))
        posted
        (reduce
         (fn [acc v]
           (let [db (d/db conn)]
             (if (already-posted? db v)
               acc   ; idempotent — a partial re-run skips posted lines
               (let [vr (d/pull db
                                [:kontor.inventory-variance/qoh-var
                                 :kontor.inventory-variance/reason
                                 {:kontor.inventory-variance/inventory-item
                                  [:db/id
                                   {:kontor.inventory-item/product [:db/id]}
                                   {:kontor.inventory-item/lot [:db/id]}]}]
                                v)
                     qoh-var  (:kontor.inventory-variance/qoh-var vr)
                     item     (:kontor.inventory-variance/inventory-item vr)
                     item-eid (:db/id item)
                     product  (:db/id (:kontor.inventory-item/product item))
                     lot      (:db/id (:kontor.inventory-item/lot item))]
                 (if (zero? (.signum ^BigDecimal qoh-var))
                   acc
                   (let [neg? (neg? (.signum ^BigDecimal qoh-var))
                         mag  (if neg? (.negate ^BigDecimal qoh-var) qoh-var)
                         _ (when (and (not neg?) (nil? found-unit-cost))
                             (throw (ex-info ":found-unit-cost required — the count has a positive (found-stock) variance"
                                             {:type :inventory/found-cost-required
                                              :variance v})))
                         ;; One atomic, gated process per variance line
                         ;; (ADR-067): the GL move + the physical
                         ;; :inventory-detail commit together,
                         ;; routed through transact-with-validation.
                         line-step
                         (fn [sdb _ctx]
                           (let [move-tx (posting/plan-stock-move
                                          sdb
                                          (cond-> {:direction (if neg? :out :in)
                                                   :book book :item product
                                                   :qty mag :commodity commodity
                                                   :journal journal
                                                   :effective-date eff
                                                   :provider prov
                                                   :account-fn account-fn}
                                            lot        (assoc :lot lot)
                                            (not neg?) (assoc :unit-cost
                                                              found-unit-cost)))
                                 detail (cond-> {:kontor.inventory-detail/inventory-item item-eid
                                                 :kontor.inventory-detail/effective-date eff
                                                 :kontor.inventory-detail/qoh-diff qoh-var
                                                 :kontor.inventory-detail/atp-diff qoh-var
                                                 :kontor.inventory-detail/source-kind :variance
                                                 :kontor.inventory-detail/source v
                                                 :kontor.inventory-detail/transaction -1}
                                          (:kontor.inventory-variance/reason vr)
                                          (assoc :kontor.inventory-detail/reason
                                                 (:kontor.inventory-variance/reason vr)))]
                             (conj (vec (ops/seal-stock-move move-tx eff)) detail)))]
                     (process/run-process
                      conn {:steps [line-step] :vt-from eff})
                     (conj acc v)))))))
         []
         lines)]
    (process/run-process
     conn {:steps [(fn [_sdb _ctx]
                     [{:db/id physical-inventory
                       :kontor.physical-inventory/status :posted}])]
           :vt-from eff})
    {:posted posted
     :count (count posted)
     :physical-inventory physical-inventory}))
