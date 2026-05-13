# Architecture

A bird's-eye view of how `kontor` is laid out, why each layer exists, and how it composes with the surrounding stack (datahike, companion modules, partner adapters, consumer apps).

For *why* each choice was made, see [decisions.md](decisions.md). For coding/API conventions across the kernel and companions (transactor opts shape, status-machine writes, valid-time stamping, namespacing, money discipline), see [conventions.md](conventions.md). This document describes the *what*.

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
│  kontor kernel — EPL-1.0, one dependency (datahike)                  │
│                                                                      │
│   Schema + lifecycle                                                 │
│   Balanced postings + multi-ledger + multi-entity                    │
│   Time: periods + bitemporal valid-time                              │
│   Sealing + audit-doc + approval-policy                              │
│   Status machine (cross-cutting)                                     │
│   Tax / Costing / E-invoice provider protocols                       │
│   Money flow: payment-application, bank-account, payment-term        │
│   Reporting: trial-balance, P&L, BS, declarative report engine       │
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

After Stage L the kernel is ~14k LOC across ~30 namespaces, organized by role:

```
src/kontor/
  ; --- Schema + lifecycle ---
  schema.clj                kernel attribute definitions (ADR-002)
  core.clj                  create-test-db, install-schema!,
                            provider registration

  ; --- Money primitives ---
  money.clj                 Money (BigDecimal + commodity), arithmetic (ADR-013)

  ; --- Balanced postings (the heart of the kernel) ---
  posting.clj               build-transaction, sum-to-zero,
                            multi-ledger + multi-entity (ADR-021, ADR-031)
  balance.clj               account-balance, bitemporal-aware
  ledger.clj                postings-against-account (ADR-021)
  trial.clj                 trial-balance over a date range
  closing.clj               year-end close, retained-earnings rollup

  ; --- Time ---
  period.clj                open/close periods, soft/hard lock (ADR-014)
  bitemporal.clj            :tx/valid-from + resolver (ADR-048)
  query.clj                 bitemporal convenience helpers

  ; --- Sealing + audit ---
  sealing.clj               :posted-at + middleware (ADR-007)
  audit.clj                 commit-hash wrapper (ADR-003)
  audit_doc.clj             :audit-doc + :approval-policy (ADR-038)

  ; --- State machine (cross-cutting) ---
  status_machine.clj        :status-transition + :status-history +
                            record-status-change! (ADR-034)
  state_machine.clj         :transaction/state transitions (kernel-internal)
  side_effect.clj           :side-effect-intent dispatcher (ADR-041)
  schedule.clj              :schedule recurring postings (ADR-032)

  ; --- Provider protocols ---
  tax_provider.clj          TaxProvider protocol + impls (ADR-005)
  tax.clj                   apply-tax to a posting
  costing_provider.clj      CostingProvider protocol — FIFO/LIFO/Avg/Std (ADR-029)
  valuation.clj             :valuation-book + :valuation-layer (ADR-027/028)
  einvoice_provider.clj     EInvoiceProvider protocol + PureXmlProvider (ADR-017)

  ; --- Money flow ---
  payment_application.clj   partial-payment primitive (ADR-043)
  reconciliation.clj        bank-line → transaction matching scaffold
  bank_account.clj          :bank-account helpers (ADR-039)
  bank_csv.clj              generic CSV importer
  payment_term.clj          due-date computation
  aging.clj                 AR aging snapshot

  ; --- Identity + scope ---
  entity.clj                :entity helpers (ADR-031)

  ; --- Reporting ---
  report.clj                declarative report engine
  financial_statements.clj  BS / P&L generators

  ; --- I/O ---
  import_/beancount.clj     Beancount round-trip (ADR-009)

  ; --- Validation ---
  validation.clj            datopia/invariant middleware (ADR-011)
```

The Phase-1 target of <2k LOC reflected the Phase-1 surface; Stages H-L added entity scope, valuation, status machine, audit/governance, master-data, jurisdiction, and workflow primitives needed to compose the companions.

