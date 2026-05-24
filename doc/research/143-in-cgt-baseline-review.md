---
date: 2026-05-24
title: 143 — IN CGT provider — baseline review against authority + note 131
audience: maintainer + future IN-CGT change agents
status: REVIEW-AFTER for ADR-103 IN CGT — independent audit against incometaxindia.gov.in (IT Act 1961 + FA 2024 + FA 2025 + IT Bill 2025), CBDT tutorials, and research note 131
---

# 143 — IN CGT provider baseline review

This note audits the ADR-103 IN CGT shipment against (a) the
canonical statute on incometaxindia.gov.in, (b) CBDT tutorials,
(c) post-FA 2024 / FA 2025 practitioner consensus, and (d) the
research-before note 131. Files reviewed:

- `modules/l10n-in/src/kontor/l10n_in/cgt_statute.clj` (358 lines)
- `modules/l10n-in/src/kontor/l10n_in/cgt_provider.clj` (763 lines)
- `modules/l10n-in/test/kontor/l10n_in/cgt_provider_test.clj` (603 lines)

Quick verdict: **substrate is well-formed and faithfully captures the
post-FA-2024 simplified rate stack, but the §54EC FY-cap accounting
has a real running-total bookkeeping bug (P0), and four P1s warrant
follow-up before exposing the provider to a real high-volume CGT
return.** The bitemporal FA 2024 cliff is modelled cleanly and tests
prove it; the worked examples reproduce note 131 §2 to the rupee.

---

## §1. Scope + method

For each item in the task spec (§1–§12), I traced the implementation
against the statute citation, then re-derived the worked example by
hand. Authority pages used:

- IT Act 1961 §111A / §112 / §112A / §50AA / §50C / §54 family /
  §70 / §74 (via CBDT tutorials + cleartax mirrors)
- Finance (No. 2) Act 2024 — 23-Jul-2024 cliff
- Finance Act 2025 — no CGT-rate change confirmed
- CBDT FAQ PIB-2036604 (the Budget 2024-25 official Q&A)
- CBDT CII table (incometaxindia.gov.in /charts /cost-inflation-
  index)

Note 131 §§1–7 was re-read in full.

---

## §2. Item-by-item verdict

### §2.1 Post-FA-2024 rates + bitemporal cliff — PASS

`cgt_statute.clj:197-228`:

- §112A: `0.125M` from 2024-07-23 (no `:effective-until`),
         `0.10M`  for 2018-04-01 .. 2024-07-23
- §111A: `0.20M`  from 2024-07-23,
         `0.15M`  for 2008-10-01 .. 2024-07-23
- §112:  `0.125M` from 2024-07-23,
         `0.20M`  for 1992-04-01 .. 2024-07-23
- §112 (with-indexation proviso): `0.20M` from 2024-07-23

All four rate parameters match Finance (No. 2) Act 2024 — verified
against CBDT FAQ PIB-2036604, BusinessToday post-Budget summary,
and the Angel One "July 23 Tax Rule" summary.

**Bitemporal correctness — PASS by construction.** The
`:effective-from` / `:effective-until` half-open window pattern with
`statute/parameter-value-at` (a strict-<) means a disposal queried
as-of an instant before 2024-07-23 reads the old rate. The provider
uses `as-of-from-ctx` (line 147-151) defaulting to `:period :to`,
so a FY-2023-24 query window (`:to 2024-04-01`) correctly hits the
pre-cliff rate.

The test suite does not include an explicit pre-cliff vs post-cliff
swap test (P2 — see §3.3 below), but the parameter shape makes the
swap automatic.

### §2.2 §112A ₹1.25 L floor — PASS

`cgt_statute.clj:236-245`:

- 125_000M from 2024-07-23 (no `:effective-until`)
- 100_000M for 2018-04-01 .. 2024-07-23

`cgt_provider.clj:392-420` reads the floor and applies `max(0, base
− floor)` before the rate. Test `listed-equity-ltcg-below-floor-
zero-tax` confirms a 100k LTCG → zero tax (below floor); test
`listed-equity-ltcg-above-floor-at-12_5pct` confirms 425k LTCG →
(425k − 125k) × 12.5 % = 37,500.

