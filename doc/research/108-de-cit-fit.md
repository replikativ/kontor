---
date: 2026-05-24
title: 108 — DE corporate income tax (KSt + Soli + GewSt) — substrate-fit assessment
status: research — Phase 3 statute fit; no code, ADR sketch in §6 (one-line addition)
audience: maintainer + the `de-corporate-income-tax-provider` implementation agent
---

# 108 — DE corporate income tax: statute and substrate fit

The Phase 3 (note 104) deliverable `de-corporate-income-tax-provider` covers a
normal incorporated business (a GmbH or an AG). DE's CIT is widely cited as the
most-component corporate stack in the OECD: three separate taxes file together
(KSt + Soli + GewSt), each with its own base, each with its own statute. This
note assesses how cleanly that stack lands on the existing kontor substrate
(ADR-099 + the note-105 adjustment layer), surfaces any abstraction stress
**before** code is written, and recommends the minimal additions if any.

Note 107 §4.1 already touched DE-CIT at a sketch level. This note is the
deeper read.

## §1. Statute summary

A normal GmbH / AG files three taxes from one set of accounting books:

### 1.1 Körperschaftsteuer (KSt) — §1 ff. KStG

Flat **15 %** federal corporate income tax on `zu versteuerndes Einkommen`
(taxable income). The base is **Handelsbilanz-Gewinn ± Steuerbilanz
adjustments** — KStG §8 Abs. 3 (verdeckte Gewinnausschüttungen — hidden
distributions added back), §10 (non-deductible expenses — half the
supervisory-board fee, the KSt and Soli themselves, ordnungsbehördliche
penalties), and §4 Abs. 5b EStG (the **GewSt itself is non-deductible** — it
neither reduces the Handelsbilanz nor the Steuerbilanz).

The substantial add-/sub-back machinery:

- **§8b KStG** — 95 % participation exemption: dividends from another
  corporation are **fully tax-free**, with 5 % added back as a non-deductible
  fictitious operating expense (so effective inclusion is 5 %). Share gains
  are the same. Threshold for dividends from the §8b kind: 10 % participation
  at the start of the year for KSt; 15 % for GewSt (§9 Nr. 2a GewStG).
- **§8c / §8d KStG** — restrictions on loss carryforward after a substantial
  shareholder change. Most consumers won't trigger; relevant for VC-backed
  companies and reorganisations.
- **§10d EStG** (via KStG §8 Abs. 1) — **Mindestbesteuerung**: losses
  carried forward only offset **100 % of current income up to €1 million**;
  the slice above €1 million only **60 %**. The remaining 40 % carries
  forward again.

### 1.2 Solidaritätszuschlag (Soli) — SolZG

A **5.5 %** surtax on the KSt liability. Post-2021 the Soli was largely
abolished for individuals (only the top ~10 % still pay it) but **for
corporations it remained in full** — KSt × 5.5 % with no Freigrenze. So it is a
flat surtax-on-tax for every GmbH.

### 1.3 Gewerbesteuer (GewSt) — GewStG

Municipal trade tax. The formula:

```
Gewerbeertrag = Gewinn aus Gewerbebetrieb (= the KSt base before §10d)
              + Σ Hinzurechnungen (§8 GewStG)
              − Σ Kürzungen (§9 GewStG)
              (rounded down to €100)

Steuermessbetrag = Gewerbeertrag × Steuermesszahl (3.5 %, §11 GewStG)
Gewerbesteuer    = Steuermessbetrag × Hebesatz (200 – 490 %, §16 GewStG;
                                                 municipal, ≥ 200 % statutory floor)
```

**§8 Hinzurechnungen (additions)** are designed to neutralise interest- /
rental-financing deductions, lifting financing costs back into the trade-tax
base. The 2008 reform consolidated them into one bucket with a single
**€200 000 Freibetrag** and a **¼ factor** applied to a per-category-weighted
sum (§8 Nr. 1 GewStG):

| Category | Weight (the share that counts) |
|---|---|
| Interest on debt (§8 Nr. 1a) | 100 % |
| Annuity / perpetual charges (§8 Nr. 1b) | 100 % |
| Silent-partner profit share (§8 Nr. 1c) | 100 % |
| Rent / lease on movable property (§8 Nr. 1d) | 20 % |
| Rent / lease on real property (§8 Nr. 1e) | 50 % |
| Royalties / licences (§8 Nr. 1f) | 25 % |

Sum the weighted items; subtract €200 000; **25 %** of the residual is the
addition. (i.e. effective interest add-back ≈ 25 %, rent-on-real-property
≈ 12.5 %, royalty ≈ 6.25 %.)

