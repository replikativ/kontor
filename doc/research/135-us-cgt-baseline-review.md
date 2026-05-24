---
date: 2026-05-24
title: 135 — US CGT baseline review
status: review-after for ADR-103 US implementation
---

# 135 — US CGT baseline review

## §1. Headline

The US CGT implementation (`modules/l10n-us/src/kontor/l10n_us/cgt_{statute,provider}.clj` + 12-test file) faithfully wires the ADR-103 pattern — schema-as-data parameters, lane classification, dispatch on `:kind :individual | :corporation`, `DisposalSource` decoupling, NIIT-as-statute-surtax, void exclusion — and the substrate hygiene is clean. **However, the implementation has TWO ship-blocker correctness bugs** that propagate to the 10 sibling jurisdictions because they were templated on this file: (a) **four of the eight 2026 LT-bracket thresholds in `parameter-values` disagree with IRS Rev. Proc. 2025-32 §3.03 by amounts ranging from $25 to $12,900**, including a single-filer 15/20 cusp that is $400 low and a HoH 15/20 cusp that is **$12,900 low** ($566,700 vs the published $579,600); and (b) **the §1250 unrecaptured base equals the entire LT real-property gain** rather than the depreciation-taken cap mandated by §1(h)(6)(A) / Pub 544 — for the test fixture this over-states the §1250-lane base by $300,000 (and the tax by $75,000) while simultaneously under-taxing the residual $300k that should have flowed to the §1(h) brackets. Three P1 findings + a handful of P2s round it out. The substrate itself is unscathed — every bug is in the L10N layer.

## §2. P0 findings (ship-blocker — wrong rate, wrong calc)

### P0-1 — Four of the eight 2026 LT-bracket thresholds disagree with Rev. Proc. 2025-32 §3.03

`/home/christian-weilbach/Development/kontor/modules/l10n-us/src/kontor/l10n_us/cgt_statute.clj:148-175`

What's wrong (authoritative values per IRS Rev. Proc. 2025-32 §3.03, [rp-25-32.pdf](https://www.irs.gov/pub/irs-drop/rp-25-32.pdf)):

| Parameter | Provider value | Rev. Proc. 2025-32 | Δ |
|---|---|---|---|
| `LT.threshold-single-0to15` | 49,450 | 49,450 | ✓ |
| `LT.threshold-single-15to20` | **545,100** | **545,500** | $400 low |
| `LT.threshold-mfj-0to15` | 98,900 | 98,900 | ✓ |
| `LT.threshold-mfj-15to20` | **613,750** | **613,700** | $50 high |
| `LT.threshold-mfs-0to15` | 49,450 | 49,450 | ✓ |
| `LT.threshold-mfs-15to20` | **306,875** | **306,850** | $25 high |
| `LT.threshold-hoh-0to15` | 66,200 | 66,200 | ✓ |
| `LT.threshold-hoh-15to20` | **566,700** | **579,600** | **$12,900 low** |

The HoH 15/20 error is the largest by far — $12,900 of LT gain that should be taxed at 15 % gets taxed at 20 %, a $645 over-tax for every HoH taxpayer sitting in that band. The other three are small enough that no individual return will visibly mis-compute, but they violate "to the cent" parity with the authority and will throw any reconciliation test that compares the provider against an IRS-published worked example. **Tests do not catch them** because no test exercises the upper bracket cusp for any status — `individual-lt-uses-§1h-bracket-schedule` deliberately constructs an MFJ case at $100k (between 0 % ceiling and the 15/20 cusp) which uses ONLY the 0-to-15 threshold.

**Fix:** update the four values (`545100 → 545500`, `613750 → 613700`, `306875 → 306850`, `566700 → 579600`); add a 20 %-bracket assertion test per filing status to catch future drift; consider also adding the Estates and Trusts thresholds ($3,300 / $16,250 per §3.03) since fiduciary income tax returns reach the same provider in principle.

