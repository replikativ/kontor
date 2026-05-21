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

(defn apply-schedule
  "Run BigDecimal `base` through `schedule`, returning the BigDecimal
   gross liability. `schedule` is plain data tagged by
   `:schedule/type` — `:flat | :progressive-bracket | :capped |
   :formula | :elect` (see the ns docstring).

   The optional `ctx` map is threaded to `:formula` schedules — its
   `:tax-unit` lets a schedule depend on household / filing status
   (FR quotient familial, DE Ehegattensplitting; note 103 GAP 3). A
   `:formula` fn is `(fn [base ctx])`."
  (^java.math.BigDecimal [schedule base]
   (apply-schedule schedule base nil))
  (^java.math.BigDecimal [schedule ^java.math.BigDecimal base ctx]
   (case (:schedule/type schedule)
     :flat                (* base (:rate schedule))
     :progressive-bracket (progressive-tax (:brackets schedule) base)
     :capped              (capped-tax schedule base)
     :formula             ((:fn schedule) base ctx)
     :elect               (elect-tax schedule base ctx)
     (throw (ex-info "apply-schedule: unknown :schedule/type"
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