Manual cross-check against Bajaj Finserv §112A guide and CleartTax
§112A summary: ₹1.25 L floor for FY 2024-25+ confirmed.

### §2.3 CII indexation election — PASS-with-caveats

`cgt_provider.clj:205-243`:

- `fiscal-year-of` correctly identifies April-March FY boundaries
  (`if (>= month0 3) year (dec year)`).
- `cii-for` reads the per-FY parameter via
  `statute/parameter-value-at`.
- `indexed-basis` computes `basis × (CII-dis ÷ CII-acq)` using
  `MathContext/DECIMAL64` ratio and rounds HALF-EVEN to whole
  rupees.

CII table (`cgt_statute.clj:283-310`) — 25 yearly values base
2001-02=100. Manual cross-check of three years against
incometaxindia.gov.in CII chart:

- FY 2010-11 = 167 ✓
- FY 2024-25 = 363 ✓
- FY 2025-26 = 376 ✓

Election plumbing (`classify` line 326-328):

```clojure
elective      (set (:disposal/elective-regime disposal))
cii-elected?  (contains? elective :in-cii-indexation)
```

Test `immovable-ltcg-with-cii-indexation-election` reproduces:
basis 3M × (376/167) → indexed 6,754,491 → gain 10,945,509 → tax
2,189,101.80. Manual trace using DECIMAL64 confirms `(376/167) =
2.2514970059880239`, × 3,000,000 = 6,754,491.017... → HALF-EVEN
floor to 6,754,491. ✓

**Caveat 1 (P1)** — the **eligibility gate** is missing. Per FA 2024
the CII proviso is **only** available to (a) resident individuals
or HUFs disposing of (b) immovable property acquired (c) **before**
23-Jul-2024. The provider checks none of these:

`cgt_provider.clj:326-328` honours the `:in-cii-indexation` elective
unconditionally. A consumer who records the election on a:
- non-resident's disposal,
- a corporate disposal,
- a listed-equity disposal,
- an immovable acquired AFTER 23-Jul-2024

...will silently get the 20%-with-indexation treatment — wrong.

Task spec §3 expectation: *"Provider verifies eligibility (resident-
individual + pre-cliff acquisition date)."* — not implemented.

**Fix**: gate `cii-elected?` on `(and (= asset-class :in-immovable)
(= subject-form :individual-resident-or-huf) (< acquired-on
2024-07-23T00:00))`. Raise `ex-info` on disallowed election OR
silently fall back to no-indexation with a `:line-items`
`{:line :indexation-denied :reason ...}`.

**Caveat 2 (P2)** — the provider always uses CII at the acquisition-
FY base, but should support **improvement costs** with their own CII
basis (§55(2)(b) + §48 second proviso). Note 131 §3.1 Example A
explicitly cites a 2015 improvement with its own CII rebase (254).
The current shape does not have an improvement-list slot. Defer to
the next iteration; record the limitation in the docstring.

### §2.4 §54 family rollover — MIXED (P0 found)

`cgt_provider.clj:124-141, 287-312, 314-364`:

- §54-family-exemptions vocabulary correct (8 items: §54, §54B,
  §54D, §54EC, §54F, §54G, §54GA, §54GB).
- §47 transfer-not-regarded vocabulary correct (6 items plus
  `:in-§10(37)-compulsory-agri` via `(keyword ...)` workaround for
  parentheses).
- §54EC ₹50 L cap enforced in `§54-family-rollover-amount`
  (line 299-309).

**P0 — §54EC cap-accumulator double-counts mixed-rollover claims**:

`cgt_provider.clj:589-604` accumulates `§54EC-used`:

```clojure
§54EC-add (if (contains? (set (:disposal/exemption-claimed d))
                         :in-§54EC)
            (:rollover c) 0M)
```

`(:rollover c)` is the FULL rollover amount returned by
`§54-family-rollover-amount`, NOT the §54EC-attributable portion.
When a disposal claims BOTH §54EC and another §54 (e.g. §54
residential rollover), `§54-family-rollover-amount` returns
`§54EC-allowed + non-§54EC-remainder`:

```clojure
(+ allowed
   (if non-§54EC (max 0M (- rollover allowed)) 0M))
```

