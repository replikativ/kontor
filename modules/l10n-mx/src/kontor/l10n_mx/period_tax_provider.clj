(ns kontor.l10n-mx.period-tax-provider
  "Mexican period taxes as kontor `PeriodTaxProvider`s (ADR-099;
   research notes 103 / 104):

   - ISN (Impuesto Sobre Nóminas) — the state employer payroll tax;
   - ISR personas morales — corporate income tax (flat 30 %);
   - ISR personas físicas — personal income tax (Stage 1, note 104):
     the annual progressive `tarifa` of Art. 152 LISR, with the
     `subsidio para el empleo` modelled as a low-income credit.

   ISN is a flat state levy on the monthly payroll; rates run ~1–3 %
   and are set per state. It is the textbook standalone payroll tax:
   `flat-rate × Σ(wage-expense postings)`.

   `isn-rates` carries a representative subset of the 32 states; for
   any other state pass an explicit `:rate`. Statutory rates change —
   verify against current state law."
  (:require [kontor.corporate-income-tax :as cit]
            [kontor.personal-income-tax :as pit]
            [kontor.standalone-payroll-tax :as spt]
            [kontor.tax-schedule :as ts]))

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
;; ISR personas morales — corporate income tax
;; ============================================================================

(def isr-corporate-rate
  "ISR personas morales — flat 30 % (Ley del ISR §9)."
  0.30M)

(defn mx-isr-corporate-provider
  "MX corporate income tax — ISR personas morales — provider. A flat
   30 % on taxable profit (utilidad fiscal). Config:
     :rate — optional override (default 30 %)"
  [{:keys [rate]}]
  (cit/corporate-income-tax-provider
   {:id        :mx-isr-corporate
    :rate      (or rate isr-corporate-rate)
    :authority :mx-sat
    :commodity :MXN
    :statute   "Ley del Impuesto sobre la Renta"}))

;; ============================================================================
;; ISR personas físicas — personal income tax (annual)
;; ============================================================================
;;
;; The annual ISR for individuals (declaración anual, Art. 152 LISR) is
;; an 11-band progressive tax. SAT publishes the schedule as a `tarifa`
;; in `límite inferior / cuota fija / % sobre excedente` form:
;;
;;   tax = cuota-fija(band) + rate(band) × (income − límite-inferior(band))
;;
;; kontor's `progressive` schedule is the MARGINAL-RATE form
;; `[{:rate :upper} …]` — each band's `:rate` is the marginal rate on
;; the slice inside that band, `:upper` the band ceiling. The two forms
;; are mathematically equivalent: a band's `cuota fija` is the integral
;; of the marginal rates over all LOWER bands. The conversion below is
;; mechanical — see `isr-tarifa-2025` for the source table and
;; `isr-personal-brackets` for the converted marginal form.
;;
;; ABSTRACTION NOTE (raised as a finding, note 104 §4 mandate 2): the
;; conversion is exact in the schedule's algebra, but SAT's *published*
;; `cuota fija` figures are each rounded to two decimals and therefore
;; differ from the exact integral of the marginal rates by up to ~3
;; cents at the top bands. kontor's `progressive` schedule computes the
;; exact integral. For audit-grade reconciliation against a SAT-form
;; computation, `isr-personal-cuota-fija-schedule` is a `:formula`
;; schedule that uses the published `cuota fija` table verbatim — pick
;; it when bit-exact agreement with SAT worksheets is required.

