# Research note 21 — Process / workflow modeling: should `kontor` grow a workflow primitive?

The question this note answers: does `kontor` need a workflow / process primitive beyond
ADR-034 (`:status-transition` / `:status-history`) and ADR-041 (`:side-effect-intent`),
and if so, what shape?

ADR-034 + ADR-041 already cover entity-facet state machines (invoice status, order
status, return status, dispute state) and the side-effect dispatcher. The open question
is whether non-accounting-shaped processes — vendor onboarding, hiring, multi-step
approvals, incident response, contract negotiation, RFP/quote/PO/invoice/payment
B2B handshakes — should live in the kernel, in a companion module, or entirely in
consumer apps.

This is a synthesis from web research into the BPM / durable-execution / process-mining /
statechart landscape, cross-referenced against what kontor's substrate already provides.
Sources are cited inline; full reference list at the end.

## TL;DR

- **The kernel should NOT grow a workflow engine.** Both BPMN-shaped engines (Camunda,
  Activiti, Flowable, jBPM, Pega) and durable-execution engines (Temporal, Restate,
  Inngest, DBOS, Hatchet, Trigger.dev, Windmill, Step Functions) are large, opinionated,
  expensive systems. kontor's scope (ADR-001) is the accounting kernel; ADR-010 explicitly
  rules out "no workflow engine." That call still holds.
- **Stay-pure plus one small lift.** The recommended path is **stay-pure plus a thin
  `:workflow-instance` correlation entity** that joins existing `:status-history` rows
  across multiple entities into one named process run, with a sentinel `:closed-at`. No
  durable-execution loop, no BPMN, no DMN — just a queryable correlation handle.
- **Process mining over kontor's bitemporal log is a 4–6 week project, not 6 months.**
  Datahike's tx-log + ADR-034 `:status-history` is structurally an OCEL 2.0 event log.
  The kernel can ship a small `kontor.process` namespace that exports XES / OCEL and
  performs the basic Heuristics-Miner discovery in pure Clojure. This is genuinely
  novel — no OSS accounting kernel ships native process mining today.
- **Flat FSM is enough; do NOT adopt statecharts in the kernel.** The cases that need
  hierarchical / parallel states (dispute-while-dunning, multi-region tax-clearance, n-of-m
  approval) factor cleanly into independent facets on the same entity (kontor's existing
  "one entity, multiple facets" pattern from ADR-034). Statecharts add ~10x conceptual
  surface for a win that the facet pattern already captures.
- **Cross-organization workflows belong in companion modules**, not the kernel. The buyer/seller
  dance is N coupled state machines linked by exchange documents (UBL, Peppol, EDIFACT,
  cXML), not one workflow. The kernel ships the document primitives (`:invoice`, `:order`,
  `:payment-application`) and the status machines; the cross-org coordination — message
  ID, in-reply-to, attestation tokens — lives in `kontor-edi-*` or per-protocol modules.
- **Companion `kontor-workflow` is plausible but NOT urgent.** If a real consumer pulls
  for it (e.g. simmis wanting to model an HR onboarding flow alongside payroll), build
  it then. The interesting question is whether to wrap an existing engine (Temporal Java
  SDK, fulcrologic/statecharts) or grow our own; both have been done at half-day cost
  by other Clojure shops.

## 1 — Landscape map

The workflow / process tooling space splits into five tribes. The dimensions that matter
for a kontor decision: code-vs-visual authoring, durable-execution guarantee, expressivity
of the state model, time-travel / replay, audit granularity, FP friendliness, license.

### 1.1 Classical BPM engines (BPMN 2.0 + DMN)

