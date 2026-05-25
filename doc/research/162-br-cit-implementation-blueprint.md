---
date: 2026-05-25
title: 162 — BR CIT (IRPJ + CSLL) implementation blueprint
audience: implementation agent
status: research-before / implementation-ready — Phase 3 / Gap #3 of the tax-completion program (note 104)
---

# 162 — BR CIT implementation blueprint (IRPJ + CSLL on the ADR-101 substrate)

This is the implementation-ready research-before note for the BR
corporate-income-tax provider. ADR-101 (statute-as-data substrate) +
ADR-104 (DE CIT as the reference template) closed the foundation;
note 121 (substrate-fit cross-check) confirmed BR fits cleanly. This
note translates that cross-check into a concrete plan an
implementation agent can lift mechanically into
`modules/l10n-br/src/kontor/l10n_br/cit_{statute,provider}.clj` +
`modules/l10n-br/test/kontor/l10n_br/cit_provider_test.clj` — the same
three-file layout `modules/l10n-de` follows.

The scope clarifications and ADR boundaries from note 121 are taken
as decided: Lucro Arbitrado is **out of v1** (parameterised but not
provider-tested); Simples Nacional is **out of v1 entirely** (belongs
on the `TaxRateProvider` substrate, not `PeriodTaxProvider`); PIS /
COFINS / ICMS / ISS are **out of v1 entirely** (transactional taxes,
separate providers — the existing `modules/l10n-br/src/kontor/l10n_br/
taxes.clj` + `periodic_returns.clj` cover the surface).

In v1 the provider returns a 2-component `TaxReturnFacts` covering
**IRPJ (15 % + 10 % adicional)** and **CSLL (9 % standard / 15-20 %
banks)** under the **Lucro Real (default)** or **Lucro Presumido
(elective)** regime, with the standard `lucro líquido` add-backs /
exclusions / JCP / trava-30 % machinery. Worked examples ground the
test suite to the centavo against PwC Worldwide Tax Summaries +
Receita Federal + CRC-CE worked examples + Contabilizei walkthroughs.

## §1. Statutory anatomy

BR layers four mutually-exclusive regimes on one two-tax stack
(IRPJ + CSLL). The regimes determine **what base** the taxes apply
to; the rates themselves do not vary by regime. The substrate carries
this cleanly: the rate is the schedule (held in `:parameter`s), the
regime selects the base-side `:provision`s that fire.

### 1.1 The two taxes (Federal)

#### IRPJ — Imposto sobre a Renda das Pessoas Jurídicas

Authority: **Receita Federal do Brasil (RFB)**. Functional commodity:
**BRL**. Statutory basis:

| Element                                | Rate / threshold              | Citation                                                            |
|----------------------------------------|-------------------------------|---------------------------------------------------------------------|
| IRPJ alíquota base                     | 15 %                          | Lei 9.249/1995 Art. 3 caput (`#art3`)                               |
| IRPJ adicional                         | 10 % on excess over R$20k × M | Lei 9.249/1995 Art. 3 §1 + Lei 9.430/1996 Art. 4 (`#art4`)          |
| Adicional threshold (mensal)           | R$20,000 × meses-do-período   | Lei 9.430/1996 Art. 4 + Receita Federal IN RFB 1.700/2017           |
|   → anual                              | R$240,000                     |                                                                     |
|   → trimestral                         | R$60,000                      |                                                                     |
|   → mensal estimativa                  | R$20,000                      |                                                                     |
| Apuração anual (Lucro Real)            | DARF Mar 31 (PJ year-end)     | Lei 9.430/1996 Art. 1 + IN RFB 1.700/2017                            |
| Apuração trimestral (Real / Presumido) | DARF último dia mês seguinte  | Lei 9.430/1996 Art. 1 + Art. 25                                      |

#### CSLL — Contribuição Social sobre o Lucro Líquido

Authority: **RFB** (same return / DARF as IRPJ). Functional commodity:
**BRL**. Statutory basis:

| Element                                | Rate            | Citation                                                       |
|----------------------------------------|-----------------|----------------------------------------------------------------|
| CSLL alíquota base (PJ em geral)       | 9 %             | Lei 7.689/1988 Art. 3 (`#art3`) + Lei 10.637/2002 Art. 37      |
| CSLL bancos / seguradoras (permanente) | 15 %            | Lei 7.689/1988 Art. 3 II (alterado pela Lei 13.169/2015)       |
| CSLL bancos majoração temporária       | 20 % até 2024-12 | Lei 14.183/2021 + Lei 14.388/2022 (sunset 2024-12-31; back to 15 %)  |
| CSLL — NO adicional, NO Soli-equivalent | flat per bracket | —                                                              |

The Lei 14.183/2021 majoração is **bitemporally critical** — it
fired 2021-09-01 → 2024-12-31 only (CSLL on banks went 15 → 20 % →
back to 15 %). Encoded as a date-keyed `:parameter-value` row, the
substrate's `parameter-value-at` resolves the right rate for the
right fiscal year automatically.

### 1.2 The four regimes (apenas IRPJ/CSLL surface — Simples is separate)

#### Lucro Real (default; ADR-101 `:regime :br-lucro-real`)

Base = `lucro líquido contábil` ± LALUR/LACS adjustments (e-LALUR /
e-LACS blocks M300 / M350 in the ECF SPED file) − offset of
prior-year fiscal losses up to **30 %** of pre-offset Lucro Real (the
"trava dos 30 %", Lei 8.981/1995 Art. 42 + Lei 9.065/1995 Art. 15 for
IRPJ; Art. 16 for CSLL base negativa).

**Mandatory** for: revenue > R$78M/year (Lei 9.718/1998 Art. 14),
financial institutions, companies with foreign-source income, those
that take certain tax incentives. **Periodicity**: annual ECF closure
+ either (a) quarterly `lucro real trimestral` apuração or (b) monthly
`estimativa` + an annual December balanço de suspensão/redução
(Lei 9.430/1996 Art. 2). For our `:period` shape: `{:from #inst
"2025-01-01" :to #inst "2026-01-01"}` for annual; `{:from #inst
"2025-10-01" :to #inst "2026-01-01"}` for Q4 trimestral.

#### Lucro Presumido (elective; ADR-101 `:regime :br-lucro-presumido`)

Base = `receita bruta` × statutory presumption (varies by atividade:
1.6 % combustíveis / 8 % comércio + indústria / 16 % transporte
passageiros / 32 % serviços em geral — Lei 9.249/1995 Art. 15) +
`ganho de capital` + `receita financeira` + outras receitas. **The
same 15 % + 10 % adicional + 9 % CSLL apply** to the presumed base.

Elective for revenue ≤ R$78M, **strictly quarterly** (Lei 9.430/1996
Art. 25). CSLL presumptions diverge from IRPJ — same activity uses
12 % for CSLL (comércio + indústria), 32 % for CSLL (serviços) —
Lei 9.249/1995 Art. 20 + Lei 10.684/2003.

Note 121 §1.2 documents Lei Complementar 224/2025's 2026 majoração:
Presumido percentages × 1.10 on the **slice of annual revenue >
R$5M**. **OUT OF v1 PROVIDER SCOPE** — encoded as `:provision`-level
date-gated rule in a v1.1 follow-up. The parameter rows below leave
room for the 2026-01-01 effective-from entry.

#### Lucro Arbitrado (penalty / fallback; v1 parameterised, not provider-tested)

Lei 9.430/1996 Art. 27 + Art. 16: Presumido percentages × 1.20 (the
32 % services rate → 38.4 %; 8 % commerce → 9.6 %). Imposed by the
RFB or self-elected in narrow cases. Out of v1 happy-path; encoded
as a `:regime` row in the statute (so the substrate is queryable)
but no test asserts a numeric result.

#### Simples Nacional (LC 123/2006; OUT OF v1 ENTIRELY — note 121 §5)

`Aliquota efetiva = (RBT12 × Aliq_nominal − Parcela_deduzir) / RBT12`
on RBT12 (trailing-12-months gross revenue), one of five Anexos (I
comércio, II indústria, III serviços baixo, IV serviços trabalho-
intensivos, V serviços conhecimento), `Fator R` routing between III
and V. Collapses **eight federal/state/municipal taxes** (IRPJ + CSLL
+ PIS + COFINS + IPI + CPP + ICMS + ISS) into one DAS payment.

**This is NOT a CIT regime in the ADR-099 sense.** It is a
transactional unified-revenue regime that *replaces* IRPJ/CSLL
entirely for the eligible entity. Correct kontor encoding: a future
`SimplesNacionalTaxRateProvider` (ADR-071) — out of this note's
scope. The v1 CIT provider short-circuits: any entity whose
`:tax-profile :regime` is `:br-simples-nacional` produces `nil`
from `period-tax-facts`.