For Mr Sharma's worked example (line 524-552):
- rollover 14.2 M, claims `#{:in-§54EC :in-§54}`
- `allowed = min(14.2M, 5M cap) = 5M` (§54EC portion)
- `non-§54EC` truthy → add `14.2M − 5M = 9.2M`
- Returns 14.2M total
- Line 600 then adds **14.2M** to `§54EC-used`, even though only
  **5M** was §54EC

**Consequence**: a SECOND disposal in the same FY that claims
`:in-§54EC` alone would see `§54EC-prior-claimed = 14.2M`, head =
`max(0, 5M − 14.2M) = 0`, and get ZERO §54EC relief — even though
the actual §54EC cap is far from exhausted (only 5M of the cap is
used). A consumer with multiple immovable-property disposals in
one FY who legitimately wants to use the bond cap on a later
disposal would be silently denied.

The bug doesn't manifest in the worked-example test (single
disposal) but would break any real high-volume return.

**Fix**: in the provider's reduce loop, change `§54EC-add` to
**only the §54EC-attributable slice** — pass it back from
`§54-family-rollover-amount`:

```clojure
;; rough sketch
(defn- §54-family-rollover-amount [...] {:total ... :§54EC-used ...})
;; reduce:
§54EC-add (:§54EC-used c)
```

Tests must add a case with two §54EC disposals where the second is
clipped by an earlier mixed-§54+§54EC claim.

**§54EC test coverage (PASS for what's tested)**:
- `§54EC-rollover-within-cap` ✓
- `§54EC-cap-clips-rollover` ✓
- `§54-residential-house-rollover-fully-shelters` ✓
- `§54EC-prior-claimed-aggregates-against-cap` ✓

**P2 — §54EC "current FY + subsequent FY" combined cap**:
Per CleartTax + Income Tax Department §54EC page (and confirmed
via WebSearch): the ₹50 L cap is across **current FY AND the
subsequent FY combined** (i.e. lifetime per single LTCG event, two-
FY rolling window). The provider models it as a per-FY hard cap
via `:in-§54EC-prior-claimed`. For a single-FY query window this
is correct; for a consumer crossing FY boundaries this needs an
additional input or a documented gotcha. Note 131 §1.5 table
says "₹50 L per FY across §54EC bonds" but the official guidance
is two-FY-combined. Defer the modeling decision; document the
gap.

### §2.5 §47 transfer-not-regarded — PASS

`cgt_provider.clj:130-141 + 280-285`:

- Six closed-set keywords; `:in-§10(37)-compulsory-agri` added via
  `keyword` fn (parentheses workaround — correct).
- `§47-exempt?` checks `:disposal/exemption-claimed` set.
- When true, gain forced to 0 AND lane = `:exempt`.

Test `§47-transfer-not-regarded-yields-zero-tax` confirms: an
amalgamation with 15M gain produces zero `:ltcg-§112` component AND
an `:exempt` component with zero liability.

Representative case (§47(vi) amalgamation, `:in-§47-amalgamation`)
verified. Other items (§47(iii), (iv), (v), (xiiib)) untested but
follow the same path.

**Note**: the provider does NOT enforce the transferee's basis
carry-over (§49) — that's the recipient-side responsibility on a
future inbound `:disposal`. Correct posture per ADR-102; document
as a consumer-side responsibility.

### §2.6 §50C anti-undervaluation — PASS

`cgt_provider.clj:249-266`:

```clojure
(> sdv (* ratio recorded)) sdv
```

Where `ratio = 1.10M` from `IN.CGT.§50C.safe-harbour-ratio` parameter
(`:effective-from 2020-04-01`, post-FA-2020 raise from 105 % to
110 %).

Verified: SDV strictly greater than 110 % × recorded triggers §50C.
Test `§50C-deemed-proceeds-applies-when-sdv-exceeds-safe-harbour`
covers both paths:
- `proceeds 10M, SDV 15M`: 15M > 1.10 × 10M = 11M → §50C bites,
  deemed = 15M, gain = 10M, tax = 1.25M ✓
- `proceeds 10M, SDV 10.5M`: 10.5M ≤ 11M → §50C silent, gain = 5M,
  tax = 625k ✓

Boundary case (`SDV == 1.10 × recorded`) treated as safe-harbour
(strict `>`) — matches the FA 2020 third proviso wording.

### §2.7 §194-IA TDS prepaid — PASS-with-quibble

`cgt_provider.clj:710-728`:

```clojure
:in-tds-§194-IA <bigdec> ; from :inputs
```

Applied to the `:ltcg-§112` component's `:prepaid` (or fallback to
first component). Test `§194-IA-tds-rides-as-prepaid` confirms
100k TDS → 100k `:prepaid` on the `:ltcg-§112` component.

