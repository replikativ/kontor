---
date: 2026-05-24
title: 122 — IN corporate income tax substrate-fit (ADR-101) + IN PIT migration sketch
audience: maintainer
status: cross-check — ADR-101 substrate-fit assessment; no code change
---

# 122 — IN CIT substrate fit + IN PIT migration sketch

The maintainer's tax-completion program just shipped two structural artifacts:
ADR-101 (statute-as-data: `:tax-concept` / `:provision` / `:regime` /
`:parameter`) and ADR-104 (DE CIT — KSt + Soli + GewSt — the first end-to-end
consumer that reproduces a BMF worked example to the cent). Before fanning out
to FR / JP / CA / US, this note cross-checks the substrate against **India**.
Two angles:

(a) Does ADR-101 handle IN corporate income tax (income-tax on companies +
    MAT) cleanly?
(b) Could the shipped IN PIT provider —
    `modules/l10n-in/src/kontor/l10n_in/period_tax_provider.clj`, the
    note-105 adjustment-layer's *original provoking case* — be migrated to
    `:provision` data, and what would that look like?

The headline: **yes on both counts, with one known stress point.** The DE CIT
pattern (parameters + scoped provisions + a thin provider with compute-fn
escape hatches) ports to IN CIT essentially verbatim. The §87A rebate, the
seven-band surcharge with marginal-relief, and the 4% cess that drove the
note-105 adjustment-layer design are *the same shapes* the substrate already
handles. The single genuine friction is **MAT** — `kontor.tax-schedule/
greater-of` IS in the substrate, but composing it through `:provision` data
needs one new piece: a way for a provision to declare "produce a minimum-tax
component to compare against the regular-tax component," because MAT and
regular CIT have *different bases* and so cannot be encoded as two
`:provision`s on the same concept. The cleanest fix is a second component the
provider assembles by hand (the DE provider does the analogous thing for
two-component KSt + GewSt) — no substrate change required. This is the same
"known stress point" note 102 §7 / §9-A flagged.

## §1. Statute summary — IN CIT for AY 2026-27 (FY 2025-26)

A normal Indian incorporated business (`Pvt. Ltd.` or `Public Ltd.`) for the
tax year filed Sep 2026 against income earned 1 Apr 2025 – 31 Mar 2026 picks
one of three regimes (irrevocable election where flagged).

### 1.1 Base rate ladder (Income-tax Act 1961 §§ Finance Act tables + 115BAA / 115BAB)

| Regime | Base rate | Eligible companies | Surcharge | MAT? |
|---|---|---|---|---|
| **Standard regime** — small | 25% | Turnover ≤ ₹400 cr in PY 2023-24 | 7% / 12% banded | YES (§115JB) |
| **Standard regime** — large | 30% | Turnover > ₹400 cr in PY 2023-24 | 7% / 12% banded | YES (§115JB) |
| **§115BAA** — concessional flat | 22% | Any domestic company that elects (Form 10-IC, irrevocable) | **Flat 10%** | **NO — exempt by §115JB(5A)** |
| **§115BAB** — new-manufacturer | 15% | Co. incorporated ≥ 1 Oct 2019; began manufacturing ≤ 31 Mar 2024 | **Flat 10%** | **NO — exempt by §115JB(5A)** |

The concessional regimes (`115BAA` / `115BAB`) require the company to forgo
SEZ §10AA, additional depreciation in backward areas, §35 R&D super-
deductions, §35AD specified-business deductions, and most Chapter VI-A
deductions (§80IA, §80IAB, §80IAC, §80IB). The election is exercised on
Form 10-IC (115BAA) or Form 10-ID (115BAB) on or before the §139(1) ITR
due date; once exercised it cannot be reversed (`incometax.gov.in` AY 2026-27
help page).

### 1.2 Surcharge (Finance Act 2025 First Schedule)

**Standard regime** — banded by total income, with marginal relief at each
threshold:

| Income band | Surcharge |
|---|---|
| ≤ ₹1 cr | 0% |
| > ₹1 cr and ≤ ₹10 cr | **7%** |
| > ₹10 cr | **12%** |

**Concessional regimes (115BAA / 115BAB)** — flat **10%** surcharge
regardless of income. No marginal relief needed (it is flat).

**Marginal relief rule** (Income-tax Act, applied via the Finance Act
provisos): at the `₹1 cr` boundary, the total of (tax + surcharge) on the
higher income may not exceed (tax at exactly ₹1 cr) + (income beyond ₹1 cr).
Same shape at the `₹10 cr` boundary against (tax + surcharge at exactly
₹10 cr). The relief is the breach amount; if positive, it reduces the
surcharge.

### 1.3 Health & Education Cess (Finance Act 2025 §2(11))

**4%** on (tax + surcharge), all regimes, no exemption, no slab. Identical
in structure to the PIT cess (see §4 below) — the existing IN PIT provider
already encodes it as a `:surtax` adjustment item.

### 1.4 Minimum Alternate Tax (§115JB)

A floor on the tax liability when a company's regular tax is lower than
**15% of book profit** (the financial-accounting profit, adjusted by the §115JB
Explanation 1 add-backs/exclusions — broadly the P&L profit ± a closed list
of items). The MAT tax rate **drops to 14% effective FY 2026-27** per the
Union Budget 2025 announcement (pwc India Tax Summaries); this note treats
the FY 2025-26 figure of 15% as the in-force value, with the 2026-04-01 step
encoded as a second `:parameter-value`.

MAT applies **only** in the standard regime — §115JB(5A) explicitly exempts
companies that have elected 115BAA or 115BAB. The MAT computation has its
own surcharge + cess layer (same bands as regular).

MAT credit (§115JAA): the difference (MAT − regular tax) carries forward up
to **15 assessment years** and can be set off against regular tax in a later
year, capped at 25% of normal tax liability per year. Gated on note 105
frontier 2 (the carry primitive) — not in scope here.

### 1.5 Dividend Distribution Tax (formerly §115-O)

**Abolished** with effect from 1 April 2020 by the Finance Act 2020. India
returned to the *classical system*: dividends are taxed in the recipient's
hands at their applicable slab rate, with §194 / 195 TDS withholding by the
distributing company (10% for resident shareholders; 20% + surcharge + cess
for non-residents subject to treaty rates). Mentioned for completeness;
**defer** — DDT is not a current-year cost and the recipient-side dividend
taxation is just another head of income flowing through the PIT provider.

