(ns kontor.l10n-jp.cgt-provider
  "JP capital-gains tax provider — `PeriodTaxProvider` (ADR-099) over
   the ADR-102 disposal substrate + ADR-101 statute-as-data. Research
   note 115.

   ## One provider, MULTI-COMPONENT TaxReturnFacts

   Unlike the JP CIT stack (one provider, three governments → three
   components), JP CGT has ONE authority (`:jp-nta` for national +
   `:jp-municipality` mirrored, but filed on Form 第三表 as a single
   separate-taxation return) and FIVE disposal-class compartments
   (`:jp-listed-securities` / `:jp-unlisted-equity` /
   `:jp-real-estate-short` / `:jp-real-estate-long` /
   `:jp-real-estate-long-residence`). Per note 115 §5 the architecture
   is **one provider, one component per active compartment**:

   - Same `:period`, same `:kind :capital-gains-tax` per component.
   - Loss-offsetting is INSIDE a component's `:base` (per-compartment
     net of gains and losses for the period), with consumer-supplied
     carry-in via `:inputs :capital-loss-carryforward {:jp-listed-
     securities :jp-unlisted-equity :jp-real-estate}` (the three JP
     buckets — real-estate short/long share one bucket inside the
     period and do NOT carry forward; listed carries forward 3 years;
     unlisted does not carry).
   - Each component carries its own progressive/flat schedule
     (`:schedule`) and its national+local effective rate baked in.
   - The 復興特別所得税 (2.1 % × the NATIONAL portion only) rides as
     an adjustment-layer `:surtax` line item, sourced from a
     `kontor.statute` provision the provider invokes per-component.

   ## Two callable shapes, one provider

   - **`:kind :individual`** — returns up to FIVE components, one per
     active regime.
   - **`:kind :corporation`** — corporate CGT folds into the CIT base
     (no separate JP regime; note 115 §1.3). The provider returns ONE
     component with `:cit-base-additions [net-gain]` and `:schedule
     nil`; the consumer composes with the JP CIT provider.

   ## The Jan-1 measurement rule — the JP-unique design feature

   §31 / §32 / §31-3 classify holding period by ELAPSED CALENDAR-YEAR
   BOUNDARIES to Jan 1 of the disposal year, NOT by days:

     elapsed-years = (year disposed-on) − (year acquired-on)
     long?         = elapsed-years > 5       (§31 long-term)
     long-residence? = elapsed-years > 10    (§31-3 preferential)

   So an asset bought 2020-12-15 and disposed 2026-02-01 has elapsed-
   years = 6 → LONG (>5). An asset bought 2021-01-02 and disposed
   2026-12-30 has elapsed-years = 5 → SHORT (not >5).

   The provider applies this rule at fact-time; the disposal companion's
   denormalized `:holding-period` is NOT consulted (the law-as-it-stood
   doctrine is preserved because the rule itself is statutorily stable
   — note 115 §3.1).

   ## DisposalSource

   The provider depends on the kernel `DisposalSource` protocol; a
   consumer without disposals wires `kontor.disposal-source/empty-source`
   and gets zero components."
  (:require [kontor.disposal-source :as ds]
            [kontor.l10n-jp.cgt-statute :as cgt-statute]
            [kontor.money :as money]
            [kontor.period-tax-provider :as ptp]
            [kontor.statute :as statute]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; Constants — the closed regime + bucket keywords
;; ============================================================================

(def regimes
  "The closed set of JP CGT regimes a disposal classifies into."
  #{:jp-listed-securities
    :jp-unlisted-equity
    :jp-real-estate-short
    :jp-real-estate-long
    :jp-real-estate-long-residence})

