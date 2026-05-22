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

   ## §87A and the surcharge are base-aware adjustment items

   The §87A rebate is income-conditional — `min(bracket-tax, cap)` iff
   total income ≤ a regime threshold, with marginal relief just above
   it — and the surcharge is income-banded. Both depend on the *base*,
   not just the tax. Since research note 105's frontier 1 (ADR-099
   addendum 4) the adjustment layer is base-aware, so §87A is a
   `:credit` item and the surcharge a `:surtax` item, each with an
   `:amount` fn of `{:base :running}` — they surface as structured
   `:credits` / `:surtaxes` on the `TaxReturnFacts`, not as opaque
   `:formula` internals. The schedule is the plain bracket ladder; the
   4 % Health & Education cess is a third adjustment item.

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

(def ^:private cess-adjustment
  "The 4 % Health & Education cess as an adjustment-layer item
   (note 105) — a surtax of 4 % of the running tax (income tax − §87A
   rebate + surcharge, which the schedule formula has already resolved
   by the time the cess applies)."
  {:code   :health-education-cess
   :label  "Health & Education Cess (4 %)"
   :op     :surtax
   :amount (fn [ctx] (ts/surtax-on health-education-cess-rate
                                   (:running ctx)))})

;; ============================================================================
;; The regime adjustment layer — §87A + surcharge as base-aware items
;; ============================================================================

(defn- regime-adjustments
  "Build the adjustment layer for a regime (research note 105): the
   §87A rebate (a base-aware `:credit`), the income-banded surcharge
   (a base-aware `:surtax` with statutory marginal relief), and the
   4 % cess. Returned in canonical order — credit, then surtaxes. Each
   `:amount` fn reads `:base` (the taxable income) and `:running` (the
   tax so far) from the fold context."
  [brackets rebate-cfg surcharge-bands]
  (let [ladder      (ts/progressive brackets)
        ;; post-§87A income tax at a given income — the surcharge's
        ;; marginal-relief comparison recomputes it at a band floor.
        post-rebate (fn [^java.math.BigDecimal ti]
                      (let [bt (ts/apply-schedule ladder ti)]
                        (- bt (rebate-87a rebate-cfg ti bt))))]
    [{:code   :87a-rebate
      :label  "§87A rebate"
      :op     :credit
      :amount (fn [ctx] (rebate-87a rebate-cfg (:base ctx) (:running ctx)))}
     {:code   :surcharge
      :label  "Surcharge on income tax"
      :op     :surtax
      :amount (fn [ctx] (surcharge-with-marginal-relief
                         surcharge-bands (:base ctx) (:running ctx)
                         post-rebate))}
     cess-adjustment]))

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

   The selected regime fixes the rate ladder (a plain bracket
   schedule) and the adjustment layer — the §87A rebate (a base-aware
   `:credit`), the income-banded surcharge (a base-aware `:surtax`)
   and the 4 % Health & Education cess (a `:surtax`). The elected
   regime is recorded on every `TaxReturnFacts` component's `:regime`
   field.

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
          :schedule   (ts/progressive brackets)
          :authority  :in-income-tax-department
          :commodity  :INR
          :statute    (case regime
                        :new "Income-tax Act §115BAC (new regime)"
                        :old "Income-tax Act §§87A, 2 (old regime)")
          :adjustments (regime-adjustments brackets rebate-cfg
                                           surcharge-bands)})]
    ;; Carry the generic provider's config keys onto the wrapper so it
    ;; stays inspectable (`:schedule`, `:commodity`, `:id`, …) the way
    ;; the AT / FR / DE l10n providers are.
    (merge (map->InIncomeTaxProvider {:inner inner :regime regime})
           (into {} inner))))
