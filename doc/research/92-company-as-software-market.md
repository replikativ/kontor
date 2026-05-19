---
date: 2026-05-18
agent: research
title: 92 — Company-as-software / company-as-data market scan vs kontor's substrate posture
status: research-note
audience: maintainer making the next strategic bet; reviewer evaluating kontor's positioning relative to Foundry, Snowflake Cortex, Pigment, Causal, Rillet, Letta, Glean, Sana, MCP, AnyLogic, and the failed bets adjacent
related:
  - doc/research/10-business-os-companion-projects.md
  - doc/research/20-ai-native-business-os.md
  - doc/research/53-kontor-v2-consolidation.md
  - doc/research/54-simmis-ui-integration.md
  - doc/research/80-mccomb-future-of-accounting-vs-kontor.md
  - doc/research/88-mccomb-substrate-seams-round-1.md
---

# 92 — Company-as-software / company-as-data market scan vs kontor's substrate posture

## §1 — TL;DR

The "company as software / company as data" vision in mid-2026 is
crowded but bifurcated. On one axis sits the **data-platform tier**
(Palantir Foundry, Snowflake AI Data Cloud + Cortex Agents, Databricks
Lakebase + Genie, Salesforce Einstein 1, Glean, Workday Sana) — multi-
billion-dollar incumbents that pitch a "decision graph / ontology /
control plane / digital twin of the organization" with proprietary
storage, governance and a closed agent runtime on top. On the other
axis sits the **modeling tier** (Causal-now-Lucanet, Pigment, Anaplan,
Mosaic-now-HiBob, Pry-now-Brex, Numeric, Rillet, Campfire, Puzzle) —
focused, AI-native re-implementations of FP&A, close, ERP and budgeting
software where AI is the structural layer rather than a bolt-on. A
third tier — **agent + memory infrastructure** (Letta, MCP / Anthropic,
LangChain + LangGraph, Zapier MCP, Bardeen) — is the connective tissue
that everyone now writes against.

Kontor sits in a position none of the above occupies: a **single-
dependency, bitemporal, EPL-1.0 accounting + payroll + HR + lease
substrate**, written in Clojure, with sealed audit and explicit valid-
time / transaction-time correction as the load-bearing feature. The
canonical comparison is Palantir Foundry Ontology — both are betting
that the organization's data backbone is the load-bearing artifact —
but Foundry is a $134B-valuation closed enterprise platform; kontor is
a library a single team can embed under an existing Clojure app. The
secondary comparison is Datomic-as-system-of-record + Stripe Sigma-as-
analyst-API — kontor inherits Datomic's "facts have time" instincts,
adds the regulator-mandated valid-time axis, and explicitly does not
offer hosted infrastructure.

The recommended posture is **substrate, not platform**: kontor's
pitch is not "the next Foundry" but "the auditable bitemporal core
your AI-augmented business workflows write through, so that every
agent action is a recorded fact with corrected valid-time, and an
auditor can see what the agent saw at decision time." That posture is
defended by ADR-007 (sealing), ADR-008/ADR-048 (bitemporal), ADR-019
+ ADR-090 (concept-iri seams, McComb-aligned per research note 80),
ADR-090/091/092 (the McComb seams round 1: concept-iri + explain +
event-bus). The competitors do not have all three: Rillet/Campfire
have neither bitemporal nor open substrate; Foundry has the platform
but not the FP/Clojure ergonomics; Datomic has the substrate but not
the accounting domain; Snowflake/Databricks have the data cloud but
no double-entry, sealed, regulator-shaped accounting model.

The strategic bets the scan suggests:

- **Ship `kontor-mcp` as a substrate companion**, not a closed
  platform — let Claude / Cursor / Codex / OpenAI Agents / Glean /
  Sana plug in via the open MCP standard the industry has
  consolidated around (Anthropic donated MCP to the Linux Foundation
  Dec 2025, 97M+ monthly SDK downloads in March 2026). The MCP server
  surfaces `*-tx-data` builders as named tools — fits naturally with
  ADR-068's builder convention. Research note 20 already sketches
  the design; the market scan reinforces the urgency.
- **Position explicitly against Foundry as "the auditable
  bitemporal core, not the platform"** — Foundry-on-prem deployments
  start at >$1M/yr; kontor is a library you embed. The pitch is the
  reverse of "buy the platform, hire the FDEs."
- **Ship the bitemporal-restatement showcase** simmis needs to land
  vs Causal / Atlas (Reflexivity). Causal-now-Lucanet lost the
  brand to a CFO platform acquisition; the room for a substrate-
  shaped sim engine that plugs into kontor is open if simmis pulls.
- **Do not become Rillet / Campfire**. They are AI-native accounting
  apps targeting hyper-growth SaaS; they're racing for the
  bookkeeping-replacement market. Kontor's posture is below that
  layer: the engine they would have built if they were not US-only,
  not closed, not LLM-front-ended. We should treat them as potential
  *consumers*, not competitors.
- **Do not chase the "agentic enterprise OS" pitch** that Salesforce,
  Sana, Pigment, Glean, Snowflake are all making. That is a platform
  positioning the kernel is structurally unsuited to. Kontor is the
  *substrate underneath* an agentic-OS app, not the agentic-OS app
  itself.

What kontor is NOT: a planning tool (cede to Pigment / Anaplan
upstream consumers), a CRM (cede to Salesforce / HubSpot upstream),
a workflow engine (cede to Zapier / n8n / UiPath upstream), a BI
tool (cede to Cube / Hex / Snowflake upstream), an LLM (cede to
Anthropic / OpenAI). Kontor is the **system-of-record substrate**
with double-entry invariants, bitemporal correction semantics, and
sealed audit. Every other tier composes on top of it.

## §2 — The "data platform" tier

### §2.1 — Palantir Foundry

Foundry is the canonical "company-as-software" pitch, with a $134B
valuation as of FY2026 Q4 and 104% YoY US revenue growth.[^palantir-q1]
Architecturally it has four load-bearing concepts:

- **Ontology** — "a digital twin of the organization — a semantic
  layer that sits on top of datasets and models" (Palantir docs).
  Object types, link types, action types, functions. The Ontology
  models *decisions* in the enterprise, not just data.[^palantir-onto]
- **Actions + Functions** — the kinetic layer. Action types capture
  data from operators or orchestrate decision-making; functions
  author business logic with arbitrary complexity. Both compose into
  a "decision graph" connecting fragmented processes.[^palantir-onto]
- **AIP** — connects generative AI into operational domains. AIP
  Logic = no-code function authoring; AIP Chatbot Studio (formerly
  AIP Agent Studio) = agent definition; AIP Evals = test cases +
  observability. Agents use "Ontology-Aware Generation" (OAG): they
  retrieve structured objects and links, not text. Closes the
  hallucination loop the way RAG cannot.[^palantir-aip]
- **Storage backbone** — Object Storage V2 (formerly Phonograph), a
  microservices architecture with branching, change management,
  audit trails via action logs, granular permissioning.[^palantir-arch]

Maturity: shipping, multi-billion-dollar revenue, dominant in
government + heavy industry. Architectural choices: relational +
graph hybrid, closed proprietary storage, OAG vs RAG, branching as a
shared workflow ("Global Branching" GA on enrollments May 2026). Where
they win: end-to-end "decision graph" pitch is unique and lands with
buyers who want one vendor; deep integration with USG; FDE delivery
model. Where they lose: cost (USG-tier pricing), lock-in to Foundry
runtime, no FP/Clojure ergonomics, proprietary storage means data
does NOT outlive the application in McComb's sense (the data is in
Foundry's Object Storage, accessed via Foundry's API, with Foundry's
governance) — they are McComb-shaped at the modeling level and McComb-
anti-shaped at the substrate level.

What they DON'T do that creates room for kontor:

- No bitemporal valid-time axis. Foundry's audit is "what changed and
  when" (tx-time); not "what did we believe then vs now" (vt-time).
  Restating a 2024 fact in 2026 with correct historical context is
  not a first-class Foundry concept.
- No double-entry invariants or accounting domain primitives. The
  Ontology is generic; a Foundry deployment for a manufacturer
  models orders + plants + equipment, not accounts + postings +
  trial balances. Closing the books on Foundry means writing the
  accounting logic from scratch in functions.
- No open license. Customers cannot fork Foundry, embed it, or run
  it without paying Palantir. The "data outlives applications" claim
  is only true within Foundry's runtime.

### §2.2 — Snowflake AI Data Cloud + Cortex Agents

Snowflake is pivoting hard from "the data warehouse" to "the governed
execution layer for the agentic enterprise."[^snowflake-cortex]
Cortex Agents (GA 2026) operates as a four-stage workflow: planning,
tool selection (Cortex Analyst for SQL over semantic models + Cortex
Search for unstructured), execution, reflection. Governance is role-
based (SNOWFLAKE.CORTEX_AGENT_USER), agents persist threads, MCP
connectors are first-class. The $200M OpenAI partnership (April 2026)
cements the AI co-investment. Project SnowWork H1 2026 → role-
specific personas Mid-2026 → multi-agent maturity H2 2026-2027.

Maturity: shipping, $5B+ ARR business now retooled around agents.
Architecture: column-store + agent runtime + governance. Where they
win: data already lives in Snowflake for most large enterprises; the
agent runtime is one toggle away. Where they lose: agents need
structured data already in Snowflake; no story for the *act* of
writing data with double-entry invariants; no bitemporal correction;
audit story is thread-level context, not regulator-shaped.

Room for kontor: Snowflake is the *data lake* over which an analyst
agent reads. Kontor is the *system of record* into which a
transactional agent writes — and writes correctly, with sum-to-zero
and sealed audit. A Snowflake consumer + kontor as the upstream
write-substrate is a natural pairing; the consumer pulls kontor
postings into Snowflake for analytics, then writes back to kontor via
the validation gate. No competitive overlap at the substrate layer.

### §2.3 — Databricks Lakehouse + Lakebase + Genie + Mosaic AI

Databricks crossed $5.4B revenue run-rate in Q4 FY2026, valued $134B,
funded $7B for Lakebase (serverless Postgres for AI agents), Genie
(conversational data agent), Agent Bricks (high-quality agents on
enterprise data).[^databricks-q4] The finance-specific play is Genpact
Finance One on Databricks: Unity Catalog + finance Lakehouse linking
source data through an enterprise ledger to semantic models, with
Mosaic AI for forecasting + Connected Planning, Genie + Agent Bricks
for conversational + scenario.

Maturity: shipping, agentic-enterprise pitch is 2026's headline.
Architecture: lakehouse + Postgres + MLflow + Genie + Agent Bricks.
Where they win: the lakehouse pattern is well-understood; Unity
Catalog is a credible governance layer; finance-vertical accelerators
through Genpact + Accenture. Where they lose: same as Snowflake — no
substrate-level accounting invariants, no bitemporal correction, no
sealed audit. Lakebase-for-AI-agents is interesting (serverless
Postgres specifically for agent state) but it is a *cache* layer, not
a regulator-shaped system of record.

Room for kontor: the Mosaic / Genpact stack assumes the GL is
upstream; kontor is the upstream GL. Same observation as Snowflake.

### §2.4 — Hex

Hex is the notebook + warehouse pitch: Context Studio as the semantic
governance layer, "Notebook Agent" + "Threads" + "Context-aware
responses" as the AI surface, MCP integration with Slack / Claude. The
positioning is "anyone in your business can get trusted data insights,
from advanced analytics to simple questions, in one integrated
platform."[^hex] Customer roster (Reddit, Figma, Brex, AWS) is data-
team-shaped.

Maturity: shipping, mid-stage. Architecture: notebook UI + semantic
layer + agent. Where they win: blends code-based notebooks with
point-and-click; semantic-layer governance via Context Studio is a
genuine architectural choice (not "another BI tool"). Where they
lose: read-only analytics, no transactional / write story, no
domain-shaped invariants.

Room for kontor: complementary, not competitive. Hex reads from where
the data lives; kontor is one possible upstream system-of-record. A
consumer wanting Hex-style analytics over a kontor deployment would
sync via export or push kontor data into a warehouse Hex reads.

### §2.5 — dbt Labs Semantic Layer + Cube

dbt's semantic layer + Cube are the "headless BI" pattern: define
metrics once in the semantic layer, expose via standardized APIs,
let every downstream tool (BI, AI, embedded analytics) consume.[^cube]
Cube 2025 release adds WASM-powered query engine + rollup
materializations; 1-second P95 on Snowflake. The architectural claim
is that metric definitions are the bottleneck, not data movement.

Maturity: shipping, well-adopted in mid-market analytics teams.
Architecture: API-first metric layer over warehouse storage. Where
they win: text-to-SQL accuracy (90%+ via semantic layer per
VentureBeat 2026 piece[^vb-semantic]), portability across BI tools.
Where they lose: read-only, no transactional invariants, depends on
upstream warehouse being correct.

Room for kontor: kontor's read API (datalog over the bitemporal
substrate) is in some sense the analogous "metrics layer" for
accounting data — but lower-level and with explicit valid-time. A
consumer running dbt over a Snowflake export of kontor postings is
the natural composition; the substrate doesn't need to be a metric
layer too.

### §2.6 — Salesforce Einstein 1 + Agentforce

Salesforce: $800M Agentforce ARR (169% YoY), 29k deals closed, 20T
tokens consumed, 2.4B agentic work units.[^salesforce-q4] The pitch
is "the operating system for the Agentic Enterprise, bringing humans
and agents together on one trusted platform." Agentforce is positioned
as autonomous: agents not only answer but also execute, validate
compliance, update CRM + ERP records, reconcile contracts.

Maturity: shipping at massive scale. Architecture: CRM + Data Cloud
+ Einstein + Agentforce. Where they win: massive installed base,
end-to-end story. Where they lose: closed proprietary platform, no
substrate-level accounting invariants, audit is row-level "modified
by" not bitemporal valid-time correction — exactly the gap research
note 20 flagged.

Room for kontor: same observation as Foundry. Salesforce is the
sales / customer-facing layer; kontor would be the finance side of
the same business. The right framing for a customer running both is
that Salesforce orchestrates customer events, those events stitch
into kontor postings via the side-effect router (ADR-074), and kontor
holds the regulator-shaped audit trail Salesforce structurally cannot.

## §3 — The "agent + memory" tier

### §3.1 — Anthropic MCP — the de facto agent-tool standard

MCP launched late 2024; surpassed 97M monthly SDK downloads + 81k
GitHub stars by March 2026; supported by every major AI vendor
(Anthropic, OpenAI, Google, Microsoft, AWS); donated to the Linux
Foundation's Agentic AI Foundation (AAIF, co-founded with Block and
OpenAI) December 2025.[^mcp] 1000+ production deployments, 50+
pre-built MCP servers in the community repo (Google Drive, Slack,
GitHub, Postgres, Puppeteer; community: Notion, Jira, Salesforce).
OAuth 2.1 standardized in June 2025 spec. H2 2026 roadmap: stateless
servers, automatic discovery via MCP Server Cards, A2A coordination.

Maturity: standardized, deployed at scale. Architecture: JSON-RPC
over HTTP/SSE, OAuth 2.1, server-side tool registry, client-side
agent. Where they win: open standard, broad adoption, Linux Foundation
governance ensures vendor neutrality. Where they lose: a *standard*,
not a *substrate* — it tells you how to call a tool, not what tool
the agent should call or whether the call should be recorded as a
bitemporal fact.

