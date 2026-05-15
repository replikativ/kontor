# kontor — what it is and why it matters

This document is for people **evaluating** kontor: founders building a
vertical SaaS, technical leads at an accounting consultancy, product
managers shopping for an accounting substrate, finance leaders sizing
up the build-vs-buy question against SAP / NetSuite / Odoo / Tryton.

If you're a Clojure developer who already decided kontor is the right
thing and wants to USE it, skip to [doc/programming.md](programming.md)
and [the ADRs](decisions.md). This doc is the pitch.

## The elevator pitch

kontor is **the bookkeeping kernel underneath an accounting product
— the part that has to be correct**. It is **not an ERP, not a UI,
not a country-specific package**. It is the substrate: the schema,
the validators, the audit chain, the bitemporal time model, the
status machines, the legal-hold story, the cross-module
composition primitive. You build the product (or the per-country
package) on top.

The bet: every accounting platform eventually has to answer the
same eight questions (audit chain, bitemporal restatement,
multi-entity intercompany, legal hold, retention / DSAR, segregation
of duties, atomic multi-module events, status-machine lifecycles).
Traditional ERPs answer them as **bolt-ons** — modules, partners,
add-ons, custom code — because the kernel wasn't designed for them.
kontor answers them as **kernel concerns**, so the substrate is
correct by default and your product layer can focus on the business
logic that actually differentiates it.

License: EPL-1.0. Runtime: Clojure on the JVM. Storage: datahike
(immutable, content-addressed, datalog-queryable).

## What kontor IS — and what it isn't

| Layer | Owns | Doesn't own |
|---|---|---|
| **kontor (the kernel)** | Schema, postings, ledgers, periods, transactions, the validator gate, kontor.process, status machines, approval policies, audit-docs, legal holds, retention, DSAR, bitemporal queries, sealing | Any UI, any chart of accounts, any tax rates, any localization data, any business policy |
| **kontor-l10n-** *(separate modules)* | German / US / Brazilian / Indian / etc. chart-of-accounts seeders, tax provider stubs, statutory report shapes | Tax rates themselves (the consumer holds these — see ADR-005), engine-specific compliance data |
| **Consumer apps** *(beleg, simmis, your product)* | UI, business workflows, integrations, tax-rate sourcing (Avalara/TaxJar), industry-specific schemas, your customers | The eight kernel concerns above (kontor handles those) |

The split exists because **the kernel is reusable across products and
the products are not reusable across customers**. A consultancy
building three different SaaS apps for three different verticals
uses one kontor and three different consumer layers. A traditional
ERP couples all of this together and pays the cost when any
customer needs a deviation.

## The eight pains kontor solves at the kernel

### 1. Audit chain that's actually a chain

Every commit in kontor is a datahike transaction. Every datahike
transaction is content-addressed and immutable. The audit trail
isn't a log file the application writes — it's the storage layer
itself. You can ask "what did the books say on 2024-03-15?" and get
the literal bytes from that moment, signed by their content hash.

