---
date: 2026-05-24
title: 144 — MX CGT provider baseline review (ADR-103 review-after)
audience: maintainer of `kontor-l10n-mx` + ADR-103 review-after
status: review-after for the MX CGT provider just shipped; output is a triaged findings list (P0/P1/P2/Nit) with file:line citations against authority + research note 132. The provider has the right shape; the §1 P0 is the gross-liability semantics on the real-estate component; everything else is P1 / P2 / Nit.
---

# 144 — MX CGT provider baseline review

This note audits the freshly-landed `kontor.l10n-mx.cgt-{statute,provider}`
plus its test suite against (a) the authority sources cited in
research note 132, (b) note 132's own design proposals, and
(c) the substrate conventions the DE / CA / JP CGT providers
established.

Scope: the three files

- `/home/christian-weilbach/Development/kontor/modules/l10n-mx/src/kontor/l10n_mx/cgt_statute.clj`
- `/home/christian-weilbach/Development/kontor/modules/l10n-mx/src/kontor/l10n_mx/cgt_provider.clj`
- `/home/christian-weilbach/Development/kontor/modules/l10n-mx/test/kontor/l10n_mx/cgt_provider_test.clj`

Method: read all three end-to-end, cross-reference each branch against
the cited LISR articles via Justia / SAT / SDV Asesores / leyes-mx
mirrors, verify the worked example numbers, and rate findings.

Bottom line: **substrate and parameter set are correctly grounded;
one P0 about `:gross-liability` semantics on the real-estate component
(the headline federal liability is missing from the number that ought
to be the federal+state total); five P1s on coverage gaps and silent
behaviour; two P2s; two Nits.** The two `:provision`s installed by the
statute namespace are inert in v1 — the provider reads parameters
directly and never calls `kontor.statute/apply-provisions`.

---

## §1. P0 ship-blockers — fix before next release

### P0-1. `:gross-liability` on the real-estate component understates the federal liability

`cgt_provider.clj:348-353` — the real-estate component sets

```clojure
:gross-liability (money/money state-surtax commodity)
:liability       (money/money (max 0M (- state-surtax total-prepaid)) commodity)
```

i.e. the **only** liability surfaced is the art. 127 state 5 % surtax.
The art. 120 acumulable folds into PIT via `:pit-base-additions`
(correct), but the no-acumulable portion is currently NOT taxed by
anyone — it sits in `:jurisdiction-specific-codes :no-acumulable` as
a denormalised number with a "TODO: cross-provider effective-rate
coupling" note. This is faithful to the docstring ("v1 implements the
linear split … no-acumulable is exposed as a `:line-item` annotated
with the effective-rate computation TODO") but it has **two
operational consequences** that need acknowledging before this code
is loaded by a downstream consumer:

1. The `total-prepaid = federal + state` figure that reduces
   `:liability` is being credited against ONLY the state 5 % surtax.
   With realistic federal withholding (art. 126: ~80-95 % of the
   ultimate ISR per note 132 §1.1.3), the federal prepayment will
   massively over-credit the state surtax and the liability rounds
   to zero. That is the wrong sign: the federal prepayment should
   credit the federal liability — which currently has no component
   to land on.
2. A downstream `compute-period-tax-liabilities` aggregator that
   simply sums all `:liability` Moneys across providers will
   produce a number that **misses the federal portion of the
   real-estate CGT entirely** — the acumulable was added to PIT
   base, the no-acumulable was discarded as a denormalised
   note-to-self.

**Recommended fix**:
- Either (a) Stage 1 reconcile-only — surface the no-acumulable as
  its own component with `:schedule :tbd` and a `:provenance` warning
  that the full federal liability requires a Stage 2 PIT two-pass; OR
- (b) actually wire the two-pass coupling per note 132 §5.3 and
  retire the TODO. Option (a) is the smaller delta and clearly
  marks the v1 partial state.
- In either case, the `:prepaid` slot should be split:
  `{:federal-prepaid …, :state-prepaid …}` so the eventual two-pass
  coupling can credit them against the correct liability.

Severity: **P0** because a customer running the provider today
through `compute-period-tax-liabilities` and trusting the
`:liability` number will under-pay federal ISR by 25-35 % of the
no-acumulable. The docstring TODO mitigates if the consumer reads
it, but the type signature gives no in-band signal.

---

## §2. P1 — substantive gaps that change a real customer's number

### P1-1. Art. 120 Option B 5-year average rate is silently ignored