Room for kontor: **`kontor-mcp` is the obvious next companion**.
Research note 20 sketches the surface. The implementation is small:
expose the kernel's ~87 `*-tx-data` builders as named MCP tools, with
JSON-schema arg validation, the validation-gate firing on every
write, the event-bus emitting `:transaction/committed` for agent
observability (ADR-092 round 1). The economic value of doing this
now is that every Claude/Cursor/Codex/OpenAI deployment can plug
into kontor without a custom adapter; the engineering cost is one
namespace + one ADR.

### §3.2 — Letta (formerly MemGPT)

Letta is the agent-memory framework. Persistent agents with
continuously-learning memory; multi-layered (persistent identity +
background learning agents + portable memory across providers); the
"memory palace" visualization; the "dream agents" pattern for sleep-
time compute.[^letta] Origin: UC Berkeley Sky Computing Lab's MemGPT
research.

Maturity: production, mid-stage startup. Architecture: agent state
+ long-running memory + queryable memory. Where they win: the
"memory" framing is genuinely novel — most agent frameworks treat
state as ephemeral session context; Letta treats memory as a first-
class persisted artifact. Where they lose: memory is *agent's own*
memory, not the company's system-of-record memory. A kontor posting
is a fact about the business; a Letta memory is a fact about what
the agent learned.

Room for kontor: complementary. A `kontor-letta` adapter would let a
Letta agent reference kontor postings as part of its memory graph
(via `:concept-iri` from ADR-090 round 1), and vice versa (kontor
recording the agent's actions in the system-of-record via
`:status-history/origin-agent` or similar). The pairing is more
interesting than competitive.

### §3.3 — Mem.ai, Granola.ai, Recall.ai

Mem.ai: personal AI second brain. Granola.ai: AI meeting notepad,
transcribes locally without bots, "AI already knows what you're
working on." Recall.ai: the infrastructure API behind 3000+ meeting
tools (HubSpot, Calendly, Instacart, ClickUp) — meeting bot SDKs +
recording SDKs + Calendar API.[^granola][^recall]

Maturity: shipping, varying scale. Architecture: capture + index +
LLM-augmented retrieval. Where they win: low-friction capture of the
unstructured-but-load-bearing content (meetings, notes) that
otherwise lives in head + email. Where they lose: not transactional;
no system-of-record stance; capture-and-summarize, not record-and-
audit.

Room for kontor: complementary at the long-game-vision level (a
kontor consumer might want Granola transcripts auto-tagged to
related transactions via `:audit-doc` per ADR-038), but no substrate
overlap. The interesting design question for the maintainer is
whether kontor should ship a *minimal* `:meeting-note/*` namespace
on the substrate that captures-and-tags meeting-derived context
against transactions — probably not, that's consumer-tier.

### §3.4 — LangChain + LangGraph

LangChain is now the agent-engineering platform; LangGraph is the
production-recommended framework for production agents; LangSmith
provides observability with detailed tracing (62% of orgs by 2026
have detailed tracing per the State of AI Agents report).[^langchain]
57% of orgs have agents in production; 89% have some observability.

Maturity: standard library tier; widely deployed. Architecture:
graph-of-nodes orchestration + tool calling + observability.
Where they win: ubiquity, observability, MCP integration. Where
they lose: Python, agent-framework-shaped (the substrate is the
agent; kontor's substrate is the business).

Room for kontor: A `kontor-langchain` MCP-shim is the cheap
adapter; equivalent to `kontor-mcp` but with LangGraph-specific
nodes. Probably not needed if MCP itself is the integration point —
LangGraph speaks MCP natively. Defer until a real consumer asks.

### §3.5 — Zapier MCP, Bardeen, Workato — the workflow tier

Zapier MCP: 30k+ actions across 9k apps, MCP standard interface,
enterprise SAML/SSO controls.[^zapier] Bardeen: browser AI agents
($20/mo Pro), 100+ pre-built templates, AI builder that observes the
working process and generates personalized agents.[^bardeen]

Maturity: shipping. Architecture: action catalog + agent interface
+ enterprise controls. Where they win: the integration surface is
already done. Where they lose: action-shaped, not substrate-shaped;
no transactional invariants; no audit trail beyond Zap-history.

Room for kontor: Zapier MCP is the natural distribution channel for
`kontor-mcp` — once kontor exposes the substrate as an MCP server,
the 30k+ Zapier-orchestrated workflows can target it. The audit gap
in Zapier-shaped workflows (no double-entry; no bitemporal; no
sealing) is exactly the gap kontor's substrate fills when the
workflow touches accounting state.

## §4 — The "FP&A / planning" tier

### §4.1 — Causal → Lucanet (the brand acquisition)

Causal was the modern FP&A challenger to Anaplan: cell-based
scenario modeling with first-class probability distributions, real-
time multiplayer collaboration, the "make spreadsheets feel modern"
pitch. **It got acquired by Lucanet and folded into Lucanet's CFO
Solution Platform; the standalone Causal brand redirects to xP&A as
part of Lucanet.** This is consequential for the market scan: the
"standalone modern FP&A tool" sub-category is collapsing — Pry was
acquired by Brex (2022); Mosaic was acquired by HiBob (2025-26);
Causal by Lucanet (2025-26). The FP&A tier is being absorbed by
either fintech (Brex) or HCM (HiBob) or CFO-platform (Lucanet)
incumbents.

Maturity: brand fading, technology subsumed. What's left of the
"FP&A as standalone" pitch is Pigment + Anaplan, both enterprise-
shaped.

Room for kontor: zero overlap at substrate. The pattern to learn
is that FP&A standalones don't survive — they get pulled into the
adjacent platform. If kontor were positioning as standalone-
accounting-app, this would be the warning. It isn't, so it doesn't
apply.

### §4.2 — Pigment

Pigment is the agentic-AI integrated business planning platform.
Three agents: Modeler (NL → governed data model), Analyst (proactive
metrics monitoring + anomaly detection), Planner (scenario simulation,
coming soon). MCP server enabling Claude + other AI integration.
Customers: Unilever, Siemens, Danone. Differentiator vs Anaplan:
"adapt the tool rather than structure planning around the tool."
Differentiator vs Causal: enterprise-scale + governance.[^pigment]

Maturity: shipping, growth-stage. Architecture: agentic AI + unified
governed data layer + elastic infrastructure + MCP. Where they win:
the three-agent modeler/analyst/planner split is genuinely useful;
MCP integration positions them as platform-friendly. Where they lose:
planning-shaped, not transactional; assumes the GL is upstream, not
in-tool.

Room for kontor: Pigment is a kontor *consumer*. A Pigment Modeler
agent could pull live actuals from kontor via MCP; a Pigment
Planner agent could simulate scenarios over kontor's bitemporal
substrate. The natural product positioning is "Pigment for planning;
kontor for actuals; the boundary is the GL."

### §4.3 — Anaplan

Anaplan is the incumbent. AI-driven scenario planning + multi-
dimensional modeling + Hyperblock + pre-built domain apps + role-
based AI agents. Differentiator: scale + domain depth.[^anaplan]

Maturity: legacy market leader, defending against Pigment + Causal-
now-Lucanet. Architecture: Hyperblock multidimensional cube +
domain apps + AI agents on top. Where they win: enterprise install
base, depth of domain-specific apps. Where they lose: legacy
architecture, slower iteration than Pigment.

Room for kontor: same as Pigment — Anaplan is a consumer of GL
actuals, not a competitor. Anaplan on kontor would be the same
pattern: planning over actuals.

### §4.4 — Mosaic-now-HiBob

Mosaic.tech (modern FP&A) was acquired by HiBob and is now HiBob
Finance: 30+ live data integrations, 150+ metrics, collaborative
forecasting, live people-data sync, connected 3-statement models.
The differentiator is "first mid-market FP&A tool fully integrated
within an HCM platform" — the FP&A acquisition reinforced HiBob's
HR-finance fit.[^hibob]

Maturity: shipping under HiBob's brand. Architecture: HCM + FP&A
under one app. Where they win: HR-finance integration; mid-market
positioning. Where they lose: same observation — the FP&A standalone
category is being absorbed.

Room for kontor: HiBob is *both* an HR consumer (kontor's payroll
substrate, per Stage R notes 72-79+82-89) AND an FP&A consumer (one
flow into the actuals API). Two upstream pulls, one downstream
substrate. The kontor positioning is "the engine they would have
built if they were not closed mid-market SaaS."

### §4.5 — Numeric

Numeric is the AI close automation platform: real-time transaction
monitoring + anomaly detection, automated reconciliations + journal
entry drafting, AI-generated variance + flux, MCP connector.
"Auto-generated matching rules for cash transactions (automating
90%+ of bank reconciliation)" + "AI-drafted flux explanations." 33%
faster close + 80% AI-drafted variance.[^numeric]

