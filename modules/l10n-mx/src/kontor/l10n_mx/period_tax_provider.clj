(ns kontor.l10n-mx.period-tax-provider
  "Mexican period taxes as kontor `PeriodTaxProvider`s (ADR-099):

   - ISN (Impuesto Sobre Nóminas) — the state employer payroll tax;
   - ISR personas morales — corporate income tax (flat 30 %);
   - ISR personas físicas — personal income tax: the annual
     progressive `tarifa` of Art. 152 LISR, with the `subsidio para
     el empleo` modelled as a low-income credit.

   ## DEPRECATION (v0.next)

   The `mx-isr-corporate-provider` (ISR PM) and
   `mx-isr-personal-provider` (ISR PF) factories below are now
   forwarders to the ADR-101 statute-as-data implementations in
   `kontor.l10n-mx.cit-provider` / `kontor.l10n-mx.pit-provider`.
   They preserve the v0.x
   consumer API; new consumers should call the new factories
   directly. Slated for removal in v0.next.

   The `mx-isn-provider` is **unchanged** — ISN is a state
   standalone employer payroll tax ( EXCLUDES payroll
   levies2). It stays on
   `StandalonePayrollTaxProvider`.

   ## Legacy helpers — kept for v0.x consumers

   `isr-tarifa-2025`, `tarifa->marginal-brackets`,
   `isr-personal-brackets`, `isr-personal-cuota-fija-schedule`, and
   `subsidio-empleo-credit` remain unchanged in this namespace —
   they're consumer-facing helpers that the deprecation shim's
   forwarder still references via `mx-isr-personal-provider`. New
   consumers should prefer the substrate-driven path:
   `kontor.l10n-mx.pit-provider/subsidio-empleo-input` reads the
   UMA-month × factor from `:parameter` data instead of hard-coding
   the 2025 figure.

   ## Form-choice abstraction note (preserved from v0.x)

   The annual ISR for individuals is published by SAT in `límite
   inferior / cuota fija / % sobre excedente` form. kontor's
   substrate-native form is `[{:rate :upper} …]` (marginal-rate);
   the two forms are mathematically equivalent. SAT's *published*
   `cuota fija` figures are rounded to 2 decimals and therefore
   differ from the exact integral of the marginal rates by up to
   ~3 cents at the top bands. The new ADR-101-based PIT provider
   uses the marginal-rate form exclusively; the `:formula`
   `isr-personal-cuota-fija-schedule` below remains for legacy
   consumers needing bit-exact SAT-form arithmetic."
  (:require [kontor.l10n-mx.cit-provider :as cit]
            [kontor.l10n-mx.pit-provider :as pit]
            [kontor.tax.standalone-payroll-tax :as spt]
            [kontor.tax.tax-schedule :as ts]))

(def isn-rates
  "ISN rate by state — a representative subset (rates change; verify).
   Supply an explicit `:rate` for states not listed; `:default` 3 %."
  {:cdmx          0.03M
   :jalisco       0.02M
   :nuevo-leon    0.03M
   :estado-mexico 0.03M
   :default       0.03M})

(defn mx-isn-provider
  "An MX ISN `PeriodTaxProvider` for one state. Config:
     :state      — a state keyword (looked up in `isn-rates`)
     :rate       — optional explicit rate override
     :wage-codes — chart account codes for payroll wage expense"
  [{:keys [state rate wage-codes]}]
  (spt/standalone-payroll-tax-provider
   {:id         :mx-isn
    :schedule   (ts/flat (or rate
                             (get isn-rates state)
                             (:default isn-rates)))
    :wage-codes wage-codes
    :authority  (keyword "mx-state" (name (or state :unknown)))
    :commodity  :MXN
    :statute    "Impuesto Sobre Nóminas (ley estatal)"
    :base-label "Nómina gravable"}))

;; ============================================================================
;; ISR personas morales — corporate income tax (DEPRECATED record-shape entry)
;; ============================================================================

(def ^{:doc "DEPRECATED — pre-ADR-101 hard-coded rate. Read
  `MX.CGT.art-9.pm-rate` from the substrate via
  `kontor.tax.statute/parameter-value-at` for the right `as-of`. Kept
  for documentation only; not consulted by the new provider."
       :deprecated "v0.next — use parameter MX.CGT.art-9.pm-rate"}
  isr-corporate-rate
  0.30M)

(defn ^:deprecated mx-isr-corporate-provider
  "DEPRECATED — forwards to `kontor.l10n-mx.cit-provider/mx-cit-provider`.

   Recommended replacement: `(kontor.l10n-mx.cit-provider/mx-cit-provider opts)`.
   The new provider reads LISR art. 9 30 % rate from `:parameter`
   data (`MX.CGT.art-9.pm-rate`, shipped by
   `kontor.l10n-mx.cgt-statute`); folds PTU deduction + CGT corporate
   base-additions + optional non-deductibles via `:provision` data.

   Per Q5.2 this thin forwarder ships for the v0.x
   public API contract; v0.x consumers continue to call the old name.

   Config: ignored — the new provider reads from `:parameter` data."
  [_opts]
  (cit/mx-cit-provider {}))

;; ============================================================================
;; ISR personas físicas — personal income tax (legacy helpers + DEPRECATED entry)
;; ============================================================================

