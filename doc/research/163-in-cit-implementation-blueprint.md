---
date: 2026-05-25
title: 163 — IN corporate income tax (CIT) implementation blueprint — ADR-101 statute-as-data
audience: implementation-agent
status: research-before, ready-to-implement
---

# 163 — IN CIT implementation blueprint (Phase 3 / Gap #3 of note 104)

Implementation-ready research-before for the **IN (India) corporate income tax**
provider. After this note lands, an implementation agent uses it to write
`modules/l10n-in/src/kontor/l10n_in/cit_statute.clj` +
`modules/l10n-in/src/kontor/l10n_in/cit_provider.clj` +
`modules/l10n-in/test/kontor/l10n_in/cit_provider_test.clj` against ADR-101's
statute-as-data substrate.

This note **extends** note 122 (substrate-fit cross-check) with the rate
catalogue, MAT composition working, golden-test reference numbers, substrate
stress findings, and an implementation outline. Note 122 confirmed the
substrate carries IN cleanly with one polish item; ADR-101 Addendum 1 has since
shipped `compose-greater-of` + `:op :schedule-override` + the two-pass query
convention, **closing every gap note 122 flagged**. This blueprint is
therefore a "wire it up" deliverable, not a "ship the substrate" one.

The headline up-front:

- **2-component `TaxReturnFacts`** — `:corporate-income-tax` (regular) +
  `:minimum-tax` (MAT §115JB) — composed via `kontor.statute/compose-greater-of`
  per ADR-101 Addendum 1.
- **9 `:parameter`s + 2 `:parameter-bracket` parents** (one for the standard
  surcharge bands, one for the MAT surcharge bands) carrying ~26
  `:parameter-value` rows total — every rate citable to a Finance Act section
  + an `incometax.gov.in` URI.
- **~11 `:provision`s** — three regime-elective `:schedule-override`s
  (§115BAA, §115BAB), three surcharge provisions (standard banded, §115BAA
  flat, §115BAB flat), one cess provision, three MAT provisions (rate,
  surcharge, cess), one foreign-co rate provision.
- **MAT carries cleanly via `compose-greater-of`** (P0-1 from note 122
  closed by ADR-101 Addendum 1). MAT credit (§115JAA, 15-yr carry-forward)
  is **deferred** to v2 — gated on note 105 frontier 2 (the carry primitive).
- **Estimated implementation effort**: ~420 LOC statute + ~160 LOC provider +
  ~350 LOC tests; ~12 deftests / ~55 assertions.

---

## §1. Statutory anatomy

All citations are to the **Income-tax Act 1961** (as in force for **AY 2026-27
/ FY 2025-26**) plus the **Finance Act 2025**, the controlling
yearly-amendment vehicle. Primary citation URIs go to **indiacode.nic.in**
(GoI National Portal, public domain) and **incometaxindia.gov.in** (CBDT,
public domain); commercial commentaries (PwC, KPMG, EY, ClearTax) used as
cross-checks only — no text lifted.

### §1.1 Three CIT regimes for domestic companies

A domestic incorporated business (`Pvt. Ltd.` or `Public Ltd.`) elects one of
**three concurrent regimes** for AY 2026-27:

| Regime | Base rate | Surcharge | Cess | MAT? | Eligibility |
|---|---|---|---|---|---|
| **Standard, small** | **25 %** | 7 % / 12 % banded | 4 % | YES (§115JB) | PY 2023-24 turnover ≤ ₹400 cr |
| **Standard, large** | **30 %** | 7 % / 12 % banded | 4 % | YES (§115JB) | PY 2023-24 turnover > ₹400 cr |
| **§115BAA** | **22 %** flat | **10 %** flat | 4 % | **NO — §115JB(5A) exempts** | Any domestic co. that elects (Form 10-IC, irrevocable) |
| **§115BAB** | **15 %** flat | **10 %** flat | 4 % | **NO — §115JB(5A) exempts** | New manufacturing co. incorporated ≥ 1 Oct 2019, commenced manufacturing ≤ 31 Mar 2024 (sunset hit; see §1.4) |

Foreign companies pay a separate flat rate — see §1.6.

**Statute citations.**

- **25 % standard small** — **Finance (No. 2) Act 2019**, raised the
  turnover threshold from ₹250 cr to ₹400 cr. Codified in
  Finance Act First Schedule Part III paragraph A reading with
  **ITA §4** (charge of income-tax).
  - URI: <https://www.indiacode.nic.in/handle/123456789/15259>
- **30 % standard large** — same Finance Act table, "any other domestic
  company" row.
- **§115BAA concessional 22 %** — **Taxation Laws (Amendment) Act 2019**
  (Act 46 of 2019), inserted §115BAA effective AY 2020-21.
  - URI: <https://incometaxindia.gov.in/Acts/Income-tax+Act,+1961/2025/102120000000088566.htm>
- **§115BAB concessional 15 %** — same Act 46 of 2019, inserted §115BAB
  effective for cos. incorporated ≥ 2019-10-01.
  - URI: <https://incometaxindia.gov.in/Acts/Income-tax+Act,+1961/2025/102120000000088567.htm>

### §1.2 Surcharge

#### Standard regime — banded with marginal relief

| Total income | Surcharge |
|---|---|
| ≤ ₹1 cr | 0 % |
| > ₹1 cr and ≤ ₹10 cr | **7 %** |
| > ₹10 cr | **12 %** |

Cited from **Finance Act 2025 First Schedule, Part III, paragraph A,
Surcharge proviso**. Both bands carry the **statutory marginal-relief rule**
(applied via the Finance Act proviso): at each threshold, `(tax + surcharge)`
on the higher income may not exceed `(tax at exactly the threshold) +
(income beyond the threshold)`. The relief is the breach amount, if any;
when positive it reduces the surcharge.

#### Concessional regimes (§115BAA / §115BAB)

**Flat 10 %** surcharge regardless of income. No marginal relief needed
(flat → no cliff). Cited from Finance Act 2025 First Schedule Part III,
proviso to paragraph A for the §115BAA/115BAB cases.

#### MAT regime

Same 7 % / 12 % banded surcharge as standard regime, applied to MAT
liability per ITA §115JB read with Finance Act 2025 Part III paragraph A.
The MAT computation has its own surcharge layer on top of the 15 % rate.

### §1.3 Health and Education Cess

**4 %** on `(tax + surcharge)`, all regimes, no exemption, no slab.

- Citation: **Finance Act 2018 §158 + §159** introduced "Health and Education
  Cess" at 4 %, **replacing** the prior "Education Cess + Secondary and
  Higher Education Cess" stack. Re-enacted annually via the latest Finance
  Act §2(11) (Finance Act 2025 §2(12) in current form).
- URI: <https://incometaxindia.gov.in/Acts/Income-tax+Act,+1961/2025/102120000000089312.htm>

The 4 % cess is shared between IN PIT and IN CIT — same rate, same base
discipline (apply on `tax + surcharge`). The blueprint's `IN.cess.rate`
parameter (note 122 §4.2 + P1-5) is intentionally *not* prefixed
`IN.CIT.cess.rate` so a future IN PIT migration (note 122 §4) can share
the row. **One parameter, two-tax reuse.**

### §1.4 Minimum Alternate Tax (§115JB)

ITA **§115JB** imposes MAT on book profit (defined per Explanation 1 — see
§1.4.2 below) at **15 %**, applicable to companies **NOT** electing
§115BAA per §115JB(5A) [Taxation Laws (Amendment) Act 2019 inserted
sub-section (5A)]. §115JAA provides a 15-year carryforward credit for
excess MAT paid (see §1.4.3).

#### §1.4.1 Rate history

- **18.5 %** until FY 2018-19.
- **15 %** from FY 2019-20 onward (Finance (No. 2) Act 2019 §50).
- A reduction to **14 %** from FY 2026-27 (AY 2027-28) was announced in the
  Union Budget 2025 — encode the step as a second `:parameter-value` with
  `:effective-from #inst "2026-04-01"` (note 122 §1.4 already specified this).

#### §1.4.2 Book profit base — Explanation 1 add-back menu

The MAT base is **not** taxable income; it is "book profit" computed from
the P&L per Companies Act 2013 with the §115JB Explanation 1 adjustments:

- **Add backs (positive base adjustments)**: income-tax provision (current +
  deferred), amounts carried to any reserve, dividends paid or proposed,
  provisions for unascertained liabilities, provisions for losses of
  subsidiary companies, depreciation, deferred tax debit, expenditure
  relating to §10/§11/§12 exempt income (excluding §10(38) — but that's
  moot post-FA 2018 LTCG taxation).
- **Deductions (negative base adjustments)**: amount withdrawn from any
  reserve credited to the P&L (if added back in earlier year), amount of
  loss brought forward OR unabsorbed depreciation (whichever is less, per
  books), profit of sick industrial undertaking, deferred tax credit,
  depreciation excluding revaluation, brought-forward business loss /
  unabsorbed depreciation per books.

These are **structurally exactly like** the DE §10 KStG non-deductibles
add-backs in ADR-104 — a closed menu of `:base-add` / `:base-deduct`
provisions scoped to the MAT component. Note 122 P1-3 flagged the
enumeration as `:base-transform` work; the v1 implementation should ship
the **6 most common** add-backs (income-tax provision, depreciation,
dividends proposed, reserve transfers, deferred-tax debit, deferred-tax
credit) and leave the long-tail provisions (§10/§11/§12 expenditure carve-
outs, sick-industrial-undertaking deductions, §72A demerger carve-outs) to
a v1.1 catalogue expansion — the substrate already supports them; the
enumeration is the work.

Citations: <https://incometaxindia.gov.in/Acts/Income-tax+Act,+1961/2025/102120000000089312.htm>
and the **Companies (Indian Accounting Standards) Rules 2015** for the
underlying P&L preparation.

#### §1.4.3 MAT credit (§115JAA)

