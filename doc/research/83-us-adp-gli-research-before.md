---
date: 2026-05-18
title: 83 — US-ADP-GLI research-before (Stage R C3 implementation prep)
status: draft
audience: maintainer / impl agent picking up Stage R C3 (`modules/payroll-us-adp/`)
---

# 83 — US-ADP-GLI research-before

Stage R C3 research-before, per the ADR-037 per-stage rhythm.
Companion to [[79-hr-payroll-stage-r-plan]] §5.2 (the C3 sketch),
[[81-hr-data-model-gold-standards]] §9.6/§9.7 (compensation-as-
entity refactor + `:person/kind` etc.), and [[73-hr-payroll-market-
pain]] Themes B + D + F (the multi-state / 401(k) / W-2 pain
inventory).

Where note 79 says "we ship `modules/payroll-us-adp/` with three
providers wrapping ADP's GLI CSV," this note answers the upstream
file-format and design questions so the impl agent can start with
the schema fixture and a CoA-mapping shape already settled —
**without re-deriving** which ADP GLI flavor we target, what
columns the parser must accept, how multi-state allocates onto
`:posting/entity` vs `:analytic-account`, what the consumer-side
CoA mapping looks like, and which accruals (ASC 710 PTO, 401(k)
employer match) land on which `:ledger/framework` (per ADR-021).

License + posture notes:

- ADP file formats and configuration documentation are **public
  spec / customer-doc material**; we quote field structures and
  sample rows for interop. No code lifted.
- ADP / Gusto / Paychex / Rippling API keys + rate tables are
  consumer-supplied at install time (mirrors the ADR-005 + ADR-
  071 posture, and matches `modules/l10n-us/src/kontor/l10n_us/
  sales_tax.clj` which ships report definitions and `state-codes`
  metadata but no API credentials).
- We never re-implement US gross-to-net: FICA, FUTA, SUTA,
  multi-state withholding, 401(k) caps, garnishment-priority
  patchwork, supplemental-wage withholding — all live in the
  payroll engine. kontor parses the result. (Same posture as
  note 79 §5.2: "we don't re-implement DE payroll math; we
  consume DATEV's result.")

---

## §1 — TL;DR (the impl agent's pre-flight)

1. **The "ADP GLI CSV" is well-shaped enough to spec.** It is a
   10-column comma-delimited ASCII text file, one row per posting
   amount, no header row, fields quoted, debits as positive
   amounts and credits as negative amounts. File extension is
   conventionally `.GLI` or `.CSV`; sample names are
   `GD1234P.GLI` (RUN-style "G" + 4-digit-code + period). The
   10-column shape is **the same across ADP RUN, Workforce Now,
   and the InfoLink GL Interface** — the column **meanings** are
   what the customer configures (account-mapping, cost-center vs
   department in the reference fields).
2. **The 10 columns** are: `Client Code` (5), `GL Account Number`
   (24), `Journal Source Code` (2, typically `PR`), `Date`
   (MMDDYYYY, 8), `Amount` (13, negative = credit), `Reference 1`
   (6), `Description` (20), `Reference 2` (6), `Reference 3` (6),
   `Record Code` (2, typically `02`).
3. **The mapping ADP customers configure** is the canonical wage-
   type-→-GL-account dictionary. The customer-side configurator
   binds each ADP wage-type bucket (`GROSS`, `ER SOCIAL
   SECURITY`, `EE FEDERAL TAX`, `NET PAY`, …) to a single GL
   account number per (company-code, optionally cost-center). The
   **kontor wage-type → CoA dictionary is exactly the same data
   shape**, consumer-supplied at install time.
4. **One config-driven parser covers ADP plus the closest cousins
   (Paychex Flex CSV-export, OnPay CSV, Gusto General Ledger
   Mapper CSV, Rippling generic-GL-mapper CSV).** All five tools
   emit a "one row per GL line, account + amount + description +
   per-row dimensions" CSV; the differences are column order,
   header-row presence, the credit convention (negative-amount vs
   separate `dr`/`cr` columns), and the wage-type vocabulary.
   Ship `AdpGliComputeProvider` first; factor a `GenericGlCsv
   ComputeProvider` underneath with vendor-specific column maps.
5. **Multi-state allocation: use `:analytic-account` with a
   `:state` axis, not `:posting/entity`.** A US LLC employing
   remote workers in 15 states is **one legal entity**; per-state
   wage allocation is a *reporting / analytic* concern, not a
   separate balanced-books entity. `:posting/entity` is reserved
   for the actual legal-employer dimension (intercompany /
   secondment / multi-LLC group). See §4 for the recommendation
   and the test-fixture catalogue.
6. **kontor does NOT flag "remote employee in a state where the
   LLC has no nexus"** — nexus determination is a tax /
   registration question the customer or their auth layer
   resolves. kontor's contribution is: (a) the per-employment
   `:work-state` analytic axis on every posting, so the customer
   can run a "wages by state" report at any moment and notice
   the new state, and (b) a scheduled completeness-check the
   customer can wire up via `kontor.schedule` (per ADR-032) to
   surface new-state hires before the next payroll.
7. **Wage-type CoA mapping data shape (~25 wage-types).** Mirrors
   the QBO-style starter chart already in `modules/l10n-us/src/
   kontor/l10n_us/chart.clj`. The mapping ships as
   `consumer-supplied EDN` (`kontor.payroll-us-adp.wage-type-
   map`), not as a baked-in default — every customer's CoA
   differs in account numbering.
8. **ASC 710 PTO accrual + 401(k) employer match accrual are
   parallel-ledger concerns** (per ADR-021). The book/GAAP ledger
   (`:ledger/framework :us-gaap`) accrues both at the period in
   which the service is rendered; the tax ledger (`:ledger/
   framework :us-tax`) defers the 401(k) match until actually
   contributed within IRC §404(a)(6) (= prior-tax-year + tax-
   return-due-date including extensions, typically 8.5 months).
   PTO accrual lives only on the book ledger (IRC §461(h)
   economic-performance test typically blocks tax-side accrual
   until the absence is actually taken).
9. **W-2 reconciliation is data-prep, not generation.** ADP files
   W-2s itself. kontor's surface: a per-employee year-to-date
   report that breaks down gross → Box 1 (federal wages) → Box 3
   (SS wages, capped at the wage base) → Box 5 (Medicare wages)
   → Box 12 codes (D=401(k), DD=employer health, W=HSA, C=GTL).
   This is one report definition (`kontor.payroll-us-adp.w2-
   recon/year-to-date-by-employee`) that reads from the
   `:posting` log; not an emit.
10. **Emit provider scope: trivial. `LocalfileEmitProvider`
    default suffices.** No US clearance regime, no real-time
    event-bus (unlike DE LODAS / BR eSocial / UK FPS / AU STP).
    The emit-side artifacts kontor produces are *audit-doc rows*
    pointing at the source GLI file + the per-period import
    receipt — not transmissions to a tax authority.

---

## §2 — ADP General Ledger Interface CSV format

The single load-bearing reference is Microsoft's published
"Payroll Connect for Dynamics GP" docs, which document the GLI
format exhaustively because Dynamics GP imports it. ADP's own
admin-portal PDFs (`support.adp.com/.../GL_Download_
Instructions.pdf`, `RUN_GL_Guide_QBO.pdf`, the GLI Update Account
Mapping quick-reference) are partly image-rendered and don't
extract cleanly via fetch, but the field structure they describe
matches Microsoft's spec line-for-line. The Juris (LexisNexis
legal-billing) interface, the Sage 50 community discussions, the
Shoptech / E2 Shop System docs, the InfoLink hosted GL Interface
landing page, and the dataproaccounting.com integration script
manual all reference the same 10-column shape.

### 2.1 — File-level metadata

- **Encoding.** ASCII (7-bit safe), comma-delimited. ADP does not
  emit UTF-8 with BOM. Customer-facing GLI files we have
  observed via the integration docs use straight ASCII because
  account numbers, codes, and US-dollar amounts don't need wider
  encoding; international-character handling is not part of the
  GLI surface. The parser should accept UTF-8 anyway (it's a
  superset of ASCII for the byte ranges actually used).
- **Delimiter.** Comma. Every field quoted with double-quotes.
- **Line terminator.** CRLF (`\r\n`) per the Windows-origin
  format; the parser should tolerate `\n`-only too.
- **Header row.** None. The first line is data.
- **File extension.** `.GLI` (preferred for InfoLink and
  Workforce Now) or `.CSV` (preferred for RUN's QuickBooks-
  Online connector). Both are the same format; the suffix is
  consumer convention.
- **File naming.** Conventional pattern is `GD<company-code>P.GLI`
  for InfoLink (example: `GD1234P.GLI`), or
  `<adp-company-id>.CSV` for RUN's QBO connector. RUN customers
  often see filenames like `WBN12345.csv` (Workforce Now + ADP
  ID).
- **One file per pay-period × company-code.** Multi-company-code
  customers receive one file per company-code; cross-company-code
  postings (if any) live in their own clearing-account rows
  within the originating company-code's file.

### 2.2 — Column structure

The 10 columns, in fixed order:

| # | Column | Max | Notes |
|---|---|---|---|
| 1 | `Client Code` | 5 | The ADP company code (e.g. `QC911`). Identifies which company-code the row belongs to. Often used as a clearing-account-pair key when the customer has cross-company-code postings. |
| 2 | `GL Account Number` | 24 | The customer's own GL account number as configured in the ADP GLI account-mapping screen. May embed cost-center / department / location sub-codes if the customer's CoA uses segmented account numbers (very common in mid-market). |
| 3 | `Journal Source Code` | 2 | Typically `PR` (payroll). ADP uses this to distinguish payroll postings from adjustment postings. |
| 4 | `Date` | 8 | `MMDDYYYY`. Typically the pay-period-end date (or check-date for some configurations). |
| 5 | `Amount` | 13 | Decimal with explicit `.` (e.g. `33222.11`). **Negative amounts are credits.** Debits are positive. No separate `DR`/`CR` columns. |
| 6 | `Reference Number 1` | 6 | Customer-configurable; commonly used for employee ID (truncated) or batch ID. |
| 7 | `Description` | 20 | Free text. Customer-configurable but ADP typically writes the wage-type label: `GROSS`, `NET PAY`, `EE FEDERAL TAX`, `ER SOCIAL SECURITY`, `EE MEDICARE`, `ER MEDICARE`, `FUTA TAX`, `EE SUI TAX`, `401K`, `MEALS`, etc. |
| 8 | `Reference Number 2` | 6 | Customer-configurable; commonly used for cost-center, department, or location. |
| 9 | `Reference Number 3` | 6 | Customer-configurable; commonly used for a second analytic axis. |
| 10 | `Record Code` | 2 | Almost always `02` (a record-type flag; ADP internal). |

### 2.3 — Sample fixture (canonical)

From Microsoft's documented sample, this is what one pay-period
payroll-run looks like as ADP exports it. It documents the
debit-positive / credit-negative convention and the typical
wage-type vocabulary:

```
"QC911","208015","PR","04302007","33222.11","","GROSS","","","02"
"QC911","208003","PR","04302007","-2696.34","","ER SOCIAL SECURITY","","","02"
"QC911","208004","PR","04302007","-630.59","","ER MEDICARE","","","02"
"QC911","208005","PR","04302007","-2696.34","","EE SOCIAL SECURITY","","","02"
"QC911","208006","PR","04302007","-630.59","","EE MEDICARE","","","02"
"QC911","208010","PR","04302007","-29051.95","","NET PAY","","","02"
"QC911","208011","PR","04302007","-8590.10","","EE FEDERAL TAX","","","02"
"QC911","208012","PR","04302007","-348.07","","FUTA TAX","","","02"
"QC911","208013","PR","04302007","-59.63","","EE SUI TAX","","","02"
"QC911","208106","PR","04302007","-398.49","","MEALS","","","02"
"QC911","","PR","04302007","1612.60","","","","","02"
```