**§9 Kürzungen (reductions)** include **1.2 % of the unit value of business
real property** held throughout the year (§9 Nr. 1, removes the implicit
property-tax double dip), **dividends from significant participations**
(≥ 15 %, §9 Nr. 2a — the GewSt arm of §8b), and **foreign permanent-
establishment income** (§9 Nr. 3).

A **€24 500 Freibetrag** in §11 Abs. 1 GewStG applies only to
Personengesellschaften and natural persons — a GmbH gets none.

GewSt is non-deductible from itself or from the KSt (§4 Abs. 5b EStG).

## §2. Worked example

A GmbH in a municipality with **Hebesatz 380 %** earns
`Handelsrechtlicher Jahresüberschuss = €150 000`. It paid €120 000 of
interest, €100 000 of rent on machinery (movable), and €50 000 of royalties
during the year. No §9 reductions apply. (Sourced from the BMF /
buchhaltungsbutler / onlinebilanz worked example; URLs in §6.)

```
Gewinn aus Gewerbebetrieb (= KSt base before §10d)        150 000
  + §8 Nr. 1a interest    120 000 × 100 %    =  120 000
  + §8 Nr. 1d movable rent 100 000 × 20 %    =   20 000
  + §8 Nr. 1f royalties     50 000 × 25 %    =   12 500
  Σ weighted                                    152 500
  − Freibetrag                                  200 000
  Residual (floored at 0)                             0
  × ¼  (the universal post-Freibetrag factor)         0
                                                ───────
Hinzurechnung                                          0
(In this scenario the €200 000 Freibetrag swallows it.)
Kürzungen §9                                           0
                                                ───────
Gewerbeertrag                                    150 000
× Steuermesszahl 3.5 %                                  5 250
× Hebesatz 380 %                                       19 950
                                                ───────
Gewerbesteuer                                         19 950

Körperschaftsteuer (15 % × 150 000)                   22 500
Solidaritätszuschlag (5.5 % × 22 500)                  1 237.50
                                                ───────
Total tax burden                                      43 687.50
Effective rate                                        29.1 %
```

A higher-financing example (e.g. interest €600 000, movable rent €200 000,
royalties €100 000) gives weighted sum = €665 000; residual after Freibetrag
= €465 000; ¼ = €116 250 addition; Gewerbeertrag = €266 250; GewSt at 380 %
= ~ €35 401 — which is what makes §8 Hinzurechnungen the operationally
material part of the calculation for any finance-intensive business.

## §3. Substrate fit — component by component

Following note 107 §4.1's posture: each of the three taxes is its own
component on **one** `TaxReturnFacts`, mirroring CA T1's federal+provincial
fan-out (`modules/l10n-ca/.../period_tax_provider.clj:36-90`). The provider
emits a 3-component vector.

### 3.1 KSt — clean

| Slot | DE wiring |
|---|---|
| base-selector | `kontor.corporate-income-tax/book-profit` — σ_E over P&L |
| `:base-transform` | `:adjustments {:additions [<§8b 5 % addback> <§10 KSt §10>] :deductions [<§8b div exempt> <Steuerbilanz>]}` — rides `:inputs` (per `kontor.corporate-income-tax` already) |
| schedule | `(ts/flat 0.15M)` |
| `:authority` | `:de-finanzamt` |
| `:statute` | `"§23 KStG"` |

Identical shape to US 1120; reuse `corporate-income-tax-provider` directly.
The Mindestbesteuerung loss-carryforward limitation is **not** in `:inputs`
the way the §8b add-back is — it depends on **prior-period** losses (a
**carry-in / carry-out** problem, note-105 frontier 2). For Phase 3
short-term, the consumer pre-computes the deductible loss outside the
provider and passes the net base via `:base-transform`; the substrate
records what it received. The full carry primitive is deferred (see §4.2).

### 3.2 Soli — clean

A surtax computed from the KSt liability. The post-note-105 mechanism:

```clojure
(defn- soli-on-kst
  [ctx] (* 0.055M (:running ctx)))

;; passed through :adjustments on the KSt component
{:code   :soli
 :label  "Solidaritätszuschlag"
 :op     :surtax
 :amount soli-on-kst}
```

**Pattern precedent shipping today: `modules/l10n-de/src/kontor/l10n_de/period_tax_provider.clj:60-65`** (DE PIT Soli). Same `:op :surtax` adjustment item, same `(fn [ctx] (... (:running ctx)))` shape. The corp Soli has *no Freigrenze and no Milderungszone* — it is the simpler case.