**Authority:** Rev. Proc. 2025-32 §3.03 ([IRS PDF](https://www.irs.gov/pub/irs-drop/rp-25-32.pdf)) — Section 3.03 ("Maximum Capital Gains Rate (§ 1(h), § 1(j)(5))").

### P0-2 — §1250 unrecaptured base is the WHOLE LT gain, not min(gain, depreciation taken)

`/home/christian-weilbach/Development/kontor/modules/l10n-us/src/kontor/l10n_us/cgt_provider.clj:107-161`, especially lines 132-157 of `classify` and the `:§1250-unrecaptured` lane dispatch.

What's wrong: under IRC §1(h)(6)(A) (and Pub 544 ch. 3 "Unrecaptured section 1250 gain"), the unrecaptured §1250 gain is "the part of any long-term capital gain on section 1250 property that is due to depreciation" — i.e., **MIN(LT gain, depreciation taken via straight-line)**. The portion of the gain that exceeds depreciation taken is regular §1231 LT capital gain and runs through the §1(h) 0/15/20 stack.

The provider's `classify` lane dispatch (line 148-153) routes the **entire residual gain** into `:§1250-unrecaptured` whenever `(pos? dep-taken)`. The recapture cond (line 132-142) returns `0M` for the individual real-property case (correct — there's no §1245-style ordinary recapture for real property), so `residual = g - 0 = g`, and `g` lands wholesale in the 25 % lane.

The §4 test `individual-§1250-unrecaptured-at-25pct` (cgt_provider_test.clj:167-184) makes this exact mistake AND CODIFIES IT as the expectation: gain $761,538, depreciation taken $461,538, asserts base $761,538 and tax $190,384.50. The correct answer is base = min(761,538, 461,538) = **$461,538**, tax = $115,384.50 in the §1250 lane, plus $300,000 in the §1(h) lane (LT residual → for a single filer with no other LT income that's 0 % on first 49,450, 15 % on next 250,550 = **$37,582.50**), total **$152,967**. The provider over-states this taxpayer's tax by **$37,417**.

**Fix:** in `classify`, when the asset class is `:us-real-property`, individual kind, and `pos? dep-taken`, split the residual into `(min residual dep-taken)` → `:§1250-unrecaptured` lane and `(- residual (min residual dep-taken))` → `:lt` lane. This is one tweak to the `cond` return — the lane field needs to become a vector (or `classify` needs to return a vector of lane-maps per disposal) so a single real-property disposal can contribute to BOTH the §1250 and the LT lanes simultaneously. The test then needs to assert (a) §1250 base = 461,538, tax = 115,384.50, AND (b) LT base = 300,000, tax = 37,582.50, AND (c) total liability across components = 152,967.

**Authority:** [26 USC §1(h)(6)(A)](https://www.law.cornell.edu/uscode/text/26/1#h_6) — "the excess (if any) of (i) the amount of long-term capital gain ... which would be treated as ordinary income if section 1250(b)(1) included all depreciation and the applicable percentage under section 1250(a) were 100 percent ..."; Pub 544 ("Unrecaptured section 1250 gain. Generally, this is the part of any long-term capital gain on section 1250 property (real property) **that is due to depreciation**.").

**Propagation risk:** the 10 sibling l10n CGT providers were templated on this provider. If any of them implements a similar "depreciation-cap on LT real-property gain" pattern (DE §17, JP unrecaptured-residence — the closest analogues), check whether they copied the same "whole gain to the depreciation lane" lane assignment.

## §3. P1 findings (significant correctness gap, not catastrophic)

### P1-1 — LT bracket applied to LT gain in isolation, not stacked on ordinary income

`cgt_provider.clj:270-296` (`lt-component`) and `cgt_provider.clj:206-227` (`lt-schedule`).

What's wrong: IRC §1(h)(1) says "If a taxpayer has a net capital gain for any taxable year, the tax imposed by this section for such taxable year shall not exceed the sum of (A) a tax computed at the rates and in the same manner as if this subsection had not been enacted on the greater of (i) taxable income reduced by the net capital gain; or (ii) the lesser of (I) the amount of taxable income taxed at a rate below 25 percent; or (II) taxable income reduced by the adjusted net capital gain; (B) 0 percent of so much of the adjusted net capital gain..." — i.e., the 0/15/20 brackets apply to the LT GAIN STACKED ON TOP of ordinary income. The 0 % bracket "shelters" the LT gain only to the extent ordinary income hasn't already filled it.

The provider's `lt-component` runs the LT amount through the bracket schedule in isolation. This is acknowledged in the docstring (lines 277-279: "v1 uses the simple bracket on the LT amount only — correctly under-reports tax for taxpayers near a bracket cusp"). **But the docstring describes the error in only one direction — under-reporting.** The error is bidirectional:

- A taxpayer with $200k ordinary income + $30k LT gain (single): correct answer applies 15 % to all $30k = $4,500 (the 0 % bracket is fully consumed by ordinary income); provider gives 0 % on all $30k = $0 (UNDER-tax by $4,500).
- A taxpayer with $0 ordinary income + $700k LT gain (single): correct answer gives 0 % on $49,450, 15 % on ~$496k, 20 % on residual = ~$104,300; provider gives 0 % on $49,450, 15 % on $495,650 = $74,348 + 20 % on $154,500 = $30,900 ≈ $105,248 (close but off; the provider also gets the cusp wrong — see P0-1).
- The classic cusp case (single, $40k ordinary income + $30k LT): correct answer is 0 % on $9,450 ($49,450 − $40,000) and 15 % on $20,550 = $3,082.50; provider gives 0 % on all $30k = $0 (UNDER-tax by $3,082.50).

The under-tax direction dominates for low-ordinary-income taxpayers; the over-tax direction (no, actually the provider only under-taxes here because the LT gain alone is always smaller than gain-stacked-on-ordinary in bracket terms — the bracket runs are always at least as low for LT-alone, so the provider only ever UNDER-taxes). Re-reading: the bracket math is "ordinary fills the bracket first, LT stacks on top," so the LT-alone calculation only consumes the LOWEST bracket(s); LT-alone tax ≤ LT-stacked tax for any non-zero ordinary income. So the docstring's "under-report" framing is directionally correct. But: a follow-up to fix this is one input `:ordinary-taxable-income` away — feed `ctx`-derived ordinary into `lt-component` and shift the bracket thresholds down by min(ordinary, threshold). Note 112 §4 mentioned the layering rule as deferred; given this is the reference template, **either fix in v1 or capture an explicit known-issue audit-doc that the consumer must override `:lt-bracket-shift`**.

**Authority:** [§1(h)(1)](https://www.law.cornell.edu/uscode/text/26/1#h_1).

### P1-2 — §1(h)(1)(E) 25 % §1250 rate is statutorily a CAP, not a flat rate

`cgt_provider.clj:298-317` (`§1250-component`).

What's wrong: §1(h)(1) is a "tax shall NOT EXCEED the sum of" formula. For unrecaptured §1250 gain in (E), the rate is "25 percent of the excess (if any) of (i) the unrecaptured section 1250 gain (or, if less, the net capital gain ...)" — and the umbrella "shall not exceed" wording means the gain is taxed at the LESSER of 25 % and the taxpayer's marginal ordinary rate that would apply to that slice. The Unrecaptured §1250 Gain Worksheet in the Schedule D instructions implements exactly this lesser-of. For most taxpayers in the 32 %+ bracket, 25 % IS the lesser, so the 25 % flat rate matches; for low-bracket taxpayers (10 %, 12 %, 22 %, 24 % marginal), the unrecaptured §1250 is taxed at the marginal rate, not 25 %.

The provider treats it as a flat 25 %. Combined with P1-1, this means: for low-income taxpayers selling rental property, the provider over-states the §1250 tax. The §1250 lane case in §4 has the taxpayer with $0 ordinary income — at single filing status the 22 % marginal bracket extends to ~$50k taxable income in 2026, so the first ~$50k of §1250 gain should be taxed at progressively 10 % → 22 %, not 25 %. Material for low-income real-estate disposers.

**Fix:** v1 can keep the flat 25 % and document it as a known over-tax for low-income filers, OR thread the marginal ordinary rate from `ctx` and apply `min(0.25, marginal-rate)` per slice. The simpler v1 fix is to add a docstring caveat similar to P1-1's; the right fix wires the ordinary-rate input.

**Authority:** [§1(h)(1)(E)](https://www.law.cornell.edu/uscode/text/26/1#h_1) + the Unrecaptured Section 1250 Gain Worksheet in the Schedule D (Form 1040) instructions.

### P1-3 — `§1211b.ordinary-offset-cap` is filing-status-ignorant; MFS halves it

`cgt_statute.clj:124-127` (parameter def) and `:212-215` (parameter-value 3000M).

What's wrong: IRC §1211(b) reads "$3,000 ($1,500 in the case of a married individual filing a separate return)" — the cap is $1,500 for MFS filers, not $3,000. The substrate ships a single parameter `US.CGT.§1211b.ordinary-offset-cap` valued $3,000 with no MFS variant. The v1 provider does not currently consume this parameter (it's used only as documentation), so the bug is latent — but the next iteration that wires the $3,000 (or $1,500 for MFS) cap into the carry-in residual computation will inherit the error.

**Fix:** split into `cap-default` and `cap-mfs`, or stamp the parameter unit as filing-status-keyed. The current `:days` / `:rate` / `:amount-money` unit vocabulary lacks a "per-filing-status amount" composite; the cleanest expression is two parameters `…cap-default` and `…cap-mfs` and a provider-side `case status :mfs … :else …` lookup, mirroring `lt-schedule`.

**Authority:** [26 USC §1211(b)](https://www.law.cornell.edu/uscode/text/26/1211).

### P1-4 — `net-lane` discards excess carry-in; substrate contract says report residual in `:line-items`

`cgt_provider.clj:233-239` (`net-lane`).

What's wrong: `net-lane` does `(max 0M (- lane-gain carry-in))` — when carry-in exceeds gain, the excess is silently dropped. The kernel substrate (`period_tax_provider.clj:138-141`) explicitly says "the residual is reported back in `:line-items`" precisely so the consumer can re-feed the unused carry-in next period. The provider does not surface the residual; a consumer using kontor as the source-of-truth for the carry-in history will lose it. The provider's tests (e.g. `lt-carryforward-loss-offsets-current-gain`) don't assert any `:line-items` carry-residual line — they only check the post-netting base.

Also, `net-lane` clamps to zero when the lane GENERATES a loss (`gain - carry > 0` is the only kept case). A NET capital LOSS in a lane has its own rules:
- Individuals: net-loss in any lane → first nets across lanes (LT loss + ST gain or vice versa, §1212(b)(1)); remaining net loss → up to $3k against ordinary income (§1211(b)); remaining → carryforward.
- Corporations: net loss → quarantined; 3 yr back + 5 yr forward (§1212(a)).

The current provider supports neither cross-lane netting NOR same-period loss-against-ordinary NOR carryforward-emission. For a v1 that fronts a CIT/PIT consumer, this is arguably out-of-scope (the consumer can do the residual carry tracking), but it should be **explicitly documented** as a known omission, not a silent zero.

**Fix:** thread `:capital-loss-carryforward-residual {:short … :long … :§1250 … :capital …}` into the returned facts (either `:line-items` per the substrate doc, or as `:jurisdiction-specific-codes :carryforward-out`); add a test asserting residual carry surfaces.

**Authority:** kernel substrate doc string at `src/kontor/period_tax_provider.clj:138-141`.

### P1-5 — Carry-in expected type ambiguous (BigDecimal vs Money)

`cgt_provider.clj:407` reads `(:capital-loss-carryforward inputs)`; the kernel substrate doc (`period_tax_provider.clj:138-141`) says `{:short <Money> :long <Money>}`. The provider's `net-lane` does plain `(- gain carry-in)` arithmetic, which only works for BigDecimal carry-in (or auto-coercing Money via custom multimethod — there is none). The tests pass plain BigDecimals (`{:long 30000M}`), so the provider works in practice, but it does not honor the documented `<Money>` shape.

**Fix:** either (a) coerce: `(if (money/money? c) (:amount c) c)`, or (b) update the substrate doc to read `<BigDecimal>` and document the commodity-must-match-functional invariant. Option (a) is more substrate-faithful.

## §4. P2 findings (polish — comments, naming, doc, audit completeness)

### P2-1 — `:§1250-ordinary` lane defined but never assigned (dead code)

`cgt_provider.clj:62-69` (`lanes` set includes `:§1250-ordinary`); the `classify` lane cond (`:145-157`) only ever returns `:lt | :st | :§1250-unrecaptured`. The corp §1250-ordinary case (excess accelerated over straight-line for pre-1986 real property) is mentioned in the docstring (`:120-141`) but the recapture branch for it returns `0M`. The corp `:§1250-ordinary` lane never receives data. Either (a) remove the lane from the closed set + drop the doc text, or (b) wire it (requires a `:disposal/depreciation-method` field that the schema does not currently carry — note this is a substrate gap if you go this way; for v1 cleanest is removal).

### P2-2 — Holding-period cutoff parameter is 365 days; §1222 says "more than 1 year"

`cgt_statute.clj:136-139` parameter-value 365M; `cgt_provider.clj:85-90` `(> (days-between acq dis) cutoff-days)`. The IRS treats "more than 1 year" as "1 year and 1 day," but 365 day-counts can be off by one in leap years. Day 366 across a leap-year boundary IS exactly "1 year and 1 day" by calendar; day 365 is "1 year." The strict-`>` comparison against 365 maps `day-366` → LT, which is correct in non-leap windows but slightly conservative in leap windows. The §1222 rule is calendar-based, not 365-day-based — using `java.time.Period` with month/year math would handle leap years correctly. Probably never matters in practice but worth a comment.

### P2-3 — `:regime` field misused for filing status

`cgt_provider.clj:292` (`lt-component`) sets `:regime status` where status is `:single`/`:mfj`/`:mfs`/`:hoh`. The substrate doc (`period_tax_provider.clj:95`) defines `:regime` as "which elective regime applied (note 102 §9-C)" — i.e., an ELECTION the taxpayer made (e.g. IN §115BAA flat 22 %, FR PFU vs barème). Filing status is not an election; it's a household characteristic. Move filing status to `:jurisdiction-specific-codes :filing-status` (which is already set at line 296 — so the `:regime` setting is redundant + semantically wrong).

### P2-4 — `:US.CGT.§1411.threshold-single` parameter name implies single-only but covers HoH

`cgt_statute.clj:110-113` codes the parameter as `threshold-single` with label "Single / HoH". `us-niit` at `cgt_provider.clj:182-186` correctly defaults to this parameter for `:single` and any non-mfj/mfs status (including `:hoh`). The naming is misleading — `threshold-any-other-case` (matching §1411(b)(3)'s statutory category) is more accurate and matches Cornell's text.

### P2-5 — `niit-component` not guarded against corp `:kind`

`cgt_provider.clj:338-367`. The fn unconditionally sets `:kind :individual` in the scoped-ctx for the provision query (`:344`) regardless of the outer provider's kind. The main `period-tax-facts` only invokes `niit-component` on the `:individual` branch (line 426), so this is moot — but a future refactor that wires NIIT to corps (which never owe it under §1411) would silently fire. Add a precondition or document the invariant.

### P2-6 — Five specialist regimes deferred without a tracking issue or audit-doc

Note 112 §4 enumerated five deferred specialist provisions: §1031 like-kind, §1202 QSBS, §121 principal residence, §453 installment sale, §1091 wash sale. The disposal schema carries the data slots (`:elective-regime` / `:exemption-claimed` / `:rollover-amount` / `:residence?`) but the provider silently IGNORES them. A consumer who flags `:disposal/exemption-claimed #{:§1202}` on a disposal receives full ordinary CGT computation — no warning, no audit trail. Either (a) emit a `:line-items` warning when a disposal carries an unrecognized exemption/regime/rollover, or (b) ship a small `:warnings` collection on the `TaxReturnFacts` for consumer-visible "I noticed but didn't compute." The kernel substrate doesn't currently support warnings; minimal patch is logging via the `:line-items` mechanism with a `:line :deferred-regime` entry.

### P2-7 — Test coverage gaps that would have caught P0-1 and P0-2

- No test exercises the **20 % bracket** for any filing status (would catch P0-1 cusps).
- No test exercises the **HoH 15/20 cusp** (would catch the $12,900 HoH error).
- No test asserts the **Estates and Trusts** thresholds (not currently in `parameters`).
- The §1250 test (`cgt_provider_test.clj:167-184`) codifies the buggy "whole gain in §1250 lane" expectation — fixing P0-2 requires updating the test's expected values AND adding a follow-on LT-lane assertion.
- No test asserts cross-component totals (sum of `:liability` across all components == expected total tax). A property test would catch P0-2 immediately because the §1250-only assertion would still pass but the total-tax would mismatch.

### P2-8 — Citations correct but pin to current versions

All `:provision/citation` and `:parameter/concept-iri` URLs point at `law.cornell.edu` (stable mirror of the US Code) — good. Rev. Proc. 2025-32 citations point to "IRS Rev. Proc. 2025-32 TY 2026" as text but never to a URL. Adding `https://www.irs.gov/pub/irs-drop/rp-25-32.pdf` to each citation lets the audit-doc machinery resolve the PDF when surfacing the provenance.

## §5. Verified-correct items

The following were checked against authority and are RIGHT:

1. **NIIT mechanics** (`cgt_provider.clj:173-192`): `min(NII, MAGI − threshold) × 3.8 %` exactly matches §1411(a) — "3.8 percent of the lesser of (A) net investment income for such taxable year, or (B) the excess (if any) of (i) MAGI ... over (ii) the threshold amount." Test `individual-niit-fires-above-magi-threshold` confirms with NII=200k, MAGI=500k, threshold=200k → min(200k, 300k) = 200k × 3.8 % = $7,600 — to the cent.
2. **NIIT thresholds** (`cgt_statute.clj:199-210`): $200k single/HoH, $250k MFJ, $125k MFS — all match §1411(b)(1-3) verbatim, all NOT inflation-indexed per §1411 (no indexing language in the statute) — correctly stamped `:effective-from #inst "2013-01-01"` (the ACA-2013 effective date).
3. **§1411 rate 3.8 %** matches §1411(a).
4. **§1222 cutoff "more than 1 year"** correctly uses STRICT `>` (`cgt_provider.clj:90`); cutoff value 365 days matches the common-practice approximation (caveat at P2-2).
5. **§1245 recapture clamp** `(min dep-taken g)` (`cgt_provider.clj:135`): exactly the §1245(a) "lower of recomputed basis or amount realized, minus adjusted basis" algebraic equivalent. Test `individual-§1245-recapture-ordinary` confirms with dep=60k, gain=110k → recapture=60k, LT residual=50k — correct per Pub 544 ch. 3.
6. **§1(h) bracket rates 0/15/20 % stable since ATRA 2012** — `:effective-from #inst "2013-01-01"` matches ATRA's 2013 effective date. Rate values 0M / 0.15M / 0.20M are right.
7. **§1250 cap rate 25 % effective from TRA 1997** — `#inst "1997-08-05"` matches the Taxpayer Relief Act of 1997's enactment date; 0.25M matches §1(h)(1)(E). (The application is wrong per P0-2 and P1-2, but the rate parameter itself is correct.)
8. **Corporate net cap gain folds into CIT base** (`cgt_provider.clj:373-388` + `:431-442`): corp has no preferential rate, so `:cit-base-additions` with `:gross-liability 0` is the right shape — consumer composes with the CIT provider. Test `corporate-net-folds-into-cit-base` confirms.
9. **Corp loss carryforward netted before fold** (`corporate-loss-carryforward-applies`): `$400k − $150k = $250k` matches §1211(a) "capital losses ... only to the extent of gains" + §1212(a) carry mechanics.
10. **Void exclusion via DisposalSource** (`voided-disposals-excluded`): correctly delegated to the companion's source impl (the provider does not need to filter — the source contract guarantees it).
11. **DisposalSource decoupling** (`cgt_provider.clj:51`, `cgt_statute.clj:32`): provider depends on `kontor.disposal-source`, not on the `kontor-disposal` companion — ADR-103 pattern correctly applied. Tests use `kontor.disposal.source/datahike-source` as the canonical impl.
12. **`:jurisdiction-specific-codes` keys** (`:pit-base-additions`, `:cit-base-additions`, `:lane`, `:filing-status`): consistent with the ADR-103 narrative and the sole-proprietor / VAT-return precedents. (Minor: `:pit-base-additions` value is a VECTOR `[recapture-amount]` even when there's only one entry — this matches ADR-100's sole-proprietor pattern, where the value collects multiple contributions.)
13. **Empty-source default** (`cgt_provider.clj` constructors reject nil source; provider supports `ds/empty-source`): ADR-103 §1 pattern honored.
14. **Filing-status branching** (`cgt_provider.clj:212-220`): all four (`:mfj :mfs :hoh :single`-default) covered without typo — verified each `case` arm reads the per-status threshold parameters with the matching `mfj` / `mfs` / `hoh` infix.
15. **`apply-adjustments` invocation for NIIT** (`cgt_provider.clj:351-352`): correctly wires `:running` via the late-bound fn (`us-niit` returns `(fn [_ctx-w-running] ...)`); the `:op :surtax` semantics match `apply-adjustments`'s `running + amt` branch. `composed-of [:lt :§1250-unrecaptured]` reflects the correct dependency: NIIT sits on top of the standalone CGT liabilities, not on the PIT-folded ST or ordinary recapture lanes.

## §6. Substrate-level observations

1. **`:line-items` carry-residual is doc-only** — kernel substrate `period_tax_provider.clj:138-141` says "the residual is reported back in `:line-items`" but provides no helper or shape spec. Every per-jurisdiction CGT provider has to invent its own residual representation. Consider shipping a `kontor.period-tax-provider/carryforward-residual` helper that constructs a canonical `:line {:line :carry-residual :bucket … :amount …}` entry.
2. **No first-class "warning" channel on `TaxReturnFacts`** — P2-6 surfaces this. Five deferred specialist regimes get silently zero'd. A `:warnings [<{:code :label :data}>]` field on `TaxReturnFacts` would let providers signal "I noticed this exemption flag but did not compute it" without polluting `:line-items`.
3. **Money vs BigDecimal contract is fuzzy in `:inputs`** — P1-5 surfaces this. The substrate doc names `<Money>` for carry-in but providers pass through BigDecimals. Either tighten the doc to "BigDecimal in functional commodity" OR ship a coercion helper.
4. **No "bracket stack on top of ordinary income" primitive** — P1-1 and P1-2 each independently want a way to express "this schedule's effective brackets shift by the consumer's ordinary income / marginal rate." Today the only escape hatch is per-provider arithmetic; a `:schedule-shift :by-ordinary-income` flag on `apply-schedule` (or a one-off `apply-stacked-schedule` helper that takes `(schedule lt-amount ordinary-amount)`) would let DE / JP / KR / IN — every jurisdiction whose CGT bracket stacks on a household ordinary-income base — share one primitive.
5. **No worksheet-style "lesser-of rate" combinator** — P1-2's "min(25 %, marginal-rate) per slice" is the §1(h) Unrecaptured §1250 worksheet shape. Multiple jurisdictions cap a special rate at the regular rate (DE §32d Günstigerprüfung — taxpayer elects the lower of 25 % vs marginal; FR PFU — same but explicitly elective). The current substrate has `:elect :min` at the schedule level and `compose-greater-of` at the component level, but no `:cap-by-rate` modifier on a schedule that says "apply min(this-rate, ordinary-marginal-rate)" per bracket slice.

## §7. Sources used

- [Rev. Proc. 2025-32 — IRS PDF (2026 inflation adjustments, §3.03 capital gains)](https://www.irs.gov/pub/irs-drop/rp-25-32.pdf)
- [26 USC §1(h) — Cornell LII (capital gains rate formula)](https://www.law.cornell.edu/uscode/text/26/1#h)
- [26 USC §1(h)(1) — Cornell LII (the "not exceed the sum of" formula)](https://www.law.cornell.edu/uscode/text/26/1#h_1)
- [26 USC §1(h)(6) — Cornell LII (unrecaptured §1250 gain definition)](https://www.law.cornell.edu/uscode/text/26/1#h_6)
- [26 USC §1211 — Cornell LII (capital loss limitation; $3,000 / $1,500 MFS)](https://www.law.cornell.edu/uscode/text/26/1211)
- [26 USC §1212 — Cornell LII (corp 3-back/5-forward; individual indefinite carry)](https://www.law.cornell.edu/uscode/text/26/1212)
- [26 USC §1222 — Cornell LII (holding-period: "more than 1 year")](https://www.law.cornell.edu/uscode/text/26/1222)
- [26 USC §1245 — Cornell LII (personal-property recapture: "lower of recomputed basis or amount realized")](https://www.law.cornell.edu/uscode/text/26/1245)
- [26 USC §1411 — Cornell LII (NIIT: thresholds, rate, calculation)](https://www.law.cornell.edu/uscode/text/26/1411)
- [IRS Publication 544, "Sales and Other Dispositions of Assets"](https://www.irs.gov/pub/irs-pdf/p544.pdf) — §1245/§1250 mechanics; "Generally, this is the part of any long-term capital gain on section 1250 property that is due to depreciation."
- [IRS Form 8960 (NIIT) overview](https://www.irs.gov/forms-pubs/about-form-8960)
- IRS Rev. Proc. 2025-32 §3.03 verbatim (extracted from the PDF):
  - MFJ / Surviving Spouse: $98,900 / $613,700
  - MFS: $49,450 / $306,850
  - HoH: $66,200 / $579,600
  - Single ("All Other Individuals"): $49,450 / $545,500
  - Estates and Trusts: $3,300 / $16,250

End of note 135.
