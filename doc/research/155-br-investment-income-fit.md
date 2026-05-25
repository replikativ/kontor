---
date: 2026-05-24
title: 155 — BR investment-income regimes — substrate fit for Phase C2
audience: maintainer + the Phase C2 `br-investment-income-provider` implementation agent
status: research-before for the BR investment-income companion (sibling to `br-cgt-provider` of note 130) + the future `br-investment-income-provider`; no code
---

# 155 — BR investment-income regimes: substrate fit for Phase C2

Brazilian investment-income taxation **changed structurally between
the 130-note draft (May 2026) and now**: PL 1087/2025 became
**Lei nº 15.270, of 26 November 2025**, **enacted**, **in force from
1 January 2026**, and **ends the unique-among-major-jurisdictions
exemption** that Lei 9.249/1995 art. 10 had granted to dividends paid
to individuals since 1996. Note 130 §1.5's claim that the law's CGT
carve-out was the *only* substrate impact was correct **for CGT**, but
incorrect for the broader investment-income picture: the IRPFM is
just one of three new pillars, the other two being a flat 10 % IRRF
on large monthly distributions and a 10 % IRRF on cross-border
dividends regardless of amount.

The BR investment-income regime now sits on **four orthogonal pillars**:

1. **Dividends and lucros — newly-taxable.** PF residents pay 10 %
   IRRF on the slice that exceeds **R$ 50,000/month from the same
   payer** (Lei 15.270/2025 art. 6). Non-residents pay 10 % IRRF on
   the **full** distribution regardless of amount (art. 7). PJ-to-PJ
   distributions among Brazilian companies remain exempt under
   Lei 9.249/95 art. 10 (preserved). Pre-2025 profits approved by
   31 December 2025 ride the 2025 grandfather (no IRRF).
2. **IRPFM — high-earner minimum tax**, 10 % on annual income above
   R$ 1,200,000 (linear ramp from 0 % to 10 % across
   R$ 600k-R$ 1.2M). Carves out capital gains under the PF ladder
   (note 130 §1.5) and certain qualified investment income, but
   **dividend income is in the base**.
3. **JCP (Juros sobre Capital Próprio)** — interest on equity. Rate
   **rose from 15 % to 17.5 % effective 2026-01-01** (PLP 128/2025).
   Deductible by the PJ payer (a substantive economic difference from
   dividends); definitive on the PF/PJ recipient.
4. **Renda fixa + variável** (financial-investment income):
   regressive table maintained at **22.5 / 20 / 17.5 / 15 %** by
   holding-period bucket for títulos públicos/CDB/letras; FII
   distributions to individual holders **remain exempt** subject to
   the 100-shareholder + listed-on-bolsa + ≤10 % ownership
   conditions (Lei 11.033/2004 art. 3 III, preserved).

This note (a) summarises each pillar with the 2026-enacted state, (b)
walks two worked examples (PF with mixed dividends + JCP + FII; PJ
remitting cross-border dividends), (c) assesses fit against the
shipped substrate (`book.declare-dividend!` + `:posting/partner` +
`PeriodTaxProvider` + the note-105 adjustment layer + ADR-101
parameters), (d) names the data gaps, (e) sketches the
`br-investment-income-provider`, (f) cites sources.

---

## §1. The four BR investment-income pillars

### 1.1 Pillar 1 — Dividend WHT (Lei 15.270/2025)