**Quibble (P2)** — the routing is brittle:

```clojure
(some (fn [[i c]] ...)) ...  ; falls back to index 0 if no §112 lane
```

If the immovable disposal is short-term (STCG → `:stcg-slab` lane,
not `:ltcg-§112`), the TDS lands on whatever `(first cs)` is — which
could be `:ltcg-§112A` (equity LTCG, completely unrelated). A
consumer reconciliation would show the TDS credited against an
unrelated rate-lane, surfacing audit confusion.

**Fix**: when no `:ltcg-§112` lane is present, look for `:stcg-slab`
instead (the slab-rate STCG lane an immovable STCG would go into).
Failing both, raise a defensive error rather than silently mis-
crediting.

Also: §194-IA threshold parameter `IN.CGT.§194-IA.threshold = 5M`
(₹50 L) is in the statute table but NEVER read by the provider.
The threshold check is the buyer's responsibility (the buyer
withholds only if consideration ≥ 50L); the seller's provider just
credits whatever the consumer supplies. Document this; the statute
parameter is documentary-only.

**P2 — §194-IA "higher of consideration or SDV" basis (Budget 2024
post-Oct 2024)**: Per WebSearch, the FA 2024 amendment specifies
the buyer withholds 1 % on the **higher of consideration or SDV**
(effective 2024-10-01). The provider's TDS handling doesn't care
about this (the consumer supplies the withheld amount), but a
future broker/buyer importer would need to model the SDV-aware
base. Documentation gap, not a provider bug.

### §2.8 §50AA debt MF — PASS

`cgt_provider.clj:186-199`:

```clojure
(cond
  (= asset-class :in-debt-mf)        false  ; §50AA — always STCG
  (= asset-class :in-debt-security)  false
  ...)
```

Test `debt-mf-always-slab-§50AA` confirms: a debt-MF held >24 months
(2024-01-01 to 2026-08-01 = 31 months) still classifies as STCG and
folds into `:stcg-slab`. ✓

**Caveat (P2)** — the §50AA "always STCG" rule applies to debt MFs
**acquired on/after 1-Apr-2023**. The provider's `:in-debt-mf` class
forces STCG regardless of acquisition date. A separate
`:in-debt-mf-pre-2023` class (note 131 §3.2) was mentioned in the
research note but is not honored by the classifier; instead a
pre-2023 debt MF would need to be recorded as `:in-other-lta` to get
the LTCG path. Document this convention OR add the date gate inside
the classifier.

### §2.9 4 % H&E cess — PASS

`cgt_statute.clj:329-344 + cgt_provider.clj:498-529`:

The cess is wired through `kontor.statute/apply-provisions` (a real
substrate exercise) via:

```clojure
{:provision/code "IN-FA-2018-cess-cgt"
 :provision/concept [:tax-concept/code :surtax]
 :provision/condition  (pr-str [:gt [:inputs :standalone-cgt-running-tax] 0M])
 :provision/consequence (pr-str {:op :surtax :amount-from :compute-fn
                                  :fn :in-cgt-cess})}
```

with `in-cgt-cess` registered at namespace-load via `(register!)`
(line 165-171). Multiplier `0.04M` reads from `IN.CGT.cess.rate`
parameter.

Composed-of `[:ltcg-§112A :stcg-§111A :ltcg-§112]` (line 528). The
cess base is "standalone CGT" = sum of LTCG-eq + STCG-eq + LTCG-
other gross-liabilities (line 696-699).

Test `cess-fires-on-standalone-cgt`: LTCG 37,500 × 0.04 = 1,500 ✓
Test `worked-example-B-patel-listed-equity-portfolio`: (36,875 +
30,000) × 0.04 = 2,675 ✓

