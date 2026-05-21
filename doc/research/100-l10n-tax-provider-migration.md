---
date: 2026-05-21
title: 100 — Migrating the 11 l10n modules onto the ADR-071 tax abstraction
status: scoping note (no code changed)
audience: maintainer + implementer planning the per-l10n tax-provider port
---

# 100 — Migrating the 11 l10n modules onto the ADR-071 tax abstraction

ADR-071's substrate shipped (note 99 Stage 2): `kontor.tax-rate-provider`
(`TaxRateProvider` + `TaxFacts` + `StaticTableProvider`) and
`kontor.tax-posting-builder` (`TaxPostingBuilder` + `StaticTablePostingBuilder`
+ `compute-tax-postings`). ADR-071 itself states the 11 per-l10n migrations are
"consumer-demand-driven" and a "multi-week multi-module project". This note
**scopes** that work; it changes no code.

## TL;DR

- **None of the 11 modules use the kernel `:tax/*` schema** — confirmed by
  `grep`: zero `:tax/code` / `:tax-rep/*` / `kontor.tax-provider` references
  under `modules/l10n-*`. Every module ships its rates as Clojure `def`
  constants/maps and posts directly. ADR-071's "the abstraction was unused"
  is literally true.
- **9 of 11 already have the ADR-071 split de facto.** AT/FR/CA/US/AU/CN/MX
  each ship a `tax.clj` (rate-determination: `compute-tax` /
  `compute-invoice-tax`) cleanly separated from `invoice.clj`
  (posting-expansion). JP has it as `consumption_tax.clj`. BR has it tangled
  inside one 774-line `taxes.clj`. DE is the **only fully-tangled** one — its
  `invoice.clj` hardcodes rates *and* SKR04 routing in one function with no
  `tax.clj` at all.
- **The migration is mostly an adapter-shimming job, not a rewrite.** The
  hard part is not the per-country tax logic (it exists and is tested) — it
  is (a) deciding whether to back each country with `StaticTableProvider`
  (needs seeding `:tax/*` entities into each chart EDN) or a bespoke
  `TaxRateProvider` wrapping the existing `compute-tax`, and (b) closing
  five concrete substrate gaps the real l10n code reveals (see §5).
- **Pilot recommendation: challenge ADR-071's "DE first".** DE *is* the
  smallest LoC delta, but it is the *least informative* pilot — it has no
  `tax.clj` to wrap, so it exercises none of the `TaxRateProvider` seam.
  Pilot **AT instead** (or DE+AT as a pair): AT is the canonical
  already-split shape, so the AT port becomes the template the other 8
  split modules copy. Do DE second as the "no tax.clj" variant.

## How the substrate expects to be used

`TaxRateProvider/rate-facts` takes a single-line `context`
(`tax_rate_provider.clj:108-124`) and returns a `TaxFacts` (or `nil`).
`TaxPostingBuilder/tax-postings` (`tax_posting_builder.clj:49-53`) turns a
`TaxFacts` into `:posting/*` maps. `StaticTableProvider` reads `:tax/*` from
the DB (`tax_rate_provider.clj:162-206`); `StaticTablePostingBuilder` walks
`:tax-rep` repartition lines (`tax_posting_builder.clj:67-99`).

Two viable migration shapes per module:

- **Shape S (static-table)** — seed `:tax` + `:tax-rep` entities into the
  country's chart EDN, then use `StaticTableProvider` /
  `StaticTablePostingBuilder` unchanged, or thinly subclassed. Works when
  the country is single-or-few-rate VAT with simple repartition.
- **Shape B (bespoke provider)** — write a per-country
  `TaxRateProvider` whose `rate-facts` *wraps the module's existing
  `compute-tax`* and emits a `TaxFacts`, plus a per-country
  `TaxPostingBuilder` factored out of the existing `invoice.clj` posting
  code. No DB rate table; the rate `def`s stay where they are. This is the
  lower-friction port for the modules that already have `compute-tax` — the
  function is the provider, it just needs a `TaxFacts`-shaped return.

