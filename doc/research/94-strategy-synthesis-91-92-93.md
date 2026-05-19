---
date: 2026-05-18
title: 94 — Strategy synthesis from notes 91 + 92 + 93
status: draft
audience: maintainer — read this before scoping the next 4-8 weeks of work
---

# 94 — Strategy synthesis: datasets + market + privacy → next round

Three research notes landed in parallel:

- **91** real-world accounting datasets fit for kontor's schema
- **92** "company-as-software" market positioning
- **93** employee track-record privacy framework

This note triages their findings into a concrete plan. The discipline:
identify the cross-cutting thesis, choose a small number of load-bearing
moves, and explicitly call out what to skip. Volume is not the goal —
sequencing is.

---

## §1 — The unifying thesis

The three notes converge on one statement:

> **kontor's competitive position is "the credible open bitemporal
> substrate that an LLM/VLM agent can write to safely under multi-
> jurisdictional privacy + audit constraints, with real public-data
> showcases proving it works."**

Each note contributes one term:

- **Note 91** — *real public-data showcases proving it works*. SEC EDGAR
  (public domain) + GLEIF (CC0) are bundleable today. Apple's
  10-K/10-K-A restatement history is the canonical bitemporal-correction
  corpus the substrate was designed to handle. No competitor showcases
  bitemporal restatement against a real public filing chain.
- **Note 92** — *credible open substrate*. The AI-native ERP gold rush
  (Rillet → $100M in 10 weeks; Campfire; Numeric; Pigment) is happening
  *now* and is exclusively closed SaaS, US-centric. No competitor occupies
  the "open license + bitemporal + multi-jurisdiction + sealed audit +
  agent-writable" slot. MCP became the de-facto standard (Linux Foundation
  Dec 2025); without an MCP server, kontor is invisible to the
  consolidating agent ecosystem.
- **Note 93** — *under multi-jurisdictional privacy + audit constraints*.
  GDPR Art. 6/9/22/35/88 + BDSG §26 + BetrVG §87 + EU AI Act Art. 5/26 +
  CA CPRA + BIPA + BAG 1 ABR 22/21 are load-bearing for the
  trans-national pitch. The substrate needs `:consent/*` + canonical
  category vocabulary + refusal posture for AI-Act-banned categories
  (emotion recognition, biometric inference, covert monitoring). This
  is not optional — without it, the EU-multi-jurisdiction story doesn't
  ship.

The three terms compose: open + bitemporal + jurisdictional + audit-doc
+ agent-writable + public-data-showcased. None of these on its own is
defensible; together they are.

---

## §2 — What changes from prior plan

The prior plan (notes 90 §4 + roadmap) was:

- **A** multi-year DE GmbH Mahnverfahren scenario
- **B** cross-stage real-adapter wiring ✅ (landed today)
- **C** agent-driven Jahresabschluss benchmark
- **D** simmis forward projection
- **E** DE+US+CA payroll showcase
- defer kontor-people-record + kontor-mcp until research lands

After 91/92/93, this re-prioritizes:

1. **Server-agnostic tool catalog moves up; a kontor-side MCP server
   stands down.** Note 92's strongest finding is "MCP is *the* agent-
   tool standard now" — but `../dvergr` already ships a working MCP
   server (`src/dvergr/mcp/server.clj`, ~443 LoC, TCP+stdio, hot-swap
   `tool-handlers` atom). Reinventing the JSON-RPC + transport plumbing
   has no value. The kontor-side leverage is the *tool catalog*
   (ADR-068 builders + read surface as a server-agnostic registry),
   which dvergr consumes today and any future standalone server can
   consume later. ~2 days for the catalog; kontor-mcp deferred until a
   consumer (beleg/simmis) asks for it.
2. **EDGAR ingest moves up significantly**. Note 91 §8 Scenario A
   (Apple 10-K 2015-2024 with restatements) is a stronger bitemporal
   showcase than the synthetic DE GmbH because it's verifiable against a
   real public filing chain. The DE GmbH stays as the *jurisdictional-depth*
   showcase but loses the headline bitemporal-correction slot.
3. **kontor-people-record + ADR-094 substrate posture move up**. Note 93's
   AI-Act refusal posture is overdue, not pre-emptive — Art. 5(1)(f)+(g)
   has been current law since Feb 2025. Without the posture documented,
   the trans-national pitch has a compliance hole. The companion stays
   minimal (categories + consent schema + retention seeds — not a UI).
4. **Cross-stage scenarios stay roughly as planned but reordered**. C
   (Jahresabschluss benchmark) becomes more concrete once MCP lands; D
   (simmis) stays gated on a `kontor-simmis` ergonomic wrapper.
