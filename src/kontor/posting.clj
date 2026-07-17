(ns kontor.posting
  "Build draft transactions out of postings, validating the structural
   double-entry invariants:

     1. A transaction has 2+ postings.
     2. Postings sum to zero **per ledger per commodity** (ADR-021 +
        the double-entry rule — multi-currency moves balance per
        currency independently, and parallel ledgers are each their
        own self-balancing book).
     3. Each posting has the minimum required fields (account,
        amount, commodity).
     4. The transaction has the minimum required header fields
        (journal, effective-date).

   What this module does NOT do:
     - Tax expansion (`kontor.tax` will plug in there)
     - Sealing / posted-at lifecycle (`kontor.compliance.sealing`)
     - Period-locked rejection (`kontor.compliance.period`)
     - Account-active / commodity-match checks (the invariant library
       will, per ADR-011)

   `build-transaction` produces a tx-data vector ready to hand to
   `datahike.api/transact`, plus a small report. It does not connect
   to a db itself — the validations performed here are purely
   structural, not catalog-aware. Callers compose this with the
   db-aware checks in `validation.clj` (Phase 1)."
  (:require [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.provider.costing-provider :as costing]
            [kontor.gate :as gate]
            [kontor.money :as money]
            [kontor.posting.build :as build]
            [kontor.posting.validate :as pv]
            [kontor.provider.valuation :as valuation]))

;; `kontor.posting` is one of kontor.validation's sub-validators (it
;; supplies `assert-postings-sum-to-zero!`). Per T-2 the
;; gate API lives in the leaf ns `kontor.gate`, which depends on
;; neither this ns nor kontor.validation — so a static require is
;; cycle-free.

;; ============================================================================
;; Structural validation — re-exported from kontor.posting.validate (.cljc)
;; ============================================================================
;;
;; The pure "does it balance / is it well-formed" check lives in
;; `kontor.posting.validate` (a `.cljc` unit depending only on
;; `kontor.money`) so the SAME logic runs client-side in the browser and
;; server-side, with no drift (research note 190). It is re-exported here
;; so every existing JVM caller — and the sum-to-zero sub-validator — keeps
;; resolving `kontor.posting/validate` (and the public balance/mode
;; helpers) unchanged. See that namespace for implementations + ADR refs.

(def default-display-type
  "See `kontor.posting.validate/default-display-type`."
  pv/default-display-type)

(def balance-affecting? pv/balance-affecting?)
(def balance-by-ledger-and-commodity pv/balance-by-ledger-and-commodity)
(def unbalanced-ledger-commodities pv/unbalanced-ledger-commodities)
(def multi-entity-mode? pv/multi-entity-mode?)
(def mixed-entity-mode? pv/mixed-entity-mode?)
(def balance-by-entity-ledger-and-commodity pv/balance-by-entity-ledger-and-commodity)
(def unbalanced-entity-ledger-commodities pv/unbalanced-entity-ledger-commodities)

(defn validate
  "Pure structural validation of a draft transaction — re-exported from
   `kontor.posting.validate` (.cljc). No db access; use for non-throwing
   inspection of a draft before committing. Returns
     {:ok? :mode :transaction :postings :errors :balance :unbalanced}.
   See `kontor.posting.validate/validate` for the full docstring + the
   ADR-021 / ADR-031 sum-to-zero semantics."
  [input]
  (pv/validate input))

;; The pure tx-data builders moved to kontor.posting.build (.cljc) so the
;; browser can build posting entities client-side (rung 1, note 192).
;; Re-exported so every JVM caller — and post-transaction! below — resolves
;; kontor.posting/build-transaction and /post-transaction-tx-data unchanged.

(def build-transaction
  "Re-exported from kontor.posting.build (.cljc). Pure tx-data builder for a
   draft transaction; see that namespace for the full input-shape docstring."
  build/build-transaction)

(def post-transaction-tx-data
  "Re-exported from kontor.posting.build (.cljc). Pure builder that seals
   (state :posted + :posted-at) and stamps valid-time via kbt/with-vt."
  build/post-transaction-tx-data)

