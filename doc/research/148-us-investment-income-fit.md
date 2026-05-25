---
date: 2026-05-24
title: 148 — US investment-income — substrate fit for the C2 investment-income provider
audience: maintainer + the Phase C2 `us-investment-income-provider` implementer
status: research-before for Phase C2 (note 104 → tax-completion program); no code; provider sketch + NIIT coordination plan
---

# 148 — US investment-income — substrate fit assessment

This note answers ONE question: does the kontor substrate (the existing
US PIT provider, the US CGT provider, the ADR-101 statute-as-data layer,
the `kontor.tax-schedule` algebra, the `marginalize` σ_E primitive)
carry enough machinery to express US investment-income taxation
faithfully — qualified vs ordinary dividends, taxable vs tax-exempt
interest, OID accrual, the §163(d) investment-interest cap, FTC on
foreign source income — or do we need new primitives?

Bottom line: **no new substrate**. The provider reuses (a) the §1(h)
0/15/20 bracket schedule the US CGT provider already assembles from
ADR-101 parameters — qualified dividends share the rate stack, so the
same `lt-schedule` is the right lever; (b) the existing
`:personal-income-tax` component kind plus a `:pit-base-additions`
hand-off — exactly the pattern US CGT uses for short-term gain → PIT;
(c) the existing `:credits` adjustment-layer slot for the §901 FTC.
Three thin coordination conventions deserve calling out — most
importantly the §1411 NIIT MUST NOT double-count between the CGT
provider (already wired) and this new one (§5 below). Three classes of
provision are deferred: §1031-style rollovers do not apply (this is
income, not disposal); §1297 PFIC and §951 CFC are out of scope for v1
(flag with `:warn` in the provider); §199A QBI explicitly excludes
investment income (the provider must NOT emit a QBI add-back).

---

## §1. The US investment-income regime — the moving parts

The US treats investment income (dividends, interest, royalties,
annuities) as the residual half of `Income` that is NOT
business / wage / SE income. The taxation splits along **three
orthogonal axes**:

### 1.1 Dividends — qualified vs ordinary (IRC §1(h)(11))

Form 1099-DIV reports a single ordinary-dividend total (Box 1a) and a
qualified-dividend SUBSET (Box 1b). The bifurcation:

- **Ordinary dividends** (Box 1a − Box 1b): taxed at the IRC §1
  marginal-rate ladder — wages and bank interest rates, same brackets
  the kernel `us-personal-income-tax-provider` already runs (the seven
  brackets in `period_tax_provider.clj:74-84`).
- **Qualified dividends** (Box 1b): taxed at the §1(h)(11) preferential
  rates — **the same 0 / 15 / 20% stack and same per-filing-status
  thresholds the US CGT provider already builds** as the §1(h) LT
  schedule (see `cgt_provider.clj:lt-schedule:216-237` and the
  parameter-codes `US.CGT.LT.rate-0/15/20` +
  `US.CGT.LT.threshold-<status>-<lane>`). Per IRC §1(h)(11)(B) →
  Cornell LII §1(h):

> "qualified dividend income … shall be treated as net capital gain"

— meaning §1(h)(11) **literally re-routes the dividend through the same
schedule the long-term gain rides**. The provider's job is to put
qualified dividends into the same component pipe the CGT provider
already feeds.

**Holding-period qualification** (§1(h)(11)(B)(iii) → §246(c) modified):
the recipient must have held the share more than **60 days** within the
**121-day window** centered on the ex-dividend date (longer for
preferred stock — 90 days within a 181-day window). The mechanical
determination is a **broker responsibility** in practice: Form 1099-DIV
Box 1b already nets out non-qualifying portions. **The provider trusts
the upstream Box 1b classification** — kontor cannot verify holding
periods from the books alone (it would need every dividend's ex-date
plus the lot acquisition dates, which sit outside the GL).

Other §1(h)(11) qualification carve-outs (the provider trusts the
upstream 1099-DIV split, same as above):
- Issuer must be a **domestic** C-corp OR a **qualified foreign
  corp** (US-treaty country, NYSE/NASDAQ-listed, etc. — §1(h)(11)(C)).
- REIT dividends are USUALLY ordinary (§ 857(c)) — handled by the
  REIT's 1099-DIV mark.
- MLPs (PTPs) typically distribute return-of-capital, not dividend —
  also marked on 1099-DIV.

### 1.2 Interest — taxable vs federally-exempt vs state-exempt

Three tax lanes:

- **Fully taxable interest** (IRC §61(a)(4)): bank interest, corporate
  bond interest, foreign government bond interest — Form 1099-INT
  Box 1; taxed at the §1 ordinary-marginal-rate ladder, same as wages.
- **Federally tax-exempt interest** (IRC §103(a)): interest on **state
  and local bonds** (municipal bonds). Form 1099-INT Box 8 (tax-exempt
  interest) + Box 9 (specified private-activity-bond interest, an AMT
  preference item but still §103-exempt for the regular ladder).
  Important §103 exceptions:
  - **Private-activity bonds** outside the §141 "qualified"
    categories → fully taxable.
  - **Arbitrage bonds** (§148) → fully taxable.
  - **Federally-guaranteed bonds** → fully taxable.
- **Federally taxable, state-exempt interest**: **US Treasury** bonds,
  notes, bills (IRC §6049 + 31 USC §3124) — Box 3 on 1099-INT.
  Treated as ordinary income federally; the **state-exemption is a
  state-PIT-provider concern**, NOT a federal-PIT lever.

**OID — original issue discount** (IRC §§1272-1275): a debt instrument
issued below redemption price accrues interest economically over its
life. The holder must include the **daily OID** in gross income each
year, **regardless of cash receipt** (§1272(a)(1)), under the
**constant-yield method** (§1272(a)(3)) over 6-month accrual periods.
Form 1099-OID reports it. **Three exclusions** from current accrual
(§1272(a)(2)):

1. Tax-exempt obligations.
2. US Savings Bonds (Series EE / I — interest deferred until
   redemption, per Topic 403 + TreasuryDirect).
3. Debt with **fixed maturity ≤ 1 year** from issue (short-term
   discount handled under §1283 separately).