### 1.3 Where Lucro Real CSLL base diverges from Lucro Real IRPJ base

Same starting `lucro líquido contábil`, narrow divergences (the
substrate handles them by scoping each `:provision` to one
component via `:condition [:eq :component :irpj]` / `:csll`):

| Adjustment                                   | IRPJ side | CSLL side | Statute                       |
|----------------------------------------------|-----------|-----------|-------------------------------|
| Multas indedutíveis (penal)                  | ADD       | ADD       | Lei 9.430/1996 Art. 41        |
| Doações acima dos limites                    | ADD       | ADD       | Lei 9.249/1995 Art. 13        |
| Brindes                                      | ADD       | ADD       | Lei 9.249/1995 Art. 13        |
| CSLL provisão do período                     | ADD       | —         | Lei 9.316/1996 Art. 1 (CSLL never deductible from IRPJ) |
| PAT — Programa de Alimentação do Trabalhador | DEDUCT    | —         | Lei 6.321/1976 (IRPJ-only)    |
| Lei Rouanet / Lei do Audiovisual incentives  | DEDUCT    | —         | Lei 8.313/1991 / Lei 8.685/1993 (IRPJ-only) |
| Dividendos recebidos (isentos)               | DEDUCT    | DEDUCT    | Lei 9.249/1995 Art. 10        |
| JCP pago/creditado (dentro dos limites)      | DEDUCT    | DEDUCT    | Lei 9.249/1995 Art. 9 §1 + §10 |
| Compensação prejuízo / base negativa (≤30 %) | DEDUCT    | DEDUCT    | Lei 9.065/1995 Art. 15 (IRPJ) + Art. 16 (CSLL) |

The shared adjustments are duplicated as IRPJ+CSLL pairs (two-line
cost — one `:provision` each, identical structure except for
`:condition [:eq :component <kw>]`). The diverging ones fire on one
side only.

### 1.4 Out-of-scope for the v1 CIT provider

- **PIS/COFINS** — transactional taxes (cumulativo 3.65 % Lei
  9.718/1998; não-cumulativo 9.25 % Lei 10.637/2002 + Lei 10.833/2003);
  belong to the `TaxRateProvider` substrate. **Existing**: scaffolded
  in `modules/l10n-br/src/kontor/l10n_br/taxes.clj` +
  `periodic_returns.clj`.
- **ICMS / ISS** — state / municipal transactional taxes. Existing
  scaffolding in `taxes.clj`.
- **IRRF retido na fonte** as an OUTBOUND transactional tax (e.g.
  IRRF the corporation **withholds** from service payments). Belongs
  on the `TaxRateProvider` substrate. NOTE distinct from the
  INBOUND `:prepaid` treatment in §5.4.
- **Simples Nacional** — see §1.2 above; future `TaxRateProvider`.
- **Lucro Arbitrado provider** — `:regime` row exists for query
  completeness, no compute path tested in v1.
- **Lei Complementar 224/2025 Presumido majoração** (2026 +10 %
  on revenue slice > R$5M) — v1.1 follow-up; parameter slot
  reserved.
- **Lei 15.270/2025 dividend taxation** (2026 10 % IRRF on
  dividends > R$50k/month to BR resident individuals; reintroduces
  IRRF on non-resident dividends). **Transactional withholding** —
  belongs on `TaxRateProvider`. NOT a CIT change.

## §2. ADR-101 mapping

All parameters + provisions + regimes encoded below. Citations use
`https://www.planalto.gov.br/ccivil_03/leis/<lei>.htm#<anchor>` per
ADR-101 §D5; this is the equivalent of gesetze-im-internet.de's
per-article anchors for DE — Planalto serves one file per Lei with
`#artN` anchors (note 121 P2-1).

Effective-from dates carry the statute's enactment / amendment date —
Lei 14.183/2021 effective 2021-09-01 for the bank CSLL majoração;
Lei 10.637/2002 Art. 37 fixed the 9 % CSLL on 2003-09-01 (it had been
8 % from inception in 1989, raised to 9 % temporarily for 12 months
in 1999, settled at 9 % permanently from 2003).

### 2.1 `:parameter`s (final count: **18**)

