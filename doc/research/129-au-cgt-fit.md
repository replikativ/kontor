---
date: 2026-05-24
title: 129 — AU capital-gains tax — substrate fit for `:disposal` (ADR-102) and the proposed `au-cgt-provider`
status: research-before for note-104 Phase 3 — AU CGT companion of the `kontor-disposal` substrate (ADR-101 / ADR-102) + future `au-cgt-provider`
audience: maintainer + the AU-CGT implementation agent
---

# 129 — AU capital-gains-tax substrate fit

Phase 3 of note 104 ships a `kontor-disposal` companion (ADR-102 — schema
already landed) and per-jurisdiction CGT providers on the ADR-101 statute
substrate. This note assesses the **Australia** fit. AU CGT is the most
structurally distinctive substrate the project will touch on **two
fronts** the prior notes (112 US / 113 DE / 114 UK / 115 JP) did not see:

1. **The 52-event enumeration** of Division 104 ITAA 1997 — every CGT
   computation begins with selecting *which* numbered event (A1 / B1 /
   C1 / … / L8) fired. Most jurisdictions hand-wave "a sale"; AU
   prescribes the event taxonomy in statute as the entry point to the
   regime. Our `:disposal/kind` enum has 7 values; AU has 52.
2. **The 50 % discount as a holder-class function** (`individual /
   trust → 50 %`, `super fund → 1/3`, `company → 0`) NOT a holding-
   period-only function — combined with the per-asset-cumulative
   four-step Subdivision 152 small-business cascade (15-yr exemption →
   50 % active asset reduction → $500 k retirement → rollover) that
   STACKS multiplicatively (50 % discount × 50 % active asset = 75 %
   gross reduction) and carries lifetime caps spanning years.

Plus a sunset gating both: **the 50 % discount is repealed 1 July 2027**
(2026-27 budget — gov.uk/Treasury reform package), replaced by cost-base
indexation + a 30 % minimum effective rate for individuals / trusts /
partnerships. The substrate must carry the law-as-it-stood doctrine for
straddle assets (acquired pre-2027 reform, sold post-).

Bottom line: the `:disposal` schema as landed covers **the core case**
(an A1 share / property sale, with discount or indexation method
selected) and needs **3 small additions** — a CGT-event slot, an
asset-class taxonomy specific to AU, and an `:au-stakeholder-cap-used`
input for the lifetime $500 k retirement cap. Five specialist regimes
(scrip-for-scrip, Div 615 interposition, 6-year absence rule for the
main residence, Div 855 indirect TAP, foreign-resident apportionment)
are deferable to consumer demand; can ride `:disposal/notes` +
`:audit-doc` + provider-input maps in the interim. **NO kernel
changes.**

---

## §1. AU CGT regime — the moving parts

### 1.1 The Division 104 event taxonomy (the 52-event entry point)

ITAA 1997 s104-5 enumerates **52 numbered CGT events** in 12 letter
groups, each tied to a specific situation that triggers a "CGT event"
and its own computation rule (the timing rule, the cost-base rule, the
capital proceeds rule). The taxpayer **must select** the event that
applies — the regime begins with "which event happened?".

| Group | Events | Domain | Anchor section |
|---|---|---|---|
| **A** Disposals | A1 | Disposal of a CGT asset (≈ 90 % of practice) | s104-10 |
| **B** Use & enjoyment before title | B1 | Hire-purchase / right-to-use precedes legal title transfer | s104-15 |
| **C** End of CGT asset | C1 / C2 / C3 | Loss/destruction; cancellation/surrender/expiry of intangible; end of option over shares | s104-20 / s104-25 / s104-30 |
| **D** Creating CGT assets | D1 / D2 / D3 / D4 | Creating contractual rights; granting an option; granting mining right; entering conservation covenant | s104-35..s104-47 |
| **E** Trusts | E1..E10 | Trust creation; trust transfers; conversions; capital payments to beneficiaries; beneficiary entitlement; disposal of trust interest | s104-55..s104-100 |
| **F** Leases | F1..F5 | Granting / renewing / variation / lease premium / capital expenditure on lease | s104-110..s104-130 |
| **G** Shares | G1 / G3 | Capital payment for shares; shares declared worthless by liquidator/administrator (G2 repealed) | s104-135 / s104-145 |
| **H** Special capital receipts | H1 / H2 | Deposit forfeiture; receipts relating to CGT asset (residual catch-all) | s104-150 / s104-155 |
| **I** Ceasing to be a resident | I1 / I2 | Individual/company ceases AU residency; trust ceases AU residency (deemed disposal of non-TAP) | s104-160 / s104-170 |
| **J** Reversal events | J1..J6 | Various rollover-reversal events (J1 company leaves consolidated group; J2 change of business; J4/J5/J6 specific rollover-failure events) | s104-175..s104-198 |
| **K** Other events | K1..K12 | K1 incoming carbon credit; K2 bankruptcy debt payment; K3 asset passing to tax-advantaged entity; K4 asset starts being trading stock; K5 collectable loss; K6 pre-CGT shares; K7 depreciating-asset non-taxable use; K8 direct value shifts; K9 carried-interest entitlement; K10/K11 forex gain/loss; K12 foreign hybrid | s104-205..s104-270 |
| **L** Consolidations | L1..L8 | Tax-cost-setting events on entry/exit of a tax-consolidated group | s104-500..s104-540 |

For the substrate this means: **AU is the only jurisdiction we touch
where the event selection is a first-class statutory step, not an
implied "sale".** The substrate accommodates this by carrying
`:disposal/au-cgt-event` on the `:disposal` (companion-namespace attr,
see §4); the provider reads it; the GL doesn't care.

The event selection is also **mutually exclusive** within event groups:
"if a CGT event in another Subdivision happens, only that event is
relevant" (s102-25(1)) — the higher-priority event suppresses A1.

### 1.2 Rates — the holder-class × method matrix

Unlike US (long vs short fixed rate split) or UK (basic vs higher band),
AU CGT is computed by:

1. Compute the **gain** (proceeds − cost base; if cost base > proceeds
   → switch to **reduced cost base** for the loss computation).
2. Apply the holder's **method choice** (discount or indexation, see
   §1.3) — produces the **net capital gain**.
3. Apply **net capital losses brought forward** + **current-year capital
   losses from other disposals** (one bucket, see §1.6).
4. The net capital gain enters the taxpayer's assessable income (s102-5)
   and is taxed at the **taxpayer's ordinary marginal rate**:
   - **Individuals**: progressive PIT brackets — 19 % / 30 % / 37 % /
     45 % (2026-27, plus 2 % Medicare levy) per the PIT provider.
   - **Companies**: flat 25 % small profits / 30 % standard.
   - **Trusts**: tax flows through to beneficiaries (presently-entitled
     beneficiary taxed at their rate); undistributed gain in trust hand
     taxed at the top marginal rate (45 %).
   - **Super funds**: 15 % accumulation / 0 % pension phase.

So the AU CGT provider does NOT own the rate — the gain folds into the
PIT / CIT provider as ordinary income (s102-5 mechanic). The CGT
provider's job is to compute the **net capital gain** that enters the
income calculation. This mirrors JP sōgō kazei treatment (note 115
§1.1) and is the cleanest substrate fit: **no separate CGT component
in `TaxReturnFacts`** — the gain rides via the GL as a P&L line, and
the existing PIT/CIT provider sweeps it. The AU CGT provider's
contract is "given a period and an entity, return the net capital gain
amount + the supporting line items" — to be **booked as an income
adjustment** read by the PIT/CIT provider.