**Excess MAT paid** (MAT − regular tax in a year MAT bound) becomes a
carry-forward credit usable in any of the **next 15 assessment years**
when regular tax exceeds MAT. Cap per year: the excess of regular tax
over MAT in that later year (i.e. you can never use more credit than the
"head-room" of the year).

- Citation: **ITA §115JAA**, 15-yr carry per Finance Act 2017 §57
  (extended from 10 yrs).
- URI: <https://incometaxindia.gov.in/Acts/Income-tax+Act,+1961/2025/102120000000089311.htm>

**v1 status: deferred.** Gated on note 105 frontier 2 (the kontor carry
primitive). The provider records the would-be MAT credit on the MAT
component's `:provenance` (so consumers downstream can see "₹X excess MAT
paid this year is carried forward 15 years per §115JAA"), but does NOT
yet feed it as an `:inputs` fact in a later-year computation. Document
this as a known limitation in the CIT ADR.

### §1.5 DDT — abolished

**Dividend Distribution Tax** (formerly §115-O at ~15 % + surcharge + cess
on the distributing company) was **abolished** with effect from **1 April
2020** by **Finance Act 2020 §40**. India returned to the classical system:
dividends are taxed in the recipient's hands at their applicable slab rate,
with §194 (resident) / §195 (non-resident) TDS withholding.