### 1.6 Carry-forward of losses (§§72, 73)

- **Non-speculative business loss** — 8 assessment years from the year the
  loss arose; settable only against business income (§72).
- **Speculative business loss** — 4 assessment years; settable only against
  speculative business income (§73).
- Unabsorbed depreciation (§32(2)) — **no time limit**, settable against any
  head except salary.

All three are *fold-over-the-period-stream* mechanics — note 105 frontier 2
(the carry primitive). Out of scope here; flagged so the CIT provider's
specification carries the deferral.

### 1.7 TDS adjustments (Chapter XVII-B)

Withholding suffered on customer payments (e.g. §194C contracts, §194J
professional fees) is a prepaid credit against the assessed CIT liability —
the `:inputs / :prepaid` slot on `TaxReturnFacts` (note 102 §2). Plain
data, no provision-modelling needed; the provider just stamps it onto the
component.

## §2. Worked example — ₹3 crore Pvt. Ltd. under the standard regime

The user's prompt asked for "₹3 crore under §115BAA hitting the 7%
surcharge," but **§115BAA's surcharge is flat 10% regardless of income** —
the 7% / 12% bands only apply to the standard regime. To exercise both
shapes, this section computes the same Pvt. Ltd. (turnover ≤ ₹400 cr, so the
25% standard base) twice — once under the standard regime hitting the 7%
band, once after electing §115BAA at the flat 10%.

### 2.1 Standard regime, taxable income ₹3 cr, turnover ≤ ₹400 cr

```
Taxable income:               ₹3,00,00,000     (= 30,000,000)
Base rate (25%):              ₹  75,00,000     (= 25% × 3 cr)
Surcharge band:               7%               (since 1 cr < 3 cr ≤ 10 cr)
Surcharge:                    ₹   5,25,000     (= 7% × 75 L)
Subtotal tax + surcharge:     ₹  80,25,000
Health & Education Cess (4%): ₹   3,21,000
TOTAL CIT liability:          ₹  83,46,000
                              ─────────────
Effective rate:               27.82%
```

Marginal-relief check at the ₹1 cr boundary:
- Tax at exactly ₹1 cr (no surcharge): ₹25 L.
- Excess income beyond ₹1 cr: ₹2 cr.
- Cap on (tax + surcharge): ₹25 L + ₹2 cr = ₹2.25 cr.
- Actual (tax + surcharge): ₹80.25 L. **Cap not breached → no marginal relief
  fires**, full 7% surcharge stands. (At ₹3 cr the marginal-relief
  arithmetic is well inside the cap; the relief mostly bites just above
  ₹1 cr where surcharge mass is small relative to the threshold tax.)

Verified against ClearTax's marginal-relief explainer and the
`incometax.gov.in` "Domestic Company AY 2026-27" help page.

### 2.2 Same income, §115BAA elected (flat 22% / 10% / 4%)

```
Taxable income:               ₹3,00,00,000
Base rate (22%):              ₹  66,00,000
Surcharge (flat 10%):         ₹   6,60,000
Subtotal:                     ₹  72,60,000
Cess (4%):                    ₹   2,90,400
TOTAL CIT liability:          ₹  75,50,400
                              ─────────────
Effective rate:               25.168%
```

The 25.17% effective rate matches the headline number every Indian tax
commentary quotes for §115BAA (ClearTax, pwc, Motilal Oswal, IndiaFilings).
The 115BAA election saves **₹7,95,600** on this fact pattern — but the
company must give up the deductions/exemptions listed in §1.1.

### 2.3 MAT trigger example (illustrative)

A Pvt. Ltd. with **book profit ₹2 cr** but **taxable income ₹50 L after
chapter VI-A deductions** (an §80IA infrastructure-incentive case):

```
Regular tax: 25% × 50 L              = ₹12,50,000
  + cess 4% (no surcharge < 1 cr)    = ₹   50,000
  → Regular total                    = ₹13,00,000

MAT base (book profit):              = ₹2,00,00,000
MAT @ 15%:                           = ₹30,00,000
  + surcharge 7% (book profit > 1 cr)= ₹ 2,10,000
  + cess 4%                          = ₹ 1,28,400
  → MAT total                        = ₹33,38,400

Final tax = max(regular, MAT)        = ₹33,38,400
MAT credit carried forward            = ₹20,38,400 (§115JAA, 15 yrs)
```

The `:greater-of` decision shape between two *different bases* is the §3.3
structural friction below.

## §3. Substrate fit — `:provision` + `:parameter` EDN sketch for IN CIT

The clean cases first; the MAT stress point in §3.3.

### 3.1 Parameters

