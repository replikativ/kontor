(ns kontor.tax-schedule
  "The tax-schedule algebra — ADR-099, research note 102 §3.

   A *schedule* is the `base → liability` half of a tax: the rate
   structure, as plain data. It is the regular, jurisdiction-neutral,
   property-testable core that `kontor.period-tax-provider` builds on
   — and a `:flat` schedule is exactly what a transaction tax's
   `rate × base` is, so the two tax families share this layer.

   `apply-schedule` runs a BigDecimal `base` through a schedule and
   returns the BigDecimal gross liability. It is commodity-agnostic —
   the caller wraps the result in `Money`.

   ## Base shapes — `base → liability`

     :flat                 rate × base
     :progressive-bracket  a bracket ladder (generalizes the CA
                           `apply-brackets` prototype, t1.clj:79)
     :capped               a flat rate on the slice of base inside
                           [floor, ceiling] — employer SI on a
                           contribution ceiling; floor = exempt amount
     :formula              an escape hatch — a named pure fn the l10n
                           module supplies for genuinely non-tabular
                           schedules (a notch, a taper)

   ## Combinators

     :elect    apply several sub-schedules to the SAME base, pick the
               `:min` or `:max` — the taxpayer-election shape
               (FR PFU-vs-barème). For a regime election where the
               choice is an explicit input, the provider just passes
               the chosen sub-schedule directly.

   `surtax-on` is the tax-on-a-tax operator — a rate applied to
   another component's *liability*, not to a base (DE Soli, JP
   復興特別所得税, IN/BR cess). It is a component-level operation, not
   a `base → liability` schedule, so it is a separate fn.

   Minimum-tax / AMT (`max` of two whole `(base, schedule)`
   computations over *different* bases) is composed by the provider
   at the `TaxReturnFacts` level — see note 102 §7 / §9-A; it is not
   an `apply-schedule` shape because its arms do not share a base."
  (:require [clojure.set]))

;; ============================================================================
;; Base shapes
;; ============================================================================

(defn- progressive-tax
  "Fold a base through a bracket ladder. Each bracket is
   `{:rate <bigdec> :upper <bigdec|nil>}`, ascending; the final
   bracket's `:upper` is nil (the open top band)."
  ^java.math.BigDecimal [brackets ^java.math.BigDecimal base]
  (:acc (reduce (fn [{:keys [acc prev]} {:keys [rate upper]}]
                  (let [top   (if (and upper (< upper base)) upper base)
                        slice (- top prev)]
                    {:acc  (if (pos? slice) (+ acc (* slice rate)) acc)
                     :prev (or upper base)}))
                {:acc 0M :prev 0M}
                brackets)))

(defn- capped-tax
  "A flat `:rate` on the slice of `base` inside `[floor, ceiling]`.
   `:floor` (default 0) is the exempt amount; `:ceiling` (default
   unbounded) the contribution cap."
  ^java.math.BigDecimal [{:keys [rate floor ceiling]} ^java.math.BigDecimal base]
  (let [lo      (or floor 0M)
        hi      (if ceiling (min ceiling base) base)
        taxable (- hi lo)]
    (if (pos? taxable) (* taxable rate) 0M)))

;; ============================================================================
;; apply-schedule
;; ============================================================================

(declare apply-schedule)

(defn- elect-tax
  [{:keys [choose schedules]} base]
  (let [liabilities (map #(apply-schedule % base) schedules)]
    (case choose
      :min (apply min liabilities)
      :max (apply max liabilities)
      (throw (ex-info "elect schedule: :choose must be :min or :max"
                      {:choose choose})))))

(defn apply-schedule
  "Run BigDecimal `base` through `schedule`, returning the BigDecimal
   gross liability. `schedule` is plain data tagged by
   `:schedule/type` — `:flat | :progressive-bracket | :capped |
   :formula | :elect` (see the ns docstring)."
  ^java.math.BigDecimal [schedule ^java.math.BigDecimal base]
  (case (:schedule/type schedule)
    :flat                (* base (:rate schedule))
    :progressive-bracket (progressive-tax (:brackets schedule) base)
    :capped              (capped-tax schedule base)
    :formula             ((:fn schedule) base)
    :elect               (elect-tax schedule base)
    (throw (ex-info "apply-schedule: unknown :schedule/type"
                    {:schedule schedule}))))

(defn surtax-on
  "Tax-on-a-tax: a `rate` applied to a prior component's
   `prior-liability` (DE Solidaritätszuschlag, JP reconstruction
   surtax, IN/BR cess). Both args BigDecimal."
  ^java.math.BigDecimal [^java.math.BigDecimal rate ^java.math.BigDecimal prior-liability]
  (* prior-liability rate))

;; ============================================================================
;; Constructors — sugar
;; ============================================================================

(defn flat
  "A `:flat` schedule."
  [rate]
  {:schedule/type :flat :rate rate})

(defn progressive
  "A `:progressive-bracket` schedule. `brackets` is an ascending
   vector of `{:rate :upper}`; the last `:upper` should be nil."
  [brackets]
  {:schedule/type :progressive-bracket :brackets (vec brackets)})

(defn capped
  "A `:capped` schedule — `rate` on `[floor, ceiling]`."
  [rate {:keys [floor ceiling]}]
  (cond-> {:schedule/type :capped :rate rate}
    floor   (assoc :floor floor)
    ceiling (assoc :ceiling ceiling)))

(def schedule-types
  "The closed set of `:schedule/type` values `apply-schedule` accepts."
  #{:flat :progressive-bracket :capped :formula :elect})
