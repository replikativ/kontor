---
date: 2026-05-24
title: 121 — BR CIT (IRPJ + CSLL) substrate-fit cross-check
audience: maintainer
status: substrate-fit cross-check — ADR-101 + ADR-104 pattern travel test
---

# 121 — BR CIT substrate-fit cross-check

ADR-101 (statute-as-data substrate: `:tax-concept` / `:provision` / `:regime`
/ `:parameter`) + ADR-104 (DE CIT as the first end-to-end consumer of that
substrate) closed Phase 3's substrate work for DE. Before fanning out to four
more jurisdictions, the maintainer asked for a cross-check against
BR / IN / CN: do the four kernel namespaces actually carry a *non-European*
statute, or did the design implicitly fit DE law? This note is the BR half of
that audit — IRPJ + CSLL + the three regime layer (Lucro Real /
Presumido / Arbitrado) + the closely-related Simples Nacional carve-out — read
against gesetze-am-planalto.gov.br, PwC Tax Summaries, the ECF Manual, and
Brazilian commercial commentary (Portal Tributário, Contabilizei, Bernhoeft,
Migalhas), then sketched as `:parameter` / `:provision` data the way
`cit_statute.clj` does for DE.

**Headline.** **The ADR-101 substrate fits BR CIT cleanly — no new primitives
required.** Every BR mechanic the maintainer asked about maps onto an existing
substrate slot:

- IRPJ 15% base + 10% adicional > R$240k/year ↔ two `:provision`s scoped via
  `[:eq :component :irpj]` with literal/parameter amounts, OR a single
  `:progressive-bracket` schedule — both encodings work, both stay inside
  `kontor.tax-schedule`.
- CSLL 9% on a closely-related-but-distinct base ↔ a second component in the
  `TaxReturnFacts`, identical pattern to KSt+GewSt: shared `book-profit`
  input, per-component `:provision`s for the BR-specific adjustments where
  the two bases diverge (CSLL non-deductible from IRPJ; the §9 JCP cap
  applies to both with the *same* limit; "lucro líquido ajustado" diverges
  only in narrow cases).
- Lucro Real / Presumido / Arbitrado ↔ **the textbook `:regime` use case** —
  three `:regime` entities, the elected one threaded via the existing
  `:regime` query argument (ADR-101 §D5; same mechanism IN old-vs-new uses).
  `:base-transform :presumption-ratio` already lives in the substrate
  (ADR-099 addendum / note 103 GAP 1) **for this exact reason**.
- Add-backs (multas, doações, brindes), exclusões (dividendos), 30%
  trava de prejuízo fiscal, JCP deduction → `:base-add` / `:base-deduct`
  via the note-105 adjustment vocabulary. JCP needs the `:amount` fn
  late-bind (cap depends on running base — TJLP × PL, vs. 50% of profit),
  which `apply-base-adjustments`'s `(fn? raw)` branch already supports.
- IRRF retido na fonte ↔ `:prepaid` Money on the component (ADR-099
  addendum 3 — the CN IIT-driven fix is already in the substrate).
- Quarterly / monthly estimativa ↔ separate `:period`s + the same provider —
  no schema work.

The two stress points are both **structural BR quirks the substrate already
names but does not yet exercise**:

- **`:regime` is shipped as ADR-101 §D5 but the ADR-104 DE pilot did not
  exercise it** — every BR encoding will. Worth one P1 hardening pass on
  the evaluator (`applicable-provisions` already filters by regime per
  `statute.clj:282-294`; the path is data-tested only by `regime-chain` so
  far). Not a P0 blocker — the code path exists.
