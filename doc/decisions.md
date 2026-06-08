# Architecture decisions — distilled

This is the **canonical short list** of design decisions a new
contributor needs to read to understand kontor as it is today.
Each entry summarises the load-bearing choice + the *why* + the
original ADR number(s) it derives from. ADR numbers are stable;
the full chronological ADR record is a local-maintainer artifact
(not part of the public repo). The code carries the load — this
file is the reading order over what the code already encodes.

---

## Table of contents

1. [Foundations — license, scope, dependency](#1-foundations)
   1.1 [License + cohabitation + scope (ADR-001, ADR-002, ADR-006, ADR-010)](#11-license--cohabitation--scope)
   1.2 [No UI, no Avalara API keys, no Python translation (ADR-001, ADR-005, ADR-010)](#12-non-goals)
2. [Substrate — money, postings, entities](#2-substrate)
   2.1 [`Money` = `BigDecimal` + commodity, HALF-EVEN (ADR-013)](#21-money)
   2.2 [Schema-namespace discipline (ADR-002)](#22-schema-namespace-discipline)
   2.3 [`:entity` and the trans-national sum-to-zero (ADR-031)](#23-entity-sum-to-zero)
3. [Bitemporal](#3-bitemporal)
   3.1 [Lean bitemporality on `:tx/valid-from` (ADR-008, ADR-048)](#31-lean-bitemporality)
4. [Sealing + audit + governance](#4-sealing--audit--governance)
   4.1 [Sealing — `:posting/posted-at` + purge-is-a-commit (ADR-007)](#41-sealing)
   4.2 [Audit-doc + privilege + retention + DSAR + legal hold (ADR-038, ADR-049, ADR-050, ADR-051, ADR-052)](#42-audit-doc--governance)
   4.3 [Status machines as data (ADR-034)](#43-status-machines)
5. [Write substrate — process, *-tx-data, kontor.book](#5-write-substrate)
   5.1 [Validation gate — vendored `datopia/invariant` (ADR-011)](#51-validation-gate)
   5.2 [`kontor.process` orchestrator (ADR-067)](#52-kontorprocess)
   5.3 [Every business write exposes a `*-tx-data` builder (ADR-068)](#53-tx-data-builders)
   5.4 [`kontor.book` verb facade (ADR-095)](#54-kontorbook)
6. [Reports as marginalizations](#6-reports-as-marginalizations)
   6.1 [`marginalize` / σ_E + `:posting/dimensions` (ADR-096, ADR-097)](#61-marginalize)
7. [Providers — the kernel's pluggable surface](#7-providers)
   7.1 [TaxRateProvider + FxRateProvider + others (ADR-005, ADR-071, ADR-072, ADR-029, ADR-017, ADR-055, ADR-063, ADR-075)](#71-provider-surface)
   7.2 [Consolidation primitive (ADR-073)](#72-consolidation)
   7.3 [Cross-DB saga primitive (ADR-074)](#73-cross-db-saga)
8. [Tax substrate](#8-tax-substrate)
   8.1 [`PeriodTaxProvider` (ADR-099)](#81-periodtaxprovider)
   8.2 [Sole-proprietor rung + VAT return (ADR-100)](#82-sole-prop--vat-return)
   8.3 [Statute as data — `:provision` / `:parameter` / `:regime` / `:tax-concept` (ADR-101)](#83-statute-as-data)
   8.4 [Capital gains — disposal substrate + DisposalProvider (ADR-102, ADR-103)](#84-cgt-substrate)
   8.5 [Fiscal-unit substrate — group-tax consolidation (ADR-113)](#85-fiscal-unit-substrate)
9. [Companion-module discipline](#9-companion-modules)
   9.1 [Per-country l10n modules ship separately (ADR-006)](#91-per-country-l10n)
   9.2 [Companion = own namespace + own install! (ADR-002, ADR-068)](#92-companion-discipline)
10. [McComb-aligned substrate seams](#10-mccomb-seams)
    10.1 [`:concept-iri`, `kontor.explain`, `kontor.workflow.event-bus` (ADR-090, ADR-091, ADR-092)](#101-mccomb-substrate-seams)
11. [Deferred / withdrawn — explicit honesty](#11-deferred--withdrawn)

---

## 1. Foundations

### 1.1 License + cohabitation + scope

**Apache 2.0.** kontor ships under Apache 2.0 — see [LICENSE](../LICENSE). (ADR-001)

**One database, two schema namespaces.** kontor and your consumer app cohabit in one datahike connection by partitioning the attribute namespace: kontor owns `:kontor.*`; your app owns its own namespaces. Posting a sales invoice writes both halves in one tx. Adding a new top-level attribute namespace requires an ADR. (ADR-002)

**The kernel is a kernel, not an ERP.** kontor provides primitives — postings, balances, periods, tax abstraction, sealing, bitemporal queries. It does **not** ship a UI, sales pipeline, CRM, country-specific data, or workflow chrome. Those live in consumer apps or l10n modules. (ADR-010)

### 1.2 Non-goals

**No UI.** Not even "just a small one". The boundary is non-negotiable because every UI choice forces a presentation paradigm on consumers.

**No US sales tax engine.** kontor provides the `TaxRateProvider` protocol; customers integrate Avalara / TaxJar / TaxCloud themselves. Bundling rate data violates vendor ToS and creates a perpetual maintenance burden.

**Single runtime.** JVM Clojure + datahike. No JS, no Python helpers, no shell scripts beyond what `clojure` aliases need. (ADR-001)

---

## 2. Substrate

### 2.1 Money

`Money` is a `BigDecimal` amount + a commodity tag (currency, share class, etc.). Arithmetic is `BigDecimal` end-to-end; rounding is HALF-EVEN unless a regulator mandates HALF-UP (some VAT jurisdictions; documented case-by-case in l10n modules). **Never doubles.** Two `Money`s compose only if commodities match; `kontor.fx/convert` is the one allowed conversion path. (ADR-013)

### 2.2 Schema-namespace discipline

Every datahike attribute the kernel owns is prefixed with `:kontor.*` to avoid silent collisions when kontor's schema cohabits with consumer-app schemas in the same datahike connection. Kernel namespaces: `:kontor.account/*`, `:kontor.journal/*`, `:kontor.transaction/*`, `:kontor.posting/*`, `:kontor.posting-dimension/*`, `:kontor.commodity/*`, `:kontor.lot/*`, `:kontor.tax/*`, `:kontor.tax-rep/*`, `:kontor.vat-group/*` (semantic rename — the prior `:tax-group/*` modelled VAT bucket-pairs), `:kontor.account-tag/*`, `:kontor.partner/*`, `:kontor.person/*` (shared person attrs), `:kontor.fiscal-position/*`, `:kontor.period/*`, `:kontor.balance-assertion/*`, `:kontor.entity/*`, `:kontor.ledger/*`, `:kontor.fx-rate/*`, `:kontor.audit/{create-uid,write-uid}`, `:kontor.audit-doc/*`, `:kontor.status-transition/*`, `:kontor.status-history/*`, `:kontor.schedule/*`, `:kontor.cross-tx/*`, `:kontor.legal-hold/*`, `:kontor.retention-policy/*`, `:kontor.dsar-request/*`, `:kontor.tax-concept/*`, `:kontor.provision/*`, `:kontor.regime/*`, `:kontor.parameter/*`, `:kontor.fiscal-unit/*`. Companion modules own `:kontor.<companion>/*` (e.g. `:kontor.asset/*`, `:kontor.lease/*`, `:kontor.disposal/*`, `:kontor.invoice/*`); HR person extras live under `:kontor.hr.person/*` (dotted sub-namespace) and ref the kernel person eid. New top-level namespaces require an ADR. (ADR-002)

### 2.3 Entity sum-to-zero

Transactions sum to zero **per `(:entity × :ledger × :commodity)` triple**, not per transaction. This is what lets one datahike connection hold multiple legal entities (parent + subsidiaries), multiple parallel books (statutory / IFRS / tax), and multiple currencies without losing audit discipline. A pure intercompany move (parent A → subsidiary B) is two single-entry rows that balance only when grouped by the triple. (ADR-031)

`kontor.entity/family` is the kernel walk over the parent / subsidiary tree; consolidation (ADR-073) folds family balances into the parent's functional currency.

---

## 3. Bitemporal

### 3.1 Lean bitemporality

kontor is bitemporal — but **lean** rather than full. Two axes:

- **Transaction time** — `:db/txInstant`, free from datahike. Read helpers accept `:as-of-tx` (`d/as-of`). The "what did the books look like as filed on date X" audit question is answered here.
- **Valid time** — `:tx/valid-from` stamped on the writing transaction, via `kontor.bitemporal`. Read helpers accept `:as-of-valid` (defaults to today). The "what is the effective state at date X" business question is answered here.

**Why lean.** Research note 08 surveyed XTDB / Datomic / Snowflake-time-travel / SMB accounting (QuickBooks, Xero, NetSuite). Industry pattern for prior-period corrections is **reverse-and-repost in the current open period**, not preserve-the-as-filed-view-forever. Big-R IAS 8 restatements explicitly restate. We pay for the cheap-and-useful half (`:tx/valid-from` + `:as-of-snapshot`); we skip the expensive half (`:valid-to` + temporal tuple). Adding `:valid-to` later is a 1-attribute schema add if a regulated-fintech use case appears. (ADR-008, ADR-048)

**Every kernel query takes `:as-of-tx` and `:as-of-valid` keyword args.** Defaults are `nil` / today. New query helpers must accept these arguments explicitly — implicit "now" reads are a bug.

---

## 4. Sealing + audit + governance

### 4.1 Sealing

A posting transitions from draft to **posted** by setting `:posting/posted-at`. After that, *silent* retraction is forbidden by middleware in `kontor.compliance.sealing`. **Explicit `:db/purge` IS allowed** but is itself a recorded commit; the audit story is "the chain documents the purge", not "deletion is impossible". This composes naturally with retention/DSAR (§4.2): deletion happens through legitimate channels that leave their own audit trail. (ADR-007)

### 4.2 Audit-doc + governance

Five interlocking governance primitives, each a thin kernel schema:

- **`:audit-doc`** — codified reasons, supporting documents, approval policy. Every posting / period-close / disposal / privileged change carries its `:audit-doc/category` + `:audit-doc/privilege` + `:audit-doc/language` + optional `:audit-doc/refs` payload. Two-axis category (legal-doctrine × subject-matter) ships in the kernel; consumers extend the vocabulary. (ADR-038, ADR-051, ADR-078 added `:audit-doc/language`, ADR-075 added `:audit-doc/category` taxonomy)
- **`:legal-hold`** — write-time invariant blocking purge of held entities. A hold names its scope (entity / partner / matter); legal-hold-aware sweepers refuse to drop matching rows. (ADR-049)
- **`:retention-policy`** + sweeper — effective-dated row expiry that respects holds. The sweeper is itself a `kontor.process` step; expiry writes its own `:audit-doc/category :retention-expiry` entries. (ADR-050)
- **`:dsar-request`** + `kontor.compliance.dsar/collect` — a bitemporal walk gathering every row referencing a data subject across kontor and companions; produces a deterministic export. (ADR-052)
- **`:audit-doc/privilege`** — attorney-client / work-product / regulatory-privilege classifiers that the DSAR walk and report engine respect. (ADR-051)

The whole governance stack composes because every primitive is a queryable schema entity, not a side-channel API.

### 4.3 Status machines

**`:status-transition`** declares the legal `(entity-type, facet, from, to)` graph (with optional per-org overrides). **`:status-history`** records every actual transition with audit metadata. `kontor.workflow.status-machine/record-status-change!` writes both the facet update and the history row in one tx, after legality check. Every workflow companion (`kontor-sales`, `kontor-invoice`, `kontor-procurement`, `kontor-collections`, `kontor-disposal`, …) uses the same primitive — six independent transition-table inventions would otherwise emerge. (ADR-034)

Bitemporally indexed status history answers "what transitions were allowed last quarter?" for free.

---

## 5. Write substrate

### 5.1 Validation gate

All business writes route through `kontor.gate/transact-with-validation` (also exposed as `kontor.validation/transact-with-validation` for back-compat), which fires middleware in this order:
1. `datopia/invariant` validators (cardinality, attribute-namespace discipline, sum-to-zero per `(:entity × :ledger × :commodity)`),
2. `kontor.compliance.sealing` (no silent retract of posted rows),
3. `kontor.compliance.legal-hold` (no purge of held rows),
4. companion-supplied validators (registered at install-time).

We vendor `datopia/invariant` into `src/kontor/_invariant/` rather than depending on it, because the upstream library is unmaintained and we own the only patches. (ADR-011)

### 5.2 `kontor.process`

A `kontor.process` is a pure list of `{:builder ... :args ...}` steps. `run-process` resolves each step's builder (a `*-tx-data` function), threads the in-progress db value through, and emits one final transaction containing every step's effects. The orchestrator gives us **multi-step atomic writes** — depreciation-roll + lease-remeasurement + tax-return + payroll-run all run as single transactions; partial failure is impossible because nothing is written until the whole step list resolves. (ADR-067)

### 5.3 `*-tx-data` builders

**Every business write exposes a pure builder that returns tx-data**. The `!` wrapper is a thin shell that routes the builder's output through `transact-with-validation`. E.g. `post-transaction-tx-data` + `post-transaction!`; `record-status-change-tx-data` + `record-status-change!`; `import-lease-tx-data` + `import-lease!`. Builders compose into `kontor.process` steps without touching a connection. Consumers can dry-run any write, diff against the current db, or batch multiple builders into one tx. (ADR-068)

This convention is the substrate's load-bearing pattern — every write everywhere in kontor + companions exposes it.

### 5.4 `kontor.book` verb facade

`kontor.book` is **organising sugar** over `*-tx-data` builders: 8 named verbs (`receive` / `pay` / `sell` / `buy` / `receive-payment` / `pay-bill` / `transfer` / `adjust`) + a generic `entry-tx-data` / `entry!`. The verbs do not add schema or capability — they exist so a Clojure dev reads `(book/sell conn {...})` instead of constructing posting maps by hand. Verbs stamp `:posting/entity` from `book` context so per-entity trial-balance filters work without consumer book-keeping. (ADR-095; the McComb arc deliberately deflated to "verb facade" rather than a θ-as-data framework — `*-tx-data` builders already ARE θ in code, and sealing neutralises the re-derivability payoff.)

---

## 6. Reports as marginalizations

### 6.1 `marginalize` / σ_E

`kontor.report/marginalize` is the quotient-epimorphism primitive: take the posting flake stream, pick a grouping function `(fn [posting] axis-value)`, sum money per group at a target as-of pair. Every report engine in kontor (trial balance, P&L, BS, VAT return, period-tax provisioning, cost-centre report) is `marginalize` over a different axis. The axes are:

- `:account` (kernel; classical trial balance),
- `:tax-tag` (kernel; VAT return rollups),
- `:account-code` (kernel; XBRL / filing taxonomies via `:concept-iri`),
- any `:posting-dimension` instance (cost centre, project, segment, anything else a companion installs).

**`:posting-dimension` + `:posting/dimensions`** is the kernel-schema extension point. A companion installs a new `:posting-dimension` (`{:posting-dimension/key :project :posting-dimension/cardinality :one ...}`) and every posting carries `:posting/dimensions {:project <ref>}`; `marginalize` pivots over `:project` for free. (ADR-096, ADR-097)

The substrate's "reports" are not a separate subsystem — they are read patterns over the posting stream.

---

## 7. Providers

### 7.1 Provider surface

Provider protocols are how kontor stays neutral about per-jurisdiction / per-vendor concerns while letting consumers and l10n modules plug in. Every provider:
- is a Clojure protocol with a small surface (3-5 methods typical),
- defines a `Facts` record shape for its return value,
- carries a `provider-id` for telemetry / audit,
- ships with a `Static*Provider` default impl in the kernel (where the data is static), or a clear "consumer plugs Avalara / TaxJar here" extension point (where it's not).

The current set (ADR-005 → ADR-071 for tax; plus ADR-017, ADR-029, ADR-055, ADR-063, ADR-072, ADR-075, ADR-099, ADR-103):

| Protocol | Purpose | Default impl |
|---|---|---|
| `TaxRateProvider` | rate lookup for a transactional tax (VAT / GST / sales tax) → `TaxFacts` | `StaticTableProvider` |
| `TaxPostingBuilder` | turn `TaxFacts` into balanced postings | `StaticTablePostingBuilder` |
| `PeriodTaxProvider` | period-incident taxes (CIT / PIT / CGT / property / wealth) → `TaxReturnFacts` | jurisdiction-specific |
| `TaxReturnPostingBuilder` | provision + payment postings from `TaxReturnFacts` | kernel-default |
| `FxRateProvider` | spot / period-average / closing FX rates | `StaticTable`, `ECB`, `Chained` |
| `CostingProvider` | inventory cost layer engine (FIFO / LIFO / WAC) | kernel built-ins |
| `EInvoiceProvider` | EN16931 / Factur-X / PEPPOL XML emission | `PureXmlProvider` |
| `DepreciationProvider` | depreciation schedule for a `:ledger` × `:asset` | straight-line / DDB |
| `LeaseProvider` | IFRS 16 / ASC 842 schedule | kernel built-in |
| `PayrollComputeProvider` + `PayrollPostingBuilder` + `PayrollEmitProvider` | engine-CSV in, GL + statutory file out | per-l10n |
| `DisposalProvider` | source of `:disposal` events for CGT providers | `DatahikeDisposalSource` (companion) |

**ADR-005 is superseded by ADR-071.** The original "one `TaxProvider` for everything" split into rate-vs-posting concerns. The DisposalSource → DisposalProvider rename happened during the W3 provider-normalisation pass.

Two providers stay record-shaped rather than protocol-shaped on purpose: the FxRateProvider's `query` map IS already ctx, and CostingProvider's positional `db` argument is on the "normalise next" list rather than a blocker.

### 7.2 Consolidation

`kontor.provider.consolidation` provides three primitives over `kontor.entity/family`:
- `translate-trial-balance-tx-data` — translate each child's TB into the parent's functional currency using `FxRateProvider`-supplied rates (spot / closing per IAS 21),
- `eliminate-intercompany-pair-tx-data` — eliminate matched receivable/payable + revenue/expense pairs by `:posting/intercompany-counterparty` ref,
- `consolidate!` — orchestrator that runs both for a `(parent, period)`.

Composes with the period-tax + lease + asset providers — each child runs its own jurisdiction's period close; consolidation rolls up afterwards. (ADR-072, ADR-073)

### 7.3 Cross-DB saga

`kontor.workflow.side-effect.cross` adds `:cross-tx/step-id` + `CrossTxRouter` + `drain!` for the rare case where a kontor transaction must trigger a side-effect in another DB (e.g. beleg's invoice store) AND the side-effect is itself transactional. Sagas are durable (the `:cross-tx/step-id` row is persisted); compensation is the consumer's responsibility. (ADR-074)

---

## 8. Tax substrate

The tax substrate is staged because tax law has three structurally distinct shapes:
1. **Transactional taxes** (VAT / GST / sales tax) — per-line, per-rate. Substrate: `TaxRateProvider` + `TaxPostingBuilder` + per-l10n rate tables. (§7.1)
2. **Period-incident taxes** (income / corporate / capital-gains / property / wealth / standalone employer-payroll) — per-(entity × period). Substrate: `PeriodTaxProvider`. (§8.1)
3. **Period reconciliations** (VAT return, sole-proprietor business net) — marginalize over `:account-code` / `:tax-tag`, write a balanced remittance posting. (§8.2)

Plus statute-as-data (§8.3) for jurisdictions where the rules themselves should be queryable, and the disposal substrate (§8.4) for capital-gains tax.

### 8.1 PeriodTaxProvider

`PeriodTaxProvider` is the period sibling of `TaxRateProvider`. A tax is `(scope, base-selector, schedule) → liability → posting`; the period sibling fills the slots with `(entity × period, marginalize/σ_E, progressive brackets)`. Three schema-free kernel namespaces:

- `kontor.tax.tax-schedule` — the schedule algebra: `:flat` / `:bracket` / `:capped` / `:formula` / `:elect` / `:sum` / `:schedule-override`, plus `:base-transform` and `apply-adjustments`. The closed `:op` set keeps schedules portable across jurisdictions; `:provision/compute-fn` is the escape hatch for the rare uncodifiable case.
- `kontor.tax.period-tax-provider` — `PeriodTaxProvider` + `TaxReturnFacts` + a closed 8-value `period-tax-kinds` enum (`:standalone-payroll-tax` / `:corporate-income-tax` / `:personal-income-tax` / `:capital-gains-tax` / `:investment-income-tax` / `:property-tax` / `:wealth-tax` / `:land-appreciation-tax`).
- `kontor.tax.tax-return-posting-builder` — provision / payment postings via the verb facade.

Kernel records (`StandalonePayrollTaxProvider`, `CorporateIncomeTaxProvider`, `PersonalIncomeTaxProvider`) cover the rate/threshold shape; per-jurisdiction richer logic uses statute-as-data (§8.3). **11 jurisdictions ship full CIT** on this path — DE / US / CA + QC / FR / JP / AU / BR / IN / MX / CN / AT. UK ships CGT + investment-income only, pending iXBRL substrate work (see §12). (ADR-099)

### 8.2 Sole-prop + VAT return

Two thin kernel namespaces:
- `kontor.tax.sole-proprietor` — `business-net` is σ_E (income − expense) for the entity; `business-income-input` folds the net onto a `PersonalIncomeTaxProvider`'s `:inputs`. The CA T2125 pattern, generalised. The "individual → corporation continuum" the project plans for starts here.
- `kontor.tax.vat-return` — periodic VAT/GST reconciliation. `compute-vat-return` marginalizes already-posted output/input VAT by account code; `vat-return-tx-data` materialises the balanced remittance posting. Schema-free, additive. (ADR-100)

### 8.3 Statute as data

For jurisdictions where the underlying statute is itself worth lifting into queryable data — **all 11 CIT jurisdictions** (DE, FR, JP, CA, AT, AU, CN, MX, US, BR, IN) ship on this path — four kernel namespaces:

- **`:tax-concept`** (~5 attrs) — cross-jurisdiction concept catalogue. 14-concept starter set: `:participation-exemption`, `:rollover-relief`, `:loss-bucket`, `:lifetime-cap`, etc. Composes with `:concept-iri` (§10.1).
- **`:provision`** (~14 attrs) — per-jurisdiction encoded statute rule. Closed predicate vocab + `:provision/compute-fn` escape hatch; default + exception via ordered `:priority` + `:exception-of`, Catala-inspired.
- **`:regime`** (~7 attrs) — elective container. Election rides ADR-034 status-machine; counterfactual via `:regime/extends`.
- **`:parameter`** + `:parameter-value` + `:parameter-bracket` (~9 attrs) — date-keyed value history. OpenFisca's parameter-tree pattern.

Single evaluator (`kontor.tax.statute/apply-provisions`) folds provisions in priority order, raises `kontor.tax/ambiguous-provision` on same-priority conflicts. **Two-pass query pattern** (qualification cliffs — compute base first, then re-query with result injected) is documented in `apply-provisions` docstring. `compose-greater-of` documents the MAT/AMT pattern in code.

`:provision/effective-from` is distinct from `:tx/valid-from` — statutory effective date vs book-entry valid-from.

`apply-provisions` return shape: `{:base-items :tax-items :schedule-overrides :provisions}`. (ADR-101 + addendum 1 + addendum 2)

### 8.4 CGT substrate

**`:disposal/*`** — companion-owned (`modules/disposal/`) substrate for ownership-change events. Kind / subject / asset-class / acquired-on / disposed-on / proceeds / basis / depreciation-taken / ownership-fraction / elective-regime / exemption-claimed / rollover / loss-bucket / state. Status-machine facet `:recorded → :recognized | :voided`. (ADR-102)

**`DisposalProvider`** — kernel-side protocol (renamed from `DisposalSource` during the W3 provider-normalisation pass). CGT providers depend on the protocol, not the companion; the companion ships the canonical `DatahikeDisposalSource` impl. Per-jurisdiction CGT providers return 0+ `:capital-gains-tax` components — some standalone with their own schedule (DE §20 25%, US §1(h) 0/15/20), others fold via `:jurisdiction-specific-codes {:cit-base-additions [...] :pit-base-additions [...]}` consumed by the country's CIT/PIT provider. **All 12 jurisdictions** (US, DE, UK, JP, CA, FR, AU, BR, IN, MX, CN, AT) ship CGT — UK is included here because CGT is decoupled from the iXBRL CIT gate. (ADR-103)

### 8.5 Fiscal-unit substrate

`:fiscal-unit` is the kernel substrate for **group-tax consolidation** (DE Organschaft, FR intégration fiscale, US §1502, JP group-tsuusan, UK group relief, AT Gruppenbesteuerung, AU TCR, CN CCSV, MX RIGS). Three attribute groups: `:kontor.fiscal-unit/*` (code / name / parent-entity / regime / computation-style / elected-from / minimum-term-ends / anchor-document / status), `:kontor.fiscal-unit-member/*`, `:kontor.transaction/elimination-*`.

Three computation styles dispatch `run-group-tax!`: `:single-base` (sum members' bases into one filing — DE Organschaft, FR intégration), `:per-member-with-netting` (each member files, losses surrender within the group — UK group relief), `:loss-surrender` (members file independently, election surrenders losses — CN CCSV).

`kontor.tax.statute/compose-aggregate-of` (sibling of `compose-greater-of`) records the **economic delta** vs separate filing: elected liability + sum(separate liabilities) + delta. The audit story is "every member could have filed alone; here is what the election bought."

**v1 DE Organschaft pilot ships** (`modules/l10n-de/organschaft_provider.clj`): wraps the DE CIT provider, sums per-member `:gewinn-aus-gewerbebetrieb`, delegates to KSt+Soli+GewSt math, matches the BMF Müller-Gruppe worked example (€237,375 KSt+Soli to the cent; €158,250 economic delta). Other jurisdictions land as consumer demand surfaces. (ADR-113)

---

## 9. Companion modules

### 9.1 Per-country l10n

`kontor-l10n-<cc>` is the artifact pattern. Each l10n module:
- owns its own attribute prefixes (e.g. `:de/*`, `:ca/*`, …) AND/OR adds to kernel taxa via `:provision` / `:parameter` data,
- ships its own LICENSE (defaults to Apache 2.0 to match the kernel; ADR-006 reserves the option if a future module incorporates copyleft data),
- exposes one `install!` entry that wires every provider for that jurisdiction (CIT + CGT + investment-income + payroll + VAT + chart),
- ships a `<cc>/preset.clj` that yields a working tax stack with one call. (ADR-006; the W4 install-shape consistency pass codified the entry-point convention)

**12 l10n modules ship today** — DE / US / CA + QC / FR / AU / BR / MX / IN / JP / CN / AT / UK. Tax-completeness varies (see [per-l10n READMEs](../modules/) — the v0.1.0-alpha matrix); UK is CGT + investment-income only pending iXBRL substrate work.

### 9.2 Companion-module discipline

A **companion module** (`kontor-asset`, `kontor-lease`, `kontor-inventory`, `kontor-procurement`, `kontor-sales`, `kontor-invoice`, `kontor-collections`, `kontor-expense`, `kontor-hr`, `kontor-disposal`, `kontor-partner`, `kontor-import-gleif`, `kontor-import-edgar`, `kontor-people-record`, payroll-* adapters, bank-* adapters):
- owns its own namespace prefix(es), never touching kernel ones,
- exposes one `install!` entry that registers schema + validators + providers,
- depends on the kernel + (optionally) other companions; the kernel never depends on a companion,
- exposes `*-tx-data` builders for every business write (ADR-068),
- ships its own tests + README. (ADR-002, ADR-068)

The kernel exposes `kontor.core/install-all-companions!` for the "one connection, every companion" case, and one-by-one `kontor-asset/install!` etc. for the "I only want this one" case.

---

## 10. McComb-aligned substrate seams

### 10.1 McComb substrate seams

Three small kernel additions implementing the McComb "data outlives applications" framing — added because they cost little and unlock real consumer integrations later:

- **`:concept-iri`** — substrate seam generalised across `:account`, `:partner`, `:commodity`, `:tax`, `:document-type`. Stable IRIs into XBRL / FIBO / gist / internal taxonomies. The XBRL bridge is `marginalize` over `:concept-iri`. (ADR-019 + ADR-090)
- **`kontor.explain`** — substrate "explain this number" graph walks (`explain-balance`, `explain-posting`, `entities-with-concept-iri`). Pure read-only datalog returning plain Clojure maps. (ADR-091)
- **`kontor.workflow.event-bus`** — in-process pub-sub on commit; `register-handler!` / `commit-and-emit` / `:transaction/committed` event kind. ADR-001 single-dep preserved; consumers wanting Kafka / NATS write an adapter. (ADR-092)

These are deliberately "barely exercised" today — they exist for consumers, not for the kernel itself.

---

## 11. Deferred / withdrawn — explicit honesty

The substrate has explicit gaps. Listing them here so a new contributor isn't surprised:

- **Fiscal-unit beyond DE** — the substrate ships (§8.5, ADR-113) and the DE Organschaft pilot lands the BMF worked example to the cent. FR intégration / US §1502 / JP group-tsuusan / UK group relief / AT Gruppenbesteuerung / AU TCR / CN CCSV / MX RIGS are next; pending consumer demand to land the next jurisdiction.
- **UK CIT + payroll** — UK ships CGT + investment-income only. Full CIT + payroll deferred (the iXBRL filing gate is the hard part, not the CIT computation).
- **`kontor-commitment`** — **WITHDRAWN**. Had zero consumers outside its own tests; the verb facade (ADR-095) covers the "named obligation" use case. (ADR-098)
- **`:local/root` deps in companion `deps.edn`** — pending. Companions currently depend on the kernel via `:local/root`; consumers cloning a single companion need to clone the workspace. Repoint to `:git/url` + `:sha` is the v0.1.0-alpha publishing blocker.
- **Datahike pin** — the kernel currently pins datahike to an in-flight branch carrying the bitemporal-v1 + DH-11 fixes. Tracked alongside the `:local/root` blocker; once those fixes land in a tagged datahike release, the pin goes away.
- **Stratum vt-aware secondary index for trial balance** — designed; pending a real OLAP-scale workload to motivate the wiring.
- **`PayrollPostingBuilder` + `PayrollEmitProvider` `provider-id` / `builder-id`** — 24 + 11 impl files deferred to a focused payroll-protocol-id wave. Functional today; cosmetic only.
- **`CostingProvider` ctx-collapse** — flagged for follow-up; functional today.
- **`LeaseProvider` `plan-schedule` ctx normalisation** — flagged for follow-up; functional today.
- **Pillar Two (OECD GloBE)** — explicitly out of scope until a customer asks; the `:provision` evaluator is the natural home.
- **Standalone `kontor-mcp` server** — gated on a consumer ask; composes with `dvergr`'s MCP today.

---

## How this file changes

Add a new section here when a decision is load-bearing for new
readers. ADR numbers are the stable handle; once an ADR has
shipped and the code carries the load, this file is the public
distillation.

When a decision is **superseded** or **withdrawn**, update the
relevant section here and reference the original by ADR number.
The narrative trail (alternatives considered, why the choice
flipped) lives in the local-maintainer ADR record alongside the
code; new readers should not need to re-litigate the choice.