**Note (per FA 2024 §2(11)(c))** — task spec mentions cess "per
§2(11)(c) of Finance Act 2024". The provision-citation only mentions
"Finance Act" without the specific Finance Act year; the rate has
been 4 % since FA 2018 (post-merger of secondary-and-higher-
education cess + the original education cess). Citation could be
tightened. (P2)

### §2.10 IT Bill 2025 one-time relief — PASS

`cgt_provider.clj:643-656`:

```clojure
ay-2027-on? (>= (compare as-of #inst "2026-04-01") 0)
onetime     (when ay-2027-on?
              (or (:in-ltcl-pre-2026-onetime carry-in) 0M))
```

`stcg-eq-net-2` and `stcg-slab-net-2` consume the onetime carry
after their respective compartment-wall cascades. Test
`ltcl-pre-2026-onetime-relief-offsets-stcg-from-ay-2027-28` uses
`as-of 2027-03-31`, carry-in 100k, STCG 200k → net 100k → tax 20k ✓.

**The `as-of ≥ 2026-04-01` gate is correct** per CAalley/IT Bill
2025 summary (AY 2027-28 begins 1-Apr-2026). A `as-of 2026-03-31`
query would correctly suppress the relief.

### §2.11 Five-bucket carry-in — PASS-with-quibble

`cgt_provider.clj:610-656` implements the §70/§74 cascade:

- Step 1: LTCL → LTCG (same compartment) — equity to equity,
  other to other.
- Step 2: STCL → STCG, then spill to LTCG (same compartment, §70(2)).
- Step 3: IT Bill 2025 pre-2026 LTCL → any STCG (gated by `as-of`).

Five buckets correctly named:
`:in-stcl-equity :in-ltcl-equity :in-stcl-other :in-ltcl-other
:in-ltcl-pre-2026-onetime` (six counting onetime).

Tests:
- `ltcl-equity-carryforward-offsets-ltcg-equity` — within-compartment LTCL → LTCG ✓
- `stcl-equity-§70-2-cross-offsets-ltcg` — STCL → STCG → spill to LTCG ✓
- `ltcl-pre-2026-onetime-relief-offsets-stcg-from-ay-2027-28` ✓
- `carry-forward across compartments isolated` (implicit by absence)

**Quibble (P2)** — the §74 8-year expiry is NOT enforced. The provider
takes consumer-supplied carry-in as a single scalar per bucket;
modeling carry-in as `[{:amount :ay-of-loss}]` with a `(<= (- this-ay
loss-ay) 8)` filter would catch expired losses. This is a consumer-
side responsibility today; document the convention.

**Quibble (P1) — `:in-stcl-other` mis-routes to `:stcg-slab`**:

Line 638-640:

```clojure
{stcg-slab-net :net slab-rem :remaining-carry}
(net-against stcg-slab (or (:in-stcl-other carry-in) 0M))
```

§70(2) says STCL can offset STCG OR LTCG of **any** compartment.
The provider applies `:in-stcl-other` only against `:stcg-slab`
(the slab-rate STCG bucket), then spills to `:ltcg-§112` (other
LTCG). This is **almost** right but:

- A taxpayer whose `:stcg-other` happens to be a slump-sale STCG
  routed via `:in-immovable` or `:in-business-undertaking-slump-
  sale` lands in `:stcg-slab` correctly. ✓
- But §70(2) actually allows STCL to also offset `:stcg-§111A`
  (listed-equity STCG) — the provider does NOT cross-pollinate.

Re-read §70(2) carefully: per CleartTax + ITAct §70(2): "Where the
result of the computation made for any assessment year under
sections 48 to 55 in respect of any short-term capital asset is a
loss, the assessee shall be entitled to have the amount of such
loss set off against the income, if any, as arrived at under a
similar computation made for the assessment year in respect of any
other capital asset." STCL is fungible across STCG types.

**Fix**: STCL (both equity + other) should cascade through ALL
STCG buckets in priority order before spilling to LTCG. Adjusting
the cascade order is a 5-line change but needs a clear consumer-
contract decision: which order does the substrate pick (equity
first, or other first)? Recommend "exhaust same-compartment first,
then other compartment, then LTCG".

### §2.12 Worked examples (note 131 §2) — PASS

