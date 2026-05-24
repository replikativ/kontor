---
date: 2026-05-24
title: 138 — AU CGT baseline review against ITAA 1997 + ATO authority
audience: maintainer
status: review-after — ADR-103 AU CGT provider (per research note 129), audit against legislation.gov.au / ato.gov.au / Big-4 practitioner guides
---

# 138 — AU CGT baseline review

ADR-103's AU branch shipped as the eighth per-jurisdiction CGT consumer of
the ADR-102 disposal substrate (after US / DE / UK / JP / CA / BR / IN /
MX / CN / FR / AT — eleven before AU). The implementation is a single
`PeriodTaxProvider` (`au-cgt-provider`) parameterised by `:kind ∈
#{:individual :trust :super-fund :company}`, backed by seven `:parameter`s
+ zero `:provision`s in `cgt_statute.clj` (the Subdivision-152 cascade is
deliberately provider-internal logic per note 129 §5), with 13 deftests /
~24 assertions in `cgt_provider_test.clj` reproducing both note 129
worked examples (§2.1 Marcus $66k → $33k and §2.2 Sarah's café $1.6M →
$0).

Per the maintainer's standing baseline-review mandate, this note audits
the encoding against the primary statute (ITAA 1997 mirrored at
legislation.gov.au + classic.austlii.edu.au), ATO published guidance
(ato.gov.au), and Big-4 / practitioner commentary (Andersen, Bristax,
Tax Talks, Wilson Pateras, MLC TechConnect, William Buck).

**Headline.** Discount rates, holder-class matrix, retirement cap value,
collectables / personal-use thresholds, 2027-07-01 sunset cutover,
exemption gates, and the Subdivision-152 cascade order are all
encoded correctly. The note 129 §2.1 ($66k → $33k) and §2.2 ($1.6M →
$0) worked examples reproduce exactly. **Two P0 findings** — both
material statute violations the encoding makes silently:

- **P0-1** — **loss-netting ordering inverts s102-5.** The provider
  nets capital losses against the POST-discount `:assessable` rather
  than the PRE-discount raw gain. ITAA 1997 s102-5 Method Statement
  Step 1-2 (current-year + prior-year losses) precedes Step 3 (the
  discount). The test at lines 352-370 explicitly acknowledges the
  bug ("future refinement") and ships it with an asserted-wrong
  result (`5000M` instead of the law-correct `10000M`). Doubles the
  understatement of capital gains for any holder with a loss against
  a discount-eligible gain.
- **P0-2** — **personal-use losses are not disregarded.** The
  provider folds a negative `realized-gain` through the
  `apply-losses` reducer (line 348-355) regardless of asset class.
  s108-20(1) **disregards** capital losses on personal-use assets
  (not just quarantining — they vanish from the loss bucket
  entirely). Combined with P0-1's ordering bug, this lets a $5k
  personal-use loss illegally offset a $10k listed-share gain.

The agent's self-flagged "retirement cap as scalar vs per-stakeholder
map" surfaces as **P1**, not P0 — the v1 `:individual` provider serves
ONE stakeholder per call, and the docstring at lines 481-485 plus
note 129 §4 Gap 6 already triage this as a future refinement. The
material gap there is for `:company` / `:trust` callers where the
$500k cap is per stakeholder receiving the distribution, not per
entity — but the `:company` discount-rate is 0 anyway and `:trust`
v1 is documented as following individual semantics. Triage **P1**
with a documentation tightening.