- **Simples Nacional is out of scope for ADR-104** (it collapses IRPJ + CSLL
  + PIS + COFINS + IPI + ICMS + ISS + CPP into one progressive bracket
  applied to *gross revenue*, with the brackets indexed by the LAST 12
  MONTHS' revenue, not the assessment period). It is **NOT a CIT regime** —
  it is a transactional regime that *includes* an IRPJ/CSLL share. The fit
  is: encode it as a *transactional* tax via `TaxRateProvider` (ADR-071) /
  `TaxPostingBuilder`, NOT via `PeriodTaxProvider`. Flag in §5 — no kernel
  change, but the per-jurisdiction module needs to know which side of the
  fence the regime sits on.

The single P2 finding is on the citation surface — gesetze-im-internet.de has
no Brazilian equivalent that we discovered; planalto.gov.br is the closest,
but the URL anchoring is by article (`#art3`) rather than article-as-file
(`__23.html`). Cosmetic; documented at the parameter level in §3.

## §1. Statute summary

Brazil layers four mutually-exclusive regimes on top of a shared two-tax
stack (IRPJ + CSLL). All four affect *what base* the two taxes apply to;
none changes the rates per se (with the noted Lei Complementar 224/2025
2026 majoração). The four regimes:

### 1.1 Lucro Real — actual book profit with statutory adjustments

The default. Base = book profit (`lucro líquido contábil`) ± LALUR/LACS
adjustments (multas indedutíveis, doações, JCP, etc.) − offset of prior-year
fiscal losses up to **30% of pre-offset Lucro Real** (the "trava dos 30%",
Lei 8.981/95 art. 42 + Lei 9.065/95 art. 15). The adjustment book is the
e-LALUR (Livro de Apuração do Lucro Real) for IRPJ + the parallel e-LACS for
CSLL, both transmitted as the M300 / M350 blocks of the ECF
(Escrituração Contábil Fiscal). Periodicity: **annual** ECF closure with
either (a) **quarterly** `lucro real trimestral` apuração (DARF in the month
following each quarter-end) or (b) **monthly estimativa** + an annual
December balanço de suspensão/redução. Lei 9.430/96 art. 1 + art. 2.

Mandatory for: revenue > R$78M/year (LC 70/91 → Lei 9.718/98 art. 14, raised
multiple times; today R$78M, IN RFB 1.700/2017), financial institutions,
companies with foreign-source income, and others enumerated in Lei 9.718/98
art. 14.

### 1.2 Lucro Presumido — base = revenue × statutory presumption ratio

Elective for revenue ≤ R$78M. Base = `gross revenue (receita bruta)` ×
statutory presumption percentage (Lei 9.249/95 art. 15 for IRPJ; art. 20 for
CSLL), + capital gains + financial income + other revenue. The presumption
percentage depends on the activity:

| Activity                                  | IRPJ base | CSLL base |
|-------------------------------------------|-----------|-----------|
| Revenda de combustíveis (fuel resale)     | 1.6%      | 12%       |
| Comércio, indústria (general)             | 8%        | 12%       |
| Serviços de transporte (passenger)        | 16%       | 12%       |
| Serviços de transporte (cargo)            | 8%        | 12%       |
| Serviços hospitalares                     | 8%        | 12%       |
| Serviços em geral (consulting, IT, legal) | 32%       | 32%       |
| Intermediação de negócios                 | 32%       | 32%       |
| Administração / locação de bens           | 32%       | 32%       |
| Cessão de direitos                        | 32%       | 32%       |

After the presumed base is computed, the *same* 15% IRPJ + 10% adicional +
9% CSLL apply. Periodicity is strictly **quarterly** (Lei 9.430/96 art. 25).
Lei Complementar 224/2025 introduces a 10% majoração of the IRPJ + CSLL
presumption percentages on the slice of annual revenue > R$5M starting
2026-01-01 — encodable as a date-keyed parameter override, see §4.

### 1.3 Lucro Arbitrado — penalty / fallback

Imposed by the RFB (or self-elected by the taxpayer in narrow cases) when
the books are unreliable: no escrituração, fraud signals, missing inventory.
Base = Lei 9.249/95 art. 15 percentage **+ 20%** (Lei 9.430/96 art. 27, art.
16 cross-reference). The 32% services rate becomes 38.4%; the 8% commerce
rate becomes 9.6%; etc. Rarely elective; encoded the same as Presumido with
a 1.20 multiplier on the per-activity ratio. Out of immediate l10n-br scope
but encoding pattern noted in §3.3.

### 1.4 Simples Nacional — micro-enterprise unified regime

Lei Complementar 123/2006. Revenue ≤ R$4.8M/year. **Collapses 8 taxes**
(IRPJ + CSLL + PIS + COFINS + IPI + CPP + ICMS + ISS) into one DAS
(Documento de Arrecadação do Simples) computed via:

```
Aliquota efetiva = (RBT12 × Aliq_nominal − Parcela_deduzir) / RBT12
```

where `RBT12` is the trailing-12-months revenue, `Aliq_nominal` and
`Parcela_deduzir` come from one of five Anexos (Anexo I commerce 4%-19%,
Anexo II industry 4.5%-30%, Anexo III low-spec services 6%-33%, Anexo IV
labor-intensive services 4.5%-33%, Anexo V knowledge services 15.5%-30.5%),
and the Anexo III vs V routing within services uses the **Fator R** (payroll
÷ revenue ≥ 28% → Anexo III; else V).

This is **not a CIT regime in the ADR-099 sense** — it is a transactional
collection mechanism that *replaces* both transaction taxes (PIS/COFINS/ICMS
/ISS) AND period taxes (IRPJ/CSLL) for the eligible entity. See §5 for the
fit recommendation.

### 1.5 The two taxes themselves

**IRPJ** — Lei 9.249/95 art. 3:
- 15% on the apurado (Real / Presumido / Arbitrado) base.
- **+10% adicional** on the portion exceeding R$20,000 × number of months in
  the apuração period (Lei 9.430/96 art. 4). So:
  - annual: R$240,000 threshold
  - quarterly: R$60,000 threshold
  - monthly estimativa: R$20,000 threshold

**CSLL** — Lei 7.689/88 art. 3 + Lei 9.249/95 art. 19:
- 9% on the CSLL base (closely tracks IRPJ; differences enumerated in §1.6).
- **15%** for banks / insurance / similar; **20%** for the same category
  temporarily through 2025-12-31 (Lei 14.183/21 + Lei 14.388/22). Lei
  15.270/2025 adjusts further from 2026 but does not touch the 9% standard
  rate.
- **No adicional** — flat regardless of size.

**Both** filed together on the annual ECF (Lucro Real) or quarterly DARF
(Presumido). Both have IRRF retido at source on certain receipts
(PCC = PIS+COFINS+CSLL withholding under Lei 10.833/03 art. 30; IRRF on
financial-investment income; IRRF on services rendered to certain payors)
that the taxpayer offsets against the tax due — `:prepaid` in
`TaxReturnFacts`.

### 1.6 Where Lucro Real CSLL base diverges from Lucro Real IRPJ base

This is the BR-specific subtlety the substrate needs to carry. The starting
point is the *same* `lucro líquido contábil`; the divergences are narrow:

1. **CSLL deductibility** — IRPJ is deductible from itself for some
   purposes BUT CSLL is *never* deductible from IRPJ (Lei 9.316/96 art. 1).
   So the CSLL provision booked in the period is added back to the IRPJ
   base.
2. **PAT (Programa de Alimentação do Trabalhador)** — deductible from
   IRPJ (Lei 6.321/76) but NOT from CSLL.
3. **Cultural and audiovisual incentives (Lei Rouanet, Lei do Audiovisual)**
   — IRPJ-only.
4. **Some specific provisions / contingencies** treated differently for
   CSLL Lucro Real vs IRPJ Lucro Real.

For the FIRST iteration of an l10n-br CIT provider, encode the IRPJ and CSLL
adjustments as two parallel families of `:provision`s scoped via
`[:eq :component :irpj]` / `[:eq :component :csll]`. The shared ones
(multas, doações, brindes, JCP) are duplicated with the appropriate
component scope — two-line cost. The diverging ones (CSLL-as-IRPJ-addback,
PAT, Rouanet) fire only on the side they apply.

## §2. Worked example — a Lucro Real Ltda hitting the R$240k adicional

A trading Ltda in 2025, calendar-year apuração:

```
Lucro líquido contábil (book profit)              R$ 800,000

LALUR/IRPJ adjustments (Part A):
  + Multa de trânsito (non-deductible)              R$  5,000
  + Doações acima do limite                         R$ 10,000
  + CSLL provisão do período (addback for IRPJ)     R$ 65,250   (computed below; see §2.2 for the forward dep)
  − Dividendos recebidos (excluded, art. 10 9249)   R$ (20,000)
  − JCP pago a sócios (art. 9 9249, dentro dos      R$ (40,000)
    limites TJLP × PL e 50% do lucro)
  − Compensação prejuízo fiscal (trava 30%)         R$ (240,000) (= 30% × pre-comp.)
                                                    ───────────
                                                    R$  (220,000) net adj.

Lucro Real (IRPJ base):                            R$ 800,000
                                                 + R$   5,000
                                                 + R$  10,000
                                                 + R$  65,250
                                                 − R$  20,000
                                                 − R$  40,000
                                                 = R$ 820,250  ← pre-compensação
                                                 − R$ 246,075  (= 30% × 820,250)
                                                 = R$ 574,175  ← Lucro Real após trava

IRPJ:
  Base 15%:      R$ 574,175 × 15%        =  R$  86,126.25
  Adicional 10%: max(0, 574,175 − 240,000) × 10%
               = R$ 334,175 × 10%        =  R$  33,417.50
  IRPJ total:                            =  R$ 119,543.75

LACS / CSLL adjustments (Part A):
  Same starting book profit R$ 800,000
  + Multa de trânsito                              R$  5,000
  + Doações acima do limite                        R$ 10,000
  − Dividendos                                     R$(20,000)
  − JCP pago                                       R$(40,000)
  (NO CSLL-addback — CSLL doesn't itself adjust its own base)
  (NO PAT for CSLL — would also adjust if not zero)
  − Compensação base negativa CSLL (trava 30%)     R$(229,500) (= 30% × 765,000 pre-comp)
                                                   ─────────
  Base CSLL pre-comp:                              R$ 765,000
  Base CSLL pós-comp:                              R$ 535,500

CSLL: 535,500 × 9%                                 =  R$  48,195.00
```

(Numbers are illustrative and chosen so the IRPJ-CSLL addback effect is
visible. The CSLL provision R$ 65,250 cited as an IRPJ addback above is
*last period's* CSLL pulled forward — in steady state the addback cancels
between adjacent periods; a clean first-period encoding would zero it.
Books-driven not numerically forward-resolved in this hand-trace.)

Cross-checked against Contabilizei's IRPJ Lucro Real walkthrough and CRC-CE's
*IRPJ Lucro Presumido* PDF (which shares the adicional arithmetic). The
adicional formula `(base − 20,000 × meses) × 10%` is canonical across every
source (Lei 9.430/96 art. 4); for annual apuração that is exactly
`(base − 240,000) × 10%`.

### 2.1 Same example as Lucro Presumido (services Ltda)

Same Ltda, but elective Presumido under §15 III (serviços em geral, 32%):

```
Receita bruta serviços trimestre Q4               R$ 3,000,000
Ganho de capital (e.g. venda de imobilizado)      R$    50,000
Receita financeira                                R$    20,000

IRPJ base presumida (Q4):
  Receita × 32%:    R$ 3,000,000 × 32% = R$ 960,000
  + ganho capital   R$    50,000
  + receita fin.    R$    20,000
  Base trimestral:  R$ 1,030,000
  IRPJ: 15%         R$   154,500
  Adicional 10%:    max(0, 1,030,000 − 60,000) × 10% = R$ 97,000
  IRPJ total Q4:    R$   251,500

CSLL base presumida (Q4):
  Receita × 32%:    R$ 3,000,000 × 32% = R$ 960,000
  + ganho capital   R$    50,000
  + receita fin.    R$    20,000
  Base trimestral:  R$ 1,030,000
  CSLL: 9%          R$    92,700
```

Net Q4 federal liability R$ 344,200. IRRF retido na fonte over Q4 deducts
from this on the DARF. The base substrate handles all of it.

## §3. Substrate fit — `:parameter` + `:provision` encoding sketch

### 3.1 Parameters

```clojure
(def parameters
  [;; --------------------------------------------------------------------
   ;; IRPJ
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
   ;; CSLL
   ;; --------------------------------------------------------------------
   {:parameter/code         "BR.CSLL.rate"
    :parameter/label        "CSLL alíquota base (9% empresa em geral)"
    :parameter/jurisdiction :br :parameter/unit :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l7689.htm#art3"}

   {:parameter/code         "BR.CSLL.rate-financial"
    :parameter/label        "CSLL alíquota 15-20% instituições financeiras"
    :parameter/jurisdiction :br :parameter/unit :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l7689.htm#art3"}

   ;; --------------------------------------------------------------------
   ;; Lucro Presumido — per-activity presumption ratios
   ;; --------------------------------------------------------------------
   {:parameter/code         "BR.IRPJ.presumido.ratio-comercio"
    :parameter/label        "Presunção 8% comércio/indústria"
    :parameter/jurisdiction :br :parameter/unit :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art15"}

   {:parameter/code         "BR.IRPJ.presumido.ratio-servicos"
    :parameter/label        "Presunção 32% serviços em geral"
    :parameter/jurisdiction :br :parameter/unit :rate}

   {:parameter/code         "BR.IRPJ.presumido.ratio-combustivel"
    :parameter/label        "Presunção 1.6% revenda de combustíveis"
    :parameter/jurisdiction :br :parameter/unit :rate}

   {:parameter/code         "BR.IRPJ.presumido.ratio-transporte-passageiros"
    :parameter/label        "Presunção 16% transporte de passageiros"
    :parameter/jurisdiction :br :parameter/unit :rate}

   {:parameter/code         "BR.CSLL.presumido.ratio-comercio"
    :parameter/label        "CSLL presunção 12% comércio/indústria (art. 20 9249)"
    :parameter/jurisdiction :br :parameter/unit :rate}

   {:parameter/code         "BR.CSLL.presumido.ratio-servicos"
    :parameter/label        "CSLL presunção 32% serviços em geral"
    :parameter/jurisdiction :br :parameter/unit :rate}

   ;; --------------------------------------------------------------------
   ;; Lucro Real
   ;; --------------------------------------------------------------------
   {:parameter/code         "BR.Real.compensacao-prejuizo-cap"
    :parameter/label        "Trava dos 30% — limite de compensação de prejuízo fiscal"
    :parameter/jurisdiction :br :parameter/unit :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9065.htm#art15"}

   {:parameter/code         "BR.JCP.deducao-cap-50pct"
    :parameter/label        "JCP — limite de 50% do lucro do período ou lucros acumulados"
    :parameter/jurisdiction :br :parameter/unit :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art9"}])

(def parameter-values
  [{:parameter-value/parameter [:parameter/code "BR.IRPJ.rate"]
    :parameter-value/effective-from #inst "1996-01-01"
    :parameter-value/decimal-value 0.15M
    :parameter-value/citation "Lei 9.249/95 art. 3 — estável desde 1996"}

   {:parameter-value/parameter [:parameter/code "BR.IRPJ.adicional-rate"]
    :parameter-value/effective-from #inst "1996-01-01"
    :parameter-value/decimal-value 0.10M
    :parameter-value/citation "Lei 9.430/96 art. 4 — estável desde 1997"}

   {:parameter-value/parameter [:parameter/code "BR.IRPJ.adicional-threshold-mensal"]
    :parameter-value/effective-from #inst "1996-01-01"
    :parameter-value/decimal-value 20000M
    :parameter-value/commodity [:commodity/code "BRL"]
    :parameter-value/citation "Lei 9.430/96 art. 4 — R$20k/mês × meses"}

   {:parameter-value/parameter [:parameter/code "BR.CSLL.rate"]
    :parameter-value/effective-from #inst "2003-09-01"
    :parameter-value/decimal-value 0.09M
    :parameter-value/citation "Lei 10.637/02 art. 37 → 9% padrão"}

   ;; Lucro Presumido — note 2026 Lei Complementar 224/2025 majoração:
   ;; revenue > R$5M annual is taxed with these ratios × 1.10 (a +10%
   ;; majoração on the presumption itself, not the rate)
   {:parameter-value/parameter [:parameter/code "BR.IRPJ.presumido.ratio-comercio"]
    :parameter-value/effective-from #inst "1996-01-01"
    :parameter-value/decimal-value 0.08M
    :parameter-value/citation "Lei 9.249/95 art. 15 caput"}

   {:parameter-value/parameter [:parameter/code "BR.IRPJ.presumido.ratio-servicos"]
    :parameter-value/effective-from #inst "1996-01-01"
    :parameter-value/decimal-value 0.32M
    :parameter-value/citation "Lei 9.249/95 art. 15 §1 III"}

   {:parameter-value/parameter [:parameter/code "BR.IRPJ.presumido.ratio-combustivel"]
    :parameter-value/effective-from #inst "1996-01-01"
    :parameter-value/decimal-value 0.016M
    :parameter-value/citation "Lei 9.249/95 art. 15 §1 I"}

   {:parameter-value/parameter [:parameter/code "BR.IRPJ.presumido.ratio-transporte-passageiros"]
    :parameter-value/effective-from #inst "1996-01-01"
    :parameter-value/decimal-value 0.16M
    :parameter-value/citation "Lei 9.249/95 art. 15 §1 II"}

   {:parameter-value/parameter [:parameter/code "BR.CSLL.presumido.ratio-comercio"]
    :parameter-value/effective-from #inst "2003-09-01"
    :parameter-value/decimal-value 0.12M
    :parameter-value/citation "Lei 9.249/95 art. 20 — 12% padrão"}

   {:parameter-value/parameter [:parameter/code "BR.CSLL.presumido.ratio-servicos"]
    :parameter-value/effective-from #inst "2003-09-01"
    :parameter-value/decimal-value 0.32M
    :parameter-value/citation "Lei 9.249/95 art. 20 I (alterado pela Lei 10.684/03)"}

   {:parameter-value/parameter [:parameter/code "BR.Real.compensacao-prejuizo-cap"]
    :parameter-value/effective-from #inst "1995-01-01"
    :parameter-value/decimal-value 0.30M
    :parameter-value/citation "Lei 8.981/95 art. 42 + Lei 9.065/95 art. 15 — trava dos 30%"}

   {:parameter-value/parameter [:parameter/code "BR.JCP.deducao-cap-50pct"]
    :parameter-value/effective-from #inst "1996-01-01"
    :parameter-value/decimal-value 0.50M
    :parameter-value/citation "Lei 9.249/95 art. 9 §1 — maior entre 50% do lucro do período ou dos acumulados"}])
```

### 3.2 Regimes

```clojure
(def regimes
  [{:regime/code            :br-lucro-real
    :regime/label           "Lucro Real (apuração anual ou trimestral)"
    :regime/jurisdiction    :br
    :regime/concept-iri     "https://www.planalto.gov.br/ccivil_03/leis/l9430.htm#art1"}

   {:regime/code            :br-lucro-presumido
    :regime/label           "Lucro Presumido (receita ≤ R$78M/ano)"
    :regime/jurisdiction    :br
    :regime/concept-iri     "https://www.planalto.gov.br/ccivil_03/leis/l9430.htm#art25"}

   {:regime/code            :br-lucro-arbitrado
    :regime/label           "Lucro Arbitrado (presunção × 1.20)"
    :regime/jurisdiction    :br
    :regime/concept-iri     "https://www.planalto.gov.br/ccivil_03/leis/l9430.htm#art27"}])
```

(Simples Nacional is NOT a `:regime` here — see §5.)

### 3.3 Provisions — IRPJ

```clojure
[;; ====================================================================
 ;; IRPJ — alíquota base 15% (every regime applies the same rate; only
 ;; the BASE changes per regime). Encoded as a flat schedule selected
 ;; in the provider, NOT as a :provision — the rate IS the schedule.
 ;; The provisions below are the BASE-side and TAX-side adjustments.
 ;; ====================================================================

 ;; ---- LUCRO REAL adjustments (base-side, :base-add / :base-deduct) ----
 ;; Add-back: multas indedutíveis (Lei 9.430/96 art. 41 + tradição da
 ;; jurisprudência — multas de natureza penal não dedutíveis)
 {:provision/code            "BR-IRPJ-Real-multas-indedutiveis"
  :provision/jurisdiction    :br
  :provision/concept         [:tax-concept/code :base-transform-add]
  :provision/title           "Adição — multas indedutíveis ao Lucro Real (IRPJ)"
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

 ;; Add-back: doações acima do limite (Lei 9.249/95 art. 13)
 {:provision/code            "BR-IRPJ-Real-doacoes-acima-limite"
  :provision/jurisdiction    :br
  :provision/concept         [:tax-concept/code :base-transform-add]
  :provision/title           "Adição — doações acima dos limites Lei 9249 art. 13"
  :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art13"
  :provision/effective-from  #inst "1996-01-01"
  :provision/priority        100
  :provision/regime          [:regime/code :br-lucro-real]
  :provision/condition       (pr-str [:and
                                      [:eq :component :irpj]
                                      [:gt [:inputs :doacoes-acima-limite] 0M]])
  :provision/consequence     (pr-str {:op :base-add
                                      :code :irpj-doacoes
                                      :label "Doações acima dos limites legais"
                                      :amount-from :tax-context-fact
                                      :fact [:inputs :doacoes-acima-limite]})}

 ;; Add-back: CSLL provisão do período (Lei 9.316/96 art. 1)
 {:provision/code            "BR-IRPJ-Real-csll-addback"
  :provision/jurisdiction    :br
  :provision/concept         [:tax-concept/code :base-transform-add]
  :provision/title           "Adição — CSLL não dedutível do IRPJ (art. 1 9316)"
  :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l9316.htm#art1"
  :provision/effective-from  #inst "1997-01-01"
  :provision/priority        100
  :provision/regime          [:regime/code :br-lucro-real]
  :provision/condition       (pr-str [:and
                                      [:eq :component :irpj]
                                      [:gt [:inputs :csll-provisao-periodo] 0M]])
  :provision/consequence     (pr-str {:op :base-add
                                      :code :irpj-csll-addback
                                      :label "CSLL provisionada (não dedutível)"
                                      :amount-from :tax-context-fact
                                      :fact [:inputs :csll-provisao-periodo]})}

 ;; Exclusion: dividendos recebidos de outras pessoas jurídicas
 ;; (Lei 9.249/95 art. 10 — não tributados; até Lei 15.270/25 que altera
 ;; a partir de 2026 para distribuições > R$50k/mês a PF)
 {:provision/code            "BR-IRPJ-Real-dividendos-excluidos"
  :provision/jurisdiction    :br
  :provision/concept         [:tax-concept/code :base-transform-deduct]
  :provision/title           "Exclusão — dividendos recebidos isentos (art. 10 9249)"
  :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l9249.htm#art10"
  :provision/effective-from  #inst "1996-01-01"
  :provision/priority        100
  :provision/regime          [:regime/code :br-lucro-real]
  :provision/condition       (pr-str [:and
                                      [:eq :component :irpj]
                                      [:gt [:inputs :dividendos-recebidos] 0M]])
  :provision/consequence     (pr-str {:op :base-deduct
                                      :code :irpj-dividendos
                                      :label "Dividendos isentos"
                                      :amount-from :tax-context-fact
                                      :fact [:inputs :dividendos-recebidos]})}

 ;; Exclusion: JCP — Lei 9.249/95 art. 9. The cap is computed via
 ;; compute-fn (TJLP × patrimônio líquido capped at 50% of profit-or-
 ;; accumulated-profits, whichever is greater). The consumer supplies
 ;; the JCP PAID (after the company's allocation decision); the
 ;; substrate caps it.
 {:provision/code            "BR-IRPJ-Real-jcp-deduction"
  :provision/jurisdiction    :br
  :provision/concept         [:tax-concept/code :base-transform-deduct]
  :provision/title           "Exclusão — JCP pago/creditado dentro dos limites"
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

 ;; Compensação de prejuízo fiscal — trava 30% (Lei 9.065/95 art. 15).
 ;; Late-bound: depends on the running base post all other adjustments.
 ;; The consumer supplies the carryforward stock; the provision computes
 ;; min(carryforward, 30% × current Lucro Real).
 {:provision/code            "BR-IRPJ-Real-trava-30pct"
  :provision/jurisdiction    :br
  :provision/concept         [:tax-concept/code :base-transform-deduct]
  :provision/title           "Compensação de prejuízo fiscal (trava 30%)"
  :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l9065.htm#art15"
  :provision/effective-from  #inst "1995-01-01"
  :provision/priority        900   ;; runs LAST — all other base-adj before
  :provision/regime          [:regime/code :br-lucro-real]
  :provision/condition       (pr-str [:and
                                      [:eq :component :irpj]
                                      [:gt [:inputs :prejuizo-fiscal-acumulado] 0M]])
  :provision/consequence     (pr-str {:op :base-deduct
                                      :code :irpj-compensacao
                                      :label "Compensação prejuízo (≤ 30% Lucro Real)"
                                      :amount-from :compute-fn
                                      :fn :br-trava-30pct})}

 ;; ---- LUCRO PRESUMIDO base ----
 ;; The Presumido base IS the presumption × revenue + ganhos + outras
 ;; receitas. Encoded as a :base-transform :presumption-ratio in the
 ;; provider (NOT as a provision) — because the substrate already has
 ;; :base-transform :presumption-ratio. The :inputs :receita-bruta
 ;; flows; the provider routes to the right activity ratio per
 ;; :inputs :atividade-codigo (e.g. :comercio / :servicos / :combustivel).

 ;; ---- IRPJ tax-side (every regime): adicional 10% via compute-fn ----
 {:provision/code            "BR-IRPJ-adicional-10pct"
  :provision/jurisdiction    :br
  :provision/concept         [:tax-concept/code :surtax]
  :provision/title           "IRPJ adicional 10% — Lei 9.430/96 art. 4"
  :provision/citation        "https://www.planalto.gov.br/ccivil_03/leis/l9430.htm#art4"
  :provision/effective-from  #inst "1997-01-01"
  :provision/priority        100
  ;; No :regime — applies in all four (Real/Presumido/Arbitrado;
  ;; Simples is not a :regime here)
  :provision/condition       (pr-str [:eq :component :irpj])
  :provision/consequence     (pr-str {:op :surtax
                                      :code :irpj-adicional
                                      :label "Adicional 10% sobre excesso R$20k×meses"
                                      :amount-from :compute-fn
                                      :fn :br-irpj-adicional})}]
```

### 3.4 CSLL provisions (parallel structure)

```clojure
[;; Most adjustments parallel IRPJ but scoped :component :csll.
 ;; Trava 30% applies to CSLL base negativa the same way (Lei 9.065/95
 ;; art. 16). JCP is deductible from CSLL too (Lei 9.249/95 art. 9 §10).
 ;; The big differences:
 ;;   - NO CSLL-addback provision on the CSLL side (would be circular)
 ;;   - NO PAT exclusion on CSLL (would be on IRPJ)
 ;;   - NO Rouanet / Audiovisual exclusion on CSLL

 {:provision/code            "BR-CSLL-Real-trava-30pct"
  ;; ... identical structure to BR-IRPJ-Real-trava-30pct
  :provision/regime          [:regime/code :br-lucro-real]
  :provision/condition       (pr-str [:and [:eq :component :csll]
                                           [:gt [:inputs :base-negativa-csll-acumulada] 0M]])
  :provision/consequence     (pr-str {:op :base-deduct
                                      :code :csll-compensacao
                                      :amount-from :compute-fn
                                      :fn :br-trava-30pct-csll})}

 ;; CSLL has NO adicional — flat 9% (or 15-20% financial). One
 ;; provision per applicable rate bracket; the provider just picks the
 ;; right :parameter for the schedule.
 ]
```

### 3.5 Compute-fns

```clojure
(defn- br-irpj-adicional
  "IRPJ adicional 10% on (base − R$20k × meses-da-apuracao)."
  [ctx]
  (fn [ctx-w-running]
    (let [;; The running base, plus the months in the period
          base      (get-in ctx [:base-amount])       ; provider must set this
          monthly   (statute/parameter-value-at (:db ctx) "BR.IRPJ.adicional-threshold-mensal"
                                                (as-of-from-ctx ctx))
          months    (months-in-period (:period ctx))  ; 1, 3, or 12
          threshold (* monthly (bigdec months))
          rate      (statute/parameter-value-at (:db ctx) "BR.IRPJ.adicional-rate"
                                                (as-of-from-ctx ctx))]
      (* (max 0M (- base threshold)) rate))))

(defn- br-jcp-cap
  "JCP deduction capped at the lesser of:
     - the JCP value the consumer claims paid;
     - TJLP × patrimônio líquido (consumer-supplied);
     - 50% of profit-of-period OR 50% of accumulated profits, whichever
       greater (the substrate sees both via :inputs)."
  ^java.math.BigDecimal [ctx]
  (let [jcp-claimed     (or (get-in ctx [:inputs :jcp-pago]) 0M)
        tjlp-x-pl       (or (get-in ctx [:inputs :jcp-tjlp-x-pl-cap]) 0M)
        profit-period   (or (get-in ctx [:inputs :lucro-periodo]) 0M)
        profit-accum    (or (get-in ctx [:inputs :lucros-acumulados]) 0M)
        cap-50pct       (statute/parameter-value-at (:db ctx) "BR.JCP.deducao-cap-50pct"
                                                    (as-of-from-ctx ctx))
        cap-50          (* cap-50pct (max profit-period profit-accum))]
    (min jcp-claimed tjlp-x-pl cap-50)))

(defn- br-trava-30pct
  "Compensação de prejuízo fiscal — min(carryforward, 30% × Lucro Real
   pre-compensação). Late-bound on :running."
  [ctx]
  (fn [ctx-w-running]
    (let [carry (or (get-in ctx [:inputs :prejuizo-fiscal-acumulado]) 0M)
          cap-rate (statute/parameter-value-at (:db ctx) "BR.Real.compensacao-prejuizo-cap"
                                               (as-of-from-ctx ctx))
          cap (* (:running ctx-w-running) cap-rate)]
      (min carry cap))))
```

Three compute-fns — exactly the same shape as DE's four. **No new
predicate, no new `:provision/consequence :op`, no new schedule type.** The
substrate is doing the work.

## §4. Abstraction stress — findings categorized

### P0 — would mis-compute a typical BR Ltda today: NONE

The substrate fits cleanly. Every BR mechanic — IRPJ rate, adicional,
CSLL rate, all four regimes, every common Lucro Real adjustment, JCP,
trava de 30%, IRRF retido — maps onto an existing primitive. **The
maintainer's hypothesis is confirmed**: ADR-101 travels.

### P1 — substrate paths exist but BR is the first real exerciser

- **P1-1: `:regime` evaluator path under-exercised.** `statute.clj:282-294`
  filters by `regime-chain`, and `regime-chain` itself has a clean test
  for cycles + transitive `:regime/extends`. But the ADR-104 DE pilot
  doesn't elect a regime — every DE provision has `:regime` nil. BR is
  the first jurisdiction where the regime filter does real work (a
  Presumido provider must NOT pick up Lucro Real provisions and vice
  versa). Recommend the l10n-br implementer add a regression test
  exercising `(applicable-provisions db {... :regime :br-lucro-presumido})`
  returning a different result-set than `(... :regime :br-lucro-real)`,
  *before* implementing the provider. The code path is correct on
  inspection — but DE doesn't prove it.

- **P1-2: the `:base-transform :presumption-ratio` is shipped but
  never used.** `tax_schedule.clj:158` implements it; the DE pilot
  doesn't trigger it. The BR Presumido path will: the provider's
  `kst-component`-equivalent for Presumido should call
  `(ts/apply-base-transform {:transform/type :presumption-ratio
  :ratio <activity-rate>} receita-bruta)` BEFORE handing to
  `apply-base-adjustments` for ganhos / receitas. Recommend an
  integration test exercising the full
  `marginalize → apply-base-transform → apply-base-adjustments →
  apply-schedule → apply-adjustments` pipeline with a non-identity
  transform. Again — code exists, BR is the first end-to-end customer.

- **P1-3: JCP cap is `(min A B C)` of three independent maxima.** The
  `br-jcp-cap` compute-fn sketched in §3.5 is straightforward but
  noteworthy because it reads FOUR `:inputs` keys (`jcp-pago`,
  `jcp-tjlp-x-pl-cap`, `lucro-periodo`, `lucros-acumulados`). The DE
  pilot's compute-fns each read one or two facts. This is not a
  substrate stress — `:inputs` is an open map — but it's the first hint
  that the cleanest authoring of complex statutory caps may want a
  helper to bundle "the consumer-supplied JCP inputs map" into one
  named structure, the way ADR-099 addendum bundled
  `:inputs :capital-loss-carryforward {:short :long}`. **No code
  change needed in this round** — note as a possible Phase 4 documented
  convention if a second jurisdiction (Latvia, Belgium ACE) has a
  similar shape.

- **P1-4: The `:provision/regime` reverse-lookup ergonomic.** Once
  the BR provider lands, listing "all provisions in the Lucro Real
  regime" or "which regimes implement `:base-transform-add`" is a
  natural query. The schema supports it (`:regime/code` is unique,
  `:provision/regime` is a ref); the helper does not yet exist. Note 91
  (`kontor.explain`) already plans `explain-balance` / `explain-posting`
  — an `explain-regime` walk would slot in cleanly. Defer; revisit
  after FR / JP CIT ship and patterns crystallize.

### P2 — minor

- **P2-1: Citation URL anchoring.** Planalto serves the entire Lei in
  one file (`l9249.htm`), with article anchors (`#art3`, `#art15`).
  This is *cosmetically* less clean than gesetze-im-internet.de's
  per-article files (`__23.html`). The encoded citation URL works as
  a deep link in any browser; the `:provision/citation` semantics
  ("URL to authority") are unchanged. **No fix.**

- **P2-2: `:atividade-codigo` routing for Presumido.** Picking
  the right per-activity presumption ratio means the consumer
  supplies an `:atividade-codigo` keyword (`:comercio`, `:servicos`,
  `:combustivel`, etc.) and the provider switches. This is provider
  code — NOT a substrate concern — but it's an early signal of the
  pattern. The CN IIT pilot also has a categorical-routing input
  (taxpayer category); the patterns can converge in a future
  documented convention.

- **P2-3: Lei Complementar 224/2025 majoração.** From 2026-01-01,
  Presumido percentages are × 1.10 on the slice of annual revenue
  > R$5M. This is the **textbook ADR-101 use case** — encode it as
  a SECOND `:parameter-value` row on each Presumido ratio parameter
  with `:effective-from #inst "2026-01-01"` and a value × 1.10.
  BUT — it's not a flat × 1.10: it's a × 1.10 *on a slice*. The cleanest
  encoding is a SECOND `:provision` keyed `:effective-from
  #inst "2026-01-01"` that fires when revenue > R$5M and adds the
  10% incremental base. The substrate handles both encodings; the
  second is more faithful to the statute. **No substrate work, but
  flag in the implementation plan as a 2026-01-01 sunset/transition
  the way DE §9 Nr. 1 needed.**

- **P2-4: Lei 15.270/2025 dividend taxation from 2026.** Adds a
  10% IRRF on dividends > R$50k/month paid to BR resident
  individuals; reintroduces 10% IRRF on dividends to non-residents.
  This is a **transactional withholding tax** (`TaxRateProvider` /
  `TaxPostingBuilder`), NOT a CIT change. Out of l10n-br CIT scope.
  Noted because the maintainer asked about "withholding adjustments
  (IRRF retido)" — the OUTGOING 10% dividend IRRF is collected by
  the paying corporation as a transaction tax; the INCOMING IRRF
  retido (PCC, financial-investment IRRF, services IRRF the
  taxpayer received in their gross revenue) is `:prepaid` on the
  receiving corporation's `TaxReturnFacts`. The two are different
  surfaces; both work with existing kontor primitives.

## §5. Minimal substrate adds — NONE

This is the headline. **The ADR-101 substrate as-shipped is sufficient
to encode IRPJ + CSLL + the three "lucro" regimes faithfully.** The
three substrate features the DE pilot did not exercise — `:regime`,
`:base-transform :presumption-ratio`, complex multi-input compute-fns —
all exist in code today; BR is the first jurisdiction that will exercise
all three. Confidence comes from inspection, not from observation; the
P1 recommendation is to add regression tests as part of the BR provider
implementation.

**One scope clarification (NOT a substrate add).** Simples Nacional is
NOT a `:regime` for `PeriodTaxProvider` purposes. It is a transactional
tax that combines (IRPJ + CSLL + PIS + COFINS + IPI + ICMS + ISS + CPP)
into one collection mechanism. The correct kontor encoding is:

- An **`l10n-br Simples Nacional TaxRateProvider`** (ADR-071) that
  computes the unified DAS rate from `Aliquota_efetiva =
  (RBT12 × Aliq_nominal − Parcela_deduzir) / RBT12` and posts one
  unified `:simples-nacional-payable` per transaction.
- A **`SimplesNacionalReturnBuilder`** (a thin period-aggregation
  helper, NOT a `PeriodTaxProvider`) that produces the monthly DAS
  consolidating the per-transaction amounts.

A company on Simples Nacional has **no IRPJ/CSLL `PeriodTaxProvider`
call at all**. The l10n-br CIT provider (the one this note sketches)
short-circuits and returns `nil` for entities whose
`:tax-profile :regime` is `:br-simples-nacional`. The Simples Anexo /
Fator R / Aliquota progressive bracket is then encodable as a
`TaxRateProvider`-side `:progressive-bracket`-on-RBT12 structure —
out of this note's scope but easy to land in a follow-up note when the
l10n-br module is being rebuilt against ADR-099 + ADR-101.

This is the **only** structural clarification: the maintainer's
question "would the substrate need extensions for Simples?" — the
answer is "Simples lives on the *other* substrate (transactional, not
period), where it fits cleanly." Both substrates are already in
production kontor; no addition needed to either.