**Example A (Mr Sharma)** — `worked-example-A-sharma-immovable-
§54EC-§54-stack` (line 524-552):
- Proceeds 1.77 cr, basis 35 L, gain 1.42 cr
- Exemptions `#{:in-§54EC :in-§54}`, rollover 1.42 cr
- §54EC = 50 L of cap; §54 = 92 L of new flat
- Net taxable = 0 → no `:ltcg-§112` component ✓
- TDS 1.8 L surfaces on... where? **The test does NOT assert TDS
  routing** (since no `:ltcg-§112` exists, the fallback routes to
  index 0 — first available component, which would be the
  `:exempt` component). Test silently passes but the consumer would
  see TDS credited to an exempt-tracking component, creating
  reconciliation confusion. (P1 cross-ref §2.7)

**Example B (Ms Patel)** — `worked-example-B-patel-listed-equity-
portfolio`:
- 3 disposals: RIL +4L, Infy +0.8L, HDFC +1.5L STCG
- Carry-in LTCL 60k → LTCG net 4.2L
- §112A floor → taxable 2.95L → tax 36,875 ✓
- STCG → tax 30,000 ✓
- Cess (36,875 + 30,000) × 4 % = 2,675 ✓

All to the rupee. Note 131 §2 Example B reproduced exactly.

---

## §3. Cross-cutting findings

### §3.1 §54EC running-total bookkeeping (the P0 from §2.4)

Restating for emphasis: the `§54EC-used` accumulator in the
provider's reduce loop **conflates** §54EC-attributable rollover
with the entire claimed rollover when a disposal claims both §54EC
and a sibling §54. This silently exhausts the cap and denies later
§54EC claims in the same FY. Fix is mechanical; ship it before the
provider sees a real multi-disposal IN return.

### §3.2 CII eligibility gate missing (P1, restated)

The election fires for non-residents, corporates, equity disposals,
and post-23-Jul-2024 immovable acquisitions — all of which are
statutorily ineligible. The substrate "carries the consumer's
declaration as documentary" posture is sound, but the FA 2024
proviso is non-elective for ineligible cases — silently honoring an
ineligible election is a misstatement risk.

### §3.3 No bitemporal cliff test (P2)

The 23-Jul-2024 FA 2024 cliff is the most consequential CGT
amendment in the past decade. The provider's parameter table
encodes both pre- and post-cliff rates with `:effective-until`
boundaries, but no test exercises a query asof a pre-cliff instant
to confirm the OLD rate fires. Add one:

```clojure
(deftest fa-2024-cliff-pre-vs-post
  (testing "as-of 2024-07-01 reads 10 % §112A; as-of 2024-07-23 reads 12.5 %"
    ...))
```

### §3.4 `as-of-from-ctx` defaulting to `:period :to`

Same posture as the BR provider — sensible default. The test fixture
explicitly passes `:as-of (:to period)` for clarity (line 74) which
is the same value `as-of-from-ctx` would compute; harmless.

### §3.5 Test isolation — clean

23 deftests, ~100 assertions. Test fixture creates a fresh in-memory
db per test; `record!` is a thin sugar over
`disposal/record-disposal!`; the provider is constructed once per
test. No hidden globals beyond `(register!)` at namespace-load
(idempotent).

### §3.6 Schema attr usage — clean

The provider reads only documented `:disposal/*` attrs:

- `:disposal/proceeds-amount` (line 257)
- `:disposal/basis-amount` (line 234, 277)
- `:disposal/rollover-amount` (line 296)
- `:disposal/asset-class` (line 193, 261, 326)
- `:disposal/acquired-on` (line 191, 232)
- `:disposal/disposed-on` (line 192, 233)
- `:disposal/elective-regime` (line 327)
- `:disposal/exemption-claimed` (line 285, 295)
- (NOT used: `:disposal/holding-period` — the provider classifies
  from acquired-on/disposed-on directly, ignoring the denormalised
  attr; consistent with note 131 §3.1 design)

No private companion internals touched. Substrate-edge hygiene
preserved.

### §3.7 `:in-§50-depreciable-loss` bucket from note 131 §3.3 — MISSING (P2)