5. **`kontor-letta-memory` is explicitly skipped**. Note 92: MCP covers
   the agent-memory composition; building a parallel Letta adapter
   duplicates effort.
6. **`kontor-fund-accounting` reserved, not built**. Note 91's FDTA
   2026/2027 wave is real but lead-time is long enough to defer.

---

## §3 — Recommended priority queue (next 4-8 weeks)

In strict execution order. Each item lists its honest size estimate +
the load-bearing rationale.

### §3.1 — ADR-094 + `kontor.consent/*` schema + canonical categories (~2-3 days)

**What.** Land note 93's substrate posture. Concrete deliverables:

- ADR-094 in `doc/decisions.md` — "Employee-monitoring substrate posture +
  consent schema + AI-Act refusal posture."
- `:audit-doc/canonical-categories` def extended with the 8 new HR
  values (`:hr-track-record`, `:hr-activity-monitoring`,
  `:hr-activity-content`, `:hr-communications`, `:hr-background-check`,
  `:hr-compensation-negotiation`, `:hr-grievance`,
  `:hr-monitoring-consent`).
- New `:consent/*` mini-schema in `modules/hr/src/kontor/hr/consent.clj`
  (~10 attrs: `:subject :scope :legal-basis :granted-at :withdrawn-at
   :supporting-doc :works-agreement-ref :state :parent-consent
   :notice-acknowledged-at`).
- `:consent/legal-basis` vocabulary keyed to GDPR Art. 6(1)(a-f) + Art.
  9(2)(a/b/h) + Art. 10 + BDSG §26 + special `:ai-act-incompatible`
  marker.
- Retention-policy seeds in `modules/l10n-{de,us,gb,ca,eu}/` (kernel
  ships shape only; per-jurisdiction durations live in l10n companions).
- Unit tests + REPL examples.