`cgt_provider.clj:50-62` — the docstring promises that
`:elective-regime :mx-art-120-option-b-5yr-average-rate` is "surfaced as
the elected lever; v1 implements Option A". The provider then never
checks the elective-regime set: `real-estate-component` reads
`asset-class`, `inputs`, `cap-used` but no election. A taxpayer
electing Option B gets identical output to Option A. The pragmatic
patch is small — emit a `:line-item` warning
`{:line :art-120-option-b-not-implemented …}` when the election is
present, so the consumer is notified at runtime that v1 ignores it.
The note 132 §1.1.2 source ([SAT art. 120](https://www.sat.gob.mx/articulo/31901/articulo-120))
makes clear that Option B can be the cheaper election when
recent-year incomes were lower than the disposal-year income; v1
silence is invisible misbehaviour.

### P1-2. Casa-habitación cooling-off uses an unusual semantic for `:mx-residence-cap-used`

`cgt_provider.clj:182-198` — the input `:mx-residence-cap-used` is
documented as a "running total"; treated as a binary flag
(`(pos? residence-cap-used)` ⇒ fully taxable). Note 132 §4 Gap E
contemplated either a "consumer enforces, provider warns" pattern or
a hard block; the current code is in-between: it does NOT raise, it
silently switches the disposal to fully-taxable without surfacing a
`:line-item` saying why. A consumer that meant the slot to be a
recorded-history counter (per the "running total" phrasing) will be
surprised when the provider takes that as "cap already used."

Pragmatic fix: rename to `:mx-residence-cap-already-used?` (boolean)
to match the actual code, or actually compare against a 3-year
cooling-off window of prior disposals. Add a `:line-item`
`{:line :casa-habitacion-cap-already-used …}` when the path fires,
so the audit trail records the reason.

### P1-3. Art. 22 `share-adjustments` map is keyed by `:disposal/external-id` only

`cgt_provider.clj:240-265` — the provider keys CUFIN/CUCA inputs
off the disposal's `:disposal/external-id`. The fixture / tests are
consistent (`"corpco-1"`, `"unlisted-pf"`). But:

- A disposal without `:external-id` (the field is not required by the
  disposal schema; see `modules/disposal/src/kontor/disposal/schema.clj`)
  will be keyed by `nil` and receive default-zero adjustments. That
  is a silent under-fold of CUFIN — the gain will be overstated.
- Per note 132 §4 Gap B the recommended encoding is
  `:inputs {:mx-share-adjustments {<issuer-eid> {…}}}` (keyed by
  ISSUER), since the same issuer's CUFIN evolution applies to every
  disposal of that issuer's shares. v1 keys per-disposal; that
  works for the single-disposal tests, but in a real PM holding
  multiple lots of the same issuer the consumer has to duplicate
  the CUFIN attestation per lot. Worth a docstring note + a
  followup.

### P1-4. Bolsa lane (art. 129) — broker withholding double-credits when present

`cgt_provider.clj:365-392` — `bolsa-component` reads
`:mx-bmv-broker-withheld` and sets `:prepaid` to that value, with
`:liability = max(0, gross − broker-wh)`. Test
`bmv-broker-withholding-credits-prepaid` (lines 233-247) verifies
this path. Question: is `:mx-bmv-broker-withheld` a
PROVIDER-CALCULATED 10 % × net OR a BROKER-REPORTED actual figure?
Per LISR art. 56 of the LMV and the 2014 reform, brokers compute
and report annual aggregates; the **provider should derive
broker-wh = 10 % × net** automatically and only accept the input
as a reconciliation override (with a runtime warning when the two
diverge). Today an arbitrary `:mx-bmv-broker-withheld` value
silently reduces `:liability` to zero regardless of correctness.
Add an invariant + warning.

### P1-5. `years-held` cap reads only the gain parameter — the loss path (art. 122) is unimplemented

`cgt_provider.clj:148-158, 505-573` — the `years-held` helper takes
`cap-years` and is called only with the gain-cap (20). The art. 122
loss path requires a divisor cap of 10 (note 132 §1.1.5; statute
encodes `MX.CGT.art-122.loss-years-cap = 10M`). The current
provider does not implement the loss-divisor mechanism (losses are
zero-clamped via `(max 0M (- bolsa-gross bolsa-carry))` and the
real-estate path returns nil when gain ≤ 0). The 10-year cap
parameter is loaded but **never read** — `grep -n "loss-years-cap"
cgt_provider.clj` returns nothing. Either ship the loss path or
remove the dead parameter (and document the deferral in the
docstring). Per the v1-substrate-first principle (note 102 §10) it
is fine to defer, but the dead parameter is misleading.

---

## §3. P2 — quality / hygiene that won't change a number but should be addressed before Phase 4

### P2-1. The `:provision` entries are inert in v1

`cgt_statute.clj:235-276` — two `:provision`s installed:
`MX-LISR-art-93-XIX-a-casa-habitacion` and `MX-LISR-art-127-state-5pct`.
Both are wired with realistic conditions/consequences and read
correctly, but `cgt_provider.clj` never calls
`(kontor.statute/apply-provisions …)`. The provider reads each
underlying parameter directly via `parameter-value-at`. The two
provisions are dormant — they will only matter if a future consumer
runs `(statute/applicable-provisions …)` for a "what-applies"
audit query.

This **matches** the documented v1 design (note 132 §6 — "the bulk
of MX CGT will migrate to `:provision`-shape in Phase 3"), so no
fix is strictly required. But: the casa-habitación cap provision's
consequence is `{:op :base-deduct :amount-from :parameter
:parameter "MX.CGT.casa-habitacion-cap-udis"}`, which has a unit
mismatch — the parameter is **denominated in UDIS**, the
`:base-deduct` op of `kontor.statute` adds/subtracts MXN amounts to
a base. A naive future evaluator that consumes this provision will
deduct 700,000 raw bigdec from a MXN base, which is wrong by a
factor of ~8.78 (the UDI rate). Recommend either:
- Marking the provision with a `:provision/needs-fx-resolution? true`
  flag so the evaluator knows to multiply by UDI before applying, or
- Removing the provision until ADR-101 has a unit-aware `:base-deduct`
  semantics.

### P2-2. State 5 % surtax (art. 127) is over-applied to ALL real-estate disposals

`cgt_provider.clj:306-308` — the state surtax is computed for both
`:mx-inmueble-residencia` and `:mx-inmueble`. The LISR art. 127 text
binds the 5 % to the seller of "land, buildings, or land + buildings"
by a persona física **before a notary** — so the rate applies to the
casa-habitación + other real-estate-by-individual cases. Some
jurisdictions actually charge less than 5 % (the state rate); some
states use a different formula. Worth a docstring note that 5 % is
the federal cap per art. 127 and the state rate **can be lower**;
v1 uses the cap.

### P2-3. `years-between` reads 365.25 days flat — minor leap-year drift

`cgt_provider.clj:124-132` — `(/ days 365.25)` then `Math/floor`. For
a disposal acquired 2018-08-15 / disposed 2026-04-10 (note 132 §2
Example A), the actual elapsed days = ~2796; 2796 / 365.25 = 7.654;
floor = 7. That matches note 132 §2's "~7.7 years → 7 full years".
Good for now. Could be a `LocalDate` `Period/between` call to be
calendar-accurate; not numerically material here.

---

## §4. Nit — cosmetic

### Nit-1. Vocabulary divergence between note 132 §3.2 and the implementation

Note 132 §3.2 named `:mx-real-estate-casa-habitacion` / `:mx-real-estate-secondary` /
`:mx-real-estate-commercial` / `:mx-share-listed-bmv` / `:mx-share-unlisted` etc.
The provider uses `:mx-inmueble-residencia` / `:mx-inmueble` /
`:mx-inmueble-comercial` / `:mx-bmv-shares` / `:mx-unlisted-shares`. The
implementation's vocabulary is shorter and Spanish-prefixed; the
research note is hyphen-flat-English. Both are open-vocab keywords —
neither is wrong. Pick one and document the mapping somewhere
(probably in the CGT statute docstring or a top-level Vocabulary
section). Otherwise future consumers will guess.

### Nit-2. Test fixture uses `[:commodity/symbol "MXN"]` as the disposal `:subject`

`cgt_provider_test.clj:41-52` — `record!` uses `:subject mxn` where
`mxn = [:commodity/symbol "MXN"]`. The `:disposal/subject` schema doc
calls for a ref to "an `:asset` / `:lot` / `:commitment` /
participation". The commodity ref is type-valid (it's an entity)
but semantically wrong — the test passes only because no provider
code reads `:disposal/subject` for these tests. Both MX and CN tests
do this. Future consumers reading the test as a template will
mis-model. Add a TODO in the fixture comment.

---

## §5. What is right (no action — pass)

The following are checked and correct against authority:

| Topic | Authority | Implementation site | Verified |
|---|---|---|---|
| Art. 120 averaging — linear split (acumulable = gain / yrs) | [SAT art. 120](https://www.sat.gob.mx/articulo/31901/articulo-120) | `cgt_provider.clj:216-234` | OK; gain-cap 20 honored at `:line 449-451` |
| Art. 120 divisor cap 20 (gain) | LISR art. 120 (Justia) | parameter `MX.CGT.art-120.gain-years-cap = 20M` | OK |
| Art. 129 BMV/BIVA 10 % flat | LISR art. 129 (Justia + SAT) | parameter `MX.CGT.art-129.bolsa-rate = 0.10M`, applied via `ts/flat` | OK |
| Art. 9 PM 30 % CIT | LISR art. 9 (Justia) | parameter `MX.CGT.art-9.pm-rate = 0.30M` | OK (rate not applied here — folds to CIT provider) |
| Art. 160 NR real-estate 25 % gross / 35 % net | LISR art. 160 (Justia + ApT CE) — option to use art. 152 max (35 %) | `cgt_provider.clj:428-466` | OK; parameters set correctly |
| Art. 161 NR shares 25 % gross / 35 % net (dictamen) | LISR art. 161 (SAT + Justia) | same | OK |
| Casa habitación 700k UDIS proceeds cap | LISR art. 93-XIX-a (Veritas, Hogare, Padilla-Bujalil) | `cgt_provider.clj:164-210`; cap = 700k UDIS × consumer-supplied UDI rate | OK math; cooling-off implementation see P1-2 |
| Casa habitación 3-yr cooling-off | LISR art. 93-XIX-a (Tirant, Hogare 2025) | parameter `MX.CGT.casa-habitacion-cooling-off-years = 3M` | OK rate; mechanism quirky — see P1-2 |
| Art. 127 state 5 % on GAIN (not gross) | [SAT art. 127](https://www.sat.gob.mx/articulo/32168/articulo-127) | `cgt_provider.clj:306-308` `(* taxable-gain state-rate)` — gain, not proceeds | OK |
| Art. 22 costo promedio CUFIN proportional add | LISR art. 22 (IDC 2025 + Pérez Góngora) | `cgt_provider.clj:240-265` `(* cufin-delta ownership)` | OK |
| Art. 22 CUCA reduction subtract | LISR art. 22 | `(- adj-basis cuca-deduct)` | OK |
| Persona-moral fold to CIT (`:cit-base-additions`) | LISR Título II Cap I | `cgt_provider.clj:472-495` | OK shape; CIT provider applies 30 % |
| Note 132 §2 Example A — Sra. Hernández casa | Per note 132 §2 | test `casa-habitacion-note-132-worked-example` (lines 119-145) — checks cap MXN = 700k × 8.78 = 6,146,000, base positive | OK |
| Note 132 §2 Example B — CorpCo CUFIN | Per note 132 §2 | test `corp-pm-cufin-adjustment-note-132-example` (lines 288-310) — basis 21.3M + 6M CUFIN = 27.3M; gain = 700k; folds to CIT base | OK |
| Voided disposals filtered out | ADR-102 + companion `DisposalSource` | test `voided-disposals-excluded` (lines 418-430) | OK |
| Bolsa lane loss carry within lane | LISR art. 129 — 10-year within-lane carry | test `bmv-loss-carry-within-lane` (lines 249-262) reads `:capital-loss-carryforward :mx-bolsa` | OK (only the carry-in is wired; carry-out not yet emitted — see followups) |
| Cap of 20 years on art. 120 | LISR art. 120 | test `art-120-years-held-cap-20` (lines 198-211) — 1995→2026 = 31 yrs clipped to 20 | OK |

---

## §6. Followups (post-P0)

1. **Two-pass PIT coupling for art. 120 no-acumulable** — note 132 §5.3
   sketches the design. This is the substrate test of the cross-
   provider pattern (analogous to IN STCG `:base-transform-add`).
   Tracking issue: closes the P0 above and lets the customer get a
   correct personal-residence federal liability.

2. **Casa-habitación 3-year window query** — instead of a binary
   `:mx-residence-cap-used?`, the provider could query the disposal
   log via the `DisposalSource` for prior
   `:exemption-claimed :mx-art-93-XIX-a-casa-habitacion` within the
   `:disposal/disposed-on - 3y` window. Substrate already supports it.

3. **Loss-path implementation (art. 122)** — surface `:mx-pf-loss-real-asset`
   / `:mx-pf-loss-share` carryforward emissions and consume them on
   the next year's run. The 10-year loss divisor cap parameter is
   loaded but unused (P1-5).

4. **INPC monthly series** — out-of-scope for v1 per docstring;
   defer to a `:parameter` time-series loader companion (per ADR-072
   FX-style pattern noted in note 132 §6).

5. **Annual inflation adjustment (art. 44-46) double-count protection** —
   note 132 §1.2.4 flags this; the MX CIT provider (`mx-isr-corporate`)
   should be the one to handle that, but a docstring cross-reference
   here would help the integrator avoid confusion.

6. **Vocabulary unification with note 132 §3.2** — pick one and
   document the mapping (Nit-1).

---

## §7. Sources cross-checked

### Authority

- [LISR Título IV Cap IV — Justia](https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-iv/) — art. 119-127 régimen general + 129 bolsa.
- [LISR Título V — Justia](https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-v/) — art. 160-161 no residentes.
- [SAT Artículo 120](https://www.sat.gob.mx/articulo/31901/articulo-120) — art. 120 averaging — canonical regulator page.
- [SAT Artículo 127](https://www.sat.gob.mx/articulo/32168/articulo-127) — confirms 5 % is on GAIN, not gross.
- [SAT Artículo 161](https://www.sat.gob.mx/articulo/88443/articulo-161) — confirms 25 % gross / 35 % net (max art. 152) lever.
- [SAT Artículo 152](https://www.sat.gob.mx/articulo/36785/articulo-152) — tarifa anual, max rate 35 % (2026 schedule, brackets indexed to inflation).
- [SAT Artículo 93](https://wwwmat.sat.gob.mx/articulo/15199/articulo-93) — art. 93 exemptions; XIX-a is the casa habitación.

### Commentary

- [SDV Asesores — Art 152 LISR 2026](https://sdv.com.mx/compendio/ley-isr/articulo-152/) — confirms 2026 tarifa with max rate 35 % from MXN 4,511,707.44.
- [Hogare — Cómo exentar el ISR al vender tu casa en México 2025](https://hogare.mx/blog/como-exentar-el-isr-al-vender-tu-casa-en-mexico-2025-y-ahorrar-mas/) — confirms 700k UDIS + 3-yr cooling-off (still in force 2025+).
- [Tirant — Consultoría: Exención de pago ISR](https://prime.tirant.com/mx/actualidad-prime/consultoria-tirant-exencion-de-pago-isr/) — same.
- [IDC 2025 — Venta de acciones y ajustes en CUFIN](https://idconline.mx/fiscal-contable/2025/11/20/venta-de-acciones-claves-fiscales-y-ajustes-en-cufin) — confirms art. 22 CUFIN proportional addition + CUCA reduction.
- [APTA CE — Art 161 LISR](http://www.apta.com.mx/aptace/leyes/articulo.php?ley=LISR&art=161&inc=&actua=6) — confirms 25 % default + max-rate option (35 %).
- [Calculamx — Tablas ISR Anual 2026](https://calculamx.com/tablas-isr/anual) — independent confirmation of the 2026 schedule + 35 % max.

### kontor substrate cited

- `/home/christian-weilbach/Development/kontor/src/kontor/statute.clj:150-174` — `parameter-value-at` lookup semantics (the right helper is used by the provider).
- `/home/christian-weilbach/Development/kontor/src/kontor/statute.clj:55-110` — `eval-condition` predicate vocabulary (`:in` supported — the statute provisions are syntactically valid even though inert).
- `/home/christian-weilbach/Development/kontor/src/kontor/tax_schedule.clj:294-303` — `flat` / `progressive` constructors used.
- `/home/christian-weilbach/Development/kontor/modules/disposal/src/kontor/disposal/schema.clj:123-237` — `:asset-class` / `:exemption-claimed` / `:elective-regime` cardinality.
- `/home/christian-weilbach/Development/kontor/modules/disposal/src/kontor/disposal.clj:179-264` — `record-disposal!` + `void!` API.
- `/home/christian-weilbach/Development/kontor/doc/research/132-mx-cgt-fit.md` — fit assessment; this note's P0 corresponds to §5.3 TODO of that note (the cross-provider two-pass).

---

End of note 144.
