(ns kontor.l10n-in.period-tax-provider
  "Indian personal income tax — as a kontor `PeriodTaxProvider`
   (ADR-099; research note 104, the tax-completion program's Stage 1).

   India is the deliberate design-stress case: a taxpayer elects,
   annually, between TWO parallel regimes that differ not only in
   their rate ladder but in which deductions feed the taxable base.

   - New regime (§115BAC — the default since FY 2023-24): a 7-band
     0/5/10/15/20/25/30 % ladder, a ₹75,000 standard deduction, very
     few other deductions, and the §87A rebate that makes a total
     income up to ₹12,00,000 effectively tax-free.
   - Old regime (optional, elected on Form 10-IEA): a 4-band
     0/5/20/30 % ladder, a ₹2,50,000 basic exemption, a ₹50,000
     standard deduction, and the full Chapter VI-A deduction surface
     (§80C, §80D, HRA, home-loan interest, …); its §87A rebate caps
     a total income of ₹5,00,000.
   - Both regimes then carry a 4 % Health & Education cess on the tax
     (a `surtax-on`) and an income-banded surcharge on high incomes.

   ## Design — regime as an explicit constructor input (note 104 §4)

   The provider takes `{:regime :new|:old}` and selects the schedule;
   the chosen regime is recorded on the `TaxReturnFacts` component's
   `:regime` field (the substrate field designed for exactly this —
   note 102 §9-C). It is NOT an `:elect` schedule: `:elect` applies
   its sub-schedules to ONE shared base (`kontor.tax-schedule` ns
   docstring; note 103 GAP 2), but the two Indian regimes do not
   share a base — the new regime's base is gross − ₹75,000, the old
   regime's is gross − ₹50,000 − Σ(Chapter VI-A). The election is a
   different-base choice, and `kontor.tax-schedule`'s own docstring
   directs: \"For a regime election where the choice is an explicit
   input, the provider just passes the chosen sub-schedule directly.\"
   See the abstraction audit in research note 104's Stage-1 report
   for the strain this still leaves (the §87A rebate).

   ## The §87A rebate lives INSIDE the schedule

   The §87A rebate is income-conditional: `min(bracket-tax, cap)` iff
   total income ≤ a regime threshold, with marginal relief just above
   it. It is neither a static `:credits` entry (those are caller-fed
   constants) nor a `:surtax-fn` (those see only the post-credit tax,
   not the income). So the regime schedule is a `:formula` —
   `taxable-income → bracket-tax → − §87A rebate → + surcharge` — the
   substrate's escape hatch carrying the real `base → liability` map.
   The 4 % cess remains a clean `:surtax-fn` on top.

   All figures are FY 2025-26 / AY 2026-27 (post Union Budget 2025).
   Verify against the current Finance Act before relying on them."
  (:require [kontor.period-tax-provider :as ptp]
            [kontor.personal-income-tax :as pit]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; Rate ladders — verify against the current Finance Act
;; ============================================================================

(def new-regime-brackets
  "§115BAC new-regime slabs, FY 2025-26 / AY 2026-27 (Union Budget
   2025). Amounts in INR. The first band is the basic exemption.
   Verify against the current Finance Act."
  [{:rate 0M    :upper 400000M}
   {:rate 0.05M :upper 800000M}
   {:rate 0.10M :upper 1200000M}
   {:rate 0.15M :upper 1600000M}
   {:rate 0.20M :upper 2000000M}
   {:rate 0.25M :upper 2400000M}
   {:rate 0.30M :upper nil}])

(def old-regime-brackets
  "Old-regime slabs, FY 2025-26 / AY 2026-27 — unchanged for years.
   Amounts in INR; the rates for an individual below 60 (the slabs
   for senior / super-senior citizens raise the exemption band).
   Verify against the current Finance Act."
  [{:rate 0M    :upper 250000M}
   {:rate 0.05M :upper 500000M}
   {:rate 0.20M :upper 1000000M}
   {:rate 0.30M :upper nil}])

(def new-regime-standard-deduction
  "New-regime standard deduction for salaried income, FY 2025-26
   (raised from ₹50,000 by Union Budget 2024). Verify."
  75000M)

(def old-regime-standard-deduction
  "Old-regime standard deduction for salaried income, FY 2025-26.
   Verify."
  50000M)

;; ============================================================================
;; §87A rebate — income-conditional, with marginal relief
;; ============================================================================

(def new-regime-87a
  "§87A rebate, new regime, FY 2025-26: a total income up to
   ₹12,00,000 pays no tax — the rebate is `min(tax, ₹60,000)`.
   The new regime grants §87A MARGINAL RELIEF: just above
   ₹12,00,000 the tax is capped at the income in excess of the
   threshold, so the notch does not punish an extra rupee. Verify
   against the current law."
  {:threshold 1200000M :cap 60000M :marginal-relief? true})

(def old-regime-87a
  "§87A rebate, old regime, FY 2025-26: a total income up to
   ₹5,00,000 pays no tax — the rebate is `min(tax, ₹12,500)`. The
   old regime's §87A is a HARD CLIFF — no marginal relief; a rupee
   above ₹5,00,000 forfeits the whole rebate. (Marginal relief in
   the old regime exists only for the surcharge, not for §87A.)
   Verify against the current law."
  {:threshold 500000M :cap 12500M :marginal-relief? false})

(defn- rebate-87a
  "The §87A rebate for `taxable-income` against `bracket-tax`.
   Below the regime threshold the rebate erases the tax (capped at
   the regime's `:cap`). Above the threshold the rebate is zero,
   EXCEPT that a regime with `:marginal-relief?` still limits the
   tax to the income in excess of the threshold (the new regime;
   the old regime's §87A is a hard cliff)."
  ^java.math.BigDecimal
  [{:keys [threshold cap marginal-relief?]} ^java.math.BigDecimal taxable-income
   ^java.math.BigDecimal bracket-tax]
  (cond
    ;; full rebate — within the threshold the tax is rebated away
    (<= taxable-income threshold)
    (min bracket-tax cap)
    ;; marginal relief — tax cannot exceed income above the threshold
    (and marginal-relief?
         (> bracket-tax (- taxable-income threshold)))
    (- bracket-tax (- taxable-income threshold))
    :else 0M))

;; ============================================================================
;; Surcharge — income-banded, with marginal relief
;; ============================================================================

(def new-regime-surcharge-bands
  "New-regime surcharge on the income tax, by total-income band,
   FY 2025-26. §115BAC caps the surcharge at 25 % (the 37 % top
   band that the old regime still carries does not apply). Each
   band is `{:over <income> :rate <surcharge-rate>}`, ascending.
   Verify against the current law."
  [{:over 5000000M  :rate 0.10M}
   {:over 10000000M :rate 0.15M}
   {:over 20000000M :rate 0.25M}])

(def old-regime-surcharge-bands
  "Old-regime surcharge on the income tax, by total-income band,
   FY 2025-26. The old regime retains the 37 % top band above
   ₹5 crore. Verify against the current law."
  [{:over 5000000M  :rate 0.10M}
   {:over 10000000M :rate 0.15M}
   {:over 20000000M :rate 0.25M}
   {:over 50000000M :rate 0.37M}])

(defn- active-surcharge-band
  "The highest surcharge band whose `:over` `taxable-income` exceeds,
   or `nil` when no surcharge applies."
  [bands ^java.math.BigDecimal taxable-income]
  (reduce (fn [b {:keys [over] :as band}]
            (if (> taxable-income over) band b))
          nil
          bands))

(defn- surcharge-with-marginal-relief
  "The surcharge on `tax`, with statutory marginal relief. The
   surcharge is `rate × tax`; but at a band threshold T the total
   `(tax + surcharge)` must not exceed `(tax + surcharge) computed at
   the income T` plus the income earned beyond T. Marginal relief is
   the amount by which that cap is breached. `tax` here is the income
   tax after the §87A rebate; `band-tax-at-threshold` is the same
   post-rebate tax recomputed at the band's `:over` income."
  ^java.math.BigDecimal
  [bands ^java.math.BigDecimal taxable-income ^java.math.BigDecimal tax
   threshold-tax-fn]
  (if-let [{:keys [over rate]} (active-surcharge-band bands taxable-income)]
    (let [raw-surcharge (* tax rate)
          ;; tax (post-rebate, no surcharge) at exactly the band floor
          tax-at-T      (threshold-tax-fn over)
          ;; the cap: tax+surcharge may not exceed this
          cap           (+ tax-at-T (- taxable-income over))
          relieved      (- (+ tax raw-surcharge) cap)]
      (if (pos? relieved)
        (max 0M (- raw-surcharge relieved))
        raw-surcharge))
    0M))

;; ============================================================================
;; The cess — a tax-on-tax surtax
;; ============================================================================

(def health-education-cess-rate
  "Health & Education Cess — 4 % of (income tax + surcharge), levied
   on both regimes. Verify against the current Finance Act."
  0.04M)

(defn- cess
  "The 4 % Health & Education cess as a computed `:surtax-fn` —
   `(income tax + surcharge − §87A rebate) → surtax`. The argument is
   the after-credits tax the substrate hands every surtax-fn (here,
   post-rebate tax-plus-surcharge, since the rebate and surcharge are
   already inside the schedule formula)."
  [tax]
  (when (pos? tax)
    {:code   :health-education-cess
     :label  "Health & Education Cess (4 %)"
     :amount (ts/surtax-on health-education-cess-rate tax)}))

;; ============================================================================
;; The regime schedule — a :formula carrying the real base → liability map
;; ============================================================================

(defn- regime-schedule
  "Build the `:formula` schedule for a regime: run the taxable income
   through the bracket ladder, subtract the §87A rebate, then add the
   income-banded surcharge with statutory marginal relief. The result
   is the tax before cess (cess is the separate `:surtax-fn`)."
  [brackets rebate-cfg surcharge-bands]
  (let [ladder        (ts/progressive brackets)
        ;; post-§87A-rebate income tax, before any surcharge
        post-rebate   (fn [^java.math.BigDecimal taxable-income]
                        (let [bracket-tax (ts/apply-schedule ladder
                                                             taxable-income)]
                          (- bracket-tax
                             (rebate-87a rebate-cfg taxable-income
                                         bracket-tax))))]
    {:schedule/type :formula
     :fn (fn [^java.math.BigDecimal taxable-income _ctx]
           (let [after-87a (post-rebate taxable-income)
                 surcharge (surcharge-with-marginal-relief
                            surcharge-bands taxable-income after-87a
                            post-rebate)]
             (+ after-87a surcharge)))}))

;; ============================================================================
;; The provider
;; ============================================================================

(def regimes
  "The two electable Indian personal-income-tax regimes."
  #{:new :old})

(defrecord InIncomeTaxProvider [inner regime]
  ptp/PeriodTaxProvider
  (provider-id [_] (ptp/provider-id inner))
  (period-tax-facts [_ context]
    ;; Delegate to the generic `PersonalIncomeTaxProvider`, then stamp
    ;; the elected regime onto every component's `:regime` field — the
    ;; substrate field designed to record which elective regime applied
    ;; (note 102 §9-C). A regime election can also ride `context
    ;; :inputs :regime`; the constructor-fixed regime takes precedence.
    (when-let [facts (ptp/period-tax-facts inner context)]
      (update facts :components
              (fn [cs] (mapv #(assoc % :regime regime) cs))))))

(defn in-income-tax-provider
  "An IN personal income tax `PeriodTaxProvider`. India runs two
   parallel regimes the taxpayer elects between annually; the chosen
   regime is an explicit constructor input.

   Config:
     :regime — `:new` (§115BAC, the statutory default) or `:old`
               (elected on Form 10-IEA). Defaults to `:new`.

   The selected regime fixes the rate ladder, the §87A rebate
   threshold/cap, and the surcharge bands — all baked into a
   `:formula` schedule (the §87A rebate is income-conditional, so it
   cannot be a static credit or a tax-only surtax). The 4 % Health &
   Education cess is a computed `:surtax-fn` on top of both regimes.
   The elected regime is recorded on every `TaxReturnFacts`
   component's `:regime` field.

   The regime ALSO governs which deductions may legitimately feed the
   `:base-transform` on `context :inputs`: the new regime allows
   essentially only the ₹75,000 standard deduction, the old regime
   the full Chapter VI-A surface (₹50,000 standard deduction + §80C /
   §80D / HRA / home-loan interest). The substrate cannot enforce
   that coupling — the provider records `:regime` so a downstream
   check can; see the Stage-1 abstraction audit (note 104).

   The provider exposes its config keys directly — `:regime`,
   `:schedule`, `:commodity`, `:authority`, `:id` — for inspection."
  [{:keys [regime] :or {regime :new}}]
  (when-not (contains? regimes regime)
    (throw (ex-info "in-income-tax-provider: :regime must be :new or :old"
                    {:regime regime :allowed regimes})))
  (let [[brackets rebate-cfg surcharge-bands]
        (case regime
          :new [new-regime-brackets new-regime-87a new-regime-surcharge-bands]
          :old [old-regime-brackets old-regime-87a old-regime-surcharge-bands])
        inner
        (pit/personal-income-tax-provider
         {:id         (case regime
                        :new :in-income-tax-new
                        :old :in-income-tax-old)
          :schedule   (regime-schedule brackets rebate-cfg surcharge-bands)
          :authority  :in-income-tax-department
          :commodity  :INR
          :statute    (case regime
                        :new "Income-tax Act §115BAC (new regime)"
                        :old "Income-tax Act §§87A, 2 (old regime)")
          :surtax-fns [cess]})]
    ;; Carry the generic provider's config keys onto the wrapper so it
    ;; stays inspectable (`:schedule`, `:commodity`, `:id`, …) the way
    ;; the AT / FR / DE l10n providers are.
    (merge (map->InIncomeTaxProvider {:inner inner :regime regime})
           (into {} inner))))