## §6. Sources

**Statute text (planalto.gov.br — Casa Civil canonical, equivalent to
gesetze-im-internet.de's BMJ role for DE)**

- [Lei 9.249/95 — Imposto de Renda das Pessoas Jurídicas](https://www.planalto.gov.br/ccivil_03/leis/l9249.htm)
  (art. 3 IRPJ rate; art. 9 JCP; art. 10 dividendos isentos;
  art. 13 doações; art. 15 Presumido IRPJ ratios; art. 19 CSLL alíquota
  base; art. 20 Presumido CSLL ratios)
- [Lei 9.430/96 — Apuração IRPJ + CSLL](https://www.planalto.gov.br/ccivil_03/leis/l9430.htm)
  (art. 1 anual; art. 2 estimativa mensal; art. 4 adicional 10% / R$20k×meses;
  art. 25 Presumido trimestral; art. 27 Arbitrado; art. 41 multas dedutíveis;
  art. 44 multas qualificadas)
- [Lei 9.065/95 — Compensação de prejuízos](https://www.planalto.gov.br/ccivil_03/leis/l9065.htm)
  (art. 15 IRPJ trava 30%; art. 16 CSLL trava 30%)
- [Lei 9.316/96 — CSLL não dedutível do IRPJ](https://www.planalto.gov.br/ccivil_03/leis/l9316.htm)
  (art. 1 CSLL-addback)
- [Lei 7.689/88 — Institui a CSLL](https://www.planalto.gov.br/ccivil_03/leis/l7689.htm)
- [Lei 10.637/02 — CSLL 9%](https://www.planalto.gov.br/ccivil_03/leis/2002/l10637.htm)
  (art. 37 fixa 9% padrão)
- [Lei Complementar 123/2006 — Simples Nacional](https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp123.htm)
- [Lei Complementar 224/2025 — Majoração Presumido a partir de 2026](https://www.planalto.gov.br/)
  (cited per Paulicon Contábil 2026)
- [Lei 15.270/2025 — Tributação de dividendos a partir de 2026](https://www.planalto.gov.br/)

**Authority / Receita Federal**

- [Receita Federal — IRPJ tributo](https://www.gov.br/receitafederal/pt-br/assuntos/orientacao-tributaria/tributos/IRPJ)
- [PGFN — Lucro real, lucro presumido e lucro arbitrado](https://www.gov.br/pgfn/pt-br/cidadania-tributaria/por-assunto/irpj-csll/lucro-real-lucro-presumido-e-lucro-arbitrado)
- [Receita Federal — Nova legislação JCP (cláusula Lei Complementar 224/2025)](https://www.gov.br/receitafederal/pt-br/assuntos/orientacao-tributaria/auditoria-fiscal/conformidade/nova-legislacao-sobre-juros-sobre-capital-proprio-jcp)
- [Receita Federal — Compensação de prejuízos perguntas-respostas (2021 PDF)](https://www.gov.br/receitafederal/pt-br/assuntos/orientacao-tributaria/declaracoes-e-demonstrativos/ecf/perguntas-e-respostas-pessoa-juridica-2021-arquivos/capitulo-x-compensacao-de-prejuizos-2021.pdf)

**Big-4 / commercial commentary**

- [PwC Worldwide Tax Summaries — Brazil Corporate Income Taxes](https://taxsummaries.pwc.com/brazil/corporate/taxes-on-corporate-income)
- [PwC Worldwide Tax Summaries — Brazil Income Determination](https://taxsummaries.pwc.com/brazil/corporate/income-determination)
- [Chambers Corporate Tax 2025 — Brazil](https://practiceguides.chambers.com/practice-guides/corporate-tax-2025/brazil/trends-and-developments)
- [Chambers Corporate Tax 2026 — Brazil](https://practiceguides.chambers.com/practice-guides/corporate-tax-2026/brazil)
- [ICLG Corporate Tax 2026 — Brazil](https://iclg.com/practice-areas/corporate-tax-laws-and-regulations/brazil)
- [BDO — Brazil Dividend WHT Reintroduced](https://www.bdo.global/en-gb/insights/tax/world-wide-tax/brazil-withholding-tax-reintroduced-on-dividends-paid-to-nonresidents)
- [Mayer Brown — Lei 15.270/2025](https://www.mayerbrown.com/en/insights/publications/2025/12/enactment-of-law-no-15270-2025-which-establishes-dividend-taxation-expands-the-exemption-threshold-and-introduces-a-minimum-tax-on-high-incomes)
- [Trench Rossi Watanabe — Lei 15.270/2025](https://www.trenchrossi.com/en/legal-alerts/brazil-enacts-law-15270-2025-which-taxes-dividends-and-amend-personal-income-tax-rules/)

**Practitioner commentary**

- [Portal Tributário — IRPJ Lucro Presumido](https://www.portaltributario.com.br/guia/lucro_presumido_irpj.html)
- [Portal Tributário — CSLL Lucro Presumido](https://www.portaltributario.com.br/guia/lucro_presumido_csl.html)
- [Portal Tributário — Lucro Arbitrado](https://www.portaltributario.com.br/guia/lucro_arbitrado.html)
- [Portal Tributário — TJLP / JCP](https://www.portaltributario.com.br/guia/tjlp.html)
- [Portal Tributário — Utilização de prejuízos fiscais](https://www.portaltributario.com.br/guia/compensacao_prejuizos.html)
- [Portal Tributário — Ajustes Lucro Real (LALUR)](https://www.portaltributario.com.br/guia/ajustes_lucro_real.html)
- [Bernhoeft — Adições e exclusões ao Lucro Real](https://www.bernhoeft.com.br/blog/adicoes-e-exclusoes-ao-lucro-real-exemplos-praticos-e-consequencias-para-a-gestao-financeira/)
- [Bernhoeft — Despesas indedutíveis no Lucro Real](https://www.bernhoeft.com.br/blog/despesas-indedutiveis-no-lucro-real/)
- [Contabilizei — Tabela Simples Nacional 2026](https://www.contabilizei.com.br/contabilidade-online/tabela-simples-nacional-completa/)
- [Contabilizei — Adicional IRPJ](https://www.contabilizei.com.br/contabilizei-responde/como-calcular-o-irpj-adicional/)
- [Contabilizei — IRPJ Lucro Presumido cálculo](https://www.contabilizei.com.br/contabilidade-online/lucro-presumido/)
- [Cora — IRPJ no Lucro Presumido](https://www.cora.com.br/blog/como-calcular-irpj-lucro-presumido/)
- [Paulicon Contábil — Lucro Presumido 2026 (LC 224/2025 majoração)](https://paulicon.com.br/2026/01/23/tributacao-pelo-lucro-presumido-em-2026/)
- [CRC-CE — IRPJ Lucro Presumido PDF](https://www.crc-ce.org.br/crcnovo/download/irpj_lucpres.PDF)
- [BPC Partners — Brazilian Corporate Tax](https://bpc-partners.com/brazilian-taxes-what-you-need-to-know/corporate-tax/)
- [Sail Global — Brazil Tax Guide 2025](https://www.sailglobal.com/tax-guides/br/brazil-tax-guides-en)
- [HCO — Corporate Accounting & Taxation in Brazil](https://www.hco.com/insights/corporate-accounting-taxation-in-brazil-guide-for-global-businesses)
- [Commenda — Brazil Corporate Tax Rates](https://www.commenda.io/brazil/corporate-tax-rates)
- [MyBusinessBrazil — IRPJ 2026](https://mybusinessbrazil.com/corporate-income-tax-in-brazil-2026-what-should-you-do/)
- [BLB Escola de Negócios — JCP por holding](https://blbescoladenegocios.com.br/blog/recebimento-de-juros-sobre-o-capital-proprio-por-pessoa-juridica/)
- [Conjur — JCP STJ Tema 1.319 (2025)](https://www.conjur.com.br/2025-nov-25/tema-319-stj-e-o-pl-1-087-vantagens-do-jcp-no-planejamento-tributario/)
- [Migalhas — JCP economia tributária](https://www.migalhas.com.br/depeso/445787/juros-sobre-capital-proprio-economia-na-remuneracao-dos-socios)
- [LH Law — JCP retroactive STJ ruling](https://www.lhlaw.com.br/en/publicacoes/brazilian-supreme-court-of-justice-to-rule-on-retroactive-interest-on-net-equity-deductions-for-corporate-income-tax-purposes/)

**ECF / LALUR mechanics**

- [Junior Contador Digital — e-LALUR e e-LACS bloco M ECF](https://juniorcontador.com.br/e-lalur-e-o-e-lacs/)
- [IRKO — Série LALUR](https://site.irko.com.br/blog/serie-lalur-e-lalur-e-e-lacs/)
- [Alterdata — LALUR/LACS](https://ajuda.alterdata.com.br/bdcc/lalur-lacs-tudo-o-que-voce-precisa-saber-sobre-o-livro-de-apuracao-do-lucro-real-contribuicao-social-107071329.html)
- [InventSoftware — SPED ECF M300/M350 mapeamento](https://docs.inventsoftware.info/TaxOne/RelatoriosMagneticos/SPEDECF/GeracaoSPEDECF/MapeamentoSaldosLALUR(M300)LACS(M350).html)

**kontor source under review**

- `src/kontor/statute.clj` — ADR-101 evaluator (parameter resolution + applicable-provisions + apply-provisions fold + compute-fn registry)
- `src/kontor/tax_schedule.clj` — apply-base-transform :presumption-ratio (already shipped for this exact case) + apply-base-adjustments / apply-adjustments
- `src/kontor/period_tax_provider.clj` — PeriodTaxProvider + TaxReturnFacts + the closed 8-value period-tax-kinds enum
- `modules/l10n-de/src/kontor/l10n_de/cit_statute.clj` — the reference pattern
- `modules/l10n-de/src/kontor/l10n_de/cit_provider.clj` — the reference provider
- `doc/research/103-period-tax-coverage-proof.md` — surfaced BR Lucro Presumido as the driver for `:base-transform :presumption-ratio`
- `doc/research/120-de-cit-baseline-review.md` — note 121's structural twin
- `doc/decisions.md` ADR-099 / ADR-100 / ADR-101 / ADR-104

---

End of note 121.