### 1.3 The 50 % discount vs indexation method — taxpayer election

For an asset acquired **before 21 Sept 1999** AND held > 12 months,
the taxpayer **elects** between two methods (Div 115 + s110-36):

- **Discount method** (default, Div 115) — net capital gain reduced
  by:
  - **50 %** for individuals + trusts (s115-100)
  - **1/3 (33⅓ %)** for complying super funds (s115-100)
  - **0 %** for companies (no discount)
  Cannot be elected if indexation has been applied to the cost base.
- **Indexation method** (s110-36) — cost base elements 1/2/4/5 (NOT
  the 3rd "ownership costs" element) indexed by CPI from the quarter
  of expenditure to the **September quarter 1999** (indexation frozen
  at that point). Discount NOT available alongside.

For an asset acquired **after 21 Sept 1999**: indexation is NOT
available; only the discount method.

For an asset acquired **before 20 Sept 1985** (CGT introduction):
**pre-CGT** — completely outside the regime; gain on disposal exempt
(subject to anti-avoidance — Div 149 deems pre-CGT status lost on
≥ 50 % change of underlying ownership).

For an asset held **≤ 12 months**: neither method available — gain
taxed at full ordinary rates.

### 1.4 The 1 July 2027 reform — `:au-discount-method` sunset

The 2026-27 federal budget (announced 13 May 2026, draft legislation
pending) **repeals the 50 % discount from 1 July 2027** for
individuals / trusts / partnerships and replaces it with:

- **Cost-base indexation** (return of the pre-1999 mechanism, CPI
  applied from acquisition quarter to disposal quarter) for assets
  held > 12 months.
- **30 % minimum effective tax rate** on the indexed gain (a floor —
  if the taxpayer's marginal rate × indexed gain > 30 % × indexed
  gain, the marginal-rate computation prevails; otherwise the 30 %
  floor applies). Pensioners + income-support recipients exempt from
  the floor.
- **New-build investor election** — investors in new-build
  residential property may elect to remain on the 50 % discount on
  the *full* gain (carve-out for housing supply).
- **Straddle assets** (acquired pre-2027, sold post-) — transitional
  rules; gain accrued before 1 July 2027 taxed under the old discount
  regime, gain after under the indexation + 30 % floor.
- **Small-business CGT concessions (Subdivision 152) survive** — the
  4-step cascade unchanged.
- **Companies** are unaffected — they were never eligible for the
  discount.
- **Super funds** are unaffected — 1/3 discount survives.

The substrate consequence is **the elective-regime enum needs to
carry both regimes** so the law-as-it-stood doctrine (note 113 §3.5)
holds: a disposal recorded with `:au-discount-method` on
2027-06-30 stays under that method even if the dataset is queried
post-reform; a disposal recorded with `:au-indexation-method-post-2027`
is the new mechanism. The enum we have (`:au-discount-method` /
`:au-indexation-method`) already separates them; ADR-101 statute
parameters carry the cut-over date.

### 1.5 Cost base — five elements (s110-25)

The cost base is a sum of FIVE statutory elements:

| # | Element | Indexable (pre-1999)? | Comment |
|---|---|---|---|
| 1st | **Acquisition cost** — money paid + property given (s110-25(2)) | Yes | The classic basis number. |
| 2nd | **Incidental costs of acquisition** — stamp duty, legal, valuation, broker (s110-25(3)) | Yes | Acquisition costs of acquisition. |
| 3rd | **Costs of ownership** — interest on borrowing, rates, repairs, insurance (s110-25(4)) | **NO** | Only for assets acquired post-20 Aug 1991; ONLY for non-deductible holding costs (an asset producing assessable income runs these through ordinary deductions — can't be both deducted AND added to basis). |
| 4th | **Capital expenditure on improvements / value preservation** (s110-25(5)) | Yes | Renovations, additions, capital-nature repairs. |
| 5th | **Capital expenditure on title defense** (s110-25(6)) | Yes | Legal costs to perfect / preserve ownership. |

The **reduced cost base** (s110-55) drops the 3rd element entirely and
removes any amounts the holder deducted under other income-tax
provisions (e.g. building depreciation under Div 43). The reduced cost
base is used **only when computing a loss** — the asymmetric mechanic
ensures the same dollar doesn't both produce a deduction (under Div
40/43) and reduce a capital gain (under Part 3-1).

For depreciating assets within Div 40 (the asset-class kontor-asset
already knows about), CGT is fully suppressed — **s118-24**: any gain
or loss on a balancing-adjustment event flows through Div 40 (the
balancing adjustment writes back accumulated depreciation as ordinary
income to the extent of depreciation taken, and any further gain runs
as ordinary income; loss is ordinary deduction). No CGT computation
runs at all for these assets.

**Substrate fit**: the 5-element decomposition is **NOT needed at
schema level** for the provider's computation. The kernel `:disposal`
already carries `:basis-amount` + `:depreciation-taken-amount`; the AU
provider derives basis vs reduced cost base from those two fields
plus the GL (3rd-element ownership costs are by-definition
non-deductible holding expenses already capitalised into the asset
ledger account). The 5-element breakdown is an audit-paper artefact;
the `:audit-doc` should attach the computed-elements worksheet (Excel
or PDF) — that is what the ATO asks to see, not what the provider
recomputes. **Design call: keep the kernel `:disposal` flat; the AU
companion ships an optional worksheet helper that produces the
5-element breakdown for the audit pack.** See §4 below.

### 1.6 Capital losses — the single bucket

AU is the **simplest loss-bucket regime of the four CGT notes** (cf.
US 4 buckets in note 112 §1.3; DE 5 buckets in note 113 §1.4; UK 2
buckets in note 114 §1.4; JP per-asset-class in note 115 §1.4):

- **One bucket**: net capital losses offset capital gains, period.
- **No offset against ordinary income** — unlike US §1211(b) which
  allows $3 000/year against ordinary, AU has no such relief.
- **Indefinite carryforward** — losses sit on the books until used.
- **No carryback** — can't refund prior-year tax.
- **FIFO order** — oldest losses applied first (s102-15).
- **No loss quarantining by asset class** — a residential-property
  loss can offset a listed-share gain (unlike JP).
- **Discount applied AFTER loss offset** — losses offset gross gain
  first, then the 50 % discount applies to the remainder
  (s102-5(1)(b) order). This is taxpayer-favourable: $100 gain less
  $30 loss = $70 net, × 50 % discount = $35 assessable. NOT
  $50 (discounted gain) − $30 (loss) = $20.
- **Collectables sub-bucket** — losses on collectables can ONLY
  offset gains on collectables (s108-10(1)) — the ONE loss-bucket
  exception. Companion-level concern, not provider-default.
- **Connected-person clogged losses** — losses on disposals to
  affiliates / associates restricted to gains on later disposals to
  the same connected person (s102-25, anti-avoidance) — too narrow
  for the substrate; consumer signals via `:audit-doc`.

**Substrate fit**: the existing `:disposal/loss-bucket :au-capital-loss`
(single value) is correct for the default; **add
`:au-collectable-loss` for the collectables compartment** as a
second value (mirrors note 115's compartment-per-asset-class idea, but
AU only needs two compartments not seven).

### 1.7 The Subdivision 152 small-business cascade

