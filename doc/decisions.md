# Architecture decisions

This is the **canonical** record of locked design choices. Other documents (architecture.md, roadmap.md, code) cite back here. Each entry lists the decision, the *why*, the alternatives considered, and the date.

---

## ADR-001 — License: EPL-1.0

**Decision.** The kernel ships under EPL-1.0.

**Why.** Matches the Clojure ecosystem default (Datomic, datahike, most replikativ libraries). Weak per-file copyleft means consumers — beleg, simmis, or third-party SaaS — can integrate without their own code becoming derivative. Aligns with the replikativ stack the user already maintains.

**Alternatives.** MIT (more permissive but breaks alignment with replikativ); Apache-2.0 (permissive + patent grant, matches our Mustang/phax dependencies — viable second choice if a contributor strongly prefers it later, but EPL is the default).

**Per-country localization modules license separately** — see ADR-006.

Date: 2026-05-09.

---

## ADR-002 — One database, two schema namespaces (beleg cohabitation)

**Decision.** Beleg's `:invoice/* :customer/* :offer/*` and accounting's `:account/* :posting/* :journal/* :tax/*` live in **one datahike instance**. Posting an invoice is a single atomic transaction that writes both the beleg side (invoice state change) and the accounting side (journal entry).

**Why.** Atomicity. The alternative (two DBs, event-bridge) trades atomicity for loose coupling — but for an SMB tool, the consequence of "invoice issued but journal entry missed" is exactly the bug class an accounting kernel exists to prevent. Single DB also means a single audit chain covers both sides.

**Alternatives.** Two DBs with a posting bridge (rejected: weaker atomicity, two audit chains to reconcile). Embed accounting *inside* beleg (rejected: coupling, prevents reuse from simmis or other consumers).

**Implication.** `kontor` does not own `(d/connect)` — it accepts a connection. Schema is transactable as `(transact conn schema/all)`. Tests open their own ephemeral memory DBs.

Date: 2026-05-09.

---

## ADR-003 — Audit-chain hardening lives upstream in datahike

**Decision.** The four datahike improvements needed for tamper-evidence-grade audit (per [research note 02](research/02-datahike-versioning-and-hashing.md)) land **upstream in `replikativ/datahike`**, not in this repo:

1. Replace the per-tx `:hash` (`clojure.core/hash` summed → 32-bit, forgeable) with a SHA-256 (or SHA-512) digest over canonical-encoded sorted EAVT datoms of the commit. Stored in `:meta`, fed back into `create-commit-id`.
2. Commit signature hook — caller-provided fn invoked at commit time with the new content hash; signature stored alongside the commit.
3. Make `:crypto-hash? true` the documented recommended config for accounting/audit use.
4. (No "sealed branch" feature needed — see ADR-007 on purge semantics.)

**Why.** The `:hash` field is internal to datahike and feeds the commit-id; layering SHA-256 on top in this repo would be ineffective because the *commit-id itself* is the cross-reference auditors verify. The fix has to be in `datahike/writing.cljc` + `datahike/db/transaction.cljc`. The user maintains datahike — this is the right place.

