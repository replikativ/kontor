---
date: 2026-05-24
title: 109 — FR corporate income tax (IS) — statute-fit assessment against the kontor substrate
status: research-before for Phase 3 FR CIT — finds the substrate fits cleanly
audience: maintainer + the FR Phase 3 implementation agent
---

# 109 — FR corporate income tax (IS) — statute-fit assessment

Phase 3 of the note-104 completion programme lights up corporate income tax for
DE / FR / CA / JP on the shipped `kontor.corporate-income-tax` substrate. This
note assesses whether French **impôt sur les sociétés (IS)** can be expressed
faithfully on the substrate **as it stands** (note 105 frontier 1 included), or
whether the build will surface stress the way IN's §87A + surcharge + cess
turned out to need the adjustment-layer work.

The short answer: **IS fits cleanly on the shipped substrate** — every
component maps onto a primitive that already exists. One area (the régime
mère-fille and CIR) deserves convention documentation, not substrate
extension. The build is l10n content + one `PeriodTaxProvider` config.

---

## §1. Statute summary — what an SA/SAS/SARL actually files

**Base rate — Art. 219 I CGI.** 25 % flat on the *bénéfice fiscal* (book
profit adjusted for réintégrations and déductions), since the close of the
Macron staged reduction in 2022. ([Service Public])

**Reduced rate for PME — Art. 219 I-b CGI.** 15 % on the first **€42,500** of
taxable profit. Profit above that is taxed at the standard 25 %. Two-bracket
shape (NOT a `:capped` schedule). Eligibility (all three required):
- annual turnover (CA HT) ≤ €10 M;
- capital entirely paid up (intégralement libéré);
- ≥ 75 % of the capital held, directly or indirectly, by natural persons (or
  by entities themselves meeting the test). ([impots.gouv.fr])

**Contribution sociale 3.3 % — CGI Art. 235 ter ZC.** 3.3 % × max(0, IS −
€763,000). A PME exemption disapplies it when CA HT < €7.63 M *and* the
capital tests are met. So it can hit a company that takes the PME 15 %
bracket if CA HT is in the €7.63 M – €10 M band with a large enough profit.
([Légifrance Art. 235 ter ZC])

**Contribution exceptionnelle (CEBGE) — LF 2025 Art. 48, LF 2026 maintenance.**
FY2025 surtax on large companies, computed on the *average* of the current and
prior-year IS:
- CA HT ≥ €1 B and < €3 B → 20.6 %;
- CA HT ≥ €3 B → 41.2 %.
LF 2026 maintains the rates but lifts the entry threshold to €1.5 B with a
€1.5 B–€1.6 B smoothing band. Temporary by statute; out of substrate scope but
shape-checkable. ([compta-online])

**Régime mère-fille — CGI Art. 145 + Art. 216 I.** Dividends received from a
subsidiary the parent holds ≥ 5 % of for ≥ 2 years are *exempt* from IS,
subject to reintegration of a **5 % quote-part de frais et charges** — so a
*95 %* effective exemption. Mechanism: a book-to-taxable deduction at the
base. ([BOFiP / search summary])

**Crédit d'Impôt Recherche (CIR) — CGI Art. 244 quater B.** 30 % of qualifying
R&D expenses up to €100 M, 5 % beyond. **Refundable immediately for PMEs**
(EU sense); for non-PMEs, carries forward 3 years then refunded. ([impots.gouv.fr])

**Carry-forward losses (déficit reportable) — CGI Art. 209 I al. 3.**
*Indefinite* carry-forward, capped per year at **€1 M + 50 % × (bénéfice − €1 M)**.
The unused fraction rolls into the next year. (LF 2025 Art. 97 layers a
temporary €2.5 B cap on very large prior-year deficits — out of scope here.)
([Légifrance Art. 209])

**CVAE.** Being phased out by 2027 — the rate is already on a glide path.
Note: CVAE is **not on IS** — it is its own *contribution sur la valeur
ajoutée*, levied on the VA aggregate (a separate base entirely). Phase 3 may
ship a CVAE `PeriodTaxProvider` as a sibling component on the same FR
`TaxReturnFacts`; the *fit assessment* below covers only IS proper.

---

## §2. Worked example — SME hitting both the 15 % bracket AND the 3.3 % CGE