(def isr-tarifa-2025
  "ISR personas físicas — annual `tarifa`, Art. 152 LISR, in the
   official SAT `límite inferior / cuota fija / % sobre excedente`
   form. This is the table published in the Anexo 8 of the Resolución
   Miscelánea Fiscal; it is adjusted for inflation when accumulated
   INPC change reaches 10 %. VERIFY AGAINST CURRENT LAW before a
   filing — límites and cuotas drift with the inflation adjustment.

   Each row: `{:lower :cuota :rate}` — `:lower` the límite inferior,
   `:cuota` the cuota fija, `:rate` the % sobre el excedente del
   límite inferior. Ascending; the last row is the open top band."
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
   inferior minus one cent (límites are inclusive on the next band's
   lower edge). The top band's `:upper` is nil."
  [tarifa]
  (let [lowers (mapv :lower tarifa)
        uppers (conj (mapv #(- % 0.01M) (subvec lowers 1)) nil)]
    (mapv (fn [row upper] {:rate (:rate row) :upper upper})
          tarifa uppers)))

(def isr-personal-brackets
  "ISR personas físicas as kontor marginal-rate brackets — the
   exact-integral form of `isr-tarifa-2025`. Fed to `ts/progressive`."
  (tarifa->marginal-brackets isr-tarifa-2025))

(defn- tarifa-cuota-fija-tax
  "Compute ISR the SAT way: locate the band whose límite inferior the
   income falls into, then `cuota-fija + rate × (income − lower)`.
   Uses the published `cuota fija` figures verbatim — see the
   abstraction note above. `_ctx` is ignored (no filing-unit split)."
  ^java.math.BigDecimal [^java.math.BigDecimal income _ctx]
  (let [row (or (last (filter #(>= income (:lower %)) isr-tarifa-2025))
                (first isr-tarifa-2025))]
    (+ (:cuota row) (* (:rate row) (- income (:lower row))))))

(def isr-personal-cuota-fija-schedule
  "An alternative ISR personas físicas schedule that reproduces SAT's
   `tarifa` arithmetic verbatim (published `cuota fija` figures). Pick
   this over the default `progressive` schedule when bit-exact
   agreement with a SAT worksheet is required — see the abstraction
   note above for the sub-cent divergence between the two."
  {:schedule/type :formula :fn tarifa-cuota-fija-tax})

;; --- Subsidio para el empleo --------------------------------------------
;;
;; The `subsidio para el empleo` is a refundable low-income employment
;; subsidy: it reduces (and at low incomes fully offsets) the ISR of
;; wage-earners. Since the 2024 reform it is computed as a flat
;; percentage of one UMA-month, granted while monthly taxable income
;; stays at or below a ceiling. kontor models it as a CREDIT on the
;; `:inputs` of the provider — it nets against the gross tax exactly
;; the way the abstraction's `− Σ credits` step expects.
;;
;; The subsidio is a MONTHLY mechanism in payroll; on the annual
;; return it is the sum of the months in which it was granted. kontor
;; takes it as an already-summed annual credit amount — the l10n
;; payroll module (or the consumer) computes the monthly grant; this
;; provider only needs the annual total to net it against annual ISR.

(def subsidio-empleo-monthly-2025
  "Subsidio para el empleo — monthly amount, post-2024 reform. It is
   13.8 % of one UMA elevated to a month, granted while monthly
   taxable income ≤ `subsidio-empleo-income-ceiling`. VERIFY: the UMA
   and the percentage are revised annually (the UMA each February)."
  475.00M)

(def subsidio-empleo-income-ceiling
  "Monthly taxable-income ceiling above which the subsidio para el
   empleo is no longer granted (post-2024 reform). VERIFY annually."
  10171.00M)

(defn subsidio-empleo-credit
  "Build a `subsidio para el empleo` credit item for the provider's
   `:inputs :credits`. `months` is the count of months in the period
   in which the worker's monthly taxable income stayed at or below
   `subsidio-empleo-income-ceiling` (default 12 — a full year of
   eligibility). The credit amount is `months × monthly subsidio`."
  ([] (subsidio-empleo-credit 12))
  ([months]
   {:code   :subsidio-empleo
    :label  "Subsidio para el empleo"
    :amount (* (bigdec months) subsidio-empleo-monthly-2025)}))

(defn mx-isr-personal-provider
  "MX personal income tax — ISR personas físicas — annual provider.
   The 11-band progressive `tarifa` of Art. 152 LISR.

   Deductions personales (gastos médicos, intereses hipotecarios,
   aportaciones de retiro, colegiaturas — capped by Art. 151 at the
   lesser of 15 % of income or 5 UMA-years) ride `context :inputs` as
   a `:base-transform`. The `subsidio para el empleo` rides `:inputs
   :credits` — build it with `subsidio-empleo-credit`.

   Config:
     :schedule — optional schedule override; defaults to the
                 marginal-bracket `progressive` form. Pass
                 `isr-personal-cuota-fija-schedule` for bit-exact SAT
                 `tarifa` arithmetic (see the abstraction note)."
  [{:keys [schedule]}]
  (pit/personal-income-tax-provider
   {:id        :mx-isr-personal
    :schedule  (or schedule (ts/progressive isr-personal-brackets))
    :authority :mx-sat
    :commodity :MXN
    :statute   "Ley del ISR, Art. 152 (personas físicas)"}))