Note the last row: empty `GL Account Number`, no description, a
positive 1,612.60 — this is the **balancing row** ADP inserts to
make the file sum to zero. In double-entry parlance: GLI is
already balanced when ADP writes it; if the parser sums the
`Amount` column across the file and gets non-zero, the file is
corrupt or truncated. The kontor parser MUST check this
invariant and reject before transacting.

(The Microsoft sample includes a final test-line of
`"QC911","ABCDEFGHIJKL","PR","04302007","-999999999.99","","TEST
LINE","","","02"` to demonstrate truncation behavior; that's
a Dynamics-GP-specific test artifact and not part of a real ADP
export.)

### 2.4 — Wage-type vocabulary (what's in column 7)

ADP does not publish an enum; the description text is what the
customer configured in the GLI account-mapping screen. The
**de-facto vocabulary** across ADP RUN, Workforce Now, and
InfoLink installations is:

**Earnings / gross side (debits to wage-expense accounts):**

- `GROSS` (the full gross wage line)
- Sometimes broken out: `REGULAR`, `OVERTIME`, `BONUS`,
  `COMMISSION`, `PTO PAID`, `HOLIDAY`, `SICK`, `IMPUTED INCOME`,
  `GTL` (group-term life over $50K), `PERSONAL USE OF AUTO`
- `MEALS`, `LODGING` (taxable fringe benefits)
- Some installations break `WAGES` from `SALARIES` from
  `OVERTIME` (each with its own GL account)

**Employer-side taxes + accruals (debits to payroll-tax-expense
or benefit-expense accounts):**

- `ER SOCIAL SECURITY` (employer FICA SS, 6.2% to wage-base cap)
- `ER MEDICARE` (employer FICA Medicare, 1.45% no cap)
- `FUTA TAX` (federal unemployment; first $7,000 per employee)
- `ER SUI TAX` or `<STATE> SUI` (state unemployment; per-state)
- `ER 401K MATCH` or `ER MATCH`
- `ER HEALTH` (employer health insurance share)
- `WORKERS COMP` (per-state)

**Employee-side deductions (credits to employee-withholding-
liability accounts):**

- `EE FEDERAL TAX` (federal income tax withheld)
- `EE STATE TAX` or `<STATE> TAX` (per-state withholding)
- `EE LOCAL TAX` (per-locality, e.g. Ohio RITA, PA PSD,
  NYC city tax, Philadelphia, San Francisco)
- `EE SOCIAL SECURITY` (employee FICA SS share)
- `EE MEDICARE` (employee FICA Medicare share + Additional
  Medicare 0.9% over $200K)
- `EE 401K` (employee pre-tax 401(k) deferral)
- `EE ROTH 401K` (employee after-tax Roth 401(k) deferral)
- `EE HEALTH` (employee health-insurance pre-tax deduction
  under Section 125)
- `EE HSA` (employee HSA contribution)
- `EE FSA` (employee FSA contribution)
- `EE DEP CARE FSA` (employee dependent-care FSA)
- `GARNISHMENT` (per-court-order; sometimes split into
  `CHILD SUPPORT`, `LEVY`, `STUDENT LOAN`, `BANKRUPTCY`)
- `EE DENTAL`, `EE VISION`, `EE LIFE INSURANCE`

**Final liability (credit to cash / wages-payable):**

- `NET PAY` (the amount actually paid to the employee — the
  large credit row that balances most of the file)

### 2.5 — The GLI configuration — what the customer sets up in ADP

This is **the canonical wage-type → GL-account mapping** kontor
consumes. The customer configures it inside ADP, then ADP emits
GLI files that already reflect the mapping. Per the ADP "How to
do the RUN General Ledger Mapping" admin guide and the
GLI-UpdateAccountMapping quick-reference (Course Hero
228342942), the mapping screen has three sections:

1. **Cash or Asset Account** — where `NET PAY` posts (typically
   a single cash / wages-payable account).
2. **Payroll Expenses** — where the gross-wage and employer-tax
   wage-types post. Customers typically pick from `Wage Expense`,
   `Payroll Tax Expense`, `Employee Benefit Expense`, plus
   subdivisions.
3. **Payroll Liabilities** — where the employee-side
   withholdings, employer-side taxes-owed-to-the-government, and
   the 401(k) / garnishment / benefit-vendor remittances post.

Per-wage-type, per-(company-code, optionally cost-center /
department / location). The customer can drive different
allocations per cost-center if their CoA segments support it.

For kontor: this is exactly the data shape the consumer of
`modules/payroll-us-adp/` must supply at install time. See §9
for the EDN literal shape.

### 2.6 — ADP RUN vs Workforce Now vs InfoLink vs Vantage HCM

| Tier | Product | Market | GLI emission |
|---|---|---|---|
| SMB | ADP RUN | <50 employees typical | GLI CSV (smaller export, often QBO-shaped) |
| Mid-market | ADP Workforce Now | 50–1000 employees | GLI CSV (InfoLink-shaped, larger, more cost-center detail) |
| Mid-market integration | InfoLink GL Interface | Hosted GLI portal | Same GLI format |
| Enterprise | ADP Vantage HCM (formerly Lifion) | 1000+ | Different format, often direct API. **Out of scope for C3.** |

We target **RUN + Workforce Now + InfoLink**. The format is
identical across the three; the only difference is what's in the
customer's account-mapping table. Vantage is enterprise-priced
and rare among mid-market consumers; the second adapter slot
(see §3) is better spent on Gusto / Rippling / Paychex than on
Vantage.

---

## §3 — Alternative US engines (one config-driven parser)

The strategic pitch of §1 bullet 4: ADP first, then refactor to
a generic-GL-CSV parser with per-vendor column maps. This is
cheap because the five major US payroll engines all converged on
the same data shape — one row per GL line — even though they
differ on column order, header presence, and credit-convention.

### 3.1 — Gusto

- **API + CSV both available.** Gusto's General Ledger Mapper
  produces a CSV per pay-run; the underlying General Ledger
  Report API
  (`POST /v1/payrolls/{payroll_uuid}/reports/general_ledger`)
  produces the same data as JSON.
- **CSV shape.** Customer-configurable column set with a header
  row. Columns typically include `Pay Date`, `Employee Name`,
  `Job Title`, `Department`, `Location`, `Amount`, `Pay Type`,
  `GL Account`, optionally `Debit`/`Credit` as separate columns
  (vs ADP's signed-amount).
- **API shape.** JSON; `journal_entries[]` with `account_uuid`,
  `amount`, `debit_or_credit`, `description`.
- **Wage-type vocabulary.** Different from ADP. Earnings codes
  are configurable; default include `regular`, `overtime`,
  `double_overtime`, `pto`, `holiday`, `sick`, `bonus`,
  `commission`. Deductions include `medical`, `dental`, `vision`,
  `hsa`, `fsa`, `dependent_care_fsa`, `401k_traditional`,
  `401k_roth`, `garnishment`, `child_support`.
- **For kontor.** Excellent target after ADP. The API-shaped
  version is closer to `PayrollFacts` per-employee +
  per-component, so it can drop ADP's "balancing row" trick.

### 3.2 — Paychex Flex

- **CSV-only via the General Ledger module.** ".CSV" file format
  per the Paychex Flex export screen; up to 18 months historical;
  export can take up to 45 minutes to produce.
- **Two grain options:** "Summary" (per-pay-period account
  totals) or detail levels (per-employee). The Summary form
  matches the ADP GLI shape (one row per GL line); the detail
  form has per-employee rows.
- **For kontor.** Use the Summary form first; the per-employee
  detail form can be a v2 enhancement when consumers want
  per-employee analytic breakdown without parsing ADP's
  description-text.

### 3.3 — OnPay

- **CSV via the OnPay GL mapping screen + direct integrations
  with QuickBooks Online / Xero / QuickBooks Desktop (IIF).**
- **CSV shape.** Configurable; "map to your general ledger by
  pay type" produces one row per (account, pay-type, optionally
  department) per pay-period.
- **Wage-type vocabulary.** Closer to Gusto than ADP (codes
  like `regular`, `overtime`, `holiday`, `bonus`, `401k`,
  `garnishment`).
- **For kontor.** Symmetric with Paychex; same parser shape
  with a different column map.

### 3.4 — Rippling

- **CSV template + API.** Rippling ships a generic CSV template
  for "non-integrated" GL targets; the four supported direct
  integrations are QBO, Xero, NetSuite, Sage Intacct.
- **The "default expense account" pattern.** Rippling assigns
  each pay-type a default GL account; new pay-types auto-route
  to the default. This means the CSV's column-7-description
  vocabulary is open-set per customer, but the GL-account
  number is always populated.
- **For kontor.** Generic CSV target; same parser shape.

### 3.5 — Justworks (PEO)

