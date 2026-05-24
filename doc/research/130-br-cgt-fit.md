---
date: 2026-05-24
title: 130 — BR capital-gains tax (ganho de capital) — substrate fit for Phase 3
audience: maintainer + the Phase 3 `br-cgt-provider` implementation agent
status: research-before for the BR CGT companion of `kontor-disposal` (ADR-102) + the future `br-cgt-provider`; no code
---

# 130 — BR capital-gains tax: substrate fit for Phase 3

Capital-gains tax in Brazil splits sharply across **three orthogonal axes**:

1. **Real-asset disposals** by individuals (ganho de capital — apurado em
   GCAP, paid DARF code 4600) → **progressive flat-rate ladder
   15 / 17.5 / 20 / 22.5 %** on the gain itself.
2. **Variable-income operations** on B3 (renda variável — apurado mensal,
   paid DARF code 6015/8468) → **15 % swing-trade / 20 % day-trade**
   with a per-month aggregate isenção and broker-side IRRF
   (`dedo-duro`).
3. **Pessoa Jurídica** disposals → integrated into IRPJ + CSLL at the
   **34 % nominal** combined rate (15 % IRPJ + 10 % adicional + 9 %
   CSLL); lucro presumido has special calculation rules.

There is no holding-period split in BR (no LTCG/STCG dichotomy), no
inflation indexation (since 1996; gain is pure nominal proceeds minus
nominal acquisition cost), and **strong compartmentalisation between
the three lanes**. Phase 3 ships a BR CGT provider that fans these into
**three components** in one `TaxReturnFacts`, plus the GL-fold seam for
the PJ case (where the gain enters CIT base via book profit).

This note (a) summarises the BR CGT regime per lane, (b) walks two
worked examples, (c) assesses fit against the shipped `kontor-disposal`
schema, (d) names the data gaps, (e) sketches the `br-cgt-provider`,
and (f) cites sources.

---

## §1. The BR CGT regime — three lanes

### 1.1 Lane A — Pessoa Física, ganho de capital on real assets