Shape B is the realistic default for 9/11 modules: their `compute-tax`
already *is* a rate provider in all but return type. Shape S only pays off
where the chart EDN is the natural home for the rate table (DE SKR04, and
arguably AT) and where effective-dating wants DB storage.

## Per-module survey

LoC = current tax-relevant lines (`tax`/`vat`-style ns + the tax portion of
`invoice.clj`). Δ = rough net new lines for the port. Size = port effort.

| Module | Rate code (file:line) | Posting expansion (file:line) | Split today? | Shape | Δ LoC | Size | Hard parts |
|---|---|---|---|---|---|---|---|
| **l10n-de** | none — rates inline in `invoice.clj:26-39` (`rev-account-by-rate` / `vat-account-by-rate`) | `invoice.clj:69-129` (`posting-builder`, `bucket-by-rate`) | **No** — fully tangled, one fn | S (SKR04 already chart-shaped) | +200 | **M** | no `tax.clj` to wrap → must *write* the rate layer; reverse-charge/EU accounts (4125/4120) named in docstring but unimplemented |
| **l10n-at** | `tax.clj:192-291` (`compute-tax`/`compute-invoice-tax`, 6 `:vat-class`) | `invoice.clj:166-305` (`plan-at-invoice-tx-data`) | **Yes** — clean | B | +130 | **S** | reverse-charge is seller-side-only today (no buyer-side both-legs); zero vs exempt routing |
| **l10n-fr** | `tax.clj:183-273` (5 TVA rates + 4 statuses) | `invoice.clj` | **Yes** — clean | B | +130 | **S** | intra-EU-b2b reverse charge; HALF-UP vs HALF-EVEN rounding note |
| **l10n-ca** | `tax.clj:213-329` (`compute-tax`, GST/HST/PST/QST) | `invoice.clj` (360 ln) | **Yes** — clean | B | +200 | **M** | 4 parallel authorities per line → 4-component `TaxFacts`; PST non-recoverable vs QST recoverable; per-component `:jurisdiction` (CRA / Revenu Québec / prov.) |
| **l10n-us** | `tax.clj:183-313` (`compute-tax`, `:rate`/`:rate-table`, `combined-rate`) | `invoice.clj` (391 ln) | **Yes** — clean, already provider-shaped | B + Avalara/SST | +600..+1500 | **XL** | no bundled rates by design; Avalara/SST/TaxJar adapters are throwing scaffolds; per-jurisdiction `:jurisdiction` split; nexus/origin-vs-destination upstream of compute |
| **l10n-jp** | `consumption_tax.clj:55-56` (`standard-rate`/`reduced-rate`); compute is in `invoice.clj` | `invoice.clj` (88 ln) | **Partial** — `consumption_tax.clj` is filing-side only; invoicing compute lives in `invoice.clj` | B | +120 | **S** | 2-rate JCT, 0-dp JPY rounding; non-taxable vs export-exempt vs out-of-scope (3 zero kinds) |
| **l10n-au** | `tax.clj:140-208` (`compute-tax`, single 10% GST) | `invoice.clj` (320 ln) | **Yes** — clean | B | +120 | **S** | trivial single-rate; gst-free vs input-taxed routing only |
| **l10n-cn** | `tax.clj:232-345` (`compute-tax`, general/small-scale ladders) | `invoice.clj` (397 ln) | **Yes** — clean | B | +250 | **M** | general vs small-scale rate ladders; surcharges (`vat.clj:79-103` UMCT/edu) computed on *net VAT payable*, not the line base — does not fit one-line `TaxFacts`; special-vs-general fapiao is buyer-side |
| **l10n-br** | `taxes.clj:62-714` — rates + `compute-tax` all in one 774-ln ns | `invoice.clj` (486 ln) | **Tangled within `taxes.clj`** — `compute-tax` (`taxes.clj:525-714`) mixes 5-tax cascade + base-composition + DIFAL + ICMS-ST | B (bespoke, heavyweight) | +400..+700 | **XL** | 5+ taxes per line (ICMS/IPI/PIS/COFINS/ISS); compound base `cálculo por dentro` (`taxes.clj:206-214`); STF-Tema-69 PIS/COFINS base; DIFAL multi-state (`taxes.clj:262-310`); ICMS-ST pre-collection (`taxes.clj:353-381`) → `:kind :pre-collection`; `MvaProvider` protocol (`taxes.clj:328-345`) already a precedent; `:jurisdiction-specific-codes {:br/icms-cst …}` |
| **l10n-in** | `taxes.clj:103-172` (`compute-tax`, slabs + `component-split` + `dispatch-supply`) | `invoice.clj:209-443` (`per-line-breakdown`, `tax-postings`) | **Yes** — clean, and `component-split` already returns component maps | B | +250 | **L** | CGST/SGST/IGST/UTGST component split → 2-4 components per line; compensation cess → `:kind :cess`; effective-dated slabs (`taxes.clj:72-78`, ADR-026); reverse-charge handled today as a seller-side suppress (`invoice.clj:224-229`) — buyer-side both-legs (ADR-071 P1-71-2) unimplemented; TDS withholding lives in `kontor-payroll-in`, not here |
| **l10n-mx** | `tax.clj:196-348` (`compute-tax`, IVA + IEPS + retenciones) | `invoice.clj` (476 ln) | **Yes** — clean | B + withholding | +300 | **L** | retenciones IVA/ISR → `:kind :withholding`; cash-basis IVA (`tax.clj:11-25`, `:tax/exigibility :on-payment`) — the no-cobrado/cobrado split is posting-builder concern; IEPS → `:kind :duty`/`:surcharge` |