**Why first.** This unblocks every subsequent companion that touches HR
data (people-record, MCP server's permission surface, Jahresabschluss
agent's consent-aware DSAR responses). It also closes the compliance
hole — the EU AI Act articles are current law.

**What this is NOT.** Not a `kontor-people-record` companion — that's
§3.5. Not a productivity-score / sentiment / emotion-recognition
scaffold — that's explicitly refused.

### §3.2 — `kontor.agent-tools` server-agnostic tool catalog (~2 days)

**Revised 2026-05-18 after surveying `../dvergr`.** dvergr already ships
a working MCP server at `src/dvergr/mcp/server.clj` (~443 LoC, TCP +
stdio, JSON-RPC, hot-swap `tool-handlers` atom). Duplicating the
JSON-RPC + transport plumbing into a standalone `kontor-mcp` module has
no marginal value — the leverage point is the kontor *tool catalog*,
not another server.

**What.** Ship a single namespace `src/kontor/agent_tools.clj` that
exposes a server-agnostic catalog: plain `{:name :description :schema
:handler}` maps.

- Wrap every `*-tx-data` builder (per ADR-068) as a write tool entry.
- Wrap the read surface: `kontor.explain/explain-balance`,
  `kontor.report/compute-report`, `kontor.dsar/collect`,
  `kontor.balance/account-balance`, `kontor.trial/trial-balance`.
- Each handler routes writes through
  `kontor.validation/transact-with-validation` — the gate is already
  audit-doc + status-machine + sealing aware.
- Tests exercising the catalog → handler invocation → tx-report (no
  MCP server in the test loop; the catalog is the unit under test).

Consumer code (showcase 07, beleg, future kontor-mcp if anyone asks
for one) registers the catalog into whichever MCP server it uses:

```clojure
(require '[dvergr.mcp.server :as dvergr-mcp]
         '[kontor.agent-tools :as kt])
(swap! dvergr-mcp/tool-handlers merge (kt/catalog))
(dvergr-mcp/start! {:port 17888})
```

**Why second.** Note 92's highest-leverage finding from kontor's side
isn't "ship a server" — it's "make kontor's surface registerable by any
agent runtime." The catalog is that surface. Building it now means
showcase 07 + every later agent scenario have a clean dependency point;
deferring it means showcase 07 invents the catalog inline.

**What this is NOT.** Not an MCP server. Not Letta-specific. Not bundled
API keys. The catalog has no transport opinions; the consumer chooses
(dvergr today; future standalone if a beleg-like consumer asks).

**Honest risk.** The kontor.validation gate may not surface
machine-actionable structured errors today — agents need parseable
error shapes so they can repair. Likely 1-day delta to extend the
validation error path. Worth scoping during the catalog build, not
upfront.

**Deferred.** A standalone `kontor-mcp` module + ADR-093 stays on the
queue (task #260 [DEFERRED]) but gated on a consumer ask. The catalog
in this section is the prerequisite for ever building it cheaply.

### §3.3 — `kontor-import-gleif` (~2-3 days)

**What.** A new module `modules/import-gleif/` that pulls GLEIF Golden
Copy LEI data (CC0) into kontor's `:partner/concept-iri` +
`:entity/concept-iri` substrate per ADR-090.

- Daily bulk download + diff-apply (small file, ~600MB uncompressed).
- LEI ↔ `:partner` reconciliation via fuzzy + exact name match.
- Level 2 RR-CDF (parent/subsidiary relationships) → ADR-073
  `kontor.entity/family` walks.
- Bidirectional: `:concept-iri` populated for both inbound (GLEIF)
  and outbound (linking) directions.
- Tests against a small fixture subset (~50 LEIs covering Apple,
  the McComb examples, the trans-national Acme fixture).

**Why third.** Smaller than EDGAR (no XBRL, no restatement chain).
Every subsequent ingest module wants GLEIF as the join key. Ships
the "ADR-090 + ADR-073 work in concert against real public data"
proof point cheaply.

**Honest risk.** GLEIF's bulk file format changes occasionally; the
parser needs a versioning story. Manageable.

### §3.4 — `kontor-import-edgar` (~1 week)

**What.** A new module `modules/import-edgar/` that pulls SEC EDGAR
XBRL filings (10-K, 10-Q, 10-K/A) into kontor's posting + concept-iri
substrate.

- HTTP client with mandatory `User-Agent` header (note 91 §10
  gotcha — without this, every request 403s).
- Bulk `companyfacts.zip` ingest path for historical backfill.
- Incremental `submissions.zip` for daily updates.
- XBRL → `:posting` + `:account` mapping via the US GAAP taxonomy
  + `:account-tag/concept-iri` per ADR-019.
- **10-K/A handling — the load-bearing bit**. When EDGAR publishes
  an amended filing, the kontor ingest opens `:tx/valid-from` for
  the amended fact, leaving the original fact valid until the
  amendment's effective date — the bitemporal-correction story is
  shown end-to-end on real data.
- Tests against an Apple subset (10-Ks 2015-2024 + the 2019 10-K/A).

**Why fourth.** Bigger than GLEIF (XBRL parsing + posting mapping
+ restatement logic) but produces the headline Apple bitemporal
showcase. Reusable for the FDTA 2027 wave (note 91 §10 future
work) without code changes — just additional taxonomies.

**Honest risk.** Mapping US GAAP to a generic kontor CoA is
opinionated; some accounts will resist auto-mapping and need
consumer overrides. The 10-K/A restatement model needs careful
test design — what counts as "amended fact" vs "new period fact"
is sometimes ambiguous in the XBRL.

**What this is NOT.** Not a full XBRL reader (we don't render
calculation linkbases; we don't validate). Not multi-taxonomy on
day one (US GAAP only; IFRS / FRS-101 in v2).

### §3.5 — `kontor-people-record` minimal companion (~2-3 days)

**What.** A small companion that operationalizes ADR-094 — exposes
the consent + retention + audit-doc + DSAR machinery against an
employee track-record consumer.

- Module `modules/people-record/`.
- Implements the consent + retention + DSAR loop on top of the
  ADR-094 schema.
- Refuses to scaffold productivity scores, emotion/sentiment fields,
  covert telemetry, biometric inference, real-time monitoring
  dashboards.
- Implements only the categories the substrate canonicalizes:
  `:hr-track-record` (performance reviews, formal documented record),
  `:hr-background-check`, `:hr-compensation-negotiation`,
  `:hr-grievance`, `:hr-monitoring-consent`. The activity-monitoring
  variants stay opt-in via consent only.
- One integration test exercising a 3-year employee lifecycle:
  hire → consent → annual review → grievance with privilege → DSAR
  request → termination → retention sweep with `:audit-doc/category`
  filter.

**Why fifth.** Demonstrates ADR-094 in operation against a realistic
consumer. Without it, the ADR is theory. With it, kontor has a
turnkey demo for "what does GDPR-compliant agent-readable HR look
like."

**What this is NOT.** Not a full HRIS. Not a UI. Not benefits/payroll
duplication (those are kontor-hr + kontor-payroll-*).

### §3.6 — Showcase 05: Apple 10-K bitemporal restatement (~2 days)

**What.** Clay notebook at `doc/showcases/05_apple_10k_bitemporal.clj`
that:

1. Bootstraps a kontor db.
2. Installs `kontor-import-edgar` + `kontor-import-gleif`.
3. Pulls Apple's 10-Ks 2015-2024 + the (illustrative) 10-K/A.
4. Renders the bitemporal-restatement narrative — Bilanz before vs
   after the restatement, with the substrate's `:as-of-valid` axis
   showing the "what we believed then vs what we know now" view side
   by side.
5. Renders an `:as-of-tx` view showing how the substrate state evolved
   on the day the 10-K/A landed.

**Why sixth.** First showcase against real public data. Demonstrates
bitemporal substrate to a finance professional in 5 minutes. Strongest
single artifact for the open-substrate competitive pitch.

**What this is NOT.** Not legal/tax advice. Not a claim Apple's
restatement was wrong. Documentation states clearly: this is
demonstrating the substrate's bitemporal mechanics against publicly
filed data.

### §3.7 — Showcase 06: Multi-year DE GmbH (from note 90 Scenario A, ~2-3 days)

**What.** As scoped in note 90 §4 Scenario A — 36-month Acme
Manufacturing GmbH narrative with payroll, lease, asset, parallel
ledgers, backdated correction, year-3 DSAR + retention sweep.

**Why seventh.** Showcase 05 (Apple) proves bitemporal at scale on real
US data; this showcase proves jurisdictional depth (HGB §238-263 +
DATEV LODAS + Steuerbilanz parallel ledger + UStVA + DSGVO + retention).
Both are needed.

### §3.8 — Showcase 07: Agent + MCP + people-record (~2 days)

**What.** Clay notebook + Claude-Desktop / Cursor / generic MCP-client
walkthrough showing an agent:

1. Reading the substrate state via `explain-balance` MCP tool.
2. Drafting a payroll correction.
3. Submitting through the `transact-with-validation` MCP write tool.
4. Observing the consent + privilege + retention machinery gate the
   write (or fail-fast with a structured error the agent can repair).

**Why eighth.** Closes the "company-as-software" loop end-to-end —
agent ↔ substrate ↔ regulatory ↔ audit-doc. First time the full pitch
is concretely demonstrable. Builds on §3.1-3.5 deliverables.

### Skipping or deferring

- **Showcase 04 simmis forward projection** (note 90 Scenario D) — stays
  deferred; gated on a `kontor-simmis` ergonomic layer that's outside
  the next 4-8 weeks.
- **Showcase C Jahresabschluss benchmark** (note 90 Scenario C / task
  #246) — stays deferred until showcase 07 lands; the benchmark needs
  the agent loop to exist first.
- **`kontor-letta-memory`** — explicitly skipped. MCP covers it.
- **`kontor-fund-accounting`** — reserved; not built. FDTA wave still
  18+ months away.
- **Companies House ingest** — defer to v1.1 (after EDGAR proves the
  XBRL pipeline shape).
- **IRS Form 990 ingest** — defer to v1.1.

---

## §4 — Showcase set (the "company-as-software" demonstration)

The user's framing — *"the goal is to get a complete implementation of a
company's organisation, i.e. its organisational software we have here,
but also models of the pieces that are not needed for the organisation"*
— maps to a coherent 8-showcase set spanning the substrate + ingest +
agent + simulation layers. Five exist; three are proposed:

| # | Title | Layer | Status | LoC est. |
|---|---|---|---|---|
| 01 | DE B2B Factur-X + Mahnverfahren | substrate + l10n | shipped | 532 |
| 02 | US LLC multi-state sales tax + Reg-F | substrate + tax-provider | shipped | 530 |
| 03 | IN B2B IRN + GSTR + TDS | substrate + l10n | shipped | 619 |
| 04 | Multi-entity intercompany | substrate + consolidation | shipped | 604 |
| 05 | **Apple 10-K bitemporal restatement** | ingest + bitemporal | proposed | ~600 |
| 06 | **Multi-year DE GmbH (Mahn + payroll + lease + DSAR)** | substrate + l10n + HR | proposed | ~800 |
| 07 | **Agent + MCP + people-record** | agent + consent + privacy | proposed | ~400 |
| 08 | DE+US+CA payroll month (note 90 Scenario E) | substrate + payroll trio | optional | ~300 |

The 8-set shape, mapped to the user's framing:

- **01-04** — organisational software you have here.
- **05** — models of the pieces not needed for the organisation (the
  outside world's filings, restatements, regulatory truth).
- **06** — the long-running organisational record (multi-year coherence,
  jurisdictional depth, real corrections).
- **07** — LLM/VLM agents using the substrate to run actions /
  reconcile data / propose corrections — the "company-as-software" loop
  closed.
- **08** — operational depth proof: payroll across jurisdictions in one
  month, multi-engine.

The simulation layer (the user's "agents to run experiments/simulations,
coarse grainings, simulations the system builds about the market,
customers, partners, production processes") is *deliberately not in this
set yet* — it lives in `kontor-simmis` once the substrate has had time
to settle. Showcase 09 (simmis forward projection from note 90 Scenario
D) is the future addition.

---

## §5 — What note 93 changes in the existing codebase

The smallest concrete delta from note 93 that needs to land BEFORE
showcase 07 can be written:

1. **Extend `:audit-doc/canonical-categories`** in `src/kontor/audit_doc.clj`
   with the 8 new values (open-set; no schema migration).
2. **Add `:consent/*` schema** to `modules/hr/src/kontor/hr/schema.clj`.
3. **Add `:retention-policy/category` seeds** to per-l10n modules
   (DE first, others to follow).
4. **Document the refusal posture** in `doc/value.md` — "kontor refuses
   to scaffold AI-Act-banned categories" as a positive feature, not a
   gap.

These are small, mostly additive, and unblock §3.5 + showcase 07.

---

## §6 — What note 92 changes in `doc/value.md` + README

Two small repositioning moves to land alongside §3.2:

1. **Add an "anti-Foundry" framing paragraph** to `doc/value.md`. The
   point isn't to attack Foundry — it's to make the substrate vs
   platform distinction load-bearing for the reader. "Embed the
   EPL-1.0 library in your Clojure app; own the data. We are the
   substrate the next Rillet would build on, not the next Rillet."
2. **Refuse the "open Rillet" / "open Pigment" framing explicitly**
   in README. Saying what kontor is *not* is more useful than
   listing what it is — there's no shortage of closed AI-native ERP
   builders right now, and confusion about that costs evaluators
   30 seconds we can save.

Both deltas are ≤ 1 paragraph each. Bundle with the kontor-mcp
shipping.

---

## §7 — Open questions for the maintainer

1. **Resolved 2026-05-18.** Standalone kontor-mcp deferred; we compose
   via dvergr's existing MCP server. The kontor-side delta is the
   server-agnostic `kontor.agent-tools` catalog (§3.2). Revisit
   standalone server only when a consumer explicitly asks for one
   without buying into dvergr's full stack.
2. **EDGAR ingest — do we ship a corpus or just the ingest module?**
   Note 91 §10 surfaces a "kontor-corpus sibling repo" question. My
   recommendation: ship the ingest module only; the showcase notebook
   downloads on first run. Keeps the kontor repo lean.
3. **kontor-people-record — does this live in the main repo or a
   sibling?** I'd lean main-repo `modules/people-record/` for now;
   small enough that splitting is premature optimization.
4. **simmis branch — is `d/branch` actually shipping in
   `../datahike-bitemporal-v1`?** This determines whether showcase 09
   (forward projection) is buildable today or needs upstream work.
   Worth a 30-minute check before scoping it.
5. **ADR-094 schema — should `:consent/legal-basis` carry the article
   keyword (`:gdpr/art-6-1-b`) or a free string?** Recommend the
   keyword vocabulary for query-ability; note 93 §6 already lists the
   full set.
6. **Showcase 05 — does it need a Yahoo-Finance fallback for stock
   price data, or is EDGAR self-sufficient?** EDGAR carries enough
   to render BS + GuV + the restatement story; stock price is not
   needed for the bitemporal-correction demonstration.

---

## §8 — Sources

- `doc/research/91-real-accounting-datasets.md` — datasets, ingest
  feasibility, FDTA roadmap
- `doc/research/92-company-as-software-market.md` — market tiers,
  MCP standardization, competitive positioning
- `doc/research/93-employee-tracking-privacy.md` — categories,
  consent schema, AI Act posture, retention seeds
- `doc/research/90-showcase-and-integration-test-map.md` — coverage
  map this synthesis builds on

ADR cross-refs: ADR-001 (single-dep) / ADR-005 (no bundled keys) /
ADR-019 (`:account-tag/concept-iri`) / ADR-068 (`*-tx-data` builders) /
ADR-073 (consolidation) / ADR-075 (audit-doc category) / ADR-090
(`:concept-iri` substrate seam) / ADR-091 (explain) / ADR-092
(event-bus).

---

End of note 94.
