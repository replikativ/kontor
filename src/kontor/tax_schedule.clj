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

   ## Schedule combinators

     :elect    apply several sub-schedules to the SAME base, pick the
               `:min` or `:max` — the taxpayer-election shape
               (FR PFU-vs-barème). For a regime election where the
               choice is an explicit input, the provider just passes
               the chosen sub-schedule directly.
     :sum      apply several sub-schedules to the SAME base and ADD
               the results — a base-surcharge levied alongside the
               main schedule (AU Medicare levy on taxable income next
               to the income-tax brackets). The additive sibling of
               `:elect`.

   ## Base transform — `apply-base-transform`

   `apply-base-transform` is the stage BETWEEN the marginalized
   aggregate and the schedule (ADR-099 addendum, note 103 GAP 1): the
   taxable base is often not the raw aggregate but a transform of it —
   a presumption ratio (BR Lucro Presumido: 8 %/32 % × revenue) or
   book profit ± statutory add-backs (corporate income tax). Tagged
   data over a closed `:transform/type` set; an absent transform is
   the identity.

   ## Component-level combinators

   `surtax-on` is the tax-on-a-tax operator — a rate applied to
   another component's *liability*, not to a base (DE Soli, JP
   復興特別所得税, IN/BR cess).

   `greater-of` / `lesser-of` combine two already-computed component
   liabilities — `greater-of` is the minimum-tax shape (AMT, IN MAT:
   `max(regular-tax, minimum-tax)` where the two arms have DIFFERENT
   bases, so it cannot be an `:elect` schedule — note 103 GAP 2).

   All three are component-level operations, not `base → liability`
   schedules, so they are separate fns — composed by the provider at
   the `TaxReturnFacts` level.")

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
  [{:keys [choose schedules]} base ctx]
  (let [liabilities (map #(apply-schedule % base ctx) schedules)]
    (case choose
      :min (apply min liabilities)
      :max (apply max liabilities)
      (throw (ex-info "elect schedule: :choose must be :min or :max"
                      {:choose choose})))))

(defn- sum-tax
  [{:keys [schedules]} base ctx]
  (reduce (fn [acc s] (+ acc (apply-schedule s base ctx))) 0M schedules))

(defn apply-schedule
  "Run BigDecimal `base` through `schedule`, returning the BigDecimal
   gross liability. `schedule` is plain data tagged by
   `:kontor.schedule/type` — `:flat | :progressive-bracket | :capped |
   :formula | :elect` (see the ns docstring).

   The optional `ctx` map is threaded to `:formula` schedules — its
   `:tax-unit` lets a schedule depend on household / filing status
   (FR quotient familial, DE Ehegattensplitting; note 103 GAP 3). A
   `:formula` fn is `(fn [base ctx])`."
  (^java.math.BigDecimal [schedule base]
   (apply-schedule schedule base nil))
  (^java.math.BigDecimal [schedule ^java.math.BigDecimal base ctx]
   (case (:kontor.schedule/type schedule)
     :flat                (* base (:rate schedule))
     :progressive-bracket (progressive-tax (:brackets schedule) base)
     :capped              (capped-tax schedule base)
     :formula             ((:fn schedule) base ctx)
     :elect               (elect-tax schedule base ctx)
     :sum                 (sum-tax schedule base ctx)
     (throw (ex-info "apply-schedule: unknown :kontor.schedule/type"
                     {:schedule schedule})))))

;; ============================================================================
;; Base transform — the stage between the aggregate and the schedule
;; ============================================================================

(def transform-types
  "The closed set of `:transform/type` values `apply-base-transform`
   accepts. An absent / nil transform is the identity."
  #{:identity :presumption-ratio :adjustments :formula})

(defn apply-base-transform
  "Transform a marginalized aggregate into the taxable `:base` before
   the schedule (ADR-099 addendum, note 103 GAP 1). `transform` is
   tagged data; `nil` / absent is the identity.

     {:transform/type :identity}                  base unchanged
     {:transform/type :presumption-ratio          ratio × base
      :ratio <bigdec>}                            (BR Lucro Presumido)
     {:transform/type :adjustments                base + Σadditions
      :additions [<bigdec>] :deductions [<bigdec>]}  − Σdeductions
                                                  (CIT book → taxable)
     {:transform/type :formula :fn <fn>}          (fn base)"
  ^java.math.BigDecimal [transform ^java.math.BigDecimal base]
  (case (:transform/type transform)
    nil                base
    :identity          base
    :presumption-ratio (* base (:ratio transform))
    :adjustments       (- (+ base (reduce + 0M (:additions transform)))
                          (reduce + 0M (:deductions transform)))
    :formula           ((:fn transform) base)
    (throw (ex-info "apply-base-transform: unknown :transform/type"
                    {:transform transform}))))

;; ============================================================================
;; Component-level combinators
;; ============================================================================

(defn surtax-on
  "Tax-on-a-tax: a `rate` applied to a prior component's
   `prior-liability` (DE Solidaritätszuschlag, JP reconstruction
   surtax, IN/BR cess). Both args BigDecimal."
  ^java.math.BigDecimal [^java.math.BigDecimal rate ^java.math.BigDecimal prior-liability]
  (* prior-liability rate))

(defn greater-of
  "The larger of two already-computed liabilities — the minimum-tax
   shape (AMT, IN MAT: the two arms have different bases, so this is
   a component-level `max`, not an `:elect` schedule; note 103 GAP 2)."
  ^java.math.BigDecimal [^java.math.BigDecimal a ^java.math.BigDecimal b]
  (if (>= (compare a b) 0) a b))

(defn lesser-of
  "The smaller of two already-computed liabilities — a liability cap."
  ^java.math.BigDecimal [^java.math.BigDecimal a ^java.math.BigDecimal b]
  (if (<= (compare a b) 0) a b))

;; ============================================================================
;; The adjustment layer — an ordered, signed, base-aware credit/surtax fold
;; ============================================================================

(defn apply-adjustments
  "Fold an ordered seq of credit / surtax adjustment `items` over a
   `gross` tax — the tax-side leg of the adjustment layer (research
   note 105 frontier 1; ADR-101 unified-vocab `:op` set). Each item:

     {:code :label
      :op          :credit | :surtax
      :refundable? <bool>            ; :credit only — false (default) floors
      :amount      <bigdec> | <fn>}  ; data, OR a base-aware fn of ctx

   A fn `:amount` is called with `ctx` plus the current `:running`
   tax — so a credit or surtax can depend on the base, the gross tax,
   the running tax and the tax-unit, not only on a static constant or
   the post-credit tax. A non-refundable credit does
   `max(0, running − amount)`; a refundable credit `running − amount`
   (may go negative — a refund / a transfer to the taxpayer); a surtax
   `running + amount`.

   Items with `:op :base-add` or `:base-deduct` are rejected here — they
   operate on the pre-schedule base; use `apply-base-adjustments` for
   those (ADR-101). The unified vocabulary lets a provider tag every
   item with its phase: base-side items feed `apply-base-adjustments`;
   tax-side items feed `apply-adjustments`.

   Returns `{:liability <signed BigDecimal> :resolved [<item>]}` —
   each resolved item carries its numeric `:amount`, so credits and
   surtaxes surface as structured data rather than being buried in a
   `:formula`."
  [^java.math.BigDecimal gross items ctx]
  (let [result
        (reduce
         (fn [{:keys [running resolved]} item]
           (let [raw (:amount item)
                 amt (bigdec (if (fn? raw)
                               (raw (assoc ctx :running running))
                               raw))
                 running' (case (:op item)
                            :surtax (+ running amt)
                            :credit (if (:refundable? item)
                                      (- running amt)
                                      (max 0M (- running amt)))
                            (:base-add :base-deduct)
                            (throw (ex-info
                                    "apply-adjustments: base-side :op must go through apply-base-adjustments"
                                    {:item item}))
                            (throw (ex-info
                                    "apply-adjustments: :op must be :credit or :surtax"
                                    {:item item})))]
             {:running  running'
              :resolved (conj resolved (assoc item :amount amt))}))
         {:running gross :resolved []}
         items)]
    {:liability (:running result)
     :resolved  (:resolved result)}))

(defn apply-base-adjustments
  "Fold an ordered seq of base-side adjustment `items` over a pre-
   schedule `base` (ADR-101 — companion to `apply-adjustments` on the
   tax-side). Same item shape; `:op` is `:base-add` or `:base-deduct`.
   Items with tax-side `:op` (`:credit` / `:surtax`) are rejected — use
   `apply-adjustments` for those.

   The named/ordered/auditable item pattern is identical to
   `apply-adjustments`: each resolved item surfaces as structured data
   with its numeric `:amount`, so DE §9 GewSt reductions, §8 GewSt
   add-backs, KSt §10 non-deductibles, etc. each carry their own
   provenance back to the provision that introduced them — distinct
   from buried-in-a-list `:base-transform :adjustments` numbers.

   `:amount` may be a fn `(ctx-with-:running) -> bigdec`, mirroring
   `apply-adjustments`'s base-aware-fn convention.

   Returns `{:base <BigDecimal> :resolved [<item>]}`."
  [^java.math.BigDecimal base items ctx]
  (let [result
        (reduce
         (fn [{:keys [running resolved]} item]
           (let [raw (:amount item)
                 amt (bigdec (if (fn? raw)
                               (raw (assoc ctx :running running))
                               raw))
                 running' (case (:op item)
                            :base-add    (+ running amt)
                            :base-deduct (- running amt)
                            (:credit :surtax)
                            (throw (ex-info
                                    "apply-base-adjustments: tax-side :op must go through apply-adjustments"
                                    {:item item}))
                            (throw (ex-info
                                    "apply-base-adjustments: :op must be :base-add or :base-deduct"
                                    {:item item})))]
             {:running  running'
              :resolved (conj resolved (assoc item :amount amt))}))
         {:running base :resolved []}
         items)]
    {:base     (:running result)
     :resolved (:resolved result)}))

;; ============================================================================
;; Constructors — sugar
;; ============================================================================

(defn flat
  "A `:flat` schedule."
  [rate]
  {:kontor.schedule/type :flat :rate rate})

(defn progressive
  "A `:progressive-bracket` schedule. `brackets` is an ascending
   vector of `{:rate :upper}`; the last `:upper` should be nil."
  [brackets]
  {:kontor.schedule/type :progressive-bracket :brackets (vec brackets)})

(defn capped
  "A `:capped` schedule — `rate` on `[floor, ceiling]`."
  [rate {:keys [floor ceiling]}]
  (cond-> {:kontor.schedule/type :capped :rate rate}
    floor   (assoc :floor floor)
    ceiling (assoc :ceiling ceiling)))

(defn sum-of
  "A `:sum` schedule — apply every sub-schedule to the same base and
   add the results (a base-surcharge alongside the main schedule, e.g.
   AU income-tax brackets + the Medicare levy)."
  [schedules]
  {:kontor.schedule/type :sum :schedules (vec schedules)})

(def schedule-types
  "The closed set of `:kontor.schedule/type` values `apply-schedule` accepts."
  #{:flat :progressive-bracket :capped :formula :elect :sum})
