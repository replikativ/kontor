---
date: 2026-05-24
title: 139 — UK CGT (TCGA 1992 + Autumn Budget 2024) baseline review
audience: maintainer
status: review-after — ADR-103 per-jurisdiction CGT providers, UK module
---

# 139 — UK CGT baseline review

ADR-103 shipped per-jurisdiction CGT providers; the UK module is a brand-new
artifact created from scratch by the parallel implementation agent:

- `modules/l10n-uk/src/kontor/l10n_uk/cgt_statute.clj` — 11 `:parameter`s, 22
  `:parameter-value`s, an empty `provisions` seq, plus an `install!`.
- `modules/l10n-uk/src/kontor/l10n_uk/cgt_provider.clj` — 423 lines, two
  `PeriodTaxProvider` instances (`uk-individual-cgt-provider` and
  `uk-corporate-cgt-provider`) wrapped in one `defrecord` switched on `:kind`.
- `modules/l10n-uk/test/kontor/l10n_uk/cgt_provider_test.clj` — 13 deftests,
  the Jane £195,256 worked example reproduces to the penny, ABC Ltd
  corporate freehold reproduces to the pound, both per note 114 §2.

Wiring (`deps.edn`, `tests.edn`) is correct — `modules/l10n-uk/{src,resources}`
appear in `:paths`, `modules/l10n-uk/test{,/resources}` appears in both
`:test` and `:dev` aliases, and `modules/l10n-uk/test` is in
`tests.edn :test-paths`. No typos.

Per the maintainer's standing mandate for every l10n module, this note
audits the UK encoding against legislation.gov.uk, gov.uk's CGT rates and
allowances page (re-fetched 2026-05-24), HMRC's Capital Gains Manual (CG
references), the gov.uk Autumn-Budget-2024 IR-lifetime-limit publication,
and secondary commentary (Deloitte TaxScape, ICAEW Taxline, BDO, Crowe UK,
TaxCalc KB, rossmartin.co.uk).

**Headline.** Statute encoding is correct: every rate, every effective
date, every cap I cross-checked matches authority. Worked examples
reproduce. The provider design is clean and the test set is honest about
what it covers. Three real correctness issues sit behind otherwise
ship-grade code — none ship-blocking, two with concrete reproduction
recipes, one a substrate quirk worth a kernel-level docstring follow-up:

- **P1** — **TCGA s2A vs. s3 loss-vs-AEA priority is not modelled.** The
  current-year-losses-mandatory / brought-forward-losses-only-down-to-AEA
  ordering rule is folded into one combined deduction
  (`allocate-losses-and-aea`). The `:unused-loss-carryforward` calc in
  `individual-components` (cgt_provider.clj:270-274) computes the residual
  loss as `max(0, losses_bucket − Σgross_gains)` — under-reports leftover
  brought-forward loss whenever gross gains < AEA + losses (concrete:
  £5,000 gain, £5,000 carry-in loss, AEA £3,000 → real residual loss
  £3,000, provider reports £0).
- **P1** — **Half-open `[from, until)` window is silently fragile at
  tax-year boundaries.** The test fixture's period `:to #inst "2025-04-05"`
  is one day earlier than the natural "TY 2024/25 ends" instant
  (2025-04-06, the start of TY 2025/26) **because** landing on 2025-04-06
  silently picks up the 14% BADR rate. The test even calls this out
  (cgt_provider_test.clj:71-73). This is a real substrate trap: a consumer
  who uses `:to #inst "2025-04-06"` to mean "everything in TY 2024/25"
  will silently get next year's rates. Fix in the provider (clamp
  `as-of-from-ctx` to `(max <:to> − 1ms)`), or document loudly in
  `kontor.statute/parameter-value-at`'s docstring + a `:period` convention
  ADR.
- **P2** — **Unused `band` validation, `_` underscore-prefixed bindings
  in let, `consume`'s `available` parameter unused for accumulator
  arithmetic when feeding fold through `_`.** The code is mostly clean
  but a few small cosmetic things deserve a follow-up pass.

Everything below details each finding with file:line and citation.

## §0. Module bootstrap audit (passed)

Concretely checked:

- `deps.edn` line 37 — `"modules/l10n-uk/src"     "modules/l10n-uk/resources"`
  in `:paths`. ✓
- `deps.edn` line 110 — `"modules/l10n-uk/test"      "modules/l10n-uk/test/resources"`
  in `:test` alias `:extra-paths`. ✓
- `deps.edn` line 162 — same path in `:dev` alias `:extra-paths`. ✓
- `tests.edn` line 27 — `"modules/l10n-uk/test"` in `:test-paths`. ✓
- Module tree shape — `modules/l10n-uk/{src,resources,test}` exist; ns
  paths `kontor.l10n-uk.cgt-statute` / `kontor.l10n-uk.cgt-provider` /
  `kontor.l10n-uk.cgt-provider-test` correctly mirror filesystem layout
  (l10n_uk/cgt_statute.clj etc.).