Maturity: shipping, growth-stage. Architecture: AI close engine over
existing GL. Where they win: the close is the painful month-end
ritual; automating it sells itself. Where they lose: bolts onto
existing GL (NetSuite / QBO / Xero); doesn't replace the substrate.

Room for kontor: Numeric is a natural *consumer*. A Numeric over
kontor would let Numeric's reconciliation agents read kontor
postings + draft reconciling journal entries via the kontor MCP
server. The competitive question is whether Rillet / Campfire eat
Numeric (they bundle close into their AI-native ERP); kontor's
position is below all of them.

### §4.6 — Rillet, Campfire, Puzzle — the AI-native ERP startups

These are the closest thing to "company-as-data" startups doing
something kontor-adjacent. Rillet: $100M+ raised in under a year,
200+ customers, hyper-growth SaaS scaling to IPO, "AI-native ERP
built by accountants for accountants." Campfire: $100M raised, mid-
market tech companies between QBO and NetSuite, proprietary "Large
Accounting Model" (LAM) claiming 95%+ accuracy on reconciliations,
"Ember AI" copilot on Claude. Puzzle: earlier-stage startups,
98% categorization automation.[^rillet][^campfire][^puzzle]

Maturity: hyper-growth (the "next NetSuite" gold rush). Architecture:
proprietary LAM + closed cloud + traditional double-entry under the
hood + AI as the structural layer. Where they win: the "QBO outgrown,
NetSuite not ready" gap is real; AI-native ERP is a credible category;
US-focused, hyper-growth-SaaS-focused, accounting-firm-friendly (PwC,
Deloitte). Where they lose: closed, US-only, no bitemporal correction
in the regulator sense, no open license. Their pitch is "replace the
20th-century accounting software" — implicitly, the AI is the
substrate; the AI is the moat. Whether the LAM is genuinely
differentiated or it's GPT-4-Turbo with accounting prompts is
unclear.

Room for kontor: these are the most interesting comparison.
- **They are eating the market kontor would not have served anyway**
  — hyper-growth US SaaS startups with $5M+ ARR. Kontor's target is
  multi-jurisdictional, multi-entity, multi-ledger consumers (the
  DE/BR/IN/US multinational from the showcases), not the YC-batch
  Series B with a US-only GAAP need.
- **They could be *consumers* of kontor if they grow into multi-
  jurisdictional**. A Campfire IPO-ready customer expanding to DE +
  IN suddenly needs SKR04, GST, IRN, TDS, Factur-X, ZUGFeRD, e-
  invoice — Campfire either rebuilds that or pulls a substrate. The
  competitive question is whether they would consider an open
  substrate dependency; the realistic answer is probably no.
- **They validate the "AI-native accounting" thesis** kontor is
  betting on. If Rillet + Campfire can raise $100M each for closed
  US-only AI-native accounting, an *open multi-jurisdictional
  substrate* a country has to localize itself has a long-tail
  audience the closed startups cannot serve.

The strategic call: do not compete head-on. Stay one layer below.
Be the substrate they would have used if they were not closed; be
the substrate the European / Indian / Brazilian equivalents of
Rillet would use if they existed.

## §5 — The "company-as-document / doc-as-software" tier

### §5.1 — Notion AI

Notion frames itself around "Meet your 24/7 AI team" — agents as
autonomous teammates, three tiers (Notion Agent, Custom Agents,
Enterprise Search + AI Meeting Notes). Deep workspace integration:
agents access context across Slack + GitHub + Google Drive +
Notion's own databases and docs.[^notion]

Maturity: shipping at scale. Architecture: doc-as-database + AI as
context-aware automation. Where they win: the workspace is where the
ad-hoc business processes live; embedding agents there is natural.
Where they lose: doc-shaped, not transactional; great for
unstructured + lightweight-structured workflows, wrong for sealed
audit trails.

Room for kontor: Notion is a consumer of meta-information about
kontor data (decisions, write-ups, runbooks); kontor is not a
Notion competitor. The most interesting kontor-Notion integration
would be `:audit-doc` references pointing at Notion pages (per
ADR-038), letting the audit chain link to the rationale a human
captured in Notion.

### §5.2 — Coda

Coda is the "all-in-one collaborative workspace." Customers: Figma,
NYT, Square, Robinhood, BuzzFeed, TED, Uber. Coda AI as a "connected
work assistant" — AI columns, content generation, AI chat. Bardeen
integration for cross-app automation.[^coda]

Maturity: shipping, mid-market. Architecture: doc + table + formula
+ automation. Same observation as Notion — workspace-shaped, not
substrate-shaped.

Room for kontor: no substrate competition; possible upstream
integration via `:audit-doc` pattern.

### §5.3 — Linear and Plane

Linear: "The product development system for teams and agents."
Linear Agent + GitHub Copilot + Cursor integration; five integrated
stages (Intake → Plan → Build → Diffs → Monitor); MCP support.
Plane: open-source alternative under AGPL-3.0, 46k GitHub stars, AI-
native project management with Projects + Wiki + AI.[^linear][^plane]

Maturity: Linear is the engineering-team-as-software canonical;
Plane is the credible open-source alt. Architecture: real-time sync
+ agent collaboration + MCP. Where they win: engineering teams
genuinely live in these tools. Where they lose: engineering-shaped;
no transactional / sealed-audit story.

Room for kontor: complementary. A Linear Agent that closes a ticket
might trigger a kontor expense posting via the side-effect router
(ADR-074), with the Linear ticket URL as `:audit-doc`. The pattern
is "engineering events stitch into business events" via the cross-
DB saga primitive.

## §6 — The "simulation + scenario" tier

### §6.1 — Reflexivity (formerly Toggle AI) — Atlas / investment AI

Reflexivity (Knabble Inc., formerly Toggle AI) raised $30M Series B
(Greycroft + IBKR), 49 employees, investment-AI platform for
institutional investors. Deep Research (autonomous agents writing
+ executing code), Knowledge Graph, Document Intelligence, Scenario
Analysis (AI simulations + portfolio stress-testing), Smart
Screening.[^reflexivity]

Maturity: shipping, mid-stage. Architecture: structured-data
aggregation (S&P, LSEG, Cboe, Nasdaq) + AI agents + scenario
simulation. Where they win: institutional-investor-shaped, deep
financial-data integration. Where they lose: investment-analytics
domain, not company-internal simulation.

Room for kontor: zero substrate overlap. Reflexivity is for external
analysts looking at companies; kontor is for the company looking at
itself. Worth noting that the "scenario analysis" pitch is real and
shipping in the institutional-investor world; if simmis wants a
comparable claim ("simulate this company internally"), Reflexivity
is one reference point for what "scenario analysis" looks like in a
shipping product.

