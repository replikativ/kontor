---
date: 2026-05-24
title: 142 — BR CGT provider — baseline review against authority + note 130
audience: maintainer + future BR-CGT change agents
status: REVIEW-AFTER for ADR-103 BR CGT — independent audit against planalto.gov.br, RFB IN-1500/2014, IN-1585/2015, and research note 130
---

# 142 — BR CGT provider baseline review

This note audits the ADR-103 BR CGT shipment against (a) the canonical
statute on planalto.gov.br, (b) RFB Instruções Normativas, and
(c) the research-before note 130. Files reviewed:

- `modules/l10n-br/src/kontor/l10n_br/cgt_statute.clj` (220 lines)
- `modules/l10n-br/src/kontor/l10n_br/cgt_provider.clj` (551 lines)
- `modules/l10n-br/test/kontor/l10n_br/cgt_provider_test.clj` (519 lines)

Quick verdict: **substrate is sound and tests pass the worked
examples cleanly, but five P1 and two P2 issues warrant follow-up
before the BR provider is exposed to a real PF taxpayer with
multi-asset-class disposals or a PJ Lucro Presumido consumer.**
Zero P0 ship-blockers; the kernel-vs-companion seam is healthy
(no kernel writes from the provider; ADR-101 used correctly).

---

## §1. Scope + method

For each item in the task spec (§1–§8), I traced the implementation
against the statute citation, then re-derived the worked example by
hand. Authority pages used:

- Lei 13.259/2016 art. 21 (per WebSearch confirmation of the four
  brackets 15 / 17.5 / 20 / 22.5 %)
- Lei 11.033/2004 art. 2 + 3 I (the 15 / 20 % B3 split + R$ 20k swing
  isenção, "vendas em cada mês" — total monthly sale value)
- Lei 9.250/95 art. 22 (R$ 35k pequeno-valor)
- Lei 11.196/2005 art. 39 (180-day residence-reinvest)
- IN-RFB-1500/2014 art. 134 / 138 / 139 (PF ganho-capital
  consolidated rules)
- IN-RFB-1585/2015 art. 56 / 63 / 64 (PF/PJ renda-variável)
- Lei 15.270/2025 (PL 1087) — confirmed CGT carve-out

Note 130 §§1–7 was re-read in full.

---

## §2. Item-by-item verdict

### §2.1 Four-bracket ladder (Lei 13.259/2016 art. 21) — PASS

`cgt_statute.clj:160-188` defines the ladder via `parameter-brackets`:

- `:upper 5000000M  :rate 0.15M`
- `:upper 10000000M :rate 0.175M`
- `:upper 30000000M :rate 0.20M`
- (no upper) `:rate 0.225M`

`cgt_provider.clj:268-272` reads it as a `ts/progressive` schedule.

Confirmed against `kontor.tax-schedule/progressive-tax`
(`src/kontor/tax_schedule.clj:68-79`) which treats `:upper` as the
**cumulative ceiling** and walks brackets in order. Manual trace at
R$ 30M and R$ 35M boundary inputs reproduces the test assertions
(`5,625,000M` and `6,750,000M`) exactly.

Tests `four-bracket-ladder-{small-gain,cliff-at-R5M,cliff-at-R10M,
cliff-at-R30M,above-R30M}` cover every bracket boundary plus
spillover. Boundary semantics match Receita's "for each disposal"
walk against year-to-date gain per IN-RFB-1500 art. 138 § 4 (as far
as a single-period query window can model — see §2.6 below for
the multi-disposal stacking caveat).

### §2.2 B3 swing/day rates + IRRF dedo-duro — PASS-with-caveats

`cgt_statute.clj:64-97 / 120-148`:

- swing-rate 0.15M (`:effective-from 2005-01-01`)
- day-rate 0.20M (effective same date)
- IRRF swing 0.00005M (0.005 %)
- IRRF day 0.01M (1 %)

Rates correct against Lei 11.033/2004 art. 2 and IN-RFB-1585/2015
art. 63 (the dedo-duro article).

`cgt_provider.clj:336-388` reads rates and wires IRRF as `:prepaid`:

- swing: `gross = net-gain × 0.15`; prepaid from
  `:inputs :br-irrf-withheld :swing`
- day: `gross = net-gain × 0.20`; prepaid from
  `:inputs :br-irrf-withheld :day`

Tests `swing-irrf-prepayment-flows-to-prepaid` and
`day-trade-irrf-flows-to-prepaid` confirm the prepaid balance maths
(`balance = liability − prepaid`).

**Caveat (P2)** — The IRRF rate parameters live in the statute table
but the provider never reads them. The provider expects the consumer
to supply the IRRF-withheld *amount* directly via `:inputs`, so the
0.00005M / 0.01M rate parameters are documentary-only. That's fine
for v1 (broker statements already report the withheld amount), but
the statute-as-data parameters are dead code unless a future broker-
importer adds a recompute path. Either:
(a) document this explicitly in `cgt_statute.clj` so the next agent
    doesn't think the provider is using them, or
(b) drop the IRRF rate parameters until a consumer needs them.

### §2.3 R$ 35k pequeno-valor + R$ 20k swing exemptions — MIXED

The R$ 20k swing exemption (Lei 11.033 art. 3 I) is implemented
correctly:

- `cgt_provider.clj:447-451` aggregates `:proceeds-amount` by
  `[year month]` across `:br-renda-variavel-long` disposals; if
  monthly total ≤ R$ 20k, every disposal in the month is exempt;
  otherwise none.
- Binary monthly rule matches IN-RFB-1585/2015 art. 56 § 2 (the
  "not just the excess — entire group" position quoted in note 130
  §4 gap A).
- Tests `swing-isenção-{under,over}-R20k-monthly-cap` confirm both
  paths.

The R$ 35k pequeno-valor (Lei 9.250 art. 22) implementation is
**too narrow** (P1):

`cgt_provider.clj:231-238` defines:

```clojure
(defn- pequeno-valor-eligible?
  [{:keys [asset-class]}]
  (= asset-class :br-equity-comum))
```

with a comment claiming "the R$ 35k pequeno-valor isenção (Lei 9.250/
95 art. 22) applies to small-share sales by individuals
(`:br-equity-comum`)".

**This contradicts the statute.** Lei 9.250 art. 22 + IN-RFB-1500/
2014 art. 134 cover "alienações de bens e direitos de pequeno valor"
*broadly* — all movables, foreign currency, and (with caveats) some
real-estate situations under the R$ 35k aggregate, plus a separate
R$ 20k ceiling for "ações alienadas no mercado de balcão" (over-the-
counter shares — distinct from B3 exchange ações). Note 130 §1.1.1
itself says:

> Pequeno valor (small disposal) — Lei 9.250/95 art. 22 + IN-RFB-
> 1500/2014 art. 134: gain on disposals whose monthly aggregate
> price is ≤ R$ 35,000 (R$ 20,000 for shares on the over-the-counter /
> negotiated outside exchange) is fully exempt. The threshold is per-
> month and per-asset-class.

The provider's restriction to `:br-equity-comum` would deny the
exemption to a PF taxpayer disposing of, say, two R$ 15k pieces of
gold or two R$ 12k cars in the same month — a textbook art. 22
case. A pequeno-valor concept that covers ações-em-bolsa
(`:br-equity-comum`) is also strange because B3 shares already get
the R$ 20k swing isenção under Lei 11.033/2004; double-counting two
distinct statutory regimes against the same asset class is at best
confusing and at worst lets a swing-trade-comum disposal evade the
R$ 20k cap by claiming the R$ 35k cap on the same sales month.

**Fix path**: redefine `pequeno-valor-eligible?` to cover the
broader vocabulary (`:br-other-movable`, `:br-foreign-currency-cash`,
the unlisted-OTC variant of `:br-unlisted-share`, etc.), and
**exclude** the B3-managed equity classes (`:br-renda-variavel-long`,
`:br-renda-variavel-day`, `:br-equity-comum` if it represents
exchange-traded shares). This is a closed substantive call that
requires re-reading IN-RFB-1500 art. 134 carefully — defer to the
next agent with a citation-grade re-read.