A `SAS` with **CA HT = €8 M**, fully paid capital, 80 % held by individuals,
12-month FY, **bénéfice fiscal = €3,000,000** (after réintégrations).

| Step | Computation | Amount |
|---|---|---|
| 15 % bracket | €42,500 × 0.15 | **€6,375** |
| 25 % bracket | (€3,000,000 − €42,500) × 0.25 | **€739,375** |
| IS gross | sum | **€745,750** |
| Contribution sociale 3.3 % | CA HT €8 M ≥ €7.63 M ⇒ NOT exempt; max(0, 745,750 − 763,000) = 0 | **€0** |
| Total | | **€745,750** |

The 3.3 % CGE does **not** fire here — IS is still below the €763 k abattement.
Push the profit to **€4,000,000**:

| Step | Computation | Amount |
|---|---|---|
| 15 % bracket | €42,500 × 0.15 | €6,375 |
| 25 % bracket | (€4,000,000 − €42,500) × 0.25 | €989,375 |
| IS gross | | **€995,750** |
| Contribution sociale 3.3 % | (995,750 − 763,000) × 0.033 | **€7,681** |
| Total | | **€1,003,431** |

This second case is the canonical "two-bracket IS + tax-on-tax surtax" shape
the substrate must handle.

---

## §3. Substrate fit — component-by-component map

### 3.1 The 25 % standard rate + 15 % PME bracket — a `:progressive-bracket` schedule

`CorporateIncomeTaxProvider` (`src/kontor/corporate_income_tax.clj:38-89`)
takes a single `:rate` and hardcodes `(ts/flat rate)`. **FR needs two
brackets**, not a flat rate. Two paths:

- **Path A (preferred) — pass a pre-built `schedule` instead of a `:rate`.**
  Promote the provider's `:rate` field to a `:schedule` field (or accept
  *either*), and let the FR config pass
  `(ts/progressive [{:rate 0.15M :upper 42500M} {:rate 0.25M :upper nil}])`.
  Behaviour-preserving: existing US-1120 / AU-EIT / CN-EIT configs that pass
  `:rate r` are wrapped to `(ts/flat r)` at the constructor.
- **Path B — FR writes its own `period-tax-facts` body**, calling
  `apply-schedule` with the progressive ladder directly and skipping the
  flat-only provider. Same posture as `kontor.l10n-ca.period-tax-provider`'s
  `t1-tax-return-facts` (`modules/l10n-ca/.../period_tax_provider.clj:37-79`)
  wrapping the immutable CA-T1 compute.

Path A is the cleaner generalisation: every other CIT jurisdiction (DE: KSt
15 % flat + Soli surtax + Gewerbesteuer; JP: graduated 19 %/23.2 %) will
want non-flat schedules too. The `CorporateIncomeTaxProvider`'s body
*already* runs `apply-schedule` on an arbitrary `schedule`; the only flat-
specific code is the constructor sugar. **This is a one-line widening, not
a redesign.**

The 15 % PME *eligibility* (CA + capital + ownership) is **not** something
the substrate should adjudicate from the GL. It rides `ctx :tax-unit` (or
`:inputs`) as a boolean / qualifying flag, and the FR config either picks the
PME schedule or the standard-25 % schedule based on it — exactly the
mechanism the US-1040 provider uses for `:filing-status`
(`modules/l10n-us/.../period_tax_provider.clj:103-112` `resolve-filing-status`).
Use the same `:tax-unit` carrier — the maintainer's question about
generalisation is answered: **`:tax-unit` already carries entity-level
eligibility predicates, not just household composition**; `:tax-unit
{:fr/pme-eligible? true}` is in scope.

### 3.2 Contribution sociale 3.3 % — a surtax adjustment item with a base-aware fn

The note-105 adjustment layer is exactly the primitive: a `:surtax` item
whose `:amount` is a **fn of `:running`** (the IS so far):

```clojure
{:code :fr/cs :label "Contribution sociale 3.3 %"
 :op :surtax
 :amount (fn [{:keys [running]}]
           (* 0.033M (max 0M (- (bigdec running) 763000M))))}
```

The PME exemption is a wrapper on the same fn (return 0 when the `:tax-unit`
carries the exemption flag).

