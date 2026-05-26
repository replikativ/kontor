(ns kontor.asset.depreciation-provider
  "The DepreciationProvider protocol + companion-shipped built-ins —
   ADR-055.

   Sibling of `kontor.tax-rate-provider` (ADR-005) and
   `kontor.costing-provider` (ADR-029): the kernel/companion ships
   the *protocol* + a handful of method built-ins; l10n modules ship
   the jurisdiction-specific impls (MACRS, AfA-degressive, CCA,
   full-expensing) and register them the same way.

   ## The protocol is event-aware via the fired-occurrence log

   `plan-schedule` is the single planning entry point. It is *pure*
   (reads a `db` value, returns a plan, transacts nothing) and
   *prospective*: it reads the book's `:schedule-occurrence` log,
   keeps every already-fired period's actual amount untouched, and
   re-plans only the un-fired tail. So a useful-life revision or a
   subsequent addition — applied to the book via
   `kontor.asset.depreciation/revise-book!` — is picked up
   automatically on the next `run-depreciation!` call, with fired
   periods never restated (IAS 16 estimate-changes are prospective).
   The research-note sketch had a separate `plan-event` method; an
   event-aware `plan-schedule` is simpler and the runner re-plans on
   every call anyway, so the two collapse into one.

   ## Conventions

   The built-ins implement the `:full` convention precisely. The
   `:convention` field is carried through into the plan so an l10n
   provider can read it, but exact first/last-period proration
   (half-year, mid-quarter, mid-month, zeitanteilig) is l10n-provider
   territory — MACRS GDS and the AfA-Tabellen bake the convention
   into their percentage tables. A built-in given a non-`:full`
   convention still computes a `:full` schedule.

   ## Effective-dated jurisdiction rules (ADR-026 pattern)

   The *which-rule-governs-this-asset* question — the German
   degressive-AfA statute windows, MACRS bonus/§179 windows — is an
   l10n concern. l10n-de ships a `:depreciation-rule` entity
   (l10n-owned namespace) with `:effective-from` / `:effective-until`
   bounds and resolves the row whose window contains the asset's
   **`:kontor.asset/acquisition-date`** — the one deliberate divergence from
   ADR-026, which keys on the transaction date. The rule that governs
   an asset is fixed at acquisition for the asset's whole life, so
   the resolved row is pinned permanently as
   `:kontor.asset-depreciation/effective-rule` at `open-book!` time and
   never re-resolved. The companion ships the *slot* + the *pattern*;
   l10n ships the *rows* + the resolution helper. A built-in here
   only ever reads `:asset-method-params` (which l10n populates from
   the resolved rule)."
  (:require [datahike.api :as d]
            [kontor.asset.depreciation :as depreciation]
            [kontor.schedule :as schedule])
  (:import [java.math BigDecimal RoundingMode]))

;; ============================================================================
;; Protocol
;; ============================================================================