### §6.2 — Causal-now-Lucanet (revisited)

Causal had the lightest-weight "scenario as a first-class object"
pitch in FP&A — cells with probability distributions, real-time
multiplayer scenario exploration. Now subsumed into Lucanet xP&A.
The standalone scenario-simulation pitch is wide open.

Room for kontor / simmis: the "scenario simulation over a bitemporal
substrate" pitch is one no living product makes credibly. Pigment's
Planner agent is the closest thing; it's "coming soon" as of mid-
2026. AnyLogic is enterprise-deep but operations-focused, not
finance/scenario-focused. If simmis can credibly run "100 scenarios
of the company over the bitemporal kontor substrate," that's a
genuine new category. The implementation cost is non-trivial; the
strategic opportunity is real.

### §6.3 — AnyLogic

AnyLogic: enterprise simulation, used by 40%+ of Fortune 100,
digital-twin pattern, multi-method (agent-based + discrete-event +
system-dynamics). Cloud API for embedding sims in operational
workflows. Gartner-recognized as an innovator in hyper-synthetic
data (June 2025).[^anylogic]

Maturity: legacy market leader in enterprise sim. Architecture:
multi-method modeling DSL + cloud runtime + integration with BI / ERP.
Where they win: depth and tenure; manufacturing + supply chain
specifically. Where they lose: ops-focused (not finance / scenario
planning), legacy UI, not AI-native, expensive.

Room for kontor: complementary. An AnyLogic sim of a supply chain
could pull cost inputs from kontor; a kontor consumer wanting to
simulate the operations side would use AnyLogic. The boundary is
operations sim vs financial bookkeeping.

### §6.4 — Mesa (open source ABM)

Mesa is the Python open-source agent-based modeling library; Mesa 4
in active development, Python 3.12-3.14 support, AgentSet class for
agent management.[^mesa]

Maturity: research-grade. Architecture: ABM in Python. Where they
win: open + lightweight + flexible. Where they lose: research-grade;
not enterprise-shaped.

Room for kontor: marginal. Simmis is JVM-Clojure (spindel-based);
Mesa is Python. If simmis ever needs a reference ABM implementation
to compare against, Mesa is the reference; otherwise no direct
relevance.

## §7 — Adjacent failed bets

### §7.1 — Diem / Libra (Facebook stablecoin)

Diem failed in early 2022 after sustained regulatory pushback. The
US Working Group on Financial Markets, the Fed (via phone calls to
participating banks reading prepared "not comfortable" statements),
and key partner withdrawals (Visa, Mastercard, PayPal) killed it.
David Marcus (former head): "100% a political kill."[^diem]

Lesson for kontor: regulators are the gating constraint on financial
infrastructure. Diem failed not on technology but on regulatory
posture. Kontor's design (multi-jurisdictional from day one, regulator-
shaped via ADR-019 external-codes + ADR-021 parallel ledgers +
ADR-090 concept-iri) is correct on this axis; the failure mode to
avoid is positioning that suggests bypassing regulators, even
unintentionally. Kontor's stance — "we are the substrate the
regulator-mandated audit trail lives in" — is the inverse of Diem's
"we are the alternative to the sovereign currency." Stay on the
right side of regulators.

### §7.2 — DAO frameworks (Aragon, DAOhaus, DAOstack)

DAOhaus + Aragon each have ~2.4-3.5k deployed DAOs, but <5% remain
active in any given 12-month window. DAOstack: 92 DAOs total.
Abandonment >60% within a year. Aragon abandoned pre-set
templates, pivoted to modular. "Onchain governance has failed to
empower organizations to reach their goals."[^dao]

Lesson for kontor: replacing the *legal entity* + *governance*
substrate with software is harder than replacing the *bookkeeping*
substrate with software. DAOs tried to dissolve the company; the
empirical answer is that the company structure (board, signatures,
fiduciary duties, regulator-mandated reporting) is the load-bearing
artifact, and software can serve it but not replace it. Kontor's
posture — "we are the substrate the company writes its records
into" — is consistent with this lesson. We do not pretend to
replace the company.

### §7.3 — The RPA wave (UiPath, Automation Anywhere, Blue Prism)

UiPath + Automation Anywhere have stalled their RPA-first growth
and are racing to reposition as agentic AI platforms. The
structural limitation: RPA automates *screen clicks*; when a process
needs judgment, the bot stops. AI on top doesn't change the
underlying architecture. Companies are leaving UiPath in 2026 for
agent-shaped alternatives.[^uipath]

Lesson for kontor: the agent-on-screen-clicks pattern (RPA + AI bolt-
on) is a stuck strategy. The agent-on-system-of-record pattern (AI
calls schema-validated tools that write transactional state) is the
correct successor. Kontor's MCP companion (when shipped) is exactly
this shape — the agent does not click an Odoo invoice screen; it
calls `kontor.posting/post-transaction!` with typed arguments through
the validation gate. The substrate is the trust boundary.

### §7.4 — QuickBooks Enterprise + the SMB-to-mid-market push

QuickBooks Enterprise's push into the mid-market is widely documented
as stuck: "SAP is overkill; QuickBooks is outgrown; Dynamics 365 F&O
is the ERP built for you" (MSDynamicsWorld). The QBO-to-NetSuite gap
is the precise market Rillet + Campfire are targeting.

Lesson for kontor: the "QBO outgrown, NetSuite not ready" gap is a
real and recurring market opportunity. Rillet and Campfire are the
2025-26 fillers. Kontor's positioning is *below* both — the substrate
they could have built on, in an open Clojure library, with bitemporal
correction. If a future Rillet-equivalent for Europe / India /
Brazil emerges, kontor is the substrate they could build on. We
should not try to be that consumer ourselves.

### §7.5 — BI 2.0 fizzles (Periscope, Mode pre-acquisition, Sisense)

Sisense's acquisition of Periscope (2019) is "widely regarded as a
dud" — Periscope, "one of the early success stories in the modern
data stack, has been twice rebranded out of existence." Mode was
acquired by ThoughtSpot for $200M (June 2023) and is now ThoughtSpot
Analyst Studio.[^bi2]