(def isr-tarifa-2025
  "ISR personas físicas — annual `tarifa`, Art. 152 LISR, in the
   official SAT `límite inferior / cuota fija / % sobre excedente`
   form. This is the table published in the Anexo 8 of the Resolución
   Miscelánea Fiscal; it is adjusted for inflation when accumulated
   INPC change reaches 10 %. VERIFY AGAINST CURRENT LAW before a
   filing — límites and cuotas drift with the inflation adjustment.

   Each row: `{:lower :cuota :rate}` — `:lower` the límite inferior,
   `:cuota` the cuota fija, `:rate` the % sobre el excedente del
   límite inferior. Ascending; the last row is the open top band.

   Legacy — the new ADR-101 PIT provider reads marginal-rate
   `:parameter-bracket` rows from `kontor.l10n-mx.pit-statute`
   instead."
  [{:lower 0.01M       :cuota 0M           :rate 0.0192M}
   {:lower 8952.50M    :cuota 171.88M      :rate 0.0640M}
   {:lower 75984.56M   :cuota 4461.94M     :rate 0.1088M}
   {:lower 133536.08M  :cuota 10723.55M    :rate 0.1600M}
   {:lower 155229.81M  :cuota 14194.54M    :rate 0.1792M}
   {:lower 185852.58M  :cuota 19682.13M    :rate 0.2136M}
   {:lower 374837.89M  :cuota 60049.40M    :rate 0.2352M}
   {:lower 590796.00M  :cuota 110842.74M   :rate 0.3000M}
   {:lower 1127926.85M :cuota 271981.99M   :rate 0.3200M}
   {:lower 1503902.47M :cuota 392294.17M   :rate 0.3400M}
   {:lower 4511707.38M :cuota 1414947.85M  :rate 0.3500M}])

(defn- tarifa->marginal-brackets
  "Convert a SAT `tarifa` (límite-inferior / cuota-fija / %-excedente
   form) into kontor's marginal-rate `progressive` brackets
   `[{:rate :upper} …]`. Each band keeps its `%-excedente` as the
   marginal `:rate`; the band's `:upper` is the next band's límite
   inferior minus one cent. The top band's `:upper` is nil.

   Legacy helper — the new ADR-101 PIT provider reads bracket rows
   from `:parameter-bracket` data directly."
  [tarifa]
  (let [lowers (mapv :lower tarifa)
        uppers (conj (mapv #(- % 0.01M) (subvec lowers 1)) nil)]
    (mapv (fn [row upper] {:rate (:rate row) :upper upper})
          tarifa uppers)))

(def isr-personal-brackets
  "ISR personas físicas as kontor marginal-rate brackets — the
   exact-integral form of `isr-tarifa-2025`. Legacy helper."
  (tarifa->marginal-brackets isr-tarifa-2025))

(defn- tarifa-cuota-fija-tax
  "Compute ISR the SAT way: locate the band whose límite inferior the
   income falls into, then `cuota-fija + rate × (income − lower)`.
   Uses the published `cuota fija` figures verbatim — see the
   abstraction note in the namespace docstring."
  ^java.math.BigDecimal [^java.math.BigDecimal income _ctx]
  (let [row (or (last (filter #(>= income (:lower %)) isr-tarifa-2025))
                (first isr-tarifa-2025))]
    (+ (:cuota row) (* (:rate row) (- income (:lower row))))))

(def isr-personal-cuota-fija-schedule
  "An alternative ISR personas físicas schedule that reproduces SAT's
   `tarifa` arithmetic verbatim (published `cuota fija` figures). Pick
   this over the default `progressive` schedule when bit-exact
   agreement with a SAT worksheet is required — see the abstraction
   note for the sub-cent divergence between the two."
  {:kontor.schedule/type :formula :fn tarifa-cuota-fija-tax})

;; --- Subsidio para el empleo --------------------------------------------

(def subsidio-empleo-monthly-2025
  "Subsidio para el empleo — monthly amount, 2025 hard-coded. Legacy
   constant — the new ADR-101 PIT provider's
   `subsidio-empleo-input` reads the UMA-month × factor from
   `:parameter` data for the right `as-of`."
  475.00M)

(def subsidio-empleo-income-ceiling
  "Monthly taxable-income ceiling above which the subsidio para el
   empleo is no longer granted (post-2024 reform). VERIFY annually."
  10171.00M)

(defn subsidio-empleo-credit
  "Build a `subsidio para el empleo` credit item for the legacy
   provider's `:inputs :credits`. `months` is the count of months in
   the period in which the worker's monthly taxable income stayed at
   or below `subsidio-empleo-income-ceiling` (default 12). The credit
   amount is `months × monthly subsidio`.

   Legacy — new consumers should call
   `kontor.l10n-mx.pit-provider/subsidio-empleo-input` instead, which
   reads the UMA-month × factor from `:parameter` data."
  ([] (subsidio-empleo-credit 12))
  ([months]
   {:code   :subsidio-empleo
    :label  "Subsidio para el empleo"
    :amount (* (bigdec months) subsidio-empleo-monthly-2025)}))

(defn ^:deprecated mx-isr-personal-provider
  "DEPRECATED — forwards to `kontor.l10n-mx.pit-provider/mx-pit-provider`.

   Recommended replacement: `(kontor.l10n-mx.pit-provider/mx-pit-provider opts)`.
   The new provider reads LISR art. 152 progressive brackets from
   year-keyed `:parameter-bracket` rows (3 sets: pre-2024 stable /
   2024-reform / 2025 — Q5.4 bitemporal history) and folds subsidio
   para el empleo / ISR retenido / CGT / investment-income lanes via
   `:provision` data. The subsidio rides `:op :credit :refundable?
   true` per Q5.5.

   Per Q5.2 this thin forwarder ships for the v0.x
   public API contract; v0.x consumers continue to call the old name.

   Config: ignored — the new provider reads from `:parameter` data."
  [_opts]
  (pit/mx-pit-provider {}))