(defprotocol DepreciationProvider
  "Compute a depreciation schedule for one (asset, ledger) book."

  (provider-id [provider]
    "A keyword identifying this provider impl. Matches the
     `:kontor.asset-depreciation/provider-id` stored on the book.")

  (plan-schedule [provider db book]
    "Given a `db` value and an `:asset-depreciation` book (eid or
     `[asset ledger]` pair), return the full forward plan:

       {:periods [{:sequence        long      ; 1-indexed
                   :date            #inst     ; valid-time of the charge
                   :amount          bigdec?   ; this period's depreciation
                                              ;   (nil for un-fired
                                              ;   units-of-production periods)
                   :method-used     keyword
                   :basis-remaining bigdec    ; book value carried forward
                   :fired?          boolean}  ; already in the occurrence log
                  ...]
        :convention   keyword
        :total        bigdec                  ; Σ planned amounts
        :provider-id  keyword
        :requires-units boolean               ; units-of-production only
        :rate-per-unit  bigdec}               ; units-of-production only

     PURE — reads `db`, transacts nothing. Already-fired periods
     carry their actual logged amount and `:fired? true`; the
     un-fired tail is re-planned over the remaining periods."))

;; ============================================================================
;; Shared helpers
;; ============================================================================

(defn- round2 ^BigDecimal [^BigDecimal x]
  (.setScale x 2 RoundingMode/HALF_EVEN))

(defn- bd+ ^BigDecimal [^BigDecimal a ^BigDecimal b] (.add a b))
(defn- bd- ^BigDecimal [^BigDecimal a ^BigDecimal b] (.subtract a b))

(defn- fired-amounts
  "Map of `sequence → fired :kontor.schedule-occurrence/amount` for a book's
   schedule."
  [db schedule-eid]
  (into {}
        (d/q '[:find ?seq ?amt
               :in $ ?s
               :where
               [?o :kontor.schedule-occurrence/schedule ?s]
               [?o :kontor.schedule-occurrence/sequence ?seq]
               [?o :kontor.schedule-occurrence/amount ?amt]]
             db schedule-eid)))

(defn- equal-split
  "Split `total` into `n` per-period amounts, each rounded to 2dp,
   with the LAST period absorbing the rounding remainder so the
   parts sum bit-exact to `total`. Returns a vector of n bigdecs."
  [^BigDecimal total ^long n]
  (if (zero? n)
    []
    (let [per (round2 (.divide total (BigDecimal/valueOf n) 12 RoundingMode/HALF_EVEN))
          head (vec (repeat (dec n) per))
          tail (bd- total (reduce bd+ 0M head))]
      (conj head tail))))

(defn- assert-full-convention!
  "The companion built-ins implement the `:full` convention only. A
   non-`:full` convention (half-year, mid-quarter, mid-month,
   zeitanteilig) needs an l10n provider that bakes the proration into
   its math — a built-in must NOT silently compute `:full` and return
   a wrong schedule. Fail loud."
  [{:keys [convention provider-id]}]
  (when-not (= :full convention)
    (throw (ex-info "Built-in DepreciationProvider supports the :full convention only — a non-:full convention needs an l10n provider"
                    {:type        :kontor.asset/unsupported-convention
                     :convention  convention
                     :provider-id provider-id}))))

(defn- assemble
  "Build the `:periods` vector. `starting-book-value` is the carrying
   amount depreciation starts from — `depreciable-base +
   salvage-value`, so a fully-depreciated book lands exactly on
   salvage whatever the book's base (a tax book with a bonus-reduced
   base still threads correctly — code-review P0-1/P1-2).

   `unfired-amount` is `(fn [sequence book-value state] → [amount
   state'])`. `state` lets a method thread its own carry (e.g. the
   declining-balance switch-to-SL flag) without a mutable cell. Fired
   periods use their logged amount and pass `state` through
   untouched."
  [{:keys [n-periods start-date frequency]} starting-book-value fired
   method-used unfired-amount]
  (loop [seq 1
         book-value starting-book-value
         state nil
         acc []]
    (if (> seq n-periods)
      acc
      (let [fired? (contains? fired seq)
            [amount state'] (if fired?
                              [(fired seq) state]
                              (unfired-amount seq book-value state))
            book-value' (if amount (bd- book-value amount) book-value)]
        (recur (inc seq)
               book-value'
               state'
               (conj acc {:sequence        seq
                          :date            (schedule/date-of-occurrence
                                            start-date frequency seq)
                          :amount          amount
                          :method-used     method-used
                          :basis-remaining book-value'
                          :fired?          fired?}))))))

(defn- plan-result
  [inputs periods]
  {:periods     periods
   :convention  (:convention inputs)
   :total       (reduce (fn [^BigDecimal a p]
                          (if-let [amt (:amount p)] (bd+ a amt) a))
                        0M periods)
   :provider-id (:provider-id inputs)})

;; ============================================================================
;; Straight-line
;; ============================================================================

(defrecord StraightLineProvider []
  DepreciationProvider
  (provider-id [_] :straight-line)
  (plan-schedule [_ db book]
    (let [{:keys [schedule depreciable-base salvage-value n-periods] :as inputs}
          (depreciation/book-plan-inputs db book)
          _ (assert-full-convention! inputs)
          fired (fired-amounts db schedule)
          accumulated (reduce bd+ 0M (vals fired))
          unfired (vec (sort (remove fired (range 1 (inc n-periods)))))
          remaining (bd- depreciable-base accumulated)
          amts (equal-split remaining (count unfired))
          unfired->amt (zipmap unfired amts)]
      (plan-result inputs
                   (assemble inputs (bd+ depreciable-base salvage-value)
                             fired :straight-line
                             (fn [seq _bv state] [(unfired->amt seq) state]))))))

;; ============================================================================
;; Declining-balance (with optional switch to straight-line)
;; ============================================================================

(defn- periods-per-year ^long [frequency]
  (case frequency :monthly 12 :quarterly 4 :annual 1))

(defrecord DecliningBalanceProvider []
  DepreciationProvider
  (provider-id [_] :declining-balance)
  (plan-schedule [_ db book]
    (let [{:keys [schedule depreciable-base salvage-value n-periods frequency
                  method-params]
           :as inputs}
          (depreciation/book-plan-inputs db book)
          _ (assert-full-convention! inputs)
          fired (fired-amounts db schedule)
          multiple (or (:kontor.asset-method-params/rate-multiple method-params) 2M)
          ceiling  (:kontor.asset-method-params/ceiling-rate method-params)
          switch?  (boolean (:kontor.asset-method-params/switch-to-straight-line method-params))
          ;; sl-rate is per-period (1/n-periods); db-rate stays
          ;; per-period; the annual :ceiling-rate is converted to a
          ;; per-period cap — all three on the same time base.
          sl-rate  (.divide 1M (BigDecimal/valueOf n-periods) 12 RoundingMode/HALF_EVEN)
          db-rate  (let [r (.multiply ^BigDecimal multiple sl-rate)]
                     (if ceiling
                       (let [cap (.divide ^BigDecimal ceiling
                                          (BigDecimal/valueOf (periods-per-year frequency))
                                          12 RoundingMode/HALF_EVEN)]
                         (if (pos? (.compareTo r cap)) cap r))
                       r))
          unfired (vec (sort (remove fired (range 1 (inc n-periods)))))
          last-unfired (last unfired)]
      (plan-result
       inputs
       (assemble
        ;; Book value threads from depreciable-base + salvage, so DB
        ;; depreciates exactly :depreciable-base whatever the book's
        ;; base (code-review P0-1).
        inputs (bd+ depreciable-base salvage-value) fired :declining-balance
        ;; `state` carries the switch-to-SL flag — once SL ≥ DB it
        ;; stays switched. No mutable cell; the flag rides the
        ;; `assemble` accumulator (code-review P2-1).
        (fn [seq book-value switched?]
          (let [floor (bd- book-value salvage-value)]
            (cond
              ;; Final un-fired period drives book value exactly to
              ;; salvage — guarantees Σ = depreciable-base.
              (= seq last-unfired)
              [(if (pos? (.signum floor)) floor 0M) switched?]

              (not (pos? (.signum floor)))
              [0M switched?]

              :else
              (let [db-amt0 (round2 (.multiply book-value db-rate))
                    db-amt  (if (pos? (.compareTo db-amt0 floor)) floor db-amt0)
                    ;; remaining un-fired periods from `seq` forward,
                    ;; inclusive — the SL denominator.
                    remaining-n (count (filter #(>= % seq) unfired))
                    sl-amt (round2 (.divide floor (BigDecimal/valueOf
                                                   (max 1 remaining-n))
                                            12 RoundingMode/HALF_EVEN))
                    switched?' (or switched?
                                   (and switch? (>= (.compareTo sl-amt db-amt) 0)))]
                [(if switched?' sl-amt db-amt) switched?'])))))))))

;; ============================================================================
;; Sum-of-years'-digits
;; ============================================================================

(defrecord SumOfYearsDigitsProvider []
  DepreciationProvider
  (provider-id [_] :sum-of-years-digits)
  (plan-schedule [_ db book]
    (let [{:keys [schedule depreciable-base salvage-value n-periods] :as inputs}
          (depreciation/book-plan-inputs db book)
          _ (assert-full-convention! inputs)
          fired (fired-amounts db schedule)
          accumulated (reduce bd+ 0M (vals fired))
          unfired (vec (sort (remove fired (range 1 (inc n-periods)))))
          m (count unfired)
          remaining (bd- depreciable-base accumulated)
          sum-weights (BigDecimal/valueOf (long (/ (* m (inc m)) 2)))
          ;; k-th un-fired period (k from 1) gets weight (m − k + 1).
          raw (mapv (fn [k]
                      (if (zero? m)
                        0M
                        (round2 (.divide (.multiply remaining
                                                    (BigDecimal/valueOf (- m k -1)))
                                         sum-weights 12 RoundingMode/HALF_EVEN))))
                    (range 1 (inc m)))
          ;; Last un-fired period absorbs the rounding remainder.
          amts (if (zero? m)
                 raw
                 (conj (vec (butlast raw))
                       (bd- remaining (reduce bd+ 0M (butlast raw)))))
          unfired->amt (zipmap unfired amts)]
      (plan-result inputs
                   (assemble inputs (bd+ depreciable-base salvage-value)
                             fired :sum-of-years-digits
                             (fn [seq _bv state] [(unfired->amt seq) state]))))))

;; ============================================================================
;; Units-of-production
;; ============================================================================
;;
;; The one method whose schedule is NOT fully forward-computable: the
;; per-period amount depends on actual usage. `plan-schedule` returns
;; the dates + a `:rate-per-unit` and `:requires-units true`; the
;; runner supplies the per-period unit actuals and computes
;; `amount = rate-per-unit × units`.

(defrecord UnitsOfProductionProvider []
  DepreciationProvider
  (provider-id [_] :units-of-production)
  (plan-schedule [_ db book]
    (let [{:keys [schedule depreciable-base n-periods start-date frequency
                  method-params]
           :as inputs}
          (depreciation/book-plan-inputs db book)
          _ (assert-full-convention! inputs)
          total-units (:kontor.asset-method-params/total-units method-params)
          _ (when-not (and total-units (pos? (.signum ^BigDecimal total-units)))
              (throw (ex-info "units-of-production needs :kontor.asset-method-params/total-units > 0"
                              {:book (:book inputs)})))
          rate (.divide ^BigDecimal depreciable-base ^BigDecimal total-units
                        12 RoundingMode/HALF_EVEN)
          fired (fired-amounts db schedule)
          periods (mapv (fn [seq]
                          {:sequence        seq
                           :date            (schedule/date-of-occurrence
                                             start-date frequency seq)
                           :amount          (get fired seq)
                           :method-used     :units-of-production
                           :basis-remaining nil
                           :fired?          (contains? fired seq)})
                        (range 1 (inc n-periods)))]
      (assoc (plan-result inputs periods)
             :requires-units true
             :rate-per-unit  rate))))

;; ============================================================================
;; Registry
;; ============================================================================

(def ^:private built-ins
  {:straight-line       ->StraightLineProvider
   :declining-balance   ->DecliningBalanceProvider
   :sum-of-years-digits ->SumOfYearsDigitsProvider
   :units-of-production ->UnitsOfProductionProvider})

(defn provider-for
  "Resolve a `:kontor.asset-depreciation/provider-id` keyword to a built-in
   `DepreciationProvider` instance. l10n modules pass their own impl
   instance directly to the runner instead of going through this."
  [provider-id]
  (if-let [ctor (built-ins provider-id)]
    (ctor)
    (throw (ex-info "No built-in DepreciationProvider for this id — pass an l10n provider instance directly"
                    {:provider-id provider-id
                     :built-ins (set (keys built-ins))}))))
