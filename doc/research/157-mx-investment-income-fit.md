---
date: 2026-05-24
title: 157 — MX investment-income regimes — substrate fit for Phase C2
audience: maintainer + the Phase C2 `mx-investment-income-provider` implementation agent
status: research-before for the MX investment-income companion (sibling to `mx-cgt-provider` of note 132) + the future `mx-investment-income-provider`; no code
---

# 157 — MX investment-income regimes: substrate fit for Phase C2

Mexican investment-income taxation centres on two textbook
ISR-Adicional features:

1. **Dividend integration via CUFIN + the 10 % ISR Adicional**
   (art. 140 LISR). The PF recipient grosses-up the dividend by
   the factor **1.4286** (≈ 1/(1−0.30)), reports the grossed-up
   amount in cumulative income, applies the personal slab rate, and
   credits the corporate ISR proxy (30 % × grossed amount = the
   factor-correction). On top of this, post-2014 distributions
   carry a **10 % definitive ISR Adicional** withheld at source by
   the distributing company — **the unique twist** that makes the
   effective combined rate ≈ 37 % even on the corporation's CUFIN
   distributions. The SCJN confirmed the constitutionality of this
   10 % overlay on 8 January 2026, ending years of taxpayer
   litigation.
2. **Bank-interest provisional withholding** (art. 54 LISR), an
   ISR provisional applied to the **daily-average balance** of
   peso deposits, **raised from 0.50 % to 0.90 %** for 2026 by
   the Ley de Ingresos de la Federación 2026. The PF credits it
   on the annual DAA against the full slab-rate ISR on actual
   interest (in real terms — INPC-adjusted nominal interest).

This sits alongside the MX CGT provider (note 132) and the existing
MX PIT provider (`modules/l10n-mx/src/kontor/l10n_mx/period_tax_provider.clj`)
without rate or structural overlaps: CGT covers asset disposals (incl.
art. 129 bolsa-listed 10 % on share gains); PIT covers cumulative slab
income; the new investment-income provider covers **dividends,
interest, and other passive income** that flow through CUFIN / art.
54-55 mechanics.

This note (a) summarises each lane with the 2026-enacted state, (b)
walks two worked examples (PF with CUFIN dividend + bank interest;
PJ-to-PJ dividend from CUFIN), (c) assesses fit against the shipped
substrate, (d) names the data gaps, (e) sketches the
`mx-investment-income-provider`, (f) cites sources.

---

## §1. The MX investment-income regime — by lane

### 1.1 Lane 1 — PF dividends via art. 140 (gross-up + 10 % Adicional)