Totals: 7 modules **S/M** (DE/AT/FR/CA/JP/AU/CN), 4 modules **L/XL**
(BR/IN/MX/US).

## §3 — Cross-check against ADR-071's own cost claims

ADR-071 Implication says "AU +120 LoC (trivial single-rate) through US +1500
LoC". This survey **confirms the endpoints and the spread**:

- **AU +120** — confirmed. Single 10% rate, `compute-tax` already pure;
  the port is a `TaxFacts` wrapper + a factored-out builder.
- **US +1500** — confirmed as the *ceiling*, with the Addendum P2-71-3
  caveat intact: US is not a single-protocol port but a multi-protocol
  integration. The `tax.clj` compute is already provider-shaped
  (`:rate`/`:rate-table`, `combined-rate`) — so the *kontor-side* shim is
  small (~+600), but a real Avalara/SST adapter (the throwing scaffolds at
  `tax_rate_provider.clj:222-241`) is the multi-week sub-project. +1500 is
  honest only if "US port" includes the Avalara adapter; the
  kontor-substrate-only port is ~+600.
- **Correction — BR is under-counted by the AU↔US framing.** ADR-071 names
  US as the worst case; on a *kontor-substrate-only* basis BR is comparable
  or worse: a 774-line `taxes.clj` whose `compute-tax` returns an 11-key
  map that must be decomposed into 5+ `TaxFacts` components, plus ICMS-ST
  (`:pre-collection`), DIFAL (multi-`:jurisdiction`), and compound bases.
  Budget BR at **+400..+700** — call it XL alongside US, not a notch below.
- **DE** — ADR-071 calls DE "the smallest refactor — already has the
  logic, just splits the function". **Half-right.** DE's posting logic is
  small, but there is *no `tax.clj`* — unlike AT/FR/CA the rate layer must
  be *written*, not split. DE is small in absolute LoC but is not the
  "just split the function" case ADR-071 implies; AT is.

## §4 — Pilot + migration order