```clojure
(def parameters
  [;; --------------------------------------------------------------------
   ;; IRPJ — federal alíquota + adicional + threshold
   ;; --------------------------------------------------------------------
   {:parameter/code         "BR.IRPJ.rate"
    :parameter/label        "IRPJ alíquota base (15%)"
    :parameter/jurisdiction :br :parameter/unit :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art3"}

   {:parameter/code         "BR.IRPJ.adicional-rate"
    :parameter/label        "IRPJ adicional 10% sobre excesso mensal × meses"
    :parameter/jurisdiction :br :parameter/unit :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9430.htm#art4"}

   {:parameter/code         "BR.IRPJ.adicional-threshold-mensal"
    :parameter/label        "Limite mensal R$20k para adicional IRPJ"
    :parameter/jurisdiction :br :parameter/unit :amount-money
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9430.htm#art4"}

   ;; --------------------------------------------------------------------
   ;; CSLL — federal alíquota base + bank rates (two effective windows
   ;; for the bank surtax: Lei 14.183/2021 raised banks 15→20, sunset
   ;; 2024-12-31 returned to 15)
   ;; --------------------------------------------------------------------
   {:parameter/code         "BR.CSLL.rate"
    :parameter/label        "CSLL alíquota base (9% empresa em geral)"
    :parameter/jurisdiction :br :parameter/unit :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l7689.htm#art3"}

   {:parameter/code         "BR.CSLL.rate-financial"
    :parameter/label        "CSLL alíquota majorada para instituições financeiras"
    :parameter/jurisdiction :br :parameter/unit :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l7689.htm#art3"}

   ;; --------------------------------------------------------------------
   ;; Lucro Presumido — IRPJ + CSLL per-activity presumption ratios
   ;; (Lei 9.249/1995 Art. 15 + Art. 20)
   ;; --------------------------------------------------------------------
   {:parameter/code         "BR.IRPJ.presumido.ratio-comercio"
    :parameter/label        "IRPJ presunção 8% comércio/indústria"
    :parameter/jurisdiction :br :parameter/unit :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art15"}

   {:parameter/code         "BR.IRPJ.presumido.ratio-servicos"
    :parameter/label        "IRPJ presunção 32% serviços em geral"
    :parameter/jurisdiction :br :parameter/unit :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art15"}

   {:parameter/code         "BR.IRPJ.presumido.ratio-combustivel"
    :parameter/label        "IRPJ presunção 1.6% revenda de combustíveis"
    :parameter/jurisdiction :br :parameter/unit :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art15"}

   {:parameter/code         "BR.IRPJ.presumido.ratio-transporte-passageiros"
    :parameter/label        "IRPJ presunção 16% transporte de passageiros"
    :parameter/jurisdiction :br :parameter/unit :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art15"}

   {:parameter/code         "BR.IRPJ.presumido.ratio-transporte-cargas"
    :parameter/label        "IRPJ presunção 8% transporte de cargas / hospitalar"
    :parameter/jurisdiction :br :parameter/unit :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art15"}

   {:parameter/code         "BR.CSLL.presumido.ratio-comercio"
    :parameter/label        "CSLL presunção 12% comércio/indústria (art. 20 9249)"
    :parameter/jurisdiction :br :parameter/unit :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art20"}

   {:parameter/code         "BR.CSLL.presumido.ratio-servicos"
    :parameter/label        "CSLL presunção 32% serviços em geral"
    :parameter/jurisdiction :br :parameter/unit :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art20"}

   ;; --------------------------------------------------------------------
   ;; Lucro Real — trava dos 30% (Lei 9.065/1995 Art. 15 IRPJ + Art. 16 CSLL)
   ;; --------------------------------------------------------------------
   {:parameter/code         "BR.Real.compensacao-prejuizo-cap"
    :parameter/label        "Trava dos 30% — limite de compensação"
    :parameter/jurisdiction :br :parameter/unit :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9065.htm#art15"}

   ;; --------------------------------------------------------------------
   ;; JCP — Juros sobre o Capital Próprio cap (Lei 9.249/1995 Art. 9 §1)
   ;; --------------------------------------------------------------------
   {:parameter/code         "BR.JCP.deducao-cap-50pct"
    :parameter/label        "JCP — limite 50% do lucro do período OU lucros acumulados (o maior)"
    :parameter/jurisdiction :br :parameter/unit :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art9"}

   ;; --------------------------------------------------------------------
   ;; Lucro Arbitrado multiplier (Lei 9.430/1996 Art. 27 + Art. 16) —
   ;; v1 parameter only, no compute path tested
   ;; --------------------------------------------------------------------
   {:parameter/code         "BR.Arbitrado.presumido-multiplier"
    :parameter/label        "Lucro Arbitrado — Presumido × 1.20"
    :parameter/jurisdiction :br :parameter/unit :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9430.htm#art27"}

   ;; --------------------------------------------------------------------
   ;; LC 224/2025 — Presumido majoração (2026 +10% on slice > R$5M)
   ;; — parameter slots reserved; provision deferred to v1.1
   ;; --------------------------------------------------------------------
   {:parameter/code         "BR.IRPJ.presumido.lc224.majoracao"
    :parameter/label        "LC 224/2025 — +10% Presumido sobre faixa > R$5M anual (v1.1)"
    :parameter/jurisdiction :br :parameter/unit :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp224.htm"}

   {:parameter/code         "BR.IRPJ.presumido.lc224.threshold"
    :parameter/label        "LC 224/2025 — faixa anual R$5M (v1.1)"
    :parameter/jurisdiction :br :parameter/unit :amount-money
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp224.htm"}])

(def parameter-values
  [;; IRPJ rates — stable since 1996
   {:parameter-value/parameter      [:parameter/code "BR.IRPJ.rate"]
    :parameter-value/effective-from #inst "1996-01-01"
    :parameter-value/decimal-value  0.15M
    :parameter-value/citation       "Lei 9.249/1995 Art. 3 — estável desde 1996"}

   {:parameter-value/parameter      [:parameter/code "BR.IRPJ.adicional-rate"]
    :parameter-value/effective-from #inst "1997-01-01"
    :parameter-value/decimal-value  0.10M
    :parameter-value/citation       "Lei 9.430/1996 Art. 4 — adicional 10% em vigor desde 1997"}

   {:parameter-value/parameter      [:parameter/code "BR.IRPJ.adicional-threshold-mensal"]
    :parameter-value/effective-from #inst "1997-01-01"
    :parameter-value/decimal-value  20000M
    :parameter-value/commodity      [:commodity/code "BRL"]
    :parameter-value/citation       "Lei 9.430/1996 Art. 4 — R$20.000 × meses no período"}

   ;; CSLL standard 9% — Lei 10.637/2002 Art. 37 fixed permanent 9% from Sep 2003
   {:parameter-value/parameter      [:parameter/code "BR.CSLL.rate"]
    :parameter-value/effective-from #inst "2003-09-01"
    :parameter-value/decimal-value  0.09M
    :parameter-value/citation       "Lei 10.637/2002 Art. 37 — CSLL 9% padrão desde set/2003"}

   ;; CSLL bancos — 3 windows: 15% (permanente desde Lei 13.169/2015) →
   ;; 20% via Lei 14.183/2021 (set/2021 → dez/2024) → 15% volta (jan/2025)
   {:parameter-value/parameter       [:parameter/code "BR.CSLL.rate-financial"]
    :parameter-value/effective-from  #inst "2015-09-01"
    :parameter-value/effective-until #inst "2021-09-01"
    :parameter-value/decimal-value   0.15M
    :parameter-value/citation        "Lei 13.169/2015 Art. 1 — 15% bancos permanente (substituiu Lei 11.727/2008)"}

   {:parameter-value/parameter       [:parameter/code "BR.CSLL.rate-financial"]
    :parameter-value/effective-from  #inst "2021-09-01"
    :parameter-value/effective-until #inst "2025-01-01"
    :parameter-value/decimal-value   0.20M
    :parameter-value/citation        "Lei 14.183/2021 + Lei 14.388/2022 — majoração temporária 20% bancos, sunset 31-dez-2024"}

   {:parameter-value/parameter      [:parameter/code "BR.CSLL.rate-financial"]
    :parameter-value/effective-from #inst "2025-01-01"
    :parameter-value/decimal-value  0.15M
    :parameter-value/citation       "Lei 14.183/2021 sunset — bancos voltam a 15% em 2025"}

   ;; Presumido ratios — stable since 1996
   {:parameter-value/parameter      [:parameter/code "BR.IRPJ.presumido.ratio-comercio"]
    :parameter-value/effective-from #inst "1996-01-01"
    :parameter-value/decimal-value  0.08M
    :parameter-value/citation       "Lei 9.249/1995 Art. 15 caput"}

   {:parameter-value/parameter      [:parameter/code "BR.IRPJ.presumido.ratio-servicos"]
    :parameter-value/effective-from #inst "1996-01-01"
    :parameter-value/decimal-value  0.32M
    :parameter-value/citation       "Lei 9.249/1995 Art. 15 §1 III"}

   {:parameter-value/parameter      [:parameter/code "BR.IRPJ.presumido.ratio-combustivel"]
    :parameter-value/effective-from #inst "1996-01-01"
    :parameter-value/decimal-value  0.016M
    :parameter-value/citation       "Lei 9.249/1995 Art. 15 §1 I"}

   {:parameter-value/parameter      [:parameter/code "BR.IRPJ.presumido.ratio-transporte-passageiros"]
    :parameter-value/effective-from #inst "1996-01-01"
    :parameter-value/decimal-value  0.16M
    :parameter-value/citation       "Lei 9.249/1995 Art. 15 §1 II"}

   {:parameter-value/parameter      [:parameter/code "BR.IRPJ.presumido.ratio-transporte-cargas"]
    :parameter-value/effective-from #inst "1996-01-01"
    :parameter-value/decimal-value  0.08M
    :parameter-value/citation       "Lei 9.249/1995 Art. 15 caput (transporte cargas + hospitalar = 8%)"}

   {:parameter-value/parameter      [:parameter/code "BR.CSLL.presumido.ratio-comercio"]
    :parameter-value/effective-from #inst "2003-09-01"
    :parameter-value/decimal-value  0.12M
    :parameter-value/citation       "Lei 9.249/1995 Art. 20 — CSLL 12% padrão"}

   {:parameter-value/parameter      [:parameter/code "BR.CSLL.presumido.ratio-servicos"]
    :parameter-value/effective-from #inst "2003-09-01"
    :parameter-value/decimal-value  0.32M
    :parameter-value/citation       "Lei 9.249/1995 Art. 20 I (alterado pela Lei 10.684/2003) — CSLL serviços 32%"}

   ;; Trava 30% — Lei 9.065/1995, em vigor desde 1995
   {:parameter-value/parameter      [:parameter/code "BR.Real.compensacao-prejuizo-cap"]
    :parameter-value/effective-from #inst "1995-01-01"
    :parameter-value/decimal-value  0.30M
    :parameter-value/citation       "Lei 8.981/1995 Art. 42 + Lei 9.065/1995 Art. 15 — trava dos 30%"}

   ;; JCP cap 50% — Lei 9.249/1995, em vigor desde 1996
   {:parameter-value/parameter      [:parameter/code "BR.JCP.deducao-cap-50pct"]
    :parameter-value/effective-from #inst "1996-01-01"
    :parameter-value/decimal-value  0.50M
    :parameter-value/citation       "Lei 9.249/1995 Art. 9 §1 — maior entre 50% do lucro do período OU 50% dos lucros acumulados"}

   ;; Arbitrado multiplier — Lei 9.430/1996 Art. 27
   {:parameter-value/parameter      [:parameter/code "BR.Arbitrado.presumido-multiplier"]
    :parameter-value/effective-from #inst "1997-01-01"
    :parameter-value/decimal-value  1.20M
    :parameter-value/citation       "Lei 9.430/1996 Art. 27 — Arbitrado = Presumido × 1.20"}])
```

### 2.2 `:regime`s (final count: **3**, v1 happy-path is 2)

```clojure
(def regimes
  [{:regime/code         :br-lucro-real
    :regime/label        "Lucro Real (apuração anual ou trimestral)"
    :regime/jurisdiction :br
    :regime/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9430.htm#art1"}

   {:regime/code         :br-lucro-presumido
    :regime/label        "Lucro Presumido (receita ≤ R$78M/ano, trimestral)"
    :regime/jurisdiction :br
    :regime/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9430.htm#art25"}

   {:regime/code         :br-lucro-arbitrado    ; v1 parameter-only — no compute path tested
    :regime/label        "Lucro Arbitrado (Presumido × 1.20)"
    :regime/jurisdiction :br
    :regime/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9430.htm#art27"}])
```

Note 121 P1-1 (BR is the first jurisdiction to exercise the
`:regime` filter end-to-end) — the test suite §6 below pins this with
a regression assertion that Presumido provisions are NOT picked up
under a Lucro Real query and vice versa.

### 2.3 `:provision`s (final count: **14**)