(defn post-transaction!
  "Build + seal a balanced transaction atomically. Routes through
   the gate (ADR-068).

   Input shape mirrors `build-transaction`. Opts:
     :posted-at  — sealing timestamp (default = now)
     :vt-from    — valid-time start (default = :kontor.transaction/effective-date)
     :vt-to      — valid-time end (default = kbt/forever)

   This is the kernel-level kernel-pure way to build + seal in one
   move. Companion modules with richer lifecycles (orders, invoices,
   procurement) compose the status machine on top — see
   `kontor.invoice.posting/post-to-ledger!` for the ADR-038-integrated
   variant.

   The pure tx-data builder is `post-transaction-tx-data`."
  ([conn input] (post-transaction! conn input {}))
  ([conn input opts]
   (gate/transact-with-validation conn (post-transaction-tx-data input opts))))

;; ============================================================================
;; Analytic-distribution expansion (ADR-022, split-line strategy)
;; ============================================================================
;;
;; Per ADR-022 kontor stores analytic distributions on a single posting
;; by default (Odoo-style — preserves the distribution as a queryable
;; fact). `expand-distribution` is the opt-in helper for consumers who
;; want the SAP / NetSuite "one posting per cost center" shape.
;;
;; Usage:
;;   (-> {:transaction {...} :postings [posting]}
;;       (update :postings #(into [] (mapcat (fn [p]
;;                                              (expand-distribution
;;                                               p [:kontor.analytic-plan/code "COST-CENTER"])))
;;                                 %)))
;;       (build-transaction))