**Pilot: AT (primary), DE (paired second).** ADR-071 nominates DE; this note
challenges that. DE has no `tax.clj`, so a DE-first pilot never exercises
the `TaxRateProvider` wrapping seam — the very thing 8 other modules need a
template for. **AT is the canonical already-split module**: porting AT
produces the reusable pattern (wrap `compute-tax` → `TaxFacts`; factor
`invoice.clj` posting code → `TaxPostingBuilder`) that FR/AU/JP/CA/CN/MX
then copy near-mechanically. Do DE immediately after AT as the deliberate
"no tax.clj, Shape S, seed SKR04 `:tax` entities" variant — that proves the
`StaticTableProvider` path too. Piloting the pair covers both shapes before
committing the bulk.

**Migration order** (each independently shippable, ADR-037 rhythm):

1. **AT** — establish Shape B template + the AT-shaped golden-fixture test
   (`test/kontor/l10n_at/posting_builder_test.clj` per ADR-071).
2. **DE** — Shape S; seed SKR04 `:tax`/`:tax-rep` into the chart EDN; prove
   `StaticTableProvider`.
3. **AU, FR, JP** — trivial-to-easy Shape-B copies of the AT template.
4. **CA** — first multi-authority module; exercises multi-component
   `TaxFacts` + per-component `:jurisdiction`. Validates §5 gap G2.
5. **CN** — rate ladders + the surcharge-on-net-VAT gap (G3).
6. **IN** — component-split + cess + effective-dated slabs + the
   reverse-charge buyer-side dispatch (P1-71-2). Validates G1.
7. **MX** — withholding via `:kind :withholding` + cash-basis exigibility.
   Validates G4.
8. **BR** — the cascade. Do last; it consumes every gap fix from 4-7.
9. **US** — substrate shim only; the Avalara/SST adapter is a separate
   `kontor-l10n-us`-companion track (P2-71-3), not gated on this order.

Rationale: easy modules first to harden the template and the golden-fixture
discipline cheaply; the two XL modules (BR, US) last so they inherit a
proven substrate and every gap closure.

## §5 — Substrate gaps the real l10n code reveals

Concrete, file:line-grounded. Each is a small, additive substrate change —
none invalidates the ADR-071 design.

- **G1 — buyer-side reverse-charge is contract-only, not implemented.**
  ADR-071 P1-71-2 says the `TaxPostingBuilder` dispatches per `:tax-use`:
  buyer-side `:purchase` reverse-charge materialises *two* postings
  (input-VAT receivable + output-VAT payable). `StaticTablePostingBuilder`
  (`tax_posting_builder.clj:82-99`, `sign-for` at `:59-65`) does **not** do
  this — it has no `:reverse-charge` branch at all; it signs every
  component uniformly by `:tax-use`. Today every l10n module dodges this:
  AT (`invoice.clj:90-99`) and IN (`invoice.clj:224-229`) handle
  reverse-charge as a *seller-side suppression* only; no module emits the
  buyer-side both-legs pattern. **Gap:** ship a reference both-sides branch
  (a `kontor.tax-posting-builder/reverse-charge-postings` helper, or a
  documented per-country override) before IN/AT ports, or P1-71-2 stays
  vapor.

- **G2 — `TaxFacts` has no field for tax-on-tax / compound base.** BR's
  `cálculo por dentro` (`taxes.clj:206-214` — ICMS base = net + IPI) and
  the kernel's own `:tax/include-base-amount` (`schema.clj:1563-1567`) both
  need a component to declare it feeds the next component's base. The
  `TaxFacts` component map (`tax_rate_provider.clj:31-52`) has `:base` but
  no `:compound-on` / ordering hint, and `StaticTableProvider` computes
  every component off the *line* base (`component-amount`,
  `tax_rate_provider.clj:150-160`). BR cascade and CN surcharges cannot be
  expressed. **Gap:** add an optional `:compound-on` / `:sequence` to the
  component map (kernel-opaque; per-country provider populates it), or
  document that compound countries always use a bespoke provider.