```clojure
;; ============================================================================
;; IN CIT parameters — Finance Act 2025 + Income-tax Act §§115BAA/115BAB/115JB
;; ============================================================================
(def parameters
  [{:parameter/code         "IN.CIT.standard.small-turnover-rate"
    :parameter/label        "Standard regime — base rate for cos. with PY 2023-24 turnover ≤ ₹400 cr"
    :parameter/jurisdiction :in
    :parameter/unit         :rate
    :parameter/concept-iri  "https://incometaxindia.gov.in/charts+tables/tax+rates.htm"}

   {:parameter/code         "IN.CIT.standard.large-turnover-rate"
    :parameter/label        "Standard regime — base rate for cos. with PY 2023-24 turnover > ₹400 cr"
    :parameter/jurisdiction :in
    :parameter/unit         :rate}

   {:parameter/code         "IN.CIT.115BAA.rate"
    :parameter/label        "§115BAA concessional flat rate for any domestic co. that elects"
    :parameter/jurisdiction :in
    :parameter/unit         :rate
    :parameter/concept-iri  "https://incometaxindia.gov.in/Acts/Income-tax+Act,+1961/2025/102120000000088566.htm"}

   {:parameter/code         "IN.CIT.115BAB.rate"
    :parameter/label        "§115BAB concessional flat rate — new-manufacturer regime"
    :parameter/jurisdiction :in :parameter/unit :rate}

   {:parameter/code         "IN.CIT.standard.surcharge-brackets"
    :parameter/label        "Standard regime surcharge banded by total income (₹1cr / ₹10cr)"
    :parameter/jurisdiction :in
    :parameter/unit         :bracket-scale}

   {:parameter/code         "IN.CIT.concessional.surcharge-rate"
    :parameter/label        "§115BAA/115BAB flat 10% surcharge"
    :parameter/jurisdiction :in :parameter/unit :rate}

   {:parameter/code         "IN.CIT.cess.rate"
    :parameter/label        "Health & Education Cess — 4% on (tax + surcharge), all regimes"
    :parameter/jurisdiction :in :parameter/unit :rate}

   {:parameter/code         "IN.CIT.MAT.rate"
    :parameter/label        "§115JB Minimum Alternate Tax rate (15% through FY 2025-26; 14% FY 2026-27)"
    :parameter/jurisdiction :in :parameter/unit :rate
    :parameter/concept-iri  "https://incometaxindia.gov.in/Acts/Income-tax+Act,+1961/2025/102120000000089312.htm"}

   {:parameter/code         "IN.CIT.turnover-threshold-small"
    :parameter/label        "₹400 cr turnover threshold separating 25% and 30% standard rates"
    :parameter/jurisdiction :in :parameter/unit :amount-money}])

(def parameter-values
  [;; Standard 25% / 30% bands — stable since Finance Act 2019 raised the
   ;; small-co threshold from ₹250 cr to ₹400 cr.
   {:parameter-value/parameter      [:parameter/code "IN.CIT.standard.small-turnover-rate"]
    :parameter-value/effective-from #inst "2019-04-01"
    :parameter-value/decimal-value  0.25M
    :parameter-value/citation       "Finance (No. 2) Act 2019 — turnover threshold raised to ₹400 cr"}

   {:parameter-value/parameter      [:parameter/code "IN.CIT.standard.large-turnover-rate"]
    :parameter-value/effective-from #inst "2019-04-01"
    :parameter-value/decimal-value  0.30M}

   ;; §115BAA — Taxation Laws (Amendment) Ordinance 2019, effective AY 2020-21
   ;; (i.e. tax year starting 1 Apr 2019).
   {:parameter-value/parameter      [:parameter/code "IN.CIT.115BAA.rate"]
    :parameter-value/effective-from #inst "2019-04-01"
    :parameter-value/decimal-value  0.22M
    :parameter-value/citation       "Taxation Laws (Amendment) Ordinance 2019 (BGBl. — n/a; Act 46 of 2019)"}

   {:parameter-value/parameter      [:parameter/code "IN.CIT.115BAB.rate"]
    :parameter-value/effective-from #inst "2019-04-01"
    :parameter-value/decimal-value  0.15M}

   {:parameter-value/parameter      [:parameter/code "IN.CIT.concessional.surcharge-rate"]
    :parameter-value/effective-from #inst "2019-04-01"
    :parameter-value/decimal-value  0.10M}

   {:parameter-value/parameter      [:parameter/code "IN.CIT.cess.rate"]
    :parameter-value/effective-from #inst "2018-04-01"  ; HEC introduced AY 2019-20
    :parameter-value/decimal-value  0.04M
    :parameter-value/citation       "Finance Act 2018 — Health & Education Cess replaced Education Cess + SHEC"}

   ;; MAT — 15% through FY 2025-26 (AY 2026-27); 14% from FY 2026-27.
   {:parameter-value/parameter       [:parameter/code "IN.CIT.MAT.rate"]
    :parameter-value/effective-from  #inst "2019-04-01"
    :parameter-value/effective-until #inst "2026-04-01"
    :parameter-value/decimal-value   0.15M
    :parameter-value/citation        "Finance (No. 2) Act 2019 — MAT rate dropped 18.5% → 15%"}

   {:parameter-value/parameter      [:parameter/code "IN.CIT.MAT.rate"]
    :parameter-value/effective-from #inst "2026-04-01"
    :parameter-value/decimal-value  0.14M
    :parameter-value/citation       "Union Budget 2025 — MAT rate 15% → 14% from FY 2026-27"}

   {:parameter-value/parameter      [:parameter/code "IN.CIT.turnover-threshold-small"]
    :parameter-value/effective-from #inst "2019-04-01"
    :parameter-value/decimal-value  4000000000M}]) ; ₹400 cr in rupees

;; Surcharge brackets are :parameter-bracket entities under the
;; "IN.CIT.standard.surcharge-brackets" parent:
(def parameter-brackets
  [{:parameter-bracket/parameter      [:parameter/code "IN.CIT.standard.surcharge-brackets"]
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
    :parameter-bracket/effective-from #inst "2018-04-01"}])
```

### 3.2 Regimes + provisions

The clean cases all encode straightforwardly. The election rides ADR-034
status-machine on a `:regime` entity:

```clojure
(def regimes
  [{:regime/code :in-cit-standard
    :regime/label "Standard regime — 25% / 30% rate + 7%/12% banded surcharge + MAT"
    :regime/jurisdiction :in}
   {:regime/code :in-cit-115BAA
    :regime/label "§115BAA — flat 22% + flat 10% surcharge; MAT-exempt; election irrevocable"
    :regime/jurisdiction :in}
   {:regime/code :in-cit-115BAB
    :regime/label "§115BAB — flat 15% for new manufacturers + flat 10% surcharge; MAT-exempt"
    :regime/jurisdiction :in}])

(def provisions
  ;; -- Surcharge: one per regime, scoped via :provision/regime --
  [{:provision/code            "IN-FinAct-surcharge-standard"
    :provision/jurisdiction    :in
    :provision/concept         [:tax-concept/code :surtax]
    :provision/regime          [:regime/code :in-cit-standard]
    :provision/title           "Finance Act surcharge — standard regime, banded with marginal relief"
    :provision/citation        "Finance Act 2025 First Schedule, Part III paragraph A"
    :provision/effective-from  #inst "2018-04-01"
    :provision/priority        100
    :provision/consequence     (pr-str {:op :surtax
                                        :code :in-surcharge-standard
                                        :label "CIT surcharge (banded with marginal relief)"
                                        :amount-from :compute-fn
                                        :fn :in-cit-surcharge-standard})}

   {:provision/code            "IN-FinAct-surcharge-115BAA"
    :provision/jurisdiction    :in
    :provision/concept         [:tax-concept/code :surtax]
    :provision/regime          [:regime/code :in-cit-115BAA]
    :provision/title           "§115BAA surcharge — flat 10%"
    :provision/effective-from  #inst "2019-04-01"
    :provision/priority        100
    :provision/consequence     (pr-str {:op :surtax
                                        :code :in-surcharge-115BAA
                                        :label "§115BAA flat surcharge"
                                        :amount-from :compute-fn
                                        :fn :in-cit-surcharge-concessional})}

   ;; -- HEC cess — regime-free; applies to all three --
   {:provision/code            "IN-FinAct-cess"
    :provision/jurisdiction    :in
    :provision/concept         [:tax-concept/code :surtax]
    :provision/title           "Health & Education Cess — 4% of (tax + surcharge)"
    :provision/citation        "Finance Act 2018 §2(11)"
    :provision/effective-from  #inst "2018-04-01"
    :provision/priority        500              ; fires AFTER surcharge per Income-tax Act order
    :provision/consequence     (pr-str {:op :surtax
                                        :code :in-hec-cess
                                        :label "Health & Education Cess (4%)"
                                        :amount-from :compute-fn
                                        :fn :in-cit-cess})}])
```

