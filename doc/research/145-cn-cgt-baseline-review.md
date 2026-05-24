---
date: 2026-05-24
title: 145 — CN CGT + LAT provider baseline review (ADR-103 review-after)
audience: maintainer of `kontor-l10n-cn` + ADR-103 review-after
status: review-after for the CN IIT + EIT CGT providers + the LAT provider just shipped; output is a triaged findings list (P0/P1/P2/Nit) with file:line citations against authority + research notes 133/87. No code changes proposed in this note — fixes are tracked separately.
---

# 145 — CN CGT + LAT provider baseline review

This note audits the freshly-landed CN CGT triptych:

- `/home/christian-weilbach/Development/kontor/modules/l10n-cn/src/kontor/l10n_cn/cgt_statute.clj`
- `/home/christian-weilbach/Development/kontor/modules/l10n-cn/src/kontor/l10n_cn/cgt_provider.clj`
- `/home/christian-weilbach/Development/kontor/modules/l10n-cn/src/kontor/l10n_cn/lat_provider.clj`
- `/home/christian-weilbach/Development/kontor/modules/l10n-cn/test/kontor/l10n_cn/cgt_provider_test.clj`
- `/home/christian-weilbach/Development/kontor/modules/l10n-cn/test/kontor/l10n_cn/lat_provider_test.clj`

against (a) authority sources cited in research note 133, (b) the
shipped design proposed there, (c) the substrate primitives the DE /
FR / JP / CA CGT providers established, and (d) recent (2024-2025)
chinatax.gov.cn confirmations that the listed-A-share + Stock Connect
IIT exemptions remain in force.

Method: read end-to-end; cross-check every Caishui circular by ID
against chinatax.gov.cn fgk + KPMG China + PwC China practitioner
guides + China Briefing 2025 commentary; verify each LAT bracket
both the marginal-rate and the quick-deduction-coefficient
formulations match; rate findings.

Bottom line: **provider triptych is grounded correctly; the LAT
schedule numerically matches the standard 速算扣除系数 quick-formula
to the yuan; the IIT and EIT lanes are correctly factored.** One P0
about LAT exemption gate ordering (the developer-only filter
short-circuits the personal-residence exemption — see §1); two P1s
on coverage gaps in EIT exception handling; three P2s on input-
shape consistency; one P1 about the listed-share **exempt-line
audit footprint** (current code emits a positive `:gross-liability`
when a deemed-gross election co-exists with an exempt disposal —
the exempt-line is fine but the line-item structure muddles audit).

The two provisions installed by `cgt_statute.clj` are inert —
provider never calls `kontor.statute/apply-provisions`. This matches
the documented v1 design ("audit-trail provisions"), but should be
acknowledged: the listed-A exemption is **NOT** evaluated via the
statute substrate. That is OK because the provider verifies the
exemption in code; the inert provision is documentation.

---

## §1. P0 ship-blockers — fix before next release

### P0-1. `cn-lat-provider`: personal-residence exemption requires the disposal to be `:cn-developer-real-estate`

`lat_provider.clj:108-113, 191-194` — the eligibility flow is

```clojure
(->> disposals
     (filter lat-eligible?)                          ; only :cn-developer-real-estate
     (remove personal-residence-exempt?)
     (remove #(ordinary-residential-developer-exempt? % ctx)))
```

The `personal-residence-exempt?` predicate (lines 115-120) reads
`:exemption-claimed :cn-lat-personal-residence` — but it can only fire
after `lat-eligible?` has already returned true, which requires
`:asset-class :cn-developer-real-estate`. So a `:cn-residential`
disposal by an individual with the Caishui [2008] 137 personal-
residence LAT exemption is **silently dropped without producing an
exemption audit line**, because it never passes the eligibility
filter at all.