No typos. Bootstrap is clean. Module participates in `bb test` like every
other l10n.

## §1. Statute-fidelity audit — every value, every date

Verified against the gov.uk CGT rates and allowances guidance page
(re-fetched 2026-05-24, see §6 for sources) plus the cross-cited
authority for each row.

### 1.1 AEA — CORRECT

| `:parameter-value/decimal-value` | `:effective-from` | `:effective-until` | Authority |
|---|---|---|---|
| 6,000 | 2023-04-06 | 2024-04-06 | Spring Budget 2023 — gov.uk page 1.1 "6 April 2023 to 5 April 2024 £6,000" |
| 3,000 | 2024-04-06 | (open) | Spring Budget 2023 — gov.uk page 1.1 "From 6 April 2024 £3,000" |

Both rows correct. Pre-2023 history (£12,300 through 2022/23) is
intentionally omitted — note 114 §3.4 documents this as a "provider-
config constant" decision; the statute table picks up at 2023-04-06
which is the earliest period a 2026-running provider will be asked
about under realistic backcast windows. Acceptable.

`:parameter/code "UK.CGT.AEA"` — `:concept-iri` set to
gov.uk/capital-gains-tax/allowances which is the correct stable URL.

### 1.2 Standard CGT rates — CORRECT (this is the headline 2024 change)

| Code | Value | `:effective-from` | `:effective-until` |
|---|---|---|---|
| `UK.CGT.std.basic-rate`  | 0.10 | 2016-04-06 | 2024-10-30 |
| `UK.CGT.std.basic-rate`  | 0.18 | 2024-10-30 | (open) |
| `UK.CGT.std.higher-rate` | 0.20 | 2016-04-06 | 2024-10-30 |
| `UK.CGT.std.higher-rate` | 0.24 | 2024-10-30 | (open) |

Cross-check vs. gov.uk page (re-fetched 2026-05-24):

> **30 October 2024 to 5 April 2025:** "18% and 24% for individuals
> (not including carried interest gains)"
> **6 April 2024 to 29 October 2024:** "10% and 20% for individuals
> (not including residential property gains and carried interest gains)"

The half-open `[from, until)` semantics make 2024-10-30 the cleanest
boundary: a disposal on 2024-10-29 reads `[2016-04-06, 2024-10-30)` =
10/20; a disposal on 2024-10-30 reads `[2024-10-30, ∞)` = 18/24.
Matches Treasury's "with effect from 30 October 2024" language exactly.

Note 114 §1.1 mentions a **32% carried-interest single rate from
6 Apr 2025** which is NOT encoded. The agent's ns docstring at
cgt_statute.clj:36-38 acknowledges the v1 simplification (single
basic/higher selection, no carried-interest lane). Acceptable
deliberate scope cut; carried interest is a tiny taxpayer cohort and
the lane can be added when a consumer asks. Document the omission in
a `:gap` line item of the module README if/when one is added.

### 1.3 Residential rates — CORRECT

| Code | Value | `:effective-from` | `:effective-until` |
|---|---|---|---|
| `UK.CGT.residential.basic-rate`  | 0.18 | 2016-04-06 | (open)   |
| `UK.CGT.residential.higher-rate` | 0.28 | 2016-04-06 | 2024-04-06 |
| `UK.CGT.residential.higher-rate` | 0.24 | 2024-04-06 | (open)   |

Spring Budget 2024 cut the higher-rate residential CGT 28% → 24%
effective 6 Apr 2024 (FA 2024 s31). Basic rate stayed at 18%
throughout. After Autumn Budget 2024 (30 Oct 2024) the residential
rates and standard rates align numerically but the codes remain
distinct — note 114 §1.1 and cgt_statute.clj:73-76 both call this
out (a future Treasury "residential surcharge re-introduction" is a
one-row migration, not a code edit). Defensible.

### 1.4 BADR — CORRECT

| Code | Value | `:effective-from` | `:effective-until` |
|---|---|---|---|
| `UK.CGT.BADR.rate` | 0.10 | 2020-03-11 | 2025-04-06 |
| `UK.CGT.BADR.rate` | 0.14 | 2025-04-06 | 2026-04-06 |
| `UK.CGT.BADR.rate` | 0.18 | 2026-04-06 | (open) |
| `UK.CGT.BADR.lifetime-cap` | 1,000,000 | 2020-03-11 | (open) |