Traditional ERPs: an audit log table the application code writes
into. You trust the application code to write it (no enforced
audit), you trust the database admin not to UPDATE the table
(it's mutable), and you trust the backup-restore story to preserve
historic state (it usually doesn't).

kontor: cryptographic chain by construction. Auditors can verify
without trusting the application code.

### 2. Bitemporal correctness — the restated past

Accounting has TWO clocks: when did the event happen in the
business world, and when did we record it in the system. Examples:
a Q1 invoice corrected in April, an asset sale backdated to month-
end, a payroll adjustment that "should have been" pre-tax. Every
accounting professional has war stories about getting these
backward.

kontor stores both axes in every transaction (`:tx/valid-from` and
`:db/txInstant`). A query like "what did our books LOOK LIKE on
March 31, including only what we KNEW on March 31" is one line of
datalog. The same query "with everything we now know, restated to
March 31" — also one line. The two axes don't get confused; the
substrate enforces it.

Traditional ERPs: one axis (system time), corrections are journal
entries with explanatory narratives. "What did our books LOOK LIKE
on March 31?" requires backup tapes.

### 3. Multi-entity intercompany — built in, not bolted on

A company with a US LLC and a German GmbH posts payroll in the
GmbH and consults the LLC. The two entities settle via
intercompany invoices. Traditional ERPs require either separate
databases (no consolidation), an "intercompany module" (an add-on
with its own data model), or extensive customization.

kontor's `:transaction/posted-from-entity` + multi-ledger postings
mean intercompany is just two postings tagged with the right
entities. Consolidation is a query. ADR-031 covers the model;
showcase #4 walks through a real scenario.

### 4. Legal hold — kernel-grade, not a third-party module

If your customer is sued or investigated, certain data has to be
preserved literally (no deletion, no modification, no anonymization)
until counsel releases the hold. Most ERPs have this as a paid
add-on or a per-customer custom build.

kontor ships ADR-049: `:legal-hold` entities with bitemporal scope
queries, a middleware that BLOCKS any destructive write to held
entities (you can't silently retract a posting under hold), an
audit-doc-required release flow, and a per-hold "what's covered
right now / what was covered last June" temporal answer. Built into
the validator gate; impossible to bypass without code that
intentionally circumvents it.

### 5. Retention + DSAR — compliance plumbing in the kernel

GDPR Article 17 ("right to erasure"). CCPA §1798.105. SOX 7-year
retention. HIPAA. Tax code retention rules per jurisdiction. Every
SaaS deals with these, every ERP makes the customer build them.

kontor ships ADR-050 (retention policies with the legal-hold
interaction worked out — held data survives retention sweeps),
ADR-051 (privilege classification — attorney-client work product
gets the right treatment under DSAR), and ADR-052 (the DSAR
"everything we know about this data subject" walk as one
bitemporal query across kernel + companions). The retention sweep
is a transactor that calls the same gate every business write
goes through — no separate "compliance mode."

### 6. Segregation of duties — approval policies tied to status machines

The clerk who creates the invoice can't be the approver who posts
it. The counsel who creates an audit-doc can't be the same counsel
who waives its attorney-client privilege. The CFO who set the
asset's useful life can't be the same person who disposes it for
zero proceeds.

ADR-038 covers this: every status transition that matters has an
approval-policy row with `:no-self-approval` / `:requires-
supporting-doc` / `:requires-non-empty-reason-note` constraints.
The policy is data; consumer apps add their own policies the same
way kontor ships its own. The gate enforces all of them on every
status change.

### 7. Atomic cross-module events — the ADR-068 win

This is the headline of Stage P: when sales closes a deal, the
system atomically does ALL of this together or NONE of it:

- Creates the customer invoice (kernel `:transaction` + `:posting`s).
- Grants the buyer read access via authz (so they can see it in
  the customer portal).
- Links the signed contract PDF as the invoice's `:origin-document`.
- Schedules an audit-doc retention reminder.
- Marks the opportunity `:closed-won` on the sales side.

In a traditional ERP, this is **five separate transactions across
five subsystems**, each of which can succeed or fail independently.
You build a saga, you handle compensating rollbacks, you sleep
poorly when a partial state lands. The auditor finds an invoice
without a contract or a contract without an invoice and you spend
two weeks tracing why.

In kontor: one `kontor.process` call. The five fragments build via
`*-tx-data` builders, compose into one tx-data, route through one
gate, commit as one datahike transaction. All-or-nothing is
structural.

This is **impossible in Odoo / Tryton / NetSuite / SAP** without
heavy customization — none of them have a kernel-level atomic-tx
primitive that spans modules cleanly. (We surveyed all four; see
research notes 44 + 47.)

### 8. Status machines — every business entity has a lifecycle

Invoices: `:draft → :sent → :paid` (or `:cancelled`, or `:partially-
paid`). Leases: `:draft → :active → :terminated` / `:expired` /
`:purchased`. Assets: `:planned → :in-service → :disposed` /
`:fully-depreciated` / `:impaired`. Holds: `:placed → :released`.
DSAR requests: `:received → :verifying-identity → :in-progress →
:fulfilled` (or `:denied`).

ADR-034 codifies this. Every transition is either legal (registered
in a `:status-transition` row, queryable, organisation-scoped) or
illegal (rejected by the gate). The history is a `:status-history`
row, audit-doc-linked where ADR-038 requires it. Every consumer
adds its own status machines via the same primitive.

Traditional ERPs: status fields with application-code transitions.
"Why can the Q3 invoice be `:cancelled`? The clerk did it." Who
authorized? "I'll check the audit log." Was it allowed? "Let me
check the source code."

## A concrete cross-module composition example

A B2B subscription company:

```clojure
;; The business event: customer signs a 12-month contract.
(process/run-process conn
  {:steps
   [;; (1) Create the customer invoice for the first month.
    (fn [sdb _]
      (invoice/create-tx-data
       sdb {:tempid "inv-1"
            :buyer customer-eid
            :seller our-entity-eid
            :total-gross 199M
            :commodity :USD
            :due-date next-30-days}))

    ;; (2) Grant the customer's user read access to the invoice.
    (fn [sdb _]
      (authz/grant-tx-data sdb authz-client
                           customer-user :view "inv-1"))

    ;; (3) Attach the signed Order Form PDF as the supporting doc.
    (fn [sdb _]
      (audit-doc/create-doc-tx-data
       sdb {:tempid "order-form"
            :code "OF-2026-12345"
            :type :order-form
            :storage-uri "s3://docs/of-2026-12345.pdf"
            :uploaded-by-uid sales-rep-uid}))

    ;; (4) Schedule the remaining 11 monthly invoices.
    (fn [sdb _]
      (schedule/define-tx-data
       sdb {:code "SUB-12345" :kind :subscription
            :frequency :monthly :n-periods 11
            :start-date (+1-month-from now)
            :total-amount (* 199M 11)}))]
   :vt-from contract-signed-at})
```

This commits as one atomic transaction routed through the kernel
gate. If ANY of the four fragments fails any validator (sealing,
period-lock, sum-to-zero, the approval policy, the datalog
invariants), the whole event aborts — no partial state. The auditor
sees one transaction with `:tx/valid-from = contract-signed-at`
spanning four modules.

In every other accounting system: four API calls, four chances to
fail, four chances to leave partial state, four chances for the
buyer-access to land before the invoice does or vice versa. Then
the orchestration layer needs an audit log of its own.

## Who this is for

**Vertical SaaS builders.** You're building accounting software for
a specific industry — restaurant POS, dental practices, freight
forwarders, SaaS subscriptions, construction GCs. Your customers
care about industry-specific workflows. You don't want to build the
core double-entry engine + audit + bitemporal + multi-entity
yourself, but you don't want to bolt onto Odoo because every
customer needs a deviation and the LGPL license complicates your
distribution story.

**Accounting consultancies.** You build three different products
for three different verticals. Sharing infrastructure across them
matters; sharing data across them doesn't.

**ERP modernization projects.** You have an existing system that's
outgrown QuickBooks but isn't ready to commit to SAP's six-figure
implementation. kontor is the substrate; you build the product
layer to match what the customer actually does.

**Embedded accounting.** Your product needs accounting
functionality (invoicing, ledger, tax routing) but isn't an
accounting product — a marketplace, a freelancer platform, a
B2B integrations company. kontor as a library, not an app.

## Who this is NOT for

**Someone shopping for "an accounting app."** kontor is a
substrate. You'll spend developer-months building the UI and
business logic. If you want to open a browser and see an invoice
form, buy QuickBooks Online or hire a Tryton implementer.

**Someone allergic to Clojure.** kontor is Clojure-on-the-JVM. The
runtime is reachable from any JVM language but the canonical API
is Clojure. A Java team can use kontor (via interop); a team that
wants every line of code to be Python or Ruby cannot.

**Someone who needs a tax engine for every country.** kontor's tax
story is "ship the `TaxProvider` protocol, integrate Avalara or
TaxJar yourself or via the consumer's adapter" (ADR-005). It does
not bundle tax rate tables. The l10n modules ship country-specific
chart-of-accounts and report shapes; they don't ship tax rates.

## What you'd build on top

A typical kontor-based product needs:

- **A UI.** Web app, mobile app, terminal — your choice. kontor
  has no opinion. ADR-010.
- **A workflow engine.** kontor ships `kontor.process` for atomic
  composition; the macro-workflow layer (orders → fulfillment →
  invoicing → revenue recognition over weeks) is yours. Many
  vertical SaaSes use a state-machine library or build their own.
- **Tax provider integration.** Sign up for Avalara or TaxJar;
  write an adapter implementing the `TaxProvider` protocol. ~200
  lines.
- **The chart of accounts.** kontor ships *some* l10n modules
  (German, US starter, Indian) with conservative defaults. Your
  customers will want overrides — kontor's account-tag and
  multi-ledger story makes this not painful.
- **Your business logic.** This is where you spend your time.
  Pricing rules, dunning policies, revenue-recognition treatments
  for your specific products, integrations with your CRM and CDP
  and payments processor.

## Where to go next

- **Programming model**: [doc/programming.md](programming.md) — the
  Clojure-developer walkthrough.
- **Architecture**: [doc/architecture.md](architecture.md) — the
  layer cake and namespace map.
- **Design decisions**: [doc/decisions.md](decisions.md) — 68 ADRs
  spanning everything from the bitemporal model to ADR-068's
  cross-module composability story.
- **Research notes**: [doc/research/](research/) — point-in-time
  research that informed each decision, including prior-art
  surveys of Odoo, Tryton, SAP, NetSuite, Oracle, KillBill, OFBiz,
  SpiceDB, EACL, XTDB v2.
- **Showcases**: [doc/showcases/](showcases/) — fully-worked
  scenarios spanning multiple modules (DE Mahnverfahren, US
  multi-state collections, IN B2B with IRN + GSTR + TDS, multi-
  entity intercompany).

License: EPL-1.0. Source: [github.com/replikativ/kontor](https://github.com/replikativ/kontor).