**Substrate fit: clean.** This is precisely the IN cess pattern from note
105 — `surtax-on` with an abattement. `apply-adjustments`
(`src/kontor/tax_schedule.clj:192-235`) already handles this; the
`CorporateIncomeTaxProvider` currently doesn't *fold adjustments* (it only
takes a `:minimum-tax`) — symmetry with `PersonalIncomeTaxProvider`'s
`:adjustments` config field is the natural extension. **Same one-line
widening as §3.1.**

### 3.3 Contribution exceptionnelle — a second surtax item, base-aware on IS

Same shape: another `:surtax` item whose `:amount` reads `:running` (or
better, the *average* of current and prior IS — which is a Phase-3-frontier-2
*carry* concern). For FY 2025 — and an FY 2026 ride at the €1.5 B threshold
— a static-rate fn keyed on `(:fr/turnover ctx :tax-unit)` returns
20.6 %, 41.2 %, or 0. The two-year averaging needs `:inputs
{:fr/prior-year-is X}` — a one-line `:inputs` convention exactly like the
CGT carry-in (note 103 §3a). **No substrate work.**

### 3.4 Régime mère-fille — a `:base-transform :adjustments` deduction

The 95 % exemption is a *book-to-taxable* adjustment: dividends from
qualifying subsidiaries are deducted from the base, then 5 % is added back
(the quote-part). The shape is exactly what `:base-transform :adjustments`
(`src/kontor/tax_schedule.clj:142-163`) was built for:

```clojure
{:transform/type :adjustments
 :additions  [(* 0.05M qualifying-dividends)]      ; quote-part
 :deductions [qualifying-dividends]}               ; full exemption
```

This is *l10n content* — the consumer's `:inputs :base-transform` carries
the figure. The substrate carries the mechanism. **Clean fit.**

### 3.5 CIR — a `:credit` adjustment item, refundable for PMEs

The CIR is the canonical refundable credit. Note 105's `apply-adjustments`
`:refundable? true` is **exactly** the signal that lets the liability go
negative — and CIR is genuinely refundable for PMEs (a transfer *to* the
taxpayer when CIR exceeds IS). Item:

```clojure
{:code :fr/cir :label "Crédit d'impôt recherche"
 :op :credit :refundable? pme?              ; PME ⇒ negative liability ⇒ refund
 :amount cir-amount}
```

The refundability flag rides `ctx :tax-unit`. **Clean fit — the adjustment
layer was specifically designed for this case.**

### 3.6 Déficit reportable — a Phase-3 *frontier 2* concern (the carry)

The €1 M + 50 % cap is **the** canonical motivating example for note 105
frontier 2 (the inter-period carry — Mealy-machine state across years). The
shape `(carry-in, base) → (taxable, carry-out)`:

```
prior-loss-pool: P_in
deduction:       min(P_in, max(1_000_000, 1_000_000 + 0.5*(base - 1_000_000)))
taxable:         base − deduction
carry-out:       P_in − deduction
```

This is **not in the substrate today** — note 105 explicitly defers frontier 2
to Phase 3 "when corporate loss carryforward demands it." **FR IS demands it.**
This is the *only* genuine abstraction stress this assessment surfaces; it is
already on the roadmap. The DE Mindestbesteuerung is the same mechanism with
different constants, so building it once for FR pays for DE.

### 3.7 Multi-component `TaxReturnFacts`

The FR return naturally emits two components on one `TaxReturnFacts`:
- `{:kind :corporate-income-tax :authority :fr-dgfip}` for IS proper
  (after CIR);
- a separate component (or composed-of edge) for the CGE when present.

The contribution sociale is naturally a **surtax inside the IS component**,
since it is computed on IS itself — the IN cess / DE Soli pattern. The CGE
*could* go either way; making it its own component lets it carry its own
`:provenance` and `:line-items`, which suits a temporary statute. This is
exactly the CA T1 federal+provincial fan-out
(`modules/l10n-ca/.../period_tax_provider.clj:36-79`).

---

## §4. Abstraction stress — explicit list

The kontor substrate fits FR IS cleanly, with **one already-on-the-roadmap
gap** and **two trivial one-line widenings** to the
`CorporateIncomeTaxProvider`:

| Item | Stress level | Action |
|---|---|---|
| 25 % flat | none | shipped |
| PME 15 % bracket | **trivial widening** — provider must accept a `:schedule` not just `:rate` | constructor change, behaviour-preserving |
| PME eligibility (CA / capital / ownership) | none | `:tax-unit` carries the flag (US-1040 precedent) |
| Contribution sociale 3.3 % | **trivial widening** — provider must accept an `:adjustments` vector (symmetry with `PersonalIncomeTaxProvider`) | constructor change |
| CEBGE | none | another `:adjustments` item; two-year averaging via `:inputs` |
| Régime mère-fille | none | `:base-transform :adjustments` already does it |
| CIR | none | adjustment layer with `:refundable? true` already does it |
| Déficit reportable (€1 M + 50 %) | **note 105 frontier 2 — the carry** | already named + deferred; **FR IS is the demand-trigger** |
| CVAE | out of scope here | separate provider on a separate base |

The two widenings are the same kind: the corporate provider lags the
personal provider in adjustment-layer and schedule generality. They are
behaviour-preserving (existing flat-rate consumers wrap their `:rate` to
`(ts/flat r)` at the constructor). **Not a redesign; one-line widening.**

The genuine abstraction stress is **the carry** — and the answer is "build
frontier 2 now, in Phase 3, exactly as note 105 anticipated." FR demands it;
DE Mindestbesteuerung second-demands it; the work pays for both.

---

## §5. Minimal substrate adds — only the two named widenings + frontier 2

**No new schema.** No new provider. No new namespace beyond
`modules/l10n-fr/src/kontor/l10n_fr/period_tax_provider.clj` (which already
exists for FR PIT and gets the IS provider as a sibling).

Kernel changes (one PR, two diffs):

1. `kontor.corporate-income-tax/corporate-income-tax-provider` — accept
   *either* `:rate` (sugar → `(ts/flat r)`) *or* `:schedule` (used as-is).
2. `kontor.corporate-income-tax/CorporateIncomeTaxProvider` — accept an
   `:adjustments` config field; assemble + fold via `apply-adjustments`
   the same way `PersonalIncomeTaxProvider` does
   (`src/kontor/personal_income_tax.clj:61-118`). Surface the resolved
   `:credits` / `:surtaxes` on the component.

Frontier-2 work (separate ADR, Phase 3):

3. A `:carry-in` / `:carry-out` convention on `period-tax-facts` for loss
   pools (FR `Art. 209 I`, DE Mindestbesteuerung) — Mealy-machine state
   keyed by `(entity, period, carry-kind)`. Note 105 §2 already names this.

---

## §6. Sources

- [Légifrance — CGI Art. 209 (report en avant)](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000048847486)
- [Légifrance — Contribution sociale, CGI Art. 235 ter ZC](https://www.legifrance.gouv.fr/codes/section_lc/LEGITEXT000006069577/LEGISCTA000006162551/)
- [Service Public — Impôt sur les sociétés (IS)](https://entreprendre.service-public.gouv.fr/vosdroits/F23575)
- [Service Public — Report de déficit](https://entreprendre.service-public.gouv.fr/vosdroits/F23628)
- [impots.gouv.fr — Taux réduit IS critère CA](https://www.impots.gouv.fr/actualite/taux-reduit-dimpot-sur-les-societes-le-critere-du-chiffre-daffaires-revu-pour-les)
- [Compta-Online — Taux IS 2026 (15 % / 25 % / CEBGE 20.6 % / 41.2 %)](https://www.compta-online.com/taux-is-impot-sur-les-societes-ao2921)
- [LégiFiscal — Contribution sociale 3.3 % (exemple de calcul)](https://www.legifiscal.fr/reperes-fiscaux/contribution-sociale-impot-societe-is-33-2024.html)
- [BOFiP — CIR aménagements LF 2025](https://bofip.impots.gouv.fr/bofip/14709-PGP.html/ACTU-2025-00105)
- [BPI France — CIR — refundable for PMEs](https://bpifrance-creation.fr/encyclopedie/aides-a-creation-a-reprise-dentreprise/aides-a-linnovation/cir-credit-dimpot-recherche)
- [Service Public — CIR (Art. 244 quater B)](https://entreprendre.service-public.gouv.fr/vosdroits/F23533)
- [LégiFiscal — Report en avant des déficits (CGI 209)](https://www.legifiscal.fr/impots-entreprises/impot-benefices/calcul-is/le-report-des-deficits.html)
