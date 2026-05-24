---
date: 2026-05-24
title: 137 — CA CGT (T1 Schedule 3 + T2 Schedule 6) baseline review against statute + authority
audience: maintainer
status: review-after — ADR-103, CA jurisdiction (12th CGT provider; 4th end-to-end ADR-101 consumer in CA after CIT)
---

# 137 — CA CGT baseline review

ADR-103 shipped CA capital-gains tax across three files in
`modules/l10n-ca/`: `cgt_statute.clj` (4 `:parameter`s + 0 `:provision`s
— intentional per the ns docstring; CA's CGT complexity sits in the
provider's per-disposal classifier, not in statute-as-data), a thin
single-component `CACapitalGainsTaxProvider` over the ADR-102
`DisposalSource` protocol (`cgt_provider.clj`, ~580 lines), and 14
deftests / 39 assertions including the note-127 §2 Example A "Singh
QSBC 2026 → $112,450 taxable" reproduction
(`cgt_provider_test.clj`). Per the maintainer's standing baseline-review
mandate, this note audits the encoding against laws-lois.justice.gc.ca
(ITA s.13, s.38, s.39, s.40, s.46, s.50, s.110.6), canada.ca CRA folios
(S3-F4-C1 CCA, S4-F8-C1 ABIL, S1-F3-C2 Principal Residence), Dept of
Finance press releases (the 2025-03-21 cancellation of the proposed
2/3 inclusion rate), and TaxTips.ca / BDO / KPMG commentary.

**Headline.** The headline rates, the 2026 LCGE indexed figure, the
ABIL half-rate, the cancellation of the 2/3 inclusion rate, the LCGE
pool semantics, the CCA-recapture split arithmetic, the rollover
deferral surface, and the worked example all reproduce to the dollar
against the cited authority. The implementation is **correct on the
mainline**, the schema/`:disposal` substrate fit is clean (ZERO schema
additions, as note 127 forecast), and CDA scope discipline is
respected (no CDA leak — that lives in `kontor.book` per ADR-095 + note
127 §3.7). **One P0 finding** (depreciable-property loss silently
mis-classified as a capital loss, violating s.20(16) terminal-loss
doctrine); **four P1 findings** (PUP s.46(1) floor + loss-denial NOT
enforced despite the parameter shipping, LPP s.41 compartment-wall
absent, only two of six rollover regimes exercised by tests,
`:ca-§38a1-listed-donation` zero-inclusion lane unverified by any
test); **six P2 findings** (holding-period denorm not stamped,
`:cnil-balance` documented but silently ignored, `:state :recorded`
not filtered alongside `:voided`, statute citation gaps, lifetime-cap
`:tax-concept` not wired, audit-doc form references missing).

The §6 substrate-level finding: the AT/CN/CA pattern of routing
fan-out via `:jurisdiction-specific-codes {:pit-base-additions [...]
:pit-base-deductions [...] :cit-base-additions [...]
:cit-base-deductions [...]}` is now used by 4+ providers; the
convention deserves promotion to a typed kernel-side helper (and an
ADR-103 addendum spelling the contract out, since none of the downstream
PIT/CIT providers consume the keys yet — the wiring is consumer code).

---

## §1. Headline correctness — what the implementation got right

Before the findings: the substrate this provider lands on is shaped
right, and the most-easily-wrong things are NOT wrong.

- **50% inclusion rate (`cgt_statute.clj:99-102`)** — the parameter
  carries a single `decimal-value 0.5M` from 1972-01-01 with no
  `:effective-until` (open forward) and a citation explicitly noting
  the cancelled 2/3 increase. This is the post-2025-03-21 reality:
  PM Carney's office cancelled the proposed two-thirds inclusion above
  $250k on March 21, 2025 (Dept of Finance / PMO release titled "Prime
  Minister Mark Carney cancels proposed capital gains tax increase").
  Confirmed against [pm.gc.ca](https://www.pm.gc.ca/en/news/news-releases/2025/03/21/prime-minister-mark-carney-cancels-proposed-capital-gains-tax-increase)
  and [Scotia Wealth](https://enrichedthinking.scotiawealthmanagement.com/2025/04/07/cancellation-of-the-proposed-capital-gains-inclusion-rate-increase/).
  No `:effective-until` on the 0.5M row, no 0.6667M row, no $250k
  bracket — the substrate matches reality. **OK.**

- **LCGE 2026 = $1,275,000 (`cgt_statute.clj:118-122`)** — the
  indexed value the encoding carries for the 2026-01-01-open window.
  ITA s.110.6(2) read in light of s.117.1 indexation, plus the
  June-25-2024 Bill C-69 §10 bump from $1,016,836 to $1,250,000
  (retained even after the inclusion-rate-increase cancellation per
  the PMO 2025-03-21 release: "The government will maintain the
  increase in the Lifetime Capital Gains Exemption limit to
  $1,250,000"). Indexation resumed 2026; the bump is to $1,275,000.
  Confirmed against [TaxTips.ca LCGE history](https://www.taxtips.ca/smallbusiness/lifetime-capital-gains-exemption.htm),
  [Shajani CPA](https://shajani.ca/lifetime-capital-gains-exemption-lcge-2026-the-1-25-million-limit-explained/),
  [Insight CPA](https://insightscpa.ca/capital-gains-tax-canada-2026-small-business-guide/),
  [WealthNorth](https://wealthnorth.ca/taxes/lifetime-capital-gains-exemption-canada/).
  Two `:parameter-value` rows with the right half-open windows
  (2024-06-25 → 2026-01-01 = $1,250,000; 2026-01-01 → open =
  $1,275,000). **OK.**

- **ABIL half-rate (`cgt_statute.clj:104-108`, `cgt_provider.clj:419-435`)** —
  parameter `CA.CGT.abil-rate` = 0.5M (s.38(c)), provider's `aggregate`
  multiplies the raw business-investment-loss magnitude by it,
  surfaces the result as a `:pit-base-deductions` (or
  `:cit-base-deductions`) entry. Matches s.38(c)'s "an allowable
  business investment loss for a taxation year is 1/2 of a business
  investment loss" — [laws-lois.justice.gc.ca s.38](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-38.html)
  + [CRA Folio S4-F8-C1](https://www.canada.ca/en/revenue-agency/services/tax/technical-information/income-tax/income-tax-folios-index/series-4-businesses/series-4-businesses-folio-8-losses/income-tax-folio-s4-f8-c1-business-investment-losses.html).
  ABIL semantics — deduction against ANY income, not just capital
  gains — correctly routes via `:pit-base-deductions` rather than
  through the capital pool. **OK.**

- **CCA-recapture split (`cgt_provider.clj:236-263`)** — the
  arithmetic identity matches s.13(1) + s.20(16) doctrine: capital
  cost = NBV + depreciation-taken; recapture = min(proceeds,
  capital-cost) − NBV; capital portion = max(0, proceeds − capital-cost).
  Verified against [CRA Line 9947](https://www.canada.ca/en/revenue-agency/services/tax/businesses/topics/rental-income/completing-form-t776-statement-real-estate-rentals/line-9947-recaptured-capital-cost-allowance.html)
  ("CCA Recapture = Proceeds of Disposition − Remaining UCC")
  and [CRA Folio S3-F4-C1](https://www.canada.ca/en/revenue-agency/services/tax/technical-information/income-tax/income-tax-folios-index/series-3-property-investments-savings-plans/series-3-property-investments-savings-plans-folio-4-capital-cost-allowance/income-tax-folio-s3-f4-c1-general-discussion-capital-cost-allowance.html).
  The note-127-§2-style cca-1 test (`cgt_provider_test.clj:312-336`)
  exercises both arms (60k recapture + 25k taxable cap gain on the
  150k proceeds / 40k NBV / 60k dep-taken case). The cca-2 test
  (`cgt_provider_test.clj:338-354`) checks the no-excess case
  (70k proceeds → 30k recapture, 0 capital). **OK on the positive-
  gain mainline.** (P0 below: the loss case is wrong.)

- **LCGE pool consumption (`cgt_provider.clj:369-400`)** — fold-walk
  over classified disposals, draws from `(stat-cap − :inputs
  :lcge-claimed-prior)` floored at zero, decrements per disposal, no
  refund of unused on second pass within the year. CNIL grind not
  applied (per task: "CNIL reduction NOT in v1 (consumer responsibility
  per note 127)"). The three LCGE tests cover (a) full consumption of
  the 2026 cap on a single disposal — Singh QSBC reproducing note 127
  §2 Ex.A to the dollar ($1,275,000 sheltered, $224,900 net, $112,450
  taxable — `cgt_provider_test.clj:141-165`); (b) prior-claim
  reducing the available pool ($700k prior → only $575k available —
  `cgt_provider_test.clj:167-186`); (c) pool exhaustion ($2M prior on
  $1.275M cap → 0 available, full $599,900 gain in pool —
  `cgt_provider_test.clj:188-205`). **OK.**

- **Corporate LCGE hard-zero (`cgt_provider.clj:540-542`)** — when
  `:kind :corporation`, the provider replaces the parameter lookup
  with `0M` regardless of what the parameter table says. Correct per
  s.110.6(2)/(2.1): LCGE is "an individual (other than a trust)" — i.e.
  natural persons + qualifying personal trusts. Test
  `corporate-lcge-not-available` (`cgt_provider_test.clj:382-397`)
  verifies the corp with `:exemption-claimed #{:ca-lcge-qsbcs}` gets
  no shelter. **OK.**

- **CDA scope discipline** — grepping the provider + statute files
  for `cda` / `nrdtoh` / `rdtoh` / `capital-dividend` confirms ZERO
  implementation code; only docstring references at
  `cgt_provider.clj:88-92` (the ns doc) and `cgt_provider.clj:380`
  (a passing comment on s.110.6(2) semantics). The CDA crediting verb
  + the s.83(2) Form-T2054 capital-dividend payment correctly remain
  `kontor.book` territory per ADR-095 + note 127 §3.7. **OK.**

- **Substrate hygiene — `DisposalSource` dependency** — provider
  requires `kontor.disposal-source` (the kernel protocol at
  `src/kontor/disposal_source.clj:63-73`), not the companion's
  `kontor.disposal.source` impl. Tests wire the companion
  (`cgt_provider_test.clj:20` requires `kontor.disposal.source :as
  disp-source` then calls `(disp-source/datahike-source conn)`). Loose
  coupling preserved. **OK.**

- **Worked Example A reproduction** — Singh QSBC sale, 2026-06-15,
  CCPC, proceeds $1,500,000, basis $100, prior LCGE $0 → $1,499,900
  raw gain, $1,275,000 LCGE applied, $224,900 net pre-inclusion,
  $112,450 taxable. Verified against note 127 §2 Ex.A exactly. **OK.**

- **Worked Example B reproduction** — OpsCo (CCPC) RBC shares sale,
  2026-04-15, proceeds $200,000, basis $80,000 → $120,000 raw gain,
  $60,000 taxable (50% × 120k) flowing to `:cit-base-additions`. Test
  `corporate-folds-into-cit-base-additions`
  (`cgt_provider_test.clj:360-380`). Matches note 127 §2 Ex.B's
  $60,000 taxable. (Note B's CDA credit + ART + capital-dividend
  payment correctly stay out of the provider; those are book-verb
  work per note 127 §3.7 / §5.5.) **OK.**

---

## §2. P0 — must-fix before this provider's next consumer

### P0-1. Depreciable-property loss case mis-classified as capital loss

**File**: `modules/l10n-ca/src/kontor/l10n_ca/cgt_provider.clj:236-263`

**What's wrong.** `cca-split` early-exits when the gain is non-positive
OR when no depreciation was taken:

```clojure
(or (not (pos? dep-taken)) (not (pos? gain)))
{:recapture 0M :capital gain}                  ;; gain may be negative
```

When proceeds < NBV (a loss on depreciable property), `gain` is
negative and the code routes the negative number into
`:capital-pre-inclusion`. The provider's `aggregate`
(`cgt_provider.clj:406-442`) sums this into `gross-capital` and (post
`max 0M`) into the capital-loss-vs-capital-gain offset. Net effect:
the loss either reduces other taxable capital gains within the year
OR (if no other gains exist) gets floored to zero.

This is **wrong for depreciable property**. ITA s.20(16) — the
terminal-loss provision — treats `(UCC at year-end − proceeds)` on the
disposition of the last asset of a CCA class as **deductible against
ordinary income** in computing income for the year, NOT as a capital
loss. A capital loss on depreciable property is statutorily impossible
— s.39(1)(b)(i) excludes depreciable property from the capital-loss
universe. See [CRA Folio S3-F4-C1 ¶1.92-1.96](https://www.canada.ca/en/revenue-agency/services/tax/technical-information/income-tax/income-tax-folios-index/series-3-property-investments-savings-plans/series-3-property-investments-savings-plans-folio-4-capital-cost-allowance/income-tax-folio-s3-f4-c1-general-discussion-capital-cost-allowance.html)
("Subsection 20(16) … requires the deduction in computing income of a
terminal loss for the year") and [laws-lois.justice.gc.ca s.20(16)](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-20.html).
Also see [Marcil Lavallée — denied terminal loss for buildings](https://marcil-lavallee.ca/en/bulletin/sale-of-building-used-for-business-or-rental-possible-denial-of-terminal-loss/).

**Authority citation.** ITA s.20(16); ITA s.39(1)(b)(i) (depreciable
property exclusion from capital loss); CRA Folio S3-F4-C1.

**Recommended fix.** Replace the early-exit's `gain` branch with an
explicit terminal-loss routing. When `:asset-class ∈ {:ca-depreciable,
:ca-class-14.1}` and `gain < 0`, route the magnitude as a
`:pit-base-deductions` / `:cit-base-deductions` entry (same shape as
ABIL), NOT into `:capital-pre-inclusion`. A first cut:

```clojure
(cond
  (and (depreciable? disposal) (neg? gain))
  {:capital-pre-inclusion 0M
   :ordinary-recapture    0M
   :terminal-loss         (- gain)   ;; positive magnitude
   :abil-deduction        0M
   :lcge-eligible?        false
   :line-items            [(assoc base-line :role :terminal-loss
                                  :amount (- gain))]
   :disposal              disposal}
  ...)
```

Then `aggregate` adds a `:terminal-loss` sum and `component` routes
it to the same `:pit-base-deductions` / `:cit-base-deductions` vec
(distinct from ABIL but mechanically identical — ordinary deduction
against any income). Add a test case: depreciable property with
proceeds < NBV (e.g. proceeds 30k, NBV 40k, dep-taken 60k → terminal
loss 10k, zero capital, zero recapture).

**Impact.** A consumer disposing depreciable property at a loss
silently gets the WRONG tax treatment — either no deduction at all
(when no other capital gains exist) or capital-pool offset (which
denies the deduction-against-ordinary-income that s.20(16) grants).
Material under-deduction in a common case (rental-real-estate
buildings sold below NBV; equipment salvaged below NBV; the
`s.13/s.20(16)` terminal-loss case is in every CA tax textbook).

---

## §3. P1 — must address within the next CA work-cycle

### P1-1. PUP $1,000 floor + non-deductible loss NOT enforced

**File**: `modules/l10n-ca/src/kontor/l10n_ca/cgt_statute.clj:82-87` +
`cgt_provider.clj` (no PUP branch).

**What's wrong.** The statute file installs a
`CA.CGT.pup-floor` parameter with value $1,000 and the s.46(1)
citation, but the provider NEVER reads it. The provider also doesn't
deny loss deductibility for `:asset-class :ca-personal-use` disposals.
Result: a PUP disposal flows through the default capital lane —
proceeds and basis are taken at face (no $1,000 floor on either
side), and any loss reduces the period's capital-gain pool (which
s.46(1)(c) says it must NOT do).

ITA s.46(1) requires:
1. **ACB floor**: greater of actual ACB and $1,000.
2. **Proceeds floor**: greater of actual proceeds and $1,000.
3. **Loss denial**: "any loss from the disposition of personal-use
   property of a taxpayer is nil" per s.46(1)(c) — except for LPP
   under s.41.

The encoded parameter is informational only. No effective enforcement.

**Authority.** [ITA s.46](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-46.html);
CRA T4037 Capital Gains guide ¶"Personal-use property."

**Recommended fix.** Add a `pup?` predicate (`asset-class
∈ {:ca-personal-use, :ca-listed-personal-property}`) and a PUP branch
in `classify`: floor proceeds and basis at the parameter's
`:pup-floor` before computing gain; for `:ca-personal-use` only (not
LPP), if the resulting gain is negative, drop the disposal entirely
(line-item `:role :pup-loss-denied`). Add tests: (a) PUP with proceeds
$800 + basis $300 → gain = $0 (both floored to $1,000 → $1,000 − $1,000
= 0); (b) PUP with proceeds $500 + basis $1,500 → loss denied
(contribution to pool = 0).

**Impact.** Two failure modes: (a) PUP gains under-stated when actual
proceeds ≤ $1,000 (the floor would make ACB ≥ proceeds → no taxable
gain, but the unenforced version computes a small gain from the
actual numbers); (b) PUP losses incorrectly offsetting other capital
gains (s.46(1)(c) prohibits this; the unenforced version allows it).
Common for personal-property liquidations (estate dispositions, art,
collectibles below the LPP threshold).

---

### P1-2. LPP s.41 compartment-wall NOT enforced

**File**: `cgt_provider.clj:103-119` (asset-class vocab includes
`:ca-listed-personal-property`); no LPP-specific classification branch.

**What's wrong.** ITA s.41 — Listed Personal Property — has its own
compartment: LPP losses offset ONLY LPP gains within the year, with
a 3-year carryback / 7-year carryforward against LPP gains only
(distinct from the general s.111(1)(b) net-capital-loss bucket which
gets 3-yr back + indefinite forward, against future taxable cap gains
of ANY kind). The provider's `:ca-lpp-loss` vocab keyword is
documented in `loss-buckets` (`cgt_provider.clj:144-146`) but the
classification pipeline never gates on it — an LPP loss just flows
through the default lane and offsets any capital gain in the pool.

**Authority.** [ITA s.41](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-41.html)
("Listed personal property losses"); [CRA T4037 §"Listed personal
property"](https://www.canada.ca/en/revenue-agency/services/forms-publications/publications/t4037/capital-gains.html).

**Recommended fix.** Two-tier separation: classify LPP disposals
into a separate intermediate bucket; aggregate them within an
inner-pool (`:lpp-pre-inclusion` = Σ LPP capital-pre-inclusion,
floored at 0); add the inner-pool's POSITIVE residual to
`:net-capital` (so LPP gains DO contribute to the period's taxable
amount), and emit the LPP residual loss back via `:line-items`
+ `:jurisdiction-specific-codes :lpp-loss-carryforward` for the
consumer's carry ledger. Add tests: (a) LPP gain + non-LPP loss →
the LPP gain is NOT offset by the non-LPP loss (each lane is sealed);
(b) LPP loss + non-LPP gain → the LPP loss is dropped from the pool
and surfaces in carryforward.

**Impact.** Mixed-bucket netting against statute — a taxpayer with
an LPP loss + a non-LPP capital gain in the same year currently sees
the loss reduce the gain (free deduction); the correct treatment is
the LPP loss must wait for a future LPP gain. Material when the
period has a substantial gain alongside a small LPP loss (art /
collectibles), or vice versa.

---

### P1-3. Only 2 of 6 rollover regimes have test coverage

**File**: `cgt_provider.clj:121-131` (vocab — six elective-regime
keywords) + `cgt_provider.clj:171-183` (`rollover-elected?` checks
six) + `cgt_provider_test.clj:277-306` (only `:ca-§85-rollover` and
`:ca-§73-spousal` exercised).

**What's wrong.** The `rollover-elected?` predicate correctly enumerates
all six rollover regimes: `:ca-§85-rollover`, `:ca-§86-reorganisation`,
`:ca-§51-conversion`, `:ca-§87-amalgamation`, `:ca-§44-replacement`,
`:ca-§73-spousal`. Tests exercise §85 (`section-85-rollover-excludes-
the-gain`) and §73 (`section-73-spousal-rollover-excludes-the-gain`).
The other four — §86 share-for-share, §51 convertible-property,
§87 amalgamation, §44 replacement-property — have no test, no
worked example, and the provider's behaviour with them is
unverified.

**Authority.** [ITA s.85](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-85.html),
[s.86](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-86.html),
[s.51](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-51.html),
[s.87](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-87.html),
[s.44](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-44.html),
[s.73](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-73.html).

**Recommended fix.** Four small test cases, one per regime, each
asserting `(== 0M (:taxable-capital s))` and a
`{:role :rollover-deferred}` line-item. (Each regime currently runs
through the same code path, so the test value is reproducibility, not
new logic.) Bonus: a `:rollover-amount` test exercising the
`realized-gain` formula's `(- p b r)` subtraction (deferred amount
SHRINKS the recognized gain rather than excluding the whole disposal,
matching the partial-rollover s.44 / s.85 election cases).

**Impact.** Tests document intent; absent tests for 4-of-6 regimes
means future refactors (adding `:ca-§85-1-stop-loss`, fixing the
rollover-amount semantics, adding the s.44 12-month replacement window
check) can break these silently. Note 127 §1.8 lists each regime with
the statutory section + the citing form (T2057, T2058, T1170,
T2054); these forms eventually need to appear in `:disposal/audit-doc`
during recognition.

---

### P1-4. `:ca-§38a1-listed-donation` zero-inclusion lane unverified

**File**: `cgt_provider.clj:193-199` (predicate) +
`cgt_provider.clj:317-323` (classification branch); no test.

**What's wrong.** The provider implements the s.38(a.1) zero-inclusion
lane for in-kind donations of publicly listed securities to qualified
donees (`charitable-public-share-zero?` predicate; classification
branch returns zero `:capital-pre-inclusion`, line-item
`:charitable-public-share-zero-inclusion`). The behaviour is correct
per [s.38(a.1)](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-38.html)
and the [CRA guide "Capital gains realized on gifts of certain
capital property"](https://www.canada.ca/en/revenue-agency/services/tax/individuals/topics/about-your-tax-return/tax-return/completing-a-tax-return/deductions-credits-expenses/line-34900-donations-gifts/capital-gains-realized-on-gifts-certain-capital-property.html).
But there is NO test for it — the §11 test only covers superficial-
loss flagging, the §4 test only covers principal residence.

**Authority.** ITA s.38(a.1); CRA "Capital gains realized on gifts
of certain capital property" (Form T1170).

**Recommended fix.** Add a §12 test:

```clojure
(deftest charitable-public-share-zero-inclusion
  (testing "in-kind donation of listed securities → zero inclusion (s.38(a.1))"
    (let [conn (fresh)]
      (record! conn {:external-id "gift-listed-rbc"
                     :acquired-on #inst "2018-01-01"
                     :disposed-on #inst "2026-09-15"
                     :asset-class :ca-public-shares
                     :proceeds {:amount 150000M :commodity cad}
                     :basis    {:amount 50000M  :commodity cad}
                     :exemption-claimed #{:ca-§38a1-listed-donation}})
      (let [facts (run-provider conn :individual p2026)
            s     (summary facts)]
        (is (== 0M (:gross-capital s))
            "the $100k gain is computed but zero-included per s.38(a.1)")
        (is (== 0M (:taxable-capital s)))
        (is (some #(= :charitable-public-share-zero-inclusion (:role %))
                  (:line-items (only-component facts))))))))
```

**Impact.** A common high-value transaction — a taxpayer donating
appreciated listed shares to a registered charity is a textbook
tax-planning move. The lane is implemented but unverified;
a regression hiding in the classify-cond ordering (a new earlier
branch swallowing the disposal) would slip past CI.

---

## §4. P2 — followup polish

### P2-1. `:disposal/holding-period` not stamped `:n-a` per note 127 §1.6

**File**: `cgt_provider.clj` (no holding-period stamping);
`cgt_provider_test.clj` (no `:holding-period` in any `record!` call).

**What's wrong.** Note 127 §1.6 says explicitly: "the `:disposal/
holding-period` enum is `:n-a` for every CA disposal." The CA
companion writer / consumer SHOULD stamp `:n-a` so the bitemporal
audit trail records the law-as-it-stood (CA had no ST/LT distinction
on the disposal date). Today the tests just omit the field and the
schema attr is left absent — informationally equivalent to "we never
looked," not "the holding-period rule was non-applicable."

**Recommended fix.** Either (a) add a CA-companion helper
`record-ca-disposal!` that stamps `:n-a` by default and recommend it
in the cgt_provider docstring, or (b) add a one-line classifier note
in the provider's `classify` that stamps `:holding-period :n-a` on
the line-item if not already present, or (c) just document the
convention in the cgt_provider ns docstring and leave the schema
absent. Cosmetic; matches note 127 wording.

---

### P2-2. `:cnil-balance` documented but silently ignored

**File**: `cgt_provider.clj:30-78` (the long ns docstring on LCGE
inputs); no consumption of `:cnil-balance` from `:inputs`.

**What's wrong.** Note 127 §3.2 explicitly anticipates a per-taxpayer
`:cnil-balance` input that should REDUCE the available LCGE
(s.110.6(1) "annual gains limit" CNIL grind). The task brief says:
"CNIL reduction NOT in v1 (consumer responsibility per note 127)." But
the provider's docstring + the `:lcge-claimed-prior` input description
don't mention that `:cnil-balance` is unsupported; a consumer might
supply it and silently get ignored. Defensive-programming gap.

**Recommended fix.** Add one paragraph in the LCGE section of the
provider docstring (`cgt_provider.clj:67-78`) noting that the CNIL
grind under s.110.6(1) is the consumer's responsibility to compute
and SUBTRACT from `:lcge-claimed-prior` before passing it in.
Possibly add an `:inputs :cnil-balance` warning (log or throw on
v1 — throw is safer; "you supplied :cnil-balance but the v1 provider
does not consume it; subtract it manually from
:lcge-claimed-prior").

---

### P2-3. `:disposal/state :recorded` is included in the pool (only `:voided` is filtered)

**File**: `modules/disposal/src/kontor/disposal/source.clj:74-86`
(the canonical impl filters `[(not= ?st :voided)]` only); CA provider
delegates.

**What's wrong.** The `DatahikeDisposalSource` includes both
`:recorded` (not yet posted to GL) AND `:recognized` (posted)
disposals — only `:voided` is excluded. For tax computation,
`:recorded` is `:disposal/realizing-tx`-less; if the consumer is
mid-flow and a disposal is recorded but the realising-tx hasn't been
posted yet, the CGT provider counts it. This may be correct (the law
attaches to the disposal event date, not the GL-posting date) — but
the behaviour is inherited from the disposal companion, not chosen by
the CA provider, and is worth confirming as the right semantics
per note 127. (Note 127 §3.1 mentions §5.1: the GL component is at
recognition; the CGT component is at the disposal event. The current
behaviour matches that, so this is informational.)

**Recommended fix.** Either (a) accept the current behaviour and
document it in the CA provider docstring (the period-tax-facts call
reads disposals at any post-recorded state — the law fires on the
event date not the GL date) OR (b) parameterise the source to filter
by `:state` if a stricter "must-be-recognized-to-count" semantics is
needed by some consumer. Probably leave as-is; document the choice.

---

### P2-4. Statute citation gaps in the ns docstring

**File**: `cgt_statute.clj:1-52` (the docstring); `cgt_provider.clj:1-92`
(the long provider docstring).

**What's wrong.** Minor citation lacunae the statute docstring could
flesh out: (a) the ABIL parameter cites s.38(c) but not s.39(1)(c)
(the actual definition of "business investment loss") or s.50(1) (the
deemed-disposition trigger that often actualises an ABIL); (b) the
PUP-floor parameter cites s.46(1) but not s.46(2) where LPP gets its
distinct floor + carry treatment; (c) the LCGE parameter
URL is `i-3.3/section-110.6.html` (lowercase i) while the inclusion-
rate URL is `I-3.3/section-38.html` (capital I) — both work on
laws-lois.justice.gc.ca, but consistency aids audit-script scraping.

**Recommended fix.** One-line additions to the statute ns docstring's
parameter blocks; capitalise the act-code letter uniformly.

---

### P2-5. `:tax-concept :lifetime-cap` not wired to the LCGE parameter

**File**: `cgt_statute.clj:75-87` (LCGE parameter) +
`src/kontor/statute.clj:571-577` (the `:lifetime-cap` concept).

**What's wrong.** ADR-101's starter concept catalogue includes
`:lifetime-cap` ("Cumulative limit across the taxpayer's lifetime on
a preferential rate or exclusion. UK BADR (£1M), US §1202 QSBS"). The
CA LCGE is exactly this shape; the parameter could carry a
`:parameter/concept-iri` or a `:parameter/concept` ref pointing at
`:lifetime-cap` so cross-jurisdiction comparisons surface in the
substrate. Today the LCGE parameter points at the laws-lois URL only.

**Recommended fix.** Add `:parameter/concept :lifetime-cap` (or
encode via the `:concept-iri` if the chosen seam is the IRI) on the
`CA.CGT.lcge-cap` parameter. Tiny graph-walk benefit; ADR-090
discipline points the way.

---

### P2-6. Audit-doc form references absent

**File**: `cgt_provider.clj:480-496` (the `line-items'` builder).

**What's wrong.** The summary line items don't carry the form code
the CRA expects for each lane. T657 for the LCGE deduction (Form
T657 "Calculation of Capital Gains Deduction"), T1170 for the
charitable in-kind donation (Form T1170 "Capital Gains on Gifts of
Certain Capital Property"), Schedule 3 line 12700 for the headline
taxable capital gain, Schedule 6 for corporate dispositions. The note
127 §5.5 calls these out as "audit-doc" attachments.

**Recommended fix.** Augment the `:role :lcge-applied-total` line
with `:form "T657"`; the `:role :charitable-public-share-zero-
inclusion` with `:form "T1170"`; the `:role :summary` with
`:form (if individual? "T1-Schedule-3" "T2-Schedule-6")`. Surface
the form code so a downstream `kontor.audit-doc` consumer can scan
and verify the right form is attached.

---

## §5. Verified-correct items (no finding)

This section catalogues areas the audit checked and confirms are
correct, so the reviewer's future self knows what NOT to re-audit.

- **50% inclusion rate parameter** — value, effective-date, citation
  treatment of the cancellation, single-row (no tiered bracket) all
  match post-2025-03-21 reality. `cgt_statute.clj:99-102`.
- **LCGE 2026 = $1,275,000 indexed value, with 2025 = $1,250,000
  predecessor row** — value matches TaxTips/Shajani/InsightCPA/BMO
  multi-source consensus; the half-open `:effective-from/-until`
  windows are correct. `cgt_statute.clj:113-122`.
- **ABIL routing to `:pit-base-deductions` (+ `:cit-base-deductions`
  for corp) — not to the capital pool** — correctly implements
  s.38(c)'s "against any income" semantics. `cgt_provider.clj:325-336`
  + `cgt_provider_test.clj:251-271`.
- **CCA recapture arithmetic for the positive-gain case** —
  `recapture = min(proceeds, capital-cost) − NBV`, `capital =
  max(0, proceeds − capital-cost)`. Verified against `cgt_provider_test`
  cases cca-1 ($60k recapture + $25k taxable cap gain) and cca-2
  ($30k recapture, $0 capital). `cgt_provider.clj:236-263`.
- **LCGE pool — single-period consumption, floored at zero,
  iteration-order allocation** — three tests (full-consumption,
  prior-claim-reduces, exhausted) all pass with the correct numbers.
  `cgt_provider.clj:369-400` + `cgt_provider_test.clj:141-205`.
- **Corporate LCGE hard-zero** — `kind :corporation` path bypasses
  the parameter lookup and uses `0M`. Matches s.110.6(2)(a)(i)
  "individual (other than a trust)." `cgt_provider.clj:540-542` +
  `cgt_provider_test.clj:382-397`.
- **`:elective-regime` rollover gate excludes the gain entirely** —
  `principal-residence-exempt?` + `rollover-elected?` correctly
  short-circuit before the LCGE/recapture/ABIL/default branches.
  `cgt_provider.clj:298-314`.
- **Principal-residence requires BOTH `:residence? true` AND
  `:ca-principal-residence` elective regime** — the
  `principal-residence-exempt?` predicate enforces both. Test
  `residence-flag-without-regime-still-taxable`
  (`cgt_provider_test.clj:232-245`) verifies the regime is required.
- **Superficial-loss flag denial** — flagged losses are dropped from
  the pool (not added to `:gross-capital`). Matches note 127 §1.7's
  "trust the consumer-supplied flag; full detection is v2"
  deferral. `cgt_provider.clj:289-296` + `cgt_provider_test.clj:438-453`.
- **Voided-disposal filtering** — handled by the DisposalSource
  companion (`disposal/source.clj:74-86` filters `[(not= ?st
  :voided)]`); test `voided-disposals-excluded`
  (`cgt_provider_test.clj:420-432`) verifies the end-to-end.
- **CDA scope discipline** — provider files contain ZERO CDA
  implementation code; only docstring references. Correctly defers
  CDA crediting + s.83(2) Form-T2054 capital-dividend payments to
  `kontor.book` per ADR-095 + note 127 §3.7.
- **Worked example A** — Singh QSBC sale, $1,275,000 LCGE applied,
  $112,450 taxable capital gain — matches note 127 §2 to the dollar
  AND matches an independent hand-derivation against the cancellation
  -corrected statute.
- **Worked example B** — OpsCo RBC sale, $60,000 taxable capital
  gain into `:cit-base-additions` — matches note 127 §2 to the
  dollar.

---

## §6. Substrate-level finding — `:jurisdiction-specific-codes` fan-out
convention deserves an addendum / kernel helper

**File**: `cgt_provider.clj:464-516` (the `component` builder) +
sibling providers in `modules/l10n-at`, `modules/l10n-cn`,
`modules/l10n-us` (all emit the same key shape).

**What's wrong / what's worth promoting.** The CA CGT provider
emits its results via four keys nested under
`:jurisdiction-specific-codes`:

- `:pit-base-additions [<bigdec> ...]` (individual disposals' taxable
  capital gain + CCA recapture)
- `:pit-base-deductions [<bigdec> ...]` (ABIL deduction; + future
  terminal-loss per P0-1)
- `:cit-base-additions [<bigdec> ...]` (corporate equivalent)
- `:cit-base-deductions [<bigdec> ...]` (corporate equivalent)

The same key family is now used by:

- `modules/l10n-us/cgt_provider.clj` (originator —
  ST: `:pit-base-additions`, LT: own schedule + `:cit-base-additions`)
- `modules/l10n-at/cgt_provider.clj` (note 134 — uses
  `:pit-base-deductions {:§28-vermietung [...]}` AND
  `:cit-base-deductions` for §10 KStG INVERSION case)
- `modules/l10n-cn/cgt_provider.clj` (note 133 — uses
  `:cit-base-deductions`)
- `modules/l10n-ca/cgt_provider.clj` (this work)

That's **four jurisdictions** already; AU/JP/BR/MX/IN/UK/FR/DE are
likely to follow. The convention is settled by *de facto* repetition.
But:

1. **No kernel-side helper exists.** Every provider open-codes the
   `(cond-> (assoc :pit-base-additions [...]) (= kind :corp) (assoc
   :cit-base-additions [...]))` ceremony.
2. **No downstream consumer reads the keys.** The kernel PIT
   (`src/kontor/personal_income_tax.clj`) and CIT
   (`src/kontor/corporate_income_tax.clj`) providers consume
   `:inputs :base-transform` only — NOT `:pit-base-additions`. The
   consumer has to manually thread the CGT component's
   `:jurisdiction-specific-codes :pit-base-additions` into the PIT
   provider's `:inputs :base-transform` on the same period; if they
   forget, the gain silently never gets taxed.
3. **No spec or schema validation.** A typo (`:pit-base-addition`
   without the trailing `-s`) goes undetected; the value space
   (vector of bigdec vs map vs scalar) varies across providers (AT uses
   `{:§28-vermietung [...]}` — a tagged map; the others use a plain
   vector).

**Recommended fix.** ADR-103 addendum + an upgrade in one of three
shapes:

- **A (cheapest)** — add a `kontor.cgt/fold-into-pit-base-transform
  [cgt-facts]` and `fold-into-cit-base-transform [cgt-facts]`
  convenience helper in the kernel. Consumer threads
  `(:inputs (-> {} (assoc :base-transform (cgt/fold-into-pit-base-transform
  cgt-facts))))` into the PIT call. Validates shape on the way through;
  one place to migrate when AT's tagged-map shape gets normalised.
- **B (more typed)** — promote the four keys to dedicated optional
  top-level fields on `TaxReturnFacts` (`:base-additions
  {:pit [<money>] :cit [<money>]} :base-deductions {:pit ... :cit
  ...}`); kernel ENFORCES it. Stronger contract; bigger lift.
- **C (kernel-aware PIT/CIT)** — teach the kernel PIT + CIT
  providers to auto-consume a list of upstream `TaxReturnFacts` from
  ctx and roll them into the base transform. Most plumbing, lowest
  consumer burden.

A is the minimum that pays for itself; B is the right long-term
shape; C is over-reach for v1.

Also: rename the per-provider opaque-but-conventional `:lane :ca-cgt`
to something kernel-typed (e.g., `:cgt-lane :ca` or
`:jurisdiction-lane :ca-cgt`) so the fanned-out components from
different jurisdictions in a multi-entity consolidation can be
distinguished without parsing strings.

---

## §7. Sources

CA statute (laws-lois.justice.gc.ca):
- [ITA s.13 — recapture of CCA + UCC machinery](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-13.html)
- [ITA s.20(16) — terminal loss](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-20.html)
- [ITA s.38 — taxable capital gain (1/2 inclusion); s.38(a.1) zero inclusion for listed-share donations; s.38(c) ABIL half-rate](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-38.html)
- [ITA s.39 — meaning of capital gain; s.39(1)(b)(i) depreciable exclusion; s.39(1)(c) BIL](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-39.html)
- [ITA s.40 — capital gain computation; s.40(2)(b) principal residence formula; s.40(2)(g)(i) superficial loss denial](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-40.html)
- [ITA s.41 — LPP loss compartment + 7-yr carryforward](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-41.html)
- [ITA s.44 — replacement-property rollover](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-44.html)
- [ITA s.46 — PUP + $1,000 floor + LPP definition](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-46.html)
- [ITA s.50 — debts deemed disposed (ABIL trigger)](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-50.html)
- [ITA s.51 — convertible-property rollover](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-51.html)
- [ITA s.54 — definitions](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-54.html)
- [ITA s.73 — spousal rollover](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-73.html)
- [ITA s.83 — capital dividend election](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-83.html)
- [ITA s.85 — transfer-to-corp rollover (T2057 election)](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-85.html)
- [ITA s.86 — share-for-share within same corp](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-86.html)
- [ITA s.87 — amalgamation rollover](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-87.html)
- [ITA s.89 — capital dividend account definition](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-89.html)
- [ITA s.110.6 — LCGE (QSBC + QFP/QFishing)](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-110.6.html)
- [ITA s.111 — losses, including s.111(1)(b) net-capital-loss 3-yr back/indefinite forward](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-111.html)
- [ITA s.117.1 — indexation of bracket amounts (drives LCGE 2026 indexation)](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-117.1.html)

CRA folios + guides (canada.ca):
- [Income Tax Folio S3-F4-C1 — General Discussion of CCA (terminal-loss ¶ 1.92-1.96)](https://www.canada.ca/en/revenue-agency/services/tax/technical-information/income-tax/income-tax-folios-index/series-3-property-investments-savings-plans/series-3-property-investments-savings-plans-folio-4-capital-cost-allowance/income-tax-folio-s3-f4-c1-general-discussion-capital-cost-allowance.html)
- [Income Tax Folio S4-F8-C1 — Business Investment Losses (ABIL)](https://www.canada.ca/en/revenue-agency/services/tax/technical-information/income-tax/income-tax-folios-index/series-4-businesses/series-4-businesses-folio-8-losses/income-tax-folio-s4-f8-c1-business-investment-losses.html)
- [Income Tax Folio S1-F3-C2 — Principal Residence](https://www.canada.ca/en/revenue-agency/services/tax/technical-information/income-tax/income-tax-folios-index/series-1-individuals/folio-3-family-unit-issues/income-tax-folio-s1-f3-c2-principal-residence.html)
- [CRA T4037 Capital Gains 2025 guide (PUP / LPP / superficial / LCGE coverage)](https://www.canada.ca/en/revenue-agency/services/forms-publications/publications/t4037/capital-gains.html)
- [CRA Line 9947 — Recaptured Capital Cost Allowance](https://www.canada.ca/en/revenue-agency/services/tax/businesses/topics/rental-income/completing-form-t776-statement-real-estate-rentals/line-9947-recaptured-capital-cost-allowance.html)
- [CRA Capital gains realized on gifts of certain capital property (s.38(a.1))](https://www.canada.ca/en/revenue-agency/services/tax/individuals/topics/about-your-tax-return/tax-return/completing-a-tax-return/deductions-credits-expenses/line-34900-donations-gifts/capital-gains-realized-on-gifts-certain-capital-property.html)
- [CRA Schedule 3 — Capital gains for T1](https://www.canada.ca/en/revenue-agency/services/tax/individuals/topics/about-your-tax-return/tax-return/completing-a-tax-return/personal-income/line-12700-capital-gains/completing-schedule-3.html)

Government of Canada releases:
- [PMO 2025-03-21 — "Prime Minister Mark Carney cancels proposed capital gains tax increase"](https://www.pm.gc.ca/en/news/news-releases/2025/03/21/prime-minister-mark-carney-cancels-proposed-capital-gains-tax-increase)
- [Department of Finance 2025-01-31 deferral announcement](https://www.canada.ca/en/department-finance/news/2025/01/government-of-canada-announces-deferral-in-implementation-of-change-to-capital-gains-inclusion-rate.html)

Commentary / cross-checks:
- [TaxTips.ca — LCGE history table](https://www.taxtips.ca/smallbusiness/lifetime-capital-gains-exemption.htm) (LCGE 2026 = $1,275,000 confirmation)
- [Shajani CPA — LCGE 2026 explainer](https://shajani.ca/lifetime-capital-gains-exemption-lcge-2026-the-1-25-million-limit-explained/) (2026 = $1,275,000 confirmation)
- [Insight CPA — Capital Gains Tax Changes 2026](https://insightscpa.ca/capital-gains-tax-canada-2026-small-business-guide/)
- [WealthNorth — LCGE Canada $1.25M small-business shares](https://wealthnorth.ca/taxes/lifetime-capital-gains-exemption-canada/)
- [BMO Private Wealth — LCGE demystified](https://privatewealth-insights.bmo.com/en/insights/wealth-planning-and-strategy/lifetime-capital-gains-exemption-demystified-strategies-for-maximizing-your-gains/)
- [Scotia Wealth — Cancellation of proposed inclusion-rate increase (2025-04-07)](https://enrichedthinking.scotiawealthmanagement.com/2025/04/07/cancellation-of-the-proposed-capital-gains-inclusion-rate-increase/)
- [Lexology — Cancellation of Canadian capital gains inclusion rate increase](https://www.lexology.com/library/detail.aspx?g=46c1a57f-a1e5-4f35-98a8-c1afec1a77df)
- [Marcil Lavallée — Sale of building used for business or rental: possible denial of terminal loss](https://marcil-lavallee.ca/en/bulletin/sale-of-building-used-for-business-or-rental-possible-denial-of-terminal-loss/)
- [Manulife — Superficial losses](https://www.manulifeim.com/retail/ca/en/viewpoints/tax-planning/superficial-losses)

kontor substrate cited:
- `modules/l10n-ca/src/kontor/l10n_ca/cgt_statute.clj:99-128` —
  parameter values audited.
- `modules/l10n-ca/src/kontor/l10n_ca/cgt_provider.clj:236-263` — the
  cca-split P0-1 finding.
- `modules/l10n-ca/src/kontor/l10n_ca/cgt_provider.clj:369-400` — the
  LCGE pool consumption.
- `modules/l10n-ca/src/kontor/l10n_ca/cgt_provider.clj:464-516` — the
  `:jurisdiction-specific-codes` fanout (§6 substrate finding).
- `modules/l10n-ca/test/kontor/l10n_ca/cgt_provider_test.clj:141-205`
  — the three LCGE tests + the Singh worked-example reproduction.
- `modules/disposal/src/kontor/disposal/source.clj:74-86` — the
  voided-only filter (P2-3).
- `src/kontor/period_tax_provider.clj:44-61` — the closed
  `period-tax-kinds` enum (`:capital-gains-tax` already in).
- `src/kontor/personal_income_tax.clj:62-118` /
  `src/kontor/corporate_income_tax.clj:38-76` — the upstream PIT/CIT
  providers that DON'T consume the `:pit-base-additions` /
  `:cit-base-additions` keys (§6 substrate-finding context).
- `src/kontor/statute.clj:571-577` — `:lifetime-cap` concept (P2-5).
- `modules/l10n-us/src/kontor/l10n_us/cgt_provider.clj` (originator of
  the `:pit-base-additions` / `:cit-base-additions` convention).
- `modules/l10n-at/src/kontor/l10n_at/cgt_provider.clj` (uses
  `:cit-base-deductions` and `:pit-base-deductions` — establishing
  precedent for the CA-added keys).
- `modules/l10n-cn/src/kontor/l10n_cn/cgt_provider.clj` (also uses
  `:cit-base-deductions`).
- `doc/research/127-ca-cgt-fit.md` — the research-before this audits
  against.
- `doc/research/126-ca-cit-baseline-review.md` — sibling baseline-
  review (CIT side; provides the structure template).

---

End of note 137.