This is reinforced by the test `lat-non-developer-real-estate-not-eligible`
(lines 207-216) which checks that `:cn-residential` produces empty
components — that's the buggy behaviour, dressed up as a green test.
The Caishui [2008] 137 framing in note 133 §1.6 says "individual
sales of personal residences are exempt from LAT" — which means
they should NOT be evaluated by LAT at all. So functionally the
result is correct, but the design intent is muddled. The test
`lat-personal-residence-exempt` (lines 182-192) records the
exemption on a `:cn-developer-real-estate` disposal — which is a
contradiction in terms (an individual is not a developer; the
exemption applies to **individuals**, not developers). Per note
133 §1.6: "individual residential sales are exempt from LAT
regardless of holding period or sole-residence status — **broader
than the IIT exemption**."

**Recommended resolution**:
- Either (a) treat `:cn-residential` as **LAT-out-of-scope by
  construction** (the only way to enter LAT is to be a developer or
  commercial transferor) and document this — drop the
  `:exemption-claimed :cn-lat-personal-residence` from the public
  API entirely, OR
- (b) widen `lat-eligible?` to include `:cn-residential` so that
  the `:cn-lat-personal-residence` exemption-claim path is actually
  exercised; the audit trail records the exemption.

Today the test `lat-personal-residence-exempt` (lines 182-192) is
**testing a path that the implementation arrived at by a different
filter** — the disposal would have been dropped at the
`lat-eligible?` gate regardless. The exemption-removal step is
dead code in the current shape.

Severity: **P0** — not a numerical wrongness today, but a logic
error that will bite a consumer who records a non-developer real-
estate disposal and expects LAT to produce an `:exempt` audit
line. The two routes give different audit output.

---

## §2. P1 — substantive gaps that change a real customer's number or behaviour

### P1-1. Listed-share exempt-line emits a positive `:gross-liability` when paired with a deemed-gross election

`cgt_provider.clj:219-261, 410-444` — when an entity has
both (a) a `:cn-listed-a-share` exempt disposal AND (b) a
`:cn-real-estate-deemed-rate` disposal in the same period, the IIT
component combines both into ONE component (single multi-line
component is the documented design — see component name
`:cn-iit-cgt`). The exempt disposal lands as an `exempt-line`
(line-item `:cn-caishui-1998-61` with a `:value` of the realised
gain). The deemed-gross liability lands in the same component's
`:gross-liability`. That's OK as long as the consumer never reads
the line-items and just trusts `:base` + `:gross-liability` —
which they shouldn't, because:

- `:base` is `net-clamped` (net-gain only) = 0 for a pure-exempt-
  plus-deemed-gross scenario.
- `:gross-liability` = `(+ net-tax deemed-gross-tax)` = `deemed-gross-tax` only.
- `:schedule` is `(ts/flat rate)` = 20 % — which **does not match
  the path that produced the liability** (the deemed-gross path is
  1-3 %, not 20 %). A downstream report that says
  "applied schedule = 20 % on base = 0" reconciles to liability 0 —
  but the actual liability is positive. The component's `:schedule`
  field misleads the reader.

**Recommended fix**: split the component into two — one
`:cn-iit-cgt-net-gain` with `:schedule (ts/flat 0.20)`, another
`:cn-iit-cgt-deemed-gross` with a `:schedule (ts/flat <provincial-rate>)`
(or `nil` if the rate varies per disposal). This is what `kontor.book`
and the rest of the substrate already do for sum/branching
schedules. See ADR-099 + ADR-101 patterns.

### P1-2. 滿五唯一 holding-period start date is whatever the consumer puts in `:acquired-on`

