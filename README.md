# kontor

A **double-entry accounting kernel** for Clojure. One dependency
([datahike](https://github.com/replikativ/datahike)). Bitemporal,
EPL-1.0, no UI, no ERP, no country-specific data bundled.

`kontor` ships the ledger semantics — accounts, journals, balanced
postings, periods, sealing, tax-engine protocol, parallel ledgers,
analytic dimensions, multi-attestation clearance — and stops there.
Per-country charts, tax rates, and e-invoice schemas live in separate
companion modules. Network adapters (PAC, IRP, SEFAZ, Peppol AP,
Avalara) live in partner adapters that never bundle credentials.

If you've used Datomic or XTDB, this will feel like home: the
accounting state *is* the datalog query target. If you've shipped
on Odoo / NetSuite / Xero, this is the substrate you would have
built underneath them.

## Why kontor

Three load-bearing differentiators:

1. **Bitemporal by default.** Every query takes `:as-of-tx` (what
   the books knew when) and `:as-of-valid` (when the fact was true).
   A 2026 restatement of a 2024 invoice picks up the tax rate legally
   in force on the original date, not today's rate. Auditors can ask
   "what did the books look like as filed on 2025-04-15" from the
   same data. No bolt-on temporal tables. ADR-008, ADR-048.
2. **One database, two schema namespaces.** kontor's attributes are
   namespaced so consumers (`beleg`, `simmis`, your app) can write
   their own `:invoice/*`, `:customer/*`, `:lead/*` etc. into the
   same datahike connection without collision. Posting a sales
   invoice writes `:invoice/status` and the matching `:transaction`
   + `:posting`s atomically in one tx. ADR-002.
3. **Datalog audit trail.** State transitions are `:status-history`
   rows with `:reason`, `:reason-note`, `:supporting-doc`, and
   `:changed-by-uid`. ADR-038 codifies the vocabulary and SoD
   enforcement (`:no-self-approval`, `:requires-supporting-doc`).
   The audit story is a query, not an ETL pipeline.

## What's *not* in kontor

- No UI. Build it in `beleg` / `simmis` / your stack.
- No ERP. (We ship sales / invoice / procurement / collections
  companions under `modules/`, but the kernel itself stays small.)
- No US sales-tax engine. We provide the `TaxProvider` protocol;
  customers integrate Avalara, TaxJar, or TaxCloud.
- No Peppol Access Point. The UBL is emitted; AP delivery is a
  partner adapter.
- No bundled API credentials, ever.
- No Odoo translation. Reference design only (FSF treats translation
  as derivative work; the licenses would propagate). ADR-001.

ADR-010 + ADR-037 explain the boundary in detail.

## Try it in five minutes

You need `clojure` and a JDK. From a fresh checkout:

```bash
bb nrepl
# or: clojure -M:dev -m nrepl.cmdline --middleware '[cider.nrepl/cider-middleware]'
```

Then in a REPL (or via `clj-nrepl-eval -p <port> "..."`):

```clojure
(require '[kontor.core       :as k]
         '[kontor.posting    :as posting]
         '[kontor.trial      :as trial]
         '[kontor.bitemporal :as kbt]
         '[datahike.api      :as d])

;; Ephemeral in-memory DB with the schema + primary ledger.
(def conn (k/create-test-db))

;; Seed a minimal catalogue. (In production, an l10n module like
;; kontor-l10n-de does this for you.)
(d/transact
  conn
  [{:commodity/symbol "EUR" :commodity/precision 2
    :commodity/iso-4217 "EUR"}
   {:account/code "1200" :account/name "Bank"
    :account/type :asset :account/active true}
   {:account/code "4400" :account/name "Sales"
    :account/type :income :account/active true}
   {:journal/code "SALES" :journal/name "Customer invoices"
    :journal/type :sale :journal/active true}])

;; Build, gate, and post a balanced sealed sales entry.
;; post-transaction! routes through transact-with-validation — the
;; kernel gate enforces sealing / period / sum-to-zero / state-
;; machine / invariants. build-transaction (the pure builder)
;; defaults display-type to :product and stamps :tx/valid-from from
;; :effective-date so bitemporal reads work out of the box.
;; (Per ADR-068, every business-write `!` wrapper is gated like this.)
(posting/post-transaction!
  conn
  {:transaction {:transaction/journal        [:journal/code "SALES"]
                 :transaction/effective-date #inst "2026-05-11"
                 :transaction/narration      "INV-2026-0001"}
   :postings    [{:posting/account   [:account/code "1200"]
                  :posting/amount     1000.00M
                  :posting/commodity [:commodity/symbol "EUR"]}
                 {:posting/account   [:account/code "4400"]
                  :posting/amount    -1000.00M
                  :posting/commodity [:commodity/symbol "EUR"]}]})

;; Trial balance. The same call answers historical and current
;; questions — :as-of-valid restates, :as-of-tx is "as filed".
(trial/trial-balance conn
                     {:as-of-valid #inst "2026-05-31"
                      :as-of-tx    #inst "2026-06-30"})
```

### The bitemporal pitch in one minute

A correction of an old fact is a *new write at a past valid-time*,
not an in-place edit. `kontor.bitemporal` exposes this directly:

```clojure
;; Correction: on 2026-06-20 we discover the May invoice's amount
;; was wrong. Write the fix with :vt-from = 2026-05-11 (the date
;; the corrected fact applies); tx-time is "today".
(d/transact
  conn
  (kbt/with-vt
    [{:db/id invoice-eid
      :invoice/total-net 950.00M}]
    #inst "2026-05-11"))

;; What did the books say on 2026-05-31, as known on 2026-05-31?
(kbt/value-at (kbt/as-of-bitemporal (d/db conn)
                                    {:tx #inst "2026-05-31"})
              invoice-eid :invoice/total-net
              #inst "2026-05-31")
;; => 1000.00M

;; What do we know NOW about the books on 2026-05-31?
(kbt/value-at (d/db conn) invoice-eid :invoice/total-net
              #inst "2026-05-31")
;; => 950.00M (the correction is visible)
```

Two axes, freely composable: tx-time (`d/as-of`) × valid-time
(`kbt/value-at`). XTDB v2 calls this "polygon resolution"; kontor
gets the same shape from a tx-meta attribute plus a small resolver.
ADR-048.

## Companion modules

`kontor` ships the kernel. Layered on top, inside this repo, are
optional companions — each can be excluded:

| Module | What it adds |
|---|---|
| `kontor-invoice` | Order→invoice bridge, status machine, AcctgTrans posting (ADR-036) |
| `kontor-sales` | Order header + items + ship-groups + adjustments (ADR-035) |
| `kontor-partner` | Party-as-root + person/org subtypes + polymorphic contact mechs (ADR-033) |
| `kontor-procurement` | Requisition + receipt + 3-way match + drop-ship + RTV (ADR-042) |
| `kontor-collections` | AR collections + dunning + dispute + credit-hold + bad-debt write-off (ADR-043) |

Plus per-jurisdiction `kontor-l10n-{de,fr,ca,us,au,jp,cn,in,br,mx,at}`
modules with charts of accounts, tax stacks, return computations,
and e-invoice emitters; and bank-statement importers
(`kontor-bank-{de,fr,ca,us,at}`).

See `modules/` and [doc/roadmap.md](doc/roadmap.md) for the current
state.

## Country coverage

The l10n modules vary in depth. The matrix below reflects what is
actually shipped in `modules/l10n-*/` today (LoC is `src/`, excludes
tests):

| Country | LoC | Chart | Invoice | Tax | Filing | Bank importer | Status |
|---|---:|---|---|---|---|---|---|
| DE | 1072 | SKR04 | Factur-X / XRechnung / ZUGFeRD (`modules/einvoice-de`) | UStVA + reverse-charge + 19/7/0 | UStVA, BWA, EÜR, P&L, BS, DATEV EXTF, year-end close | `bank-de` (217 LoC, 11 bank CSV formats) | **complete-enough-to-build-on** (drives showcase 01) |
| CA | 2500 | CA baseline | — | GST/HST + QST + BC PST | GST34-2 (15 lines), T1/T2125/S3/S4/S8/S9/S11, BC428, T4/T5/T5018 XML (CRA 2026V4 XSD-validated), T619 envelope, NoA scaffold, AcroForm PDF mechanics | `bank-ca` (161 LoC) | **complete-enough-to-build-on** (XFA-fill + cert deferred per Phase 4-CA-cert) |
| BR | 1882 | Plano de Contas Referencial | NF-e 4.0 (kernel emitter; signing in partner) | ICMS state matrix + IPI + PIS + COFINS + ISS + IRPJ + CSLL + CBS/IBS dual-VAT scaffold | SPED EFD-ICMS/IPI subset, ECF De/Para | — | **scaffolded** (filable artifacts; SEFAZ transmission is partner) |
| IN | 963 | — | NIC IRN clearance scaffold, EWB | GST + TDS withholding + reverse-charge | GSTR-1 export shape | — | **scaffolded** (drives showcase 03) |
| JP | 563 | J-GAAP | Peppol PINT JP UBL | JCT 10/8/exempt/zero-rated + 3 zero-tax categories | QIS registration number validation | — | **scaffolded** |
| CN | 489 | ASBE/ASSBE | fapiao (8/18/20-digit validators) + draft EInvoiceProvider XML | VAT 13/9/6/3/0 + surcharges (UMCT/edu) | — (STA platform deferred to `kontor-l10n-cn-fapiao`) | — | **scaffolded** |
| AU | 458 | ATO-aligned (~40 accts) | Peppol PINT A-NZ UBL | GST 10% single-rate | BAS Simpler / Full | — | **scaffolded** |
| MX | 472 | — (no chart shipped) | CFDI scaffold | — | RFC + CFDI identifier validators | — | **thin** (no chart, no tax stack) |
| AT | 147 | AT baseline | — | — | UVA stub | `bank-at` (170 LoC) | **thin** (chart + bank importer only) |
| FR | 136 | FR PCG baseline | — | — | CA3 stub | `bank-fr` (147 LoC) | **thin** (chart + CA3 stub + bank importer) |
| US | 121 | — (no chart shipped) | — | sales-tax stub (`TaxProvider` seam — wrap Avalara/TaxJar/SST per ADR-005) | — | `bank-us` (162 LoC) | **thin** (drives showcase 02 against the substrate seams; full chart + 1099 series deferred to Phase 5-US) |

The pattern: the substrate is country-agnostic; the depth of each
l10n module reflects which user stories we've actually exercised
end-to-end. DE / CA / BR / IN are deep enough to build a real consumer
on; FR / AT / MX / US are scaffolds that prove the seams compose but
need a real customer pull before they go further.

## Showcases

Four end-to-end Clay notebooks in [doc/showcases/](doc/showcases/),
each tells a story on synthetic data with cited regulatory sources
and doubles as an integration test:

- [`01_de_b2b_factur_x.clj`](doc/showcases/01_de_b2b_factur_x.clj) —
  German GmbH B2B SaaS with Factur-X invoicing + DE Mahnverfahren
  collections.
- [`02_us_llc_multi_state.clj`](doc/showcases/02_us_llc_multi_state.clj) —
  US LLC selling SaaS across CA/NY/TX/WA with Regulation F-compliant
  dunning.
- [`03_in_b2b_irn_tds.clj`](doc/showcases/03_in_b2b_irn_tds.clj) —
  Indian B2B manufacturer with NIC IRN e-invoice clearance + GSTR-1
  export shape + TDS withholding + intercompany reverse-charge.
- [`04_multi_entity_intercompany.clj`](doc/showcases/04_multi_entity_intercompany.clj) —
  DE parent + US subsidiary end-to-end O2C + P2P with multi-entity
  sum-to-zero (ADR-031), analytic cost-centers (ADR-022), Stage J
  sales bridge, Stage K procurement, and `:no-self-approval`
  enforcement (ADR-038).

Render with `clojure -M:notebooks` then
`(scicloj.clay.v2.api/make! {:source-path "doc/showcases/01_de_b2b_factur_x.clj"})`.

## Substrate-tier seams

`kontor` exposes pluggable seams so consumers can plug their own
logic (or a partner adapter) without touching the kernel. As of the
2026-05-17 trans-national substrate work, the public seams are:

| Seam | ADR | Built-in impls | What it lets you plug |
|---|---|---|---|
| `TaxRateProvider` + `TaxFacts` + `TaxPostingBuilder` | ADR-071 (supersedes ADR-005) | `StaticTableProvider`; scaffolds for `AvalaraProvider` / `TaxJarProvider` / `SstCsvProvider`; per-l10n posting builders | Per-jurisdiction tax engines; rate-determination is pure data (`TaxFacts`) so per-country posting builders own the chart-of-accounts knowledge |
| `FxRateProvider` | ADR-072 | `StaticTableProvider` (last-known + inverse + triangulation); `EcbReferenceRatesProvider` (CSV ingest, attribution required); `ChainedProvider`; scaffolds for `XeProvider` / `OandaProvider` / `FedH10Provider` | Foreign-exchange rates per IAS 21 / ASC 830 rate-types (`:spot :closing :average :opening :historical`). Consumed by `kontor.fx`, `kontor.report/compute-report` (`:translate-to`), `kontor.lease.posting/plan-fx-retranslation`, and `kontor.consolidation`. |
| `EInvoiceProvider` | ADR-017 | `PureXmlProvider` (UBL / Factur-X / NF-e / Peppol PINT shapes) | Per-country e-invoice envelope generation. Signing + transmission live in partner adapters; the kernel emits the pure data. |
| `CostingProvider` | ADR-029 | FIFO, LIFO, WeightedAverage, StandardCost | Inventory cost methods over `:valuation-layer` + `:layer-consumption`. |
| `DepreciationProvider` | ADR-055 | straight-line, declining-balance, units-of-production | Per-(asset, ledger) depreciation method — different books may depreciate the same asset on different schedules (Handelsbilanz ≠ Steuerbilanz). |
| `LeaseProvider` | ADR-063 | the operating-lease ROU plug; `plan-fx-retranslation` provider mode | IFRS 16 / ASC 842 lessee-side lease accounting; per-`(lease, ledger)` classification. |
| `CrossTxRouter` | ADR-074 | content-hash `:cross-tx/step-id` idempotency + `drain!` worker over `:side-effect-intent` | Cross-DB atomic-feel commits (kontor↔stratum secondary index, intercompany kontor↔kontor, kontor↔scriptum audit log). Saga + content-hash, not XA/JTA. |

Plus three primitives that compose with the seams above:

- **`kontor.consolidation`** (ADR-073) — `translate-trial-balance-tx-data` + `eliminate-intercompany-pair-tx-data` + `consolidate!` orchestrator over `kontor.entity/family`. Closes the architecture-review §4 Gap 4 at the substrate level; companion-tier `kontor-consolidation` will layer ownership %, minority interest, IFRS 10 control on top.
- **`kontor.side-effect.cross`** (ADR-074) — the cross-DB saga implementation that backs `CrossTxRouter`. ~250 LoC; reuses `:side-effect-intent/*` schema verbatim.
- **`:account-tag/concept-iri`** (research note 78; commit `9a160aa`) — schema seam for XBRL / filing taxonomies (`{namespace-URI}#{local-name}`). Substrate stores + indexes; verification is companion-tier.

## Where to next

Two audience-specific docs, both worth bookmarking:

- **[doc/value.md](doc/value.md)** — **for evaluators and business
  stakeholders.** What kontor IS (a substrate, not an app), the
  eight kernel concerns it solves that traditional ERPs make hard,
  who it's for, who it isn't for. Plain English with accounting
  terms defined inline.
- **[doc/programming.md](doc/programming.md)** — **for Clojure
  developers.** The three-axis programming model (bitemporal
  substrate + status machines + the transact gate), the post-Stage-P
  transact programming model (`*-tx-data` builders + `kontor.process`
  + `transact-with-validation`), composition patterns, the
  documented carve-outs, how to add a new transactor.

And the deeper material:

- **[doc/architecture.md](doc/architecture.md)** — layer cake,
  schema-as-source-of-truth, how companions compose without forking
  the kernel.
- **[doc/decisions.md](doc/decisions.md)** — every architectural
  choice with rationale (ADR-001 … ADR-074, including ADR-067's
  `kontor.process` facility, ADR-068's universal `*-tx-data` builder
  convention, and the 2026-05-17 trans-national substrate quartet —
  ADR-071 tax abstraction redesign, ADR-072 FxRateProvider, ADR-073
  consolidation primitive, ADR-074 cross-DB saga). Start here for
  any non-trivial question about *why* the schema looks the way it
  does.
- **[doc/roadmap.md](doc/roadmap.md)** — phased plan with acceptance
  criteria.
- **[doc/research/](doc/research/)** — 78 point-in-time research
  notes spanning prior-art surveys (Odoo, Tryton, SAP, NetSuite,
  Oracle, KillBill, OFBiz, SpiceDB, EACL, XTDB v1/v2, Postgres SSI),
  the bitemporal substrate arc (notes 55-68 + 77), the cross-DB
  atomic-transact study (note 71), the trans-national review-after
  (note 76), and the XBRL substrate-design input (note 78). See the
  table of contents at [doc/research/00-index.md](doc/research/00-index.md).
- **[doc/showcases/](doc/showcases/)** — four end-to-end narrative
  notebooks (DE Mahnverfahren, US multi-state, IN B2B with IRN+TDS,
  multi-entity intercompany). Each tells a story on synthetic data
  with cited regulatory sources.
- **[CLAUDE.md](CLAUDE.md)** — iteration loop, REPL conventions,
  per-stage rhythm. Useful for humans, not just AI assistants.

## License

EPL-1.0. See [LICENSE](LICENSE).

Per-country localization modules ship under their own licenses —
e.g. `kontor-l10n-de` may carry GPLv3 because its chart of accounts
is sourced from Tryton/GnuCash. Each `modules/<name>/` directory
documents its license. Pull only the modules whose terms you accept.

## Status

Trans-national substrate landed 2026-05-17/-18 (ADRs 071-074).
Kernel + 74 ADRs total.

The kernel runs the four showcase notebooks end-to-end (DE / US /
IN / multi-entity). Every business-write transactor across kernel +
companions exposes a pure `*-tx-data` builder + a thin `!` wrapper
routing through the kernel validation gate — atomic cross-module
composition (the "create invoice + grant access + log audit-doc in
one transaction" win) is structurally enforced (ADR-067 / 068).

Architecture-review §4 trans-national gaps now closed at the
substrate level: per-entity sum-to-zero (ADR-031), `FxRateProvider`
+ Money translation (ADR-072), consolidation primitive (ADR-073),
cross-DB saga primitive (ADR-074). HR/payroll remains
research-before-complete (notes 72/73/74) and gated on 5 design
calls in note 74.

It is *not* yet 1.0 — a small datahike contribution (closing the
`:period/lock-tx` self-ref carve-out, task #75) remains, the
trans-national substrate's per-l10n migration to `TaxRateProvider`
+ `TaxPostingBuilder` (ADR-071) is per-module work, and the FX +
consolidation surface still wants real-customer mileage.

## Contributing

Open an issue describing the slice you'd like to take from
[doc/roadmap.md](doc/roadmap.md), then follow the iteration loop in
[CLAUDE.md](CLAUDE.md): test-first, ADR for any non-trivial design
call, and the one-DB cohabitation invariant from ADR-002 holds
throughout. REPL cycles run at ~200ms; reserve `bb ci` for the
pre-commit pass.
