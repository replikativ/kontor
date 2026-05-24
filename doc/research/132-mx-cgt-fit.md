---
date: 2026-05-24
title: 132 — MX capital-gains tax (enajenación de bienes) — substrate fit for Phase 3
audience: maintainer + the Phase 3 `mx-cgt-provider` implementation agent
status: research-before for the MX CGT companion of `kontor-disposal` (ADR-102) + the future `mx-cgt-provider`; no code
---

# 132 — MX capital-gains tax: substrate fit for Phase 3

Mexico has **no separate CGT regime**. Capital gains on the disposal
of assets ("enajenación de bienes") are folded into the income-tax
stack (ISR — Impuesto Sobre la Renta) of the disponer, with three
critical Mexican twists:

1. **Personas físicas (individuals)** use a **gain-averaging
   mechanism** (art. 120 LISR): the gain is divided by years held
   (capped at 20), and the resulting fraction is added to other
   income at the **progressive ISR rate (0 – 35 %)**; the rest of the
   gain (the "no-acumulable" portion) is taxed at a separate average
   rate derived from the taxpayer's recent income history.
2. **INPC inflation adjustment**: the **cost basis is updated** for
   inflation between acquisition and disposal months (art. 121 LISR
   for individuals; art. 19 for corporations). Mexico has retained
   real-terms taxation, unlike BR (which dropped it in 1996) and IN
   (which mostly dropped it in 2024). This is a defining feature of
   the MX tax landscape.
3. **Notary-side provisional payment** (art. 126 LISR): on real-
   estate disposals before a notary public, the notary computes ISR
   under their responsibility and **withholds + remits** a
   provisional payment **plus a 5 % to the State** (art. 127), which
   is then credited at the annual return.

For corporations (**personas morales**), capital gains enter the
regular **30 % CIT base** under art. 9 LISR; gains on shares are
computed via the **costo promedio por acción** (art. 22 LISR) with
adjustments for CUFIN (Cuenta de Utilidad Fiscal Neta), pending tax
losses, and capital reductions — a uniquely Mexican mechanism that
prevents double taxation of corporate earnings already distributed
as dividends.

This note (a) summarises the MX regime, (b) walks two worked
examples, (c) assesses fit against `kontor-disposal`, (d) names data
gaps, (e) sketches `mx-cgt-provider`, (f) cites sources.

---

## §1. The MX CGT regime — by taxpayer type

### 1.1 Personas físicas — art. 120 averaging + INPC adjustment