(def carry-buckets
  "The three loss-carryforward buckets the `:inputs
   :capital-loss-carryforward` map keys against. Real-estate short
   and long SHARE one bucket inside the period; only listed carries
   forward across periods (3 years per §37-12-2)."
  #{:jp-listed-securities
    :jp-unlisted-equity
    :jp-real-estate})

;; ============================================================================
;; Jan-1 holding-period classifier — THE JP-unique rule
;; ============================================================================

(defn- year-of
  "Extract the Gregorian calendar year from a java.util.Date in UTC."
  ^long [^java.util.Date d]
  (let [cal (java.util.Calendar/getInstance (java.util.TimeZone/getTimeZone "UTC"))]
    (.setTime cal d)
    (long (.get cal java.util.Calendar/YEAR))))

(defn jan-1-elapsed-years
  "JP holding-period measurement per 租税特別措置法 §31 / §32 / §31-3:
   the elapsed CALENDAR-YEAR boundary count from `acquired-on` to
   Jan 1 of the year that contains `disposed-on`.

   Implementation: `(year disposed-on) − (year acquired-on)`. This is
   the count of Jan-1 boundaries crossed BEFORE the disposal year:
   for an asset bought 2020-12-15 and sold 2026-02-01 the chain is
   2020 → 2021 → 2022 → 2023 → 2024 → 2025 → 2026 (Jan 1 of disposal
   year) = 6 boundaries crossed = 6 elapsed years → LONG (>5).

   Returns 0 when either date is nil."
  ^long [^java.util.Date acquired-on ^java.util.Date disposed-on]
  (if (and acquired-on disposed-on)
    (- (year-of disposed-on) (year-of acquired-on))
    0))

;; ============================================================================
;; Disposal classification — disposal → regime
;; ============================================================================

(defn- as-of-from-ctx
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- realized-gain
  "Gain (positive) or loss (negative) on one disposal, in the proceeds
   commodity: `proceeds − basis − rollover-amount` (the §36-2 rollover
   slice is excluded — already deferred into the replacement)."
  ^java.math.BigDecimal [disposal]
  (let [p (or (:disposal/proceeds-amount disposal) 0M)
        b (or (:disposal/basis-amount disposal) 0M)
        r (or (:disposal/rollover-amount disposal) 0M)]
    (- p b r)))

(defn- elective-regimes [disposal]
  (set (:disposal/elective-regime disposal)))

(defn- exemptions [disposal]
  (set (:disposal/exemption-claimed disposal)))

(defn- classify
  "Classify one disposal into a JP CGT regime. The asset-class drives
   the bucket; for real estate, the Jan-1 measurement rule + the
   `:residence?` flag + the §31-3 election decide between short, long,
   and long-residence.

   Returns `{:regime <kw> :disposal <map>}`. An unrecognized
   asset-class is left out (the provider drops it — extending the
   regime set is an ADR change)."
  [disposal long-cutoff long-residence-cutoff]
  (let [ac          (:disposal/asset-class disposal)
        residence?  (boolean (:disposal/residence? disposal))
        elects      (elective-regimes disposal)
        acq         (:disposal/acquired-on disposal)
        disp        (:disposal/disposed-on disposal)
        elapsed     (jan-1-elapsed-years acq disp)
        long?       (> elapsed long-cutoff)
        long-res?   (> elapsed long-residence-cutoff)
        regime
        (case ac
          :jp-listed-securities :jp-listed-securities
          :jp-unlisted-equity   :jp-unlisted-equity
          :jp-real-estate-residence
          (cond
            (and long-res? residence? (contains? elects :jp-§31-3))
            :jp-real-estate-long-residence
            long? :jp-real-estate-long
            :else :jp-real-estate-short)
          :jp-real-estate
          (cond
            (and long-res? residence? (contains? elects :jp-§31-3))
            :jp-real-estate-long-residence
            long? :jp-real-estate-long
            :else :jp-real-estate-short)
          nil)]
    (when regime
      {:regime regime :disposal disposal})))

;; ============================================================================
;; §35 ¥30M deduction + §36-2 rollover handling
;; ============================================================================

(defn- §35-deduction-claimed?
  "True when any disposal in the classified group claims §35. The
   ¥30M deduction is per-residence per-period (cannot be used in
   consecutive years — consumer's job to gate)."
  [disposals]
  (boolean (some #(contains? (exemptions %) :jp-§35-residence) disposals)))

(defn- apply-§35-deduction
  "Subtract the §35 ¥30M deduction from the per-regime gross when the
   regime contains an eligible residence disposal. Floored at 0."
  ^java.math.BigDecimal [^java.math.BigDecimal gross-gain
                         ^java.math.BigDecimal deduction-amount
                         claimed?]
  (if claimed?
    (max 0M (- gross-gain deduction-amount))
    gross-gain))

;; ============================================================================
;; Loss netting + carry-in
;; ============================================================================

(defn- net-against-carry
  "Net `regime-gain` against the supplied carry-in (a positive carry =
   a loss available to offset gains). Returns the net amount (0 if
   carry consumes the gain)."
  ^java.math.BigDecimal [^java.math.BigDecimal regime-gain
                         ^java.math.BigDecimal carry-in]
  (max 0M (- regime-gain (or carry-in 0M))))

;; ============================================================================
;; Compute-fn — 復興特別所得税 (the only ADR-101 provision)
;; ============================================================================

(defn- jp-cgt-reconstruction-surtax
  "復興特別所得税 — 2.1 % × the NATIONAL CGT amount. Late-bound to
   the running tax via apply-adjustments. The provider's national-tax
   pass runs over the national gross only (NOT national+local), so
   `:running` at the time the surtax fires is the national gross —
   exactly the statutory base."
  [ctx]
  (let [db    (:db ctx)
        as-of (as-of-from-ctx ctx)
        rate  (statute/parameter-value-at db "JP.CGT.reconstruction-surtax-rate" as-of)]
    (fn [ctx-w-running] (* (or (:running ctx-w-running) 0M) rate))))

(defn register!
  "Register JP CGT compute-fns with `kontor.statute`. Called at
   namespace load; idempotent."
  []
  (statute/register-compute-fn! :jp-cgt-reconstruction-surtax
                                jp-cgt-reconstruction-surtax))

(register!)

;; ============================================================================
;; National + local schedules per regime
;; ============================================================================

(defn- flat-rate [db code as-of]
  (statute/parameter-value-at db code as-of))

(defn- listed-national-schedule    [db as-of] (ts/flat (flat-rate db "JP.CGT.listed.national-rate" as-of)))
(defn- listed-local-schedule       [db as-of] (ts/flat (flat-rate db "JP.CGT.listed.local-rate" as-of)))
(defn- unlisted-national-schedule  [db as-of] (ts/flat (flat-rate db "JP.CGT.unlisted.national-rate" as-of)))
(defn- unlisted-local-schedule     [db as-of] (ts/flat (flat-rate db "JP.CGT.unlisted.local-rate" as-of)))
(defn- re-short-national-schedule  [db as-of] (ts/flat (flat-rate db "JP.CGT.realestate-short.national-rate" as-of)))
(defn- re-short-local-schedule     [db as-of] (ts/flat (flat-rate db "JP.CGT.realestate-short.local-rate" as-of)))
(defn- re-long-national-schedule   [db as-of] (ts/flat (flat-rate db "JP.CGT.realestate-long.national-rate" as-of)))
(defn- re-long-local-schedule      [db as-of] (ts/flat (flat-rate db "JP.CGT.realestate-long.local-rate" as-of)))

(defn- re-long-residence-national-schedule
  "§31-3 national side — 10 % on the first ¥60M, 15 % on the slice
   above."
  [db as-of]
  (ts/progressive
   [{:rate  (flat-rate db "JP.CGT.realestate-long-residence.national-low-rate"  as-of)
     :upper (flat-rate db "JP.CGT.realestate-long-residence.kink"               as-of)}
    {:rate  (flat-rate db "JP.CGT.realestate-long-residence.national-high-rate" as-of)
     :upper nil}]))

(defn- re-long-residence-local-schedule
  "§31-3 local side — 4 % on the first ¥60M, 5 % above."
  [db as-of]
  (ts/progressive
   [{:rate  (flat-rate db "JP.CGT.realestate-long-residence.local-low-rate"  as-of)
     :upper (flat-rate db "JP.CGT.realestate-long-residence.kink"            as-of)}
    {:rate  (flat-rate db "JP.CGT.realestate-long-residence.local-high-rate" as-of)
     :upper nil}]))

(defn- schedules-for
  "Return `{:national :local}` schedule maps for a regime."
  [regime db as-of]
  (case regime
    :jp-listed-securities          {:national (listed-national-schedule db as-of)
                                    :local    (listed-local-schedule    db as-of)}
    :jp-unlisted-equity            {:national (unlisted-national-schedule db as-of)
                                    :local    (unlisted-local-schedule    db as-of)}
    :jp-real-estate-short          {:national (re-short-national-schedule db as-of)
                                    :local    (re-short-local-schedule    db as-of)}
    :jp-real-estate-long           {:national (re-long-national-schedule db as-of)
                                    :local    (re-long-local-schedule    db as-of)}
    :jp-real-estate-long-residence {:national (re-long-residence-national-schedule db as-of)
                                    :local    (re-long-residence-local-schedule    db as-of)}))

(defn- regime-statute-label [regime]
  (case regime
    :jp-listed-securities          "租税特別措置法 §37-10 (上場株式等)"
    :jp-unlisted-equity            "租税特別措置法 §37-10 (一般株式等)"
    :jp-real-estate-short          "租税特別措置法 §32 (短期譲渡)"
    :jp-real-estate-long           "租税特別措置法 §31 (長期譲渡)"
    :jp-real-estate-long-residence "租税特別措置法 §31-3 (居住用財産軽減税率)"))

;; ============================================================================
;; Component build — per-regime
;; ============================================================================

(defn- carry-key-for-regime
  "Map a regime to its loss-carry bucket."
  [regime]
  (case regime
    :jp-listed-securities          :jp-listed-securities
    :jp-unlisted-equity            :jp-unlisted-equity
    (:jp-real-estate-short
     :jp-real-estate-long
     :jp-real-estate-long-residence) :jp-real-estate))

(defn- regime-component
  "Build the TaxReturnFacts component for one regime. The component
   carries:
   - `:base`             — net taxable gain after §35 deduction + carry-in
   - `:schedule`         — the national progressive/flat schedule (audit)
   - `:gross-liability`  — national + local + 復興 combined
   - `:liability`        — same as gross (no further credits in JP CGT)
   - `:line-items`       — the auditable breakdown (national / local /
                           reconstruction surtax / §35 deduction line)
   - `:jurisdiction-specific-codes :regime` — the regime keyword"
  [{:keys [db ctx as-of commodity §35-amount]} regime regime-disposals carry-in]
  (let [scoped-ctx     (assoc ctx :db db :as-of as-of :regime regime)
        gross-gain     (->> regime-disposals (map realized-gain) (reduce + 0M))
        §35?           (and (= regime :jp-real-estate-long-residence)
                            (§35-deduction-claimed? regime-disposals))
        after-§35      (apply-§35-deduction gross-gain §35-amount §35?)
        net-base       (net-against-carry after-§35 carry-in)
        {:keys [national local]} (schedules-for regime db as-of)
        national-gross (ts/apply-schedule national net-base scoped-ctx)
        local-gross    (ts/apply-schedule local    net-base scoped-ctx)
        ;; Reconstruction surtax — fold the :surtax provisions that match
        ;; :concept :surtax + :jurisdiction :jp + :as-of as-of, with
        ;; :pass :national in ctx (so the JP-FUKKO provision fires).
        nat-surtax-ctx (assoc scoped-ctx :pass :national :regime regime)
        {:keys [tax-items provisions]}
        (statute/apply-provisions db {:concept :surtax
                                      :jurisdiction :jp
                                      :as-of as-of} nat-surtax-ctx)
        {:keys [liability resolved]}
        (ts/apply-adjustments national-gross tax-items nat-surtax-ctx)
        national-with-surtax liability
        total-liability      (+ national-with-surtax local-gross)
        line-items
        (cond-> [{:line :gross-gain
                  :label "Gross gain (proceeds − basis − rollover)"
                  :value (money/money gross-gain commodity)}]
          §35?
          (conj {:line :§35-deduction
                 :label "§35 principal-residence deduction (¥30,000,000)"
                 :value (money/money (- §35-amount) commodity)})
          (pos? (or carry-in 0M))
          (conj {:line :carry-in
                 :label "Capital-loss carry-in"
                 :value (money/money (- carry-in) commodity)})
          true
          (into [{:line :taxable-base
                  :label "Taxable base (after deduction / carry)"
                  :value (money/money net-base commodity)}
                 {:line :national-tax
                  :label "National income tax (所得税)"
                  :value (money/money national-gross commodity)}
                 {:line :reconstruction-surtax
                  :label "復興特別所得税 (Special Reconstruction Income Tax)"
                  :value (money/money (- national-with-surtax national-gross) commodity)}
                 {:line :local-tax
                  :label "Inhabitants tax (住民税)"
                  :value (money/money local-gross commodity)}]))]
    {:kind            :capital-gains-tax
     :authority       :jp-nta
     :base            (money/money net-base commodity)
     :schedule        national
     :gross-liability (money/money total-liability commodity)
     :liability       (money/money total-liability commodity)
     :prepaid         (money/zero commodity)
     :regime          regime
     :line-items      line-items
     :surtaxes        (mapv #(select-keys % [:code :label :amount :provenance])
                            resolved)
     :provenance      {:provider-id :jp-cgt
                       :statute     (regime-statute-label regime)
                       :provisions-applied (mapv :provision/code provisions)
                       :as-of       as-of}
     :jurisdiction-specific-codes {:regime regime
                                   :compartment regime
                                   :pre-carry-base (apply-§35-deduction
                                                    gross-gain §35-amount §35?)
                                   :carry-in (or carry-in 0M)
                                   :residual-loss (max 0M (- (or carry-in 0M)
                                                             (apply-§35-deduction
                                                              gross-gain §35-amount §35?)))}}))

;; ============================================================================
;; Corporate component — gains fold into CIT
;; ============================================================================

(defn- corp-net-component
  "Corporate component — JP corporate CGT folds into corporate-income-
   tax (note 115 §1.3, no separate regime). The provider returns ONE
   component with `:cit-base-additions [net-gain]` and `:schedule nil`;
   the consumer composes with the JP CIT provider on the same period."
  [{:keys [commodity]} ^java.math.BigDecimal net-gain]
  {:kind            :capital-gains-tax
   :authority       :jp-nta
   :base            (money/money net-gain commodity)
   :schedule        nil
   :gross-liability (money/zero commodity)
   :liability       (money/zero commodity)
   :prepaid         (money/zero commodity)
   :line-items      [{:line :corp-net-gain
                      :label "Net capital gain (folds into CIT base)"
                      :value (money/money net-gain commodity)}]
   :jurisdiction-specific-codes {:cit-base-additions [net-gain]
                                 :regime :jp-corporate}})

;; ============================================================================
;; The provider record
;; ============================================================================

(defrecord JpCapitalGainsTaxProvider
           [id source authority commodity statute kind]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs] :as ctx}]
    (let [db          (or (:db ctx)
                          (throw (ex-info ":db required in ctx for JP CGT provider"
                                          {:ctx-keys (keys ctx)})))
          as-of       (as-of-from-ctx ctx)
          long-cutoff (long (or (statute/parameter-value-at
                                 db "JP.CGT.realestate.long-cutoff-years" as-of)
                                5))
          long-res-cutoff (long (or (statute/parameter-value-at
                                     db "JP.CGT.realestate.long-residence-cutoff-years"
                                     as-of)
                                    10))
          §35-amount  (or (statute/parameter-value-at
                           db "JP.CGT.§35.residence-deduction" as-of)
                          30000000M)
          disposals   (ds/disposals-in source entity period)
          classified  (->> disposals
                           (map #(classify % long-cutoff long-res-cutoff))
                           (remove nil?))
          carry-in    (or (:capital-loss-carryforward inputs) {})
          opts        {:db db :ctx ctx :as-of as-of :commodity commodity
                       :§35-amount §35-amount}]
      (case kind
        :individual
        (let [by-regime (group-by :regime classified)
              ;; Build a regime-specific component for every regime
              ;; with non-zero gross gain after netting.
              components
              (vec
               (keep
                (fn [regime]
                  (let [items     (get by-regime regime [])
                        disposals (map :disposal items)
                        gross     (reduce + 0M (map realized-gain disposals))]
                    (when (or (pos? gross)
                              (some (fn [d] (contains? (exemptions d)
                                                       :jp-§35-residence))
                                    disposals))
                      (let [carry (get carry-in (carry-key-for-regime regime) 0M)]
                        (regime-component opts regime disposals carry)))))
                [:jp-listed-securities
                 :jp-unlisted-equity
                 :jp-real-estate-long-residence
                 :jp-real-estate-long
                 :jp-real-estate-short]))]
          (ptp/tax-return-facts
           {:entity               entity
            :period               period
            :jurisdiction         {:country :jp :authority authority}
            :functional-commodity commodity
            :components           components}))

        :corporation
        (let [net-gain (reduce + 0M
                               (map (fn [{:keys [disposal]}] (realized-gain disposal))
                                    classified))
              corp-cmp (when (pos? net-gain)
                         (corp-net-component opts net-gain))]
          (ptp/tax-return-facts
           {:entity               entity
            :period               period
            :jurisdiction         {:country :jp :authority authority}
            :functional-commodity commodity
            :components           (vec (remove nil? [corp-cmp]))}))

        (throw (ex-info "JP CGT provider :kind must be :individual or :corporation"
                        {:kind kind}))))))

;; ============================================================================
;; Constructors
;; ============================================================================

(defn jp-individual-cgt-provider
  "Build a JP individual CGT provider. Required: `:source` — a
   `DisposalSource` (kernel protocol). Per-disposal regimes are
   determined provider-internally from `:disposal/asset-class` +
   `:disposal/residence?` + `:disposal/elective-regime` and the Jan-1
   measurement rule."
  [{:keys [source id]}]
  (when-not source (throw (ex-info ":source DisposalSource required" {})))
  (->JpCapitalGainsTaxProvider (or id :jp-cgt-individual) source :jp-nta :JPY
                               "租税特別措置法 §31 / §32 / §31-3 / §35 / §37-10"
                               :individual))

(defn jp-corporate-cgt-provider
  "Build a JP corporate CGT provider. Required: `:source`. Returns a
   single component whose `:cit-base-additions` slot the consumer
   threads into the JP CIT provider's `:inputs` (note 115 §1.3 — no
   separate JP corporate CGT regime)."
  [{:keys [source id]}]
  (when-not source (throw (ex-info ":source DisposalSource required" {})))
  (->JpCapitalGainsTaxProvider (or id :jp-cgt-corporate) source :jp-nta :JPY
                               "法人税法 §22 (corporate gains fold into ordinary income)"
                               :corporation))

(defn install-statute!
  "Install the JP CGT statute (parameters + provisions) into `conn`."
  [conn]
  (cgt-statute/install! conn))