Three compute-fns registered by the provider (mirroring DE's pattern):

```clojure
(defn- in-cit-surcharge-standard
  "Banded surcharge with marginal relief at ₹1cr and ₹10cr.
   The fn returns a (fn [ctx-w-running]) per the apply-adjustments late-bound
   convention — surcharge depends on :base (income band) AND :running
   (post-credit tax)."
  [ctx]
  (let [db    (:db ctx)
        as-of (or (:as-of ctx) (-> ctx :period :to))
        bands (statute/parameter-brackets-at db "IN.CIT.standard.surcharge-brackets" as-of)
        base-rate (case (get-in ctx [:tax-unit :turnover-band])
                    :small (statute/parameter-value-at db "IN.CIT.standard.small-turnover-rate" as-of)
                    :large (statute/parameter-value-at db "IN.CIT.standard.large-turnover-rate" as-of))]
    (fn [{:keys [base running] :as ctx-w-running}]
      (let [band (active-band bands base)
            raw  (* running (:rate band))
            ;; marginal-relief: clamp (running + surcharge) so it doesn't
            ;; exceed (tax at threshold) + (income beyond threshold)
            threshold      (some-> band :prior-upper)        ; the bracket's lower edge
            tax-at-thresh  (* threshold base-rate)
            cap            (when threshold (+ tax-at-thresh (- base threshold)))]
        (if (and cap (> (+ running raw) cap))
          (max 0M (- cap running))
          raw)))))

(defn- in-cit-cess [ctx]
  (fn [{:keys [running]}]
    (* running (statute/parameter-value-at (:db ctx) "IN.CIT.cess.rate"
                                           (or (:as-of ctx) (-> ctx :period :to))))))
```

The provider:

```clojure
(defrecord InCITProvider [id commodity]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period db inputs tax-unit] :as ctx}]
    (let [as-of   (or (:as-of ctx) (:to period))
          regime  (or (:regime tax-unit) :in-cit-standard)
          base    (or (:taxable-income inputs)
                      (throw (ex-info "IN CIT needs :inputs :taxable-income" {:inputs inputs})))
          ;; ... two-component fold like DE: regular CIT + (when standard) MAT
          ]
      ...)))
```

**Verdict on §3.1–3.2:** every clean piece encodes inside the existing
substrate. The §115BAA / §115BAB *elective with restrictions* shape rides
the existing `:regime` mechanism the way IN PIT's `:new` / `:old` would
(see §4). Surcharge bands ride `:parameter-bracket`. Cess is one
`:provision`. Provisions are regime-scoped via `:provision/regime`. **No
substrate change needed.**

### 3.3 MAT — the structural friction (and the fix)

MAT does NOT fit as a third `:provision` on the regular CIT computation,
because:

- **MAT and regular CIT have different bases.** Regular CIT base = taxable
  income (after Chapter VI-A deductions, etc.). MAT base = **book profit**
  (with §115JB Explanation 1 add-backs/exclusions — a different walk of the
  same P&L). A single `:provision` consequence cannot express "swap the
  base."
- **MAT fires conditionally.** The final liability is
  `max(regular_tax, MAT)` — `kontor.tax-schedule/greater-of`. Two
  liabilities, two bases, then the comparison.
- **`:elect` would be wrong.** As both `kontor.tax-schedule` (ns docstring)
  and the IN PIT provider explicitly note, `:elect` applies multiple
  sub-schedules to ONE shared base. MAT has a different base, so the
  combinator is `greater-of` at the component level, not `:elect` at the
  schedule level.

The substrate already names this: `period-tax-kinds` has
`:minimum-tax` and `kontor.tax-schedule/greater-of` is a documented
component-level combinator. So the **substrate primitives exist**; the
question is how to express the `(regular, MAT) → max` choice as data.

**Two options:**

- **Option A — provider assembles two components manually.** The IN CIT
  provider returns a `TaxReturnFacts` with **two components**: one
  `:corporate-income-tax` (the regular regime computation) and one
  `:minimum-tax` (the §115JB MAT computation). The provider names a
  third "presented liability" entry via `:composed-of [:corporate-income-tax
  :minimum-tax]` (this field already exists per note 102 §9-A/D). The
  *consumer* — or a thin wrapper around `apply-provisions` — picks the
  greater. This is exactly what the DE CIT provider does to assemble its
  KSt + GewSt two-component return; the structural similarity is high.
- **Option B — a new `:provision/consequence` op `:override-with-min`.**
  Add a 3rd-component-level `:op` that means "the produced liability
  replaces the parent's only if larger." Cost: a vocabulary addition
  parallel to `:base-add` / `:base-deduct` / `:credit` / `:surtax`,
  plus a new namespace for "post-component-fold" provisions.

**Recommendation: Option A**, mirroring DE CIT. MAT is naturally a sibling
component (separate authority? — same `:in-income-tax-department`; separate
filing-line — `Schedule MAT` is its own form section). Treating it as a
component carries the audit trail cleanly: the resolved-items list shows
both arms, the consumer sees what was sacrificed when MAT bound.

This matches **note 102 §7's existing flag**: "the AMT / IN MAT shape — two
arms with different bases — cannot be an `:elect` schedule; it is a
component-level `max`, so it is a separate fn. Composed by the provider
at the `TaxReturnFacts` level." ADR-101 did not contradict this; it just
left the composition to provider code. **The substrate-fit verdict for MAT
is "supported, by the same pattern DE CIT uses for its two-component
return; no substrate change."**

The audit-doc shape for the MAT-binding case is then:

```clojure
{:kind :corporate-income-tax
 :base ₹50,00,000
 :liability ₹13,00,000
 :provenance {:provisions-applied ["IN-FinAct-base-25" "IN-FinAct-cess"]}}

{:kind :minimum-tax
 :authority :in-income-tax-department
 :base ₹2,00,00,000    ; book profit, NOT taxable income
 :schedule {:schedule/type :flat :rate 0.15M}
 :gross-liability ₹30,00,000
 :surtaxes [{:code :in-surcharge-standard :amount ₹2,10,000 ...}
            {:code :in-hec-cess           :amount ₹1,28,400 ...}]
 :liability ₹33,38,400
 :composed-of [:corporate-income-tax]
 :provenance {:provisions-applied ["IN-§115JB" "IN-FinAct-surcharge-standard" "IN-FinAct-cess"]}}

;; Provider also surfaces:
{:presented-liability ₹33,38,400  ; max of the two
 :mat-credit ₹20,38,400           ; carry per §115JAA — :carry-out (note 105 frontier 2)
 :composed-of [:corporate-income-tax :minimum-tax]}
```

The MAT credit carry-out is exactly note 105 frontier 2 (the carry primitive,
unshipped). Encode the MAT *computation* now; the *credit utilisation in a
later year* lands when the carry primitive does.

## §4. IN PIT migration sketch — would the shipped provider port?

This is the more interesting question for the maintainer: the IN PIT provider
at `modules/l10n-in/src/kontor/l10n_in/period_tax_provider.clj` is the
**provoking case** for note 105's adjustment-layer design. The §87A rebate
(regime-dependent slab + marginal relief in the new regime, hard cliff in
the old) and the seven-band income-based surcharge with statutory
marginal-relief are precisely the "base-aware, ordered, signed" adjustment
items note 105 named.

The provider has been shipped, record-shaped, and works. If ADR-101 cannot
host it cleanly without substrate additions, **the substrate has failed its
hardest known test**. Let's check.

### 4.1 What the existing provider does (architectural recap)

`in-income-tax-provider {:regime :new|:old}` constructs:

- A regime-fixed `progressive` bracket schedule (4-band old, 7-band new).
- A regime-fixed adjustment layer (3 items): `:credit` for §87A, `:surtax`
  for the income-banded surcharge, `:surtax` for the 4% cess.
- Each adjustment item's `:amount` is a function of `(ctx-with :base
  :running)` — the base-aware fn convention note 105 introduced.
- The §87A and surcharge fns close over the regime's rebate config /
  surcharge bands as Clojure data; cess is rate × running.
- Wraps a generic `personal-income-tax-provider` that runs the schedule +
  the adjustment fold.
- Stamps `:regime <kw>` onto every returned `TaxReturnFacts` component.

The "regime" is an explicit constructor input — NOT an `:elect` schedule,
because the two regimes have *different bases* (new = gross − ₹75k standard
deduction; old = gross − ₹50k − Σ(Chapter VI-A)). Same friction as MAT in
§3.3.

### 4.2 Port to ADR-101 — `:parameter` data first

Every numeric the provider currently encodes as a Clojure literal can become
a `:parameter`. The full migration list (FY 2025-26 / AY 2026-27 values):

| Current literal | `:parameter/code` | Unit | Value | Citation |
|---|---|---|---|---|
| `new-regime-brackets` (7 rows) | `IN.PIT.new.brackets` | `:bracket-scale` | (table) | §115BAC, Union Budget 2025 |
| `old-regime-brackets` (4 rows) | `IN.PIT.old.brackets` | `:bracket-scale` | (table) | First Schedule, Finance Act 2025 |
| `new-regime-standard-deduction` | `IN.PIT.new.standard-deduction` | `:amount-money` | ₹75,000 | §16(ia), Union Budget 2024 |
| `old-regime-standard-deduction` | `IN.PIT.old.standard-deduction` | `:amount-money` | ₹50,000 | §16(ia) |
| `new-regime-87a` `:threshold` | `IN.PIT.new.§87A.threshold` | `:amount-money` | ₹12,00,000 | §87A proviso |
| `new-regime-87a` `:cap` | `IN.PIT.new.§87A.cap` | `:amount-money` | ₹60,000 | §87A |
| `old-regime-87a` `:threshold` | `IN.PIT.old.§87A.threshold` | `:amount-money` | ₹5,00,000 | §87A |
| `old-regime-87a` `:cap` | `IN.PIT.old.§87A.cap` | `:amount-money` | ₹12,500 | §87A |
| `new-regime-surcharge-bands` (3 rows) | `IN.PIT.new.surcharge-bands` | `:bracket-scale` | 10/15/25 % | Finance Act 2025 + §115BAC cap |
| `old-regime-surcharge-bands` (4 rows) | `IN.PIT.old.surcharge-bands` | `:bracket-scale` | 10/15/25/37 % | Finance Act 2025 |
| `health-education-cess-rate` | `IN.PIT.cess.rate` | `:rate` | 0.04M | Finance Act 2018 §2(11) — **reuses `IN.CIT.cess.rate`!** |

**Cess sharing.** The 4% Health & Education Cess is identical on PIT and
CIT — same Finance Act sub-section, same rate, same base (tax + surcharge).
A migrated IN PIT and a future IN CIT provider should *both* reference
`IN.cess.rate` (drop the per-domain prefix) — `:parameter` is jurisdiction-
scoped, not tax-domain-scoped, by design. This is exactly the
cross-jurisdiction-concept benefit note 119 §D6 named for the catalogue,
applied one level down to a per-jurisdiction parameter.

### 4.3 Port to ADR-101 — `:provision` data + `:regime`

The two regimes become `:regime` entities; the schedule becomes one
`:provision` per regime; the §87A rebate, surcharge, and cess become three
adjustments per regime. Sketch (showing only the new regime for brevity;
old regime is symmetric with different `:parameter` refs and
`:provision/regime`):

```clojure
(def regimes
  [{:regime/code :in-pit-new
    :regime/label "§115BAC new regime — statutory default since FY 2023-24"
    :regime/jurisdiction :in}
   {:regime/code :in-pit-old
    :regime/label "Old regime — elected on Form 10-IEA"
    :regime/jurisdiction :in}])