- v1 does NOT encode DDT. The CIT provider docstring documents the
  abolition for future code archaeologists ("if you're looking for a
  §115-O provision, it was abolished 2020-04-01; dividends flow through
  the PIT/CIT provider on the recipient side").
- Citation: **Finance Act 2020 §40 (omitting §115-O)** + **§44 (omitting
  §115-Q)**.
- URI: <https://www.indiacode.nic.in/handle/123456789/15247>

### §1.6 Foreign companies

A foreign company carrying on business in India through a Permanent
Establishment (PE) pays CIT on India-source profits at a separate rate:

- **35 %** for AY 2025-26 onward (reduced from 40 % by **Finance (No. 2)
  Act 2024 §2**).
- Surcharge bands for foreign cos. are: **2 %** > ₹1 cr / **5 %** > ₹10 cr
  (lower than domestic bands).
- 4 % cess on `(tax + surcharge)`.

This is a separate regime per ITA §90 / §115A. **v1 ships the 35 % rate
with bitemporal effective-from + a single `:provision` gated on
`[:eq [:tax-unit :foreign-co?] true]`**, so the existing standard regime
path doesn't fire. The lower foreign-co surcharge bands ship as a separate
`:parameter-bracket` parent.

- Citation: **Finance (No. 2) Act 2024 §2 + First Schedule Part III
  paragraph E**.
- URI: <https://www.indiacode.nic.in/handle/123456789/15247>
- Pre-FA-2024 history: 40 % from 1989 → FY 2023-24; preserved as a
  `:parameter-value` with `:effective-until #inst "2024-04-01"`.

### §1.7 Carry-forward of losses (§§72, 73)

- **Non-speculative business loss** — 8 AYs, business-income only (§72).
- **Speculative business loss** — 4 AYs, speculative-income only (§73).
- **Unabsorbed depreciation** — no time limit, any head except salary
  (§32(2)).

All three are fold-over-period-stream mechanics — note 105 frontier 2
(carry primitive, unshipped). Out of scope here; the IN CIT ADR
documentation must reference the deferral so consumers know what the
substrate supports today and what waits on a kernel primitive.

### §1.8 Sibling reminder — AMT (§115JC)

**Alternate Minimum Tax** is the partnership / LLP / individual-trust
equivalent of MAT — same `greater-of` structure, applied to non-corporate
assessees. **OUT OF SCOPE** for the CIT provider; mentioned only so future
contributors don't get confused and try to fold it into this provider.
AMT belongs in the PIT (or a new partnership-tax) provider.

---

## §2. ADR-101 mapping — `:parameter`s, `:provision`s, `:regime`s

Mirrors ADR-104 (DE CIT) structurally. The clean cases are direct;
§115BAA / §115BAB ride `:schedule-override` (ADR-101 Addendum 1); MAT
rides `:greater-of` composition (Addendum 1).

### §2.1 Parameters (9 codes; ~26 values)

```clojure
(def parameters
  [;; ----- Standard regime base rates -----
   {:parameter/code         "IN.CIT.standard.small-turnover-rate"
    :parameter/label        "Standard regime — base rate, PY turnover ≤ ₹400 cr"
    :parameter/jurisdiction :in
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.indiacode.nic.in/handle/123456789/15259"}

   {:parameter/code         "IN.CIT.standard.large-turnover-rate"
    :parameter/label        "Standard regime — base rate, PY turnover > ₹400 cr"
    :parameter/jurisdiction :in
    :parameter/unit         :rate}

   ;; ----- Concessional regime rates -----
   {:parameter/code         "IN.CIT.115BAA.rate"
    :parameter/label        "§115BAA flat concessional rate (irrevocable election)"
    :parameter/jurisdiction :in
    :parameter/unit         :rate
    :parameter/concept-iri  "https://incometaxindia.gov.in/Acts/Income-tax+Act,+1961/2025/102120000000088566.htm"}

   {:parameter/code         "IN.CIT.115BAB.rate"
    :parameter/label        "§115BAB flat concessional rate — new-manufacturing"
    :parameter/jurisdiction :in
    :parameter/unit         :rate
    :parameter/concept-iri  "https://incometaxindia.gov.in/Acts/Income-tax+Act,+1961/2025/102120000000088567.htm"}

   ;; ----- Surcharge -----
   {:parameter/code         "IN.CIT.standard.surcharge-brackets"
    :parameter/label        "Standard-regime surcharge — banded ₹1cr/₹10cr w/ marginal relief"
    :parameter/jurisdiction :in
    :parameter/unit         :bracket-scale}

   {:parameter/code         "IN.CIT.concessional.surcharge-rate"
    :parameter/label        "§115BAA/§115BAB flat 10 % surcharge"
    :parameter/jurisdiction :in
    :parameter/unit         :rate}

   {:parameter/code         "IN.CIT.foreign.surcharge-brackets"
    :parameter/label        "Foreign-co surcharge — banded ₹1cr/₹10cr (2%/5%)"
    :parameter/jurisdiction :in
    :parameter/unit         :bracket-scale}

   ;; ----- Cess (shared between IN PIT and IN CIT — note 122 P1-5) -----
   {:parameter/code         "IN.cess.rate"
    :parameter/label        "Health & Education Cess — 4% on (tax + surcharge), all heads"
    :parameter/jurisdiction :in
    :parameter/unit         :rate
    :parameter/concept-iri  "https://incometaxindia.gov.in/Acts/Income-tax+Act,+1961/2025/102120000000089312.htm"}

   ;; ----- MAT (§115JB) -----
   {:parameter/code         "IN.CIT.MAT.rate"
    :parameter/label        "§115JB Minimum Alternate Tax rate"
    :parameter/jurisdiction :in
    :parameter/unit         :rate
    :parameter/concept-iri  "https://incometaxindia.gov.in/Acts/Income-tax+Act,+1961/2025/102120000000089312.htm"}

   ;; ----- Foreign-co rate -----
   {:parameter/code         "IN.CIT.foreign.rate"
    :parameter/label        "Foreign-co CIT rate — Finance Act 2024 reduced 40% → 35%"
    :parameter/jurisdiction :in
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.indiacode.nic.in/handle/123456789/15247"}])
```

### §2.2 Parameter-values (bitemporal, citation-bearing)

Key date-keyed values — every rate must carry its `:effective-from` and a
`:citation` back to the Finance Act / Act 46 of 2019 / etc.

```clojure
(def parameter-values
  [;; Standard 25% — Finance (No.2) Act 2019 raised threshold to ₹400 cr
   {:parameter-value/parameter      [:parameter/code "IN.CIT.standard.small-turnover-rate"]
    :parameter-value/effective-from #inst "2019-04-01"
    :parameter-value/decimal-value  0.25M
    :parameter-value/citation       "Finance (No. 2) Act 2019 §2 + First Schedule Part III A"}

   ;; Standard 30% — long-standing default rate
   {:parameter-value/parameter      [:parameter/code "IN.CIT.standard.large-turnover-rate"]
    :parameter-value/effective-from #inst "2019-04-01"
    :parameter-value/decimal-value  0.30M
    :parameter-value/citation       "Finance Act First Schedule Part III A (long-standing default)"}

   ;; §115BAA — effective AY 2020-21 = FY 2019-20
   {:parameter-value/parameter      [:parameter/code "IN.CIT.115BAA.rate"]
    :parameter-value/effective-from #inst "2019-04-01"
    :parameter-value/decimal-value  0.22M
    :parameter-value/citation       "Taxation Laws (Amendment) Act 2019 (Act 46/2019) §4"}

   ;; §115BAB — same Act 46/2019
   {:parameter-value/parameter      [:parameter/code "IN.CIT.115BAB.rate"]
    :parameter-value/effective-from #inst "2019-04-01"
    :parameter-value/decimal-value  0.15M
    :parameter-value/citation       "Taxation Laws (Amendment) Act 2019 (Act 46/2019) §5"}

   ;; Concessional flat 10% surcharge
   {:parameter-value/parameter      [:parameter/code "IN.CIT.concessional.surcharge-rate"]
    :parameter-value/effective-from #inst "2019-04-01"
    :parameter-value/decimal-value  0.10M
    :parameter-value/citation       "Finance Act 2020 §2 + Act 46/2019"}

   ;; Shared 4% HEC cess
   {:parameter-value/parameter      [:parameter/code "IN.cess.rate"]
    :parameter-value/effective-from #inst "2018-04-01"
    :parameter-value/decimal-value  0.04M
    :parameter-value/citation       "Finance Act 2018 §158 — replaced Education Cess + SHEC w/ HEC 4%"}

   ;; MAT 15% through FY 2025-26; 14% from FY 2026-27 (Union Budget 2025)
   {:parameter-value/parameter       [:parameter/code "IN.CIT.MAT.rate"]
    :parameter-value/effective-from  #inst "2019-04-01"
    :parameter-value/effective-until #inst "2026-04-01"
    :parameter-value/decimal-value   0.15M
    :parameter-value/citation        "Finance (No. 2) Act 2019 §50 — MAT 18.5% → 15%"}

   {:parameter-value/parameter      [:parameter/code "IN.CIT.MAT.rate"]
    :parameter-value/effective-from #inst "2026-04-01"
    :parameter-value/decimal-value  0.14M
    :parameter-value/citation       "Union Budget 2025 — MAT 15% → 14% from FY 2026-27"}

   ;; Foreign-co rate — 40% pre-FA-2024; 35% from FA 2024
   {:parameter-value/parameter       [:parameter/code "IN.CIT.foreign.rate"]
    :parameter-value/effective-from  #inst "1989-04-01"
    :parameter-value/effective-until #inst "2024-04-01"
    :parameter-value/decimal-value   0.40M
    :parameter-value/citation        "Long-standing 40% foreign-co rate (pre-FA-2024)"}

   {:parameter-value/parameter      [:parameter/code "IN.CIT.foreign.rate"]
    :parameter-value/effective-from #inst "2024-04-01"
    :parameter-value/decimal-value  0.35M
    :parameter-value/citation       "Finance (No. 2) Act 2024 §2 — foreign co. 40% → 35%"}])
```

### §2.3 Parameter-brackets (surcharge bands)

```clojure
(def parameter-brackets
  [;; ----- Standard-regime surcharge: 0/7/12 banded at ₹1cr/₹10cr -----
   {:parameter-bracket/parameter      [:parameter/code "IN.CIT.standard.surcharge-brackets"]
    :parameter-bracket/index          0
    :parameter-bracket/rate           0M
    :parameter-bracket/upper          10000000M       ; ₹1 cr
    :parameter-bracket/effective-from #inst "2018-04-01"}
   {:parameter-bracket/parameter      [:parameter/code "IN.CIT.standard.surcharge-brackets"]
    :parameter-bracket/index          1
    :parameter-bracket/rate           0.07M
    :parameter-bracket/upper          100000000M      ; ₹10 cr
    :parameter-bracket/effective-from #inst "2018-04-01"}
   {:parameter-bracket/parameter      [:parameter/code "IN.CIT.standard.surcharge-brackets"]
    :parameter-bracket/index          2
    :parameter-bracket/rate           0.12M
    :parameter-bracket/upper          nil
    :parameter-bracket/effective-from #inst "2018-04-01"}

   ;; ----- Foreign-co surcharge: 0/2/5 banded -----
   {:parameter-bracket/parameter      [:parameter/code "IN.CIT.foreign.surcharge-brackets"]
    :parameter-bracket/index          0
    :parameter-bracket/rate           0M
    :parameter-bracket/upper          10000000M
    :parameter-bracket/effective-from #inst "2018-04-01"}
   {:parameter-bracket/parameter      [:parameter/code "IN.CIT.foreign.surcharge-brackets"]
    :parameter-bracket/index          1
    :parameter-bracket/rate           0.02M
    :parameter-bracket/upper          100000000M
    :parameter-bracket/effective-from #inst "2018-04-01"}
   {:parameter-bracket/parameter      [:parameter/code "IN.CIT.foreign.surcharge-brackets"]
    :parameter-bracket/index          2
    :parameter-bracket/rate           0.05M
    :parameter-bracket/upper          nil
    :parameter-bracket/effective-from #inst "2018-04-01"}])
```

### §2.4 Regimes (3 entities)

```clojure
(def regimes
  [{:regime/code        :in-cit-standard
    :regime/label       "Standard regime — 25%/30% + 7%/12% banded surcharge + MAT applies"
    :regime/jurisdiction :in}
   {:regime/code        :in-cit-115BAA
    :regime/label       "§115BAA — flat 22% + flat 10% surcharge; MAT-exempt; irrevocable"
    :regime/jurisdiction :in}
   {:regime/code        :in-cit-115BAB
    :regime/label       "§115BAB — new-manufacturer flat 15% + flat 10% surcharge; MAT-exempt"
    :regime/jurisdiction :in}])
```

### §2.5 Provisions (~11 rows)

```clojure
(def provisions
  [;; --------------------------------------------------------------------
   ;; STANDARD REGIME — base rate via :schedule-override on turnover band
   ;; --------------------------------------------------------------------
   ;; §115BA / Finance Act 2019 — 25% on turnover ≤ ₹400 cr.
   ;; Two-pass query: consumer pre-computes :turnover-band on :tax-unit
   ;; from PY 2023-24 turnover; the provision reads that ctx fact.
   {:provision/code            "IN-CIT-Standard-25"
    :provision/jurisdiction    :in
    :provision/concept         [:tax-concept/code :elective-regime]
    :provision/title           "Standard regime base — 25% (PY turnover ≤ ₹400 cr)"
    :provision/citation        "Finance (No. 2) Act 2019 §2 + First Schedule Part III A"
    :provision/effective-from  #inst "2019-04-01"
    :provision/priority        100
    :provision/condition       (pr-str [:and
                                        [:eq :component :regular]
                                        [:eq [:tax-unit :regime] :in-cit-standard]
                                        [:eq [:tax-unit :turnover-band] :small]])
    :provision/consequence     (pr-str {:op :schedule-override
                                        :code :in-cit-standard-small
                                        :label "Standard regime, 25% (small-turnover)"
                                        :schedule {:schedule/type :flat
                                                   :rate-from :parameter
                                                   :parameter "IN.CIT.standard.small-turnover-rate"}})}

   {:provision/code            "IN-CIT-Standard-30"
    :provision/jurisdiction    :in
    :provision/concept         [:tax-concept/code :elective-regime]
    :provision/title           "Standard regime base — 30% (PY turnover > ₹400 cr)"
    :provision/citation        "Finance Act First Schedule Part III A (default rate)"
    :provision/effective-from  #inst "2019-04-01"
    :provision/priority        100
    :provision/condition       (pr-str [:and
                                        [:eq :component :regular]
                                        [:eq [:tax-unit :regime] :in-cit-standard]
                                        [:eq [:tax-unit :turnover-band] :large]])
    :provision/consequence     (pr-str {:op :schedule-override
                                        :code :in-cit-standard-large
                                        :label "Standard regime, 30% (large-turnover)"
                                        :schedule {:schedule/type :flat
                                                   :rate-from :parameter
                                                   :parameter "IN.CIT.standard.large-turnover-rate"}})}

   ;; --------------------------------------------------------------------
   ;; §115BAA — flat 22%
   ;; --------------------------------------------------------------------
   {:provision/code            "IN-CIT-115BAA-22"
    :provision/jurisdiction    :in
    :provision/concept         [:tax-concept/code :elective-regime]
    :provision/title           "§115BAA concessional flat 22% — domestic co. election (irrevocable)"
    :provision/citation        "Income-tax Act §115BAA / Act 46/2019"
    :provision/effective-from  #inst "2019-04-01"
    :provision/priority        100
    :provision/condition       (pr-str [:and
                                        [:eq :component :regular]
                                        [:eq [:tax-unit :regime] :in-cit-115BAA]])
    :provision/consequence     (pr-str {:op :schedule-override
                                        :code :in-cit-115BAA
                                        :label "§115BAA flat 22%"
                                        :schedule {:schedule/type :flat
                                                   :rate-from :parameter
                                                   :parameter "IN.CIT.115BAA.rate"}})}

   ;; --------------------------------------------------------------------
   ;; §115BAB — flat 15% (new-manufacturer; sunset clause documented)
   ;; --------------------------------------------------------------------
   ;; Sunset: the eligibility window (commencement of manufacturing on or
   ;; before 31 Mar 2024) has HIT, so no new electors after that date.
   ;; Existing electors continue at 15%. Encoded with :effective-from
   ;; bracketing the eligibility window in the citation; the substrate
   ;; itself fires for any cos. whose :tax-unit :regime is :in-cit-115BAB,
   ;; on the assumption the consumer enforces eligibility upstream.
   {:provision/code            "IN-CIT-115BAB-15"
    :provision/jurisdiction    :in
    :provision/concept         [:tax-concept/code :elective-regime]
    :provision/title           "§115BAB concessional flat 15% — new manufacturing co. (commence ≤ 2024-03-31)"
    :provision/citation        "Income-tax Act §115BAB / Act 46/2019 (commencement window sunset 2024-03-31)"
    :provision/effective-from  #inst "2019-10-01"
    :provision/priority        100
    :provision/condition       (pr-str [:and
                                        [:eq :component :regular]
                                        [:eq [:tax-unit :regime] :in-cit-115BAB]])
    :provision/consequence     (pr-str {:op :schedule-override
                                        :code :in-cit-115BAB
                                        :label "§115BAB flat 15%"
                                        :schedule {:schedule/type :flat
                                                   :rate-from :parameter
                                                   :parameter "IN.CIT.115BAB.rate"}})}

   ;; --------------------------------------------------------------------
   ;; FOREIGN COMPANY — flat 35% (post-FA-2024)
   ;; --------------------------------------------------------------------
   {:provision/code            "IN-CIT-Foreign-Co"
    :provision/jurisdiction    :in
    :provision/concept         [:tax-concept/code :elective-regime]
    :provision/title           "Foreign co. CIT — 35% (post-FA-2024); 40% pre-FA-2024 via :parameter history"
    :provision/citation        "Income-tax Act §90 / §115A; Finance (No. 2) Act 2024 §2"
    :provision/effective-from  #inst "1989-04-01"
    :provision/priority        100
    :provision/condition       (pr-str [:and
                                        [:eq :component :regular]
                                        [:eq [:tax-unit :foreign-co?] true]])
    :provision/consequence     (pr-str {:op :schedule-override
                                        :code :in-cit-foreign
                                        :label "Foreign-co flat rate"
                                        :schedule {:schedule/type :flat
                                                   :rate-from :parameter
                                                   :parameter "IN.CIT.foreign.rate"}})}

   ;; --------------------------------------------------------------------
   ;; SURCHARGES — three provisions, one per regime
   ;; --------------------------------------------------------------------
   ;; Standard regime banded surcharge w/ marginal relief
   {:provision/code            "IN-CIT-Surcharge-Standard"
    :provision/jurisdiction    :in
    :provision/concept         [:tax-concept/code :surtax]
    :provision/title           "Standard-regime surcharge — banded 0/7/12 with marginal relief"
    :provision/citation        "Finance Act 2025 First Schedule Part III A (Surcharge proviso)"
    :provision/effective-from  #inst "2018-04-01"
    :provision/priority        100
    :provision/condition       (pr-str [:and
                                        [:eq :component :regular]
                                        [:eq [:tax-unit :regime] :in-cit-standard]
                                        [:not [:eq [:tax-unit :foreign-co?] true]]])
    :provision/consequence     (pr-str {:op :surtax
                                        :code :in-surcharge-standard
                                        :label "CIT surcharge — standard regime (banded, marginal relief)"
                                        :amount-from :compute-fn
                                        :fn :in-cit-surcharge-standard})}

   ;; §115BAA / §115BAB flat 10% surcharge
   {:provision/code            "IN-CIT-Surcharge-Concessional"
    :provision/jurisdiction    :in
    :provision/concept         [:tax-concept/code :surtax]
    :provision/title           "§115BAA/§115BAB flat 10% surcharge"
    :provision/citation        "Finance Act 2020 §2 / Act 46/2019"
    :provision/effective-from  #inst "2019-04-01"
    :provision/priority        100
    :provision/condition       (pr-str [:and
                                        [:eq :component :regular]
                                        [:or
                                         [:eq [:tax-unit :regime] :in-cit-115BAA]
                                         [:eq [:tax-unit :regime] :in-cit-115BAB]]])
    :provision/consequence     (pr-str {:op :surtax
                                        :code :in-surcharge-concessional
                                        :label "Flat 10% surcharge (§115BAA/§115BAB)"
                                        :amount-from :compute-fn
                                        :fn :in-cit-surcharge-concessional})}

   ;; Foreign-co surcharge
   {:provision/code            "IN-CIT-Surcharge-Foreign"
    :provision/jurisdiction    :in
    :provision/concept         [:tax-concept/code :surtax]
    :provision/title           "Foreign-co surcharge — banded 0/2/5"
    :provision/citation        "Finance Act 2025 First Schedule Part III E"
    :provision/effective-from  #inst "2018-04-01"
    :provision/priority        100
    :provision/condition       (pr-str [:and
                                        [:eq :component :regular]
                                        [:eq [:tax-unit :foreign-co?] true]])
    :provision/consequence     (pr-str {:op :surtax
                                        :code :in-surcharge-foreign
                                        :label "CIT surcharge — foreign co. (banded, no marginal relief)"
                                        :amount-from :compute-fn
                                        :fn :in-cit-surcharge-foreign})}

   ;; --------------------------------------------------------------------
   ;; HEC CESS — fires for both :regular and :mat components, all regimes
   ;; --------------------------------------------------------------------
   {:provision/code            "IN-FinAct-Cess"
    :provision/jurisdiction    :in
    :provision/concept         [:tax-concept/code :surtax]
    :provision/title           "Health & Education Cess — 4% of (tax + surcharge)"
    :provision/citation        "Finance Act 2018 §158 + Finance Act 2025 §2(12)"
    :provision/effective-from  #inst "2018-04-01"
    :provision/priority        500              ; AFTER surcharge in the fold
    :provision/condition       (pr-str [:or
                                        [:eq :component :regular]
                                        [:eq :component :mat]])
    :provision/consequence     (pr-str {:op :surtax
                                        :code :in-hec-cess
                                        :label "Health & Education Cess (4%)"
                                        :amount-from :compute-fn
                                        :fn :in-cit-cess})}

   ;; --------------------------------------------------------------------
   ;; MAT (§115JB) — fires only when :tax-unit :regime is :in-cit-standard
   ;; AND :foreign-co? is not true (foreign cos. don't get MAT). When the
   ;; consumer picks a concessional regime, §115JB(5A) exempts → this
   ;; provision's condition does NOT match → no MAT component is built.
   ;; --------------------------------------------------------------------
   {:provision/code            "IN-MAT-115JB"
    :provision/jurisdiction    :in
    :provision/concept         [:tax-concept/code :elective-regime]
    :provision/title           "§115JB Minimum Alternate Tax — 15% on book profit (14% from FY 2026-27)"
    :provision/citation        "Income-tax Act §115JB; Finance (No. 2) Act 2019 §50; UB-2025"
    :provision/effective-from  #inst "2019-04-01"
    :provision/priority        100
    :provision/condition       (pr-str [:and
                                        [:eq :component :mat]
                                        [:eq [:tax-unit :regime] :in-cit-standard]
                                        [:not [:eq [:tax-unit :foreign-co?] true]]])
    :provision/consequence     (pr-str {:op :schedule-override
                                        :code :in-mat-flat
                                        :label "§115JB MAT flat rate (15% or 14% per AY)"
                                        :schedule {:schedule/type :flat
                                                   :rate-from :parameter
                                                   :parameter "IN.CIT.MAT.rate"}})}

   ;; MAT surcharge — same standard 0/7/12 bands as regular
   {:provision/code            "IN-MAT-Surcharge"
    :provision/jurisdiction    :in
    :provision/concept         [:tax-concept/code :surtax]
    :provision/title           "MAT surcharge — same standard 0/7/12 bands"
    :provision/citation        "Finance Act 2025 First Schedule Part III A (MAT rows)"
    :provision/effective-from  #inst "2018-04-01"
    :provision/priority        100
    :provision/condition       (pr-str [:eq :component :mat])
    :provision/consequence     (pr-str {:op :surtax
                                        :code :in-mat-surcharge
                                        :label "MAT surcharge (banded, marginal relief)"
                                        :amount-from :compute-fn
                                        :fn :in-cit-surcharge-standard})}])

   ;; (NOTE: MAT cess re-uses the IN-FinAct-Cess provision via its
   ;;  :or :component :mat condition above — same rate, same base, no
   ;;  duplicate provision needed.)
```

### §2.6 Base-transform provisions (deferred to v1.1)

§115JB Explanation 1 add-back menu — v1.1 work per §1.4.2. The substrate
supports them as `:base-add` / `:base-deduct` scoped via
`[:eq :component :mat]`. v1 leaves the MAT base as a single
`:inputs :book-profit-115jb` fact the consumer pre-computes (consistent
with JP CIT's deferral of book→tax add-backs per ADR-106).

---

## §3. MAT composition — the structural call (compose-greater-of)

This is the load-bearing structural decision: how does the provider
express `max(regular, MAT)` as ADR-101 data?

### §3.1 ADR-101 Addendum 1 already shipped the helper

Note 122 P0-1 named "MAT composition needs a documented two-component +
greater-of pattern." ADR-101 Addendum 1 closed it:

> `kontor.statute/compose-greater-of` — the MAT / AMT composition
> convention. The canonical use is the MAT / AMT pattern: regular tax
> computed against the book / taxable base; an alternative minimum tax
> (US CAMT §59A, **IN MAT §115JB**, JP local-minimum, KR AMT) computed
> against a different base. The taxpayer owes the GREATER of the two —
> exactly this fn.

The signature is `(compose-greater-of a b) → component` where the
greater `:liability` wins and **both** are recorded in `:composed-of`
+ `:composition` for audit. **IN MAT is the canonical reference case
mentioned in the addendum's docstring** — implementing it validates
the substrate at its named-case.

### §3.2 The IN CIT provider's component shape

The provider builds a **2-component `TaxReturnFacts`** when the regime is
standard, **1-component** when 115BAA/115BAB (no MAT per §115JB(5A)),
and **1-component** when foreign-co.

```
Standard regime, ₹50L income / ₹2cr book profit:
  components:
    - {:kind :corporate-income-tax  :authority :in-cbdt
       :base ₹50,00,000  :liability ₹13,00,000  :regime :in-cit-standard
       :provenance {:provisions-applied ["IN-CIT-Standard-25"
                                         "IN-FinAct-Cess"]}}
    - {:kind :minimum-tax           :authority :in-cbdt
       :base ₹2,00,00,000  :liability ₹33,38,400
       :composed-of [:corporate-income-tax]
       :provenance {:provisions-applied ["IN-MAT-115JB"
                                         "IN-MAT-Surcharge"
                                         "IN-FinAct-Cess"]}}

  PLUS the provider then calls (compose-greater-of c1 c2) and
  appends the prevailing component (here :minimum-tax) to the
  components vector as :kind :corporate-income-tax with the
  composition audit trail. Consumers reading :liability sum the
  prevailing component only — see §3.3.
```

### §3.3 Which component does the consumer remit on?

This is a real design call. Two options:

**Option A — 3-component vector, prevailing element marked.** Components
0 and 1 are the candidates; component 2 is the prevailing one with
`:composed-of [:corporate-income-tax :minimum-tax]` + `:composition`.
Consumer code sums only `:kind` from `period-tax-kinds` with
`:composition nil`, or filters `(filter :composition components)`.

**Option B — 2-component vector, prevailing component replaces both
in place; both candidates archived in `:composition`.** Provider returns
ONE `:corporate-income-tax` component (the prevailing arm) with the
losing arm preserved in `:composition`. Consumer always sums all
components.

**Recommendation: Option B.** Simpler downstream — `sum :liability`
across `components` always gives the right answer. The audit trail lives
in the prevailing component's `:composition`. This matches the
`compose-greater-of` docstring contract ("Output preserves the
prevailing component's structure; adds `:composed-of` + `:composition`")
— the helper returns ONE component; the provider replaces the two
candidates with that one.

### §3.4 §115JB(5A) gating — no MAT for concessional / foreign

When the consumer picks `:in-cit-115BAA` or `:in-cit-115BAB`, or when
`:foreign-co? true`, the `IN-MAT-115JB` provision's condition does NOT
match → no MAT schedule fires → the provider **skips the MAT component
entirely**. The composition step is a no-op (only one candidate
component exists). Result: returned `TaxReturnFacts` has just one
`:corporate-income-tax` component.

This is the substrate doing its job — condition gating handles the
exemption; no provider-side `if-MAT-applies` branch needed.

### §3.5 MAT credit deferred (note 105 frontier 2)

When MAT prevails, the prevailing component's `:provenance` should
record the would-be credit:

```clojure
{:provenance {:provider-id :in-cit
              :statute "§115JB / §115JAA"
              :provisions-applied [...]
              :mat-credit-carry-forward {:amount  20,38,400M
                                         :commodity :INR
                                         :max-years 15
                                         :statute "§115JAA"
                                         :status :recorded-deferred-utilisation}}}
```

The consumer can read this for disclosure / planning purposes; the
*utilisation* in a later year waits on note 105 frontier 2.

---

## §4. Worked examples (golden test reference numbers)

Three examples, each cited to an authority + commercial cross-check.
All arithmetic uses **`BigDecimal` HALF_EVEN** (kontor convention; note
122 §2 used integer cash; this blueprint forces explicit BigDecimal so
tests catch any nominal rounding regressions).

### §4.1 Example A — domestic Pvt. Ltd., standard regime, ≤ ₹400 cr turnover

**Facts.** Pvt. Ltd. with PY 2023-24 turnover ₹150 cr (small),
AY 2026-27 taxable income ₹50,00,000 (under ₹1 cr → no surcharge),
book profit ₹60,00,000.

**Regular CIT computation.**
```
Taxable income                  ₹50,00,000.00
Base rate 25%                 = ₹12,50,000.00
Surcharge (≤ ₹1 cr → 0%)      = ₹       0.00
Subtotal (tax + surcharge)    = ₹12,50,000.00
HEC cess 4%                   = ₹   50,000.00
Regular liability             = ₹13,00,000.00
                                ─────────────
```

**MAT computation (§115JB).**
```
Book profit                     ₹60,00,000.00
MAT rate 15%                  = ₹ 9,00,000.00
Surcharge (≤ ₹1 cr → 0%)      = ₹       0.00
Subtotal                      = ₹ 9,00,000.00
HEC cess 4%                   = ₹    36,000.00
MAT liability                 = ₹ 9,36,000.00
                                ─────────────
```

**Composition.** `max(13_00_000, 9_36_000) = ₹13,00,000`. Regular
prevails; no MAT credit accrues.

**Source.** PwC India Tax Summaries §"Taxes on corporate income"
(<https://taxsummaries.pwc.com/india/corporate/taxes-on-corporate-income>),
cross-checked with ClearTax CIT calculator (<https://cleartax.in/s/tax-rate-domestic-company>)
and `incometax.gov.in` "Domestic Company AY 2026-27 help page"
(<https://www.incometax.gov.in/iec/foportal/help/company/return-applicable>).

### §4.2 Example B — domestic co. electing §115BAA at ₹5 cr income

**Facts.** Domestic Pvt. Ltd., §115BAA elected (irrevocable per Form
10-IC filed AY 2020-21), AY 2026-27 taxable income ₹5,00,00,000.
No book profit needed (MAT exempt per §115JB(5A)).

**Computation (single component).**
```
Taxable income                  ₹5,00,00,000.00
Base rate 22%                 = ₹1,10,00,000.00
Surcharge (flat 10%)          = ₹  11,00,000.00
Subtotal                      = ₹1,21,00,000.00
HEC cess 4%                   = ₹   4,84,000.00
Liability                     = ₹1,25,84,000.00
                                ───────────────
Effective rate                = 25.168 %
```

The 25.168 % effective rate is the headline number every Indian
commentary quotes for §115BAA (ClearTax §115BAA explainer; PwC India
Tax Summaries; Motilal Oswal "Corporate Tax 2025-26 rates"). Verified
to the rupee against ClearTax's §115BAA calculator.

**Composition.** Single regular component only — no MAT component built
because `IN-MAT-115JB` condition `[:eq [:tax-unit :regime] :in-cit-standard]`
does not match.

**Sources.** ClearTax §115BAA explainer
(<https://cleartax.in/s/section-115-baa-tax-rate-domestic-companies>);
PwC India Tax Summaries; Motilal Oswal Corporate Tax 2025-26 guide
(<https://www.motilaloswal.com/personal-finance/tax/corporate-tax-in-india-2025-26-rates-regimes-complete-guide>).

### §4.3 Example C — MAT-binding case (standard regime, book ≫ taxable)

**Facts.** Standard regime, PY 2023-24 turnover ₹150 cr (small),
AY 2026-27 taxable income **₹50,00,000** (after large Chapter VI-A
§80IA infrastructure deductions), book profit **₹2,00,00,000** (pre-
deductions), no surcharge bracket reached on the regular side but
MAT pushes past ₹1 cr (so 7 % surcharge on MAT).

**Regular CIT.**
```
Taxable income                  ₹50,00,000.00
Base rate 25%                 = ₹12,50,000.00
Surcharge (≤ ₹1 cr → 0%)      = ₹       0.00
HEC cess 4%                   = ₹    50,000.00
Regular liability             = ₹13,00,000.00
```

**MAT.**
```
Book profit                     ₹2,00,00,000.00
MAT rate 15%                  = ₹  30,00,000.00
Surcharge 7% (> ₹1 cr)        = ₹   2,10,000.00
Subtotal                      = ₹  32,10,000.00
HEC cess 4%                   = ₹   1,28,400.00
MAT liability                 = ₹  33,38,400.00
```

**Composition.** `max(13_00_000, 33_38_400) = ₹33,38,400`. **MAT
prevails.** Excess MAT paid = `33_38_400 - 13_00_000 = ₹20,38,400`
recorded in `:provenance :mat-credit-carry-forward` (max 15 AYs per
§115JAA; utilisation deferred — note 105 frontier 2).

**Marginal-relief check on MAT surcharge at ₹1 cr.**
- Tax at exactly ₹1 cr (no surcharge): `1_00_00_000 × 15% = ₹15,00,000`.
- Excess income beyond ₹1 cr: `2_00_00_000 - 1_00_00_000 = ₹1,00,00,000`.
- Cap on `(tax + surcharge)`: `15_00_000 + 1_00_00_000 = ₹1,15,00,000`.
- Actual `(tax + surcharge) = 32,10,000` — well inside the cap →
  no marginal relief fires. (Marginal relief mostly bites just above
  ₹1 cr where surcharge mass is small relative to the threshold tax.)

**Source.** ClearTax MAT calculator
(<https://cleartax.in/s/tax-planning-under-mat>); Tax2win MAT under
§115JB (<https://tax2win.in/guide/minimum-alternative-tax>);
TaxBuddy §115JB explainer (<https://www.taxbuddy.com/blog/section-115jb-of-the-income-tax-act>).

### §4.4 Bitemporal swap test — foreign co. 40% → 35%

**Facts.** Foreign co. branch in India, ₹2,00,00,000 royalty income
(under §115A), run twice — once `:as-of #inst "2024-03-31"` (pre-FA-2024
should fire 40%), once `:as-of #inst "2025-06-30"` (post-FA-2024 should
fire 35%). No surcharge (under ₹1 cr — wait, this case is at ₹2 cr so
foreign surcharge band 2% fires for the > ₹1 cr range).

**Pre-FA-2024 (`:as-of 2024-03-31`).**
```
Income                          ₹2,00,00,000.00
Base rate 40%                 = ₹  80,00,000.00
Surcharge 2% (> ₹1 cr)        = ₹   1,60,000.00
Subtotal                      = ₹  81,60,000.00
HEC cess 4%                   = ₹   3,26,400.00
Liability                     = ₹  84,86,400.00
```

**Post-FA-2024 (`:as-of 2025-06-30`).**
```
Income                          ₹2,00,00,000.00
Base rate 35%                 = ₹  70,00,000.00
Surcharge 2% (> ₹1 cr)        = ₹   1,40,000.00
Subtotal                      = ₹  71,40,000.00
HEC cess 4%                   = ₹   2,85,600.00
Liability                     = ₹  74,25,600.00
```

Demonstrates `:parameter-value/effective-from + :effective-until`
bracketing works end-to-end.

**Source.** PwC India Tax Summaries §"Foreign companies"; KPMG India
Tax Card 2025-26 (foreign-co rate reduction confirmation).

---

## §5. Substrate-fit stress findings

Re-audited against ADR-101 + Addendum 1 + Addendum 2 + the IN-specific
gotchas notes 121-123 surfaced. **Net: zero new substrate gaps;
everything closes against shipped substrate.**

### §5.1 P0 — none open after ADR-101 Addendum 1

Note 122 named **P0-1 "MAT composition needs documented two-component +
greater-of"**. **CLOSED** by ADR-101 Addendum 1's `compose-greater-of`
helper. The IN CIT provider is the first end-to-end consumer of the
helper on a real statute (the addendum's own statute_test §8 covers the
fn signature; a Phase-3 jurisdiction has not yet wired it).

### §5.2 P1 — three items, all addressable in the IN CIT module

- **P1-A: Two-pass turnover-band query (note 122 §P1-2).** Whether the
  small (25%) or large (30%) standard rate applies depends on **PY
  2023-24 turnover** — a fact from a different period than the assessed
  period. The substrate already supports this via the **two-pass query
  pattern** (ADR-101 Addendum 1 §3): the consumer pre-computes
  `:turnover-band :small | :large` on `:tax-unit` from PY data; the
  provision reads `[:eq [:tax-unit :turnover-band] :small]`. Mirrors DE
  Hebesatz, US filing-status, FR PME, CA CCPC — all `:tax-unit`-shaped.
  **Documentation work, not substrate work.**

- **P1-B: §115BAA/§115BAB election irrevocability (note 122 §P1-1).**
  ADR-101 §D5 says regime election rides ADR-034 status-machine. The
  irrevocability constraint is a status-machine guard ("no transition
  from `:in-cit-115BAA` back to `:in-cit-standard`"). **Belongs in the
  consumer's election helper**, not the provider; the provider trusts
  `:tax-unit :regime`. Document the guard in the IN CIT ADR and provide
  a sample `kontor.l10n-in.cit-provider/elect-115BAA!` helper that wires
  the status-machine constraint. (Out of scope for v1; mention in ADR.)

- **P1-C: §115JB Explanation 1 add-back catalogue (note 122 §P1-3).**
  The 12-ish add-backs to compute the MAT book-profit base are
  structurally identical to DE §10 KStG non-deductibles in ADR-104.
  Substrate supports them as `:base-add` / `:base-deduct` provisions
  scoped `[:eq :component :mat]`. **v1 ships the 6 most common
  add-backs**; long-tail add-backs ship as a v1.1 catalogue expansion.
  v1's simpler path: consumer pre-computes `:inputs :book-profit-115jb`
  with add-backs already applied (mirrors JP CIT's deferral of
  book→tax add-backs per ADR-106).

### §5.3 P2 — six known limitations, document and move on

- **P2-A**: §115BBE windfall-gain 60 % regime — separate punitive regime
  for unexplained cash credits / §68/69-69D additions. Off-path; defer.
- **P2-B**: §234 interest (delayed advance tax, late filing) — mechanically
  a base-aware surtax over the delay window. Substrate supports; not
  scope for v1.
- **P2-C**: Special CGT rates (§111A, §112, §112A) — flow through the IN
  CGT provider (already shipped per ADR-103). Not CIT scope.
- **P2-D**: §115BAB non-business income split (15% on manufacturing,
  22% on non-business) — two-rate split inside one regime. Substrate
  could express via a second `:provision/condition` matching
  `[:eq :income-class :non-business]`, but v1 keeps the provider
  simple. Document.
- **P2-E**: DDT — abolished 2020-04-01. Document the abolition in the
  provider docstring for code archaeologists; no encoding work.
- **P2-F**: AMT (§115JC) — partnership/LLP analog of MAT. **Out of scope
  for CIT provider**; belongs in PIT or a future partnership-tax
  provider.

### §5.4 Substrate stress questions from the brief (answered)

> Does `compose-greater-of` work for the MAT > Regular composition, or
> does it need a refinement (e.g., the MAT-credit carryforward feedback
> loop)?

**Works for the composition.** The carryforward feedback loop is a
**separate** primitive (note 105 frontier 2) — multi-year period folding,
not single-year composition. `compose-greater-of` handles the current-year
`max(regular, MAT)`; the next-year carry utilisation is "add a fact
`:inputs :mat-credit-available` from priors, optionally consume up to
`(regular − MAT)`, surface what's left as a new `:mat-credit-carry-out`",
which is a separate compute-fn the IN provider can add when the carry
primitive lands.

> Does the turnover-band cliff (₹400 cr) work via the two-pass query,
> or does it need a new mechanism?

**Two-pass query works**, with a twist: the cliff is not on this year's
income (i.e. not on the **outcome of computation**); it's on **prior
year's turnover**. So it's not even a two-pass scenario; it's a
pre-computed `:tax-unit` fact the consumer supplies. Cleaner than the
CN SLPE / IN PIT surcharge case (which IS true two-pass — gated on
this-year's net income). Document the convention.

> Does §115BAA's "permanent election that disables most deductions"
> express via `:regime` or via `:schedule-override + :predicate
> {:elected? true}`?

**Via `:regime` + `:schedule-override`.** The election lives on
`:regime` (ADR-101 §D5); the rate consequence is `:schedule-override`
gated on `[:eq [:tax-unit :regime] :in-cit-115BAA]`. The "disables most
deductions" piece is **enforced by which other provisions can fire**:
deduction provisions (when migrated from the IN PIT or added for IN
CIT) would carry `[:not [:eq [:tax-unit :regime] :in-cit-115BAA]]` in
their condition — substrate handles it via condition-gating, no new
machinery needed.

> Does the 4 % cess work via the existing `:surtax` op, or does it need
> a new `:op` to reference "cumulative-of-prior-passes"?

**Works via existing `:surtax`.** The adjustment-layer fold
(`kontor.tax-schedule/apply-adjustments`) threads `:running` through
each surtax item in priority order — `:running` IS the cumulative-of-
prior-passes total. Cess at `:priority 500` fires AFTER all surcharges
(`:priority 100`) — by the time the cess compute-fn runs, `:running`
is `(gross + surcharge)`. The DE Soli + the existing IN PIT cess
compute-fn use exactly this pattern; IN CIT cess re-uses it. No new
op needed.

### §5.5 What's NOT in scope for v1

For honesty / future contributors:

- MAT credit utilisation (§115JAA) — defer to note 105 frontier 2.
- §115JB Explanation 1 full 12-row add-back catalogue — v1 ships 6;
  v1.1 expands.
- Election irrevocability guard — belongs in a consumer-side election
  helper, not the provider.
- §115BAB sunset enforcement — provider trusts consumer to pass valid
  `:tax-unit :regime`.
- §115BBE windfall rate, §234 interest, §195 foreign-payment TDS — all
  separate provisions or providers.
- AMT (§115JC) — separate provider for non-corporate assessees.

---

## §6. Implementation outline

### §6.1 File layout

```
modules/l10n-in/
  src/kontor/l10n_in/
    cit_statute.clj            (~420 LOC)
    cit_provider.clj           (~160 LOC)
  test/kontor/l10n_in/
    cit_provider_test.clj      (~350 LOC, ~12 deftests / ~55 assertions)
```

No new schema. No new substrate code. Consumer of:
- `kontor.statute` (ADR-101 substrate)
- `kontor.statute/compose-greater-of` (ADR-101 Addendum 1)
- `kontor.tax-schedule` (`:flat` schedule; `apply-adjustments`)
- `kontor.period-tax-provider` (`PeriodTaxProvider` + `TaxReturnFacts`)

### §6.2 `cit_statute.clj` shape

Mirrors ADR-104's DE statute file shape exactly:

```clojure
(ns kontor.l10n-in.cit-statute
  "IN corporate income tax — Regular CIT + MAT (§115JB) encoded as
   kontor.statute data per ADR-101 / ADR-101 Addendum 1; research
   notes 122 + 163."
  (:require [datahike.api :as d]))

(def parameters [...])             ; 9 codes (§2.1)
(def parameter-values [...])       ; ~12 rows (§2.2)
(def parameter-brackets [...])     ; 6 rows (§2.3) — 2 parents × 3 bands
(def regimes [...])                ; 3 entities (§2.4)
(def provisions [...])             ; 11 provisions (§2.5)

(defn install!
  "Install IN CIT statute (parameters + brackets + regimes + provisions)
   into `conn`. Idempotent on identity attrs."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (d/transact conn parameter-brackets)
  (d/transact conn regimes)
  (d/transact conn provisions))
```

### §6.3 `cit_provider.clj` shape

```clojure
(ns kontor.l10n-in.cit-provider
  "IN corporate income tax provider — Regular CIT + MAT (§115JB)
   composed via kontor.statute/compose-greater-of per ADR-101
   Addendum 1. First end-to-end consumer of compose-greater-of
   on a real statute; the reference example for jurisdictions with
   minimum-tax composition (US CAMT, KR AMT, JP local-minimum).

   ## Inputs the consumer supplies

   :tax-unit (company config):
     {:regime       <kw>   required — one of
                            :in-cit-standard / :in-cit-115BAA / :in-cit-115BAB
      :turnover-band <kw>  required when :regime :in-cit-standard
                            (consumer pre-computes from PY 2023-24
                            turnover: :small (≤ ₹400 cr) | :large)
      :foreign-co?  <bool> optional, default false — when true the
                            standard / concessional regime provisions
                            don't fire; the foreign 35% rate fires
                            instead. MAT is also skipped.}

   :inputs (period facts):
     {:taxable-income      <BigDecimal>  required — ITA-§4 base for
                                          regular CIT
      :book-profit-115jb   <BigDecimal>  required when standard regime
                                          (i.e. when MAT applies) —
                                          consumer pre-computes per
                                          §115JB Explanation 1
      :prepaid             <Money>       optional — TDS suffered, advance
                                          tax remitted (Chapter XVII-B);
                                          stamped on the prevailing
                                          component for the return}

   ## Out-of-substrate

   Compute-fns for the four non-data consequences:
     :in-cit-surcharge-standard      — banded with marginal relief
     :in-cit-surcharge-concessional  — flat 10%
     :in-cit-surcharge-foreign       — banded (no marginal relief)
     :in-cit-cess                    — 4% × :running"
  (:require [kontor.l10n-in.cit-statute :as cit-statute]
            [kontor.period-tax-provider :as ptp]
            [kontor.statute :as statute]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; Compute-fn registration
;; ============================================================================

(defn- as-of-from-ctx [ctx] ...)

(defn- active-band
  "Lookup the active bracket given a base amount and a list of
   :parameter-bracket rows, returning {:rate :upper :prior-upper}."
  [brackets base] ...)

(defn- in-cit-surcharge-standard
  "Banded surcharge w/ marginal relief at ₹1cr and ₹10cr.
   Late-bound — surcharge depends on :base AND :running."
  [ctx]
  (let [db    (:db ctx)
        as-of (as-of-from-ctx ctx)
        bands (statute/parameter-brackets-at db "IN.CIT.standard.surcharge-brackets" as-of)]
    (fn [{:keys [base running] :as _ctx-w-running}]
      (let [band (active-band bands base)
            raw  (* running (:rate band))
            threshold     (:prior-upper band)
            tax-at-thresh (when threshold (* threshold (some-rate-here ...)))
            cap           (when threshold (+ tax-at-thresh (- base threshold)))]
        (if (and cap (> (+ running raw) cap))
          (max 0M (- cap running))
          raw)))))

(defn- in-cit-surcharge-concessional [ctx]
  (fn [{:keys [running]}]
    (* running (statute/parameter-value-at (:db ctx)
                                           "IN.CIT.concessional.surcharge-rate"
                                           (as-of-from-ctx ctx)))))

(defn- in-cit-surcharge-foreign [ctx]
  ...)  ; banded over IN.CIT.foreign.surcharge-brackets, no marginal relief

(defn- in-cit-cess [ctx]
  (fn [{:keys [running]}]
    (* running (statute/parameter-value-at (:db ctx) "IN.cess.rate"
                                           (as-of-from-ctx ctx)))))

(defn register! []
  (statute/register-compute-fn! :in-cit-surcharge-standard      in-cit-surcharge-standard)
  (statute/register-compute-fn! :in-cit-surcharge-concessional  in-cit-surcharge-concessional)
  (statute/register-compute-fn! :in-cit-surcharge-foreign       in-cit-surcharge-foreign)
  (statute/register-compute-fn! :in-cit-cess                    in-cit-cess))

(register!)

;; ============================================================================
;; Provider
;; ============================================================================

(defn- component-items
  "Per-component query: schedule-override + surtax provisions."
  [db ctx as-of component]
  (let [scoped-ctx (assoc ctx :component component :db db :as-of as-of)
        overrides  (statute/apply-provisions db {:concept :elective-regime
                                                 :jurisdiction :in
                                                 :as-of as-of} scoped-ctx)
        surtaxes   (statute/apply-provisions db {:concept :surtax
                                                 :jurisdiction :in
                                                 :as-of as-of} scoped-ctx)]
    {:schedule-overrides (:schedule-overrides overrides)
     :tax-items          (:tax-items surtaxes)
     :provisions         (concat (:provisions overrides) (:provisions surtaxes))}))

(defn- pick-schedule [overrides default-fallback-err-msg]
  (or (some-> overrides first :schedule)
      (throw (ex-info default-fallback-err-msg ...))))

(defn- regular-component [db ctx as-of taxable-income functional-commodity]
  (let [{:keys [schedule-overrides tax-items provisions]}
        (component-items db ctx as-of :regular)
        schedule  (pick-schedule schedule-overrides
                                 "IN CIT regular component: no schedule-override fired — check :tax-unit :regime + :turnover-band")
        scoped-ctx (assoc ctx :component :regular :db db :as-of as-of)
        gross     (ts/apply-schedule schedule taxable-income)
        {liability :liability tax-resolved :resolved}
        (ts/apply-adjustments gross tax-items scoped-ctx)]
    {:kind            :corporate-income-tax
     :authority       :in-cbdt
     :base            {:amount taxable-income :commodity functional-commodity}
     :schedule        schedule
     :gross-liability {:amount gross :commodity functional-commodity}
     :surtaxes        (mapv #(select-keys % [:code :label :amount :provenance]) tax-resolved)
     :liability       {:amount liability :commodity functional-commodity}
     :regime          (get-in ctx [:tax-unit :regime])
     :provenance      {:provider-id :in-cit
                       :statute "Income-tax Act 1961 (Regular CIT)"
                       :provisions-applied (mapv :provision/code provisions)
                       :as-of as-of}}))

(defn- mat-component
  "Build MAT component only when applicable (standard regime + not foreign).
   Returns nil to signal 'no MAT' (caller skips composition)."
  [db ctx as-of book-profit functional-commodity]
  (let [{:keys [schedule-overrides tax-items provisions]}
        (component-items db ctx as-of :mat)]
    (when (seq schedule-overrides)        ; MAT provision fired
      (let [schedule  (first-schedule schedule-overrides)
            scoped-ctx (assoc ctx :component :mat :db db :as-of as-of)
            gross     (ts/apply-schedule schedule book-profit)
            {liability :liability tax-resolved :resolved}
            (ts/apply-adjustments gross tax-items scoped-ctx)
            mat-credit-carry (max 0M (- liability ...))]   ; computed by caller
        {:kind            :minimum-tax
         :authority       :in-cbdt
         :base            {:amount book-profit :commodity functional-commodity}
         :schedule        schedule
         :gross-liability {:amount gross :commodity functional-commodity}
         :surtaxes        (mapv #(select-keys % [:code :label :amount :provenance]) tax-resolved)
         :liability       {:amount liability :commodity functional-commodity}
         :regime          (get-in ctx [:tax-unit :regime])
         :composed-of     [:corporate-income-tax]
         :provenance      {:provider-id :in-cit
                           :statute "§115JB"
                           :provisions-applied (mapv :provision/code provisions)
                           :as-of as-of}}))))

(defrecord INCITProvider [id commodity]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period db inputs] :as ctx}]
    (let [as-of           (or (:as-of ctx) (:to period))
          taxable-income  (or (:taxable-income inputs)
                              (throw (ex-info "IN CIT needs :inputs :taxable-income" {:inputs inputs})))
          book-profit-115jb (:book-profit-115jb inputs)
          regular         (regular-component db ctx as-of taxable-income commodity)
          mat             (when book-profit-115jb
                            (mat-component db ctx as-of book-profit-115jb commodity))
          prevailing      (if mat
                            (-> (statute/compose-greater-of regular mat)
                                (assoc-in [:provenance :mat-credit-carry-forward]
                                          (when (> (-> mat :liability :amount)
                                                   (-> regular :liability :amount))
                                            {:amount   (- (-> mat :liability :amount)
                                                          (-> regular :liability :amount))
                                             :commodity commodity
                                             :max-years 15
                                             :statute  "§115JAA"
                                             :status   :recorded-deferred-utilisation})))
                            regular)]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :in :authority :in-cbdt}
        :functional-commodity commodity
        :components           [prevailing]}))))

(defn in-cit-provider
  [{:keys [id commodity] :or {id :in-cit commodity :INR}}]
  (->INCITProvider id commodity))

(defn install-statute!
  [conn] (cit-statute/install! conn))
```

**Provider key.** `:authority :in-cbdt` (Central Board of Direct Taxes,
the income-tax authority under the Department of Revenue). Default
commodity `:INR`.

### §6.4 `cit_provider_test.clj` shape (~12 deftests / ~55 assertions)

Mirrors `cit_provider_test.clj` for DE:

- `§1` BMF-analog — Example A (standard regime, ₹50L income, no
  surcharge, regular prevails).
  - `bmf-analog-standard-small-50L` (4 assertions: base, gross,
    surtaxes, liability)
- `§2` Example B — §115BAA election @ ₹5cr income.
  - `case-115BAA-5cr` (4 assertions including 25.168 % effective rate)
- `§3` Example C — MAT-binding case.
  - `mat-binding-case-50L-vs-2cr-book` (6 assertions: regular liability,
    MAT liability, prevailing, composition record, mat-credit-carry-
    forward amount, prevailing :kind)
- `§4` Bitemporal foreign-co swap (Example D).
  - `foreign-co-pre-2024-40pct` (3 assertions)
  - `foreign-co-post-2024-35pct` (3 assertions)
- `§5` `:tax-unit` required-fact traps.
  - `regime-missing-raises` (1 assertion)
  - `turnover-band-missing-raises-when-standard` (1 assertion)
- `§6` 115BAA does NOT compute MAT.
  - `115BAA-skips-mat-component` (3 assertions: components count,
    no `:composition`, only `:corporate-income-tax` kind)
- `§7` Marginal relief at ₹1 cr boundary.
  - `marginal-relief-at-1cr-boundary` (3 assertions covering the cliff
    just above ₹1 cr where MR bites)
- `§8` Provenance.
  - `provisions-applied-recorded-in-provenance` (2 assertions for
    standard + 115BAA paths)
- `§9` Substrate guarantees.
  - `installable-is-idempotent` (1 assertion)
  - `functional-commodity-is-inr-on-every-money` (2 assertions)

Total: ~12 deftests, ~55 assertions.

### §6.5 Per-AY bitemporal seams

Every rate provision carries `:effective-from`. The following AY-keyed
swaps must be testable:

- **AY 2026-27** (FY 2025-26) — MAT rate 15 %.
- **AY 2027-28** (FY 2026-27) — MAT rate **14 %** (Union Budget 2025).
- **AY 2024-25** (FY 2023-24) — Foreign-co rate 40 %.
- **AY 2025-26** (FY 2024-25) — Foreign-co rate **35 %** (FA 2024).

The `:as-of` parameter to the provider drives parameter resolution. The
bitemporal swap test (Example D in §4.4) covers the foreign-co
transition explicitly; a parallel test for MAT 15→14 should run twice
with `:as-of #inst "2026-03-31"` vs `#inst "2026-04-01"`.

**Period-start convention** (ADR-101 Addendum 2): if a future amendment
applies to "fiscal years beginning on or after X", the provision condition
must include `[:geq [:period :from] X]`, not rely on `:effective-from`.
v1 of the IN CIT provider does NOT need this — all amendments to date
are "transactions on or after X" (event-date semantics, ADR-101
Addendum 2 form 1). Note 122 / this blueprint use `:effective-from`
exclusively.

---

## §7. License + sourcing notes

### §7.1 Primary statute (public domain)

Every `:provision/citation` resolves to either:

- **indiacode.nic.in** — Government of India National Portal. All
  statutes (Income-tax Act 1961, Finance Acts, Taxation Laws Amendments)
  are public domain by virtue of being central legislation.
- **incometaxindia.gov.in** — Central Board of Direct Taxes site. CBDT
  circulars + notifications + the Income-tax Act text as published by
  the Ministry of Finance. Public domain (works of the Government of
  India under §2(k) of Indian Copyright Act 1957 exempted from
  copyright per §52(1)(q)).

### §7.2 Secondary (commercial commentary) — citation only

Used to cross-check worked example numbers. NO text lifted:

- **PwC India Tax Summaries** — Taxes on corporate income / Foreign
  companies.
  - <https://taxsummaries.pwc.com/india/corporate/taxes-on-corporate-income>
- **KPMG India Tax Card 2025-26** — confirmation of foreign-co rate cut.
- **EY India Tax Insights** — UB-2025 highlights for the MAT 14 % step.
- **ClearTax** — §115BAA / Marginal Relief / MAT / Domestic Co. tax
  rate explainers (calculator cross-checks for golden test numbers).
- **Tax2win** — §87A / MAT / Set-off-of-losses calculators.
- **Motilal Oswal** — Corporate Tax 2025-26 Rates & Regimes guide.
- **IndiaFilings** — Domestic company tax rates FY 2025-26 + 2026-27.
- **Bajaj Finserv** — §115BAA + §115BAB conditions explainers.
- **TaxBuddy** — §115JB provisions explainer.

### §7.3 ADR-090 concept-IRI alignment

Every `:parameter` carries a `:parameter/concept-iri` pointing at the
GoI / CBDT source page for the rate. Aligns with ADR-090's substrate
seam for stable IRIs into XBRL / FIBO taxonomies (note 78). The CBDT
Income-tax Act URI path is stable across years (the
`102120000000089312.htm`-style file numbers are CBDT's permanent
identifiers for §-anchored statute text).

### §7.4 Implementation work is original

The DE/JP CIT providers, the IN PIT provider, and the IN CGT provider
(per ADR-103) all wrote provider code from scratch against the
substrate. The IN CIT provider follows the same convention — no
translation of any Catala / OpenFisca / Avalara / commercial tax-engine
code; the provider is original Clojure expressing the statute as
ADR-101 data.

---

## §8. Estimated implementation effort

| Artifact | LOC | Notes |
|---|---|---|
| `cit_statute.clj` | ~420 | 9 parameters + 12 values + 6 brackets + 3 regimes + 11 provisions + install! |
| `cit_provider.clj` | ~160 | 4 compute-fns + register! + 3 component-builders (regular/mat/foreign) + provider + helper |
| `cit_provider_test.clj` | ~350 | ~12 deftests / ~55 assertions; 3 worked examples + bitemporal swap + traps + provenance |
| **Total** | **~930** | Pure Clojure; no substrate changes; ~1 day of implementation effort |

Compare to ADR-104 DE CIT (~315 + ~280 + ~200 ≈ 800 LOC) and JP CIT
(~500 + ~410 + ~480 ≈ 1390 LOC). IN sits between the two — simpler
than JP's 5-component stack, slightly larger than DE because of the
MAT 2-component composition + foreign-co regime.

---

## §9. Summary table — implementation checklist

| Task | File | Reference |
|---|---|---|
| Encode 9 parameters | `cit_statute.clj` | §2.1 |
| Encode 12 parameter-values w/ citations | `cit_statute.clj` | §2.2 |
| Encode 6 parameter-brackets (standard + foreign surcharges) | `cit_statute.clj` | §2.3 |
| Encode 3 regimes (standard / 115BAA / 115BAB) | `cit_statute.clj` | §2.4 |
| Encode 11 provisions (5 rates + 3 surcharges + 1 cess + 2 MAT) | `cit_statute.clj` | §2.5 |
| `install!` idempotent | `cit_statute.clj` | §6.2 |
| 4 compute-fns: surcharge-standard / surcharge-concessional / surcharge-foreign / cess | `cit_provider.clj` | §6.3 |
| `regular-component` builder | `cit_provider.clj` | §6.3 |
| `mat-component` builder (returns nil when MAT skipped) | `cit_provider.clj` | §6.3 |
| `INCITProvider` record using `compose-greater-of` | `cit_provider.clj` | §3 + §6.3 |
| `:authority :in-cbdt`; commodity `:INR` | `cit_provider.clj` | §6.3 |
| Tests: 3 worked examples + bitemporal foreign + traps + provenance | `cit_provider_test.clj` | §4 + §6.4 |
| Document MAT credit deferral in provider docstring | `cit_provider.clj` | §3.5 |
| Document DDT abolition in provider docstring | `cit_provider.clj` | §1.5 |
| Note 122 P0-1 closed by ADR-101 Addendum 1 (no substrate change) | n/a | §5.1 |

---

## §10. Sources

### Statute (public domain)

- [Income-tax Act 1961 — indiacode.nic.in](https://www.indiacode.nic.in/handle/123456789/15259)
- [Income-tax Act §115BAA — incometaxindia.gov.in](https://incometaxindia.gov.in/Acts/Income-tax+Act,+1961/2025/102120000000088566.htm)
- [Income-tax Act §115BAB — incometaxindia.gov.in](https://incometaxindia.gov.in/Acts/Income-tax+Act,+1961/2025/102120000000088567.htm)
- [Income-tax Act §115JB (MAT) — incometaxindia.gov.in](https://incometaxindia.gov.in/Acts/Income-tax+Act,+1961/2025/102120000000089312.htm)
- [Income-tax Act §115JAA (MAT credit) — incometaxindia.gov.in](https://incometaxindia.gov.in/Acts/Income-tax+Act,+1961/2025/102120000000089311.htm)
- [Finance (No. 2) Act 2024 — indiacode.nic.in](https://www.indiacode.nic.in/handle/123456789/15247)
- [Taxation Laws (Amendment) Act 2019 (Act 46/2019) — PRS legislative brief](https://prsindia.org/billtrack/prs-products/prs-legislative-brief-3358)
- [Domestic Company for AY 2026-27 — incometax.gov.in help page](https://www.incometax.gov.in/iec/foportal/help/company/return-applicable)
- [Tax Rates — Domestic — incometaxindia.gov.in tables index](https://incometaxindia.gov.in/Documents/Left%20Menu/TAX%20RATES-domestic.htm)

### Commercial commentary (citation only, no text lifted)

- [pwc India Tax Summaries — Taxes on corporate income](https://taxsummaries.pwc.com/india/corporate/taxes-on-corporate-income)
- [ClearTax — §115BAA new tax rate for domestic companies](https://cleartax.in/s/section-115-baa-tax-rate-domestic-companies)
- [ClearTax — Marginal relief surcharge AY 2026-27](https://cleartax.in/s/marginal-relief-surcharge)
- [ClearTax — MAT eligibility and calculation](https://cleartax.in/s/tax-planning-under-mat)
- [Tax2win — MAT under §115JB](https://tax2win.in/guide/minimum-alternative-tax)
- [Motilal Oswal — Corporate Tax 2025-26 rates and regimes](https://www.motilaloswal.com/personal-finance/tax/corporate-tax-in-india-2025-26-rates-regimes-complete-guide)
- [Motilal Oswal — §115BAB 15% rate for new manufacturers](https://www.motilaloswal.com/personal-finance/tax/section-115bab-15-corporate-tax-guide-for-new-manufacturers)
- [IndiaFilings — Domestic company tax rates FY 2025-26 + 2026-27](https://www.indiafilings.com/income-tax/domestic-company-tax-rate)
- [Bajaj Finserv — §115BAA conditions and Form 10-IC](https://www.bajajfinserv.in/investments/section-115-baa-of-income-tax-act)
- [Bajaj Finserv — §115BAB applicability](https://www.bajajfinserv.in/investments/section-115bab-of-income-tax-act)
- [TaxBuddy — §115JB MAT provisions](https://www.taxbuddy.com/blog/section-115jb-of-the-income-tax-act)
- [PIB India — ₹400 cr turnover threshold (Budget 2019)](https://www.pib.gov.in/Pressreleaseshare.aspx?PRID=1577365)

### DDT abolition (informational)

- [Cyril Amarchand — DDT abolition (2020 classical-system return)](https://corporate.cyrilamarchandblogs.com/2020/04/abolition-of-dividend-distribution-tax-a-new-paradigm-for-equity-investments/)
- [KPMG — Taxation of dividends post-DDT (May 2020)](https://assets.kpmg.com/content/dam/kpmgsites/in/pdf/2020/10/taxation-of-dividend.pdf)

### kontor sources under reference

- `doc/decisions.md` — ADR-099, ADR-101, ADR-101 Addendum 1, ADR-101
  Addendum 2, ADR-104, ADR-106
- `doc/research/122-in-cit-substrate-fit.md` — the substrate-fit
  cross-check this blueprint extends
- `doc/research/108-de-cit-fit.md` + `doc/research/120-de-cit-baseline-review.md`
  — DE CIT reference template
- `doc/research/110-jp-cit-fit.md` — JP CIT multi-component reference
- `doc/research/119-adr-101-draft.md` — ADR-101 design decisions
- `src/kontor/statute.clj` — ADR-101 evaluator + `compose-greater-of`
- `src/kontor/tax_schedule.clj` — schedule + adjustment-layer
- `src/kontor/period_tax_provider.clj` — `PeriodTaxProvider` protocol +
  `TaxReturnFacts` + `period-tax-kinds` enum (includes `:minimum-tax`)
- `modules/l10n-de/src/kontor/l10n_de/cit_statute.clj` — gold-standard
  parameter + provision data
- `modules/l10n-de/src/kontor/l10n_de/cit_provider.clj` — gold-standard
  thin provider
- `modules/l10n-jp/src/kontor/l10n_jp/cit_provider.clj` — multi-pass
  surtax cascade reference (cross-component context injection)
- `modules/l10n-in/src/kontor/l10n_in/period_tax_provider.clj` — IN PIT
  provider (record-shaped; same module's existing convention)

---

End of note 163.