### §2.4 Residence reinvestment (Lei 11.196/2005 art. 39) — PASS

`cgt_provider.clj:132-141 + 178-192`:

- `claims-residence-reinvest?` accepts either
  `:elective-regime :br-residence-reinvest` or
  `:exemption-claimed :br-art-39-residence-reinvest`
- In `classify`, when claimed AND lane = `:br-ganho-capital`:
  - `reinvest-fraction = rollover-amount / proceeds`
  - `taxable = pre-rollover-gain × (1 − reinvest-fraction)`

The proportional formula matches note 130 §2 Example A exactly:
R$ 1.2M sale × 75 % reinvested → 75 % exempt → R$ 100k taxable →
R$ 15k tax (test `residence-reinvest-exemption-worked-example-a`).

**Small note (P2)** — The 180-day deadline (Lei 11.196/2005 art. 39
+ statute parameter `BR.CGT.PF.art39-reinvest-days = 180M`) is NOT
enforced by the provider. The disposal companion carries
`:rollover-deadline` and the consumer's recorder synthesises it, but
the BR CGT provider never checks whether the deadline has passed
(or whether the reinvestment actually occurred). For an honest
substrate this is the consumer's responsibility — but a defensive
check ("if `:rollover-deadline < as-of` AND no fulfilment evidence,
warn or refuse") would catch consumer mistakes. Leave for v2.

**Also not enforced** (P2): the "**once every 5 years**" rule
(note 130 §1.1.1). Same posture — consumer-side responsibility.

### §2.5 Lei 15.270/2025 (PL 1087) IRPFM carve-out — PASS

`cgt_statute.clj:24-28` and `cgt_provider.clj:44-50` both correctly
document that the IRPFM is a SEPARATE provider on the dividend
ledger and has zero CGT-provider impact. Confirmed against
Mattos Filho commentary (note 130 §1.5). No code wires anything to
IRPFM in this provider — correct.

### §2.6 Three-lane loss-carry isolation — PASS

`cgt_provider.clj:256-262 + 469-471`:

- `:inputs :capital-loss-carryforward` is a per-lane map:
  `{:br-ganho-capital ... :br-renda-variavel-long ...
    :br-renda-variavel-day ...}`
- Each lane is netted against ONLY its own bucket via `net-lane`.

Test `carryforward-cross-lane-isolation` proves a swing carry-in
(`:br-renda-variavel-long 999999M`) does NOT touch the day-trade
lane — assertion holds.

**Concern (P1) — bracket walk across multi-period queries**:
the four-bracket ladder is annual (note 130 §5.2 — Receita walks the
ladder against the **year-to-date gain running-total**). The
provider's docstring (line 52-60) acknowledges this and says
"a consumer aggregating across an entire calendar year gets the
correct cumulative result; a sub-annual period under-counts the
ladder walk for taxpayers above R$ 5M". This is documented but
not test-covered:

- A consumer calling the provider with two consecutive 6-month
  periods on a R$ 8M-gain taxpayer would see each call walk only
  the first bracket → tax = 8M × 0.15 = R$ 1.2M, vs. the correct
  annual walk 5M × 0.15 + 3M × 0.175 = R$ 1.275M (R$ 75k under-
  count per disposal split).

**Mitigation**: add a doc-test (or example) showing the canonical
annual-period usage; add a sibling helper `br-ganho-capital-tx-by-
year` that aggregates multi-period inputs OR re-validate the
docstring's "annual query window" expectation. Could also gate
on `:period` width and raise an informative error when the window
is sub-annual and the gain crosses R$ 5M.

Tests cover only single-period single-call cases.

### §2.7 Lucro Presumido informative error — PASS

`cgt_provider.clj:488-499` checks `:tax-unit :tax-regime`, requires
membership in `#{:lucro-real :lucro-presumido}`, and raises an
informative `ex-info` for `:lucro-presumido`:

> BR CGT v1 supports only :lucro-real. :lucro-presumido has a
> different presumption base (note 130 §1.3) — file an extension
> request with the maintainer.

Test `lucro-presumido-informative-error` confirms the message
matches `#"v1 supports only :lucro-real"`. The message is
informative (cites note 130 §1.3 + maintainer escalation path).

### §2.8 Worked examples (note 130 §2) — PASS

**Example A** (R$ 1.2M sale, R$ 900k reinvest, 25 % taxable →
R$ 15k tax) reproduced exactly by
`residence-reinvest-exemption-worked-example-a`. ✓

**Example B** (May 2026 swing R$ 18k exempt + day R$ 12k gain →
R$ 2,400 tax − R$ 120 IRRF = R$ 2,280 balance) reproduced exactly
by `mixed-b3-month-worked-example-b`, including the combined-DARF
balance arithmetic. ✓

---

## §3. Cross-cutting findings

### §3.1 Corporate path applies PF residence-reinvest reduction (P1)

`cgt_provider.clj:488-505` is the `:corporation` branch:

```clojure
(let [net-cap (- (reduce + 0M (map :gain classified))
                 (or (:br-corp-net-capital carry-in) 0M))
      net-cap' (max 0M net-cap)]
  ...)
```

`classified` was built by `classify`, which applies the art. 39
residence-reinvest reduction (line 179-192) unconditionally when
`:lane = :br-ganho-capital`. A corporation does not have access to
the art. 39 PF residence-reinvest exemption — Lei 11.196/2005 art. 39
is `pessoa física` only. If a corporation records a disposal with
`:elective-regime :br-residence-reinvest`, the provider would
**incorrectly** reduce the gain proportionally.

**Fix**: gate the reinvest reduction in `classify` on a `:kind`
parameter, or have the corporate branch use a separate classifier
that skips the reinvest path. Test coverage is currently zero for
this edge.

### §3.2 §54EC-style provisional double-cap-counting — N/A (this is the IN provider concern)

(Documented in the IN review, note 143 §3.1 — kept here as a
cross-reference; BR has no equivalent multi-rollover-§ stack.)

### §3.3 Statute parameter `decimal-value 180M` for days (P2)

`cgt_statute.clj:151-154`:

```clojure
{:parameter-value/parameter [:parameter/code "BR.CGT.PF.art39-reinvest-days"]
 :parameter-value/decimal-value 180M  ; days as BigDecimal
 ...}
```

A "days" parameter modelled as `:decimal-value 180M` is awkward —
ADR-101 likely has a `:long-value` or `:integer-value` slot that
better expresses "180 calendar days". If the schema only carries
`:decimal-value`, that's fine; otherwise switch to the integer
form. (Not a correctness bug; a cleanliness P2.)

### §3.4 No bitemporal test on the ladder (P2)

Lei 13.259/2016 art. 21 has been stable since 2017-01-01, so a
bitemporal test would be theoretical — but a `:effective-until` on
the ladder is conspicuously absent (the statute does not yet have
a sunset; this matches reality). When a new BR CGT rate cliff
arrives (PL discussions periodically resurface), the bitemporal
slot will need to gain `:effective-until` and a parallel new
bracket-set; the test suite has no precedent here. Compare with
the IN provider, which exercises the FA 2024 cliff cleanly
(note 143 §2.1).

Document the precedent before the next cliff.

### §3.5 `as-of-from-ctx` uses period :to as the bitemporal axis (PASS)

`cgt_provider.clj:143-147`:

```clojure
(defn- as-of-from-ctx [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))
```

Reasonable default — the END of the assessment window is the
canonical "law-as-it-stood" instant for filing purposes. ADR-008
compatible. Consumers can override via `:as-of`.

---

## §4. Schema + companion coupling

### §4.1 ADR-101 usage — clean

`cgt_statute.clj` installs eight parameters (one bracket-scale +
seven scalars). The provider reads via `statute/parameter-value-at`
(line 274-288) and `statute/parameter-brackets-at` (line 272). No
direct datahike `d/q` from the provider — the substrate seam holds.

Idempotent `install!` (line 212-220) is correct: `:parameter/code`
is the unique-identity attr per ADR-101.

### §4.2 Disposal companion usage — clean

The provider reads only documented `:disposal/*` attrs:

- `:disposal/proceeds-amount` (line 130)
- `:disposal/basis-amount` (line 124)
- `:disposal/rollover-amount` (line 125, 181)
- `:disposal/asset-class` (line 175)
- `:disposal/disposed-on` (line 153)
- `:disposal/elective-regime` (line 138)
- `:disposal/exemption-claimed` (line 139)
- `:disposal/external-id` (line 308)

No private companion internals are touched. Good substrate-edge
hygiene.

### §4.3 Provider-test posture — adequate but not exhaustive

The test suite covers all four ladder brackets, both isenções
(R$ 35k binary on/off + R$ 20k binary on/off), three carry-forward
cross-isolation cases, the corporate `:lucro-real` path, and both
worked examples — solid baseline. Gaps:

- No multi-month aggregation test (e.g., April + May `:br-equity-
  comum` disposals: each month aggregates independently).
- No reinvest fraction edge cases (rollover > proceeds → fraction
  > 1; rollover = 0 + reinvest claimed → fraction 0; both produce
  defined but untested arithmetic).
- No test for `:asset-class :br-bdr` / `:br-fii` / `:br-etf` (note
  130 §3.2 vocabulary). The provider's `default-asset-class->lane`
  (line 90-107) doesn't include `:br-fii` (which should be a
  separate FII lane per note 130 §1.2, with its own 20 % rate and
  NO isenção). FII is silently lane-routed to `:br-ganho-capital`
  via the default-fallback (line 178) → **wrong rate** for an FII
  disposal that the consumer thoughtlessly labels with
  `:br-fii`. **P1**.