(def provisions
  ;; §16(ia) — standard deduction (regime-keyed; base-deduct)
  [{:provision/code            "IN-PIT-§16ia-new"
    :provision/jurisdiction    :in
    :provision/concept         [:tax-concept/code :base-transform-deduct]
    :provision/regime          [:regime/code :in-pit-new]
    :provision/title           "§16(ia) standard deduction — new regime ₹75,000"
    :provision/citation        "https://incometaxindia.gov.in/Acts/Income-tax+Act,+1961/2025/.../§16"
    :provision/effective-from  #inst "2024-04-01"   ; raised to ₹75k by Union Budget 2024
    :provision/priority        100
    :provision/consequence     (pr-str {:op :base-deduct
                                        :code :in-§16ia-new
                                        :label "§16(ia) standard deduction (new regime)"
                                        :amount-from :parameter
                                        :parameter "IN.PIT.new.standard-deduction"})}

   ;; §115BAC bracket schedule — modelled as a :schedule-override consequence
   ;; (the substrate's :provision/consequence vocab already supports schedule
   ;; selection — DE CIT's KSt rate IS effectively a schedule-override
   ;; via the parameter-driven :flat).
   ;; Cleaner shape: the provider reads the regime → looks up the
   ;; corresponding :bracket-scale parameter → builds the progressive
   ;; schedule. NOT a :provision at all; this is the DE pattern (DE.KSt.rate
   ;; is a parameter; the schedule is built by the provider). Mirror it
   ;; here: schedule construction stays in the provider; the brackets are
   ;; just parameter data.

   ;; §87A rebate — base-aware credit
   {:provision/code            "IN-PIT-§87A-new"
    :provision/jurisdiction    :in
    :provision/concept         [:tax-concept/code :non-refundable-credit]
    :provision/regime          [:regime/code :in-pit-new]
    :provision/title           "§87A rebate — new regime (₹60k cap at ₹12L, marginal relief)"
    :provision/citation        "https://incometaxindia.gov.in/Acts/.../§87A"
    :provision/effective-from  #inst "2025-04-01"  ; FY 2025-26 cap raised
    :provision/priority        200
    :provision/consequence     (pr-str {:op :credit
                                        :code :in-§87A
                                        :label "§87A rebate"
                                        :amount-from :compute-fn
                                        :fn :in-pit-§87A-new})}

   ;; Surcharge — base-aware surtax (banded with marginal relief)
   {:provision/code            "IN-PIT-surcharge-new"
    :provision/jurisdiction    :in
    :provision/concept         [:tax-concept/code :surtax]
    :provision/regime          [:regime/code :in-pit-new]
    :provision/title           "§115BAC surcharge — banded (cap 25%, no 37% band)"
    :provision/effective-from  #inst "2023-04-01"
    :provision/priority        300
    :provision/consequence     (pr-str {:op :surtax
                                        :code :in-surcharge-new
                                        :label "Surcharge on income tax (new regime)"
                                        :amount-from :compute-fn
                                        :fn :in-pit-surcharge-new})}

   ;; HEC cess — regime-free (priority 500 to fire after surcharge)
   {:provision/code            "IN-PIT-cess"
    :provision/jurisdiction    :in
    :provision/concept         [:tax-concept/code :surtax]
    :provision/title           "Health & Education Cess — 4% of (tax + surcharge)"
    :provision/effective-from  #inst "2018-04-01"
    :provision/priority        500
    :provision/consequence     (pr-str {:op :surtax
                                        :code :in-hec-cess
                                        :label "Health & Education Cess (4%)"
                                        :amount-from :compute-fn
                                        :fn :in-cess})}])