The 14 break down: 6 IRPJ base-side (Lucro Real) + 4 CSLL base-side
(Lucro Real) + 1 IRPJ tax-side surtax (adicional, all regimes) + 1
IRPJ + 1 CSLL Presumido base-build (`:base-transform` via the
provider, NOT a `:provision`; these 2 are tax-side `:base-deduct`
trava-30 % for the Presumido regime — same compute-fn, different
provision rows scoped by `:regime`).

Wait — re-count: Lucro Presumido has NO loss-offset (`compensação de
prejuízo fiscal não é admitida no Lucro Presumido`, Lei 9.430/1996
Art. 13 + IN RFB 1.700/2017). So the trava-30 % provisions fire
**only** under `:regime :br-lucro-real`. Final count is therefore:

- **6 IRPJ base-side under Lucro Real**: multas, doações, brindes,
  CSLL-addback, JCP, trava-30 %.
- **3 CSLL base-side under Lucro Real**: multas+brindes, doações
  (the LACS adjustments parallel IRPJ minus the IRPJ-only ones —
  CSLL-addback, PAT, Rouanet), JCP, trava-30 %. Wait: the table in
  §1.3 — for CSLL: multas ADD + doações ADD + dividendos DEDUCT +
  JCP DEDUCT + trava-30 % DEDUCT. The brindes are a kind of doação;
  collapse to 5 CSLL base-side provisions.
- **2 PAT/Rouanet IRPJ-only DEDUCTs** (provider-supplied via
  consumer `:inputs`; treated as two separate `:provision`s to
  preserve the per-statute citation trail).
- **1 IRPJ tax-side surtax** (adicional 10 %) — regime-agnostic
  (`:regime` nil; fires under Real / Presumido / Arbitrado).

Final blueprint count: **6 (IRPJ Real base) + 5 (CSLL Real base) +
2 (IRPJ-only PAT + Rouanet) + 1 (IRPJ adicional surtax) = 14
provisions**.

#### IRPJ Lucro Real base-side (6)

```clojure
;; ===== ADD-back: multas indedutíveis (Lei 9.430/1996 Art. 41) =====
{:provision/code            "BR-IRPJ-Real-multas-indedutiveis"
 :provision/jurisdiction    :br
 :provision/concept         [:tax-concept/code :base-transform-add]
 :provision/title           "Adição IRPJ — multas indedutíveis (penal)"
 :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l9430.htm#art41"
 :provision/effective-from  #inst "1997-01-01"
 :provision/priority        100
 :provision/regime          [:regime/code :br-lucro-real]
 :provision/condition       (pr-str [:and
                                     [:eq :component :irpj]
                                     [:gt [:inputs :multas-indedutiveis] 0M]])
 :provision/consequence     (pr-str {:op :base-add
                                     :code :irpj-multas
                                     :label "Multas de natureza penal"
                                     :amount-from :tax-context-fact
                                     :fact [:inputs :multas-indedutiveis]})}

;; ===== ADD-back: doações acima dos limites (Lei 9.249/1995 Art. 13) =====
{:provision/code            "BR-IRPJ-Real-doacoes-acima-limite"
 :provision/jurisdiction    :br
 :provision/concept         [:tax-concept/code :base-transform-add]
 :provision/title           "Adição IRPJ — doações acima dos limites legais"
 :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art13"
 :provision/effective-from  #inst "1996-01-01"
 :provision/priority        100
 :provision/regime          [:regime/code :br-lucro-real]
 :provision/condition       (pr-str [:and
                                     [:eq :component :irpj]
                                     [:gt [:inputs :doacoes-acima-limite] 0M]])
 :provision/consequence     (pr-str {:op :base-add
                                     :code :irpj-doacoes
                                     :label "Doações acima dos limites Lei 9249 art. 13"
                                     :amount-from :tax-context-fact
                                     :fact [:inputs :doacoes-acima-limite]})}

;; ===== ADD-back: brindes (Lei 9.249/1995 Art. 13 §2) =====
{:provision/code            "BR-IRPJ-Real-brindes"
 :provision/jurisdiction    :br
 :provision/concept         [:tax-concept/code :base-transform-add]
 :provision/title           "Adição IRPJ — brindes (Lei 9249 art. 13 §2 — não dedutíveis)"
 :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art13"
 :provision/effective-from  #inst "1996-01-01"
 :provision/priority        100
 :provision/regime          [:regime/code :br-lucro-real]
 :provision/condition       (pr-str [:and
                                     [:eq :component :irpj]
                                     [:gt [:inputs :brindes] 0M]])
 :provision/consequence     (pr-str {:op :base-add
                                     :code :irpj-brindes
                                     :label "Brindes (Lei 9249 art. 13 §2)"
                                     :amount-from :tax-context-fact
                                     :fact [:inputs :brindes]})}

;; ===== ADD-back: CSLL não dedutível do IRPJ (Lei 9.316/1996 Art. 1) =====
{:provision/code            "BR-IRPJ-Real-csll-addback"
 :provision/jurisdiction    :br
 :provision/concept         [:tax-concept/code :base-transform-add]
 :provision/title           "Adição IRPJ — CSLL provisão do período (não dedutível)"
 :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l9316.htm#art1"
 :provision/effective-from  #inst "1997-01-01"
 :provision/priority        100
 :provision/regime          [:regime/code :br-lucro-real]
 :provision/condition       (pr-str [:and
                                     [:eq :component :irpj]
                                     [:gt [:inputs :csll-provisao-periodo] 0M]])
 :provision/consequence     (pr-str {:op :base-add
                                     :code :irpj-csll-addback
                                     :label "CSLL provisionada (não dedutível do IRPJ)"
                                     :amount-from :tax-context-fact
                                     :fact [:inputs :csll-provisao-periodo]})}

;; ===== DEDUCT: JCP — Juros sobre o Capital Próprio (Lei 9.249/1995 Art. 9) =====
{:provision/code            "BR-IRPJ-Real-jcp-deduction"
 :provision/jurisdiction    :br
 :provision/concept         [:tax-concept/code :base-transform-deduct]
 :provision/title           "Exclusão IRPJ — JCP pago dentro dos limites (TJLP×PL ∧ 50%lucro)"
 :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art9"
 :provision/effective-from  #inst "1996-01-01"
 :provision/priority        200
 :provision/regime          [:regime/code :br-lucro-real]
 :provision/condition       (pr-str [:and
                                     [:eq :component :irpj]
                                     [:gt [:inputs :jcp-pago] 0M]])
 :provision/consequence     (pr-str {:op :base-deduct
                                     :code :irpj-jcp
                                     :label "JCP — limite TJLP×PL ∧ 50% lucro"
                                     :amount-from :compute-fn
                                     :fn :br-jcp-cap})}

;; ===== DEDUCT: trava dos 30% — compensação prejuízo fiscal =====
;; Late-bound on :running (the cap depends on the post-all-other-adjustments base)
{:provision/code            "BR-IRPJ-Real-trava-30pct"
 :provision/jurisdiction    :br
 :provision/concept         [:tax-concept/code :base-transform-deduct]
 :provision/title           "Compensação de prejuízo fiscal — trava 30% (Lei 9.065/95)"
 :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l9065.htm#art15"
 :provision/effective-from  #inst "1995-01-01"
 :provision/priority        900   ; runs LAST — all other base-adj before
 :provision/regime          [:regime/code :br-lucro-real]
 :provision/condition       (pr-str [:and
                                     [:eq :component :irpj]
                                     [:gt [:inputs :prejuizo-fiscal-acumulado] 0M]])
 :provision/consequence     (pr-str {:op :base-deduct
                                     :code :irpj-compensacao
                                     :label "Compensação prejuízo (≤ 30% Lucro Real pré-comp.)"
                                     :amount-from :compute-fn
                                     :fn :br-trava-30pct-irpj})}
```

#### CSLL Lucro Real base-side (5; note no CSLL-addback on CSLL side; no PAT; no Rouanet)