`cgt_provider.clj:149-163` — `manwuweiyi-exempt?` reads
`:disposal/acquired-on` and `:disposal/disposed-on`, requires
`(- disposed acquired) ≥ 5 years`. Per chinese tax practice and
Guoshuifa [2006] 108 §1 ([辽宁省税务局 explainer](https://liaoning.chinatax.gov.cn/art/2023/2/6/art_525_96687.html)),
the 5-year clock starts at the **EARLIER** of (a) purchase contract
date, (b) deed-tax (契税) payment receipt date, (c) property
certificate (房产证) issuance date. The provider does not encode
this — it trusts whatever the consumer writes into `:acquired-on`.
A consumer who wrote the property-certificate date when the deed-
tax-receipt date was earlier would fail the prong by 1-2 months
and lose the exemption.

**Recommended fix**: add a docstring note "consumer must supply
`:acquired-on` as the earliest of contract / receipt / certificate
date" — and/or accept an `:inputs :cn-holding-period-start-date-evidence`
slot recording which date was chosen.

### P1-3. `cn-eit-cgt-provider` doesn't filter for resident-corporation residency

`cgt_provider.clj:450-475` — the EIT provider classifies disposals
into `:special-restructuring` / `:intra-group-100pct` /
`:five-year-spread` / `:normal` and folds gains into
`:cit-base-additions` regardless of residency. Per note 133 §1.4 +
§1.7, non-resident enterprises are subject to **10 % WHT** under
SAT Announcement [2017] No. 37 + EIT Law Article 4 (the provider
has the parameter `CN.EIT.non-resident-wht-rate = 0.10M` loaded
but the EIT provider never reads it). The SAT [2015] 7 indirect-
transfer re-characterisation is not implemented at all (note 133
§1.7).

The EIT provider also never reads `:tax-unit :tax-residency`. A
non-resident corporation's disposal currently folds via
`:cit-base-additions` into the (consumer-supplied) CIT provider,
which presumably applies 25 % — but the right answer for non-
resident enterprises is 10 % WHT, not 25 % CIT inclusion.

Severity: P1. The note 133 design explicitly scopes the v1
provider to "the exceptions" (deferrals + spreads + indirect-
transfer re-characterisation) and the residency branch is one of
them. The provider docstring should mention this deferral
explicitly so a consumer with non-resident-corp disposals does not
get surprised by an over-tax.

### P1-4. `cn-iit-cgt-provider` always emits a component when there are exempt-lines, even if zero liability

`cgt_provider.clj:415-444` — the component-emission gate is

```clojure
(if (and (zero? net-gain-sum) (zero? deemed-gross-tax) (empty? exempt-lines))
  []
  [(iit-component …)])
```

If `exempt-lines` is non-empty (e.g. a listed-A-share gain), a
component is emitted with `:base 0`, `:gross-liability 0`,
`:liability 0`. The test `listed-a-share-residents-no-component`
(lines 155-175) tolerates either nil OR zero — so the test
implicitly knows about both paths. That's OK for audit (the
component carries the exempt-line for the audit trail) but
inconsistent with the `cn-eit-cgt-provider` which emits NOTHING
when there are no additions or deductions. Two providers, two
emit semantics. Pick one and document.

Recommendation: keep the IIT path (zero-amount audit component) —
it preserves the exemption-trail in the period facts. Update the
EIT provider to emit a zero-amount audit component when there are
disposals with exceptions whose net effect is zero (this also
helps the eventual SAT [2015] 7 indirect-transfer audit trail).

### P1-5. `cn-eit-cgt-provider` reads `:equity-payment-share` from `:tax-unit`, not `:inputs`

`cgt_provider.clj:289-300` — the special-restructuring equity-share
input rides
`(get-in ctx [:tax-unit :equity-payment-share])`. Substrate convention
(see DE / CA CGT providers + ADR-099) is that per-disposal "facts
of the world" ride `:inputs`, and `:tax-unit` carries person/entity
attributes (residency, family-status, CCPC?-flag style). The
`:equity-payment-share` is a per-disposal economic fact, not a
property of the tax-unit. Moving the slot to `:inputs` reduces
substrate drift.

### P1-6. Non-resident-individual A-share path applies 20 % — but treaty caps may reduce

`cgt_provider.clj:139-147, 177-192` — the test
`listed-a-share-non-resident-NOT-exempt` (lines 177-192) verifies
that a non-resident individual on A-shares pays 20 %. Per
chinatax.gov.cn + KPMG China practice, **the A-share IIT
exemption (Caishui [1998] 61) is residency-neutral in the
canonical text** — the original Caishui treats "individuals"
without distinguishing residency, and EY Tax Alert 2022042 +
practitioner consensus is that the temporary exemption applies to
*both* resident and non-resident individuals. The provider's
narrower interpretation (resident-only) is **safe in the
under-claim direction** (the customer pays MORE than possibly
required) but is contrary to the typical reading. Worth flagging
to the maintainer for verification with a tax counsel before
charging non-resident individuals A-share IIT.

(Note 133 §1.1 cites Caishui [1998] 61 + [2005] 35 + [2009] 167
without distinguishing residency. The provider's narrowing to
resident-only is an interpretive choice, not a statutory
requirement; document the choice.)

---

## §3. P2 — quality / hygiene

### P2-1. The Stock Connect parameter `:days` stores millisecond epoch

`cgt_statute.clj:200-203` — the Stock Connect exemption-until is
stored as `(bigdec (.getTime #inst "2027-12-31"))` with
`:parameter/unit :days` (label `"end date — Caishui [2014] 81
(extended)"`). The unit name doesn't match the value (epoch ms,
not days). The provider never reads this parameter; it's
documentation. But a future consumer (or `parameter-value-at`
caller) that interprets the value as "days since epoch" or "ms
since epoch" will get the wrong date. Three fixes possible:

1. Rename the parameter `:unit :date-ms`.
2. Use a `:parameter-value/instant-value` attr (would need
   schema work — heavier).
3. Make the parameter's `:effective-until` itself the carrier
   (the date of exemption-end IS just the `:effective-until` of
   the rate parameter). This is the most substrate-aligned
   solution; remove the helper parameter entirely and read the
   rate's `:effective-until`.

### P2-2. `cn-lat-provider` `lat-schedule` returns the top bracket for zero deductibles

`lat_provider.clj:86-102` — when `deductibles = 0`, the bracket
boundaries scale to 0, 0, 0; the first three brackets are
collapsed; the schedule then says "everything is in bracket 4 at
60 %." That matches the docstring intent ("short-circuits to the
top bracket"). But the call site (`lat-component-one`) uses
`(:disposal/basis-amount d)` for deductibles — which means a
disposal with NO basis (i.e. consumer-supplied 0 because dev
recorded no acquisition cost) gets a 60 % rate on its entire
value-add. That is **statutorily incorrect** (zero deductibles ⇒
the gain has no value-add ratio to compare to, and LAT requires
positive deductibles to be assessed). Recommend raising
`:cn-lat-no-deductibles` from `lat-component-one` instead of
silently applying 60 %.

### P2-3. `cn-iit-cgt-provider` `:cn-iit-prepaid` reads `(bigdec (or … 0M))` — double-coerces

`cgt_provider.clj:236` — `(bigdec (or (get-in ctx [:inputs :cn-iit-prepaid]) 0M))`.
The `0M` default is already a BigDecimal; the consumer-supplied
value is documented as a BigDecimal. The `bigdec` wrap is defensive
but masks a type bug if the consumer passes a Long. Pick a
posture: either be permissive and document the coercion, or be
strict (require BigDecimal, raise on type mismatch).

### P2-4. `:components` always-vector vs. always-nil-or-vector

`cgt_provider.clj:429-432, 465-468` — IIT returns `[]` for the empty
case, vec-of-one for the populated case. EIT returns `[]` for the
empty case, vec-of-one for the populated case. Both consistent.
Good. But the `iit-component` helper takes `exempt-lines` (vector)
and `line-items` (vector concat) — when `exempt-lines` is empty,
the concat still produces the four standard lines. That's fine.
No fix; just calling it out to confirm I checked.

---

## §4. Nit — cosmetic

### Nit-1. The `lat-schedule` builder uses `(butlast lat-bracket-rates)`