Source: [Receita Federal — Alíquotas / Ganhos de Capital](https://www.gov.br/receitafederal/pt-br/assuntos/meu-imposto-de-renda/pagamento/ganhos-de-capital/aliquotas);
[Lei 13.259/2016 art. 21 (replacing prior Lei 8.981/95)](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2016/lei/l13259.htm);
[IN-RFB-1500/2014](https://normas.receita.fazenda.gov.br/sijut2consulta/link.action?visao=anotado&idAto=57305) (current consolidated PF rules).

The **progressive ladder** for individuals' gains on bens e direitos
(movables, immovables, foreign-currency cash, participations in non-
listed entities, BDRs, etc.):

| Bracket | Rate    | Cumulative gain in the calendar year |
|---------|---------|---------------------------------------|
| I       | 15 %    | up to R$ 5,000,000                    |
| II      | 17.5 %  | > R$ 5M and ≤ R$ 10M                  |
| III     | 20 %    | > R$ 10M and ≤ R$ 30M                 |
| IV      | 22.5 %  | > R$ 30M                              |

The ladder is **per-event** and the brackets stack: a R$ 6M gain on a
single disposal pays 15 % on the first R$ 5M and 17.5 % on the next
R$ 1M (= R$ 750,000 + R$ 175,000 = R$ 925,000). Distinct events in the
same calendar year aggregate at the *taxpayer* level for the bracket
walk (per IN-RFB-1500/2014 art. 138).

**Tax basis**: gross sale price MINUS acquisition cost (no inflation
indexation since 01-01-1996 — Lei 9.250/95 art. 17). Pre-1996
acquisitions use the **escalating "redutor" table** (% reduction on
the gain, ranging from 100 % for pre-1969 acquisitions down to 0 % for
post-1988); IN-RFB-1500/2014 art. 139.

**Payment**: DARF code 4600, due by the **last business day of the
month following the disposal**. The calculation is done in the
**GCAP** program (Programa de Apuração dos Ganhos de Capital), which
emits the DARF.

#### 1.1.1 PF isenções (exemptions)

- **Pequeno valor (small disposal)** — Lei 9.250/95 art. 22 + IN-RFB-
  1500/2014 art. 134: gain on disposals whose **monthly aggregate
  price** is ≤ **R$ 35,000** (R$ 20,000 for shares on the over-the-
  counter / negotiated outside exchange) is fully exempt. The
  threshold is per-month and per-asset-class, applied to the **sale
  price** (not the gain). If the seller disposes of multiple bens in
  the same month, the prices aggregate against the R$ 35k ceiling —
  see [VLMA tax bulletin](https://vlma.com.br/publicacoes/tributacao-dos-ganhos-de-capital-da-pessoa-fisica)
  and [Portal Tributário](https://www.portaltributario.com.br/guia/isencao_ganho_capital_pf.html).
- **Residence reinvestment (Lei 11.196/2005 art. 39)** — gain on the
  sale of a residential property is **fully exempt** if the seller
  applies the entire proceeds within **180 days** toward the
  acquisition (or financing-payoff) of another residential property in
  Brazil. **Once every 5 years.** Construction does NOT qualify
  (Receita's position; see [Chambarelli Advogados](https://chambarelli.com.br/isencao-de-ganho-de-capital-nao-aplicacao-na-construcao-de-casa/)).
  Partial application → proportional exemption.
- **Sole-residence ≤ R$ 440k** — Lei 9.250/95 art. 23: a one-shot
  exemption if the taxpayer owns only this one residential property
  AND the sale price is ≤ R$ 440,000 AND no other such sale in the
  last 5 years.
- **Pre-1969 acquisitions** — 100 % redutor (effectively exempt).
- **Inheritance/donation** — Lei 9.532/97: the heir/donee inherits the
  **declared cost** of the deceased/donor; no gain is realised at
  transmission UNLESS the executor elects to step up to market value
  (which itself becomes a 15 % taxable gain at the estate level).

#### 1.1.2 PF rollover (no general regime)

BR has **no general CGT rollover regime** like US §1031 or DE §6b.
The only "rollovers" are the residence reinvestment exemption (above)
and the inheritance step-up election (above). Substrate impact:
`:disposal/rollover-into-asset` is used **narrowly** — only when
`:exemption-claimed` includes `:br-art-39-residence-reinvest`.

### 1.2 Lane B — Pessoa Física, renda variável on B3

Source: [B3 — Tributação Pessoa Física](https://atendimento.b3.com.br/sys_attachment.do?sys_id=9f2f5d101b312d106b982fc13b4bcb67);
[XP — Day trade no IR](https://conteudos.xpi.com.br/aprenda-a-investir/relatorios/day-trade-no-imposto-de-renda/);
Lei 11.033/2004 art. 2 (the 15 / 20 % split);
IN-RFB-1585/2015 art. 56-65 (current consolidated B3-PF rules).

| Operation kind | Rate  | IRRF (broker-withheld) | Isenção                                    |
|----------------|-------|------------------------|--------------------------------------------|
| Swing-trade (comum) on equities (ações à vista) | 15 % | 0.005 % on price (the "dedo-duro") | Yes — total monthly sale price ≤ R$ 20,000 (per Lei 11.033 art. 3 I) |
| Day-trade (same-day open + close) on equities | 20 % | 1 % on monthly gain        | **No** isenção                              |
| Futures / opções / mini-índice (BM&F) | 15 % swing / 20 % day | 0.005 % / 1 %                   | No (the R$ 20k applies only to ações)       |
| FIIs (real-estate funds) | 20 % flat            | 0.005 %                                | **No** isenção even for small sales         |
| ETFs (índice, exceto FIIs)| 15 % swing / 20 % day | 0.005 % / 1 %                  | **No** (the R$ 20k ações exemption does not extend) |
| BDRs (depositary receipts) | 15 % swing / 20 % day | 0.005 % / 1 %                | **No** (BDRs are not "ações" for the exemption) |

The IRRF ("dedo-duro" — finger-pointer) is a **prepayment**: it is
deducted by the broker at trade time, reported to Receita, and the
taxpayer offsets it against the monthly DARF (code 6015 for ações,
8468 for FII, etc.).

#### 1.2.1 Loss compensation — strict compartments

[Receita Federal — IN-RFB-1585/2015 art. 64](https://normas.receita.fazenda.gov.br/sijut2consulta/link.action?visao=anotado&idAto=70004):
losses in renda variável compensate only **profits of the same
nature**. Two watertight buckets:

- **Common (swing-trade)** losses ↔ common profits only.
- **Day-trade** losses ↔ day-trade profits only.

Carry-forward is **indefinite within bucket** — no time limit (per
[nsctotal](https://www.nsctotal.com.br/noticias/compensacao-prejuizo-bolsa-regras-calculo-imposto-de-renda-2026)
+ IN-RFB-1585/2015 art. 64 §2). But the loss must be **reported every
year** on the Imposto de Renda annual return (Renda Variável form,
"Apuração de Resultados") — failure to report breaks the chain.

FIIs have their own watertight bucket (FII losses → FII profits only,
indefinite carry-forward).

### 1.3 Lane C — Pessoa Jurídica

Source: [Portal Tributário — Lucro Real](https://www.portaltributario.com.br/guia/lucro_real.html);
[Contabilizei — Lucro Presumido](https://www.contabilizei.com.br/contabilidade-online/lucro-real/);
[BPC Partners — Brazil Tax Updates 2025](https://bpc-partners.com/news/brazil-tax-updates-lucro-presumido-import-duties-non-profits-more/).

PJ disposals fold into **regular CIT** (IRPJ + CSLL), with three
shapes depending on regime:

| Regime           | Gain treatment                                                                 | Combined nominal |
|------------------|--------------------------------------------------------------------------------|------------------|
| **Lucro Real**   | Gain = proceeds − NBV (book value, gross of accumulated depreciation). Folds into the period's lucro real, taxed at IRPJ 15 % + 10 % adicional (on profits > R$ 240k/yr) + CSLL 9 % = **34 % combined nominal**. | 34 %         |
| **Lucro Presumido** | Gain (proceeds − NBV) is **added on top** of presumed profit (not multiplied by the presunção %), taxed at same 15 + 10 + 9 stack = **34 %**. Per [Contábeis — apurar ganho no Lucro Presumido](https://www.contabeis.com.br/noticias/71820/como-apurar-o-ganho-de-capital-no-lucro-presumido/). | 34 %         |
| **Simples Nacional** | Capital gains are **NOT** under Simples — pay 15 / 17.5 / 20 / 22.5 % via DARF 0507 (the PF ladder applied at the PJ level — `Lei Complementar 123/2006` art. 13 § 1, II, with `IN-RFB-1700/2017` art. 314).     | 15-22.5 %    |

#### 1.3.1 PJ rollover — limited

- **Bem do ativo permanente substituído** (asset replacement) —
  historic but very narrow. RIR/2018 art. 421-422 allows deferral
  *only* for specific industries (insurance reserves, fundos sociais);
  there is no general §1031-style rollover.
- **Reorganização societária** (incorporação, fusão, cisão) — Lei
  9.249/95 art. 21: the absorbed company's assets transfer at book
  value to the acquirer; **no gain at the entity level**. Capital
  receipts in restructuring are non-taxable to the contributing
  shareholders when the contribution is "ao valor declarado" (book
  value); a step-up election triggers 15-22.5 % at the contributor.

### 1.4 What does NOT exist in BR CGT

To preempt the agent's hunt for parallels:

- **No LTCG / STCG distinction**. Holding period is **immaterial** for
  the rate — both a 1-day and a 30-year hold pay the same 15 / 17.5 / …
  ladder on the gain.
- **No inflation indexation** since 1996. Pre-1996 acquisitions use
  the redutor table; post-1996 gains are purely nominal. (Lei 9.249/95
  art. 4 ended monetary correction.)
- **No participation exemption** (no DE §8b, no UK SSE). All PJ-on-PJ
  gains are taxable at 34 %.
- **No principal-residence permanent exclusion** like US §121. The two
  PF reliefs (art. 39 reinvest, art. 23 sole-residence ≤ R$ 440k) are
  **narrower and electable**.
- **No NIIT-style surtax** on PF investment income.
- **No territorial / foreign-source exclusion**. Foreign gains are
  taxed at the SAME ladder (15-22.5 %), with FX conversion at the
  PTAX rate on the disposal date (IN-RFB-1500/2014 art. 137).

### 1.5 PL 1087/2025 (now Lei 15.270/2025) — the "high earner minimum tax"

[Lei 15.270/2025 (PL 1087)](https://www.planalto.gov.br/ccivil_03/_Ato2023-2026/2025/Lei/L15270.htm)
+ [Mattos Filho](https://www.mattosfilho.com.br/unico/camara-aprova-pl-1087/)
introduces a **10 % minimum income tax (IRPFM)** on individuals
receiving > R$ 50,000 / month in profits + dividends from a single
entity, effective 2026-01-01. **Capital gains are NOT in the IRPFM
base** (the law explicitly carves out gains taxed under the PF ladder
of §1.1). Substrate impact: zero on the CGT provider; this is a
**separate** minimum-tax provider that consumes the dividend ledger,
not the disposal log.

---

## §2. Worked examples

### Example A — Pessoa Física, real-estate sale with reinvestment

A PF taxpayer sells a residential apartment in São Paulo on 2026-03-15
for R$ 1,200,000 (acquired 2018-06 for R$ 800,000; no improvements).

- Gain = 1,200,000 − 800,000 = **R$ 400,000**.
- The taxpayer applies R$ 900,000 of the proceeds toward a new
  residential apartment closing on 2026-07-10 (within 180 days).
- The applied fraction is 900,000 / 1,200,000 = 75 % → 75 % of the gain
  is exempt under Lei 11.196 art. 39.
- Taxable gain = R$ 400,000 × 25 % = **R$ 100,000**.
- Tax (Bracket I, 15 %) = **R$ 15,000**, DARF 4600, due 2026-04-30.

Substrate trace: one `:disposal` with `:disposal/kind :sale`,
`:disposal/subject-kind :real-estate-private`, `:asset-class
:br-residencial`, `:residence? true`, `:exemption-claimed
#{:br-art-39-residence-reinvest}`, `:rollover-into-asset <new
apartment asset>`, `:rollover-amount 900000M`, `:rollover-deadline
2026-09-11` (180 days from sale). The BR CGT provider reads this,
computes the proportional exemption, applies the ladder to the
remaining R$ 100k.

### Example B — Pessoa Física, mixed B3 month

A PF taxpayer in May 2026:

- Swing-trade gains on ações: R$ 8,000 (total May sale price
  R$ 18,000 — under the R$ 20k isenção).
- Swing-trade gain on FII: R$ 5,000 (sale price R$ 50,000; FII has
  **no** R$ 20k isenção).
- Day-trade gain on mini-índice: R$ 12,000.
- IRRF withheld by broker:
  - Swing ações dedo-duro: 18,000 × 0.005 % = R$ 0.90 (no DARF since
    isenção applies).
  - FII dedo-duro: 50,000 × 0.005 % = R$ 2.50.
  - Day-trade dedo-duro: 12,000 × 1 % = R$ 120.

Monthly calculation:

| Lane                | Gain      | Rate | Bruto    | IRRF offset | DARF (6015/8468) |
|---------------------|-----------|------|----------|-------------|-------------------|
| Swing ações         | exempt    | —    | —        | —           | —                |
| Swing FII           | R$ 5,000  | 20 % | R$ 1,000 | R$ 2.50     | R$ 997.50 (8468) |
| Day-trade futuros   | R$ 12,000 | 20 % | R$ 2,400 | R$ 120      | R$ 2,280 (6015)  |

Due 2026-06-30 (last business day of the month after the gain).

Substrate trace: three `:disposal`s — `:asset-class
:br-listed-equity` (swing), `:asset-class :br-fii` (FII swing),
`:asset-class :br-day-trade-derivative` (day-trade); each carries
`:disposal/exemption-claimed` flags + the `:loss-bucket` lane tag.

---

## §3. `:disposal` schema fit assessment

The shipped `kontor-disposal` schema (loaded at
`modules/disposal/src/kontor/disposal/schema.clj`) was designed
generically against US/DE/UK/JP. BR's three-lane structure stresses it
in three places:

### 3.1 `:holding-period` — irrelevant for BR

BR has **no** ST/LT rate split. The shipped enum (`:short` / `:long` /
`:n-a`) accommodates this: a BR disposal's classifier returns **always
`:n-a`**. The BR provider ignores the field; the law-as-it-stood
snapshot lands `:n-a` in the audit chain.

### 3.2 `:asset-class` — BR needs its own vocabulary

BR's lane-routing reads off the asset class, not the subject kind.
Suggested BR-namespaced values for `:disposal/asset-class`:

| Value                        | Meaning                                                    |
|------------------------------|------------------------------------------------------------|
| `:br-real-estate-residencial`| Residencial (eligible for art. 39 reinvest, art. 23 ≤R$440k)|
| `:br-real-estate-comercial`  | Non-residential                                            |
| `:br-real-estate-rural`      | Rural — special ITR/ITBI interactions                      |
| `:br-listed-equity-swing`    | Ação à vista, swing trade (15 %, R$20k/mês isento)         |
| `:br-listed-equity-day`      | Ação à vista, day trade (20 %, no isento)                  |
| `:br-fii`                    | Fundo de investimento imobiliário (20 % swing/day, no isento)|
| `:br-etf`                    | ETF (15/20 %, no isento)                                   |
| `:br-bdr`                    | BDR (15/20 %, no isento)                                   |
| `:br-derivative-future`      | Futuros, opções, mini-índice                               |
| `:br-unlisted-share`         | Sociedade limitada / S.A. fechada                          |
| `:br-foreign-currency-cash`  | Moeda estrangeira em espécie                               |
| `:br-foreign-asset`          | Bem ou direito no exterior                                 |
| `:br-other-movable`          | Demais bens móveis                                         |

These extend `:disposal/asset-class` (already in the schema; the docstring
mentions "open vocabulary, conventionally namespaced by jurisdiction").
No schema change required.

### 3.3 `:loss-bucket` — three watertight BR lanes

Suggested BR `:loss-bucket` values (one per compartment):

- `:br-pf-real-asset` (ladder lane — losses on this rarely carry; per
  IN-RFB-1500/2014 art. 142, **PF losses on real assets do NOT
  carry** at all, only intra-lane within the same month if a multi-
  disposal aggregate yields a net loss).
- `:br-pf-swing` (renda variável common — indefinite carry within bucket).
- `:br-pf-day-trade` (day-trade — indefinite carry within bucket).
- `:br-pf-fii` (FII bucket — indefinite carry within bucket).
- `:br-pj-irpj-csll` (PJ lucro real/presumido — capital losses fold
  into the period's lucro real, subject to the **30 % cap** on prior-
  year loss utilisation per Lei 9.065/95 art. 42 — but this is a
  **CIT-provider** concern, not a CGT-provider concern; the CGT
  provider just reports the gain; the CIT provider runs the cap).

### 3.4 `:exemption-claimed` — BR vocabulary

Suggested BR keywords for `:disposal/exemption-claimed`:

- `:br-art-39-residence-reinvest` (Lei 11.196 art. 39 — 180-day
  residence reinvestment; pairs with `:disposal/rollover-into-asset`,
  `:rollover-amount`, `:rollover-deadline`).
- `:br-art-23-sole-residence` (Lei 9.250 art. 23 — sole-residence
  ≤ R$ 440k, once per 5 years).
- `:br-art-22-pequeno-valor` (Lei 9.250 art. 22 — R$ 35k/month
  small-disposal aggregate; see gap §4 below).
- `:br-r$20k-bolsa` (Lei 11.033 art. 3 — R$ 20k/month ações swing
  isento).
- `:br-pre-1969-redutor` (100 % redutor for pre-1969 acquisitions).
- `:br-redutor-table` (escalating reduction for 1969-1988 acquisitions —
  paired with a numeric reducão %).
- `:br-inheritance-no-step-up` (Lei 9.532/97 — heir inherits cost).

### 3.5 `:elective-regime` — BR vocabulary

Suggested BR keywords for `:disposal/elective-regime`:

- `:br-inheritance-step-up` (executor elects market-value step-up at
  estate level; gain taxable at 15 % to estate).
- `:br-simples-darf-0507` (PJ-Simples taxpayer pays via DARF 0507 at
  PF ladder).
- `:br-lucro-real` / `:br-lucro-presumido` (PJ regime declaration for
  the GL-fold side).

### 3.6 `:rollover-into-asset` — narrow BR use

The shipped rollover triple
(`:rollover-into-asset` + `:rollover-amount` + `:rollover-deadline`)
fits the art. 39 residence-reinvestment exemption directly. The
`:rollover-deadline` is exactly 180 days after the sale; the BR
companion's recorder helper computes it.

No schema change.

---

## §4. Concrete data gaps

The shipped `kontor-disposal` schema covers BR's structural shape; two
**substantive gaps** and one **soft gap** remain:

### Gap A — R$ 35k/month aggregate exemption (Lei 9.250 art. 22)

The pequeno-valor exemption applies to the **monthly aggregate sale
price** across all bens e direitos of the same kind (ações vs.
demais). One disposal's eligibility depends on **other disposals in
the same month**. The shipped schema is **event-shaped** — each
`:disposal` is one event; it does not natively track "sum across the
month against a ceiling."

**Resolution**: this is a **provider-side fold**, not a schema gap.
The BR provider reads the period's `:disposal`s, groups by month +
`:asset-class` bucket (e.g. all `:br-real-estate-*` together;
`:br-listed-equity-swing` separately), sums the **sale prices** per
group, and applies the R$ 35k (or R$ 20k for swing ações) cap **per
group per month**. If the group's monthly aggregate is ≤ cap, every
disposal in the group is exempt; otherwise the entire group is
taxable (NOT just the excess — the IN-RFB-1585/2015 art. 56 § 2
position).

**Substrate impact**: zero. The provider does the aggregation. The
audit-doc trail (per-disposal) suffices for Receita.

### Gap B — IRRF (dedo-duro) prepayment offset

The B3 lane B has broker-side IRRF (0.005 % swing, 1 % day) that is a
**prepayment** offset against the DARF. The shipped schema has no
field for "IRRF withheld at trade time." Two options:

1. **Store on the disposal**: add `:disposal/irrf-withheld-amount` +
   `:disposal/irrf-withheld-commodity` (BR-specific). Provider reads
   it and subtracts from the gross tax to compute the DARF.
2. **Store on a per-period `:inputs` map**: the consumer hands the
   provider `:inputs {:br-irrf-withheld {:swing <Money> :day <Money>
   :fii <Money>}}` from their broker reconciliation. Provider applies
   bucket-wide.

Option 2 is cleaner — it matches the existing `:inputs`-keyed
`:capital-loss-carryforward` shape (`kontor.period-tax-provider`
:138-141), keeps the disposal companion jurisdiction-neutral, and
accommodates the reality that brokers report IRRF in **monthly
summary** (one number per month per bucket), not per-trade.

**Recommendation**: ship without a new field; document the `:inputs`
key the BR provider expects. The companion's broker importer (a
v2 / kontor-broker-br-b3 follow-on) can synthesise the monthly summary
from per-trade IRRF data — but the CGT provider consumes the summary.

### Gap C — pre-1996 redutor table

For pre-1969 acquisitions: 100 % redutor (effectively exempt). For
1969-1988 acquisitions: an escalating % reduction that needs to be
**looked up by acquisition year**. The shipped schema does NOT carry
this — the provider would compute it from `:disposal/acquired-on`.

**Resolution**: **provider-side lookup**, no schema gap. The BR
provider ships a 1969-1988 redutor table (20 yearly values) as a
parameter (ADR-101 `:parameter` + `:parameter-value`, date-keyed by
acquisition year), reads `:disposal/acquired-on`, and applies the
reduction before the ladder. Audit-doc cites IN-RFB-1500/2014 art. 139.

### Bottom line — schema posture

**Zero schema changes** required at the kontor-disposal companion
level. BR fits the shipped shape with:

- 13 new `:disposal/asset-class` values (open vocabulary).
- 5 new `:disposal/loss-bucket` values (open vocabulary).
- 7 new `:disposal/exemption-claimed` keywords (cardinality-many open
  set).
- 3 new `:disposal/elective-regime` keywords (cardinality-many open
  set).
- One `:inputs` shape extension for IRRF offset (provider-side, not
  schema).

All open-vocabulary extensions — the BR companion ships these as
documented conventions; the kernel and the disposal companion remain
unchanged.

---

## §5. `br-cgt-provider` sketch

### 5.1 Component count

The BR provider returns ONE `TaxReturnFacts` per assessed entity per
period with up to **four components**, one per active lane:

```clojure
{:kind :capital-gains-tax :authority :br-rfb
 :composed-of [:br-pf-real-asset]  ;; the ladder lane
 :base ...           ;; sum of gains across all real-asset disposals in period
 :schedule (ts/bracket [[5_000_000M 0.15M]
                        [5_000_000M 0.175M]
                        [20_000_000M 0.20M]
                        [nil 0.225M]])
 :line-items [...]}  ;; per-disposal trace

{:kind :capital-gains-tax :authority :br-rfb
 :composed-of [:br-pf-swing]
 :base ...           ;; monthly sum of swing gains (after R$20k isento)
 :schedule (ts/flat 0.15M)
 :line-items [...]}

{:kind :capital-gains-tax :authority :br-rfb
 :composed-of [:br-pf-day-trade]
 :base ...           ;; monthly sum of day-trade gains
 :schedule (ts/flat 0.20M)
 :line-items [...]}

{:kind :capital-gains-tax :authority :br-rfb
 :composed-of [:br-pf-fii]
 :base ...
 :schedule (ts/flat 0.20M)
 :line-items [...]}
```

For PJ, the provider returns **no component** of its own — the gain
is computed by the provider (so the consumer's books reflect the
correct disposal P&L) and the resulting gain entry **flows into the
existing `br-cit-provider` via the GL** (the disposal's
`:realizing-tx` posts to a P&L account that the CIT provider's
`book-profit` selector sweeps). This is the same posture US note 112
§5.5 settled on (Option 1).

### 5.2 The bracket ladder via `kontor.tax-schedule`

The four-bracket PF ladder fits `(ts/bracket [...])` cleanly. Each
bracket carries `[width rate]`; the BR provider builds it once at
construction time:

```clojure
(def br-pf-ganho-bracket
  (ts/bracket [[5000000M    0.15M]    ;; up to R$ 5M
               [5000000M    0.175M]   ;; R$ 5M–R$ 10M
               [20000000M   0.20M]    ;; R$ 10M–R$ 30M
               [nil         0.225M]]))  ;; above R$ 30M
```

The bracket is **annual** — the BR provider accumulates the year's
disposals to walk it correctly (Receita's interpretation of "for each
disposal" walks the ladder against the **year-to-date gain
running-total**, per IN-RFB-1500/2014 art. 138 § 4).

### 5.3 The B3 lane B isenção logic

Per-month aggregation, per-asset-class group:

```clojure
(defn classify-swing-month
  "For a PF taxpayer's swing-trade ações in a single calendar month,
   return {:exempt? boolean :gain Money} where :exempt? is true iff
   the sum of sale prices is ≤ R$ 20,000 (Lei 11.033 art. 3 I)."
  [disposals-in-month]
  (let [total-price (apply m/+ (map :proceeds disposals-in-month))]
    {:exempt? (m/≤ total-price 20000M)
     :gain   (apply m/+ (map gain-of disposals-in-month))}))
```

The IRRF offset is applied AFTER the bracket: `darf-due = gross-tax −
irrf-withheld-this-month`.

### 5.4 Two-pass query — when needed

The R$ 20k / R$ 35k caps are **first-pass aggregates** (price sums,
not gain sums); they don't induce qualification cliffs on the gain
itself. So **no two-pass needed** for BR PF.

The PJ lucro-real path's 30 %-cap on prior-year loss utilisation
(Lei 9.065/95 art. 42) IS a two-pass concern, but it lives on the
**CIT provider** (br-cit-provider, not yet written; CIT note expected
post-130), not on the CGT provider.

### 5.5 Authority and emission

All three lanes file to **Receita Federal** (`:br-rfb`). The PF real-
asset lane uses **DARF 4600** (per-disposal, monthly); the B3 lanes
use **DARF 6015** (ações comum + day) and **DARF 8468** (FII). The
annual reconciliation lands on the **DAA / DIRPF** (Declaração de
Ajuste Anual da Pessoa Física), specifically:

- Ganhos de Capital (DAA Demonstrativo de Ganhos de Capital — the GCAP
  XML import).
- Renda Variável (DAA Demonstrativo de Renda Variável — month-by-month
  apuração).

A v2 `kontor-l10n-br-cgt-emit` extension can synthesise the GCAP XML
from the disposal log; v1 ships the computation + DARF lines, leaves
the XML to the consumer.

### 5.6 Substrate stress this provider surfaces

- **Schedule algebra**: clean. `:bracket` for the ladder, `:flat` for
  each B3 lane. Note 105's adjustment layer carries the IRRF offset as
  a `:credit` adjustment.
- **Disposal shape**: clean. Five open-vocabulary extensions (per
  §3.2-§3.5) + the IRRF `:inputs` (§4 gap B).
- **Period-tax kinds enum**: `:capital-gains-tax` already in. No
  extension.
- **Multi-component fan-out**: the JP precedent (note 115) is the same
  shape. Reuse.
- **GL-fold for PJ**: the existing `br-cit-provider` (forthcoming
  per the CN/IN/JP/CA/DE/FR Phase-2 ladder; expected at note ≥132)
  will sweep gains via its `:base-transform-add` of the disposal P&L
  accounts. Coordination point with that provider.

Total: **0 kernel changes**, **0 disposal-companion schema changes**,
**1 provider + 1 module file (`kontor-l10n-br/cgt-statute.clj`)** as the
ladder + redutor parameters in ADR-101 statute-as-data shape, **1
provider sketch (`cgt-provider.clj`)**, **1 test file**. Within the
conservative posture of note 107.

---

## §6. ADR-101 statute-as-data — what BR CGT writes

The provider is record-shaped (per note 102 §10 — Phase 2 PIT-like
providers stay record-shaped; complex Phase 3 may migrate to
`:provision`). The **parameters** that DO go through ADR-101 schema:

```clojure
;; The four-bracket PF ladder (Lei 13.259/2016 art. 21)
{:parameter/code   :br/cgt-pf-ladder
 :parameter/jurisdiction :br
 :parameter/concept-iri "https://kontor.dev/concept/cgt-rate-bracket"
 :parameter/values
 [{:parameter-value/effective-from #inst "2017-01-01"
   :parameter-value/brackets
   [{:parameter-bracket/width 5000000M :parameter-bracket/rate 0.15M}
    {:parameter-bracket/width 5000000M :parameter-bracket/rate 0.175M}
    {:parameter-bracket/width 20000000M :parameter-bracket/rate 0.20M}
    {:parameter-bracket/width nil      :parameter-bracket/rate 0.225M}]}]}

;; The R$ 35k pequeno-valor cap (Lei 9.250/95 art. 22)
{:parameter/code :br/cgt-pf-pequeno-valor-cap
 :parameter/jurisdiction :br
 :parameter/values [{:parameter-value/effective-from #inst "1995-12-26"
                     :parameter-value/amount 35000M
                     :parameter-value/currency :BRL}]}

;; The R$ 20k bolsa swing isenção (Lei 11.033 art. 3 I)
{:parameter/code :br/cgt-pf-bolsa-swing-cap
 :parameter/values [{:parameter-value/effective-from #inst "2005-01-01"
                     :parameter-value/amount 20000M
                     :parameter-value/currency :BRL}]}

;; The PF/PJ regime rates (B3 lanes)
{:parameter/code :br/cgt-pf-swing-rate
 :parameter/values [{:parameter-value/effective-from #inst "2005-01-01"
                     :parameter-value/rate 0.15M}]}
{:parameter/code :br/cgt-pf-daytrade-rate
 :parameter/values [{:parameter-value/effective-from #inst "2005-01-01"
                     :parameter-value/rate 0.20M}]}
{:parameter/code :br/cgt-pf-fii-rate
 :parameter/values [{:parameter-value/effective-from #inst "2005-01-01"
                     :parameter-value/rate 0.20M}]}
```

Plus a `:parameter` per pre-1996 redutor year (20 yearly values). The
art. 39 residence-reinvestment exemption is a **provision** (not a
parameter — it has conditions: `:residence?`, `:rollover-into-asset`,
`:rollover-deadline` within 180 days) — but Phase 2 keeps it
record-shaped in the provider.

---

## §7. Sources

### BR statutory primary

- [Lei 13.259/2016](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2016/lei/l13259.htm)
  — art. 21: the four-bracket 15 / 17.5 / 20 / 22.5 % CGT ladder for PF.
- [Lei 9.250/1995](https://www.planalto.gov.br/ccivil_03/leis/l9250.htm)
  — art. 22 (pequeno valor R$ 35k), art. 23 (sole-residence R$ 440k).
- [Lei 11.196/2005 art. 39](https://www.planalto.gov.br/ccivil_03/_ato2004-2006/2005/lei/l11196.htm#art39)
  — 180-day residence-reinvestment exemption.
- [Lei 11.033/2004 art. 2-3](https://www.planalto.gov.br/ccivil_03/_ato2004-2006/2004/lei/l11033.htm)
  — B3 renda variável 15 / 20 % split + R$ 20k isenção.
- [Lei 9.249/1995 art. 17, 21](https://www.planalto.gov.br/ccivil_03/leis/l9249.htm)
  — end of indexation; restructuring book-value transfer.
- [Lei 9.532/1997](https://www.planalto.gov.br/ccivil_03/leis/l9532.htm)
  — inheritance step-up election.
- [Lei 15.270/2025 (ex-PL 1087)](https://www.planalto.gov.br/ccivil_03/_Ato2023-2026/2025/Lei/L15270.htm)
  — IRPFM 10 % high-earner minimum; CGT carve-out.

### Receita Federal regulatory

- [Receita Federal — Alíquotas / Ganhos de Capital](https://www.gov.br/receitafederal/pt-br/assuntos/meu-imposto-de-renda/pagamento/ganhos-de-capital/aliquotas)
  — the canonical ladder.
- [IN-RFB-1500/2014](https://normas.receita.fazenda.gov.br/sijut2consulta/link.action?visao=anotado&idAto=57305)
  — consolidated IRPF rules (arts. 134, 137-142 on ganho de capital).
- [IN-RFB-1585/2015](https://normas.receita.fazenda.gov.br/sijut2consulta/link.action?visao=anotado&idAto=70004)
  — consolidated PF + PJ tributação em renda variável; loss-compensation
  rules (art. 56-65).

### Reference / commentary

- [Portal Tributário — Isenções do Ganho de Capital PF](https://www.portaltributario.com.br/guia/isencao_ganho_capital_pf.html)
  — the canonical isenções summary.
- [VLMA — Tributação dos ganhos de capital da pessoa física](https://vlma.com.br/publicacoes/tributacao-dos-ganhos-de-capital-da-pessoa-fisica)
  — pequeno-valor R$ 35k aggregate rule.
- [Contabilizei — Lucro Real](https://www.contabilizei.com.br/contabilidade-online/lucro-real/)
  — PJ IRPJ + CSLL 34 % stack.
- [Contábeis — Lucro Presumido: ganho de capital](https://www.contabeis.com.br/noticias/71820/como-apurar-o-ganho-de-capital-no-lucro-presumido/)
  — PJ Lucro Presumido CGT add-on.
- [B3 — Tributação Pessoa Física](https://atendimento.b3.com.br/sys_attachment.do?sys_id=9f2f5d101b312d106b982fc13b4bcb67)
  — broker-perspective IRRF + lane rates.
- [XP — Day Trade no Imposto de Renda](https://conteudos.xpi.com.br/aprenda-a-investir/relatorios/day-trade-no-imposto-de-renda/)
  — current 2025/2026 worked examples.
- [NSCTotal — Compensação de prejuízo na bolsa 2026](https://www.nsctotal.com.br/noticias/compensacao-prejuizo-bolsa-regras-calculo-imposto-de-renda-2026)
  — loss carry rules; same-nature requirement.
- [Mattos Filho — PL 1087](https://www.mattosfilho.com.br/unico/camara-aprova-pl-1087/)
  — the high-earner minimum tax that does NOT touch CGT.
- [BPC Partners — Brazil Tax Updates 2025](https://bpc-partners.com/news/brazil-tax-updates-lucro-presumido-import-duties-non-profits-more/)
  — LC 224/25 lucro presumido 10 % uplift starting 2026.

### kontor substrate cited

- `modules/disposal/src/kontor/disposal/schema.clj` — the shipped
  disposal schema this note assesses; lines 110-130 for `:asset-class`,
  166-201 for the Money pairs, 219-262 for elective/exemption/
  rollover, 264-272 for `:loss-bucket`.
- `src/kontor/period_tax_provider.clj:44-61` — `:capital-gains-tax`
  already in the closed enum.
- `src/kontor/period_tax_provider.clj:138-141` —
  `:capital-loss-carryforward` `:inputs` shape (extends to per-
  compartment map for BR).
- `src/kontor/tax_schedule.clj:64-90` — `:bracket` (the ladder) +
  `:flat` (each B3 lane) — all algebra the BR provider needs.
- `src/kontor/personal_income_tax.clj:71-83` — adjustment-layer
  pattern the IRRF offset mirrors as a `:credit`.
- `doc/research/107-phase-3-incorporation-and-disposal.md` §3 — the
  disposal schema this note exercises.
- `doc/research/112-us-cgt-fit.md` §5 — provider-sketch pattern
  reused.
- `doc/research/115-jp-cgt-fit.md` §5 — multi-component pattern
  reused for BR's three-lane fan-out.

---

End of note 130.
