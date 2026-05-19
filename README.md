# kontor

**Real accounting, queryable through time.**

kontor is a Clojure library that gives you the inside of an accounting
system — postings, accounts, ledgers, tax, periods, sealing,
bitemporal audit — as data your app queries with Datalog. It doesn't
ship a UI or an ERP; it ships the substrate those things are built ON.

```clojure
;; Re-run the same trial balance "as filed on March 31" vs. "what we know now":
(trial/trial-balance conn {:as-of-valid #inst "2024-03-31"
                           :as-of-tx    #inst "2024-04-15"})
```

One dependency
([datahike](https://github.com/replikativ/datahike)). EPL-1.0. JVM
Clojure only. No bundled API credentials, ever.

## See it work — narrative notebooks

Six end-to-end scenarios, each a complete story on cited regulatory
sources. Open the source on GitHub for code+commentary, or render
locally with `clojure -M:notebooks:dev` + Clay's `make!`.

| Showcase | What you'll see |
|---|---|
| [05 — Apple 10-K bitemporal restatement](doc/showcases/05_apple_10k_bitemporal.clj) | Ingest Apple's actual 2009 10-K + the 2010 amendment from SEC EDGAR. `(d/valid-at db 2009-12-01)` returns the original numbers; `(d/valid-at db 2010-02-01)` returns the ASC 605-25 restatement. Real public data; no XBRL parser required. |
| [06 — Multi-year DE GmbH with backdated correction](doc/showcases/06_de_gmbh_multi_year.clj) | 3-year synthetic Acme GmbH (München). Y2 Q4 the Steuerberater finds a misclassified Y1 expense; kontor's bitemporal substrate records the corrected split per EStG §4(5) without rewriting history. Y3 DSAR walk + retention sweep close the loop. |
| [01 — DE B2B Factur-X + Mahnverfahren](doc/showcases/01_de_b2b_factur_x.clj) | German B2B invoicing with Factur-X / ZUGFeRD + 3-level dunning per BGB §286. The simplest end-to-end happy path. |
| [02 — US multi-state SaaS + Reg-F](doc/showcases/02_us_llc_multi_state.clj) | SaaS billed across CA/NY/TX/WA via the `TaxProvider` seam; collections with CFPB Reg-F frequency cap. |
| [03 — IN B2B + IRN + TDS](doc/showcases/03_in_b2b_irn_tds.clj) | Indian B2B with NIC IRN clearance, GSTR-1 export shape, TDS withholding, reverse-charge mechanism. |
| [04 — Multi-entity intercompany](doc/showcases/04_multi_entity_intercompany.clj) | DE parent + US subsidiary with intercompany eliminations + cost-center analytic dimensions + ADR-038 `:no-self-approval` enforcement. |

## What makes kontor different

Three claims, each tested by a showcase above.

**Bitemporal by default.** Every query takes two clocks — when the
fact was true in the business world (`:as-of-valid`) and when the
books knew it (`:as-of-tx`). A correction is a new write at a past
valid-time, not an in-place edit. The 2009 → 2010 Apple 10-K/A
restatement in showcase 05 is the canonical demo: same fact, same
period-end, two different filed-dates → `(d/valid-at)` returns the
authoritative one at any timeline point.

**One database, two schema namespaces.** kontor's attributes are
namespaced (`:posting/*`, `:account/*`, `:transaction/*`, ...) so your
app can write its own `:invoice/status`, `:customer/notes`, `:lead/*`
into the same datahike connection without collision. Posting a sales
invoice writes the business state + the matching GL `:transaction`
atomically in one tx.

**Audit trail is a datalog query, not an ETL pipeline.** Every state
transition records the actor, reason, supporting document, prior
state. "Who changed this invoice from approved to cancelled and why?"
is one query. The substrate ships consent + retention + legal-hold +
DSAR primitives in the same shape — GDPR Art. 17 / BDSG §26 / EU AI
Act Art. 5 compliance is structural, not bolt-on.

## Try it in 60 seconds

You need a JDK + `clojure` + (optionally) `babashka`. From a fresh
checkout:

```bash
bb nrepl
# or: clojure -M:dev -m nrepl.cmdline --middleware '[cider.nrepl/cider-middleware]'
```

Then in a REPL (or `clj-nrepl-eval -p <port> "..."`):

```clojure
(require '[kontor.core    :as k]
         '[kontor.posting :as posting]
         '[kontor.trial   :as trial]
         '[datahike.api   :as d])

(def conn (k/create-test-db))                ;; in-memory; schema loaded

(d/transact conn
  [{:commodity/symbol "EUR" :commodity/precision 2}
   {:account/code "1200" :account/name "Bank"  :account/type :asset  :account/active true}
   {:account/code "4400" :account/name "Sales" :account/type :income :account/active true}
   {:journal/code "SALES" :journal/name "Customer invoices"
    :journal/type :sale    :journal/active true}])

;; Post a balanced sealed sales entry — kernel gate enforces sum-to-zero
;; + sealing + period-lock + invariants.
(posting/post-transaction!
  conn
  {:transaction {:transaction/journal        [:journal/code "SALES"]
                 :transaction/effective-date #inst "2026-05-11"
                 :transaction/narration      "INV-2026-0001"}
   :postings    [{:posting/account [:account/code "1200"]
                  :posting/amount   1000.00M
                  :posting/commodity [:commodity/symbol "EUR"]}
                 {:posting/account [:account/code "4400"]
                  :posting/amount  -1000.00M
                  :posting/commodity [:commodity/symbol "EUR"]}]})

(trial/trial-balance conn {:as-of-valid #inst "2026-05-31"
                           :as-of-tx    #inst "2026-06-30"})
```

For the bitemporal-correction story — the headline feature — see
[doc/start-here.md](doc/start-here.md), which walks through showcase
06 step-by-step.

## What's in the box

The kernel under `src/kontor/` is small; everything else is a
composable companion under `modules/`. Each module has its own
README + can be excluded if you don't need it. Pull only the pieces
your app uses.

**Accounting primitives** — booked beyond the kernel's posting /
balance / period semantics:

| Module | Adds |
|---|---|
| [`asset`](modules/asset/README.md) | `:asset` register + per-(asset, ledger) depreciation books (ADR-053/054/055) |
| [`lease`](modules/lease/README.md) | IFRS 16 / ASC 842 lessee-side + modifications + FX retranslation (ADR-063/064) |
| [`inventory`](modules/inventory/README.md) | FIFO / LIFO / WeightedAverage / StandardCost + valuation layers (ADR-029) |
| [`expense`](modules/expense/README.md) | Expense report + reimbursement + multi-currency lines |

**Order-to-cash + procure-to-pay**:

| Module | Adds |
|---|---|
| [`partner`](modules/partner/README.md) | Party-as-root + person/org subtypes + polymorphic contact mechs (ADR-033) |
| [`sales`](modules/sales/README.md) | Order header + items + ship-groups + adjustments (ADR-035) |
| [`invoice`](modules/invoice/README.md) | Order → invoice bridge + status machine + AcctgTrans posting (ADR-036) |
| [`procurement`](modules/procurement/README.md) | Requisition + receipt + 3-way match + drop-ship + RTV (ADR-042) |
| [`collections`](modules/collections/README.md) | AR collections + dunning + dispute + credit-hold + bad-debt write-off (ADR-043) |

**Workforce**:

| Module | Adds |
|---|---|
| [`hr`](modules/hr/README.md) | `:person` / `:employment` / `:compensation` + `:consent/*` (ADR-075 + ADR-094) |
| [`people-record`](modules/people-record/README.md) | Career history + reviews + promotions, consent-gated (ADR-094) |
| `payroll-{de-datev,us-adp,ca,fr,au,br,mx,in,jp,cn,at}` | 11 country payroll adapters — parse engine export, post to country chart, emit regulator filing (ADR-076..087) |

**Compliance + authz**:

| Module | Adds |
|---|---|
| [`authz`](modules/authz/README.md) | ReBAC indexed traversal + consumer-readiness API (ADR-066/127) |

**Real-data ingest**:

| Module | Adds |
|---|---|
| [`import-gleif`](modules/import-gleif/README.md) | GLEIF Golden Copy LEI ingest (CC0) → `:entity/lei` substrate |
| [`import-edgar`](modules/import-edgar/README.md) | SEC EDGAR `companyfacts` JSON ingest with bitemporal restatement supersession |

**Localization** — per-jurisdiction charts, tax rules, filings, e-invoice:

`l10n-{de,fr,ca,us,au,jp,cn,in,br,mx,at}` + `einvoice-de`
+ `bank-{de,fr,ca,us,at}`. Depth varies by country — see the
[country coverage](#country-coverage) matrix below. The l10n modules
ship under their own licenses (some charts are GPLv3 per Tryton /
GnuCash provenance) — each module's README documents its terms.

**Agent integration** (substrate-side, not under `modules/`):

- `kontor.agent-tools` — server-agnostic tool catalog for LLM / MCP
  agents over the kontor read+write surface. Composes with
  [dvergr](https://github.com/replikativ/dvergr)'s MCP server today;
  a standalone `kontor-mcp` is deferred until a consumer asks for one.

## What kontor doesn't ship

Four genuine non-features:

- **No UI.** Build it in your app — we use HTMX in
  [beleg](https://github.com/replikativ/beleg); others use Replicant
  / React / Reagent.
- **No bundled API credentials**, ever (ADR-005). The TaxProvider /
  PAC / SEFAZ / Peppol AP / Avalara seams are protocols; you wire
  the credentials.
- **No US sales-tax rate tables.** We provide the `TaxRateProvider`
  seam (ADR-071); integrate Avalara, TaxJar, TaxCloud, or your own.
- **No scaffolding for EU AI Act-banned categories** (ADR-094). The
  project refuses to canonicalize real-time biometric emotion
  recognition, covert workforce monitoring, or automated termination
  recommendations — even though the substrate could technically host
  them.

## Where to read next

Three audience-specific entry points:

- **Curious about kontor as an accounting system.** Read
  [doc/start-here.md](doc/start-here.md) (a single-page walkthrough of
  showcase 06) and then [doc/value.md](doc/value.md) (the 8 kernel
  concerns kontor solves that ERPs make hard, with accounting terms
  defined inline).
- **Clojure developer who wants to use the substrate.** Read
  [doc/programming.md](doc/programming.md) (the three-axis programming
  model: bitemporal + status machines + the transact gate) + open
  showcase 06's source as a working example.
- **Architect / evaluator.** Read [doc/architecture.md](doc/architecture.md)
  for the layer cake + namespace map, then
  [doc/decisions.md](doc/decisions.md) for the 94 ADRs (each one
  ~1-2 pages of context + rationale + non-decisions).

For the full bench:

- **[doc/roadmap.md](doc/roadmap.md)** — phased plan with acceptance
  criteria.
- **[doc/research/](doc/research/)** — 94 point-in-time research notes
  (prior-art surveys, design exploration, market-pain audits). Start
  with [00-index.md](doc/research/00-index.md). Note 94 is the most
  recent strategic synthesis.
- **[doc/showcases/](doc/showcases/)** — the 6 narrative notebooks
  linked above.
- **[CLAUDE.md](CLAUDE.md)** — iteration loop, REPL conventions,
  per-stage rhythm. Written for AI assistants but useful for human
  contributors too.

## Country coverage

L10n modules vary in depth. The matrix below reflects what's actually
shipped in `modules/l10n-*/` (LoC is `src/`, excludes tests). The
substrate is country-agnostic; depth reflects which user stories
we've exercised end-to-end.

| Country | Chart | Invoice | Tax | Filing | Bank importer | Status |
|---|---|---|---|---|---|---|
| **DE** | SKR04 | Factur-X / XRechnung / ZUGFeRD | UStVA + reverse-charge + 19/7/0 | UStVA, BWA, EÜR, P&L, BS, DATEV EXTF + LODAS Buchungsbeleg, year-end close | `bank-de` (11 bank CSV formats) | drives showcases 01, 06 |
| **CA** | CA baseline | — | GST/HST + QST + BC PST | GST34-2, T1/T2125/S3/S4/S8/S9/S11, BC428, T4/T5/T5018 XML (CRA 2026V4 XSD), T619 envelope, PDF mechanics | `bank-ca` | complete-enough-to-build-on |
| **BR** | Plano de Contas Referencial | NF-e 4.0 (signing in partner) | ICMS state matrix + IPI + PIS + COFINS + ISS + IRPJ + CSLL + CBS/IBS scaffold | SPED EFD-ICMS/IPI subset, ECF De/Para | — | scaffolded |
| **IN** | — | NIC IRN clearance scaffold, EWB | GST + TDS withholding + reverse-charge | GSTR-1 export shape | — | drives showcase 03 |
| **JP** | J-GAAP | Peppol PINT JP UBL | JCT 10/8/exempt/zero + 3 zero-tax categories | QIS registration validation | — | scaffolded |
| **CN** | ASBE/ASSBE | fapiao validators + EInvoiceProvider XML | VAT 13/9/6/3/0 + surcharges (UMCT/edu) | — | — | scaffolded |
| **AU** | ATO-aligned | Peppol PINT A-NZ UBL | GST 10% single-rate | BAS Simpler / Full | — | scaffolded |
| **MX** | — | CFDI scaffold + Nómina (payroll) | — | RFC + CFDI validators | — | thin |
| **AT** | AT baseline | — | — | UVA stub + mBGM payroll | `bank-at` | thin |
| **FR** | FR PCG baseline | — | — | CA3 stub + DSN payroll | `bank-fr` | thin |
| **US** | — | — | sales-tax stub via `TaxProvider` (Avalara/TaxJar) | — | `bank-us` | drives showcase 02; full chart deferred |

Plus 11 country payroll adapters
(`modules/payroll-{de-datev,us-adp,ca,fr,au,br,mx,in,jp,cn,at}`) —
each parses an engine export (DATEV LODAS, ADP GLI, Ceridian
Dayforce, Silae, Xero, RH Sistemas, CONTPAQi, Keka, freee, Yonyou,
BMD), posts to the country's chart, and emits the regulator filing
(LODAS Importdatei, W-2, T4 + T619, DSN NEODES, STP P2, eSocial, CFDI
Nómina, Form 24Q, Gensen, IIT, mBGM + L16).

## Substrate-tier seams

Pluggable interfaces consumers extend without forking the kernel:

| Seam | ADR | Built-in impls | What it lets you plug |
|---|---|---|---|
| `TaxRateProvider` + `TaxFacts` + `TaxPostingBuilder` | ADR-071 | `StaticTableProvider`; scaffolds for Avalara / TaxJar / SST | Per-jurisdiction tax engines; rate determination is pure data |
| `FxRateProvider` | ADR-072 | StaticTable + ECB CSV + ChainedProvider; scaffolds for Xe / OANDA / Fed H.10 | Foreign-exchange rates per IAS 21 / ASC 830 rate-types |
| `EInvoiceProvider` | ADR-017 | `PureXmlProvider` (UBL / Factur-X / NF-e / Peppol PINT) | Per-country e-invoice envelope generation |
| `CostingProvider` | ADR-029 | FIFO, LIFO, WeightedAverage, StandardCost | Inventory cost methods over valuation layers |
| `DepreciationProvider` | ADR-055 | straight-line, declining-balance, units-of-production | Per-(asset, ledger) depreciation method |
| `LeaseProvider` | ADR-063 | operating-lease ROU + `plan-fx-retranslation` | IFRS 16 / ASC 842 lessee-side; per-(lease, ledger) classification |
| `PayrollProvider` trio | ADR-075 | 11 country adapters | Per-country compute + posting + emit |
| `CrossTxRouter` | ADR-074 | content-hash idempotency + `drain!` over `:side-effect-intent` | Cross-DB atomic-feel commits (saga + content-hash) |

## Status

Trans-national substrate, 11 country payroll adapters, McComb-aligned
substrate seams, ADR-094 employee-monitoring posture, and three v0
ingest companions (import-gleif, import-edgar, people-record) landed
2026-05-17/-18. 94 ADRs total; 2223 tests / 8721 assertions.

The kernel runs all six showcase notebooks end-to-end. Every business-
write transactor across kernel + companions exposes a pure `*-tx-data`
builder + a thin `!` wrapper routing through the kernel validation
gate — atomic cross-module composition is structurally enforced
(ADR-067 + ADR-068).

It is *not* yet 1.0. Open work: a small datahike upstream contribution
(task #75), per-l10n migration to ADR-071's `TaxRateProvider` shape,
real-customer mileage on the FX + consolidation surface, and the
agent-driven Jahresabschluss benchmark spec (task #246).

## License

EPL-1.0. See [LICENSE](LICENSE).

Per-country localization modules ship under their own licenses —
e.g. `kontor-l10n-de` may carry GPLv3 because its chart of accounts
is sourced from Tryton/GnuCash. Each `modules/<name>/` directory
documents its license. Pull only the modules whose terms you accept.

## Contributing

Open an issue describing the slice you'd like to take from
[doc/roadmap.md](doc/roadmap.md), then follow the iteration loop in
[CLAUDE.md](CLAUDE.md): test-first, ADR for any non-trivial design
call, and the one-DB cohabitation invariant from ADR-002 holds
throughout. REPL cycles run at ~200ms; reserve `bb ci` for the
pre-commit pass.