For the provider: **OID is already-realized ordinary interest income
in the period**. It rides the same GL `Income:Interest` lane as bank
interest. The consumer's bookkeeping discipline (book OID monthly /
annually against `Income:Interest:OID`) is what makes the marginalized
aggregate correct. Kontor does NOT compute the daily accrual — that's
the consumer's bookkeeping, fed by 1099-OID Box 1 (the broker-computed
accrual). **Provider posture: a `kontor.book/receive!` on
`Income:Interest:OID` is identical to a `receive!` on
`Income:Interest:Bank` — both fall into the ordinary-interest lane.**

**Market discount** (IRC §§1276-1278): a bond purchased on the
secondary market at a discount to redemption. The gain on disposition
is treated as **ordinary income** up to the accrued market discount
(§1276(a)) — NOT capital gain. The default is ratable accrual deferred
until disposition; a §1278(b) **election** accelerates inclusion to
the year-by-year accrual (irrevocable). For the provider: the
**ordinary-income split** on a market-discount bond sale shows up in
**the US CGT provider's `:ordinary-recapture` lane** (the same lane
§1245 personal-property recapture rides — see `cgt_provider.clj:329-346`,
which folds the recapture into `:pit-base-additions`). NO new
investment-income wiring needed; just documentation that the disposal
schema's `:disposal/depreciation-taken-amount` field on a debt
instrument should be re-purposed as the market-discount accrual, OR
(cleaner) the consumer books the accrued-market-discount portion
through `kontor.book/receive!` to `Income:Interest:MarketDiscount`
**before** booking the disposal. The cleaner discipline keeps the
disposal pure capital and the ordinary slice in the right lane.

### 1.3 §1411 NIIT — the 3.8% surtax (the substrate already does this)

Per IRC §1411(c)(1): **net investment income** = gross interest +
dividends + annuities + royalties + rents + net gain on disposition of
investment property, MINUS allocable deductions, EXCLUDING:

- **§103 tax-exempt interest** (per §1411(c)(1)(A)(i) — gross income
  for §1411 starts from §61 inclusion, and §103 takes muni interest
  OUT of gross income).
- Trade-or-business income (other than passive trade-or-business).
- Distributions from qualified plans (IRAs, 401(k)s — §1411(c)(5)).

Above-MAGI-threshold surtax: 3.8% × min(NII, MAGI − threshold).
Thresholds (NOT inflation-indexed, per §1411(b)): $200k single/HoH,
$250k MFJ, $125k MFS.

**The US CGT provider's `niit-component` already implements this**
(`cgt_provider.clj:348-377` + the `us-niit` compute-fn registered via
`statute/register-compute-fn!:204-208`). The compute-fn reads
`:net-investment-income` and `:magi` from `ctx :inputs` — the consumer
(or, in the new world, **the investment-income provider**) is
responsible for summing the NII inputs across all investment-income
sources. **The new investment-income provider MUST NOT emit a second
NIIT component** — §5 details the coordination convention.

### 1.4 §163(d) — investment-interest expense deduction

Interest **paid** on debt allocable to investment property (margin
loans, REIT-leverage allocations, real-estate-investment-financing
interest) is deductible only up to the year's **net investment income**
(§163(d)(1)). Excess is **carried forward indefinitely** (§163(d)(2)).

Mechanically this is a **deduction against gross investment income at
the §163(d) ceiling**. For the provider:

- The consumer books the investment interest paid via
  `kontor.book/pay!` to `Expense:Interest:Investment` (the discipline
  is a chart-of-accounts convention).
- The provider marginalizes both `Income:Investment*` (positive) and
  `Expense:Interest:Investment` (positive — a debit), and computes
  the deductible amount = min(investment interest paid, net
  investment income) **before NII passes to the NIIT calc**.
- The carry-forward excess is consumer-supplied via `:inputs
  :investment-interest-carryforward` — identical shape to the existing
  `:capital-loss-carryforward` input slot.

**§163(d)(4)(B) "election to treat qualified-dividend-and-LTCG as
investment income for §163(d)"**: the taxpayer can ELECT to throw some
of the preferentially-taxed income into the investment-income pot, so
the §163(d) deduction reaches it — at the cost of losing the
preferential rate on the elected portion. v1 ships WITHOUT the
election; the consumer supplies `:investment-interest-deduction`
directly via `:inputs` and accepts whatever computation they did off-
substrate. v2 may add a `:163d-election-amount` input.

### 1.5 §901 — Foreign tax credit on foreign-source investment income

Foreign withholding on dividends + interest (e.g. 15% German Kapitalert-
ragsteuer on a US holder's DAX dividend; 10% UK on a UK interest
payment) → **§901 credit** against US tax on the foreign-source income.
Form 1116 limits the credit per §904 income basket. For investment
income, the relevant basket is **passive category income**
(§904(d)(2)(A) — dividends, interest, royalties from passive
investments).

For the provider:
- The consumer supplies foreign withholding as a per-foreign-source
  line in `:inputs :foreign-tax-credit-amounts`.
- The provider folds it into the PIT component's `:credits` slot —
  exactly the same shape DE's foreign-tax credit uses
  (`personal_income_tax.clj:84-100`).
- v1 does NOT compute the §904 limitation (the per-basket
  proportionality calculation is non-trivial and depends on TOTAL
  taxable income, not just investment income). v1 caps the FTC at the
  consumer-supplied amount and notes the limit-test is consumer
  responsibility (Form 1116 prepared off-substrate, the substrate
  receives only the post-limit credit).

### 1.6 §199A QBI deduction — the "must not fire" rule

QBI explicitly **excludes** investment income (§ 199A(c)(3)(B)):
- "any item of short-term capital gain, … long-term capital gain, …"
- "any dividend, … or payment in lieu of dividends"
- "any interest income other than interest income which is properly
  allocable to a trade or business"

