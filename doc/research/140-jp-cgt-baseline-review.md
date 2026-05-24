---
date: 2026-05-24
title: 140 — JP CGT provider — baseline code review against NTA + research note 115
audience: maintainer; the agent that drafted modules/l10n-jp/cgt_*; ADR-103 reviewers
status: review-after of `modules/l10n-jp/src/kontor/l10n_jp/cgt_*` per the rhythm of ADR-037
related: 115 (JP CGT fit), 120 (DE CIT baseline review template), ADR-101, ADR-102, ADR-103, ADR-099
---

# 140 — JP CGT provider: baseline review

This note audits the freshly-landed JP CGT provider against (a) note 115's
substrate-fit assessment, (b) NTA / e-gov / 復興措置法 primary sources, and
(c) the kontor substrate conventions (ADR-099 `PeriodTaxProvider`,
ADR-101 statute-as-data, ADR-102 disposal substrate).

The implementation is broadly correct: the Jan-1 measurement rule is
present and the boundary tests fire, the five regimes fan out into
components, the 復興 2.1 % surtax is encoded as a statute provision and
gated on `:pass :national`, the corporate-CGT-folds-into-CIT mechanic
follows the US CGT precedent, and the §31-3 progressive rate is
correctly encoded. **Two regression-level findings sit on top of that
correct skeleton**: P0-1 (§35 lane restriction is over-narrow and
contradicts NTA tsutatsu 35-2) and P1-1 (`:gross-liability` / `:schedule`
component-shape conflation that misrepresents how `:liability` was
derived). All other findings are P2 polish.

## Files reviewed

- `/home/christian-weilbach/Development/kontor/modules/l10n-jp/src/kontor/l10n_jp/cgt_statute.clj` (304 lines)
- `/home/christian-weilbach/Development/kontor/modules/l10n-jp/src/kontor/l10n_jp/cgt_provider.clj` (524 lines)
- `/home/christian-weilbach/Development/kontor/modules/l10n-jp/test/kontor/l10n_jp/cgt_provider_test.clj` (512 lines)
- `/home/christian-weilbach/Development/kontor/doc/research/115-jp-cgt-fit.md`
- Cross-reference: `/home/christian-weilbach/Development/kontor/modules/l10n-us/src/kontor/l10n_us/cgt_provider.clj` (sibling provider, similar shape)

---

## Bottom-line triage

| Severity | Count | Summary |
|---|---|---|
| **P0 (ship blocker)** | 1 | §35 lane restriction is over-narrow → contradicts NTA tsutatsu 35-2 → wrong tax for short-term residence sales |
| **P1 (must-fix soon)** | 3 | `:gross-liability` + `:schedule` component-shape conflation; statute citation names wrong law; §35 + carry-in line-item bug (`(- §35-amount)` for non-claimed) |
| **P2 (polish)** | 6 | constructor `:id` ignored in `:provenance`; `:residual-loss` shape; `nat-surtax-ctx` carries `:regime` twice; under-tested §35+§31-3 above-¥60M case; tsutatsu numbers absent on most parameters; `:provenance` lacks `:as-of` formal nesting; `(reduce + 0M ...)` numeric idiom |

---

## P0 — Ship blockers

### P0-1. §35 ¥30M residence deduction is restricted to the §31-3 lane → tax wrong for short-term and plain-long residence sales

**Where**

- `cgt_provider.clj:325-328`:

  ```clojure
  §35?           (and (= regime :jp-real-estate-long-residence)
                      (§35-deduction-claimed? regime-disposals))
  after-§35      (apply-§35-deduction gross-gain §35-amount §35?)
  ```

- The companion test at `cgt_provider_test.clj:262-283` ENCODES this
  restriction as expected behaviour:

  ```clojure
  (is (== 50000000M (-> re :base :amount))
      "§35 deduction NOT applied without :elective-regime :jp-§31-3")
  ```

**What NTA says** (definitive — verified live 2026-05-24):