Note 131 §3.3 lists 6 carry-in buckets including
`:in-§50-depreciable-loss`. The provider's cascade only honors 5
(the IT Act §41 depreciable-block treatment is OUT of CGT-provider
scope per the note). This is intentional but undocumented; add a
docstring note that depreciable-block losses are §41 business-
income, not §74 CGT.

---

## §4. Severity-ranked follow-ups

| # | Severity | Issue | File:line | Suggested fix |
|---|----------|-------|-----------|---------------|
| 1 | **P0** | §54EC FY-cap accumulator double-counts mixed-§54 rollover (silently exhausts cap on later disposals in same FY) | `cgt_provider.clj:589-604 + 287-312` | Return `{:total :§54EC-used}` from `§54-family-rollover-amount`; accumulate only `:§54EC-used` |
| 2 | P1 | CII indexation election fires unconditionally — no resident-individual / immovable / pre-23-Jul-2024 gate | `cgt_provider.clj:326-328` | Gate `cii-elected?` on asset-class + subject-form + acquired-on |
| 3 | P1 | §70(2) STCL doesn't cross-pollinate across STCG buckets (equity STCL can offset slab STCG and vice-versa) | `cgt_provider.clj:633-640` | Re-shape cascade: STCL → all STCG in priority, then spill to LTCG |
| 4 | P1 | §194-IA TDS routes to `:ltcg-§112`; falls back to index 0 if absent (mis-credits to unrelated rate-lane) | `cgt_provider.clj:710-728` | Fallback chain: §112 → `:stcg-slab` → defensive error |
| 5 | P1 | Worked-example A (Sharma) does not assert TDS routing — silently passes despite the mis-route | `cgt_provider_test.clj:524-552` | Add assertion on which component receives the 180k TDS prepaid |
| 6 | P2 | CII no improvement-cost CII basis (§55(2)(b)) | `cgt_provider.clj:224-243` | Document gap; defer to v2 with `:asset-improvement` companion |
| 7 | P2 | §54EC "current FY + subsequent FY" combined cap not modelled (provider treats as per-FY) | `cgt_provider.clj:299-309` | Document the gap; consumer-side responsibility for cross-FY tracking |
| 8 | P2 | §74 8-year carry expiry not enforced (consumer-side) | `cgt_provider.clj:610-656` | Document; consider `[{:amount :ay-of-loss}]` shape in v2 |
| 9 | P2 | §50AA always-STCG ignores 1-Apr-2023 acquisition gate; consumer must use `:in-other-lta` for pre-2023 debt MF | `cgt_provider.clj:186-199` | Document the asset-class convention OR add date gate inside classifier |
| 10 | P2 | §194-IA threshold parameter is dead code (5M cap unused) | `cgt_statute.clj:121-125` | Document as buyer-side; or drop |
| 11 | P2 | §194-IA SDV-aware base (post-Oct-2024 amendment) not modelled | `cgt_provider.clj:710-728` | Document gap; defer to broker-importer iteration |
| 12 | P2 | No bitemporal cliff test (pre-cliff rate read) | `cgt_provider_test.clj` | Add `fa-2024-cliff-pre-vs-post` deftest |
| 13 | P2 | Cess citation "Finance Act" without year; rate has been 4 % since FA 2018 not FA 2024 | `cgt_statute.clj:340-343` | Tighten citation |

**1 P0, 4 P1s, 8 P2s.** Fix the P0 before the provider sees a real
multi-§54EC return. P1s before exposing to non-resident consumers
or high-volume immovable filings. P2s as the next iteration's
followup grist.

---

## §5. Comparison with the per-stage rhythm

Per `CLAUDE.md` §"Per-stage rhythm" — IN shipment followed steps 1
(research-before — note 131) + 2 (implement — ADR-103). This note
IS step 3 (review-after).

The P0 (§54EC accumulator) is the kind of bug that survives unit
tests because the test grid covers single-disposal cases. The P1s
(CII gate, STCL §70(2) cross-pollination) are statutory edge-case
misses that the research note correctly flagged but the
implementation under-modeled. The P2s are documented-gap items
that a v2 should pick up.

This is a higher-than-average finding density (1 P0 + 4 P1) for a
post-research-note implementation — likely because IN's statute is
genuinely the densest of any jurisdiction in the suite (the
research note 131 itself is 800 lines). Recommend a focused 1-day
follow-up to close the P0 + P1s before ticking the IN slice on the
roadmap.