```

Five compute-fns then carry the imperative kernels (§87A's `cond` over
in-threshold / marginal-relief / cliff; surcharge's
`active-band + marginal-relief-clamp`; cess's `rate × running`) — the same
shape as the current `rebate-87a` / `surcharge-with-marginal-relief` /
`cess-adjustment` fns, just reading their rates from `:parameter` lookups
rather than from closed-over Clojure data. The kernel logic is **byte-for-
byte the same fn** — only the rate sourcing changes.

### 4.4 Stress check — does the substrate handle §87A's regime-dependent
slab + the seven-band surcharge?

- **§87A regime-dependent slab.** Yes. Two `:provision`s — one per
  regime — both bound to the `:non-refundable-credit` concept, gated by
  `:provision/regime`. The compute-fn reads the regime's threshold +
  cap parameters at `:as-of`. Marginal relief is in the fn body (cond
  on `(<= base threshold)` / `(and marginal-relief? (> bracket-tax
  (- base threshold)))`). The current `rebate-87a` fn at
  `period_tax_provider.clj:114-132` ports verbatim — only the closed-over
  `{:threshold :cap :marginal-relief?}` becomes three `parameter-value-at`
  lookups + a per-regime statically-known marginal-relief flag (encoded as
  a 4th parameter `IN.PIT.new.§87A.marginal-relief?` of unit `:rate` with
  value 1M/0M, or — cleaner — flagged inside the compute-fn since the
  marginal-relief discipline is regime-statutory rather than yearly-tunable).
- **Seven-band surcharge with marginal relief.** Yes. The bands are
  `:parameter-bracket` rows under `IN.PIT.new.surcharge-bands`
  (3 rows: 10%/15%/25%) and `IN.PIT.old.surcharge-bands` (4 rows:
  10%/15%/25%/37%). The compute-fn does
  `parameter-brackets-at db code as-of → active-surcharge-band → marginal
  relief clamp` — same shape as the current `active-surcharge-band` +
  `surcharge-with-marginal-relief` at `period_tax_provider.clj:157-187`.
  The substrate's `:parameter-bracket` is genuinely just what these
  brackets need: ordered `{:rate :upper}` rows under a parent parameter.
- **4% cess.** Trivially. One compute-fn, one parameter, regime-free
  `:provision` — fires for both regimes via the absence of
  `:provision/regime`.
- **Standard deduction.** A `:base-deduct` provision per regime, sourcing
  the deduction amount from a parameter via `:amount-from :parameter`.
  Already in the substrate's adjustment vocabulary (note 105 + ADR-101's
  unified `:base-add` / `:base-deduct` / `:credit` / `:surtax`).
- **Schedule selection** (the progressive ladder itself) — handled by the
  provider, reading `IN.PIT.{regime}.brackets` parameter-brackets at
  `:as-of`. DE CIT does the same thing (DE.KSt.rate is a parameter; the
  `:flat` schedule is built by the provider).

**Verdict on §4.2–4.4: the IN PIT provider could migrate to ADR-101 data
shape WITHOUT a single substrate change**, including its hardest sub-cases
(§87A's regime-conditional slab + marginal relief, the 4-band/7-band
surcharge bands with marginal relief, the 4% cess, the standard deduction).
That's the substrate validation note 105's design hoped for: the provoking
case still fits.

The migration is **not recommended now** — record-shaped providers and
statute-data providers coexist by ADR-101 design ("migrate opportunistically
when l10n modules are touched; not a forced sweep"). But the *fact that
it could migrate without substrate friction* is the strongest possible
substrate-fit signal for IN.

## §5. Abstraction stress — P0 / P1 / P2

### P0 — would mis-compute a typical Pvt. Ltd. today, fix before shipping IN CIT

- **P0-1: MAT composition needs a documented "two-component + greater-of"
  pattern.** Not a substrate bug per §3.3, but the ADR-104 reference example
  (DE CIT) does NOT exercise this shape — both DE components are *additive*
  (KSt + GewSt summed for total burden), not *competitive* (max of). When
  the IN CIT provider is built it will be the first encoded `greater-of`
  case; the convention (provider-assembled, `:composed-of` declares the
  lineage, consumer-or-helper picks the max) needs to be documented in
  ADR-101 §D or in a small new ADR. **Recommendation: an ADR-101 addendum
  3, "minimum-tax composition pattern,"** documenting the IN MAT shape
  before fanning out to ADR-105/106/107 (FR/JP/CA CIT — JP local CIT is
  technically a surtax not a minimum, but FR's CVAE has minimum-tax flavour
  worth confirming).

### P1 — matters for some real consumers; fix in the IN CIT ADR or first review

- **P1-1: Election irrevocability is not expressible in `:regime` alone.**
  §115BAA / §115BAB are *one-way doors* — once a company elects, it cannot
  revert. `:regime/extends` does not model this; the election event itself
  rides ADR-034 status-machine per ADR-101 §D5, but the *constraint that
  no reverse transition is permitted* needs to be a status-machine guard,
  not a regime attribute. Probably out-of-scope for the substrate (the
  guard belongs in the per-jurisdiction provider's election helper) but
  worth naming in the IN CIT ADR.
- **P1-2: Turnover-band gating is not "fact in the period."** Whether the
  small (25%) or large (30%) standard rate applies depends on **previous
  year's** turnover (PY 2023-24 for AY 2026-27). That's a fact from a
  *different period* than the one being assessed. The substrate's
  `:tax-context-fact` reads `ctx` — no problem if the consumer passes the
  PY turnover explicitly. But the convention needs documenting: which
  period's facts feed which provision? The cleanest is a `:tax-unit`
  field `:turnover-band :small | :large` that the consumer pre-computes
  from PY data; the provider sees it as a `[:eq :tax-unit :turnover-band
  :small]` condition. (Mirror the DE Hebesatz pattern — also a
  `:tax-unit` fact, also not in the books, also pre-resolved by the
  consumer.)
- **P1-3: §115JB book-profit add-back catalogue.** §115JB Explanation 1
  enumerates ~12 add-back / exclude items the book profit must be
  adjusted by before MAT applies (deferred tax, statutory transfers to
  reserves, the deferred-tax credit, etc.). These are structurally
  *exactly* like the DE §10 KStG non-deductibles — a list of
  `:base-add` / `:base-deduct` provisions scoped to the MAT component.
  Trivially fits the substrate; the work is enumerating and citing
  the twelve items.
- **P1-4: MAT credit carry-forward (§115JAA).** 15-year carry-out gated
  on note 105 frontier 2 (carry primitive). Re-flag here — the IN CIT
  ADR must reference the deferral so the substrate-side is honest about
  what's supported and what's pending.
- **P1-5: HEC cess is shared between IN PIT and IN CIT.** When IN CIT
  ships, point its cess provision at the same `IN.cess.rate` parameter
  the migrated IN PIT would use. Avoids parameter duplication and
  guarantees a Finance Act cess-rate change is one-row.

### P2 — defer; document as known gaps

- **P2-1: §115BAB non-business income.** §115BAB taxes manufacturing
  business income at 15% but non-business income (e.g. capital gains) at
  the regular 22%. Two-rate split inside one regime. Out of scope for
  initial IN CIT; document as a known limitation, model when a 115BAB-electing
  consumer hits it.
- **P2-2: §115BBE windfall-gain rate of 60%.** Unexplained cash credits /
  §68 / 69-69D taxed at 60% + 25% surcharge — separate punitive regime
  for tax-evasion findings. Off-path for normal CIT computation; defer.
- **P2-3: Section 234 interest** (delayed advance tax / late filing).
  Separate Schedule of the ITR. Mechanically a `:base-aware` `:surtax`
  on the delay window — fits the substrate but is its own provision
  surface. Defer.
- **P2-4: Special CGT rates** (§111A LTCG @ 10%, §112A LTCG @ 10% above
  ₹1L, §111A STCG @ 15%) — these are part of the PIT/CIT *return*'s
  income computation but each head has its own rate. Modelled as separate
  `:capital-gains-tax` components (per note 102 §7 — the hybrid). Will
  arrive in the IN CGT provider phase, not IN CIT.
- **P2-5: DDT — abolished.** Document the abolition in the ADR; no encoding
  work.
- **P2-6: Foreign companies / branch profits.** §115A and the 35% rate for
  non-resident corps' India PE — a separate regime entirely. Out of scope
  for the initial domestic Pvt. Ltd. consumer.

## §6. Minimal substrate adds

**Preferred: zero.** Note 105 already shipped the largest IN ask (the
base-aware adjustment-layer with marginal-relief on signed credits and
surtaxes). ADR-101 then made that algebra *data*. The IN CIT clean cases
(rate, surcharge, cess) all fit. The §87A + surcharge + cess that drove
the original note-105 design have already been validated against IN
practice in `period_tax_provider.clj` — they were the spec; ADR-101 just
hosts the same spec as data.

The single substrate-adjacent recommendation is **documentation, not code**:

- **An ADR-101 addendum on minimum-tax composition** (P0-1) — explicitly
  document the "provider returns two components, `:composed-of` declares
  lineage, a helper picks `greater-of`" pattern for `:minimum-tax` kinds
  before IN CIT ships. Avoids each Phase-3 author inventing a different
  convention.

No new schema attrs. No new `:provision/consequence` `:op` values. No new
predicate keywords. No new combinator in `kontor.tax-schedule`. **The
substrate as it stands at ADR-101 + note 105 handles IN cleanly.** That
this is the case for the country whose adjustment-layer drove the design
is the substrate-fit validation note 105 promised.

## §7. Sources

**Statute / authority (incometax.gov.in canonical)**

- [Domestic Company for AY 2026-27 — incometax.gov.in](https://www.incometax.gov.in/iec/foportal/help/company/return-applicable)
- [Tax Rates — Domestic — incometaxindia.gov.in PDF index](https://incometaxindia.gov.in/Documents/Left%20Menu/TAX%20RATES-domestic.htm)
- [Set Off and Carry Forward of Loss — incometaxindia.gov.in (Tutorials, Finance Act 2025)](https://incometaxindia.gov.in/Tutorials/21-%20MCQ%20set%20off%20and%20carry%20frwrd.pdf)

**Commercial commentary (cross-check)**

- [pwc India Tax Summaries — Taxes on corporate income](https://taxsummaries.pwc.com/india/corporate/taxes-on-corporate-income)
- [ClearTax — Section 115BAA new tax rate for domestic companies](https://cleartax.in/s/section-115-baa-tax-rate-domestic-companies)
- [ClearTax — Marginal relief surcharge for AY 2026-27](https://cleartax.in/s/marginal-relief-surcharge)
- [ClearTax — Set off and carry forward of losses](https://cleartax.in/s/set-off-carry-forward-losses)
- [ClearTax — Income tax rebate under Section 87A](https://cleartax.in/s/income-tax-rebate-us-87a)
- [ClearTax — MAT eligibility and calculation](https://cleartax.in/s/tax-planning-under-mat)
- [Tax2win — §87A rebate FY 2025-26 (tax-free up to ₹12L, ₹60k cap, marginal relief)](https://tax2win.in/guide/section-87a)
- [Tax2win — MAT under §115JB](https://tax2win.in/guide/minimum-alternative-tax)
- [Tax2win — Set off and carry forward of losses](https://tax2win.in/guide/set-off-and-carry-forward-of-losses)
- [Motilal Oswal — Corporate tax 2025-26 rates and regimes](https://www.motilaloswal.com/personal-finance/tax/corporate-tax-in-india-2025-26-rates-regimes-complete-guide)
- [Motilal Oswal — §115BAB 15% rate for new manufacturers](https://www.motilaloswal.com/personal-finance/tax/section-115bab-15-corporate-tax-guide-for-new-manufacturers)
- [IndiaFilings — Domestic company tax rates FY 2025-26 + 2026-27](https://www.indiafilings.com/income-tax/domestic-company-tax-rate)
- [PolicyBazaar — MAT changes Union Budget 2026](https://www.policybazaar.com/income-tax/minimum-alternate-tax/)
- [BankBazaar — Surcharge on income tax FY 2025-26](https://www.bankbazaar.com/tax/surcharge-on-income-tax.html)
- [Bajaj Finserv — §115BAA conditions and Form 10-IC](https://www.bajajfinserv.in/investments/section-115-baa-of-income-tax-act)
- [Bajaj Finserv — §115BAB applicability](https://www.bajajfinserv.in/investments/section-115bab-of-income-tax-act)
- [TaxBuddy — §115JB MAT provisions](https://www.taxbuddy.com/blog/section-115jb-of-the-income-tax-act)
- [PRS India — Taxation Laws (Amendment) Ordinance 2019 — 115BAA/115BAB origin](https://prsindia.org/billtrack/prs-products/prs-legislative-brief-3358)
- [PIB India — ₹400 cr turnover threshold (Budget 2019)](https://www.pib.gov.in/Pressreleaseshare.aspx?PRID=1577365)

**DDT abolition (2020 classical-system return)**

- [Cyril Amarchand — DDT abolition new paradigm](https://corporate.cyrilamarchandblogs.com/2020/04/abolition-of-dividend-distribution-tax-a-new-paradigm-for-equity-investments/)
- [KPMG — Taxation of dividends post-DDT (May 2020)](https://assets.kpmg.com/content/dam/kpmgsites/in/pdf/2020/10/taxation-of-dividend.pdf)
- [BDO — India dividend income — two regimes](https://www.bdo.global/en-gb/microsites/tax-newsletters/corporate-tax-news/issue-57-january-2021/india-dividend-income-taxation-of-two-regimes)
- [Wikipedia — Dividend distribution tax (India)](https://en.wikipedia.org/wiki/Dividend_distribution_tax)

**kontor source under review**

- `doc/decisions.md` — ADR-099, ADR-101, ADR-104
- `doc/research/105-the-algebra-of-a-tax.md` — note 105 adjustment-layer
- `doc/research/119-adr-101-draft.md` — ADR-101 design choices
- `doc/research/120-de-cit-baseline-review.md` — DE baseline review template
  for this note
- `src/kontor/statute.clj` — ADR-101 evaluator
- `src/kontor/tax_schedule.clj` — schedule + adjustment-layer
- `src/kontor/period_tax_provider.clj` — `PeriodTaxProvider` protocol +
  `TaxReturnFacts`
- `modules/l10n-de/src/kontor/l10n_de/cit_statute.clj` — gold-standard
  parameter + provision data
- `modules/l10n-de/src/kontor/l10n_de/cit_provider.clj` — gold-standard
  thin provider
- `modules/l10n-in/src/kontor/l10n_in/period_tax_provider.clj` — the
  record-shaped IN PIT, migration source for §4

---

End of note 122.