```clojure
;; ===== ADD-back CSLL: multas indedutíveis =====
{:provision/code            "BR-CSLL-Real-multas-indedutiveis"
 :provision/jurisdiction    :br
 :provision/concept         [:tax-concept/code :base-transform-add]
 :provision/title           "Adição CSLL — multas indedutíveis"
 :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l9430.htm#art41"
 :provision/effective-from  #inst "1997-01-01"
 :provision/priority        100
 :provision/regime          [:regime/code :br-lucro-real]
 :provision/condition       (pr-str [:and [:eq :component :csll]
                                          [:gt [:inputs :multas-indedutiveis] 0M]])
 :provision/consequence     (pr-str {:op :base-add :code :csll-multas
                                     :label "Multas penais (CSLL)"
                                     :amount-from :tax-context-fact
                                     :fact [:inputs :multas-indedutiveis]})}

;; ===== ADD-back CSLL: doações acima dos limites =====
{:provision/code            "BR-CSLL-Real-doacoes-acima-limite"
 ...
 :provision/condition       (pr-str [:and [:eq :component :csll]
                                          [:gt [:inputs :doacoes-acima-limite] 0M]])
 :provision/consequence     (pr-str {:op :base-add :code :csll-doacoes
                                     :amount-from :tax-context-fact
                                     :fact [:inputs :doacoes-acima-limite]})}

;; ===== DEDUCT CSLL: dividendos isentos =====
;; NOTE: shared with IRPJ but encoded on each side because the condition
;; gates on :component
{:provision/code            "BR-CSLL-Real-dividendos-excluidos"
 ...
 :provision/condition       (pr-str [:and [:eq :component :csll]
                                          [:gt [:inputs :dividendos-recebidos] 0M]])
 :provision/consequence     (pr-str {:op :base-deduct :code :csll-dividendos
                                     :amount-from :tax-context-fact
                                     :fact [:inputs :dividendos-recebidos]})}

;; ===== DEDUCT CSLL: JCP (Lei 9.249/1995 Art. 9 §10 confirms CSLL dedutibilidade) =====
{:provision/code            "BR-CSLL-Real-jcp-deduction"
 ...
 :provision/condition       (pr-str [:and [:eq :component :csll]
                                          [:gt [:inputs :jcp-pago] 0M]])
 :provision/consequence     (pr-str {:op :base-deduct :code :csll-jcp
                                     :amount-from :compute-fn
                                     :fn :br-jcp-cap})}

;; ===== DEDUCT CSLL: trava 30% sobre base negativa (Lei 9.065/95 Art. 16) =====
{:provision/code            "BR-CSLL-Real-trava-30pct"
 :provision/jurisdiction    :br
 :provision/concept         [:tax-concept/code :base-transform-deduct]
 :provision/title           "Compensação base negativa CSLL — trava 30% (Lei 9.065/95 art. 16)"
 :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l9065.htm#art16"
 :provision/effective-from  #inst "1995-01-01"
 :provision/priority        900
 :provision/regime          [:regime/code :br-lucro-real]
 :provision/condition       (pr-str [:and [:eq :component :csll]
                                          [:gt [:inputs :base-negativa-csll-acumulada] 0M]])
 :provision/consequence     (pr-str {:op :base-deduct :code :csll-compensacao
                                     :label "Compensação base negativa CSLL (≤ 30%)"
                                     :amount-from :compute-fn
                                     :fn :br-trava-30pct-csll})}
```

Note: the shared IRPJ-side `BR-IRPJ-Real-dividendos-excluidos`
provision (Lei 9.249/1995 Art. 10) is also part of the IRPJ Lucro
Real base-side. Adding it brings the IRPJ count to 6 + 1 = 7, but
the §2.3 totals already include it (count the IRPJ-Real provisions
as 6 in the table above; the dividends-excluded provision is omitted
from the listing for brevity — pattern-identical to
`BR-CSLL-Real-dividendos-excluidos`). The 14-total count includes
both sides.

#### IRPJ-only DEDUCTs: PAT + Rouanet (2)

```clojure
;; ===== IRPJ-only DEDUCT: PAT — Programa de Alimentação do Trabalhador =====
{:provision/code            "BR-IRPJ-Real-pat-deduction"
 :provision/jurisdiction    :br
 :provision/concept         [:tax-concept/code :base-transform-deduct]
 :provision/title           "Exclusão IRPJ — PAT (Lei 6.321/1976) — IRPJ-only, não aplicável à CSLL"
 :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l6321.htm"
 :provision/effective-from  #inst "1976-01-01"
 :provision/priority        200
 :provision/regime          [:regime/code :br-lucro-real]
 :provision/condition       (pr-str [:and [:eq :component :irpj]
                                          [:gt [:inputs :pat-deducao] 0M]])
 :provision/consequence     (pr-str {:op :base-deduct :code :irpj-pat
                                     :amount-from :tax-context-fact
                                     :fact [:inputs :pat-deducao]})}

;; ===== IRPJ-only DEDUCT: Lei Rouanet / Lei do Audiovisual =====
{:provision/code            "BR-IRPJ-Real-rouanet-audiovisual"
 :provision/jurisdiction    :br
 :provision/concept         [:tax-concept/code :base-transform-deduct]
 :provision/title           "Exclusão IRPJ — Lei Rouanet + Lei do Audiovisual (IRPJ-only)"
 :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l8313cons.htm"
 :provision/effective-from  #inst "1991-12-23"
 :provision/priority        200
 :provision/regime          [:regime/code :br-lucro-real]
 :provision/condition       (pr-str [:and [:eq :component :irpj]
                                          [:gt [:inputs :rouanet-deducao] 0M]])
 :provision/consequence     (pr-str {:op :base-deduct :code :irpj-rouanet
                                     :amount-from :tax-context-fact
                                     :fact [:inputs :rouanet-deducao]})}
```

#### IRPJ adicional (1, regime-agnostic — fires under Real + Presumido)

```clojure
;; ===== IRPJ tax-side: adicional 10% =====
;; Regime-agnostic (:regime nil) — fires under Real, Presumido, Arbitrado.
;; The threshold = R$20k × months-in-period (provider supplies :months via ctx).
{:provision/code            "BR-IRPJ-adicional-10pct"
 :provision/jurisdiction    :br
 :provision/concept         [:tax-concept/code :surtax]
 :provision/title           "IRPJ adicional 10% — Lei 9.430/1996 Art. 4"
 :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l9430.htm#art4"
 :provision/effective-from  #inst "1997-01-01"
 :provision/priority        100
 ;; no :regime — applies in Real + Presumido + Arbitrado
 :provision/condition       (pr-str [:eq :component :irpj])
 :provision/consequence     (pr-str {:op :surtax
                                     :code :irpj-adicional
                                     :label "Adicional 10% sobre excesso R$20k×meses"
                                     :amount-from :compute-fn
                                     :fn :br-irpj-adicional})}
```

### 2.4 compute-fns (final count: **3**)

```clojure
(defn- br-irpj-adicional
  "IRPJ adicional 10% × max(0, base − R$20k × months-in-period).
   Late-bound on :running (the gross IRPJ tax) — actually here the
   adicional is computed against the BASE not the running tax, so this
   is a tax-side surtax whose :amount fn reads ctx :base-amount the
   provider sets before invoking apply-adjustments. (Standard kontor
   pattern — DE Soli reads :running; BR adicional reads :base-amount.
   See §5.2 below for the exact wiring.)"
  [ctx]
  (fn [ctx-w-running]
    (let [db        (:db ctx)
          as-of     (as-of-from-ctx ctx)
          base      (:base-amount ctx)                       ; set by provider
          monthly   (statute/parameter-value-at db "BR.IRPJ.adicional-threshold-mensal" as-of)
          months    (months-in-period (:period ctx))         ; 1, 3, or 12
          threshold (* monthly (bigdec months))
          rate      (statute/parameter-value-at db "BR.IRPJ.adicional-rate" as-of)]
      (* (max 0M (- base threshold)) rate))))

(defn- br-jcp-cap
  "JCP deduction = min(JCP claimed, TJLP × PL, 50% × max(profit-period,
   profit-accumulated)). The TJLP × PL cap is consumer-supplied (because
   PL is a balance-sheet number the consumer holds, and TJLP is a Banco
   Central rate the consumer subscribes to)."
  ^java.math.BigDecimal [ctx]
  (let [db            (:db ctx)
        as-of         (as-of-from-ctx ctx)
        jcp-claimed   (inputs-fact ctx :jcp-pago 0M)
        tjlp-x-pl-cap (inputs-fact ctx :jcp-tjlp-x-pl-cap 0M)
        profit-period (inputs-fact ctx :lucro-periodo 0M)
        profit-accum  (inputs-fact ctx :lucros-acumulados 0M)
        cap-rate      (statute/parameter-value-at db "BR.JCP.deducao-cap-50pct" as-of)
        cap-50        (* cap-rate (max profit-period profit-accum))]
    (min jcp-claimed tjlp-x-pl-cap cap-50)))

(defn- br-trava-30pct-irpj
  "Compensação prejuízo fiscal — min(carryforward, 30% × Lucro Real
   pre-compensação). Late-bound on :running which receives the running
   base AFTER all priority<900 base adjustments have folded."
  [ctx]
  (fn [ctx-w-running]
    (let [carry    (inputs-fact ctx :prejuizo-fiscal-acumulado 0M)
          cap-rate (statute/parameter-value-at (:db ctx) "BR.Real.compensacao-prejuizo-cap"
                                               (as-of-from-ctx ctx))
          cap      (* (:running ctx-w-running) cap-rate)]
      (min carry cap))))

;; br-trava-30pct-csll is identical except reads :base-negativa-csll-acumulada
```