The most operationally consequential AU CGT specialty. Four
concessions that **stack in a prescribed order** (s152-10(1A)
prescribes the gateway tests; the stack order itself is
taxpayer-elective):

#### Gateway (Subdivision 152-A) — basic conditions

ALL must hold for the disposal to access ANY of the four concessions:

1. **CGT event A1 (or B1/C1/D1 in limited cases) happens** to the
   asset.
2. The event would (apart from the concessions) result in a **gain**.
3. The disposing entity is a **CGT small business entity** — EITHER
   (a) aggregated turnover < $2M (s328-110, ITAA 1997's small-business
   threshold) OR (b) **maximum net asset value test** — the holder +
   their affiliates + connected entities collectively own ≤ $6M in
   net CGT assets (s152-15) at the moment of the event (snapshot, not
   running average).
4. The asset must satisfy the **active asset test** (s152-35) —
   active for ≥ half the test period if held ≤ 15 years, or ≥ 7.5
   years out of the test period if held > 15 years. An active asset
   (s152-40) is one used in the carrying on of a business by the
   holder, an affiliate, or a connected entity. Listed shares,
   passive rental property, and financial investments are NOT active
   (excluded by s152-40(4)).
5. **For shares / units in a trust**: in addition, the entity must
   pass the **80 % test** (s152-10(2)) — ≥ 80 % of the company's /
   trust's asset value comprises active assets — AND the disposing
   entity must be a **CGT concession stakeholder** (s152-60: natural
   person with ≥ 20 % participation OR a spouse with combined ≥ 20 %)
   OR holds entities with ≥ 90 % participation by CGT concession
   stakeholders.

#### Step 1 — 15-year exemption (Subdivision 152-B, s152-105)

If the asset was held **continuously > 15 years** AND the holder is
≥ 55 (or permanently incapacitated) AND the disposal is in connection
with retirement, the **entire gain is disregarded** — no further
concessions needed (and no further computation runs). This is the
strongest concession and is **mutually exclusive** with the rest of
the cascade.

#### Step 2 — 50 % active asset reduction (Subdivision 152-C, s152-205)

The gain (after the general 50 % discount in §1.3 above for
individuals / trusts) is further **reduced by 50 %**. Stacks
multiplicatively with the general discount → **75 % overall reduction**
for an individual selling an active business asset held > 12 months.
No election needed — applies automatically unless the holder elects
out (s152-210).

#### Step 3 — Small business retirement exemption (Subdivision 152-D, s152-305)

The holder elects to exempt up to **$500 000 lifetime** of gain (cap
is **per CGT concession stakeholder**, not per disposal). If the
holder is < 55 at election time, the exempt amount must be paid into
a complying super fund / RSA. The $500 k cap is **lifetime cumulative
across all prior small-business retirement exemption claims by that
stakeholder** — state that does NOT live in the current year's books
(mirrors UK BADR lifetime cap, note 114 §1.1). The substrate consumes
an `:inputs :au-retirement-cap-used` lifetime-state map.

#### Step 4 — Small business rollover (Subdivision 152-E, s152-410)

The holder elects to **defer** the remaining gain by reinvesting in
a **replacement active asset** within a **2-year period** ending 2
years after the CGT event (s104-197). The deferred gain is recognised
as a J5 / J6 CGT event if the replacement isn't acquired in time, or
if the replacement ceases to be an active asset. Basis of the
replacement is reduced by the deferred gain.

#### Worked stack (the cascade in motion)

A 60-year-old founder sells active business assets held 8 years for
$2 000 000 (cost base $400 000). Gross gain $1 600 000.

- **Gateway**: < $6M net assets, active asset test (8 yrs > 4 yrs
  half-of-test-period) — passes.
- **NOT 15-yr exemption** (only 8 years held).
- **General 50 % discount** (individual, > 12 months): $1 600 000 ×
  50 % = $800 000.
- **152-C 50 % active asset reduction**: $800 000 × 50 % = $400 000.
- **152-D retirement exemption** (elects $400 000, well under $500 k
  cap; → directed to super since age ≥ 55 is not required to be paid
  into super): $400 000 − $400 000 = **$0 assessable gain**.

Effective tax-free disposal of $1 600 000 gain, with $400 000
contribution to super. This is the "owner sells the business" headline
example.

### 1.8 Rollovers (the Division 124 family + Division 122 + Division 615)

AU CGT has the most extensive rollover taxonomy of the four CGT notes,
mostly contained in **Division 124** ("replacement-asset roll-overs"):

| Subdivision | Trigger | Outcome | Substrate fit |
|---|---|---|---|
| **122-A** (s122-15) | Individual / trustee transfers to a wholly-owned company in exchange for shares | Gain deferred; company inherits cost base | `:au-§122-A-rollover` |
| **122-B** | Partnership transfers to a wholly-owned company | Same | (extend enum) |
| **124-B** (s124-70) | **Involuntary disposal** — compulsory acquisition by government / loss by destruction etc. | Gain deferred if replacement acquired within 1 yr before / 1 yr after | `:au-§124-B-involuntary-rollover` (extend enum) |
| **124-M** (s124-780) | **Scrip-for-scrip exchange** — target shareholder receives acquirer shares in takeover | Gain deferred; acquirer-share cost base = original target-share cost base | `:au-§124-M-scrip-for-scrip` |
| **124-N** (s124-855) | Unit-trust → company conversion | Gain deferred; cost-base carryover | (extend enum) |
| **615** (s615-5) | Interposed-company restructure — holding co inserted between shareholders and op-co | Gain deferred; cost base of op-co shares → cost base of new holdco shares | `:au-Div-615-interposition` (extend enum) |
| **152-E** (s152-410) | **Small-business rollover** — replacement active asset | Gain deferred 2 yrs | `:au-§152-rollover` |

For the substrate, **the elective-regime enum needs to carry the
rollover kind** because each has different replacement timing rules,
different basis-carryover rules, and different reversal events (the J
events in §1.1). The kernel `:disposal/rollover-into-asset` + `-amount`
+ `-deadline` carry the data; the AU companion enum-extends
`:elective-regime` with the variants above.

### 1.9 Main residence exemption (Subdivision 118-B)

For an individual's principal residence, the gain is **fully exempt**
(s118-110) provided:

- The dwelling is the taxpayer's main residence throughout the
  ownership period (full exemption).
- The land area ≤ 2 hectares (s118-120).
- The taxpayer was an **Australian tax resident** at the time of the
  CGT event (s118-110(3) — the 2019 amendment denies the exemption
  to non-residents from 30 Jun 2020).

**Partial exemption** (s118-185) applies if the dwelling was only
used as main residence for part of the ownership period — pro-rata
by days.

**6-year absence rule** (s118-145) — if the holder moves out and
rents the property, they can continue to treat it as main residence
for up to 6 years per absence (resets if reoccupied) — provided NO
other dwelling is treated as main residence in the same period.
Indefinite if the property is NOT income-producing during the
absence.

**Substrate fit**: kernel `:disposal/residence?` is the gate. The
6-year absence detail is an audit-fact (consumer signals via
`:audit-doc` carrying the absence-period log); the provider applies
the exemption to the period the asset qualifies. No schema change
needed for v1 — extend if a future consumer needs structured
absence-period tracking.

### 1.10 Foreign residents — Division 855 + indirect TAP