Source: [Artículo 140 LISR (Justia)](https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-viii/);
[SAT — Artículo 140](https://www.sat.gob.mx/articulo/32450/articulo-140);
[SDV — Art. 140 LISR 2026: ISR por Dividendos](https://sdv.com.mx/compendio/ley-isr/articulo-140/);
[vLex — Ingresos por dividendos PF art. 140 LISR](https://vlex.com.mx/vid/ingresos-dividendos-personas-fisicas-554227350);
[IDC — Qué impuestos se pagan por dividendos](https://idconline.mx/fiscal-contable/2025/08/11/que-impuestos-se-pagan-por-ingresos-obtenidos-en-dividendos);
[CS Contabilidad — Dividendos y sus Efectos Fiscales LISR](https://cscontabilidad.com/dividendos-y-sus-efectos-fiscales-en-mexico-todo-lo-que-debes-saber-segun-la-lisr);
[Heranza — Aspectos fiscales del decreto de dividendos](https://heranza.com/aspectos-fiscales-y-legales-del-decreto-de-dividendos/);
[Manuel Nevárez — Análisis de la distribución de dividendos](https://www.manuelnevarez.com.mx/es/journal/analisis-de-la-distribucion-de-dividendos/).

#### 1.1.1 The grossing-up mechanism

When a Mexican PJ distributes a dividend to a PF, the PF
**acumula** the dividend after multiplying by the factor:

```
factor = 1 / (1 − ISR-corporate-rate) = 1 / (1 − 0.30) = 1.4286
```

The arithmetic:
- Dividend declared from CUFIN: MXN 100,000.
- Grossed-up: 100,000 × 1.4286 = **MXN 142,860**.
- This amount enters the PF's cumulative income.
- The PF applies the art. 152 slab (top marginal 35 %).
- Against the slab tax, the PF **credits** the deemed corporate
  ISR: 142,860 × 0.30 = **MXN 42,860**.
- The credit is **not** refundable — it can offset other slab
  income's ISR but not exceed the dividend's own gross-up step.

Net effect for a PF in the 35 % top bracket:
- Slab tax on the grossed-up: 142,860 × 0.35 = MXN 50,001.
- Credit: 42,860.
- Marginal ISR on the dividend: MXN 7,141 (= MXN 100,000 × 7.14 %).
- **Plus** the 10 % Adicional (see §1.1.3) → MXN 10,000.
- **Plus** the corporate ISR already paid by the PJ: 42,860.
- **Total economic tax** on the underlying MXN 142,860 of corporate
  pre-tax profit: ~60,000 ≈ **42 %** combined.

For a PF in lower brackets, the credit can fully offset the
slab tax (i.e., the dividend is effectively taxed only at the
corporate ISR rate of 30 % plus the 10 % Adicional = 40 %).

#### 1.1.2 CUFIN — the post-tax retained-earnings account

CUFIN (Cuenta de Utilidad Fiscal Neta) tracks **post-CIT** retained
earnings. Dividends paid from CUFIN are deemed to have already
borne the 30 % CIT, which is precisely why the gross-up + credit
mechanism works arithmetically: the gross-up reverses the 30 %
to put the PF on the "received gross profit" footing.

Dividends paid **outside** CUFIN (i.e., from pre-tax profit, or
from CUCA capital reductions deemed dividend-like) bear an
**additional corporate ISR** at distribution (the PJ pays 30 % on
the grossed-up amount as a definitive tax — art. 10 LISR), AND
the PF still picks up the same grossing-up + 10 % Adicional.
Effective combined rate on out-of-CUFIN distributions:
~60 % +.

CUFIN is **per-issuer** and **bitemporally segmented**: there are
**two parallel CUFIN balances**, one for pre-2014 profits and one
for post-2014 profits. The 10 % Adicional (§1.1.3) applies
**only to the post-2014 CUFIN slice**. Distinguishing the two
requires per-issuer attestation — see Gap A below.

#### 1.1.3 The 10 % ISR Adicional (post-2014 CUFIN only)

Source: [Cronista — SCJN aprobó el 10% extra a los dividendos](https://www.cronista.com/mexico/actualidad-mx/es-oficial-y-no-hay-vuelta-atras-la-suprema-corte-aprobo-el-10-extra-a-los-dividendos-del-isr-y-las-empresas-tendran-que-abonar-mas/);
[Cronista — La Suprema Corte falló a favor del 10% adicional](https://www.cronista.com/mexico/actualidad-mx/la-suprema-corte-fallo-a-favor-del-cobro-extra-de-10-a-los-dividendos-del-isr-celebra-el-gobierno-de-mexico/);
[Calculadora ISR Dividendos 2026](https://cotizadorhipotecario.mx/impuestos/calculadora-isr-dividendos/).

Effective FY 2014 onwards, the distributing PJ must **withhold a
10 % ISR Adicional** on dividends paid to PF (and to non-residents,
under separate art. 164). The withholding is **definitive** —
cannot be credited against the PF's slab tax. It applies **only**
to dividends sourced from the **post-2014 CUFIN** slice.

The constitutional challenge (multiple amparo proceedings since
2014 arguing double taxation + violation of capacidad contributiva)
was **finally resolved by the SCJN Pleno on 8 January 2026** in
favour of the government, ending the litigation. As of the date
of this note, the 10 % Adicional is **definitively settled law**.

To **avoid** the 10 % Adicional, a corporation must:
- Maintain **two separate CUFIN ledgers** (pre-2014, post-2014);
- Distribute **first** from the pre-2014 CUFIN until exhausted;
- **Demonstrate the source** via the **constancia de retenciones e
  información de pagos** (the dividend receipt issued to the
  shareholder, which itemises CUFIN vs. non-CUFIN vs. gravable-
  income source).

If the corporation cannot demonstrate pre-2014 sourcing, **the law
presumes post-2014** and the 10 % Adicional applies.

#### 1.1.4 The constancia requirement

Per art. 76 fr. XI LISR, the PJ issues a **constancia de
retenciones e información de pagos** to every dividend recipient,
detailing:
- Dividend amount, gross.
- Source: CUFIN-pre-2014, CUFIN-post-2014, gravable income, or
  capital-reduction-deemed-dividend.
- 10 % Adicional withheld (if applicable).
- Date.

This **constancia is the audit trail** for the PF's annual return.
The PF needs it to (a) demonstrate the gross-up + credit mechanism,
and (b) prove the 10 % Adicional was correctly applied (or correctly
not applied for pre-2014-sourced dividends).

For substrate: the constancia maps cleanly to ADR-038 `:audit-doc`
with category `:mx-constancia-retenciones-dividendos`.

#### 1.1.5 Capital reductions — art. 78 deemed-dividend

When a PJ reduces its capital below its CUCA (Cuenta de Capital de
Aportación) balance, the excess is **deemed a dividend** for tax
purposes. The 10 % Adicional applies, the PJ pays the art. 10
distribution tax, the PF acumulación + gross-up runs. This is
the corporate-finance "anti-disguised-dividend" rule.

Substrate impact: zero. The deemed-dividend transaction looks
identical on the wire to a regular dividend (a `book.declare-
dividend!` entry with `:audit-doc/category :mx-capital-reduction-
deemed-dividend`); the provider's classifier treats it identically.

### 1.2 Lane 2 — PF interest income (art. 54-55, bank withholding)

Source: [Notas Fiscales — Retención del ISR sobre intereses](https://notasfiscales.com.mx/retencion-del-isr-sobre-intereses/);
[Siempre al Día — Retención de ISR en intereses bancarios 2026 (0.90%)](https://siemprealdia.co/mexico/fiscal/retencion-de-isr-en-intereses-bancarios/);
[Ser Empresario — ISR 2026: Mayor retención en intereses](https://www.serempresario.com.mx/post/isr-2026-mayor-retenci%C3%B3n-en-intereses);
[Russell Bedford — Aumento en retención de intereses 2026](https://russellbedford.mx/fiscal/aumento-en-la-tasa-de-retencion-por-intereses-en-el-ejercicio-2026/);
[Fintualist — Nuevas tablas y retenciones 2026](https://fintualist.com/mexico/educacion-financiera/nuevas-tablas-y-retenciones-actualizadas-de-isr-para-tus-ingresos-e-inversiones-en-2026/);
[Cronista — SAT impone mano dura a retenciones 2026](https://www.cronista.com/mexico/finanzas-economia/el-sat-impone-mano-dura-a-las-retenciones-de-2026-iran-por-ahorros-bancarios-y-transacciones-digitales/);
[Buen Contador — Inversión 2026 retención ISR](https://buencontador.com/inversion-2026-retencion-sube-impuesto-no/).

#### 1.2.1 The provisional 0.90 % withholding

Per art. 54 LISR + the **Ley de Ingresos de la Federación 2026** (LIF
2026), banks and other financial-system entities **withhold an annual
ISR** at **0.90 % on the daily-average balance** of peso-denominated
deposits that generate interest in time instruments. The rate rose
from **0.50 % in 2025 to 0.90 % in 2026** — an 80 % increase
attributable to higher prevailing interest rates (the withholding is
calibrated to approximate the slab-rate ISR on the real interest).

The withholding is **provisional**:
- PF credits it on the annual DAA against the slab-rate ISR on
  real interest.
- If the PF's annual real interest is below the slab threshold (or
  losses occur due to inflation), the withholding is refundable.

#### 1.2.2 Real vs. nominal interest — the INPC adjustment

The PF's **acumulación** is the **real interest** — nominal interest
minus the **inflation adjustment** on the **principal** during the
period. Per art. 134 LISR:

```
real-interest = nominal-interest − (principal × INPC-factor)
```

where INPC-factor measures inflation between the start and end of
the holding period. In a high-inflation environment (which MX has
sometimes), the real interest can be **negative**, producing a
**deductible loss** (capped at the PF's other interest income — the
art. 135 last paragraph "límite" rule).

The INPC monthly series is the same one the MX CGT provider uses
for art. 121 basis adjustment (note 132 §4 Gap A) — share the
ADR-101 parameter `:mx/inpc-monthly`.

#### 1.2.3 SOFIPOs and savings co-ops — exemption

Per art. 93 fr. XX, the **first 5 UMAs** (in 2026: 5 × 42,794.64
annual = MXN 213,973.20) of interest received from **SOFIPOs**
(Sociedades Financieras Populares) and **savings cooperatives**
(SOCAPs) is **exempt**. Banks do **NOT** qualify — the exemption is
narrow and intentional (a vehicle for financial inclusion).

#### 1.2.4 Annual return threshold

A PF is **required to file** the annual DAA if real interest > MXN
100,000/year (art. 150 LISR). Below that, the 0.90 % withholding is
typically the final tax (no slab-rate recompute).

### 1.3 Lane 3 — PJ-to-PJ dividends from CUFIN (tax-free)

Source: [Justia — Art 16 LISR ingresos PM](https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-ii/capitulo-i/);
[CS Contabilidad — Dividendos y sus Efectos Fiscales](https://cscontabilidad.com/dividendos-y-sus-efectos-fiscales-en-mexico-todo-lo-que-debes-saber-segun-la-lisr).

Dividends from one Mexican PJ to another Mexican PJ are **not
acumulables** (art. 16 fr. III) when sourced from **CUFIN**. The
recipient PJ books the dividend as **tax-exempt income** (no CIT,
no Adicional). The PJ-to-PJ leg is fully relieved.

When the eventual ultimate PF shareholder receives a dividend
through the chain, the 10 % Adicional and the PF gross-up apply
**at the terminal distribution to the PF**, not at intermediate
PJ-to-PJ steps. CUFIN balances transfer up the chain (the parent's
CUFIN grows by the post-tax distributions received from its
subsidiary's CUFIN), preserving the no-double-taxation guarantee.

This is structurally identical to BR's PJ-to-PJ exemption under
Lei 9.249/95 art. 10 (preserved by Lei 15.270/2025, see note 155
§1.1.3); MX's mechanism is cleaner because CUFIN explicitly tracks
the post-tax pool.

### 1.4 Lane 4 — Foreign-source dividends and interest

Source: [Artículo 5 LISR (Justia)](https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-i/);
[Artículo 142 LISR (Justia)](https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-ix/).

Foreign-source dividends and interest are **acumulables** to the PF
at gross (no factor adjustment — the foreign company didn't pay MX
CIT). Foreign tax is creditable per art. 5 LISR, capped at the
MX ISR that would otherwise apply (per-country basket + an overall
cap). Foreign interest follows the same shape as domestic interest
but **without** the INPC inflation adjustment (the inflation
adjustment is currency-specific; foreign-currency principal is
exposed to FX gain/loss separately under art. 143).

For non-residents earning Mexican-source dividends, the **art. 164
LISR** definitive withholding applies: 10 % on the gross
distribution, increased per applicable treaty (most treaties cap at
5-15 % on portfolio dividends; many at 0 % for substantial
participation per the Latin-American treaty pattern).

This lane's substrate fit is the same as Lane 1 with a "foreign"
flag; v1 provider handles domestic + non-resident; foreign-source
inbound is a future companion `mx-foreign-source-income-provider`
or extension.

### 1.5 Lane 5 — Other passive income (rent, royalties)

Out of scope for this provider:
- **Rental income from PF real estate** → goes to MX PIT under
  art. 114 (separate ledger; this is **not** typical investment
  income but it shares the "passive" feel).
- **Royalty income** → mostly business-income for the PF; royalty
  WHT on PJ payments to PF rides §1.1's classification machinery
  but is more frequently business-income-handled.
- **PJ rental and royalty income** → folds into regular CIT base.

Recommendation: investment-income provider v1 covers Lanes 1-4
(dividends + interest, domestic + cross-border). Lane 5 is a
follow-on.

### 1.6 What does NOT exist in MX investment income

- **No high-earner minimum tax** like BR's IRPFM (Lei 15.270/2025 —
  note 155 §1.2). MX has art. 140's straightforward gross-up + 10 %.
- **No DDT-equivalent at the company level** for distributions —
  MX uses the gross-up + credit mechanism that achieves the same
  economic result without a corporate-level extra tax (the 10 %
  Adicional is **above** corporate ISR, not in lieu of recipient
  tax).
- **No franking or imputation system** like AU. MX's gross-up + credit
  is the closest analogue but distinct (the credit is direct, no
  cascading credit-balance ledger like AU's franking account).
- **No qualified vs. ordinary dividend split** like US. All MX
  dividends are treated identically (CUFIN sourcing being the only
  segmentation).
- **No principal-residence-style PF interest exemption** beyond the
  narrow SOFIPO 5-UMA carve-out.

---

## §2. Worked examples

### Example A — PF dividend from post-2014 CUFIN + bank interest

Sr. López, PF residing in CDMX, FY 2026:

- Salary: MXN 600,000 → ordinary ISR.
- Dividend from CorpA (Mexican S.A.), MXN 200,000, **from post-2014
  CUFIN** (per constancia). CorpA withholds 10 % Adicional =
  **MXN 20,000**.
- Bank interest from BBVA, MXN 80,000 nominal. Daily-average
  balance MXN 2,000,000 → 0.90 % × 2M = **MXN 18,000** withheld.
- INPC inflation adjustment on principal: MXN 60,000 (illustrative
  3 %/yr inflation).
- Real interest = 80,000 − 60,000 = MXN 20,000.

**Dividend computation (art. 140)**:
- Gross-up: 200,000 × 1.4286 = MXN 285,720.
- Goes into cumulative income.
- Acumulable to ISR: MXN 285,720.
- Credit against ISR: 285,720 × 0.30 = MXN 85,716.

**Interest computation (art. 134-135)**:
- Real interest acumulable: MXN 20,000.
- Withholding credit: MXN 18,000 (provisional).

**Salary**: MXN 600,000 acumulable.

**Total cumulative income**: 600,000 + 285,720 + 20,000 =
MXN 905,720.

**ISR on cumulative (art. 152 progressive)**: Sr. López falls in
the band MXN 750k-MXN 1M, marginal rate 30 %; using the published
2026 art. 152 table:
- Cumulative ISR ≈ MXN 192,000 (illustrative; per actual 2026
  tarifa).

**Less credits**:
- Corporate-ISR proxy on dividend: MXN 85,716.
- Bank interest withholding: MXN 18,000.
- Total credits: MXN 103,716.

**ISR payable on DAA** = 192,000 − 103,716 = **MXN 88,284**.

**Plus the 10 % Adicional already paid (definitive)**: MXN 20,000.

**Total economic tax** on the year: 88,284 + 20,000 = MXN 108,284
on MXN 905,720 income = **~12 % effective**.

Substrate trace: the provider reads `book.declare-dividend!` entries
where Sr. López is the partner; sees the constancia in
`:audit-doc :mx-constancia-retenciones-dividendos` declaring
"CUFIN-post-2014"; computes the gross-up + 10 % Adicional + the
factor-credit; for interest, reads `:inputs :mx-bank-interest
{:nominal-interest 80000M :daily-avg-balance 2000000M
:inpc-factor 0.03M}` and computes the real-interest acumulación +
the 0.90 % withholding credit.

The provider emits TWO components:
1. The dividend → base-transform-add into PIT for the grossed-up
   amount; emits a `:credit` adjustment for the factor-credit;
   emits the 10 % Adicional as a standalone definitive component.
2. The interest → base-transform-add into PIT for the real-interest
   amount; emits a `:credit` adjustment for the 0.90 % provisional
   withholding.

### Example B — PJ-to-PJ dividend from CUFIN, then to PF

ParentCo (Mexican S.A. de C.V.) wholly owns SubCo. SubCo's CUFIN
balance is MXN 30,000,000 (all post-2014). SubCo declares a
dividend of MXN 10,000,000 to ParentCo on 2026-03-15.

**SubCo → ParentCo leg**:
- Dividend from CUFIN → **not acumulable** for ParentCo (art. 16
  fr. III).
- ParentCo books: MXN 10M tax-exempt income, debit cash, credit
  exempt-income account.
- ParentCo's CUFIN increases by MXN 10M (the post-tax pool
  transferred up).
- **No Adicional** — Adicional applies only to PF / NR recipients
  per art. 140; PJ recipients are exempt.

Substrate trace: the dividend declaration on SubCo's books uses
`book.declare-dividend!`; ParentCo books the receipt as
`:account/category :exempt-income`. The provider's classifier sees
the recipient partner is a Mexican PJ (`:partner/jurisdiction :MX`,
`:partner/legal-form :persona-moral`), emits **zero components**
(no MX investment-income-tax due at the PJ level), simply records
the CUFIN movement on ParentCo's audit chain via a
`:line-item :cufin-credit` for downstream reuse.

Later, ParentCo distributes MXN 5,000,000 of its (now MXN
40M-balance) CUFIN to Mrs. Garcia (PF):
- ParentCo withholds 10 % Adicional = MXN 500,000 → SAT.
- Mrs. Garcia grosses-up: 5M × 1.4286 = MXN 7,143,000.
- Acumulates the gross-up in PIT; takes 30 % credit = MXN 2,143,000.

The chain shows the **integration is preserved**: no double-CIT,
single 10 % Adicional at the terminal step. This is the architecture
MX investment-income substrate must respect — the provider's
classifier on the **PJ recipient leg** emits zero tax components.

---

## §3. Substrate fit assessment

### 3.1 Lane 1 (Dividends) — base-transform-add to PIT + standalone Adicional

The dividend computation has two parts:
1. **Gross-up + slab + credit**: a `:base-transform-add` for the
   grossed-up amount into the PIT provider, plus a `:credit`
   adjustment item carrying the 30 % factor-credit. Per the IN
   note 156 §3.1 pattern (and BR note 155 §3.3 IRPFM-to-IRPF).
2. **10 % Adicional**: a **standalone definitive component** —
   `(ts/flat 0.10M)` on the dividend base, withheld by the PJ payer
   (the consumer books this as a remittance on the **payer's** side
   directly; on the **recipient's** side it's already-net cash).

The provider's classifier on the recipient's books reads the
constancia (`:audit-doc :mx-constancia-retenciones-dividendos`) to
determine source-CUFIN-bucket and compute the correct combination.
**Substrate impact: ZERO new primitives.**

### 3.2 Lane 2 (Interest) — real-interest computation + INPC

The real-interest = nominal − inflation-adjustment math requires:
- Nominal interest (from bank statement, consumer-fed via
  `:inputs :mx-bank-interest`).
- Principal daily-average balance (same source).
- INPC factor between deposit start and end (from ADR-101 parameter
  `:mx/inpc-monthly`, already needed by MX CGT — note 132 §4
  Gap A).

The provider computes the real interest, emits a `:base-
transform-add` (positive or negative — losses are allowed within
the interest lane) into the PIT provider; emits a `:credit`
adjustment for the 0.90 % provisional withholding.

The withholding rate cliff (0.50 % → 0.90 % from 2026-01-01) rides
ADR-101 `:mx/interest-withholding-rate` bitemporally.

**Substrate impact: ZERO new primitives.**

### 3.3 Lane 3 (PJ-to-PJ) — zero-component fold to CUFIN ledger

When the recipient is a Mexican PJ and the dividend is sourced from
CUFIN, the provider returns **no tax components** but emits an
**audit-only line item** recording the CUFIN movement. The CUFIN
balance lives on the **issuer's** books, not the recipient's
(though the recipient's CUFIN grows by the received amount); MX
CUFIN tracking is per-issuer.

For substrate: the existing CUFIN-attestation `:audit-doc/category`
(per MX CGT note 132 §4 Gap B) carries the data. This provider
participates by:
- Recognising the dividend transaction on the recipient PJ's books.
- Asserting (via `:line-items :pj-to-pj-cufin-exempt`) that no
  acumulación / Adicional applies.
- Optionally invoking the consumer's CUFIN-update side-effect
  helper (a separate companion's responsibility — the kontor-cufin
  companion may be a future Phase C3 / C4 module).

**Substrate impact: ZERO new primitives** at this companion level.

### 3.4 Lane 4 (Foreign-source) — gross + foreign-tax-credit

PF receives foreign-source dividend / interest: acumulación at
gross (no factor), foreign-tax credit up to the MX ISR otherwise
due. The provider reads `:inputs :mx-foreign-tax-credit
{:by-country {:US <Money> :CA <Money>}}`, computes per-country
basket caps, emits a `:credit` adjustment item.

**Substrate impact: ZERO** (reuses adjustment-item shape).

### 3.5 CUFIN-source attestation — `:audit-doc` carries it

The constancia's CUFIN-source flag (pre-2014 / post-2014 /
gravable-income / capital-reduction) determines the Adicional
applicability and the gross-up. The substrate carries this via
`:audit-doc/category :mx-constancia-retenciones-dividendos` +
a `:audit-doc/payload` map containing the structured constancia
data (e.g., `{:cufin-bucket :post-2014 :gross 200000M :adicional-
withheld 20000M}`).

**Substrate impact: ZERO** (uses existing `:audit-doc` shape).

### 3.6 Per-PJ-payer aggregation — same as BR / IN

The Adicional applies per-distribution; no monthly or annual
aggregation cap. So **no per-payer fold** needed (unlike BR's
R$ 50k/month/payer trigger or IN's per-section TDS thresholds).
This is the cleanest of the three jurisdictions.

### 3.7 Bottom line — substrate posture

**Zero schema changes.** MX fits the shipped shape with:
- 2 ADR-101 `:parameter`s: `:mx/dividend-adicional-rate` (the 10 %),
  `:mx/interest-withholding-rate` (the 0.90 % cliff).
- The existing `:mx/inpc-monthly` parameter (shared with MX CGT).
- 1 ADR-101 `:parameter`: `:mx/dividend-gross-up-factor` (the
  1.4286, which is derived from the 30 % CIT rate but a separate
  parameter keeps the audit trail clean).
- ~5 `:inputs` map keys: `:mx-bank-interest`,
  `:mx-foreign-tax-credit`, `:mx-cufin-source-attestation`,
  `:mx-recipient-form`, `:mx-other-cumulative-income`.
- 2 new `:audit-doc/category` keywords:
  `:mx-constancia-retenciones-dividendos`,
  `:mx-capital-reduction-deemed-dividend`.

Open-vocabulary extensions in `kontor-l10n-mx`. Kernel untouched.

---

## §4. Concrete data gaps

### Gap A — CUFIN-source determination per dividend

The 10 % Adicional turns on whether the dividend is sourced from
pre-2014 CUFIN or post-2014 CUFIN. The substrate has no first-class
CUFIN-bucket tracking; the constancia carries it.

**Resolution**: the consumer pre-loads the constancia data into
`:audit-doc :mx-constancia-retenciones-dividendos`. The provider
reads `:audit-doc/payload :cufin-bucket` for each dividend
transaction; defaults to `:post-2014` (the law's presumption) if
unspecified — applying the Adicional conservatively.

A future MX-CUFIN companion (potential Phase C3 / C4 module) could
add first-class CUFIN-ledger tracking per issuer for both
disposal-side basis adjustment (MX CGT note 132 §4 Gap B) and
dividend-side source determination. Out of scope for v1.

**Substrate impact: ZERO** (uses existing `:audit-doc`).

### Gap B — INPC monthly series

The real-interest computation needs INPC-factor between deposit
start and end. The shipped MX CGT provider (note 132 §6) already
loads the INPC monthly series as a time-series parameter (per the
ADR-072 fx-rate-style pattern, since the monthly series since 1995
is ~360 values).

**Resolution**: share the parameter. The MX investment-income
provider reads the same `:mx/inpc-monthly` source. The first MX
provider to load it ships the snapshot; subsequent providers reuse.

**Substrate impact: ZERO** (already addressed by note 132).

### Gap C — Daily-average-balance computation

The 0.90 % is on the **daily-average balance** of peso deposits —
the bank computes this internally and reports the withheld amount
on the annual constancia de retenciones. The consumer feeds the
withheld amount as `:inputs :mx-bank-interest :withholding-applied`;
the principal-daily-average is informational (for back-of-envelope
verification only).

**Resolution**: provider trusts the bank's number; raises a warning
if `(withholding-applied / principal-daily-average × 100) > 0.91`
or `< 0.89`. **Substrate impact: ZERO.**

### Gap D — Cross-provider coupling (MX CGT INPC, MX PIT cumulative income)

The investment-income provider needs:
1. The taxpayer's other cumulative income from PIT (for art. 134
   real-interest cap and for the dividend slab-rate calc).
2. The INPC monthly series from CGT.

Both are existing or already-needed parameters. The PIT cumulative-
income coupling mirrors the pattern documented in note 132 §4 Gap D
(MX art. 120 averaging). Recommendation: same pattern — consumer
wires PIT provider first, feeds output as `:inputs
:mx-other-cumulative-income` to investment-income provider.

**Substrate impact: ZERO.**

### Gap E — Bitemporal interest-withholding rate cliff

The 0.50 % → 0.90 % rate cliff at 2026-01-01 is set by **Ley de
Ingresos de la Federación** (annually). The provider's parameter:

```clojure
{:parameter/code :mx/interest-withholding-rate
 :parameter/values
 [{:parameter-value/effective-from #inst "2024-01-01"
   :parameter-value/rate 0.0050M}
  {:parameter-value/effective-from #inst "2025-01-01"
   :parameter-value/rate 0.0050M}
  {:parameter-value/effective-from #inst "2026-01-01"
   :parameter-value/rate 0.0090M}]}
```

The 2027 rate will land via the LIF 2027 process; the provider's
parameter file ships an annual update.

**Substrate impact: ZERO new primitives.**

### Bottom line — schema posture

**Zero schema changes.** MX fits the shipped shape with 3 ADR-101
parameters (1 new + 2 reused), ~5 `:inputs` map keys, 2 new
`:audit-doc/category` keywords, and provider-side folds. Kernel and
disposal-companion untouched.

---

## §5. `mx-investment-income-provider` sketch

### 5.1 Component count

The provider returns ONE `TaxReturnFacts` per assessed entity per
FY with up to **four components**, by recipient kind + income kind:

```clojure
;; PF dividend — base-transform to PIT + standalone Adicional
{:kind :investment-income-tax :authority :mx-sat
 :composed-of [:mx-pf-dividend-acumulable]
 :base ...                                ;; gross-up = dividend × 1.4286
 :schedule :delegated-to-pit
 :base-transform-add
 [{:to-provider :mx-pit :amount <gross-up> :category :otros-ingresos}]
 :adjustment-items
 [{:kind :credit :name :corporate-isr-proxy
   :amount <gross-up × 0.30>}]            ;; art. 140 credit
 :line-items [:per-constancia :cufin-bucket]}

{:kind :investment-income-tax :authority :mx-sat
 :composed-of [:mx-pf-dividend-adicional]
 :base ...                                ;; gross dividend (post-2014 CUFIN slice)
 :schedule (ts/flat 0.10M)                ;; definitive 10 %
 :line-items [:withheld-at-source-by-payer]}

;; PF interest — base-transform to PIT + 0.90 % withholding credit
{:kind :investment-income-tax :authority :mx-sat
 :composed-of [:mx-pf-interest-real]
 :base ...                                ;; real interest = nominal − INPC adj
 :schedule :delegated-to-pit
 :base-transform-add
 [{:to-provider :mx-pit :amount <real-interest> :category :otros-ingresos}]
 :prepaid <0.90 % × daily-avg-balance>    ;; provisional credit
 :adjustment-items
 [{:kind :credit :name :provisional-withholding
   :amount <0.90% withheld>}]
 :line-items [:nominal :inpc-adjustment :real-interest]}

;; PJ-to-PJ dividend — zero tax, audit-only
{:kind :investment-income-tax :authority :mx-sat
 :composed-of [:mx-pj-dividend-exempt]
 :base ...                                ;; received dividend
 :schedule (ts/flat 0.0M)                 ;; exempt
 :line-items [:cufin-credit :pj-to-pj-exempt-art-16-fr-iii]}
```

The non-resident lane (art. 164 — out of scope above as a
foreign-source-credit, but in-scope when the recipient is a
non-resident in MX-source distribution) returns a definitive 10 %
component (or treaty-reduced):

```clojure
{:kind :investment-income-tax :authority :mx-sat
 :composed-of [:mx-nr-dividend-art-164]
 :base ...
 :schedule (ts/flat 0.10M)                ;; or treaty-reduced
 :line-items [:withheld-at-source :treaty-applied]}
```

### 5.2 The factor-credit mechanism

```clojure
(defn dividend-art-140
  "Compute the art. 140 gross-up and factor-credit for a PF dividend."
  [{:keys [dividend cufin-bucket]} ctx]
  (let [gross-up      (m/* dividend 1.4286M)         ;; published factor
        credit        (m/* gross-up 0.30M)            ;; deemed corporate ISR
        adicional     (if (= cufin-bucket :post-2014)
                         (m/* dividend 0.10M)
                         0M)]
    {:gross-up gross-up
     :acumulable-to-pit gross-up
     :pit-credit credit
     :adicional-definitive adicional}))
```

### 5.3 The real-interest computation

```clojure
(defn interest-real
  "Compute the real (INPC-adjusted) interest per art. 134."
  [{:keys [nominal-interest daily-avg-balance deposit-start deposit-end]} inpc-table]
  (let [inpc-factor  (inpc-factor-between inpc-table deposit-start deposit-end)
        inflation    (m/* daily-avg-balance (m/- inpc-factor 1M))
        real-interest (m/- nominal-interest inflation)]
    {:real-interest real-interest
     :loss-if-negative (if (m/< real-interest 0M) (m/- 0M real-interest) 0M)}))
```

### 5.4 The PJ-to-PJ exempt fold

```clojure
(defn dividend-pj-to-pj
  "When the recipient is a Mexican PJ and the source is CUFIN, the
   dividend is exempt under art. 16 fr. III. Return an audit-only
   component."
  [{:keys [dividend cufin-bucket]}]
  {:kind :investment-income-tax
   :composed-of [:mx-pj-dividend-exempt]
   :base dividend
   :schedule (ts/flat 0.0M)
   :line-items [{:kind :cufin-credit :amount dividend :bucket cufin-bucket}
                {:kind :exempt :authority "art. 16 fr. III LISR"}]})
```

### 5.5 Authority and emission

All lanes file to **SAT** (`:mx-sat`):
- **DAA (Declaración Anual de Personas Físicas)** — annual personal
  return; dividend acumulación lands in "Otros ingresos" with
  factor-credit subtraction; interest acumulación lands in
  "Intereses" with the INPC adjustment computed.
- **Constancia de retenciones** — issued by the PJ at distribution
  (and by the bank annually for interest); kontor reads them as
  `:audit-doc`.
- **DEP (Declaración Estandarizada de Pagos)** — used by the PJ
  payer to remit the 10 % Adicional.
- **PJ-to-PJ** distributions: no return — informational only on the
  recipient's books.

A v2 `kontor-l10n-mx-investment-income-emit` extension can populate
the DAA's Otros ingresos / Intereses sections. v1 ships the
computation only.

### 5.6 Substrate stress this provider surfaces

- **Cross-provider coupling** (CGT INPC + PIT cumulative income):
  the same pattern note 132 §5.3 documented. Stable, low-stress.
- **`:audit-doc/payload` structured data**: the constancia carries
  a non-trivial map (`{:cufin-bucket :post-2014 :gross ... :adicional
  ...}`). The existing `:audit-doc/payload` is a free-form map — no
  schema change needed.
- **Factor-credit as a `:credit` adjustment item**: the gross-up
  pattern is the first kontor provider to use a **derived credit
  parameter** (the 30 % is the corporate-ISR rate, not a separate
  legal value). Pattern: a parameter computed from another parameter
  (`:mx/dividend-gross-up-factor` = `1 / (1 - :mx/cit-rate)`). The
  audit doc records the rationale; the provider can either hard-code
  the factor or compute it from the CIT rate. **Recommendation**:
  store both — the factor as a parameter (so the law-as-it-stood
  audit can show the 1.4286 explicitly) AND the derivation in the
  provider's docstring.
- **Zero-tax PJ-to-PJ component**: the first kontor provider to emit
  a component with `(ts/flat 0.0M)` purely for audit-chain
  presence. The convention is clean (a `:line-items :exempt` makes
  the rationale explicit); document.

Total: **0 kernel changes**, **0 disposal-companion changes**, **1
provider** (`mx-investment-income-provider.clj`), **1 statute file**
(`mx-investment-income-statute.clj`), **1 test file**. Within the
note 107 conservative posture.

---

## §6. ADR-101 statute-as-data — what MX investment-income writes

The provider stays record-shaped (Phase 2 / C2 per note 102 §10).
ADR-101 parameters:

```clojure
;; The 10 % Adicional rate
{:parameter/code :mx/dividend-adicional-rate
 :parameter/jurisdiction :mx
 :parameter/concept-iri "https://kontor.dev/concept/dividend-additional-wht"
 :parameter/values
 [{:parameter-value/effective-from #inst "2014-01-01"
   :parameter-value/rate 0.10M}]}

;; The gross-up factor — derived from CIT rate but stored explicitly
{:parameter/code :mx/dividend-gross-up-factor
 :parameter/values
 [{:parameter-value/effective-from #inst "2014-01-01"
   :parameter-value/factor 1.4286M}]}

;; The interest withholding rate (annual LIF cliff)
{:parameter/code :mx/interest-withholding-rate
 :parameter/values
 [{:parameter-value/effective-from #inst "2023-01-01"
   :parameter-value/rate 0.0050M}
  {:parameter-value/effective-from #inst "2024-01-01"
   :parameter-value/rate 0.0050M}
  {:parameter-value/effective-from #inst "2025-01-01"
   :parameter-value/rate 0.0050M}
  {:parameter-value/effective-from #inst "2026-01-01"
   :parameter-value/rate 0.0090M}]}

;; SOFIPO interest exemption (5 UMAs)
{:parameter/code :mx/interest-sofipo-exempt-umas
 :parameter/values
 [{:parameter-value/effective-from #inst "2020-01-01"
   :parameter-value/amount 5M}]}   ;; 5 UMAs

;; Annual return threshold for interest (art. 150)
{:parameter/code :mx/annual-return-threshold-interest
 :parameter/values
 [{:parameter-value/effective-from #inst "2014-01-01"
   :parameter-value/amount 100000M
   :parameter-value/currency :MXN}]}

;; Non-resident dividend (art. 164) base rate
{:parameter/code :mx/nr-dividend-base-rate
 :parameter/values
 [{:parameter-value/effective-from #inst "2014-01-01"
   :parameter-value/rate 0.10M}]}

;; The CIT rate (already in mx-cit-statute; share the parameter)
;; :mx/cit-rate (note 132 + mx-cit-statute) — 30 %
```

UMA daily value, INPC monthly series, and UDI series are already
needed and ride the fx-rate-style time-series pattern (per note
132 §6).

The CUFIN-bucket determination (pre-2014 vs. post-2014) and the
art. 16 fr. III PJ-to-PJ exemption stay record-shaped in the
provider for Phase 2 (not migrated to `:provision`-shape).

---

## §7. Sources

### MX statutory primary

- **Ley del ISR (LISR)**:
  - Título I Disposiciones Generales — art. **5** (foreign tax
    credit).
  - Título II Cap I — art. **10** (corporate ISR on distributions
    outside CUFIN), art. **16** (PJ income — fr. III dividends not
    acumulable when from CUFIN), art. **77** (CUFIN account), art.
    **78** (CUCA — capital reductions deemed dividend).
  - Título IV — Cap VI (Intereses) arts. **133-136** (PF interest
    income), **142-143** (other passive income).
  - Cap VIII — Dividendos PF — art. **140** (gross-up + credit +
    10 % Adicional).
  - Cap IX — Otros ingresos PF — art. **142** (foreign-source +
    catch-all).
  - Art. **54-55** — interest withholding by financial-system
    entities.
  - Art. **93** fr. XX — SOFIPO 5-UMA interest exemption.
  - Art. **150** — annual return obligation (interest > MXN 100k).
  - Art. **76** fr. XI — constancia de retenciones e información
    de pagos.
  - Título V — art. **164** (NR dividend WHT).
- **Ley de Ingresos de la Federación 2026 (LIF 2026)** — annual
  budget law; sets the 0.90 % interest withholding rate for 2026.
- **RLISR (Reglamento)** — implementation regulations.

### SAT regulatory + SCJN

- [Artículo 140 — SAT portal](https://www.sat.gob.mx/articulo/32450/articulo-140)
  — canonical dividend reference (and SAT wwwmat mirror).
- [Artículo 140 — SAT (wwwmat)](https://wwwmat.sat.gob.mx/articulo/32450/articulo-140).
- [Artículo 161 — SAT (NR shares)](https://www.sat.gob.mx/articulo/88443/articulo-161)
  — non-resident treatment (companion to art. 164).
- **SCJN 8 January 2026** — Pleno ruling confirming
  constitutionality of art. 140 10 % Adicional, ending years of
  amparo litigation; see [Cronista — La Suprema Corte falló a favor del 10%](https://www.cronista.com/mexico/actualidad-mx/la-suprema-corte-fallo-a-favor-del-cobro-extra-de-10-a-los-dividendos-del-isr-celebra-el-gobierno-de-mexico/)
  + [Cronista — Es oficial: SCJN aprobó el 10% extra](https://www.cronista.com/mexico/actualidad-mx/es-oficial-y-no-hay-vuelta-atras-la-suprema-corte-aprobo-el-10-extra-a-los-dividendos-del-isr-y-las-empresas-tendran-que-abonar-mas/).
- INPC monthly series — published by INEGI.

### Reference / commentary

- [Justia — Artículo 140 LISR](https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-viii/)
  — canonical statute text.
- [SDV — Art. 140 LISR 2026: ISR por Dividendos](https://sdv.com.mx/compendio/ley-isr/articulo-140/)
  — 2026 worked-example reference.
- [vLex — Ingresos por dividendos PF art. 140 (554227350)](https://vlex.com.mx/vid/ingresos-dividendos-personas-fisicas-554227350)
  + [vLex — (757557689)](https://vlex.com.mx/vid/ingresos-dividendos-personas-fisicas-757557689).
- [IDC — Qué impuestos se pagan por dividendos](https://idconline.mx/fiscal-contable/2025/08/11/que-impuestos-se-pagan-por-ingresos-obtenidos-en-dividendos)
  — practitioner walkthrough.
- [CS Contabilidad — Dividendos y sus Efectos Fiscales LISR](https://cscontabilidad.com/dividendos-y-sus-efectos-fiscales-en-mexico-todo-lo-que-debes-saber-segun-la-lisr)
  — comprehensive corporate + PF view.
- [Heranza — Aspectos fiscales y legales del decreto de dividendos](https://heranza.com/aspectos-fiscales-y-legales-del-decreto-de-dividendos/).
- [Manuel Nevárez — Análisis de la distribución de dividendos](https://www.manuelnevarez.com.mx/es/journal/analisis-de-la-distribucion-de-dividendos/).
- [Calculadora ISR Dividendos 2026 (cotizadorhipotecario.mx)](https://cotizadorhipotecario.mx/impuestos/calculadora-isr-dividendos/)
  — 2026 calculator surfacing the gross-up + Adicional.
- [vLex — Dividendos (680438257)](https://vlex.com.mx/vid/dividendos-680438257).
- [UMICH — Tratamiento Fiscal de los Dividendos y su Impacto (PDF)](https://rges.umich.mx/index.php/rges/article/download/59/38/)
  — academic analysis.
- [Diputados — Análisis de Distribución de Dividendos (PDF)](https://sitl.diputados.gob.mx/LXIV_leg/cuadros_comparativos/2PO1/1099-2PO1-19.pdf).

### Interest income references

- [Notas Fiscales — Retención del ISR sobre intereses](https://notasfiscales.com.mx/retencion-del-isr-sobre-intereses/)
  — canonical art. 54-55 reference.
- [Siempre al Día — Retención de ISR en intereses bancarios 2026 (0.90%)](https://siemprealdia.co/mexico/fiscal/retencion-de-isr-en-intereses-bancarios/)
  — 2026 rate cliff confirmation.
- [Siempre al Día — ISR en intereses bancarios general](https://siemprealdia.co/mexico/fiscal/isr-en-intereses-bancarios/).
- [Ser Empresario — ISR 2026: Mayor retención en intereses](https://www.serempresario.com.mx/post/isr-2026-mayor-retenci%C3%B3n-en-intereses)
  + [mirror](https://www.serempresario.com.mx/isr-2026-mayor-retencion-en-intereses/)
  — 80 % rate hike commentary.
- [Russell Bedford — Aumento en retención de intereses 2026](https://russellbedford.mx/fiscal/aumento-en-la-tasa-de-retencion-por-intereses-en-el-ejercicio-2026/).
- [Fintualist — Nuevas tablas y retenciones ISR 2026](https://fintualist.com/mexico/educacion-financiera/nuevas-tablas-y-retenciones-actualizadas-de-isr-para-tus-ingresos-e-inversiones-en-2026/).
- [Cronista — SAT impone mano dura a retenciones 2026](https://www.cronista.com/mexico/finanzas-economia/el-sat-impone-mano-dura-a-las-retenciones-de-2026-iran-por-ahorros-bancarios-y-transacciones-digitales/).
- [Buen Contador — Inversión 2026 retención ISR](https://buencontador.com/inversion-2026-retencion-sube-impuesto-no/).
- [Contadigital — ISR sobre intereses personas físicas](https://www.contadigital.mx/posts/isr-sobre-intereses-personas-fisicas).

### kontor substrate cited

- `src/kontor/book.clj:296-330` — `declare-dividend!` +
  `distribute-dividend!`; both legs (PF and PJ-to-PJ) ride this.
- `src/kontor/period_tax_provider.clj` — `PeriodTaxProvider`; the
  four components ride this.
- `src/kontor/tax_schedule.clj` — `:flat` (10 % Adicional, 0 % PJ-
  to-PJ, art. 164 NR); the resident slab-bound components use
  `:delegated-to-pit`.
- `src/kontor/personal_income_tax.clj:71-83` — adjustment-layer;
  the factor-credit + provisional-withholding-credit ride as
  `:credit` items per note 105.
- `src/kontor/audit_doc.clj` — `:audit-doc` / `:audit-doc/category`;
  the constancia and the capital-reduction-deemed-dividend tags
  ride this (ADR-038).
- `src/kontor/statute.clj` — `apply-provisions` (ADR-101).
- `src/kontor/fx_rate_provider.clj` — the time-series primitive
  that INPC and UMA reuse (per note 132 §6).
- `modules/l10n-mx/src/kontor/l10n_mx/period_tax_provider.clj` —
  the existing MX PIT provider; the dividend-acumulable and
  interest-real components base-transform-add into this.
- `modules/l10n-mx/src/kontor/l10n_mx/cgt_provider.clj` — the MX CGT
  provider; sibling; shares INPC parameter + CUFIN-attestation
  `:audit-doc` pattern.
- `doc/research/132-mx-cgt-fit.md` §3 + §6 — sibling MX note; same
  posture on INPC time-series, CUFIN-attestation via `:audit-doc`,
  cross-provider coupling pattern.
- `doc/research/107-phase-3-incorporation-and-disposal.md` — the
  disposal substrate (relevant for art. 22 share-disposal CUFIN
  adjustment, MX CGT not this provider).
- `doc/research/155-br-investment-income-fit.md` — sibling BR note;
  contrast: BR has IRPFM minimum + dividend WHT; MX has gross-up +
  10 % Adicional. Different mechanisms; similar substrate posture.
- `doc/research/156-in-investment-income-fit.md` — sibling IN note;
  contrast: IN has slab-rate + §80M chain relief; MX has gross-up.
  Similar substrate posture (delegate-to-PIT + per-source folds).

---

End of note 157.