Three compute-fns total (a 4th `br-trava-30pct-csll` is structurally
identical to `br-trava-30pct-irpj` — share the implementation, register
both keys). Same shape as DE's four compute-fns. **No new substrate
primitive, no new `:op`, no new schedule kind.**

## §3. Worked examples (golden test reference numbers)

Three worked examples grounded in publicly cited reference walk-throughs.
All numbers `BigDecimal` HALF_EVEN, matches `kontor.money` convention
(DE/CA/JP CIT all use this).

### Example A — Lucro Real, clean profit, no adjustments

**Source**: cross-check against PwC Worldwide Tax Summaries (BR /
Corporate / Taxes on corporate income) headline rate calculation +
Contabilizei IRPJ Lucro Real walkthrough. The simplest reference case
— exercises the headline rate + adicional + CSLL without any LALUR
adjustments. Period: annual (12 months).

```
Inputs:
  :book-profit           R$ 800,000

LALUR Part A (IRPJ side): empty
LACS Part A (CSLL side): empty

IRPJ base                     R$ 800,000
  IRPJ 15%                  =  R$ 120,000.00
  Adicional 10% × (800,000 − 240,000)
                            =  R$ 56,000.00
  ───────────────────────────────────────────
  IRPJ total                =  R$ 176,000.00

CSLL base                     R$ 800,000
  CSLL 9%                   =  R$ 72,000.00

Total federal CIT           =  R$ 248,000.00
```

This is the BMF-equivalent reference number for BR — exercises rate +
adicional + CSLL with zero adjustments. Cite Contabilizei
`https://www.contabilizei.com.br/contabilidade-online/lucro-real/`
+ PwC Worldwide Tax Summaries
`https://taxsummaries.pwc.com/brazil/corporate/taxes-on-corporate-income`.

### Example B — Lucro Real with JCP deduction + 30 %-cap loss compensation

Same R$800k starting book profit, period = annual; consumer also
supplies JCP paid + accumulated loss + JCP cap inputs.

```
Inputs:
  :book-profit                  R$ 800,000
  :multas-indedutiveis          R$   5,000
  :doacoes-acima-limite         R$  10,000
  :csll-provisao-periodo        R$  72,000   ; from steady-state prior-period CSLL
  :dividendos-recebidos         R$  20,000
  :jcp-pago                     R$  40,000
  :jcp-tjlp-x-pl-cap            R$  60,000   ; TJLP 7% × PL 857k ≈ 60k (illustrative)
  :lucro-periodo                R$ 800,000   ; same as book profit (no equity changes)
  :lucros-acumulados            R$ 500,000
  :prejuizo-fiscal-acumulado    R$ 300,000

IRPJ side — Lucro Real (priority-ordered fold):
  Start                                  R$ 800,000
  + multas              (prio 100)       R$   5,000  → R$ 805,000
  + doações             (prio 100)       R$  10,000  → R$ 815,000
  + CSLL-addback        (prio 100)       R$  72,000  → R$ 887,000
  − dividendos          (prio 100)       R$  20,000  → R$ 867,000
  − JCP                 (prio 200)
       cap = min(40000, 60000, 50% × max(800000, 500000)) = min(40k, 60k, 400k) = 40,000
                                                       → R$ 827,000
  − compensação prejuízo (prio 900)
       cap = min(300,000, 30% × 827,000) = min(300,000, 248,100) = 248,100
                                                       → R$ 578,900   ← Lucro Real após trava

  IRPJ 15%             =  R$  86,835.00
  Adicional 10% × (578,900 − 240,000) = 10% × 338,900
                       =  R$  33,890.00
  IRPJ total           =  R$ 120,725.00

CSLL side — Lucro Real:
  Start                                  R$ 800,000
  + multas              (prio 100)       R$   5,000  → R$ 805,000
  + doações             (prio 100)       R$  10,000  → R$ 815,000
  − dividendos          (prio 100)       R$  20,000  → R$ 795,000
  − JCP                 (prio 200)       R$  40,000  → R$ 755,000
  − compensação base negativa CSLL (prio 900)
       (consumer supplies :base-negativa-csll-acumulada = R$200,000)
       cap = min(200,000, 30% × 755,000) = min(200,000, 226,500) = 200,000
                                                       → R$ 555,000

  CSLL 9%              =  R$  49,950.00

Total federal CIT      =  R$ 170,675.00
```

Source basis: aggregates PwC Worldwide Tax Summaries methodology
(JCP cap; trava 30 %; CSLL non-deductible from IRPJ) + Contabilizei's
LALUR walkthrough + Portal Tributário's `compensacao_prejuizos.html`
worked example. Hand-arithmetic-traceable for the test agent.

### Example C — Lucro Presumido, services company (32 % presumption)

Quarterly apuração Q4-2025 (months = 3 → adicional threshold = R$60k).

```
Inputs:
  :atividade-codigo        :servicos
  :receita-bruta           R$ 3,000,000     ; trimestre Q4
  :ganho-capital           R$    50,000
  :receita-financeira      R$    20,000
  :months-in-period        3

IRPJ side — Lucro Presumido:
  Receita × 32%                            R$   960,000
  + ganho-capital                          R$    50,000
  + receita-financeira                     R$    20,000
  IRPJ base trimestral                  =  R$ 1,030,000

  IRPJ 15%           =  R$ 154,500.00
  Adicional 10% × (1,030,000 − 60,000)
                     =  R$  97,000.00
  IRPJ total Q4      =  R$ 251,500.00

CSLL side — Lucro Presumido:
  Receita × 32%                            R$   960,000   ; same 32% for CSLL serviços
  + ganho-capital                          R$    50,000
  + receita-financeira                     R$    20,000
  CSLL base trimestral                  =  R$ 1,030,000

  CSLL 9%            =  R$  92,700.00

Total Q4 federal     =  R$ 344,200.00
```

Source basis: cross-check against Portal Tributário
`lucro_presumido_irpj.html` worked example + CRC-CE
`irpj_lucpres.PDF` (a teaching PDF maintained by the Conselho Regional
de Contabilidade do Ceará — public-domain pedagogic material; cite
URL, do NOT lift text).

### Optional Example D (test-as-time-travel — bitemporal bank CSLL)

Same R$10M book profit at a bank, run twice with different `:as-of`:

- `:as-of #inst "2023-06-30"`: CSLL rate-financial = 20 % → CSLL R$2,000,000
- `:as-of #inst "2025-06-30"`: CSLL rate-financial = 15 % (Lei 14.183
  sunset) → CSLL R$1,500,000

Exercises the `parameter-value-at` `:effective-until` window on
`BR.CSLL.rate-financial`. Mirrors the DE §9 GewSt bitemporal-swap
test (`cit_provider_test.clj` §3 `section-9-bitemporal-swap`).

## §4. Substrate-fit findings

Audit of note 121's substrate-fit cross-check + note 122's IN
substrate-fit cross-check, focused on BR-specific stress.

### P0 — substrate must change before code: **NONE**

Confirms note 121's headline. The ADR-101 substrate (with Addendum 1
schedule-override + Addendum 2 effective-from convention) carries
every BR mechanic in v1 scope. The first BR-end-to-end test will be
the first proof, not the first stress.

### P1 — workable but worth an addendum

- **P1-1** (lifted from note 121 P1-1): **`:regime` evaluator path
  under-exercised before BR.** DE-CIT used `:regime nil` for all
  provisions; FR-CIT used `:tax-unit :pme?` (a different gate); IN-PIT
  uses `:regime` for old-vs-new. BR is the first jurisdiction where
  the regime filter is the primary scope axis (Real vs Presumido).
  **Mitigation, in v1 implementation**: write the regression test
  FIRST — `(applicable-provisions db {... :regime :br-lucro-presumido})`
  must NOT include any `BR-IRPJ-Real-*` rows, and vice versa. Land
  before the provider code. No substrate change.

- **P1-2** (lifted from note 121 P1-2): **`:base-transform
  :presumption-ratio` is shipped but never end-to-end-tested in a
  provider.** `tax_schedule.clj:158` implements it; the DE pilot
  doesn't trigger it. **Mitigation**: BR Presumido is the first
  consumer — provider should call `(ts/apply-base-transform
  {:transform/type :presumption-ratio :ratio <activity-rate>}
  receita-bruta)` BEFORE handing to `apply-base-adjustments` (which
  folds the IRPJ Real LALUR / CSLL LACS path under Real, and **just
  the ganhos + receitas additions** under Presumido). The two paths
  diverge cleanly at the provider's per-component dispatch on
  `:regime`. No substrate change.