A foreign-resident holder is taxed on AU CGT only on **Taxable
Australian Property** (s855-15 — "TAP"). TAP comprises:

1. **Direct AU real property** (land, leases of land, mining
   tenements).
2. **Indirect interests** — ≥ 10 % membership in an entity (CFC
   or otherwise) where > 50 % of asset value comes from AU real
   property — the **principal asset test** (s855-30).
3. **AU business assets** used through an AU permanent establishment.
4. Options / rights to acquire any of the above.

All other capital gains by foreign residents are disregarded
(s855-10). The 50 % discount is **denied to foreign residents** for
the foreign-residence portion of their ownership period
(s115-115) — apportioned by either market-value method (asset
revalued at 8 May 2012, the apportionment-rules commencement, or
date of non-residence-change) or time-apportionment method.

**Substrate fit**: kernel `:disposal/asset-class :au-foreign-asset-tap`
flags the TAP-AU-real-property case; non-TAP foreign holdings get
**no disposal recorded** (the substrate doesn't need to know
about them — they're irrelevant to AU tax). The
foreign-resident-apportionment of the discount is a provider-side
calculation reading the holder's residence-period history; can ride
`:inputs :au-residence-period-history` map on the period-tax call.

### 1.11 Companies (no CGT discount, gains via CIT)

Australian companies pay **no CGT discount** — capital gains land in
the company's assessable income at the full corporate rate (25 % SBE
/ 30 % standard). The CGT computation still runs (cost base, capital
proceeds, capital losses, rollovers all apply), but the discount
mechanism is closed off. **Holding-company restructures** can use
Div 615 to defer gains on the inserted layer; intercompany asset
transfers within a consolidated group are deferred under the tax
consolidation rules (Part 3-90, ITAA 1997).

For the substrate: companies are handled the same way as individuals
in the AU CGT provider — compute the net capital gain, fold into the
CIT provider as ordinary income (the CIT provider already exists
for AU? — note 102 §10 lists AU as pending; if the AU CIT provider
hasn't shipped yet, the GL line lands and the consumer's CIT
computation reads it).

---

## §2. Worked examples

### Example A — Individual selling listed shares (CGT event A1, discount method)

Marcus, AU resident individual, sells 5 000 BHP shares on
2026-03-15 for $200 000. Acquired 2022-06-01 for $130 000;
$2 000 brokerage on each side.

- **CGT event**: **A1** (s104-10 — disposal of CGT asset).
- **Cost base** (s110-25): 1st element $130 000 + 2nd element
  $2 000 (buy brokerage) + no 3rd / 4th / 5th → **$132 000**.
- **Capital proceeds** (s116-20): $200 000 − $2 000 sell brokerage =
  **$198 000**.
- **Capital gain**: $198 000 − $132 000 = **$66 000**.
- **Holding period**: 2022-06-01 → 2026-03-15 = ~3.8 yrs > 12 months
  → discount-eligible.
- **Method**: discount (post-1999 acquisition; indexation unavailable).
- **Discount**: $66 000 × 50 % = **$33 000 net capital gain**.
- Folds into Marcus's assessable income at his marginal rate (assume
  37 % bracket → **$12 210 tax** on the gain).

Substrate trace: one `:disposal` —

```clojure
{:disposal/kind             :sale
 :disposal/au-cgt-event     :a1                      ;; companion attr
 :disposal/subject-kind     :securities-stock
 :disposal/asset-class      :au-listed-shares        ;; companion enum
 :disposal/acquired-on      #inst "2022-06-01"
 :disposal/disposed-on      #inst "2026-03-15"
 :disposal/holding-period   :long
 :disposal/proceeds-amount  198000M
 :disposal/basis-amount     132000M
 :disposal/elective-regime  #{:au-discount-method}
 :disposal/loss-bucket      :au-capital-loss}
```

The `au-cgt-provider`'s read at year-end groups all `:disposal`s
of the entity, applies losses (none here), applies the 50 %
discount, returns $33 000 as the period net capital gain → posts a
GL line that the PIT provider then sweeps.

### Example B — Individual selling business with 152 cascade

Sarah, 60, AU resident, sells her active business (a café operated
for 8 years) for $2 000 000. Cost base $400 000. < $6M net assets.
Active asset for full 8 years.

- **CGT event**: **A1**.
- **Capital gain**: $2 000 000 − $400 000 = **$1 600 000**.
- **Gateway** (s152-10): passes (≤ $6M net assets, active asset
  test).
- **NOT 15-year exemption** (only 8 yrs).
- **General 50 % discount** (individual, held > 12 months): $1 600 000
  × 50 % = **$800 000**.
- **152-C 50 % active asset reduction**: $800 000 × 50 % = **$400 000**.
- **152-D retirement exemption** (elects $400 000; under $500 k
  lifetime cap; since age ≥ 55, no compulsion to direct to super):
  $400 000 − $400 000 = **$0 assessable gain**.

Substrate trace —

```clojure
{:disposal/kind             :sale
 :disposal/au-cgt-event     :a1
 :disposal/subject-kind     :business-segment
 :disposal/asset-class      :au-active-business-asset
 :disposal/holding-period   :long
 :disposal/proceeds-amount  2000000M
 :disposal/basis-amount     400000M
 :disposal/elective-regime  #{:au-discount-method
                              :au-§152-50-active-reduction
                              :au-§152-retirement-exemption}
 ;; Provider reads `:inputs :au-retirement-cap-used` to confirm cap room
 :disposal/loss-bucket      :au-capital-loss}
```

The `au-cgt-provider` applies the elections in cascade order; emits
a $400 000 retirement-cap consumption line so the next-year `:inputs
:au-retirement-cap-used` increments to $400 000 (cap residual
$100 000 for a future disposal).

### Example C — Scrip-for-scrip rollover (124-M)

Tom holds 10 000 ASX-listed Target Co shares (cost base $50 000).
Acquirer Co makes a scrip-for-scrip takeover offer: 1 Acquirer
share per Target share. Tom accepts; receives 10 000 Acquirer
shares (market value $120 000 at completion).

- **CGT event**: A1 on the Target shares (disposal).
- **Capital gain** absent rollover: $120 000 − $50 000 = **$70 000**.
- **Election**: 124-M scrip-for-scrip — Tom defers the gain; cost
  base of the 10 000 Acquirer shares is **$50 000** (carried over);
  acquisition date for discount-method purposes is the original
  Target acquisition date.

Substrate trace —

```clojure
{:disposal/kind                  :sale
 :disposal/au-cgt-event          :a1
 :disposal/asset-class           :au-listed-shares
 :disposal/proceeds-amount       120000M
 :disposal/basis-amount          50000M
 :disposal/elective-regime       #{:au-§124-M-scrip-for-scrip}
 :disposal/rollover-into-asset   #db/id<acquirer-shares-asset-id>
 :disposal/rollover-amount       70000M  ;; the gain deferred
 :disposal/loss-bucket           :au-capital-loss}
```

The provider sees the rollover election + the linked replacement
asset; emits **$0 current-period gain**; the rollover-amount is
audit-trail (the replacement asset's `:asset/cost-base` should be
adjusted by an asset-side workflow, not by the CGT provider).

---

## §3. `:disposal` schema fit assessment

The schema as shipped (`modules/disposal/src/kontor/disposal/schema.clj`)
fits AU well — better than UK + JP both, which needed more bespoke
attrs. The mapping:

| `:disposal` attr | AU coverage |
|---|---|
| `:disposal/kind :sale` | A1 — covers ~90 % of AU practice. |
| `:disposal/kind :gift` | Triggers A1 (still a disposal under s104-10) + may invoke holdover under Div 122 (uncommon for individuals). |
| `:disposal/kind :abandonment` | C1 (loss/destruction) — fits if the asset is intangible (e.g. derelict shares); for tangible loss, C1 is preferable to A1. |
| `:disposal/kind :conversion` | Could fit C1 + 124-B involuntary rollover (compulsory acquisition under power); AU's mechanism is the s124-70 rollover, not a separate kind. |
| `:disposal/kind :distribution-in-kind` | E4 (entitlement to trust distribution as non-cash) — fits. |
| `:disposal/kind :deemed` | I1 (residency cease) — fits via the `:deemed` value + jurisdiction-tag in `:asset-class`. |
| `:disposal/subject-kind` | The 9 enum values cover AU's class needs adequately, with `:asset-class` as the discriminator (see §4). |
| `:disposal/acquired-on / disposed-on` | Carries the dates the AU classifier reads (the 12-month holding-period test + 1 July 2027 reform cutover). |
| `:disposal/holding-period` | `:short / :long` — `:long` means > 12 months → discount-eligible; `:short` → not. AU does NOT need a third enum value (unlike JP's `:long-residence`). |
| `:disposal/proceeds-amount + basis-amount` | The two figures the provider needs (5-element decomposition is audit-paper, not provider input). |
| `:disposal/depreciation-taken-amount` | Read by the provider to apply s118-24 — if non-zero AND the asset is Div 40, CGT is suppressed and the gain runs as ordinary income (the kernel's existing `:basis-amount` should already be net of depreciation). |
| `:disposal/elective-regime` | Cardinality-many keyword set — perfect fit for the AU cascade (a single disposal can carry `#{:au-discount-method :au-§152-50-active-reduction :au-§152-retirement-exemption}` as in Example B). |
| `:disposal/exemption-claimed` | Carries the dollar-threshold + main-residence exemptions (`:au-collectable-under-500`, `:au-personal-use-under-10k`, `:au-main-residence`). |
| `:disposal/rollover-into-asset / -amount / -deadline` | Carries the Div 124 + 122-A + 615 + 152-E rollover targets. |
| `:disposal/loss-bucket` | Single value `:au-capital-loss` covers default; **add `:au-collectable-loss`** for collectables compartment. |
| `:disposal/realizing-tx` | Audit-trail link to the GL transaction. |
| `:disposal/audit-doc` | Carries the 5-element cost-base worksheet, the 6-year-absence log, the retirement-cap consumption record, etc. |

The schema covers AU. **The three gaps are companion-namespace attrs,
not kernel additions** — see §4.

---

## §4. Concrete data gaps

Three companion-level additions and one `:inputs`-shape clarification.
Zero kernel changes. Zero schedule-algebra changes.

### Gap 1 — `:disposal/au-cgt-event` (companion attr)

Carries the Division 104 event identifier — a closed enum:

```clojure
{:db/ident       :disposal/au-cgt-event
 :db/valueType   :db.type/keyword
 :db/cardinality :db.cardinality/one
 :db/doc         "AU CGT event identifier per ITAA 1997 s104-5
                  Division 104 numbered events. Closed-by-l10n-ADR
                  enum: :a1, :b1, :c1, :c2, :c3, :d1, :d2, :d3,
                  :d4, :e1..:e10, :f1..:f5, :g1, :g3, :h1, :h2,
                  :i1, :i2, :j1..:j6, :k1..:k12, :l1..:l8."}
```

Default at `record-disposal!` time: if `:disposal/kind = :sale` AND
`:subject-kind ∈ #{:fixed-asset :participation :securities-stock}`,
default to `:a1` (covers 90 % of consumer use). Provider reads it to
dispatch to event-specific computation (most events behave like A1;
the C, D, K events have specialty cost-base rules).

**Justification for not living in kernel**: only AU expresses CGT
through a numbered-event taxonomy at statute level; other jurisdictions
mention "events" colloquially or roll the concept into "kind". The
attr is jurisdiction-idiomatic.

### Gap 2 — `:disposal/asset-class` AU value extensions

The kernel attr is already open vocabulary; just enumerate the AU
values. The l10n-au ADR closes the set to:

- `:au-listed-shares` — ASX-listed equity (CGT event A1; discount /
  indexation applicable).
- `:au-property-main-residence` — principal residence within Subdiv
  118-B; full / partial exemption per `:residence?` + audit-doc.
- `:au-property-investment` — investment real estate; CGT event A1,
  no main-residence exemption, discount applies.
- `:au-active-business-asset` — asset gateway for Subdiv 152; the
  active-asset and 80 % tests run on this.
- `:au-goodwill` — business goodwill (often part of an
  `:au-active-business-asset` disposal); CGT event A1; pre-1985
  goodwill exempt under pre-CGT rules.
- `:au-collectable` — artwork, jewellery, antique, coin, stamp; the
  $500 first-element threshold under s118-10(1).
- `:au-personal-use` — boat, car, furniture; the $10 000 first-element
  threshold under s118-10(3).
- `:au-foreign-asset-tap` — Taxable Australian Property held by a
  foreign resident under Div 855.
- `:au-crypto` — cryptocurrency (CGT event A1; no special regime;
  exemption-claim eligibility for personal-use < $10 000 disputed by
  the ATO — see TD 2014/26).

### Gap 3 — `:disposal/elective-regime` AU value extensions

The kernel attr is `:db.cardinality/many` keyword — perfect for the
cascade. The l10n-au ADR adds:

- `:au-discount-method` — Div 115 50 % (individual / trust) / 33⅓ %
  (super fund) / 0 % (company) discount.
- `:au-indexation-method` — s110-36 indexation for pre-21-Sept-1999
  acquisitions.
- `:au-indexation-method-post-2027` — the new mechanism from 1 July
  2027 (assets > 12 months held, CPI from acquisition to disposal,
  30 % min effective rate).
- `:au-new-build-50pct-election` — investor election to remain on
  the discount method for new-build residential property (2027 reform
  carve-out).
- `:au-§152-15y-exemption` — Subdiv 152-B full exemption.
- `:au-§152-50-active-reduction` — Subdiv 152-C 50 % reduction.
- `:au-§152-retirement-exemption` — Subdiv 152-D $500 k lifetime cap
  (provider reads `:inputs :au-retirement-cap-used` to confirm cap
  room).
- `:au-§152-rollover` — Subdiv 152-E 2-yr replacement-asset rollover.
- `:au-§122-A-rollover` — wholly-owned-company rollover.
- `:au-§124-M-scrip-for-scrip` — scrip-for-scrip rollover.
- `:au-§124-B-involuntary-rollover` — compulsory acquisition (s124-70)
  / loss / destruction rollover.
- `:au-§124-N-trust-to-company` — unit-trust → company rollover.
- `:au-Div-615-interposition` — interposed holding-company rollover.

### Gap 4 — `:disposal/exemption-claimed` AU value extensions

Cardinality-many keyword set on the kernel attr — extend with:

- `:au-main-residence` — Subdiv 118-B full exemption.
- `:au-main-residence-partial` — Subdiv 118-B partial (apportioned by
  days; provider reads the audit-doc absence log).
- `:au-collectable-under-500` — s118-10(1) first-element threshold.
- `:au-personal-use-under-10k` — s118-10(3) first-element threshold.
- `:au-pre-cgt-asset` — acquired pre-20 Sept 1985 (subject to Div 149
  deemed-loss rules on > 50 % underlying-ownership change).

### Gap 5 — `:disposal/loss-bucket` AU value extensions

Two values needed (the AU regime is structurally simpler than US / DE):

- `:au-capital-loss` — the default single bucket; carries forward
  indefinitely; offsets capital gains only.
- `:au-collectable-loss` — the one compartmental exception under
  s108-10(1); losses on collectables offset only gains on
  collectables.

### Gap 6 — `:inputs :au-retirement-cap-used` shape

The PeriodTaxProvider call's `:inputs` map must carry the per-entity
running consumption of the $500 k lifetime Subdiv 152-D cap:

```clojure
{:au-retirement-cap-used {:cgt-concession-stakeholder-id  amount}}
```

The map keyed by the stakeholder's identity (a `:partner` or
`:person` ref); the value is the cumulative consumed amount across
prior years. The provider reads the input, computes remaining cap,
caps the elected exemption at `min(elected-amount, $500 k −
cap-used)`. The post-period mutation emits an updated map.

Mirrors UK note 114 §4 Gap 4 (BADR lifetime cap) — same shape, same
machinery; the cap value is the AU $500 k vs UK £1 M.

### Gap 7 — Cost-base 5-element decomposition: NOT a schema concern

§1.5 above already concluded: the 5-element breakdown is an
audit-paper artefact (what the ATO asks to see in a worksheet), not
a provider-input. The kernel `:disposal/basis-amount` carries the
already-summed cost base. The AU companion ships a helper
`au-cost-base-worksheet/build` that, given the `:disposal` + a
consumer-supplied per-element breakdown map (optional), emits an
audit-doc with the 5-element table. **No schema change.**

---

## §5. `au-cgt-provider` sketch

### Architecture — one provider returning a single-component `TaxReturnFacts`

Per §1.2 the AU CGT provider does NOT own the rate. Its contract is
"given a `(period, entity)`, return the **net capital gain** that the
income-tax provider sweeps". That makes it the simplest provider
architecture of the four CGT notes:

```clojure
(defrecord AuCgtProvider []
  PeriodTaxProvider
  (period-tax-facts [_ ctx period entity]
    (let [disposals (disposals-in-period ctx period entity)
          ;; Group by AU CGT event (default A1 for most)
          a1-disps  (filter :a1-or-similar? disposals)
          ;; Apply the 152 cascade gateway tests
          {:keys [eligible ineligible]} (split-by-152-gateway a1-disps ctx)
          ;; Compute per-disposal gain/loss with method election
          gains    (map #(compute-disposal-net % ctx) disposals)
          ;; Apply current-year capital losses (FIFO)
          {:keys [net-gain residual-loss-carryforward]}
            (apply-losses gains
                          (get-in ctx [:inputs :au-capital-loss-carryforward] 0M)
                          (get-in ctx [:inputs :au-collectable-loss-carryforward] 0M))]
      {:kind            :capital-gains-tax
       :authority       :au-ato
       :period          period
       :base            net-gain
       :line-items      (per-disposal-line-items disposals)
       :schedule        nil    ;; AU CGT has no schedule of its own
       :composed-of     []
       :provisions      []     ;; AU CGT does not use ADR-101 provisions;
                               ;; statute is rule-by-asset-class, not
                               ;; bracket-by-income. (Future Phase 4
                               ;; consideration if ADR-101 expands.)
       :reads-inputs   #{:au-capital-loss-carryforward
                          :au-collectable-loss-carryforward
                          :au-retirement-cap-used
                          :au-residence-period-history}
       :emits-inputs   {:au-capital-loss-carryforward residual-loss-carryforward
                         :au-retirement-cap-used        (updated-cap-map ...)}})))
```

**Key design calls**:

1. **No `:schedule` set** — the gain flows into the PIT/CIT provider
   as an income adjustment. The AU CGT provider's `:base` IS the
   final number; no further bracket application.
2. **Multi-component NOT needed** — only one component per period (one
   net capital gain figure). Unlike JP CGT (note 115 §5), AU's losses
   are NOT compartmentalised by asset class (except collectables),
   so the gain consolidates across asset types.
3. **The collectables sub-bucket** is handled internally: the
   provider tracks two parallel carryforwards
   (`:au-capital-loss-carryforward` + `:au-collectable-loss-carryforward`)
   and applies the appropriate losses to the appropriate disposals
   before the discount.
4. **The 1 July 2027 cutover** is a **statute parameter** on ADR-101:

   ```clojure
   {:parameter/id          :au-cgt-discount-method-end
    :parameter/jurisdiction :au-cth
    :parameter/value-fn    (fn [_] #inst "2027-07-01")}
   ```

   The provider reads the parameter at compute time. Disposals with
   `:disposed-on < 2027-07-01` use the discount method; those after
   use the indexation + 30 %-floor mechanism. Bitemporal-safe: the
   `:provision/effective-from` carries the cutover date so a
   bitemporal query (`as-of-valid :before 2027-07-01`) still sees the
   discount in effect.
5. **The 152 cascade is provider-internal logic** — not ADR-101
   provisions (the cascade is a tax-payer-elected ordered sequence,
   not a base-transform / surtax / credit). Documenting the cascade
   in code with extensive comments + audit-doc references is the
   correct posture; trying to express it as provisions would force
   awkward conditionals into a vocabulary not designed for them.

### Substrate stress this provider surfaces

- **None on the schedule algebra** — provider returns a `:base` and
  no schedule; PIT/CIT does the rest. The `:schedule` slot being
  optional / nullable on `TaxReturnFacts` is a kernel ergonomics
  question worth raising in the ADR-101 addendum track.
- **One on the `:disposal` shape** — three companion-namespace attrs
  (`:au-cgt-event`, asset-class enum, elective-regime enum), one
  loss-bucket value addition (`:au-collectable-loss`).
- **One on the `:inputs` shape** — `:au-retirement-cap-used`
  per-stakeholder cap residual map (mirrors UK BADR Gap 4).
- **One on the ADR-101 parameter substrate** — the 2027-07-01
  discount-sunset parameter; standard ADR-101 mechanism.

Total: **0 kernel additions, 0 schedule-algebra additions, 4
companion-namespace additions, 1 ADR-101 parameter, 1 `:inputs`
shape extension.**

---

## §6. Open questions for the implementation agent

1. **Should the AU provider emit a separate `TaxReturnFacts` or fold
   into the AU PIT/CIT provider's `:inputs`?** §5 sketched a separate
   provider. The alternative is to expose `au-net-capital-gain` as
   an `:inputs` key the PIT/CIT provider reads. **Recommendation: separate
   provider.** Reasons: (a) the gain computation is non-trivial
   (cascade + method election + loss application) and benefits from
   isolated unit tests; (b) the audit trail is cleaner — one provider's
   facts cover all CGT calculations; (c) the JP regime (note 115 §5)
   already chose the separate-provider posture; consistency is worth
   something.

2. **Should `:disposal/au-cgt-event` be required or optional?** If
   required, the substrate forces every disposal to choose an event
   even when the consumer only wants A1. Recommendation: **optional
   with default A1** if `:disposal/kind = :sale` AND `:subject-kind
   ∈ #{:fixed-asset :participation :securities-stock :real-estate-private
   :real-estate-investment}`. The provider raises on unmatched cases.

3. **Should the 5-element cost-base worksheet helper live in
   `kontor-disposal` or `l10n-au`?** §1.5 says it's an AU-specific
   audit artefact. **Recommendation: `l10n-au` ships
   `au-cost-base-worksheet/build`**, which produces a `:audit-doc`
   per the AU template. The kernel `:basis-amount` remains the only
   provider input.

4. **How does the AU PIT/CIT provider receive the AU CGT provider's
   output?** The cleanest mechanism is a GL income posting tagged
   with `:posting-dimension :income-type :capital-gain`; the PIT/CIT
   provider's marginalize-over-period reads it as ordinary income.
   The AU CGT provider posts; the AU PIT/CIT provider sweeps. (No
   provider-to-provider direct dependency.)

5. **Foreign-resident apportionment of the discount** — a substrate-
   side helper or out-of-band? The ATO publishes the apportionment
   formulas; encoding them in the AU CGT provider is moderate effort.
   **Recommendation: ship in v1, gated on
   `:disposal/asset-class :au-foreign-asset-tap` + a `:inputs
   :au-residence-period-history` time-series of residency status.**
   Without this, foreign-resident TAP disposals will overstate the
   discount.

6. **The 1 July 2027 reform — when to ship the indexation branch?**
   The reform is announced but pre-legislative. **Recommendation:
   ship discount + a stub for the indexation regime** (the parameter
   exists, the provider branch is documented but raises
   `:not-yet-implemented` until Treasury publishes the indexation
   factor source); add the indexation mechanism in a P2 follow-up
   when the legislation is final. Avoids speculative implementation.

---

## §7. Sources

### AU statute (ITAA 1997)

- **Part 3-1** — General rules about capital gains and capital losses
  (Divisions 100-121).
- **Part 3-3** — Capital gains and capital losses — special topics
  (Divisions 122-180).
- **Division 100** — Guide to capital gains.
- **Division 102, s102-5** — Net capital gain enters assessable income.
- **Division 104, s104-5** — Summary of the 52 CGT events.
  - s104-10 — CGT event A1 disposal of a CGT asset.
  - s104-15 — CGT event B1 use and enjoyment before title.
  - s104-20 — CGT event C1 loss or destruction of CGT asset.
  - s104-25 — CGT event C2 cancellation, surrender, similar endings.
  - s104-35 — CGT event D1 creating contractual or other rights.
  - s104-55 — CGT event E1 creating a trust over a CGT asset.
  - s104-135 — CGT event G1 capital payment for shares.
  - s104-160 — CGT event I1 individual or company ceasing to be AU
    resident.
  - s104-205 — CGT event K1 incoming carbon credit.
  - s104-220 — CGT event K4 asset starts being trading stock.
  - s104-225 — CGT event K5 special capital loss from collectable.
  - s104-230 — CGT event K6 pre-CGT shares.
- **Division 108** — CGT assets — collectables, personal-use assets.
  - s108-10 — Collectables; s108-20 — Personal-use assets.
- **Division 110** — Cost base and reduced cost base.
  - s110-25 — General rules about cost base (5 elements).
  - s110-36 — Indexation.
  - s110-55 — Reduced cost base.
- **Division 115** — Discount capital gains.
  - s115-100 — Discount percentages (50 % individual/trust; 1/3 super
    fund; 0 % company).
  - s115-115 — Foreign-resident apportionment.
- **Division 118** — Exemptions.
  - s118-10 — Collectables and personal-use assets thresholds.
  - s118-24 — Depreciating assets (CGT suppression).
  - s118-110 — Main residence exemption.
  - s118-120 — 2-hectare land limit.
  - s118-145 — 6-year absence rule.
  - s118-185 — Partial main-residence exemption.
- **Division 122** — Rollover for transfer of asset(s) to a wholly-
  owned company.
  - s122-15 — Subdivision 122-A (individual / trustee transfers).
- **Division 124** — Replacement-asset rollovers.
  - s124-70 — Subdivision 124-B (involuntary disposal).
  - s124-780 — Subdivision 124-M (scrip-for-scrip).
  - s124-855 — Subdivision 124-N (unit trust → company).
- **Division 149** — Pre-CGT loss-of-status anti-avoidance.
- **Subdivisions 152-A through 152-E** — Small business CGT
  concessions.
  - s152-10 — Basic conditions; s152-15 — $6M MNAV test.
  - s152-35 — Active asset test; s152-40 — Active asset definition.
  - s152-60 — CGT concession stakeholder.
  - s152-105 — 15-year exemption.
  - s152-205 — 50 % active asset reduction.
  - s152-305 — Retirement exemption ($500 k lifetime).
  - s152-410 — Small business rollover.
- **Division 615** — Roll-overs for business restructures (interposed
  company).
- **Division 855** — Capital gains and foreign residents.
  - s855-10 — Foreign residents disregard non-TAP gains.
  - s855-15 — Taxable Australian property.
  - s855-30 — Principal asset test.
- **Part 3-90** — Tax consolidation; intercompany asset transfer
  deferral.

### ATO guidance

- ATO — [CGT events](https://www.ato.gov.au/individuals-and-families/investments-and-assets/capital-gains-tax/cgt-events).
- ATO — [Does CGT apply to you?](https://www.ato.gov.au/forms-and-instructions/capital-gains-tax-guide-2022/part-a-about-capital-gains-tax/does-capital-gains-tax-apply-to-you).
- ATO — [List of CGT assets and exemptions](https://www.ato.gov.au/individuals-and-families/investments-and-assets/capital-gains-tax/list-of-cgt-assets-and-exemptions).
- ATO — [CGT discount](https://www.ato.gov.au/individuals-and-families/investments-and-assets/capital-gains-tax/cgt-discount).
- ATO — [Indexing the cost base](https://www.ato.gov.au/individuals-and-families/investments-and-assets/capital-gains-tax/calculating-your-cgt/indexing-the-cost-base).
- ATO — [Small business CGT concessions](https://www.ato.gov.au/businesses-and-organisations/income-deductions-and-concessions/incentives-and-concessions/small-business-cgt-concessions).
- ATO — [CGT concessions eligibility overview](https://www.ato.gov.au/businesses-and-organisations/income-deductions-and-concessions/incentives-and-concessions/small-business-cgt-concessions/small-business-cgt-concessions-eligibility-conditions/cgt-concessions-eligibility-overview).
- ATO — [Active asset test](https://www.ato.gov.au/businesses-and-organisations/income-deductions-and-concessions/incentives-and-concessions/small-business-cgt-concessions/small-business-cgt-concessions-eligibility-conditions/active-asset-test).
- ATO — [Additional conditions for shares/trust interests (80 % test)](https://www.ato.gov.au/businesses-and-organisations/income-deductions-and-concessions/incentives-and-concessions/small-business-cgt-concessions/small-business-cgt-concessions-eligibility-conditions/additional-conditions-if-the-cgt-asset-is-a-share-or-trust-interest).
- ATO — [Small business retirement exemption](https://www.ato.gov.au/businesses-and-organisations/income-deductions-and-concessions/incentives-and-concessions/small-business-cgt-concessions/small-business-retirement-exemption).
- ATO — [Small business 15-year exemption](https://www.ato.gov.au/businesses-and-organisations/income-deductions-and-concessions/incentives-and-concessions/small-business-cgt-concessions/small-business-15-year-exemption).
- ATO — [CGT and depreciating assets](https://www.ato.gov.au/businesses-and-organisations/assets-and-property/capital-gains-tax-for-business-assets/depreciating-assets-and-cgt).
- ATO — [Using capital losses to reduce capital gains](https://www.ato.gov.au/individuals-and-families/investments-and-assets/capital-gains-tax/calculating-your-cgt/using-capital-losses-to-reduce-capital-gains).
- ATO — [Involuntary disposal of a CGT asset](https://ato.gov.au/InvoluntarydisposalCGT).
- ATO — [Taxable Australian property](https://www.ato.gov.au/individuals-and-families/investments-and-assets/capital-gains-tax/foreign-residents-and-capital-gains-tax/taxable-australian-property).
- ATO — [CGT discount for foreign residents](https://www.ato.gov.au/individuals-and-families/investments-and-assets/capital-gains-tax/foreign-residents-and-capital-gains-tax/cgt-discount-for-foreign-residents).
- ATO — [Eligibility for main residence exemption](https://www.ato.gov.au/individuals-and-families/investments-and-assets/capital-gains-tax/property-and-capital-gains-tax/your-main-residence---home/eligibility-for-main-residence-exemption).
- ATO — [Treating former home as main residence](https://www.ato.gov.au/individuals-and-families/investments-and-assets/capital-gains-tax/property-and-capital-gains-tax/your-main-residence/treating-former-home-as-main-residence).
- ATO — [TD 1999/40 collectables](https://www.ato.gov.au/law/view/document?locid='TXD/TD199940/NAT/ATO').
- ATO — [TR 2001/12 capital](https://www.ato.gov.au/law/view/pdf?DocId=TXR%2FTR200112%2FNAT%2FATO%2F00001&filename=law%2Fview%2Fpdf%2Fpbr%2Ftr2001-012.pdf&PiT=20040915000001).

### Reform / 2026-27 budget

- Budget 2026-27 — [Tax reform](https://budget.gov.au/content/04-tax-reform.htm).
- Budget 2026-27 — [Negative Gearing and CGT Reform factsheet](https://budget.gov.au/content/factsheets/download/tax-explainers-negative-gearing-capital-gains-tax.pdf).
- William Buck — [Federal Budget 2026 — CGT analysis](https://williambuck.com/tools/federal-budget-2026/capital-gains-tax/).
- H&R Block — [Proposed Capital Gains Tax Changes Australia](https://www.hrblock.com.au/tax-academy/proposed-capital-gains-tax-changes-australia).
- Clayton Utz — [Australian Budget 2026-27: sweeping tax changes](https://www.claytonutz.com/insights/2026/may/australian-budget-2026-27-sweeping-tax-changes-to-bring-foreseeable-and-unintended-consequences-for-investors).
- AusTax.tools — [Small Business CGT Concessions survive 2027 reform](https://austax.tools/tax-insights/cgt-discount-reform-small-business-2027/).
- KPMG — [Federal Budget 2026 analysis](https://assets.kpmg.com/content/dam/kpmgsites/au/pdf/2026/federal-budget-2026-analysis.pdf).

### Big-4 / professional commentary

- BDO — [Court clarifies taxable Australian property rules](https://www.bdo.com.au/en-au/insights/tax/technical-updates/taxable-australian-property-insights-from-ytl-and-newmont-cases).
- Grant Thornton — [Significant foreign resident CGT reforms](https://www.grantthornton.com.au/insights/client-alerts/significant-foreign-resident-cgt-reforms-draft-legislation-released/).
- Grant Thornton — [Small Business CGT Concessions eligibility for selling your business](https://www.grantthornton.com.au/insights/blogs/small-business-cgt-concessions-eligibility-for-selling-your-business/).
- Moore Australia — [Small Business CGT Concessions Explained](https://www.moore-australia.com.au/news/what-are-the-small-business-cgt-concessions/).
- MLC TechConnect — [Guide to CGT small business concessions](https://www.mlc.com.au/content/dam/mlcsecure/adviser/technical/pdf/guide_to_sbcgt.pdf).
- Andersen Australia — [Scrip for Scrip Rollover: Key Considerations](https://au.andersen.com/scrip-for-scrip-rollover/).
- Bristax — [CGT Events](https://bristax.com.au/cgt-articles/cgt-events/) — comprehensive A1–L8 enumeration.
- Bristax — [CGT Event K6](https://bristax.com.au/cgt-articles/cgt-event-k6/) — pre-CGT shares anti-avoidance.
- Bristax — [Scrip for Scrip Rollover](https://bristax.com.au/cgt-articles/scrip-for-scrip-rollover/).
- Bristax — [CGT Main Residence Exemption](https://bristax.com.au/cgt-articles/cgt-main-residence-exemption/).
- Bristax — [Taxable Australian Property](https://bristax.com.au/tax-articles/taxable-australian-property/).
- Pointon Partners — [Small Business CGT — Passing the Threshold Tests](https://pointonpartners.com.au/small-business-cgt-passing-the-threshold-tests/).
- Velocity Legal — [Small Business CGT Concessions Guide + Flowchart 2026](https://www.velocitylegal.com.au/blog/small-business-cgt-concessions-guide-flowchart).
- Tax Talks — [50% CGT discount around companies and trusts](https://www.taxtalks.com.au/articles/cgt-discount/).
- Tax Talks — [Div 40 ITAA97](https://www.taxtalks.com.au/articles/div-40/).
- ATO Tax Rates Info — [Capital Gains Events](https://atotaxrates.info/capital-gains-tax/capital-gains-events/).
- ATO Tax Rates Info — [Some Significant CGT dates](https://atotaxrates.info/capital-gains-tax/some-significant-cgt-dates/).
- Wikipedia — [Capital gains tax in Australia](https://en.wikipedia.org/wiki/Capital_gains_tax_in_Australia).
- Wolters Kluwer — [Valuing Businesses for the $6M MNAV Test](https://www.wolterskluwer.com/en-au/expert-insights/valuing-businesses-for-the-purpose-of-the-6-million-dollar-mnav-test-to-access-cgt-concessions).
- HSF Kramer — [Australia's non-resident CGT changes](https://www.hsfkramer.com/insights/2026-04/australias-non-resident-cgt-changes).

### kontor substrate cited

- `modules/disposal/src/kontor/disposal/schema.clj` — the `:disposal`
  schema this note assesses fit against.
- `doc/research/107-phase-3-incorporation-and-disposal.md` §3 — the
  Phase 3 plan that motivated the `:disposal` substrate.
- `doc/research/112-us-cgt-fit.md` — US fit (loss-bucket comparison
  reference).
- `doc/research/113-de-cgt-fit.md` — DE fit (rollover-relief comparison).
- `doc/research/114-uk-cgt-fit.md` — UK fit (BADR lifetime-cap shape
  reused for AU $500 k retirement cap).
- `doc/research/115-jp-cgt-fit.md` — JP fit (compartment-by-asset-class
  comparison; multi-component vs single-component provider).
- `doc/decisions.md` ADR-099 — the `PeriodTaxProvider` substrate.
- `doc/decisions.md` ADR-101 — statute-as-data substrate; the AU
  2027-07-01 discount-sunset parameter rides this.
- `doc/decisions.md` ADR-102 — `kontor-disposal` companion.

---

End of note 129.
