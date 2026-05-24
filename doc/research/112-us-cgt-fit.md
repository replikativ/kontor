---
date: 2026-05-24
title: 112 — US capital-gains tax — substrate fit for the proposed `:disposal` schema
status: research-before for Phase 3 CGT (note 104); reviews note 107's `:disposal` proposal against the US regime
---

# 112 — US CGT fit against the proposed `:disposal` schema

Phase 3 of note 104 introduces a `kontor-disposal` companion (note 107 §3)
plus a `DisposalSource` protocol; per-jurisdiction CGT providers consume
the protocol. This note assesses whether the proposed `:disposal` schema
carries enough data for US Form 1040 Schedule D / Form 8949 (individuals)
and US Form 1120 Schedule D (C-corporations), and proposes the **minimum**
schema extension to ship a usable US provider.

Bottom line: the proposed schema covers the **two core cases** —
short-vs-long gain or loss on a fully-disposed share/asset — and needs
**3 small additive fields** for the most common US provisions. Five
specialist regimes (§1031, §1202, §453, §121, §1091 wash-sale) are
deferable until consumer demand, with the option to model them out-of-
band via `:disposal/notes` + the existing `:audit-doc` + provider-side
input maps.

---

## §1. US CGT regime — the moving parts

### 1.1 Individuals (Form 1040 Schedule D + Form 8949)