**Posture**: the investment-income provider MUST NOT emit any QBI
add-back. This is a NEGATIVE design constraint — easy to honor
(don't write the code) but worth flagging so a later "QBI provider"
implementer doesn't get cute with the marginalized income aggregate.

### 1.7 PFIC / CFC — flag, defer

- **§1297 PFIC**: passive foreign investment corp — three regimes
  (§1291 default excess-distribution + interest, §1296
  mark-to-market, §1295 QEF). Each requires Form 8621 and shareholder-
  level tracking of foreign corp asset/income tests + per-share
  excess-distribution allocations — **the substrate cannot determine
  PFIC status from the books**.
- **§951 CFC / §951A GILTI**: similar — controlled-foreign-corp
  Subpart F + GILTI inclusions are computed off-substrate by a
  specialist preparer; the result enters the US holder's return as a
  pre-computed inclusion line.

**v1 posture**: the provider accepts an `:inputs :pre-computed
:pfic-inclusions` / `:gilti-inclusion` slot and folds the value into
ordinary income; it does NOT compute or validate. A `(when (and
pfic? (zero? pfic-inclusion-input)) (warn …))` log line documents the
"likely missing input" case. A future `kontor-l10n-us-pfic` extension
can add real §1291/§1296 computation when consumer demand surfaces.

---

## §2. Worked examples

### 2.1 Pure investment income (MFJ, qualified-dividend lane wins)

Source: IRS Pub 550 ch. 1 + the Qualified Dividends and Capital Gain
Tax Worksheet ("QDCGTW").

A married couple files MFJ for tax year 2026. The W-2 wages add up to
$120 000. They received from a single brokerage account:

- $40 000 ordinary dividends (Box 1a), of which $35 000 are qualified
  (Box 1b).
- $8 000 corporate bond interest (1099-INT Box 1).
- $5 000 muni bond interest (1099-INT Box 8 — §103 exempt).
- $3 000 Treasury bond interest (1099-INT Box 3 — federally taxable).
- $2 000 OID on a corporate discount bond (1099-OID Box 1).

Compute (2026 figures — IRS Rev. Proc. 2025-32):

```
Ordinary income (wages + non-qual div + taxable interest + OID):
  wages              120 000
  ord div            40 000 − 35 000 = 5 000  (the non-qualified slice)
  bank/corp interest 8 000
  Treasury interest  3 000  (federal-taxable)
  OID                2 000
  muni interest      0   (§103-exempt)
  Standard ded MFJ  (30 000)  (approx 2026 figure)
  ────────────────────────────
  Taxable ordinary  108 000

Qualified-dividend lane:
  QD                35 000

Total taxable income for §1(h) stacking purposes:
  108 000 + 35 000 = 143 000   (still inside MFJ 0%-bracket ceiling of $98 900?
                                NO — taxable income exceeds $98 900.)

§1(h) stacking — QDCGTW mechanics:
  Step A: tax on the ordinary 108 000 at MFJ §1 brackets
            ≈ 15 612  (10% on first 23.2k + 12% on next 71.1k + 22% on next 13.7k)
  Step B: apply LT/QD brackets to the 35 000 of QD:
    - 0% bracket ceiling for MFJ 2026: 98 900
    - Ordinary income (108 000) already fills the 0% bracket → $0 at 0%
    - 15% bracket ceiling for MFJ 2026: 613 700
    - Stack QD on top of 108k → QD entirely in the 15% slice = 35 000 × 15% = 5 250
  Step C: tax = 15 612 + 5 250 = 20 862

(Compare: if QD were ordinary, marginal lane = 22% on the bulk → 7 700
on the QD slice, a $2 450 disadvantage. The preferential split is
worth $2 450 here.)
```

What the provider does:
- Marginalize `Income:Dividends:Qualified` → 35 000 (§1(h)(11) lane).
- Marginalize `Income:Dividends:Ordinary-Nonqualified` → 5 000
  (§1 ordinary lane).
- Marginalize `Income:Interest:Bank` + `:Treasury` + `:OID` → 13 000
  (§1 ordinary lane).
- Marginalize `Income:Interest:Municipal` → 5 000 (§103-EXCLUDED —
  the provider sees but **routes to `:line-items` informational only,
  NOT to taxable base**).
- Emit ONE `:personal-income-tax` component with:
  - `:base` = 35 000 (the QD slice; QD has its own brackets);
  - `:schedule` = the existing `lt-schedule` from the CGT provider
    (filing-status MFJ for 2026: 0 / 15 / 20% brackets);
  - `:jurisdiction-specific-codes :pit-base-additions [18 000]`
    (5 000 nonqualified div + 13 000 ordinary interest → the
    consumer threads to the existing PIT provider on the same
    period, so §1 ordinary-rate ladder eats the 18 000);
  - `:line-items` informational: muni-interest 5 000 (§103-exempt),
    NII contribution = 35 000 + 5 000 + 13 000 = 53 000 (NOT 5 000
    of muni — §1411(c)(1)(A)(i) follows §61 inclusion).

**§1(h) stacking nuance**: the QD bracket is determined by where the
QD sits in the *total* taxable income (ordinary + QD), not just the QD
amount alone. The kernel CGT provider's `lt-component`
(`cgt_provider.clj:280-306`) acknowledges this gap with a v1 simple-
bracket: "v1 uses the simple bracket on the LT amount only —
correctly under-reports tax for taxpayers near a bracket cusp". **The
investment-income provider inherits this same gap** and the same
remediation path (v2: pass `:inputs :ordinary-taxable-income-band`
into the `lt-schedule` call so the bracket selection skips the bands
already filled by ordinary income). This is a SHARED follow-up
across the CGT + investment-income providers — both want the same
§1(h) layering fix.

### 2.2 Investment income + NIIT (single, above MAGI threshold)

Same brokerage profile as 2.1 but the taxpayer is single and earns
$280 000 of W-2 wages (total MAGI well above the $200k single NIIT
threshold).

Inputs:
- Wages 280 000.
- QD 35 000.
- Ordinary dividends + interest (taxable) 18 000.
- Muni interest 5 000 (§103-exempt — EXCLUDED from NIIT base).

NIIT calc:
- NII = QD 35 000 + ord div 5 000 + ord interest 13 000 = **53 000**.
  (Muni interest is EXCLUDED — §1411(c)(1)(A)(i) routes through §61,
   and §103 takes muni interest OUT of §61 gross income.)
- MAGI = 280 000 + 35 000 + 18 000 = **333 000** (or whatever the
  consumer computed — MAGI is a §6013 add-back beast; provider
  trusts the input).
- Excess over threshold = 333 000 − 200 000 = 133 000.
- NIIT base = min(NII, excess) = min(53 000, 133 000) = 53 000.
- NIIT = 53 000 × 3.8% = **$2 014**.

**Coordination problem**: the US CGT provider's `niit-component`
already runs the same compute-fn, using `ctx :inputs
:net-investment-income`. If the consumer wires BOTH providers AND
passes the FULL NII (including dividend + interest) to BOTH, the NIIT
fires TWICE — double-count.

The resolution (§5 below): exactly ONE provider emits the NIIT
component per return. The **investment-income provider takes
ownership**, since investment income is the dominant input by typical
volume. The CGT provider's `niit-component` is **suppressed by
configuration** when an investment-income provider is also installed —
or, equivalently, the consumer pre-nets the NII input it passes to
each provider so the CGT provider sees only the capital-gain portion
and the investment-income provider sees only the dividend + interest
portion. **The cleanest convention** (§5 below recommends): the
investment-income provider is the canonical NIIT site, and the CGT
provider's NIIT component is **opt-out** via a constructor flag
(`:emit-niit? false`).

### 2.3 Foreign source dividend + §901 credit

A US single taxpayer receives:
- $4 000 dividend from a German DAX company (qualified — Germany is a
  US-treaty country, US-PE listed ADR), with 15% German Kapitalert-
  ragsteuer withheld → **$600 foreign tax paid**.
- $1 200 US tax on the dividend at the 15% QD rate (no NIIT — below
  thresholds).

The §901 credit (subject to §904 limit, computed off-substrate via
Form 1116):
- Limit ≈ (foreign-source taxable income / total taxable income) ×
  total US tax. The consumer computes this externally; suppose the
  limit shakes out to $600 (well-within).
- Credit applied = 600 (the dollar-for-dollar offset).
- Net US tax on the $4 000 dividend = 1 200 − 600 = **$600**.

Provider mechanics:
- Marginalize `Income:Dividends:Qualified:Foreign-DE` → 4 000 →
  routed to the QD lane (same `lt-schedule`).
- `:inputs :foreign-tax-credit-amounts {:de 600}` → folded into the
  PIT component's `:credits` adjustment-layer slot at code
  `:us-§901-ftc-de` label "Foreign tax credit — DE".
- `:line-items`: a `:de-withholding-paid` informational row, a
  `:de-§901-credit` row showing the credit applied.

---

## §3. Substrate fit — what carries, what doesn't

| Investment-income requirement | Substrate slot | Carries? |
|---|---|---|
| Marginalize dividend income | `kontor.report/marginalize` on `:account-code` for accounts under `Income:Dividends:*` | **Yes** — the QD vs ordinary distinction lives in chart-of-accounts naming convention |
| Marginalize interest income (taxable vs muni) | Same — `Income:Interest:Bank` vs `Income:Interest:Municipal` | **Yes** — chart-of-accounts discipline |
| Apply §1 ordinary brackets to non-QD income | Existing `us-personal-income-tax-provider` (Form 1040 schedule) | **Yes** — fold via `:pit-base-additions` |
| Apply §1(h) 0/15/20 brackets to QD | Existing `lt-schedule` in `kontor.l10n-us.cgt-provider` + parameter table in `cgt-statute.clj:48-97` | **Yes** — REUSE the same schedule constructor |
| Per-filing-status thresholds | `ctx :tax-unit :filing-status` (already routed by the PIT + CGT providers) | **Yes** — same `:single /:mfj /:mfs /:hoh` keyword set |
| §103 muni-interest exclusion | Chart-of-accounts: the marginalize step simply does not look at `Income:Interest:Municipal` for the taxable base; it DOES surface it as a `:line-items` informational row | **Yes** — no new mechanism |
| OID accrual | Consumer's bookkeeping (book daily/monthly OID via `kontor.book/receive!` to `Income:Interest:OID`) | **Yes** — provider does NOT compute |
| Market discount split | Consumer books accrued ordinary portion via `receive!` BEFORE the disposal; the disposal is then pure capital | **Yes** — bookkeeping discipline, no provider work |
| §1411 NIIT | EXISTING `us-niit` compute-fn (statute layer) + `niit-component` (CGT provider) — REUSE | **Yes** — coordinate (§5) |
| §163(d) investment-interest cap | Marginalize `Expense:Interest:Investment`, cap at NII, fold residual to `:inputs :investment-interest-carryforward` | **Yes** — same shape as `:capital-loss-carryforward` |
| §901 FTC | `:inputs :foreign-tax-credit-amounts` → `:credits` adjustment-layer slot | **Yes** — existing PIT credit slot |
| §1(h) stacking (QD on top of ordinary) | v1: simple-bracket gap (under-reports near cusps); v2: pass `:inputs :ordinary-taxable-income-band` to the LT schedule | **Partial** — shared follow-up with CGT provider |
| §199A QBI exclusion | NEGATIVE — provider explicitly does NOT emit a QBI add-back | **N/A** |
| §1297 PFIC / §951 CFC | Pre-computed `:inputs :pfic-inclusions` / `:gilti-inclusion` → fold to ordinary | **Defer to v2** |
| §163(d)(4)(B) elect-to-treat-QD-as-investment-income | v1: consumer supplies the post-election `:investment-interest-deduction`; provider does not compute | **Defer to v2** |
| §1099-DIV / §1099-INT reporting | Provider emits `:line-items` mirroring 1099-DIV / 1099-INT box layout for return preparation | **Yes** — informational layer |

**No new kernel substrate**: every requirement maps to an existing
slot. The provider is pure composition.

### 3.1 Chart-of-accounts discipline (the implicit invariant)

For the marginalize step to work, the consumer chart must keep
investment-income types in separable accounts:

```
Income:Dividends:Qualified
Income:Dividends:Ordinary           (non-qualified portion of 1099-DIV Box 1a)
Income:Dividends:REIT               (always ordinary by default)
Income:Interest:Bank
Income:Interest:Corporate-Bond
Income:Interest:Treasury            (federal-taxable, state-exempt)
Income:Interest:OID
Income:Interest:Market-Discount
Income:Interest:Municipal           (§103-exempt)
Income:Interest:Municipal-PAB-AMT   (§103-exempt regular, AMT preference)
Expense:Interest:Investment         (§163(d)-eligible margin interest)
```

The provider reads `:account/code` prefix-matches; this is a
convention, not enforced by the substrate. A future `kontor-l10n-us`
default-chart helper could ship the recommended chart subset to make
this turnkey.

### 3.2 Where this lives in `period-tax-kinds`

The `period-tax-kinds` enum
(`src/kontor/period_tax_provider.clj:44-61`) is CLOSED. It does NOT
include `:investment-income-tax` — and **it should not**. US
investment-income tax is structurally PIT-with-a-special-lane (the
QD lane reusing the §1(h) brackets), not a structurally new tax kind.
The provider emits **`:personal-income-tax` components** (one for the
QD lane with the §1(h) schedule, one for the ordinary lane that just
contributes `:pit-base-additions`, optionally one for NIIT), all
tagged via `:jurisdiction-specific-codes :lane` exactly the way
`cgt_provider.clj:264-377` tags its lanes (`:st /:lt /:§1250-…`
/`:niit`).

This keeps the enum honest. If a future jurisdiction has a
structurally distinct investment-income tax (say, a flat withholding
regime that doesn't compose with PIT at all — DE Abgeltungsteuer is
the prototype), the question of whether to ADD `:investment-income-tax`
to the enum becomes an ADR decision per that jurisdiction. For the
US, NO enum extension.

---

## §4. `us-investment-income-provider` sketch

### 4.1 Constructor and config

```clojure
(defn us-investment-income-provider
  "US federal investment-income tax (1040 + Sch B + Sch D-equivalent
   for qualified dividends) provider. Composes with
   `us-personal-income-tax-provider` via :pit-base-additions and with
   `us-individual-cgt-provider` via the NIIT-coordination convention
   (§5). Config:

     :emit-niit?  default true — when true, this provider owns the
                  §1411 NIIT computation. When false, the consumer is
                  expected to wire NIIT through some OTHER provider
                  (typically the CGT provider with its own
                  :emit-niit? true)."
  [{:keys [emit-niit?] :or {emit-niit? true}}]
  (->USInvestmentIncomeTaxProvider
   :us-investment-income :us-irs :USD
   "IRC §1(h)(11), §103, §163(d), §901, §1411"
   emit-niit?))
```

### 4.2 Component layout per `TaxReturnFacts`

ONE `TaxReturnFacts` per assessed entity per period, with up to FIVE
components:

1. **QD component** — `:kind :personal-income-tax`, `:base` = qualified
   dividends, `:schedule` = `lt-schedule` (the CGT provider's §1(h)
   bracket assembly), `:gross-liability` = schedule × base, lane =
   `:qualified-dividend`, regime = filing-status.
2. **Ordinary-investment component** — `:kind :personal-income-tax`,
   `:base` = 0 (no own schedule), `:jurisdiction-specific-codes
   :pit-base-additions [<non-QD div + taxable interest + OID +
   §163(d)-adjusted excess>]` — the consumer threads this addition
   into the existing PIT provider on the same period.
3. **§163(d) interest deduction component** — `:kind
   :personal-income-tax`, `:base` = `:investment-interest-deducted`
   (negative — it REDUCES the PIT base via
   `:pit-base-additions [-deduction]`); carry-forward residual in
   `:jurisdiction-specific-codes :investment-interest-carryforward`.
4. **FTC credit component** — `:kind :personal-income-tax`, applied
   as `:credits` adjustment-layer items aggregated across foreign
   jurisdictions; `:liability` = sum of credit values (negative —
   they REDUCE the PIT liability).
5. **NIIT component** (only when `:emit-niit? true`) — same shape as
   the CGT provider's `niit-component`, but the NII input INCLUDES
   the dividend + interest portion (not just gains). The provider
   computes NII = QD + ordinary div + taxable interest (NOT muni) −
   §163(d) deduction, and uses the EXISTING `us-niit` compute-fn
   registered by the CGT provider — REUSE not duplicate.

### 4.3 Base-selector — what marginalizes into what

```clojure
(defn- investment-income-base-selectors [ctx commodity]
  (let [postings (report/report-postings
                  (:conn ctx) {:from   (-> ctx :period :from)
                               :to     (-> ctx :period :to)
                               :entity (:entity ctx)})
        by-code  (report/marginalize postings :account-code
                                     {:sign :inflow :commodity commodity})]
    {:qualified-dividends  (sum-prefix by-code "Income:Dividends:Qualified")
     :ordinary-dividends   (sum-prefix by-code "Income:Dividends:Ordinary")
     :reit-dividends       (sum-prefix by-code "Income:Dividends:REIT")
     :bank-interest        (sum-prefix by-code "Income:Interest:Bank")
     :corp-bond-interest   (sum-prefix by-code "Income:Interest:Corporate")
     :treasury-interest    (sum-prefix by-code "Income:Interest:Treasury")
     :oid-interest         (sum-prefix by-code "Income:Interest:OID")
     :market-discount      (sum-prefix by-code "Income:Interest:Market-Discount")
     :muni-interest        (sum-prefix by-code "Income:Interest:Municipal")
     :investment-interest-paid (sum-prefix by-code "Expense:Interest:Investment")}))
```

The `sum-prefix` helper folds a `marginalize` result over an account-
code prefix. It is provider-internal — analogous to the CGT provider's
`classify` lane-tagger and `sum-lane` aggregator.

### 4.4 Statute file shape — `us-investment-income-statute.clj`

`kontor.statute` parameters + provisions for the US investment-income
regime. **Most parameters already exist** in `cgt-statute.clj` (the
`US.CGT.LT.*` rates and per-filing-status thresholds, the
`US.CGT.§1411.*` rate and thresholds). The investment-income statute
file adds:

```
US.INV.§163d.deduction-rate         — 1.00 (the cap rate; always 100%)
US.INV.§103.exempt-account-pattern  — "Income:Interest:Municipal*"
                                      (a metadata token, NOT used by
                                       the evaluator — documentation
                                       only; the provider hard-codes
                                       the chart convention)
```

And ONE provision — a §163(d) compute-fn registration for the
investment-interest cap (analogous to the §1411 NIIT compute-fn):

```clojure
{:provision/code            "US-IRC-§163d-investment-interest-cap"
 :provision/jurisdiction    :us
 :provision/concept         [:tax-concept/code :deduction-cap]
 :provision/title           "§163(d) — investment interest deduction limited to NII"
 :provision/citation        "https://www.law.cornell.edu/uscode/text/26/163#d"
 :provision/effective-from  #inst "1986-10-22"  ; TRA 1986 source
 :provision/priority        100
 :provision/consequence     (pr-str {:op :compute-fn
                                     :fn :us-163d-cap})}
```

The compute-fn `:us-163d-cap` reads NII (from `ctx :inputs`) and
investment-interest paid (from marginalized expenses), returns
`min(paid, NII)`, surfaces the excess as a carryforward in
`:jurisdiction-specific-codes :investment-interest-carryforward`.

`:tax-concept :deduction-cap` is a new concept code — would extend
ADR-101's 14-concept starter set by one. Per ADR-101, a new concept is
a normal additive operation (the catalogue is "closed-by-ADR"); a
follow-up ADR documents the addition.

### 4.5 Composition — the consumer wires three providers

```clojure
;; In the consumer (e.g. an individual tax return for the period):
(let [period       {:from #inst "2026-01-01" :to #inst "2027-01-01"}
      tax-unit     {:filing-status :mfj}
      inputs       {:tax-unit tax-unit
                    :magi 333000M
                    :foreign-tax-credit-amounts {:de 600M}
                    :investment-interest-carryforward 0M
                    :capital-loss-carryforward {:short 0M :long 0M}}

      ;; 1. Run the investment-income provider first — produces the
      ;;    QD-lane tax + the :pit-base-additions for ordinary stuff +
      ;;    NIIT (since :emit-niit? true).
      inv-facts    (ptp/period-tax-facts
                    inv-prov
                    {:conn conn :entity entity :period period :inputs inputs
                     :db @conn :as-of (:to period)})

      ;; 2. Extract :pit-base-additions, thread into PIT call.
      pit-adds     (reduce + 0M
                           (mapcat #(get-in % [:jurisdiction-specific-codes
                                               :pit-base-additions])
                                   (:components inv-facts)))
      pit-inputs   (-> inputs
                       (assoc :base-transform {:transform/type :adjustments
                                               :adjustments [pit-adds]}))
      pit-facts    (ptp/period-tax-facts
                    pit-prov
                    {:conn conn :entity entity :period period :inputs pit-inputs
                     :db @conn :as-of (:to period)})

      ;; 3. Run CGT provider with :emit-niit? false (NIIT already owned
      ;;    by inv-prov; CGT contributes its cap-gain portion to NII via
      ;;    inputs threading).
      cgt-facts    (ptp/period-tax-facts
                    cgt-prov-no-niit
                    {:conn conn :entity entity :period period :inputs inputs
                     :db @conn :as-of (:to period)})]
  ;; provision = sum of liabilities across the three returns
  [inv-facts pit-facts cgt-facts])
```

The threading is verbose because each provider is an INDEPENDENT
`:personal-income-tax` component producer; the consumer is the
composition site. This is the **same pattern** US CGT already uses
with PIT (`cgt_provider.clj:37-43` describes the explicit-composition
convention). The pattern is intentional: each provider is testable in
isolation, and the composition is auditable.

A **convenience wrapper** is reasonable to ship alongside the
provider — e.g. `kontor.l10n-us/individual-return!` that takes
`{:conn :entity :period :inputs :brokerage-statement}` and threads the
three providers + a `kontor.tax-return-posting-builder` call into one
balanced posting. This is the **ADR-067 process-builder** pattern; the
investment-income provider does not need to embed it.

---

## §5. NIIT coordination — the single hardest design call

The US CGT provider already emits a `niit-component`. The new
investment-income provider also computes NII (a strictly LARGER
quantity than capital gains alone — NII = dividends + interest + capital
gains − allocable deductions). If both providers emit a NIIT line, the
surtax fires twice. The four ways out:

### 5.1 Option A — investment-income provider takes ownership; CGT opts out

**The recommended convention.** The investment-income provider is the
canonical NIIT site (it sees the full NII set: dividends + interest +
the CGT-provider-supplied capital-gain piece). The CGT provider gains
an `:emit-niit?` config flag (default TRUE — preserves existing
behavior for consumers who use only the CGT provider). When both
providers are wired, the consumer passes `:emit-niit? false` to the
CGT provider.

Concretely (CGT provider change):

```clojure
;; cgt-provider.clj: extend the defrecord with emit-niit?
(defrecord USCapitalGainsTaxProvider
           [id source authority commodity statute kind emit-niit?]
  ptp/PeriodTaxProvider
  (period-tax-facts [_ ctx]
    (let [... niit-cmp (when (and emit-niit? (pos? running)) ...)]
      ...)))

(defn us-individual-cgt-provider [{:keys [source id emit-niit?]
                                   :or {emit-niit? true}}]
  (->USCapitalGainsTaxProvider (or id :us-cgt-individual) source :us-irs :USD
                               "IRC §1(h), §1411, §1211(b)" :individual
                               emit-niit?))
```

The investment-income provider wires its NIIT via the **SAME
compute-fn** (`:us-niit`) already registered in `cgt_provider.clj:210`
— it reads NII + MAGI + filing-status from `ctx`, returns the surtax
amount. The compute-fn is provider-agnostic; both providers can call
it, but ONLY ONE emits the resulting component into the
`TaxReturnFacts`.

The consumer feeds the FULL NII to the investment-income provider:

```clojure
:inputs {:net-investment-income (+ qd-amount
                                    ord-div-amount
                                    taxable-interest-amount
                                    (- inv-interest-deduction)   ; §163(d)
                                    capital-gains-amount-from-CGT-facts)
         :magi <consumer-computed>}
```

**Trade-off**: the consumer is responsible for the input arithmetic.
This is consistent with the **explicit composition** posture across
kontor's tax substrate (note 105's adjustment layer + ADR-099's per-
component independence). A convenience helper
(`kontor.l10n-us/aggregate-nii [inv-facts cgt-facts]`) computes the
NII total from the two providers' marginalized outputs, removing the
manual arithmetic.

