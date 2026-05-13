# 18 — Integrated business operating systems: what makes them feel unified, and what kontor is missing

> Date: 2026-05-13. Author: research agent. Verified? medium — vendor docs + secondary press; primary engineering talks cited where available. Not all customer-side claims (e.g., Tesla WARP internals) are independently verifiable; we flag those as "vendor-narrative."

This note is a design-philosophy survey, not a feature checklist. It looks at modern "business operating systems" that have a reputation for feeling **integrated** rather than **siloed**, lifts the design moves that produce that feeling, and asks honestly what `kontor` — a Clojure/datahike double-entry accounting **kernel** — is missing from this angle. The goal is to inform kontor's positioning (what it should and should not try to be) and to seed ADRs about exposing primitives that consumers (`beleg`, `simmis`) can compose into an integrated stack.

## 1. What "integrated" actually means

If you ask a CFO or COO what they mean when they say "integrated" they will give you four different answers — and conflating them is why every ERP project pretends to fix the problem and never quite does. Across the systems studied, four orthogonal dimensions emerge, and the products with a strong "feels integrated" reputation are the ones that nail **at least three**.

1. **Data unity** — there is one canonical model of the business. A `Customer` means the same thing in collections as in CRM as in commissions. A `Posting` in finance is the same row as the `Shipment` in ops, just projected differently. The data is **not** copied between systems with reconciliation jobs; it is the same data.
2. **Workflow continuity** — a single business event (an invoice arrives, an order is shipped, a payment clears) traverses many functions without a human re-keying anything. The system does not stop at a department boundary; it propagates.
3. **Temporal honesty** — every state in the system has a known time of validity *and* a known time of recording. "Why did revenue drop in March?" is answerable today, three months from now, after the books are closed, and after someone has retroactively reclassified a $4M transaction. This is the bitemporal lens kontor already embraces (ADR-008).
4. **Surface unity** — the things the end user touches (UI, API, reports) feel like one product. They share an idiom: the same "object types," the same primitives, the same shortcuts. This is the most visible dimension and the most overrated; you can fail the other three and feel "integrated" because the UI is pretty (Odoo), or nail the others and feel "fragmented" because every team built their own React app (Tesla pre-WARP).

**Thesis.** The systems people experience as integrated are the ones that treat the **business as one mutable graph** (Foundry's "ontology", Tesla WARP's vertical integration, Modern Treasury's ledger, Mercury's banking command center) and then **layer workflow, time, and surface on top of that graph**. The ones experienced as siloed are the ones where the graph itself is fragmented — multiple databases, multiple object models, multiple sources of truth — and bolt-on integration tries to paper it over with ETL and reconciliation.

This matters for kontor: the kernel's job is **to be the unified graph for accounting facts**, exposed via datahike so consumers can compose on top of it. The question this note asks is whether kontor's primitives are rich enough for an integrated stack to be built on top, or whether we have implicitly assumed the consumer will solve the integration problem.

## 2. Systems studied

### 2.1 The "new ERP" wave — internal/closed systems with strong reputations

**Tesla WARP** ([Grokipedia][warp-grok], [Medium / Joshi][warp-joshi], [Vijayan keynote][vijayan-yt], [Electrek][electrek-3dx]). Built 2012-2013 by Jay Vijayan and ~25 engineers in roughly four months to replace SAP at Tesla scale. The publicly-reported design moves are:

- **Vertical integration** — sales, manufacturing, supply chain, service, and ordering on one system, not separate SAP / Salesforce / ServiceMax instances stitched together. Vijayan's framing in his 2014 CIO Insight interview was explicit: "Elon's vision is to build a vertically integrated organization where information flow happens seamlessly across departments and where we have a closed feedback loop to our customers." [warp-grok]
- **Real-time as a first-class default** — order to factory to delivery is one continuous event stream, not nightly batches. [warp-joshi]
- **Engineer-as-author** — Tesla's engineers extend WARP themselves; the user's report is that 24-25 engineers can keep up with a $200M → $4B scale-up because they own both the schema and the workflow code. [warp-grok]
- **Closed feedback loop** — service data and customer telemetry flow back to engineering. The same system that books revenue also schedules software OTA pushes. [warp-joshi]

Caveat: WARP is closed; almost everything written about it is press-and-vendor-narrative. The "4 months / 25 engineers" claim should be read as the founding team — the system itself has grown over a decade.

**Palantir Foundry** ([Ontology overview][foundry-onto], [Object backend][foundry-obj], [Action types][foundry-actions], [Core concepts][foundry-core], [Why ontology][foundry-why]). Foundry's central idea is the **Ontology**: a layer that sits on top of integrated datasources and contains both **semantic elements** (object types, properties, link types) and **kinetic elements** (action types, functions, dynamic security). Object types are schema definitions of real-world entities; objects are instances; link types are typed relationships; action types are user-authored, parameterized mutations that write back to the underlying datasources with side-effect orchestration. Functions are TypeScript/Python authored business logic that can be invoked from dashboards, workshop apps, and action types. [foundry-onto, foundry-core]

The thing to absorb: Foundry's pitch is that **the ontology is the operational layer, not a documentation layer**. Decisions are taken in the ontology (via action types), the action log becomes the audit trail, and the same ontology is queried by analytical dashboards, used by ML, and exposed to AIP agents. Airbus uses Foundry to connect "in-flight, engineering and operations data"; BP runs production operations on it. [foundry-airbus]

**Anduril Lattice** ([Lattice OS][lattice]). Less of a business-OS and more of a sensor-and-effector OS, but the design philosophy maps directly: every sensor, effector, and autonomous platform is an object in a single graph; the platform is "sensor-agnostic, network-agnostic, system-agnostic"; the SDK exposes the same primitives to humans and AI. The defense parallel for ERP: a single operational graph beats federated point-tools.

**Ramp / Brex** ([Ramp Builders blog on workflows][ramp-workflows], [Brex AI accounting API][brex-erp]). Both began as expense management products and have moved upstream toward the ledger. Ramp's `Workflows` post is unusually candid: they explicitly say they pivoted from "a collection of excellent point-solutions" to a unified platform built on three primitives — **Actions** (Python functions, sync or async), **Conditions** (booleans), and **Graphs** (vertices + edges). Workflow execution is topological-sort + traversal. Ramp configures via declarative config layered on top of an SDK layered on top of a core engine, and explicitly cites 45M+ workflow runs at ~100ms each. [ramp-workflows]