Source: [Lei 15.270/2025 (planalto)](https://www.planalto.gov.br/ccivil_03/_Ato2023-2026/2025/Lei/L15270.htm);
[Receita Federal — Perguntas e Respostas Lei 15.270/2025](https://www.gov.br/receitafederal/pt-br/assuntos/noticias/2025/dezembro/receita-federal-orienta-sobre-os-procedimentos-para-o-recolhimento-do-imposto-de-renda-retido-na-fonte-sobre-lucros-e-dividendos);
[Trench Rossi Watanabe — Brazil enacts Law 15,270/2025](https://www.trenchrossi.com/en/legal-alerts/brazil-enacts-law-15270-2025-which-taxes-dividends-and-amend-personal-income-tax-rules/);
[Mayer Brown — Enactment of Law 15,270/2025](https://www.mayerbrown.com/en/insights/publications/2025/12/enactment-of-law-no-15270-2025-which-establishes-dividend-taxation-expands-the-exemption-threshold-and-introduces-a-minimum-tax-on-high-incomes);
[Felsberg — Brazil's Law 15,270/25 Profits/Dividends 2026](https://www.felsberg.com.br/en/law-15270-taxation-profits-dividends-2026/).

#### 1.1.1 Resident-individual leg — 10 % IRRF above R$ 50,000/month/payer

The trigger is **per (payer-entity, recipient-individual, month)**:
any **dividend or lucro** distributed by the **same** Brazilian
legal entity to the **same** Brazilian-resident individual in the
**same calendar month** that totals **more than R$ 50,000** is
subject to **10 % IRRF on the entire amount** (no deduction —
"sobre o valor total", not the excess).

| Trigger axis    | Threshold     | Rate         | Base                                |
|------------------|---------------|---------------|-------------------------------------|
| Per payer-payee per month | R$ 50,000 | 10 % IRRF | Total monthly distribution (not just excess) |

**Examples.** Sole shareholder PF receives R$ 40k/month from CorpA →
R$ 0 IRRF. Same PF receives R$ 60k/month from CorpA → R$ 6,000 IRRF
(10 % on the entire R$ 60k). Same PF receives R$ 40k/month each from
CorpA and CorpB → R$ 0 IRRF (the R$ 50k cap is **per payer**, not
aggregated across payers).

The IRRF is **definitive on the recipient** — there is no annual
adjustment, no credit against other tax. The dividend itself remains
out of the cumulative DAA base (the IRRF replaces it). The payer is
the substituto tributário with the remittance + DARF obligation.

#### 1.1.2 Non-resident leg — 10 % IRRF on the full amount

Per art. 7, **any dividend or lucro distributed to a non-resident**
(individual or entity, regardless of treaty country, regardless of
amount) is subject to 10 % IRRF on the **gross** distribution.
Exemptions: foreign sovereigns + foreign public-pension funds + a
narrow set of qualified institutional investors enumerated in the
law (art. 7 §2).

Treaty positions: most BR treaties **do NOT cap dividend WHT** —
they treat it as residual taxation in the source state. The 10 %
rate is **within** treaty ceilings (most BR treaties allow 10-15 %
source WHT on portfolio dividends), so the new tax is treaty-
compliant. A small set of older treaties (notably AT) **did** cap
WHT at 0 % under the prior dividend-exemption regime — those
positions are subject to ongoing litigation. **kontor's provider
does not opine on treaty positions; it computes the statutory rate
and surfaces it as a `:line-item` the consumer can override.**

#### 1.1.3 PJ-to-PJ leg — exemption preserved

Lei 15.270/2025 art. 6 §3 expressly **preserves** Lei 9.249/1995
art. 10 for distributions between Brazilian-resident PJs. A Brazilian
parent receiving dividends from a Brazilian subsidiary continues to
book the receipt as **tax-exempt income** (no IRPJ, no CSLL). This
prevents the cascading taxation that abolishing the exemption would
have caused for multi-entity groups.

#### 1.1.4 Transition — 2025 grandfather

Profits **determined up to fiscal year 2025**, whose **distribution
was approved** by the competent corporate body (annual shareholder
meeting / AGM) by **31 December 2025**, are **not subject to the
new IRRF**, regardless of when actual payment occurs (the original
RFB guidance held this open through 2028; subsequent commentary
narrows this — see Receita Federal Perguntas e Respostas linked
above for current position).

Substrate impact: the dividend's `:transaction/effective-date`
matters less than the **approval-date** in `:audit-doc`. The
provider needs to know the date the AGM approved the distribution
to apply the grandfather. Suggested: `:audit-doc/category
:br-dividend-approval-resolution` with `:audit-doc/event-date` set
to the AGM date.

### 1.2 Pillar 2 — IRPFM (Imposto de Renda Pessoa Física Mínimo)

Source: [Lei 15.270/2025 art. 9-13 (planalto)](https://www.planalto.gov.br/ccivil_03/_Ato2023-2026/2025/Lei/L15270.htm);
[Mayer Brown — IRPFM](https://www.mayerbrown.com/en/insights/publications/2025/12/enactment-of-law-no-15270-2025-which-establishes-dividend-taxation-expands-the-exemption-threshold-and-introduces-a-minimum-tax-on-high-incomes);
[Felsberg — Transitional Rule for Dividend Taxation](https://www.felsberg.com.br/en/law-15270-transitional-rule-dividend-taxation-brazil);
[A&M — Brazil Law 15,270/2025 Dividend WHT Returns](https://www.alvarezandmarsal.com/thought-leadership/tax-news-alert-brazil-law-15-270-2025-dividend-withholding-tax-returns-in-2026).

#### 1.2.1 Shape — linear ramp 0 % → 10 % across R$ 600k-R$ 1.2M

The IRPFM is a **floor**, not a surcharge: the taxpayer's total
annual ordinary IRPF + the new IRPFM together must reach the
threshold rate × annual income. Computation:

1. Compute **base annual income** (taxable + certain exempt
   categories the law includes back — notably dividends, JCP, and
   FII distributions, with carve-outs for capital gains taxed under
   the PF ladder and certain qualified investment income).
2. Apply the **effective IRPFM rate** by band:

   | Annual income       | Effective IRPFM rate          |
   |--------------------|-------------------------------|
   | ≤ R$ 600,000        | 0 %                           |
   | R$ 600k–R$ 1.2M     | linear ramp 0 % → 10 %         |
   | > R$ 1.2M            | 10 % flat                     |

3. Subtract **credits**: the ordinary IRPF + IRRF on dividends + any
   dividend-WHT already paid abroad (treaty credit).
4. Pay the positive residual.

The IRPFM is **explicitly stratified above** the ordinary IRPF —
not a substitute. A taxpayer earning R$ 2M/year with R$ 500k of
that as dividends pays:
- R$ 50,000 IRRF on the dividends (10 % × R$ 500k);
- ordinary IRPF on the non-dividend R$ 1.5M (per the 27.5 % top
  bracket — say ~R$ 250k after the standard deduction);
- IRPFM: 10 % × R$ 2M = R$ 200k *before* credit; minus R$ 50k IRRF
  + R$ 250k IRPF = R$ 300k credit → **IRPFM payable = R$ 0** (the
  credit exceeds the floor).

The IRPFM bites when **most of the taxpayer's income is dividends**
(or other low-tax categories) and the ordinary IRPF is small. A PF
shareholder taking R$ 2M as dividends, no salary, no business income
would pay: R$ 200k IRRF (10 % × R$ 2M); ordinary IRPF = 0 (dividends
are out of the ordinary base); IRPFM = R$ 200k − R$ 200k credit =
R$ 0. **The IRPFM tops up cases where dividends are spread across
multiple payers** (each ≤ R$ 50k/month so no IRRF triggers) but the
**annual total is high**. A PF receiving R$ 40k/month from each of
five different PJ payers → R$ 200k IRRF triggered = 0 (no payer
exceeded R$ 50k/month), R$ 2.4M annual dividend income → IRPFM
applies the 10 % floor and the recipient owes ~R$ 240k − $ 0
credits = **R$ 240k IRPFM**.

#### 1.2.2 The dividend-bunching anti-avoidance role

The IRPFM is explicitly designed to neutralise the dividend-bunching
strategy that the R$ 50k/month-per-payer IRRF trigger invites. Three
shapes of bunching:

- **Multi-payer split**: spread dividends across many payers so each
  pays ≤ R$ 50k/month. IRPFM catches it: per-payer R$ 50k → R$ 600k
  annual per payer → for high earners across many payers, IRPFM
  bites the aggregate.
- **Inter-temporal compression**: defer to a single payment >
  R$ 50k. IRRF catches it directly.
- **Salary substitution**: pay the shareholder less salary, more
  dividend. IRPFM neutralises by including dividends in the floor
  base.

The provider must implement the **IRPFM-against-ordinary-IRPF**
credit logic correctly; this is the only substrate stress point.

### 1.3 Pillar 3 — JCP (Juros sobre Capital Próprio)

Source: [PLP 128/2025 (camara.leg.br)](https://www.camara.leg.br/noticias/1233924-camara-aprova-projeto-que-reduz-beneficios-fiscais-federais-e-aumenta-tributacao-de-bets-e-fintechs);
[Cescon Barrieu — PLP 128/2025](https://cesconbarrieu.com.br/congresso-aprova-plp-no-128-2025-corte-linear-de-beneficios-fiscais-e-mudancas-relevantes-em-jcp-bets-e-fintechs/);
[Portal Tributário — TJLP / Juros Capital Próprio](https://www.portaltributario.com.br/guia/tjlp.html);
[Maia Advogados — Mudanças no JCP — PLP 128/2025](https://lfmaia.com.br/mudancas-nos-juros-sobre-capital-proprio-jcp-plp-128-2025/).

**Shape**: a Brazilian PJ may distribute *interest on equity* (JCP)
to its shareholders, capped at the lower of (a) **50 % of the
period's profit before JCP** and (b) **50 % of accumulated retained
earnings**, multiplied by the **TJLP** rate on the period's
opening equity. Two economic effects:

- The JCP is **deductible** by the payer for IRPJ + CSLL
  (it's "interest" expense, not equity distribution). At the 34 %
  combined CIT, this saves the payer 34 % × JCP on its tax bill.
- The recipient pays **IRRF on the JCP** at **17.5 %** (from
  2026-01-01) — raised from 15 % by PLP 128/2025. Definitive on PF
  and on PJ-Simples; creditable for PJ-Lucro-Real (as IRRF) against
  their own CIT.

The combined effective rate post-2026:
- **Payer**: -34 % JCP (deduction) + 17.5 % IRRF remitted = net
  cost 17.5 %.
- **Recipient**: 17.5 % IRRF definitive.

Net economic outcome: ~17.5 % combined vs. dividends' 0 % on
distributions ≤ R$ 50k/month, but ~10 % above + IRPFM floor of
10 %. **JCP is no longer the unambiguously better choice** post-
reform — when the recipient is below the dividend WHT trigger and
the IRPFM floor, dividends are cheaper than JCP.

A JCP **deliberated and credited (booked)** by 31 December 2025
benefits from the **old 15 % IRRF** even if paid in 2026. Booking
date matters; payment date does not. Substrate implication: the
`:transaction/effective-date` of the JCP-declaration entry sets the
rate. Provider does the bitemporal rate lookup.

JCP is fully out of the IRPFM base (it's already taxed at 17.5 %,
which exceeds the 10 % floor).

### 1.4 Pillar 4 — Renda fixa, FII, demais financeiros

Source: [XP — Tabela regressiva](https://conteudos.xpi.com.br/aprenda-a-investir/relatorios/tabela-regressiva/);
[Rico — Renda Fixa Imposto de Renda 2026](https://riconnect.rico.com.vc/blog/renda-fixa-imposto-de-renda/);
[Brazil Economy — Fundos Imobiliários e FI-Infra 2026](https://brazileconomy.com.br/financas/2026/01/fundos-imobiliarios-e-fi-infra-ganham-atratividade-com-nova-lei-do-imposto-de-renda/);
[Receita Federal — Tabela tributação 2026](https://www.gov.br/receitafederal/pt-br/assuntos/meu-imposto-de-renda/tabelas/2026).

The pre-existing regressive IRRF on financial investments is
**preserved unchanged** by Lei 15.270/2025:

| Holding period           | IRRF rate    |
|---------------------------|--------------|
| ≤ 180 days                | 22.5 %       |
| 181-360 days              | 20 %         |
| 361-720 days              | 17.5 %       |
| > 720 days                | 15 %         |

Applies to títulos públicos, CDB, debêntures (non-incentivada),
LCI/LCA (for PJ only — PF exemption), fundos de renda fixa, etc.

**LCI / LCA / CRI / CRA / debêntures incentivadas** (Lei 11.033/2004
art. 3 II) — **exempt for individuals**. Preserved unchanged.

**FII (Fundo de Investimento Imobiliário)** — distributions to PF
**exempt** subject to the historical conditions (Lei 11.033/2004
art. 3 III): the FII must be listed on B3 (or organised OTC) with
≥ 100 cotistas, and the PF holder must own ≤ 10 %. Lei 15.270/2025
explicitly preserves this exemption (commentary uniform across
sources cited above; the legislative debate saw a proposed amendment
to tax FIIs at 5 % which was rejected). Result: FII gains
**attractiveness** post-reform (other dividend channels are now
taxed; FII is the only remaining shareholder-class with full
exemption on regular distributions).

**FI-Infra (Fundo de Investimento em Infraestrutura)** — also
preserved exempt.

**ETF (excluding equity ETFs)** — flat 15 % on gain (no regressive
table). Equity ETFs fall under the renda-variável B3 lane (note 130
§1.2).

### 1.5 What does NOT exist (or no longer exists) in BR investment income

- **No prior-DDT-equivalent ever existed in BR.** The 1996-2025 PF
  exemption was unilateral (no shareholder-level DDT to replace);
  Lei 15.270/2025 reintroduces shareholder-side taxation directly.
- **No equity-receiving cost-step-up** on dividend distributions (BR
  has no concept like US's qualified-dividend basis adjustment).
- **No participation exemption above the 10 % PJ ownership threshold**
  for foreign-source dividends — Brazilian CFC rules under
  Lei 12.973/2014 are unchanged by 15.270 and tax controlled
  foreign affiliates' profits on **deemed distribution** annually
  regardless of actual remittance.

---

## §2. Worked examples

### Example A — PF with mixed dividend + JCP + FII income, mid-tier earner

Sra. Costa, Brazilian resident, FY 2026:

- Salary: R$ 240,000 (R$ 20k/month) → ordinary IRPF.
- Dividends from CorpA: R$ 60,000/month each January-December
  (R$ 720,000 total). **Above R$ 50k/month → 10 % IRRF on R$ 60k each
  month = R$ 6,000 × 12 = R$ 72,000 IRRF total**.
- Dividends from CorpB: R$ 30,000/month (R$ 360,000 total). **Below
  R$ 50k → no IRRF**.
- JCP from CorpC: R$ 80,000 total, paid in March 2026 (post-PLP-128).
  **17.5 % IRRF = R$ 14,000**.
- FII distributions: R$ 50,000 total (≤10 % stake, listed, >100
  holders). **Exempt**.
- CDB interest, 18 months held: R$ 5,000. **17.5 % IRRF = R$ 875**.

Tax computation:

| Pillar / line                  | Income          | Tax (gross) | Tax (after IRRF credit) |
|--------------------------------|-----------------|-------------|--------------------------|
| Ordinary IRPF on salary         | R$ 240,000      | ~R$ 47,000  | ~R$ 47,000               |
| IRRF on CorpA dividends         | R$ 720,000      | R$ 72,000   | definitive — closed     |
| Dividends from CorpB (exempt of IRRF) | R$ 360,000 | R$ 0        | (but in IRPFM base)      |
| IRRF on JCP                     | R$ 80,000       | R$ 14,000   | definitive — closed     |
| FII (exempt)                    | R$ 50,000       | R$ 0        | (out of IRPFM base too — qualified exempt)|
| IRRF on CDB                     | R$ 5,000        | R$ 875      | definitive — closed     |
| **IRPFM base** = salary + all dividends + JCP = R$ 240k + R$ 720k + R$ 360k + R$ 80k | R$ 1,400,000 | 10 % × R$ 1.4M = R$ 140,000 (gross floor) | credit ordinary IRPF + dividend IRRF + JCP IRRF = R$ 47k + R$ 72k + R$ 14k = R$ 133,000 → **IRPFM payable = R$ 7,000** |

Total tax-year cash out: R$ 47k (IRPF) + R$ 72k + R$ 14k + R$ 875
(IRRFs) + R$ 7k (IRPFM) = **R$ 140,875**, on income of R$ 1,455,000
= **effective rate ~9.7 %**.

Substrate trace: each dividend declaration is one
`book.declare-dividend!` entry per month per payer with
`:transaction/partner` = Sra. Costa; the IRRF posting rides as a
**liability for the payer** to RFB. The
`br-investment-income-provider`, on the PF side, reads the period's
distributions where the recipient partner is the assessed PF, sums
the monthly amounts per (payer, recipient), applies the R$ 50k/month
trigger, computes IRRF; for IRPFM, walks the annual base and applies
the credit.

### Example B — PJ remitting dividends abroad

CorpD (Brazilian S.A.) declares R$ 5,000,000 of dividends to its
Luxembourg shareholder LuxCo on 2026-04-15. CorpD's results are 2026
profits (no grandfather).

Pillar 1.1.2: 10 % IRRF on the full R$ 5,000,000 = **R$ 500,000**
remitted to RFB on the dividend payment date.

LuxCo receives net R$ 4,500,000. BR-LU treaty (signed 2010) caps WHT
at 15 % on portfolio dividends; the new 10 % is within the cap, so
no treaty-protection refund.

Substrate trace: one `book.declare-dividend!` entry on CorpD's
books for R$ 5,000,000 with `:transaction/partner` = LuxCo (a
`:partner/kind :foreign-entity`, `:partner/jurisdiction :LU`). The
provider's classifier sees the partner-foreign tag, applies the 10 %
flat regardless of amount, emits the IRRF liability posting + the
DCTFWeb periodic filing line (`:authority :br-rfb-foreign-payment`).

---

## §3. Substrate fit assessment

The four pillars stress different substrate primitives:

### 3.1 Pillar 1 (Dividend WHT) — per-period per-partner aggregate

The substrate already has:
- `book.declare-dividend!` (ADR-095) — the verb that books the
  declaration of a dividend.
- `book.distribute-dividend!` — the cash settlement.
- `:transaction/partner` — points at the recipient (a `:partner`).
- `:posting/partner` — already on each posting (sealing, ADR-007).

What the provider needs to **derive**:
- **Per (payer, recipient, month)** aggregates of dividend
  distributions. This is a `kontor.report/marginalize` operation
  (ADR-096) over the dividend-declared transactions with axes
  `{:payer-entity :recipient-partner :calendar-month}`.
- The threshold check is then a **provider-side fold**, mirror of
  BR CGT note 130 §4 Gap A (the pequeno-valor R$ 35k/month
  aggregate exemption).

**Substrate impact: ZERO new primitives.** The R$ 50k trigger fits
the existing fold pattern.

### 3.2 Pillar 1 cross-border leg — `:partner/jurisdiction` classifier

The flat-10 % regardless-of-amount leg for non-residents requires
the provider to know the recipient's jurisdiction. The substrate
has `:partner/jurisdiction` (a kernel attribute on `:partner`); a
PF-or-PJ partner without a jurisdiction defaults to BR. The
provider's classifier reads it.

**Substrate impact: ZERO.**

### 3.3 Pillar 2 (IRPFM) — full-period base assembly via PIT coupling

The IRPFM is a **floor relative to ordinary IRPF + IRRF credits**.
This is the substrate's most-stressed point — the provider needs:

1. The taxpayer's **ordinary IRPF for the year** (from the existing
   `br-period-tax-provider` for IRPF).
2. The IRRF withheld on dividends (from this provider's own
   computation in Pillar 1).
3. The IRRF withheld on JCP (Pillar 3).
4. Any foreign-tax-credit-paid abroad (an `:inputs` line from the
   consumer).

This is the **cross-provider coupling** pattern documented for MX
note 132 §4 Gap D (art. 120 averaging needs PIT output). The
substrate accommodates via:
- Two-pass query: IRPFM computes its base, sends the
  `:base-transform-add` to the IRPF provider for the dividend
  inclusion, gets the **ordinary IRPF** as a credit, applies the
  10 % floor minus credits.
- Or: the consumer runs `br-period-tax-provider` for IRPF first,
  feeds the output as `:inputs :br-ordinary-irpf-paid` to the
  IRPFM provider. (Simpler shape; matches the IN CGT note 131
  §5.2 pattern.)

**Recommendation**: ship the simpler shape — consumer wires the
order, provider reads `:inputs`. Two-pass within one provider call
is a follow-on optimisation.

**Substrate impact: ZERO new primitives.** Reuses the existing
provider-coupling pattern.

### 3.4 Pillar 3 (JCP) — bitemporal rate cliff

The 15 % → 17.5 % rate cliff at 2026-01-01 (with
deliberation-date overriding payment-date) is a textbook ADR-101
`:parameter` use case. The provider:

```clojure
{:parameter/code :br/jcp-irrf-rate
 :parameter/jurisdiction :br
 :parameter/values
 [{:parameter-value/effective-from #inst "2005-01-01"
   :parameter-value/rate 0.15M}
  {:parameter-value/effective-from #inst "2026-01-01"
   :parameter-value/rate 0.175M}]}
```

The provider reads the rate at the JCP **deliberation date** (not
the payment date) — captured via the JCP-declaration
`:transaction/effective-date`. ADR-048 bitemporality already handles
the as-of query (`:tx/valid-from`).

**Substrate impact: ZERO new primitives.**

### 3.5 Pillar 4 (Renda fixa) — provider-side holding-period classifier

The 22.5 / 20 / 17.5 / 15 % regressive table needs the **holding
period in days** at maturity. The substrate has no first-class
"investment holding" entity, but the consumer's broker importer
(future companion `kontor-broker-br-b3`) would supply per-disposal
maturity data. For v1, the **provider consumes** the consumer's
per-period summary:

```clojure
:inputs {:br-renda-fixa
         [{:holding-days 180 :gain 1000M}
          {:holding-days 365 :gain 2000M}
          {:holding-days 800 :gain 5000M}]}
```

Provider buckets by holding period, applies the rate from
`:br/renda-fixa-bracket` parameter, computes IRRF.

For FII distributions: the **payer** (the fund administrator) does
the eligibility test (≥100 cotistas + ≤10 % stake + listed) and
reports the distribution as **exempt**. The provider consumes the
informational summary and asserts the exemption claim (or raises
if the consumer cannot demonstrate eligibility via `:audit-doc`).

**Substrate impact: ZERO new primitives.** Two new `:inputs` keys.

### 3.6 Bottom line — substrate posture

**Zero schema changes** required. BR investment income fits the
shipped shape with:

- 1 new ADR-101 `:parameter` (the JCP rate cliff).
- 1 IRPFM `:provision`-style record in the provider (the floor +
  credit-against-ordinary logic; stays record-shaped in Phase C2
  per note 102 §10).
- 6 new `:inputs` map shapes: `:br-dividend-per-payer-per-month`,
  `:br-renda-fixa`, `:br-ordinary-irpf-paid`, `:br-foreign-dividend-
  tax-credit`, `:br-fii-distribution`, `:br-jcp-summary`.
- 1 new `:audit-doc/category` keyword (`:br-dividend-approval-
  resolution`) for the 2025-grandfather check.

All open-vocabulary extensions in `kontor-l10n-br`. Kernel
untouched.

---

## §4. Concrete data gaps

### Gap A — Per-payer per-month aggregation

The R$ 50k/month-per-payer trigger requires `(payer × recipient ×
month)` aggregation across the dividend ledger. The substrate's
`book.declare-dividend!` writes one transaction per declaration
event; multiple declarations from the same payer in the same month
are separate transactions.

**Resolution**: provider-side `kontor.report/marginalize` over the
dividend account postings keyed by `:transaction/effective-date`
(truncated to month) and `:posting/partner` (the payer entity on
the recipient's books or the recipient on the payer's books). The
classifier is straightforward; no schema gap.

The provider's docstring must spell out that it expects dividend
distributions to use the `book.declare-dividend!` verb (or its
`*-tx-data` builder) — ad-hoc dividend postings that bypass the
verb may not carry `:transaction/partner` correctly, causing the
classifier to miss them.

### Gap B — 2025-grandfather AGM approval date

The transition needs the AGM-approval date for each distribution.
The substrate has no native field; recommend the convention:

- `:audit-doc/category :br-dividend-approval-resolution`,
- `:audit-doc/event-date <AGM date>`,
- `:transaction/audit-doc` references the resolution document.

The provider walks `:audit-doc` on each candidate dividend
transaction; if a 2025-or-earlier approval-date is present, it
applies the grandfather (no IRRF, no IRPFM inclusion).

**Substrate impact: ZERO** (uses existing `:audit-doc`).

### Gap C — Cross-provider coupling for IRPFM credit

The IRPFM credit-against-ordinary-IRPF needs the ordinary IRPF
liability. Two consumer-side options (see §3.3): two-pass within
one provider call, or wire the IRPF provider first and pass output
as `:inputs`. Recommendation: ship the second; the first is a
v2 ergonomic improvement.

**Substrate impact: ZERO.**

### Gap D — Cross-border treaty override

Treaty-based rate caps below 10 % are rare for BR (most treaties
allow ≥ 10 %), but a few legacy treaties (AT, the historical
JP position) could trigger refund claims. The provider should not
opine on treaty positions; consumers needing treaty-based reduced
withholding handle it as a `:line-item` override on the recipient's
side.

**Substrate impact: ZERO** (consumer-side override per
`:adjustment-items` shape per note 105).

### Bottom line — schema posture

**Zero schema changes** required at any layer. Pillars 1-4 fit the
shipped shape with open-vocabulary extensions in `kontor-l10n-br`
and 1 new ADR-101 `:parameter`.

---

## §5. `br-investment-income-provider` sketch

### 5.1 Component count

The provider returns ONE `TaxReturnFacts` per assessed entity per
period with up to **four components**, one per pillar that triggered:

```clojure
{:kind :investment-income-tax :authority :br-rfb
 :composed-of [:br-dividend-irrf]   ;; Pillar 1 — R$50k/month + cross-border
 :base ...                            ;; sum of triggering distributions
 :schedule (ts/flat 0.10M)            ;; 10 % flat
 :line-items [:per-payer-aggregate :grandfather-applied :treaty-override]}

{:kind :investment-income-tax :authority :br-rfb
 :composed-of [:br-irpfm]            ;; Pillar 2 — high-earner floor
 :base ...                            ;; full annual base after credits
 :schedule :irpfm-ramp                ;; linear 0%-10% across R$600k-1.2M
 :line-items [:floor-vs-ordinary :credit-irpf :credit-irrf-dividend
              :credit-irrf-jcp :credit-foreign-tax]}

{:kind :investment-income-tax :authority :br-rfb
 :composed-of [:br-jcp-irrf]         ;; Pillar 3 — 17.5 % from 2026
 :base ...                            ;; JCP distributions in period
 :schedule (ts/flat 0.175M)           ;; bitemporally — 15% pre-2026
 :line-items [:per-jcp-deliberation-date]}

{:kind :investment-income-tax :authority :br-rfb
 :composed-of [:br-renda-fixa]       ;; Pillar 4 — regressive table
 :base ...                            ;; bucketed gains by holding period
 :schedule :br-regressiva-by-bucket   ;; bracket-by-bucket
 :line-items [:per-bucket-detail]}
```

FII distributions are **exempt** and produce **no component** —
provider emits an informational `:exemption-applied` line for audit.

### 5.2 Schedule algebra

- Pillar 1 (dividend IRRF): `(ts/flat 0.10M)`. The trigger logic
  (R$ 50k/month) is **classifier-side**, not schedule-side; the
  schedule only fires on the already-included base.
- Pillar 2 (IRPFM): a **piecewise-linear ramp** —
  `(ts/bracket [[600000M 0.0M] [600000M :ramp-0-to-10pct] [nil
  0.10M]])` won't capture the ramp; needs the **formula** kind
  (`(ts/formula ...)`) per note 105. The provider supplies a
  pure-function that returns the effective rate as `max(0, min(0.10,
  (income - 600000) / 600000 × 0.10))`.
- Pillar 3 (JCP): `(ts/flat <rate>)` where `<rate>` is the
  bitemporal lookup from ADR-101 `:br/jcp-irrf-rate`.
- Pillar 4 (renda fixa): four separate `(ts/flat)` per bucket; the
  base of each is the gain in that bucket.

The piecewise-linear IRPFM ramp may surface a **substrate gap**:
the existing `(ts/bracket ...)` is step-wise, not piecewise-linear.
Two options:
1. Use `(ts/formula ...)` to compute the rate × income directly.
2. Approximate the ramp with N steps (e.g., 100 brackets, each
   R$ 6k wide). Both are pure-arithmetic; option 1 is cleaner.

**Recommendation**: option 1, via `(ts/formula ...)`. No new
substrate primitive needed; the IRPFM is the **first** kontor
provider to use it for a rate-of-base computation. Note 105's
adjustment layer handles surtax-on-the-rate but the ramp itself is
schedule. Verify the `(ts/formula ...)` kind accepts this shape in
the implementation phase; if not, extending `kontor.tax-schedule` to
support `:piecewise-linear` is a minor (substrate-internal) add.

### 5.3 IRPFM credit-against-ordinary-IRPF

The credit logic is:

```clojure
(defn irpfm-payable
  [base ordinary-irpf irrf-dividend irrf-jcp foreign-credit]
  (let [floor-rate (irpfm-effective-rate base)
        floor      (m/* base floor-rate)
        credits    (m/+ ordinary-irpf irrf-dividend irrf-jcp foreign-credit)]
    (m/max 0M (m/- floor credits))))
```

The provider returns the IRPFM component with `:adjustment-items`
encoding the credits per note 105's signed/base-aware shape (each
credit is a `:credit` adjustment, ordered by category).

### 5.4 Authority and emission

All pillars file to **Receita Federal** (`:br-rfb`):
- **Pillar 1 IRRF**: DARF (specific code; currently DARF 0561 for
  rendimentos do trabalho, **new code** to be assigned by RFB
  Instrução Normativa pending publication for dividend IRRF — likely
  in early 2026).
- **Pillar 2 IRPFM**: due with the **DAA** (Declaração de Ajuste
  Anual) by 30 April of the year following.
- **Pillar 3 JCP IRRF**: DARF 5706.
- **Pillar 4 Renda fixa**: DARF 8053 (most cases) or per-product
  code; FII distributions: no IRRF (exempt for PF).

Annual reconciliation lands on the **DAA** for the PF; for the PJ
payer's perspective, on **DCTFWeb** (the monthly federal tax-
return-of-record).

A v2 `kontor-l10n-br-investment-income-emit` extension can
synthesise the DAA's "Rendimentos sujeitos à tributação exclusiva"
section + the IRPFM schedule. v1 ships the computation only.

### 5.5 Substrate stress this provider surfaces

- **`(ts/formula ...)` for IRPFM**: first provider to use it for a
  piecewise-linear rate. Surfaces whether the kind's call signature
  fits a parametric-on-base schedule. If extension needed, it's
  modest.
- **Cross-provider coupling** (IRPFM ↔ IRPF): the second instance
  after MX art. 120 (note 132 §5.3). The pattern is establishing
  itself; the `kontor-l10n-br` documentation should articulate
  the recommended wiring order: `br-period-tax-provider` (for
  IRPF) **first**, then `br-investment-income-provider` (for
  Pillar 2 IRPFM), reading `:inputs :br-ordinary-irpf-paid` from
  the first.
- **Per-payer aggregation via `kontor.report/marginalize`**: the
  R$ 50k/month/payer trigger is the first investment-income
  provider to use marginalize-as-classifier. Pattern: define a
  classifier function that the provider calls per-(payer,
  recipient, month) cell; trigger on cells > threshold.
- **Bitemporal rate cliff (JCP)**: ADR-101 + ADR-048 carry it
  natively. Provider reads parameter at the **deliberation date**
  (per PLP 128/2025 §2 transition). One audit-doc category for the
  AGM resolution date.
- **`:audit-doc/category :br-dividend-approval-resolution`**:
  open-vocabulary extension; no kernel change.

Total: **0 kernel changes**, **0 disposal-companion changes**, **1
provider** (`br-investment-income-provider.clj`), **1 statute file**
(`br-investment-income-statute.clj`), **1 test file**. Within the
conservative posture of note 107.

---

## §6. ADR-101 statute-as-data — what BR investment-income writes

The provider stays record-shaped (Phase 2 / C2 per note 102 §10).
The parameters that DO ride ADR-101:

```clojure
;; The dividend WHT rate (10 %)
{:parameter/code :br/dividend-irrf-rate
 :parameter/jurisdiction :br
 :parameter/concept-iri "https://kontor.dev/concept/dividend-wht-rate"
 :parameter/values
 [{:parameter-value/effective-from #inst "2026-01-01"
   :parameter-value/rate 0.10M}]}

;; The R$ 50k/month per-payer trigger
{:parameter/code :br/dividend-irrf-trigger
 :parameter/values
 [{:parameter-value/effective-from #inst "2026-01-01"
   :parameter-value/amount 50000M
   :parameter-value/currency :BRL}]}

;; The IRPFM ramp boundaries
{:parameter/code :br/irpfm-band-low
 :parameter/values
 [{:parameter-value/effective-from #inst "2026-01-01"
   :parameter-value/amount 600000M}]}
{:parameter/code :br/irpfm-band-high
 :parameter/values
 [{:parameter-value/effective-from #inst "2026-01-01"
   :parameter-value/amount 1200000M}]}
{:parameter/code :br/irpfm-top-rate
 :parameter/values
 [{:parameter-value/effective-from #inst "2026-01-01"
   :parameter-value/rate 0.10M}]}

;; The JCP IRRF rate (the cliff)
{:parameter/code :br/jcp-irrf-rate
 :parameter/values
 [{:parameter-value/effective-from #inst "2005-01-01"
   :parameter-value/rate 0.15M}
  {:parameter-value/effective-from #inst "2026-01-01"
   :parameter-value/rate 0.175M}]}

;; The renda-fixa regressive table — published as four bands
{:parameter/code :br/renda-fixa-table
 :parameter/values
 [{:parameter-value/effective-from #inst "2005-01-01"
   :parameter-value/brackets
   [{:parameter-bracket/upper-days 180 :parameter-bracket/rate 0.225M}
    {:parameter-bracket/upper-days 360 :parameter-bracket/rate 0.20M}
    {:parameter-bracket/upper-days 720 :parameter-bracket/rate 0.175M}
    {:parameter-bracket/upper-days nil :parameter-bracket/rate 0.15M}]}]}
```

Note the **`:parameter-bracket/upper-days`** axis is novel (existing
`:parameter-bracket` is in monetary `:width`). The renda-fixa table
might be cleaner as **four separate flat parameters keyed by
holding-period bucket** to avoid extending `:parameter-bracket`:

```clojure
{:parameter/code :br/renda-fixa-rate-bucket-1   ;; ≤ 180 days
 :parameter/values [{:effective-from #inst "2005-01-01" :rate 0.225M}]}
{:parameter/code :br/renda-fixa-rate-bucket-2   ;; 181-360
 :parameter/values [{:effective-from #inst "2005-01-01" :rate 0.20M}]}
{:parameter/code :br/renda-fixa-rate-bucket-3   ;; 361-720
 :parameter/values [{:effective-from #inst "2005-01-01" :rate 0.175M}]}
{:parameter/code :br/renda-fixa-rate-bucket-4   ;; > 720
 :parameter/values [{:effective-from #inst "2005-01-01" :rate 0.15M}]}
```

**Recommendation**: ship four flat parameters. The provider's
classifier maps holding-days to bucket, looks up the per-bucket
rate. No `:parameter-bracket` extension.

The FII exemption stays in the provider as a **provision-style
record** for Phase 2 (conditions: ≥100 cotistas + ≤10 % stake +
listed). The 2025-grandfather rule is similarly a record (condition
on AGM-approval-date).

---

## §7. Sources

### BR statutory primary

- [Lei 15.270/2025 (planalto)](https://www.planalto.gov.br/ccivil_03/_Ato2023-2026/2025/Lei/L15270.htm)
  — enacted 26 November 2025; art. 6 (resident dividend WHT 10 %
  above R$ 50k/month), art. 7 (non-resident dividend WHT 10 % flat),
  arts. 9-13 (IRPFM minimum tax), art. 15 (transition).
- [Lei 15.270/2025 full text via Câmara legin](https://www2.camara.leg.br/legin/fed/lei/2025/lei-15270-26-novembro-2025-798354-publicacaooriginal-177117-pl.html)
  — secondary mirror.
- [Lei 9.249/1995 art. 10](https://www.planalto.gov.br/ccivil_03/leis/l9249.htm)
  — the 1996 dividend exemption; **preserved by Lei 15.270/2025 for
  PJ-to-PJ distributions** (art. 6 §3).
- [Lei 11.033/2004 art. 3](https://www.planalto.gov.br/ccivil_03/_ato2004-2006/2004/lei/l11033.htm)
  — FII PF-exemption + LCI/LCA exemption; **preserved** by
  Lei 15.270/2025.
- [Lei Complementar 128/2025 (= PLP 128)](https://www.camara.leg.br/noticias/1233924-camara-aprova-projeto-que-reduz-beneficios-fiscais-federais-e-aumenta-tributacao-de-bets-e-fintechs)
  — JCP IRRF 15 % → 17.5 % from 2026-01-01; deliberation-date
  grandfather.
- [Lei 12.973/2014](https://www.planalto.gov.br/ccivil_03/_ato2011-2014/2014/lei/l12973.htm)
  — BR CFC rules (out of scope for this provider but referenced).

### Receita Federal regulatory

- [Receita Federal — Perguntas e Respostas Lei 15.270/2025](https://www.gov.br/receitafederal/pt-br/assuntos/noticias/2025/dezembro/receita-federal-orienta-sobre-os-procedimentos-para-o-recolhimento-do-imposto-de-renda-retido-na-fonte-sobre-lucros-e-dividendos)
  — RFB's December-2025 Q&A on procedures for the new dividend WHT.
- [Receita Federal — Tabela de tributação 2026](https://www.gov.br/receitafederal/pt-br/assuntos/meu-imposto-de-renda/tabelas/2026)
  — current rate tables.
- Likely forthcoming (early-2026): RFB Instrução Normativa specifying
  the new DARF code for dividend IRRF + the DAA schedule changes.
  Not yet published as of this note.

### Commentary / legal alerts

- [Trench Rossi Watanabe — Brazil enacts Law 15,270/2025](https://www.trenchrossi.com/en/legal-alerts/brazil-enacts-law-15270-2025-which-taxes-dividends-and-amend-personal-income-tax-rules/)
  — comprehensive English-language enactment summary.
- [Mayer Brown — Enactment of Law 15,270/2025](https://www.mayerbrown.com/en/insights/publications/2025/12/enactment-of-law-no-15270-2025-which-establishes-dividend-taxation-expands-the-exemption-threshold-and-introduces-a-minimum-tax-on-high-incomes)
  — IRPFM detail.
- [Alvarez & Marsal — Brazil Law 15,270/2025 Dividend WHT Returns](https://www.alvarezandmarsal.com/thought-leadership/tax-news-alert-brazil-law-15-270-2025-dividend-withholding-tax-returns-in-2026)
  — practitioner walkthrough.
- [Mattos Filho — Brazil enacts law on taxation of dividends](https://www.mattosfilho.com.br/en/unico/brazil-taxation-dividends/)
  — domestic-firm summary.
- [Felsberg — Brazilian Law 15,270/2025 transitional rule](https://www.felsberg.com.br/en/law-15270-transitional-rule-dividend-taxation-brazil)
  — the AGM-approval-by-31-Dec-2025 grandfather detail.
- [Felsberg — Brazil's Law 15,270/25 New Taxation 2026](https://www.felsberg.com.br/en/law-15270-taxation-profits-dividends-2026/)
  — full-pillar summary.
- [PwC Brasil — Tributação de dividendos Lei 15.270](https://www.pwc.com.br/pt/thinking-about-taxes/tax-intelligence/2025/tax-intelligence-ed-48-tributacao-de-dividendos.pdf)
  — PwC's edition 48.
- [PwC Brasil — From complexity to execution (Law 15,270)](https://www.pwc.com.br/pt/thinking-about-taxes/tax-intelligence/2025/tax-intelligence-strategy_ed05-Law-15270-25.pdf)
  — implementation guide.
- [Sperling Advogados — New dividend taxation law approved](https://sperling.adv.br/publicacoes/new-law-on-taxation-of-dividends-as-of-2026-is-approved/)
  — domestic-firm summary.
- [Williamfreire — Dividend Remittances Abroad: Law No. 15,270/2025 (PDF)](https://williamfreire.com.br/wp-content/uploads/2025/12/Memo-Law-No-15270-Dividends-Non-Residents.pdf)
  — non-resident leg detail.
- [BDO — Brazil Withholding Tax Reintroduced on Dividends to Nonresidents](https://www.bdo.global/en-gb/insights/tax/world-wide-tax/brazil-withholding-tax-reintroduced-on-dividends-paid-to-nonresidents)
  — non-resident leg.
- [EY — Brazilian tax authority issues guidance on new dividend WHT](https://taxnews.ey.com/news/2025-2550-brazilian-tax-authority-issues-guidance-on-new-withholding-tax-on-dividends-paid-to-nonresidents)
  — EY follow-up on RFB guidance.
- [Cescon Barrieu — Congresso aprova PLP 128/2025 (JCP changes)](https://cesconbarrieu.com.br/congresso-aprova-plp-no-128-2025-corte-linear-de-beneficios-fiscais-e-mudancas-relevantes-em-jcp-bets-e-fintechs/)
  — PLP 128 detail.
- [Maia Advogados — Mudanças no JCP — PLP 128/2025](https://lfmaia.com.br/mudancas-nos-juros-sobre-capital-proprio-jcp-plp-128-2025/)
  — JCP 17.5 % rate detail.
- [PwC Brasil — Tax intelligence ed. 41 PLP 128 (PDF)](https://www.pwc.com.br/pt/thinking-about-taxes/tax-intelligence/2025/tax-intelligence-express-ed-41.pdf)
  — JCP + bets/fintechs.
- [Jota — Câmara aprova PLP 128 (JCP, bets, fintechs)](https://www.jota.info/tributos/camara-aprova-plp-128-que-reduz-beneficios-fiscais-e-taxa-fintechs-bets-e-jcp).
- [Senado — Senado aprova PLP 128/2025](https://www12.senado.leg.br/noticias/materias/2025/12/17/senado-aprova-reducao-de-incentivos-fiscais-e-maior-tributacao-para-bets-e-fintechs).
- [Save Educação — Lei 15.270 e o Fim da Isenção](https://saveeducacao.com.br/tributacao-lucros-lei-15270-2026/).
- [Escola Superior — Lei 15.270/2025: O Fim da Isenção sobre Dividendos](https://escolasuperioresn.com.br/lei-15270-2025-tributacao-dividendos-irpf/).
- [Contplan — Lei 15.270/2025 — IRPF Mínimo](https://www.contplan.com.br/lei-15270-2025-distribuicao-de-lucros-irpf-minimo/).
- [Demarest — Receita Federal Perguntas Lei 15.270](https://www.demarest.com.br/receita-federal-divulga-perguntas-e-respostas-sobre-a-nova-tributacao-de-dividendos-e-altas-rendas/).
- [Contábeis — Fim da isenção de dividendos](https://www.contabeis.com.br/noticias/74712/fim-da-isencao-de-dividendos-o-que-muda-para-empresas-e-socios/).
- [Lacerda Diniz — PLP 128/2025](https://lacerdadiniz.com.br/plp-128-2025-reducao-beneficios-fiscais/).
- [Ciari Moreira — Nova tributação dividendos e proteção residentes exterior](https://ciarimoreira.com.br/a-nova-tributacao-dos-dividendos-e-a-protecao-constitucional-dos-socios-residentes-no-exterior/)
  — treaty-position commentary.
- [International Tax Review — Brazil approves dividend taxation](https://www.internationaltaxreview.com/article/2fp4qg1922wi7yb6u8kjk/sponsored/brazil-approves-dividend-taxation-and-expands-income-tax-exemptions).

### Renda fixa + FII

- [XP Investimentos — Tabela regressiva](https://conteudos.xpi.com.br/aprenda-a-investir/relatorios/tabela-regressiva/)
  — current 22.5 / 20 / 17.5 / 15 % bucket table.
- [Rico — Renda Fixa Imposto de Renda 2026](https://riconnect.rico.com.vc/blog/renda-fixa-imposto-de-renda/)
  — 2026 confirmation, table preserved.
- [Brazil Economy — Fundos Imobiliários ganham atratividade 2026](https://brazileconomy.com.br/financas/2026/01/fundos-imobiliarios-e-fi-infra-ganham-atratividade-com-nova-lei-do-imposto-de-renda/)
  — FII exemption preserved + post-reform attractiveness shift.

### kontor substrate cited

- `src/kontor/book.clj:296-330` — `declare-dividend!` +
  `distribute-dividend!` verbs (ADR-095).
- `src/kontor/period_tax_provider.clj` —
  `PeriodTaxProvider` + `TaxReturnFacts`; the IRPFM and dividend-
  IRRF providers ride this.
- `src/kontor/tax_schedule.clj` — `:flat` (dividend IRRF, JCP IRRF),
  `:formula` (IRPFM ramp), `:bracket` (potential renda fixa
  alternative if bucketing-via-flats isn't acceptable).
- `src/kontor/personal_income_tax.clj:71-83` — adjustment-layer
  pattern; IRPFM credits-against-ordinary-IRPF ride as `:credit`
  adjustment items per note 105.
- `src/kontor/statute.clj` — `apply-provisions` (ADR-101); the
  parameters in §6 ride this evaluator.
- `modules/l10n-br/src/kontor/l10n_br/period_tax_provider.clj` —
  the existing BR IRPF provider; IRPFM couples to it via
  `:inputs :br-ordinary-irpf-paid`.
- `modules/l10n-br/src/kontor/l10n_br/cgt_provider.clj` — the BR CGT
  provider; sibling to this; same posture on per-period folds.
- `doc/research/130-br-cgt-fit.md` — sibling BR note; same
  posture on jurisdiction-namespaced parameters + per-period
  aggregation patterns. **Note 130 §1.5 must be updated** to
  reflect the 2025-enacted state of Lei 15.270/2025 (the IRPFM
  carve-out for CGT is correct; the broader investment-income
  picture changed).
- `doc/research/131-in-cgt-fit.md` §5.2 — provider-coupling pattern
  (CGT slab-rate fold to PIT); IRPFM-to-IRPF here reuses.
- `doc/research/132-mx-cgt-fit.md` §5.3 — provider-coupling for art.
  120 averaging; IRPFM here reuses the same shape.
- `doc/research/107-phase-3-incorporation-and-disposal.md` — the
  disposal substrate (relevant for FII redemption gains that route
  through the renda variável B3 lane).

---

End of note 155.
