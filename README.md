# kontor

A double-entry bookkeeping **kernel** for Clojure, built on
[datahike](https://github.com/replikativ/datahike). `kontor` ships the
ledger semantics an accountant needs — accounts, journals, balanced
postings, periods, tax engine, audit trail, e-invoice envelopes,
parallel ledgers, analytic dimensions — and the extension seams a
software architect needs to bolt on jurisdictions, government
clearance flows, and downstream filing artifacts. The kernel itself
is EPL-1.0 with a single dependency (datahike); per-country charts,
tax data, and e-invoice schemas live in separate, license-tagged
modules so consumers pull only what they need.

> **Status.** Kernel + 26 ADRs landed (schema, sealing, periods,
> parallel ledgers, analytic accounting, multi-attestation
> lifecycle, document composition, effective-dated tax rates,
> first-class country/state entities). Per-country modules at
> varying maturity: Germany (UStVA, SKR04 chart, DATEV, year-end
> close, Factur-X/XRechnung wrapper) is the most complete; Brazil
> ships an NF-e 4.0 XML emitter plus SPED; India ships GST 2.0 tax
> engine plus IRN and e-way bill payload builders; Mexico ships
> identifiers, CFDI 4.0 envelope and complemento composition;
> Canada ships GST/HST plus year-versioned T1/T2125/BC428 schedules
> and NoA ingestion; Japan, Australia, China, France, Austria, US
> ship scaffolded charts and a working tax-return computation.

## What it covers today

**Bookkeeping primitives.**

- Double-entry postings with **per-ledger sum-to-zero per commodity**
  (a single source transaction may post different amounts to the
  primary ledger and an IFRS / HGB / US-GAAP secondary ledger; each
  ledger balances independently).
- **Multi-currency** with a typed `Money` value (BigDecimal + commodity
  ref). HALF-EVEN rounding by default; HALF-UP available for
  regulators that mandate it.
- **Bitemporal queries** — every read takes `:as-of-tx` (transaction
  time, what the books knew when) and `:as-of-valid` (valid time,
  when the fact was true). Tx-time comes from datahike's history;
  valid-time is modelled explicitly on each posting and transaction.
- **Period model** with two-level locking: a *soft* `:period/locked-at`
  that can be reopened (auditable commit), and a *hard* `:period/sealed-at`
  that is monotone and irrevocable. Plus a special-period
  (`:period/adjustment?`) for German year-end adjustments (SAP-style
  periods 13-16).
- **Pre-close hook** — `period/close!` runs configurable checks (no
  draft transactions in range, trial-balance-zero per commodity,
  …) before locking.
- **Year-end close** — closing entry rolls revenue and expense
  postings into retained earnings.
- **Parallel ledgers** (ADR-021). One transaction, N book-specific
  postings: primary, IFRS, local-GAAP, budget, statistical,
  adjustment-only.
- **Analytic dimensions** (ADR-012, ADR-022): cost centers, profit
  centers, projects. Per-account required-plans flag, sum-to-100
  invariant per plan, choice of Odoo-style per-line distribution or
  SAP-style split-line expansion.
- **External-codes pattern** (ADR-019). One account may carry many
  regulator codes — internal code, SKR04, DATEV, IFRS group,
  Brazilian Plano Referencial, Indian ICAI, Chinese ASBE — all
  queryable as data without schema changes per new regulator.

**Tax engines per jurisdiction.**

| Jurisdiction | Module | What it computes |
|---|---|---|
| Germany | `l10n-de` | UStVA (Umsatzsteuer-Voranmeldung), 8 load-bearing Kennzahlen on SKR04 |
| Austria | `l10n-at` | UVA (Umsatzsteuervoranmeldung), Kennzahl-based monthly return |
| France | `l10n-fr` | CA3 (Cerfa 3310-CA3) monthly TVA |
| Canada | `l10n-ca` | GST/HST (GST34-2), QST + BC/SK/MB PST preparatory reports |
| Australia | `l10n-au` | GST + BAS labels (G1/G2/G3/…, 1A/1B) |
| Japan | `l10n-jp` | Consumption Tax (10% standard / 8% reduced / 3 zero-tax categories) |
| China | `l10n-cn` | VAT (13/9/6/3/0%) + fapiao lifecycle |
| India | `l10n-in` | GST 2.0 (0/0.25/3/5/18/40 + cess), CGST+SGST / IGST / UTGST dispatch keyed on place-of-supply |
| Brazil | `l10n-br` | Legacy stack (ICMS by state matrix + IPI + PIS + COFINS + ISS + IRPJ + CSLL) + new IBS/CBS dual-VAT transition |
| Mexico | `l10n-mx` | RFC / CURP / CLABE validators; IVA/IEPS scaffolded |
| US | `l10n-us` | Per-state sales-tax filing report scaffold; computation deferred to Avalara/TaxJar |

The kernel ships a `TaxProvider` protocol (ADR-005); each module
loads its rates as data. Rates are **effective-dated** (ADR-026):
a posting dated 2024-11-15 resolves IGST against the pre-GST-2.0
record; a posting dated 2025-09-22 resolves against the new
slab. Historical amendments round-trip correctly.

**E-invoice + clearance support.**

| Format | Module | Notes |
|---|---|---|
| Factur-X / XRechnung 3.0 / ZUGFeRD | `einvoice-de` | Wraps Mustang (APL2). PDF/A-3 + embedded UBL/CII. |
| Peppol PINT JP | `l10n-jp` | UBL 2.1 Invoice, JP customization; T-number on PartyTaxScheme. |
| Peppol PINT A-NZ | `l10n-au` | UBL 2.1, sole supported A-NZ profile since 2025-05-15; ABN on PartyTaxScheme. |
| NF-e 4.0 | `l10n-br` | XML emitter verified against SEFAZ Manual 4.01 + NT 2018.001. |
| CFDI 4.0 + complementos | `l10n-mx` | Envelope + ordered complemento composition (ADR-025); Pagos / Carta Porte / Nómina hooks. |
| IRN payload (NIC IRP) | `l10n-in` | Canonical JSON for the Indian e-invoice portal. |
| E-way bill payload | `l10n-in` | Separate attestation; depends-on graph back to the IRN. |
| Fapiao (Chinese e-invoice) | `l10n-cn` | Tracks the STA platform `:pending-attestation` → `:posted` lifecycle. |

The kernel ships the **pure emitters** (XML / JSON / UBL); signing,
PAC submission, SEFAZ transmission, IRP submission, and Peppol
Access Point delivery live in **partner adapters** that never bundle
credentials.

**Audit and sealing.**

- A posting becomes legally binding when `:posting/posted-at` is set
  (ADR-007). After that the sealing middleware refuses silent
  retracts. Explicit `:db/purge` is still permitted — it is itself a
  recorded commit, so right-to-erasure obligations are satisfied
  without breaking the audit chain.
- A `:pending-attestation` lifecycle state (ADR-018) covers the
  in-flight window between submission to an authority (SEFAZ,
  IRP, STA platform) and the authority's response. Sealing does not
  fire while pending.
- **Multi-attestation per transaction** (ADR-024). One Indian goods
  invoice may carry an IRN attestation *and* an e-way bill
  attestation, each with its own validity window and a
  `depends-on` reference. Italian *integrazione*, Saudi ZATCA's
  PIH-chain, and Korean NTS chains use the same shape.
- Commit-level audit hashing rides on datahike's `:crypto-hash?`
  config (SHA-512 Merkle tree across the EAV/AEVT/AVET indices).

## Architecture

```
  +---------------------------------------------------------------------+
  |     Consumer apps (beleg invoicing; simmis ERP-shaped workloads;    |
  |                    your CRM / your portal)                          |
  +---------------------------------------------------------------------+
                                  |
  +---------------------------------------------------------------------+
  |  Partner adapters (NEVER bundle credentials, NEVER in the kernel):  |
  |   PAC providers (MX), IRP / EWB providers (IN), SEFAZ transmitters  |
  |   (BR), Peppol Access Points (JP/AU/DE), Avalara/TaxJar (US sales). |
  +---------------------------------------------------------------------+
                                  |
  +-----------+   +-----------+   +-----------+   +-----------+
  | l10n-de   |   | l10n-br   |   | l10n-in   |   | l10n-mx   | ...
  |  SKR04    |   |  NF-e 4.0 |   |  IRN+EWB  |   |  CFDI 4.0 |
  |  UStVA    |   |  ICMS+IBS |   |  GST 2.0  |   |  IVA+IEPS |
  |  DATEV    |   |  SPED     |   |  Place-of |   |  Complem- |
  |  Factur-X |   |  PIS/COF  |   |  -supply  |   |  entos    |
  +-----------+   +-----------+   +-----------+   +-----------+
       |               |               |               |
       +---------------+---------------+---------------+
                       |
  +---------------------------------------------------------------------+
  |  kontor kernel  —  EPL-1.0,  one dependency (datahike)              |
  |                                                                     |
  |   schema  posting  money  tax  sealing  audit  balance  trial       |
  |   period  ledger   query  validation     analytic   beancount       |
  |   country/state    document-type    attestation     complemento     |
  +---------------------------------------------------------------------+
                                  |
  +---------------------------------------------------------------------+
  |  datahike  —  event-sourced EAV store with native bitemporality     |
  +---------------------------------------------------------------------+
```

The pattern is **kernel + per-jurisdiction modules + partner
adapters**. The kernel knows nothing about a specific country; an
l10n module installs a chart of accounts, a tax-rate stack, and
optionally an e-invoice emitter. A partner adapter takes that
emitter's output, signs it, and exchanges it with the relevant
authority. Each layer can be swapped — replace the
`StaticTableProvider` with an Avalara `TaxProvider` for US sales
tax, replace the `PacProvider` for Mexican CFDI with a different
vendor, plug a different Peppol AP behind the JP / AU UBL
emitters — without touching the kernel.

## Design pillars

**Bitemporal by default (ADR-008).** Every read accepts `:as-of-tx`
and `:as-of-valid`. Restating a 2024 invoice in 2026 picks up the
rate that was legally in force on the original date, not the rate
the database holds today; auditors can ask "what did the books look
like *as filed* on 2025-04-15" and get the answer from the same
data. No bolt-on temporal table, no shadow history.

**Multi-attestation lifecycle (ADR-024).** A single transaction may
carry zero or more government-issued artifacts (IRN, e-way bill,
PAC stamp, CFDI UUID, NF-e access key, fapiao number, ZATCA stamp),
each with its own format, validity window, and `depends-on`
reference to a parent attestation. This is what makes Indian goods
movement (IRN + EWB), Italian *integrazione*, Saudi PIH-chains, and
Polish KSeF version-time-dispatch tractable in one schema. The
architect gets a uniform query surface; the accountant gets a
correct audit story for the half-issued, half-expired,
half-superseded reality of multi-document clearance regimes.

**Document composition (ADR-025).** A CFDI 4.0 is an envelope plus
N stacked complementos (TimbreFiscalDigital, Pagos 2.0, Carta
Porte 3.1, Nómina 1.2, ComercioExterior, …). The kernel models
this directly: a `:complemento` entity per fragment, ordered, with
the XML namespace as a queryable field. The same shape covers
Peppol UBL extensions and NF-e information groups. l10n-mx owns
the XSD validation; the kernel owns the assembly.

**Effective-dated tax rates (ADR-026).** Tax records carry
`:tax/effective-from` and `:tax/effective-until` timestamps. The
provider's rate resolution picks the record whose window contains
the transaction's effective date. Historical postings round-trip
against the rate that was in force on the invoice date — without
manual rate-stack switching. Real-world cases that drove this:
India's GST 2.0 cutover on 2025-09-22, Mexico's annual IEPS cuota
refresh, Brazil's CBS/IBS transition rates that apply only during
2026-2027, the German VAT-on-restaurants temporary cut of
2020-2023.

**Country + state entities with external-codes (ADR-023).** Country
and state are first-class entities, identified by ISO 3166 codes
and carrying per-regulator external codes (Indian GSTN state code,
Brazilian IBGE, Canadian CRA province code, SAT `c_Estado`, UN
M49). Place-of-supply on a transaction is a ref to a state — the
same model serves Indian CGST/SGST/IGST dispatch, Canadian
HST/GST+PST split, Brazilian state-level ICMS, and Mexican CFDI's
`c_Estado` catalog with one schema lift. Adding a new regulator
never changes the schema.

**Parallel ledgers (ADR-021).** A `:posting/ledger` ref tags every
posting; one transaction can post different amounts to a *primary*
ledger and one or more *secondary* ledgers (IFRS, HGB, US-GAAP,
budget, statistical). Sum-to-zero is **per ledger** within a
transaction — you cannot net an IFRS debit against a local-GAAP
credit. This is the SAP `RLDNR`-on-line-item pattern, adapted to
event-sourced storage. IFRS-16 vs ASC-842 lease accounting,
GAAP-vs-tax book differences, and full IFRS / HGB parallel books
all ride this single mechanism.

**Hybrid invariant strategy (ADR-011).** State-shape invariants
(sum-to-zero, commodity match, active accounts) live in declarative
`datopia/invariant` predicates the auditor can read. Behaviour
constraints (sealing, period locks, state-machine transitions, tax
repartition sum-to-100) live in hand-rolled middleware. Each class
of constraint goes where it reads most clearly.

**License-clean separation.** The kernel is EPL-1.0 with one
dependency (datahike). Per-country charts of accounts and tax-rate
data ship as separate artifacts under whatever license their data
source requires — German SKR04 facts may carry GPLv3 from
Tryton/GnuCash; Canadian CRA-published facts carry the kernel's
EPL; Mexican catalogs sourced from SAT carry their own terms.
Bundling everything under one EPL claim would be license
laundering and we don't do it. Consumers pull only the modules they
need and accept those modules' licenses (ADR-001, ADR-006).

## What's NOT in scope

> The single most useful clarification for both audiences:
> `kontor` is the engine, not the application.

- **Not an ERP.** No CRM, no inventory, no MRP, no HR. Beleg owns
  invoice lifecycle; consumer apps own everything else.
- **Not a UI.** No web framework, no view layer, no PDF rendering
  beyond the form-fill scaffolds in `l10n-ca`. Consumers (beleg
  HTMX, simmis Replicant) build their own.
- **Not a US sales-tax engine.** We provide the `TaxProvider`
  protocol; customers integrate Avalara, TaxJar, or TaxCloud. Sales-tax
  rate tables and nexus rules are not bundled.
- **Not a Peppol Access Point.** The UBL is emitted; AP delivery
  uses phax/peppol-commons in a partner adapter when a customer
  needs it.
- **Not a translation of Odoo.** The FSF treats translation as
  derivative work; LGPLv3 would propagate to the kernel. Odoo is a
  reference oracle, not a code source (ADR-001).
- **No payroll engine yet.** Mexican Nómina 1.2, Indian payroll, and
  German Lohn are deferred. The CFDI Nómina complemento *shape* is
  modeled; the payroll *computation* is not.
- **No municipality / district-level entities yet.** Brazilian
  município (5,570 IBGE entries) and Mexican municipio (2,469 SAT
  entries) are required for full NF-e and CFDI 4.0 but stay in the
  relevant l10n module under a future ADR.
- **No PAC / IRP / SEFAZ / NETFILE network integration in the
  kernel.** Those live in partner adapters. The kernel never holds
  API credentials.
- **No bank-reconciliation matching heuristics.** Bank-statement
  parsers ship per country (`bank-de`, `bank-ca`, `bank-fr`,
  `bank-at`, `bank-us`); matching is a consumer-app concern.
- **No clean-room reimplementation of Odoo's `account.move`.** The
  schema is independently designed against datahike idioms (ADR-001,
  ADR-002, ADR-008).

## Jurisdictional coverage matrix

| Jurisdiction | Tax surface | Identifiers | E-invoice / clearance | Returns / declarations | Status |
|---|---|---|---|---|---|
| Germany (DE) | USt 19/7/0%, USt-on-restaurant historical (cut 2020-2023) | USt-IdNr | Factur-X / XRechnung 3.0 / ZUGFeRD via Mustang | UStVA (monthly Kennzahlen), DATEV EXTF, EÜR, BWA, P&L year-end close | Most complete; SKR04 chart, year-end close, DATEV export, e-invoice |
| Austria (AT) | USt 20/13/10/0% | UID | (Peppol via DE module) | UVA (U30 monthly) field codes | Chart + UVA computation |
| France (FR) | TVA 20/10/5.5/2.1% | SIREN/SIRET | (Peppol-ready) | CA3 (Cerfa 3310-CA3) | Chart (PCG) + CA3 computation |
| Canada (CA) | GST 5% / HST 13/15% / QST 9.975% / PST per province | BN, GST/HST number | (paper / NETFILE web; transmission deferred) | GST34-2, T1 + S1/S3/S4/S8/S9/S11, T2125, BC428 (year-versioned 2024), QST, T4/T5/T5018 info returns via T619 | Three-ring architecture (kernel / renderer / transmission); cert-gated transmission deferred |
| United States (US) | (per-state, computation outsourced to Avalara/TaxJar) | EIN, SSN/ITIN | (none in scope) | Per-state sales-tax filing report scaffold | Scaffold; Avalara adapter when a consumer needs it |
| Australia (AU) | GST 10% (single rate) | ABN | Peppol PINT A-NZ UBL 2.1 (mandatory since 2025-05-15) | BAS labels G1/G2/G3/G7/G10/G11/1A/1B | Tax + BAS + Peppol emit |
| Japan (JP) | Consumption Tax 10/8/0%, three zero-tax categories | Qualified Invoice Issuer T-number | Peppol PINT JP UBL 2.1 | JCT return computation | Tax + return + Peppol emit |
| China (CN) | VAT 13/9/6/3/0% | (taxpayer ID) | Fapiao tracking (`:pending-attestation` lifecycle); fully-digital e-fapiao QR-signature | (filings via STA platform) | Tax + fapiao lifecycle + chart |
| India (IN) | GST 2.0: 0/0.25/3/5/18/40% + cess; CGST+SGST intra-state, IGST inter-state, UTGST | GSTIN, PAN, TAN | IRN payload (NIC IRP) + E-way bill payload, multi-attestation with depends-on graph | (GSTR-1 / GSTR-3B emit deferred) | Tax engine + identifiers + states + IRN + EWB; chart + GSTR emit pending |
| Mexico (MX) | (IVA 16/8/0 + IEPS scaffold) | RFC, CURP, CLABE | CFDI 4.0 envelope + complemento composition (Pagos, Carta Porte, Nómina, ComercioExterior placeholders) | (DIOT, Contabilidad Electrónica deferred) | Identifiers + CFDI envelope + composition; tax + chart pending |
| Brazil (BR) | Legacy: ICMS state matrix + IPI + PIS + COFINS + ISS + IRPJ + CSLL; New: IBS + CBS dual-VAT (test rates 2026-2027) | CNPJ, IE | NF-e 4.0 XML (mod 55) verified against SEFAZ Manual 4.01 | SPED EFD-ICMS/IPI scaffold | NF-e XML comprehensive; SPED scaffold; IBS/CBS transition rates loaded |

Read each module's `src/kontor/l10n_<cc>/` namespace docstrings for
the authoritative one-pager — the surface evolves with each
release and the docstrings stay current.

## What it feels like

```clojure
(require '[kontor.core    :as k]
         '[kontor.posting :as posting]
         '[kontor.trial   :as trial]
         '[datahike.api   :as d])

;; Ephemeral in-memory DB with the schema + primary ledger bootstrapped.
(def conn (k/create-test-db))

;; Seed a minimal catalogue (typically installed by an l10n module).
(d/transact
  conn
  [{:commodity/symbol "EUR" :commodity/precision 2 :commodity/iso-4217 "EUR"}
   {:account/code "1200" :account/name "Bank"  :account/type :asset  :account/active true}
   {:account/code "4400" :account/name "Sales" :account/type :income :account/active true}
   {:journal/code "SALES" :journal/name "Customer invoices"
    :journal/type :sale :journal/active true}])

;; Build and post a balanced sales entry. The kernel checks sum-to-zero
;; per ledger per commodity before transact.
(d/transact
  conn
  (posting/build-transaction
    {:transaction {:transaction/journal        [:journal/code "SALES"]
                   :transaction/effective-date #inst "2026-05-11"
                   :transaction/narration      "Customer invoice INV-2026-0001"
                   :transaction/state          :posted}
     :postings    [{:posting/account   [:account/code "1200"]
                    :posting/amount     1000.00M
                    :posting/commodity [:commodity/symbol "EUR"]}
                   {:posting/account   [:account/code "4400"]
                    :posting/amount    -1000.00M
                    :posting/commodity [:commodity/symbol "EUR"]}]}))

;; Trial balance, bitemporal — same call answers historical and
;; current-as-of-tx questions.
(trial/trial-balance conn {:as-of-valid #inst "2026-05-31"
                           :as-of-tx    #inst "2026-06-30"})
```

## Project documents

- [doc/decisions.md](doc/decisions.md) — every architectural decision
  with rationale (ADR-001 … ADR-026). Start here for any non-trivial
  question about *why* the schema looks the way it does.
- [doc/architecture.md](doc/architecture.md) — layer cake,
  namespaces, kernel module list.
- [doc/roadmap.md](doc/roadmap.md) — phased plan with acceptance
  criteria per phase.
- [doc/research/00-index.md](doc/research/00-index.md) — research
  reports that informed the decisions.
- [CLAUDE.md](CLAUDE.md) — guidance for AI-assisted iteration; also
  useful for humans onboarding to the codebase.

## License

> **Kernel: EPL-1.0.** See [LICENSE](LICENSE).
>
> **Per-country l10n modules ship their own licenses** — the kernel
> stays EPL-1.0, but `kontor-l10n-de` (for example) may carry GPLv3
> when its chart of accounts is sourced from Tryton or GnuCash.
> Each `modules/<name>/` directory documents its license. Pull only
> the modules whose terms you accept.

## Contributing

Open an issue describing the slice you'd like to take from
[doc/roadmap.md](doc/roadmap.md), then follow the iteration loop in
[CLAUDE.md](CLAUDE.md). Test-first; ADR before any non-trivial design
change; the one-DB cohabitation invariant from ADR-002 holds
throughout. The current iteration loop on a running nREPL is about
200ms per cycle; reserve `bb ci` for the final pre-commit pass.