Foreign-resident apportionment (Div 855 / s115-115) is the second
P1 — the provider treats `:au-foreign-resident-non-tap` as a
binary full-exemption gate, which is correct for NON-TAP (the
asset's gain is wholly disregarded by s855-10), but the
`:au-foreign-asset-tap` class (TAP held by a foreign resident)
correctly attracts CGT and s115-115 calls for time-apportioned
discount denial — the provider currently grants the FULL discount
in that case. v1 deferred per note 129 §6 Q5 with `:inputs
:au-residence-period-history` hooked but unused.

Everything else triages to P2 (line-item polish, ergonomic gaps,
documentation tightenings). Sections below detail each finding with
file:line + citation.

---

## §1. Statute-fidelity audit — parameters first, then logic

### 1.1 `AU.CGT.discount-rate.individual` = 0.50M from 1999-09-21 — CORRECT

ITAA 1997 s115-100(a): for individuals and trusts the discount
percentage is 50%. Stable since the New Business Tax System (Capital
Gains Tax) Act 1999 (No. 165, 1999) which inserted Division 115
effective 1999-09-21 for events on or after that date. The encoded
`0.50M` at `cgt_statute.clj:127` with `:effective-from #inst
"1999-09-21"` matches the statute text and the Bristax / Tax Talks
historical commentary. **OK.**

### 1.2 `AU.CGT.discount-rate.super-fund` = 0.333333M from 1999-09-21 — CORRECT VALUE, ROUNDING NOTE

ITAA 1997 s115-100(b): the discount percentage for a complying
superannuation fund is 33⅓%. The encoded `0.333333M` is the closest
6dp truncation of 1/3.

The docstring at `cgt_statute.clj:131-134` explicitly explains the
6dp choice ("BigDecimal-stored as the closest 6dp; multiplication is
immediate, so we choose 6dp for explicitness in the audit trail").
This is defensible — the provider multiplies `gain × (1 − rate)` so
the residual rounding error on a $66,000 gain is
`66000 × 0.333333 = 22,000.0779... − 22,000 = $0.078`. The test at
lines 156-161 asserts `44000.022M` which is the literal
`66000 × (1 − 0.333333)` — not the law-correct `44000.00M`.

**Triage**: P2. For super-fund liability where the gain folds into
the fund's 15%/0% rate, the post-discount residual is a sub-cent
artefact. But the published ATO Worksheet 7 example for super-fund
discount-method calculations does NOT show fractional cents in the
assessable amount — the actual ATO mechanic is `gross × 2/3` with
HALF-EVEN rounding at the end. Tightening to either (a) store a
`1/3` BigDecimal with extended scale and use `divide` not `multiply`
or (b) round the post-discount amount to 2dp would close the gap.
For v1 the existing six-decimal residual is harmless and clearly
documented; defer to a P2 cleanup.

### 1.3 `AU.CGT.discount-rate.company` = 0.00M from 1999-09-21 — CORRECT

s115-100 is silent on companies — no discount applies. Encoded as a
zero parameter rather than as the absence of a parameter, which is
the right shape: the discount-eligible? predicate at
`cgt_provider.clj:208-217` rules out `:company` by holder class
before the rate lookup, so the 0 is belt-and-braces. **OK.**

### 1.4 `AU.CGT.discount-sunset-date` = 2027-07-01 — CORRECT

The 2026-27 Federal Budget announced 2026-05-13 (Tax reform package)
repeals the 50% CGT discount for individuals, trusts, and
partnerships from 1 July 2027 — confirmed by Baker McKenzie, NAB,
Corrs Chambers Westgarth, Greenwoods, Perpetual, and the published
budget.gov.au pages. The encoded sunset stored as
`(bigdec (.getTime #inst "2027-07-01"))` at `cgt_statute.clj:146`
with the parameter's own `:effective-from #inst "2026-05-13"`
(announcement date) is bitemporally correct — a query as-of
2026-05-12 sees no sunset parameter; a query as-of 2026-05-13+
sees the sunset = 2027-07-01.

**Subtle note**: the storage form is **the cutover date itself as
ms-since-epoch** in a BigDecimal cell. The provider coerces back via
`date-ms->date` at `cgt_provider.clj:111-117`. This is unusual
within the ADR-101 substrate (no other parameter so far is a date),
but the existing `:parameter-value/decimal-value` schema is
bigdec-typed so the workaround is the right shape pending an ADR-101
addendum adding a `:parameter-value/date-value` slot. **OK with a
P2 follow-up**: file a 1-line note that future date-valued
parameters should converge on a single carrier shape.

### 1.5 `AU.CGT.§152-D.retirement-cap-lifetime` = 500000M from 2007-07-01 — CORRECT

ITAA 1997 s152-305(1) lifetime limit. The MLC TechConnect guide
("Effective Use of the CGT Retirement Exemption", 2007) and the
SMSF Association historical paper confirm the $500,000 figure has
been **stable since 2007-07-01** when Tax Laws Amendment (Simplified
Super) Act 2007 raised it from $200,000 to $500,000. The
encoded value and effective-from match. **OK.**

The cap is **per CGT concession stakeholder** — see §1.7 and §3.4
below for the shape-of-the-state-input concern.

### 1.6 `AU.CGT.§118-10.collectable-threshold` = 500M, `personal-use-threshold` = 10000M — CORRECT (with caveat in §3.2)

ITAA 1997 s118-10(1) (collectables, first-element ≤ $500) and
s118-10(3) (personal-use assets, first-element ≤ $10,000). Both are
stable since CGT introduction 1985-09-20. The values and dates at
`cgt_statute.clj:156-163` match. **OK.**

### 1.7 `AU.CGT.holding-period-cutoff-days` = 365M — STRICT vs ">12 months"

s115-25 requires the asset to have been held "for at least 12
months" by the time of the CGT event for the discount-method test.
The encoded 365-day cutoff with the provider's `long-term?`
predicate at `cgt_provider.clj:98-103` using strict `>` means:

- A disposal at exactly 365 days held: NOT discount-eligible (the
  predicate returns false for `(> 365 365)`).
- A disposal at 366 days held: discount-eligible.

The s115-25(b) wording is "the entity acquired the CGT asset at
least 12 months before the CGT event," which is a 12-MONTH test, not
a 365-DAY test. The ATO's published guidance ("CGT discount")
clarifies: "You must own the asset for at least 12 months before
the CGT event to qualify for the CGT discount." A 366-day hold
(e.g. acquired 2025-01-01, disposed 2026-01-02) satisfies the
12-month test; a 365-day hold (acquired 2025-01-01, disposed
2026-01-01) does NOT (calendar interpretation: "12 months" is to
the same day next year, exclusive of the acquisition day —
2026-01-02 is the FIRST day that completes the 12 months).

The provider's `>` predicate happens to produce the right answer
for the 365/366 boundary AND the on-the-day edge (a hold from
2025-01-01 to 2026-01-01 = exactly 365 days, NOT discount-eligible
— matches the ATO 12-month-test reading). But the parameter name
"holding-period-cutoff-days" + the docstring at `cgt_statute.clj:121`
("> 12 months → discount-eligible") elides the distinction.

**Triage**: P2 (correctness OK; documentation clarification). A
future leap-year case (held over Feb 29 of a leap year) is the only
place the 365-day model and the calendar-month model diverge; the
ATO's official "12 months" wording is calendar-based, so a Feb-29-
straddling hold would be discount-eligible at 365 days (because
calendar 12 months from 2024-02-28 is 2025-02-28 which is exactly
365 days). The provider's `>` predicate would deny the discount
here. **Edge case**, but document.

### 1.8 No `:provisions` shipped — CORRECT for v1, but `:provision/concept` slot exists

The agent ships an empty `provisions` vector at `cgt_statute.clj:184`
with a docstring explaining why: the Subdivision-152 cascade is a
taxpayer-elected ORDERED sequence whose semantics don't fit the
ADR-101 closed `:op` vocabulary (`:base-add` / `:base-deduct` /
`:credit` / `:surtax` / `:schedule-override`). This is consistent
with note 129 §5's recommendation.

**Cross-check**: the sibling DE / FR / JP / CA CIT providers
similarly use `:provisions` for `:base-transform` /`:surtax` /
`:credit` mechanics and use code for cascade-style logic. The
posture is internally consistent. **OK.**

A future ADR-101 expansion (note 129 §6 footnote — the 30%
minimum-effective-rate floor for individuals / trusts from 2027-07-01)
will naturally fit `:provisions` via a `:concept :minimum-tax`
provision. The shipped `concept-iri` set in the
`starter-concept-catalogue` (`kontor.statute:609`) includes
`:minimum-tax`; the slot is ready when Treasury publishes the
indexation factor source.

---

## §2. Provider-logic audit — the operational mechanics

### 2.1 Holder-class kind set — CORRECT

`kinds = #{:individual :trust :super-fund :company}` at
`cgt_provider.clj:80-82` matches note 129 §1.2's four-class matrix.
`cit-kinds = #{:company}` at `cgt_provider.clj:84-86` correctly
identifies which kinds fold into CIT vs PIT — confirmed by ITAA 1997
s102-5 (gains enter assessable income, taxed at the holder's marginal
rate — trusts flow through to beneficiaries; super funds at 15%
accumulation; companies at 25-30%). **OK.**

### 2.2 Discount-eligibility gate — CORRECT for individuals/trusts/super, OK for companies, BUT incomplete for foreign residents

`discount-eligible?` at `cgt_provider.clj:208-217` checks:
- holder kind ≠ `:company`
- `long-term?` (held > 365 days)
- not under `:au-indexation-method` regime election

This satisfies the s115-25 (holding) + s115-100 (kind) gates AND
the mutual exclusion with the indexation method (s110-36 — the
two are alternatives, not stackable). **OK as far as it goes.**

**Gap**: the predicate does NOT check the s115-115 foreign-resident
apportionment gate. A foreign resident holding TAP and disposing
after 2012-05-08 should have their discount **time-apportioned** to
the Australian-residence days within the discount testing period
(s115-115(2) formula: `Australian-resident days / (2 × total days)`,
where the `/ 2` reflects the half-discount baseline). The provider
currently treats `:au-foreign-resident-non-tap` as a binary full
exemption (correct — s855-10), but does nothing for `:au-foreign-
asset-tap` + foreign-resident discount apportionment.

Note 129 §6 Q5 lists this as a v1 deferral with `:inputs
:au-residence-period-history` flagged as the carrier; the provider
indeed does not read this input. The agent's report flagged this as
a binary gate rather than time-apportioned discount denial — **the
agent is right**, this is the gap. Triage **P1**: it materially
overstates the discount for any foreign-resident TAP disposal, but
v1 scope per note 129 explicitly defers it. Promote to P0 if a
consumer arrives wanting foreign-resident CGT.

### 2.3 Subdivision 152 cascade — CASCADE ORDER CORRECT, MECHANICS CORRECT

`apply-cascade` at `cgt_provider.clj:234-270` implements:

1. 50% active-asset reduction if `:au-§152-50-active-reduction`
   elected → `gain × 0.5`.
2. Retirement exemption capped at `min(after-152c, cap-remaining-in)`
   if `:au-§152-retirement-exemption` elected.

The 15-yr exemption (Subdiv 152-B) is handled as an exemption gate
in `compute-disposal` at `cgt_provider.clj:339-346` BEFORE entering
the discount/cascade path — correct, the 15-yr is mutually exclusive
with the rest of the cascade per s152-115.

The Subdiv 152-E rollover is documented in the docstring at
`cgt_provider.clj:51-52` as handled by the substrate via
`:disposal/rollover-amount` (which the kernel's `realized-gain`
already subtracts), so the cascade does not re-enter it. This is
correct — the rollover deferral happens at the proceeds-basis-
rollover level, not as a cascade step.

**Cascade order matches s102-5 Method Statement Step 4 + ATO's
"Small business 50% active asset reduction" guidance**: discount →
50% active-asset reduction → retirement exemption → rollover.
Reproduced in note 129 §2.2 and the test at lines 167-191. The
`$1.6M → 50% disc → $800k → 50% §152-C → $400k → $400k §152-D →
$0` chain reproduces exactly. **OK.**

**Sub-concern**: the s152-205 50% active-asset reduction is
**AUTOMATIC** if basic conditions are met (s152-205(1) per the
ATO published guide: "applies automatically … unless you choose
for it not to apply"). The provider only fires it on EXPLICIT
election via `:au-§152-50-active-reduction`. This is the wrong
default: a consumer that simply elects retirement exemption
without thinking about the 50% reduction gets the WRONG calculation
(the consumer loses the multiplicative interaction).

**Triage**: **P1** — it produces the wrong number for a half-aware
consumer. A consumer who carefully reads the docstrings and
electively sets `#{:au-§152-50-active-reduction :au-§152-retirement-
exemption}` (as the test does at line 177-178) gets the right
answer; a consumer who only sets `#{:au-§152-retirement-exemption}`
gets a $400k retirement exemption against the post-discount $800k,
not the post-152-C $400k. The bug is "AUTOMATIC means automatic" —
the provider should treat 152-C as a SBE-gateway-satisfying default
unless the consumer explicitly elects out. A fix candidate: an
opt-out element `:au-§152-50-active-reduction-elected-out` instead
of an opt-in. (Per s152-210, the election out is a positive
election that the holder MUST make; absent the election the
reduction applies.)

### 2.4 Loss netting — P0-1 LAW ORDER INVERTED

This is the headline P0.

`apply-losses` at `cgt_provider.clj:393-408` reduces by summing
`computed`'s `:assessable` field — i.e. the POST-discount,
POST-cascade amount — then subtracting `carry-in`:

```clojure
(let [sum (reduce + 0M (map :assessable computed))
      after-carry (- sum (or carry-in 0M))]
  ...)
```

ITAA 1997 s102-5 Method Statement (confirmed by classic.austlii.edu.au
and the ATO "Using capital losses to reduce capital gains" guide):

> **Step 1.** Reduce the capital gains made during the income year
>  by the capital losses for the income year (in any order you
>  choose).
> **Step 2.** Apply any previously unapplied net capital losses
>  from earlier income years to reduce the amounts (if any) of
>  capital gains remaining after the reduction under step 1.
> **Step 3.** Reduce by the discount percentage each amount of a
>  discount capital gain remaining after step 2 (if any).
> **Step 4.** If any of your capital gains qualify for any of the
>  small business concessions in Subdivisions 152-C, 152-D and
>  152-E, apply those concessions to each capital gain as provided
>  for in those Subdivisions.

The ATO's published "Using capital losses to reduce capital gains"
guide is explicit: "If you have any capital losses from other
assets, you must subtract these from your capital gains **before**
applying the discount." Authority is unambiguous.

The provider's ordering is **post-discount, post-cascade losses**,
which is taxpayer-UNFAVOURABLE: a $30k discount-eligible gain
against a $10k loss becomes:

- Law-correct (Step 1-3): `(30000 − 10000) × 0.5 = 10000` assessable.
- Provider: `(30000 × 0.5) − 10000 = 5000` assessable.

The provider understates by 100% — a 2:1 understatement of the
assessable gain. The test at `cgt_provider_test.clj:352-370`
explicitly acknowledges the bug:

> ;; (AU mechanics: losses offset BEFORE discount per s102-5; here for
> ;;  simplicity the provider nets against the post-cascade `:assessable`.
> ;;  The numerical difference is significant for losses against the
> ;;  same disposal — future refinement; this asserts the carry-in
> ;;  channel works.)
> (is (== 5000M (-> cmp :base :amount)))

This is a shipped test asserting a result the test text says is
wrong. That's NOT okay for an "all-stages-green" ship signal —
note 129's worked-example checks pass because §2.1 and §2.2 don't
involve losses, but the loss-applied case (the third worked-example
class) is failing silently as a test that asserts the wrong number.

**Triage: P0.** The fix is mechanical: in `compute-disposal` defer
the discount/cascade to AFTER `apply-losses` runs over `:raw-gain`,
not `:assessable`. The structure change is:

1. `compute-disposal` returns `:raw-gain` only (no discount, no
   cascade) for non-exempt disposals; loss disposals stay as
   `:assessable = raw-gain`.
2. `apply-losses` nets by raw-gain. Result: net-pre-discount.
3. A new `apply-discount-and-cascade` step applies the discount +
   152-C + 152-D to the netted gain.

The retirement-cap state still threads through step 3, so the
cap-tracking test (`retirement-cap-tracks-prior-consumption`) keeps
working. The §2.2 worked example reproduces (it has no losses). The
$66k-LT-$10k-loss test asserts `10000M` not `5000M` after the fix.

### 2.5 Personal-use loss handling — P0-2 LOSS NOT DISREGARDED

`compute-disposal` at `cgt_provider.clj:347-355` treats any negative
`:raw-gain` (regardless of asset class) as a loss that flows into
the net stream:

```clojure
(neg? raw)
;; Loss — no discount, no cascade. Flow into net stream.
{:disposal disposal :raw-gain raw :gain-after-disc raw :assessable raw
 :exempt-reason nil ...}
```

ITAA 1997 s108-20(1) is unequivocal:

> "If you make a capital loss from a personal use asset, you must
>  disregard the loss."

The ATO "List of CGT assets and exemptions" guide repeats: "You
must disregard capital losses from personal use assets." This is
NOT quarantining (collectables — losses go into a per-class
sub-bucket); this is full **disregard** — the loss vanishes.

The provider currently lets a $10k loss on a sold boat
(`:asset-class :au-personal-use` with basis > $10k so not
threshold-exempt) net against a $20k gain on listed shares,
producing $10k assessable. The law-correct answer is $20k
assessable (the personal-use loss is disregarded).

Compounded with P0-1's ordering bug, the law-correct result for the
combination "personal-use loss + LT share gain" is BOTH (a) discount
applied to gross gain unmodified by the loss, AND (b) loss
disregarded entirely. The provider currently fails both.

**Triage: P0.** Fix: in `compute-disposal`, when `(neg? raw)` AND
`(= asset-class :au-personal-use)`, return a disregarded-loss
shape that does not flow into `apply-losses`. The test
`personal-use-under-10k-exempt` at lines 376-388 covers the
threshold-exemption path correctly but does NOT cover the
above-threshold loss case — add a deftest asserting that an
above-threshold personal-use loss is disregarded.

Collectables losses behave differently (s108-10): they DO offset,
but ONLY against collectables gains. The provider's single-bucket
loss netting also doesn't honour this constraint — see §3.3 below
as a separate finding.

### 2.6 Exemption-gate ordering — CORRECT

`compute-disposal` runs gates in order at `cgt_provider.clj:310-355`:

1. `indexation?` → raises (v1 unimplemented)
2. `below-thresh` (s118-10 collectables / personal-use thresholds)
3. `main-res` (Subdiv 118-B)
4. `non-tap` (Div 855)
5. `§152-15?` (Subdiv 152-B 15-year exemption)
6. `(neg? raw)` (loss)
7. default — discount + cascade

The order is correct under ITAA 1997 — thresholds, main residence,
and Div 855 all produce full disregard before any computation runs.
The 15-yr exemption is also a full disregard (s152-105). **OK.**

### 2.7 Main-residence AND-gate — CORRECT

`main-residence-exempt?` at `cgt_provider.clj:162-170` requires
BOTH `:disposal/residence? true` AND `:au-main-residence` in
`:exemption-claimed`. The test `main-residence-claim-without-
residence-flag-not-exempt` at lines 217-231 covers the gate.
**OK.**

Note: a more aggressive provider could derive `:au-main-residence`
exemption-claim from `:residence? true` plus
`asset-class :au-property-main-residence`; the explicit
"both-needed" gate is a defensible safety measure (a non-residence
property bears a `:residence? true` flag in error, but absent the
exemption-claim it still gets taxed). **OK as-is.**

### 2.8 Foreign-resident non-TAP — CORRECT FOR THE NON-TAP CASE

`foreign-non-tap-exempt?` at `cgt_provider.clj:172-177` is a single-
check exemption: if the disposal claims `:au-foreign-resident-non-
tap`, the gain is disregarded. ITAA 1997 s855-10 supports this — a
foreign resident disregards capital gains from non-TAP assets.

The agent's report flagged this as a binary gate rather than
time-apportioned discount denial. **Distinction matters**: the
binary gate applies to **non-TAP** disposals (correctly disregards
them entirely); time-apportionment (s115-115) applies to **TAP**
disposals (gain is assessable but the discount is reduced for the
foreign-residence period). The provider currently handles non-TAP
correctly but does NOT implement TAP apportionment. See §2.2.

### 2.9 15-yr exemption — TRUSTS CONSUMER

`§152-15y-claimed?` at `cgt_provider.clj:179-184` trusts the consumer
to have checked age 55+, continuous 15-year hold, and retirement
connection (note 129 §1.7). This is the consistent pattern with
BADR / IR / SSE elsewhere in the cgt providers, and is the right
v1 posture — the eligibility tests involve facts (age, hold
continuity, retirement intent) outside the GL's reach. **OK.**

### 2.10 Indexation method — RAISES CORRECTLY

`compute-disposal` at `cgt_provider.clj:310-315` raises
`:not-yet-implemented` if `:au-indexation-method` is elected, with a
pointer to note 129 §6 Q6. This is the correct conservative posture
— an indexation election would silently bypass the discount and
produce a wrong number; raising surfaces the gap. Test
`indexation-election-raises` at lines 266-277 covers it. **OK.**

### 2.11 Cap-remaining threading — CORRECT FOR SCALAR-INPUT CASE

The cap-tracking loop at `cgt_provider.clj:497-504` correctly
threads `cap-remaining` through the `reduce` over `disposals`. Each
disposal sees the cap as consumed by all earlier disposals in the
period. This is the right shape for a within-period multi-disposal
case (e.g. two disposals on the same return both electing the
retirement exemption — the second disposal sees the cap residual
after the first consumed its slice).

The `:au-retirement-cap-used` input scalar is correctly read at
`cgt_provider.clj:486` as the LIFETIME-TO-PERIOD-START used
amount. The `init-cap` at line 496 = `max(0, $500k − used)` is the
correct headroom-going-into-the-period. **OK for the per-entity-as-
stakeholder case.**

The agent's flagged limitation is real but mis-scoped: see §3.4
below.

---

## §3. Cross-substrate / convention findings

### 3.1 Loss-bucket key naming — TWO ACCEPTED FORMS, INCONSISTENT WITH KERNEL DEFAULT

`cgt_provider.clj:487-490` reads:

```clojure
carry-in    (or (:capital-loss-carryforward inputs)
                (:au-capital-loss-carryforward inputs)
                {})
```

The kernel `PeriodTaxProvider`'s documented `:inputs` shape
(`period_tax_provider.clj:138-141`) uses
`:capital-loss-carryforward` as the canonical key. Sibling
providers:

- UK individual: reads `:capital-loss-carryforward {:uk-capital ...}`
  at `l10n_uk/cgt_provider.clj:233`.
- US: reads `:capital-loss-carryforward {:short / :long ...}` per
  note 112 §3.

The AU provider accepts BOTH `:au-capital-loss-carryforward` and the
canonical `:capital-loss-carryforward`. The test at lines 360-364
uses `:au-capital-loss-carryforward` exclusively. **The dual
acceptance is a future maintenance hazard** — a consumer reading the
provider docstring at line 487-488 sees both keys and won't know
which is canonical.

**Triage**: **P1**. The kernel convention is `:capital-loss-
carryforward`. The `:au-`-prefixed alias should be dropped (or kept
only with a deprecation warning). The test should be updated to use
the canonical key.

### 3.2 Personal-use threshold measures BASIS not PROCEEDS — CORRECT

`below-threshold-exempt?` at `cgt_provider.clj:147-160` checks the
**basis** (first-element acquisition cost) against the threshold,
not the proceeds. This is correct per s108-10(1) and s108-20(2): the
threshold tests the "first element" of the cost base (i.e. the
acquisition cost), not the proceeds. The docstring at
`cgt_provider.clj:151` notes this explicitly with the s108-10
citation. **OK.**

ATO published example: a personal-use boat bought for $8,000 and
sold for $25,000 — gain disregarded because basis ≤ $10,000.
Reproduced by the `personal-use-under-10k-exempt` test at
`cgt_provider_test.clj:376-388`. **OK.**

### 3.3 Collectables loss quarantining NOT enforced — P1

s108-10(1): "If you make a capital loss from a collectable, **you
can only use it to reduce capital gains from collectables**."
Quarantine is sub-bucket within the capital-loss bucket.

The provider's single `apply-losses` reducer at `cgt_provider.clj:
393-408` does NOT enforce this — a collectable loss (a sold antique
sold for less than its basis where basis > $500) flows through the
generic loss bucket and offsets any gain, including listed-shares.

Note 129 §1.6 / §4 Gap 5 already flagged this: "extend with
`:au-collectable-loss` for the collectables compartment." The
`:disposal/loss-bucket` kernel attr exists and can carry the
sub-bucket. The provider does not consume it.

**Triage**: **P1**. The error vector is narrow (consumer with
collectables AND a gain in another class AND a collectable-class
loss) — niche, but a real statute violation. Fix: split
`apply-losses` to two passes — `:au-collectable` losses against
collectable gains only, then `:au-capital` losses against everything
else. The kernel disposal already has the `:loss-bucket` field, so
no schema change.

### 3.4 Retirement cap as scalar vs per-stakeholder map — P1 (mis-scoped from the agent's report)

The agent's self-report flagged this as a single scalar vs
per-stakeholder map shape. Let me triage carefully:

- **Individual holder** — the holder IS the stakeholder; one cap
  per entity. The current scalar `:au-retirement-cap-used` is
  correct for this case. The `:individual` provider is the v1 happy
  path.
- **Trust holder** — same; the trust as a stakeholder consumes its
  own cap. (Trust beneficiaries who later receive the distribution
  may consume their own caps separately on the distribution event,
  but that's a different CGT event — not this disposal.)
- **Company holder** — companies have NO discount AND companies'
  retirement exemption flows to the CGT concession stakeholders
  receiving the payment (s152-325 + s152-330). Each of those
  stakeholders consumes their own $500k cap. A single scalar per
  entity is **wrong** for the company case — the consumer would
  need to track per-stakeholder caps for the company's stakeholders.

But: the `:company` `discount-rate` is 0 AND
`§152-retirement-elected?` doesn't gate by holder kind. A
`:company` holder electing the retirement exemption WOULD invoke
the cap-consumption path with a scalar cap. The current model
implicitly treats the company AS the stakeholder, which is wrong
under s152-325.

The agent's report flagged this. **Agree** with the agent's
identification — the gap is real for the `:company` case (and for
trusts distributing to multiple stakeholders). **Disagree** that
the v1 ships an incorrect default — for `:individual` it is
correct, and the v1 scope per note 129 was the individual-holder
happy path.

**Triage**: **P1**. Document the gap explicitly in the
constructor docstring at `cgt_provider.clj:521-541` — "v1 the
retirement cap is treated as per-entity, which is correct for
`:individual` and `:trust` holders that ARE the sole CGT concession
stakeholder; `:company` and multi-stakeholder `:trust` callers
need consumer-supplied per-stakeholder logic before the provider
sees the input." Replace the `:inputs :au-retirement-cap-used`
scalar with a map shape
`{:au-retirement-cap-used-by-stakeholder {<sid> <amount>} ...}` in
a future v2.

### 3.5 Test asserts unintended "post-cascade not pre-discount" semantics — P0 (covered in §2.4)

Already detailed above. Re-listed here for triage tracking.

### 3.6 1-July-2027 reform — sunset gate works; the indexation+30% floor is a TODO — OK

The sunset gating works correctly (test
`discount-sunsets-post-2027-for-individuals` at lines 283-311
confirms — individuals lose the discount, super funds keep their
1/3 share). The indexation + 30% minimum-effective-rate stub is
documented as a TODO at `cgt_statute.clj:38-43` + `cgt_provider.clj:
193`. Note 129 §6 Q6 says wait for Treasury's indexation factor
source. **OK.** The provider produces a CONSERVATIVE estimate
post-sunset (full gain, no discount), which over-states the tax —
the right direction for a stub.

### 3.7 `:as-of` resolution from ctx — CORRECT BUT IDIOSYNCRATIC

`as-of-from-ctx` at `cgt_provider.clj:105-109` falls back from
`:as-of` → `:period :to` → `now`. The `:period :to` fallback is
the **end of the period** which corresponds to the latest
in-period disposal. This is correct for parameter-value lookup
(the parameter value in effect AT period end is the effective rate),
but inconsistent with some sibling providers (e.g. CA CIT uses
`:period :from`).

**Triage**: **P2**. The CA difference doesn't materially impact
AU's encoded parameters (sunsets and rates are within periods, not
straddling). Document the choice.

### 3.8 Voided disposals — EXCLUDED CORRECTLY VIA DISPOSAL-SOURCE

The companion `DatahikeDisposalSource` at
`modules/disposal/src/kontor/disposal/source.clj:73-86` excludes
`:state :voided` rows. Test `voided-disposals-excluded` at
`cgt_provider_test.clj:317-328` confirms. **OK.**

### 3.9 Component shape — `:base` is `:net` (assessable), `:liability` is zero — CORRECT

`one-component` at `cgt_provider.clj:414-456` returns a component
with `:base (money net commodity)` and `:liability (zero
commodity)`. This is the right shape per note 129 §5: AU CGT does
NOT own the rate; the gain folds into PIT (via
`:pit-base-additions`) or CIT (via `:cit-base-additions`). The
component is a "feeder" not a self-contained tax liability.
**OK.**

The `:jurisdiction-specific-codes` map carries the per-line
`:lane :au-net-capital-gain` and `:holder-kind` tags so the
downstream PIT/CIT provider can route appropriately. **OK.**

---

## §4. Worked examples — reproduction status

### 4.1 Note 129 §2.1 — Marcus LT shares $66k → $33k

Test `individual-listed-shares-50pct-discount` at lines 100-118
reproduces exactly. `:base :amount = 33000M`, `:pit-base-additions
= [33000M]`. Hand-check: `(198000 - 132000) × (1 - 0.50) = 66000 ×
0.5 = 33000`. **Reproduces to the cent.**

### 4.2 Note 129 §2.2 — Sarah's café $1.6M → $0 via cascade

Test `subdiv-152-cascade-zero-assessable` at lines 167-191 reproduces
exactly. `(2000000 - 400000) × 0.5 × 0.5 − 400000 = 1600000 ×
0.25 − 400000 = 400000 − 400000 = 0`. Cap-remaining-after = $100k
($500k − $400k). **Reproduces to the cent.**

### 4.3 Test cases that materially deviate from law

- `loss-carryforward-offsets` at lines 352-370 — asserts `5000M`
  where the law-correct answer is `10000M`. **Test ships the
  wrong answer with an explanatory comment.** P0 fix needed.
- An above-threshold personal-use loss (no test exists) — would
  produce a non-disregarded loss that nets against gains. P0 fix
  needed.
- A collectable loss against a non-collectable gain (no test
  exists) — would produce a quarantine-violating offset. P1 fix
  needed.
- A `:company` holder electing the retirement exemption (no test
  exists) — would consume the per-entity scalar cap as if the
  company were a sole CGT concession stakeholder. P1 fix needed
  (per s152-325).
- A foreign-resident TAP disposal with `:au-foreign-asset-tap`
  asset-class and a year of foreign residency (no test exists) —
  would attract full discount instead of time-apportioned discount.
  P1 fix needed (per s115-115).

---

## §5. Triage summary

### P0 (ship-blockers)

1. **Loss-netting order** — `apply-losses` nets against the
   post-discount `:assessable` rather than the pre-discount raw
   gain. Violates ITAA 1997 s102-5 Method Statement Step 1-3 order
   and ATO published guidance. Test
   `loss-carryforward-offsets` ships the wrong answer with an
   explanatory comment. **Fix**: restructure `compute-disposal` so
   discount + cascade apply AFTER `apply-losses` runs over raw
   gains. Files: `cgt_provider.clj:393-408` and the order in
   `compute-disposal:296-387`.

2. **Personal-use losses not disregarded** — s108-20(1) requires
   personal-use losses to be DISREGARDED (not quarantined). Current
   `compute-disposal:347-355` lets them flow into the loss bucket.
   **Fix**: when `(neg? raw)` AND `(= asset-class :au-personal-use)`,
   return a `:assessable 0M` shape (loss disregarded) rather than
   a `:assessable raw` shape. Files: `cgt_provider.clj:347-355`.

### P1 (fix before v1.1)

3. **152-C 50% active-asset reduction is automatic, not opt-in** —
   per s152-205 the reduction applies AUTOMATICALLY if SBE
   conditions are met; the provider currently requires explicit
   election. A consumer who elects only retirement exemption misses
   the multiplicative interaction. **Fix**: invert the gating —
   apply 152-C unless `:au-§152-50-active-reduction-elected-out`
   appears in `:elective-regime`. Files: `cgt_provider.clj:223-227`,
   `apply-cascade:234-270`.

4. **Foreign-resident TAP discount apportionment not implemented** —
   provider grants full discount for TAP disposals by a foreign
   resident; s115-115 requires time-apportionment by
   Australian-residence days. Per note 129 §6 Q5 v1 deferral;
   promote when a foreign-resident consumer arrives. **Fix**:
   implement s115-115 formula consuming `:inputs
   :au-residence-period-history`. Files: `cgt_provider.clj:190-217`
   (resolve the apportionment alongside `discount-rate`).

5. **Collectables loss quarantining not enforced** — s108-10(1)
   restricts collectable losses to offset collectable gains.
   Provider folds them into the generic capital-loss bucket. **Fix**:
   split `apply-losses` into two passes — collectables-only first,
   then everything-else. Files: `cgt_provider.clj:393-408`.

6. **Loss-bucket key naming inconsistent with kernel** — provider
   accepts both `:capital-loss-carryforward` and
   `:au-capital-loss-carryforward`; canonical is the former. **Fix**:
   drop the `:au-`-prefixed alias; update test. Files:
   `cgt_provider.clj:487-490`,
   `cgt_provider_test.clj:362`.

7. **Retirement-cap scalar wrong for company / multi-stakeholder
   trust holders** — per s152-325, when a company or trust claims
   the exemption the cap is consumed by the receiving CGT concession
   stakeholders (per-person), not by the entity. v1 happy path
   (individual or single-stakeholder trust) is correct. **Fix**: a
   future v2 should accept a per-stakeholder map; current
   `:inputs :au-retirement-cap-used` is per-entity scalar. Mark
   limitation in constructor docstring. Files:
   `cgt_provider.clj:481-486` (docstring), `:521-541`
   (constructor docstring).

### P2 (polish / follow-up)

8. **`1/3` rounding for super-fund discount** — store-and-multiply
   at 6dp produces sub-cent residue (`44000.022M` instead of
   `44000.00M`). ATO publishes a 2dp final figure. **Fix**: either
   round post-discount to 2dp HALF-EVEN or store rate as a divisor
   not a multiplier and use `BigDecimal.divide(3M)` with extended
   scale. Files: `cgt_statute.clj:131-134`, `cgt_provider.clj:361`.

9. **`AU.CGT.discount-sunset-date` stored as ms-since-epoch in
   bigdec** — works but is idiosyncratic. Filed as ADR-101
   follow-up: extend parameter-value carrier to support
   `:date-value`. Files: `cgt_statute.clj:142-147`,
   `cgt_provider.clj:111-117`.

10. **Holding-period parameter naming** — "365 days" vs the
    statute's "12 months". Document the calendar-month vs
    day-count discrepancy at the leap-year edge. Files:
    `cgt_statute.clj:121-122` docstring.

11. **`:as-of` resolution to `:period :to`** — defensible, but
    inconsistent with some sibling providers. Document the choice
    so consumers don't trip. Files: `cgt_provider.clj:105-109`.

12. **No test for above-threshold personal-use loss** — add a
    deftest asserting an above-$10k personal-use loss is
    disregarded, even after P0-2 fix lands.

13. **No test for collectables loss quarantining** — add a deftest
    asserting a `:au-collectable-loss`-bucket loss does NOT
    offset a `:au-capital-loss`-bucket gain.

14. **No test for foreign-resident TAP apportionment** — add a
    deftest after P1-4 lands.

15. **No test for `:company` retirement exemption** — even with
    the P1-7 limitation in place, a test exercising the company
    path would confirm the per-entity-scalar behaviour is what
    ships.

---

## §6. Bottom-line ship signal

**Substrate fits AU** (the schema-side audit per note 129 §3
holds — kernel `:disposal` covers core, companion adds
asset-class + elective-regime + exemption-claimed enum values
without kernel changes). **Statute-data encoding is faithful**
(parameters: discount rates, sunset date, retirement cap,
thresholds — all match legislation.gov.au and ATO commentary).
**The two worked examples in note 129 reproduce to the cent**
(Marcus §2.1, Sarah §2.2).

**Provider logic has two material law violations** (P0-1 loss
order, P0-2 personal-use loss disregard) and one important
**default-direction inversion** (P1-3 152-C automatic vs opt-in).
The P0s are mechanical fixes — restructure `compute-disposal`
to defer discount + cascade until after `apply-losses` runs,
and special-case personal-use losses to a zero `:assessable`.

**Recommendation**: hold the v1 ship signal until the two P0s land
with passing tests. The P1s can ride into v1.1 with the foreign-
resident apportionment promotion gated on consumer demand. P2s
are pure polish.

---

## §7. Sources

### Primary statute (ITAA 1997)

- **Division 100** — Guide to capital gains tax (s100-50 working
  out net capital gain or loss).
- **Division 102, s102-5** — Net capital gain enters assessable
  income; the Method Statement (Steps 1-4) defining the order:
  current-year losses → prior-year losses → discount → 152-C/D/E
  concessions.
- **Division 102, s102-10** — Net capital loss working out
  (mirrors s102-5 for losses).
- **Division 102, s102-15** — Application of prior-year net capital
  losses.
- **Division 104, s104-5** — Summary of the 52 CGT events.
- **Division 108, s108-10** — Collectables; loss quarantining
  ("can only use it to reduce capital gains from collectables");
  $500 first-element threshold; set-of-collectables exemption rule.
- **Division 108, s108-20** — Personal-use assets; **capital
  losses disregarded**; $10,000 first-element threshold; set-of-
  personal-use exemption rule.
- **Division 110, s110-25** — Cost base 5 elements.
- **Division 110, s110-36** — Indexation method.
- **Division 110, s110-55** — Reduced cost base.
- **Division 115, s115-25** — Holding-period test ("at least 12
  months").
- **Division 115, s115-100** — Discount percentages (50%
  individual / trust; 33⅓% complying super fund; 0% company).
- **Division 115, s115-105** — Foreign or temporary residents,
  individuals with direct gains.
- **Division 115, s115-115** — Foreign or temporary residents
  apportionment formula; the time-apportionment ratio (Australian-
  resident days / (2 × total days)) and the market-value alternative
  for assets held on 8 May 2012.
- **Division 118, s118-10** — Collectables and personal-use assets
  thresholds.
- **Division 118, s118-20** — Anti-overlap rule (CGT supersedes only
  when amount NOT included in assessable / exempt income).
- **Division 118, s118-24** — Depreciating assets — CGT suppressed.
- **Division 118, s118-110** — Main residence exemption.
- **Division 118, s118-120** — 2-hectare land limit.
- **Division 118, s118-145** — 6-year absence rule.
- **Subdivision 152-A, s152-10 / s152-15 / s152-35 / s152-40 /
  s152-60** — Basic conditions, $6M MNAV test, active asset test,
  active asset definition, CGT concession stakeholder.
- **Subdivision 152-B, s152-105 / s152-115** — 15-year exemption;
  mutual exclusion with the rest of the cascade.
- **Subdivision 152-C, s152-205 / s152-210** — 50% active asset
  reduction; **applies automatically unless elected out**.
- **Subdivision 152-D, s152-305 / s152-325 / s152-330** — $500,000
  lifetime cap per CGT concession stakeholder; company / trust
  distribution mechanics.
- **Subdivision 152-E, s152-410** — Small business rollover.
- **Division 855, s855-10 / s855-15** — Foreign residents disregard
  non-TAP gains; TAP definition.

### ATO published guidance

- [CGT discount](https://www.ato.gov.au/individuals-and-families/investments-and-assets/capital-gains-tax/cgt-discount)
- [Using capital losses to reduce capital gains](https://www.ato.gov.au/individuals-and-families/investments-and-assets/capital-gains-tax/calculating-your-cgt/using-capital-losses-to-reduce-capital-gains)
  — explicit: "subtract these from your capital gains BEFORE applying the discount."
- [List of CGT assets and exemptions](https://www.ato.gov.au/individuals-and-families/investments-and-assets/capital-gains-tax/list-of-cgt-assets-and-exemptions)
  — confirms $500 / $10,000 thresholds and the **disregard** of personal-use losses.
- [Small business 50% active asset reduction](https://www.ato.gov.au/forms-and-instructions/advanced-guide-to-cgt-concessions-for-small-business-2009/small-business-50-active-asset-reduction)
  — confirms automatic application, cascade order.
- [Small business retirement exemption](https://www.ato.gov.au/businesses-and-organisations/income-deductions-and-concessions/incentives-and-concessions/small-business-cgt-concessions/small-business-retirement-exemption)
  — confirms $500k lifetime cap per stakeholder, company/trust mechanics.
- [CGT discount for foreign residents](https://www.ato.gov.au/individuals-and-families/investments-and-assets/capital-gains-tax/foreign-residents-and-capital-gains-tax/cgt-discount-for-foreign-residents)
- [Guide to capital gains tax 2025: About capital gains tax](https://www.ato.gov.au/law/view/print?DocID=SAV%2FGCGT%2F00004)

### 2026-27 budget reform sources

- [Tax reform | Budget 2026-27](https://budget.gov.au/content/04-tax-reform.htm)
- [Boosting home ownership — Reforming negative gearing and CGT (ATO)](https://www.ato.gov.au/about-ato/new-legislation/in-detail/individuals/tax-reform-boosting-home-ownership-reforming-negative-gearing-and-capital-gains-tax)
  — confirms 1 July 2027 sunset, indexation + 30% minimum tax, transitional rules.
- [Baker McKenzie — Australia: Budget Bites — CGT Discount and Negative Gearing](https://www.bakermckenzie.com/en/insight/publications/2026/05/australia-budget-bites-cgt-discount-and-negative-gearing)
- [Australia CGT Changes 2026 (Hudson Financial Planning)](https://hudsonfinancialplanning.com.au/resources/education-reports/australia-cgt-changes-2026/)
- [The new rules of the game (Gilbert + Tobin)](https://www.gtlaw.com.au/insights/the-new-rules-of-the-game-what-the-202627-federal-budget-means-for-private-capital-in-australia)

### Big-4 / practitioner commentary

- [MLC TechConnect — Guide to CGT small business concessions](https://www.mlc.com.au/content/dam/mlcsecure/adviser/technical/pdf/guide_to_sbcgt.pdf)
- [SMSF Association — In-specie contributions and the retirement exemption](https://www.smsfassociation.com/wp-content/uploads/2016/08/120828_technically_speaking_august2012.pdf)
  — confirms $500k cap stable since 1 July 2007.
- [Andersen Australia — Tax Discount Guide for Temporary & Foreign Residents](https://au.andersen.com/reference-guide-part-3-capital-gains-tax-discount-for-temporary-foreign-residents/)
  — confirms s115-115 time-apportionment formula.
- [Bristax — CGT Events](https://bristax.com.au/cgt-articles/cgt-events/)
- [Tax Talks — The 4 Small Business CGT Concessions](https://www.taxtalks.com.au/articles/the-4-small-business-cgt-concessions/)
- [Wilson Pateras — The CGT Retirement Exemption Concession](https://www.wilsonpateras.com.au/blog/the-cgt-retirement-exemption-concession/)
- [Treasury — Removing Capital Gains Tax Discount for Foreign Individuals (EM)](https://treasury.gov.au/sites/default/files/2019-03/Explanatory_Materials-2.pdf)

### kontor substrate cited

- `modules/l10n-au/src/kontor/l10n_au/cgt_statute.clj`
- `modules/l10n-au/src/kontor/l10n_au/cgt_provider.clj`
- `modules/l10n-au/test/kontor/l10n_au/cgt_provider_test.clj`
- `modules/disposal/src/kontor/disposal/schema.clj` — the kernel
  `:disposal` shape AU consumes.
- `modules/disposal/src/kontor/disposal.clj` — `record-disposal!`
  / `disposals-in-period` / `realized-gain`.
- `modules/disposal/src/kontor/disposal/source.clj` — the
  `DatahikeDisposalSource` implementation.
- `src/kontor/disposal_source.clj` — the kernel `DisposalSource`
  protocol.
- `src/kontor/statute.clj` — `parameter-value-at` /
  `apply-provisions` (the latter not used by AU).
- `src/kontor/period_tax_provider.clj` — `PeriodTaxProvider`
  protocol + `TaxReturnFacts` (the canonical `:inputs
  :capital-loss-carryforward` shape).
- `modules/l10n-uk/src/kontor/l10n_uk/cgt_provider.clj` —
  reference for lifetime-cap shape (BADR `:tax-unit
  :badr-lifetime-claimed` pattern; AU should converge).
- `doc/research/129-au-cgt-fit.md` — the research-before that
  motivated the encoding.
- `doc/decisions.md` ADR-099 — `PeriodTaxProvider` substrate.
- `doc/decisions.md` ADR-101 — statute-as-data substrate.
- `doc/decisions.md` ADR-102 — `kontor-disposal` companion.
- `doc/decisions.md` ADR-103 — per-jurisdiction CGT providers.

---

End of note 138.