Brex's 2025 "AI-Native Accounting API" announcement emphasizes the same shift: **event-driven**, bidirectional, real-time, "same intelligence as our Accounting Journal." Validation rules and policy constraints defined in the ERP flow back into Brex *before* transactions finalize. [brex-erp]

**Anrok** ([Anrok product][anrok], [Stripe app][anrok-stripe]). The tax-compliance story. Anrok's pitch is that tax is **a primitive in the billing pipeline, not a bolt-on**: their Stripe app puts compliance "directly into Stripe Billing and Checkout" with zero engineering effort. They monitor thresholds across 11,000+ US jurisdictions and 100+ countries. The lesson for kontor's `TaxProvider`: customers expect tax to be invisible-when-correct and not a separate workflow.

**Mercury** ([Mercury Tech][mercury], [Wikipedia][mercury-wiki]). Haskell backend, Elm frontend, type-safe end to end. Mercury markets itself as "not a passive ledger" but a "command center for financial operations." From the engineering interview literature: ACH orchestration, real-time fraud detection at <50ms, reconciliation pipelines that nightly match internal state against partner-bank feeds. [mercury-techinterview]

**Pilot / Puzzle / Numeric / Nominal** — the **continuous close** cohort ([Puzzle][puzzle], [Numeric][numeric]). All four sell the same thesis: month-end-batch is dead, AI-assisted reconciliation runs continuously, and burn/runway/revenue is live. Puzzle exposes an **Embedded Accounting API** for fintechs. The architectural pattern across this cohort: **ingest events, categorize with ML, post immediately, surface exceptions in real-time**. The close becomes a review of exceptions, not a reconstruction of the period.

**Settle / Tipalti / Routable / Tabs** — the modern AP/AR cohort. Routable's framing is explicit: "real-time, two-way sync with QuickBooks, NetSuite, Xero" — they refuse to be a silo, every bill they process flows back into the GL synchronously. [routable]

**Modern Treasury** ([Modern Treasury Ledgers][mt-ledgers], [Scale a Ledger Part I][mt-scale-i], [Designing with Concurrency Control][mt-concurrency]). The clearest first-principles articulation of "ledger as API" in the industry. Their design moves:

- **Three primitives**: Accounts, Transactions, Entries — debits and credits enforced at the API boundary, not in application code.
- **Immutability**: "every change is recorded such that past states can always be retrieved" — past states are queryable, not just rebuildable.
- **Concurrency**: optimistic-locking with a `version` parameter to prevent double-spend even with out-of-order writes.
- **Balance computation**: efficient time-windowed and account-subset aggregation as a first-class API call, not "run a long query."
- **Posting semantics**: "money cannot move without specifying source and destination."

This is the closest thing to kontor's `posting` model in the SaaS world. Where they differ: Modern Treasury treats the ledger as **product infrastructure** (digital wallets, in-app balances), kontor treats it as **the business's books**. The former needs ms latency at 1000s/sec/account; the latter needs auditability at a few hundred transactions/day-per-entity but with bitemporal queries over a decade. The primitives, however, are the same.

### 2.2 FP-in-finance shops — the modeling philosophy

**Jane Street** ([Tech overview][jane-tech], [Bonsai][bonsai], [Incremental][incremental], [Seven Implementations of Incremental][incr-7]). "OCaml all the way down." The relevant transferable patterns:

- **Incremental computation** as a first-class primitive. When inputs change, descendants are *exactly* the nodes that need to recompute. This is the same pattern kontor's `spindel`-aligned sibling project explores; it's how Jane Street keeps a "live business view" responsive while underlying ticks/trades arrive. [incremental, incr-7]
- **Bonsai**: components are purely functional state machines, composable, with a single set of incrementality primitives used both for UI and "incrementaliz[ing] an expensive business logic computation on a live-updating dataset." [bonsai]
- Same language top-to-bottom — and the relevant note here is not "use OCaml" but "use one language that's expressive enough that you don't need a separate orchestration DSL on top of it." This is also Mercury's argument for Haskell+Elm.

**Standard Chartered** ([Serokell post][sc-serokell], [HIW 2021 talk][mu-hiw21], [HIW 2022 Mu+GHC][mu-hiw22], [ACM POPL 2024][mu-acm]). Their analytics library Cortex is ~6.5M lines of Mu (a Haskell dialect) and forms the price-and-risk engine across all asset classes. The relevant move: **financial products are algebraic, not enumerated**. Following Peyton Jones / Eber / Seward's 2000 *Composing Contracts* pearl [peyton-jones-contracts], a contract is a value built from combinators (`zero`, `one`, `give`, `truncate`, `then`, `scale`, ...). Pricing falls out of a denotational semantics, not a switch statement over product types. The pattern is: define the smallest set of primitives that close under composition, then build every actual product as a composition.

This is the **deepest lesson for kontor**: an accounting kernel can have the same shape. A posting, a tax application, a withholding, a deferred-revenue schedule — these should be combinators on a small set of primitives, not 30 different Python classes inheriting from `BaseDocument`.

**Bloomberg** ([CUFP 2014 Ransan talk][bloomberg-cufp], [OCaml industrial users][ocaml-industry]). OCaml in production for derivatives risk management, GUI generation from contract types, and DSL-driven trade capture. The pattern again: **contracts as data + DSL**, with the GUI generated from the contract type, not hand-built per product. New products ship faster because the platform is generative.

**Klarna** ([Erlang Solutions case study][klarna-esl], [Klarna OSS][klarna-oss]). Erlang for the core transaction system; Scala/Clojure/Haskell layered for specific workloads. The cited benefit is fault tolerance — "downtime to zero" claimed — not language elegance. Relevant insight for kontor: **the JVM is fine**, but treat the kernel as something that must not crash silently; reach for supervision-tree-style isolation if you grow into worker pipelines.

**Discord** ([Rust + Elixir at scale][discord-rust]). Not finance, but the lesson translates: 11M concurrent users on Elixir, NIFs in Rust for the hot paths, ~5 engineers running 20+ services. The principle: **pragmatic polyglotism on a uniform substrate** — BEAM is the substrate, Rust is the escape hatch, the data model is shared. For kontor: datahike is the substrate; if a posting throughput hot path needs raw Java or even GraalVM Truffle, it's an escape hatch, not a parallel system.

### 2.3 Failed / forgotten attempts — and the recurring failure modes