`lat_provider.clj:96-102` — `(mapv … scaled-uppers (butlast lat-bracket-rates))`
pairs the three finite uppers with the first three rates, then
conj's the open-ended top bracket. This is correct but fragile — if
`lat-bracket-boundaries` and `lat-bracket-rates` ever drift in
length (e.g. someone adds a `0.70M` bracket without adding a
matching upper), the `butlast` will silently produce a wrong
schedule. A `(when (not= (count rates) (inc (count uppers))) (throw …))`
guard at namespace load time would catch this. Nit because the
brackets are stable per 国务院令 No. 138 and won't change without a
PRC State Council circular.

### Nit-2. The `manwuweiyi-exempt?` compare uses `compare`, not direct `>=`

`cgt_provider.clj:160-163` — `(>= (compare (holding-years …) 5M) 0)`.
On BigDecimal, `(>= a b)` works natively; the `(compare a b)`
indirection works but is over-engineered. The result is identical.

### Nit-3. The CN cgt-statute Stock Connect provision uses `:effective-until #inst "2028-01-01"`

`cgt_statute.clj:262` — the provision's `:effective-until` is
`2028-01-01`, matching the documented "extended through
2027-12-31" of the 2024 MOF/SAT/CSRC joint notice. Good. But the
parameter `CN.IIT.stock-connect.exemption-until` (the helper
parameter that stores the ms-epoch date) has
`:effective-from #inst "2014-11-17"` and NO `:effective-until` —
which means a query at any `:as-of` past 2014-11-17 returns the
ms-epoch value. That's consistent with the intent (the parameter
records "the end date of the exemption window", and the date
itself doesn't expire), but the parameter shape is awkward (per
P2-1, the value should just BE the parameter's `:effective-until`).

---

## §5. What is right (no action — pass)