Lesson for kontor: standalone BI tier got eaten by ML / AI / data-
cloud platforms (Sisense → AI-BI; Mode → ThoughtSpot's GenAI; the
modern data stack's BI tier collapsed into the data-platform layer).
Substrate-tier survives differently from app-tier. Kontor at the
substrate is well-positioned not to follow this pattern as long as
it stays a library (not a hosted app).

## §8 — What kontor IS and is NOT

### §8.1 — What kontor IS

- **A bitemporal accounting substrate**. ADR-008 + ADR-048 +
  `:db.valid/from` polygon supersession (research notes 55-68 + 77).
  Every fact has both transaction-time and valid-time; corrections
  preserve "what we believed then" as a first-class queryable axis.
- **A double-entry kernel** with sum-to-zero per (entity, ledger,
  commodity), parallel ledgers (HB/StB/IFRS), multi-entity, analytic
  dimensions. ADR-021, ADR-022, ADR-031.
- **A sealed-audit primitive** (ADR-007). `:posting/posted-at`
  middleware blocks silent retraction; `:db/purge` is allowed but is
  itself a recorded commit. GDPR Art. 17 + auditor traceability
  coexist.
- **A McComb-aligned substrate** (research note 80 + note 88).
  Concept-IRI seams across six entity types (ADR-090); explain-the-
  number graph walks (ADR-091); in-process event bus (ADR-092).
- **A per-jurisdiction localization story** via `kontor-l10n-<cc>`
  (DE/US/CA/IN/BR/AT in flight); per-country tax + payroll + chart
  + filing taxonomy + e-invoice format pluggable.
- **A Clojure-only, single-dependency-on-datahike library**. ADR-001
  + ADR-002. EPL-1.0. The kernel reads as a normal Clojure library
  any consumer (beleg, simmis, third-party) embeds.
- **A cross-DB saga primitive** (ADR-074) for the realistic case
  where the deployment spans kontor + an external system (Salesforce,
  Stripe, bank, tax authority, e-invoice clearinghouse).

### §8.2 — What kontor IS NOT

- **NOT a planning tool**. Cede to Pigment / Anaplan / Causal-now-
  Lucanet upstream. Their domain is "what if"; kontor's domain is
  "what was + what is." The boundary is the GL.
- **NOT a CRM**. Cede to Salesforce / HubSpot / Salesforce
  Agentforce upstream. The boundary is the customer event stitching
  into a kontor posting via side-effect router.
- **NOT a BI tool**. Cede to Cube / Hex / Snowflake / Mode-now-
  ThoughtSpot / Sisense upstream. Kontor's read API (datalog) is
  the *low-level* primitive a BI tool reads from, not the BI tool
  itself.
- **NOT a workflow engine**. Cede to Zapier / n8n / UiPath / Bardeen
  / Workato upstream. The kontor.process step-list (ADR-067) is for
  *atomically composing kontor writes*, not for general workflow
  orchestration.
- **NOT an LLM or an agent framework**. Cede to Anthropic / OpenAI /
  Letta / LangChain / LangGraph. Kontor exposes the substrate; the
  agent runtime lives upstream.
- **NOT a UI or an ERP shell**. ADR-010 carves this out explicitly.
  beleg + simmis + third-party consumers are the UI.
- **NOT a hosted SaaS**. EPL-1.0 library; customers self-host or
  embed in their app.
- **NOT a closed AI-native ERP**. Cede the closed proprietary AI-
  native ERP market to Rillet + Campfire + Puzzle + future entrants.
  Be one layer below, open, multi-jurisdiction.

## §9 — The gap kontor fills (and nobody else does)

The gap is the **open, embeddable, bitemporal, multi-jurisdictional
accounting substrate that AI agents can write through with sealed
audit and regulator-shaped correction semantics**. The four properties
are non-trivial to combine:

| Property | Who has it? | Who doesn't? |
|---|---|---|
| Open / embeddable / EPL-1.0 | kontor | Foundry, Salesforce, Rillet, Campfire, Snowflake, Databricks, Pigment, Anaplan, Numeric |
| Bitemporal (tx-time × valid-time) | kontor, Datomic, XTDB v2 | All the others |
| Multi-jurisdictional + multi-ledger | kontor, Tryton (GPLv3, can't lift), Odoo (LGPL-derivative-tainted), SAP | Rillet, Campfire, Puzzle (US-only); Foundry (generic), Snowflake (generic) |
| Sealed audit + regulator-shaped | kontor, NetSuite, SAP, Odoo, Tryton | Snowflake, Databricks, Foundry, Salesforce, Datomic |
| AI-agent writable via MCP / tools | (not yet — `kontor-mcp` is on roadmap), Rillet, Campfire, Pigment, Salesforce, Snowflake, Anthropic | Datomic, Tryton, classic NetSuite |

No competitor in mid-2026 has **all five**. Datomic has bitemporal +
open substrate but no accounting; Odoo / Tryton have accounting +
multi-jurisdiction but no bitemporal + license problems; Rillet has
AI + accounting but no bitemporal + closed + US-only; Foundry has the
ontology + agent runtime but no bitemporal + closed + no accounting
domain; Snowflake has the data cloud + agents but no double-entry +
no bitemporal. The product gap kontor fills is genuinely empty in
mid-2026.

The substrate move that makes this gap *visible* to consumers is the
**`kontor-mcp` companion**. Until kontor speaks MCP, the substrate is
de facto invisible to the agent ecosystem the rest of the market has
consolidated around. Shipping MCP makes the gap real.

## §10 — Risks / outflanking scenarios

### §10.1 — Datomic / XTDB add an accounting companion

The closest substrate-shaped competitor is **Datomic + XTDB v2** (the
JVM bitemporal lineage). If Cognitect / Cognitect's successors (now
Nubank?) shipped a Datomic Accounting Companion, or if XTDB v2 added
"booking primitives," the substrate gap closes. Mitigation: build the
companions and the per-jurisdiction l10n now, while the gap is open.
Kontor is ahead by ~1-2 years on the accounting-specific surface;
the bitemporal substrate itself is comparable.

### §10.2 — Anthropic / OpenAI ship a structured-data agent runtime

Anthropic's "Agent Studio" or OpenAI's "Operator" + a built-in
accounting domain knowledge could subsume the kontor-mcp layer. Both
companies have the GTM to plausibly do this. Mitigation: kontor is
not the LLM and is not the agent runtime; we are the substrate they
plug into via MCP. As long as the AI agent runtime stays at the
infrastructure layer (Anthropic, OpenAI, MCP, LangGraph) and does
not vertically integrate downward into "the system of record," the
substrate is safe. If they do vertically integrate (Anthropic ships
a "Claude Accounting" hosted accounting service), kontor's hedge is
the open license + multi-jurisdiction depth.

### §10.3 — Rillet / Campfire vertically integrate

Either could decide that hyper-growth-US-SaaS is too narrow, expand
to Europe / India / Brazil, build their own multi-jurisdiction l10n.
Mitigation: this is a 2-3 year project with significant regulatory
risk; we have the head start on the surface they would need to
replicate (DE SKR03/04, Factur-X, ZUGFeRD, GSTN/GST/IRN/TDS, NF-e,
Peppol AP). Stay ahead by shipping more l10n modules.

### §10.4 — Foundry pivots downward to substrate

Palantir has the engineering to ship a Foundry Lite or open the
Ontology layer. Probability: low (the FDE delivery model is the
moat; opening the Ontology cannibalizes it). Mitigation: posture
kontor as the *anti-Foundry* — cheap, open, embeddable, where Foundry
is enterprise + closed + FDE-delivered.

### §10.5 — Salesforce / Workday / Microsoft acquire a kontor-shaped
competitor

The incumbent platforms have the cash to acquire a Tryton + payroll
+ bitemporal substrate. Probability: medium (the AI-native ERP
acquisition pattern is real — Rillet / Campfire are precisely the
candidates). Mitigation: stay open + multi-jurisdiction + Clojure-
shaped — the things the incumbents would not pivot to.

### §10.6 — Regulator demands AI-specific audit trail

A jurisdiction (likely EU under the AI Act in 2026-27) mandates a
specific "what did the AI see at decision time" audit trail with
specific reference standards. Mitigation: kontor's bitemporal +
`:audit-doc` (ADR-038) + status-history + event-bus (ADR-092) are
the right primitives; the standards work is the catch-up. Track AI
Act implementation closely.

## §11 — Concrete substrate moves the scan suggests

### §11.1 — Ship `kontor-mcp` as the substrate companion (HIGH PRIORITY)

The single most-leveraged move the scan suggests is shipping
`kontor-mcp` as a companion module. Research note 20 has the design;
the market scan reinforces:

- MCP is now Linux Foundation governed; 97M monthly SDK downloads;
  every major AI vendor speaks it. Building anything else as the
  agent interface is malpractice.
- The `*-tx-data` builders (ADR-068) are the natural unit of
  exposure — 87 builders × 207 `!`-wrappers per research note 69.
  Each builder is a named MCP tool with a JSON schema derived from
  the kontor schema. The validation gate fires; the event bus emits;
  the bitemporal write goes through.
- The MCP server is a small namespace: ~500 LoC + the per-tool
  schema-derivation logic. Test surface is enumerable.
- Strategically, this is what makes kontor *visible* to the agent
  ecosystem the rest of the market is consolidating around.

ADR draft: ADR-093 — `kontor-mcp` companion. Implementation cost: 1
week. Token cost (in agent-research-rhythm terms): ~$30-50 for
reference study + market-pain study + implement + review-after.

### §11.2 — Ship `kontor-letta-memory` only if a real consumer pulls (LOW PRIORITY)

Letta is interesting but speculative. The pairing (kontor as system-
of-record, Letta as agent-memory) would let an agent reference past
postings as part of its memory graph. But: Letta's memory pattern is
agent-internal; kontor's substrate is company-canonical. The
integration is a one-direction adapter (Letta agent reads kontor
postings via MCP and stores derived memory) — that's already
covered by `kontor-mcp`. A dedicated `kontor-letta` companion is not
needed.

Defer. If a consumer asks, build the cheapest pass-through.

### §11.3 — Position explicitly against Foundry (MEDIUM PRIORITY)

Add to `doc/value.md`:

> Foundry is the canonical "enterprise decision graph" pitch. Kontor
> is the inverse: not "buy the platform, hire the FDEs" but "embed
> the EPL-1.0 library, use it from your Clojure app, own the data."
> Foundry has the ontology; kontor has the bitemporal accounting
> substrate. They are not competitors at the substrate layer —
> kontor is what you would build under Foundry if Foundry let you,
> in 2000 lines of Clojure instead of 200M of Java + Rust.

The positioning is honest: the customer who can afford Foundry will
buy Foundry; the customer who cannot will embed kontor; the customer
running both wants a bridge. Frame the comparison in the docs.

ADR: not needed; documentation move.

### §11.4 — Ship the simmis bitemporal-restatement showcase (HIGH PRIORITY,
DEFERRED TO SIMMIS-PRIORITY DECISION)