---

## Schema namespaces

Every attribute is namespaced so kontor can cohabit with companion modules and consumer apps in one datahike connection (ADR-002). The kernel namespace list, as of Stage L:

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

XTDB v2 calls this "polygon resolution"; kontor gets the same shape from a tx-meta attribute plus a small resolver. Helpers in `kontor.query` make this ergonomic. The default — when neither parameter is passed — is now/now.

---

## Status machine and the audit story

Every state-bearing entity (invoice, transaction, dispute, case, promise, receipt, requisition, payment-promise, …) carries a *facet* — a `:db.type/keyword` attribute like `:invoice/status` or `:dispute/state`. State changes go through `kontor.status-machine/record-status-change!` (or its `…-tx-data` sibling for atomic composition), which:

1. Validates against `:status-transition` rows (per (entity-type, facet, from, to)).
2. Writes a `:status-history` row with `:reason`, `:reason-note`, `:supporting-doc`, `:changed-by-uid`, `:changed-at`, `:origin-transaction`.
3. Stamps the writing tx with `:tx/valid-from` (default: now; explicit `:vt-from` opt for backdated transitions, e.g. migration).
4. Enforces governance — ADR-038's `:no-self-approval`, `:requires-supporting-doc`, `:requires-non-empty-reason-note` — per a per-facet `:approval-policy` row.

"When did invoice X transition to `:paid`?" is a query over its `:status-history`, not a separate audit log. Restating the invoice's status at any past valid-time uses `(kbt/value-at db invoice :invoice/status cutoff)`. **The audit story is the same data as the operational data.**

Per ADR-048 the status-transition timestamp denorms (`:invoice/sent-at`, `:dispute/opened-at`, …) have been removed in favour of the `:status-history` + `:tx/valid-from` resolution. A few legacy denorms remain on `collection-case`, `credit-hold`, `dunning-pause`, and `payment-promise`; these are tracked as follow-ups.

---

## Provider protocols

kontor exposes three pluggable seams. Each lets a consumer plug in their own logic (or a partner adapter) without touching the kernel.

### TaxProvider (ADR-005)

```clojure
(defprotocol TaxProvider
  (resolve-taxes [this {:keys [partner posting context]}]
    "Given a context (date, partner country, fiscal position, ...)
     and a base posting, return [] or N additional postings that
     materialize the applicable taxes.")
  (provider-id [this]))
```

Implementations: `StaticTableProvider` (per-country EDN, default), `SstCsvProvider` (US Streamlined Sales Tax), `AvalaraProvider` / `TaxJarProvider` (customer's API key — never bundled).

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

Commit-level cryptographic integrity rides on datahike's `:crypto-hash? true` (SHA-512 Merkle-tree across EAV/AEVT/AVET). A planned upstream PR ("Track B") adds per-commit SHA-256 leaf hashes + commit signatures; `audit.clj` will cut over when it lands.

---

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

Each stage (J, K, L, M, …) follows a three-step pattern, codified by ADR-037:

1. **Research-before.** Background agents study a license-clean reference implementation (OFBiz Apache-2.0, Sylius MIT, KillBill Apache-2.0) at file:line depth, plus market-pain research from G2/Capterra/Trustpilot/OSS issue trackers. Output: notes in `doc/research/`.
2. **Implement.** Draft ADRs in `doc/decisions.md`. Schema → helpers → tests. One ADR per coherent commit; `bb test` after each.
3. **Review-after.** Independent code-review + market-pain-review agents audit the result. P0s fixed before the stage closes; P1s/P2s triaged into followups.

Cross-stage user stories (`doc/showcases/0[1-4]_*`) validate the substrate end-to-end and surface the integration friction that per-stage research cannot see.

The kernel does not evolve by accretion — every ADR records a *why*. To extend the schema, write an ADR (numbered, in `doc/decisions.md`), reference it from the code, then ship. This is the contract that lets you build on `kontor` without forking the kernel.
