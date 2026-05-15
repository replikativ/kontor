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
  choice with rationale (ADR-001 … ADR-068, including ADR-067's
  `kontor.process` facility and ADR-068's universal `*-tx-data`
  builder convention). Start here for any non-trivial question
  about *why* the schema looks the way it does.
- **[doc/roadmap.md](doc/roadmap.md)** — phased plan with acceptance
  criteria.
- **[doc/research/](doc/research/)** — 49 point-in-time research
  notes spanning prior-art surveys (Odoo, Tryton, SAP, NetSuite,
  Oracle, KillBill, OFBiz, SpiceDB, EACL, XTDB v2, Postgres SSI),
  review-after audits, and design call analysis. Note 48 + 49 are
  the Stage P review-after pair.
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

End of Stage P (universal `*-tx-data` builders + cross-module
composition). Kernel + 68 ADRs landed.

The kernel runs the four showcase notebooks end-to-end (DE / US /
IN / multi-entity). Every business-write transactor across kernel +
companions now exposes a pure `*-tx-data` builder + a thin `!`
wrapper routing through the kernel validation gate — atomic
cross-module composition (the "create invoice + grant access + log
audit-doc in one transaction" win) is structurally enforced.

`bb test`: 939 tests / 3423 assertions / 0 failures.

It is *not* yet 1.0 — two lease follow-up tasks (#123, #124) and a
small datahike contribution (closing the `:period/lock-tx` self-ref
carve-out, task #75) remain. The authz consumer-readiness gap (#127,
the two `ADR-066-deferred` `AuthzClient` methods) was closed on
2026-05-15 — `write-schema!` / `read-schema` now route through
`kontor.authz.schema/{write-schema-tx-data,read-schema}` with
structural validation; the SpiceDB-string parser stays deferred.

## Contributing

Open an issue describing the slice you'd like to take from
[doc/roadmap.md](doc/roadmap.md), then follow the iteration loop in
[CLAUDE.md](CLAUDE.md): test-first, ADR for any non-trivial design
call, and the one-DB cohabitation invariant from ADR-002 holds
throughout. REPL cycles run at ~200ms; reserve `bb ci` for the
pre-commit pass.