---

## §6. Sources

### Statute primary

- **Income-tax Act 1961** — §111A, §112, §112A, §50, §50AA, §50C,
  §50CA, §54, §54B, §54D, §54EC, §54F, §54G, §54GA, §54GB, §47,
  §70, §74, §194-IA (verified against incometaxindia.gov.in mirror
  + CleartTax section reference pages cited in note 131 §7).
- **Finance (No. 2) Act 2024** — 23-Jul-2024 cliff; confirmed
  rates §112A 12.5 %, §111A 20 %, §112 12.5 %, §112A floor ₹1.25 L,
  §50C threshold 110 %, §54EC cap ₹50 L per FY.
- **Finance Act 2025** — no CGT rate change (BusinessToday +
  Grant Thornton summaries).
- **Income-tax Bill 2025** — one-time LTCL → STCG relief from AY
  2027-28 onward (CAalley summary).

### CBDT regulatory

- [CBDT FAQ on new CGT regime — PIB-2036604](https://www.pib.gov.in/PressReleaseIframePage.aspx?PRID=2036604)
  — the official Budget 2024-25 Q&A.
- [CBDT — Income Tax Department Section 54EC](https://www.incometaxindia.gov.in/w/section-54ec-20)
  — the "current FY + subsequent FY" combined cap.
- [CBDT — TDS Purchase of Immovable Property](https://www.incometaxindia.gov.in/w/tds-purchase-of-immovable-property)
  — §194-IA + the FA 2024 SDV amendment.
- [CBDT — CII chart](https://incometaxindia.gov.in/charts%20%20tables/cost-inflation-index.htm)
  — official CII values matched against `cgt_statute.clj:283-310`.

### Reference + commentary

- [CleartTax — §112A LTCG](https://cleartax.in/s/long-term-capital-gains-on-shares)
  — ₹1.25 L floor confirmation.
- [Bajaj Finserv — §112A](https://www.bajajfinserv.in/investments/section-112a-income-tax-act)
  — pre- and post-FA-2024 rate cliff confirmation.
- [CleartTax — §54EC bonds](https://cleartax.in/s/section-54ec-bonds)
  — current-FY + subsequent-FY combined ₹50 L cap.
- [CleartTax — §50C](https://cleartax.in/s/taxability-sale-land-building-section-50c)
  — 110 % safe-harbour confirmation.
- [Angel One — New July 23 Tax Rule](https://www.angelone.in/news/taxation/new-july-23-tax-rule-could-raise-your-capital-gains-bill-here-s-what-changed)
  — FA 2024 effective-date confirmation.
- [TaxGuru — New TDS Rules on Immovable Property](https://taxguru.in/income-tax/new-tds-rules-immovable-property-sales-effective-1st-october-2024.html)
  — FA 2024 §194-IA SDV-aware base.
- [Bajaj Finserv — §74](https://www.bajajfinserv.in/investments/section-74-of-income-tax-act)
  — 8-year carry-forward + §70(2) cross-set-off rules.
- [CAalley — IT Bill 2025 one-time LTCL relief](https://www.caalley.com/news-updates/indian-news/one-time-set-off-of-long-term-capital-loss-against-stcg-new-income-tax-bill-2025-allows-this-from-tax-year-2026-27-onwards)
  — AY 2027-28 effective date.

### kontor substrate cited

- `doc/research/131-in-cgt-fit.md` — the research-before; all
  §-references in this review point to it.
- `src/kontor/tax_schedule.clj:64-90` — `:flat` + `:progressive`
  the provider uses.
- `src/kontor/statute.clj:150-188 + 440-…` — `parameter-value-at`,
  `parameter-brackets-at`, `apply-provisions` (used for cess).
- `src/kontor/period_tax_provider.clj:102-184` — `TaxReturnFacts`,
  `balance`.
- `modules/disposal/src/kontor/disposal/source.clj:60-87` — the
  `DisposalSource` impl the IN provider consumes; period filter +
  void exclusion preserved.
- `modules/disposal/src/kontor/disposal/schema.clj` — only
  documented `:disposal/*` attrs touched.

---

End of note 143.