**Compiere → Adempiere → iDempiere** ([Wikipedia][adempiere-wiki], [Wikipedia][idempiere-wiki]). The canonical "fork the corporate-sponsor" story. Compiere's community split off Adempiere in 2006 over a sense that VC-driven product direction was ignoring contributions. Adempiere had its own implosion ("I have quit as leader … I have enough after nearly 4 years of battles" — RedHuan D Oon) and iDempiere broke off in 2011 with an OSGi-based plug-in architecture. iDempiere survives as a decentralized project. Lesson: **governance is harder than code**, and "open source ERP" projects die more often from people than from technology.

**Apache OFBiz** ([Apache user stories][ofbiz-users], [FAQ][ofbiz-faq], [TEC review][ofbiz-tec], [Grokipedia][ofbiz-grok]). The canonical refrains from the post-mortems and reviews: documentation is community-dependent and lacking; the out-of-the-box UX is "not a project priority — it is expected to be tailored per customer"; "flexibility comes at the price of complexity"; "Apache OFBiz is just too big" for small open-source companies to absorb. OFBiz has impressive technical depth (entity engine, service engine, screen widget framework — see kontor's own `doc/research/12-ofbiz-companion-mappings.md`) but the breadth is the problem.

**Odoo / OpenERP / TinyERP** ([Wikipedia][odoo-wiki], [Pragmatic Techsoft history][odoo-pragmatic], [Cybrosys history][odoo-cybrosys], [Quora architecture mess?][odoo-quora]). Born 2005 as TinyERP, rebranded OpenERP 2008, Odoo 2014 when "ERP" felt too narrow. The architecture criticism is recurrent: **monolithic; changes in one module affect the whole system; scaling one module means scaling everything; rolling out updates requires system-wide testing; innovation bottlenecked by inter-module coupling**. [odoo-microservices]

**SAP S/4HANA migrations** ([CIO famous disasters][cio-disasters], [CIO migration struggles][cio-s4-migration], [Onfinity top 5 failures][onfinity-sap], [G2 reviews][sap-g2], [Capterra][sap-capterra]). Hershey 1999 ($100M in undeliverable Halloween orders), Nike 2000 ($400M, demand-planning glitch), Avon 2013 (abandoned because employees were leaving over it), Lidl (€500M, 7 years, returned to legacy), Revlon (sales drop + investor lawsuit), Leaseplan (cited the "monolithic nature … hindered incremental product and service improvements"). Horváth's recent study: >60% of S/4HANA migrations have schedule/budget/quality deviations.

The cross-cutting failure modes:

1. **ERP-as-IT-project** rather than business transformation — the system gets installed, the business doesn't change, the system gets blamed.
2. **Compressed timelines** to meet calendar pressure (Hershey).
3. **Inadequate user training and change management**.
4. **Excessive customization** that diverges from upgrade paths.
5. **Data migration underestimated** — duplicate records, inconsistent formats, missing fields.

Note that **none of the recurring failures are technical**. They are organizational. This matters for kontor's positioning: building a better ledger does not save the customer; building a ledger that makes change cheap does.

### 2.4 Newer OSS attempts

**ERPNext / Frappe** ([Wikipedia][erpnext-wiki], [GitHub modularization issue][erpnext-modular]). ERPNext is metadata-driven (DocType is a first-class concept) and survives where Compiere/Adempiere died, partly because the framework (Frappe) is genuinely useful on its own. But the criticism from inside the project — issue #51180, "Modularization of ERPNext: Moving toward a Pluggable Architecture" — admits the monolith is a problem and that the bundled CRM duplicates Frappe CRM "creating redundancy and bloat."

**Twenty** ([Twenty.com][twenty], [GitHub][twenty-gh], [Data model docs][twenty-data], [Codeline review][twenty-runtime]). "The data model is runtime, not code." The backend generates the entire GraphQL schema dynamically from object metadata stored in Postgres; defining a custom object via the UI yields a `findMany` query on the API seconds later. **The data model is data**, and the API is a live projection of that data. This is exactly the move Foundry made and is structurally similar to what datahike enables for kontor — the schema is data, you can query the schema.

**Erxes** — TypeScript, GraphQL Federation + tRPC microservices, Module Federation micro-frontends. The pitch is monorepo + micro-services. [erxes-gh]

**Akaunting, Crater** — Laravel/PHP, focused on accounting for SMEs, App-Store-style extension model. Conventional architecture; nothing structurally novel. [akaunting]

**Sylius** ([Sylius][sylius], [Architecture][sylius-arch]). E-commerce-as-framework. Component-based, every Sylius component (Taxation, Pricing, Order) can be used standalone. Sylius's architecture document is one of the cleanest examples in the OSS world of "build the minimal model, then bundle integration, then layer UI." A direct parallel to what kontor should be in the accounting space.

### 2.5 Workflow primitives and durable execution

**Temporal / Inngest / Restate / DBOS** ([Kai Waehner overview][durable-exec], [Temporal vs Inngest][temporal-vs-inngest], [Restate definition][restate], [Materialized View commentary][materializedview-durable], [Dev.to review][devstarsj-durable]). The durable-execution wave. Four invariants across the family:

1. **Journaled steps** — every external interaction is recorded to a persistent log before its result is observed.
2. **Automatic retries with idempotency** — failed steps are retried, completed steps are not re-executed.
3. **Durable timers and signals** — survive crashes.
4. **Resumability** — any in-flight execution can recover.

The crucial design move: workflows are **code in a general-purpose language**, not BPMN diagrams. The orchestrator manages state; the developer writes regular control flow. Camunda etc. require workflows expressed in BPMN DSL/YAML/visual graph; durable execution flips that.

**Event sourcing + CQRS** ([Greg Young CQRS post][greg-young-cqrs], [Fowler CQRS bliki][fowler-cqrs], [Akka persistence design][akka-design], [Petabridge large-scale][petabridge-scale]). Greg Young's iconic talk uses the **"accountants don't use pencils, they use pens"** framing to argue event sourcing is the natural representation of business activity: events are immutable, you don't update the past, you append corrections. The "double-entry ledger is an event-sourced system" framing has been repeated often enough to be folklore. [eventide-er, datomic-es]

The relevant move for kontor: **kontor already is event-sourced** via datahike's append-only history. The question is whether we surface this in the API as cleanly as Temporal/Eventide/Modern Treasury do.

### 2.6 Composable business — what Gartner actually means

**Gartner's composable thesis** ([Becoming Composable][gartner-composable], [MACH alliance][machalliance]). The catchphrase is "modularity, autonomy, orchestration, discovery." MACH = microservices-based, API-first, cloud-native, headless. Operationally, the prediction is that 70% of enterprise digital-experience platforms will be composable by 2026. The mechanism: every component is pluggable, scalable, replaceable; PBCs ("packaged business capabilities") are independently deployable.

The honest reading: this is a reaction to monolithic SAP/Oracle, packaged for vendors to sell. It is structurally correct (the architecture argument) and operationally optimistic (composing 30 vendors is harder than buying one). For kontor: we are betting on this thesis (kontor as PBC, consumers as orchestrators) but we should be honest about the integration tax it imposes on the customer.

## 3. Comparative design table

Dimensions:

- **R-time**: how real-time the data flow is (∞ = always; ⏰ = batch).
- **DM unity**: one canonical data model? (✓ / partial / ✗)
- **WF primitive**: what is "a workflow"?
- **FP/ES depth**: how deeply does it lean on functional / event-sourcing modeling?
- **UI/data sep**: is the UI a separate composable surface?
- **Ext seams**: how do third parties extend?
- **License**: license model.

| System | R-time | DM unity | WF primitive | FP/ES depth | UI/data sep | Ext seams | License |
|---|---|---|---|---|---|---|---|
| Tesla WARP | ∞ | ✓ (vertical) | Internal services | low (vendor-narrative) | unified | n/a (closed) | Proprietary |
| Palantir Foundry | ∞ | ✓ (Ontology) | Action types + Functions | medium (action log) | Workshop on Ontology | Functions in TS/Python, SDK | Proprietary |
| Anduril Lattice | ∞ | ✓ (object graph) | Mesh tasks, autonomy SDK | medium | unified C2 surface | Lattice SDK | Proprietary |
| Ramp | ∞ | ✓ (config + AST) | Actions/Conditions/Graphs (AST in PG) | medium (DSL) | unified | Workflow config, integrations | Proprietary |
| Brex | ∞ | ✓ (Accounting Journal) | Event-driven AI rules | medium | unified | Accounting API | Proprietary |
| Anrok | ∞ | partial (tax-only) | Compliance hooks in billing pipeline | low | embedded in Stripe etc. | Stripe app + connectors | Proprietary |
| Mercury | ∞ | ✓ (Haskell types) | Command-center rules | high (Haskell+Elm) | unified | API (limited) | Proprietary |
| Puzzle/Numeric/Nominal | ∞ | partial (accounting) | AI-agent + exception queue | low | unified | Embedded Accounting API | Proprietary |
| Modern Treasury | ∞ | ✓ (Accounts/Tx/Entries) | API-driven only | medium (immutable, optimistic-lock) | none (infra) | REST API | Proprietary |
| Settle/Tipalti/Routable | ∞ | partial (AP/AR) | Sync workflows to GL | low | unified per product | Bi-directional GL sync | Proprietary |
| Jane Street (internal) | ∞ | ✓ (OCaml types) | Bonsai + Incremental | very high | Bonsai on top of types | Internal libs | Closed |
| Standard Chartered Mu/Cortex | ∞ | ✓ (algebraic) | Combinator expressions | very high (Haskell dialect) | n/a | Mu libs | Closed |
| Bloomberg | ∞ | ✓ (OCaml DSL) | DSL-defined contracts | very high | GUI-from-types | OCaml libs | Closed |
| Apache OFBiz | ⏰ | partial (entity engine) | Service engine + screen widgets | low | bundled | Plugins, custom services | Apache-2.0 |
| Compiere / iDempiere | ⏰ | partial | OSGi modules | low | bundled | OSGi plugins | GPL / others |
| Odoo (monolith) | ⏰/∞ | partial (ORM) | XML workflows + Python actions | low | bundled | Python modules | LGPL + proprietary |
| ERPNext / Frappe | ⏰/∞ | ✓ (DocType metadata) | Server scripts + hooks | low | bundled | DocType extensions, apps | GPL-3 |
| Twenty | ∞ | ✓ (runtime metadata) | Workflow builder | low | unified | GraphQL extensions | MIT |
| Sylius | ⏰/∞ | ✓ (component-based) | State machines | medium | decoupled (headless) | Symfony bundles | MIT |
| Akaunting / Crater | ⏰ | partial | Conventional MVC | low | bundled | App-store extensions | AGPL / MIT |
| Temporal / Inngest / Restate | n/a | n/a | Durable execution (code) | medium (journaled) | none | SDK in many langs | Apache-2.0 / commercial OSS |
| Eventide / Akka Persistence | n/a | n/a | Aggregates + events | very high (ES/CQRS) | none | Aggregate roots | MIT / Apache-2.0 |
| **kontor (today)** | **∞** | **✓ (datahike schema)** | **postings + assertions** | **medium-high (bitemporal, sealed)** | **✗ (no UI, by design)** | **datahike attrs + protocols** | **EPL-1.0** |

The pattern: **the products with strong "integrated" reputations score ✓ on data-model unity, ∞ on real-time, and have a single workflow primitive that scales from trivial to complex**. The failed/forgotten ones either fragment the data model (Compiere/Odoo modules drifting), batch the data flow (most pre-2015 ERPs), or have no first-class workflow primitive (every team writes ad-hoc Python).

## 4. Patterns kontor should adopt

The following are concrete and tied to existing kontor ADRs / schema. Each lists who pioneered it, why it works, and how it'd land in kontor.

### 4.1 Ontology-as-runtime (Foundry, Twenty)

**Pioneer**: Foundry — semantic + kinetic ontology. Twenty independently re-derived a simpler version (metadata-as-data, schema-as-runtime).

**Why it works**: Customers and consumer apps need to extend the data model without forking. If the schema is data, then "add a new dimension to a Posting" is a transaction, not a deploy. This is the same idea kontor already has via datahike's schema-as-data and the `:account-tag/*` namespace convention.

**What kontor is missing**: We have schema-as-data implicitly, but we don't expose it as a first-class consumer API. Specifically:

1. **No documented way to register a new namespace from a consumer module without an upstream PR.** ADR-002 says namespaces require an ADR — that's right for kernel attributes, but consumer-defined extension attributes should be cheap.
2. **No "action type" equivalent.** Consumers write ad-hoc `kontor.posting/post-transaction!` callers; there is no declarative "this is an Action: it takes these params, validates these invariants, writes these postings, has this side effect." Foundry's action types make the *thing the user did* part of the audit trail; we have the audit trail but the action shape is implicit.
3. **No reflection API.** Foundry's Workshop and AIP can introspect the Ontology because object types are queryable as data. Consumers of kontor would benefit from a `(kontor.schema/object-types db)` that returns the list of `:account`, `:posting`, `:partner`, etc. with their attribute lists.

**Concrete proposal**: An ADR for **kontor Actions** — a small layer (~200 LOC) that lets consumers declare:

```clojure
(defaction post-invoice-payment
  :params  {:invoice-id :db/id :amount Money :date :time/date}
  :guards  [#(< 0 (:amount %))]
  :effects [(post-tx ...) (mark-invoice-paid ...)]
  :audit   {:user-id ... :reason ...})
```

The action becomes a first-class entity (`:action/*` namespace), the invocation is recorded as a transaction with the action's id, and consumers can reflect over the registered actions. This is **Foundry's action type adapted to kontor's scale and idiom**, and it solves the "every consumer reinvents the wrapping pattern" problem we'll otherwise hit when `beleg` and `simmis` both build invoice-payment flows.

### 4.2 Algebraic primitives + DSL on top (Standard Chartered Mu, Peyton Jones contracts, Bloomberg)

**Pioneer**: Peyton Jones / Eber / Seward 2000 *Composing Contracts*. Operationalized at Standard Chartered (Mu/Cortex, 6.5M LOC), Bloomberg (contract DSL), LexiFi (MLFi).

**Why it works**: When new products are common (every customer has a new revenue model, a new tax wrinkle, a new lease type), enumerating them in code or schema produces O(N) growth and O(N²) integration bugs. A small set of combinators that *compose* produces a generative space — the next product is a new expression, not a new class.

**What kontor already has**: A reasonable money primitive (`kontor.money`), a posting primitive that enforces balance, a tax-provider protocol. These are the seeds of combinators.

**What kontor is missing**:

1. **Posting schedules as combinators.** Deferred revenue recognition, depreciation, amortization, lease accounting (IFRS 16 / ASC 842) — these are all **schedule combinators**: `(straight-line amount months)`, `(declining-balance amount rate years)`, `(milestone-revenue contract milestones)`. Today a consumer writing IFRS 16 would hand-roll a schedule loop. We should have `kontor.schedule` with a small composable algebra.
2. **Tax application as a composable transform.** `apply-tax` is currently one-shot. Real-world tax is a stack: customer-exemption → reverse-charge-check → jurisdictional-rate-lookup → split-by-component (state/county/city) → withholding → rounding. A combinator stack would make per-country l10n modules contribute small transforms instead of monolithic providers.
3. **Posting transforms** — e.g. `(reverse tx)`, `(net-against tx other-tx)`, `(reclassify tx new-account)` — should be first-class with audit semantics, not ad-hoc helpers.

**Concrete proposal**: Two ADRs. (a) `kontor.schedule` — a small combinator library for revenue/expense schedules, modeled on Peyton Jones contracts but tuned to accounting (not derivative pricing). (b) `kontor.tax/compose` — a tax-application stack that lets l10n modules contribute layered transforms instead of monolithic providers.

### 4.3 Durable execution for cross-aggregate workflows (Temporal/Inngest/Restate)

**Pioneer**: Temporal (formerly Cadence at Uber). Refined by Inngest (event-driven), Restate (minimal), DBOS (DB-native).

**Why it works**: An accounting workflow that spans (a) wait-for-invoice, (b) wait-for-payment, (c) post-revenue, (d) wait-30-days, (e) recognize-deferred, (f) handle-refund-if-applicable cannot be expressed as a single transaction. Today this is implemented as cron jobs + DB state + reconciliation. Durable execution makes it a single function with awaits.

**What kontor is missing**: We have *no* workflow primitive. A consumer writing a subscription billing flow must build all of (a)-(f) on their own, with their own scheduler, idempotency keys, and retry logic. This is **the integration tax** that pushes consumers back toward Odoo.

**Concrete proposal**: Do NOT build a durable-execution engine inside kontor. But:

1. **Document the integration pattern** with Temporal / Inngest / a Clojure-native option (e.g., `missionary`, `tasks` on top of `clojure.core.async`).
2. **Provide idempotency primitives**: `:transaction/idempotency-key` attribute + a kontor helper that says "post this tx if and only if no tx with this key exists." This is the smallest primitive that makes external workflow engines safe to use against kontor.
3. **Provide saga-compensation helpers**: `(compensate tx)` returns a reversing posting tagged so it can be matched to the original.

### 4.4 Incremental computation for live reports (Jane Street Incremental + Bonsai)

**Pioneer**: Jane Street. Adopted by Adapton (academic), Salsa (Rust), Reflex (Haskell), and Clojure's `spindel`.

**Why it works**: A trial balance, an AR aging, a cash forecast, an open-invoice list — these are queries over postings that should update on each new posting, not on a refresh button. A 100ms query that runs 1000 times a day costs less than a 5ms query that runs 100k times because incremental computation pays for itself when reads dominate.

**What kontor is missing**: All bitemporal queries today are point-in-time recomputes. There is no story for "subscribe to trial-balance for this period and push me a delta when it changes."

**Concrete proposal**: This belongs in `simmis` / `spindel`, not kontor. But kontor should:

1. **Expose tx-log subscriptions** as a documented primitive (datahike likely already supports this via `:tx-listen!` or similar — verify and document).
2. **Design queries so they are diff-friendly**: query results should be sets or maps keyed by entity, not lists, so a consumer can compute set/map diffs cheaply.
3. **Tag postings with `:posting/lot-id` and `:posting/period-id` consistently** so per-period and per-lot aggregations don't require scanning history.

### 4.5 Ledger as command center, not passive record (Mercury, Modern Treasury)

**Pioneer**: Modern Treasury made it explicit; Mercury markets it as a brand.

**Why it works**: A ledger that is *only* a record of past events is a tail. A ledger that is also the place where rules ("if expense > $500, require approval"; "if account < $100, alert") live becomes the head. The latter is far stickier and far more central to the business.

**What kontor is missing**: We have postings but no first-class **rules engine** or **policy** primitive. A consumer wanting "auto-flag transactions over $X, require manager approval" must build that on top of kontor with their own state.

**Concrete proposal**: A small `kontor.policy` namespace — declarative rules (`:policy/predicate`, `:policy/action`, `:policy/scope`) that live next to postings. ADR-pending. This dovetails with 4.1 (Actions): a policy is the predicate side, an action is the response side.

### 4.6 Idempotent webhook / event ingest (Brex AI accounting API, Ramp)

**Pioneer**: Stripe (long ago). Refined by Brex and Ramp for accounting events specifically.

**Why it works**: Real-time integration means events arrive out of order, retried, duplicated. The only way to stay consistent is idempotency keys at the API boundary.

**What kontor is missing**: A documented "this is how you ingest an external event idempotently" pattern. The piece is small (an attribute + a uniqueness constraint) but absent.

**Concrete proposal**: `:transaction/source-system` + `:transaction/source-id` + a uniqueness constraint per (source-system, source-id). Helper `(ingest! conn source-system source-id tx-data)` that no-ops on duplicate.

### 4.7 Schema-driven UI/integration generation (Bloomberg DSL → GUI, Twenty)

**Pioneer**: Bloomberg in OCaml; Twenty as a recent OSS instance.

**Why it works**: If the schema is data, then the UI / API / docs / validation are all projections of the schema. Adding a field happens in one place.

**What kontor is missing — and should embrace**: kontor explicitly does **not** ship a UI (ADR-010). But the spirit of this pattern can still apply:

1. **Generate documentation from schema.** A `(kontor.docs/generate)` that emits a Markdown reference from the live schema is one tiny helper that pays back forever.
2. **Generate validation predicates from schema.** A consumer writing forms in `beleg` should be able to derive its validation from the kontor schema, not duplicate it.
3. **Generate sample data / fixtures from schema.** Today `kontor.core/create-test-db` returns an empty DB; a `(seed-realistic)` helper would help consumers test.

## 5. Patterns to AVOID

From the failures studied:

- **Don't ship a UI to "help" consumers.** Odoo, Compiere, OFBiz all lost the plot here. UI breeds opinions, opinions breed customization, customization breeds upgrade pain. ADR-010 already commits to this — keep the discipline.
- **Don't ship a workflow DSL.** BPMN-style DSL is what makes Camunda integrations painful. If we add workflow at all (4.3), it's a Clojure function with awaits, not XML.
- **Don't enumerate business objects when a combinator suffices.** Avoid the Odoo trap of a separate Python class per product type. Use combinators (4.2).
- **Don't ship per-country data inside the kernel.** ADR-005 already commits to this. Don't relax it under pressure.
- **Don't bundle modules unless they share semantics.** Frappe/ERPNext's CRM-bundled-into-ERP-but-also-a-separate-app problem is what happens when "we already have a customer table" trumps "is CRM the same concern as accounting?" — it isn't. Keep kontor narrow.
- **Don't compete with Modern Treasury.** They are infra for fintechs; we are kernel for accounting. We can borrow their primitive design (Accounts/Transactions/Entries → ours: accounts/postings/transactions) without competing on their throughput envelope.
- **Don't promise "integrated" if the integration is the consumer's problem.** Be honest in the README: kontor is the substrate, not the assembled system. The composable-business thesis is real but it imposes a real integration tax (Gartner-style breathless marketing notwithstanding).
- **Don't over-abstract before having three real consumers.** The Mu/Cortex story is 6.5M LOC because Standard Chartered has thousands of products. kontor has `beleg` (one) and `simmis` (planned). Abstractions invented for hypothetical third consumers are dead weight.
- **Don't fork your own community.** The Compiere → Adempiere → iDempiere fork pattern is the cautionary tale. Governance is harder than code. Keep ADRs as the contract.

## 6. What's specifically missing from kontor — the integrated-workflow lens

Honest list, scoped by what shows up when you try to build an integrated stack on top:

1. **No declarative action / command layer.** Consumers write `(post-transaction! conn ...)` directly. No way to register a named action, no audit of "user X performed action Y with params Z." → §4.1.
2. **No idempotency primitive.** External ingest (webhooks, scheduled jobs, retried imports) cannot stay consistent without this. → §4.6.
3. **No workflow / saga primitive — even a tiny one.** Multi-step business flows live in consumers. We should at least define **what we expect from a durable-execution layer** (the protocol kontor speaks to it). → §4.3.
4. **No policy / rules surface.** "Require approval if X" lives in consumer code, with consumer state. We could provide attribute-level policies attached to accounts/partners/transactions. → §4.5.
5. **No subscription / change-feed primitive surfaced.** datahike may have one; kontor doesn't expose it. Live reports / dashboards / agent integrations need this. → §4.4.
6. **No schedule combinators.** Deferred revenue, depreciation, lease accounting, subscription billing — all require schedule generation. Today every consumer rolls its own. → §4.2.
7. **No reflection on schema.** "List all account-tags" or "what attributes belong to a posting" should be a one-liner. → §4.1, §4.7.
8. **No multi-entity workflow primitives.** Showcase 4 (intercompany) exists, but the substrate for "post in entity A, mirror in entity B with FX, settle in entity C" is hand-rolled. → §4.2 + §4.6.
9. **No sample external-integration patterns documented.** A consumer wiring Stripe webhooks → kontor postings has to figure it out. → §4.6.
10. **No "view" / "projection" namespace.** Every consumer that needs an AR aging or trial balance writes a query from scratch. A `kontor.views` namespace with named, parameterized, bitemporal projections (`(ar-aging conn :as-of ...)`, `(open-invoices conn :customer ...)`) would be a small, high-leverage addition.

Note what is NOT missing: the kernel itself (postings, accounts, tax, bitemporal queries, sealing, audit, periods) is solid. The gap is the **integration glue** — the thin layer that turns "a great ledger" into "a substrate for an integrated stack."

## 7. Open questions for design discussion

1. **Should kontor expose an Actions layer (§4.1), or is this `beleg`'s job?** Tension: putting it in the kernel makes consumers share a vocabulary; putting it in consumers keeps the kernel narrow. Possible split: kernel exposes `kontor.action/defaction` macro + `:action/*` schema; consumers register actions.
2. **Combinators or schemas for schedules (§4.2)?** Schedules can be expressed as combinator functions (composable, opaque) or as schema entities (queryable, introspectable). The latter loses some expressive power but gains a lot for reporting. Foundry chose the schema side; Standard Chartered chose the combinator side. We probably want a hybrid: combinators that *generate* schedule entities.
3. **Do we want a Clojure-native durable-execution module, or do we recommend Temporal?** A Clojure-native option (built on datahike's tx-log + a small reducer) is appealing for the "JVM Clojure single-runtime" promise (CLAUDE.md), but it's a real ~3-month build with hard edges. Recommending Temporal is faster but introduces a second runtime.
4. **Should `kontor.policy` evaluate at write-time, read-time, or both?** Write-time means policy violations block postings; read-time means they surface as flags. Tax-style validations want write-time; "alert me if cash < X" wants read-time-with-subscription.
5. **How much of the Foundry "ontology = ops layer" framing should we adopt?** We share the technical foundation (schema-as-data + bitemporal). But Foundry's surface for end-user reasoning (Object Explorer, Workshop, AIP) is a consumer concern. Should kontor's ontology be **queryable by AI agents directly**, with kontor providing the MCP / function-calling adapter, or is that strictly a `simmis` concern?
6. **What's the kontor stance on Modern-Treasury-style **product ledgers** vs accounting ledgers?** Customers using kontor for things like in-app wallets, prepaid balances, gift cards — should we encourage that with a separate `kontor.product-ledger` namespace, or stay strict that kontor is for accounting books only?
7. **What's the right amount of "policy as data" before it becomes its own DSL trap?** Ramp's workflow ASTs in Postgres are ~45M runs of evidence that small declarative graphs work. Camunda's BPMN graphs are evidence that big declarative graphs do not. Where's the line?
8. **Should we publish a "kontor integration patterns" doc as a first-class deliverable**, separate from kernel docs? The "how to wire Stripe → kontor" recipe is a different audience from the kernel internals doc, and right now we have neither.

---

## Sources

[warp-grok]: https://grokipedia.com/page/warp-erp-system "Warp (ERP system) — Grokipedia"
[warp-joshi]: https://medium.com/@joshiabhi777/the-untold-story-of-teslas-custom-erp-system-why-building-warp-was-a-genius-move-5538abeca454 "The Untold Story of Tesla's Custom ERP System — Abhishek Joshi"
[vijayan-yt]: https://www.youtube.com/watch?v=JzdziadEkzs "The (epic) Untold Tesla Story - Jay Vijayan and Tesla Warp Drive"
[electrek-3dx]: https://electrek.co/2016/02/15/tesla-3dx-model-3/ "Tesla 3DX platform — Electrek"
[foundry-onto]: https://www.palantir.com/docs/foundry/ontology/overview "Overview • Ontology • Palantir"
[foundry-obj]: https://www.palantir.com/docs/foundry/object-backend/overview "Ontology architecture — Palantir"
[foundry-actions]: https://www.palantir.com/docs/foundry/action-types/overview "Action types • Overview • Palantir"
[foundry-core]: https://www.palantir.com/docs/foundry/ontology/core-concepts "Core concepts • Palantir"
[foundry-why]: https://www.palantir.com/docs/foundry/ontology/why-ontology "Why create an Ontology? • Palantir"
[foundry-airbus]: https://www.palantir.com/impact/airbus/ "Impact | Airbus and Skywise"
[lattice]: https://www.anduril.com/news/anduril-s-lattice-a-trusted-dual-use-commercial-and-military-platform-for-public-safety-security "Anduril's Lattice — Anduril"
[ramp-workflows]: https://engineering.ramp.com/post/workflows "Abstraction Engineering — Ramp Builders Blog"
[brex-erp]: https://www.brex.com/journal/press/brex-launches-ai-native-accounting-api "Brex Launches AI-Native Accounting API"
[anrok]: https://www.anrok.com/product "Platform overview | Anrok"
[anrok-stripe]: https://www.anrok.com/resources/introducing-anrok-stripe-app "Introducing Anrok's Stripe app"
[mercury]: https://mercury.com/ "Mercury — Banking for startups"
[mercury-wiki]: https://en.wikipedia.org/wiki/Mercury_Technologies "Mercury Technologies — Wikipedia"
[mercury-techinterview]: https://www.techinterview.org/companies/mercury/ "Mercury Interview Guide — Tech Interview"
[puzzle]: https://puzzle.io/ "Puzzle - AI Accounting Software for Startups"
[numeric]: https://www.numeric.io/ "Numeric | AI-Powered Close Automation"
[routable]: https://www.routable.com/resources/bill-vs-tipalti/ "BILL vs Tipalti: AP Automation Comparison | Routable"
[mt-ledgers]: https://www.moderntreasury.com/products/ledgers "Ledgers - Modern Treasury"
[mt-scale-i]: https://www.moderntreasury.com/journal/how-to-scale-a-ledger-part-i "How to Scale a Ledger, Part I"
[mt-concurrency]: https://www.moderntreasury.com/journal/designing-ledgers-with-optimistic-locking "Designing the Ledgers API with Concurrency Control"
[jane-tech]: https://www.janestreet.com/technology/ "Technology — Jane Street"
[bonsai]: https://github.com/janestreet/bonsai "janestreet/bonsai — GitHub"
[incremental]: https://www.janestreet.com/tech-talks/intro-to-incr-dom/ "Introduction to Incr_dom — Jane Street tech talks"
[incr-7]: https://www.janestreet.com/tech-talks/seven-implementations-of-incremental/ "Seven Implementations of Incremental — Jane Street"
[sc-serokell]: https://serokell.io/blog/haskell-in-production-standard-chartered "Haskell in Production: Standard Chartered — Serokell"
[mu-hiw21]: https://icfp21.sigplan.org/details/hiw-2021-papers/14/Haskell-reinterpreted-large-scale-real-world-experience-with-the-Mu-compiler-in-Fin "Haskell reinterpreted — HIW 2021 (ICFP)"
[mu-hiw22]: https://icfp22.sigplan.org/details/hiw-2022/8/Compiling-Mu-with-GHC-Halfway-Down-the-Rabbit-Hole "Compiling Mu with GHC — HIW 2022 (ICFP)"
[mu-acm]: https://dl.acm.org/doi/10.1145/3674633 "Functional Programming in Financial Markets — ACM POPL/OOPSLA"
[peyton-jones-contracts]: https://www.cs.tufts.edu/~nr/cs257/archive/simon-peyton-jones/contracts.pdf "Composing Contracts (Peyton Jones, Eber, Seward, 2000)"
[bloomberg-cufp]: http://cufp.org/2014/maxime-ransan-adopting-functional-programming-with-ocaml-at-bloomberg-lp.html "CUFP 2014 — Adopting OCaml at Bloomberg (Maxime Ransan)"
[ocaml-industry]: https://ocaml.org/industrial-users "OCaml in Industry"
[klarna-esl]: https://www.erlang-solutions.com/case-studies/klarna/ "Klarna — Erlang Solutions case study"
[klarna-oss]: https://klarna.github.io/ "Klarna Engineering — Open Source"
[discord-rust]: https://discord.com/blog/using-rust-to-scale-elixir-for-11-million-concurrent-users "Using Rust to Scale Elixir for 11M Concurrent Users — Discord"
[adempiere-wiki]: https://en.wikipedia.org/wiki/Adempiere "Adempiere — Wikipedia"
[idempiere-wiki]: https://en.wikipedia.org/wiki/IDempiere "iDempiere — Wikipedia"
[ofbiz-users]: https://cwiki.apache.org/confluence/display/OFBIZ/Apache+OFBiz+User+Stories "Apache OFBiz User Stories"
[ofbiz-faq]: https://ofbiz.apache.org/faqs.html "Apache OFBiz FAQ"
[ofbiz-tec]: https://www3.technologyevaluation.com/solutions/15999/apache-ofbiz "Apache OFBiz Reviews — TEC"
[ofbiz-grok]: https://grokipedia.com/page/Apache_OFBiz "Apache OFBiz — Grokipedia"
[odoo-wiki]: https://en.wikipedia.org/wiki/Odoo "Odoo — Wikipedia"
[odoo-pragmatic]: https://blog.pragtech.co.in/journey-of-odoo-from-tinyerp-to-openerp-to-odoo/ "Journey of Odoo — Pragmatic Techsoft"
[odoo-cybrosys]: https://www.cybrosys.com/blog/odoo-the-journey-from-tinyerp-to-odoo "Odoo: From TinyERP to Odoo — Cybrosys"
[odoo-quora]: https://www.quora.com/Are-Odoo-OpenERP-architecture-and-documentation-a-mess "Are Odoo/OpenERP architecture and documentation a mess? — Quora"
[odoo-microservices]: https://medium.com/@jacobweber005/the-future-of-odoo-erp-development-from-monolithic-systems-to-microservices-5b87bda2896a "Odoo monolith to microservices — Jacob Weber"
[cio-disasters]: https://www.cio.com/article/278677/enterprise-resource-planning-10-famous-erp-disasters-dustups-and-disappointments.html "18 famous ERP disasters — CIO"
[cio-s4-migration]: https://www.cio.com/article/3851772/sap-users-struggle-with-s4-hana-migration.html "SAP customers struggle with S/4HANA migration — CIO"
[onfinity-sap]: https://onfinity.io/blog/technologies/top-5-sap-s-4-hana-failures-and-lessons-learned/ "Top 5 SAP S/4 HANA Failures — Onfinity"
[sap-g2]: https://www.g2.com/products/sap-cloud-erp-formerly-sap-s-4hana-cloud/reviews "SAP Cloud ERP Reviews — G2"
[sap-capterra]: https://www.capterra.com/p/152293/SAP-S-4HANA/reviews/ "SAP S/4HANA Reviews — Capterra"
[erpnext-wiki]: https://en.wikipedia.org/wiki/ERPNext "ERPNext — Wikipedia"
[erpnext-modular]: https://github.com/frappe/erpnext/issues/51180 "Modularization of ERPNext — GitHub issue"
[twenty]: https://twenty.com/ "Twenty | #1 open source CRM"
[twenty-gh]: https://github.com/twentyhq/twenty "twentyhq/twenty — GitHub"
[twenty-data]: https://docs.twenty.com/user-guide/data-model/overview "Twenty Data Model — Docs"
[twenty-runtime]: https://www.codeline.co/thoughts/repo-review/2024/twenty-open-source-crm "Twenty: data model is runtime — Florian Narr"
[erxes-gh]: https://github.com/erxes/erxes "erxes — GitHub"
[akaunting]: https://www.softaculous.com/apps/erp/Akaunting "Akaunting — Softaculous"
[sylius]: https://sylius.com/ "Sylius — Open Source Headless eCommerce"
[sylius-arch]: https://docs.sylius.com/the-book/architecture "Sylius Architecture — Docs"
[durable-exec]: https://www.kai-waehner.de/blog/2025/06/05/the-rise-of-the-durable-execution-engine-temporal-restate-in-an-event-driven-architecture-apache-kafka/ "The Rise of the Durable Execution Engine — Kai Waehner"
[temporal-vs-inngest]: https://www.inngest.com/compare-to-temporal "Inngest vs Temporal"
[restate]: https://www.restate.dev/what-is-durable-execution "What is Durable Execution? — Restate"
[materializedview-durable]: https://materializedview.io/p/durable-execution-justifying-the-bubble "Durable Execution: Justifying the Bubble — Materialized View"
[devstarsj-durable]: https://devstarsj.github.io/2026/04/03/durable-execution-temporal-restate-dbos-distributed-workflows-2026/ "Durable Execution — Temporal, Restate, DBOS"
[greg-young-cqrs]: http://codebetter.com/gregyoung/2010/02/13/cqrs-and-event-sourcing/ "CQRS and Event Sourcing — Greg Young"
[fowler-cqrs]: https://martinfowler.com/bliki/CQRS.html "CQRS — Martin Fowler"
[akka-design]: https://akka.io/blog/cloud-native-app-design-techniques-cqrs-event-sourcing-messaging "Cloud-native CQRS / ES — Akka"
[petabridge-scale]: https://petabridge.com/blog/largescale-cqrs-akkadotnet-v1.5/ "Scaling Akka.Persistence.Query — Petabridge"
[eventide-er]: https://martinfowler.com/eaaDev/AccountingNarrative.html "Patterns for Accounting — Fowler"
[datomic-es]: https://vvvvalvalval.github.io/posts/2018-11-12-datomic-event-sourcing-without-the-hassle.html "Datomic: Event Sourcing without the hassle"
[gartner-composable]: https://www.gartner.com/en/doc/becoming-composable-gartner-trend-insight-report "Becoming Composable — Gartner Trend Insight"
[machalliance]: https://machalliance.org/insights-hub/composable-comes-of-age-in-the-gartner-dxp-magic-quadrant "Composable comes of age — MACH Alliance"