### 5.2 Option B — emit-niit? defaults FALSE on CGT; investment-income owns

Cleaner — no flag drift. The current CGT provider behavior changes:
NIIT becomes opt-IN on the CGT provider. Consumers who use only the
CGT provider (no investment-income provider in the wire) flip the flag
on at construction.

**Trade-off**: **breaks backward compatibility** for the
`us-individual-cgt-provider` shipped in C1. Acceptable if (a) the
provider is brand-new and not yet in production at any external
consumer, OR (b) we ship a migration ADR that calls the change out.
The CGT provider IS brand-new (per the task description "11 CGT
providers shipped"); if no external consumer depends on it yet, **this
option is cleaner**. The maintainer's call.

### 5.3 Option C — pre-net the NII at the consumer

Each provider receives a PRE-NETTED NII slice via its own `:inputs
:net-investment-income`. Investment-income provider sees only the
dividend + interest portion; CGT provider sees only the gain portion.
Each emits its own NIIT sub-component. The two sub-NIIT amounts SUM
to the correct total.

**Trade-off**: the §1411 threshold subtraction is non-linear — it
fires ONCE on the total NII − threshold excess, not per slice. Splitting
the NII pre-emption introduces a bug where each provider sees only its
slice for the `min(NII, excess)` calc and the totals do not add up
correctly. **Rejected** — only Options A and B are mathematically
clean.

### 5.4 Option D — kernel-level NIIT aggregator

Move NIIT out of both providers into a kernel-level aggregator that
reads ALL `TaxReturnFacts` for a period, sums NII contributions across
components, and emits ONE NIIT component into a synthesized return.

**Trade-off**: introduces a new substrate primitive (an aggregator
across provider outputs — currently no such layer exists). Out of
proportion for a one-jurisdiction problem. **Rejected** for v1; revisit
if other jurisdictions surface the same multi-source-surtax pattern
(e.g. AU Medicare Levy, JP reconstruction surtax — both are currently
single-provider-emitted so the analog hasn't bitten yet).

### 5.5 Recommendation

**Option A** (CGT default `emit-niit? true`, investment-income flips
it to false in compositions) for safety. **Option B** if the maintainer
confirms the CGT provider has no external dependents yet — it is
cleaner. Either way, the convention deserves a sentence in the
investment-income provider's docstring AND a complementary sentence in
the CGT provider's docstring. A test in
`us_investment_income_provider_test.clj` should exercise the
double-count guard explicitly: wire both providers, sum the NIIT
across `:components` of all returned `TaxReturnFacts`, assert
== single 3.8% × NII.

---

## §6. Sources

Statute (canonical citations to Cornell LII Title 26):
- [IRC §1(h)(11) — Qualified dividend income](https://www.law.cornell.edu/uscode/text/26/1#h_11)
  — 0/15/20% rate stack + 60-day-in-121-day holding period via
  §246(c) substitution.
- [IRC §1(h) — Maximum capital gains rate](https://www.law.cornell.edu/uscode/text/26/1#h)
  — the broader §1(h) preferential-rate substrate the QD lane rides.
- [IRC §61 — Gross income defined](https://www.law.cornell.edu/uscode/text/26/61)
  — interest + dividend inclusion baseline.
- [IRC §103 — Interest on State and local bonds](https://www.law.cornell.edu/uscode/text/26/103)
  — federal-tax-exempt muni-bond interest; PAB / arbitrage / federally-
  guaranteed carve-outs (§§141, 148, 149).
- [IRC §1272 — Current inclusion in income of OID](https://www.law.cornell.edu/uscode/text/26/1272)
  — daily OID + constant-yield method + 6-month accrual periods;
  exclusions for tax-exempts, savings bonds, ≤1-year debt.
- [IRC §§1273-1275 — OID rules](https://www.law.cornell.edu/uscode/text/26/subtitle-A/chapter-1/subchapter-P/part-V/subpart-A)
  — issue-price + redemption-price mechanics.
- [IRC §1276 — Market discount as ordinary income on disposition](https://www.law.cornell.edu/uscode/text/26/1276).
- [IRC §1278(b) — Election to accrue market discount currently](https://www.law.cornell.edu/uscode/text/26/1278)
  — the irrevocable election.
- [IRC §163(d) — Investment interest deduction limitation](https://www.law.cornell.edu/uscode/text/26/163#d)
  — cap at NII, indefinite carryforward; §163(d)(4)(B) elect-to-treat-
  QD-as-investment-income.
- [IRC §901 — Taxes of foreign countries](https://www.law.cornell.edu/uscode/text/26/901)
  — the FTC anchor.
- [IRC §904 — Limitation on credit](https://www.law.cornell.edu/uscode/text/26/904)
  — per-basket limitation; passive category includes dividends +
  interest.
- [IRC §1411 — Net Investment Income Tax](https://www.law.cornell.edu/uscode/text/26/1411)
  — 3.8% on NII above MAGI threshold; §1411(c)(1) NII definition;
  §1411(c)(5) qualified-plan exclusion.
- [IRC §199A — QBI deduction](https://www.law.cornell.edu/uscode/text/26/199A)
  — §199A(c)(3)(B) exclusion of dividends + portfolio interest + capital
  gains from QBI.
- [IRC §1297 — PFIC definition](https://www.law.cornell.edu/uscode/text/26/1297);
  [§1291 — Default excess-distribution regime](https://www.law.cornell.edu/uscode/text/26/1291);
  [§1296 — Mark-to-market election](https://www.law.cornell.edu/uscode/text/26/1296);
  [§1295 — QEF election](https://www.law.cornell.edu/uscode/text/26/1295).
- [IRC §951 / §951A — Subpart F + GILTI](https://www.law.cornell.edu/uscode/text/26/951).

IRS publications + form instructions:
- [IRS Publication 550 (2025), "Investment Income and Expenses"](https://www.irs.gov/publications/p550)
  — the canonical practitioner reference: chap. 1 (interest income,
  taxable vs tax-exempt vs OID), chap. 2 (dividends and other
  distributions, qualified vs ordinary, holding-period mechanics),
  chap. 3 (investment expenses + §163(d) cap), chap. 4 (sales and
  trades of investment property — also covered in note 112).
- [IRS Publication 1212 (12/2025), "Guide to Original Issue Discount (OID) Instruments"](https://www.irs.gov/publications/p1212)
  — the OID accrual computation in practitioner detail.
- [IRS Form 1099-DIV instructions (01/2024)](https://www.irs.gov/instructions/i1099div)
  — Box 1a (ordinary div) + Box 1b (qualified div) split; the broker's
  qualification responsibility.
- [IRS Topic 403 — Interest Received](https://www.irs.gov/taxtopics/tc403)
  — 1099-INT lane mechanics; Treasury bonds federal-taxable / state-
  exempt; muni-bond exclusion summary.
- [IRS Topic 404 — Dividends and other corporate distributions](https://www.irs.gov/taxtopics/tc404)
  — practitioner summary of the qualified-vs-ordinary lane.
- [IRS Topic 856 — Foreign tax credit](https://www.irs.gov/taxtopics/tc856).
- [IRS Form 1116 instructions (2025)](https://www.irs.gov/instructions/i1116)
  — the §904 limitation worksheet.
- [IRS Form 8960 instructions — NIIT](https://www.irs.gov/forms-pubs/about-form-8960).
- [IRS Form 8621 instructions (12/2025) — PFIC information](https://www.irs.gov/instructions/i8621).
- [IRS Q&A on Net Investment Income Tax](https://www.irs.gov/newsroom/questions-and-answers-on-the-net-investment-income-tax).
- [TreasuryDirect — Tax information for EE and I bonds](https://www.treasurydirect.gov/savings-bonds/tax-information-ee-i-bonds/)
  — the §1272(a)(2)(B) deferral.
- [IRS Publication 537 — Installment sales](https://www.irs.gov/forms-pubs/about-publication-537)
  (for cross-reference with §1276 / §1278 — installment market discount).
- [IRS Rev. Proc. 2025-32 — 2026 inflation-indexed amounts](https://www.irs.gov/pub/irs-drop/) — the §1(h) thresholds the parameters already track.

kontor substrate (file:line — what the provider reuses):
- `src/kontor/period_tax_provider.clj:44-61` —
  `period-tax-kinds` closed enum; `:personal-income-tax` is what the
  provider emits (no enum extension).
- `src/kontor/period_tax_provider.clj:67-106` — `TaxReturnFacts` +
  component shape; `:jurisdiction-specific-codes :pit-base-additions`
  is the existing hand-off.
- `src/kontor/personal_income_tax.clj:60-118` — `PersonalIncomeTaxProvider`
  + `:adjustments` / `:credits` / `:surtaxes` slot the FTC rides.
- `src/kontor/tax_schedule.clj:64-90` — `:progressive-bracket` + `:flat`
  + `apply-schedule` — the algebra the §1(h) bracket assembly uses.
- `src/kontor/statute.clj` — `apply-provisions` + `parameter-value-at`
  + `register-compute-fn!` — ADR-101 the new statute file plugs into.
- `src/kontor/report.clj:227-280` — `marginalize` + `:account-codes`
  engine — the base-selector.
- `src/kontor/book.clj:227-330` — `receive!` + `pay!` + `adjust!` +
  `declare-dividend!` + `distribute-dividend!`: the verb facade
  shareholders use to book dividend / interest receipts.
- `modules/l10n-us/src/kontor/l10n_us/cgt_provider.clj:216-237` —
  `lt-schedule` — REUSED VERBATIM by the investment-income provider's
  QD lane (read the §1(h) parameters from ADR-101, assemble the
  bracket vector, return a `:progressive-bracket` schedule).
- `modules/l10n-us/src/kontor/l10n_us/cgt_provider.clj:174-211` —
  `us-niit` compute-fn + `register!` — REUSED VERBATIM; the
  investment-income provider does NOT re-register, just calls
  through the same `statute/apply-provisions` path.
- `modules/l10n-us/src/kontor/l10n_us/cgt_provider.clj:348-377` —
  `niit-component` — the SHAPE the investment-income provider
  mirrors when it emits its own NIIT component.
- `modules/l10n-us/src/kontor/l10n_us/cgt_statute.clj:48-127` —
  `parameters` — the `US.CGT.LT.*` rate + threshold parameters the
  investment-income provider READS to assemble the QD lane schedule
  (no new parameters needed for QD; the investment-income statute
  file ADDS only the §163(d) and §103 metadata).
- `modules/l10n-us/src/kontor/l10n_us/cgt_statute.clj:232-250` —
  the `:surtax` provision the investment-income provider IMITATES if
  it owns NIIT emission (Option A / B in §5).
- `modules/l10n-us/src/kontor/l10n_us/period_tax_provider.clj:60-95` —
  `us-personal-income-tax-provider` + the ordinary-rate ladder; the
  investment-income provider feeds it via `:pit-base-additions`.
- `doc/research/112-us-cgt-fit.md` — the sibling note for US CGT;
  this note (148) deliberately mirrors its structure and shares the
  §5 NIIT coordination concern.

Related prior-art (informs design, not citation):
- [Norton Rose Fulbright — Dividends and §1411 surtax (2021)](https://www.projectfinance.law/publications/2021/june/dividends-and-section-1411-surtax)
  — practitioner-side commentary on the NIIT-on-dividends mechanics.
- [The Tax Adviser — Tax Treatment of Market Discount Bonds (2007)](https://www.thetaxadviser.com/issues/2007/oct/taxtreatmentofmarketdiscountbonds/)
  — the §1276 / §1278(b) election trade-off.
- [Bradford Tax Institute — §163(d)(4)(C) text mirror](https://bradfordtaxinstitute.com/Endnotes/IRC_Section_163d.pdf).

---

## §7. Open questions / follow-ups

1. **§1(h) stacking (shared with CGT provider)**: v1 of both
   providers uses the simple bracket on the QD / LT amount alone,
   under-reporting tax for taxpayers near a bracket cusp. A SHARED
   v2 fix: pass `:inputs :ordinary-taxable-income-band` (the value of
   ordinary taxable income already filling the QD / LT brackets'
   bottom slices) into `lt-schedule`'s caller, so the bracket selector
   starts above the ordinary-filled bands. Make this a single
   follow-up note that covers BOTH providers.
2. **§163(d)(4)(B) election** (defer to v2): when the consumer asks
   to treat some QD as investment income for §163(d) purposes,
   the provider needs an extra `:inputs :163d-election-amount` slot
   and must MOVE the elected amount from the QD lane (preferential
   rate) to the ordinary lane (and into NII).
2. **Brokered statement importer** (post-v1): a
   `kontor.l10n-us.brokerage-statement` importer that reads
   1099-DIV / 1099-INT / 1099-OID / 1099-B JSON into the appropriate
   `kontor.book/receive!` calls — parallel to the existing
   `kontor.bank-csv` importer. **Out of scope for v1** (the investment-
   income provider is purely a calculation-time component); a
   companion importer reduces friction at the consumer side.
3. **State investment-income piece**: most US states tax dividends +
   interest at the state-PIT marginal rate; the muni-bond treatment
   varies (often own-state muni is state-exempt, other-state muni is
   state-taxable; Treasury bonds always state-exempt). This is a
   **per-state-PIT-provider** concern; the federal investment-income
   provider does NOT handle it. The Phase D state-PIT iteration
   (deferred per note 102 §10) will add a parallel base-selector layer
   that respects per-state muni-bond classification.
4. **NIIT coordination convention** (§5): the maintainer's call between
   Option A (config flag, conservative) and Option B (flip the CGT
   provider default to opt-IN, cleaner). Resolution before the
   investment-income provider lands.
5. **PFIC v2**: a `kontor-l10n-us-pfic` extension if demand surfaces
   for §1291 / §1296 / §1295 mechanics on direct foreign-mutual-fund
   holdings. The substrate change would likely be small — most PFIC
   work is data input + per-share holding-period bookkeeping, not
   new tax algebra.

---

End of note 148.