Workflow ([IRS Form 8949 instructions](https://www.irs.gov/forms-pubs/about-form-8949);
[IRS Pub 550, "Investment Income and Expenses"](https://www.irs.gov/forms-pubs/about-publication-550);
[IRS Pub 544, "Sales and Other Dispositions of Assets"](https://www.irs.gov/forms-pubs/about-publication-544)):

1. Each disposition lists on Form 8949 with `(description, date acquired,
   date sold, proceeds, cost basis, adjustment, gain/loss)`.
2. Form 8949 fans into Schedule D **short-term** (held ≤ 1 year — taxed
   at ordinary §1 rates) vs **long-term** (held > 1 year — preferential
   0 % / 15 % / 20 % brackets per §1(h)).
3. Two specialty rate lanes carve out of long-term:
   - **§1250 unrecaptured gain** — depreciation taken on real property
     is recaptured at a 25 % cap rate (Pub 544 ch. 3).
   - **Collectibles + §1202 50 % QSBS exclusion gain** — 28 % cap rate.
4. Capital losses net within each lane; net loss against ordinary income
   capped at $3 000/year (§1211(b)); excess carries forward indefinitely.
5. **Net Investment Income Tax (NIIT) §1411** — additional 3.8 % on net
   investment income above MAGI thresholds ($200k single / $250k MFJ).
6. Specific provisions that change the computation:
   - **§1031 like-kind exchange** — real-property-only since TCJA 2017;
     gain deferred via basis carryover into the replacement property.
   - **§1202 Qualified Small Business Stock** — held 5+ years, issuer is
     a C-corp with ≤ $50M gross assets at issuance, active business
     test; exclude greater of $10M or 10× basis (100 % for stock acquired
     after 2010-09-27).
   - **§121 principal-residence exclusion** — $250k single / $500k MFJ
     on a primary residence owned + used 2 of the last 5 years.
   - **§453 installment sale** — recognise gain pro-rata as payments
     come in; gross profit ratio × principal received each year.
   - **§1091 wash sale** — disallow loss when substantially-identical
     security is reacquired within 30 days (before or after); disallowed
     loss adds to the basis of the replacement.

### 1.2 C-corporations (Form 1120 Schedule D)

C-corps **have no preferential CGT rate** — net capital gain enters
taxable income at the ordinary 21 % §11 rate. The Schedule D still
exists because:

- **Capital losses are quarantined**: a corp's capital losses offset only
  capital gains (§1211(a)); excess is **carried back 3 years and forward 5**
  (§1212(a)) — different from individuals' indefinite forward-only.
- **§1245** (personal property) recaptures all depreciation taken as
  **ordinary income** (Pub 544 ch. 3); only gain above original cost is
  capital.
- **§1250** (real property) recaptures the excess of accelerated over
  straight-line as ordinary; straight-line depreciation IS capital but
  unrecaptured-§1250 only matters for individuals (the 25 % cap doesn't
  apply at the corp level — it's all 21 %).
- §1031 like-kind exchanges and §453 installment sales apply to corps
  too, with the same deferral mechanics.
- §1202 / §121 do NOT apply to corps.

So a corp CGT provider is roughly: classify each disposition into
ordinary-vs-capital, fold capital gains and losses to a net, route the
net to the regular CIT base (no separate schedule), and report the
quarantined loss carry as part of the return.

---

## §2. Two worked examples

### 2.1 Individual — long-term sale + §1202 QSBS exclusion

Source: [IRS Pub 550 ch. 4 "Sales and Trades of Investment Property"](https://www.irs.gov/forms-pubs/about-publication-550).

A founder acquires 1 000 shares of QSBS in NewCo on 2020-03-01 for
$50 000 (basis $50 / share). NewCo's gross assets at issuance were
$8M (under the $50M test). On 2026-04-01 (held 6 years) the founder
sells all shares for $5 050 000 (proceeds $5 050 / share).

- Realised gain = $5 050 000 − $50 000 = $5 000 000.
- §1202 exclusion ceiling = greater of $10M, 10 × $50 000 = $10M.
- All $5 000 000 of gain is excluded.
- Form 8949 reports the disposition with code `Q`; Schedule D includes
  $0 in net long-term gain.

### 2.2 C-corp — depreciated real property + §1250 recapture

Source: [IRS Pub 544 ch. 3](https://www.irs.gov/forms-pubs/about-publication-544).

CorpCo bought a commercial building on 2010-06-01 for $800 000 (land
$200 000, building $600 000). It used straight-line MACRS, taking
$461 538 of depreciation through 2025-12-31 (15.4 years out of 39).
NBV at 2026-04-01 sale = $800 000 − $461 538 = $338 462. Sale proceeds
$1 100 000.

- Total realised gain = $1 100 000 − $338 462 = $761 538.
- §1250 ordinary recapture (excess of accelerated over straight-line)
  = $0 (because straight-line was used).
- Remaining $761 538 is §1231 capital gain; at corp level it folds into
  taxable income at 21 %. Tax = $159 923.
- If CorpCo had a $200 000 unused capital loss carry-in: it offsets the
  $761 538, taxable capital portion = $561 538; tax = $117 923.

These two worked examples expose the data the CGT computation needs:
proceeds, basis, depreciation taken, acquisition date, sale date, the
holding-vehicle's `:kind`, §1202/§121 eligibility, and any loss-carry
input.

---

## §3. `:disposal` schema fit — what carries, what doesn't

Mapping each US computation requirement against note 107 §3.2 / the
maintainer's proposal:

| US requirement | Proposed field | Carries? |
|---|---|---|
| Short vs long-term cutoff (1 year) | `:disposal/holding-period` `:short`/`:long`/`:n-a` | **Yes** — denorm computed from `(acquired-on, effective-date)` |
| Acquisition date for the holding-period rule itself | `:disposal/acquired-on` (in note 107 §3.2) | **Yes** — already in proposal |
| Sale/disposal date | `:disposal/effective-date` (note 107 §3.2) | **Yes** |
| Gross proceeds | `:disposal/proceeds` + `:disposal/proceeds-commodity` | **Yes** |
| Cost basis | `:disposal/basis` + `:disposal/basis-commodity` | **Yes** |
| Subject kind (asset / share / lot / commitment) | `:disposal/subject` (polymorphic) + `:disposal/subject-kind` | **Yes** — `:fixed-asset`/`:participation`/`:inventory`/`:intangible`/`:business-segment` enum reaches the four core cases |
| GL transaction binding (where the gain/loss leg lives) | `:disposal/realizing-tx` → `:transaction` | **Yes** |
| Disposal-authorisation document | `:disposal/audit-doc` | **Yes** |
| Disposition kind for routing | `:disposal/kind` `:sale`/`:incorporation-contribution`/`:abandonment`/`:gift`/`:conversion`/`:distribution-in-kind` | **Mostly** — `:sale` covers Form 8949; `:incorporation-contribution` covers the deemed-disposal seam; `:abandonment`/`:gift`/`:conversion` cover the long tail. **§1031 like-kind is NOT in the enum** — see §4 |

The proposed schema correctly carries the two **structural** axes
(holding period + subject polymorphism) and the four **scalar** axes
(proceeds, basis, dates, kind). For the two **most common** US flows
(LT/ST sale of stock or a fully depreciated fixed asset by an
individual or corp), the schema is **sufficient**: the provider reads
`:disposal/holding-period` to lane the gain, computes
`gain = :proceeds − :basis`, and folds into the period's CGT total.

What the schema **does NOT carry** is the per-provision metadata for the
five specialist regimes (§1031, §1202, §453, §121, §1091). §4 details.

---

## §4. Data gaps — fields the US needs that the proposal lacks

Each gap below names (a) the provision, (b) the missing data, (c) the
minimal additive field, (d) the recommended posture (ship in v1 vs defer).

### Gap A — depreciation taken (for §1245 / §1250 recapture)

**Provision**: §1245 (personal property — depreciation recaptured as
ordinary income up to total depreciation); §1250 (real property — for
individuals, unrecaptured depreciation at 25 % cap rate; for corps,
excess-accelerated-over-straight-line recaptured ordinary).

**Missing**: the cumulative depreciation taken against the subject. This
is queryable from `:asset-depreciation` for a registered `:asset`, but
**not derivable** for an asset held off-register (e.g. an investment
property never registered, a participation in a partnership) or for an
asset acquired pre-kontor (mid-life import).

**Minimal field**: `:disposal/depreciation-taken` (BigDecimal, in
`:disposal/basis-commodity`). The provider reads this to split the
realised gain into the ordinary-recapture portion vs the capital
portion. Caller-supplied; computed from the `:asset` register when
present, supplied by the consumer otherwise.

**Posture**: **ship in v1**. Essential for the corp worked example
(§2.2) and any individual disposing of a rental property or business
equipment. Cheap (one BigDecimal field).

### Gap B — §1031 rollover (deferred gain, basis carryover)

**Provision**: §1031 like-kind exchange (real property only, TCJA 2017).
Gain is **deferred** by carrying the substituted basis into the
replacement property.

**Missing**: pointer to the replacement asset; identification of the
"boot" portion (cash or non-like-kind property received) that IS
recognised.

**Minimal field**: `:disposal/rollover-into-subject` (polymorphic ref,
mirroring `:disposal/subject`) + `:disposal/boot` (Money) +
`:disposal/boot-commodity`. When `:rollover-into-subject` is set, the
provider treats the disposal as deferred: gain recognised = min(realised
gain, boot received); deferred portion = realised gain − recognised.

**Posture**: **defer to a follow-up ADR** — §1031 is post-TCJA narrow
(real property only), used by a small subset of consumers, and adds
cross-disposal state (the new asset's basis depends on the old's). The
v1 provider rejects `:disposal/kind :conversion` with an exception
saying "§1031 not yet supported — record as taxable sale or extend the
schema."

### Gap C — §1202 QSBS qualification

**Provision**: 100 % exclusion (capped at greater of $10M or 10× basis)
for C-corp stock held 5+ years, issuer ≤ $50M gross assets at issuance.

**Missing**: the QSBS-eligibility tag on the disposal (or on the lot);
the per-lot acquisition date is already in `:disposal/acquired-on`, so
the 5-year hold can be computed.

**Minimal field**: `:disposal/qsbs-eligible?` (boolean) +
`:disposal/qsbs-original-basis` (BigDecimal — for the 10× cap;
defaults to `:disposal/basis`). The eligibility test (issuer-status +
active-business + gross-asset cap) is a **consumer responsibility** —
kontor cannot verify it from the books. The provider reads the flag,
computes the exclusion ceiling, and excludes up to that amount.

**Posture**: **defer**. QSBS is high-value but specialist — startup
founders and angel investors. v1 ships without; a `kontor-l10n-us-qsbs`
extension can add it later, OR the consumer routes the excluded gain
via a `:disposal/notes` + an out-of-band `:credits` line on the PIT
return.

### Gap D — §121 principal-residence exclusion

**Provision**: $250k / $500k exclusion on principal-residence sale,
ownership + use 2 of 5 years.

**Missing**: `:use-as-principal-residence` flag + ownership/use period
tracking.

**Minimal field**: `:disposal/principal-residence?` (boolean) +
`:disposal/principal-residence-eligible-amount` (Money — caller-
supplied; the eligibility test is again consumer responsibility).
Provider reads the flag, excludes up to the supplied amount.

**Posture**: **defer**. Most kontor consumers are businesses, not
individuals selling their homes. When demand surfaces, the field is a
trivial v2 add.

### Gap E — §453 installment sale

**Provision**: gain recognised pro-rata as payments collected.

**Missing**: payment schedule (recognition is spread across periods, so
ONE `:disposal` becomes N period-tax events).

**Minimal field**: `:disposal/installment-schedule` (vector of
`{:date :amount}`); the provider computes a gross-profit-ratio and
applies it to each period's collections.

**Posture**: **defer**. Installment sales interact with `kontor-asset`'s
payment-application substrate (ADR-043) and the period-tax provider's
`:base-period` (note 102 §9-E) in non-trivial ways. v1 ships without;
the consumer treats installment sales as cash-basis recognition manually
until demand justifies the lift.

### Gap F — §1091 wash sale (cross-disposal awareness)

**Provision**: disallow a loss when a substantially-identical security
is repurchased within 30 days before or after; the disallowed loss adds
to the basis of the replacement.

**Missing**: cross-disposal awareness — the wash-sale rule needs to
look at ALL `:disposal`s of substantially-identical commodities within
a 60-day window.

**Minimal field**: NONE on the disposal itself; this is a
**provider-time computation** that walks the disposal log. What's
needed at the disposal level is `:disposal/commodity-identifier` (a
ref or string — CUSIP, ticker) so the provider can join "substantially
identical." The existing `:disposal/lot` ref already disambiguates per-
lot disposals; a top-level commodity ref would help with cross-account
matching.

**Posture**: **defer**. Wash-sale detection is broker-statement work in
practice (Form 1099-B already lane-codes for wash sales). The v1
kontor provider trusts the upstream wash-sale lane code; cross-disposal
detection in-kontor is a v3 enhancement when consumers demand it.

### Gap G — NIIT and capital loss carryforward

**Provision**: NIIT 3.8 % on net investment income above MAGI; capital
loss limits ($3 000 individuals, corp's 3-back / 5-forward).

**Missing**: NOTHING — NIIT is a **surtax** on net investment income,
which the PIT provider's existing `:surtaxes` adjustment-layer
(`kontor.personal-income-tax`) supports natively (note 105's adjustment
layer is ordered/signed/base-aware). The capital-loss carry is the
same `:inputs :capital-loss-carryforward` input shape already named in
`kontor.period-tax-provider:138-141` ("`:inputs {:capital-loss-
carryforward {:short <Money> :long <Money>}}`").

**Posture**: **already covered** by the existing substrate; the CGT
provider wires it.

### Summary — what to add in v1

| Field | Provision | Cost |
|---|---|---|
| `:disposal/depreciation-taken` + `-commodity` | §1245, §1250 | trivial — one BigDecimal + commodity ref |
| `:disposal/holding-period` (already in proposal) | ST/LT laning | — |
| `:disposal/acquired-on` (already in proposal) | ST/LT computation, §1202 5-yr hold | — |

**Two NEW fields cover the v1 US provider**. Five specialist provisions
(§1031, §1202, §121, §453, §1091) are deferred to v2 or to specialist
extensions when demand is concrete.

---

## §5. `us-cgt-provider` sketch

### 5.1 Component count

ONE component per disposal lane (short / long / ordinary-recapture),
fanned into ONE `TaxReturnFacts` per assessed entity per period.

- Individual provider returns up to FOUR components: short-term net
  capital gain, long-term net capital gain, §1250 unrecaptured (taxed
  at 25 %), §1411 NIIT surtax.
- Corp provider returns ONE component: net capital portion (folded into
  the regular 21 % CIT base) + the carry tracking in `:line-items`.

### 5.2 Base shape

```clojure
(defrecord UsCapitalGainsTaxProvider
           [id source authority commodity statute kind]   ;; kind = :individual | :corporation
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs] :as ctx}]
    (let [disposals  (disposal-source/disposals-in source entity period)
          {:keys [short long ordinary-recapture]}
          (classify-disposals disposals kind)
          carry-in   (:capital-loss-carryforward inputs)
          ;; … net within lanes, apply carry-in, route to schedules …
          ]
      (ptp/tax-return-facts
       {:entity entity :period period
        :jurisdiction {:authority authority}
        :functional-commodity commodity
        :components [...]}))))
```

### 5.3 `DisposalSource` integration

The provider depends on the protocol, not the companion. Protocol shape
(at minimum):

```clojure
(defprotocol DisposalSource
  (disposals-in [this entity period]
    "All `:disposal`s for `entity` realised in `period`, as plain
     maps with the :disposal/* fields."))
```

`kontor-disposal` ships the canonical implementation against the
companion's schema; a consumer that prefers external storage (e.g.
imports Form 1099-B JSON) writes their own.

### 5.4 Configuration

```clojure
(defn us-individual-cgt-provider
  [{:keys [source]}]
  (->UsCapitalGainsTaxProvider :us-cgt-individual source :us-irs :USD
                               "IRC §1(h), §1411" :individual))

(defn us-corporate-cgt-provider
  [{:keys [source]}]
  (->UsCapitalGainsTaxProvider :us-cgt-corporate source :us-irs :USD
                               "IRC §1211(a), §1212(a)" :corporation))
```

The provider is `:capital-gains-tax` (already in
`period-tax-kinds`, `kontor.period-tax-provider:51-61`). The two-axis
laning is straight `(:disposal/holding-period, :disposal/subject-kind)`
→ `:short` vs `:long` vs `:ordinary-recapture` — the algebra is in
`kontor.tax-schedule` already (a `:progressive-bracket` for the LT
0/15/20 stack, a `:flat 0.25M` for the §1250 lane, the NIIT as a
`:surtax-on` adjustment).

### 5.5 Where the corp version folds in

For corps, the net capital gain DOES NOT go through a separate schedule
— it adjusts the base of `us-corporate-income-tax-provider`. Two clean
options:

1. **The CGT provider returns the net as a `:base-transform :adjustments`
   addition** to the CIT provider's input — composed at the consumer
   level. Most aligned with note 105's adjustment-layer posture.
2. **The CGT provider returns its own `:capital-gains-tax` component**
   with the gain as the base and 21 % as the schedule; the CIT provider
   netted that gain out of its book-profit base via `:base-transform`.

Option 1 wins on faithfulness (the §1212 carry-tracking lives with the
CGT provider; the CIT provider stays a flat-rate atom). Option 2 wins on
substrate parity (every period tax owns its own component). The
maintainer's call at implementation time; ADR-099's multi-component
posture supports either.

---

## §6. Sources

- [IRS Publication 544, "Sales and Other Dispositions of Assets"](https://www.irs.gov/forms-pubs/about-publication-544)
  — §1231 / §1245 / §1250 mechanics; the §2.2 corp recapture example.
- [IRS Publication 550, "Investment Income and Expenses"](https://www.irs.gov/forms-pubs/about-publication-550)
  — Schedule D / Form 8949 workflow; ST vs LT lane definitions; wash-
  sale rule.
- [IRS Form 8949 + instructions](https://www.irs.gov/forms-pubs/about-form-8949)
  — the per-disposition reporting fields kontor's `:disposal` must
  feed.
- [IRS §1202 Qualified Small Business Stock — Topic 409 overview](https://www.irs.gov/taxtopics/tc409)
  + [IRC §1202(d) gross-asset test](https://www.law.cornell.edu/uscode/text/26/1202).
- [IRS §121 principal residence exclusion — Topic 701](https://www.irs.gov/taxtopics/tc701).
- [IRC §1031, post-TCJA real-property-only — IRS FS-2008-18 + Pub 544 ch. 1](https://www.irs.gov/newsroom/like-kind-exchanges-real-estate-tax-tips).
- [IRC §453 installment sales — Pub 537](https://www.irs.gov/forms-pubs/about-publication-537).
- [IRC §1411 Net Investment Income Tax — instructions for Form 8960](https://www.irs.gov/forms-pubs/about-form-8960).
- [IRC §1212(a) corporate capital loss carryover](https://www.law.cornell.edu/uscode/text/26/1212)
  — the 3-back / 5-forward rule used in §1.2.
- kontor substrate (file:line):
  - `src/kontor/period_tax_provider.clj:44-61` — `:capital-gains-tax`
    already in the closed enum.
  - `src/kontor/period_tax_provider.clj:138-141` —
    `:capital-loss-carryforward` `:inputs` shape (already designed).
  - `src/kontor/tax_schedule.clj:64-90` — `:progressive-bracket` /
    `:flat` / `surtax-on` — all algebra the US provider needs.
  - `src/kontor/personal_income_tax.clj:71-83` — adjustment-layer
    pattern the NIIT surtax mirrors.
  - `modules/l10n-us/src/kontor/l10n_us/period_tax_provider.clj:40-49`
    — the shipped 1120 provider; the CGT provider sits beside it.
  - `modules/asset/src/kontor/asset/asset.clj:249-314` — `dispose!`
    integration seam (gap A's depreciation-taken).
  - `doc/research/107-phase-3-incorporation-and-disposal.md` §3.2 — the
    proposed `:disposal` schema this note assesses.

---

End of note 112.