**Alternatives.** Patch repo / fork (rejected: divergence cost). App-layer wrapper (rejected: doesn't actually close the gap because attackers writing to konserve directly bypass it).

**Sequencing.** This is **Track B** — runs in parallel with Phase 1 of `kontor`. Phase 1 ships using today's datahike with `:crypto-hash? true` and a documented gap; the cutover happens when Track B lands.

Date: 2026-05-09.

---

## ADR-004 — Country sequence: DE → CA → US

**Decision.** Per-country localization ships in this order: Germany, then Canada, then United States.

**Why.** Three signals point the same direction (research note 03):

- **DE** has a stable, well-documented compliance footprint (SKR03/SKR04 since the 1980s; HGB/GoBD; XRechnung/Factur-X via Mustang). High one-time effort, very low annual maintenance.
- **CA** is roughly equivalent in shape: federal GST + 4 HST provinces + 3 PST/RST provinces + Quebec QST = ~12 jurisdictions. CRA publishes XML schemas openly. Validates the multi-jurisdiction tax model with a tractable number of rules. ~1.5-2× DE effort.
- **US** has the easiest accounting model (no GAAP requirement for SMBs, no GoBD, no e-invoicing mandate, QBO-style default chart) but ~11,000 sales-tax jurisdictions and 50 different filing return formats. Going DE→US first would force the sales-tax architecture before validating it on Canada's smaller surface.

**Alternatives.** DE→US (rejected: forces commitment to either a paid Avalara dep day-one or an SST-only US story that excludes CA/TX/NY/FL/IL). CA-first (rejected: smaller market, no e-invoicing differentiator, beleg's existing user base is German-speaking).

Date: 2026-05-09.

---

## ADR-005 — `tax-provider` protocol from day 1

**Decision.** The kernel defines `kontor.tax-provider/TaxProvider` protocol as a first-class abstraction. It accepts (transaction context, partner, line items) and returns the tax postings to attach. Three implementations ship:

1. **Static-table provider** — for DE, CA, and any country whose tax rules fit a finite EDN table. The default and most common case.
2. **CSV-feeder provider** — quarterly-refreshed Streamlined Sales Tax (SST) CSVs for the 24 SST member US states. Free public data.
3. **External-API adapter shape** — a thin contract that customer-supplied Avalara/TaxJar/TaxCloud API keys plug into. We ship the contract; customers ship the keys.

**Why.** US sales tax is uniquely uncapturable in static data (~11,000 jurisdictions, weekly rate changes, product-taxability matrices). Other countries' tax engines fit in a few KB of EDN. A single abstraction lets the kernel handle both regimes without an internal switch on country.

**Implication.** **We do not bundle anyone's API key, ToS-restricted data, or rate tables that we lack the right to redistribute.** Avalara/TaxJar adapters are scaffolding; customers register themselves. (Research note 03 flags the redistribution restriction explicitly.)

**Implication 2.** **Recoverable vs non-recoverable** is a kernel-level property of a tax (`:tax/recoverable?` boolean), not a provider concern. VAT/HST/QST/GST = recoverable. PST/RST/US sales tax = non-recoverable. The repartition machinery posts both correctly.

Date: 2026-05-09.

---

## ADR-006 — Per-country localization modules with their own licenses

**Decision.** Each `kontor-l10n-<cc>` module is its own artifact with a license matching its data sources:

- `kontor-l10n-de` — **GPLv3** if SKR03/SKR04 facts are sourced from Tryton or GnuCash. LGPLv3 if sourced from Odoo. Pick once, document.
- `kontor-l10n-ca` — **EPL-1.0** (CRA-published facts are public; no third-party data dependency).
- `kontor-l10n-us` — **EPL-1.0** for the kernel pieces; **SST data files retain SST's public terms**; Avalara/TaxJar adapters carry no API data.

**Why.** Honest licensing. The GPLv3 vs LGPLv3 decision propagates to consumers, so it must be visible at the artifact boundary. Bundling everything under EPL would be a license-laundering claim we cannot defend.

**Implication.** A consumer who wants German support pulls in `kontor-l10n-de` and accepts its license terms (likely GPLv3). The kernel itself stays EPL-1.0.

**Open question.** Whether the EU sui generis database right (Directive 96/9/EC) attaches to a re-keyed EDN projection of an Odoo CSV. Conservative read: facts (account number 1200 → "Bank") are not protectable; *selection and arrangement* might be. Re-encoding with our own structure + crediting source is the practical compromise.

Date: 2026-05-09.

---

## ADR-007 — Purge is a recorded commit, not a violation

**Decision.** We do not attempt to *prevent* `:db/purge` of posted entries. Instead:

- A `:posting/posted-at` marker on a posting indicates it has been posted.
- Application middleware refuses **silent retract** (`[:db/retract …]`) of any datom on a posted entity.
- An **explicit purge** (`[:db/purge …]`) of a posted entity is permitted, but **it is itself a commit** in datahike's commit DAG. The chain self-documents that a purge happened, when, and by whom.
- The audit story is therefore: "at commit X, posted entity Y existed with these values; at commit X+n, a purge commit removed it." Auditors reconstruct from the commit graph.

**Why.** The user pointed out that purging in datahike is itself a commit (not a destructive operation outside the chain). Right-to-erasure (GDPR Art. 17, equivalent in CCPA, etc.) is a real legal obligation — preventing all deletion would create non-compliance with a different regime. The correct invariant is *traceability of changes*, not *immutability of data*.

**Why this is better than Odoo.** Odoo's `inalterable_hash` chain prevents row mutation but provides no story for legally-mandated deletion. A right-to-erasure request against Odoo books either creates a hash-chain break (auditor sees corruption) or requires database surgery outside Odoo (auditor sees nothing). Datahike's model — where deletion is a recorded commit — handles both regulatory regimes coherently.

**What's still on us.** The middleware enforcement that refuses silent retracts and requires `:db/purge` for posted entries lives in `kontor/sealing.clj`. This is policy, not mechanism; cleanly testable.

Date: 2026-05-09.

---

## ADR-008 — Bitemporal modeling: lean, not full

**Decision (revised 2026-05-10 per [research note 08](research/08-bitemporality-evidence.md)).** Schema retains a single valid-time anchor per posting and a single transaction-time snapshot axis on reads — not a full bitemporal model.

Specifically:
- **Transaction time** = `:db/txInstant` (datahike-supplied, free). Read helpers accept `:as-of-snapshot` to slice it.
- **Valid time** = `:posting/valid-from` on every posting; `:transaction/effective-date` on every header. **No** `:posting/valid-to`.
- **No** `:posting/temporal-key` composite tuple. The bitemporal index it indexed has no real workload (`:valid-to` was never read by anything in production paths).
- Reads take `:as-of-valid` as an *optional* filter (defaults to today); `:as-of-snapshot` is also optional and is the audit-trail feature.

**Why the revision.** Research note 08 surveyed XTDB, Datomic (Nubank), Snowflake-time-travel, and SMB products (QuickBooks, Xero, NetSuite). Findings:
- Vendor marketing for bitemporality cites the same two examples (FINRA trade reconstruction; Martin Fowler's payroll). No SMB accounting product carries a bitemporal model.
- The dominant industry pattern for prior-period corrections is **reverse-and-repost in the current open period**, not preserve-the-as-filed-view-forever (Nubank's stated approach; QBO/Xero workflow guidance; Big Four restatement guidance for "little r" revisions).
- "Big R" IAS 8 restatements explicitly RESTATE prior periods rather than preserve them — the opposite of bitemporal preservation.
- The cost we were paying — `:valid-to` + tuple + 10-20% extra discipline in every read — wasn't being repaid.

What we keep:
- `:posting/valid-from` is cheap, natural for backdated invoices, useful at all sizes.
- `:as-of-snapshot` (datahike `d/as-of`) is free and answers "what did the books look like as filed on date X" — the genuine audit-trail demand.

**Migration.** Forward-compatible. Schema attribute removed from kernel; the dropped attrs were unused by any kernel code outside their own definition + balance/ledger filters.

**If a regulated-fintech use case appears later.** Restoring `:posting/valid-to` is a 1-attr schema add — the design can grow back without retroactive migration of existing rows (we'd interpret "absent valid-to" as "open-ended", which is what the prior code did anyway).

Date: 2026-05-09 (initial), revised 2026-05-10.

---

## ADR-009 — Beancount round-trip as the canonical correctness test

**Decision.** Phase 1 acceptance criterion includes a **Beancount round-trip test**: parse a representative `.beancount` file, transact into datahike, dump back to Beancount syntax, byte-diff against the original (modulo whitespace/comment normalization).

**Why.** Beancount is the most-respected open-source double-entry implementation. Round-tripping its examples pins our semantics against a known-correct reference: postings sum to zero, balance assertions hold, multi-currency lots round-trip, account hierarchies survive. Cheaper and more rigorous than writing our own correctness suite from scratch.

**Implication.** A Beancount parser is a deferred but expected dependency — likely a small `instaparse` grammar — to be written in Phase 1. Beancount's grammar is small (~200 lines).

Date: 2026-05-09.

---

## ADR-010 — Scope boundaries (what we are not)

**Decision.** `kontor` is explicitly NOT:

- **An ERP.** No CRM, no inventory, no MRP, no HR, no project management. Beleg owns customer/offer/invoice. simmis or other consumers own anything else.
- **A UI.** No web framework, no view layer, no HTML rendering. Consumers (beleg HTMX, simmis Replicant) build their own.
- **A Peppol Access Point.** Defer until a customer needs it. Mustang covers Factur-X without Peppol AP. phax/peppol-commons exists for when we need it.
- **A US sales tax engine.** We provide the protocol; customers integrate Avalara/TaxJar/TaxCloud.
- **A bank reconciliation product.** We provide CAMT.053 import + the reconciliation primitives; matching heuristics are a separate product layer.
- **A clean-room reimplementation of Odoo.** Odoo's `account.move` is studied as a reference but not translated. PTA semantics + datahike idioms are our base.

**Why.** Each of these is a multi-year project on its own. Trying to be all of them is what makes Odoo Odoo.

Date: 2026-05-09.

---

## ADR-011 — Hybrid invariant strategy: `datopia/invariant` + middleware

**Decision.** Validation splits along the *state vs behavior* axis.

| Class | Examples | Where |
|---|---|---|
| **State-shape invariants** | sum-to-zero per commodity; account must be `:account/active`; posting commodity matches account commodity | `datopia/invariant` library |
| **Behavior / lifecycle constraints** | sealing (no silent retract of posted entries); period locking; transaction state machine (draft → posted → cancelled, no skip / regress); tax-repartition sums to 100% per (tax, doc-type, type) | hand-rolled middleware in `validation.clj` / `sealing.clj` / `period.clj` / `state_machine.clj` |

**Why.** State predicates are exactly what `invariant` was designed for — declarative, datalog-native, REPL-testable. Behavior constraints (especially sealing) are not expressible: the library's four-DB-snapshot model sees state diffs, not retract-vs-add or transaction context. Middleware predicates also read more clearly to auditors who don't know Datalog.

**Followups.**
- Bump `datopia/invariant`'s datahike pin from `0.6.1595` to `0.8.x`. The library is currently at risk of bitrot. We maintain it.
- Add invariant resource files in `resources/invariants/`.
- Wire `(install-invariants! conn)` next to `(schema/install! conn)` in `core.clj`.

**Alternatives.** Use `invariant` for *everything* (rejected: sealing structurally not expressible; state-machine awkward). Hand-roll *everything* (rejected: redundant with library that exists and is locally maintained; loses declarative testability for the state predicates).

**Status.** Adopted. Implementation deferred to Phase 1 closure (after the Money + posting + sum-to-zero slice lands).

Date: 2026-05-09. Per [research note 04](research/04-invariant-library-fit.md).

---

## ADR-012 — Analytic accounts (cost / profit centers) added to scope

**Decision.** The kernel will support analytic accounting (cost-center / profit-center / project dimension) via a separate `:analytic/*` namespace, layered on top of the core posting model. Each posting may carry zero or more `:analytic/distributions` (refs to analytic-distribution entities, each a `(plan, account, percent)`). This mirrors Odoo's `account_analytic_distribution_model` shape.

**Why.** The openclaw exploratory work already used `:cost-center/*` and `:profit-center/*` namespaces; they are real, well-understood concepts in SMB accounting (German Kostenrechnung; Canadian SR&ED project tracking; US job costing). Postponing past Phase 1 and bolting on later would create awkward retrofitting in the posting model. Adding now while the schema is fluid costs ~3-5 attributes.

**Implication.** Phase 1 schema gains: `:analytic-plan/*`, `:analytic-account/*`, `:analytic-distribution/*`. Postings gain `:posting/analytic-distributions` (cardinality-many ref). The reporting/aggregation engine that consumes analytic distributions ships in Phase 1.5 alongside the declarative report engine.

**Scope.** **Schema only in Phase 1.** Distribution-aware reports come in Phase 1.5. Cost allocation algorithms (overhead allocation, multi-step distribution) are deferred to a future phase or remain a consumer-app concern.

Date: 2026-05-09. Per [research note 06](research/06-openclaw-extraction-inventory.md).

---

## ADR-013 — Money type: BigDecimal + commodity tag, HALF-EVEN default

**Decision.** Money is represented by an immutable `Money` record containing a `BigDecimal` amount and a commodity ref/keyword. Arithmetic is commodity-checked: cross-commodity ops throw. Default rounding mode is `HALF_EVEN` (banker's rounding); a `HALF_UP` mode is available where regulators require it (e.g. some VAT computations).

**Why.** XTDB's investigation (research note 05) confirmed that BigDecimal is the right primitive across the JVM ecosystem; there is no widely-adopted JVM `Money` type with currency-checked arithmetic that we'd want to adopt as a dependency. Joda-Money exists but is heavier than needed. A small Clojure record gives us:
- Compile-time clarity that monetary values aren't naked `BigDecimal`s.
- Currency-mismatch errors at the *operation* site, not at posting time.
- Easy serialization to/from datahike (decompose into the existing `:posting/amount` + `:posting/commodity` attribute pair).
- Configurable per-commodity precision via the `:commodity/precision` attribute.

**Why HALF-EVEN default.** It's the IEEE 754 default, the ISO/GAAP recommended rounding mode for financial reporting (avoids systematic bias over many transactions), and it's what BigDecimal defaults to. HALF-UP exists for regulators who specifically mandate it (German VAT does in some edge cases; we model the override per-tax via a `:tax/rounding-mode` attribute when added).

**Alternatives.** Naked `BigDecimal` + parallel `:commodity` reference (rejected: every arithmetic site has to pass both, easy to forget; common bugs class). Joda-Money / `clojure.java-time.Money` (rejected: extra dep, doesn't add enough). Bigint cents (rejected: precision insufficient for FX rates, multi-fractional-digit commodities like crypto).

**Implication.** `src/kontor/money.clj` ships in Phase 1's first slice. Every kernel function that takes a monetary amount accepts a `Money`; raw BigDecimals are a smell.

Date: 2026-05-09.

---

## ADR-014 — Period model: hard/soft locks + DE special periods

**Decision (2026-05-10, per [research note 07](research/07-period-semantics-comparison.md)).** Replace the single `:period/locked-at` lock state with two:

- `:period/locked-at` — **soft close**. Reopen-able via `period/reopen!`; the reopen is itself a recorded commit. Refuses new postings whose `:posting/valid-from` falls in the range. Matches Odoo's `period_lock_date` / `tax_lock_date` / `sale_lock_date` / `purchase_lock_date`, NetSuite's "Locked", Xero's "Period Lock Date".
- `:period/sealed-at` — **hard close**. **Monotone** (the date can only move forward); **irrevocable** (no `period/reopen!` path); refuses any retract on the period entity. Matches Odoo's `hard_lock_date`, Xero's "End of Year Lock Date", Sage's "locked", NetSuite's "Closed".

Plus a **special-period flag** required for DE compliance:

- `:period/adjustment? boolean` — when true, the period overlaps the same date range as a normal period and represents a year-end-adjustment bucket (SAP's special periods 13–16). Multiple periods may share an effective-date range; the discriminator decides which one a posting routes into via the new `:posting/period-tag` attribute (defaults to nil = normal period).

Plus a **pre-close validation hook**:

- `period/close!` calls a pluggable `pre-close-checks` fn before setting `:period/locked-at`. Default checks: no `:transaction/state :draft` postings in range, no unreconciled bank lines, trial-balance-zero per commodity. Refuse close if any check fails.

**Why.** Every accounting system aimed at accountants distinguishes soft-vs-hard lock (Odoo, NetSuite, Xero, Intacct); QuickBooks is the lone exception and its docs apologise for it. DE compliance specifically demands period-13-16 semantics for HGB year-end adjustments — without them, DATEV / SKR03 / SKR04 export in Phase 2-DE will produce books that fail audit review.

**Implication.**
- `validation.clj` gains a check: any tx that would touch a `:period/sealed-at`-marked entity (retract, period-attribute write) is rejected.
- `period/close!` adds the pre-check hook (consumers can override).
- `period/reopen!` rejects sealed periods.
- Tests cover: close-without-draft passes; close-with-draft fails; reopen-soft works; reopen-hard fails; period-13 posting routes correctly.

**Deferred.** Per-user lock exception (Odoo `account.lock_exception`) and fiscal-year first-class entity. Both come in Phase 3 when reporting needs them.

Date: 2026-05-10. Per [research note 07](research/07-period-semantics-comparison.md).

---

## Decisions deferred (open)

The following choices are NOT yet locked. Update this section as we converge.

- **Beancount parser implementation strategy.** instaparse vs hand-rolled vs Java port (Beancount has a Java port: `beancount-java`). Defer to first Beancount integration test.
- **Whether the kernel ships a basic `account-tag` engine (the join between accounts and tax-report boxes) in Phase 1 or defers to Phase 1.5.** Currently: include in Phase 1 because the tax engine needs tags to express VAT-report shapes.
- **Concrete commodity / lot model.** PTA-style (commodity is a string, lot is `(date, cost, label)`) vs richer (commodity is an entity with metadata, lot is a refed entity). Trade-off: PTA-style is lighter, richer is more queryable. Lean PTA-style for Phase 1.
- **Whether `period.locked-at` triggers a datahike branch automatically (per-fiscal-year branches as the persistence pattern), or stays as an attribute.** Fork-per-period is elegant but we want to feel out the ergonomics first.