(defn- distribution-matches-plan?
  "True iff a distribution map's :kontor.analytic-distribution/plan value
   equals the supplied plan-spec. Plan-spec may be an eid, a
   lookup-ref like [:kontor.analytic-plan/code \"COST-CENTER\"], or any
   value that the caller used in the distribution entry."
  [plan-spec dist]
  (= plan-spec (:kontor.analytic-distribution/plan dist)))

(defn- precision-for-amount
  "At the build/expansion layer we don't have DB access to query
   :kontor.commodity/precision, so derive precision from the Money's amount
   scale (max 2 for fiat). Per ADR-013."
  [^java.math.BigDecimal amt]
  (max 2 (.scale amt)))

(defn- expand-distribution--per-plan
  "Split a posting's amount across the distribution entries that
   match `plan-spec`. Other plans' distributions ride along on each
   child unchanged. See `expand-distribution`."
  [posting plan-spec]
  (let [all-dists (:kontor.posting/analytic-distributions posting)
        matching  (filterv (partial distribution-matches-plan? plan-spec) all-dists)
        others    (filterv (complement (partial distribution-matches-plan? plan-spec))
                           all-dists)]
    (if (empty? matching)
      [posting]
      (let [parent-money (money/posting->money posting)
            percents     (mapv :kontor.analytic-distribution/percent matching)
            precision    (precision-for-amount (:amount parent-money))
            splits       (money/split-by-percentages parent-money percents precision)]
        (->> (map vector matching splits)
             ;; Drop zero-percent children — no zero-amount postings.
             (remove (fn [[d _]]
                       (zero? (compare (bigdec (:kontor.analytic-distribution/percent d))
                                       0M))))
             (mapv (fn [[dist split-money]]
                     (-> posting
                         (assoc :kontor.posting/amount (:amount split-money))
                         (assoc :kontor.posting/analytic-distributions
                                (into [{:kontor.analytic-distribution/plan plan-spec
                                        :kontor.analytic-distribution/account
                                        (:kontor.analytic-distribution/account dist)
                                        :kontor.analytic-distribution/percent 100M}]
                                      others))))))))))

(defn expand-distribution
  "Expand a single posting that carries analytic distributions under
   `plan-spec` into N postings, one per distribution entry, with the
   amount split per percent (largest-remainder method — see
   `kontor.money/split-by-percentages`).

   `plan-spec` matches a distribution's :kontor.analytic-distribution/plan
   value exactly (eid, lookup-ref, etc. — pass it in whatever form
   the caller used to tag the distribution).

   Each child posting inherits:
     - :kontor.posting/account, :kontor.posting/commodity, :kontor.posting/ledger
     - :kontor.posting/partner, :kontor.posting/narration
     - :kontor.posting/display-type, :kontor.posting/period-tag
     - other plans' distributions (they ride along unchanged on each
       child — the default semantic). Pass :strategy :cartesian to
       split across all plans simultaneously (rare; not yet implemented).

   Child amount = trunc(amount × percent / 100), with residue
   redistributed by largest-remainder so the children sum bit-exact
   to the parent.

   Distributions with :kontor.analytic-distribution/percent = 0 produce no
   child (we don't emit zero-amount postings).

   If the posting carries no distributions for `plan-spec`, returns
   `[posting]` unchanged (caller can fold this into a mapcat).

   The expander does NOT validate the per-plan sum-to-100 invariant
   itself — that's `kontor.posting/validate`'s job at posting time.
   Callers that build by hand should validate before expanding.

   Returns a sequence of posting maps."
  ([posting plan-spec]
   (expand-distribution posting plan-spec {}))
  ([posting plan-spec {:keys [strategy] :or {strategy :per-plan}}]
   (case strategy
     :per-plan  (expand-distribution--per-plan posting plan-spec)
     :cartesian (throw (ex-info "expand-distribution :cartesian not yet implemented"
                                {:posting posting :plan-spec plan-spec}))
     (throw (ex-info "Unknown :strategy" {:strategy strategy})))))

;; ============================================================================
;; Stock-move posting builder — ADR-030
;; ============================================================================

(def stock-move-roles
  "Stable vocabulary of `account-fn` role keywords the stock-move
   builder asks for. Consumers should switch on these and return a
   resolved account ref for each.

   ## Receipt + issue (in use today)

     :inventory         — the stock asset account (Dr on receipt,
                          Cr on issue / scrap)
     :gr-ir-clearing    — Goods-Received / Invoice-Received clearing
                          (Cr on receipt; flips to AP at invoice time)
     :cogs              — Cost-of-Goods-Sold expense (Dr on issue
                          for sale; Continental jurisdictions may
                          redirect to a generic material-expense
                          account via the account-fn)
     :price-variance    — Purchase-Price-Variance / Material-Usage-
                          Variance expense (standard-cost provider
                          emits this leg when actual ≠ standard)

   ## Adjustments (reserved; consumed by a future `plan-adjustment-move`)

     :landed-cost-clearing — accrual account for landed-cost
                             vouchers arriving after receipt
     :revaluation-gain     — Cr leg of an upward revaluation
     :revaluation-loss     — Dr leg of a downward revaluation
     :write-down-expense   — Dr leg of an inventory write-down
     :write-up-revenue     — Cr leg of an inventory write-up

   New roles may be added; existing roles must remain spelled this
   way (forward-compat surface — published once, never renamed)."
  #{:inventory :gr-ir-clearing :cogs :price-variance
    :landed-cost-clearing :revaluation-gain :revaluation-loss
    :write-down-expense :write-up-revenue})

(defn- ^java.math.BigDecimal money-amount [bd]
  (.setScale ^java.math.BigDecimal bd 2 java.math.RoundingMode/HALF_EVEN))

(defn- ledger-assoc
  "Conditionally tag a posting map with :kontor.posting/ledger. Omits the key
   when `ledger` is nil so the kernel defaults apply (ADR-021)."
  [posting ledger]
  (cond-> posting
    ledger (assoc :kontor.posting/ledger ledger)))

(defn- receipt-postings
  "Build the GL postings for a receipt: Dr inventory / Cr GR-IR-clearing
   (+ optional price-variance line for standard cost).

   `account-fn` resolves accounts by role keyword."
  [{:keys [tx-tempid commodity ledger qty unit-cost variance account-fn move]}]
  (let [total      (money-amount (.multiply ^java.math.BigDecimal qty
                                            ^java.math.BigDecimal unit-cost))
        gr-ir-amt  (if variance
                     (money-amount (.add total ^java.math.BigDecimal variance))
                     total)
        base       [(ledger-assoc
                     {:kontor.posting/account     (account-fn move :inventory)
                      :kontor.posting/amount      total
                      :kontor.posting/commodity   commodity
                      :kontor.posting/transaction tx-tempid}
                     ledger)
                    (ledger-assoc
                     {:kontor.posting/account     (account-fn move :gr-ir-clearing)
                      :kontor.posting/amount      (.negate ^java.math.BigDecimal gr-ir-amt)
                      :kontor.posting/commodity   commodity
                      :kontor.posting/transaction tx-tempid}
                     ledger)]]
    (if (and variance (not (zero? (.signum ^java.math.BigDecimal variance))))
      (conj base
            (ledger-assoc
             {:kontor.posting/account     (account-fn move :price-variance)
              :kontor.posting/amount      ^java.math.BigDecimal variance
              :kontor.posting/commodity   commodity
              :kontor.posting/transaction tx-tempid}
             ledger))
      base)))