The 11 March 2020 effective-from is the BADR/ER-cap-cut Budget day
(TaxCalc KB 3111 confirms: "for disposals on or after 11 March 2020 …
£10 million to £1 million"). FA 2020 also renamed Entrepreneurs'
Relief to BADR.

Rate trajectory verified against gov.uk (re-fetched 2026-05-24):

> **2024/25 (6 April to 29 October 2024):** 10%
> **30 October 2024 to 5 April 2025:** 10%
> **6 April 2025 to 5 April 2026:** 14%
> **6 April 2026 onwards:** 18%

The 10/14/18 trajectory matches; the boundaries are at 6 Apr (start of
TY) not 30 Oct (Autumn Budget) because that's the Treasury-chosen
phase-in. ✓

**One pre-2020-03-11 omission**: the £10M Entrepreneurs' Relief cap
window (2008-06-23 through 2020-03-10) is not encoded. A taxpayer
querying their pre-March-2020 BADR/ER history via this provider would
see `parameter-value-at` return `nil` for the cap. The provider's
`(param "UK.CGT.BADR.lifetime-cap")` then becomes `nil`, gets `or
… 0M`'d at cgt_provider.clj:226, and a `:badr-eligible` slice would
hit a remaining cap of `max(0, 0 − prior-used)` = 0, silently
denying all BADR claims pre-2020. **P2** — add a 10,000,000M row for
2008-06-23 → 2020-03-11 if pre-2020 backcast is in scope; if not,
document the deliberate cut-off in the statute ns docstring.

### 1.5 Investors' Relief — CORRECT (including the £1M cap slash)

| Code | Value | `:effective-from` | `:effective-until` |
|---|---|---|---|
| `UK.CGT.IR.rate` | 0.10 | 2016-04-06 | 2025-04-06 |
| `UK.CGT.IR.rate` | 0.14 | 2025-04-06 | 2026-04-06 |
| `UK.CGT.IR.rate` | 0.18 | 2026-04-06 | (open) |
| `UK.CGT.IR.lifetime-cap` | 10,000,000 | 2016-04-06 | 2024-10-30 |
| `UK.CGT.IR.lifetime-cap` | 1,000,000  | 2024-10-30 | (open) |

The IR rate trajectory mirrors BADR exactly (Deloitte TaxScape:
"the changes see the rate of tax on BADR or IR qualifying disposals
remain at 10% to 5 April 2025, increase to 14% for the 2025/26 tax
year, and to 18% thereafter"). ✓

The £1M cap is effective from 30 October 2024 (gov.uk publication
"Capital Gains Tax — Investors' Relief: reduction in the lifetime
limit": "reduces the lifetime limit from £10 million to £1 million for
IR qualifying disposals made on or after 30 October 2024"). The
half-open windows correctly anchor at 30 Oct 2024 (not 6 Apr 2025).

cgt_statute.clj:205-208 has a polite note about the Investors' Relief
cap discrepancy in the task spec — the implementation correctly
follows note 114 (the authoritative research). Good.

### 1.6 SSE 10% threshold — CORRECT

`UK.CGT.SSE.min-holding-fraction = 0.10M`, effective 2002-04-01 (open).

TCGA Sch 7AC was inserted by Finance Act 2002 (commencement
1 April 2002). The 10% threshold is the substantial-shareholding
test. The substrate doesn't currently use this parameter — the
provider filters disposals carrying `#{:uk-sse}` in
`:exemption-claimed` and trusts the consumer's eligibility flag
(cgt_provider.clj:322-330). Encoding the threshold as a parameter is
forward-leaning (a future provider that queries the holding
percentage and applies the test mechanically can read it). No issue
today; intent is clear.

The 12-month-in-6-years rule (CG53078 — "twelve-month period
beginning not more than six years before the day on which the
disposal takes place") is NOT encoded. The agent correctly defers
this to consumer assertion (the `:uk-sse` flag is asserted by the
consumer who has the holding-period data; the provider can't compute
this from `:disposal/acquired-on` alone because SSE allows
non-contiguous windows per CG53080C). Acceptable v1 cut; this matches
note 114 §3.4 SSE design.

### 1.7 What's NOT encoded — gap-list

Documented gaps consistent with note 114:

- **Indexation factors** — frozen Dec 2017; cgt_provider.clj:24-28 +
  cgt_statute.clj:30-32 both correctly delegate to the consumer
  ("already-indexed basis") per note 114 §3.2. The `:disposal/basis-amount`
  for a corporate pre-2018 share-purchase IS expected to be RPI-rolled
  to Dec-2017 by the consumer. ✓
- **Rollover relief s152** — gain deferred via
  `:disposal/rollover-amount` (the kernel disposal schema attr); the
  provider's `realized-gain` correctly subtracts `r` at
  cgt_provider.clj:101. ✓
- **Holdover relief s165** — gift-of-business-asset; recorded as
  `:disposal/kind :gift` per the kernel enum. Provider doesn't
  specially treat it (the gain just flows). Note 114 §3.4 lists this
  as deferred; OK.
- **Negligible-value claim s24(2)** — `:disposal/kind :abandonment`
  with nil proceeds (or zero) — works. Provider has no specific
  treatment; the loss surfaces via the loss-bucket. ✓
- **Connected-persons "clogged" losses TCGA s18** — not modelled.
  Note 114 §3.4 P3 explicitly defers to a follow-up. ✓
- **Carried-interest single 32% rate from 2025-04-06** — not
  modelled (see §1.2). Acceptable.
- **Income-band determination from a UK PIT provider** — note 114
  §5.1 envisaged threading `(:income-tax-facts inputs)` to compute the
  basic-band residual. v1 takes the simpler tax-unit
  `:income-band` keyword and defaults to `:higher`. Documented at
  cgt_provider.clj:75-77 + cgt_statute.clj:33-38. Acceptable v1 cut.

## §2. Provider logic audit — line-by-line

### 2.1 `realized-gain` (cgt_provider.clj:89-101) — CORRECT

`proceeds − basis − rollover-amount`. Matches HMRC computation
surface; rollover is the s152-deferred slice. For corporates the
"already-indexed basis" comment at cgt_provider.clj:93-96 documents
the convention plainly. ✓

### 2.2 `classify` (cgt_provider.clj:121-140) — CORRECT for v1

Disposal → lane keyword. Priority: BADR > IR > residential > standard.
A disposal carrying both `#{:uk-badr :uk-investors-relief}` (illegal
under the statute — they're mutually exclusive per the 5%-vs-3% +
employee-vs-non-employee distinction) would silently pick BADR. The
kernel's exemption-claimed set is many-cardinality so this CAN happen
data-wise. **P3 (advisory)**: a doc line saying "if both BADR and IR
are present, BADR wins" — or a 1-line `when` raise. Not a real risk
because the consumer asserts qualification but it's a future-debugging
landmine. Skip if you prefer simplicity.

### 2.3 `apply-lifetime-cap` (cgt_provider.clj:187-195) — CORRECT

`remaining = max(0, max-cap − prior-used)`; `capped = min(claim,
remaining)`. The Jane test exercises this exactly (£1.4M − £100 −
£3k AEA → 1,396,900 BADR-eligible, capped to 1,000,000, overflow
396,900 cascades to standard). ✓

### 2.4 `allocate-losses-and-aea` (cgt_provider.clj:169-185) — **P1 (semantic)**

The provider treats losses + AEA as a **single combined deduction**
swept across `[:standard :residential :ir-eligible :badr-eligible]`
in highest-rate-first order. Highest-rate-first IS the HMRC
taxpayer-favourable allocation (gov.uk page references "you can use
your annual exempt amount against the gains charged at the highest
rates"; principle confirmed in TaxAdviser commentary). ✓ for the
ordering.

But **collapsing losses + AEA into one deduction violates TCGA s2A /
s3 priority** in two cases:

- **Current-year losses are mandatory, fully consumed against gains
  before AEA applies.** Right now if gains = 5,000 and current-year
  losses = 1,000 and AEA = 3,000, the provider deducts 4,000 from
  gains → net 1,000. Statutorily losses absorb 1,000 first (net 4,000),
  then AEA absorbs 3,000 (net 1,000). Same answer. ✓ in this case
  because losses ≤ gains.
- **Brought-forward losses are only used to bring net down to the AEA,
  not below.** This is the real bug. Reproduction: gross gains 5,000,
  brought-forward loss 5,000, AEA 3,000. Statutory result: AEA absorbs
  3,000 (taxable net pre-loss = 2,000); brought-forward loss absorbs
  only 2,000 (down to zero); **3,000 of brought-forward loss carries
  forward**. Provider result: `total-loss-bucket = 5,000`, `deductions
  = 8,000`, sweeps 5,000 gains → 0; `unused-loss = max(0, 5,000 −
  5,000) = 0`. **Loses 3,000 of permanent loss capacity.**

The provider's `:unused-loss-carryforward` (cgt_provider.clj:270-274)
formula is `max(0, total-loss-bucket − Σgross_gains)`. As shown above
this **under-reports** the residual loss whenever gross gains < AEA +
losses. Tests never exercise this scenario (every loss-carryforward
test in §5 has gross gains > losses + AEA).

**Fix**: split `allocate-losses-and-aea` into two passes — losses
first (taking the highest-rate-first cascade), THEN AEA on the
residual gains (also highest-rate-first), with an explicit "AEA is
applied to taxable net, not loss" boundary. Re-derive
`unused-loss-carryforward` as `max(0, losses_bucket − Σgains_absorbed_
by_losses)`. Document the s2A/s3 ordering in a docstring.

Severity: **P1** because real customers (e.g. small individual
investors carrying ~£20k of crisis-era losses against modest gains
within AEA range) would silently lose loss capacity. Workaround
exists (the consumer can re-compute the carryforward themselves) but
that's exactly what the provider is meant to do.

### 2.5 BADR / IR overflow cascade — CORRECT post-Oct-2024

cgt_provider.clj:247-254 cascades BADR / IR cap-overflow into the
STANDARD slice (not RESIDENTIAL). This is correct per HMRC
guidance: "Gains above the lifetime limit are taxed at the standard
CGT rates" (BDO, Crowe UK, multiple secondaries). The post-Oct-2024
alignment of standard and residential at 18/24 means the choice is
behaviour-equivalent today.

**Pre-Oct-2024 caveat**: standard was 10/20 and residential was
18/24, so a hypothetical BADR-eligible residential disposal (rare
but technically possible for furnished holiday lettings pre-2025)
with cap-exceeded would now go to STANDARD-10/20 not
RESIDENTIAL-18/24. BADR on furnished holiday lets was a real
allowance pre-April-2025 abolition. Severity here is **P3** —
unlikely-edge-case, narrow window, plausibly under-charges the
taxpayer 8 points which is fine if anything. Skip; document if you
must.

### 2.6 `band-rate` default (cgt_provider.clj:201-208) — CORRECT

`(case band :basic basic :higher higher higher)` — unknown band
defaults to `:higher` (the conservative choice). cgt_provider.clj:74-77
ns docstring documents this. ✓ The `income-bands` set defined at
cgt_provider.clj:73-77 isn't actually used to validate the input
(it's documentation-only). **P3**: add a `(when-not (income-bands
band) (throw ...))` if you want strict validation, or remove the set
if it's docs-only. Pick one; current state is "looks like
validation, isn't."

### 2.7 `individual-components` — corner cases

A few small things:

- **Line 270 `unused-loss` calc** — see §2.4 (P1). The `0M` term in
  the `+` at line 273 plus the comment "portion of in-period loss that
  offset gains is (total - residual)" reads like leftover scratch
  work. Likely needs a re-think anyway when fixing the s2A/s3
  ordering.
- **`(when (pos? (+ gross-base resi-amt std-amt)))` at line 275** —
  `gross-base = badr-claim + ir-claim + std-amt + resi-amt`; adding
  `resi-amt + std-amt` again is redundant. Effectively gates on
  `gross-base > 0`. The intent is "emit only if SOMETHING is
  taxable" which is right; but `(pos? gross-base)` would be clearer.
  **P3 cosmetic**.
- **`band` is destructured then only used for `(:income-band unit)`
  in `or`** — fine; just noted.
- **`carry-loss` from `:inputs :capital-loss-carryforward :uk-capital`**
  — keys are correctly UK-namespaced. ✓

### 2.8 `corporate-components` — mostly correct, one cosmetic

- **`gross = (reduce + 0M (map :gain post-sse))`** — signed sum across
  non-SSE disposals. ✓
- **`net = gross − carry-loss`** — current-period chargeable gains
  reduced by carry-in. ✓
- **`net' = max(0, net)`** — clamps to zero. ✓
- **`carry-out = max(0, carry-loss − gross)`** — works for the gross
  > 0 case. For gross < 0 (net in-period loss): `carry-out =
  max(0, carry-loss + |gross|)` which is correctly the carry-in PLUS
  the new period's loss. ✓
- **`:line :losses-applied :value (money/money (min carry-loss gross)
  commodity)`** at line 354 — when gross is negative (net in-period
  loss), shows a NEGATIVE "losses applied" amount which is confusing
  audit-trail-wise. **P3 cosmetic**: `(max 0M (min carry-loss gross))`
  would render 0 in the loss case, which is the truthful "no carry-in
  loss was needed because the period was itself a loss" reading.
- **Pre-existing-loss-only scenario**: if a corp has NO disposals this
  period (`classified = []`) and a carry-in loss, the `when` predicate
  at line 335 evaluates `(or (pos? 0) (pos? 0) (pos? carry-out))`.
  `carry-out = max(0, carry-loss − 0) = carry-loss > 0`, so a
  component IS emitted carrying `:cit-base-additions [0]` and the
  unused carryforward in `:jurisdiction-specific-codes`. Good — the
  corporate consumer needs to know the carryforward persists. ✓

### 2.9 The half-open window trap — **P1 (substrate quirk)**

cgt_provider.clj:83-87 `as-of-from-ctx` reads `(:to ctx)` for the
"as-of" instant fed to `parameter-value-at`. `parameter-value-at`
uses `[from, until)` half-open windows (statute.clj:170-172).

Concrete trap: TY 2024/25 ends on **5 April 2025**; TY 2025/26
starts **6 April 2025**. A consumer modeling "everything in TY
2024/25" with `:period {:to #inst "2025-04-06"}` (the exclusive
upper-bound convention) feeds `as-of = 2025-04-06` to
`parameter-value-at`. The BADR-rate window is
`[2020-03-11, 2025-04-06)`. 2025-04-06 is NOT in that window
(half-open). The next window `[2025-04-06, 2026-04-06)` = 14% IS in
that window. So the consumer asking "compute my TY 2024/25 BADR"
silently gets the 14% rate.

The test's own comment at cgt_provider_test.clj:71-73 is the smoking
gun — the implementor noticed the trap and worked around it by
picking `:to #inst "2025-04-05"` (one day inside the desired
window). But this is consumer-hostile: every UK CGT consumer will
need to know this quirk, and the natural "tax year period"
convention `{:from "2024-04-06" :to "2025-04-06"}` is wrong.

This is a substrate quirk shared with every l10n CGT/PIT/CIT
provider, not UK-specific. Options:

1. **Provider-level fix** — `as-of-from-ctx` returns `(:to period)
   − 1ms` for end-anchored reads. Trivial change; localized.
2. **Substrate-level convention** — document in
   `kontor.statute/parameter-value-at`'s docstring + a kontor
   convention note that `as-of` for end-period reads should be `(:to
   period) − 1ms`, and add a `kontor.statute/parameter-value-at-end-
   of-period` helper.
3. **Statute-side fix** — encode value windows as `[from, until]`
   closed instead of `[from, until)` half-open. Big migration; not
   advised.

I'd lean (1) for the UK provider TODAY (a one-line fix) and file (2)
as a kernel follow-up. Mark as **P1** because every other CGT
provider in the project (CA / DE / JP / US / FR / AT / BR / IN / MX
/ CN / AU) has the same issue and they all need to be checked +
fixed in tandem.

### 2.10 Provider error handling — small wins

- `kind` rejection at cgt_provider.clj:386-387 raises with a clear
  message. ✓
- `:source` rejection at cgt_provider.clj:403 + 413 raises early. ✓
- `:db` rejection at cgt_provider.clj:374-376 raises with the
  ctx-keys for debugging. ✓
- No `nil` propagation booby-traps spotted in the arithmetic
  (`or … 0M` defaults at lines 98-100, 226-232, 233).

## §3. Test-suite audit

### 3.1 Worked-example reproduction

- **note 114 §2.1 Jane £195,256** — cgt_provider_test.clj:106-126.
  Hand verification: gain 1,400,000 − 100 = 1,399,900. AEA 3,000 →
  1,396,900. BADR slice 1,000,000 @ 10% = 100,000. Standard slice
  396,900 @ 24% = 95,256. Total 195,256. ✓ Matches the test's
  expected `195256M` exactly.
- **note 114 §2.2 ABC Ltd freehold (chargeable-gain 149,800)** —
  cgt_provider_test.clj:327-347. Hand verification: 450,000 − 300,200
  = 149,800. ✓ matches `149800M`. Test correctly asserts liability=0
  (this layer feeds CT base, doesn't compute corporate tax) and
  asserts `:cit-base-additions [149800M]`.

Both reproduce to the penny / pound. Solid.

### 3.2 Coverage map (13 tests / ~24 assertions)

- **§1 plumbing**: `empty-source-returns-zero-components`,
  `kind-validation`. Both shoulder-checks. ✓
- **§2 BADR**: Jane sale + `badr-lifetime-cap-fully-exhausted-stops-
  future-claims` (the 500k second sale → 119,280 at 24%). The latter
  exercises the cap-overflow cascade to standard cleanly.
- **§3 residential**: higher-rate (24%) + basic-rate (18%). Both
  reproduce to the penny.
- **§4 AEA**: the small-gain (under AEA) returns no component (lane
  is dropped). The "AEA absorbed by standard, BADR untouched" test
  is the highest-rate-first cascade in action.
- **§5 losses**: carry-forward + in-period loss. Both run.
- **§6 IR**: claims at 10% within £1M cap. ✓
- **§7 corporate**: SSE exemption + ABC Ltd freehold + corp
  carryforward.
- **§8**: voided disposal exclusion. ✓
- **§9 bitemporal**: pre-Budget-2024 disposal reads pre-budget
  rates. This is the only test that exercises the rate-window
  evaluation; it succeeds because `:to #inst "2024-10-29"` lands in
  `[2016-04-06, 2024-10-30)`. ✓

### 3.3 Coverage gaps

Three real holes the agent left:

- **No test for the AEA-vs-loss priority** (the P1 from §2.4). The
  test would be: gross 5,000, carry-loss 5,000, AEA 3,000 → real
  unused carryforward should be 3,000, provider returns 0. Add
  alongside the fix.
- **No test for the rate-window-boundary trap** (the P1 from
  §2.9). Test: `:to #inst "2025-04-06"` produces a wrong rate.
  Add alongside the fix; assert correct rate after fix.
- **No test for `:disposal/exemption-claimed #{:uk-badr
  :uk-investors-relief}` mutually-exclusive collision** (the P3 from
  §2.2). Add only if you decide to enforce mutual exclusion.

Other gaps that note 114 already triaged as deferred:

- **Indexation factor consumption for a corporate pre-2018
  acquisition** — the test ABC Ltd freehold is pre-Dec-2017 + the
  basis is already pre-rolled; a test where the consumer is meant
  to compute the indexation themselves would be a one-line addition
  but the point is documentation, not provider logic. Skip per note
  114 §3.2.
- **Rollover relief (s152)** — `:disposal/rollover-amount`
  reduces `realized-gain` correctly per code review but isn't
  exercised by a test. **P2 (followup)**: add a rollover test.
- **Holdover relief (s165) gift-of-business-asset** — same.
  Document; skip.
- **Connected-persons clogged losses (s18)** — note 114 §3.4 P3 defer.

### 3.4 Test-mechanics nits

- **`record!` helper at cgt_provider_test.clj:39-50** uses `:subject
  gbp` as a polymorphic-ref placeholder. This works (the disposal
  schema accepts any ref) but is semantically odd — the disposal's
  `:subject` is supposed to be the disposed entity, not its
  commodity. **P3 cosmetic**: define a tiny placeholder entity (e.g.
  `{:db/id "asset-placeholder" :commodity/symbol "PLACEHOLDER"}`) for
  cleaner test data. Not a real bug.
- **`run-provider`** doesn't pass `:as-of` separately from `:period`
  — it relies on `as-of-from-ctx` reading `:to`. Fine for tests
  while we tolerate the substrate quirk; revisit after §2.9 fix.
- **No `is (= …)` for `:line-items`** — tests only check
  `:liability` and a couple of `:jurisdiction-specific-codes` keys.
  This is fine for v1; line-items are audit-trail noise, not
  computation-correctness.

## §4. Cross-cutting / market-pain checks

Quick market-pain scan against the gov.uk page + Deloitte / BDO /
Crowe / ICAEW commentary, looking for things UK CGT customers
actually complain about that the provider might miss:

- **60-day in-year residential CGT return** (HMRC requirement since
  6 Apr 2020) — the provider returns a `TaxReturnFacts` for the
  assessment period; it doesn't surface the 60-day-from-completion
  payment deadline. **P2**: a `:filing-windows` line in
  `:jurisdiction-specific-codes` or per-component would help a
  remittance-calendar consumer. Defer to ADR-103-followup; not UK-
  specific (DE has the IRN 24-hour clock, FR has acompte deadlines).
- **Anti-forestalling on the Autumn-Budget-2024 rate change** —
  KPMG TaxNewsFlash described anti-forestalling provisions
  (treating contracted-but-uncompleted disposals between Oct 30 and
  Apr 6 as post-budget for rate purposes). Provider's
  `:disposed-on` is the realising-date so a consumer would need to
  consult the SPA exchange date vs. completion date externally.
  **P3 (advisory)**: document in module README + a UK-specific
  note on how to set `:disposed-on` for anti-forestalling cases.
- **No connected-persons modelling** (note 114 §3.4 P3, deferred).
  Re-confirmed.
- **No carried-interest 32% lane** (§1.2). Re-confirmed.

## §5. P0 / P1 / P2 / P3 summary

**P0**: none.

**P1** (real correctness issues that mis-charge a real customer):

1. **§2.4 / §2.7** — TCGA s2A/s3 loss-vs-AEA ordering not modelled;
   `:unused-loss-carryforward` under-reports residual brought-forward
   loss when gross gains < losses + AEA. Concrete repro: gain 5k,
   carry-loss 5k, AEA 3k → real residual 3k, provider returns 0.
   Fix: split `allocate-losses-and-aea` into losses-first then
   AEA-on-residual passes; re-derive carryforward from absorbed-by-
   losses subtotal. Add the §3.3 test.

2. **§2.9** — Half-open `[from, until)` rate-window evaluation makes
   the natural `{:to "2025-04-06"}` tax-year-end period
   silently pick up next year's rate. The implementer noticed and
   worked around it in tests but didn't fix the provider. Fix in
   `as-of-from-ctx`: clamp to `(:to period) − 1ms` for end-period
   reads. Track as a kernel followup affecting every CGT/PIT/CIT
   provider.

**P2** (followups — real but low-impact or out-of-v1-scope):

1. **§1.4** — Pre-2020-03-11 BADR/ER lifetime-cap window not
   encoded (`parameter-value-at` returns nil → silently denies
   BADR claims for pre-2020 backcasts). Add the £10M window or
   document the cut-off.
2. **§4** — 60-day in-year residential CGT return deadline not
   surfaced. Add to `:jurisdiction-specific-codes` or
   per-component `:filing-windows`. Not UK-specific; coordinate.
3. **§3.3** — No test for `:disposal/rollover-amount` s152 path
   (`realized-gain` subtracts it correctly but it's not exercised).

**P3** (cosmetic / very-edge-case):

1. **§2.2** — `:uk-badr` + `:uk-investors-relief` co-claim case is
   silently BADR. Add a `when` raise or a docstring line.
2. **§2.5** — Pre-Oct-2024 BADR-on-furnished-holiday-lets cap-
   overflow goes to STANDARD instead of RESIDENTIAL (under-charges
   ~8 points in a 6-month window of a discontinued allowance). Note
   in docstring.
3. **§2.6** — `income-bands` set defined but never validated. Add
   a `when-not` or remove.
4. **§2.7** — `(pos? (+ gross-base resi-amt std-amt))` is
   redundant; `(pos? gross-base)` is clearer.
5. **§2.8** — Corp `:losses-applied` line-item shows negative when
   period is itself a loss; clamp to `(max 0M …)`.
6. **§3.4** — Test fixture's `:subject gbp` placeholder is odd
   (commodity ref masquerading as disposed entity).
7. **§4** — Carried-interest 32% lane + anti-forestalling docs.

## §6. Sources

Authority (gov.uk + legislation.gov.uk):

- **Capital Gains Tax rates and allowances** (gov.uk; re-fetched
  2026-05-24 to verify the 30 Oct 2024 boundary):
  https://www.gov.uk/guidance/capital-gains-tax-rates-and-allowances
- **TCGA 1992 Schedule 7AC (SSE)**:
  https://www.legislation.gov.uk/ukpga/1992/12/schedule/7AC
- **TCGA 1992 s2A** (computation of CGT — the loss/AEA priority):
  https://www.legislation.gov.uk/ukpga/1992/12/section/2A
- **HMRC CG53078** (SSE 12-month requirement):
  https://www.gov.uk/hmrc-internal-manuals/capital-gains-manual/cg53078
- **HMRC CG53080C** (SSE aggregation of non-contiguous periods):
  https://www.gov.uk/hmrc-internal-manuals/capital-gains-manual/cg53080c
- **Investors' Relief lifetime-limit reduction (Autumn Budget 2024)**:
  https://www.gov.uk/government/publications/capital-gains-tax-investors-relief-lifetime-limit-reduction/capital-gains-tax-investors-relief-reduction-in-the-lifetime-limit
- **Reduction in the lifetime limit for Entrepreneurs' Relief**
  (the 11 March 2020 announcement):
  https://www.gov.uk/government/publications/reduction-in-the-lifetime-limit-for-entrepreneurs-relief-technical-note/reduction-in-the-lifetime-limit-for-entrepreneurs-relief-technical-note
- **HMRC CG14260** (rate allocation manual):
  https://www.gov.uk/hmrc-internal-manuals/capital-gains-manual/cg14260
- **HMRC CG63955** (BADR rate page — referenced in spec):
  https://www.gov.uk/hmrc-internal-manuals/capital-gains-manual/cg63955

Practitioner / professional analysis:

- **Deloitte TaxScape** — Autumn Budget 2024 CGT/BADR/IR (BADR + IR
  rate alignment):
  https://taxscape.deloitte.com/measures-autumn-budget-2024/cgt-rates-and-badr-and-investors-relief.aspx
- **BDO** — Business Asset Disposal Relief overview:
  https://www.bdo.co.uk/en-gb/insights/tax/private-client/business-asset-disposal-relief
- **Crowe UK** — Business Asset Disposal and Investors' Relief:
  https://www.crowe.com/uk/insights/investors-relief-update
- **ICAEW Taxline** — Investors' relief: stepping out of the shadows:
  https://www.icaew.com/technical/tax/tax-faculty/taxline/articles/2025/investors-relief-stepping-out-of-the-shadows
- **TaxCalc KB 3111** — Entrepreneurs Relief changes from 11 Mar 2020:
  https://kb.taxcalc.com/3111
- **ByteStart** — BADR rate trajectory 10 → 14 → 18:
  https://www.bytestart.co.uk/self-employed-tax/business-asset-disposal-relief-small-business/
- **rossmartin.co.uk** — Autumn Budget 2024 CGT:
  https://www.rossmartin.co.uk/autumn-budget-2024/8057-capital-gains-tax-cgt-autumn-budget-2024

kontor substrate / project context:

- `doc/research/114-uk-cgt-fit.md` — the research-before note that
  drove the UK provider design (the authoritative reference for v1
  scope cuts).
- `doc/research/107-phase-3-incorporation-and-disposal.md` §3 — the
  `:disposal/*` schema this provider reads.
- `doc/research/102-period-tax-provider-design.md` §7 — `:capital-
  gains-tax` enum + the hybrid placement story.
- `doc/research/120-de-cit-baseline-review.md` — the structural
  template this note follows.
- `src/kontor/period_tax_provider.clj` — the protocol the provider
  implements.
- `src/kontor/disposal_source.clj` — the kernel-side `DisposalSource`
  protocol the provider depends on.
- `modules/disposal/src/kontor/disposal/source.clj` — the canonical
  `DatahikeDisposalSource` impl the tests use.
- `src/kontor/statute.clj` lines 150-174 — the
  `parameter-value-at` half-open window evaluator (the source of the
  §2.9 quirk).

---

End of note 139.