- **タックスアンサー No.3302 「マイホームを売ったときの特例」** ([nta.go.jp/3302](https://www.nta.go.jp/taxes/shiraberu/taxanswer/joto/3302.htm)):
  「**所有期間の長短に関係なく**譲渡所得から最高3,000万円まで控除ができる」
  ("regardless of holding-period length, up to ¥30M can be deducted
  from capital-gains income"). **No holding-period requirement.**
- **タックスアンサー No.3305 「マイホームを売ったときの軽減税率」**
  ([nta.go.jp/3305](https://www.nta.go.jp/taxes/shiraberu/taxanswer/joto/3305.htm)):
  「居住用財産を譲渡した場合の3,000万円の特別控除の特例**と**軽減税率の特例は、
  重ねて受けることができます」 — §35 and §31-3 are **combinable**, not
  mutually exclusive. The 軽減税率 (§31-3) is a separate election on
  top of §35.
- **租税特別措置法第35条第1項** ([zeiken.co.jp/35](https://www.zeiken.co.jp/hourei/HHSOZ000000/35.html)):
  the statute applies the ¥30M deduction to BOTH 長期譲渡所得 AND
  短期譲渡所得 of qualifying residence — explicitly. The 5-yr-or-less
  short-term case is in-scope.
- **措置法通達 35-2, 35-6** (NTA's own guidance numbers, cited on No.3302
  page): the deduction applies independently of the holding-period
  bucket and is gated by occupancy / 3-year-vacate / no-related-party /
  prior-2-year exclusion checks, NOT by holding period.

**Why the current code is wrong**

- A taxpayer who sells a residence held 4 years (short-term, §32 lane,
  39.63 %) and claims §35 will, per the NTA, deduct ¥30M from gross
  gain BEFORE applying 39.63 %. The current provider drops `§35?` to
  false in the `:jp-real-estate-short` lane → over-taxes by
  `min(gain, ¥30M) × 0.3963` ≈ up to ¥11.9M of over-collection.
- A taxpayer who sells a residence held 7 years (long-term, §31 lane,
  20.315 %, NOT §31-3 because <10 yrs) and claims §35 hits the same
  bug → over-taxes by `min(gain, ¥30M) × 0.20315` ≈ up to ¥6.1M.
- The cgt_provider docstring at `cgt_provider.clj:28-30` says "§35 ¥30M
  principal-residence deduction ... applies as a base deduction BEFORE
  the rate; signalled by `:exemption-claimed :jp-§35-residence`" — and
  THIS docstring is consistent with the statute. The code at
  `:325-328` then narrows that to the long-residence lane only, in
  direct contradiction to its own docstring.

**Note 115's posture**

The task prompt mentions "agent restricted §35 firing to §31-3 lane
only. Per note 115 §1.5 + nta.go.jp, §35 has NO holding-period
requirement". Note 115 §1.5 (line 122-126) is explicit:

> **Principal-residence ¥30 M deduction** ... **no holding-period
> requirement**; cannot be used in consecutive years; cannot be combined
> with a housing-loan tax credit for 3 years.

And §2 example B (line 174-189) walks through Takahashi's case which
combines §35 (¥30M) with §31-3 (14.21%) — confirming combinability, not
exclusivity.

**Fix shape**

The §35 gating in `regime-component` (cgt_provider.clj:325) should be:

```clojure
§35?  (and (#{:jp-real-estate-short
              :jp-real-estate-long
              :jp-real-estate-long-residence} regime)
           (§35-deduction-claimed? regime-disposals))
```

(All three real-estate-residence-sale lanes; equity lanes are out of
scope because §35 is a residence-only relief.)

Additional: the `:disposal/residence?` flag should also gate (a
non-residence real-estate sale must not claim §35 even if the consumer
incorrectly stamps `:jp-§35-residence` on the disposal). The provider
should drop `:jp-§35-residence` on non-residence disposals, ideally
raising an `ex-info` rather than silently dropping (defensive — the
consumer's data is wrong).

Tests to add:
1. §35 + §32 (short-term, 39.63%) → deduct ¥30M, then 39.63% on remainder.
2. §35 + §31 (plain long, 20.315%, <10 yrs) → deduct ¥30M, then 20.315%.
3. Existing §35-only test at `:262` should INVERT its assertion: the
   ¥50M base should become ¥20M (after §35) and the liability should
   drop accordingly.

**Risk if shipped as-is**: Real-world tax-determination bug. Any JP
individual using kontor for a sub-10-year residence sale will be told
to pay too much. This is exactly the failure mode the per-stage
review-after rhythm exists to catch.

**Severity**: P0. Statute citation conflict + actively wrong tax.

---

## P1 — Must-fix soon

### P1-1. Component `:gross-liability` and `:schedule` shape conflation

**Where**: `cgt_provider.clj:370-376`:

```clojure
{:kind            :capital-gains-tax
 :authority       :jp-nta
 :base            (money/money net-base commodity)
 :schedule        national                                     ; <-- 1
 :gross-liability (money/money total-liability commodity)      ; <-- 2
 :liability       (money/money total-liability commodity)      ; <-- 3
 ...
 :surtaxes        (mapv #(select-keys % [:code :label :amount :provenance])
                        resolved)}
```

**What's wrong** (per `period_tax_provider.clj:86-94` — the component
spec):

> `:base         <Money>   the resolved taxable base`
> `:schedule     <data>    the kontor.tax-schedule that produced the gross`
> `:gross-liability <Money> base through the schedule, before credits`
> `:surtaxes      [...]    tax-on-tax surcharges added AFTER credits;`
> `                        :liability = gross − Σcredits + Σsurtaxes`

So per the spec:
- `:schedule` is the schedule that produced `:gross-liability` from
  `:base`. The current code stores ONLY the national schedule, but the
  computed `:gross-liability` = `national-gross + local-gross + 復興`
  is the result of three schedules + a surtax. Reproducing
  `:gross-liability` from `(apply-schedule :schedule :base)` is
  impossible — auditor's mental model breaks.
- `:gross-liability` should be PRE-surtax (just `national-gross +
  local-gross`), NOT include the 復興. The 復興 lives in `:surtaxes`,
  and the spec formula `:liability = gross − Σcredits + Σsurtaxes`
  then reconstructs `:liability` from `:gross-liability` +
  `:surtaxes`. With the current encoding, the spec formula
  double-counts: `total-liability + (復興 from :surtaxes) ≠
  total-liability`.

**Why it matters**: the `period_tax_provider/balance` helper, any
auditable explain-this-number report (ADR-091), and any downstream
`TaxReturnPostingBuilder` that respects the spec formula will all be
confused. The component validator `valid-return-facts?` only checks
`:kind` and `:liability`, so this passes the validator while violating
the component contract.

**Fix shape**

```clojure
(let [pre-surtax-liability (+ national-gross local-gross)]
  {:base            (money/money net-base commodity)
   :schedule        {:national national :local local}   ; or :sum-of [national local]
   :gross-liability (money/money pre-surtax-liability commodity)
   :liability       (money/money total-liability commodity)
   :surtaxes        ...  ; the 復興 surtax line; already correct
   ...})
```

Either (a) compose national + local into a `(ts/sum-of [national
local])` schedule and store that as `:schedule`, or (b) record both in
the `:schedule` slot as a map. The `tax_schedule/sum-of` constructor
exists exactly for this case (`tax_schedule.clj:312-317`).

If the team prefers, a third option: split the per-regime component
into TWO components — one for `:authority :jp-nta` (national + 復興)
and one for `:authority :jp-municipality` (local). This mirrors the JP
CIT pattern at `cit_provider.clj:286/317/349` where the three
governments each get their own component. Both shapes are valid; the
spec-compliant single-component shape is the cheaper fix.

**Severity**: P1. The substrate accepts the current shape (no
exception) but the audit/explain story breaks.

### P1-2. Statute citation names the wrong law

**Where**:
- `cgt_statute.clj:147` parameter concept-iri:
  `"https://elaws.e-gov.go.jp/document?lawid=423AC0000000117"` — OK,
  but elaws redirects to `laws.e-gov.go.jp` (301). Should be updated.
- `cgt_statute.clj:242` parameter-value citation:
  `"東日本大震災復興特別措置法 §13 — 2.1 % × national tax, 2013-2037"`
  — **wrong law name**.
- `cgt_statute.clj:281` provision citation: same elaws URL.
- `cgt_provider.clj:39-42` docstring: "復興特別所得税法 §13" — same
  issue.

**What the law is actually called** (verified against
[hourei.net/423AC0000000117](https://hourei.net/law/423AC0000000117)
and the [NTA pamphlet](https://www.nta.go.jp/publication/pamph/shotoku/fukko_tokubetsu/index.htm)):

- Formal name: **東日本大震災からの復興のための施策を実施するために必要な
  財源の確保に関する特別措置法**
  ("Special Measures Law Concerning the Securing of Financial Resources
  Necessary to Implement Measures for Reconstruction from the Great
  East Japan Earthquake")
- Promulgated 2011-12-02 as **平成23年法律第117号** (Law No. 117 of 2011).
- The 2.1 % rate for individuals is in **第13条** (Article 13) —
  「個人に係る復興特別所得税の税率」("Tax rate of reconstruction special
  income tax on individuals"). **§13 is correct.**

So "復興特別所得税法" doesn't exist as a law name (there's no such
short-titled statute — the body of rules forms part of the longer
"特別措置法"), and "東日本大震災復興特別措置法" is colloquial; the formal
citation should be the full title or its conventional contraction
**「復興財源確保法」** + Article 13.

**Fix shape** — pick one consistent form across all citations:

```clojure
"復興財源確保法 §13 (平成23年法律第117号) — 2.1 % × national tax, 2013-2037"
```

(`復興財源確保法` is the conventional Japanese short-title that NTA and
practitioners use; e-gov uses the full title.) Update both
parameter-value-citation (line 242), provision-title (line 280), and
the provider docstring (line 39-42, line 220-225).

**Severity**: P1. Citation hygiene matters in tax software — an
auditor following the citation finds no such law.

### P1-3. `:carry-in` line-item is conditioned on `(pos? (or carry-in 0M))` but the line shows `(- carry-in)` — fine for positive, breaks if a consumer ever passes a negative

**Where**: `cgt_provider.clj:354-356`:

```clojure
(pos? (or carry-in 0M))
(conj {:line :carry-in
       :label "Capital-loss carry-in"
       :value (money/money (- carry-in) commodity)})
```

This is a small one — a consumer passing a negative `carry-in`
(misinterpreting the sign convention; the docstring at lines 18-24
says "a positive carry = a loss available to offset gains") would
silently skip the line item. The `net-against-carry` fold at line 214
uses `max 0M (- regime-gain (or carry-in 0M))` which also silently
ignores negative carries (treating them as 0). Two interacting silent
defaults compound: a consumer who interprets "carry" as
`{positive = gain pulled forward}` (the opposite of the convention)
gets zero tax change AND no diagnostic.

**Fix shape**: Either (a) validate `carry-in` at the top of
`period-tax-facts` (reject negatives with `ex-info`), or (b)
document-and-test the negative case as a no-op explicitly.

**Severity**: P1. Defensive-programming gap that surfaces when a
consumer misreads the convention.

### P1-4. `gross-liability` doesn't actually include 復興 in the spec contract — see P1-1; pulling this out separately because the existing test at `:471-492` happens to PASS with the current bug

The reconstruction-surtax-on-national-only test reads:

```clojure
(is (== 31500M (-> surtax-line :value :amount)))
(is (== 31500M (:amount surtax-item)))
```

Both pass because `:surtaxes` correctly contains the surtax line.
**The test does NOT check `:gross-liability` vs `:liability` separately.**
A follow-up should add:

```clojure
(is (== 1500000M (-> lsec :gross-liability :amount))
    "gross-liability is pre-surtax national + local: 1.5M + 500k = 2M? -- NO 1.5M + 500k = 2,000,000")
;; OR
(is (== 2000000M (-> lsec :gross-liability :amount)))
(is (== 2031500M (-> lsec :liability :amount))
    "liability = gross + 復興 surtax = 2,031,500")
```

With the current implementation, `:gross-liability` returns
`(money/money 2031500M ...)` — the same as `:liability` — which
violates the spec.

**Severity**: P1 (couples with P1-1).

---

## P2 — Polish

### P2-1. Constructor `:id` is ignored in `:provenance :provider-id`

**Where**:
- `cgt_provider.clj:381`: `:provider-id :jp-cgt` (hardcoded).
- `cgt_provider.clj:497-507`: constructor accepts `:id` and forwards to
  the record's `id` field, but `regime-component` doesn't reach the
  record — it sees `opts` only. So `id` is captured but unused.

**Test confirms it**: `cgt_provider_test.clj:509`:
```clojure
(is (= [:jp-cgt] [(get-in re [:provenance :provider-id])]))
```
A consumer who passes `:id :jp-cgt-individual-tokyo-office` sees
`:jp-cgt` in the audit trail — confusing.

**Fix**: thread `id` from the provider record into the `opts` map at
`period-tax-facts` (cgt_provider.clj:445) and use it at
cgt_provider.clj:381.

**Severity**: P2.

### P2-2. `:residual-loss` shape mixes concerns

**Where**: `cgt_provider.clj:390-392`:

```clojure
:residual-loss (max 0M (- (or carry-in 0M)
                          (apply-§35-deduction
                           gross-gain §35-amount §35?)))
```

`apply-§35-deduction` returns a non-negative value (it's the AFTER-§35
gross). Subtracting it from `carry-in` and flooring at 0 yields the
"unused carry-in" — which is the residual loss carried forward to
next year. This is correct semantically but the calculation is opaque.
Recommend extracting `after-§35-gain` once and reusing:

```clojure
(let [after-§35-gain (apply-§35-deduction gross-gain §35-amount §35?)
      residual-loss (max 0M (- (or carry-in 0M) after-§35-gain))]
  ...)
```

Also: the residual-loss for a `:jp-real-estate-*` regime is mis-named
because real-estate losses do NOT carry forward (per note 115 §1.4 and
your own docstring at cgt_provider.clj:23-24). Reporting a
`:residual-loss` value on a real-estate component is misleading —
the consumer might think they can carry it. Either zero it out for
real-estate regimes or rename it `:unused-carry-in` and document that
the consumer-side decides whether the bucket carries forward.

**Severity**: P2.

### P2-3. `nat-surtax-ctx` carries `:regime` twice

**Where**: `cgt_provider.clj:336`:

```clojure
nat-surtax-ctx (assoc scoped-ctx :pass :national :regime regime)
```

`scoped-ctx` already has `:regime regime` set on line 324. Redundant.
Cosmetic but signals the author was checking-and-double-checking — a
hint that the surtax-ctx wiring could use a docstring.

**Severity**: P2.

### P2-4. `:jp-§31-3` election + ¥60M-above test missing the §35-also-claimed case

**Where**: `cgt_provider_test.clj:328-347`. The §31-3-above-60M test
has `:gross 100M` with **no §35 claimed**. The §31-3-below-60M test
DOES claim §35 (¥30M deduction → ¥25M base ≤ ¥60M).

Missing: §31-3 election + §35 claimed + gross > ¥90M (so after §35,
base > ¥60M, exercises the progressive split). Worked example:
- Gross ¥100M − §35 ¥30M = ¥70M base.
- National: 60M × 10% + 10M × 15% = 6M + 1.5M = 7.5M
- Surtax: 7.5M × 2.1% = 157,500
- Local: 60M × 4% + 10M × 5% = 2.4M + 500k = 2.9M
- Total: 10,557,500

This is the worst-case "all reliefs interact" path — high-value
Takahashi-style sale + §35 + §31-3 + above-kink. Worth adding.

**Severity**: P2.

### P2-5. Tsutatsu numbers absent on most parameters

**Where**: `cgt_statute.clj:53-160`. The agent ships taxanswer (No.3xxx)
URLs for some parameters (3208, 3211, 3305) but not all. The §35
parameter (line 140) points to elaws only — no taxanswer No.3302 link
even though that's the canonical NTA reference. Adding taxanswer
URLs in parallel with elaws URLs would let an auditor read the rule
quickly without parsing statute.

Also: NTA tsutatsu numbers (e.g. 措置法通達 35-2, 31-3-2, 31-3-14,
31-3-15, 35-6) per [No.3302 source page](https://www.nta.go.jp/taxes/shiraberu/taxanswer/joto/3302.htm)
— these are the official administrative guidance the NTA publishes
and the agency itself uses. Citing them in the parameter `:citation`
field would mirror the DE CIT pattern (note 120 P0 fixes added BMF
Fundstelle to every parameter-value).

**Severity**: P2.

### P2-6. `:provenance` field doesn't include `:computed-at`

**Where**: `cgt_provider.clj:381-384`:

```clojure
:provenance      {:provider-id :jp-cgt
                  :statute     (regime-statute-label regime)
                  :provisions-applied (mapv :provision/code provisions)
                  :as-of       as-of}
```

The component spec at `period_tax_provider.clj:98` says
`:provenance {:provider-id :statute :computed-at :form}`. Adding
`:computed-at (java.util.Date.)` would mirror the spec. The current
`:as-of` is the bitemporal axis; `:computed-at` is when the
computation ran (different from `:as-of` in a replay or audit
scenario). Cheap.

**Severity**: P2.

---

## What's correct (and load-bearing)

### Jan-1 measurement rule — PRESENT and CORRECT

`cgt_provider.clj:95-117` — `year-of` uses UTC-anchored
`java.util.Calendar`, eliminating the timezone-drift risk that would
turn 2026-01-01 instants into 2025 in JST-anchored calendars.
`jan-1-elapsed-years` returns `(year disposed-on) − (year acquired-on)`
which IS the calendar-year boundary count.

Boundary tests at `cgt_provider_test.clj:106-113` exercise:
- 2020-12-15 → 2026-02-01: 6 boundaries → LONG ✓
- 2021-01-02 → 2026-12-30: 5 boundaries → NOT >5 → SHORT ✓
  (this is the JP-unique nasty edge — the actual elapsed time is 5y
  11m but the Jan-1 measurement says SHORT)
- 2015-06-01 → 2026-05-01: 11 boundaries → §31-3 eligible ✓

And the integration test `jan-1-boundary-classifies-long` at
`cgt_provider_test.clj:228-242` verifies the rule end-to-end through
a real disposal classification.

The implementation choice — `(year disposed-on) − (year acquired-on)`
WITHOUT a January-1 boundary check on the disposal year — is correct
because the disposal year's Jan 1 is necessarily ≥ acquired-on's year
boundary, and `>` (strict) vs `≥` (non-strict) is handled at the
threshold comparison (`(> elapsed long-cutoff)` at line 162).

### Five-regime fan-out — PRESENT and CORRECT

The `case` dispatch at `cgt_provider.clj:164-179` covers the closed
regime set and routes correctly:

- `:jp-listed-securities` and `:jp-unlisted-equity` are direct asset-class
  routes (no holding-period dependency for JP equity).
- `:jp-real-estate-residence` and `:jp-real-estate` both check
  `(and long-res? residence? (contains? elects :jp-§31-3))` first,
  then `long?`, then default short. This handles:
  - residence + 10 yrs + §31-3 election → long-residence
  - residence + 10 yrs WITHOUT §31-3 election → plain long (correct —
    the §31-3 is an election, not automatic)
  - residence + <10 yrs → falls through to long/short
  - non-residence → falls through to long/short

One subtle nit: the docstring at `cgt_provider.clj:148-153` says
"Returns `{:regime <kw> :disposal <map>}`" but does not mention what
happens to a `:jp-listed-securities` disposal whose `:residence?` flag
is set (it's silently ignored — `:residence?` is only inspected on the
real-estate branches). This is correct behaviour but worth a comment.

### Multi-regime fan-out — PRESENT and CORRECT

`cgt_provider.clj:452-469` iterates the fixed regime order and emits
a component per regime with non-zero gross. The test
`multi-regime-fan-out` at `cgt_provider_test.clj:436-465` verifies
two simultaneous disposals fan out into two distinct components, each
with its own 復興 surtax line. Watertight.

### Worked-example arithmetic — VERIFIED

**Mr Sato (note 115 §2.1)** — short-term ¥13.5M gain × 39.63 % =
¥5,350,050:

- 13,500,000 × 0.30 = 4,050,000 national
- 4,050,000 × 0.021 = 85,050 復興
- 13,500,000 × 0.09 = 1,215,000 local
- Total: 4,050,000 + 85,050 + 1,215,000 = 5,350,050 ✓

`cgt_provider_test.clj:353-369` exercises this end-to-end. Spot-on.

**Mr Takahashi (note 115 §2.2)** — long-residence ¥55M − §35 ¥30M =
¥25M × 14.21 % = ¥3,552,500:

- 25,000,000 × 0.10 = 2,500,000 national
- 2,500,000 × 0.021 = 52,500 復興
- 25,000,000 × 0.04 = 1,000,000 local
- Total: 2,500,000 + 52,500 + 1,000,000 = 3,552,500 ✓

`cgt_provider_test.clj:371-389`. Spot-on.

### 復興 surtax on national only — PRESENT and CORRECT

`cgt_statute.clj:285` — `:provision/condition (pr-str [:eq :pass :national])`.

`cgt_provider.clj:336-344` — the surtax provider call sets `:pass
:national` in ctx and runs `apply-provisions` over national-gross
only. The local gross is computed separately (line 332) and added
AFTER (line 344, `(+ national-with-surtax local-gross)`). The
provision's `:eq :pass :national` predicate correctly fires only on
the national pass.

`cgt_provider_test.clj:471-492` (`reconstruction-surtax-on-national-only`)
verifies: ¥10M × 15 % = ¥1.5M national; 復興 = ¥1.5M × 2.1 % = ¥31,500.
The surtax-line value is ¥31,500 (NOT ¥31,500 + an extra 0.5M ×
2.1 % = ¥10,500 that would fire if 復興 incorrectly hit the local
side).

### Loss-carry buckets — CORRECT semantics

`cgt_provider.clj:82-89` — three buckets, with real-estate-short and
real-estate-long-residence sharing the `:jp-real-estate` bucket per
note 115 §1.4. The `carry-key-for-regime` map at `cgt_provider.clj:303`
encodes this correctly. The fact that real-estate doesn't carry
across periods is a CONSUMER concern (the consumer doesn't
re-supply an `:inputs :capital-loss-carryforward :jp-real-estate`
next period); the provider's mechanics don't enforce that, which is
fine — note 115's substrate-fit explicitly punts carry-forward
duration to consumer policy. The docstring at line 22-24 says so;
good.

### Corporate fold-into-CIT — PRESENT and CORRECT

`cgt_provider.clj:398-415` mirrors the US sibling at
`l10n-us/cgt_provider.clj`. Sets `:cit-base-additions [net-gain]`
under `:jurisdiction-specific-codes`, `:liability` is zero, the
consumer composes with the JP CIT provider. Test at
`cgt_provider_test.clj:395-413` checks the `:cit-base-additions`
slot. Idiomatic.

### DisposalSource protocol use — IDIOMATIC

The provider imports `kontor.disposal-source :as ds` (kernel
protocol, ADR-102) and calls `(ds/disposals-in source entity period)`
at `cgt_provider.clj:440`. The test wires
`kontor.disposal.source/datahike-source` (the companion's reference
impl). Empty-source path works (test
`empty-source-returns-zero-components` at cgt_provider_test.clj:89).

---

## Substrate friction noted (not P0/P1)

### F1. `:disposal/elective-regime` and `:disposal/exemption-claimed` are jurisdiction-tagged keywords

The provider reads `:jp-§31-3`, `:jp-§35-residence`. There's no
companion-level registry of valid values; a typo silently becomes a
no-op. Substrate-wide concern (all CGT providers face this). The
disposal companion could ship a per-jurisdiction validator
(`(register-elective-regimes! :jp #{:jp-§31-3 :jp-§36-2-replacement})`)
and warn on unknown values. Out of scope for this review; flag for
ADR-103 closure or note 116 follow-up.

### F2. The `:authority` field is `:jp-nta` for the whole component, but a JP CGT return spans BOTH national (NTA) and local (municipalities)

The JP CIT provider at `cit_provider.clj:286/317/349` correctly splits
across three authorities (national NTA, prefecture, municipality).
The JP CGT collapses this to `:jp-nta` only, even though
`local-gross` (5 % / 9 %) is paid to the municipality, not NTA.

In JP practice, the **inhabitants' tax** (住民税) component IS filed
via Form B in the same March return cycle and the same NTA returns
infrastructure (NTA collects and remits to the municipality), so
treating `:authority :jp-nta` as the unified collection point isn't
strictly wrong. But the post-collection MONEY FLOW differs (national
treasury vs municipal treasury), and a downstream remittance builder
that respects authority will miscount.

Recommendation: either (a) split into two components per regime
(`:authority :jp-nta` for national+復興, `:authority :jp-municipality`
for local) — mirrors the CIT 3-component split, OR (b) document that
`:authority :jp-nta` is the single-collector convention and a future
remittance builder splits per the rate-table. The provider docstring
at lines 9-13 hints at the latter but doesn't commit; commit one
way.

Severity: design-call. Defer to ADR-103 follow-up if the team
prefers the single-collector convention.

### F3. `JP.CGT.realestate-short.local-rate` lookup-IRI is a taxanswer URL, not an e-gov statute URL

Cosmetic — the inhabitants' rate is in 地方税法 (Local Tax Law), not in
租税特別措置法. Worth pointing the `:concept-iri` at the 地方税法 e-gov
URL for the symmetric statute pointer, even though the taxanswer URL
is more user-friendly. Same applies to the §31-3 local rates (lines
123, 128).

### F4. The `id` field in the constructor is forwarded to the record but never reaches `:provenance` — see P2-1

---

## What I did NOT verify (out of scope or already trusted)

- The disposal companion's `:disposal/state :voided` exclusion — trusted
  per `disposal_source.clj:84-86` which already does the filter.
- The `kontor.statute/parameter-value-at` parameter-resolution
  semantics (the half-open `[effective-from, effective-until)`
  window) — trusted per the implementation at `statute.clj:150-174`,
  and `JP.CGT.reconstruction-surtax-rate` correctly has
  `:effective-from #inst "2013-01-01"` + `:effective-until #inst
  "2038-01-01"`, which means a `as-of` of 2037-12-31 IS in the window
  (it's < 2038-01-01) — correct sunset.
- The `kontor.statute/apply-provisions` evaluator — trusted per its
  contract at `statute.clj:440-481`.
- The `kontor.tax-schedule/progressive` constructor and `apply-schedule`
  on a `:progressive-bracket` shape — trusted per `tax_schedule.clj`.
- The §36-2 replacement-property deferral — the docstring at
  `cgt_provider.clj:33-37` says it's handled by `realized-gain`
  excluding `:rollover-amount`; this is correct because the
  disposal companion's `realized-gain` (and the provider's local
  `realized-gain` at line 129-137) both subtract `rollover-amount`.
  Not tested but the mechanic is right.

---

## Sources

JP statute and tsutatsu (primary):

- [NTA タックスアンサー No.3211 短期譲渡所得 (§32)](https://www.nta.go.jp/taxes/shiraberu/taxanswer/joto/3211.htm)
- [NTA タックスアンサー No.3208 長期譲渡所得 (§31)](https://www.nta.go.jp/taxes/shiraberu/taxanswer/joto/3208.htm)
- [NTA タックスアンサー No.3302 マイホームを売ったときの特例 (§35 ¥30M)](https://www.nta.go.jp/taxes/shiraberu/taxanswer/joto/3302.htm)
- [NTA タックスアンサー No.3305 マイホームを売ったときの軽減税率 (§31-3)](https://www.nta.go.jp/taxes/shiraberu/taxanswer/joto/3305.htm)
- [NTA タックスアンサー No.1465 上場株式等の譲渡損失の繰越 (§37-12-2)](https://www.nta.go.jp/taxes/shiraberu/taxanswer/shotoku/1465.htm)
- [NTA 個人の方に係る復興特別所得税のあらまし](https://www.nta.go.jp/publication/pamph/shotoku/fukko_tokubetsu/index.htm)
- [復興財源確保法 §13 (法令リード mirror)](https://hourei.net/law/423AC0000000117)
- [租税特別措置法第35条 (zeiken.co.jp)](https://www.zeiken.co.jp/hourei/HHSOZ000000/35.html)

Internal references:

- `doc/research/115-jp-cgt-fit.md` — substrate-fit assessment (note 115)
- `doc/research/120-de-cit-baseline-review.md` — template for this review's structure
- `doc/decisions.md` — ADR-099 / ADR-101 / ADR-102 / ADR-103
- `modules/l10n-us/src/kontor/l10n_us/cgt_provider.clj` — sibling, US CGT
- `modules/l10n-jp/src/kontor/l10n_jp/cit_provider.clj` — sibling, JP CIT (the 3-authority precedent)
- `src/kontor/period_tax_provider.clj:67-100` — `TaxReturnFacts` component contract
- `src/kontor/disposal_source.clj` — DisposalSource protocol
- `src/kontor/statute.clj:440-481` — `apply-provisions` contract
- `src/kontor/tax_schedule.clj:294-303` — `flat` / `progressive` constructors

---

## Recommended close-out for ADR-103 (JP slice)

1. **Block ship until P0-1 is fixed**: the §35 lane restriction
   contradicts NTA and produces wrong tax. This is the
   "review-after rhythm catches the design call" moment. Cheap fix
   (3-line gating + 2 new tests + 1 inverted assertion).
2. **Roll P1-1 + P1-2 + P1-3 + P1-4 into the same fix-up commit**:
   `:gross-liability` + `:schedule` shape, citation hygiene, carry-in
   defensiveness, gross-liability test. These are coherent
   "tighten the contract" changes.
3. **Triage P2-1..P2-6 to a follow-up note (141 or 142)**: polish that
   doesn't block ship. P2-1 (constructor `:id` ignored) is the most
   user-visible; the rest are doc / shape / test-coverage hygiene.
4. **Friction items F1-F4** are substrate-wide. Carry them into the
   end-of-stage cross-CGT review (companion to note 120 for DE-CIT).

The shipped code IS substantively correct on the JP-unique design
features (Jan-1 measurement, 5-regime fan-out, 復興-on-national-only,
corporate-fold-into-CIT). The P0 is a single mis-narrowed gate and
the P1s are a single conflated `:gross-liability` / `:schedule` line.
Once those land, the JP CGT slice is ready.

End of note 140.