(defn- assert-balanced!
  "Validate the assembled postings for a stock-move tx and throw if
   they don't sum to zero per (ledger, commodity). Mirrors
   `build-transaction`'s contract so `plan-stock-move` cannot emit
   structurally-broken tx-data."
  [tx-base posting-entities]
  (let [report (validate {:transaction tx-base :postings posting-entities})]
    (when-not (:ok? report)
      (throw (ex-info "plan-stock-move: assembled tx is not balanced"
                      {:report report
                       :postings posting-entities})))))

(defn- issue-postings
  "Build the GL postings for an issue: Dr cogs / Cr inventory at
   total consumption value."
  [{:keys [tx-tempid commodity ledger consumptions account-fn move]}]
  (let [total (reduce (fn [^java.math.BigDecimal acc {:keys [qty unit-cost]}]
                        (.add acc (.multiply ^java.math.BigDecimal qty
                                             ^java.math.BigDecimal unit-cost)))
                      0M
                      consumptions)
        total (money-amount total)]
    [(ledger-assoc
      {:kontor.posting/account     (account-fn move :cogs)
       :kontor.posting/amount      total
       :kontor.posting/commodity   commodity
       :kontor.posting/transaction tx-tempid}
      ledger)
     (ledger-assoc
      {:kontor.posting/account     (account-fn move :inventory)
       :kontor.posting/amount      (.negate total)
       :kontor.posting/commodity   commodity
       :kontor.posting/transaction tx-tempid}
      ledger)]))

