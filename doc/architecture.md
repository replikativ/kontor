# Architecture

A bird's-eye view of how `kontor` is laid out, why each layer exists, and how it composes with the surrounding stack (datahike, companion modules, partner adapters, consumer apps).

For *why* each choice was made, see [decisions.md](decisions.md). For coding conventions (transactor opts shape, status-machine writes, valid-time stamping, namespacing, money discipline) see the same file's §2 and §5. This document describes the *what*.

---

## The layer cake

```
┌──────────────────────────────────────────────────────────────────────┐
│  Consumer applications                                               │
│   beleg (HTMX billing)    simmis (Replicant SPA)    your app         │
│   own UI, own routing, own auth                                      │
└──────────────────────────────────────────────────────────────────────┘
                                ▲
                                │  (pulls in as deps)
                                │
┌──────────────────────────────────────────────────────────────────────┐
│  Partner adapters (NEVER bundle credentials, NEVER in the kernel)    │
│   PAC providers (MX), IRP/EWB providers (IN), SEFAZ transmitters     │
│   (BR), Peppol Access Points (JP/AU/DE), Avalara/TaxJar (US sales).  │
└──────────────────────────────────────────────────────────────────────┘
                                ▲
                                │
┌──────────────────────────────────────────────────────────────────────┐
│  Companion modules (separate Maven artifacts, live in modules/)      │
│                                                                      │
│   Kernel-adjacent                                                    │
│    modules/partner       (ADR-033: party root + person/org)          │
│    modules/sales         (ADR-035: order + items + ship-groups)      │
│    modules/invoice       (ADR-036: order→invoice bridge + status)    │
│    modules/procurement   (ADR-042: requisition + receipt + 3-way)    │
│    modules/collections   (ADR-043: AR collections + dunning + …)     │
│                                                                      │
│   Per-country localizations                                          │
│    modules/l10n-{de,fr,ca,us,au,jp,cn,in,br,mx,at}                  │
│                                                                      │
│   Per-country bank-statement importers                               │
│    modules/bank-{de,fr,ca,us,at}                                     │
│                                                                      │
│   E-invoice emitters                                                 │
│    modules/einvoice-de   (Factur-X / XRechnung / ZUGFeRD)            │
└──────────────────────────────────────────────────────────────────────┘
                                ▲
                                │
┌──────────────────────────────────────────────────────────────────────┐
│  kontor kernel — Apache 2.0, one dependency (datahike)                  │
│                                                                      │
│   Substrate: schema, money, bitemporal, entity                       │
│   Write path: book (verbs) → posting (sum-to-zero) → validation gate │
│   Read path: balance / ledger / trial / closing → report (σ_E)       │
│               + financial_statements + explain                       │
│   Policies: sealing, status-machine, period-lock, audit-doc,         │
│     compliance (legal-hold, retention, DSAR), schedule, process,     │
│     side-effect (incl. cross-DB saga), event-bus                     │
│   Providers: tax (rate / posting / period / statute-as-data),        │
│     disposal+CGT, payroll trio, FX, costing, e-invoice,              │
│     depreciation, multi-entity consolidation                         │
│   Money flow: payment-application, reconciliation, bank-account,     │
│     payment-term, aging                                              │
└──────────────────────────────────────────────────────────────────────┘
                                ▲
                                │  (uses, never embeds)
                                │
┌──────────────────────────────────────────────────────────────────────┐
│  datahike (replikativ) — EAV store with native bitemporality         │
│   schema, transactions, query, history, branches, commits            │
│   :crypto-hash? true → SHA-512 Merkle-tree over indices              │
└──────────────────────────────────────────────────────────────────────┘
```

The pattern is **kernel + companions + partner adapters**. The kernel knows nothing about a specific country or third-party API; a companion adds a chart of accounts, a tax stack, an e-invoice emitter, or a workflow surface. A partner adapter signs and submits the kernel's emitted artifact to the relevant authority. Each layer can be swapped without touching the kernel.

---

## Kernel: namespaces

As of v0.1.0-alpha the kernel is ~20k LOC across ~57 namespaces. The conceptual frame is 5 axes (substrate, write path, read path, policies, providers); the **directory layout** clusters by domain — most axes map cleanly to one cluster:

```
src/kontor/
  ; ============================================================
  ; SUBSTRATE — data + time + identity (talks to datahike) — top-level
  ; ============================================================
  schema.clj                kernel attribute definitions (ADR-002)
  core.clj                  create-test-db, install-schema!, provider registration
  money.clj                 Money (BigDecimal + commodity), arithmetic (ADR-013)
  bitemporal.clj            :tx/valid-from + with-vt + resolver (ADR-048)
  entity.clj                :entity helpers + family walk (ADR-031)

  ; ============================================================
  ; WRITE PATH — verbs → balanced postings → validation gate — top-level
  ; ============================================================
  book.clj                  verb facade — entry!/receive!/pay!/sell!/buy!/… (ADR-095)
  posting.clj               build-transaction, sum-to-zero,
                            multi-(entity, ledger, commodity) (ADR-021, ADR-031, ADR-068)
  gate.clj                  transact-with-validation + registry
  validation.clj            transactor-gate middleware (ADR-011) — composes:
  invariant.clj                datopia/invariant vendored — datalog state-shape invariants
                            (sealing + legal-hold + period-lock + state-machine
                             + sum-to-zero — see policies below)

  ; banking/ : reconciliation + payments + bank refs
  banking/reconciliation.clj    bank-line → transaction matching scaffold
  banking/payment_application.clj  partial-payment primitive (ADR-043)
  banking/bank_csv.clj          generic CSV importer
  banking/bank_account.clj      :bank-account helpers (ADR-039)
  banking/payment_term.clj      due-date computation

  ; document/, import_/ : already-clustered substrate-ish entry points
  document/invoice.clj      kernel-side invoice records (post namespace-collision rename)
  import_/beancount.clj     Beancount round-trip (ADR-009)

  ; ============================================================
  ; READ PATH — datahike → views (all vt-aware) — reporting/
  ; ============================================================
  reporting/balance.clj         account-balance, bitemporal-aware
  reporting/ledger.clj          postings-against-account (ADR-021)
  reporting/trial.clj           trial-balance over a date range
  reporting/closing.clj         year-end close, retained-earnings rollup
  reporting/aging.clj           AR aging snapshot
  reporting/report.clj          declarative report engine + marginalize σ_E (ADR-096)
  reporting/financial_statements.clj  BS / P&L generators
  reporting/explain.clj         substrate "explain this number" graph walks (ADR-091)

  ; ============================================================
  ; POLICIES — cross-cutting middleware + lifecycle
  ; ============================================================
  ; compliance/ : seal + audit + legal-hold + retention + dsar + period
  compliance/sealing.clj        :posted-at + middleware (ADR-007)
  compliance/legal_hold.clj     :legal-hold blocks destructive writes (ADR-049)
  compliance/retention.clj      :retention-policy sweeper (ADR-050)
  compliance/dsar.clj           :dsar-request + collect (ADR-052)
  compliance/period.clj         open/close periods, soft/hard lock (ADR-014)
  compliance/audit_doc.clj      :audit-doc + :approval-policy + :privilege (ADR-038, ADR-051)

  ; workflow/ : status machines + processes + side effects
  workflow/status_machine.clj   :status-transition + :status-history +
                                record-status-change! (ADR-034)
  workflow/state_machine.clj    :transaction/state transitions (kernel-internal)
  workflow/side_effect.clj      :side-effect-intent dispatcher (ADR-041)
  workflow/side_effect/cross.clj  :cross-tx/step-id + CrossTxRouter + drain! (ADR-074)
  workflow/schedule.clj         :schedule recurring postings (ADR-032)
  workflow/process.clj          kontor.workflow.process / run-process orchestrator (ADR-067)
  workflow/event_bus.clj        in-process commit pub/sub (ADR-092)

  ; ============================================================
  ; PROVIDER PROTOCOLS — pluggable, called by both paths
  ; ============================================================
  ; tax/ : the whole tax stack (transactional + period + statute-as-data)
  tax/tax_rate_provider.clj     TaxRateProvider + TaxFacts + StaticTableProvider (ADR-071)
  tax/tax_posting_builder.clj   TaxPostingBuilder + StaticTablePostingBuilder (ADR-071)
  tax/period_tax_provider.clj   PeriodTaxProvider + TaxReturnFacts (ADR-099)
  tax/tax_return_posting_builder.clj  provision/payment posting builder (ADR-099)
  tax/tax_schedule.clj          schedule algebra — flat/bracket/capped/formula/elect/sum
                                + base-transform + apply-adjustments (ADR-099)
  tax/statute.clj               :tax-concept / :provision / :regime / :parameter
                                evaluator (ADR-101)
  tax/cgt.clj                   CGT helpers (ADR-103)
  ; Tax — kernel impls of PeriodTaxProvider
  tax/standalone_payroll_tax.clj  generic employer-payroll levy
  tax/corporate_income_tax.clj    flat-rate CIT on marginalized book profit
  tax/personal_income_tax.clj     schedule(income − deductions) − credits + surtaxes
  tax/vat_return.clj            periodic VAT/GST reconciliation (ADR-100)
  tax/sole_proprietor.clj       business-net + business-income-input (ADR-100)

  ; fx/ : FX conversion + rate providers
  fx/fx.clj                     Money-level convert / translate-* / to-functional-commodity
  fx/fx_rate_provider.clj       FxRateProvider + StaticTable / ECB / Chained (ADR-072)

  ; provider/ : remaining impl-of-protocol surfaces (payroll, costing,
  ;             valuation, e-invoice, consolidation, disposal)
  provider/payroll_provider.clj    PayrollComputeProvider + PayrollPostingBuilder
                                   + PayrollEmitProvider trio (ADR-075)
  provider/costing_provider.clj    CostingProvider — FIFO/LIFO/Avg/Std (ADR-029)
  provider/valuation.clj           :valuation-book + :valuation-layer (ADR-027/028)
  provider/einvoice_provider.clj   EInvoiceProvider + PureXmlProvider (ADR-017)
  provider/consolidation.clj       translate + eliminate + consolidate! (ADR-073)
  provider/disposal_provider.clj   DisposalProvider protocol (kernel side of kontor-disposal)

```