**However** — the `apply-adjustments` machinery is wired through
`PersonalIncomeTaxProvider` (`src/kontor/personal_income_tax.clj:75-83`), NOT
through `CorporateIncomeTaxProvider`. **This is the first stress point — see §4.1.**

### 3.3 GewSt — clean (with one judgement call)

A **second `:corporate-income-tax` component** on the same `TaxReturnFacts`,
`:authority :de-municipality`, with its own:

| Slot | DE wiring |
|---|---|
| base-selector | the SAME `book-profit` σ_E (§7 GewStG starts from `Gewinn aus Gewerbebetrieb`) — provider calls it once, reuses for both KSt and GewSt components |
| `:base-transform` | `:adjustments {:additions [<§8 weighted Hinzurechnungen>] :deductions [<§9 Kürzungen>]}` — DIFFERENT additions/deductions from KSt's; the §8 weighted-sum + €200 000 Freibetrag + ¼ is computed **before** the transform and supplied as a single additions entry (the substrate doesn't need to know the weights — they are l10n configuration) |
| schedule | `(ts/flat (* 0.035M hebesatz))` — Steuermesszahl × Hebesatz folded into one effective rate per municipality |
| `:jurisdiction-specific-codes` | `{:de/hebesatz <bigdec> :de/messzahl 0.035M}` so the audit trail records what feeds the rate |

Alternative: model `:schedule` as `(ts/sum-of [(ts/flat 0.035M)])` and apply
the Hebesatz as a `:surtax` of `(* (hebesatz − 1) running)`. **Reject** — it
obscures what the Messzahl and Hebesatz separately mean. The folded effective
rate is right because `:jurisdiction-specific-codes` preserves the two
factors verbatim for the audit. (Reporting layer can always decompose.)

The €200 000 Freibetrag on the weighted sum and the **rounding-down to €100**
on the Gewerbeertrag are **not in the substrate** — they live in the l10n
helper that constructs the GewSt component's `:base-transform`. Same posture
as DE PIT's §32a `:formula` schedule that bundles the EUR-floor rounding
inside the formula (`l10n_de/period_tax_provider.clj:28-38`).

### 3.4 The 3-component `TaxReturnFacts`

```clojure
(ptp/tax-return-facts
 {:entity entity :period period
  :jurisdiction {:country "DE" :authority :de-finanzamt}
  :functional-commodity :EUR
  :components
  [{:kind :corporate-income-tax :authority :de-finanzamt
    :base ... :schedule (ts/flat 0.15M) :gross-liability ...
    :surtaxes [{:code :soli :label "Solidaritätszuschlag"
                :amount (money 1237.50M :EUR)}]
    :liability ... :line-items [...]
    :composed-of nil
    :provenance {:provider-id :de-kst :form "KSt 1" :statute "§23 KStG"}}
   {:kind :corporate-income-tax :authority :de-municipality
    :base ... :schedule (ts/flat 0.0133M)  ; 3.5 % × 380 %
    :base-transform {:transform/type :adjustments :additions [...]}
    :liability ... :line-items [{:line :hebesatz :value 380M} ...]
    :jurisdiction-specific-codes {:de/hebesatz 380M :de/messzahl 0.035M
                                  :de/municipality "Berlin"}
    :provenance {:provider-id :de-gewst :form "GewSt 1A" :statute "§§7-11 GewStG"}}]})
```

The Soli rides on the KSt component as a `:surtax` (note 105's structured
output of `apply-adjustments`); the substrate's `total-liability` then sums
KSt-after-Soli + GewSt automatically. Audit-clean, multi-authority-aware,
no special-casing.

## §4. Abstraction stress

### 4.1 (Real, small) `CorporateIncomeTaxProvider` does not run the adjustment fold

`src/kontor/corporate_income_tax.clj:38-89` computes `flat-tax` and optionally
applies `greater-of` for AT Mindest-KöSt, then constructs the component
directly with **no `apply-adjustments` call**. Contrast `PersonalIncomeTaxProvider`
(`src/kontor/personal_income_tax.clj:75-83`) which folds an `:adjustments`
vector and emits `:credits` / `:surtaxes`.

**Consequence**: a naive `de-corporate-income-tax-provider` cannot supply
the Soli as `{:op :surtax}` the way DE PIT does — there is no fold to receive
it. Workarounds available **with the existing substrate**:

- **A.** Compose: emit the KSt component from `corporate-income-tax-provider`,
  then add a *separate* `:corporate-income-tax`-kind component with
  `:composed-of [:corporate-income-tax]` for the Soli (closed enum: the Soli
  is corporate-income-tax-adjacent and the period-tax-kinds enum does not
  carry a `:surtax` kind separately — by design, note 102 §9-A).