---

## §5. Severity-ranked follow-ups

| # | Severity | Issue | File:line | Suggested fix |
|---|----------|-------|-----------|---------------|
| 1 | P1 | `pequeno-valor-eligible?` too narrow (only `:br-equity-comum`); contradicts Lei 9.250 art. 22 + IN-RFB-1500 art. 134 | `cgt_provider.clj:231-238` | Re-read art. 134 + redefine the predicate over the broader bens-e-direitos vocabulary; exclude exchange-traded equity |
| 2 | P1 | Corporate path applies the PF-only art. 39 residence-reinvest reduction | `cgt_provider.clj:488-505` (via `classify`'s unconditional reinvest math at 179-192) | Gate reinvest math on `:kind :individual` OR fork the classifier |
| 3 | P1 | `:br-fii` lane is missing from the routing; FII disposals fall back to `:br-ganho-capital` (wrong rate) | `cgt_provider.clj:90-107` | Add `:br-fii` lane + 20 % flat rate component; per-month aggregate isolation; add tests |
| 4 | P1 | Annual-bracket walk is sub-annual-period-broken without warning | `cgt_provider.clj:52-60 + 296` | Add doc-test for the canonical full-year usage; or guard against sub-annual `:period` windows when gain crosses R$ 5M |
| 5 | P2 | IRRF rate parameters in statute table are dead code (provider uses consumer-supplied amounts only) | `cgt_statute.clj:86-97 + 139-148` | Document explicitly OR drop until a recompute path lands |
| 6 | P2 | 180-day reinvest deadline + 5-year frequency rule un-enforced | `cgt_provider.clj:132-141` | Defensive consumer-error check at provider-time, OR document as consumer responsibility |
| 7 | P2 | `:decimal-value 180M` for days is awkward modeling | `cgt_statute.clj:151-154` | Consider integer-typed parameter-value slot if ADR-101 has one |
| 8 | P2 | No bitemporal-cliff precedent in the test suite | `cgt_provider_test.clj` | Add a synthetic-cliff test (e.g. hypothetical 2027 rate change) — even if statute-stable today |
| 9 | P2 | Multi-month aggregation untested | `cgt_provider_test.clj` | Add April + May `:br-equity-comum` disposal test where each month aggregates independently |

**Zero P0 ship-blockers.** The kernel-vs-companion seam is healthy.
The shipped tests (16 deftests / ~60 assertions) prove the core
ladder + reinvest + isenção math and the corporate fold.

---

## §6. Comparison with the per-stage rhythm

Per `CLAUDE.md` §"Per-stage rhythm (Stage I onward)" — the BR shipment
followed steps 1 (research-before — note 130) + 2 (implement —
ADR-103). This note IS step 3 (review-after) for the BR slice.

The P0/P1 hunt yielded ZERO P0s, four P1s, and five P2s. Two of the
P1s (items #1 + #3) reflect an underspecified vocabulary — note 130
§3.2 enumerates 13 asset classes but the provider only routes 13 of
them, partially conflating the conservative `:br-equity-comum`
label. Item #2 (corporate reinvest) is a sharing-classify-across-
kinds bug that's easy to fix.

This is the typical "stage J/K" finding profile — competent
implementation of the documented path, edge cases unprotected.
Recommend ticking the BR roadmap item with a follow-up annotation
pointing at this note's §5 table.

---

## §7. Sources

### Statute primary

- [Lei 13.259/2016](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2016/lei/l13259.htm)
  — art. 21: four-bracket ladder (verified via WebSearch summary of
  the article).
- [Lei 9.250/1995](https://www.planalto.gov.br/ccivil_03/leis/l9250.htm)
  — art. 22 (pequeno-valor), art. 23 (sole-residence ≤ R$ 440k).
- [Lei 11.196/2005 art. 39](https://www.planalto.gov.br/ccivil_03/_ato2004-2006/2005/lei/l11196.htm#art39)
  — 180-day residence-reinvest.
- [Lei 11.033/2004 art. 2-3](https://www.planalto.gov.br/ccivil_03/_ato2004-2006/2004/lei/l11033.htm)
  — 15 % swing / 20 % day; art. 3 I R$ 20k isenção.
- [Lei 15.270/2025 (ex-PL 1087)](https://www.planalto.gov.br/ccivil_03/_Ato2023-2026/2025/Lei/L15270.htm)
  — IRPFM; CGT explicitly carved out.

### Regulatory + commentary

- [IN-RFB-1500/2014](https://normas.receita.fazenda.gov.br/sijut2consulta/link.action?visao=anotado&idAto=57305)
  — art. 134, 138, 139 consolidated PF rules.
- [IN-RFB-1585/2015](https://normas.receita.fazenda.gov.br/sijut2consulta/link.action?visao=anotado&idAto=70004)
  — art. 56, 63, 64 PF/PJ renda-variável.
- [Infomoney — R$ 20k vs R$ 35k teto explanation](https://www.infomoney.com.br/minhas-financas/qual-e-o-teto-de-isencao-para-ganhos-em-vendas-de-acoes-r-20-mil-ou-r-35-mil-2/)
  — the two-ceiling structure (B3 ações vs OTC) cited.
- [Grana — R$ 20k IR isenção](https://blog.grana.capital/2023/08/14/isencao-de-imposto-de-renda-em-acoes-vai-ate-o-limite-de-r-20-mil-em-vendas-por-mes)
  — confirmed the R$ 20k is on gross monthly sales, not gain.

### kontor substrate cited

- `doc/research/130-br-cgt-fit.md` — the research-before; all
  §-references in this review point to it.
- `src/kontor/tax_schedule.clj:64-79` — `progressive-tax` fold
  semantics confirmed by manual trace.
- `src/kontor/statute.clj:150-188` — `parameter-value-at` +
  `parameter-brackets-at` (the ADR-101 read API the provider uses).
- `src/kontor/period_tax_provider.clj:102-184` — `TaxReturnFacts`,
  `total-liability`, `balance` (the protocol the provider implements).
- `modules/disposal/src/kontor/disposal/source.clj:60-87` — period
  filter on `:disposal/disposed-on`, void-excluded; the source the
  provider consumes.
- `modules/disposal/src/kontor/disposal/schema.clj:123-284` — every
  `:disposal/*` attr the provider reads is in the documented
  vocabulary.

---

End of note 142.