**Cluster mapping**: substrate + gate + write API + AI stay top-level; banking/document/import_ stay near write-path; reporting/compliance/workflow/tax/fx/provider are the clusters. The 5-axis frame is a narrative map for new readers; the directory clusters by domain so "where's the tax stuff?" / "where's the compliance stuff?" is a one-glance answer.

**On the ~20k LOC kernel**: the substrate (schema/money/bitemporal/entity/core) is ~5.3k LOC; providers ~5.0k; policies ~4.4k; write path ~3.8k; read path ~2.1k. The McComb-aligned substrate seams (ADR-090/091/092) and the tax-completion program (ADR-099 / ADR-101 / ADR-103 / ADR-113) account for most of the growth past the original Phase-1 envelope.

---

## Schema namespaces

Every attribute is namespaced so kontor can cohabit with companion modules and consumer apps in one datahike connection (ADR-002). The full kernel + companion namespace list is in [decisions.md §2.2](decisions.md#22-schema-namespace-discipline); here are the load-bearing groups:

```
; Core double-entry (Phase 0/1)
:account/*                charts of accounts
:account-tag/*            report-time aggregation tags
:journal/*                journals
:transaction/*            headers
:posting/*                individual postings
:commodity/*              currencies + tradeable units
:lot/*                    cost-basis lots
:partner/*                customers / vendors / contacts
                          (extended by partner companion)
:fiscal-position/*        tax + account remapping
:period/*                 open/close periods + sealing markers
:balance-assertion/*      pinned balance checkpoints

; Tax (Phase 1)
:tax/*                    tax definitions
:tax-rep/*                tax repartition lines
:tax-group/*              per-country VAT clearing groupings
:tax-application/*        per-(line, tax) computation record (ADR-016)

; Geography (ADR-023)
:country/*                ISO 3166 + external codes
:country-code/*           regulator codes per country
:country-group/*          trade blocs (EU, USMCA, …)
:state/*                  ISO 3166-2 + external codes
:state-code/*             regulator codes per state
:partner-state/*          partner ↔ state junction

; Analytic (ADR-012, ADR-022)
:analytic-plan/*          dimension definitions
:analytic-account/*       leaves
:analytic-distribution/*  how a posting splits across leaves

; Ledgers + entities (ADR-021, ADR-031)
:ledger/*                 parallel-book definitions
:posting-ledger/*         ledger ref on posting
:entity/*                 legal-entity scope
:posting-entity/*         entity ref on posting
:ledger-entity/*          per-(ledger, entity) overrides
:valuation-book-entity/*  per-(book, entity) overrides

; Valuation (ADR-027/028)
:valuation-book/*         parallel cost bases
:valuation-layer/*        per-receipt cost layers
:layer-consumption/*      FIFO/LIFO consumption
:layer-adjustment/*       manual adjustments

; Clearance + composition (ADR-024, ADR-025)
:attestation/*            government artifacts (IRN, EWB, PAC stamp)
:transaction-attestations/* junction
:complemento/*            CFDI / NF-e nested fragments
:transaction-complementos/* junction

; Scheduling + state machine (ADR-032, ADR-034, ADR-041)
:schedule/*               recurring postings
:schedule-occurrence/*
:status-transition/*      allowed transitions per (entity-type, facet)
:status-history/*         recorded transitions per entity
:side-effect-intent/*     cross-aggregate side-effect dispatcher
:account-type-direction/* debit/credit data table

; Audit + governance (ADR-038)
:audit-doc/*              supporting docs
:approval-policy/*        SoD enforcement rules

; Master data (ADR-039)
:partner-merge/*          non-destructive merge
:bank-account/*           external bank-account entity
:partner-bank-account/*   junction
:partner-tag/*            segmentation

; Jurisdiction (ADR-040)
:partner-tax-id/*         multi-tax-id-per-jurisdiction junction

; Money flow (ADR-043)
:payment-application/*    partial-payment primitive

; Bitemporal (ADR-048)
:tx/valid-from            on every writing tx
:tx/valid-to              (defaults to forever; postings never use)
```

Companion modules add their own namespaces (`:invoice/*`, `:order/*`, `:collection-case/*`, `:dispute/*`, …) without collision — that cohabitation is an architectural invariant (ADR-002).

---

## The double-entry kernel

A **transaction** is the atomic accounting unit. Within a transaction, postings sum to zero **per (entity, ledger, commodity)**. The single-entity / single-ledger case (the overwhelming default for SMBs) collapses to the familiar "debits = credits in this currency"; multi-ledger users add a `:posting/ledger` ref and the invariant holds per ledger; multi-entity users add `:posting/entity` and the invariant holds per (entity, ledger). ADR-021 + ADR-031.

A **posting** carries:
- `:posting/account` (which account is debited or credited)
- `:posting/amount` (positive = debit, negative = credit, in transaction currency)
- `:posting/commodity` (the unit of `amount`)
- `:posting/display-type` (`:product :tax :payment-term :rounding :section :note` — the Odoo discriminator)
- `:posting/partner` (optional override of the transaction's partner)
- `:posting/ledger` (optional; absent ⇒ primary ledger group; ADR-021)
- `:posting/entity` (optional; absent ⇒ unscoped; ADR-031)
- `:posting/posted-at` (set when the parent transaction is posted; sealing trigger)

The valid-time of a posting lives on its writing tx via `:tx/valid-from` (ADR-048). All postings written by one tx share one valid-from; the kernel builders stamp it from `:transaction/effective-date`.

**Postings are immutable once posted** (ADR-007 sealing rule). Corrections create new transactions that *reverse* and *replace*, not in-place edits.

**Multi-ledger example:**

```clojure
;; One sale transaction posting to two ledgers simultaneously — primary
;; (local GAAP) at €1000, IFRS at €1050 (different revenue recognition).
(posting/build-transaction
  {:transaction {...}
   :postings
   [{:posting/account [:account/code "1200"] :posting/amount  1000.00M
     :posting/commodity eur :posting/ledger [:ledger/code "primary"]}
    {:posting/account [:account/code "4400"] :posting/amount -1000.00M
     :posting/commodity eur :posting/ledger [:ledger/code "primary"]}
    {:posting/account [:account/code "1200"] :posting/amount  1050.00M
     :posting/commodity eur :posting/ledger [:ledger/code "ifrs"]}
    {:posting/account [:account/code "4400"] :posting/amount -1050.00M
     :posting/commodity eur :posting/ledger [:ledger/code "ifrs"]}]})
```

Each ledger balances independently. The SAP `RLDNR`-on-line-item pattern, adapted to event-sourced storage. IFRS-16 vs ASC-842 lease accounting, GAAP-vs-tax book differences, and full IFRS / HGB parallel books all ride this single mechanism.

---

## Bitemporal queries (ADR-008, ADR-048)

Every accounting query takes two implicit dimensions:

- **tx-time** — what the database knew at this transaction time. Datahike supplies this for free via `(d/as-of db tx-or-instant)`.
- **valid-time** — when the recorded fact was true in the world. kontor models this with one tx-meta attribute, `:tx/valid-from`, on the writing tx. Every datom in that tx inherits the valid-time. Readers resolve via `kontor.bitemporal/value-at` (single fact at a cutoff), `values-between` (a window), or `timeline` (full history of one (entity, attribute)).

The two axes compose. `(kbt/as-of-bitemporal db {:tx t :vt v})` returns a HistoricalDB pinned at tx-time `t`; the resolver applied on top of it answers "what did we think the fact was at valid-time `v`, as known by tx-time `t`."

Backdated corrections: wrap your tx-data with `(kbt/with-vt tx-data effective-date)`. The default `:vt-from` for postings is `:transaction/effective-date`; the default `:vt-to` is `forever`. Mid-flight corrections of a previous fact are *new writes at a past valid-time*, not in-place edits — append-only, audit-clean, ADR-007-compatible.

```clojure
;; Trial balance as of Dec 31 2024 as known on Mar 31 2025
(trial-balance conn
               {:as-of-valid #inst "2024-12-31"
                :as-of-tx    #inst "2025-03-31"})

;; Historical fact for one entity:
(kbt/value-at db invoice-eid :invoice/total-net #inst "2024-12-31")
```

XTDB v2 calls this "polygon resolution"; kontor gets the same shape from a tx-meta attribute plus a small resolver. Helpers in `kontor.bitemporal` (`as-of-bitemporal`, `value-at`, `with-vt`) make this ergonomic. The default — when neither parameter is passed — is now/now.

---

## Status machine and the audit story

Every state-bearing entity (invoice, transaction, dispute, case, promise, receipt, requisition, payment-promise, …) carries a *facet* — a `:db.type/keyword` attribute like `:invoice/status` or `:dispute/state`. State changes go through `kontor.workflow.status-machine/record-status-change!` (or its `…-tx-data` sibling for atomic composition), which:

1. Validates against `:status-transition` rows (per (entity-type, facet, from, to)).
2. Writes a `:status-history` row with `:reason`, `:reason-note`, `:supporting-doc`, `:changed-by-uid`, `:changed-at`, `:origin-transaction`.
3. Stamps the writing tx with `:tx/valid-from` (default: now; explicit `:vt-from` opt for backdated transitions, e.g. migration).
4. Enforces governance — ADR-038's `:no-self-approval`, `:requires-supporting-doc`, `:requires-non-empty-reason-note` — per a per-facet `:approval-policy` row.

"When did invoice X transition to `:paid`?" is a query over its `:status-history`, not a separate audit log. Restating the invoice's status at any past valid-time uses `(kbt/value-at db invoice :invoice/status cutoff)`. **The audit story is the same data as the operational data.**

Per ADR-048 the status-transition timestamp denorms (`:invoice/sent-at`, `:dispute/opened-at`, …) have been removed in favour of the `:status-history` + `:tx/valid-from` resolution. A few legacy denorms remain on `collection-case`, `credit-hold`, `dunning-pause`, and `payment-promise`; these are tracked as follow-ups.

---

## Provider protocols

kontor exposes seven pluggable seams. Each lets a consumer plug in their own logic (or a partner adapter) without touching the kernel.

### TaxRateProvider + TaxPostingBuilder (ADR-071)

ADR-071 splits the tax abstraction into three pieces that separate
rate-determination from posting-expansion — the older single-protocol
`TaxProvider` (ADR-005) conflated both and sat unused, so every l10n
module rolled its own posting builder. The trio is implemented in
`kontor.tax.tax-rate-provider` + `kontor.tax.tax-posting-builder`; the
legacy `kontor.tax-provider` namespace is removed.

```clojure
(defprotocol TaxRateProvider          ; rate determination — no chart
  (provider-id [this])
  (rate-facts  [this context]))       ;  → a TaxFacts (or nil)

;; TaxFacts is a pure-data record: :tax-use, :line-base, :commodity,
;; :jurisdiction, and :components — per-component rate items keyed by
;; :kind (:output-vat :input-vat :sales-tax :reverse-charge
;; :withholding :pre-collection :surcharge :cess :duty :fee), each
;; with :provenance and an opaque :jurisdiction-specific-codes slot.

(defprotocol TaxPostingBuilder        ; posting materialization — chart-aware
  (builder-id   [this])
  (tax-postings [this tax-facts opts])) ;  → vector of :posting/* maps
```

Implementations shipped: `StaticTableProvider` (reads the `:tax/*`
schema, respects effective-dated windows) and `StaticTablePostingBuilder`
(walks `:tax-rep` repartition lines); `AvalaraProvider` / `TaxJarProvider`
/ `SstCsvProvider` are throwing scaffolds — customers hold their own
keys (ADR-005); `ChainedProvider` composes. `compute-tax-postings`
wires the trio for one line.

### FxRateProvider (ADR-072)

Foreign-exchange rate lookup, IAS 21 / ASC 830 vocabulary:

```clojure
(defprotocol FxRateProvider
  (resolve-rate [this {:keys [from-commodity to-commodity at-date rate-type]}])
  (resolve-period-rates [this {:keys [from-commodity to-commodity from-date to-date rate-type]}])
  (provider-id [this]))
```

Rate-types: `:spot :closing :average :opening :historical`. Built-ins: `StaticTableProvider` (reads `:fx-rate/*` from the connected db with last-on-or-before fallback + inverse derivation + optional triangulation via a base commodity), `EcbReferenceRatesProvider` (StaticTable + EUR pivot + `ingest-ecb-csv-rows!` helper; ECB attribution string exported), `ChainedProvider`. Customer-credentialed scaffolds for `XeProvider`, `OandaProvider`, `FedH10Provider` — no API keys bundled.

`kontor.fx` wraps the provider with Money-level operations: `convert` (single Money), `translate-money-seq`, `translate-amounts-by-commodity`, `to-functional-currency`. `kontor.report/compute-report` grew a `:translate-to` + `:fx-provider` + `:rate-type` opt that adds `:line/value-translated` alongside each per-commodity `:line/value`. `kontor.lease.posting/plan-fx-retranslation` accepts an alternative `:fx-provider + :book-balance + :prior-rc-carrying` mode that computes the IAS 21 retranslation delta itself.

### CostingProvider (ADR-029)

Plug-in costing method for inventory valuation:

```clojure
(defprotocol CostingProvider
  (plan-receipt [this db request])
  (plan-consumption [this db request]))
```

Built-in impls: FIFO, LIFO, WeightedAverage, StandardCost. Drives `:valuation-layer` + `:layer-consumption` rows on inventory moves (`kontor.posting/plan-stock-move`).

### EInvoiceProvider (ADR-017)

Pure-data e-invoice generation. The kernel ships `PureXmlProvider` (UBL/Factur-X/NF-e shapes); signing and transmission happen in partner adapters that hold credentials.

### DepreciationProvider (ADR-055)

Per-asset depreciation method (straight-line, declining-balance, units-of-production). Plug per book — different books may depreciate the same asset on different schedules.

### LeaseProvider (ADR-063)

Per-lease classification + posting plug. The operating-lease ROU plug + `plan-fx-retranslation` provider mode let consumers compose IFRS 16 / ASC 842 lessee-side lease accounting at per-`(lease, ledger)` granularity. See `modules/lease/`.

### PeriodTaxProvider + statute-as-data (ADR-099, ADR-101)

Sibling of `TaxRateProvider` for *period / entity-incident* taxes — income / corporate / capital-gains / property / wealth / standalone employer-payroll. A tax is `(scope, base-selector, schedule) → liability → posting`; the period sibling fills the slots with `(entity × period, marginalize / σ_E, progressive brackets)`.

```clojure
(defprotocol PeriodTaxProvider
  (provider-id   [this])
  (return-facts  [this context]))         ; → TaxReturnFacts (multi-component)
```

The kernel ships three thin namespaces — `kontor.tax.tax-schedule` (the schedule algebra: `:flat`, `:bracket`, `:capped`, `:formula`, `:elect`, `:sum` + `:base-transform` + `:apply-adjustments` + `:schedule-override`), `kontor.tax.period-tax-provider` (the protocol + `TaxReturnFacts` + a closed 8-value `period-tax-kinds` enum), `kontor.tax.tax-return-posting-builder` (provision / payment via the verb facade) — plus four reference impls (`standalone-payroll-tax`, `corporate-income-tax`, `personal-income-tax`, `vat-return`).

**Statute-as-data (ADR-101)** lifts tax law itself into queryable substrate. Four schema namespaces:

- `:tax-concept` — cross-jurisdiction concept catalogue (`:participation-exemption`, `:rollover-relief`, `:loss-bucket`, `:lifetime-cap`, …). Composes with ADR-090 `:concept-iri`.
- `:provision` — per-jurisdiction encoded statute rule. Closed predicate vocab + `:provision/compute-fn` escape hatch; default + exception via ordered `:priority` + `:exception-of`.
- `:regime` — elective container. Election rides ADR-034 status-machine; counterfactual via `:regime/extends`.
- `:parameter` + `:parameter-value` + `:parameter-bracket` — date-keyed value history (OpenFisca-style parameter tree).

`kontor.tax.statute/apply-provisions` is the single evaluator that folds provisions in priority order, raises `kontor.tax/ambiguous-provision` on same-priority conflicts. Per-country CIT and CGT ship as `:provision` data — **all 11 CIT jurisdictions and 12 CGT jurisdictions** on the path.

### DisposalProvider (ADR-103)

The capital-gains tax substrate. `:disposal/*` is a companion (`modules/disposal/`); the kernel exposes `kontor.provider.disposal-provider/DisposalProvider` as the protocol every CGT provider depends on. **Twelve per-jurisdiction CGT providers** ship in `modules/l10n-{us,de,ca,au,uk,jp,fr,br,in,mx,cn,at}/`, each producing 0+ `:capital-gains-tax` components.

### PayrollProvider trio (ADR-075)

```clojure
(defprotocol PayrollComputeProvider (compute-payroll-run [this facts]))
(defprotocol PayrollPostingBuilder  (payroll-postings    [this run]))
(defprotocol PayrollEmitProvider    (emit-statutory      [this run]))
```

Compute → post → emit. Eleven country adapters (`modules/payroll-{de,us,ca,fr,au,br,mx,in,jp,cn,at}`), orchestrated by `kontor.hr.payroll/run-payroll!`.

### CrossTxRouter (ADR-074)

Cross-DB atomic-feel commits — `kontor.workflow.side-effect.cross` extends ADR-041's `:side-effect-intent` so the "side effect" can be a tx-data commit against a *different* datahike conn (another kontor instance, a stratum secondary index, a scriptum audit log). Saga + content-hash idempotency via a `:cross-tx/step-id` attribute (`:db.unique :db.unique/identity`), not XA / JTA.

```clojure
(defprotocol CrossTxRouter
  (resolve-conn [_ system-id]))  ; consumer's system-id → conn mapping
```

A worker `drain!`'s pending `:cross-tx-post` intents: claim intent → resolve target conn → check target for the deterministic SHA-256-derived step-id → skip-or-transact-augmented → mark done (or failed). Crash-safe — if the worker dies after target-commit but before mark-done, the next worker sees the step-id present and goes straight to mark-done.

---

## Multi-entity consolidation (ADR-073)

`kontor.provider.consolidation` provides the substrate primitive for translating + eliminating across a multi-entity family:

- **`translate-trial-balance-tx-data`** — per IAS 21 / ASC 830 rate-types, translate one operating entity's trial balance into the consolidation entity's presentation commodity. CTA plug posts the translation residual to a designated account.
- **`eliminate-intercompany-pair-tx-data`** — given a `:transaction/intercompany-pair-id` shared across N source txs, emit one elimination tx whose postings exactly negate every paired posting, stamped with `:posting/entity = elimination-entity`.
- **`consolidate-tx-data` + `consolidate!`** — walks `kontor.entity/family`, runs translation per :operating entity + elimination per pair-id, commits all fragments as `kontor.process/run-process` steps under one validation gate.

This is the FX-translation + intercompany-elimination substrate. Group-tax consolidation (single CIT return across a family of entities) is a separate substrate — see below.

---

## Fiscal-unit substrate (ADR-113)

For group-tax regimes that file ONE CIT return for a family of entities (DE Organschaft, FR intégration fiscale, US §1502, JP group-tsuusan, UK group relief, AT Gruppenbesteuerung, AU TCR, CN CCSV, MX RIGS), kontor ships a kernel-level `:fiscal-unit` substrate independent of `kontor.provider.consolidation` (which is FX + elimination, not tax aggregation).

Three attribute groups: `:kontor.fiscal-unit/*` (code / name / parent-entity / regime / computation-style / elected-from / minimum-term-ends / anchor-document / status), `:kontor.fiscal-unit-member/*` (member entity refs + per-member ownership-fraction + role), `:kontor.transaction/elimination-*` (slots on transactions for intra-group elimination).

`kontor.tax.fiscal-unit/run-group-tax!` dispatches on `:computation-style`:

- `:single-base` — sum members' bases into one filing (DE Organschaft, FR intégration). One filing, one liability.
- `:per-member-with-netting` — each member files, losses surrender within the group (UK group relief).
- `:loss-surrender` — members file independently, election surrenders losses (CN CCSV).

`kontor.tax.statute/compose-aggregate-of` (sibling of `compose-greater-of`) records the **economic delta** vs separate filing: elected liability + sum(separate liabilities) + delta. The audit story is "every member could have filed alone; here is what the election bought."

**v1 DE Organschaft pilot ships** in `modules/l10n-de/organschaft_provider.clj` — wraps the DE CIT provider, sums per-member `:gewinn-aus-gewerbebetrieb`, delegates to KSt+Soli+GewSt math, matches the BMF Müller-Gruppe worked example to the cent (€237,375 KSt+Soli; €158,250 economic delta). Other jurisdictions land as consumer demand surfaces.

---

## Sealing

```clojure
;; Application middleware refuses silent retract of posted entries.
(defn transact-with-sealing [conn tx-data]
  (let [forbidden (find-silent-retracts-of-posted tx-data (d/db conn))]
    (when (seq forbidden)
      (throw (ex-info "Refused: silent retract of posted entries"
                     {:posted-eids (mapv first forbidden)
                      :remediation "Use [:db/purge ...] explicitly"})))
    (d/transact conn tx-data)))
```

A posting becomes legally binding when `:posting/posted-at` is set (ADR-007). The sealing middleware refuses silent retracts after that. Explicit `:db/purge` is permitted — it is itself a recorded commit, so right-to-erasure obligations are satisfied without breaking the audit chain.

Posting transitions (draft → posted) propagate `:transaction/posted-at` to every child posting's `:posting/posted-at` — this is what the sealing middleware enforces.

---

## Audit + governance (ADR-038)

The `:audit-doc` + `:approval-policy` primitives provide the structural backbone for audit-defensible state transitions:

- **`:audit-doc`** — any supporting document attached to a `:status-history` row (engagement letter, board resolution, signed correction, vendor onboarding KYC, write-off justification). Carries a content hash + URI; the URI's storage is out of kernel scope.
- **`:approval-policy`** — per-(entity-type, facet) rules enforced at status-change time. `:no-self-approval`, `:requires-supporting-doc`, `:requires-non-empty-reason-note`, `:approver-role`. Composable.

Commit-level cryptographic integrity rides on datahike's `:crypto-hash? true` (SHA-512 Merkle-tree across EAV/AEVT/AVET). A planned upstream PR ("Track B") adds per-commit SHA-256 leaf hashes + commit signatures; the kernel's `audit-doc` surface will cut over when it lands.

---

## Compliance: legal-hold, retention, DSAR (ADR-049 / ADR-050 / ADR-052)

Three substrate-level primitives for regulator-facing obligations:

- **`kontor.compliance.legal-hold`** (ADR-049) — `:legal-hold` blocks destructive writes (retraction, purge) against held entities. Hold lifecycle rides ADR-034 status-machine. Middleware runs BEFORE sealing's no-silent-retract check so the more-specific "blocked by hold X" error wins on destructive-write-of-posted-held-entity.
- **`kontor.compliance.retention`** (ADR-050) — `:retention-policy` rules + sweeper that surfaces purge candidates (never auto-purges; the operator decides). Composable with `kontor.compliance.legal-hold` — held entities are never candidates regardless of retention rule.
- **`kontor.compliance.dsar`** (ADR-052) — `:dsar-request` + `kontor.compliance.dsar/collect` walks an entity's connected graph to assemble the data-subject-access export. Supports GDPR Art. 15 / CCPA-style queries.

## Verb facade (ADR-095)

`kontor.book` is the maintainer-facing surface for business writes — `entry!`/`receive!`/`pay!`/`sell!`/`buy!`/`receive-payment!`/`pay-bill!`/`transfer!`/`adjust!` + matching `*-tx-data` builders per ADR-068. Each verb composes `posting/build-transaction` with appropriate defaults (journal selection, partner inference, entity scoping) and routes through `validation/transact-with-validation`. Companion modules (invoice / sales / procurement / collections) layer their own verbs on top using the same gate.

## Substrate seams: explain + event-bus (ADR-091, ADR-092)

Two McComb-aligned seams for "data outlives applications" composition:

- **`kontor.explain`** (ADR-091) — substrate "explain this number" graph walks. `explain-balance`, `explain-posting`, `entities-with-concept-iri`. Pure read-only datalog returning plain Clojure maps; useful for audit-trail UIs and AI-tool integrations.
- **`kontor.workflow.event-bus`** (ADR-092) — in-process pub/sub on commit. `register-handler!` / `commit-and-emit` / `:transaction/committed` event kind. ADR-001 single-dep posture preserved — consumers wanting Kafka/NATS write an adapter.

The McComb arc also introduces `:concept-iri` as a generalized seam across substrate entities (`:account`, `:partner`, `:commodity`, `:tax`, `:document-type` per ADR-090) — stable IRIs into XBRL / FIBO / gist / internal taxonomies.

## Beancount round-trip

Beancount is the most-respected open-source double-entry implementation. kontor ships a parser and dumper to/from the datahike representation. ADR-009 makes round-tripping a representative `.beancount` file a Phase-1 acceptance criterion: parse, transact, dump, byte-diff. This pins kontor's semantics against a known-correct reference. See `src/kontor/import_/beancount.clj` and `examples/example.beancount`.

---

## How modules compose

Every companion module declares its own attribute namespaces (`:invoice/*`, `:order/*`, `:collection-case/*`, `:dispute/*`, …) and ships an `install!` function that transacts its schema atop the kernel's. A tenant's `(install-schema! conn)` call is composed:

```clojure
(defn install-all! [conn]
  (kontor.core/install-schema! conn)
  (kontor.partner.schema/install! conn)
  (kontor.sales.schema/install! conn)
  (kontor.invoice.schema/install! conn)
  (kontor.procurement.schema/install! conn)
  (kontor.collections.schema/install! conn))
```

The cohabitation invariant (ADR-002) means none of these collide. Consumers can pick any subset.

Writing an invoice through `kontor-invoice` produces one tx that:

1. Sets `:invoice/status` `:draft → :sent` and writes a `:status-history` row.
2. Builds the matching `:transaction` + `:posting`s via `kontor.posting/build-transaction`.
3. Stamps `:tx/valid-from` from the invoice's effective date.
4. (Per ADR-038) records `:reason`, `:reason-note`, `:supporting-doc`, `:changed-by-uid`.

All atomic. Sum-to-zero validated. Bitemporally indexed. The reverse — voiding a sent invoice — emits a *reversing* transaction (not an in-place edit) per ADR-007.

---

## Cross-platform: the read side runs in the browser

kontor's substrate is `.cljc`, and the whole **read side runs on
ClojureScript against datahike-cljs** — not as a reimplementation, but
the *same code*. `account-balance`, `trial-balance`, the account-ledger
statement (`postings-against` / `running-balance`), the declarative report
engine, and the financial statements (`compute-statement` → P&L / balance
sheet) all execute in a browser or Node runtime and produce numbers
identical to the JVM, because they *are* the JVM code. `Money`
(BigDecimal-backed on the JVM, fress `Bigdec` on cljs) and the bitemporal
resolver are `.cljc` too, so amounts store and sum with full decimal
fidelity client-side.

This is what lets a consumer compute a trial balance or a statement
locally — offline, or optimistically ahead of a server round-trip —
without a second, drifting implementation.

**The write path is portable too.** A browser can build a balanced,
sealed entry (`kontor.book.build/entry-tx-data`), commit it through the
same validation gate the server uses (`kontor.gate/transact-with-validation`
— the invariant pass plus the `[:db.fn/call validate-and-apply …]`
structural validators), and read it straight back — all on datahike-cljs
with real `:db.type/bigdec` amounts. The gate commits via datahike's
async `transact!` on cljs and the synchronous `transact` on the JVM; the
validation is identical, so an unbalanced entry is rejected in the browser
with the same `:validation/sum-to-zero` it raises on the server
(`kontor.posting-write-cljs-test`). What remains JVM-only is the
`kontor.book` `!` verb facade (thin sugar over the portable builder) and
`kontor.posting`'s inventory-costing helpers (`plan-stock-move`) — a
backend concern, not something a client posts.

The portable core lives in `modules/substrate/`; the read namespaces live
in `src/kontor/reporting/*.cljc`. A Node test lane
(`kontor.node-runner`, run by `./bin/run-cljstests` in CI) executes every
portable namespace against a real datahike-cljs db, and
`kontor.reporting-portability-test` (JVM) fails the build if a reporting
namespace ever ships without a cljs exercise — the guarantee cannot rot
silently. ADR-118-era research notes 191 / 192 record the port.

## What lives where

| Concern | Layer |
|---|---|
| Branches, commits, history, content addressing | datahike upstream |
| SHA-256 leaf hash, commit signature hook | datahike upstream (planned PR) |
| Schema, postings, balance, period, sealing, audit-doc, status machine, bitemporal resolver, provider protocols | kontor kernel |
| Order / invoice / partner / procurement / collections workflows | companion modules (`modules/`) |
| Per-country charts, tax rates, return computations | l10n companion modules |
| Bank-statement parsers | bank companion modules |
| E-invoice signing, PAC submission, SEFAZ transmission, Peppol AP delivery, Avalara/TaxJar | partner adapters (never bundle credentials) |
| UI, routing, auth, PDF rendering | consumer apps (beleg, simmis, your app) |

---

## How the substrate evolves

The kernel does not evolve by accretion — every ADR records a *why*.
To extend the schema, write an ADR, reference it from the code, then
ship. End-to-end user stories under [`doc/showcases/`](showcases/)
exercise the substrate against authority-published worked examples so
regressions are caught before they reach a consumer. This is the
contract that lets you build on kontor without forking the kernel.