(defn plan-stock-move
  "Plan the kernel-level facts for a single stock movement. Pure
   (db-read only; no transact). Returns tx-data ready for
   `datahike.api/transact`.

   Mandatory input keys:
     :direction       :in | :out
     :book            valuation-book eid or :kontor.valuation-book/code string
     :item            ref (caller-defined item entity)
     :qty             bigdec
     :commodity       commodity ref
     :journal         journal ref
     :effective-date  #inst
     :unit-cost       bigdec  — required for :in
     :provider        CostingProvider impl — required
     :account-fn      (fn [move role-kw] → account ref) — required

   Optional keys:
     :ledger     ledger ref (defaults to nil = primary ledger group)
     :lot        lot ref
     :narration  string
     :transaction-state  :draft (default) | :posted
     :note       string

   ADR-030 — pure; no side effects; reads `db` for layer resolution
   on issues. Hooks are external (the CostingProvider and account-fn
   ARE the seams).

   For an :in move:
     - Resolve receipt via `(plan-receipt provider db request)` →
       create one new :valuation-layer.
     - Emit Dr inventory / Cr GR-IR-clearing postings (+ optional
       :price-variance leg from standard-cost provider).

   For an :out move:
     - Resolve consumption via `(plan-consumption provider db request)`
       → create N :layer-consumption entities pointing at drawn layers.
     - Emit Dr COGS / Cr inventory postings at total consumption value.

   Returns tx-data as a flat vector. Caller composes:
     (d/transact conn (plan-stock-move db move-spec))"
  [db {:keys [direction book item qty commodity lot journal effective-date
              unit-cost ledger narration provider account-fn
              transaction-state note]
       :or   {transaction-state :draft}
       :as   move-spec}]
  (when-not provider
    (throw (ex-info "plan-stock-move: :provider is required" {:move move-spec})))
  (when-not account-fn
    (throw (ex-info "plan-stock-move: :account-fn is required" {:move move-spec})))
  (when-not (#{:in :out} direction)
    (throw (ex-info "plan-stock-move: :direction must be :in or :out"
                    {:move move-spec})))
  (let [book-eid (valuation/resolve-book db book)
        _ (when-not book-eid
            (throw (ex-info "plan-stock-move: book not found"
                            {:book book})))
        tx-tempid -1
        tx-base   (cond-> {:db/id                       tx-tempid
                           :kontor.transaction/journal         journal
                           :kontor.transaction/effective-date  effective-date
                           :kontor.transaction/state           transaction-state}
                    narration (assoc :kontor.transaction/narration narration))]
    (case direction

      :in
      (let [receipt-req {:book      book-eid
                         :item      item
                         :qty       qty
                         :unit-cost unit-cost
                         :commodity commodity
                         :lot       lot}
            {:keys [layer-data variance]}
            (costing/plan-receipt provider db receipt-req)
            layer-tempid -200
            layer-entity (cond-> {:db/id                              layer-tempid
                                  :kontor.valuation-layer/book               book-eid
                                  :kontor.valuation-layer/item               item
                                  :kontor.valuation-layer/origin-transaction tx-tempid
                                  :kontor.valuation-layer/qty-original       (:qty layer-data)
                                  :kontor.valuation-layer/unit-cost-original (:unit-cost layer-data)
                                  :kontor.valuation-layer/commodity          commodity
                                  :kontor.valuation-layer/received-at        effective-date}
                           lot  (assoc :kontor.valuation-layer/lot lot)
                           note (assoc :kontor.valuation-layer/note note))
            postings (receipt-postings
                      {:tx-tempid tx-tempid
                       :commodity commodity
                       :ledger    ledger
                       :qty       qty
                       ;; Use the LAYER's unit-cost for the inventory leg.
                       ;; For FIFO/AVG that's the user's unit-cost (no variance).
                       ;; For Standard that's the standard cost; variance carries
                       ;; the delta and lands on the variance leg.
                       :unit-cost (:unit-cost layer-data)
                       :variance  variance
                       :account-fn account-fn
                       :move      move-spec})
            posting-entities
            (mapv (fn [i p]
                    (cond-> (assoc p :db/id (- -300 i))
                      (nil? (:kontor.posting/display-type p))
                      (assoc :kontor.posting/display-type :product)))
                  (range)
                  postings)
            _ (assert-balanced! tx-base posting-entities)]
        (kbt/with-vt (into [tx-base layer-entity] posting-entities)
          effective-date kbt/forever))

      :out
      (let [;; Thread the move's effective-date as the bitemporal
            ;; cursor for layer + consumption visibility. A backdated
            ;; issue sees only layers received at or before its
            ;; effective-date and only consumptions already issued.
            consumption-req {:book book-eid :item item :qty qty :lot lot
                             :as-of-valid effective-date}
            {:keys [consumptions underflow]}
            (costing/plan-consumption provider db consumption-req)]
        (when (and underflow (pos? (.signum ^java.math.BigDecimal underflow)))
          (throw (ex-info "plan-stock-move: insufficient stock to satisfy issue"
                          {:requested qty :underflow underflow :item item :book book-eid})))
        (let [postings (issue-postings
                        {:tx-tempid tx-tempid
                         :commodity commodity
                         :ledger    ledger
                         :consumptions consumptions
                         :account-fn account-fn
                         :move      move-spec})
              consumption-entities
              (mapv (fn [i {:keys [layer qty unit-cost]}]
                      {:db/id                                       (- -400 i)
                       :kontor.layer-consumption/layer                     layer
                       :kontor.layer-consumption/qty                       qty
                       :kontor.layer-consumption/unit-cost-at-consumption  unit-cost
                       :kontor.layer-consumption/issue-transaction         tx-tempid
                       :kontor.layer-consumption/issued-at                 effective-date})
                    (range)
                    consumptions)
              posting-entities
              (mapv (fn [i p]
                      (cond-> (assoc p :db/id (- -300 i))
                        (nil? (:kontor.posting/display-type p))
                        (assoc :kontor.posting/display-type :product)))
                    (range)
                    postings)
              _ (assert-balanced! tx-base posting-entities)]
          (kbt/with-vt (into (into [tx-base] posting-entities) consumption-entities)
            effective-date kbt/forever))))))