- **G3 — surcharges computed on net-tax-payable don't fit the per-line
  `TaxFacts`.** CN UMCT + education surcharges (`vat.clj:79-103`,
  `umct-rate-for-tier`) are computed on the *period's net VAT payable*, not
  on any single invoice line. `TaxRateProvider/rate-facts` is strictly
  per-line (`tax_rate_provider.clj:108-124`). This is *correctly* out of
  invoicing scope — but ADR-071's `:component/kind :surcharge` enum value
  implies surcharges are line-level. **Gap:** documentation — clarify that
  `:surcharge` covers line-level surcharges (BR FCP) only; period-level
  surcharges stay in the filing-side report engine (`kontor.report`), not
  the tax provider. No code change, but the enum is misleading without it.

- **G4 — withholding has no posting-side contract.** ADR-071 keeps
  withholding in `TaxRateProvider` with `:kind :withholding`. MX retenciones
  (`tax.clj:254-304`) and IN TDS are real cases. But
  `StaticTablePostingBuilder` (`tax_posting_builder.clj`) has no
  `:withholding` branch — `sign-for` would post it like ordinary VAT, which
  is wrong (withholding reduces the supplier's cash receipt; it is a
  *receivable* against own tax payable, MX `tax.clj:62-75`). **Gap:** same
  shape as G1 — a reference withholding-posting branch, or the contract
  that withholding always needs a bespoke builder. Decide before MX/IN.

- **G5 — the static-table path has no per-country seed data and the
  pipeline is line-at-a-time.** `StaticTableProvider` reads `:tax/*`
  entities (`tax_rate_provider.clj:171-177`) but **no l10n chart EDN seeds
  any** — confirmed by grep. Shape S for *any* module is blocked on
  authoring `:tax`/`:tax-rep` seed EDN. Separately, `compute-tax-postings`
  (`tax_posting_builder.clj:126-137`) processes *one line*; every l10n
  `compute-invoice-tax` aggregates + buckets across lines (e.g.
  `invoice.clj:45-63` DE `bucket-by-rate`). **Gap (minor):** a
  multi-line/invoice-level convenience wrapper (the deferred
  `kontor.tax-pipeline` ns ADR-071 mentions) so per-l10n builders don't
  each re-roll line aggregation. Not blocking — but every module will
  otherwise duplicate bucketing.

Smaller observations (not gaps, worth noting): `tax-facts` and the
component map both carry `:jurisdiction` — CA (4 authorities) and BR (DIFAL)
will exercise the per-component slot heavily, and it is currently only
populated from `:tax/authority` (`tax_rate_provider.clj:195-196`); the
`:jurisdiction-specific-codes` opaque slot is ready for BR CST / IN
GST-state-code with no change.

## Deferrals (explicit)

- The real Avalara / TaxJar / SST adapters (US) — separate `kontor-l10n-us`
  track, not gated on this migration (P2-71-3).
- The `:tax-fact/*` audit-snapshot entity + `:posting/tax-fact-id` — ADR-071
  names it deferred; this migration does not need it, though BR/US (purchased
  rates) will eventually want it.
- `kontor.tax-pipeline` full ns with the `kontor.document.invoice/send!`
  adapter — deferred by ADR-071; G5's minor wrapper is the part this
  migration would pull forward.
- CN period-level surcharges, IN/MX withholding *workflow* (vs the rate
  facts) — stay in the filing-side namespaces; not in scope for the tax
  provider port.

## Bottom line

The 11-module migration is **adapter shimming + 4 small additive substrate
fixes (G1-G4) + seed data (G5)**, not a rewrite — because 9 of 11 modules
already separated rate-determination from posting-expansion years ago. Pilot
**AT** (canonical split → reusable template), pair with **DE** (Shape S /
no-`tax.clj` variant), then easy→hard: AU/FR/JP → CA → CN → IN → MX → BR,
with US's substrate shim parallel and its Avalara adapter on its own track.
Close G1 (reverse-charge both-legs) and G4 (withholding posting) before the
IN/MX ports or ADR-071's P1-71-2 reverse-charge contract remains unproven.