- **B.** The Soli is the only surtax on KSt; an l10n-de wrapper provider
  recomputes the Soli post-hoc and stuffs it into the KSt component's
  `:surtaxes` field manually. Works; loses the symmetry with DE PIT.
- **C.** Extend `CorporateIncomeTaxProvider` to accept an `:adjustments`
  config (and a parallel `:inputs :credits` / `:surtaxes`), routed through
  `apply-adjustments` exactly like `PersonalIncomeTaxProvider`.

**Recommendation: C.** It is a one-time generalisation that aligns the two
generic providers and unblocks every CIT jurisdiction with a surtax (DE Soli,
JP defense surtax, JP local CIT, BR CSLL stack). The change is purely
additive (no `:adjustments` ⇒ current behaviour); it's the same `:adjustments`
construct already proven by `PersonalIncomeTaxProvider` and IN's
regime-adjustments fan-out. This is what note 107 §6 "the §8 add-backs … note
105's ordered-signed adjustment layer handles this fine" already presumed —
this note surfaces that the wiring is presumed-but-not-shipped.

**Stress severity**: **small but real**. It's not a substrate gap — the
algebra is there (`apply-adjustments` works fine on a flat schedule) — it's
that one of the two generic providers does not call it yet. One ADR addendum,
~20 lines of code, additive.

### 4.2 (Real, deferred) Mindestbesteuerung is a carry

§10d EStG's "losses > €1M only offset 60 %" is the carry-forward case
note 105 frontier 2 named explicitly. The current substrate models it as
"`:base-transform` arithmetic the consumer pre-computes" — fine for a single
period in isolation; **incorrect** when the leftover loss-carryforward must
propagate to the next period. Phase 3's DE-CIT shouldn't *fail* on this — the
consumer pre-computes and we record the net base — but the audit story is
incomplete: the kontor DB will not carry the residual loss-pool. Note-105
frontier 2 (the `(carry-in, carry-out)` Mealy primitive) is the eventual fix;
this jurisdiction does not by itself unlock the substrate work.

### 4.3 (Hypothetical, not real) "GewSt ↔ KSt as a DAG"

Note 105 frontier 3 (the tax-graph) named "DE Gewerbesteuer ↔ KSt and other
interacting taxes" as the demand trigger. **Under current law, this is a
phantom requirement.** Since 2008 (UntStRefG) **GewSt is non-deductible from
the KSt base** (§4 Abs. 5b EStG). The two taxes share `book-profit` as a
starting point but each applies its own independent `:base-transform`. There
is **no edge** between the two that the substrate would need to evaluate.

Were the Merz coalition's mooted CIT reform to *reintroduce* GewSt
deductibility, frontier 3 would suddenly become real. The substrate is fine
for the law as it stands; the design call is to **not pre-build frontier 3**
on the speculation it returns.

### 4.4 (Hypothetical) Organschaft

KStG §14 Organschaft pools subsidiary profits into the parent for KSt
(and GewSt §2 Abs. 2 S. 2 for GewSt). **kontor models this today**: the
base-selector marginalises over `kontor.entity/family` (ADR-031 + ADR-073).
A `de-organschaft-provider` would be a thin wrapper that passes
`:entity (entity/family parent)` to `book-profit`. **No substrate change**;
schedule out per note 104 — clearly Phase 4 not Phase 3.

### 4.5 (Sanity check) §8b 95 % participation exemption

A dividend from a >10 % participation is exempt; 5 % adds back as a
fictitious non-deductible operating expense. This is **a `:base-transform`
adjustment** — exactly what `:adjustments {:additions [<5% of div>]
:deductions [<full div>]}` is for. No stress. The §9 Nr. 2a GewSt equivalent
applies symmetrically on the GewSt component (with the 15 % participation
threshold differing).

### 4.6 (Sanity check) The €200 000 Freibetrag and Steuermesszahl rounding

These are **arithmetic inside the l10n helper that builds the components'
`:base-transform`**, not substrate concerns. Same posture as DE PIT's §32a
formula and IN's surcharge marginal-relief — the substrate carries the
result; the jurisdiction-specific helper computes it. No stress.

## §5. Minimal substrate adds

Conservative posture (the note-107 default): **one addition, one addendum to
ADR-099**.

- **ADR-099 Addendum — `CorporateIncomeTaxProvider` adjustment layer.** Accept
  optional `:adjustments` config + `:inputs :credits` / `:surtaxes` /
  `:adjustments`; route through `kontor.tax-schedule/apply-adjustments`
  exactly like `PersonalIncomeTaxProvider` does
  (`src/kontor/personal_income_tax.clj:75-83`). Surface the resolved items as
  `:credits` / `:surtaxes` on the component. No `:adjustments` ⇒ current
  behaviour (backward-compatible). Two-clause docstring change; one branch
  in `period-tax-facts`. **Unblocks DE Soli, JP defense surtax, JP local
  CIT, and every future CIT-side surtax** — it is not a DE-only change.

