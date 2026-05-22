(ns kontor.l10n-br.period-tax-provider
  "Brazilian personal income tax — Imposto sobre a Renda da Pessoa
   Física (IRPF) — as a kontor `PeriodTaxProvider` (ADR-099; research
   notes 103 / 104, Phase 1).

   ## Cadence — monthly IRRF, annual ajuste

   IRPF is *withheld at source* every month on salary as IRRF (Imposto
   de Renda Retido na Fonte) and *reconciled once a year* in the
   Declaração de Ajuste Anual (DAA). kontor models the annual return:
   the provider is parameterised on the **annual** schedule; the IRRF
   already withheld feeds the component's `:prepaid` when the consumer
   supplies it under `:inputs {:irrf-withheld <amount>}`, and
   `kontor.period-tax-provider/balance` then yields the saldo a pagar
   (positive) or the restituição (negative). Monthly IRRF assessment
   uses the *same shape* — the monthly tabela is the annual one ÷ 12
   (`monthly-irrf-brackets`) — but is not the return; it is a
   withholding pre-payment.

   ## The schedule

   A 5-band progressive ladder, 0 / 7.5 / 15 / 22.5 / 27.5 %. The
   Brazilian tabela is *published* in `limite de renda / alíquota /
   parcela a deduzir` form — a flat-rate-on-the-band's-top-bracket
   minus a constant. That `parcela a deduzir` form is mathematically
   identical to a marginal-rate progressive ladder; kontor's
   `progressive` schedule IS the marginal-rate form, so the published
   table converts cleanly — the bracket UPPER bounds ARE the published
   `limite de renda` figures and the marginal rates ARE the published
   alíquotas. See `irpf-annual-brackets` for the conversion and the
   `verify` block in the test for the cross-check against the published
   `parcela a deduzir` numbers.

   ## Deductions — itemized vs. the simplified discount

   The taxpayer picks, per the DAA, the *better* of two deduction
   regimes:

   - **itemized** (deduções legais) — INSS contributions, dependents
     (a per-dependent fixed amount), education expenses (capped per
     person), health expenses (uncapped), private-pension (PGBL)
     contributions, etc.;
   - **simplified** (desconto simplificado) — a flat 20 % of taxable
     income, capped at `simplified-discount-cap`, *in lieu of* every
     itemized deduction.

   Both ride the `:inputs :base-transform`. `irpf-base-transform`
   builds the `:formula` transform that takes the better of the two —
   the `:elect`-style taxpayer choice, here expressed on the BASE side
   (the choice is between deduction regimes, not between schedules, so
   it belongs in the base-transform, not in an `:elect` schedule). A
   consumer that has already made the election can instead pass a
   plain `:adjustments` transform directly.

   ## What this namespace is NOT

   This provider determines the IRPF liability as a `TaxReturnFacts`.
   It does not emit the DAA file, does not compute the monthly IRRF
   payroll line (that is `modules/payroll-br`'s job), and does not
   carry IRPJ/CSLL — corporate income tax for BR is a later phase
   (note 104 §3 Phase 3)."
  (:require [kontor.personal-income-tax :as pit]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; The annual IRPF schedule — tabela progressiva anual
;; ============================================================================

(def irpf-annual-brackets
  "IRPF — tabela progressiva ANUAL, exercício 2025 / ano-calendário
   2024 (the table the Declaração de Ajuste Anual filed in 2025 uses).
   VERIFY against current law — the Receita Federal re-tables the
   brackets, most recently by Lei 14.848/2024 / MP 1.206/2024 raising
   the isenção; the 2026 ano-calendário table differs again.

   The Brazilian tabela is published as `limite de renda / alíquota /
   parcela a deduzir`:

     até        R$ 24.511,92  —  0    %  —  parcela R$      0,00
     de 24.511,93 a 33.919,80 —  7,5  %  —  parcela R$  1.838,39
     de 33.919,81 a 45.012,60 — 15    %  —  parcela R$  4.382,38
     de 45.012,61 a 55.976,16 — 22,5  %  —  parcela R$  7.758,32
     acima de       55.976,16 — 27,5  %  —  parcela R$ 10.557,13

   That `parcela a deduzir` form is the closed-form of a marginal-rate
   progressive ladder; the marginal form below is exact (the published
   `parcela` constants are themselves rounded — the test cross-checks
   the two agree). Each `:upper` IS a published `limite de renda`; the
   final band is the open top (`:upper nil`)."
  [{:rate 0M     :upper 24511.92M}
   {:rate 0.075M :upper 33919.80M}
   {:rate 0.15M  :upper 45012.60M}
   {:rate 0.225M :upper 55976.16M}
   {:rate 0.275M :upper nil}])

(def irpf-monthly-brackets
  "IRPF — tabela progressiva MENSAL (the IRRF withholding table) =
   `irpf-annual-brackets` ÷ 12. Provided for the monthly IRRF
   pre-payment computation; the annual schedule is the one the
   provider runs for the return. VERIFY against current law."
  (mapv (fn [{:keys [rate upper]}]
          {:rate  rate
           :upper (when upper
                    (.divide ^java.math.BigDecimal upper 12M 2
                             java.math.RoundingMode/HALF_EVEN))})
        irpf-annual-brackets))

;; ============================================================================
;; Deductions — itemized vs. the simplified discount
;; ============================================================================

(def simplified-discount-rate
  "Desconto simplificado — 20 % of taxable income, in lieu of every
   itemized deduction."
  0.20M)

(def simplified-discount-cap
  "Desconto simplificado — the cap on the 20 % discount, ano-calendário
   2024 (R$ 16.754,34). VERIFY against current law."
  16754.34M)

(def dependent-deduction-annual
  "Dedução por dependente — the per-dependent annual deduction,
   ano-calendário 2024 (R$ 2.275,08). VERIFY against current law."
  2275.08M)

(def education-deduction-cap
  "Despesas com instrução — the annual per-person cap on education
   expenses, ano-calendário 2024 (R$ 3.561,50). Health expenses
   (despesas médicas) are, by contrast, UNCAPPED. VERIFY against
   current law."
  3561.50M)

(defn simplified-discount
  "The desconto simplificado for a `taxable-base` — `min(20 % × base,
   cap)`. Never negative."
  ^java.math.BigDecimal [^java.math.BigDecimal taxable-base]
  (min (* simplified-discount-rate (max 0M taxable-base))
       simplified-discount-cap))

(defn itemized-deductions
  "Total itemized deductions (deduções legais) from an `:itemized`
   inputs map. Every field is optional; an absent field is 0.

     :inss          — INSS / official-pension contributions (uncapped)
     :private-pension — PGBL contributions (the 12 %-of-income cap is
                      the consumer's to enforce; passed as a figure)
     :dependents    — a COUNT of dependents — × `dependent-deduction-annual`
     :education     — a vector of per-person education spends — each
                      capped at `education-deduction-cap`
     :health        — health expenses (despesas médicas) — UNCAPPED
     :other         — any further statutory deduction, pre-validated"
  ^java.math.BigDecimal
  [{:keys [inss private-pension dependents education health other]}]
  (+ (bigdec (or inss 0M))
     (bigdec (or private-pension 0M))
     (* (bigdec (or dependents 0)) dependent-deduction-annual)
     (reduce (fn [acc spend]
               (+ acc (min (bigdec spend) education-deduction-cap)))
             0M
             (or education []))
     (bigdec (or health 0M))
     (bigdec (or other 0M))))

(defn irpf-base-transform
  "Build the `:inputs :base-transform` for the IRPF return — a
   `:formula` transform that subtracts the BETTER of the itemized
   deductions and the desconto simplificado from the marginalized
   gross income (the renda tributável).

   `deductions` is a map of itemized inputs (see `itemized-deductions`).
   The result is the `max(0, base − chosen-deduction)` where
   `chosen-deduction = max(Σitemized, simplified-discount)` — the
   regime that yields the lower tax, exactly the taxpayer's DAA
   election.

   Pass `{:force :simplified}` or `{:force :itemized}` to skip the
   election and pin the regime."
  [{:keys [force] :as deductions}]
  {:transform/type :formula
   :fn (fn [^java.math.BigDecimal base]
         (let [itemized   (itemized-deductions deductions)
               simplified (simplified-discount base)
               chosen     (case force
                            :simplified simplified
                            :itemized   itemized
                            (max itemized simplified))]
           (max 0M (- base chosen))))})

;; ============================================================================
;; The provider
;; ============================================================================

(defn br-irpf-provider
  "BR personal income tax — IRPF — `PeriodTaxProvider`. The 5-band
   annual `progressive` schedule (tabela progressiva anual).

   Deductions ride `context :inputs` as a `:base-transform` — build it
   with `irpf-base-transform` (itemized vs. simplified election). The
   IRRF withheld monthly feeds the component's `:prepaid` when the
   consumer supplies it; `kontor.period-tax-provider/balance` then
   yields the saldo a pagar / restituição. Optional config:

     :schedule — schedule override (default the annual tabela)"
  [{:keys [schedule]}]
  (pit/personal-income-tax-provider
   {:id        :br-irpf
    :schedule  (or schedule (ts/progressive irpf-annual-brackets))
    :authority :br-receita-federal
    :commodity :BRL
    :statute   "Lei 9.250/1995; tabela progressiva anual (RIR/2018)"}))
