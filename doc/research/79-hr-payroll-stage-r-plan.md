---
date: 2026-05-18
title: 79 — HR / payroll Stage R implementation plan (5 design calls answered + per-country sequencing)
status: draft
audience: maintainer (implementation-ready) + contributors picking up Stage R after the design dust settles
---

# 79 — HR / payroll Stage R implementation plan

Stage R **implementation plan**, built on the research-before bundle
[[72-hr-payroll-reference-study]] (OFBiz file:line reference),
[[73-hr-payroll-market-pain]] (12-theme pain catalog + the 5 pains
kontor uniquely fits), and [[74-hr-payroll-internal-gap-analysis]]
(60% / 30% / 10% substrate audit + the 5 design calls).

Where notes 72–74 ask "what do we know and what's ambiguous," this
note answers "okay, ship it: here is what is decided, what the schema
looks like, which country goes first, and how the simmis-simulation
pitch lands on top." The maintainer or a contributor should be able
to open this note and **start coding without re-deriving the design
trade-offs**.

License posture (unchanged from ADR-001 + note 72): OFBiz
`humanres` patterns AND structure are Apache-2.0 lift-safe; Tryton
`company` is GPL (read for design, don't lift); Odoo `hr` is LGPL
(read for design, don't lift). Per-country payroll providers
(DATEV LODAS spec, ADP file format, HMRC RTI XSD, ATO STP Phase 2
XSD) are **specifications**, not code — implementations are kontor's
own work and EPL-compatible.

---

## §1 — TL;DR (the maintainer-actionable verdict)

- **All five design calls go with note 74's recommendations,** but
  with sharper reasoning where reuse is possible: companion-tier
  (`kontor-hr`), Workday-style multi-employment, hybrid `:person`
  root + `:partner/kind :employee` linker, kernel-level
  `PayrollProvider` mirroring the **three-protocol ADR-071 split**
  (not the old single-protocol ADR-005 shape, which was superseded),
  and a new `:audit-doc/category` axis orthogonal to
  `:audit-doc/privilege`.
- **The schema sketch is ~14 attrs across 6 entities** plus 5
  status-machine seed rows; everything else (legal-hold, retention,
  DSAR, schedule, audit-doc, parallel ledgers, status-machine,
  process, multi-entity, FX) is already shipped substrate.
- **DE-DATEV-LODAS is C2.** The maintainer's home jurisdiction, the
  Personio-DATEV adapter shape is the canonical reference (note 09
  §3 + note 72 §1.5), and the architecture-review trans-national
  pitch (note 69 §4 Gap 3) is grounded in DE-vs-rest book-keeping
  conventions. Skipping DE first would make the rest of the
  per-country rollout speculative.
- **C3's first non-DE country is US-ADP-export.** Largest market by
  revenue, well-documented file format (no real-time API needed
  for v1), and the multi-state pain (note 73 Theme B) lands the
  `:posting/entity` + jurisdiction-tagging pitch hard.
- **UK is deferred to C4 and is gated on note 78 + the iXBRL stack**
  — UK 2026-04-01 mandatory iXBRL filing changes the entire UK
  reporting calculus, so doing UK payroll without iXBRL emission is
  half-a-feature.
- **Simmis-simulation needs only the C1 substrate** (schema +
  protocol + `kontor.hr` namespace). Headcount projection, recurring
  payroll expense, multi-country payroll comparison — all of these
  fall out of `:employment` × `:schedule` × `PayrollProvider`
  composition. No simulation-specific schema additions required.
- **Estimated effort: C1 ≈ 5 days, C2 ≈ 8 days, C3 ≈ 6 days, C4 ≈
  10 days.** Total Stage R ≈ 5–6 person-weeks once the design dust
  settles. Per-country sequencing past C3 is gated decision-by-
  decision; no commitment beyond C3.

---

## §2 — The 5 design calls, answered

Each call below is *decided*. The arguments are presented for the
record (so a contributor disagreeing with the choice can find the
hinges and reopen), but the recommendation is the project's
position.

### 2.1 — Call 1: substrate-vs-companion split for `:person`

**Decided: Companion (`kontor-hr`). All of `:person/*`,
`:employment/*`, `:department/*`, `:absence/*`, `:pay-period/*`,
`:benefit-*` land in `kontor-hr`. No kernel-side schema addition.**

*For substrate-tier:* `:posting/partner` already references
`:partner/*` in the kernel (`schema.clj:480-598`); six potential
downstream companions reference employee identity (hr, expense,
procurement, authz, project, fleet) — meets ADR-034's "six
independent inventions" threshold; ADR-039 already promoted
`:partner/*` despite being domain-shaped.

*For companion-tier (winning):* `:partner` was promoted because
every posting references one — hot path. `:person` is one
indirection off the hot path: postings → `:partner` → `:person`
(call 3). Half the kernel's consumers (single-founder accounting,
SaaS using external HR, embedded fintech) will never use
`:person/*` — kernel inclusion costs them schema-doc noise for
zero gain. HR is symmetric with `kontor-sales` (ADR-035),
`kontor-procurement` (ADR-042), `kontor-lease` (ADR-062) — all
companion-tier; promoting HR alone breaks the pattern.

**Implication.** `kontor-hr` ships its own `schema.clj`,
`core.clj`, `person.clj`, `employment.clj`, `pay_period.clj`,
`payroll.clj` + `bridge.clj` wiring `PayrollProvider` into
`kontor.posting/build-transaction`. Same shape as `kontor-sales`.

### 2.2 — Call 2: multi-job per person (Workday) vs single (ADP/Tryton)

**Decided: Multi-job (Workday). One `:person` may have N concurrent
`:employment` rows, one per employing entity. Single-employment is
a degenerate case of the multi-job model.**

*For single-employment (Tryton/ADP):* Simpler. SMB market
expectation. Fits on one screen.

*For multi-employment (winning):* Three reasons.

1. **Trans-national pitch needs it (note 73 Theme C P1).** An
   executive employed by Acme-DE-GmbH and seconded to Acme-US-LLC
   has two distinct employments — DE-payroll-provider for DE
   social-insurance, US-payroll-provider for FICA + 401(k).
   Reducing this to "one employment + secondary-entity pointer"
   collapses on "wage as of March 31?". ADR-031 `:posting/entity`
   already makes multi-entity substrate present; multi-employment
   is the personnel-side reflection.
2. **OFBiz precedent (`humanres-entitymodel.xml:330-370`).**
   Composite PK on (employer party, employee party, roles, fromDate)
   — Apache-2.0, lift-safe.
3. **Substrate cost is zero.** A 1:N relation. Retrofitting
   multi-employment later is **high blast radius** (note 74 §5
   confirms): synthetic splits, ambiguous wage joins, downstream
   report re-disambiguation.

**Implication.** `:employment/person` is `:db.type/ref
:db.cardinality/one`. Reverse `:person/employments` is implicit in
datahike. Re-hire = new `:employment` with later `:start-date`.
Concurrent rows with overlapping windows = multi-employment.

### 2.3 — Call 3: employee-as-`:partner` vs separate `:person` root

**Decided: Hybrid. `:person` is a new root entity in `kontor-hr`
carrying human-only attributes. `:partner/kind :employee` is added
to the open-set kernel enum, with a new `:partner/person` ref
linking partner→person. `:posting/partner` always points to
`:partner` (unchanged); `:employment/person` points to `:person`.**

*For pure `:partner/kind :employee` reuse:* Smallest entity count.
`:posting/partner` resolves natively (expense reimbursement,
salary advance, employee loans).

*For pure separate `:person` root:* `:partner/tax-id` / `:partner/
credit-limit` / `:partner/kyc-status` don't fit humans; `:person/
birth-date` / `:passport` / `:national-id` don't fit companies.

*For hybrid (winning):* OFBiz solves the same tension with `Party`
(universal root) + `Person` (human FK extension). Two axes:

1. **Privacy.** `:person/*` carries PII; DSAR walks (ADR-052) hit
   it heavily. `:partner` carries business identifiers; DSAR walks
   it lightly. The DSAR walker treats the two axes differently —
   `:partner` exports without redaction, `:person` exports apply
   per-attr retention masking.
2. **Lifecycle.** A `:partner` may pre-date employment (was a
   vendor first) and post-date it (continues as customer). A
   `:person` is born once, dies once, GDPR-erasable per ADR-050.
   The two lifecycles co-exist via the indirection.

**Implication.** `kontor-hr/install!` adds `:partner/kind :employee`
to the open-set enum (ADR-039 already permits this); `kontor-hr`
ships `:partner/person` (`:db.type/ref`); kernel never sees
`:person`. `kontor.dsar/collect` grows a `:person` collector
registered by `kontor-hr` per the ADR-052 pattern.

**Documented downside.** Two-step lookup (partner → person →
employment → wage) costs a relation hop. Negligible in datalog;
helper `kontor.hr/employee-current-wage` hides it.

### 2.4 — Call 4: `PayrollProvider` protocol shape

**Decided: Three-protocol-plus-data-shape split mirroring ADR-071's
`TaxRateProvider` / `TaxFacts` / `TaxPostingBuilder`, plus a fourth
emit-side protocol for jurisdictional event-bus reporting.**

1. **`PayrollComputeProvider`** — gross-to-net engine. Returns a
   sequence of **`PayrollFacts`**.
2. **`PayrollFacts`** — pure data inter-protocol shape: per-
   employee + per-component, carrying gross, net, employer-side
   accruals, per-jurisdiction slots, and a `:jurisdiction-specific-
   codes` opaque slot (mirrors ADR-071 P2-71-2).
3. **`PayrollPostingBuilder`** — materializes GL postings from
   `PayrollFacts`. Per-country chart-of-accounts (DE SKR04, US
   wage-types).
4. **`PayrollEmitProvider`** — jurisdictional event-bus emissions
   (BR eSocial, UK FPS, DATEV LODAS Lohnimport, ATO STP Phase 2).
   Some countries need it (BR, UK, AU, DE); others don't (US has
   no clearance regime — ADR-071 P2-71-3 parallel).

*For mirroring the old single-method `TaxProvider`:* Simpler.

*For three-protocol split (winning):* ADR-071 / research note 70
established the principle for tax: rate-providers don't need CoA,
posting-builders don't need rate tables. Payroll has identical
structure — gross-to-net math (DE EStG brackets, US FICA, UK PAYE)
and CoA mapping (SKR04 4120 vs US 5200) are orthogonal, hit by
different consumers. A DATEV LODAS compute-provider doesn't know
SKR04 account numbers; a `kontor-l10n-de` posting-builder doesn't
know LODAS rate tables. The split is structural, not stylistic.

The fourth (emit) protocol is forced by note 73 Theme C P2 (BR
eSocial). Substrate's contract is "round-trip the event payload";
transmission is consumer-held credential. Mirrors
`kontor.einvoice-provider` (ADR-017) + clearance attestation
(ADR-018, ADR-024).

**Component-kind enum** (open-set; consumer extends):
`:base-wage | :bonus | :overtime | :imputed-income | :employer-si
| :employee-si | :employer-pension | :employee-pension |
:withholding-tax | :garnishment | :voluntary-deduction |
:equity-vest`. Substrate does not enforce specific kinds —
jurisdiction-specific extensions land in adapters (same posture as
`:component/kind` in ADR-071).

**Why the shape matters.** Without it, every
`kontor-payroll-<cc>-<vendor>` re-invents the boundary, and the
"book vs tax basis" parallel-ledger pitch (ADR-021) cannot be
substrate-level (each adapter would have to know to dual-post).
With it, the per-component split is the invariant that parallel-
ledger posting-builders consume.

**Sub-question: `:pay-period` vs `:period/kind :payroll`?** Keep
separate (note 74 §3.4). `:pay-period` has its own lifecycle
(`:open → :computed → :approved → :posted → :paid`); references
`:pay-period/fiscal-period` → `:period`; per-entity frequency
(DE monthly + US biweekly within one group). Overloading `:period`
costs more in long-term query complexity.

### 2.5 — Call 5: PII classification axis

**Decided: New `:audit-doc/category` keyword, orthogonal to
`:audit-doc/privilege`. Two axes; both default to nil/`:none`.**

*For extending the privilege enum* (`:pii-payroll`, `:pii-medical`,
`:pii-immigration`): Smallest change. Already open-set per ADR-051.

*For new orthogonal category axis (winning):* `:audit-doc/privilege`
is **legal-doctrine** classification (attorney-client, work-product,
trade-secret); `:audit-doc/category` is **subject-matter domain**
(payroll, HR-personnel, HR-medical, HR-immigration, tax-filing,
financial). The auth layer needs both axes:
- "HR role can access category `:payroll` regardless of privilege"
- "Auditor role can access privilege `:attorney-client` regardless
  of category"
- "Tax-prep contractor can access category `:tax-filing` UNLESS
  privilege `:attorney-client`"

Conflating the two destroys the auth grid. A single-axis enum
forces a W-4 to choose between `:pii-payroll` (loses legal-
privilege classifier) or `:work-product` (loses HR-domain
classifier). GDPR Article 30 records-of-processing organize by
"category of personal data" — the regulatory schema *is* two-axis.

**Composition with DSAR + retention.** `:retention-policy` (ADR-050)
keys on (jurisdiction, entity-type, **category**); medical PII vs.
payroll PII have different retention floors. The category axis is
the missing dimension.

**Implication.** Two schema lines added in kernel: `:audit-doc/
category` (kw, cardinality/one) + `:retention-policy/category`
(kw, cardinality/one). ADR-051 addendum documents it. No
migration; existing docs resolve to `:none`. **This is the only
kernel-side schema change required by Stage R** — everything else
lives in `kontor-hr`.

---

## §3 — Schema sketch (concrete, transactable)

Minimum Stage R schema. Hosts: **K** = kernel
(`src/kontor/schema.clj`), **H** = `kontor-hr` companion
(`modules/kontor-hr/src/kontor/hr/schema.clj`). Each attr's Call#
indicates which design call it answers.

```clojure
;; --- KERNEL ADDITIONS (Call 5) ---
[K] :audit-doc/category           kw    one         ; subject-matter; nil=:none
[K] :retention-policy/category    kw    one         ; per-category retention rule

;; --- COMPANION (kontor-hr) ---
;; :person — human identity root (Call 3)
[H] :person/external-id           string one unique/identity
[H] :person/given-name            string one
[H] :person/family-name           string one
[H] :person/birth-date            inst   one        ; PII (category :hr-personnel)
[H] :person/citizenship           string many       ; ISO-3166 alpha-2
[H] :person/national-id           ref    many       ; → :audit-doc; SSN/AHV
[H] :person/state                 kw     one        ; ADR-034 facet

;; :partner/person — kernel↔companion linker (Call 3)
[H] :partner/person               ref    one        ; set when :partner/kind=:employee

;; :employment — Workday-style relationship (Calls 1+2)
[H] :employment/person            ref    one        ; → :person; many per person
[H] :employment/entity            ref    one        ; → :entity (ADR-031)
[H] :employment/start-date        inst   one
[H] :employment/end-date          inst   one        ; nil=open
[H] :employment/job-title         string one
[H] :employment/department        ref    one        ; → :department
[H] :employment/manager           ref    one        ; → :employment (not :person)
[H] :employment/wage              bigdec one        ; BITEMPORAL via :db.valid/from
                                                    ; replaces OFBiz PayHistory
[H] :employment/wage-commodity    ref    one
[H] :employment/wage-period       kw     one        ; :hourly|:monthly|:annual|...
[H] :employment/exempt-flag       bool   one        ; FLSA/Beamter/cadre
[H] :employment/fulltime-flag     bool   one
[H] :employment/contract-doc      ref    one        ; → :audit-doc (ADR-038)
[H] :employment/state             kw     one        ; ADR-034 facet
[H] :employment/termination-reason kw    one        ; open-set per jurisdiction

;; :department — recursive per-entity org tree
[H] :department/code              string one
[H] :department/name              string one
[H] :department/entity            ref    one
[H] :department/parent            ref    one        ; → :department; nil=root
[H] :department/manager           ref    one        ; → :employment

;; :pay-period — payroll temporal axis (Call 4 sub-question)
[H] :pay-period/entity            ref    one
[H] :pay-period/start-date        inst   one
[H] :pay-period/end-date          inst   one
[H] :pay-period/frequency         kw     one        ; :weekly|:biweekly|:monthly|...
[H] :pay-period/fiscal-period     ref    one        ; → :period (ADR-014)
[H] :pay-period/state             kw     one        ; ADR-034 facet

;; :payroll-run — one (pay-period × entity) execution
[H] :payroll-run/pay-period       ref    one
[H] :payroll-run/provider-id      kw     one        ; e.g. :datev-lodas
[H] :payroll-run/state            kw     one        ; ADR-034 facet
[H] :payroll-run/control-total-gross bigdec one
[H] :payroll-run/control-total-net   bigdec one
[H] :payroll-run/payroll-facts    ref    many       ; → :payroll-facts (frozen)

;; Status-machine seeds (kontor.status-machine; Call 1)
;;  :employment/state  — :applicant → :offered → :hired → :active →
;;                        :on-leave → :terminated → :rehired
;;  :pay-period/state  — :open → :computed → :approved → :posted → :paid
;;  :payroll-run/state — :proposed → :computed → :approved → :posted →
;;                        :emitted → :reconciled
;;  :person/state      — :active → :deceased | :purged (GDPR Art. 17)
;;  :absence/state     — (deferred to C4) :requested → :approved|:rejected
```

**Deferred to C4+:** `:work-schedule`, `:absence-type`,
`:absence-reason`, `:absence`, `:absence-allocation`,
`:benefit-type`, `:benefit-enrollment`, `:deduction-type`,
`:payroll-preference`. Each follows OFBiz patterns from note 72 §5.

**C1 totals:** ~14 attrs (2 kernel + 12 companion-shaped roots);
4 status-machine seeds; 1 kernel-side enum extension. ~1 day of
schema work; everything else is consumer-side helpers.

---

## §4 — `PayrollProvider` protocol (the full sketch)

The C1 protocol surface, ready for the impl phase. Lives in
`src/kontor/payroll_provider.clj` (kernel — Call 4 decision puts
the protocols in the kernel even though the entities live in
`kontor-hr`, mirroring how `kontor.tax-rate-provider` and
`kontor.fx-rate-provider` sit in the kernel alongside companion-
shaped entities).

```clojure
(ns kontor.payroll-provider
  "Stage R payroll provider protocols. Mirrors ADR-071's three-
   protocol split (TaxRateProvider / TaxFacts / TaxPostingBuilder)
   plus a fourth EmitProvider for jurisdictions with mandatory
   event-bus reporting (BR eSocial, UK FPS, AU STP, DE LODAS).

   Compose via kontor.hr.payroll/run-payroll!:
     1. PayrollComputeProvider.compute-payroll → PayrollFacts*
     2. PayrollPostingBuilder.build-postings   → :posting*
     3. PayrollEmitProvider.emit-payroll-events → :payroll-event*
     4. kontor.process.run-process composes (1)+(2)+(3) atomically.")

(defprotocol PayrollComputeProvider
  (provider-id  [this]
    "Stable kw — :datev-lodas, :adp-wfn, :gusto-api, :hmrc-rti,
     :silae, :zenhr, :pluxee, :static-table (default), …")
  (compute-payroll [this {:as ctx
                          :keys [pay-period-eid entity-eid
                                  employment-eids variable-inputs
                                  as-of]}]
    "PURE function: rate-lookup + math, no transact. Returns a
     vector of :payroll-facts maps (one per employment). Throws
     ex-info on missing-data; does NOT silently zero.

     :variable-inputs is a map keyed by employment-eid carrying
     pay-period-specific overrides (overtime hours, bonus, RSU
     vest events, retro adjustments). Consumer assembles from
     :analytic-line (timesheets), :equity-event, etc."))

(defprotocol PayrollPostingBuilder
  (build-postings [this payroll-facts
                   {:keys [accounts ledger fx-provider]}]
    "Per-country posting expansion. Returns a vector of posting
     maps shaped per ADR-068 *-tx-data builder convention.
     :accounts is a per-component-kind → :account map (consumer
     supplies CoA mapping). :ledger is the target :ledger eid
     (ADR-021 — multi-ledger book/tax basis split).
     :fx-provider per ADR-072 if commodity translation needed."))

(defprotocol PayrollEmitProvider
  (emit-payroll-events [this payroll-facts
                        {:keys [pay-period-eid entity-eid]}]
    "Returns a vector of :payroll-event entities (one per required
     emission, e.g. one :br/s-1200 per employment in Brazil, one
     :uk/fps per pay-period in UK). Entities are transactable;
     transmission is consumer's job — consumer holds the cert
     and the endpoint URL (mirrors :sent-by-consumer? per ADR-017).

     Default impl (LocalfileEmitProvider) writes events as
     :audit-doc rows with :audit-doc/category :tax-filing for
     the consumer to manually upload."))
```

The `kontor.hr.payroll/run-payroll!` orchestrator (in `kontor-hr`)
composes the three providers via `kontor.process.run-process`
(ADR-067):

```clojure
(defn run-payroll!
  [conn {:keys [pay-period-eid entity-eid compute-provider
                 posting-builder emit-provider accounts ledger
                 fx-provider]}]
  (process/run-process conn
    [(payroll-facts-tx-data compute-provider ctx)
     (payroll-postings-tx-data posting-builder facts opts)
     (payroll-events-tx-data emit-provider facts ctx)]
    {:gates [legal-hold/check-not-held
             period/check-not-locked
             status-machine/check-pay-period-approvable]}))
```

The gate stack matches note 69 §3.2's end-to-end trace
(legal-hold → period → state-machine → sum-to-zero) — Stage R
payroll inherits the existing process-orchestration discipline.

---

## §5 — Per-country sequencing

The market-pain catalog (note 73) plus the maintainer's home
jurisdiction (DE) plus the trans-national pitch (note 69 §4 Gap 3)
fix the first country as **DE-DATEV-LODAS**. The next 9 countries
sequence below; only DE through C3 (US-ADP) is committed work — C4
onward is gated on real consumer demand.

### 5.1 — C2: DE-DATEV-LODAS (first; ~8 days)

**Engine convention.** DATEV LODAS / Lohn und Gehalt is dominant
DE Mittelstand. Round-trip via two ASCII files: "Lohnimport-Datei"
(in: master-data + variable inputs) and "Lohnauswertungsdatei"
(out: per-employee gross/deductions/net). Personio + DATEV is the
canonical adapter pattern (note 09 §3).

**Minimum viable impl** (`modules/kontor-payroll-de-datev/`):
- `compute_provider.clj` — `DatevLodasComputeProvider` parses the
  Lohnauswertungsdatei into `:payroll-facts`. **Critical**: we
  DON'T re-implement DE payroll math (EStG + SGB + ELStAM +
  Kurzarbeit = hundreds of pages); we consume DATEV's result.
- `posting_builder.clj` — Maps DATEV wage-types to SKR04 accounts
  (4120 Löhne, 4130 Soziale Aufwendungen, …) via existing
  `kontor-l10n-de` catalog.
- `emit_provider.clj` — Writes Lohnimport-Datei for onboarding,
  wage-change, termination events (the inbound direction).

**Effort breakdown.** Parser 1d, SKR04 mapping 2d, emit 2d,
end-to-end test (DE GmbH multi-month per ADR-037 cross-stage)
1d, ADR + docs 1d, review-after 1d.

**Gated on real consumer story?** Yes — the maintainer's own
DE-GmbH is the consumer; the DE Mahnverfahren showcase
(`doc/showcases/01_de_b2b_factur_x.clj`) supplies the data shape.

### 5.2 — C3: US-ADP-export (~6 days)

**Engine convention.** ADP Workforce Now (mid-market) / ADP RUN
(SMB). Both emit a "General Ledger Interface" (GLI) CSV — per-
employee debits/credits ready for GL posting. Alternatives: Gusto
(API), Rippling (API), Paychex, OnPay.

**Minimum viable impl** (`modules/kontor-payroll-us-adp/`):
- `compute_provider.clj` — `AdpGliProvider` parses GLI CSV into
  `:payroll-facts`. Same posture: we don't re-implement US payroll
  (FICA + multi-state + 401(k) caps + SUTA + garnishment); we
  consume ADP's output.
- `posting_builder.clj` — Maps ADP wage-types to a consumer-
  supplied US CoA (we don't ship one — note 73 Theme B P1 "kontor
  cannot solve US multi-state with code").
- `emit_provider.clj` — Not needed (no US clearance regime); the
  `LocalfileEmitProvider` default suffices.

**Effort.** GLI parser 1d, CoA fixture 1d, multi-state allocation
tests 2d, W-2 data-prep stub 1d, docs + review-after 1d.

**Gated?** Soft — pick when a US consumer surfaces. GLI format is
public; ADP is the largest single adapter target.

### 5.3 — C4–C12: the rest of the rollout

Each country below has the same shape: `compute_provider.clj`
consumes a per-vendor result file/API, `posting_builder.clj` maps
to per-country CoA, `emit_provider.clj` produces the per-
jurisdiction event-bus payload (when applicable).

| Order | Country | Dominant engine | Emit-needed? | Effort | Gating |
|---|---|---|---|---|---|
| C4 | UK | HMRC RTI / FPS via Sage / Xero / FreeAgent | Yes (FPS XML to HMRC) | ~10d | **Gated on note 78 iXBRL stack** — UK's 2026-04-01 mandatory iXBRL mandate means RTI alone is half a feature. Co-sequence with the kontor-xbrl-uk module per note 78. |
| C5 | CA | CRA T4/T4A info-returns + ROE Web for terminations | Yes (T619 envelope already shipped per roadmap Phase 4-CA; T4 slip extension) | ~5d | Existing infrastructure makes this cheap. T4 generator already in tree; add the T4-from-`:payroll-facts` builder. |
| C6 | FR | Silae / Sage / Cegid; DSN (Déclaration Sociale Nominative) | Yes (DSN XML to net-entreprises.fr) | ~10d | Gated on real FR consumer. DSN is a single monthly event-bus payload; per-vendor file formats vary. |
| C7 | AU | Xero / MYOB; STP Phase 2 to ATO | Yes (STP Phase 2 XML) | ~8d | Gated on real AU consumer. STP Phase 2 is mandatory since 2022; spec is public. |
| C8 | JP | freee / Money Forward / 弥生 (Yayoi) | No (jurisdictional reporting is annual, paper-friendly; my-number-handling is the hard part) | ~7d | Gated on real JP consumer. JP payroll math is moderate complexity; my-number PII handling is the substrate add (uses `:audit-doc/category :hr-personnel` + `:audit-doc/privilege :pii-sensitive`). |
| C9 | IN | ZenHR / Keka / GreytHR; quarterly TDS + monthly PF/ESI + per-state PT | Yes (TDS quarterly returns; PF monthly via UAN) | ~12d | Gated. IN is the second-most-painful adapter (note 73 Theme C P3); per-state professional tax fragments the rollout. |
| C10 | BR | Pluxee / RH Sistemas / Senior; eSocial (40+ event types) | Yes (eSocial S-1000..S-2399 events) | ~15d | Gated. BR is the **most complex** adapter (note 73 Theme C P2); 40+ event-type schemas. Only build when a BR consumer pays. |
| C11 | MX | CONTPAQi / Aspel NOI; CFDI Nómina + SUA for IMSS | Yes (CFDI Nómina 1.2 XML) | ~10d | Gated. CFDI Nómina is the e-payslip clearance — co-sequence with the existing kontor-l10n-mx work on CFDI invoicing. |
| C12 | other EU (NL, ES, IT, BE, AT, CH, …) | Per-country | Mostly yes | ~6-10d each | Pure long-tail; pick when consumer demand surfaces. |

**Sequencing principle.** DE first (maintainer's home + Mittelstand
pitch + canonical Personio-DATEV adapter shape). US second
(largest market + well-documented file format + multi-state
exercise for `:posting/entity`). UK third (gated on iXBRL). CA
fourth (cheap; existing T619 infrastructure). FR/AU/JP fifth-
seventh. IN/BR/MX deferred to real consumer demand because of
complexity disproportionate to substrate-value-added.

**What "minimum viable" excludes.** No vendor API keys bundled.
No real-time clocking integration. No benefits-enrollment UX. No
year-end form generation (separate per-country adapter). No
gross-to-net math reimplementation. The adapter is **glue** — it
parses what the engine already produced and routes it to the
substrate's existing primitives (postings, audit-docs, schedules,
status-history).

---

## §6 — Dependencies on shipped substrate

Stage R is overwhelmingly a **consumer of existing substrate**. The
table maps Stage R needs to shipped primitives. (Note 74 §1.11
already shows the substrate-availability shape; this version
tightens to Stage R impl checkpoints.)

| Stage R need | Substrate primitive | ADR | C |
|---|---|---|---|
| Employment / pay-period / payroll-run states | `kontor.status-machine` | 034 | C1 |
| Contract retention + content-hash | `kontor.audit-doc` | 038 | C1 |
| PII privilege + category classification | `:audit-doc/privilege` + new `:audit-doc/category` | 051 + addendum | C1 |
| Personnel retention per jurisdiction | `:retention-policy` (+ new `:category` slot) | 050 | C1, per-country as we go |
| Legal hold over personnel data | `:legal-hold` scope-query | 049 | C1 |
| Subject-access requests | `:dsar-request` + `dsar/collect` | 052 | C1 (register `:person` + `:employment` collectors) |
| Book-vs-tax wage accrual | `:ledger/framework` parallel ledgers | 021 | C2 (DE HGB §249 vs IFRS IAS 19) |
| Payroll period ↔ fiscal period | `kontor.period` (sibling, not reused) | 014 | C1 |
| Multi-entity employment | `:posting/entity` sum-to-zero | 031 | C1 |
| PTO accrual + visa expiry sweeps | `:schedule/*` | 032 | C4 |
| Gross-to-net as multi-step | `kontor.process` + ADR-068 builders | 067/068 | C2 |
| FX for foreign-employed-domestic-paid | `FxRateProvider` | 072 | C2 (DE expat) |
| Approval policy on payroll-correction | `kontor.audit-doc/approval-policy` | 038 | C2 |

**Net new substrate:** 2 attrs + 1 protocol-trio namespace
(`kontor.payroll-provider`) + 1 ADR-051 addendum. Roughly **5
hours of substrate code**; the rest is companion-tier. The note 74
"60% / 30% / 10%" framing holds: 60% already substrate, 30%
`kontor-hr` entities (§3), 10% per-country glue (§5).

---

## §7 — Simmis-simulation relevance

Simmis spawns agents that take a kontor snapshot + a business
model + "what if" knobs and project forward several quarters.
Payroll is the largest recurring expense for most businesses; the
simulation pitch lives or dies on Stage R's forward-projection
substrate.

The bridge is **already substrate** — the question is which Stage
R primitives are strictly necessary vs. nice-to-have.

**Strictly necessary (lands C1):**

1. **`:employment/wage` bitemporal.** Knob: "3% raise on 2027-01-01"
   = tx with `:db.valid/from #inst "2027-01-01"`; subsequent payroll
   projections pick up new wage at the right valid time.
2. **`:employment/entity` + `:posting/entity`** (already substrate
   per ADR-031). Knob: "move 5 engineers from US-LLC to DE-GmbH."
3. **`:pay-period` + `PayrollComputeProvider`.** Simulation loop:
   for each future `:pay-period`, invoke `compute-payroll` with
   synthetic `variable-inputs`; gross-to-net is a pure function
   call. No transact, no DB mutation — simulation as REPL exercise.
4. **`:schedule/*`** (already substrate per ADR-032). Walks forward
   via `(kontor.schedule/emit-due-events!)` against future dates.

**Nice-to-have (lands C2–C4):**

5. **Employer-side accrual modeling** (PTO, employer SI, pension,
   RSU). Per parallel-ledger ADR-021, accrues on HGB book. C2 adds
   DE HGB §249 PTO accrual; C3 adds US ASC 710 + 401(k) match.
6. **Headcount projection.** "5 new engineers 2027-03-01 at $180k"
   = 5 `:employment` rows with future `:start-date`. Pure schema.
7. **Multi-country cost comparison.** Run C2 + C3 + C5 providers
   against synthetic employments with varied `:employment/entity`;
   `PayrollFacts.employer-side` carries the answer.
8. **PTO accrual + balance projection.** `:schedule/code
   "pto-accrual-monthly"` × `:absence-allocation` (substrate
   already shipped); consumer-side projection.

### Out of scope for simulation:

- Gross-to-net jurisdictional math (use the existing engine via
  `compute-payroll`; simulation isn't going to out-compute DATEV).
- Time-tracking projection (assume average hours unless the user
  supplies a calendar).
- Benefits open-enrollment elections (assume current enrollments
  carry forward).

**Take-away.** Items 1–4 are C1 deliverables. With those, simmis
can simulate **headcount + payroll cost + multi-entity allocation**
end-to-end. Items 5–7 are C2 add-ons. The substrate makes
simulation an **emergent property** of the existing primitives —
no simulation-specific schema or new namespace required, only
disciplined use of the bitemporal + schedule + entity + provider
plumbing already in place.

---

## §8 — Effort + sequencing (the implementation timeline)

Three committable checkpoints.

### C1 — Substrate schema + protocols + `kontor.hr` skeleton (~5 days)

**Goal.** All Stage R substrate lands; no per-country code yet.
After C1, a contributor can write a `PayrollComputeProvider` impl
and round-trip it through the substrate.

- **D1.** 2 kernel attr additions (`:audit-doc/category`,
  `:retention-policy/category`); ADR-051 addendum; ADR-Stage-R-1
  draft (kontor-hr scope).
- **D2.** `kontor.payroll-provider` namespace: 3 protocols +
  `PayrollFacts` data shape + mock-provider contract test.
- **D3.** `kontor-hr` module skeleton (`modules/kontor-hr/{deps.edn,
  src/kontor/hr/{schema.clj, core.clj, person.clj, employment.clj,
  pay_period.clj}}`). Schema per §3.
- **D4.** Status-machine seeds for 4 facets; DSAR collectors for
  `:person` + `:employment`; legal-hold scope-query examples;
  retention-policy DE seeds.
- **D5.** End-to-end "create-employment-with-contract" test.
  ADR-Stage-R-1 commit. `bb ci` passes. Review-after agent.

**Acceptance.** `(kontor.hr.core/hire! conn {…})` atomically
transacts person + partner + employment + contract audit-doc +
status-history with the legal-hold/period/state-machine gate
stack honored.

### C2 — DE-DATEV-LODAS + simmis-simulation use case (~8 days)

**Goal.** First production adapter; maintainer's home jurisdiction;
cross-stage user-story validation (per ADR-037) on the DE GmbH
B2B scenario extended with payroll.

- **D1–2.** `DatevLodasComputeProvider` + Lohnauswertungsdatei
  parser + fixture.
- **D3–4.** `posting_builder.clj` with SKR04 mapping; parallel-
  ledger HGB vs tax-book PTO accrual.
- **D5.** `emit_provider.clj` writing Lohnimport-Datei (onboarding,
  wage-change events).
- **D6.** Cross-stage end-to-end: DE GmbH, monthly payroll, mid-
  month wage change, Q1 correction in Q2 (note 74 §4 #8 scenario).
  Asserts both bitemporal views ("what was true on March 31" +
  "what we know now").
- **D7.** Simmis simulation harness: load kontor snapshot + business
  model (5 employees, 3% raise on 2027-01-01, 2 new hires 2027-
  04-01); 6-quarter projection; assert payroll cost + accrued PTO
  per quarter. Pure-function REPL example.
- **D8.** ADR-Stage-R-2, docs, review-after.

**Acceptance.** The DE Mahnverfahren showcase
(`doc/showcases/01_de_b2b_factur_x.clj`) extended with monthly
payroll round-tripping through DATEV LODAS, posting to SKR04,
accruing HGB PTO liability, producing a DSAR-respecting export.

### C3 — US-ADP-export (~6 days)

**Goal.** Second adapter validates the protocol across a
fundamentally different jurisdiction (multi-state, no clearance,
GLI file format).

- **D1.** GLI parser + fixture.
- **D2–3.** US CoA mapping; per-state allocation via
  `:posting/entity`.
- **D4.** Multi-state remote-employee test (note 73 Theme B P1).
- **D5.** 401(k) match accrual + W-2 data-prep stub.
- **D6.** ADR-Stage-R-3, docs, review-after.

**Acceptance.** A US LLC with 3 employees in 3 states posts a
monthly payroll, allocates wage expense per state via
`:posting/entity`, accrues ASC 710 PTO + 401(k) match, year-to-
date wage report reconciles by state.

### Gated work (C4+):

| Checkpoint | Country | Trigger |
|---|---|---|
| C4 | UK | iXBRL stack (note 78) is C2-ready |
| C5 | CA | Cheap follow-on; T619 infrastructure already shipped — pick whenever |
| C6 | FR | Real FR consumer demand |
| C7 | AU | Real AU consumer demand |
| C8 | JP | Real JP consumer demand + my-number PII work |
| C9 | IN | Real IN consumer demand + state-tax breadth |
| C10 | BR | Real BR consumer demand + eSocial 40-event budget |
| C11 | MX | Real MX consumer + CFDI Nómina co-sequence with kontor-l10n-mx |
| C12 | NL/ES/IT/… | Long-tail, consumer-demand-driven |

**Wallclock total.** C1+C2+C3 ≈ 4 weeks of focused work for the
maintainer or one contributor. Each gated checkpoint adds 1-3
weeks at the maintainer's pace.

---

## §9 — Open questions (post-this-note maintainer input)

These items are NOT settled by this note. The five §2 design calls
ARE settled; the items below are sequencing / scope questions that
need a maintainer decision after C1 lands.

1. **Position layer (OFBiz `EmplPosition` + `EmplPositionFulfillment`).**
   Note 72 §1.2 + §5 ranked positions as v2 deferral. Per the
   simmis pitch, "headcount projection" is a position-layer concept
   (one CFO position with a vacancy is different from "no CFO").
   *Is C4 the right time to land positions, or defer to C5+?*

2. **Recruitment workflow (`:employment-application`,
   `:job-requisition`, `:job-interview`).** OFBiz models these
   (note 72 §1.1); commodity SaaS (Greenhouse, Lever, Workable)
   replaces. *Out of scope per note 09 §4 + note 74 §2.7 — confirm?*

3. **Performance review + skills.** OFBiz `PartyQual`, `PartySkill`,
   `PerfReview`. Substrate-shaped? Probably not — these are HRIS
   features without accounting consequences. *Confirm deferral?*

4. **Benefits enrollment (`:benefit-type`, `:benefit-enrollment`,
   `:deduction-type`, `:payroll-preference`).** These are in
   `kontor-hr` per §3, but C1 ships only `:person/*`,
   `:employment/*`, `:pay-period/*`. *When does the benefits stack
   land — C2 (because DE Lohnsteuer needs deduction-types) or C4?*

5. **Cross-stage user-story validation timing.** ADR-037's per-
   stage rhythm says cross-stage validation runs after 2–3 stages
   land. *Does the DE-GmbH-on-assignment-to-US-LLC story (note 74
   §4 #8) land in C2 (single stage) or wait for C3 (two stages)?*

These five items are intentionally a **short** list — the note's
purpose is to reduce design-overhead so implementation can start.
Most decisions should be made by the §2 calls + the §3-§5 sketches.

---

## §10 — Sources

**Kontor file:line.**
- `schema.clj:480-598` — `:partner/*` (Call 3 bridge target).
- `schema.clj:3480-3642` — `:audit-doc/*` incl. `:privilege`
  facet (Call 5 extension site).
- `schema.clj:2501-2506` — `:ledger/framework` (book vs tax).
- `schema.clj:3058-3120` — `:posting/entity` (ADR-031).
- `schema.clj:785-866` — `:dsar-request/*`.
- `status_machine.clj:43-360` — facet registration.
- `posting.clj:184-196` — per-entity sum-to-zero.
- `process.clj:110-138` — `run-process` gated atomic.
- `audit_doc.clj`, `legal_hold.clj`, `retention.clj`, `dsar.clj`,
  `schedule.clj`, `fx_rate_provider.clj`, `fx.clj` — full
  substrate already shipped.

**ADRs consumed.** 005 (superseded TaxProvider), 014 (period), 021
(parallel ledgers), 031 (`:entity`), 032 (`:schedule`), 034
(status-machine), 037 (per-stage rhythm + cross-stage validation),
038 (audit-doc + approval), 039 (partner master), 049 (legal-hold),
050 (retention), 051 (privilege — this note adds `:category` axis),
052 (DSAR), 061 (kontor-expense), 067 (process), 068 (`*-tx-data`),
071 (TaxRateProvider/TaxFacts/TaxPostingBuilder — **shape mirrored
directly**), 072 (FxRateProvider).

**Research notes.** 09 (predecessor), 22 (per-country retention
floors), 69 (architecture review §4 Gap 3 ranks Stage R P1
trans-national), 72 (OFBiz `humanres` reference — Apache-2.0
lift-safe; §1.2 catalog, §5 prioritization), 73 (market-pain
themes A/B/C/H/J/K + §2 "5 pains kontor uniquely fits"), 74
(internal gap; §1.11 substrate-already-covers table; §3 the 5
design calls answered here in §2), 78 (XBRL — UK iXBRL 2026-04-01
mandate gates C4).

**External specifications** (public; clean-room implementations):
- DATEV LODAS Schnittstellen-Dokumentation (datev.de)
- ADP GLI export format (Workforce Now admin docs)
- HMRC RTI FPS — https://www.gov.uk/guidance/running-payroll
- ATO STP Phase 2 — softwaredevelopers.ato.gov.au
- Receita Federal eSocial S-1000..S-2399 — gov.br/esocial
- CRA T619 + T4/T5 (already shipped per Phase 4-CA)
- SAT CFDI Nómina v1.2 (sat.gob.mx)
- IRS Publication 15 (background; US gross-to-net is out of scope)

**License posture.** OFBiz `humanres` Apache-2.0 lift-safe (§3 +
§5 grounded here); Tryton GPLv3 + Odoo LGPLv3 are design patterns
only; per-country payroll-engine file formats are public specs
(clean-room impl); kontor itself EPL-1.0, companions follow ADR-006.

---

End of note 79.