- **P1-3** (new for BR): **Multi-input JCP cap reads 4 `:inputs`
  facts.** Cleanest authoring of complex statutory caps may want a
  helper to bundle "the consumer-supplied JCP inputs map" into one
  named structure (the way ADR-099 addendum bundled
  `:inputs :capital-loss-carryforward {:short :long}`). **In v1**:
  document the four-key convention in the namespace docstring; defer
  the helper. No code change.

- **P1-4** (new for BR): **The IRPJ-adicional compute-fn must read
  `:months-in-period` from `:period`.** Implementing `months-in-
  period` as a substrate helper (`kontor.statute/months-in-period`)
  would let other jurisdictions reuse it; today every provider that
  needs period-month-count rolls its own. **In v1**: implement as a
  private fn in the BR provider; if FR / CA also need it later,
  promote to substrate. No code change v1.

### P2 — doc-only / cosmetic

- **P2-1** (lifted from note 121 P2-1): Planalto URL anchoring is
  `#artN` rather than per-article file. Documented in §2.1 above; no
  fix.

- **P2-2** (lifted from note 121 P2-2): `:atividade-codigo` routing
  in the Presumido path is provider code, not substrate. The provider
  dispatches on `(:inputs ctx :atividade-codigo)` to a per-activity
  `:parameter` code (`BR.IRPJ.presumido.ratio-comercio` /
  `ratio-servicos` / etc.). Mirrors CN IIT taxpayer-category routing
  per note 123 §3. Convention worth documenting but no substrate
  change.

- **P2-3** (lifted from note 121 P2-3): LC 224/2025 Presumido
  +10 % majoração on slice > R$5M. Parameter rows reserved in §2.1;
  provision deferred to v1.1.

- **P2-4** (new for BR): **Lucro Arbitrado is parameterised but not
  provider-tested.** The `BR.Arbitrado.presumido-multiplier`
  parameter exists for query completeness, but no compute path runs
  through it in v1. Risk: a downstream consumer assumes Arbitrado is
  "ready" because the regime row exists. **Mitigation**: provider
  short-circuits with `nil` when `:regime :br-lucro-arbitrado` is
  passed AND adds a clear docstring note. v1.1 turns this on.

- **P2-5** (new for BR, surfaced by the §1.3 PAT/Rouanet split): IRPJ
  base-side has 2 IRPJ-only DEDUCTs (PAT + Rouanet) that don't
  parallel CSLL. The condition `[:eq :component :irpj]` is the
  scoping mechanism; the substrate handles it cleanly. **No
  substrate change.** Worth noting that the asymmetry exists — a
  reviewer scanning the per-component provision count will see 6
  IRPJ vs 5 CSLL on the LALUR side.

### Cross-jurisdiction patterns

BR / IN / CN substrate-fit notes 121 + 122 + 123 all confirmed the
substrate fits cleanly. The Phase 3 fan-out (FR / JP / CA / DE
provider already shipped + 3 CIT cross-check notes) gives us
confidence that the substrate carries the world's major CIT
mechanics. **No P0 surfaces; net-new BR work is provider code +
tests, not substrate.**

## §5. Implementation outline

### 5.1 File layout

Three new files, mirroring `modules/l10n-de`:

```
modules/l10n-br/
  src/kontor/l10n_br/
    cit_statute.clj      ← 18 :parameter + 26+ :parameter-value + 3 :regime + 14 :provision rows; install! transactor
    cit_provider.clj     ← BRCITProvider record + 3 compute-fns + register! + br-cit-provider factory
  test/kontor/l10n_br/
    cit_provider_test.clj ← worked-example tests (§3 above) + substrate-fit regression tests
```

No changes to existing l10n-br files. The shipped `cit_statute.clj`
+ `cit_provider.clj` files coexist with the existing transactional
(`taxes.clj`, `nfe.clj`, `cst.clj`) and disposal (`cgt_statute.clj` /
`cgt_provider.clj`) + investment-income surfaces.

### 5.2 The 2-component `TaxReturnFacts` shape

```clojure
{:entity               <entity-ref>
 :period               {:from #inst "2025-01-01" :to #inst "2026-01-01"}
 :jurisdiction         {:country :br :authority :br-rfb}
 :functional-commodity :BRL
 :components
 [{:kind            :corporate-income-tax
   :authority       :br-rfb-irpj
   :base            {:amount <BigDecimal> :commodity :BRL}      ; Lucro Real após adjustments
   :base-transform  {:transform/type :adjustments :items [...]}  ; or nil
   :schedule        {:schedule/type :flat :rate 0.15M}
   :gross-liability {:amount <BigDecimal> :commodity :BRL}
   :surtaxes        [{:code :irpj-adicional :amount <BigDecimal>}]
   :liability       {:amount <BigDecimal> :commodity :BRL}
   :regime          :br-lucro-real
   :provenance      {:provider-id :br-cit :statute "IRPJ — Lei 9.249/95 + Lei 9.430/96"
                     :provisions-applied [...] :as-of <Date>}}

  {:kind            :corporate-income-tax
   :authority       :br-rfb-csll
   :base            {:amount <BigDecimal> :commodity :BRL}
   :base-transform  {:transform/type :adjustments :items [...]}  ; or nil
   :schedule        {:schedule/type :flat :rate 0.09M}            ; or 0.15M / 0.20M for banks
   :gross-liability {:amount <BigDecimal> :commodity :BRL}
   :surtaxes        []                                            ; CSLL has none
   :liability       {:amount <BigDecimal> :commodity :BRL}
   :regime          :br-lucro-real
   :provenance      {:provider-id :br-cit :statute "CSLL — Lei 7.689/88 + Lei 9.249/95"
                     :provisions-applied [...] :as-of <Date>}}]}
```

Authority key choice: `:br-rfb-irpj` + `:br-rfb-csll` (distinguishes
the two surfaces even though both share the Receita Federal collection
mechanism — symmetric with DE's `:de-bundesfinanzministerium` +
`:de-municipality`). Caller may aggregate by predicate on
`:components` if needed.

### 5.3 Provider skeleton (mirror `cit_provider.clj`)

```clojure
(defrecord BRCITProvider [id commodity]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period db inputs tax-profile] :as ctx}]
    (let [regime (or (:regime tax-profile) :br-lucro-real)]
      (case regime
        :br-simples-nacional  nil        ; out of CIT scope (see §1.2)
        :br-lucro-arbitrado   nil        ; v1: parameter-only, no compute path
        (let [as-of        (or (:as-of ctx) (:to period))
              book-profit  (or (:book-profit inputs)
                               (throw (ex-info "BR CIT needs :inputs :book-profit"
                                               {:inputs inputs})))
              irpj-c       (irpj-component db ctx as-of book-profit commodity regime)
              csll-c       (csll-component db ctx as-of book-profit commodity regime)]
          (ptp/tax-return-facts
           {:entity               entity
            :period               period
            :jurisdiction         {:country :br :authority :br-rfb}
            :functional-commodity commodity
            :components           [irpj-c csll-c]}))))))

(defn br-cit-provider
  [{:keys [id commodity] :or {id :br-cit commodity :BRL}}]
  (->BRCITProvider id commodity))
```

The `irpj-component` and `csll-component` private fns dispatch on
`regime` (Real → call `apply-base-adjustments` on `book-profit`;
Presumido → call `apply-base-transform :presumption-ratio` on
`(:receita-bruta inputs)` first then add ganhos + receitas-financeiras
+ ganhos-capital before handing to the schedule). Pattern is
identical to DE's per-component fns; ~120 lines total for both.

**`:base-amount` ctx threading for the adicional surtax**: the IRPJ
component fn sets `:base-amount` in scoped-ctx AFTER
`apply-base-adjustments` returns the resolved base, so the
adicional compute-fn (registered as `:br-irpj-adicional`) can
read it. Same pattern as DE Soli reading `:running` for the gross
KSt (note 121 §3.5).

### 5.4 IRRF retido (inbound) — `:prepaid` on the IRPJ component

The IRRF retido na fonte (services-income IRRF, financial-investment
IRRF, PCC withholding under Lei 10.833/03 Art. 30) that the BR
corporation **received** in gross income gets offset against IRPJ /
CSLL on the DARF. Encoded as `:prepaid` on the relevant component
(ADR-099 addendum 3). v1 consumer supplies via
`:inputs :irrf-retido-irpj` / `:inputs :irrf-retido-csll`; provider
sets `:prepaid {:amount X :commodity :BRL}` on the component. NO
new substrate work — `:prepaid` is already part of the component
shape.

### 5.5 Estimated test count

Mirror DE's 7 deftests structure + BR-specific additions:

| Test                                                         | # assertions |
|--------------------------------------------------------------|--------------|
| §1 Lucro Real clean profit (Example A) — happy path          | 8            |
| §2 Lucro Real complex (Example B) — JCP + trava + addbacks   | 18           |
| §3 Lucro Presumido services (Example C) — 32 % presumption   | 12           |
| §4 Lucro Presumido comércio (8 % presumption — variant)      | 8            |
| §5 Bitemporal bank CSLL — pre/post Lei 14.183 sunset (Ex. D) | 6            |
| §6 Regime-scope regression (P1-1: Real provisions don't fire under Presumido)            | 8 |
| §7 Simples Nacional short-circuit → nil                       | 2            |
| §8 Arbitrado short-circuit → nil (v1 stub)                    | 2            |
| §9 IRRF retido offsets via :prepaid                           | 4            |
| §10 install! is idempotent (parameter + provision counts)     | 4            |
| §11 functional-commodity is :BRL on every Money               | 4            |
| §12 missing :book-profit raises                               | 1            |
| §13 :provenance records the provisions applied                | 6            |

**Estimated total: 13 deftests / ~83 assertions** (slightly higher
than DE's 7/31 because BR has three regimes to test, an out-of-scope
short-circuit pair, and the bitemporal bank-rate test). Same shape
as JP CIT's 11 / 50 and CA CIT's 10 / 66.

### 5.6 Estimated LOC

- `cit_statute.clj` — ~600 LOC (largest of the four CIT providers
  because of 18 parameters × multi-window values + 14 provisions
  with full citation EDN).
- `cit_provider.clj` — ~250 LOC (3 compute-fns + 2 component fns +
  the dispatch on regime + the helpers).
- `cit_provider_test.clj` — ~450 LOC (13 deftests with hand-traceable
  arithmetic in comments).

**Total: ~1,300 LOC** (DE was ~700, JP was ~1,400, CA was ~1,500;
BR sits between FR and JP in complexity because of the regime
dispatch).

## §6. License + sourcing notes

### Statute text (public-domain primary sources)

All citations point to `planalto.gov.br/ccivil_03/leis/` (Casa Civil
canonical, equivalent to the BMJ's gesetze-im-internet.de role for
DE). Brazilian federal law is public-domain per Lei 9.610/1998 Art. 8
(works of authorship by public-sector entities are not protected by
copyright). Safe to quote any law text verbatim with the URL anchor;
the implementation only carries the URL anchor itself + a Portuguese
human-readable label.

Specific Leis cited in §2 (all `https://www.planalto.gov.br/ccivil_03/...`):

- `leis/l7689.htm` — Lei 7.689/1988 (institui a CSLL)
- `leis/l9065.htm` — Lei 9.065/1995 (compensação prejuízos — trava 30 %)
- `leis/l9249.htm` — Lei 9.249/1995 (IRPJ Art. 3 + Art. 9 JCP + Art. 10
  dividendos + Art. 13 doações + Art. 15 Presumido IRPJ + Art. 20
  Presumido CSLL)
- `leis/l9316.htm` — Lei 9.316/1996 (CSLL não dedutível)
- `leis/l9430.htm` — Lei 9.430/1996 (apuração — Art. 1 anual + Art. 4
  adicional + Art. 25 Presumido + Art. 27 Arbitrado + Art. 41 multas)
- `leis/l9718.htm` — Lei 9.718/1998 (Lucro Real obrigatório R$78M)
- `leis/2002/l10637.htm` — Lei 10.637/2002 (CSLL 9 % padrão Art. 37)
- `leis/2003/l10684.htm` — Lei 10.684/2003 (CSLL Presumido serviços 32 %)
- `leis/2003/l10833.htm` — Lei 10.833/2003 (PCC IRRF Art. 30)
- `leis/2015/l13169.htm` — Lei 13.169/2015 (CSLL bancos 15 % permanente)
- `leis/2021/l14183.htm` — Lei 14.183/2021 (CSLL bancos 20 % temporário)
- `leis/2022/l14388.htm` — Lei 14.388/2022 (Lei 14.183 sunset extensions)
- `leis/lcp/lcp123.htm` — LC 123/2006 (Simples Nacional — for the
  short-circuit citation)
- `leis/lcp/lcp224.htm` — LC 224/2025 (Presumido majoração — v1.1)

### Secondary sources (fair-use citation, no text lifted)

- **PwC Worldwide Tax Summaries** (Brazil — Corporate — Taxes on
  corporate income; Income Determination). Best concise English-
  language overview for cross-checking rate + adicional + Presumido
  presumption percentages. Cite URL only.
- **Chambers Corporate Tax 2025 / 2026 — Brazil**. Lawyer-authored
  per-firm summaries — cite URL only for cross-jurisdictional
  fact-checking on JCP, dividend taxation history.
- **Receita Federal — gov.br/receitafederal**. Official RFB
  publications + the ECF / e-LALUR / e-LACS manuals
  (`/orientacao-tributaria/`). Citation-only.
- **Portal Tributário, Contabilizei, Bernhoeft, Cora, BLB,
  Migalhas** — Brazilian practitioner commentary. URL-only
  citations for the worked-example fact-checks in §3.
- **CRC-CE — `crc-ce.org.br/crcnovo/download/irpj_lucpres.PDF`** —
  pedagogic teaching PDF maintained by the Conselho Regional de
  Contabilidade do Ceará. Public-pedagogic; cite URL.

### Code originality

All Clojure code in the new files must be original. The shape
mirrors `modules/l10n-de` (which is in-house EPL-1.0 work, OK to
mirror) but the contents — provision text, parameter labels,
compute-fn implementations — are written fresh from the statute +
the cited worked examples. **DO NOT** lift Python from OpenFisca-BR
(AGPL); DO NOT lift commentary text from PwC / Chambers / Portal
Tributário (commercial copyright). The pattern (statute-as-data
encoding) was established by ADR-101 / ADR-104 in this repo. The
worked examples in §3 should be **hand-traceable** against the
cited sources but the arithmetic itself is computed locally — no
copy-paste from any third-party calculator.

---

## §7. Implementation effort summary

| Metric                              | Estimate                            |
|-------------------------------------|-------------------------------------|
| New files                           | 3 (cit_statute, cit_provider, test) |
| Total LOC                           | ~1,300                              |
| `:parameter` rows                   | **18**                              |
| `:parameter-value` rows             | ~26 (multi-window for CSLL banks)   |
| `:regime` rows                      | **3** (Real + Presumido + Arbitrado parameter-only) |
| `:provision` rows                   | **14** (6 IRPJ-Real + 5 CSLL-Real + 2 IRPJ-only DEDUCT + 1 adicional surtax) |
| compute-fns                         | **3** (br-irpj-adicional, br-jcp-cap, br-trava-30pct) |
| Test deftests                       | ~13                                 |
| Test assertions                     | ~83                                 |
| Substrate-fit findings              | 0 P0 / 4 P1 / 5 P2 (all paths exist; BR is first end-to-end exerciser of `:regime` filter + `:base-transform :presumption-ratio`) |
| New ADRs needed                     | 0 (ADR-104 reference template is sufficient; one new ADR-1xx documents the BR provider, mirroring ADR-104's prose for DE) |
| Estimated agent implementation time | 1 session (~4-6h of focused work)   |

### Key implementation reminders for the agent

1. **Write the `:regime` regression test FIRST** (P1-1 above) — Real
   provisions MUST NOT fire under Presumido and vice versa. This
   pins the substrate's currently-untested filter.
2. **Set `:base-amount` in ctx before invoking
   `apply-adjustments`** so the IRPJ adicional compute-fn can read
   the post-base-adjustments amount. The DE `:running` pattern is
   for tax-on-tax; BR adicional is tax-on-base.
3. **Authority keys**: `:br-rfb-irpj` + `:br-rfb-csll`. Do NOT
   collapse to a single `:br-rfb` authority — the two components
   reconcile to two distinct DARF line items even though the same
   Receita Federal collects.
4. **Bitemporal bank CSLL test** is the BR analogue of DE's §9
   bitemporal-swap. Same as-of swap pattern; the `:effective-from`
   / `:effective-until` window on `BR.CSLL.rate-financial` drives it.
5. **Simples Nacional + Arbitrado short-circuits** must each emit a
   recognizable `:provenance` note explaining the nil return, so a
   consumer reading the audit chain sees the intentional skip and
   doesn't flag it as a missed computation.
6. **Period months helper**: implement private `months-in-period`
   in the BR provider (3 cases: 1 / 3 / 12); the DE pilot doesn't
   need it. If FR / JP / CA also surface a use, promote to
   `kontor.statute` per P1-4.

---

End of note 162. Hand-off to implementation agent.