The "scenario simulation over a bitemporal substrate" pitch has no
shipping competitor. Pigment Planner is "coming soon" as of mid-
2026. Causal is gone (subsumed by Lucanet). Reflexivity is for
institutional-investor external simulation. AnyLogic is operations.

If simmis can credibly run "100 scenarios over a bitemporal kontor
substrate," that's a genuinely new category in mid-2026. The
prerequisite is the bitemporal-restatement showcase: pick a real
period (e.g., a closed 2024 fiscal year), restate it under a
different assumption set (e.g., different FX assumption, different
tax rule interpretation, different recognition policy), show the
two restated views *side-by-side at the same point in time* — that
is the McComb-aligned "any number explains itself" claim plus the
scenario-simulation claim, made concrete.

Implementation cost: 2-3 weeks across kontor (probably no new
substrate; the seams are in place per ADR-090/091/092). simmis-
specific UI: more.

Decision needed: when does simmis need to land vs the open
strategic window? Probably not in the next 3 months given Stage R
(payroll) is in flight, but the substrate move is to make sure
ADR-048's bitemporal axis + ADR-091's explain primitives + the
simmis bridge from research note 54 are all ready when simmis pulls.

### §11.5 — Build the "kontor-rillet bridge" pattern (LOW PRIORITY)

If a Rillet customer expanding to DE/IN/BR wants kontor's l10n
without abandoning Rillet, the bridge is a thin adapter that pulls
Rillet journal entries via API + writes them to kontor via the
validation gate. The bridge would not be substrate; it would be a
consumer-tier companion. Not worth building speculatively. But the
maintainer should be aware this is the realistic "kontor inside
Rillet" path: the customer-side bridge, not a vendor partnership.

### §11.6 — Document the substrate's relationship to McComb (DONE)

Research notes 80 + 88 + the ADR-090/091/092 substrate seams are
the documented position. The ADRs themselves are the long-form
artifacts. The maintainer-facing documentation (`doc/value.md`,
`doc/programming.md`) does not currently surface this position.
Cheap fix: add a "Why is this McComb-shaped?" sidebar to
`doc/value.md`. Already a P2 from note 88.

### §11.7 — Track the AI-Act implementation closely (HIGH PRIORITY, MONITORING)

The EU AI Act enters force in stages through 2027. Article 50
(transparency obligations), Article 52 (transparency for high-risk
AI in employment / financial services), and the audit-trail
requirements being clarified by the AI Office are likely to mandate
specific recording for AI-driven financial decisions. Kontor's
substrate (bitemporal + `:audit-doc` + status-history + event-bus)
is well-shaped to satisfy these; the work is making sure the kontor
implementation maps onto the regulator's specific definitions when
they crystallize. No action this quarter; track and respond.

## §12 — Open questions for maintainer decisions

1. **Ship `kontor-mcp` now or wait?** The market is consolidating
   around MCP; every quarter we wait costs us the easy distribution
   channel. The cost is one week + one ADR. **Recommendation: ship
   in the next sprint, treat as ADR-093.**
2. **Position explicitly against Foundry in docs, or stay quiet?**
   Foundry is the canonical comparison; not addressing it leaves
   the comparison to readers. **Recommendation: add the explicit
   sidebar to `doc/value.md` (cheap, no ADR).**
3. **Is `kontor-letta-memory` a real companion, or just a one-way
   MCP pass-through?** **Recommendation: pass-through suffices; do
   not build a dedicated companion.**
4. **When does simmis need the bitemporal-restatement showcase to
   land?** Need maintainer + simmis-author input on simmis timeline.
5. **How aggressive should the "anti-Rillet" positioning be?** The
   honest answer is "they're not competitors at the substrate;
   they're potential consumers." But the analyst / press / investor
   read of kontor will be "is this the open Rillet?" The
   maintainer's choice: lean into that framing (drives interest
   from the Rillet-curious) or refuse it (preserves the substrate-
   not-app positioning). **Recommendation: refuse it explicitly in
   `doc/value.md` and `README.md` — "We are the substrate the next
   Rillet would build on, not the next Rillet."**
6. **Should kontor track the "scenario simulation over a bitemporal
   substrate" category claim or cede it?** Pigment Planner is
   coming; Causal is gone. If simmis ships the showcase, kontor
   has a defensible claim. If not, the category opens for an
   incumbent (Pigment) or a startup we have not seen yet.
7. **MCP-as-distribution-channel: should kontor publish to the
   AAIF Linux-Foundation MCP server registry?** Probably yes once
   `kontor-mcp` ships. Low cost; standardized.
8. **What's the kontor-Reflexivity-equivalent positioning?** Not
   investment AI (they win that), but "the bitemporal substrate
   under any AI workflow that touches financial state." Possibly a
   blog post / lightning talk at AAIF or strange-loop / Clojure
   conference once `kontor-mcp` ships.

## §13 — Sources

All URLs accessed 2026-05-18.

[^palantir-q1]: Palantir Q1 FY2026 release.
  <https://www.sec.gov/Archives/edgar/data/0001321655/000132165526000026/a2026q1ex991pressrelease.htm>
[^palantir-onto]: Palantir Foundry Ontology docs.
  <https://www.palantir.com/docs/foundry/ontology/overview>;
  <https://www.palantir.com/docs/foundry/architecture-center/ontology-system>
[^palantir-aip]: AIP architecture overview.
  <https://www.palantir.com/docs/foundry/architecture-center/aip-architecture>;
  <https://www.palantir.com/docs/foundry/aip/aip-features>
[^palantir-arch]: Palantir Ontology system explainer.
  <https://zerofuturetech.substack.com/p/palantir-ontology-explained-why-its>
[^snowflake-cortex]: Snowflake Cortex Agents docs +
  press releases.
  <https://www.snowflake.com/en/blog/enterprise-ai-agent-platform/>;
  <https://docs.snowflake.com/en/user-guide/snowflake-cortex/cortex-agents>;
  <https://www.snowflake.com/en/news/press-releases/snowflake-expands-snowflake-intelligence-and-cortex-code-to-power-the-control-plane-for-the-agentic-enterprise/>