| Topic | Authority | Implementation site | Verified |
|---|---|---|---|
| IIT category 9 — 20 % flat | 个人所得税法 §3 + chinatax.gov.cn fgk | `cgt_statute.clj:135-138` parameter `CN.IIT.CGT.flat-rate = 0.20M` | OK |
| EIT — 25 % standard | 企业所得税法 §4 (chinatax.gov.cn) | parameter `CN.EIT.standard-rate = 0.25M` | OK |
| Non-resident enterprise WHT 10 % | SAT Announcement [2017] No. 37 + EIT Law §4 | parameter `CN.EIT.non-resident-wht-rate = 0.10M` | OK rate loaded; P1-3 above is about it not being USED |
| Caishui [1998] 61 listed A/B share exemption STILL IN FORCE 2026 | [chinatax.gov.cn fgk Caishui [1998] 61](https://fgk.chinatax.gov.cn/zcfgk/c102416/c5202588/content.html) + KPMG China 2024 alerts | `cgt_provider.clj:139-147` (residency-conditional) | OK on temporariness (note that residency narrowing is an interpretive call — P1-6) |
| Stock Connect IIT exemption extended through 2027-12-31 | [财政部 税务总局 中国证监会 — 2023 延续公告 + 2024 extension](https://shanghai.chinatax.gov.cn/zcfw/zcfgk/grsds/202308/t468397.html) + cls.cn 2024 reporting | `cgt_statute.clj:255-262` provision `effective-until #inst "2028-01-01"` | OK |
| 滿五唯一 — BOTH 5-year + sole-residence prongs required | Guoshuifa [2006] 108 §1 ([chinatax.gov.cn fgk](https://www.chinatax.gov.cn/chinatax/n810341/n810765/n812183/200607/c1197073/content.html)) + 辽宁省税务局 + V&T 2023 explainer | `cgt_provider.clj:149-163` `(and resident? = :cn-residential :residence? sole-residence? years ≥ 5)` | OK on AND gate; P1-2 above is about which date `:acquired-on` represents |
| Real-estate deemed-rate election 1-3 % | Guoshuifa [2006] 108 + provincial bureaus | `cgt_provider.clj:165-208` reads `:elective-regime :cn-real-estate-deemed-rate`, ctx `:tax-unit :deemed-rate` defaults to 0.01M | OK rates band; provincial choice is consumer's responsibility |
| EIT — gains fold to ordinary CIT 25 % | 企业所得税法 §6 + §4 | `cgt_provider.clj:319-323` `(:additions (+ additions gain))` → `:cit-base-additions` | OK |
| Caishui [2009] 59 + [2014] 109 special restructuring — 50 % threshold (lowered from 75 % in 2014) | [KPMG China M&A 2016 + Mazars HK 2015 + PwC 2014 + chinatax.gov.cn fgk](https://assets.kpmg.com/content/dam/kpmg/pdf/2016/06/taxation-cross-border-mergers-and-acquisitions-china-2016.pdf) | `cgt_statute.clj:157-167` parameter date-keyed transition 2009-01-01 → 75 %; 2014-01-01 → 50 % | OK + bitemporal correctness checked |
| 85 % equity-payment criterion + 12-month lockup | Caishui [2009] 59 §5 | parameter `CN.EIT.special-restructuring.equity-payment-threshold = 0.85M` + `lockup-months = 12M` | OK |
| Caishui [2014] 109 §3 — 100 %-controlled intra-group full deferral | KPMG China 2016 + Lexology (Procedural rules on tax-free cross-border share acquisitions) | `cgt_provider.clj:280-281, 343-350` `:cn-intra-group-100pct` ⇒ all gain to `:cit-base-deductions` | OK |
| Caishui [2014] 116 — non-cash 5-year spread | Note 133 §1.4 — Caishui [2014] 116 + China Briefing | `cgt_provider.clj:283-287, 352-364` 1/5 to current, 4/5 to deferred | OK |
| Caishui [2008] 137 — individual residential LAT exemption STILL IN FORCE 2024-2025 | [财政部 国家税务总局 2008 137号 — 现行 per shui5.cn + 2024 财政部 税务总局 住建部 公告 (CN 110)](https://fgk.chinatax.gov.cn/zcfgk/c102416/c5235817/content.html) | `lat_provider.clj:115-120, 191-194` | Substantively OK (note: shui5.cn marks 财税[2008]137 itself as "条款失效"; the underlying exemption was preserved + clarified by 2024 公告 — see [Fujian](https://zjt.fujian.gov.cn/jdhy/hygq/202411/t20241118_6567746.htm) and [Chongqing Bishan](https://www.bishan.gov.cn/ztzl_241/hmhq/zczy/cxssdsfyhzc/grzrzfmzyhs/202311/t20231128_12621697.html)). See P0-1 above for the path-not-taken concern. |
| LAT four-tier bracket — 30 / 40 / 50 / 60 % at 50 / 100 / 200 % | 土地增值税暂行条例 §7 ([chinatax.gov.cn fgk](https://fgk.chinatax.gov.cn/zcfgk/c100010/c5194433/content.html)) | `lat_provider.clj:72-79` + `progressive` schedule | OK |
| LAT bracket math — equivalent to 速算扣除系数 quick formula | Standard PRC tax practice (taxnote.org + chinaacc.com + 知乎) | tests `lat-bracket-2/3/4-...-percent` (lines 97-146) | OK to the yuan: bracket-2 7M VA / 10M ded = 7M × 40 % − 10M × 5 % = 2.3M ✓; bracket-3 15M / 10M = 15M × 50 % − 10M × 15 % = 6M ✓; bracket-4 30M / 10M = 30M × 60 % − 10M × 35 % = 14.5M ✓ — the substrate `progressive` constructor's marginal computation matches the quick-deduction-coefficient formula. |
| LAT ordinary-residential exemption at ≤ 20 % VA | 土地增值税暂行条例 §8 §1 | `lat_provider.clj:129-139, 194` | OK shape (note: this prong is for **developers**, not individuals — implementation correctly gates on both `:developer?` + `:ordinary-residential?` + ratio ≤ 0.20) |
| Note 133 §2.1 — Wang ¥3.96M | Note 133 §2.1 | test `iit-unlisted-equity-20pct` (lines 115-132) | OK 19,800,000 × 20 % = 3,960,000 ✓ |
| Note 133 §2.2 — Zhang ¥0 | Note 133 §2.2 | test `manwuweiyi-both-prongs-exempt` (lines 212-227) | OK (the test checks zero liability, not zero-component — generous OR-tolerant assertion is correct given the audit-line-emission gate) |
| Note 133 §2.3 — GMP-PRC ¥60M taxable + ¥540M deferred | Note 133 §2.3 | test `eit-special-restructuring-equity-deferred` (lines 338-361) | OK 600M gain × 10 % cash = 60M additions; × 90 % equity = 540M deductions ✓ |
| Void exclusion | ADR-102 + companion DisposalSource | test `voided-disposals-excluded` (lines 386-399) | OK |
| LAT-eligible disposals dropped from IIT/EIT | Note 133 §3 — to prevent double-count | `cgt_provider.clj:411-414, 458-461` + test `lat-eligible-disposals-skipped-by-iit-and-eit` (lines 405-421) | OK |
| IIT individual no carryforward | Note 133 §1.8 / §6.2 — IIT Law category-9 has no carry | `cgt_provider.clj:222` `net-clamped (max 0M net-gain-sum)`; `:capital-loss-carryforward :cn-iit` slot NOT consumed | OK (zero-pads negative; the slot is documented as accepted-but-discarded in the provider docstring) |

---

## §6. Followups (post-P0)

1. **LAT exemption-gate logic** (P0-1): pick one of the two
   resolutions and ship the docstring fix; update the test to
   reflect the chosen path.
2. **IIT component splitting** when listed-share exempt + deemed-
   gross coexist (P1-1): two components, two schedules.
3. **滿五唯一 acquired-on date discipline** (P1-2): docstring +
   optional `:inputs` slot for date-source evidence.
4. **EIT non-resident path + SAT [2015] 7 indirect transfer**
   (P1-3): the v1 deferral is OK per note 133 §5.2 — just
   document the gap loudly so consumers know.
5. **`:equity-payment-share` move from `:tax-unit` to `:inputs`**
   (P1-5): substrate-drift fix.
6. **Non-resident-individual A-share interpretive call** (P1-6):
   confirm with tax counsel; note the choice in the provider
   docstring.
7. **Stock Connect parameter shape** (P2-1): drop the helper
   parameter and let the rate parameter's `:effective-until`
   carry the date.
8. **LAT zero-deductibles raise** (P2-2): replace silent 60 %
   with `:cn-lat-no-deductibles` exception.

---

## §7. Sources cross-checked

### CN authority (chinatax.gov.cn — public; fgk = 法规库)

- [中华人民共和国个人所得税法 §3](https://fgk.chinatax.gov.cn/zcfgk/c100009/c5193028/content.html) — 20 % flat for category 9 财产转让所得.
- [财政部 国家税务总局 1998 No. 61 — 关于个人转让股票所得继续暂免征收个人所得税的通知](https://fgk.chinatax.gov.cn/zcfgk/c102416/c5202588/content.html) — the canonical text; effective 1997-01-01; **still continued (现行)** per its retention in the fgk active set + 2024-2025 practitioner alerts.
- [Guoshuifa [2006] 108 — chinatax.gov.cn](https://www.chinatax.gov.cn/chinatax/n810341/n810765/n812183/200607/c1197073/content.html) — individual residential property IIT: net-gain 20 % vs deemed-gross 1-3 %; 满五唯一 prong.
- [财政部 税务总局 中国证监会 2023 — 沪港深港通 IIT 延续公告](https://shanghai.chinatax.gov.cn/zcfw/zcfgk/grsds/202308/t468397.html) + [财政部税务总局 2026 No. 8 — 创新企业 CDR IIT 延续 to 2027-12-31](https://fgk.chinatax.gov.cn/zcfgk/c102416/c5247185/content.html) — the chain of extensions of Caishui [2014] 81 / [2014] 79; current end date **2027-12-31**.
- [中华人民共和国土地增值税暂行条例 §7](https://fgk.chinatax.gov.cn/zcfgk/c100010/c5194433/content.html) — the canonical four-tier bracket.
- [财政部 国家税务总局 财税[2008]137号 — 房地产交易环节税收政策的通知](https://www.shui5.cn/article/02/9198.html) — the personal-residence LAT exemption text. (Note: shui5.cn marks article-level "条款失效" but the underlying exemption was preserved by the 2024 公告 below.)
- [财政部 税务总局 住房城乡建设部 — 2024 房地产市场平稳健康发展公告](https://fgk.chinatax.gov.cn/zcfgk/c102416/c5235817/content.html) — December 1, 2024 effective; clarifies ordinary-residential 20 %-cap continuation + LAT pre-collect adjustments.
- [Caishui [2014] 109 — KPMG China 2016 + Mazars HK 2015 + chinatax.gov.cn fgk](https://fgk.chinatax.gov.cn/zcfgk/c100012/) — 50 % equity threshold (lowered from 75 % in Caishui [2009] 59).

### Commentary

- [KPMG China — Taxation of cross-border M&A in China (2016)](https://assets.kpmg.com/content/dam/kpmg/pdf/2016/06/taxation-cross-border-mergers-and-acquisitions-china-2016.pdf) — confirms 75 → 50 % lower threshold.
- [Mazars HK — Easing of tax treatment in corporate restructuring 2015](https://www.mazars.hk/Home/Insights/Our-publications/Tax-publications/China-tax-newsletters/China-corporate-restructuring-tax-treatment) — confirms the 100 %-controlled intra-group relaxation.
- [chinaacc.com — 个人转让住房土地增值税](https://www.zhihu.com/question/324265107) + [辽宁省税务局 满五唯一 explainer](https://liaoning.chinatax.gov.cn/art/2023/2/6/art_525_96687.html) — the "earliest of three dates" rule for 满五.
- [cls.cn 2024 — 活跃资本市场 六大税收优惠政策延续 沪深港通](https://www.cls.cn/detail/1440225) — the Stock Connect 2024-2027 extension.
- [taxnote.org — 土地增值税税率](http://www.taxnote.org/tax/show.php?id=68) + [chinaacc.com — 速算扣除系数 derivation](https://www.chinaacc.com/kuaijishiwu/krky/zh20250326100859.shtml) — confirms 速算扣除系数 5 % / 15 % / 35 % for brackets 2 / 3 / 4 (the LAT marginal-bracket math).

### kontor substrate cited

- `/home/christian-weilbach/Development/kontor/src/kontor/statute.clj:150-174` — `parameter-value-at` lookup semantics + bitemporal `[:effective-from, :effective-until)` half-open window (verified the 2014-01-01 transition of `CN.EIT.special-restructuring.equity-threshold` works).
- `/home/christian-weilbach/Development/kontor/src/kontor/statute.clj:55-110` — `eval-condition` predicate vocabulary (`:in`, `:eq`, `:geq`, `:and` — all syntactically valid in the dormant `:provision` set).
- `/home/christian-weilbach/Development/kontor/src/kontor/tax_schedule.clj:294-303` — `flat` / `progressive` constructors.
- `/home/christian-weilbach/Development/kontor/src/kontor/tax_schedule.clj:110-` — `apply-schedule` (the LAT call site).
- `/home/christian-weilbach/Development/kontor/modules/disposal/src/kontor/disposal/schema.clj:123-237` — `:asset-class` / `:exemption-claimed` / `:elective-regime` cardinality.
- `/home/christian-weilbach/Development/kontor/modules/disposal/src/kontor/disposal.clj:155-156` — set-coerced storage of `:elective-regime` + `:exemption-claimed`.
- `/home/christian-weilbach/Development/kontor/doc/research/133-cn-cgt-fit.md` — fit assessment; this note's P0/P1 correspond to §3 (the `:base-method` design call) and §5.2 (the EIT exception-only scope).

---

End of note 145.