That is the entire net substrate growth for DE. The §8 / §9 GewSt arithmetic,
the per-municipality Hebesatz table, the §8b 5 % addback, the €200 000
Freibetrag, the EUR rounding, the Soli rate — **all l10n content** that lives
in the `kontor.l10n-de.corporate-income-tax-provider` namespace.

If §4.1 is rejected (the maintainer prefers workaround A or B), there is
**zero substrate growth** for DE; the Soli rides as a separate component
with `:composed-of [:corporate-income-tax]`. The recommendation stands
because the addendum is small and pays off across three jurisdictions; the
fallback is honest.

## §6. Sources

**kontor substrate cited (file:line)**:

- `src/kontor/corporate_income_tax.clj:38-89` — `CorporateIncomeTaxProvider`
  (the no-adjustment-fold today).
- `src/kontor/personal_income_tax.clj:61-118` — `PersonalIncomeTaxProvider`
  (the with-adjustment-fold; the model §4.1 / §5 would mirror).
- `src/kontor/tax_schedule.clj:142-235` — `apply-base-transform` +
  `apply-adjustments` (the algebra the §8 weighted sum and the Soli land on).
- `src/kontor/period_tax_provider.clj:67-100` — `TaxReturnFacts` (the
  multi-component shape the 3-tax stack uses).
- `modules/l10n-de/src/kontor/l10n_de/period_tax_provider.clj:60-78` — DE PIT
  Soli precedent (the exact `{:op :surtax :amount (fn [ctx] …)}` shape).
- `modules/l10n-ca/src/kontor/l10n_ca/period_tax_provider.clj:36-90` —
  multi-authority fan-out precedent (federal + provincial → DE will fan
  out federal + municipal).
- `modules/l10n-us/src/kontor/l10n_us/period_tax_provider.clj:40-49` — the
  shipped reference flat-rate CIT (the shape the KSt component matches).
- `modules/l10n-in/src/kontor/l10n_in/period_tax_provider.clj:213-237` — base-aware
  adjustment-fold precedent (the structural form a richer DE CIT
  `:adjustments` would take).
- `doc/research/107-phase-3-incorporation-and-disposal.md` §4.1 — the prior
  DE-CIT sketch this note deepens.
- `doc/research/104-tax-completion-individual-to-corporation.md` — Phase 3
  mandate.
- `doc/research/105-the-algebra-of-a-tax.md` — frontiers 1 (built), 2 (carry,
  for Mindestbesteuerung), 3 (graph, the speculative GewSt↔KSt edge).
- `doc/decisions.md` ADR-099 + addenda.

**External references (license-clean reading)**:

- pwc Tax Summaries — Germany corporate taxes: `https://taxsummaries.pwc.com/germany/corporate/taxes-on-corporate-income`
- BMF Gewerbesteuer-Handbuch GewStH 2016, §8 Hinzurechnungen overview:
  `https://gewsth.bundesfinanzministerium.de/gewsth/2016/A-Gewerbesteuergesetz/II-Bemessung-der-Gewerbesteuer/Paragraf-8/inhalt.html`
- steuerkurse.de — §8 GewStG Hinzurechnungen factors and €200 000 Freibetrag:
  `https://www.steuerkurse.de/gewerbesteuer-gewst/hinzurechnungen-gewstg.html`
- steuerkurse.de — §9 GewStG Kürzungen overview:
  `https://www.steuerkurse.de/gewerbesteuer-gewst/kuerzungen-gewstg.html`
- onlinebilanz.de — GmbH worked example (the §2 scenario base case, adapted):
  `https://onlinebilanz.de/gewerbesteuer-berechnen-gmbh/`
- buchhaltungsbutler.de — Gewerbesteuer-Rechner 2026 explanation:
  `https://www.buchhaltungsbutler.de/wiki/gewerbesteuer-rechner/`
- dejure.org — §8 GewStG statute text:
  `https://dejure.org/gesetze/GewStG/8.html`
- dejure.org — §9 GewStG statute text:
  `https://dejure.org/gesetze/GewStG/9.html`
- EY Deutschland — Körperschaftsteuersenkung ab 2028 (the upcoming Merz
  reform pointer; today: 15 % unchanged):
  `https://www.ey.com/de_de/insights/tax-law-magazine/kehrseite-der-steuersenkung`

---

End of note 108.