[^databricks-q4]: Databricks Q4 FY2026 + Lakebase + Genie.
  <https://www.constellationr.com/insights/news/databricks-details-q4-figures-raises-7-billion-build-out-lakebase-genie>;
  <https://www.prnewswire.com/news-releases/databricks-grows-65-yoy-surpasses-5-4-billion-revenue-run-rate-doubles-down-on-lakebase-and-genie-302682674.html>
[^hex]: Hex product positioning. <https://hex.tech>
[^cube]: Cube semantic layer.
  <https://cube.dev/>; <https://github.com/cube-js/cube>
[^vb-semantic]: VentureBeat — Headless vs native semantic layer for
  text-to-SQL.
  <https://venturebeat.com/ai/headless-vs-native-semantic-layer-the-architectural-key-to-unlocking-90-text>
[^salesforce-q4]: Salesforce FY2026 Q4 + Agentforce.
  <https://www.sec.gov/Archives/edgar/data/0001108524/000110852426000056/crm-q4fy26xexhibit991.htm>;
  <https://www.salesforce.com/agentforce/>
[^mcp]: Anthropic Model Context Protocol intro + Wikipedia + 2026
  enterprise guides.
  <https://www.anthropic.com/news/model-context-protocol>;
  <https://en.wikipedia.org/wiki/Model_Context_Protocol>;
  <https://workos.com/blog/everything-your-team-needs-to-know-about-mcp-in-2026>
[^letta]: Letta product page (memory-first persistent agents).
  <https://letta.com>
[^granola]: Granola.ai product page.
  <https://www.granola.ai>
[^recall]: Recall.ai product page.
  <https://www.recall.ai>
[^langchain]: LangChain State of AI Agents 2026.
  <https://www.langchain.com/state-of-agent-engineering>;
  <https://www.langchain.com/blog/on-agent-frameworks-and-agent-observability>
[^zapier]: Zapier MCP (30k+ actions, 9k apps).
  <https://zapier.com/mcp>;
  <https://zapier.com/blog/zapier-mcp-guide/>
[^bardeen]: Bardeen.ai browser-agent automation.
  <https://www.bardeen.ai/>;
  <https://www.bardeen.ai/posts/automating-automation-an-ai-agent-that-automates-enterprise-workflows>
[^pigment]: Pigment integrated business planning platform.
  <https://www.pigment.com>
[^anaplan]: Anaplan platform.
  <https://www.anaplan.com/platform/>
[^hibob]: HiBob Finance (post-Mosaic acquisition).
  <https://www.hibob.com/finance/>
[^numeric]: Numeric AI close platform.
  <https://numeric.io>;
  <https://www.numeric.io/blog/rillet-vs-campfire>
[^rillet]: Rillet AI-native ERP.
  <https://www.rillet.com/>;
  <https://financialit.net/news/fundraising-news/rillet-raises-70m-replace-20th-century-accounting-software-ai-native-erp-built>
[^campfire]: Campfire AI-native ERP comparison via Numeric blog.
  <https://www.numeric.io/blog/rillet-vs-campfire>
[^puzzle]: Puzzle.io AI accounting for startups.
  <https://puzzle.io/blog/ai-accounting-software-startups>;
  <https://puzzle.io/blog/best-ai-accounting-software>;
  <https://puzzle.io/blog/accounting-agent-software-startups-finance-guide>
[^notion]: Notion AI.
  <https://www.notion.com/product/ai>
[^coda]: Coda all-in-one workspace.
  <https://coda.io>
[^linear]: Linear 2026 positioning (product development system for
  teams and agents).
  <https://linear.app>
[^plane]: Plane open-source project management.
  <https://plane.so/>;
  <https://github.com/makeplane/plane>
[^reflexivity]: Reflexivity investment AI platform (formerly Toggle AI).
  <https://reflexivity.com/en>;
  <https://www.fintechfutures.com/ai-in-fintech/ai-co-pilot-solution-reflexivity-raises-30m-series-b-led-by-greycroft-and-ibkr>
[^anylogic]: AnyLogic enterprise simulation + digital twin.
  <https://www.anylogic.com/features/digital-twin/>;
  <https://www.anylogic.com/features/enterprise-simulation/>
[^mesa]: Mesa Python ABM library.
  <https://github.com/mesa/mesa>;
  <https://mesa.readthedocs.io/stable/>
[^diem]: Diem/Libra failure analysis.
  <https://www.theblock.co/post/328852/former-facebook-exec-says-diem-libra-stablecoin-fell-victim-to-a-political-kill>;
  <https://www.washingtonpost.com/technology/2022/01/28/facebook-cryptocurrency-diem/>;
  <https://www.cnn.com/2022/02/01/tech/facebook-diem-association-dissolving>
[^dao]: DAO frameworks adoption + abandonment analysis.
  <https://blog.aragon.org/the-future-of-governance-is-modular-2/>;
  <https://peerj.com/articles/cs-3320/>;
  <https://www.aragon.org/how-to/three-learnings-from-six-years-of-building-dao-frameworks>;
  <https://dl.acm.org/doi/10.1145/3777416>
[^uipath]: UiPath / Automation Anywhere transition from RPA to
  agentic AI.
  <https://aibusiness.com/agentic-ai/uipath-moves-into-agentic-ai-realm>;
  <https://www.reworked.co/digital-workplace/uipaths-business-automation-vision-shifts-from-rpa-to-agentic-ai/>;
  <https://runautomat.com/blog/why-companies-are-leaving-uipath-2026>;
  <https://diginomica.com/why-uipath-re-designing-its-platform-around-agents-build-automations-not-just-run-them>
[^bi2]: Sisense-Periscope + Mode-ThoughtSpot acquisition history.
  <https://techcrunch.com/2019/05/14/sisense-acquires-periscope-data-to-build-integrated-data-science-and-analytics-solution/>;
  <https://benn.substack.com/p/how-an-acquisition-fails>;
  <https://www.thoughtspot.com/press-releases/thoughtspot-acquires-mode-analytics-for-200m>;
  <https://medium.com/@5000fish/mode-analytics-was-acquired-by-thoughtspot-best-mode-alternatives-in-2026-6f02c83fe0e3>

Internal:

- `doc/research/10-business-os-companion-projects.md` — Odoo/Tryton/
  SAP/Salesforce companion survey.
- `doc/research/20-ai-native-business-os.md` — AI-native business-OS
  landscape; MCP companion sketch; AccountingBench failure modes.
- `doc/research/53-kontor-v2-consolidation.md` — v2 consolidation
  recommendations driving `doc/value.md` + `doc/programming.md`.
- `doc/research/54-simmis-ui-integration.md` — simmis-side rendering
  strategy; flexibility-vs-consistency tension.
- `doc/research/80-mccomb-future-of-accounting-vs-kontor.md` —
  McComb / Dunn / REA survey; the substrate-vs-modeling distinction.
- `doc/research/88-mccomb-substrate-seams-round-1.md` — ADR-090/091/
  092 substrate seam implementation; round-2 candidates.
- `README.md`, `CLAUDE.md`, `doc/value.md`, `doc/programming.md` —
  current public positioning.
- ADRs cited: ADR-001 (single-dep), ADR-002 (cohabitation), ADR-007
  (sealing), ADR-008/ADR-048 (bitemporal), ADR-010 (scope carve-
  outs), ADR-019 (external-codes), ADR-021 (parallel ledgers),
  ADR-022 (analytic dimensions), ADR-031 (multi-entity), ADR-038
  (audit-doc + approval-policy), ADR-067 (kontor.process), ADR-068
  (*-tx-data builders), ADR-074 (cross-DB saga), ADR-090 (concept-iri
  seams), ADR-091 (kontor.explain), ADR-092 (kontor.event-bus).

---

End of note.