**Camunda 8 / Zeebe** is the modern reference. Zeebe is an event-sourced, distributed
workflow engine that writes to an append-only log on local disk, replicates across
brokers, and uses an external-task pattern that decouples engine from business code
([Camunda docs, "Introduction to Zeebe"](https://docs.camunda.io/docs/components/zeebe/zeebe-overview/);
[Camunda blog, Intuit scaling case study](https://camunda.com/blog/2024/08/scaling-workflow-engines-intuit-camunda-8-zeebe/)).
What it gets right: durable execution, visual model that auditors and analysts can read,
DMN decision tables for rules, two decades of BPMN tooling. What bites users: as of
Camunda 8.6 (October 2024), production use requires a commercial Enterprise license —
the v7 Apache-2.0 era is over ([Camunda blog, licensing update, 2024](https://camunda.com/blog/2024/04/licensing-update-camunda-8-self-managed/)).
Camunda 7 itself was a 2013 fork of Activiti.

**Lineage.** jBPM (2003) → Activiti (2010, founders left Red Hat for Alfresco) →
Camunda (2013, fork of Activiti) → Flowable (2016, Activiti founders left Alfresco)
→ Camunda 8 (2022, complete rewrite as Zeebe) ([Capital One Tech, OSS BPM
comparison 2022](https://medium.com/capital-one-tech/2022-open-source-bpm-comparison-33b7b53e9c98);
[ECM Architect, Flowable fork announcement, 2016](https://ecmarchitect.com/archives/2016/10/15/4192)).
jBPM survived as Kogito (cloud-native Drools + jBPM rewrite, 2020). All four have
production deployments; none have escaped the "diagram is the source of truth"
constraint.

**DMN (Decision Model & Notation)** is BPMN's complement. Where BPMN expresses control
flow, DMN expresses decision tables. When a BPMN diagram has more than two or three
exclusive-gateway diamonds, the consensus is "factor the decision out to DMN" ([Camunda
docs, "BPMN, DMN, and FEEL"](https://docs.camunda.io/docs/components/concepts/bpmn-dmn-feel/);
[Trisotech, BPMN best practices](https://www.trisotech.com/bpmn-modeling-best-practices/)).
DMN tables version independently of the process — change a tax rule without redeploying
the workflow. This is genuinely useful and structurally close to kontor's "vocabulary
as data" pattern (ADR-034, ADR-037).

**Pega** is the high-end commercial reference. Pega differs from classical BPM by being
**case-management-shaped** rather than process-shaped: ad-hoc steps allowed, execution
order not forced, AI-driven next-best-action recommendations ([Pega community blog,
"Case Management x BPM"](https://support.pega.com/discussion/case-management-x-bpm-whats-true-difference)).
The Pega frame is closer to what kontor already does with `:status-history` plus
optional `:approval-policy`: most "processes" in finance are actually weakly-ordered
checklists with hard preconditions, not strict sequence diagrams.

**Common BPMN failure modes** ([Visual Paradigm, "BPMN diagram best
practices"](https://skills.visual-paradigm.com/docs/bpmn-diagram-types-explained/bpmn-diagram-best-practices/);
[Visual Paradigm, "Common pitfalls in BPMN modeling"](https://archimate.visual-paradigm.com/2025/01/27/write-a-comprehensive-guide-for-chapter-4-of-the-bpmn-toc-above/);
[FlyingDog, "5 common pitfalls in enterprise BPM"](https://www.flyingdog.de/portal/en/blog/bpm-implementation-mistakes-avoid-enterprise/)):
~100 symbols in the BPMN 2.0 spec, event-based gateway is not intuitive, business rules
get inlined into the diagram (use DMN), diagrams become unreadable past ~20 nodes, IT
ownership leads to low business-side adoption, "the diagram is executable" is a category
error (BPMN is a modeling notation, the executor is a separate concern).

### 1.2 Durable-execution engines (code-first)

**Temporal** is the dominant code-first alternative. Maxim Fateev's pitch:
"fault-oblivious stateful execution — write code as if failures don't exist, the platform
records each step and replays from any point of failure without re-executing completed
steps" ([SE Radio 596, Fateev on durable execution,
2023](https://se-radio.net/2023/12/se-radio-596-maxim-fateev-on-durable-execution-with-temporalse-radio-596/);
[Temporal docs, "Workflow Execution
overview"](https://docs.temporal.io/workflow-execution)). The killer demo is a workflow
that survives a JVM restart, deploy, network partition, and decade-long sleep without
losing state. Workflows are real code in Java/Go/TS/.NET; activities are the side-effect
boundaries.

Fateev's 2025 essay "The Fallacy of the Graph" argues graphs are a wrong-shape
abstraction for procedural logic: data-passing fragility (string keys for cross-node
references), brittle refactoring, weak compile-time checks, accidental complexity of
gateways for what is fundamentally an if/else ([Maxim Fateev,
Medium](https://medium.com/@mfateev/the-fallacy-of-the-graph-why-your-next-agentic-workflow-should-be-code-not-a-diagram-85b8f70b197a);
[HN discussion](https://news.ycombinator.com/item?id=44962240)). The counter-argument
(Long Quanzheng): code-first doesn't have to mean durable-execution-only; declarative
JSON workflows like Netflix Conductor are a third path ([Long Quanzheng,
Medium](https://medium.com/@qlong/workflow-should-be-code-but-durable-execution-is-not-the-only-way-519f7682360c)).

**The 2024–2026 durable-execution bubble** ([Kai Waehner, "The Rise of the Durable
Execution
Engine"](https://www.kai-waehner.de/blog/2025/06/05/the-rise-of-the-durable-execution-engine-temporal-restate-in-an-event-driven-architecture-apache-kafka/);
[Materialized View, "Durable Execution: Justifying the
Bubble"](https://materializedview.io/p/durable-execution-justifying-the-bubble);
[Golem.cloud, "The Emerging Landscape of Durable
Computing"](https://www.golem.cloud/post/the-emerging-landscape-of-durable-computing);
[The New Stack, Temporal Replay 2026](https://thenewstack.io/temporal-replay-2026-news/)):
Restate (Rust core, opt-in SDK, opened cloud product 2025); Inngest (TS, every step is a
durable transactional unit, retry re-runs from beginning skipping completed steps);
Hatchet (Go, 2024, performance-focused); Trigger.dev (TS, serverless-friendly);
Windmill (Rust + 20+ language runners, "13× faster than Airflow",
self-hostable); DBOS (Postgres-backed durable workflows, co-founded by Stonebraker —
"workflow architectures are preferred over event-driven; AI frameworks are converging
on workflow-style models supporting durable
execution" — [DBOS blog](https://www.dbos.dev/blog/why-postgres-durable-execution);
[SE Radio 681, Qian Li](https://se-radio.net/2025/08/se-radio-681-qian-li-on-dbos-durable-execution-serverless-computing-platform/)).
Cloud-native equivalents: AWS Step Functions (Amazon States Language, saga pattern,
weak on long-running multi-month flows and on subprocess scoping — [DZone, GCP
Workflows vs Step Functions vs
Temporal](https://dzone.com/articles/choosing-between-gcp-workflows-aws-step-functions);
[Camunda forum, BPMN vs Step
Functions](https://forum.camunda.io/t/bpmn-vs-aws-step-function/5460)).

The **architectural commonality** across all of these: a journal of events that drives
replay. They are event-sourced workflow runtimes wearing different fashion.

### 1.3 Process mining (data-driven workflow discovery)

**Celonis** is the market leader. The model: ingest event logs (case-id, activity,
timestamp + attributes), discover process variants using Heuristics Miner / Inductive
Miner algorithms, visualize end-to-end paths, detect deviations from the happy path
([Celonis, "How does process mining
work?"](https://www.celonis.com/insights/topics/how-does-process-mining-work);
[d-nb.info, Celonis PQL paper](https://d-nb.info/1259733416/34)). Object-Centric
Process Mining (OCPM) — using **OCEL 2.0** — extends the model so one event can
relate to multiple objects, removing the case-id straitjacket and supporting
many-to-many relationships natively ([Celonis OCPM
overview](https://www.celonis.com/blog/what-is-object-centric-process-mining-ocpm);
[OCEL 2.0
specification](https://www.ocel-standard.org/2.0/ocel20_specification.pdf);
[arXiv 2412.00393](https://arxiv.org/html/2412.00393v1)).

**OSS / academic.** ProM (Java, GPLv3, 1500+ plug-ins, de-facto academic standard);
Apromore (community edition + commercial); Disco (Fluxicon, commercial freemium); PM4Py
(Python, leading OSS); bupaR (R) ([ProM tools](https://promtools.org/);
[Apromore comparison page](https://www.processmining-software.com/tools/apromore/);
[awesome-processmining repo](https://github.com/TheWoops/awesome-processmining)).

**The kontor angle.** kontor's substrate is already an OCEL 2.0 event log in disguise.
Every `:status-history` row carries `:entity`, `:facet`, `:to`, `:changed-at`,
`:changed-by-uid`, `:reason`, `:origin-transaction` — the case-id + activity + timestamp
+ resource + attributes that OCEL needs. Datahike's tx-time gives the assertion-time
column. The `:transaction` ↔ `:posting` ↔ `:invoice` ↔ `:order` graph supplies multi-object
relations. Exporting OCEL is a datalog query, not a project.

### 1.4 Workflow-shaped UIs that hide BPM

**Linear** is the reference "good state-machine UX." Per-team workflows with default
categories (Backlog → Todo → In Progress → Done → Canceled), opinionated defaults,
custom statuses allowed but discouraged, categories cannot be reordered ([Linear docs,
"Issue status"](https://linear.app/docs/configuring-workflows); [Productivity Stack,
Linear guide 2026](https://productivitystack.io/guides/linear-app-complete-guide/)).
Linear's design ethos — "work with the grain, the defaults exist for a reason" —
matches kontor's ADR-034 pattern of seeded transitions plus per-org override.

**Notion / Coda / Airtable** databases-with-views are DIY workflow: a "Status" column,
some automations, sometimes a Kanban view. Useful for unstructured ops; awkward for
finance where the invariants matter. **Zapier / Make / n8n** are integration-flavored
("when X happens in app A, do Y in app B"); n8n is the self-hostable, code-friendly
option of the three ([n8n vs Zapier](https://n8n.io/vs/zapier/);
[Digidop comparison](https://www.digidop.com/blog/n8n-vs-make-vs-zapier)). **Retool /
Internal / Tooljet** are admin-tool-shaped workflow — the UI is the workflow.

For kontor's consumers, the UX question is downstream of the kernel. The kernel just
needs to expose status, history, and policy queries cleanly; the UI shape is the
consumer's call. ADR-010 ("no UI") still holds.

### 1.5 State machines as a first-class primitive

**XState** is the dominant JS implementation of Harel statecharts (1987). Statecharts
add three things over flat FSMs: hierarchical (nested) states, orthogonal (parallel)
states, and extended state — data that's not part of the finite state ([XState GitHub,
statelyai/xstate](https://github.com/statelyai/xstate); [statecharts.dev,
"Parallel state"](https://statecharts.dev/glossary/parallel-state.html);
[stately.ai docs, parallel states](https://stately.ai/docs/parallel-states)).
The main lesson: statecharts are valuable as executable behavior, not just documentation.

**Akka FSM** (Scala, deprecated in favor of typed Akka Persistence); **Erlang/Elixir
`gen_statem`** (since OTP 20, 2017, replacing the older `gen_fsm`; Mealy-machine shaped;
Turing-complete; supports state time-outs, event time-outs, callback module swap —
[Erlang docs](https://www.erlang.org/doc/apps/stdlib/gen_statem.html);
[Erlang Solutions, "gen_statem
unveiled"](https://www.erlang-solutions.com/blog/gen-statem-unveiled/)).
The OTP tradition: state machines are *processes* with mailboxes; the OS schedules them.

**Clojure state-machine libraries.** `metosin/tilakone` — minimalist FSM in pure data,
145 LOC, "deprecated" status per Metosin themselves
([repo](https://github.com/metosin/tilakone)); `cdorrat/reduce-fsm`; `ztellman/automat`
(combinator-based, regex-like). For statecharts: `lucywang000/clj-statecharts`
(inspired by XState, re-frame integration, Eclipse-licensed) and
`fulcrologic/statecharts` (W3C SCXML 2015-09-01, MIT-licensed, supports invoke
semantics — [Fulcrologic GitHub](https://github.com/fulcrologic/statecharts);
[lucywang000 GitHub](https://github.com/lucywang000/clj-statecharts)). Both are
production-shaped Clojure libraries.

**Sylius** (PHP, MIT) ships the canonical order state machine via Winzou
StateMachineBundle, with order/checkout/payment/shipment as separate state machines
on the same Order entity — the "multiple facets on one entity" pattern that ADR-034
generalizes ([Sylius docs](https://docs.sylius.com/the-book/architecture/state-machine);
[Sylius blog on
state-machine-in-ecommerce](https://sylius.com/blog/what-is-state-machine-and-why-is-it-useful-in-modeling-ecommerce-processes/)).

### 1.6 Comparison table

| Tool | Authoring | Durable | State model | Time travel | Audit granularity | FP-friendly | License |
|---|---|---|---|---|---|---|---|
| Camunda 8 / Zeebe | BPMN visual | Yes (event-sourced) | BPMN 2.0 (rich) | Replay via log | Per-event | No (Java OO) | Commercial (prod) |
| Activiti / Flowable | BPMN visual | Yes (DB-backed) | BPMN 2.0 | Limited | Per-task | No | Apache-2.0 |
| Pega | Low-code visual | Yes | Case + BPMN | Vendor | Per-step | No | Commercial |
| Temporal | Code (Java/Go/TS) | Yes (journal/replay) | Code | Replay via journal | Per-step | Partial | MIT (Cloud commercial) |
| Restate | Code (TS/Java) | Yes | Code | Replay | Per-step | Partial | BSL |
| Inngest | Code (TS) | Yes | Code (`step.run`) | Replay | Per-step | Partial | Source-available |
| DBOS | Code + Postgres | Yes | Code | SQL on log | Per-step | Partial | MIT |
| Hatchet / Trigger.dev | Code | Yes | Code | Replay | Per-step | Partial | MIT/source-available |
| Step Functions | JSON (ASL) + visual | Yes | DAG | Limited | Per-state | Partial | AWS |
| XState | Code (JS/TS) | No (lib only) | Statecharts (rich) | No | Library scope | Partial | MIT |
| clj-statecharts / fulcro statecharts | Code (Clojure) | No | Statecharts | No | Library scope | **Yes** | EPL / MIT |
| Tilakone | Code (Clojure data) | No | Flat FSM | No | Library scope | **Yes** | EPL (deprecated) |
| Linear | UX, no API auth | n/a | Per-team category-bucketed FSM | n/a | Per-issue | n/a | Commercial SaaS |
| Celonis | n/a — discovery | n/a | OCEL 2.0 | n/a | Per-event | n/a | Commercial |
| n8n / Make / Zapier | Visual + light code | Partial | DAG | No | Per-execution | No | Various |
| **kontor today** | **Datalog data (ADR-034)** | **Yes (datahike tx-log + intent rows)** | **Flat FSM per facet** | **Yes (bitemporal)** | **Per-history-row + per-tx** | **Yes (Clojure)** | **EPL-1.0** |

kontor's row already reads well against this matrix. The gaps a workflow primitive
would fill: hierarchical / parallel state expressivity (statechart-class), correlation
across multiple entities (one workflow = many state machines), externally-visible
process maps (process-mining export). None of those is structurally hard from where
the kernel sits.

## 2 — The thesis: should kontor grow a workflow primitive?

Three options were on the table. I evaluated each:

### Option A — Stay pure

Workflows live entirely in consumer apps. kontor offers `:status-history` (ADR-034) and
`:side-effect-intent` (ADR-041) as composable primitives; the kernel takes no opinion on
"a workflow."

**Pros.** Minimal scope. Aligns with ADR-010 ("no workflow engine"). Consumers pick
their own engine — Temporal Java SDK if they want durable execution, fulcrologic
statecharts if they want statecharts in-process, XState in the frontend, whatever. Zero
risk of building the wrong abstraction.

**Cons.** A real-world business process — vendor onboarding produces `:partner` +
`:bank-account` + `:tax-cert-doc` + a `:audit-doc` upload + an `:approval-policy`
check + a final status change on the partner — already crosses 5+ entities. Consumers
will reinvent a correlation key (a UUID stuck in every entity's `:external-ref` or a
free-form `:tags` field) to join those events into one process. Each consumer's
correlation will differ, breaking the audit narrative across deployments.

### Option B — Light primitive (RECOMMENDED)

Add one small kernel entity:

```clojure
:workflow-instance/id          string :db.unique/identity
:workflow-instance/kind        keyword     ; :vendor-onboarding | :hire | :rfp-to-payment | ...
:workflow-instance/started-at  instant
:workflow-instance/closed-at   instant     ; nil = open
:workflow-instance/outcome     keyword     ; :completed | :cancelled | :abandoned
:workflow-instance/owner-uid   ref         ; the partner / case / order this is for
:workflow-instance/notes       string
```

Plus one **back-reference attribute** on `:status-history`:

```clojure
:status-history/workflow       ref → :workflow-instance      ; optional
```

And one **back-reference on `:side-effect-intent`** for the same.

That's it. The kernel ships no engine, no scheduler, no DAG, no DMN. Just a
correlation handle that joins existing status changes + side-effect intents into a
named process run, with a sentinel close.

**Helpers** (~50 LOC in `kontor.workflow`):

- `open-workflow!` — transact a new `:workflow-instance` row.
- `close-workflow!` — set `:closed-at` and `:outcome`.
- `history-of-workflow` — datalog query: every `:status-history` row tagged with this
  workflow, ordered by `:changed-at`. Trivial.
- `open-workflows-for-entity` — partner X has 2 onboarding workflows in flight.
- `gc-abandoned-workflows!` — sweeper for workflows with no activity in N days.

**Pros.** Solves the correlation problem with one entity. Bitemporal-native. Works with
any execution engine the consumer chooses (Temporal calls `record-status-change!`,
passes the workflow-id; XState frontend does the same). Process-mining-friendly: a
workflow IS a case in OCEL terms. Doesn't lock kontor into a process model. Reversible:
if it gets bloated, deprecate the helpers and the schema stays as documentation.

**Cons.** Adds 1 entity + 7 attrs + 1 namespace to the kernel. Borderline scope creep.
Risk that "workflow" attracts kitchen-sink contributions ("can we add an `:assignee`?
a `:due-date`? a `:priority`? a `:checklist`?" — no, that's the consumer's job).

### Option C — Companion module `kontor-workflow`

Sibling artifact, similar to `kontor-collections` or `kontor-procurement`, that wraps
either fulcrologic/statecharts or a Temporal SDK with kontor-shaped primitives. Provides
durable-execution semantics on top of the kernel.

**Pros.** No kernel pollution. Optional dep. Can be opinionated (e.g. "we use
fulcrologic/statecharts under the hood, you write SCXML, we persist instances as
`:workflow-instance` rows joined to `:status-history`").

**Cons.** **Not urgent.** No consumer is asking for this yet. Building a process engine
"in case someone wants it" is the over-abstraction trap (§ 6). Better to ship the
Option B correlation handle now, watch what beleg / simmis / future consumers actually
build on top of `:status-history` + `:side-effect-intent`, and grow the companion in
response to real demand.

### Recommendation

**Option B.** Land a one-entity `:workflow-instance` correlation primitive in the kernel
as a small ADR (call it ADR-049 by current numbering). Defer Option C until a consumer
has built ~3 multi-entity processes and reports specific friction. The risk of Option B
is much smaller than the risk of Option A's correlation-key drift across consumers.

Rationale, point-by-point:

1. **The kernel's job is audit + state, not orchestration.** ADR-010's "no workflow
   engine" was the right call and stays. A correlation entity isn't an engine; it's a
   labeled join.
2. **OCEL 2.0 wants cases.** Process mining over `:status-history` is mostly trivial
   *already*; what's awkward is identifying which events belong to the same logical
   process when one process spans 5 entities. `:workflow-instance` solves that with one
   `:db.unique/identity` field.
3. **Cross-org workflows need a correlation anchor too.** When `kontor-edi` (future)
   exchanges a UBL Invoice with a buyer, the `:invoice/external-ref` is the cross-org
   correlation. Inside our side, `:workflow-instance` joins our `:order` + `:invoice` +
   `:payment-application` to one named run.
4. **The shape is forced by what `:status-history` already wants to say.** Today, a
   `:status-history` row knows the entity, the facet, the from/to, the actor, the
   reason. It does *not* know "this is part of a 7-step onboarding for Acme Corp." A
   ref slot fixes that without changing anything else.

## 3 — Process mining over kontor's bitemporal log

**Claim: this is a 4–6 week project, not 6 months.** kontor's substrate is structurally
an OCEL 2.0 event log already. The work is mechanical.

### 3.1 Mapping kontor to OCEL 2.0

OCEL 2.0 ([spec](https://www.ocel-standard.org/2.0/ocel20_specification.pdf)) wants:

- **Events.** id, type, timestamp, attributes, related-objects.
- **Objects.** id, type, attributes, related-objects (O2O).
- **Event-to-Object (E2O) relations.** Optional qualifier.

kontor's mapping:

| OCEL | kontor source |
|---|---|
| Event id | `:status-history`'s entity-id (or its tx-id) |
| Event type | `:status-history/facet` + `:to` (e.g. `:invoice/status :paid`) |
| Event timestamp | `:status-history/changed-at` (valid-time); also datahike tx-time as recording-time |
| Event attributes | `:status-history/reason`, `:reason-note`, `:supporting-doc`, `:origin-transaction` |
| Event actor / resource | `:status-history/changed-by-uid` |
| Object id | `:status-history/entity` |
| Object type | `:status-history/entity-type` |
| Object attributes | `d/pull` the entity at `:as-of-tx` |
| E2O relations | Existing refs: `:invoice/order`, `:invoice/transaction`, `:order-item/invoice-line` |
| Case correlation | New `:workflow-instance/id` (Option B) OR derived (e.g. all events sharing `:order` ref) |
| Recording vs valid time | Datahike tx-time + `:status-history/changed-at` directly |

The OCEL standard explicitly wants both recording time and valid time; kontor's
bitemporality (ADR-008) provides both natively. No competitor OSS accounting system has
this out of the box.

### 3.2 Proposed API sketch (NOT a commitment)

```clojure
(ns kontor.process
  "Process-mining export + minimal discovery over the kontor bitemporal log.")

(defn export-xes
  "Export :status-history events scoped by predicate as an XES 2.0 log.
   Compatible with ProM / Disco / Celonis import."
  [db {:keys [from to entity-types facets workflow-kinds]
       :or {from #inst \"1970\"
            to (java.util.Date.)}}]
  ...)

(defn export-ocel
  "Export an OCEL 2.0 event log. Includes E2O via existing kernel refs
   (:invoice/order, :order-item/invoice-line, :payment-application/invoice
   etc.). Suitable for object-centric process mining."
  [db opts]
  ...)

(defn discover-variants
  "Group events by case (workflow-instance OR a user-provided case-fn)
   and return {variant-trace count} for the top-N variants.
   Pure Clojure, no external deps."
  [db {:keys [case-fn top-n] :or {top-n 50}}]
  ...)

(defn conformance-check
  "Given a reference variant (sequence of (entity-type facet to) tuples)
   and a case-fn, return cases that deviate from the reference with the
   first deviation point."
  [db reference case-fn]
  ...)

(defn bottleneck-times
  "For each (from, to) transition pair, mean + p50 + p95 + p99 of
   inter-event duration. Bitemporal: by valid-time or by tx-time."
  [db {:keys [time-axis] :or {time-axis :valid}}]
  ...)
```

**Effort estimate.**

- Week 1: XES export + OCEL export + round-trip test against PM4Py.
- Week 2: `discover-variants` (basic Heuristics Miner: count direct-successors and
  threshold; this is ~200 LOC of pure Clojure).
- Week 3: `conformance-check` + `bottleneck-times`.
- Week 4: integration test on a real showcase (e.g. showcase 04 multi-entity intercompany).
- Weeks 5–6: docs + research note ("kontor as a free process-mining substrate").

This is genuinely a kontor differentiator. No OSS accounting tool ships native process
mining. Every competitor reaches for Celonis / Apromore / PM4Py and exports CSVs from
their ERP. kontor would *be* the event log, queryably and bitemporally.

## 4 — Statecharts vs flat FSM

ADR-034 ships flat FSMs. The question: is Harel's hierarchical / parallel state model
worth the complexity for kontor?

### 4.1 Where statecharts win

The textbook cases ([statecharts.dev resources](https://statecharts.dev/resources.html)):

- **Parallel regions.** A spacecraft can be `Pressurized` AND `Powered` AND `On-Comms`
  AND `Navigating` simultaneously; each is an independent FSM. Modeling as one flat
  FSM gives 16 product states; modeling as four parallel regions gives 8 states.
- **Hierarchical states.** A `Player` is `Active` (sub-states: `Playing`, `Paused`,
  `Buffering`) vs `Inactive`. When `Active`, the same "stop" event always returns to
  `Inactive`, regardless of substate — hierarchy lets you write the transition once.
- **Time-out at a parent level.** "Wherever in `Active`, after 30s of no input, go to
  `Idle`" is one line in a statechart, N lines in a flat FSM.

### 4.2 Where the kontor cases sit

Real cases that smell like they need statecharts:

| Case | Flat-FSM approach in kontor today | Verdict |
|---|---|---|
| Dispute open while dunning runs | Two independent facets on `:invoice`: `:invoice/collections-status` and `:invoice/dispute-state`. Dunning policy queries both. | Flat FSM wins — facets are the parallel regions. |
| Multi-region tax clearance (e.g. BR NF-e + state SEFAZ + federal CTE) | `:transaction/state` flat machine + `:invoice/clearance-status` per jurisdiction. | Flat FSM wins. |
| n-of-m approval (e.g. 2-of-3 directors must approve before posting) | `:approval-request` entity with N `:approval` child rows; predicate counts approvals. Not really a "state" problem — it's a counting problem. | Counting, not statechart. |
| Sub-states of "active" (draft → ready → sent → paid all "open"; cancelled/refunded "closed") | Two facets: `:invoice/status` (the 5-value FSM) + `:invoice/lifecycle` (`:open | :closed`) computed from status. Or just `:open?` as a derived predicate. | Predicate or denorm; flat FSM wins. |
| Dunning sequence with timers ("after 14 days in 'level-1' auto-advance to 'level-2'") | `:status-transition/auto-after-millis` + sweeper (ADR-041). | Already done; flat FSM wins. |

### 4.3 Conclusion

The cases that look like they need statecharts factor cleanly into multiple facets on
one entity — which kontor already supports (ADR-034: "one entity can have multiple facets
— multiple independent state machines on the same row"). The cost of going to full
statecharts is: a SCXML or XState-like data model, an interpreter, transition guards
expressed in a sub-language, history pseudo-states, internal vs external transitions,
final states. ~10× the conceptual surface. The win — for kontor's domain — is small
because finance state machines tend to be wide and shallow (many states, few hierarchies)
rather than deep (few states, many sub-states).

**Recommendation: keep flat FSM in the kernel.** A consumer that wants statecharts can
pull in `fulcrologic/statecharts` and use it for in-process orchestration, calling
`kontor.status-machine/record-status-change!` at the boundary. The kernel does not need
to ship the statechart runtime.

## 5 — Cross-organization workflows

The buyer/seller/auditor/regulator dance: RFP → quote → PO → fulfillment → invoice →
payment → reconciliation. Kontor today is single-organization (with multi-entity
support, ADR-031, but all entities within one tenant).

### 5.1 What changes for cross-org

A cross-org workflow is **N coupled state machines linked by exchange documents**,
not one workflow. Each org has its own status machine on its own copy of the entity:

- Buyer: `:rfp/status :issued`, `:quote/status :received`, `:purchase-order/status :sent`, `:receipt/status :goods-received`, `:invoice/status :received-from-vendor`, `:payment-application/status :paid`.
- Seller: `:rfp/status :received`, `:quote/status :sent`, `:sales-order/status :received`, `:shipment/status :sent`, `:invoice/status :sent`, `:payment-application/status :received`.

The exchange documents (UBL, Peppol BIS, EDIFACT, cXML) carry the message-id + in-reply-to
that lets the two sides correlate ([Coupa, "Procurement
Orchestration"](https://www.coupa.com/blog/procurement-orchestration-from-intake-to-pay/);
[Ivalua, "Procurement orchestration
guide"](https://www.ivalua.com/blog/procurement-orchestration/);
[Precoro, "Complete Procurement Orchestration Guide"](https://precoro.com/blog/procurement-orchestration/)).

### 5.2 What primitives change

- **Document message-id + in-reply-to.** Currently `:invoice/external-ref` is a string.
  For cross-org, we want structured `:document/message-id` (UUID), `:document/in-reply-to`
  (ref or message-id), `:document/conversation-id` (groups all related messages — the
  "RFP-to-payment thread"). This lives in a `kontor-edi` companion, not the kernel.
- **Receipt attestation.** "I (the buyer) acknowledge receipt of your message at T."
  Maps to `:audit-doc` + a new `:document-receipt` row. Companion-shaped.
- **Counterparty-perspective view.** When a buyer receives a UBL Invoice, they need to
  see the seller's invoice ID + their own `:invoice/external-ref`. Same entity, two
  perspectives. Companion-shaped (handled by the EDI ingestor mapping seller-side
  fields to buyer-side fields).

### 5.3 Recommendation

Cross-org workflows go in `kontor-edi-*` companions (one per protocol family: UBL/Peppol,
EDIFACT, cXML, NF-e/CFDI/IRN clearance is special). The kernel ships the document
correlation primitive (`:workflow-instance` from Option B above) so that the buyer-side
"my purchase from Acme" and the seller-side "Acme's sale to me" each have their own
local workflow handle, even though the exchange documents bridge them.

Don't try to model the cross-org workflow as ONE entity in the kernel; that's the
WS-Choreography mistake (cross-org choreography languages died for a reason — no central
runtime, no agreement on semantics, every party reimplements). Model it as N local
workflows + document exchange.

## 6 — What to NOT build

The over-abstraction trap. Below are the seductive ideas that should be **declined**.

1. **BPMN engine.** Even the OSS ones (Camunda 7-era, Activiti, Flowable, jBPM) are
   hundreds of thousands of lines of Java with complex DB schemas, persistence layers,
   timer services, transaction-boundary coordination, BPMN parsers. Camunda 8's
   relicensing of Zeebe demonstrates the long-term economics: even the vendors can't
   sustainably keep this OSS at scale. Out of scope.

2. **Statechart runtime in the kernel.** Pulling SCXML + the W3C state-machine
   semantics into the kernel is a ~5kLOC commitment. fulcrologic/statecharts does this
   well; consumers can adopt it without our help. Out of scope.

3. **DMN runtime in the kernel.** DMN's FEEL expression language is a small language
   in its own right. `:approval-policy` (ADR-038) is the kernel's answer; consumers
   needing richer rule tables can pull in a Drools-equivalent OSS lib or build one on
   top of `:approval-policy`'s rule keyword. Out of scope.

4. **Durable-execution loop.** Don't build a Temporal-shaped journal/replay engine.
   datahike's tx-log + `:side-effect-intent` already cover the cases kontor's domain
   needs (deterministic accounting, idempotent dispatch); a generic durable-execution
   loop is a much bigger commitment that no consumer has asked for. Out of scope.

5. **Process-mining ML / clustering.** Variant discovery + conformance + bottleneck
   timing is enough. ML-based clustering, predictive-monitoring, root-cause analysis
   are research-grade features that PM4Py and Celonis spend dozens of FTEs on. Out of
   scope. Export OCEL; let consumers run their preferred miner.

6. **Visual workflow designer.** Don't ship a UI. ADR-010. Consumers who want
   visual editing pull in `bpmn-js` or stately.ai's visualizer; the kernel's job is
   the data model.

7. **Cross-org choreography in one entity.** Discussed in § 5; one workflow per
   organization, coupled by exchange documents in companion modules.

The principle from § 1.1 (the BPMN-pitfalls research) applies to us too: "the diagram
is executable" is a category error. If a future kontor consumer wants visual modeling,
they generate the visual from the data (the seeded `:status-transition` rows + the
observed `:status-history`) — they don't author in the visual and compile down. Same
direction Linear takes — the UI is a view of the data, not the editor for the data.

## 7 — Open questions and concrete next-step proposals

### Open questions

1. **Does `:workflow-instance` need a `:state` facet of its own?** I.e., is a
   workflow-instance itself a status machine with `:active`, `:paused`, `:closed`
   transitions, OR is it a passive label that gets a `:closed-at` set when the consumer
   says it's done? Recommend the latter (passive label) for v1; promote to a status
   machine if a consumer needs pause/resume.

2. **Should `:workflow-instance` carry an `:owner-entity` or just a `:tags` set?** A
   vendor-onboarding workflow has one obvious owner (the `:partner` being onboarded);
   a cross-org RFP-to-payment has the `:purchase-order` (or the buyer-side `:partner`).
   Concrete: `:workflow-instance/owner` as a ref, plus a `:workflow-instance/tags` for
   secondary owners. Need real cases to decide.

3. **How does `:workflow-instance` interact with `:audit-doc` (ADR-038)?** Probably:
   `:audit-doc` rows attach to `:status-history` rows (already); a workflow-instance is
   just the join. No new relation needed.

4. **OCEL 2.0 export — case-id strategy when `:workflow-instance` is absent.** Default
   should be "case-id = entity-id" (one case per entity), with `--workflow` flag to
   group by workflow-instance, and `--object-centric` to emit OCEL-2 with E2O relations
   from existing refs. Document the trade-off: per-entity gives lots of small cases;
   per-workflow gives few large cases; OCPM gives the most accurate but largest log.

5. **Does process mining run in the kernel or in a sibling `kontor-process` module?**
   Recommend kernel: XES + OCEL export is ~300 LOC pure-data manipulation and no
   external deps (output is JSON / EDN / XML strings). Heuristics-Miner discovery is
   another ~300 LOC. Conformance checking is ~200 LOC. Total ~800 LOC — well under
   the kernel's per-namespace budget.

### Concrete next-step proposals

**Step 1 (1–2 sessions): ADR-049 — Workflow-instance correlation primitive.**

- Add the `:workflow-instance` entity (7 attrs).
- Add `:status-history/workflow` and `:side-effect-intent/workflow` ref attrs.
- Add `kontor.workflow` namespace with `open-workflow!`, `close-workflow!`,
  `history-of-workflow`, `open-workflows-for-entity`, `gc-abandoned-workflows!`.
- Tests: a vendor-onboarding workflow that touches `:partner`, `:bank-account`,
  `:audit-doc`, `:approval-request`, then closes; `history-of-workflow` returns the
  full multi-entity narrative in order.

**Step 2 (3–4 sessions): ADR-050 — `kontor.process` process-mining substrate.**

- `export-xes` for ProM / Disco / Celonis compatibility.
- `export-ocel` for OCPM (Celonis + PM4Py + arXiv 2412.00393 line of research).
- `discover-variants` — pure-Clojure Heuristics-Miner top-N variant discovery.
- `conformance-check` — reference variant + deviation report.
- `bottleneck-times` — per-transition p50/p95/p99 latency by valid-time or tx-time.
- Showcase: run process mining over showcase 02 (US multi-state collections) and
  showcase 04 (multi-entity intercompany), document the variants discovered.

**Step 3 (deferred, only on consumer demand): `kontor-workflow` companion.**

If beleg or simmis ever asks for "I want to define an HR onboarding workflow with a
state machine that has timers + retries + branches, and have it persist across
restarts" — *then* build the companion. Wrap `fulcrologic/statecharts` for the
in-process case, or write a thin Temporal-Java-SDK adapter for the long-running case.
Don't speculate.

**Step 4 (parallel, not blocking): document the existing pattern.**

The current ADR-034 + ADR-038 + ADR-041 combo already supports more workflow shape than
the docs make clear. Add a `doc/patterns/workflow.md` page showing how the existing
primitives compose for: vendor onboarding (5 entities, 3 approvals, 1 final state
change); n-of-m approval (`:approval-policy` + `:approval-request` + counting); time-
based escalation (`:auto-after-millis`); side-effect retry (intent rows + worker).

## References

### Camunda / BPMN / DMN / Flowable / Activiti lineage

- [Camunda 8 — Introduction to Zeebe](https://docs.camunda.io/docs/components/zeebe/zeebe-overview/)
- [Camunda 8 — Architecture](https://docs.camunda.io/docs/components/zeebe/technical-concepts/architecture/)
- [Camunda 8 — BPMN, DMN, and FEEL](https://docs.camunda.io/docs/components/concepts/bpmn-dmn-feel/)
- [Camunda blog — Scaling Workflow Engines at Intuit (2024)](https://camunda.com/blog/2024/08/scaling-workflow-engines-intuit-camunda-8-zeebe/)
- [Camunda blog — Licensing update for Camunda 8 self-managed (April 2024)](https://camunda.com/blog/2024/04/licensing-update-camunda-8-self-managed/)
- [Camunda blog — How open is Camunda Platform 8?](https://camunda.com/blog/2022/05/how-open-is-camunda-platform-8/)
- [Camunda — Wikipedia](https://en.wikipedia.org/wiki/Camunda)
- [Capital One Tech — 2022 Open Source BPM Comparison](https://medium.com/capital-one-tech/2022-open-source-bpm-comparison-33b7b53e9c98)
- [ECM Architect — Activiti founders fork to create Flowable (2016)](https://ecmarchitect.com/archives/2016/10/15/4192)
- [Activiti — Wikipedia](https://en.wikipedia.org/wiki/Activiti_(software))
- [Flowable — Wikipedia](https://en.wikipedia.org/wiki/Flowable)
- [Visual Paradigm — BPMN Diagram Best Practices](https://skills.visual-paradigm.com/docs/bpmn-diagram-types-explained/bpmn-diagram-best-practices/)
- [Visual Paradigm — Common Pitfalls in BPMN Modeling](https://archimate.visual-paradigm.com/2025/01/27/write-a-comprehensive-guide-for-chapter-4-of-the-bpmn-toc-above/)
- [FlyingDog — 5 common pitfalls in enterprise BPM implementation](https://www.flyingdog.de/portal/en/blog/bpm-implementation-mistakes-avoid-enterprise/)
- [Trisotech — BPMN modeling best practices](https://www.trisotech.com/bpmn-modeling-best-practices/)
- [JointJS — Why building a BPMN modeler UI is harder than it looks](https://www.jointjs.com/blog/why-building-a-bpmn-modeler-ui-is-harder-than-it-looks)
- [Pega — Case Management x BPM, what's the true difference?](https://support.pega.com/discussion/case-management-x-bpm-whats-true-difference)
- [Charter Global — Pega vs traditional BPM tools](https://www.charterglobal.com/pega-vs-traditional-bpm-tools-digital-transformation/)

### Temporal / Restate / Inngest / DBOS / Hatchet / Trigger.dev / Windmill / Step Functions

- [Temporal — Workflow Execution overview](https://docs.temporal.io/workflow-execution)
- [Temporal — homepage](https://temporal.io/)
- [The New Stack — Temporal Replay 2026](https://thenewstack.io/temporal-replay-2026-news/)
- [SE Radio 596 — Maxim Fateev on durable execution (2023)](https://se-radio.net/2023/12/se-radio-596-maxim-fateev-on-durable-execution-with-temporalse-radio-596/)
- [WorkOS interview — Maxim Fateev on durable execution for AI agents](https://workos.com/blog/maxim-fateev-temporal-durable-execution-ai-agents)
- [Maxim Fateev — "The Fallacy of the Graph" (Medium)](https://medium.com/@mfateev/the-fallacy-of-the-graph-why-your-next-agentic-workflow-should-be-code-not-a-diagram-85b8f70b197a)
- [Temporal blog — "The Fallacy of the Graph"](https://temporal.io/blog/the-fallacy-of-the-graph-why-your-next-workflow-should-be-code-not-a-diagram)
- [HN discussion — "The Fallacy of the Graph"](https://news.ycombinator.com/item?id=44962240)
- [Long Quanzheng — "Workflow Should be Code, but Durable Execution is NOT the ONLY way"](https://medium.com/@qlong/workflow-should-be-code-but-durable-execution-is-not-the-only-way-519f7682360c)
- [Kai Waehner — The rise of the durable execution engine](https://www.kai-waehner.de/blog/2025/06/05/the-rise-of-the-durable-execution-engine-temporal-restate-in-an-event-driven-architecture-apache-kafka/)
- [Materialized View — Durable execution: justifying the bubble](https://materializedview.io/p/durable-execution-justifying-the-bubble)
- [Java Code Geeks — Durable execution: what Temporal and Conductor are solving](https://www.javacodegeeks.com/2026/05/durable-execution-what-temporal-and-conductor-are-solving-that-queues-cant.html)
- [Golem.cloud — The emerging landscape of durable computing](https://www.golem.cloud/post/the-emerging-landscape-of-durable-computing)
- [Restate blog — Building a modern durable-execution engine from first principles](https://www.restate.dev/blog/building-a-modern-durable-execution-engine-from-first-principles)
- [Why Now — Restate & durable execution](https://whynowtech.substack.com/p/restate-and-durable-execution)
- [Inngest — How functions are executed: durable execution](https://www.inngest.com/docs/learn/how-functions-are-executed)
- [Inngest vs Temporal](https://www.inngest.com/compare-to-temporal)
- [DBOS — homepage](https://www.dbos.dev/)
- [DBOS blog — Why Postgres is a good choice for durable workflow execution](https://www.dbos.dev/blog/why-postgres-durable-execution)
- [Supabase blog — Running durable workflows in Postgres using DBOS](https://supabase.com/blog/durable-workflows-in-postgres-dbos)
- [SE Radio 681 — Qian Li on DBOS durable execution](https://se-radio.net/2025/08/se-radio-681-qian-li-on-dbos-durable-execution-serverless-computing-platform/)
- [tiarebalbi blog — DBOS vs Temporal: choosing durable execution in 2026](https://www.tiarebalbi.com/en/blog/dbos-vs-temporal-postgres-durable-execution)
- [Hatchet — GitHub](https://github.com/hatchet-dev/hatchet)
- [Trigger.dev vs Windmill comparison (openalternative.co)](https://openalternative.co/compare/trigger/vs/windmill)
- [Windmill — Hatchet benchmark](https://www.windmill.dev/docs/misc/benchmarks/competitors/hatchet)
- [Windmill — Alternatives comparison](https://www.windmill.dev/docs/compared_to/peers)
- [Matthieu Mordrel — Ultimate guide to TypeScript orchestration (Temporal vs Trigger.dev vs Inngest)](https://medium.com/@matthieumordrel/the-ultimate-guide-to-typescript-orchestration-temporal-vs-trigger-dev-vs-inngest-and-beyond-29e1147c8f2d)
- [AWS — Implement the serverless saga pattern with Step Functions](https://docs.aws.amazon.com/prescriptive-guidance/latest/patterns/implement-the-serverless-saga-pattern-by-using-aws-step-functions.html)
- [DZone — GCP Workflows vs AWS Step Functions vs Temporal](https://dzone.com/articles/choosing-between-gcp-workflows-aws-step-functions)
- [Camunda forum — BPMN vs AWS Step Functions](https://forum.camunda.io/t/bpmn-vs-aws-step-function/5460)

### Process mining

- [Celonis — How does process mining work?](https://www.celonis.com/insights/topics/how-does-process-mining-work)
- [Celonis — What is process mining?](https://www.celonis.com/insights/topics/what-is-process-mining)
- [Celonis — What is object-centric process mining?](https://www.celonis.com/blog/what-is-object-centric-process-mining-ocpm)
- [Celonis docs — Getting started with object-centric process mining](https://docs.celonis.com/en/object-centric-process-mining-overview.html)
- [Celonis PQL: a query language for process mining (paper)](https://d-nb.info/1259733416/34)
- [ProM tools](https://promtools.org/)
- [Apromore — process mining software comparison](https://www.processmining-software.com/tools/apromore/)
- [Awesome process mining (GitHub)](https://github.com/TheWoops/awesome-processmining)
- [OCEL 2.0 specification](https://www.ocel-standard.org/2.0/ocel20_specification.pdf)
- [OCEL standard](https://www.ocel-standard.org/)
- [Wil van der Aalst — OCEL 2.0: Enabling Object-Centric Process Mining](https://www.linkedin.com/pulse/ocel-20-enabling-object-centric-process-mining-wil-van-der-aalst-yafie)
- [arXiv 2412.00393 — Advancing Object-Centric Process Mining](https://arxiv.org/html/2412.00393v1)
- [MDPI Mathematics — Object-Centric Process Mining: Unraveling the Fabric of Real Processes](https://www.mdpi.com/2227-7390/11/12/2691)
- [Microsoft Learn — Create an object-centric event log (Power Automate)](https://learn.microsoft.com/en-us/power-automate/object-centric-create-event-log)

### Statecharts / XState / Erlang / Sylius / Clojure FSM libraries

- [statelyai/xstate (GitHub)](https://github.com/statelyai/xstate)
- [statecharts.dev — Parallel state](https://statecharts.dev/glossary/parallel-state.html)
- [statecharts.dev — Resources](https://statecharts.dev/resources.html)
- [stately.ai — Parallel states docs](https://stately.ai/docs/parallel-states)
- [HN — Statecharts: hierarchical state machines](https://news.ycombinator.com/item?id=47908833)
- [Erlang docs — gen_statem behaviour](https://www.erlang.org/doc/apps/stdlib/gen_statem.html)
- [Erlang Solutions — gen_statem unveiled](https://www.erlang-solutions.com/blog/gen-statem-unveiled/)
- [Learn You Some Erlang — Rage against the finite-state machines](https://learnyousomeerlang.com/finite-state-machines)
- [metosin/tilakone (GitHub)](https://github.com/metosin/tilakone)
- [cdorrat/reduce-fsm (GitHub)](https://github.com/cdorrat/reduce-fsm)
- [ztellman/automat (GitHub)](https://github.com/ztellman/automat)
- [lucywang000/clj-statecharts (GitHub)](https://github.com/lucywang000/clj-statecharts)
- [clj-statecharts docs — parallel states](https://lucywang000.github.io/clj-statecharts/docs/parallel-states/)
- [fulcrologic/statecharts (GitHub)](https://github.com/fulcrologic/statecharts)
- [fulcrologic/statecharts — Guide](https://github.com/fulcrologic/statecharts/blob/main/Guide.adoc)
- [Sylius docs — State Machine](https://docs.sylius.com/the-book/architecture/state-machine)
- [Sylius blog — What is a state machine and why is it useful in modeling e-commerce processes](https://sylius.com/blog/what-is-state-machine-and-why-is-it-useful-in-modeling-ecommerce-processes/)
- [Sylius — Customizing State Machines (1.12)](https://github.com/Sylius/Sylius/blob/1.12/docs/customization/state_machine.rst)

### Event sourcing, Datomic-shape audit logs, accounting analogy

- [Martin Fowler — Event Sourcing](https://martinfowler.com/eaaDev/EventSourcing.html)
- [Microsoft Learn — Event Sourcing pattern](https://learn.microsoft.com/en-us/azure/architecture/patterns/event-sourcing)
- [Arnaud Lemaire — Event Sourcing, Audit Logs, and Event Logs](https://medium.com/sundaytech/event-sourcing-audit-logs-and-event-logs-deb8f3c54663)
- [Arkency blog — Audit log with event sourcing](https://blog.arkency.com/audit-log-with-event-sourcing/)
- [Kurrent — Event sourcing vs audit log](https://www.kurrent.io/blog/event-sourcing-audit)
- [Event-Driven.io — Is the audit log a proper architecture driver for event sourcing?](https://event-driven.io/en/audit_log_event_sourcing/)
- [Eventuate — Why event sourcing?](https://eventuate.io/whyeventsourcing.html)
- [Val on Programming — Datomic: Event Sourcing without the hassle](https://vvvvalvalval.github.io/posts/2018-11-12-datomic-event-sourcing-without-the-hassle.html)
- [jherrlin — Entity event log in Datomic](https://jherrlin.github.io/posts/datomic-entity-event-log/)
- [ClojurePatterns — Datomic temporal modeling: event-based bitemporal control](https://clojurepatterns.com/21/9/2/)
- [Datomic — Log API reference](https://docs.datomic.com/reference/log.html)

### Linear / Notion / n8n / Zapier / Make / approval workflows in ERP

- [Linear docs — Issue status (workflows)](https://linear.app/docs/configuring-workflows)
- [Linear docs — Conceptual model](https://linear.app/docs/conceptual-model)
- [Linear changelog — Custom statuses for projects](https://linear.app/changelog/2024-03-19-custom-statuses-for-projects)
- [n8n vs Zapier](https://n8n.io/vs/zapier/)
- [Zapier blog — n8n vs Zapier](https://zapier.com/blog/n8n-vs-zapier/)
- [Digidop — n8n vs Make vs Zapier](https://www.digidop.com/blog/n8n-vs-make-vs-zapier)
- [SAP blog — Flexible workflow scenarios in S/4HANA](https://blog.sap-press.com/flexible-workflow-scenarios-in-sap-s4hana)
- [SAP Community — Flexible Workflow for Supplier Invoice Approval in S/4HANA](https://community.sap.com/t5/technology-blog-posts-by-members/flexible-workflow-for-supplier-invoice-approval-in-s-4hana/ba-p/14256845)
- [SAP Learning — Workflow processes in Financial Accounting](https://learning.sap.com/learning-journeys/implementing-record-to-report-in-sap-s-4hana/using-workflow-processes-in-financial-accounting)
- [Dokka — How to create a custom approval workflow in NetSuite](https://dokka.com/how-to-create-a-custom-approval-workflow-in-netsuite)
- [Houseblend — Custom NetSuite workflows for high-value approvals](https://www.houseblend.io/articles/netsuite-custom-approval-workflow)

### Cross-organization workflow / procurement orchestration

- [Coupa — Procurement orchestration: from intake to pay](https://www.coupa.com/blog/procurement-orchestration-from-intake-to-pay/)
- [Ivalua — Procurement orchestration: process, benefits & strategies for 2026](https://www.ivalua.com/blog/procurement-orchestration/)
- [Precoro — Complete procurement orchestration guide](https://precoro.com/blog/procurement-orchestration/)
- [Zip — What is procurement orchestration? (2026)](https://ziphq.com/blog/procurement-orchestration)
- [Tonkean — Top 6 procurement intake & orchestration platforms (2026)](https://www.tonkean.com/blog/top-6-procurement-intake-and-orchestration-platforms-for-enterprise-teams-2026-guide)
- [GBG — How to build the ideal KYB onboarding process](https://www.gbg.com/en/blog/kyb-onboarding/)
- [dotfile — End-to-end KYB process](http://www.dotfile.com/blog-articles/end-to-end-kyb-process)
- [iDenfy — The guide to KYB onboarding](https://idenfy.com/blog/the-guide-to-kyb-onboarding/)
- [Compliancely — Top 5 vendor onboarding software (2026)](https://compliancely.com/blog/vendor-onboarding-software-comparison/)