Source: [LISR Título IV Capítulo IV — Justia México](https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-iv/);
[Artículo 120 LISR (Justia)](https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-iv/seccion-i/);
[Artículo 120 (SAT portal)](https://www.sat.gob.mx/articulo/31901/articulo-120);
[Indetec — Guía Práctica ISR Enajenación Inmuebles](https://www.indetec.gob.mx/delivery?srv=0&sl=3&path=%2Fbiblioteca%2FEspeciales%2F385_Guia_Practica_ISR_Enajenacion_Inmuebles.pdf).

#### 1.1.1 Computing the gain

Per art. 121 LISR, the gain on a real-asset disposal by an individual
is:

```
gain = consideration
       − INPC-updated original cost (MOI)
       − INPC-updated cost of improvements
       − INPC-updated commissions, notarial fees, taxes paid at acquisition
       − local taxes on the disposal paid by the seller
       − selling commissions
```

The INPC adjustment factor is `INPC[month-before-disposal] /
INPC[month-of-acquisition]` — see [Fiscalidad MX](https://fiscalidadmx.com/calculo-de-isr-por-enajenacion-de-bienes-inmuebles/).
This **always reduces** the taxable gain (and may even produce a
loss if cumulative inflation has outrun nominal appreciation).

For depreciable assets (buildings; not land), art. 124 LISR allows a
**3 % annual depreciation** (5 % for commercial buildings) on the
INPC-updated construction cost, but only when the seller did NOT
already depreciate it elsewhere (no double dip).

#### 1.1.2 The art. 120 averaging mechanism (the MX-unique twist)

Once the gain is computed, art. 120 splits it into two parts:

- **Ganancia acumulable** (acumulable part) = `gain / years-held`
  (capped at 20 years). This piece joins the taxpayer's other
  cumulative income for the year, taxed at the **art. 152
  progressive schedule** (11 brackets, 1.92 % to 35 % marginal).
- **Ganancia no acumulable** (non-cumulative part) = `gain × (years-1) /
  years` (i.e. the rest). This is taxed at a **separate "tasa
  efectiva"** (effective rate) — computed two ways and the
  taxpayer **elects** the lower:
  - **Option A — current-year rate**: marginal tax / acumulable
    income, applied to the no-acumulable portion.
  - **Option B — 5-year-average rate**: average effective rate over
    the last 5 tax years, applied to the no-acumulable portion (per
    [SAT art. 120](https://www.sat.gob.mx/articulo/31901/articulo-120)).
  - Option B's "average" formula: `Σ effective-rates / 5`, where the
    effective rate for each year = `tax-paid / acumulable-income`.
  - If the taxpayer had no acumulable income in any of the prior 4
    years, the rate is computed as if the no-acumulable portion had
    been distributed evenly across those years.

Net effect: capital gains on long-held assets are taxed at **lower
effective rates** than the headline 35 %, because the averaging
diffuses the gain across the holding period.

#### 1.1.3 Notary-side provisional payment (art. 126 + 127)

Source: [SoyConta — ¿Cuánto se paga de ISR en enajenación de inmuebles?](https://www.soyconta.com/cuanto-se-paga-de-isr-en-enajenacion-de-inmuebles/);
[BCS Tramites — ISR a Entidades Federativas (5% Notarios)](https://tramites.bcs.gob.mx/catX/isr-a-entidades-federativas-enajenacion-de-bienes-inmuebles-5-notarios/).

For real-estate sales before a notary public (most non-broker
transactions):

- **Art. 126 — Federal provisional payment**: notary computes ISR
  under their responsibility (using the art. 120 averaging) and
  remits within **15 days** of the deed signing. Treated as a
  prepayment; the seller credits it on the annual return.
- **Art. 127 — State 5 %**: an **additional 5 %** of the gain is
  withheld and remitted to the **Entidad Federativa** (state); the
  seller credits this against the art. 126 federal provisional.
- Notaries, brokers, and judges with notarial functions all have
  this responsibility.

Result: an individual selling real estate sees ~80-95 % of the
ultimate ISR liability collected at closing; the annual return
reconciles via averaging and the 5-year rate election.

#### 1.1.4 Personas físicas — exemptions (art. 93 LISR)

Source: [Veritas — ISR en la enajenación de una casa habitación](https://www.veritas.org.mx/Ambito-universitario/Ambito-universitario/ISR-en-la-enajenacion-de-una-casa-habitacion);
[Hogare — Guía 2025 sobre el ISR y cómo exentarlo](https://hogare.mx/blog/vendes-tu-casa-guia-2025-sobre-el-isr-y-como-podrias-exentarlo/);
[AMPI — Fundamento para Exentar ISR](https://ampi.org/fundamento-para-exentar-isr-por-enajenacion-a-cargo-de-personas-fisicas-extranjeras-para-el-ano-2022/);
[Padilla-Bujalil — Exención del ISR Casa Habitación](https://www.padilla-bujalil.com.mx/exencion-del-isr-en-la-enajenacion-de-la-casa-habitacion-del-contribuyente/).

| Art. 93 fracción | Exemption                                                | Cap / condition |
|------------------|----------------------------------------------------------|------------------|
| **XIX a)**       | Casa habitación (principal residence)                    | Consideration ≤ **700,000 UDIS** (≈ MXN 6.2 M at UDI ≈ 8.84 in May 2026 per [calculadora-udi](https://calculadora-udi.com/)); once per 3 years; formalised before a notary; declared under oath |
| **XIX b)**       | Bienes muebles (movable goods)                           | Up to 3 SMG (salario mínimo general) per year for non-investment movables — narrow |
| **XIX c)**       | Goods inherited / donated / acquired by adverse possession | Subject-to-tax via separate Title IV Cap V (income from acquisition) — out of CGT scope |
| **XIX d)**       | Derechos parcelarios (ejido / communal land rights)      | Specific to ejidatarios |

The casa-habitación exemption is the major one. The 700k UDIS ceiling
is **on consideration**, not on the gain — disposals with
consideration ≤ 700k UDIS are **fully exempt**; above, the **excess
proceeds (proportional gain)** are taxed under art. 120. The
"once per 3 years" rule (added by 2014 reform; tightened from
"once ever" of pre-2014) is verified by the notary via SAT records.

Land area: per RLISR art. 129, "casa habitación" includes land up to
**3× the construction area** — wider tracts split into "house" + "land
beyond" with the latter taxed.

#### 1.1.5 Personas físicas — loss treatment (art. 122)

Source: [IDC — Pérdida en la enajenación de bienes muebles](https://idconline.mx/fiscal-contable/2023/05/30/perdida-en-la-enajenacion-de-bienes-muebles-deducible);
[Stratego — Tratamiento fiscal de la enajenación de inmuebles](https://www.stratego-st.com/articulos/tratamiento-fiscal-de-la-enajenacion-de-inmuebles/).

Losses on real estate, shares, or partnership interests by individuals
follow a **3-year carry-forward with a divide-and-conquer rule**:

1. The loss is **divided by years held** (capped at 10 — note this
   is 10, NOT 20 as for gains).
2. **Part A (loss / years)** offsets **other income of the same year**
   (cumulative income except Chapters I salary + II business
   activity).
3. **Part B (loss × (years-1) / years)** can offset gains from disposals
   of similar property in the **same year OR the following 3 calendar
   years**.
4. After 3 years, unused Part B is **lost** (no further carry).

Losses on movable goods (excluding investments) are **NOT
deductible** (per art. 121 last paragraph + IDC commentary).

#### 1.1.6 Personas físicas — shares disposed via Mexican stock exchange

Source: [Articulo 129 LISR (Justia)](https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-iv/seccion-ii/);
[Soltum — Tratamiento de acciones extranjeras en SIC](https://soltum.com.mx/tratamiento-fiscal-de-la-enajenacion-de-acciones-extranjeras-en-el-sic/);
[IDC — Anual 2024 declaración por venta de acciones en bolsa](https://idconline.mx/fiscal-contable/2025/04/25/anual-2024-declaracion-por-venta-de-acciones-en-bolsa).

Per art. 129 LISR (the bolsa lane — Sección II of Capítulo IV):

- **Definitive 10 % rate** on gains from the disposal of shares,
  ETFs, FIBRAS (REITs), or share-derivative warrants traded through
  **Bolsa Mexicana de Valores (BMV)** or **BIVA**, OR shares of
  foreign companies listed on those exchanges (incl. via the
  **Sistema Internacional de Cotizaciones, SIC**).
- The 10 % is computed on a **net basis**: aggregate annual gains
  minus aggregate annual losses (within the same lane).
- Losses on this lane **carry forward 10 years** within the lane
  (longer than the 3-year general rule).
- The 10 % is **outside** the cumulative income — it is a **separate
  schedular tax**; provider returns a separate component.
- The brokerage is **NOT** required to withhold (intermediaries
  inform the SAT; the seller self-assesses).

SAT's criterio 37/ISR/N extends this to **foreign shares listed via
SIC even if traded outside Mexico** (controversial extension; some
taxpayers litigate this).

### 1.2 Personas morales (corporations) — art. 9 + art. 18 + arts. 19-22

Source: [LISR Título II Cap I — Justia](https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-ii/capitulo-i/);
[Leyes MX — Art 22 LISR](https://leyes-mx.com/ley_del_impuesto_sobre_la_renta/22.htm);
[ACFMX — Enajenación de acciones](https://acfmx.com/blog/185-enajenacion-de-acciones-acfmx);
[IDC — Venta de acciones: claves fiscales y ajustes en CUFIN](https://idconline.mx/fiscal-contable/2025/11/20/venta-de-acciones-claves-fiscales-y-ajustes-en-cufin);
[miskuentas — Determinación de la ganancia por enajenación de acciones](https://www.miskuentas.com/noticias/actualidad/determinacion-para-obtener-la-ganancia-por-la-enajenacion-de-acciones/).

No averaging, no schedular split, no preferential rate. Corporate
gains fold into the **30 % flat CIT** (art. 9 LISR) via the regular
income-determination machinery.

#### 1.2.1 Land disposals — art. 19 LISR

Gain on land disposal = sale price − MOI × INPC-adjustment-factor
(`INPC[month-before-sale] / INPC[month-of-acquisition]`). The
adjusted-MOI lower-bounds the gain at the real (inflation-adjusted)
appreciation. Result joins art. 18-IV's "ingresos acumulables."

#### 1.2.2 Depreciable-asset disposals — art. 31 + art. 18-IV

Gain = sale price − **(MOI × INPC-adj − accumulated INPC-adjusted
depreciation)**. The "saldo pendiente de deducir actualizado" is the
NBV-equivalent in inflation-adjusted terms. Result also into art. 18-
IV. **No depreciation recapture** as a separate ordinary-income lane
(unlike US §1245/1250) — the entire gain is ordinary CIT.

#### 1.2.3 Share disposals — art. 22 LISR + art. 22-A (costo promedio)

The **costo promedio por acción** machinery — a Mexican specialty:

1. Compute the **adjusted cost basis (costo comprobado de
   adquisición actualizado)** of each tranche of shares acquired.
2. Adjust for **CUFIN movements** between acquisition and disposal:
   if CUFIN increased between acquisition and sale, the increase
   is **added** to basis (proportionally to the holder's share —
   prevents double taxation since CUFIN represents post-tax retained
   earnings that the issuer already taxed at CIT).
3. Subtract **pending tax losses** (capital-loss type only) generated
   between acquisition and sale month.
4. Subtract **capital reimbursements** (CUCA — Cuenta de Capital de
   Aportación — payments back to shareholders not characterised as
   dividends).
5. Compute **average cost per share** = (adjusted basis sum) / (total
   shares held on disposal date).
6. Gain = (proceeds per share − average cost per share) × shares
   sold.

The CUFIN adjustment is the killer feature: a corporation that has
retained MXN 10 M of post-tax profits between two ownership dates of
its shareholder, when the shareholder sells, sees its **basis stepped
up by its proportional share of the MXN 10 M CUFIN increase** —
avoiding economic double-taxation that BR's regime, by contrast,
fully suffers.

A **dictamen** (certified-accountant valuation) is required for
large share disposals (>MXN 227,400,400 in 2026 indexed; see
art. 31-A CFF). Without dictamen, the buyer is **statutorily liable**
for a 25 % withholding on **gross** proceeds; with dictamen, the
withholding is on the computed **gain**.

#### 1.2.4 Annual inflation adjustment — art. 44-46 (separate from CGT)

Source: [Justia — Arts 44-46 LISR](https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-ii/capitulo-iii/);
[Algor — Procedimiento ajuste anual por inflación](https://cards.algoreducation.com/es/content/erxUOJ9q/ajuste-anual-inflacion-personas-morales);
[Facturama — ¿Cómo se realiza el ajuste anual por inflación?](https://facturama.mx/blog/ajuste-anual-por-inflacion/).

This is a **balance-sheet-level** inflation adjustment, NOT a CGT
mechanism. Corporations compute monthly averages of credits and
debts, multiply the differential by the year's inflation factor, and
recognise an accumulable (if debts > credits) or deductible (if
credits > debts) item. **Separate from `mx-cgt-provider`** — lives
in `mx-cit-provider`. Out of CGT scope but mentioned for
completeness; the CGT provider should NOT double-count.

### 1.3 Non-residents — Title V

Source: [Justia — Arts 153-175 LISR Título V](https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-v/);
[SAT — Art 161](https://www.sat.gob.mx/articulo/88443/articulo-161);
[Consultores MV — Impuestos sobre las ganancias por acciones empresas familiares](https://consultoresmv.org/blogs/impuestos-sobre-las-ganancias-por-acciones-en-las-empresas-familiares).

Non-residents pay on Mexican-sourced gains via withholding by the
buyer:

- **Real estate (art. 160)**: 25 % on **gross** consideration, OR
  35 % on **net** gain if the non-resident appoints a Mexican
  representative + obtains a dictamen.
- **Shares of Mexican company (art. 161)**: 25 % on **gross**, OR
  35 % on **net** with representative + dictamen.
- **Shares via BMV/BIVA (art. 161 last paragraph)**: 10 % on gain
  (same rate as residents under art. 129).
- **Treaty overrides** are widely used (most Mexican treaties cap
  share-gain taxation at residence, with anti-abuse carve-outs for
  real-estate-rich companies).

### 1.4 What does NOT exist in MX CGT

- **No holding-period rate split** like US/IN LTCG vs STCG. The
  averaging mechanism (art. 120) functions analogously — longer
  holds dilute the gain across more years — but it operates as a
  continuum, not a binary cliff.
- **No participation exemption** (no DE §8b, no UK SSE, no JP
  participation). Corporate gains on share disposals fully taxed at
  30 %, mitigated only by CUFIN adjustment.
- **No principal-residence reinvestment rollover** like US §1031 /
  IN §54 / BR art. 39. The art. 93-XIX-a exemption is a one-shot
  exclusion with a 3-year cooling-off, not a rollover.
- **No general business-asset rollover** like DE §6b / UK s152.
- **IETU (Impuesto Empresarial a Tasa Única)** — abolished 2014.
  Do NOT model it.
- **No formal LTCL/STCL split** (the loss-divisor mechanism per
  art. 122 is the MX-unique parallel).

---

## §2. Worked examples

### Example A — Persona física, casa habitación above 700k UDIS

Sra. Hernández sells her primary residence on 2026-04-10:

- Acquired 2018-08 for MXN 4,500,000 (INPC 2018-08 = 100.492).
- Sale price 2026-04 = MXN 9,500,000 (UDI on 2026-04-10 ≈ 8.78, so
  700k UDIS ≈ MXN 6,146,000).
- INPC 2026-03 (the month before sale) = 137.84 (illustrative).
- Construction value at acquisition = 70 % = MXN 3,150,000; land =
  30 % = MXN 1,350,000.

Step 1 — exemption test: consideration MXN 9.5 M > 700k UDIS ≈
MXN 6.146 M, so the disposal is **partly exempt, partly taxable**.
The exempt fraction = 6,146,000 / 9,500,000 = **64.69 %**; the
taxable fraction = **35.31 %**.

Step 2 — taxable consideration = MXN 9,500,000 × 35.31 % =
**MXN 3,354,450**.

Step 3 — INPC-adjusted basis (proportional to taxable share):

- INPC factor = 137.84 / 100.492 = **1.3716**.
- MOI updated = 4,500,000 × 1.3716 = MXN 6,172,200.
- Depreciation (3 %/yr × ~8 yrs on construction = 24 %) on the
  construction tranche, INPC-updated, then subtracted: 3,150,000 ×
  0.24 × 1.3716 = **MXN 1,036,945** depreciation.
- Adjusted basis = 6,172,200 − 1,036,945 = MXN 5,135,255.
- Taxable share of basis = 5,135,255 × 35.31 % = MXN 1,813,278.

Step 4 — gain = 3,354,450 − 1,813,278 = **MXN 1,541,172**.

Step 5 — apply art. 120 averaging (held ~ 7.7 years → 7 full years):

- Acumulable = 1,541,172 / 7 = **MXN 220,167**.
- No-acumulable = 1,541,172 × 6/7 = **MXN 1,321,005**.

Step 6 — Sra. Hernández's other 2026 cumulative income is
MXN 1,200,000 (salary + freelance). Adding acumulable = MXN
1,420,167, which falls in the **art. 152 bracket: between
MXN 1,000,000 and MXN 3,000,000, marginal rate 30 %** (illustrative;
actual rates per current art. 152 table).

- Tax on cumulative income with acumulable = ~MXN 277,000.
- Tax on cumulative income WITHOUT acumulable = ~MXN 218,000.
- Tax attributable to acumulable = 277,000 − 218,000 = **MXN 59,000**.
- Effective rate on acumulable = 59,000 / 220,167 = **26.80 %**.
- Tax on no-acumulable = 1,321,005 × 26.80 % = **MXN 354,029**.
- (Could elect Option B 5-year average if lower; not pursued here.)

Total ISR on the disposal = 59,000 + 354,029 = **MXN 413,029**.

Step 7 — notary withholding: federal art. 126 + state art. 127 5 %.
The notary already paid most of this; Sra. Hernández reconciles on
her DAA (Declaración Anual de Personas Físicas) for 2026.

Substrate trace: ONE `:disposal` with
- `:subject-kind :real-estate-private`,
- `:asset-class :mx-real-estate-casa-habitacion`,
- `:residence? true`,
- `:exemption-claimed #{:mx-art-93-XIX-a-casa-habitacion}`,
- `:acquired-on #inst "2018-08-15"`, `:disposed-on #inst "2026-04-10"`,
- `:proceeds-amount 9500000M`, `:basis-amount 4500000M`
  (NOMINAL — the provider does the INPC adjustment),
- `:depreciation-taken-amount 0M` (the seller did NOT depreciate
  separately; the provider applies art. 124's 3 %/year).

### Example B — Persona moral, share disposal with CUFIN

CorpCo (a Mexican S.A. de C.V.) holds 30 % of SubCo. CorpCo acquired
its 300 shares on 2020-03 for MXN 15,000,000. SubCo's CUFIN
increased from MXN 5,000,000 (2020-03) to MXN 25,000,000 (2026-04).
CorpCo sells all 300 shares on 2026-05-15 for MXN 28,000,000.

Step 1 — INPC-adjusted MOI: INPC 2026-04 / INPC 2020-03 ≈ 1.42.
Adjusted MOI = 15,000,000 × 1.42 = **MXN 21,300,000**.

Step 2 — CUFIN adjustment per art. 22:

- CUFIN increase = 25,000,000 − 5,000,000 = MXN 20,000,000.
- CorpCo's proportional share (30 %) = MXN 6,000,000.
- CUFIN adjustment **added to basis** = +MXN 6,000,000.

Step 3 — pending tax losses, capital reductions (assume zero for
this example).

Step 4 — adjusted basis = 21,300,000 + 6,000,000 = **MXN 27,300,000**.

Step 5 — average cost per share = 27,300,000 / 300 = MXN 91,000.

Step 6 — proceeds per share = 28,000,000 / 300 ≈ MXN 93,333.

Step 7 — gain per share = MXN 2,333; total gain = MXN 700,000.

Step 8 — CIT at 30 % = **MXN 210,000**.

Without the CUFIN adjustment, the nominal gain would have been
28,000,000 − 21,300,000 = 6,700,000 and CIT = 2,010,000 — a 10×
larger bill. The CUFIN mechanism prevented economic double taxation
on the MXN 6 M of SubCo retained earnings that SubCo had already
been taxed at 30 % CIT when earned.

Substrate trace: ONE `:disposal` with
- `:subject-kind :participation`,
- `:asset-class :mx-share-unlisted`,
- `:subject-form :corp`,
- `:ownership-fraction 0.30M`,
- `:acquired-on #inst "2020-03-15"`, `:disposed-on #inst "2026-05-15"`,
- `:proceeds-amount 28000000M`, `:basis-amount 15000000M`.

The `:audit-doc` carries the CUFIN-movement attestation (Mexican
practice — issuer-supplied formato of CUFIN evolution). The mx-cgt-
provider reads `:audit-doc` for the CUFIN deltas + capital reductions
(see gap §4 below).

---

## §3. `:disposal` schema fit assessment

### 3.1 `:holding-period` — semi-relevant for MX

MX has no fixed cliff, but **years held** is a critical input to:
- Art. 120 averaging (the divisor; capped at 20).
- Art. 122 loss treatment (divisor capped at 10).
- Art. 124 depreciation on residential construction (3 %/year on
  building tranche).

The shipped `:disposal/holding-period :short / :long / :n-a` enum
doesn't capture the **numeric** years-held that MX needs.

**Resolution**: the years-held computation is **provider-side**, derived
from `:acquired-on` and `:disposed-on` (both already in the schema).
The `:holding-period` enum is set to `:n-a` for MX disposals — the
rate is independent of the binary classifier; the provider works
with the dates directly.

### 3.2 `:asset-class` — MX vocabulary

| Value                                       | Meaning                                                |
|---------------------------------------------|--------------------------------------------------------|
| `:mx-real-estate-casa-habitacion`           | Primary residence (art. 93-XIX-a eligible)             |
| `:mx-real-estate-secondary`                 | Non-primary real estate (no exemption)                 |
| `:mx-real-estate-commercial`                | Commercial real estate                                 |
| `:mx-real-estate-rural`                     | Rural land / ejido (special rules)                     |
| `:mx-share-listed-bmv`                      | Listed share on BMV / BIVA (art. 129 — 10 % flat)      |
| `:mx-share-listed-sic`                      | Foreign listed share via SIC (art. 129 per SAT 37/ISR/N) |
| `:mx-share-unlisted`                        | Unlisted share, S.A./S.A. de C.V. (art. 22 costo promedio) |
| `:mx-partnership-interest`                  | S. de R.L. / Asociación en Participación interest      |
| `:mx-fibra`                                 | FIBRA (Mexican REIT) — special rules                   |
| `:mx-derivative`                            | Derivative (10 % under art. 129 if exchange-traded)    |
| `:mx-bullion-jewellery`                     | Movable goods (mostly non-deductible losses)           |
| `:mx-vehicle-used`                          | Used vehicles (special art. 93-XIX-b narrowness)       |
| `:mx-business-fixed-asset`                  | PM fixed asset (art. 31)                               |
| `:mx-business-land`                         | PM land (art. 19 — INPC-adjusted MOI)                  |
| `:mx-business-intangible`                   | Patent / trademark (PM regular treatment)              |

### 3.3 `:loss-bucket` — MX compartments

| Value                          | Meaning                                                |
|--------------------------------|--------------------------------------------------------|
| `:mx-pf-loss-real-asset`       | PF loss on real asset (art. 122 — 3-yr carry, divide rule) |
| `:mx-pf-loss-share`            | PF loss on share (art. 122 — same carry rules)         |
| `:mx-pf-loss-bolsa`            | PF loss on BMV/BIVA listed shares (art. 129 — 10-yr carry within lane) |
| `:mx-pf-loss-movable`          | PF loss on movables — **NOT deductible** (provider raises) |
| `:mx-pm-cgt-loss`              | PM capital loss — folds into regular CIT base, subject to art. 28 limits |
| `:mx-nr-no-carry`              | Non-resident — typically no carry, single-event tax    |

### 3.4 `:exemption-claimed` — MX vocabulary

| Value                                     | Article                  |
|-------------------------------------------|--------------------------|
| `:mx-art-93-XIX-a-casa-habitacion`        | Casa habitación 700k UDIS|
| `:mx-art-93-XIX-b-bienes-muebles`         | Movable goods (narrow)   |
| `:mx-art-93-XIX-c-herencia-donacion`      | Inheritance/donation (out of CGT — Ch V) |
| `:mx-art-93-XIX-d-derechos-parcelarios`   | Ejidatario parcel rights |
| `:mx-treaty-share-gain-resident-only`     | Treaty-based exemption (NR sharer) |

### 3.5 `:elective-regime` — MX vocabulary

| Value                                  | Meaning                                       |
|----------------------------------------|-----------------------------------------------|
| `:mx-art-120-option-a-current-year-rate`| Election of current-year effective rate       |
| `:mx-art-120-option-b-5yr-average-rate`| Election of 5-year average rate               |
| `:mx-art-161-dictamen-on-net`          | Non-resident dictamen election → 35 % on net gain (vs. 25 % gross) |
| `:mx-pm-resico`                        | PM under Régimen Simplificado de Confianza — different treatment |

### 3.6 `:rollover-into-asset` — minimal MX use

MX has no general rollover. The triple stays unused in MX provider
output, but is harmless as `:db.cardinality/one` optional fields.

---

## §4. Concrete data gaps

### Gap A — INPC adjustment factor lookup

The INPC monthly series is required for every gain computation. The
shipped schema carries `:acquired-on` and `:disposed-on` (months
derivable). The factor is `INPC[disp-1] / INPC[acq]`.

**Resolution**: ADR-101 `:parameter` of `:mx/inpc-monthly` with one
`:parameter-value` per (year, month) pair from 1995 to current. The
INPC is published by INEGI monthly; the `kontor-l10n-mx` parameter
file ships a static snapshot; a forward-compatible loader pattern
(per JP CIT) reads new values as INEGI publishes them. **Zero
schema gap.**

### Gap B — CUFIN movement and capital reduction tracking

For PM share disposals, the costo promedio adjustment needs **per-
holding-period CUFIN deltas** + **CUCA reductions** of the issuer
between acquisition and disposal months. This information is NOT in
the disponer's books — it comes from the **issuer's** records, via
a per-issuer attestation (the "Determinación de costo fiscal de
acciones" formato).

**Resolution**: this is **per-disposal input data**, not a kernel
concern. Two options:

1. **Audit-doc reference**: `:audit-doc` of category
   `:mx-cufin-attestation` carries the per-month CUFIN balance of
   the issuer between the two dates. Provider reads it.
2. **`:inputs` map**: the consumer hands the provider
   `:inputs {:mx-share-adjustments {<issuer-eid>
                                     {:cufin-deltas [...]
                                      :cuca-reductions [...]
                                      :pending-losses ...}}}`.

Option 2 is more substrate-aligned. **Zero schema gap.**

### Gap C — Notary withholding (federal + state 5 %)

Notary remits federal provisional (art. 126) + state 5 % (art. 127).
Same pattern as BR's IRRF and IN's TDS:

```clojure
:inputs {:mx-notary-withheld {:federal-provisional <Money>
                              :state-5pct          <Money>
                              :withheld-on         #inst "2026-04-25"}}
```

Provider credits both against the final annual ISR. Zero schema gap.

### Gap D — Art. 120 averaging — needs `:years-held` (computed) +
prior-5-year effective-rate history

The averaging mechanism needs:
1. **Years held** — computable from `:acquired-on` + `:disposed-on`.
2. **Other cumulative income for the year** — comes from the PIT
   provider (`mx-pit-provider`) via `:inputs :mx-other-cumulative-
   income`.
3. **5-year effective rate history** — comes from the consumer's
   filing history (or computed from prior-year PIT-provider
   outputs).

All three are provider-side. Zero schema gap, but documents the
**cross-provider coupling**: `mx-cgt-provider` must read the
`mx-pit-provider`'s output (or input) to compute the cumulative-
income-with-acumulable. Recommendation: model as a **two-pass
query** within the same period — provider computes its own
`acumulable`, sends it to `mx-pit-provider` as a `:base-transform-
add`, gets back the **delta in PIT liability** as the tax on the
acumulable, applies the same effective rate to the no-acumulable.
Pattern: the IN slab-rate STCG `:base-transform-add` to `in-pit-
provider` (see note 131 §5.2) at a smaller scale.

### Gap E — Casa habitación 3-year cooling-off proof

The 700k UDIS exemption is "**once every 3 years**". Proof: the
seller declares under oath, the notary verifies via SAT consult.
For substrate purposes, the consumer's prior-disposal log captures
this — the recorder helper checks the disposal log for any prior
`:exemption-claimed #{:mx-art-93-XIX-a-casa-habitacion}` within 3
years and warns/blocks. **Zero schema gap** (uses existing log).

### Bottom line — schema posture

**Zero schema changes** required. MX fits the shipped shape with:

- 15 new `:disposal/asset-class` values.
- 6 new `:disposal/loss-bucket` values.
- 5 new `:disposal/exemption-claimed` keywords.
- 4 new `:disposal/elective-regime` keywords.
- `:inputs` extensions for INPC, CUFIN/CUCA, notary withholding,
  cumulative-income coupling.

All open-vocabulary extensions in `kontor-l10n-mx`. Kernel and
disposal-companion untouched.

---

## §5. `mx-cgt-provider` sketch

### 5.1 Component count

ONE `TaxReturnFacts` per assessed entity per tax year. Component count
depends on taxpayer kind:

**For personas físicas**, up to **four components**:
- `:mx-pf-art-120-acumulable` — base = gain/years; folds into PIT
  via `:base-transform-add` to `mx-pit-provider`.
- `:mx-pf-art-120-no-acumulable` — base = gain × (years-1)/years;
  schedule = effective-rate (computed via two-pass against PIT).
- `:mx-pf-art-129-bolsa` — base = annual net bolsa gain; schedule =
  `:flat 0.10M`; loss carry-forward within lane (10 years).
- `:mx-pf-clawback-cooling-off` — rare, triggered if a prior
  casa-habitación exemption is invalidated.

**For personas morales**, ONE component that folds into CIT:
- `:mx-pm-cgt-fold` — base = (proceeds − adjusted basis − CUFIN
  adj); not its own tax computation; emits a
  `:base-transform-add` to `mx-cit-provider` (which then applies
  30 % flat under art. 9).

**For non-residents**, ONE component per disposal:
- `:mx-nr-gross-25` or `:mx-nr-net-35` based on
  `:elective-regime :mx-art-161-dictamen-on-net`.

### 5.2 Schedule algebra

- Bolsa 10 %: `(ts/flat 0.10M)`.
- NR gross 25 %: `(ts/flat 0.25M)`.
- NR net 35 % with dictamen: `(ts/flat 0.35M)`.
- Art. 120 acumulable: **delegated** to PIT (schedule = the PIT
  schedule itself).
- Art. 120 no-acumulable: **dynamic flat** computed from prior pass —
  needs a schedule constructor that takes a runtime rate (kontor.tax-
  schedule already supports this via the `:flat` constructor accepting
  a BigDecimal arg).

### 5.3 The two-pass query (art. 120 averaging)

```clojure
(defn mx-art-120-tax
  [{:keys [gain years-held] :as disposal} ctx]
  (let [acumulable     (m// gain (min years-held 20))
        no-acumulable  (m/- gain acumulable)
        pit-without    (pit-tax-without ctx)
        pit-with-acc   (pit-tax-with-additional-income ctx acumulable)
        tax-on-acc     (m/- pit-with-acc pit-without)
        effective-rate (m// tax-on-acc acumulable)
        tax-on-no-acc  (m/* no-acumulable effective-rate)]
    {:acumulable acumulable :tax-acumulable tax-on-acc
     :no-acumulable no-acumulable :tax-no-acumulable tax-on-no-acc
     :total (m/+ tax-on-acc tax-on-no-acc)}))
```

Option A (current-year rate) is the default; Option B (5-year
average) is opt-in via `:elective-regime :mx-art-120-option-b-5yr-
average-rate`. Provider computes both, surfaces the elected one,
records the unused alternative in `:line-items` for audit.

### 5.4 The CUFIN / CUCA pass (art. 22 for PM shares)

```clojure
(defn mx-art-22-costo-promedio
  [{:keys [proceeds basis acquired-on disposed-on ownership-fraction]
    :as disposal}
   {:keys [inpc-table issuer-cufin-evolution issuer-cuca-reductions
           pending-losses] :as ctx}]
  (let [inpc-factor      (inpc-factor-between inpc-table acquired-on disposed-on)
        moi-adjusted     (m/* basis inpc-factor)
        cufin-delta      (sum-cufin-deltas issuer-cufin-evolution acquired-on disposed-on)
        cufin-add        (m/* cufin-delta ownership-fraction)
        cuca-deduct      (sum-cuca-reductions issuer-cuca-reductions acquired-on disposed-on)
        losses-deduct    (sum-pending-losses pending-losses acquired-on disposed-on)
        adjusted-basis   (m/- (m/+ moi-adjusted cufin-add)
                              cuca-deduct losses-deduct)]
    {:adjusted-basis adjusted-basis
     :gain (m/- proceeds adjusted-basis)}))
```

### 5.5 Authority and emission

`:authority :mx-sat`. Filings:
- **DAA (Declaración Anual de Personas Físicas)** — annual personal
  return, includes "Otros ingresos" with art. 120 averaging
  computation.
- **Declaración Anual de Personas Morales** — annual corporate
  return (forma 18), CGT folds into general income line.
- **Pagos provisionales** — notary remittance via DEP (Declaración
  Estandarizada de Pagos) for real estate; intermediary INFOSAT for
  bolsa.
- **Dictamen de Enajenación de Acciones** — Contador Público Inscrito
  formato required for large PM share disposals.

A v2 `kontor-l10n-mx-cgt-emit` extension can synthesise the DAA XML.
v1 ships the computation only.

---

## §6. ADR-101 statute-as-data — what MX CGT writes

```clojure
;; The casa habitación cap (700k UDIS)
{:parameter/code :mx/cgt-casa-habitacion-cap-udis
 :parameter/values [{:parameter-value/effective-from #inst "2014-01-01"
                     :parameter-value/amount 700000M
                     :parameter-value/unit :udis}]}

;; UDI daily series — too large for parameter snapshot; loaded as
;; `:fx-rate/*`-like time series (per ADR-072 pattern). Provider
;; queries the value at the disposal date.

;; The INPC monthly series — similar treatment.

;; The art. 129 bolsa rate
{:parameter/code :mx/cgt-bolsa-rate
 :parameter/values [{:parameter-value/effective-from #inst "2014-01-01"
                     :parameter-value/rate 0.10M}]}

;; The art. 120 averaging caps
{:parameter/code :mx/cgt-anos-gain-cap
 :parameter/values [{:parameter-value/effective-from #inst "2014-01-01"
                     :parameter-value/amount 20M}]}
{:parameter/code :mx/cgt-anos-loss-cap
 :parameter/values [{:parameter-value/effective-from #inst "2014-01-01"
                     :parameter-value/amount 10M}]}

;; The art. 122 loss carry years
{:parameter/code :mx/cgt-loss-carry-years
 :parameter/values [{:parameter-value/effective-from #inst "2014-01-01"
                     :parameter-value/amount 3M}]}
{:parameter/code :mx/cgt-bolsa-loss-carry-years
 :parameter/values [{:parameter-value/effective-from #inst "2014-01-01"
                     :parameter-value/amount 10M}]}

;; The art. 161 NR rates
{:parameter/code :mx/cgt-nr-share-gross-rate
 :parameter/values [{:parameter-value/effective-from #inst "2014-01-01"
                     :parameter-value/rate 0.25M}]}
{:parameter/code :mx/cgt-nr-share-net-rate
 :parameter/values [{:parameter-value/effective-from #inst "2014-01-01"
                     :parameter-value/rate 0.35M}]}
```

UDI and INPC monthly series are too large for parameter snapshots
(daily UDI ≈ 11,000 values since 1995). Use the **fx-rate-style
time series pattern** of ADR-072 — load incrementally, query at
specific dates. MX UDI/INPC ride the same primitive.

Provisions (art. 93-XIX-a, art. 122 loss treatment, art. 22 costo
promedio) stay in the record-shaped provider for Phase 2 (per note
102 §10). The complexity of art. 120 averaging and art. 22 CUFIN
adjustment is high enough that ADR-101 `:provision`-shape migration
may be appropriate in Phase 3.

---

## §7. Sources

### MX statutory primary

- **Ley del ISR (LISR)**:
  - Título II Cap I — Ingresos PM. [Justia](https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-ii/capitulo-i/).
    Arts. **9** (CIT rate 30 %), **16-19** (income; art. 19 land gain),
    **20-22** (share gain, costo promedio), **22-A** (dictamen),
    **44-46** (annual inflation adjustment — out of CGT).
  - Título II Cap II — Deducciones; art. 31 (depreciable assets).
  - Título IV Cap IV — Enajenación de Bienes PF.
    [Justia](https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-iv/).
    Sección I (general régimen): art. **119** (alcance), **120**
    (averaging), **121** (deductions), **122** (loss treatment), **124**
    (depreciation on construction), **126** (federal provisional),
    **127** (state 5 %).
  - Sección II (bolsa): art. **129** (10 % flat on listed-share gain).
  - Título IV Cap I — Exenciones; art. **93** fr. XIX (casa habitación,
    movables).
  - Título V — Residents in extranjero; arts. **153-175**,
    incl. **160** (real estate NR) + **161** (shares NR).
- **CFF (Código Fiscal de la Federación)** art. 31-A (dictamen
  threshold).
- **RLISR (Reglamento de LISR)** art. 129 (3× construction-area rule
  for casa habitación land).

### SAT regulatory

- [Artículo 120 — SAT portal](https://www.sat.gob.mx/articulo/31901/articulo-120)
  — averaging mechanism canonical reference.
- [Artículo 161 — SAT portal](https://www.sat.gob.mx/articulo/88443/articulo-161)
  — non-resident share-gain rate.
- [Criterio 37/ISR/N — SAT](https://wwwmat.sat.gob.mx/articulo/50782/criterio-46/isr/n)
  — extension of art. 129 to SIC foreign shares.
- [RMF 2025 Anexo 3](https://www.sat.gob.mx/minisitio/NormatividadRMFyRGCE/documentos2025/rmf/anexos/Anexo3_RMF2025_03012025.pdf)
  — 2025 regulatory updates.

### Reference / commentary

- [LISR full PDF — Diputados](http://www.diputados.gob.mx/LeyesBiblio/pdf/LISR.pdf).
- [Leyes MX — Art 22 LISR (costo promedio)](https://leyes-mx.com/ley_del_impuesto_sobre_la_renta/22.htm).
- [Contadores México — ISR en enajenación de inmuebles PF](https://www.contadoresmexico.org.mx/Boletin/ISR-enajenacion-inmuebles-personas-fisicas).
- [Indetec — Guía Práctica ISR Enajenación Inmuebles (PDF)](https://www.indetec.gob.mx/delivery?srv=0&sl=3&path=%2Fbiblioteca%2FEspeciales%2F385_Guia_Practica_ISR_Enajenacion_Inmuebles.pdf).
- [Fiscalidad MX — Cálculo de ISR por enajenación de inmuebles](https://fiscalidadmx.com/calculo-de-isr-por-enajenacion-de-bienes-inmuebles/).
- [Veritas — ISR en la enajenación de una casa habitación](https://www.veritas.org.mx/Ambito-universitario/Ambito-universitario/ISR-en-la-enajenacion-de-una-casa-habitacion).
- [Hogare — Guía 2025 sobre ISR casa habitación](https://hogare.mx/blog/vendes-tu-casa-guia-2025-sobre-el-isr-y-como-podrias-exentarlo/).
- [Padilla-Bujalil — Exención del ISR Casa Habitación](https://www.padilla-bujalil.com.mx/exencion-del-isr-en-la-enajenacion-de-la-casa-habitacion-del-contribuyente/).
- [ACFMX — Enajenación de acciones](https://acfmx.com/blog/185-enajenacion-de-acciones-acfmx).
- [IDC — Venta de acciones y ajustes CUFIN](https://idconline.mx/fiscal-contable/2025/11/20/venta-de-acciones-claves-fiscales-y-ajustes-en-cufin).
- [miskuentas — Determinación de ganancia por enajenación de acciones](https://www.miskuentas.com/noticias/actualidad/determinacion-para-obtener-la-ganancia-por-la-enajenacion-de-acciones/).
- [Pérez Góngora — Beneficios del dictamen por enajenación de acciones](https://www.perezgongora.com/blog/beneficios-del-dictamen-por-enajenacion-de-acciones).
- [SoyConta — ¿Cuánto se paga de ISR en enajenación de inmuebles?](https://www.soyconta.com/cuanto-se-paga-de-isr-en-enajenacion-de-inmuebles/).
- [BCS Tramites — ISR a Entidades Federativas 5 %](https://tramites.bcs.gob.mx/catX/isr-a-entidades-federativas-enajenacion-de-bienes-inmuebles-5-notarios/).
- [Soltum — Tratamiento fiscal de acciones extranjeras en SIC](https://soltum.com.mx/tratamiento-fiscal-de-la-enajenacion-de-acciones-extranjeras-en-el-sic/).
- [IDC — Anual 2024: declaración por venta de acciones en bolsa](https://idconline.mx/fiscal-contable/2025/04/25/anual-2024-declaracion-por-venta-de-acciones-en-bolsa).
- [Consultores MV — Impuestos sobre ganancias por acciones empresas familiares](https://consultoresmv.org/blogs/impuestos-sobre-las-ganancias-por-acciones-en-las-empresas-familiares).
- [Algor — Procedimiento ajuste anual por inflación](https://cards.algoreducation.com/es/content/erxUOJ9q/ajuste-anual-inflacion-personas-morales).
- [Facturama — Ajuste anual por inflación](https://facturama.mx/blog/ajuste-anual-por-inflacion/).
- [IDC — Pérdida en enajenación de bienes muebles](https://idconline.mx/fiscal-contable/2023/05/30/perdida-en-la-enajenacion-de-bienes-muebles-deducible).
- [Stratego — Tratamiento fiscal de la enajenación de inmuebles](https://www.stratego-st.com/articulos/tratamiento-fiscal-de-la-enajenacion-de-inmuebles/).
- [Calculadora UDI — Valor 2026](https://calculadora-udi.com/udi/2026)
  — UDI ≈ 8.78–8.84 in May 2026 (700k UDIS ≈ MXN 6.15–6.19 M).

### kontor substrate cited

- `modules/disposal/src/kontor/disposal/schema.clj` — the shipped
  disposal schema this note assesses. `:asset-class` at lines 111-
  120, `:exemption-claimed` at 227-233, `:elective-regime` at 219-
  225, dates at 132-148, money pairs at 164-201.
- `src/kontor/period_tax_provider.clj:44-61` — `:capital-gains-tax`
  in the closed enum.
- `src/kontor/period_tax_provider.clj:138-141` —
  `:capital-loss-carryforward` `:inputs` shape (extends to per-
  bucket map for MX).
- `src/kontor/tax_schedule.clj:64-90` — `:flat` for the bolsa lane,
  the NR lanes, and the dynamic rate from art. 120 Option A.
- `src/kontor/personal_income_tax.clj:71-83` — adjustment-layer
  pattern; the notary withholding rides as a `:credit` adjustment.
- `src/kontor/fx_rate_provider.clj` — the time-series primitive that
  UDI + INPC reuse (per §6 above).
- `modules/l10n-mx/src/kontor/l10n_mx/period_tax_provider.clj` —
  the existing MX PIT provider that art. 120 averaging couples to via
  `:base-transform-add` (per §5.3).
- `doc/research/107-phase-3-incorporation-and-disposal.md` §3 — the
  disposal schema this note exercises.
- `doc/research/112-us-cgt-fit.md` §5 — provider-sketch pattern reused.
- `doc/research/115-jp-cgt-fit.md` §5 — multi-component pattern reused.
- `doc/research/130-br-cgt-fit.md` — sibling note (BR); same posture on
  notary-withholding-as-`:inputs`, exemptions as provider-side folds.
- `doc/research/131-in-cgt-fit.md` — sibling note (IN); same posture on
  rollover, `:asset-class` open vocabulary, dispatch to PIT.

---

End of note 132.