- **Different shape entirely.** Justworks is a PEO — the
  customer's company-EIN does not file payroll taxes; Justworks
  Employment Group LLC files under its own EIN. From the
  customer's books, **payroll appears as a single line-item
  invoice** ("Justworks invoice #12345 — $42,891.23 — wages +
  taxes + service-fee") paid to Justworks; the per-employee /
  per-tax-bucket detail lives in Justworks' Custom Payroll
  Report CSV (export from the dashboard).
- **For kontor.** Justworks is a *consumer-side bookkeeping*
  decision: either book the single Justworks invoice (simple,
  loses per-employee detail) or pull the Custom Payroll Report
  CSV and decompose into per-bucket postings (matches non-PEO
  shape, requires parsing). The Custom Payroll Report CSV is
  similar enough to the Gusto / OnPay shape that the same
  `GenericGlCsv` parser plus a Justworks column map handles it.
  **Out of C3's primary scope.**

### 3.6 — QuickBooks Payroll

- **Native to QBO.** QBO Payroll doesn't emit a GLI-style CSV
  because it's already inside the GL. Consumers using QBO
  Payroll typically don't need kontor's payroll adapter — they
  would export the QBO journal log directly and migrate it.
- **For kontor.** Out of C3's primary scope; if a consumer is
  on QBO Payroll, the migration path is the QBO journal
  export, not a payroll-engine adapter.

### 3.7 — A single config-driven parser

The five in-scope engines (ADP, Gusto, Paychex, OnPay, Rippling)
all emit "one row per GL line" CSVs with:

- A GL account number (always present).
- A signed amount, OR separate debit and credit columns.
- A pay-period / pay-date.
- A description text (the wage-type label).
- Zero to three additional dimension columns (cost-center,
  department, location, employee, batch).

The parser shape (sketch):

```clojure
{:vendor :adp                   ; :adp | :gusto | :paychex | :onpay | :rippling
 :columns
 [{:idx 0  :role :client-code           :type :string}
  {:idx 1  :role :gl-account            :type :string :required true}
  {:idx 2  :role :journal-source-code   :type :string}
  {:idx 3  :role :date                  :type :date   :format "MMddyyyy"}
  {:idx 4  :role :amount-signed         :type :decimal
                                        :credit-sign :negative}
  {:idx 5  :role :reference-1           :type :string}
  {:idx 6  :role :description           :type :string}
  {:idx 7  :role :reference-2           :type :string :map :cost-center}
  {:idx 8  :role :reference-3           :type :string :map :state}
  {:idx 9  :role :record-code           :type :string}]
 :has-header false
 :encoding :ascii
 :delimiter \,
 :quote \"
 :line-terminator :crlf-or-lf
 :balance-check :sum-to-zero}
```

The Gusto / Paychex / OnPay / Rippling variants override
`:columns`, `:has-header`, and `:credit-sign` (Gusto uses
explicit `:debit`/`:credit` columns, others mostly negative-
signed amounts). One parser, six column maps. Estimated parser
LoC: ~100 lines including the balance-check invariant.

### 3.8 — Recommendation

- Ship **`AdpGliComputeProvider`** as the primary C3 deliverable.
- Factor the parser **internally** as `kontor.payroll-us-adp.csv
  /parse` taking a `:vendor` keyword and a column map.
- Document the `:gusto`, `:paychex`, `:onpay`, `:rippling`
  column maps in `modules/payroll-us-adp/resources/vendor-
  columns.edn` as **reference fixtures**, not as production
  paths. Consumers wanting Gusto-first install would write
  their own `GustoApiComputeProvider` (or a thin `GustoCsv
  ComputeProvider`) on top of the same parser. **Do not** name
  the C3 module `kontor-payroll-us-generic` — that obscures the
  ADP-first focus.

---

## §4 — Multi-state allocation: recommendation

Note 73 Theme B P1 is the most-cited US complexity: a US LLC
with remote employees in 5–15 states needs per-state wage
allocation in the GL. Note 79 §5.2 says "per-state allocation
via `:posting/entity`" — **this note refines that to
`:analytic-account` with a `:state` axis, NOT `:posting/entity`.**

### 4.1 — Why not `:posting/entity`

Per ADR-031 §"What kontor adopts," `:entity` is the **legal
accounting unit** (subsidiary, branch, consolidation parent,
elimination subsidiary). The sum-to-zero invariant runs per-
(entity, ledger, commodity). If we mapped per-state to
`:posting/entity`, then a US LLC with employees in 15 states
would be modeled as 15 entities. That breaks the model:

1. **Sum-to-zero would have to hold per-state.** But payroll
   debits wage-expense in one state and credits wages-payable
   from a single cash account at the corporate level — that's
   inherently cross-state. We'd need 14 intercompany clearing
   pairs per payroll-run.
2. **A US LLC is one legal entity.** It files one consolidated
   federal Form 1120 (or 1065 for a partnership LLC). Carving
   it into 15 sub-entities collides with this reality.
3. **The `:entity/functional-commodity`** is one currency per
   entity. 15 USD sub-entities are degenerate.
4. **`:posting/entity` is reserved** for true multi-entity
   scenarios that note 79 §2.2 carved out: the executive
   employed by Acme-DE-GmbH and seconded to Acme-US-LLC, where
   each employer-side legal-entity has its own books, payroll
   provider, and statutory filings. That's the case we built
   `:posting/entity` for; we shouldn't dilute it for analytic
   slicing.

### 4.2 — Why `:analytic-account/state` (ADR-022 axis)

ADR-022 + ADR-032 already ship the `cost-center` analytic plan
as a kernel-installed default. Per-state wage allocation fits
the analytic-account shape exactly:

1. **Per-plan sum-to-100 holds at the posting level.** Each
   `gross-wage` posting carries a `:state-allocation` analytic
   distribution: 60% CA, 40% NY for a hybrid employee, or
   100% CO for a Denver-only employee. The kontor invariant
   already enforces the sum-to-100.
2. **A single `wages-by-state` report definition** runs across
   all postings + their analytic distributions and produces the
   per-state wage total without re-querying or re-allocating.
3. **State-tax withholding postings** carry `:state` as part of
   the wage-type description anyway (the ADP `EE STATE TAX`
   line typically already has the state code embedded). The
   posting builder routes those to per-state
   `state-tax-withholding-payable` accounts (e.g.
   `2150-CA`, `2150-NY`) without using `:posting/entity`.
4. **A new state hire doesn't require a schema migration** — it
   adds an `:analytic-account` row with `:plan :state` +
   `:code "WY"`, idempotent at install time. With
   `:posting/entity`, adding a state means adding an entity row
   and a clearing-account-pair for every cross-state posting.

### 4.3 — The fixture shape

The `:state` analytic plan in `modules/payroll-us-adp/resources
/state-analytic-plan.edn`:

```clojure
{:analytic-plan/code "state"
 :analytic-plan/name "US state of work"
 :analytic-plan/applicability :optional   ; per-account-required only
                                          ; on wage-expense, payroll-tax
                                          ; expense, benefit accounts
 :analytic-plan/active true}
```

`:analytic-account` rows per state (idempotent, one-time
install):

```clojure
[{:analytic-account/plan [:analytic-plan/code "state"]
  :analytic-account/code "CA"
  :analytic-account/name "California"
  :analytic-account/external-codes {:iso-3166-2-us "US-CA"}}
 ; … 50 states + DC + PR + US-VI + GU + AS + MP
 ]
```

The wage-expense account (and the employer-tax expense, and
the employee benefit expense, and the wages-payable, …) carries
`:account/required-analytic-plans [:state]` per ADR-022 — so
the posting builder is forced to attach a `:state` distribution
to every wage posting.

### 4.4 — When `:posting/entity` IS used

For the cross-border / multi-LLC scenarios:

- **Multi-LLC US group.** Acme-Holdings has Acme-CA-Operating-
  LLC + Acme-DE-Operating-LLC (Delaware corp) + Acme-IP-
  Holding-LLC. Each is a real legal entity with separate
  payrolls, separate filings. `:posting/entity` correctly
  separates them.
- **DE-GmbH-secondment-to-US scenario** (note 79 §2.2).
  Single human, two employments, two legal employers,
  `:posting/entity` per side. Per-state analytic on each side
  is still the right axis.
- **Justworks / PEO co-employment.** The customer's company-
  EIN is the `:posting/entity`; Justworks' EIN doesn't appear
  in the customer's books (Justworks is a vendor — `:partner
  /kind :supplier`).

### 4.5 — Nexus and the "new state" exception

The customer's first hire in a new state (e.g., a remote
engineer moves from CA to WY) triggers a SUTA-registration,
state-income-tax-withholding-registration, and (often) a
workers'-comp-registration before the next pay-period.
**kontor does not flag this.** The auth / process layer is
responsible. kontor's contribution:

1. **The `wages-by-state` report** surfaces the new state
   immediately. A reasonable consumer wires a scheduled
   `kontor.report/compute-report` (per ADR-032 `:schedule`) to
   run weekly and alert when a previously-zero state has a
   non-zero total.
2. **A `kontor.status-machine` `:state-registration` facet**
   (consumer-side) can model the per-(entity, state)
   registration status — `:not-registered → :pending →
   :active → :inactive`. The substrate hosts it; the customer
   manages the workflow.
3. **The `:audit-doc/category :tax-filing`** (per ADR-051 with
   note 79's §2.5 category addendum) on each registration
   confirmation document, with `:audit-doc/expiry-date` for
   periodic renewals (some states require annual renewal of
   the withholding registration).

We document this responsibility split clearly in the C3
adapter README; the failure mode is well-known and consumer-
managed everywhere we've seen it (ADP, Gusto, Rippling).

---

## §5 — US wage-type → CoA conventions

There is no US national chart of accounts (no SKR04 equivalent).
Every US customer keeps their own. But there are conventions
that hold across QuickBooks Online / NetSuite / Sage Intacct /
Xero starter charts and across every accountant blog covering
payroll bookkeeping. The QBO-style starter chart already in
`modules/l10n-us/src/kontor/l10n_us/chart.clj` provides the
account-ranges we'll map ADP wage-types into.

### 5.1 — The typical US CoA for payroll

| Range | Category | Examples |
|---|---|---|
| 5000–5199 | Wage expense | `5000 Wages – Officer`, `5010 Wages – Operating`, `5020 Wages – Admin`, `5030 Wages – Sales`, `5040 Wages – Engineering` |
| 5200–5249 | Payroll tax expense (employer side) | `5200 Payroll Tax – FICA SS`, `5210 Payroll Tax – FICA Medicare`, `5220 Payroll Tax – FUTA`, `5230 Payroll Tax – SUTA`, `5240 Payroll Tax – State UI` |
| 5300–5399 | Employee benefit expense | `5300 Health Insurance – Employer Share`, `5310 401(k) Employer Match`, `5320 Workers Comp`, `5330 Life Insurance – Employer Share` |
| 2100 | Wages payable | `2100 Wages Payable` (the cash side of `NET PAY` until the bank disbursement clears) |
| 2110–2149 | Employee tax withholding payables | `2110 Federal Income Tax Withheld`, `2115 FICA SS Withheld – Employee`, `2120 FICA Medicare Withheld – Employee`, `2125 Additional Medicare Withheld`, `2130 State Income Tax Withheld`, `2140 Local Income Tax Withheld` |
| 2150–2199 | Employer-side tax payables (the matching liabilities) | `2150 FICA SS Payable – Employer`, `2155 FICA Medicare Payable – Employer`, `2160 FUTA Payable`, `2165 SUTA Payable`, `2170 State UI Payable` |
| 2200–2249 | Voluntary deduction payables | `2200 401(k) Employee Deferral Payable`, `2205 401(k) Roth Deferral Payable`, `2210 401(k) Employer Match Payable`, `2220 HSA Payable`, `2225 FSA Payable`, `2230 Dep Care FSA Payable`, `2240 Health Insurance Premium – Employee Share Payable` |
| 2250–2289 | Garnishment payables | `2250 Garnishment Payable`, `2260 Child Support Payable`, `2270 Bankruptcy Order Payable`, `2280 Student Loan Garnishment Payable` |
| 2290–2299 | PTO accrual (ASC 710) | `2290 PTO Accrual` (current portion), `2295 PTO Accrual – Long-Term` |

These ranges are **conventional, not mandated**. Real customer
CoAs vary in numbering — some use four digits, some five, some
six; some segment account numbers (`5000-100-CA` for "wages,
operating, California"). The consumer-supplied wage-type map
(§9) handles all of these uniformly because it carries the
customer's actual account codes — kontor doesn't bake in any
numbering.

### 5.2 — The default ADP wage-type → CoA mapping (starter)

We ship a **reference fixture**, not a default mapping. The
consumer copies it, edits to match their CoA, and supplies it
to the adapter at install time. Example fixture:

```clojure
;; modules/payroll-us-adp/resources/wage-type-map-reference.edn
;; "Reference" — copy + edit to match your CoA.
{:vendor :adp
 :description-rules
 [;; Earnings
  {:match #"^GROSS$"            :account "5010" :role :wage-expense}
  {:match #"^REGULAR$"          :account "5010" :role :wage-expense}
  {:match #"^OVERTIME$"         :account "5010" :role :wage-expense}
  {:match #"^BONUS$"            :account "5010" :role :wage-expense}
  {:match #"^COMMISSION$"       :account "5010" :role :wage-expense}
  {:match #"^PTO PAID$"         :account "5010" :role :wage-expense}
  {:match #"^IMPUTED INCOME$"   :account "5010" :role :wage-imputed
                                                :tax-treatment :imputed}
  {:match #"^GTL$"              :account "5010" :role :wage-imputed}

  ;; Employer-side taxes
  {:match #"^ER SOCIAL SECURITY$" :account "5200" :role :er-tax}
  {:match #"^ER MEDICARE$"        :account "5210" :role :er-tax}
  {:match #"^FUTA TAX$"           :account "5220" :role :er-tax}
  {:match #"^([A-Z]{2}) SUI$"     :account "5230" :role :er-tax
                                  :state-from-group 1}
  {:match #"^ER SUI TAX$"         :account "5230" :role :er-tax}

  ;; Employer-side benefits
  {:match #"^ER HEALTH$"          :account "5300" :role :er-benefit}
  {:match #"^ER 401K MATCH$"      :account "5310" :role :er-401k-match
                                                  :asc-710? false
                                                  :irc-404a6? true}
  {:match #"^WORKERS COMP$"       :account "5320" :role :er-benefit}

  ;; Employee-side withholdings
  {:match #"^EE FEDERAL TAX$"     :account "2110" :role :ee-fed-withheld}
  {:match #"^([A-Z]{2}) TAX$"     :account "2130" :role :ee-state-withheld
                                                  :state-from-group 1}
  {:match #"^EE STATE TAX$"       :account "2130" :role :ee-state-withheld}
  {:match #"^EE LOCAL TAX$"       :account "2140" :role :ee-local-withheld}
  {:match #"^EE SOCIAL SECURITY$" :account "2115" :role :ee-fica-ss}
  {:match #"^EE MEDICARE$"        :account "2120" :role :ee-fica-medi}
  {:match #"^EE 401K$"            :account "2200" :role :ee-401k-deferral
                                                  :w2-box "12" :w2-code "D"}
  {:match #"^EE ROTH 401K$"       :account "2205" :role :ee-roth-deferral
                                                  :w2-box "12" :w2-code "AA"}
  {:match #"^EE HEALTH$"          :account "2240" :role :ee-section125
                                                  :section-125? true}
  {:match #"^EE HSA$"             :account "2220" :role :ee-hsa
                                                  :w2-box "12" :w2-code "W"}
  {:match #"^EE FSA$"             :account "2225" :role :ee-fsa}
  {:match #"^EE DEP CARE FSA$"    :account "2230" :role :ee-dep-care-fsa
                                                  :w2-box "10"}

  ;; Garnishments
  {:match #"^GARNISHMENT$"        :account "2250" :role :garnishment}
  {:match #"^CHILD SUPPORT$"      :account "2260" :role :child-support}

  ;; Final liability
  {:match #"^NET PAY$"            :account "2100" :role :net-pay-liability}]}
```

The kontor parser walks the GLI rows, matches the description
text against the regex rules in order, and routes each row to
the configured account. The optional `:state-from-group`
extracts the 2-letter state code from the regex capture group
for analytic-distribution attachment. The `:asc-710?`,
`:irc-404a6?`, `:w2-box`, `:w2-code`, `:section-125?` flags
inform the parallel-ledger and W-2 reconciliation logic in §6
and §7.

### 5.3 — Cost-center / department / location mapping

ADP's columns 6, 8, 9 (Reference 1 / Reference 2 / Reference 3)
carry the customer's per-row dimensions. The mapping data shape
declares which dimension each reference column carries:

```clojure
{:reference-mappings
 [{:column :reference-2 :plan "cost-center" :role :cost-center}
  {:column :reference-3 :plan "state"        :role :state}]}
```

The parser attaches the corresponding analytic-distribution to
each posting. The `:state` distribution allocates 100% to the
single state from the reference; richer multi-state allocations
(60/40 hybrid employees) need ADP to be configured with split
rows per employee per state — which it can do — or with a
post-parse customer-supplied splitter.

---

## §6 — ASC 710 PTO accrual + 401(k) match parallel ledger

The §1 bullet 8 commitment: PTO accrual + 401(k) match accrual
are parallel-ledger concerns per ADR-021. The book/GAAP ledger
(`:ledger/framework :us-gaap`) accrues both at the period in
which the service is rendered; the tax ledger (`:ledger/
framework :us-tax`) defers per IRC §461(h) and §404(a)(6).

### 6.1 — ASC 710 PTO accrual (book ledger)

**The four conditions (FASB ASC 710-10-25-1).** All four must
be met for accrual:

1. The employee's right to receive compensation for future
   absences is **attributable to employee services already
   rendered**.
2. The right **vests or accumulates** (vesting means the
   employee gets paid out for unused PTO at separation;
   accumulating means unused PTO carries forward to a later
   period).
3. Payment of the compensation is **probable**.
4. The amount of the payment can be **reasonably estimated**.

"Use it or lose it" PTO that does not vest at separation
typically does NOT meet condition 2 and is NOT accrued. Standard
US private-sector vacation policies (vest at separation, carry
forward partially) MEET all four conditions.

**Calculation.** Multiply each employee's unused PTO hours by
their fully-loaded hourly rate (base + employer payroll taxes).
Sum across all employees. The result is the period-end
**balance-sheet liability**.

**Journal entry (per pay-period, book ledger only):**

```
Dr  PTO Expense (5040 or similar)        $X
Cr  PTO Accrual (2290)                       $X
```

When an employee actually takes PTO, the wage expense reverses
the accrual instead of hitting wage-expense fresh:

```
Dr  PTO Accrual (2290)                   $Y
Cr  Cash / Wages Payable (2100)              $Y
```

For kontor: the `kontor.payroll-us-adp.accrual/asc-710-pto-tx-
data` step in the C3 process produces these postings against
`:ledger/framework :us-gaap` only. The ADP GLI file already
contains the actual PTO-paid postings; the accrual delta is
the customer-side balance-sheet adjustment that kontor
calculates from `:employment/pto-balance-hours` (companion-side
schema, deferred to C4 per note 79 §3 deferred list — for C3
we expose the **shape** and let the customer transact the
accrual manually using the `kontor.process` orchestrator).

**Tax-side timing (IRC §461(h)).** The tax ledger does NOT
accrue PTO. Under the economic-performance test, vacation
liability is deductible only when the absence is taken (or in
limited "recurring-item" exception cases when paid within 8.5
months of year-end). The tax ledger sees only the actual
PTO-paid postings; the accrual is a book-tax temporary
difference that the customer's tax-prep engine handles.

### 6.2 — 401(k) employer match accrual (book ledger)

**Book treatment.** The employer-match wage event is **earned
when the wages are earned**. ASC 715 / ASC 710 / Wiley GAAP
treatment: accrue the match per-pay-period as wages are paid;
the contra-account is `2210 401(k) Employer Match Payable`.

**Tax treatment (IRC §404(a)(6)).** The deduction is allowed
**when the contribution is actually made**, with a critical
grace period: contributions paid not later than the **due
date of the employer's tax return (including extensions)** are
**deemed made on the last day of the preceding tax year**.

- Cash-basis or accrual-basis employer can use the grace period.
- For 401(k) **matching** contributions specifically (per the
  IRS 2023 Issue Snapshot and the Groom Law Group analysis):
  the match must be **on account of employee deferrals from
  compensation earned during the tax year**. An employer
  cannot make a match in March of year+1 on deferrals from
  compensation earned in year+1 and deduct it in year.
- Grace period is roughly **8.5 months** (Form 1120 / 1065
  March 15 due date + 6 months extension = September 15;
  Schedule C / Form 1040 April 15 due date + 6 months extension
  = October 15).
- The contribution must be **treated by the plan as having
  been made on the last day of the preceding tax year** (so
  the trustee must record it as a year-N contribution, not a
  year-N+1 contribution).
- Allocation timing has a stricter rule: contributions must be
  paid within 30 days after the §404(a)(6) period for
  allocation; for deduction the only rule is the §404(a)(6)
  period itself.

**For kontor.** The book ledger accrues the match per pay-
period. The tax ledger:

- If the match is paid by the §404(a)(6) deadline + meets the
  "on account of" rule + the plan treats it as a prior-year
  contribution: the tax ledger ALSO recognizes the deduction
  in the prior year. **Book = tax in this happy case.**
- If the match is paid after the §404(a)(6) deadline OR the
  plan treats it as a current-year contribution OR it's a
  discretionary match made after year-end on current-year
  service: the tax ledger defers to the year the contribution
  hits. **Book ≠ tax; the difference is a deferred-tax-
  liability adjustment the consumer's tax-prep engine
  handles.**

The C3 adapter writes book-ledger postings for the accrual at
each pay-period (per the `:irc-404a6?` flag on the wage-type-
map row). The tax-ledger treatment is a **late-cycle
adjustment** the consumer-side process must explicitly request
(documented in the C3 README, not auto-emitted). This matches
the ADR-021 parallel-ledger pattern: substrate ships the dual-
post primitive; the choice of which postings to dual-post is
business-policy.

### 6.3 — Roll-up to ADR-021's parallel-ledger story

ADR-021's `:ledger/framework` keyword discriminates `:us-gaap`
from `:us-tax` (and from `:hgb`, `:ifrs`, etc.). The C3 adapter
writes:

- **Always** to `:us-gaap` ledger (or whatever ledger the
  consumer has named for book purposes).
- **Always** to `:us-tax` ledger for the wage-expense lines
  (because book = tax for cash-basis wages once paid).
- **Conditionally** to `:us-gaap` only for the ASC 710 PTO
  accrual delta.
- **Conditionally** to `:us-tax` for the 401(k) match when
  the §404(a)(6) conditions are met (consumer-controlled
  config: `:tax-recognition-policy :on-accrual-when-eligible`
  vs `:on-cash`).

The book-vs-tax delta is one of note 73's "5 pains kontor
uniquely fits" — most US payroll systems don't expose this
parallel-ledger surface at all; book and tax are reconciled
manually at year-end. kontor's structural answer is one of
its load-bearing pitches.

---

## §7 — PayrollEmitProvider scope for US

Per note 79 §5.2: "no US clearance regime; the `Localfile
EmitProvider` default suffices." This section pins down the
scope so the impl agent doesn't accidentally over-build.

### 7.1 — What kontor does NOT emit

- **Federal Form 941** (quarterly employer's federal tax
  return). ADP files this directly.
- **Federal Form 940** (annual FUTA). ADP files this directly.
- **W-2 / W-3** (employee year-end + transmittal). ADP files
  this directly via SSA's BSO portal.
- **State withholding returns** (CA DE-9, NY NYS-1, TX C-3,
  etc.). ADP files these or the customer files via the state
  portal.
- **State UI quarterly returns**. ADP files via state SUTA
  portal.
- **1099 / 1099-NEC for contractors** — if the customer pays
  contractors through ADP, ADP issues 1099s. If through other
  channels, the customer files. Out of payroll-adapter scope.

### 7.2 — What kontor DOES emit (the `LocalfileEmitProvider`)

- **`:audit-doc/category :payroll`** entries for each imported
  GLI file (per-pay-period, per-company-code). `:audit-doc/
  content-hash` (per ADR-038) of the GLI file's bytes; the
  audit-doc is the customer-side proof-of-import.
- **`:audit-doc/category :tax-filing`** stubs (per note 79
  §2.5 category extension) for the per-period filings the
  customer's process should track outside kontor — placeholder
  for ADP's filing confirmation if the customer wants to
  attach the ADP-supplied confirmation PDF.
- **`:payroll-event` rows** as record-only entries (not
  transmitted) for each pay-period: `:payroll-event/kind
  :pay-period-imported`, `:payroll-event/source-file` ref,
  `:payroll-event/period-end-date`.

That's it. No transmissions, no certs, no endpoint URLs. ADP
handles the actual government-facing side.

### 7.3 — W-2 reconciliation surface (the data-prep)

This is the one substantive emit-adjacent surface. Per note
73 Theme F P2: "Multi-jurisdiction wage reporting (W-2 box 16
state, box 18 local) doesn't reconcile with year-totals." The
kontor pitch: per-employee year-to-date totals derived from
the posting log, broken out by W-2 box.

The mapping rules (from IRS Pub 15 + the year-end reconciliation
worksheet at `irs.gov/pub/irs-tege/year-end_reconciliation_
worksheet.pdf`):

- **Box 1 (Federal wages, tips, other comp.) = Gross wages -
  pre-tax 401(k) deferral - pre-tax Section 125 (health, FSA,
  dep-care FSA) - pre-tax HSA.** Roth 401(k) does NOT reduce
  Box 1.
- **Box 3 (Social Security wages) = Gross wages - Section 125
  - HSA**, capped at the annual SS wage base (e.g., $168,600
  for 2024). 401(k) deferral does NOT reduce Box 3.
- **Box 5 (Medicare wages) = Gross wages - Section 125 - HSA**,
  no cap. 401(k) deferral does NOT reduce Box 5.
- **Box 4 (SS tax withheld) = Box 3 × 6.2%**.
- **Box 6 (Medicare tax withheld) = Box 5 × 1.45% + Additional
  Medicare 0.9% on Box 5 over $200,000**.
- **Box 12 codes:**
  - **D** — 401(k) traditional pre-tax deferral
  - **AA** — Roth 401(k) deferral
  - **DD** — Cost of employer-sponsored health coverage
  - **W** — Employer HSA contribution (+ employee HSA via §125
    cafeteria plan)
  - **C** — Group-term life insurance over $50,000 (imputed)
  - **E / BB** — 403(b) traditional / Roth (not common in
    private sector)
- **Box 14** — free-text employer codes; commonly union dues,
  state-disability-insurance (CA SDI, NJ SDI, NY SDI),
  uniform allowance, after-tax HSA.
- **Box 16 (State wages)** = state-allocated Box 1 (per the
  `:analytic-account/state` distribution per §4).
- **Box 17 (State income tax)** = sum of EE state-withholding
  postings per state.
- **Box 18 (Local wages) / Box 19 (Local tax)** = sum of EE
  local-withholding postings per locality.

The report-definition (`kontor.payroll-us-adp.w2-recon/year-to-
date-by-employee`) walks the posting log + the wage-type-map's
`:w2-box` + `:w2-code` + `:section-125?` flags + the §4 state
analytic distributions and produces a per-employee structure:

```clojure
{:employee-id "E1234"
 :year 2026
 :w2-box-1 78400.00M
 :w2-box-3 89200.00M     ; gross less §125 (10,800 health)
 :w2-box-5 89200.00M
 :w2-box-4  5530.40M
 :w2-box-6  1293.40M
 :w2-box-12 {"D"  20000.00M       ; 401(k) deferral
             "DD" 14400.00M       ; employer health cost
             "W"   3850.00M}      ; HSA
 :w2-box-16 {:CA 78400.00M}        ; single-state employee
 :w2-box-17 {:CA  4760.00M}}
```

Year-end: the customer cross-checks this against ADP's
generated W-2 and resolves any reconciliation deltas. The
report does NOT generate the W-2 form itself.

---

## §8 — Multi-state edge cases (the test catalogue)

The end-to-end scenarios C3 must exercise. Each is a kontor
test (`modules/payroll-us-adp/test/kontor/payroll_us_adp/
multi_state_scenarios_test.clj`) — one per scenario.

### 8.1 — Simple single-state

- Employee in Austin, TX. TX has no state income tax.
- ADP GLI carries `EE FEDERAL TAX`, `EE SOCIAL SECURITY`,
  `EE MEDICARE` deductions; no state-tax line; SUTA at TX
  rate.
- All wage postings attach `:state "TX"` analytic
  distribution.
- `wages-by-state` report shows 100% TX.

### 8.2 — NY-NJ commuter (no convenience-rule complication)

- Employee lives in NJ, works in NY office 5 days/week.
- NY withholds (work-state). NJ allows credit for NY tax paid;
  employee files NJ-1040 with credit.
- ADP GLI: `EE FEDERAL TAX`, `EE NY TAX`, `EE NJ SDI` (NJ
  disability), no NJ income-tax withholding (NY-NJ doesn't
  have full reciprocity; NJ allows the credit instead).
- kontor: 100% `:state "NY"` allocation on wage postings.
- Edge: if employee starts working 2 days/week from NJ home,
  see 8.5 (convenience-rule).

### 8.3 — PA-NJ reciprocity

- Employee lives in PA, works in NJ (or vice versa). PA-NJ
  have a reciprocity agreement (one of the rare full bilateral
  ones).
- Employee files form NJ-165 (residency declaration);
  employer withholds for residence state only.
- ADP GLI: `EE PA TAX` only (if employee is PA resident),
  no NJ tax.
- kontor: 100% `:state "NJ"` allocation on wage-expense
  postings (work state for expense reporting); but EE
  withholding is to `2130-PA` not `2130-NJ`. **The work
  state ≠ withholding state distinction is real.**

### 8.4 — Multi-state remote: CA primary + CO quarterly site visit

- Full-time CA employee spends two weeks per quarter
  on-site in CO (Denver office).
- Whether CO requires withholding depends on day-count
  thresholds (CO is roughly the "any work-day creates CO
  withholding" jurisdiction; some states have higher
  thresholds — IL has a 30-day threshold; OH has 20).
- ADP customer typically: payroll engine prorates the
  in-CO weeks → CO state withholding; the rest stays CA.
- ADP GLI: `EE CA TAX`, `EE CO TAX` per-pay-period during
  visit weeks.
- kontor: wage-expense postings carry `:state "CA" 92%,
  :state "CO" 8%` analytic distribution (or similar; the
  consumer-supplied allocator decides the percentage based
  on day-count).

### 8.5 — NY convenience-rule (the load-bearing US edge case)

- Employee lives in CT, hired by NY-based company, works from
  CT home 5 days/week. The NY convenience-of-the-employer rule
  (upheld by NY Tax Appeals Tribunal in May 2025 re: Zelinsky
  case) sources the wages to NY even though employee is in CT.
- Employee owes NY tax; CT allows credit for NY tax paid.
- ADP customer typically configures NY withholding.
- ADP GLI: `EE NY TAX` (no CT line).
- kontor: 100% `:state "NY"` (work-state-fiction per
  convenience rule). The customer-side allocator captures the
  convenience-rule policy; substrate doesn't enforce.

### 8.6 — Fully remote US LLC with 15 states

- Hypothetical SaaS LLC headquartered in DE, no physical
  office; 15 engineers each in a different state (CA, TX, NY,
  FL, IL, MA, WA, CO, GA, NC, VA, OH, PA, AZ, NJ).
- ADP files quarterly returns in all 15 states + DE (state of
  incorporation).
- ADP GLI: per-pay-period file lists all employees' wages
  with per-state tax lines.
- kontor: 1 `:posting/entity` (the DE-incorporated LLC),
  15 `:analytic-account/state` distributions across
  postings. `wages-by-state` report → 15 rows. SUTA-payable
  liability → 15 sub-accounts (`2165-CA`, `2165-TX`, etc.).

### 8.7 — Mid-year state transition

- Employee starts year in CA, relocates to TX on August 15.
- Through Aug 14: full CA withholding. Aug 15 onward: no state
  tax (TX has none), but federal + FICA unchanged.
- ADP handles via mid-year state-tax-setup change.
- ADP GLI: `EE CA TAX` lines stop in mid-August.
- kontor: bitemporal `:employment/state` (consumer-side
  schema or via analytic-distribution timeline) reflects the
  Aug 15 switch. Per ADR-008 + the bitemporal substrate, a
  "wages by state as of 2026-06-30" query returns CA-only;
  "as of 2026-12-31" returns split.

### 8.8 — Reciprocal-agreement state pair (OH-IN)

- Employee lives in IN, works in OH. OH-IN reciprocity:
  employer withholds IN tax only.
- ADP GLI: `EE IN TAX` only.
- kontor: 100% `:state "OH"` on wage-expense (work state);
  withholding to `2130-IN`. Same pattern as 8.3.

### 8.9 — Local tax (PA PSD, OH RITA, NYC)

- Employee in Philadelphia, PA. Owes PA state + Philadelphia
  city wage tax (3.75% resident / 3.44% non-resident
  approximately).
- ADP GLI: `EE PA TAX`, `EE PHILADELPHIA TAX` (or
  `EE LOCAL TAX` with locality in description).
- kontor: route `EE PHILADELPHIA TAX` to `2140-PA-PHILA` per
  the consumer's wage-type-map regex.

### 8.10 — Multi-LLC (out-of-C3-scope but documented)

- Acme has Acme-Operating-LLC + Acme-IP-Holding-LLC. Each is
  a separate `:posting/entity`. Each has its own ADP payroll
  (or shares one ADP account with multi-company-code).
- ADP emits two GLI files (one per company-code) or one file
  with column-1 `Client Code` distinguishing.
- kontor: distinct `:posting/entity` per file; per-entity
  sum-to-zero invariant enforced.

The C3 test suite ships scenarios **8.1, 8.2, 8.3, 8.4, 8.6,
8.7** as table-driven tests. **8.5 (convenience-rule),
8.8 (OH-IN reciprocity), 8.9 (local tax), 8.10 (multi-LLC)**
ship as documentation references — the underlying substrate
already handles them but doesn't need a dedicated test in C3.

---

## §9 — Concrete impl recommendations for C3

### 9.1 — File tree

```
modules/payroll-us-adp/
  deps.edn                                  ; deps on kontor + kontor-hr + l10n-us
  README.md                                 ; ADP-focused, license posture, install steps
  src/kontor/payroll_us_adp/
    core.clj                                ; install! — registers providers, loads default
                                            ; state analytic plan
    schema.clj                              ; module-local schema additions (the
                                            ; :payroll-event/*, :payroll-import/* rows;
                                            ; companion-side analytic accounts for states)
    csv.clj                                 ; the GLI CSV parser (config-driven)
    wage_type_map.clj                       ; load + validate the consumer-supplied
                                            ; description-rules
    compute_provider.clj                    ; AdpGliComputeProvider impl —
                                            ; parses GLI + produces PayrollFacts
    posting_builder.clj                     ; PayrollPostingBuilder impl — maps
                                            ; PayrollFacts → :posting via wage-type-map
                                            ; + :state analytic distribution
    emit_provider.clj                       ; LocalfileEmitProvider — writes the
                                            ; :audit-doc rows + :payroll-event rows
    accrual.clj                             ; ASC 710 PTO accrual helper +
                                            ; 401(k) employer match accrual helper
                                            ; (parallel-ledger aware)
    w2_recon.clj                            ; year-to-date per-employee report w/
                                            ; W-2 box mapping
    states.clj                              ; data — 50 states + DC + 5 territories
                                            ; ISO-3166-2 codes + reciprocity edges
  resources/kontor/payroll_us_adp/
    state-analytic-plan.edn                 ; the `:state` analytic plan + 56 accounts
    wage-type-map-reference.edn             ; reference fixture — consumer copies/edits
    fixtures/
      gli-acme-single-state-tx.csv          ; §8.1 fixture
      gli-acme-ny-nj.csv                    ; §8.2 fixture
      gli-acme-pa-nj-reciprocity.csv        ; §8.3 fixture
      gli-acme-ca-co-traveler.csv           ; §8.4 fixture
      gli-acme-15-state-remote.csv          ; §8.6 fixture (largest)
      gli-acme-mid-year-relocate.csv        ; §8.7 fixture (two pay-periods)
  test/kontor/payroll_us_adp/
    csv_test.clj                            ; parser shape + sum-to-zero invariant
    wage_type_map_test.clj                  ; regex matching + role assignment
    compute_provider_test.clj               ; PayrollFacts shape
    posting_builder_test.clj                ; per-state allocation; per-ledger
                                            ; HGB-vs-tax dual-post for 401(k) match
    multi_state_scenarios_test.clj          ; §8.1, 8.2, 8.3, 8.4, 8.6, 8.7
    w2_recon_test.clj                       ; year-end Box 1/3/5/12 reconciliation
    accrual_test.clj                        ; ASC 710 + 401(k) match parallel-ledger
    end_to_end_test.clj                     ; full C3 acceptance — DE LLC, 3 employees,
                                            ; 3 states, monthly payroll, ASC 710 +
                                            ; 401(k) match accrued, W-2 YTD report
                                            ; reconciles by state
```

### 9.2 — The wage-type mapping data shape (EDN literal sketch)

Reference fixture, shipped at `resources/kontor/payroll_us_adp/
wage-type-map-reference.edn`. Consumer copies + edits:

```clojure
{:vendor :adp
 :csv-format
 {:columns
  [{:idx 0 :role :client-code}
   {:idx 1 :role :gl-account            :required true}
   {:idx 2 :role :journal-source-code}
   {:idx 3 :role :date :format "MMddyyyy"}
   {:idx 4 :role :amount-signed :credit-sign :negative}
   {:idx 5 :role :reference-1}
   {:idx 6 :role :description}
   {:idx 7 :role :reference-2}
   {:idx 8 :role :reference-3}
   {:idx 9 :role :record-code}]
  :has-header false
  :encoding :ascii
  :delimiter \,
  :quote \"
  :balance-check :sum-to-zero}

 :reference-mappings
 [{:column :reference-2 :plan "cost-center" :role :cost-center}
  {:column :reference-3 :plan "state"        :role :state}]

 :description-rules
 [{:match #"^GROSS$"               :role :wage-expense
   :account "5010"
   :ledgers #{:us-gaap :us-tax}}

  {:match #"^OVERTIME$"            :role :wage-expense
   :account "5010"
   :ledgers #{:us-gaap :us-tax}}

  {:match #"^BONUS$"               :role :wage-expense
   :account "5010"
   :ledgers #{:us-gaap :us-tax}
   :w2-supplemental? true}

  {:match #"^PTO PAID$"            :role :pto-paid
   :account "5010"
   :ledgers #{:us-gaap :us-tax}
   :reverses-accrual :asc-710-pto}

  {:match #"^ER SOCIAL SECURITY$"  :role :er-fica-ss
   :account "5200"
   :ledgers #{:us-gaap :us-tax}}

  {:match #"^ER MEDICARE$"         :role :er-fica-medicare
   :account "5210"
   :ledgers #{:us-gaap :us-tax}}

  {:match #"^FUTA TAX$"            :role :er-futa
   :account "5220"
   :ledgers #{:us-gaap :us-tax}}

  {:match #"^([A-Z]{2}) SUI$"      :role :er-suta
   :account "5230"
   :ledgers #{:us-gaap :us-tax}
   :state-from-group 1}

  {:match #"^ER 401K MATCH$"       :role :er-401k-match
   :account "5310"
   :contra-account "2210"
   :ledgers #{:us-gaap}            ; tax ledger lands per §404(a)(6) policy
   :tax-recognition-policy :on-cash-default
   :irc-404a6? true}

  {:match #"^ER HEALTH$"           :role :er-health
   :account "5300"
   :ledgers #{:us-gaap :us-tax}
   :w2-box "12" :w2-code "DD"}

  {:match #"^EE FEDERAL TAX$"      :role :ee-fed-withheld
   :account "2110"
   :ledgers #{:us-gaap :us-tax}}

  {:match #"^([A-Z]{2}) TAX$"      :role :ee-state-withheld
   :account "2130"                 ; or per-state segment e.g. "2130-CA"
   :ledgers #{:us-gaap :us-tax}
   :state-from-group 1
   :w2-box "17"}

  {:match #"^EE SOCIAL SECURITY$"  :role :ee-fica-ss
   :account "2115"
   :ledgers #{:us-gaap :us-tax}
   :w2-box "4"}

  {:match #"^EE MEDICARE$"         :role :ee-fica-medicare
   :account "2120"
   :ledgers #{:us-gaap :us-tax}
   :w2-box "6"}

  {:match #"^EE 401K$"             :role :ee-401k-deferral
   :account "2200"
   :ledgers #{:us-gaap :us-tax}
   :w2-box "12" :w2-code "D"
   :reduces-box-1? true}

  {:match #"^EE ROTH 401K$"        :role :ee-roth-deferral
   :account "2205"
   :ledgers #{:us-gaap :us-tax}
   :w2-box "12" :w2-code "AA"
   :reduces-box-1? false}

  {:match #"^EE HEALTH$"           :role :ee-section125
   :account "2240"
   :ledgers #{:us-gaap :us-tax}
   :section-125? true
   :reduces-box-1? true
   :reduces-box-3? true
   :reduces-box-5? true}

  {:match #"^EE HSA$"              :role :ee-hsa
   :account "2220"
   :ledgers #{:us-gaap :us-tax}
   :w2-box "12" :w2-code "W"
   :reduces-box-1? true
   :reduces-box-3? true             ; if via §125 cafeteria plan
   :reduces-box-5? true}

  {:match #"^EE FSA$"              :role :ee-fsa
   :account "2225"
   :ledgers #{:us-gaap :us-tax}
   :section-125? true
   :reduces-box-1? true
   :reduces-box-3? true
   :reduces-box-5? true}

  {:match #"^EE DEP CARE FSA$"     :role :ee-dep-care-fsa
   :account "2230"
   :ledgers #{:us-gaap :us-tax}
   :w2-box "10"
   :reduces-box-1? true}

  {:match #"^GARNISHMENT$"         :role :garnishment
   :account "2250"
   :ledgers #{:us-gaap :us-tax}}

  {:match #"^CHILD SUPPORT$"       :role :child-support
   :account "2260"
   :ledgers #{:us-gaap :us-tax}}

  {:match #"^NET PAY$"             :role :net-pay-liability
   :account "2100"
   :ledgers #{:us-gaap :us-tax}}

  ;; Catch-all: any unmatched description gets logged + routed
  ;; to a customer-supplied :unmapped-suspense account so the
  ;; parser never silently drops a row.
  {:match #".*"                    :role :unmapped
   :account "1999"
   :ledgers #{:us-gaap :us-tax}
   :flag-for-review? true}]}
```

The `:reduces-box-1?` / `:reduces-box-3?` / `:reduces-box-5?`
flags drive the W-2 reconciliation report in §7. The
`:ledgers #{:us-gaap :us-tax}` set drives the parallel-ledger
dual-post per ADR-021. The `:tax-recognition-policy` consumer
override on the 401(k) match line is the §6.2 hinge.

### 9.3 — Multi-state-employees test fixture shape

The largest fixture (`gli-acme-15-state-remote.csv`) — 15
employees, one per state, monthly pay-period. Simplified
sketch (real fixture has 15 × ~10 GLI rows ≈ 150 lines):

```
"AC001","5010","PR","04302026","8500.00","E101","GROSS","ENG","CA","02"
"AC001","2110","PR","04302026","-1500.00","E101","EE FEDERAL TAX","ENG","CA","02"
"AC001","2130","PR","04302026","-680.00","E101","CA TAX","ENG","CA","02"
"AC001","2115","PR","04302026","-527.00","E101","EE SOCIAL SECURITY","ENG","CA","02"
"AC001","2120","PR","04302026","-123.25","E101","EE MEDICARE","ENG","CA","02"
"AC001","5200","PR","04302026","527.00","E101","ER SOCIAL SECURITY","ENG","CA","02"
"AC001","5210","PR","04302026","123.25","E101","ER MEDICARE","ENG","CA","02"
"AC001","5220","PR","04302026","51.00","E101","FUTA TAX","ENG","CA","02"
"AC001","5230","PR","04302026","221.00","E101","CA SUI","ENG","CA","02"
"AC001","2100","PR","04302026","-5669.75","E101","NET PAY","ENG","CA","02"
"AC001","5010","PR","04302026","9200.00","E102","GROSS","ENG","TX","02"
...
;; (zero-state-tax row for TX — no "TX TAX" line, just no state-withholding)
...
```

Tests assert:

- File sum-to-zero invariant holds (parser reject if not).
- 15 distinct `:state` analytic-account values appear in
  postings.
- `wages-by-state` report returns 15 rows summing to gross.
- Per-state SUTA payable equals the sum of per-employee
  `<STATE> SUI` rows.
- W-2 YTD report (after 12 monthly fixtures) produces 15
  per-employee structures with correct Box 1/3/5/12 totals.

### 9.4 — Known gotchas

1. **The "balancing row" trap.** ADP's GLI files include a
   sum-to-zero balancing row with empty `GL Account Number`.
   The parser MUST handle this row gracefully (route to a
   designated `:account "9999-suspense"` or skip it after
   verifying it makes the file balance). **Do not transact the
   balancing row as a posting** — it's a file-format artifact,
   not a real posting.
2. **Description-text vocabulary drift.** Real customer ADP
   installations have non-canonical description text — `"GROSS
   WAGES"` instead of `"GROSS"`, `"FICA EE SS"` instead of
   `"EE SOCIAL SECURITY"`, `"401K EE"` instead of `"EE 401K"`.
   The regex rules MUST be customer-overridable; the reference
   fixture is a starting point. The catch-all `#".*"` → `:flag-
   for-review?` rule is essential.
3. **Account-number segmentation.** Customers using segmented
   CoAs (`5010-100-CA` for "wage expense, operating dept, CA")
   embed the segments in column 2. The wage-type-map can
   either (a) match the full segmented number directly, or (b)
   apply a regex to split the segment for analytic-account
   inference. Either works; the fixture should demonstrate (b)
   to keep the consumer's CoA changes minimal.
4. **Per-state SUTA wage-base caps.** Each state has its own
   SUTA wage base ($7K Florida → $66K Washington range, 2024
   numbers). ADP handles the cap math; kontor just consumes
   the result. **Do not validate that the `<STATE> SUI` amount
   per employee per year falls under the wage base.** That's
   ADP's job; trying to re-derive will fail in edge cases
   (mid-year state transitions, multi-employer wage-base
   sharing).
5. **Mid-period wage changes vs ADP's averaging.** ADP
   typically calculates per-pay-period without retroactive
   per-day rates. If the customer changes a wage rate mid-pay-
   period, ADP usually applies the new rate to the entire next
   pay-period (not the partial current period). The bitemporal
   `:employment/wage` or `:compensation` (per note 81 §9.6)
   should be set with `:db.valid/from` matching the **next pay
   period start**, not the actual change date. Document this
   in the README.
6. **The `:section-125?` flag and Roth.** Section 125 cafeteria
   plan deductions reduce Boxes 1, 3, AND 5. 401(k) traditional
   deferral reduces Box 1 only. Roth 401(k) reduces none. HSA
   reduces Box 1; if through §125 also Box 3 and Box 5. The
   wage-type-map MUST distinguish; the reference fixture above
   sets the correct flags. **Confusing these is the most
   common payroll-bookkeeping error** per the IRS year-end
   reconciliation worksheet commentary.
7. **Additional Medicare 0.9%.** Triggers on wages over
   $200,000 per employee per year (single threshold;
   joint-filing threshold is $250,000 but withholding only
   considers the $200,000 single-employee threshold). ADP
   computes; kontor consumes. The W-2 reconciliation report
   should add the Additional Medicare withheld to Box 6 totals.
8. **State-local-tax descriptions are non-uniform.** Ohio
   municipal taxes might appear as `OH RITA`, `RITA`,
   `CINCINNATI TAX`, or just `LOCAL TAX` with the locality
   in a reference column. Pennsylvania PSD codes are a
   4-digit numeric identifier that may or may not appear in
   description vs reference. Customers in OH / PA / IN / KY
   / NY (NYC) / MD / MI need locality-specific regex rules.
   Defer to the consumer-supplied wage-type-map.
9. **ADP's `EE STATE TAX` vs `<STATE> TAX`.** Some installs
   emit `EE STATE TAX` with the state in reference-1 or
   reference-2; others emit `CA TAX`, `NY TAX`, `OH TAX`.
   The reference fixture handles both via `#"^EE STATE TAX$"`
   (state from reference column) and `#"^([A-Z]{2}) TAX$"`
   (state from regex capture).
10. **PEO-via-Justworks confusion.** If a customer's books
    show ADP and Justworks — they're using ADP for some entity
    and Justworks for another. Don't blindly parse Justworks-
    issued GLIs as ADP. Module `kontor-payroll-us-justworks`
    is a separate (deferred) consumer module.

---

## §10 — Open questions

1. **Wage-type-map first-class schema?** Currently §9.2 has the
   mapping as consumer-supplied EDN read at install time, not
   transacted into the DB. Pro: lightweight, no schema burden,
   the customer's source-of-truth is their config file. Con:
   no bitemporal history of mapping changes; if the customer
   updates the mapping mid-year, downstream W-2 reconciliation
   may use a different mapping than the original posting. Open:
   should we transact the mapping as `:payroll-wage-type-rule`
   entities with `:db.valid/from` bitemporal stamps? Recommend:
   **defer to C3 ship; revisit only if customer reports the
   inconsistency.**
2. **`:employment/work-state` vs analytic-distribution.**
   Note 81 §10 item 5 calls out "multiple work locations per
   employment" as a deferred gap. For C3, do we need an
   `:employment/work-state` (single-cardinality, current state)
   OR `:employment/work-state-history` (bitemporal, per-(date,
   state, fraction))? The analytic-distribution on postings
   captures the per-pay-period reality; the employment-side
   attribute would capture the standing assignment. Recommend:
   **for C3, allocation lives on postings only; revisit when
   simmis simulation needs per-employment forward-projection.**
3. **401(k) employer match: who runs the §404(a)(6)
   eligibility check?** kontor's `:tax-recognition-policy
   :on-cash-default` punts the call to the consumer. Should
   substrate ship a helper that, given (employer-tax-year-end,
   actual-contribution-date, plan-document-treatment), returns
   "deductible in year N or N+1"? Recommend: **defer; the
   call has plan-document-specific inputs kontor can't see.
   The customer's tax-prep engine answers this.**
4. **Localfile audit-doc storage location.** ADR-038 leaves
   `:audit-doc/storage-uri` consumer-supplied. For C3 the
   emit-provider needs a default — a relative path under the
   datahike-DB's directory? A file:// URL? An S3 bucket? Open
   for the impl agent; suggest the simplest: a `:storage-uri`
   string the consumer passes to `install!`.
5. **PTO accrual data path.** kontor's companion-side `:pto-
   balance-hours` is deferred to C4 per note 79 §3. For C3,
   ASC 710 accrual is a "primitive shape, consumer-driven"
   surface — the customer transacts the accrual delta manually
   via `kontor.process.run-process`. Is this acceptable for C3
   shipping? Recommend: **yes; the alternative (build the
   `:employment/pto-policy` machinery + `kontor.schedule`
   integration in C3) doubles C3's scope. Document the
   workaround in the README.**
6. **W-2 reconciliation report — pre-tax vs post-tax timing
   of mid-year 401(k) contribution-limit hit.** The 2024 limit
   is $23,000 ($30,500 with catch-up over 50). If an employee
   hits the limit in October, subsequent paychecks have no
   401(k) deferral. The W-2 Box 12-D total must equal the YTD
   `:ee-401k-deferral` postings sum (which already reflects
   the cap because ADP enforced it). No special handling
   needed; the open question is whether to validate the cap
   ourselves as a sanity-check. Recommend: **skip; ADP's
   computation is authoritative.**
7. **Reciprocity-agreement matrix in `states.clj`?** §8.3
   and §8.8 reference reciprocity. Do we ship a static map
   of (resident-state, work-state) → withholding-state, or
   leave it consumer-supplied? Recommend: **ship the matrix as
   reference data in `states.clj` based on the 2025 state
   agreements (citable per §11). Mark it as informational —
   the actual withholding decision is ADP's; we use the matrix
   only for the `wages-by-state` report's "this looks unusual"
   sanity-check.**

---

## §11 — Sources

All URLs accessed 2026-05-18 unless noted.

**ADP General Ledger Interface (canonical 10-column spec):**

- Microsoft Learn, "Payroll Connect for Dynamics GP" —
  `learn.microsoft.com/en-us/dynamics-gp/payroll/payrollconnect`
  (the most exhaustive public spec: the 10-column table, the
  canonical sample fixture, the truncation rules, the
  CSV/ASCII format declaration). This single page is the
  load-bearing reference for §2.
- ADP RUN General Ledger Mapping infographic —
  `support.adp.com/adp_payroll/content/hybrid/GL/Online-
  Infographic-GL-Mapping.pdf` (image-rendered; structurally
  confirms the three sections — Cash/Asset, Payroll Expenses,
  Payroll Liabilities).
- ADP RUN General Ledger & QuickBooks Online guide —
  `support.adp.com/adp_payroll/content/hybrid/GL/RUN_GL_
  Guide_QBO.pdf` (image-rendered; documents the QBO-bound
  CSV flavor).
- ADP General Ledger Interface Update Account Mapping
  quick-reference (via Course Hero 228342942) —
  `coursehero.com/file/228342942/GLI-UpdateAccountMapping-
  QuickReferencepdf/`.
- ADP InfoLink G/L Interface portal —
  `glinterface.adp.com/logout.html`.
- ADP Workforce Now product page —
  `adp.com/what-we-offer/products/adp-workforce-now.aspx`.
- ADP General Ledger Documents API guide for marketplace —
  `developers.adp.com/guides/api-guides/general-ledger-
  documents-api-guide-for-marketplace`.
- ADP Workforce Now CSV report download —
  `otcdc1.adp.com/ezlmhelp/v18_30/wfn/general/standardui/en-
  us/as_help/reports/reports-downloading.htm`.
- ADP Payroll Ledger article —
  `adp.com/resources/articles-and-insights/articles/p/payroll-
  ledger.aspx`.
- ADP Multi-State Payroll how-to —
  `adp.com/resources/articles-and-insights/articles/m/multi-
  state-payroll-processing.aspx`.

**Third-party GLI documentation (confirms the 10-column shape):**

- Juris Interface to ADP — `juris.com/help/juris/Content/
  Topics/Juris/10-Transactions/05-Journal%20entries%20-
  %20GENERAL-LEDGER/create-ADP-journal-entry-import-batch-
  hdi.htm` (10-column A-J detailed spec for the Juris
  legal-billing import).
- Juris ADP Interface utility reference —
  `juris.com/help/Juris/26/Content/JurisHelp/Utilities/ADP_
  Interface.htm`.
- LexisNexis Juris support: ".gli" file extension —
  `supportcenter.lexisnexis.com/app/answers/answer_view/a_
  id/1074944/~/juris-interface-to-adp-(automatic-data-
  processing)`.
- DataPro Accounting — ADP-to-GL Integration User Manual —
  `dataproaccounting.com/wp-content/uploads/2024/08/
  ADPtoGLIntegration-User-Manual.pdf`.
- Sage 50 community: GL Interface from ADP —
  `communityhub.sage.com/ca/sage_50/f/general-discussion/
  104668/gl-interface-from-adp`.
- Sage 100 ERP: how to import ADP payroll —
  `erpvar.com/sage-100cloud-erp-consultant-blog/bid/97675/
  sage-100-erp-mas-90-how-to-import-adp-payroll-to-sage-100-
  gl`.
- Shoptech / E2 Shop System ADP Interface guide —
  `client.shoptech.com/faq/Accounting/Interfaces_and_Payroll/
  ADP/ADP_Interface.pdf`.
- Evosus ADP integration —
  `legacysupport.evosus.com/s/article/evosus-payroll-services-
  adp-integration`.
- Vintti ADP Workforce Now review —
  `vintti.com/blog/adp-workforce-now-review-a-look-at-its-
  accounting-and-payroll-features`.

**Alternative US payroll engines (§3):**

- Gusto General Ledger Mapper —
  `support.gusto.com/article/221020155120720/Download-mapped-
  payroll-ledgers-with-the-General-Ledger-Mapper`.
- Gusto Embedded Payroll: create a general ledger report —
  `docs.gusto.com/embedded-payroll/reference/post-payrolls-
  payroll_uuid-reports-general_ledger`.
- Gusto Embedded blog: payroll API capabilities —
  `embedded.gusto.com/blog/payroll-api-capabilities-payroll-
  data-management/`.
- Gusto Embedded blog: core concepts of payroll APIs —
  `embedded.gusto.com/blog/core-concepts-payroll-apis/`.
- Gusto Embedded: create a job and compensation —
  `docs.gusto.com/embedded-payroll/docs/create-a-job-and-
  compensation`.
- Paychex Flex General Ledger module — Church360° guide —
  `360ledger.zendesk.com/hc/en-us/articles/212002868-How-do-
  I-export-payroll-information-from-Paychex`.
- Paychex Flex custom reporting / data export —
  `eservices.paychex.com/secure/HRO_MMS/hro_common_tlostand_
  rpt_data_export.html`.
- Paychex QuickBooks Online integration —
  `paychex.com/newsroom/news-releases/paychex-introduces-
  quickbooks-integration`.
- Paychex General Ledger Reporting via Design Manager —
  `knowledge.designmanager.com/support/solutions/articles/
  22000203550-importing-a-paychex-payroll-file-from-the-
  paychex-general-ledger-reporting-service`.
- OnPay GL mapping by pay type —
  `help.onpay.com/hc/en-us/articles/360024911951-How-to-map-
  to-your-general-ledger-by-pay-type`.
- OnPay QuickBooks Online setup —
  `help.onpay.com/hc/en-us/articles/202194789-QuickBooks-
  Online-App-setup-guide`.
- OnPay Xero integration —
  `help.onpay.com/hc/en-us/articles/115002342711-Xero-
  integration-setup-and-pay-run-export`.
- OnPay payroll journal entry overview —
  `onpay.com/insights/payroll-journal-entry-overview/`.
- OnPay payroll accounting guide —
  `onpay.com/insights/basic-payroll-accounting-guide/`.
- Rippling making-the-switch payroll guide —
  `go.rippling.com/rs/345-FHM-674/images/rippling-making-the-
  smart-payroll-switch.pdf`.
- Rippling GL accounting integrations —
  `centricity-solutions.com/wp-content/uploads/Payroll_
  Rippling-General-Ledger-Accounting-Integrations.pdf`.
- Rippling payroll journal entry guide —
  `rippling.com/blog/payroll-journal-entry`.
- Justworks PEO & Payroll Solutions —
  `justworks.com/`.
- Justworks PEO onboarding guide —
  `help.justworks.com/hc/en-us/articles/39218430210331-
  Welcome-to-Justworks-PEO-Your-Onboarding-Guide`.
- Justworks Custom Payroll Report —
  `help.justworks.com/hc/en-us/articles/12377306911259-
  Custom-Payroll-Report`.
- IRS Third Party Payer / PEO arrangements —
  `irs.gov/government-entities/third-party-payer-arrangements-
  professional-employer-organizations`.

**Multi-state allocation (§4 + §8):**

- ADP Multi-State Payroll how-to —
  `adp.com/resources/articles-and-insights/articles/m/multi-
  state-payroll-processing.aspx`.
- Anders CPA: employer's guide to multi-state payroll —
  `anderscpa.com/learn/blog/employer-guide-multi-state-
  payroll-tax-withholding-remote-workers/`.
- LSL CPAs: multi-state payroll & remote work compliance —
  `lslcpas.com/navigating-multi-state-payroll-taxation-and-
  remote-work-compliance/`.
- Volpe Consulting: managing multi-state payroll taxes —
  `volpeconsulting-accounting.com/blog/multi-state-payroll-
  taxes/`.
- Workforce PayHub: payroll nexus —
  `workforcepayhub.com/blog/what-is-a-payroll-nexus`.
- Fusion Taxes: tax for remote workers + PEO + nexus —
  `fusiontaxes.com/thought-leadership/blog/tax-for-remote-
  workers-using-peos-and-income-tax-nexus/`.
- VantagePoint: payroll nexus essentials —
  `vantagepointbenefit.com/understanding-payroll-nexus-
  essential-information-requirements/`.
- Our Tax Partner: multi-state payroll compliance for
  remote workforce —
  `ourtaxpartner.com/multi-state-payroll-compliance-remote-
  workforce/`.
- Valor Payroll Solutions: how to tax remote employees —
  `valorpayrollsolutions.com/blog/how-to-tax-remote-
  employees-a-comprehensive-guide-for-multistate-workforces/`.
- Outsail: multi-state payroll tax compliance —
  `outsail.co/post/multi-state-payroll-tax-compliance-
  managing-remote-work`.

**Reciprocity matrix (§8.3 + §8.8):**

- Smart SMS Solutions: 2025 state tax reciprocity matrix —
  `smartsmssolutions.com/resources/blog/business/state-tax-
  reciprocity-agreements-payroll`.
- Check HQ: navigating multi-state tax complexity —
  `checkhq.com/resources/blog/what-is-reciprocity-managing-
  multi-state-tax-complexity-in-payroll`.
- TurboTax / Intuit: state reciprocal agreements —
  `ttlc.intuit.com/turbotax-support/en-us/help-article/state-
  taxes/states-reciprocal-agreements/L4JKSLqpR_US_en_US`.
- Patriot Software: reciprocal agreements by state —
  `patriotsoftware.com/blog/payroll/tax-reciprocity-between-
  states-agreement/`.
- Z-Tax: U.S. states with income tax reciprocity agreements —
  `ztaxonline.com/comprehensive-tax-payroll-accounting-
  bookeeping-services-in-all-us-states/u-s-states-with-
  income-tax-reciprocity-agreements`.
- Michigan Treasury: withholding reciprocity examples —
  `michigan.gov/taxes/business-taxes/payroll-service-
  providers/withholding-reciprocity-examples`.
- Symmetry: deeper look at reciprocity agreements —
  `symmetry.com/payroll-tax-insights/a-deeper-look-at-
  reciprocity-agreements`.
- Landrum HR: managing multi-state payroll —
  `landrumhr.com/blogs/managing-multi-state-payroll/`.
- Warp: state income tax withholding for remote workers
  2026 guide —
  `warp.co/blog/state-income-tax-withholding-remote-
  employees`.

**Convenience-of-the-employer rule (§8.5):**

- Benefits Law Advisor (Seyfarth Shaw): Remote Work
  Challenges after NY Tax Appeals Tribunal upholds
  Convenience Rule, July 2025 —
  `benefitslawadvisor.com/2025/07/articles/uncategorized/
  remote-work-challenges-after-new-york-tax-appeals-tribunal-
  upholds-income-tax-convenience-rule/`.
- Forvis Mazars: Zelinsky loses second NY Convenience
  challenge, July 2025 —
  `forvismazars.us/forsights/2025/7/professor-zelinsky-
  loses-second-convenience-of-the-employer-challenge-in-new-
  york`.
- Shay CPA: NY Convenience Rule guide for tech companies —
  `shaycpa.com/understanding-new-yorks-convenience-of-the-
  employer-rule-a-guide-for-tech-companies-with-remote-
  workforces/`.
- Fusion Taxes: NY remote workers tax nexus & compliance —
  `fusiontaxes.com/thought-leadership/blog/new-york-remote-
  worker-tax-nexus/`.
- Mosey: convenience of the employer rule and COE states —
  `mosey.com/blog/convenience-of-the-employer-rule-coe/`.
- Wipfli: understanding the convenience of the employer
  rule —
  `wipfli.com/insights/articles/tax-understanding-the-
  convenience-of-the-employer-rule`.
- Employment Law Worldview (Squire Patton Boggs):
  navigating the convenience rule —
  `employmentlawworldview.com/work-is-where-the-tax-is-
  navigating-the-convenience-of-the-employer-rule-us/`.
- SmartAsset: what is the convenience of the employer
  rule —
  `smartasset.com/taxes/convenience-of-the-employer-rule`.
- NJ Division of Taxation: convenience-rule FAQ —
  `nj.gov/treasury/taxation/conveniencerulefaq.shtml`.
- NY tax.ny.gov: withholding tax requirements —
  `tax.ny.gov/bus/wt/whtax_require.htm`.

**ASC 710 Compensated Absences (§6.1):**

- PwC Viewpoint, 6.4 Compensated Absences —
  `viewpoint.pwc.com/dt/us/en/pwc/accounting_guides/pensions-
  and-employee-benefitspeb/peb_guide/Chapter-6-PEB/64_
  Compensated_absences_8.html`.
- PwC Viewpoint, ASC 710-10-25 Recognition —
  `viewpoint.pwc.com/content/pwc-madison/ditaroot/us/en/fasb/
  GAAP/Codification/Codification/Codification/Expenses/71X_
  Compensation/Compensation_General/Overall/710-10-25.html`.
- Wiley GAAP 2020: ASC 710 Compensation General —
  `onlinelibrary.wiley.com/doi/10.1002/9781119652663.ch41`.
- Wiley GAAP 2023: Practitioner's Guide ASC 710 —
  `onlinelibrary.wiley.com/doi/10.1002/9781394152698.ch40`.
- FASB Statement No. 43 (superseded; historical PTO accrual
  spec) —
  `fasb.org/page/PageContent?pageId=%2Freference-library%2F
  superseded-standards%2Fsummary-of-statement-no-43.html`.
- Journal of Accountancy: vacation and sick day accruals
  during pandemic —
  `journalofaccountancy.com/news/2021/mar/vacation-sick-day-
  accruals-during-coronavirus-pandemic/`.
- Journal of Accountancy: vacation and sick pay accruals
  resulting from pandemic —
  `journalofaccountancy.com/issues/2021/jul/vacation-sick-
  pay-accruals-resulting-from-coronavirus-pandemic/`.
- Sweeney Conrad: holiday-season compensated absences —
  `blog.sweeneyconrad.com/bellevue-cpa-what-employers-need-
  to-know-about-compensated-absences-this-holiday-season`.
- Accounting Hub: accrued vacation US GAAP rules —
  `accountinghub-online.com/accounting-for-accrued-vacation-
  us-gaap-rules/`.
- FinOptimal: accrued vacation journal entry guide —
  `finoptimal.com/resources/accrued-vacation-journal-entry-
  guide`.
- The CFO Club: adjust vacation accrual for employees —
  `thecfoclub.com/accounting/adjust-vacation-accrual/`.

**IRC §404(a)(6) 401(k) match deductibility timing (§6.2):**

- IRS Issue Snapshot: Deductibility of employer contributions
  to a 401(k) plan made after the end of the tax year —
  `irs.gov/retirement-plans/issue-snapshot-deductibility-of-
  employer-contributions-to-a-401k-plan-made-after-the-end-
  of-the-tax-year`.
- Groom Law Group: Recent IRS Snapshot suggests audit
  interest in retroactive 401(k) contribution timing —
  `groom.com/resources/recent-irs-snapshot-suggests-audit-
  interest-in-timing-of-employer-deductions-of-retroactive-
  contributions-to-401k-plans/`.
- IRS Internal Revenue Code §404 (Bloomberg Tax) —
  `irc.bloombergtax.com/public/uscode/doc/irc/section_404`.
- 26 USC §404 (U.S. House) —
  `uscode.house.gov/view.xhtml?req=(title:26+section:404+
  edition:prelim)+OR+(granuleid:USC-prelim-title26-
  section404)`.
- IRS Chapter 9: Verifying 404 Deductions for DC Plans —
  `irs.gov/pub/irs-tege/epche903.pdf`.
- Tax Notes: IRC Section 404 —
  `taxnotes.com/research/federal/usc26/404`.
- Tax Notes: IRS rules on deduction timing for 401(k) plan
  contributions —
  `taxnotes.com/research/federal/irs-guidance/revenue-
  rulings/irs-rules-on-deduction-timing-for-401k-plan-
  contributions-some/d65p`.
- Accounting Insights: IRC 404 employer contribution rules
  and tax deduction limits —
  `accountinginsights.org/irc-404-employer-contribution-rules-
  and-tax-deduction-limits/`.
- ASPPA: IRS freshens discussion of deductibility of
  employer contributions to a 401(k) —
  `asppa-net.org/news/2023/10/irs-freshens-discussion-
  deductibility-employer-contributions-401k/`.
- PSCA: Timing tax deductible employer contributions —
  `psca.org/news/psca-news/2025/3/timing-tax-deductible-
  employer-contributions/`.

**W-2 reconciliation (§7.3):**

- IRS: Year-end Reconciliation Worksheet for Forms 941,
  W-2, and W-3 —
  `irs.gov/pub/irs-tege/year-end_reconciliation_worksheet.pdf`.
- IRS Video: 10 Minutes on Reconciling Forms 941/W-3/W-2
  to Gross Payroll —
  `irsvideos.gov/Governments/Employers/10MinutesOnReconciling
  Forms941W-3W-2ToGrossPayroll`.
- CalcBee W-2 Box Calculator —
  `calcbee.com/calculators/hr/taxes/w2-box-calculator/`.
- WA Office of Financial Management: 941 to W-2 reconciliation —
  `ofm.wa.gov/sites/default/files/public/accounting/ppa/OFM_
  941_to_W-2_Reconciliation_11.12.20.pdf`.
- Vision Payroll: reconcile your Form W-2 box 1 —
  `visionpayroll.com/wp-content/uploads/2011/01/2010-Form-
  W-2-Reconciliation.pdf`.
- Illinois State Univ Payroll: W-2 reconciliation —
  `payroll.illinoisstate.edu/downloads/pdf/W-2-Reconciliation-
  Calculator.pdf`.
- Pennsylvania W-2 reconciliation worksheet (PA-40 W-2 RW) —
  `pa.gov/content/dam/copapwp-pagov/en/revenue/documents/
  formsandpublications/formsforindividuals/pit/documents/pa-
  40w-2_rw.pdf`.
- Toast Payroll: understand your Form W-2 —
  `support.toasttab.com/en/article/How-to-Read-your-W2`.
- Wojeski: reconciling 941 to payroll —
  `wojeskico.com/content-library-blog/steps-for-reconciling-
  irs-form-941-to-payroll`.
- Consumer Direct TX: pay-stubs & W-2 reconciliation guide —
  `consumerdirecttx.com/wp-content/uploads/2013/08/Pay-stubs-
  and-W-2-Reconciliation-Guide_20160708.pdf`.

**US CoA payroll conventions (§5):**

- Speedy Ledgers: how payroll taxes fit in chart of
  accounts —
  `speedyledgers.com/articles/understanding-how-payroll-
  taxes-fit-into-your-chart-of-accounts`.
- Fit Small Business: payroll accounting guide —
  `fitsmallbusiness.com/how-to-do-payroll-accounting/`.
- Proformative: chart of accounts payroll taxes —
  `proformative.com/questions/chart-accounts-payroll-taxes/`.
- AccountingCoach: payroll accounting in-depth —
  `accountingcoach.com/payroll-accounting/explanation`.
- Intuit QuickBooks: payroll liabilities types + employer
  guide —
  `quickbooks.intuit.com/r/payroll/payroll-liabilities/`.
- Intuit QuickBooks: chart of accounts in QuickBooks Online —
  `quickbooks.intuit.com/learn-support/en-us/help-article/
  chart-accounts/learn-chart-accounts-quickbooks-online/
  L2yc6KBob_US_en_US`.
- Rippling blog: payroll journal entry types & best practices —
  `rippling.com/blog/payroll-journal-entry`.
- Ramp blog: payroll journal entry types, examples & guide —
  `ramp.com/blog/payroll-journal-entry`.
- DualEntry blog: payroll ledger →  GL —
  `dualentry.com/blog/the-payroll-ledger`.
- AccountingTools: payroll journal entries —
  `accountingtools.com/articles/payroll-entries`.

**Kontor anchors:**

- `/home/christian-weilbach/Development/kontor/doc/research/
  79-hr-payroll-stage-r-plan.md` (§5.2 — C3 sketch).
- `/home/christian-weilbach/Development/kontor/doc/research/
  81-hr-data-model-gold-standards.md` (§9.6 compensation-as-
  entity; §9.7 `:person/kind`, `:employment/work-time-
  fraction`).
- `/home/christian-weilbach/Development/kontor/doc/research/
  73-hr-payroll-market-pain.md` (Themes B / D / F).
- `/home/christian-weilbach/Development/kontor/modules/l10n-us/
  src/kontor/l10n_us/chart.clj` (QBO starter chart consumed
  by §5).
- `/home/christian-weilbach/Development/kontor/modules/l10n-us/
  src/kontor/l10n_us/sales_tax.clj` (provider-pluggable
  posture mirrored for the wage-type-map).
- `doc/decisions.md` ADR-005 (TaxProvider, superseded by
  ADR-071), ADR-008 (bitemporal), ADR-021 (parallel ledgers),
  ADR-022 (per-account required analytic plans + sum-to-100),
  ADR-031 (`:entity`), ADR-032 (`:schedule` + `cost-center`
  analytic plan), ADR-038 (`:audit-doc`), ADR-051 (`:audit-
  doc/privilege`; note 79 §2.5 adds the `:category` axis),
  ADR-067 (`kontor.process`), ADR-068 (`*-tx-data` builder),
  ADR-071 (TaxRateProvider/TaxFacts/TaxPostingBuilder shape
  mirrored for `PayrollComputeProvider`/`PayrollFacts`/
  `PayrollPostingBuilder`), ADR-072 (`FxRateProvider`).

**License posture summary.** ADP / Gusto / Paychex / OnPay /
Rippling / Justworks file-format and configuration
documentation are public customer-facing material; we
reference column structures and convention names without
lifting code. The wage-type-map data shape is consumer-
supplied at install time (mirrors ADR-005 / ADR-071). No
proprietary CoA bundled; the QBO starter chart already in
`modules/l10n-us/src/kontor/l10n_us/chart.clj` is the
reference for §5's account ranges (it's loaded from
`resources/kontor/l10n_us/chart.edn` which kontor ships
under EPL-1.0 per the repo's own license posture). PEO /
co-employment treatment per IRS Third Party Payer
arrangements (public IRS guidance). FASB ASC 710 quoted at
the four-condition / five-paragraph spec level (public
codification access via PwC Viewpoint + Wiley; the
underlying FASB ASC requires paid access for the
authoritative text but the structural rules cited here are
publicly summarized by every Big-4 firm + every payroll-
software vendor). IRC §404(a)(6) cited via IRS public
guidance + USC public text.

---

End of note 83.
