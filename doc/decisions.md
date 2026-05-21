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

## ADR-005 — `tax-provider` protocol from day 1 — **SUPERSEDED by ADR-071 (2026-05-17)**

**Original decision (2026-05-09).** The kernel defines `kontor.tax-provider/TaxProvider` protocol as a first-class abstraction. It accepts (transaction context, partner, line items) and returns the tax postings to attach. Three implementations ship: static-table, SST-CSV feeder, external-API adapter shape.

**Why superseded.** Research note 70 found:
- `StaticTableProvider/resolve-taxes` returned literal `[]`; **zero callers anywhere** in `src/` or `test/`. The protocol was dead code throughout.
- The pattern that actually ran in production: `posting-builder` — a per-country function the kernel invoked from `kontor.document.invoice/send!`. Only `l10n-de` implemented it; every other l10n module's invoices produced no tax postings.
- The original signature `(resolve-taxes context → vector-of-postings)` conflated *rate determination* with *posting expansion* — two concerns with different consumers (Avalara knows rates, not charts of accounts), different lifecycles (rate tables change weekly; chart-of-accounts rarely), and different test strategies.

**Two preserved invariants from the original ADR still hold:**
1. **We do not bundle anyone's API key, ToS-restricted data, or rate tables we lack the right to redistribute.** Avalara/TaxJar adapters remain scaffolding; customers register themselves. (Research note 03.)
2. **Recoverable vs non-recoverable** stays a tax-level property (`:tax/recoverable?` boolean), not a provider concern. VAT/HST/QST/GST = recoverable. PST/RST/US sales tax = non-recoverable. The repartition machinery posts both correctly.

**The `:tax/* :tax-rep/* :tax-group/*` schema attrs (`schema.clj:1244-1393`) are preserved** — they back the static-table impl of the new `TaxRateProvider` (ADR-071).

See ADR-071 for the replacement design.

Date: 2026-05-09 (superseded 2026-05-17).

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

> **Superseded by [ADR-048](#adr-048--normalize-valid-time-to-txvalid-from-drop-postingvalid-from).** The per-posting valid-from anchor described below was removed; valid-time now lives on the writing tx via `kontor.bitemporal`'s `:tx/valid-from`. The semantics (lean over full bitemporality, reverse-and-repost over valid-time supersession) remain unchanged; only the storage location moved. Read this ADR for the rationale; refer to ADR-048 for the current implementation.

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

## ADR-015 — CA tax-filing architecture: three rings, year-versioned forms, cert deferred

**Decision (2026-05-10, per the CA filing research synthesis).** Going depth-first on Canadian filings, the architecture is:

### a) Three concentric rings

```
  +-----------------------------+
  |  Transmission (cert-gated)  |  NETFILE / EFILE / CIF — one ns, optional, deferred
  +-----------------------------+
  |  Renderer (never cert-gated)|  CRA fillable PDFs, info-return XML (T4/T5/T5018), GST34 sheet
  +-----------------------------+
  |  Kernel (model + compute)   |  Form records + pure schedule fns, year-tagged
  +-----------------------------+
              kontor kernel
```

The *kernel* ring is pure: form records (T1 jacket, S1, S3, T2125, BC428, …) and pure compute functions over them and the underlying postings. The *renderer* ring is downstream-only: it produces filable artifacts but contains no calculation logic. The *transmission* ring is the only ring that ever needs CRA certification.

### b) Year-versioned form namespaces

Tax forms change yearly. We encode that drift via namespace, not in-file switches:

```
kontor.l10n-ca.y2024.t1
kontor.l10n-ca.y2024.s1
kontor.l10n-ca.y2024.t2125
kontor.l10n-ca.y2024.bc428
kontor.l10n-ca.y2025.t1       ; ... etc.
```

Each year-namespace is immutable once its tax year is filed. Backporting a fix to a closed-year form is allowed but must preserve the assessed numbers.

### c) Quebec is out of scope for v1

Revenu Québec runs its own intake systems (ImpôtNet, RL-slip XML transmitter, TED, CO-17), its own authorization regime separate from CRA cert, and ~50+ TP1 schedules separate from federal. Doing Quebec roughly doubles the project. Defer until a Quebec consumer surfaces. Restoration path: a `kontor.l10n-qc/` sibling module; no kernel changes needed.

### d) CRA NETFILE / EFILE / CIF certification deferred

Cert is achievable solo (StudioTax, GenuTax, AdvTax, FutureTax, MyTaxExpress and TaxFreeway are existence proofs of small-vendor cert) but the work is bounded and seasonal (Nov–Feb each year). We defer it behind a single transmission-ring namespace. The kernel + renderer rings deliver real value today via:

- **Paper file** — fill CRA's official fillable PDFs, user signs and mails. Sanctioned for all years.
- **Info-return XML** (T4 / T5 / T5018 / T2202 / etc.) — public XSDs, Internet File Transfer + Web Access Code, no software cert.
- **GST/HST values** — transcribed by the user into CRA's NETFILE web form (the form requires no software cert).

When we cert later, the new transmission code plugs in alongside these without altering rings 1–2.

### e) PDF library: Apache PDFBox

Filing artifacts in the renderer ring fill CRA-published AcroForm PDFs. We use **Apache PDFBox** (Apache-2.0, JVM-native, no native binary required). The Haskell prior-art project (`blamario/canadian-income-tax`, GPL-3) shells out to `pdftk`; we don't because JVM has integrated options and we want to avoid a native-binary install step. PDFBox is a single Maven coordinate, license-compatible with EPL.

### f) Reference oracle, not source

`blamario/canadian-income-tax` (Haskell, GPL-3) is the cleanest open implementation of T1 federal + BC/ON/AB/MB provincial calculations. We use it as a numerical oracle (run it, compare numbers against ours) but **do not copy any code or test fixtures** — GPL-3 is incompatible with our EPL-1.0. All `kontor` calculations are independently derived from CRA's published guides (5000-G, T4002, etc.) and the Income Tax Act / Regulations.

### Why this shape

The research synthesis established two architectural facts that drive everything else:

1. **The CA filing surface splits into a cert-required half (T1, T2, GIFT, AFR) and a non-cert half (info-return XML, GST/HST web-form values, paper T1).** Both halves share the same kernel computation; they diverge only at the transmission layer.
2. **Form changes are annual, not architectural.** Year-versioned namespaces are the cheapest way to absorb threshold/line/schedule drift without retroactive edits to filed-year code.

### Alternatives considered

- *One flat `kontor.l10n-ca.t1` ns with year branches inside.* Rejected: every form-line change in any year forces edits to a file that other years' tests pin against. Year-tagged namespaces are cheaper to maintain and easier to audit.
- *`.tax`-file generation for upload to a commercial certified tool.* Rejected: commercial save formats (`.tax2024`, `.u15`, etc.) are internal save files, not interchange formats. The only `.tax` extension CRA-side is GIFT for GST/HST, which is itself cert-gated.
- *Pursue NETFILE cert in this iteration.* Rejected: would block all delivery on a Nov–Feb seasonal process for one filer's benefit. Cert is more valuable once kernel + renderer are mature.
- *Generic "tax rule engine" abstracting all forms.* Rejected: every working tool (open and closed) ends up with per-form modules because forms don't share structure regularly enough to abstract well. The shared substrate is at the level below — Money arithmetic, postings, period model — which `kontor` already provides.

### Implications

- New CA file layout (additions only — existing files unchanged):
  - `modules/l10n-ca/src/kontor/l10n_ca/y2024/{t1,s1,s3,s4,s7,s8,s9,s11,t2125,s13,bc428,bc479}.clj`
  - `modules/l10n-ca/src/kontor/l10n_ca/gst_hst.clj` — filing-complete (line-by-line GST34-2 values, period detection, transcription sheet)
  - `modules/l10n-ca/src/kontor/l10n_ca/noa.clj` — CRA NoA PDF parser → kernel opening-balance facts
  - `modules/l10n-ca/src/kontor/l10n_ca/pdf.clj` — PDFBox AcroForm renderer (shared helpers)
  - `modules/l10n-ca/src/kontor/l10n_ca/xml/{t4,t5,t5018,t619}.clj` — info-return XML against published XSDs
- Existing `returns.clj` evolves into the GST/HST filing module; the QST/BC-PST functions stay but are documented as preparatory (Quebec is deferred; BC PST is in scope as a separate provincial filing the BC self-employed registrant may need).
- New dep: `org.apache.pdfbox/pdfbox` (renderer ring only — kernel ring remains datahike-only).
- Annual maintenance budget (per the research synthesis): ~1–2 person-months/year per tax-year, concentrated Dec–Feb, for the non-cert build. Cert adds ~1–2 months and pins the calendar.

Date: 2026-05-10. Per the CA filing research synthesis in conversation 2026-05-10.

---

## ADR-016 — Multi-tax-per-line: `:posting/tax-breakdown` as a structured collection

**Decision (2026-05-10, per the CN/JP/BR/AU research synthesis).** Add a per-posting attribute `:posting/tax-breakdown` carrying a cardinality-many ref to small `:tax-application/*` entities. Each application captures one (tax, base, amount, tag-set, compound-on) tuple. The breakdown is **parallel to**, not a replacement for, the existing `:posting/taxes-applied` + generated `:tax`-display-type postings. The breakdown is *intent* (what the country module computed); the generated tax-line postings are *bookkeeping* (what hit the ledger).

### Why a new collection (the current schema looks like it covers this)

`:posting/taxes-applied` (cardinality-many ref to `:tax` entities) already records "this product line was taxed by these N taxes." For DE 19% + DE 7% mixed-rate invoices and CA GST+PST stacking, this is enough. The cases where it isn't:

- **Brazil five-tax stack on one line** (ICMS + IPI + PIS + COFINS + ISS). The base for ICMS is "net + IPI" in some scenarios (`:tax/include-base-amount true` for IPI); the base for PIS/COFINS is "net + ICMS + IPI." Today, recovering each tax's actual base requires reconstructing the compound chain by walking `:posting/tax-rep → :tax-rep/tax → :tax/include-base-amount` for every tax-line, in the right order. With a breakdown, the answer is one entity per (line, tax): `{:tax-application/tax X :tax-application/base 1234.56M :tax-application/amount 234.56M}` — no re-derivation.
- **Statutory reporting** (BR SPED EFD-ICMS/IPI block C100, DE UStVA box 81 / 86) wants the per-tax base directly. Querying the breakdown is one Datalog clause; reconstructing it from posted tax-lines is several joins + an ordering invariant.
- **Audit defensibility**: regulators ask "what base did you compute ICMS-ST against on invoice X line 3?" The breakdown answers in one row; the current model forces an inferred-from-the-chain answer.

### Schema

```clojure
;; A tax application: one tax computed against one base, on one posting.
:tax-application/posting        ref one        ; back to the :product posting
:tax-application/tax            ref one        ; the :tax entity
:tax-application/base           bigdec one     ; the base this tax saw
:tax-application/amount         bigdec one     ; the tax computed
:tax-application/tags           ref many       ; account-tag refs for reporting
:tax-application/compound-on    ref many       ; other :tax entities whose
                                               ;   amount was added into :base
:tax-application/sequence       long one       ; order within the chain

;; Posting carries the collection.
:posting/tax-breakdown          ref many       ; back-refed; the breakdown
                                               ;   entities point to the
                                               ;   posting via /posting attr
```

`:tax-application` is a small entity (5 attrs), one per (line, tax) pairing. A BR five-tax line creates 5 of them. A DE single-VAT line creates 1. A CA BC sale with GST+PST creates 2. The cardinality-many `:posting/tax-breakdown` ref keeps query-side ergonomics: one pull on the posting gets the full breakdown.

### Worked examples

**JP — dual-rate invoice (10% on goods, 8% on food)**

```clojure
;; Product posting (debit AR): 11,080 (10,000 net + 1,080 tax)
;; Two product lines? Two postings. One per rate.

;; Goods line: 1,000 @ 10%
{:posting/account [:account/code "AR"]
 :posting/amount 1100M  :posting/commodity :JPY
 :posting/display-type :product
 :posting/tax-breakdown
 [{:tax-application/tax [:tax/code "JP-JCT-10"]
   :tax-application/base 1000M
   :tax-application/amount 100M
   :tax-application/tags [[:account-tag/name "jp-line-1-10pct"]]}]}

;; Food line: 1,000 @ 8%
{:posting/account [:account/code "AR"]
 :posting/amount 1080M  :posting/commodity :JPY
 :posting/display-type :product
 :posting/tax-breakdown
 [{:tax-application/tax [:tax/code "JP-JCT-8"]
   :tax-application/base 1000M
   :tax-application/amount 80M
   :tax-application/tags [[:account-tag/name "jp-line-1-8pct"]]}]}
```

**BR — five-tax line (R$ 1,000 net intrastate goods sale, SP→SP, ICMS 18% + IPI 10% + PIS 1.65% + COFINS 7.6% + ISS N/A for goods)**

```clojure
{:posting/account [:account/code "AR"]
 :posting/amount 1382.50M
 :posting/commodity :BRL
 :posting/display-type :product
 :posting/tax-breakdown
 [{:tax-application/tax [:tax/code "BR-IPI-10"]
   :tax-application/base 1000.00M     ; net
   :tax-application/amount 100.00M
   :tax-application/sequence 1
   :tax-application/tags [[:account-tag/name "br-ipi"]]}

  {:tax-application/tax [:tax/code "BR-ICMS-SP-18"]
   :tax-application/base 1100.00M     ; net + IPI ("by inside")
   :tax-application/amount 198.00M
   :tax-application/sequence 2
   :tax-application/compound-on [[:tax/code "BR-IPI-10"]]
   :tax-application/tags [[:account-tag/name "br-icms-sp"]]}

  {:tax-application/tax [:tax/code "BR-PIS-1.65"]
   :tax-application/base 1100.00M     ; net + IPI
   :tax-application/amount 18.15M
   :tax-application/sequence 3
   :tax-application/compound-on [[:tax/code "BR-IPI-10"]]
   :tax-application/tags [[:account-tag/name "br-pis"]]}

  {:tax-application/tax [:tax/code "BR-COFINS-7.6"]
   :tax-application/base 1100.00M
   :tax-application/amount 83.60M
   :tax-application/sequence 4
   :tax-application/compound-on [[:tax/code "BR-IPI-10"]]
   :tax-application/tags [[:account-tag/name "br-cofins"]]}]}
```

The five generated `:tax`-display-type postings (each balancing against its respective tax-payable account) are unchanged. They're the bookkeeping; the breakdown is the model.

**DE — §13b reverse charge (intra-EU service, 1,000 net, 19% notional)**

```clojure
;; Single product line; the breakdown captures both legs of the
;; reverse-charge as one tax-application entity (acquirer self-assesses).
{:posting/account [:account/code "6320"]  ; service expense
 :posting/amount 1000M  :posting/commodity :EUR
 :posting/display-type :product
 :posting/tax-breakdown
 [{:tax-application/tax [:tax/code "DE-VAT-13b-19"]
   :tax-application/base 1000M
   :tax-application/amount 190M
   :tax-application/tags [[:account-tag/name "de-ust-46-base"]
                          [:account-tag/name "de-ust-66-input"]]}]}
```

This is where the breakdown earns its keep for DE too: today, reverse-charge produces two tax-line postings (output liability + input asset, both at 190) that *report* to two different UStVA boxes (line 46 base, line 66 input). With the breakdown, the relationship "both legs come from the same §13b application" is explicit instead of inferred from co-occurrence in the transaction.

### Posting-builder integration

`posting/build-transaction` gains an optional step: if a `:product` posting carries `:posting/tax-breakdown`, the builder generates the matching `:tax`-display-type postings automatically (one per application, debiting/crediting per the tax's repartition lines), AND inserts the back-refs (`:tax-application/posting`). The breakdown remains the single source of truth for the country module; the postings remain the single source of truth for the ledger.

### Alternatives

- *Keep current model, document the reconstruction.* Rejected: putting reporting load on every consumer is the wrong incentive; auditors specifically need direct retrieval; BR SPED block C170 wants per-tax-per-item base/amount as a flat field.
- *Replace `:posting/taxes-applied` entirely with `:posting/tax-breakdown`.* Rejected: existing CA/DE code uses `:posting/taxes-applied` and that's still the right shape when you just want "which taxes touched this line." Keep both; the breakdown is the richer view.
- *Embed the breakdown as an EDN-string attribute.* Rejected: opaque to Datalog queries; defeats the whole point.

### Implications

- Schema additions: `:tax-application/*` (5 attrs) + `:posting/tax-breakdown` ref.
- `posting/build-transaction` gains breakdown-aware tax-line generation.
- Reports gain a fast path: `[:find ?app ?base ?amount :where [?p :posting/tax-breakdown ?app] [?app :tax-application/tax [:tax/code "BR-ICMS-SP-18"]] [?app :tax-application/base ?base] [?app :tax-application/amount ?amount]]`.
- Existing CA/DE tests should pass unchanged (the breakdown is optional; absent on legacy postings).

Date: 2026-05-10.

---

## ADR-017 — `EInvoiceProvider` protocol

**Decision (2026-05-10).** Define `kontor.einvoice-provider/EInvoiceProvider` protocol as a sibling abstraction to `TaxProvider` (ADR-005). Three implementations ship: a **PureXmlProvider** (no transmission — produces an XML payload the caller hands off), a **PeppolProvider** (transmits via a configured Peppol access point), and an **AttestingProvider** **scaffold** (the shape that BR/CN partner adapters fit into). Customer-supplied API keys for production attestation services (Sovos LATAM, Avalara LATAM, TecnoSpeed, Aisino, Baiwang) are *not* bundled.

### Why

Every major ERP separates e-invoicing from the ledger (SAP extracted NF-e from CVB into SAP NFE and then SAP DRC; NetSuite ships a Golden Tax *API* not a connector; Microsoft Dynamics is migrating to the Globalization Studio e-invoicing service). The pattern is universal because:

- **E-invoice formats are jurisdiction-specific** (UBL PINT JP vs UBL PINT A-NZ vs NF-e 4.0 XML vs e-fapiao OFD/XML vs Factur-X). The kernel cannot ship them all.
- **Transmission infrastructure is jurisdiction-specific** (Peppol access-point network in AU/JP; SEFAZ web services in BR; STA platform in CN; KoSIT XRechnung gateway in DE). The kernel cannot operate any of them.
- **Attestation is the cert-gated step** for BR + CN. ICP-Brasil certs sign NF-e XMLs before SEFAZ accepts them. STA-platform whitelisting controls fapiao issuance. These are partner concerns by definition.

The kernel ships the **invoice envelope** (a content document that downstream emitters serialize into the country format) and the protocol seam. Concrete emitters/transmitters plug in per country.

### Protocol shape

```clojure
(defprotocol EInvoiceProvider
  (envelope-format
    [this]
    "Return a keyword identifying the format this provider emits.
     E.g. :peppol/pint-jp, :peppol/pint-anz, :br/nfe-4.0, :cn/e-fapiao,
     :de/xrechnung, :de/factur-x.")

  (emit
    [this invoice]
    "Given a :invoice entity (already posted in the kernel), produce the
     wire artifact for this format. Returns
       {:einvoice/format <keyword>
        :einvoice/payload <string-or-bytes>
        :einvoice/content-type <mime-string>
        :einvoice/intended-for :keep-on-file | :transmit | :clearance}
     The kernel does not interpret the payload; downstream code that
     transmits or files it does.")

  (transmit!
    [this invoice payload]
    "Optional: send the payload to whatever endpoint the provider
     targets (Peppol AP, SEFAZ, STA platform). Returns
       {:einvoice/transmitted? boolean
        :einvoice/clearance-token <string-or-nil>  ; SEFAZ key, fapiao number
        :einvoice/raw-response <provider-shape>}
     Providers that don't transmit (PureXmlProvider) implement this as
     a no-op returning {:einvoice/transmitted? false}."))
```

### Worked examples

**JP — Peppol PINT JP, no transmission, just emit**

```clojure
(def jp-provider
  (->PureXmlProvider :peppol/pint-jp))

(let [{:einvoice/keys [payload]} (emit jp-provider some-invoice)]
  ;; payload is the UBL PINT JP XML string.
  ;; Caller sends via their own access point or keeps on file.
  (spit "INV-2026-0001.xml" payload))
```

**AU — Peppol with transmission**

```clojure
(def au-provider
  (->PeppolProvider {:profile :peppol/pint-anz
                     :access-point-endpoint "https://ap.example/as4"
                     :as4-creds (System/getenv "AP_AS4_CREDS")}))

(let [{:einvoice/keys [payload]} (emit au-provider some-invoice)
      result (transmit! au-provider some-invoice payload)]
  (when (:einvoice/transmitted? result)
    (mark-invoice-sent! conn some-invoice)))
```

**BR — NF-e with SEFAZ clearance (partner artifact)**

```clojure
;; This protocol impl is supplied by a partner module like
;; kontor-l10n-br-nfe (NOT in this repo). It signs the NF-e XML with
;; an ICP-Brasil cert and POSTs to the appropriate SEFAZ web service.
(def br-provider
  (->NFeAttestingProvider
   {:cert-keystore (System/getenv "ICP_BRASIL_KEYSTORE")
    :sefaz-uf "SP"            ; transmit to São Paulo SEFAZ
    :nfe-environment :prod}))

(let [{:einvoice/keys [payload]} (emit br-provider invoice)
      result (transmit! br-provider invoice payload)]
  (case (:cStat (:einvoice/raw-response result))
    "100" (do (assoc-clearance-token! invoice (:einvoice/clearance-token result))
              (mark-posted! invoice))
    "110" (mark-rejected! invoice (:einvoice/raw-response result))))
```

**CN — fapiao via STA platform (partner artifact)**

```clojure
;; Same shape. The clearance-token here is the e-fapiao number returned
;; by the STA's electronic invoice service platform.
(def cn-provider
  (->FapiaoAttestingProvider
   {:sta-platform-creds (System/getenv "STA_DIGITAL_ACCT_CREDS")
    :issuer-bn "91110000XXXXXXXXXX"}))

(let [{:einvoice/keys [payload]} (emit cn-provider invoice)
      result (transmit! cn-provider invoice payload)]
  (when (:einvoice/transmitted? result)
    (assoc-clearance-token! invoice (:einvoice/clearance-token result))))
```

### Alternatives

- *Hard-code per-country emitters as functions, not a protocol.* Rejected: BR/CN/DE/JP/AU/NZ/IT/MX/ES/IN — that's eight-plus country emitters; the protocol seam keeps consumers's code uniform regardless of jurisdiction.
- *Force a single XML format internally (UBL or EN16931).* Rejected: SEFAZ NF-e is its own schema not derivable from UBL; e-fapiao is OFD-based; the cross-format aspiration is what the EU + Peppol communities are still negotiating. We follow CRA's pragmatism: each format is its own pipeline.
- *Pull e-invoicing into the kernel as a first-class entity.* Rejected: the kernel already has `:invoice/factur-x-xml` and `:invoice/factur-x-pdf` from einvoice-de; these become *one* provider's emit-result. Generalizing them into a renderer ring artifact (per ADR-015) is the way.

### Implications

- New file: `src/kontor/einvoice_provider.clj` (protocol + PureXmlProvider).
- New file pattern: `modules/<l10n>/src/kontor/<l10n>/einvoice.clj` per country that implements the protocol.
- Existing `modules/einvoice-de` becomes a concrete implementation of `EInvoiceProvider` for `:de/factur-x` and `:de/xrechnung` formats.
- `:invoice/factur-x-xml` and `:invoice/factur-x-pdf` attributes generalize to `:invoice/einvoice-payload` (string) + `:invoice/einvoice-format` (keyword) + `:invoice/einvoice-pdf` (bytes, optional). The DE-specific attributes stay as aliases for backward compatibility for one release, then deprecate.
- BR/CN partner artifacts can be developed externally without forking the kernel.

Date: 2026-05-10.

---

## ADR-018 — Clearance-token lifecycle: `:pending-attestation` state + `:posting/clearance-token`

**Decision (2026-05-10).** Extend `:transaction/state` enum with `:pending-attestation`. Add `:posting/clearance-token` (string) and `:transaction/clearance-token` (string, mirrored for query ergonomics). The state machine becomes:

```
:draft → :pending-attestation → :posted → :cancelled
       ↘                       ↗
        :posted   (Peppol JP/AU/DE — no clearance step)
```

A transaction enters `:pending-attestation` when the country module's `EInvoiceProvider/transmit!` is called and the response is in-flight or rejected. It transitions to `:posted` only after a successful clearance token is received. **Sealing (ADR-007) does NOT apply during `:pending-attestation`** — the entry is not yet legally valid. The audit chain still records every state transition.

### Why

BR NF-e and CN fapiao both make an invoice "legally void" until the government accepts it (BR: SEFAZ returns `cStat=100` with a 44-digit access key; CN: STA platform issues an e-fapiao number after attestation). Today's `:draft → :posted` lifecycle has two problems for this:

1. **Premature sealing.** A `:posted` posting is sealed (ADR-007: no silent retract). But a BR invoice that SEFAZ rejects must be edited and retransmitted — the lifecycle needs an unsealed "submitted, awaiting response" state.
2. **Missing reference.** The clearance token is the legal identifier of the invoice (SEFAZ "chave de acesso", CN fapiao number). It must be stored on the entity, queryable, immutable once set. Without an explicit attribute, country modules invent ad-hoc fields, breaking the cohabitation invariant (ADR-002).

The state addition is small but lifecycle-critical. Most other ADRs key off `:posted-at`; `:pending-attestation` is the "I submitted, am waiting" middle state.

### Worked examples

**BR NF-e flow**

```
1. Country module builds breakdown + product line.
2. transition: nil → :draft
   posting-builder commits initial postings; nothing is sealed.
3. EInvoiceProvider/emit produces NF-e 4.0 XML.
4. EInvoiceProvider/transmit! POSTs to SEFAZ SP.
   transition: :draft → :pending-attestation
   :transaction/clearance-token = nil
   sealing middleware does NOT lock retract.
5. SEFAZ responds cStat=100, chave="35 1234 5678 90...".
   transition: :pending-attestation → :posted
   :transaction/clearance-token = "35123456789012345678901234567890123456789012345"
   :posting/posted-at + :transaction/posted-at set on each leg.
   sealing middleware now enforces.
6. (Optional) SEFAZ later rejects, owner cancels (NF-e "cancelamento").
   transition: :posted → :cancelled
   reversal transaction posted per ADR-007.
```

**CN fapiao flow** — same pattern, but the `clearance-token` is the 8-digit e-fapiao number (or 32-char QR signature for fully-digital e-fapiao), and the transmission is to the STA electronic-invoice service platform rather than SEFAZ.

**JP / AU / DE Peppol flow** — bypass `:pending-attestation` entirely. The protocol implementation transitions directly `:draft → :posted` because Peppol has no government clearance step (the four-corner model has no central attestation). The `:transaction/clearance-token` field is left nil.

### Sealing interaction

- `:pending-attestation` transactions are retractable (sealing middleware *does not* fire).
- `:posted` transactions are sealed (ADR-007 applies as today).
- `:cancelled` transactions are reversed via a new `:transaction/reverses`-linked entry, not by un-posting the original. Cancellation of a `:pending-attestation` transaction is direct deletion (the entry never had legal effect).

### Alternatives

- *Use `:transaction/posted-at = nil` as the "pending" signal without adding a state.* Rejected: confusing for queries (`(d/q '[:find ?t :where [?t :transaction/state :posted]])` is the natural reporting query; we want it to *exclude* in-flight entries naturally).
- *Make `:clearance-token` only on `:invoice`, not on `:transaction` / `:posting`.* Rejected: the kernel has invoice-less transactions too (bank-line-originated bookings); a journal entry might still need clearance metadata in non-invoice contexts; better to put the token at the posting level where it's universally available.

### Implications

- `validation.clj` state-machine invariant updates: add `:pending-attestation` and the legal transitions.
- `sealing.clj` middleware checks `(= :posted (:transaction/state ...))` before refusing retract — already so today; the only change is that `:pending-attestation` is not `:posted` so it falls through correctly.
- Reports filter by `:transaction/state :posted` and naturally exclude in-flight entries.
- Country modules that don't use clearance (CA, DE without §14 e-rechnung-mandate-2025, US, AT, FR, JP, AU) ignore the state entirely.

Date: 2026-05-10.

---

## ADR-019 — `:account/external-codes` keyed map (entity-modeled)

**Decision (2026-05-10).** Add a many-cardinality ref `:account/external-codes` pointing to small `:account-code/*` entities. Each entity carries `(:account-code/regulator :account-code/code :account-code/account)` with a composite identity tuple. Codes are looked up by `[:account-code/account :account-code/regulator]` pairs.

### Why

Today `:account/code` is a single string, treated as the country's canonical code (SKR04 1200 in DE, QBO 1010 in US, etc.). Two real-world cases force an extension:

1. **One account, multiple regulator codes.** A Brazilian company maps every analytical account to (a) its own internal code (b) a Plano de Contas Referencial code for RFB ECF filing (c) optionally a sector-specific reporting code. The "De/Para" (from/to) mapping is intrinsic to the BR filing process.
2. **One account, multiple accounting standards in parallel.** A multinational uses an internal management chart, then maps each account to (a) local statutory chart (b) IFRS group chart. SAP, NetSuite, and Dynamics all support this via account-code-mapping tables. For a kontor user filing CA T1 + DE UStVA + BR ECF on the same legal entity, the management chart maps to all three statutory charts simultaneously.

The single `:account/code` cannot represent these. Extending to a keyed map handles both and is the cleanest way to encode "this account answers to regulators X, Y, Z with codes a, b, c."

### Schema

```clojure
:account-code/account     ref one        ; back to the account
:account-code/regulator   keyword one    ; :br/plano-referencial, :cn/asbe,
                                          ;   :de/datev, :ifrs/group,
                                          ;   :us/qbo-tax, etc.
:account-code/code        string one     ; the code in that regulator's system
:account-code/identity    tuple [:account-code/account
                                 :account-code/regulator]  ; unique
                          unique-identity
:account-code/note        string one     ; optional explanation

:account/external-codes   ref many       ; from account → its codes
```

### Worked examples

**BR — De/Para mapping for ECF**

```clojure
(def acct-1234
  {:account/path "Assets:Bank:Main"
   :account/code "1.1.1.1.01"            ; user's internal code
   :account/external-codes
   [{:account-code/regulator :br/plano-referencial
     :account-code/code "L100A_1.01.01.02.01"
     :account-code/note "ECF L100A line 'Bancos Conta Movimento – No País'"}
    {:account-code/regulator :br/sped-contabil
     :account-code/code "111101"
     :account-code/note "ECD SPED block I050"}]})
```

**CN — ASBE mandated code**

```clojure
(def acct-cash-on-hand
  {:account/path "Assets:Cash"
   :account/code "1001"
   :account/external-codes
   [{:account-code/regulator :cn/asbe
     :account-code/code "1001"
     :account-code/note "ASBE 1001 库存现金 Cash on Hand"}]})
```

**DE + IFRS for a multinational**

```clojure
(def acct-sales-de
  {:account/path "Income:Sales:Germany"
   :account/code "4400"
   :account/external-codes
   [{:account-code/regulator :de/skr04
     :account-code/code "4400"}
    {:account-code/regulator :ifrs/group
     :account-code/code "REVENUE-EUROPE"
     :account-code/note "Maps to IFRS 15 revenue group for consolidation"}
    {:account-code/regulator :de/datev
     :account-code/code "8400"
     :account-code/note "DATEV-Konto (numeric scheme differs from SKR04 code)"}]})
```

### Alternatives

- *Store as an EDN map on `:account/external-codes` as a single string.* Rejected: opaque to Datalog; cannot index by regulator; cannot enforce uniqueness per (account, regulator) pair.
- *Use cardinality-many string attribute with `<regulator>:<code>` encoding.* Rejected: same drawbacks; ad-hoc string parsing everywhere.
- *Add `:account/<regulator>-code` attributes per regulator.* Rejected: requires schema changes for every new regulator; doesn't scale.
- *Use datahike's composite-tuple type for the (account, regulator, code) directly on `:account`.* Considered; the entity-per-mapping shape is cleaner because it admits the optional `:note` field without bloating `:account` itself, and queries by regulator are natural.

### Implications

- Schema additions: `:account-code/*` (5 attrs) + `:account/external-codes` ref.
- Existing `:account/code` stays (kernel-facing, not regulator-specific). Country modules that previously embedded their code as `:account/code` keep doing so for the dominant regulator; the multi-regulator case uses `:account/external-codes`.
- Reports that key off statutory codes look up via `[:account-code/account ?a :account-code/regulator :br/plano-referencial]`.
- A future ADR for IFRS consolidation rides this same mechanism (account → IFRS group mapping).

### Multi-entity / multi-company note

This ADR specifically does *not* introduce a `:company/*` namespace (multi-entity in one DB). One-DB-per-tenant remains per ADR-002 for v1. When multi-entity is added later (and it will be for large-co support), the same `:account-code` entity gets a `:account-code/company` ref so the same account can mean different things in different subsidiaries. The shape we've chosen accommodates that future without retrofitting.

Date: 2026-05-10.

### Addendum 2026-05-18 — `:account-tag/concept-iri` substrate seam for XBRL / filing taxonomies

A second axis of "this account / tag maps to an external standard's identifier" surfaced during the research pass on XBRL and accounting taxonomies (`doc/research/78-xbrl-and-accounting-taxonomies.md`). XBRL concept IRIs (e.g. `http://xbrl.ifrs.org/taxonomy/2024-03-27/ifrs-full#Revenue`) name a filing-taxonomy entry the same way `:account-code/regulator + :account-code/code` names a regulator's code.

**Decision (minimal seam, not full taxonomy ingest).** Add ONE optional schema attr on `:account-tag` rather than re-shaping `:account/external-codes`:

```clojure
:account-tag/concept-iri  string one indexed   ; optional XBRL / filing-taxonomy
                                                ;   concept IRI
```

Consumers who want their tags to map to XBRL concepts (the IFRS Foundation taxonomy, FASB US-GAAP, UK FRC, DE E-Bilanz / HGB-Taxonomie, JP-EDINET, …) carry the IRI here. Consumers who don't care leave it blank; the rest of the report engine continues to operate on tag names (`:account-tag/name`).

**Why on `:account-tag` and not on `:account/external-codes`.** The two mechanisms are *complementary*, not overlapping:
- `:account/external-codes` records "which code does this account get in this regulator's chart-of-accounts." That's about chart-mapping at the bookkeeping layer.
- `:account-tag/concept-iri` records "which financial-reporting concept does this tag identify." Tags can attach to accounts, postings, or tax-rep lines; that's the granularity XBRL reporting needs.

Both can be present simultaneously: a single posting can hit an account with a SKR04 code (via `:account/external-codes`) tagged with a tag that has an IFRS concept IRI (via `:account-tag/concept-iri`). The bookkeeping code goes to DATEV; the concept IRI is what an iXBRL emitter would tag the fact with.

**Bitemporal interaction.** XBRL concept IRIs typically encode a taxonomy version (`.../2024-03-27/...`). When taxonomy catalogs are themselves ingested into kontor as data (a future companion module — see note 78 §8 Direction B/C), the concept-iri here doubles as a foreign key into that catalog, and `kontor`'s bitemporal axis makes the version-resolution time-correct ("which IRI did this tag point at on the filing date?"). The substrate-tier change today does NOT require that catalog to exist; consumers can dereference the IRI manually against any source they like.

**What this addendum does NOT do** (deliberately deferred per the "ship the seam, defer the rest" call in this conversation's context):
- No `:taxonomy/*` / `:concept/*` / `:concept-arc/*` schema for full taxonomy catalogs (deferred to Direction B in note 78, gated on first consumer pull or simmis filing-readiness sim).
- No `kontor.taxonomy/install-from-data!` ingest helper.
- No calc-linkbase verification primitive.
- No iXBRL emission (companion-tier, multi-month per jurisdiction).
- No substrate-level validation of the IRI format. Consumers pick the format their taxonomy source uses; verification belongs in a companion.

**Reversibility.** Trivial — drop the attr if the design call is reversed. No existing data depends on it.

**Research backing.** `doc/research/78-xbrl-and-accounting-taxonomies.md` (4-direction analysis, file:line citations for Odoo + Tryton XBRL footprint, Arelle code survey at `/home/christian-weilbach/Development/arelle`).

Date: 2026-05-18.

---

## ADR-020 — Document-type registry (kernel-level)

**Decision (2026-05-11, surfaced by the BR + CN Odoo-gap analyses).**
Promote "document type" to a first-class kernel entity. A document type
is the regulator-recognized kind of a fiscal document (NF-e mod 55,
NFC-e mod 65, CT-e mod 57, CN special-VAT fapiao type 01, etc.).
Schema additions:

```clojure
:document-type/code           string  unique-identity   ; "55", "65", "01"
:document-type/jurisdiction   keyword index             ; :br/sefaz, :cn/sta, :de/finanzamt
:document-type/name           string                    ; "NF-e (mercadorias)"
:document-type/internal-type  keyword                   ; :invoice :credit-note :debit-note :all
:document-type/prefix         string                    ; "NFe", "NFCe"
:document-type/active?        boolean

:transaction/document-type    ref     index             ; ref to :document-type
:transaction/clearance-format keyword                   ; :br/nfe :br/nfc-e :cn/fapiao-special-18 ...
```

The `:document-type` registry is what `:transaction/document-type` refs
*should* point at; the small `:clearance-format` enum is the
quick-lookup discriminator used by validation and emitters. They carry
redundant information by design — registry is data, enum is keyword
dispatch in code paths.

### Why kernel-level (not in l10n-br)

The BR gap-analysis surfaced this need first, but the same pattern is
documented in Odoo's `l10n_latam_invoice_document` — a community
module **separate from** `l10n_br` because Argentina, Chile, Uruguay,
Mexico, Peru, Colombia all need it. China's fapiao type (special vs
general vs fully-digital) is a degenerate case of the same shape:
discriminator between otherwise-indistinguishable clearance strings.
Promoting once at the kernel saves four+ duplications later.

### Why both an entity registry AND an enum

The registry (`:document-type/*`) is the data model: lookup by
jurisdiction code, query active types, attach to transactions. The
enum (`:transaction/clearance-format`) is the kernel-side dispatch
key — emitters and validators decide *which schema to emit* by
keyword, not by walking back through `:document-type` refs.

The two stay in sync via convention:

```clojure
{:document-type/code "55"
 :document-type/jurisdiction :br/sefaz
 :document-type/internal-type :invoice
 :clearance-format :br/nfe}
```

`:clearance-format` is the keyword consumers use; it directly maps
to one document-type entity. Implementations check the keyword first
and only walk to the entity when they need its name / prefix /
internal-type for further routing.

### Worked examples

**Brazil — NF-e mercadorias (mod 55)**

```clojure
;; Register at l10n-br install time:
{:document-type/code "55"
 :document-type/jurisdiction :br/sefaz
 :document-type/name "Nota Fiscal Eletrônica — mercadorias"
 :document-type/internal-type :invoice
 :document-type/prefix "NFe"
 :document-type/active? true}

;; Transaction:
{:transaction/document-type [:document-type/code "55"]
 :transaction/clearance-format :br/nfe
 :transaction/clearance-token "35260112345678000100550010000000011234567890"
 :transaction/state :posted}
```

**China — Special VAT fapiao (code 01)**

```clojure
{:document-type/code "01"
 :document-type/jurisdiction :cn/sta
 :document-type/name "增值税专用发票 (Special VAT Fapiao)"
 :document-type/internal-type :invoice
 :document-type/prefix "VAT-S"
 :document-type/active? true}

;; Transaction:
{:transaction/document-type [:document-type/code "01"]
 :transaction/clearance-format :cn/fapiao-special-18
 :transaction/clearance-token "123456789012345678"   ; 10-digit code + 8-digit number
 :transaction/state :posted}

;; Fully-digital e-fapiao: same registry pattern, different clearance-format
{:transaction/clearance-format :cn/fapiao-digital-20
 :transaction/clearance-token "12345678901234567890"}
```

**Germany — Belegnummer / sequential invoice number**

Currently a free string. With the registry, German tax-compliance
software could attach a document type per `:invoice/lifecycle-event`
without changing the transaction schema:

```clojure
{:document-type/code "RE"        ; Rechnung
 :document-type/jurisdiction :de/finanzamt
 :clearance-format :de/rechnung}
```

### Alternatives considered

- *Per-country `:transaction/<country>-doc-type` strings.* Rejected:
  scales linearly with country count; can't query "all NF-e
  transactions in this period" without country-specific code path.
- *Single string `:transaction/document-type-code`.* Rejected: lacks
  jurisdiction qualifier; "55" collides across countries
  (Brazil's NF-e, theoretical other-jurisdiction code).
- *No enum, just refs.* Rejected: pulling the ref entity every time
  an emitter dispatches is unnecessary I/O and locks emitters to a
  DB round-trip.

### Implications

- New schema: 6 attrs on `:document-type`, 2 attrs on `:transaction`.
- `l10n-br/chart.clj` and `l10n-cn/chart.clj` install the relevant
  document-type entities alongside the chart of accounts.
- `kontor.l10n-br.nfe/document-type-codes` map deprecates in favor
  of `:transaction/clearance-format` keyword + the registry.
- Backward compatibility: existing transactions without a
  `:document-type` ref or `:clearance-format` keyword continue to
  work (both attrs optional). l10n modules add them on issue going
  forward.
- Extends ADR-018: `:transaction/clearance-token` now carries a
  format hint via `:clearance-format` so validators know what regex
  to apply (18-digit vs 20-digit vs 44-digit etc.).

Date: 2026-05-11.

---

## ADR-021 — Parallel ledgers: `:ledger` entity + `:posting/ledger` ref

**Decision.** The kernel adds a first-class `:ledger` entity and a cardinality-one `:posting/ledger` ref on every posting. One *primary* ledger is bootstrapped automatically when the schema is installed; consumers may add *secondary* ledgers (IFRS, US-GAAP, HGB, budget, statistical, ...) without rewriting history. Sum-to-zero is enforced **per ledger within a transaction**, not across ledgers.

### Why now (not later)

`:posting/ledger` is one of the two attributes that cannot be retrofitted cleanly: every existing posting in a production database would need to be assigned to "primary" by migration, and any downstream report that filters by ledger would break silently on un-tagged historical data. Adding it now while the kernel is pre-1.0 costs one attribute and one bootstrap call.

### How competitors model this

- **SAP S/4HANA Universal Journal (ACDOCA).** One leading ledger `0L` plus N non-leading + extension ledgers. **`RLDNR` is a column on every line item.** One document number, multiple line rows per ledger. Ledger groups let a posting target a subset of ledgers. Up to 10 currency-type columns per line — currency translation runs *per-line on posting*. Chart of accounts is shared across ledgers within a company code.
- **NetSuite Multi-Book.** Primary book + up to 4 secondary books per subsidiary. Each posting carries a book tag; one source transaction generates N internal postings, one per book. Secondary books may be *Full* (independent recognition + currency + COA) or *Adjustment-Only* (manual period-end deltas only).
- **Oracle ERP Cloud — Subledger Accounting (SLA).** Primary ledger + secondary ledgers + reporting currencies. The "Subledger" conversion level *derives entirely independent journal entries per ledger from the same source event*. A secondary ledger can have its own chart of accounts.
- **Microsoft D365 Finance.** Each legal entity has one Ledger; "parallel GAAP" is implemented via posting layers (Current, Operations, Tax) on the same ledger or a separate legal entity.
- **Odoo (counter-example).** **No ledger field exists.** Parallel GAAP is solved by creating separate `res.company` entities in a corporate group + fiscal positions remapping accounts/taxes at transaction time. Reporting consolidates across companies. This is the legacy small-business approach; kontor explicitly rejects it because (a) it conflates *legal entity* with *accounting framework*, and (b) it forces every cross-framework adjustment to traverse intercompany books.

### Convergent enterprise pattern (what kontor adopts)

The kernel adopts **SAP's RLDNR pattern**, which is the most natural fit for Datahike's append-only EAV store:

- Ledger is a **ref on the posting line**, not on the transaction. One transaction may post to multiple ledgers; each posting carries its own `:posting/ledger`.
- A single source event (one `:transaction`) produces N postings — same as today, but each posting names its ledger. Different IFRS-vs-local-GAAP amounts ride on separate postings, not on a single posting with two amount slots. This is the "Oracle SLA derives separate journal entries" pattern adapted to event-sourced storage: the *transaction* is the source event; the postings are the SLA derivations.
- **Sum-to-zero is per-ledger within a transaction.** A transaction is balanced iff, *for each ledger appearing in its postings*, the postings against that ledger sum to zero per commodity. This is the only sane invariant for parallel ledgers — you cannot net an IFRS debit against a local-GAAP credit.
- **Chart of accounts is shared by default** across ledgers within one kontor database (matches SAP, NetSuite, D365). Per-ledger COA (Oracle's flexible mode) is *possible* — each `:account` could carry per-ledger `:account/external-codes` — but is not a kernel-enforced concept.
- **Currency-type-per-line** (SAP's 10-column model) is **not adopted.** Per ADR-013 money is `BigDecimal + commodity`; FX translation lives in `kontor.balance` and produces *additional postings* in the target ledger, not parallel columns. This stays event-sourced and avoids the "10 amount columns" wide-row trap.

### Schema

```clojure
{:db/ident       :ledger/code
 :db/valueType   :db.type/string
 :db/cardinality :db.cardinality/one
 :db/unique      :db.unique/identity}  ; "primary", "ifrs", "local", ...

{:db/ident       :ledger/name           :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
{:db/ident       :ledger/type           :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
;; :primary | :secondary | :adjustment | :budget | :statistical
{:db/ident       :ledger/framework      :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
;; :IFRS | :US-GAAP | :HGB | :ASBE | :NCRF | :ind-AS | ... (free-form, l10n-defined)
{:db/ident       :ledger/commodity      :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}
;; Accounting currency for this ledger (when it differs from the transaction commodity).
{:db/ident       :ledger/active         :db/valueType :db.type/boolean :db/cardinality :db.cardinality/one}

{:db/ident       :posting/ledger
 :db/valueType   :db.type/ref
 :db/cardinality :db.cardinality/one
 :db/doc         "The ledger this posting lives in. Defaults to the
                  bootstrapped primary ledger when not explicitly set
                  by the posting-builder."}
```

### Bootstrap + defaulting strategy

`(kontor.core/install-schema! conn)` transacts a single seed entity:

```clojure
{:ledger/code      "primary"
 :ledger/name      "Primary ledger"
 :ledger/type      :primary
 :ledger/framework :local
 :ledger/active    true}
```

**`:posting/ledger` is fully optional.** The builder does *not* inject a default lookup-ref. An absent `:posting/ledger` is interpreted as "the primary book" by every reader and validator: the sum-to-zero grouping keys postings under their raw `:posting/ledger` value (nil included), and queries that ask "what's the primary balance?" treat the nil-keyed group as primary.

This deviates from the original SAP-style "column-on-every-line" plan because the invariant library (`invariant.datahike`) runs speculative `db-with` against an *empty schema-only DB* during validation — that DB has no entities, so a `[:ledger/code "primary"]` lookup ref in tx-data fails to resolve. Read-time defaulting sidesteps the problem entirely AND keeps historical postings (created before this ADR) working without migration. Multi-ledger users explicitly tag their postings; everyone else pays nothing.

### Alternatives considered

- *Per-transaction ledger ref (one ledger per transaction).* Rejected: SAP's "one document, multiple ledger lines" is the cleanest model when an IFRS adjustment differs from local GAAP — both lines reference the same source event/document.
- *Posting layer enum (D365 style: `:posting/layer` with `:current`/`:operations`/`:tax`).* Rejected: keyword enum is less flexible than a ref entity; cannot attach a framework name, currency, or close-state to a keyword.
- *Multi-company model (Odoo style).* Rejected: see above; conflates legal entity with accounting framework.
- *Branch-per-ledger in datahike.* Rejected: branches are expensive to query across; reporting "show me both ledgers side-by-side" is a join, not a union.

### Implications

- New schema: 6 attrs on `:ledger`, 1 attr on `:posting`. Existing tests pass because `:posting/ledger` is optional with a default resolution.
- `kontor.balance` and `kontor.trial` must filter by ledger; when no ledger is supplied, they default to `primary`.
- The sum-to-zero invariant in `kontor.posting` becomes per-ledger.
- l10n modules MAY install jurisdiction-specific secondary ledgers (e.g. `kontor-l10n-de` could install an `hgb` ledger alongside `primary`-as-IFRS for users in DE), but the kernel ships only `primary`.
- Forward-compat for fixed-asset register / lease accounting / revenue recognition: each consumer engine that produces postings can post to multiple ledgers, which is exactly the IFRS-16 vs ASC-842 split.

Date: 2026-05-11.

---

## ADR-022 — Per-account required analytic plans + sum-to-100 invariant

**Decision.** Extend ADR-012 with two small additions: a `:account/required-analytic-plans` set ref attribute, and a documented invariant that distributions on a posting must sum to 100% per plan (or be absent entirely for that plan).

### Why

ADR-012 made analytic accounting schema-only. The enterprise pattern survey (SAP, NetSuite, Oracle, D365) shows two features that are universal and that ADR-012 left unspecified:

1. **Per-account mandatory-ness.** Every major ERP lets you say "expense accounts require a cost center" or "revenue accounts require a profit center." In SAP this is enforced via document-splitting rules + field-status groups; in NetSuite via the "Mandatory" flag on the segment + per-account-class filter; in Oracle via the Accounting Flexfield + cross-validation; in D365 via the account structure's allowed-dimensions matrix. The convergent shape is "a set of dimension families required for this account."

2. **Per-plan sum-to-100.** Without this, you cannot trust an analytic distribution: 60% to project A + 30% to project B (sum 90%) silently loses 10% of the cost. Odoo enforces this at write time; every enterprise ERP enforces it via the posting-builder. The schema already stores percent as a `bigdec`; the invariant is the missing piece.

### Schema

```clojure
{:db/ident       :account/required-analytic-plans
 :db/valueType   :db.type/ref
 :db/cardinality :db.cardinality/many
 :db/doc         "Set of :analytic-plan entities that postings against
                  this account must populate. The posting-builder
                  validates that each named plan has distributions
                  summing to 100. Optional; nil = no plan required."}
```

### Invariant

When a posting carries any `:analytic-distribution` for plan P, the percents under P must sum to exactly 100 (bigdec, two decimals). A posting may omit a plan entirely (no distributions for that plan) unless the posting's account names that plan in `:account/required-analytic-plans`. The invariant lives in `kontor.posting/validate-posting` and is exercised at `post-transaction!` time, not at write of an individual datom.

### Distribution semantics: per-line vs split-posting

The survey also raised this fork: every enterprise ERP **expands distributions into N postings at the posting-builder layer** (60% to project A, 40% to B → two postings). Only Odoo keeps the distribution as JSON on the line.

kontor supports **both shapes**:

- **Per-line distribution (Odoo-style)** is the schema's default — `:posting/analytic-distributions` holds N (plan, account, percent) triples on a single posting. This is *more* expressive at query time (you can ask "what was the cost-center mix on this specific posting?") and aligns with Datahike's immutable-fact model (the distribution is a fact, not a transformation).
- **Split-line expansion (SAP/NetSuite-style)** is available via `kontor.posting/expand-distribution` — a builder helper that takes a posting with distributions and produces N postings whose amounts are split per percent. Useful when downstream reports expect "one posting per cost center" (most legacy report templates).

The two shapes coexist; consumers pick per posting. ADR-012 implicitly chose the per-line shape; this ADR explicitly admits the split-line shape as a builder option.

### Alternatives considered

- *Named dimension attributes on posting* (`:posting/cost-center`, `:posting/profit-center` as direct refs). Rejected: this is what three of four enterprise ERPs do (SAP, NetSuite, D365), but it requires kernel schema migration per new dimension. The plan/account approach (matches Odoo + Oracle's flexfield value-set model) lets consumers add dimensions as data, not as schema. Consumer apps that want fast direct-attribute access can *still* add their own namespaced attrs (`:beleg/cost-center-ref`) without conflicting with the kernel.
- *Per-plan applicability rule on `:analytic-plan/applicability` instead of `:account/required-analytic-plans`.* Rejected: applicability is currently a coarse keyword on the plan ("which posting context"), not per-account. The per-account approach matches every enterprise ERP and is the level of granularity actually needed.
- *Skip sum-to-100 enforcement entirely.* Rejected: silently losing percent points is a real bug class that costs companies money in management reporting.

### Implications

- New schema: 1 attr on `:account`. No change to `:posting` or `:analytic-distribution`.
- The validation lives in `kontor.posting`. Tests in `test/kontor/analytic_test.clj` cover both required-plan and sum-to-100 cases.
- ADR-012 stays valid; this is a *refinement*, not a replacement.

Date: 2026-05-11.

---

## ADR-023 — First-class `:country` + `:state` entities with `:transaction/place-of-supply`

**Decision.** The kernel adds first-class `:country` and `:state` entities, with `:state/country` as a required ref and a composite-tuple identity on `(country, code)`. A `:country-group` entity models EU / EEA / NAFTA / etc. as data. Both country and state carry an `:external-codes` map (refs to `:country-code` / `:state-code` entities) following the ADR-019 pattern, so per-regulator codes (Indian GSTN, Brazilian IBGE, Canadian CRA, UN M49, …) live alongside the canonical ISO identifiers without proliferating typed columns. Partners gain `:partner/state`; transactions gain `:transaction/place-of-supply` — both optional, both refs to `:state`.

### Why now

The kernel acquired the Brazilian, Chinese, Japanese, Australian, German and Canadian l10n modules in the last few weeks. India and Mexico are the next two and *both* stress sub-national tax routing:

- **India.** CGST+SGST applies intra-state; IGST inter-state; UTGST for union territories. Dispatch keys off `(issuer state, place of supply)`. GSTIN encodes the state in chars 1–2. Place-of-supply may differ from the partner's registered state (especially for services), so partner-state ≠ POS.
- **Quebec.** QST (TVQ) at 9.975% on top of 5% federal GST; Canadian HST/GST+PST varies by province. ADR-015d defers QST implementation but the schema must accommodate Quebec without re-work later.
- **Brazil.** Already shipped; uses ad-hoc state strings in function-argument maps. Retrofit becomes opt-in once the entity exists.
- **Mexico.** State-level identity for partner address; CFDI 4.0 catalog `c_Estado`.

Without a first-class `:state`, every consumer parses two-letter codes from strings and re-derives intra-vs-inter-state dispatch by string compare. With it, dispatch is a ref-equality and the same model serves IN / CA / BR / MX / AU with one schema lift.

### How competitors model this

- **Odoo 19** — `res.country` (~250 entities, ISO-2 unique, `currency_id` FK, address-format template, `country_group_ids` for EU/etc.) and `res.country.state` (`country_id` FK, composite unique `(country_id, code)`, no translatable name in v19). India's `l10n_in` extends state with a single `l10n_in_tin` (GSTN code) — minimal kernel, one optional field per regulator. Brazilian l10n loads 5,570 município rows into `res.city`. Place-of-supply on the invoice header is a separate `l10n_in_state_id` on `account.move`, *distinct* from the partner address state.
- **SAP S/4HANA** — `T005` (Countries) keyed by internal 3-char `LAND1`; carries ISO-2 / ISO-3 / numeric, default currency, EU flag (`XEGLD`), address-format params. `T005S` (Regions) keyed `(LAND1, BLAND)`; carries `FPRCD` (provincial tax code) + `HERBL` (state of manufacture). State *name* lives in companion text table `T005U` for 1-to-many translation.
- **NetSuite** — country is a hard-coded enumeration (no custom countries). State has `(country, state)` composite key + `fullname` + `shortname`. India GST place-of-supply is a *transaction-body* field, separate from the customer address.
- **Microsoft D365 F&O** — `LogisticsAddressCountryRegion` PK is `CountryRegionId` (ISO-3 by default), separate `ISOcode` (ISO-2). `LogisticsAddressState` composite `(CountryRegionId, StateId)`.

All four reference systems treat country + state as first-class entities. None use bare strings. EU group membership is data (Odoo's many-to-many `country_group_ids`) in three of the four; only SAP uses a single flag, which doesn't generalize to EEA / NAFTA / G7.

### What kontor adopts (and improves)

- **Country and state as entities** — convergent pattern.
- **Composite identity on `(country, state-code)`** — convergent.
- **External-codes map mirroring ADR-019** — strictly improves on the four references. Odoo adds one column per regulator (`l10n_in_tin`, `l10n_br_ibge_code`); kontor stores them as data entities so adding a new regulator never changes the schema. The same pattern already pays off for accounts.
- **Country-group entity** (Odoo's pattern, not SAP's flag) — supports EU + EEA + NAFTA + G7 + future groupings without schema churn.
- **`:transaction/place-of-supply`** as a *transaction*-level ref, separate from `:partner/state` (the registered address). Matches Odoo's `l10n_in.account_move.l10n_in_state_id` and NetSuite's transaction-body POS field. We do NOT add `:posting/place-of-supply` — no enterprise ERP carries per-line state; India's per-line variance is rate-driven (via HSN), not state-driven.

### Schema

```clojure
;; Country -----------------------------------------------------------
:country/code           ISO-2 string, :db.unique/identity     ; "IN" "BR" "CA"
:country/code-iso3      ISO-3 string, :db.unique/identity     ; optional
:country/name           string                                ; English
:country/default-commodity   ref → :commodity                 ; INR BRL CAD
:country/external-codes      cardinality-many ref → :country-code
:country/groups              cardinality-many ref → :country-group
:country/active         boolean

;; Country external codes (per-regulator) ----------------------------
:country-code/country   ref → :country (back-ref)
:country-code/regulator keyword       ; :iso-3166-1-numeric :un/m49 :sap/land1 …
:country-code/code      string        ; "356" (India under M49)
:country-code/identity  tuple [country, regulator] unique
:country-code/note      string (optional)

;; Country groups ----------------------------------------------------
:country-group/code     string, :db.unique/identity  ; "EU" "EEA" "NAFTA"
:country-group/name     string

;; State -------------------------------------------------------------
:state/country          ref → :country (required)
:state/code             string                       ; ISO 3166-2 suffix
                                                     ; "MH" "QC" "SP" "JAL"
:state/name             string
:state/identity         tuple [country, code] unique
:state/external-codes   cardinality-many ref → :state-code
:state/active           boolean

;; State external codes (per-regulator) ------------------------------
:state-code/state       ref → :state
:state-code/regulator   keyword       ; :in/gst :br/ibge :ca/cra
                                      ; :iso-3166-2 :sap/bland …
:state-code/code        string        ; "27" "35" "13" "IN-MH"
:state-code/identity    tuple [state, regulator] unique
:state-code/note        string (optional)

;; Refs added to existing entities -----------------------------------
:partner/state                ref, optional   ; partner's registered state
:transaction/place-of-supply  ref, optional   ; needed for IN GST dispatch
```

### Bootstrap

The kernel ships no country / state data. Each l10n module installs the slice it needs:
- `kontor-l10n-in` transacts ~37 Indian states/UTs with `:in/gst` external codes
- `kontor-l10n-br` transacts 27 UFs with `:br/ibge` codes (retrofit; opt-in)
- `kontor-l10n-mx` transacts 32 estados with SAT `c_Estado` codes
- `kontor-l10n-ca` transacts 13 provinces/territories with `:ca/cra` codes

A `kontor-iso-base` data module could ship the full 250-country list later. For now, "load what your l10n module needs" is sufficient.

### Brazil retrofit — opt-in

Existing BR code (`l10n_br/nfe.clj`, `taxes.clj`, `sped.clj`, and tests) uses state codes as strings in function-argument maps. None of those strings are stored as datoms; they flow through `invoice-element` → `determine-id-dest` → XML emission. The retrofit is therefore **purely additive**:

1. `kontor-l10n-br` will gain a `chart-states.edn` loader that transacts `:state` entities with `:br/ibge` codes attached.
2. New NF-e callers MAY pass `:state-ref` instead of `:state` in issuer/recipient maps; the emitter resolves the ref to the 2-letter code before XML emission.
3. Existing callers continue passing `:state "SP"` strings — unchanged.
4. No existing BR test changes; new entity-aware tests are added separately.

### What this ADR does NOT do (deferred)

- **Does not migrate `:partner/country-code` (or the other 4 string `*-country-code` attrs on `account-tag`, `tax`, `tax-group`, `fiscal-position`) to refs.** These work as-is; migrating them now would touch ~20 call sites across kernel + l10n modules without a load-bearing query that needs it. A future ADR-024 can consolidate when a real use case demands (e.g. "give me every partner in the EU").
- **Does not add `:locality` / `:municipality` entity** for Brazilian municípios (IBGE-coded, 5,570 entries) or Mexican municipios (SAT `c_Municipio`, 2,469 entries). NF-e and CFDI 4.0 require this granularity but it stays in the relevant l10n module under a separate ADR.
- **Does not add `:fiscal-position/state`** (Odoo-style state-scoped fiscal positions). No current use case forces it.
- **Does not add `:posting/place-of-supply`** (per-line POS). No enterprise ERP does; per-line GST variance is HSN-driven, not state-driven.
- **Does not consolidate `:document-type/jurisdiction`** to refs. ADR-020 correctly chose keyword jurisdictions (`:br/sefaz`, `:cn/sta`) which model authorities, not countries. No change needed.

### Alternatives considered

- *Bare string `:partner/state` / `:transaction/place-of-supply`.* Rejected: every tax engine would reparse strings; no place to attach `:in/gst-state-code "27"`; no referential integrity. None of the four reference ERPs took this path.
- *Hierarchical `:jurisdiction` entity (SAP-style country → state → county → city).* Rejected: YAGNI for current scope. The only jurisdiction that needs county/city granularity is the US, and ADR-005 explicitly outsources US sales tax to Avalara.
- *Single `:state.<country>/code` typed enum per country.* Rejected: 37 such enums across our l10n module set; doesn't match how any reference system models it.
- *Translate `:state/name` via a separate text table (SAP `T005U` pattern).* Rejected: Odoo v19 also dropped translatable state names; consumer apps can carry their own translation overlay if needed.
- *EU as a flag on country (`:country/eu?`).* Rejected: doesn't generalize to EEA / NAFTA / G7 / customs unions. Odoo's many-to-many `:country/groups` model is strictly more flexible.

### Implications

- New schema: 7 country attrs + 5 country-code attrs + 2 country-group attrs + 6 state attrs + 5 state-code attrs + 2 refs on existing entities = **27 new attributes**.
- All new attrs are optional from the perspective of existing code. The kernel test suite (currently 465 tests / 1531 assertions) gains a small ledger-entity-style test file and otherwise unchanged.
- Forward-compat for India (`kontor-l10n-in`): the GST intra/inter-state dispatch becomes `(= (:transaction/place-of-supply tx) (:partner/state issuer-as-partner))`, exactly as Odoo does it.
- Forward-compat for Mexico (`kontor-l10n-mx`): partner addresses carry `:partner/state` ref; CFDI's `c_Estado` code lives in `:state-code` under `:sat/c-estado`.

Date: 2026-05-11.

---

## ADR-024 — Multi-attestation lifecycle: `:attestation` entity per transaction

**Decision.** Introduce a first-class `:attestation` entity. A single transaction may carry zero or more attestations, each tracking one government-issued artifact (IRN, e-way bill, PAC stamp, CFDI UUID, NF-e access key, fapiao number, etc.) with its own token, format, valid-time window, lifecycle state, and `depends-on` graph. The existing `:transaction/clearance-token` + `:transaction/clearance-format` remain for the typical single-attestation case, deprecated in favor of the new entity for any jurisdiction with coupled artifacts.

### Why now

The India research established (verified against Odoo's `l10n_in_edi.account_move` + `l10n_in_ewaybill.ewaybill`) that a single Indian tax invoice for goods movement carries **two distinct attestations**:

- **IRN** (Invoice Reference Number) — issued by the IRP portal at invoice generation; indefinite legal validity; uniquely identifies the commercial document.
- **E-way bill** (EWB) — issued by the EWB portal *when the truck physically rolls*; validity is bounded by distance (1 day per 200 km for regular cargo; 1 day per 20 km for ODC); cancellable within 24 hours of generation if movement does not occur; capped 360 days total from 2025-01-01.

Part A of the EWB auto-derives from the IRN (NIC offers "EWB Generation by IRN") — so the EWB *depends on* the IRN. Part B (vehicle/transporter) is added later in a separate transaction. This forces a `depends-on` relation between attestations the kernel cannot currently express.

The kernel today has `:transaction/clearance-token` (one string per transaction). India needs two. Italy's *integrazione* (synthetic outbound for received foreign invoices through SdI), Poland's KSeF (FA(2)→FA(3) schema-version-time-dispatched), and KSA's PIH-chain link consecutive invoices all want the same generalization.

### What kontor adds

```clojure
:attestation/transaction   ref → :transaction (back-ref; required)
:attestation/format        keyword                ; :in/irn :in/ewb-part-a
                                                  ; :br/nfe-44 :mx/cfdi-uuid
                                                  ; :cn/fapiao-20 :sa/zatca-icv …
:attestation/token         string                 ; the issued artifact identifier
:attestation/state         keyword                ; :pending | :issued
                                                  ; | :revoked | :expired
                                                  ; | :superseded
:attestation/issued-at     instant                ; when the authority responded
:attestation/valid-from    instant                ; optional — start of the
                                                  ; legal validity window
:attestation/valid-until   instant                ; optional — end of validity
                                                  ; (EWB distance computation
                                                  ; lives in the consumer)
:attestation/depends-on    cardinality-many ref   ; →:attestation; e.g. EWB
                                                  ; Part A depends on IRN
:attestation/payload       string                 ; canonical bytes sent to /
                                                  ; received from the authority
                                                  ; (KSA / Korea / Turkey
                                                  ; cryptographic-stamp regimes
                                                  ; need byte-exact storage)
:attestation/payload-hash  string                 ; SHA-256 of payload, hex
                                                  ; (PIH chains, audit checks)
:attestation/note          string                 ; optional

:attestation/identity      tuple [transaction, format] unique
                                                  ; one (transaction, format)
                                                  ; pair exists at most once
                                                  ; — re-issuing replaces

:transaction/attestations  cardinality-many ref   ; → :attestation
                                                  ; the canonical list
```

### Migration / coexistence with `:transaction/clearance-token`

- `:transaction/clearance-token` (string) and `:transaction/clearance-format` (keyword) stay in the schema. Single-attestation jurisdictions (Brazil NF-e, China fapiao) can continue to use them.
- New code SHOULD prefer `:transaction/attestations`. Multi-attestation jurisdictions (India IRN + EWB; Italy synthetic-issuer; Korea NTS chain) MUST use `:transaction/attestations` because the singular cannot represent the depends-on graph or the per-attestation valid-window.
- When both are present, `:transaction/attestations` is authoritative.
- A follow-up ADR may migrate single-attestation BR/CN to `:transaction/attestations` and retire the singular attrs; for now they coexist.

### State semantics

The lifecycle keyword set is intentionally larger than the binary `:pending-attestation`/`:posted` on the transaction:
- `:pending` — submitted to the authority, awaiting response (mid-flight)
- `:issued` — authority returned a valid token (the success state)
- `:revoked` — authority cancelled / taxpayer cancelled within the cancellation window (BR within 24h on NF-e; MX within 3 business days on CFDI; IN within 24h on IRN)
- `:expired` — time-bounded attestation past its `valid-until` (e.g. expired EWB)
- `:superseded` — replaced by a later attestation in a chain (PIH chain re-issuance; KSA's previous-invoice-hash linking)

### Worked examples

**India invoice + e-way bill:**
```
:transaction      INV-2026-IN-0001
:transaction/attestations
  [{:attestation/format     :in/irn
    :attestation/token      "f8b3a1c9…"          ; SHA-256-based IRN
    :attestation/state      :issued
    :attestation/issued-at  #inst "2026-05-11T10:23Z"
    :attestation/payload    "{...signed-JWS...}"}
   {:attestation/format     :in/ewb-part-a
    :attestation/token      "1234567890123"
    :attestation/state      :issued
    :attestation/issued-at  #inst "2026-05-11T10:24Z"
    :attestation/valid-from #inst "2026-05-11T10:24Z"
    :attestation/valid-until #inst "2026-05-13T10:24Z"  ; 400 km, 1d/200km
    :attestation/depends-on [<IRN attestation eid>]}]
```

**Brazil NF-e (single attestation, opt-in path):**
```
:transaction/attestations
  [{:attestation/format    :br/nfe-44
    :attestation/token     "35260112345678000100550010000000011234567890"
    :attestation/state     :issued}]
```

### Alternatives considered

- *Per-format singleton attrs (`:transaction/irn`, `:transaction/ewb-part-a`, `:transaction/cfdi-uuid`).* Rejected: scales linearly with format count; no place for valid-window or depends-on; no chain-of-supersession story.
- *JSON blob on `:transaction/attestation-bundle`.* Rejected: opaque to datalog; can't be queried by format or state.
- *Reuse `:transaction/clearance-token` as cardinality-many string.* Rejected: loses the per-token format + state + valid-window. The string-only shape can't express the depends-on graph.

### Implications

- New schema: 10 attrs on `:attestation` + 1 attr on `:transaction`.
- Existing transactions transact unchanged (cardinality-many is absent → empty).
- `kontor.sealing` and the `:transaction/state` machine remain unchanged; attestation lifecycle is parallel.
- Consumer pattern: a partner adapter (e.g. `kontor-l10n-in-irp`) submits to the authority, then transacts the resulting `:attestation` referencing the parent transaction.
- ADR-018's `:pending-attestation` transaction-state remains as a *transaction-level* gate; the per-attestation lifecycle gives finer-grained tracking.

Date: 2026-05-11.

---

## ADR-025 — Document composition: `:complemento` entity for nested e-invoice fragments

**Decision.** Introduce a `:complemento` entity for nested e-invoice fragments (CFDI complementos, Peppol UBL extensions, NF-e information groups). A document type (per ADR-020) can declare a list of admitted complemento namespaces; per-transaction, an ordered set of `:complemento` entities holds the XSD-namespaced fragments that get spliced into the envelope at serialization time. The XSD-validation registry lives in the l10n module that owns the namespace, not in the kernel.

### Why now

The Mexico research established that every CFDI is *one envelope + N stacked complementos*, with each complemento carrying its own XSD, namespace, and conditional applicability rules:

- **TimbreFiscalDigital 1.1** — the PAC stamp itself; present on every issued CFDI
- **Pagos 2.0** — required on every payment-type (P) CFDI
- **Carta Porte 3.1** — mandatory for transport of goods (since April 2024; enforcement intensifies in 2026)
- **Nómina 1.2 Rev. E** — every employer-employee payment (updated Dec 2025, effective Jan 2026)
- **ComercioExterior 2.0** — exports
- **PagosExtranjero / Retenciones**, **Hidrocarburos**, **INE**, **Educativas**, **Notarios**, …

ADR-020 currently models *one* envelope per `:document-type` and ADR-017's `EInvoiceProvider/emit` takes a single envelope. Mexico forces a *composition* shape: one parent envelope (CFDI) + an ordered, namespace-typed list of children. Beyond Mexico the same shape covers Peppol UBL extensions (UBL `cac:AdditionalDocumentReference` / `cbc:Note`), NF-e information groups (`InfAdic`, `Cana`, `Combustivel`), and ZATCA additional document references.

### What kontor adds

```clojure
:complemento/transaction      ref → :transaction (back-ref; required)
:complemento/namespace        string             ; canonical XML namespace
                                                 ; URI, e.g.
                                                 ; "http://www.sat.gob.mx/CartaPorte31"
:complemento/format           keyword            ; convenience identifier
                                                 ; e.g. :mx/cfdi-carta-porte-3.1
                                                 ; :mx/cfdi-pagos-2.0
                                                 ; :mx/cfdi-nomina-1.2
                                                 ; :ubl/factur-x-additional-doc
:complemento/sequence         long               ; ordering within the envelope
                                                 ; (Mexico requires a defined
                                                 ; order; some XSDs enforce it)
:complemento/payload          string             ; the XML fragment as a string
                                                 ; (the kernel stores opaque
                                                 ; bytes; l10n module owns
                                                 ; the schema validator)
:complemento/active           boolean            ; idempotency / soft-supersede
:complemento/identity         tuple [transaction, namespace] unique
                                                 ; one fragment per (txn, ns)

:transaction/complementos     cardinality-many ref → :complemento
```

### Relation to ADR-020 (document-type registry) and ADR-024 (attestations)

- **ADR-020** still owns the envelope's document-type identity. `:document-type` may gain a future `:document-type/admitted-complementos` list (deferred — not needed for v1).
- **ADR-024** owns the *separately-issued government artifacts* (IRN, EWB). Some complementos *are* attestations (the CFDI's TimbreFiscalDigital — the PAC stamp). When that's the case, a single transaction carries BOTH a `:complemento` (the XML fragment to splice into the envelope) AND an `:attestation` (the lifecycle record); they reference the same data via different views. The two namespaces are deliberately orthogonal:
  - `:complemento` is **serialization-time**: what XML chunks belong inside this envelope
  - `:attestation` is **lifecycle-time**: what artifacts has the authority issued; what's their validity / dependency graph

### How serialization works

The `EInvoiceProvider/emit` for a complemento-bearing format (e.g. `:mx/cfdi-4.0`) iterates `:transaction/complementos` sorted by `:complemento/sequence`, splices each payload XML into the envelope's `<Complemento>` parent, and returns the combined document. Validation against the XSD set is the responsibility of the emit provider, which holds the XSD registry in its module.

### Alternatives considered

- *Embed complementos as opaque JSON on the transaction.* Rejected: no per-fragment querying; no namespace-based provider dispatch; no per-fragment lifecycle.
- *Per-format singleton attrs (`:transaction/cfdi-carta-porte`, `:transaction/cfdi-pagos`).* Rejected: scales linearly with complemento count; ~15 Mexican complementos alone.
- *Reuse `:transaction/attestations` (ADR-024) for complementos.* Rejected: confuses serialization-shape with lifecycle. A Carta Porte complemento has no separate authority response — it's part of the parent CFDI's PAC stamp. Conflating loses clarity.

### Implications

- New schema: 6 attrs on `:complemento` + 1 attr on `:transaction`.
- Existing transactions transact unchanged.
- l10n-mx CFDI emitter assembles the `<Complemento>` block from `:transaction/complementos`; the PAC-roundtrip writes the resulting `:tfd:TimbreFiscalDigital` *both* as the final complemento AND as an `:attestation` (per ADR-024) — the two views of the same artifact.
- Future Peppol UBL extensions (e.g. factur-x's `cac:AdditionalDocumentReference`) use the same shape.

Date: 2026-05-11.

---

## ADR-026 — Effective-dated tax rates via `:tax/effective-from` + `:tax/effective-until`

**Decision.** Tax entities gain optional `:tax/effective-from` and `:tax/effective-until` timestamp attributes. The `TaxProvider` protocol's rate resolution selects the tax record whose validity window contains the transaction's `:transaction/effective-date`. Historical rate stacks coexist with current ones in the same DB; nothing is migrated when a jurisdiction changes rates.

### Why now

The India research surfaced **GST 2.0** (effective 2025-09-22) — a major rate-slab rationalization that collapsed 5/12/18/28%+cess into 0/0.25/3/5/18/40%. Tobacco and pan masala overhaul on 2026-02-01 further changed the cess structure. Any transaction dated *before* 2025-09-22 must be valued at the *old* rates for retro-filings, audit reconciliation, and amended-return generation; transactions from 2025-09-22 onward use the new structure.

Beyond India, the same pattern recurs:
- **Mexico**: IEPS cuotas (per-liter, per-piece) update annually per DOF; cigarettes go from 160% (2025) to 200% (2026); fuel cuotas refresh each January. Border-zone IVA stimulus extended through Dec 31, 2026 — needs a sunset date.
- **Brazil**: IBS + CBS transition rates apply only during 2026-2027 (CBS 0.9% / IBS 0.1% test phase); full rates come online 2027-01-01 per the Reforma Tributária schedule.
- **Germany**: VAT cut to 7% on restaurant food during 2020-2023 then reverted to 19% — a real-world historical case.

A tax registry keyed only on `(country, type)` cannot answer "what was the IGST rate on 2024-11-15?" without per-jurisdiction custom logic. Making validity dates first-class moves this to data.

### What kontor adds

```clojure
:tax/effective-from   instant   ; optional; nil = always-effective
:tax/effective-until  instant   ; optional; nil = no end date
```

The selection rule:
> For a transaction with `:transaction/effective-date = D`, find the tax record matching the requested `(country, type, rate-bucket)` where `effective-from ≤ D < effective-until` (open interval on the right; nil bounds count as -inf / +inf).

When two records match (overlapping windows in faulty data), the longest-effective-from-not-exceeding-D wins; ties produce a validation warning.

### Why an open interval on the right

`effective-until` is the first day the rate is no longer valid. India's GST 2.0 went into effect at 00:00 IST on 2025-09-22, so the old 18% record has `effective-until #inst "2025-09-22T00:00:00+05:30"` and the new 18% record has `effective-from #inst "2025-09-22T00:00:00+05:30"`. The instant value is contained in the new record's window, not the old one.

### Alternatives considered

- *Year-versioned tax namespaces (`:tax-2024/igst-18`, `:tax-2025/igst-18`).* Rejected: requires renaming the entity every change; can't represent mid-year transitions; loses identity across windows.
- *Bitemporal versioning via datahike `:keep-history?`.* Rejected: that's tx-time, not valid-time. A 2026 retro-amendment of a 2024 invoice must use the rate that was *legally in effect on the invoice date*, not the rate the database knew about on the transact-time. ADR-008 already establishes valid-time vs tx-time separation; tax effectivity is a valid-time concept.
- *Always store the rate as a number on the posting (snapshot at post-time).* Rejected: doesn't help when the rate is re-derived (e.g. amending an old invoice; running a "what-if" report under historical rates). Plus the kernel already stores the computed amount on `:posting/amount`; what's missing is the structured-rate lookup for new transactions and amendments.

### Implications

- New schema: 2 optional attrs on `:tax`.
- Existing tax records (DE / CA / BR / CN / JP / AU) all have nil `:tax/effective-from` and `:tax/effective-until` — they're treated as always-effective, behavior unchanged.
- `kontor.tax_provider` / `kontor.tax` resolve rates by filtering on effective window before per-jurisdiction logic.
- l10n-in ships *two* IGST-18% entities: one with `:tax/effective-until #inst "2025-09-22T00:00:00+05:30"` (pre-GST-2.0; legacy items routed here), one with `:tax/effective-from` of the same instant.
- l10n-br's CBS-0.9% / IBS-0.1% test-phase rates carry `:tax/effective-until #inst "2027-01-01"`.
- l10n-mx's IEPS cuotas carry annual effective windows.

Date: 2026-05-11.

---

## ADR-027 — `:valuation-book` entity: parallel cost bases for inventory

**Decision.** Introduce a first-class `:valuation-book` entity. A valuation book is an *orthogonal* lens on physical stock — it picks which cost basis (FIFO / LIFO / weighted-average / standard) applies and under which accounting framework (legal / group / tax-DE / IFRS / management). One physical inventory may carry several books in parallel; valuation queries always name the book. One `"primary"` book is bootstrapped at schema-install time.

### Why now

Five surveys (Odoo, SAP/NetSuite/Oracle/D365, Tryton, ERPNext, Frappe) converge on the same architectural shape: stock movements are immutable events, but the *cost basis* of each movement depends on which valuation book reads it. Without a first-class book, every consumer would invent its own per-method cost field on the move — exactly what SAP retired in S/4HANA when collapsing into the Material Ledger.

The most stress-tested case is **SAP's Material Ledger**: up to three parallel valuation views (legal / group / profit-center) of the same material, each with its own cost method and currency. Oracle's Cost Books and D365's posting-layers play the same role. Without it, IFRS-16 vs ASC-842, US-GAAP vs local-GAAP, FIFO-for-tax vs Average-for-management, and consolidated-vs-statutory cost reporting all require schema duplication.

### How competitors model this

- **SAP Material Ledger** (mandatory in S/4HANA) — per material, up to 3 valuation views in up to multiple currencies; views are independent (different cost methods + different currencies + different ledger destinations).
- **Oracle Fusion Cost Management** — per Cost Org, multiple Cost Books; each book points at a primary or secondary ledger. "Ledgerless" cost books supported for simulation.
- **NetSuite Multi-Book** — per subsidiary, primary + up to 4 secondary books with book-specific cost methods.
- **Microsoft D365** — multiple costing methods per ledger via Item Model Groups.
- **Tryton** — single book per company; `product_cost_*` modules layer on optional cost-tracking variations.
- **ERPNext / Odoo** — single book per company; cost method on the item, not on a separate book entity.

### What kontor adopts

The SAP/Oracle/NetSuite pattern (first-class entity, per-company), not the Odoo/ERPNext pattern (flag on the item). Reasoning: the design pillar that makes parallel ledgers work in kontor (ADR-021) carries directly to valuation books — same shape, orthogonal axis.

### Schema

```clojure
:valuation-book/code         string :db.unique/identity   ; "primary" "ifrs" "tax-de"
:valuation-book/name         string
:valuation-book/framework    keyword                      ; :legal | :group | :ifrs
                                                          ; | :tax-de | :management | …
:valuation-book/cost-method  keyword                      ; :fifo | :lifo | :avg | :standard
:valuation-book/commodity    ref → :commodity (optional)  ; accounting currency for this
                                                          ; book (may differ from txn ccy)
:valuation-book/active       boolean
```

### Bootstrap

`kontor.core/install-schema!` transacts a single seed:
```clojure
{:valuation-book/code        "primary"
 :valuation-book/name        "Primary valuation book"
 :valuation-book/framework   :legal
 :valuation-book/cost-method :fifo
 :valuation-book/active      true}
```

Idempotent via `:db.unique/identity` on `:valuation-book/code`. Consumers add secondary books as data; the kernel ships only the primary.

### Alternatives considered

- *Cost-method as a field on `:product` (Odoo/ERPNext style).* Rejected: doesn't support parallel bases on the same item.
- *Cost-method on `:ledger`.* Rejected: ledger is *which set of books reads this posting*; valuation book is *which cost basis this posting uses*. They're orthogonal. A posting may live in the IFRS ledger and the legal ledger but use FIFO under one book and average under another.
- *Multi-entity / per-company-code books.* Deferred: kontor doesn't yet model multi-entity (cf. Q2 deferred-list). When that lands, `:valuation-book/entity` becomes a ref. For now books are tenant-scoped.

### Implications

- New schema: 6 attrs on `:valuation-book`. No change to existing entities.
- `kontor.core/install-schema!` gains one more bootstrap call (mirrors `kontor.ledger/install-defaults!` from ADR-021).
- `CostingProvider` (ADR-029) selects layers under a specific book; layers (ADR-028) reference their book.

Date: 2026-05-11.

---

## ADR-028 — `:valuation-layer` + `:layer-consumption` + `:layer-adjustment`

**Decision.** Inventory valuation lives in three immutable fact entities, mirroring the kernel's posting/transaction pattern:

- `:valuation-layer` — created at receipt. One layer per `(book, receipt-event)` pair. Carries the *original* quantity and unit cost.
- `:layer-consumption` — created at issue. References the layer that was drawn from. Carries the consumed quantity and the unit cost *as of the consumption time* (which may differ from the layer's original after adjustments).
- `:layer-adjustment` — created at landed-cost arrival / revaluation. References the layer being adjusted. Signed monetary amount + reason keyword.

All three are append-only. **Remaining quantity** and **current unit cost** are *views* derived per query:
```
qty-remaining(L) = L.qty-original − Σ consumption.qty WHERE consumption.layer = L
current-cost(L)  = (L.qty-original × L.unit-cost + Σ adjustment.amount) / L.qty-original
```

### Why this shape

Five surveys produced three competing layer-storage designs:

| Design | Example | Trade-off |
|---|---|---|
| **No explicit layer** — replay from move log | Tryton | Pure; slow at scale |
| **Per-event queue snapshot** on the move | ERPNext (`stock_queue` JSON on each SLE) | Fast lookup; mutable on repost; force-introduces a background-job repost system |
| **Explicit layer entity** with mutable remaining-qty | SAP Material Ledger | Standard ERP pattern; layers are mutated under load |
| **Explicit layer + immutable consumption events** (this ADR) | (none in the wild) | Append-only; views derived; no repost machinery |

kontor adopts the fourth design because it's the only one consistent with both ADR-008 (bitemporal append-only) and the existing kernel posting model. Frappe's `amended_from` pattern (new doc with link to cancelled original) is the same idea: corrections are *new facts*, not mutations. ERPNext's `Repost Item Valuation` is the failure-mode catalogue of the third design — kontor avoids the entire category.

### Schema

```clojure
;; Valuation layer — created at receipt
:valuation-layer/book                ref → :valuation-book   ; required
:valuation-layer/item                ref                      ; generic; the module
                                                              ; that owns the inventory
                                                              ; defines what an item is
:valuation-layer/lot                 ref → :lot               ; optional
:valuation-layer/origin-transaction  ref → :transaction       ; the receipt tx
:valuation-layer/qty-original        bigdec                   ; received quantity
:valuation-layer/unit-cost-original  bigdec                   ; per-unit cost at receipt
:valuation-layer/commodity           ref → :commodity         ; cost currency
:valuation-layer/received-at         instant
:valuation-layer/note                string  (optional)

;; Consumption event — created at issue
:layer-consumption/layer                  ref → :valuation-layer  ; required
:layer-consumption/qty                    bigdec                  ; consumed quantity
:layer-consumption/unit-cost-at-consumption  bigdec               ; book value at issue
:layer-consumption/issue-transaction      ref → :transaction      ; the issue tx
:layer-consumption/issued-at              instant

;; Adjustment event — created at landed cost / revaluation
:layer-adjustment/layer                ref → :valuation-layer    ; required
:layer-adjustment/amount               bigdec                    ; signed total amount
                                                                 ; (not per-unit)
:layer-adjustment/reason               keyword                   ; :landed-cost |
                                                                 ; :revaluation |
                                                                 ; :correction | …
:layer-adjustment/origin-transaction   ref → :transaction
:layer-adjustment/applied-at           instant
:layer-adjustment/note                 string (optional)
```

### Period-close interaction (ADR-014 supplement)

A layer's `origin-transaction` ties it to a fiscal period via `:transaction/effective-date` + the period model. When the period seals (per ADR-014), the layer's `qty-original` and `unit-cost-original` become immutable in fact-storage *by virtue of being immutable facts already*. The interesting question is **adjustments that arrive after the seal**: per the Frappe / ERPNext evidence, the safer stance is **reject late adjustments to sealed-period layers**; consumers post the adjustment to the next open period instead. Implementation lives in `kontor.posting`'s validator; this ADR documents the policy.

### Why not include item entity in the kernel

Five surveys all show item / product as a *consumer* concern — Odoo's `product.template` carries cost method + accounts + pricelist data, none of which the kernel needs. Kontor's `:valuation-layer/item` is a generic ref; the consumer-side inventory module defines the item entity and points the layer at it. This keeps the kernel scope honest (ADR-010).

### Alternatives considered

- *Mutate `qty-remaining` on the layer.* Rejected: ERPNext's repost machinery is the cost.
- *Snapshot the FIFO queue inline on the issue posting.* Rejected: ERPNext-style; recomputing from facts via the bitemporal index is the kontor-native shape.
- *Single `:movement` entity covering both layers and consumption.* Rejected: receipts and issues have different cardinalities (one layer can have N consumptions); separating them keeps datalog queries simple.

### Implications

- New schema: ~17 attrs across three entity namespaces.
- `kontor.valuation` namespace ships view helpers: `qty-remaining`, `current-unit-cost`, `available-layers-for-item`.
- Tests verify view correctness across receipt → multiple issues → adjustment scenarios.

Date: 2026-05-11.

---

## ADR-029 — `CostingProvider` protocol: pluggable cost engines

**Decision.** Add a `CostingProvider` protocol — direct sibling to `TaxProvider` (ADR-005) and `EInvoiceProvider` (ADR-017). The kernel ships impls for FIFO, LIFO, weighted average, and standard cost. Modules and consumers register additional impls (e.g. jurisdiction-specific Anglo-Saxon FIFO, Continental immediate-expense, lot-isolated FIFO). The posting-builder (ADR-030) takes a provider as a parameter.

### Protocol

```clojure
(defprotocol CostingProvider
  "Compute the cost basis of a stock movement."

  (plan-consumption
    [provider db request]
    "For an outbound (issue) move: which layers to consume, in what
     quantities, at what unit costs. Returns
       {:consumptions [{:layer eid :qty bigdec :unit-cost bigdec} …]
        :variance Money?            ; standard-cost only
        :extra-postings [...]?}     ; rarely needed; e.g. price-variance
     Reads `db` to find candidate layers; respects bitemporal context.")

  (plan-receipt
    [provider db request]
    "For an inbound (receipt) move: what layer to create. Returns
       {:layer-data {:qty bigdec :unit-cost bigdec
                     :commodity eid :item eid :lot eid?}}
     Most providers just echo the input; standard-cost adjusts unit
     cost to the book's standard price and emits a price-variance
     posting in :extra-postings."))
```

### Why two methods, not one

Anglo-Saxon vs Continental accounting differs *specifically* in *when COGS is recognized*. Anglo-Saxon defers COGS until sale → the receipt creates a layer at supplier price; the issue triggers FIFO/AVG and posts to COGS. Continental recognizes the expense at issue regardless of method. Both methods are needed to express the bi-directional flow cleanly; a single function would couple receipt and issue logic awkwardly.

### Why a protocol, not a multimethod

Mirrors the existing kernel pattern (`TaxProvider`, `EInvoiceProvider`, `PacProvider`). Consumers can `extend-protocol` from outside the kernel without modifying kernel namespaces. Multimethods would be equivalent but inconsistent with the existing seams.

### Kernel-shipped implementations

- `FIFOCostingProvider` — picks oldest unconsumed layers first
- `LIFOCostingProvider` — picks newest unconsumed layers first
- `WeightedAverageProvider` — distributes across all available layers at their current weighted-average unit cost
- `StandardCostProvider` — uses the book's `:valuation-book/standard-cost` (a sibling entity that this provider consults); generates `:variance` Money + a `:extra-postings` entry to a price-variance account when actual ≠ standard

Anglo-Saxon and Continental variants are not separate impls — they're the same FIFO/AVG logic with different account-resolution rules at the posting-builder layer (ADR-030).

### Alternatives considered

- *Single function with a `:method` kw arg.* Rejected: protocol dispatch is more discoverable + extensible.
- *Hard-code methods in `kontor.posting`.* Rejected: consumers need to plug in jurisdiction-specific or custom engines without forking the kernel.
- *Async / streaming for very large layer scans.* Rejected as YAGNI: bitemporal datalog with indexes on `:valuation-layer/book + item` covers reasonable scale. Revisit when a real workload demands it.

### Implications

- New protocol in `kontor.costing-provider`.
- Four concrete impls in same namespace.
- Tests exercise each impl on a 3-receipt-2-issue scenario; standard-cost test verifies variance Money + extra posting.

Date: 2026-05-11.

---

## ADR-030 — `plan-stock-move`: pure posting-builder for inventory transactions

**Decision.** Add `kontor.posting/plan-stock-move` — a pure function (well, db-aware-pure: it reads a db value but does not transact) that takes a stock-move spec + a `CostingProvider` + an account-resolution callback and returns datahike tx-data ready for `d/transact`. Mirrors the existing `kontor.posting/build-transaction` but for inventory moves.

Hooks live *outside* the function via the existing provider-protocol seam — the costing-provider and the account-resolver are the seams. The function itself stays pure.

### Signature (high-level)

```clojure
(defn plan-stock-move
  "Plan the kernel-level facts for a stock movement.

   Input
     db            — datahike db value
     move-spec     — {:direction :in | :out
                      :book      <:valuation-book ref or code>
                      :item      <ref>
                      :qty       <bigdec>
                      :unit-cost <bigdec, only required for :in>
                      :commodity <:commodity ref>
                      :lot       <ref, optional>
                      :journal   <:journal ref>
                      :effective-date <#inst>
                      :narration <string, optional>}
     provider      — CostingProvider impl
     account-fn    — fn taking the move-spec + a :role kw
                     (`:inventory`, `:cogs`, `:variance`, `:in-transit`,
                      `:scrap-expense`, …) and returning an account ref

   Output  — tx-data vector (the kernel :transaction + :postings
             + :valuation-layer or :layer-consumption + optional
             :layer-adjustment entities, all stitched via tempids).
   Pure: reads `db`, returns data. Does not transact."
  [db move-spec provider account-fn])
```

### Why pure

The kernel's existing posting builder is pure. Inventory shouldn't break that contract. Composition stays clean:
```clojure
(d/transact conn (plan-stock-move (d/db conn) move-spec provider account-fn))
```

The function reads `db` because it needs to resolve layer eids to plan consumption, but it never writes — same shape as datalog queries.

### Hooks

Per Frappe / Tryton evidence, hooks should live at the boundary (registered externally), not be parameters to the kernel function. The `CostingProvider` and `account-fn` ARE the hooks. Consumers wanting to mutate the planned tx-data wrap the function:
```clojure
(let [base (plan-stock-move db move-spec provider account-fn)
      enriched (my-custom-pre-post-hook base move-spec)]
  (d/transact conn enriched))
```

### Anglo-Saxon vs Continental

Different `account-fn` implementations. Same `CostingProvider`. The account resolver knows which jurisdiction → which account roles fire on receipt vs issue:
- Anglo-Saxon receipt: `:inventory` + `:gr-ir-clearing`
- Anglo-Saxon issue (sale): `:cogs` + `:inventory`
- Continental receipt: `:inventory` + `:gr-ir-clearing` (same)
- Continental issue (consumption): `:material-expense` + `:inventory`

The kernel doesn't model jurisdictions; the inventory module's account resolver does.

### Implications

- One new function in `kontor.posting`.
- Tests exercise: simple receipt → balanced 2-line tx with a new layer; simple issue → balanced 2-line tx with N consumption events; standard-cost receipt with variance → balanced 3-line tx (inventory at standard, gr-ir at actual, variance for the delta).
- Parallel-book test: same physical move under FIFO and Standard books emits different cost amounts and different variance lines — verifying ADR-027's orthogonality claim.

Date: 2026-05-11.

---

## ADR-031 — `:entity` entity: per-(entity, ledger, commodity) sum-to-zero for transnational books

**Decision.** Introduce a first-class `:entity` entity representing a legal accounting unit (subsidiary, branch, consolidation parent, elimination subsidiary). Optional `:entity` refs on `:posting`, `:ledger`, `:valuation-book`. Sum-to-zero invariant extends to per-(entity, ledger, commodity) when any posting in a transaction carries `:posting/entity`; falls back to per-(ledger, commodity) otherwise. Backward-compatible with tenant-scoped (single-entity) deployments.

### Why now

The kernel covers double-entry postings (ADR-021), parallel ledgers (ADR-021), parallel valuation books (ADR-027), country/state geography (ADR-023), bitemporal queries (ADR-008). It does not represent the legal-entity dimension that every transnational deployment needs. A group with Acme-Germany-GmbH + Acme-US-Inc + Acme-Brazil-Ltda + Acme-Group-AG can be modeled today only by running four separate tenants — which makes cross-entity queries (intercompany matching, group consolidation) impossible from inside one kontor database.

Per the four-ERP survey (SAP, Oracle Fusion, NetSuite, D365), the entity is a load-bearing dimension that attaches to the GL *line item* — `ACDOCA.RBUKRS` (SAP), `XLA_AE_LINES.LEGAL_ENTITY_ID` (Oracle), `Transaction.subsidiary` per line (NetSuite), `DataAreaId` per-LE on the voucher (D365). Cross-entity transactions are an enforced cross-cutting case in all four: **each entity's footprint in a multi-entity transaction must balance independently** (SAP `OBYA` clearing accounts, Oracle Intercompany Balancing Rules, NetSuite Advanced Intercompany Journal Entries, D365 posting profiles per intercompany combination).

### How competitors model this

- **SAP S/4HANA.** Company Code (`BUKRS`) — the canonical accounting unit with country, currency, chart of accounts, fiscal year variant, tax procedure. Company (one level above company code) groups codes for consolidation. Group Reporting and Universal Parallel Accounting layer on top. ACDOCA carries `RBUKRS` on every line; cross-company-code transactions auto-balance via OBYA clearing-account pairs.
- **Oracle Fusion ERP Cloud.** Legal Entity is first-class. Primary Ledger defined by the four C's (chart, calendar, currency, convention). Each LE accounts for itself in a primary ledger; Business Units (operational) sit below. Intercompany Balancing Rules balance per-LE within a document.
- **NetSuite OneWorld.** Subsidiary is the entity. Hierarchical via parent links. Each subsidiary has its own base currency, nexus, optionally per-subsidiary CoA. Elimination Subsidiaries hold consolidation eliminations and must share their parent's base currency + country.
- **Microsoft D365 Finance.** Legal Entity = `DataAreaId`. Exactly one ledger per LE. Intercompany via posting profiles per LE-pair; consolidation runs into a dedicated consolidation legal entity.
- **Odoo.** `res.company` with `parent_id` + `parent_path` for hierarchy; partner-delegated address/VAT. Accounts use `company_ids` M2M (shared CoA, per-company code overlay). `account.move` requires `company_id`.
- **Tryton.** `company.company` with strict per-company FK on every accounting model. No parent hierarchy in core. `Move.consolidation_company` field marks elimination postings filtered out of subsidiary GL queries.

### What kontor adopts

The kernel-narrow path. From the convergent pattern across six reference systems:

- `:entity` is a top-level entity, not nested under `:partner`. Partners stay global; entity scoping is independent.
- Per-entity ledgers (one entity → many ledgers; never a ledger spanning entities). Consolidation is a *separate entity* with its own ledger, not a ledger that aggregates across entities.
- Per-entity valuation books.
- Entity ref lives on the GL *line item* (`:posting/entity`), not the document header. A multi-entity transaction has lines tagged with different entities; the document spans them.
- Hierarchy via `:entity/parent-entity` (Odoo's pattern; Tryton lacks it but every transnational deployment needs it).
- Mark synthetic entities (consolidation rollups, elimination subsidiaries) via `:entity/kind` — NetSuite's load-bearing pattern; prevents accidental real postings into a virtual entity.

### Schema

```clojure
:entity/code                  string :db.unique/identity   ; "acme-de" "acme-us"
                                                           ; "acme-group" "acme-elims"
:entity/name                  string
:entity/country               ref → :country  (optional)
:entity/functional-commodity  ref → :commodity (optional;
                                                nil for synthetic entities)
:entity/parent-entity         ref → :entity   (optional; group hierarchy)
:entity/accounting-standard   keyword         (optional; :hgb :us-gaap
                                                :br-gaap :ifrs :local …)
:entity/kind                  keyword                       ; :operating (default)
                                                           ; | :elimination
                                                           ; | :consolidation
:entity/active                boolean

;; Optional refs on existing kernel entities. Schema-optional;
;; multi-entity-required (see Invariant below).
:posting/entity         ref → :entity
:ledger/entity          ref → :entity
:valuation-book/entity  ref → :entity
```

### Invariant — per-(entity, ledger, commodity) sum-to-zero

Mode is decided by the *postings*, not the transaction:

- **Single-entity mode** (no posting carries `:posting/entity`): existing per-(ledger, commodity) invariant from ADR-021 holds unchanged. Backward-compatible.
- **Multi-entity mode** (every posting carries `:posting/entity`): sum-to-zero must hold per-(entity, ledger, commodity) triple. A cross-entity intercompany document satisfies the invariant by carrying matching intercompany-clearing postings on each side.
- **Mixed mode** (some postings have entity refs, some don't): **rejected** as a validation error. The ambiguity is real — defaulting one way or the other would mask data-quality bugs.

The fallback / detection logic lives in `kontor.posting/validate`, sibling of the existing per-ledger grouping. No transaction-header flag needed; the postings' shape determines the mode.

### Bootstrap

No kernel-level bootstrap. Unlike `:ledger` (primary auto-installed via ADR-021) and `:valuation-book` (primary auto-installed via ADR-027), the kernel ships no default entity. Single-entity tenants opt out of the multi-entity dimension entirely by never assigning `:posting/entity` — and the invariant degrades to today's per-ledger shape. Multi-entity tenants install their own entity tree as data, typically alongside chart-of-accounts setup.

### Alternatives considered

- *`:transaction/entity` header ref.* Rejected per the four-ERP survey: ACDOCA places entity on the line, not the document. Header redundancy creates "does the header override the line?" ambiguity. Drop.
- *`:partner/entity` to scope partners per-entity.* Rejected: every reference system keeps partners global with per-entity overlays. SAP's BP-general / FI-per-company-code split is the most elaborate, and even it is a partner-side overlay, not a scoping field. If a future workflow needs to associate a partner with an internal entity (intercompany counterparty), add then.
- *`:ledger/entity` required at schema level.* Rejected: would force every existing tenant to seed an entity before running a single posting. Matches the `:posting/ledger` decision in ADR-021: schema-optional, semantic-required-for-multi-entity-use.
- *Required `:functional-commodity`.* Rejected: synthetic entities (consolidation, elimination) may not have a functional currency — the consolidation view runs in a group currency, the elimination subsidiary inherits its parent's. Mark optional.
- *`:entity/functional-calendar` for per-entity fiscal year variants.* Defer to ADR-032. SAP/NetSuite/D365 all support it; this needs to interact with the existing period model (ADR-014), and bundling makes the slice too big.
- *Ship intercompany / consolidation / CTA generation in the kernel.* Out of scope: these are consumer-side workflows that compose kernel primitives. The `:entity/kind` distinction is enough kernel surface; the workflow lives in a future `kontor-consolidation` module.
- *Multi-company chart of accounts via M2M (Odoo) vs strict per-company FK (Tryton).* Neither — kontor's existing `:account` is tenant-scoped and that's fine. Per-entity account *codes* live in `:account/external-codes` (ADR-019) keyed by regulator, which is more flexible than either approach. Consumers wanting a "global account, per-entity code" model use external-codes; consumers wanting "physically separate accounts per entity" install separate `:account` entities and link via external-codes.

### Implications

- New schema: 8 attrs on `:entity` + 3 optional refs = 11 attributes.
- `kontor.entity` namespace ships with: `by-code`, `resolve-entity`, `descendants` (hierarchy traversal). No auto-bootstrap.
- `kontor.posting/validate` extended for mixed-mode detection + per-entity balance check.
- `kontor.posting/balance-by-ledger-and-commodity` becomes `balance-by-entity-ledger-and-commodity` when entity refs are present; preserves the old shape otherwise.
- Tests cover: single-entity (unchanged), full multi-entity (intercompany balances per entity), mixed-mode rejection, hierarchy queries (descendants of a parent entity), `:entity/kind` enum coverage.
- Forward-compat: `kontor-consolidation` (future module) can ship intercompany workflow, CTA computation, and group-consolidation reports without further kernel work.

Date: 2026-05-11.

---

## ADR-032 — `:schedule` entity for recurring postings + `:cost-center` analytic plan bootstrap

**Decision.** Add a kernel-level `:schedule` entity representing a sequence of dates at which a recurring posting fires. Add a kernel-level `:cost-center` analytic plan, bootstrapped at install time (sibling of the `primary` ledger and `primary` valuation book). Both are *cross-cutting primitives* that surfaced in six independent companion-project research lines and would otherwise be reinvented per consumer.

### Why these two together

Both came out of the survey of business-OS companion projects (research note 10) and HR / personnel (research note 09). Bundling because:

- They're tiny — together about 10 attrs + 1 new entity + 1 install-time data row.
- They land BEFORE any companion ships, so the companions can rely on them without coordination.
- They have no cross-dependencies; landing one without the other works.

### `:schedule` — what surfaced

Six unrelated companion projects all need *the same* primitive: a recurring schedule that emits a posting per period. Today, each would invent its own:

| Companion | Use case |
|---|---|
| `kontor-asset` | Monthly depreciation — `Dr Depreciation Expense / Cr Accumulated Depreciation` per period |
| `kontor-revrec` | Over-time revenue recognition (ASC 606 / IFRS 15) — `Dr Contract Asset / Cr Revenue` per period |
| `kontor-subscription` | Recurring SaaS billing — `Dr AR / Cr Deferred Revenue` per recurrence |
| `kontor-lease` (future) | IFRS 16 / ASC 842 lease-liability amortization per period |
| `kontor-hr` | PTO accrual reversals (`Dr Wage Expense / Cr PTO Liability` per accrual cycle) |
| `kontor-insurance` (future) | Insurance premium amortization (`Dr Insurance Expense / Cr Prepaid Insurance` monthly) |

The convergent shape: an entity carrying a recurrence rule + a target template, with a side-table of *materialized occurrences* (one per fired posting). The kernel ships the entity + a `:schedule-occurrence` log; consumers ship the rule-evaluation engine appropriate to their domain.

### `:schedule` schema

```clojure
:schedule/code             string :db.unique/identity
:schedule/name             string
:schedule/kind             keyword                       ; :depreciation
                                                         ; | :revenue-recognition
                                                         ; | :subscription-billing
                                                         ; | :lease-amortization
                                                         ; | :pto-accrual
                                                         ; | :prepaid-amortization
                                                         ; | … free-form
:schedule/origin-entity    ref                            ; the asset / contract /
                                                         ; subscription / etc. that
                                                         ; this schedule belongs to
                                                         ; (generic ref — consumer
                                                         ; defines what an :asset is)
:schedule/start-date       instant
:schedule/end-date         instant                        ; optional; nil = indefinite
:schedule/frequency        keyword                       ; :daily | :weekly | :monthly
                                                         ; | :quarterly | :annual
                                                         ; | :custom
:schedule/total-amount     bigdec                        ; total to amortize (optional;
                                                         ; for finite schedules)
:schedule/total-commodity  ref → :commodity
:schedule/state            keyword                       ; :active | :paused
                                                         ; | :completed | :cancelled
:schedule/active           boolean
:schedule/note             string

;; Each firing of the schedule produces one :schedule-occurrence,
;; immutable and timestamped. The occurrence links to the
;; kernel :transaction it produced.
:schedule-occurrence/schedule        ref → :schedule
:schedule-occurrence/sequence        long                 ; 1, 2, 3, …
:schedule-occurrence/scheduled-date  instant
:schedule-occurrence/transaction     ref → :transaction
:schedule-occurrence/amount          bigdec               ; this period's amount
:schedule-occurrence/commodity       ref → :commodity
:schedule-occurrence/fired-at        instant              ; when the posting was made
:schedule-occurrence/identity        tuple [schedule, sequence] unique
                                                          ; idempotency: re-firing
                                                          ; period 7 collapses to one
                                                          ; record
```

### What the kernel does NOT model

Out of scope (each consumer ships its own):

- **The rule-evaluation engine** — how amounts per period are computed (straight-line vs declining-balance vs units-of-production depreciation; recurring vs over-time revrec; tiered vs flat subscription billing). The consumer computes the amount; the kernel records the occurrence.
- **The trigger** — who runs the schedule (a cron job, a manual close-period step, an interactive UI). The kernel just records `:fired-at`.
- **The posting shape** — the DR/CR accounts. The consumer's posting-builder decides. The kernel just stores the resulting `:transaction` ref.

### `:cost-center` analytic plan

Bootstrap at kernel install (sibling of primary ledger + primary valuation book):

```clojure
{:analytic-plan/code   "cost-center"
 :analytic-plan/name   "Cost centers"
 :analytic-plan/applicability :optional
 :analytic-plan/active true}
```

Every companion will lean on this plan:
- HR: cost-center on `:employment` (research note 09)
- Project: cost-center on timesheet line (which is just an analytic-line per Odoo pattern)
- Manufacturing: work-center on production order
- Asset: cost-center on depreciation schedule
- Fleet: vehicle as analytic dimension under cost-center

Consumers seed the actual `:analytic-account` values (the specific cost centers); the plan is in place from kernel install.

### Bootstrap

`kontor.core/install-schema!` gains one more transact step:

```clojure
{:analytic-plan/code "cost-center"
 :analytic-plan/name "Cost centers"
 :analytic-plan/applicability :optional
 :analytic-plan/active true}
```

Idempotent via `:db.unique/identity` on `:analytic-plan/code`.

No bootstrap data for `:schedule` itself — schedules are entirely consumer-installed.

### Alternatives considered

- *No `:schedule` entity; let each companion define its own (e.g. `:depreciation-schedule`, `:revrec-schedule`).* Rejected: six independent inventions of the same shape; kernel queries can't ask "show me all schedules firing this period" across consumers; no shared idempotency story for re-firing a missed occurrence.
- *Bake the rule-evaluation engine into the kernel.* Rejected: each domain has its own math (depreciation methods, ASC 606 input vs output method, subscription proration). The kernel doesn't know which method applies; consumers do. The `CostingProvider` analogue would be a `ScheduleProvider`, but most consumers use one well-known method per schedule-kind and don't need a runtime-pluggable engine.
- *Make `:schedule-occurrence` an attribute on `:transaction` rather than a separate entity.* Rejected: a single occurrence may fail to fire (period closed) and need re-firing later; it's a first-class lifecycle entity, not a posting attribute.
- *Tightly couple `:schedule` to a specific origin-entity type (e.g. require it to point at `:asset`).* Rejected: the same schedule shape covers six unrelated origin entities. Generic ref keeps the kernel scope-honest.

### Implications

- New schema: 9 attrs on `:schedule` + 8 attrs on `:schedule-occurrence` = 17 attrs total.
- New install-time data: 1 row (the `cost-center` analytic plan).
- `kontor.core/install-schema!` gains one transact.
- `kontor.schedule` namespace ships with: `next-occurrence-date`, `pending-occurrences`, `fire-occurrence!` (records the occurrence + kernel transaction), `by-code`, idempotency helpers.
- Each companion's posting-builder consumes the schedule for its domain math; the kernel just records what happened.
- Forward-compat: a future cross-companion "schedule monitor" can show all pending occurrences across asset / revrec / subscription / lease in one view.

Date: 2026-05-12.

---

## ADR-033 — `kontor-partner`: party-as-root model with polymorphic contact mechanisms

**Decision.** Land `kontor-partner` as the first business-OS companion (under `modules/partner/`). Extend the kernel's existing `:partner/*` namespace with subtype entities (`:person`, `:org`), a polymorphic contact-mechanism root with typed subtypes (`:contact-mech` + `:postal-address` / `:telecom-number` / `:email-address`), a temporal junction (`:partner-contact-mech`), a multi-purpose routing junction (`:partner-contact-mech-purpose`), capability roles (`:partner-role`), and temporal multi-role relationships (`:partner-relationship`). Vocabularies for role / purpose / relationship type are documented Clojure keywords; consumers extend. The companion ships its own schema-install fn so kernel-only consumers stay minimal.

### Why this companion, why now

Six downstream companions (sales, procurement, asset, revrec, subscription, hr) reference parties as customers / suppliers / employees / sponsors / vendors. Without a unified party model, each invents its own, the kernel's `:posting/partner` ref points at a moving target, and intercompany scenarios under ADR-031 can't be expressed cleanly. The OFBiz/Tryton/Workday/SAP-BP convergent shape (research note 12) gives us a license-clean reference oracle in Apache-2.0 — we can read AND lift structure without translation. Per the hybrid-plan checkpoint, partner gets the 3-4 weeks of depth required before sales lands on top of it.

### Why party-as-root with subtypes (not single entity, not no-root)

The four-ERP survey converges on the same shape: a single root entity owns identity / status / audit / external-id, and Person / Organization subtypes carry their own attributes via FK to the root. OFBiz uses `partyTypeId` discriminator; Tryton uses `party.party` + subclasses; Workday models Person + Org as Worker subtype hierarchy; SAP's Business Partner extends to BP-Person / BP-Org. Odoo's single-table-with-flag model (`res.partner.is_company`) is the outlier — it makes per-type queries verbose (`[where is_company=true]` everywhere) and lets attributes drift across the typing boundary (a person can accidentally carry `ticker_symbol`).

Concrete advantages of subtype entities for kontor:
- Attribute domains stay separate at the schema layer (`:person/birth-date` cannot accidentally land on an org).
- Datalog queries on type are direct (`[?p :person/partner ?party]`) rather than filtered by flag.
- Sensitive PII (SSN, passport) lives on `:person` and can be encrypted or excluded from index dumps without touching `:org` traffic.
- The Workday "Worker has many Employments" pattern lands naturally when kontor-hr extends `:person` later.

### Why polymorphic contact-mech (not typed entities, not embedded scalars)

OFBiz's ContactMech root + typed subtype (PostalAddress / TelecomNumber / etc.) is the canonical interchange shape for vCard 4.0 and Peppol Business Card. It supports:
- Cross-type queries ("all contact mechs for partner X regardless of type").
- Cross-type purpose routing ("the BILLING contact for partner X" — could be email OR postal).
- Verification flags + temporal validity at the junction layer, independent of mechanism type.
- vCard / Peppol round-trip: their canonical model is structurally identical, so import / export becomes a 1:1 mapping rather than a flattening / re-inflation.

Embedded scalars on `:partner` (one address + one phone) would be simpler but break Peppol/vCard round-trip and forbid multi-purpose routing. Typed entities (separate `:postal-address` / `:phone` / `:email` without a shared root) would force every junction to use polymorphic refs and require schema-time enumeration of acceptable subtype targets — datahike can model this but at the cost of cross-type-aware queries.

### Why role / purpose / relationship-type as keywords (not entities)

OFBiz models RoleType, ContactMechPurposeType, and PartyRelationshipType as recursive enum tables — supporting i18n labels, parent-child hierarchy, and group filtering. For kontor v1 this is over-engineering: the canonical role / purpose / relationship vocabularies are small fixed sets that rarely change, and per-locale labels live in the consumer UI layer (not in the kernel data). We model them as documented Clojure keyword sets, with consumers free to extend their own keywords. If a future requirement surfaces (e.g. role grouping for permission inheritance, i18n at the data layer), promote to entities at that point — the keyword-to-entity migration is mechanical.

The canonical vocabulary documented in this ADR:

**Role types** (`:partner-role/role-type`):
`:customer` `:supplier` `:employee` `:contractor` `:carrier` `:bill-to` `:ship-to` `:bill-from` `:ship-from` `:internal-organization` `:owner` `:agent` `:warranty-provider` `:beneficiary` `:guarantor` `:end-user`

**Contact-mech purposes** (`:partner-contact-mech-purpose/purpose-type`):
`:general-correspondence` `:billing-location` `:shipping-location` `:primary-location` `:primary-email` `:billing-email` `:order-email` `:primary-phone` `:billing-phone` `:fax-number` `:home-location` `:work-location` `:mobile-phone` `:emergency-contact`

**Relationship types** (`:partner-relationship/relationship-type`):
`:employment` `:contractor-engagement` `:subsidiary` `:branch` `:partnership` `:agent-representation` `:reseller-channel` `:franchise` `:family` `:vendor-customer` `:successor-predecessor` `:trust-beneficiary`

### Schema (companion-installed)

The companion installs the following attribute set on top of the kernel schema. The kernel's existing `:partner/*` attrs (external-id, name, kind, country-code, tax-id, state — already present, see ADR-002 and ADR-023) remain unchanged; new attributes extend the `:partner/*` namespace and add the new namespaces below.

#### Root: partner extensions

```clojure
:partner/type            keyword          ; :person | :org — discriminator
:partner/status          keyword          ; :enabled | :disabled | :archived
:partner/preferred-commodity ref          ; :commodity ref (currency of choice)
:partner/created-at      instant
:partner/modified-at     instant
:partner/description     string
```

#### Person subtype

```clojure
:person/partner          ref :db.unique/value  ; FK to :partner (1:1)
:person/first-name       string
:person/middle-name      string
:person/last-name        string
:person/salutation       string
:person/suffix           string
:person/nickname         string
:person/first-name-local string           ; non-Latin script
:person/last-name-local  string
:person/gender           keyword          ; :male | :female | :nonbinary | :unspecified — free-form
:person/birth-date       instant
:person/deceased-date    instant
:person/marital-status   keyword          ; :single | :married | :divorced | :widowed | :partnered | :unspecified
:person/national-id-type keyword          ; :ssn | :passport | :national-id | :tin | etc.
:person/national-id      string           ; encrypted at consumer layer if regulated
```

#### Organization subtype

```clojure
:org/partner             ref :db.unique/value  ; FK to :partner (1:1)
:org/legal-name          string           ; the formal registered name
:org/legal-form          keyword          ; :gmbh | :llc | :inc | :sa | :ltd | etc.
:org/trading-name        string           ; "doing business as"
:org/registration-number string           ; e.g. HRB, EIN, ABN
:org/duns                string           ; D-U-N-S 9-digit identifier
:org/lei                 string           ; Legal Entity Identifier (ISO 17442)
:org/ticker-symbol       string
:org/exchange            string           ; stock exchange where listed
:org/annual-revenue      bigdec
:org/revenue-commodity   ref → :commodity
:org/num-employees       long
:org/incorporation-date  instant
:org/dissolution-date    instant
```

#### Polymorphic contact-mech root

```clojure
:contact-mech/code       string :db.unique/identity   ; opaque consumer-supplied code
:contact-mech/type       keyword                       ; :postal | :telecom | :email | :web | :ftp
:contact-mech/info-string string                       ; fallback untyped storage
:contact-mech/created-at instant
:contact-mech/modified-at instant
```

#### Postal address subtype

```clojure
:postal-address/contact-mech ref :db.unique/value      ; FK to :contact-mech (1:1)
:postal-address/to-name      string
:postal-address/attn-name    string
:postal-address/address1     string
:postal-address/address2     string
:postal-address/house-number string
:postal-address/house-number-ext string
:postal-address/directions   string
:postal-address/city         string
:postal-address/postal-code  string
:postal-address/postal-code-ext string
:postal-address/county       string
:postal-address/region       string                    ; state/province as free string
:postal-address/state        ref → :state              ; structured state (ADR-023)
:postal-address/country      ref → :country            ; structured country (ADR-023)
:postal-address/latitude     bigdec
:postal-address/longitude    bigdec
```

#### Telecom subtype

```clojure
:telecom-number/contact-mech     ref :db.unique/value  ; FK
:telecom-number/country-code     string
:telecom-number/area-code        string
:telecom-number/contact-number   string
:telecom-number/extension        string
:telecom-number/ask-for-name     string                ; routing hint
```

#### Email subtype

```clojure
:email-address/contact-mech ref :db.unique/value       ; FK
:email-address/address      string                     ; the email address itself
:email-address/verified?    boolean
:email-address/bounced?     boolean
```

#### Partner-contact-mech junction (temporal)

```clojure
:partner-contact-mech/partner      ref → :partner
:partner-contact-mech/contact-mech ref → :contact-mech
:partner-contact-mech/from-date    instant
:partner-contact-mech/thru-date    instant
:partner-contact-mech/role-type    keyword            ; optional role context
:partner-contact-mech/allow-solicitation? boolean
:partner-contact-mech/verified?    boolean
:partner-contact-mech/comments     string
:partner-contact-mech/identity     tuple [partner, contact-mech, from-date] unique
```

#### Partner-contact-mech-purpose junction (multi-purpose)

```clojure
:partner-contact-mech-purpose/partner      ref → :partner
:partner-contact-mech-purpose/contact-mech ref → :contact-mech
:partner-contact-mech-purpose/purpose-type keyword
:partner-contact-mech-purpose/from-date    instant
:partner-contact-mech-purpose/thru-date    instant
:partner-contact-mech-purpose/identity     tuple [partner, contact-mech, purpose-type, from-date] unique
```

#### Partner-role (capability)

```clojure
:partner-role/partner    ref → :partner
:partner-role/role-type  keyword
:partner-role/from-date  instant
:partner-role/thru-date  instant
:partner-role/identity   tuple [partner, role-type, from-date] unique
```

#### Partner-relationship (temporal, multi-role)

```clojure
:partner-relationship/partner-from       ref → :partner
:partner-relationship/partner-to         ref → :partner
:partner-relationship/role-type-from     keyword
:partner-relationship/role-type-to       keyword
:partner-relationship/from-date          instant
:partner-relationship/thru-date          instant
:partner-relationship/relationship-type  keyword     ; :employment | :subsidiary | etc.
:partner-relationship/status             keyword     ; :active | :inactive | :pending
:partner-relationship/relationship-name  string      ; "Senior Engineer", "Wholly-owned"
:partner-relationship/position-title     string
:partner-relationship/priority           long
:partner-relationship/comments           string
:partner-relationship/identity           tuple [partner-from, role-type-from, partner-to, role-type-to, from-date] unique
```

### Composition model

A typical partner setup:

```clojure
;; A person who is both customer and employee, working at an org
{:partner/external-id "P-1001"
 :partner/type        :person
 :partner/status      :enabled}

;; Subtype payload
{:person/partner    [:partner/external-id "P-1001"]
 :person/first-name "Jane"
 :person/last-name  "Doe"
 :person/birth-date #inst "1985-03-12"}

;; Two roles, both currently active
{:partner-role/partner   [:partner/external-id "P-1001"]
 :partner-role/role-type :customer
 :partner-role/from-date #inst "2024-01-15"}

{:partner-role/partner   [:partner/external-id "P-1001"]
 :partner-role/role-type :employee
 :partner-role/from-date #inst "2025-06-01"}

;; One email serving two purposes (billing + general)
{:contact-mech/code "CM-jane-1"
 :contact-mech/type :email}

{:email-address/contact-mech [:contact-mech/code "CM-jane-1"]
 :email-address/address      "jane@example.com"
 :email-address/verified?    true}

{:partner-contact-mech/partner      [:partner/external-id "P-1001"]
 :partner-contact-mech/contact-mech [:contact-mech/code "CM-jane-1"]
 :partner-contact-mech/from-date    #inst "2024-01-15"
 :partner-contact-mech/verified?    true}

{:partner-contact-mech-purpose/partner      [:partner/external-id "P-1001"]
 :partner-contact-mech-purpose/contact-mech [:contact-mech/code "CM-jane-1"]
 :partner-contact-mech-purpose/purpose-type :billing-email
 :partner-contact-mech-purpose/from-date    #inst "2024-01-15"}

;; Employment relationship to an org
{:partner-relationship/partner-from      [:partner/external-id "P-1001"]
 :partner-relationship/role-type-from    :employee
 :partner-relationship/partner-to        [:partner/external-id "O-2001"]
 :partner-relationship/role-type-to      :internal-organization
 :partner-relationship/relationship-type :employment
 :partner-relationship/from-date         #inst "2025-06-01"
 :partner-relationship/position-title    "Senior Engineer"
 :partner-relationship/status            :active}
```

### Bootstrap

No bootstrap data. The companion ships only the schema-install fn — `(kontor.partner.schema/install! conn)` transacts the attrs above and returns the tx-report. Consumers call this once after `kontor.core/install-schema!` (in any order; idempotent via `:db/ident`).

### Public surface

The `kontor.partner` namespace ships:
- Resolution: `by-external-id`, `resolve-partner` (string→eid, eid→eid, nil→nil)
- Subtype access: `person`, `org` (pulled subtype map, or nil)
- Role queries: `roles-of`, `has-role?`, `partners-with-role`, `partners-with-role-as-of` (bitemporal)
- Contact-mech queries: `contact-mechs-of`, `contact-mech-by-purpose`, `primary-email`, `primary-postal-address`
- Relationship traversal: `relationships-of`, `relationships-from`, `relationships-to`, `active-relationships-as-of`, `current-employer`, `current-employees`
- Effective-date helpers: `active-as-of?` (junction-level predicate respecting from-date / thru-date)

Bitemporal-aware (ADR-008): every query helper accepts an `:as-of-valid` parameter that filters junction validity. Default is `now`.

### Alternatives considered

- **Single `:partner` entity with conditional attrs** (Odoo `res.partner` shape). Rejected: cross-type drift, verbose flag-filtering queries, sensitive PII colocated with org marketing data. The keystroke savings don't compensate.
- **Two top-level entities (`:person` + `:org`, no shared `:partner` root)**. Rejected: every kernel ref site (`:posting/partner`, future `:order/customer`, etc.) would need polymorphic refs across two targets, breaking simple datalog joins. Also breaks intercompany scenarios where the same entity is sometimes referred to as a customer and sometimes as a supplier.
- **Heavy contact-mech (separate typed entities, no polymorphic root)**. Rejected: breaks the multi-purpose routing pattern (purpose junctions would need polymorphic refs across address/phone/email targets), and breaks vCard/Peppol round-trip cleanliness.
- **Embedded simple contact on `:partner`** (one scalar address + one scalar phone). Rejected: forbids multi-purpose, breaks interchange, doesn't survive real-world tenants with billing-distinct-from-shipping setups. Pragmatic for an MVP but not for the foundational companion.
- **Role / purpose / relationship-type as entities** (with parent-child + i18n labels). Deferred. v1 uses keyword vocabularies; promote later if needed.
- **Versioned `:partner` snapshots via bitemporal valid-time on the root entity**. Rejected: OFBiz doesn't model this — root attributes are mutable, and history lives in datahike's tx-time axis (ADR-008). Junction-level temporal validity (`from-date`/`thru-date`) is enough; full bitemporal partner snapshots are a heavyweight pattern with no near-term consumer demand.
- **Cardinality-many on the subtype FK** (allow one party to be both Person AND Org). Rejected: real-world partners are exclusively one OR the other; cardinality-one + `:db.unique/value` enforces the constraint at the schema layer. If a future regulatory hybrid case appears (sole proprietorship oddly modeled), it would warrant its own discriminator value.

### Implications

- ~95 new attributes across 11 namespaces, all isolated under their own keyword namespaces (zero collision with kernel attrs or other consumers).
- The kernel's `:partner/*` namespace gains 6 attrs (type discriminator, status, preferred-commodity, created-at, modified-at, description) — these live in the companion schema and are additive, so existing kernel-only consumers see no breaking change.
- `:posting/partner` references continue to point at `:partner` root — unchanged semantics.
- The companion schema is opt-in: kernel-only consumers do not call `kontor.partner.schema/install!` and are unaffected.
- Forward-compat with downstream companions:
  - kontor-sales: `:order/customer` → `:partner` (via :partner-role :customer).
  - kontor-procurement: `:requirement/vendor` → `:partner` (via :partner-role :supplier).
  - kontor-asset: `:asset/owner-partner` → `:partner` (via :internal-organization role).
  - kontor-hr: `:employment/person` → `:person`; `:employment/employer` → `:org` (via :partner-relationship :employment).

### Vendor / customer rendering on existing posting flow

Posting still references the root (`:posting/partner`). For UX, consumers fetch subtype data + active roles + primary contact mechs via the `kontor.partner` helpers. There's no breaking change to ADR-002 (cohabitation) or ADR-020 (multi-party / multi-role on transactions).

Date: 2026-05-12.

---

## ADR-034 — `:status-transition` cross-cutting primitive for entity state machines

**Decision.** Add a kernel-level `:status-transition` entity representing one legal state transition for one entity-type-and-facet (with optional per-org scope), and a `:status-history` entity recording each actual transition with audit metadata. Together they provide a declarative, queryable, bitemporal state-machine primitive that every workflow companion (sales, invoice, procurement, return, payment, requirement) uses without reinventing per-domain transition tables. The kernel ships zero seeds — consumers install their own vocabulary.

### Why now, why kernel-level

Five companion modules in the immediate pipeline (sales, invoice, procurement, return, payment) and at least three more in research (revrec, subscription, asset) need entity state machines. Without a shared primitive, each invents its own — six independent transition-table inventions, no cross-entity queries ("show me all entities currently in a `:rejected` state"), no shared idempotency story, no shared audit-history schema. Following the same reasoning as ADR-032 (`:schedule`), promote the abstraction to the kernel when six independent inventions would otherwise emerge.

The OFBiz study (research note 12 expanded by the pass-2 state-machine study) revealed that OFBiz itself uses a **single generic `StatusValidChange` table** for OrderHeader, OrderItem, Invoice, Quote, Return, Shipment, Payment, CustRequest, and Requirement — eleven different entity types, one transition matrix. Sylius uses YAML config files per state graph. Both approaches encode "transitions are data, not code"; the OFBiz pattern is more flexible (per-tenant overrides, runtime queryable). Kontor's bitemporal datalog gives us a third path that beats both: per-org override + "what transitions were allowed last quarter?" for free.

### What the new primitive provides

**`:status-transition`** — the legal-transitions table. One row per allowed (entity-type, facet, from, to) combination. Optional org scope lets a tenant deviate from the default vocabulary.

**`:status-history`** — the audit-trail entity. One row per actual transition: the entity that transitioned, which facet, from-state, to-state, when, by whom, why, with optional ref to the originating transaction. This is the OFBiz `OrderStatus` pattern, generalized.

**`kontor.status-machine`** namespace — pure-function predicates over those entities:
- `legal-transition?` — is `from → to` allowed for this entity-type+facet, considering org scope?
- `legal-transitions-from` — what states is `from` allowed to move to?
- `record-status-change!` — convenience transactor: writes the entity's facet attribute AND the history row in one tx, after checking legality.
- `status-history-of` — pulled history rows for an entity, ordered oldest-first.
- `current-status` — read the current facet value (typically equivalent to a one-attr pull).

### Schema

```clojure
;; The transition table — one row per legal (entity-type, facet, from, to).
:status-transition/entity-type    keyword          ; :order | :order-item | :invoice |
                                                   ;   :requirement | :shipment | …
:status-transition/facet          keyword          ; the attribute on the entity that
                                                   ; carries this state — typically
                                                   ; :order/status, :invoice/status,
                                                   ; :order-item/status, etc.
:status-transition/from           keyword          ; from-state. Use :*/nil sentinel
                                                   ; for the "new entity" pseudo-state.
:status-transition/to             keyword
:status-transition/name           string           ; human-readable transition name
                                                   ; ("Approve Order", "Mark Paid")
:status-transition/applies-to-org ref → :entity    ; optional; nil = applies to all orgs
:status-transition/active         boolean          ; soft-delete without dropping audit
:status-transition/identity       tuple [entity-type, facet, from, to, applies-to-org]
                                                   ; unique — one row per combination

;; The audit-trail table — one row per actual transition.
:status-history/entity            ref              ; the entity that transitioned
:status-history/entity-type       keyword          ; denorm of the entity's type for
                                                   ; cross-entity queries
:status-history/facet             keyword
:status-history/from              keyword          ; nil for entity creation
:status-history/to                keyword
:status-history/changed-at        instant          ; valid-time (when the transition
                                                   ; occurred semantically, distinct
                                                   ; from datahike's :db/txInstant)
:status-history/changed-by-uid    ref → :create/uid
:status-history/reason            string           ; free-text rationale
:status-history/origin-transaction ref → :transaction ; optional — links to the
                                                      ; kernel transaction that
                                                      ; caused the change
```

### Vocabulary semantics

- **`:status-transition/facet`** — every entity that participates in a state machine has at least one *facet*: the attribute carrying that state. `:order/status` is one facet of `:order`; `:order-item/status` is a facet of `:order-item`. One entity can have multiple facets — e.g., a future revenue-contract entity could have `:contract/lifecycle-status` AND `:contract/payment-status` AND `:contract/recognition-status` as three independent state machines on the same entity. This matches Sylius's four-facet decomposition while keeping the underlying table generic.

- **`:status-transition/from`** — for the "new entity" pseudo-state (when an entity has no prior state datom), use the `:*/nil` keyword convention (e.g. `:order.status/nil`). Avoids datahike's awkward handling of `nil` as a tx value.

- **`:status-transition/applies-to-org`** — when nil, the row applies tenant-wide. When set, the row scopes to that org (an `:entity` ref per ADR-031). The lookup is "match either the org-specific row OR the global default" — an org-specific override does not require deleting the global. This is the OFBiz `ProductStore.headerApprovedStatus` pattern, generalized over `:entity`.

- **`:status-history/changed-at` vs `:db/txInstant`** — `changed-at` is the *valid-time* of the transition (when, semantically, the order moved to APPROVED — typically `now`, but can be backdated for migration). `:db/txInstant` is the kernel-managed tx-time (when, physically, the datom was committed). Per ADR-008 bitemporality, these are independent.

- **No required `:transaction` link.** Some transitions happen without a kernel `:transaction` (e.g., setting an order to `:on-hold` while fraud-check runs). When a transition IS the result of a posting tx (e.g., `:invoice/status → :posted` happens because the AcctgTrans was created), the consumer should populate `:status-history/origin-transaction` for the audit chain.

### Public surface

```clojure
(ns kontor.status-machine
  (:require [datahike.api :as d]))

(defn legal-transition?
  "True iff the (entity-type, facet, from, to) transition is allowed
   for the given org. Org is nil for tenant-wide queries; when set,
   prefers an org-specific override but falls back to the global row."
  ([db entity-type facet from to] (legal-transition? db entity-type facet from to nil))
  ([db entity-type facet from to org] …))

(defn legal-transitions-from
  "Set of states `from` is allowed to move to for this entity-type+facet
   (optionally scoped by org)."
  [db entity-type facet from] …)

(defn record-status-change!
  "Convenience transactor. In one tx:
     1. Verifies the transition is legal (throws if not).
     2. Sets the entity's facet attribute to `to`.
     3. Writes a :status-history row with the audit metadata.
   Returns the tx-report."
  [conn {:keys [entity entity-type facet from to changed-at
                changed-by-uid reason origin-transaction]}] …)

(defn status-history-of
  "Pulled :status-history rows for `entity`, oldest first. Optionally
   filtered by facet."
  [db entity] …)
```

### Bootstrap

Schema is installed by `kontor.core/install-schema!` (idempotent, runs once at kernel install). **No seed data.** Consumers install their own transition vocabulary:

- kontor-sales seeds order + order-item transitions (Stage J, ADR-035).
- kontor-invoice seeds invoice transitions (Stage J, ADR-036).
- kontor-procurement seeds requirement + receipt transitions (Stage K).
- Future companions seed their own.

Multiple consumers can co-seed the same table without collision (composite identity tuple ensures idempotency).

### Relationship to existing `kontor.state-machine`

The kernel already ships `kontor.state-machine` (`src/kontor/state_machine.clj`), which encodes the `:transaction/state` lifecycle (`:draft → :pending-attestation? → :posted → :cancelled`) as a hardcoded Clojure map. That namespace ALSO enforces semantic guards specific to transactions: `:posted` requires `:posted-at` in the same tx (so sealing markers stay coherent per ADR-007), pending-attestation interacts with EInvoiceProvider workflow (ADR-018).

The existing namespace stays as-is. Migrating `:transaction/state` to the generic `:status-transition` table would lose those domain-specific guards (or push them into a custom validator on top of the table, which is uglier). Future work could promote a "transition-with-guards" subprotocol that combines the table with a guard fn, but that's not in scope here.

New companion entities (`:order/status`, `:invoice/status`, `:requirement/status`, etc.) use the new generic machinery. The kernel ships two state-machine mechanisms side by side: domain-specific (`kontor.state-machine` for transactions) and generic-table-driven (`kontor.status-machine` for everything else). Eventually one wins; for now both pay rent.

### Alternatives considered

- **Hardcoded per-entity Clojure transition maps.** This is what the existing `kontor.state-machine` does for transactions. Rejected as the general approach: doesn't scale to 11+ entity types, can't be queried at runtime, can't be per-org overridden without re-deploying, no shared audit-history schema.
- **YAML / EDN config files per state graph (Sylius pattern).** Rejected: kontor already has a database, no reason to introduce a parallel config artifact. The "config is data, data is config" argument works in our favor — make it a datahike entity from the start.
- **Database-level CHECK constraints for legal transitions.** Datahike doesn't have CHECK constraints in the relational sense, and even if it did, the "validate before transact" pattern (our `legal-transition?` predicate) is more composable: callers can dry-run a transition, branch on its legality, etc.
- **`:status-history` as a partial datahike index of `:db/txInstant` over `:order/status` writes.** Tempting because datahike's tx-time history is "free". Rejected: tx-time captures *when the datom was written*, not the semantic "when the change applied" (valid-time), and lacks the audit fields (`changed-by-uid`, `reason`, `origin-transaction`). The history row is a domain concept, not an index artifact.
- **Per-state guard fns registered at compile time.** Sylius's callbacks-on-transition pattern. Rejected for the kernel primitive; the table is dumb data. Domain-specific guards live in the consumer's transition-helper (e.g., `kontor.sales/approve-order!` checks reservation status before calling `record-status-change!`).
- **Composite tuple identity `[entity-type, facet, from, to, applies-to-org]`** vs separate uniqueness assertions. Picked tuple for simplicity; the alternative (entity-type-and-facet level uniqueness with a separate org-scoping table) would split one entity into two and complicate queries.

### Implications

- 9 new attrs on `:status-transition` + 8 attrs on `:status-history` = 17 attrs total in the kernel schema.
- New `kontor.status-machine` namespace with 5 public fns.
- Test coverage: legal-transition? matrix queries, illegal-transition rejection, history append idempotency, org-scoped override semantics, bitemporal history queries (as-of-tx + valid-time on `:status-history/changed-at`).
- Forward-compatible: future "cross-entity in-state-X dashboard" queries become a single datalog query on `:status-history`. Same with "transitions that historically occurred between dates X and Y."
- The kontor-sales (ADR-035) and kontor-invoice (ADR-036) companions seed this table in their `install!` fn. Idempotent across multiple installs.
- The existing `kontor.state-machine` for `:transaction/state` is untouched and continues to work. Future deprecation possible but deferred.

Date: 2026-05-12.

---

## ADR-035 — `kontor-sales`: order machinery (header, items, ship groups, adjustments, roles)

**Decision.** Land `kontor-sales` as the second foundation companion (under `modules/sales/`). Provide the order aggregate: `:order/*` header (with type discriminator `:sales | :purchase`), `:order-item/*` lines, the `:ship-group/*` + `:ship-group-assoc/*` + `:inv-reservation/*` fulfillment-plan triple, `:order-adjustment/*` (multi-level via single `:scope` ref, Sylius-pattern), and `:order-role/*` (partner role on order, per ADR-033 vocabulary). Reservation happens at order creation (not approval), with per-item opt-outs. State machine: two facets (`:order/status` and `:order-item/status`) driven by ADR-034's `:status-transition` table, seeded by the companion's `install!`.

This companion **does not** handle the order→invoice bridge or the AcctgTrans posting — those land in `kontor-invoice` (ADR-036). It also **does not** handle the procurement-specific extensions (requirement entity, 3-way match, RTV) — those land in `kontor-procurement` (Stage K). It DOES introduce `:order/type :sales` AND `:order/type :purchase` as the discriminator, on the OFBiz pattern that one order model serves both; procurement just adds the requirement-and-receipt extensions on top.

### Why now, why split from invoice

The OFBiz study (research note 12 pass 2) confirmed that the order surface is substantial (12+ namespaces if you bundle the invoice bridge). The Sylius study reinforced this. Per the Stage J planning question (3-way matrix), splitting `kontor-sales` + `kontor-invoice` is justified because kontor-procurement (Stage K) will reuse the invoice machinery for vendor invoices — without the split, procurement either duplicates the bridge or imports unrelated sales code. The kernel already ships `:invoice/*` + `:invoice-line/*` (lines 1191-1370 in `src/kontor/schema.clj`); `kontor-invoice` will extend those with order-bridge fields, not reinvent the invoice entity.

### Schema

#### Order header

```clojure
:order/external-id    string :db.unique/identity   ; consumer-supplied opaque ID
:order/type           keyword                       ; :sales | :purchase — discriminator
                                                    ; (kontor-procurement adds the
                                                    ; :purchase-only extensions)
:order/status         keyword                       ; :order.status/{created,approved,
                                                    ;   completed,cancelled,rejected,
                                                    ;   hold} — facet driven by ADR-034
:order/order-date     instant
:order/entry-date     instant
:order/currency       ref → :commodity              ; default currency for the order
:order/bill-from-partner ref → :partner             ; vendor / seller
                                                    ; (for :sales, the org; for
                                                    ; :purchase, the supplier)
:order/bill-to-partner   ref → :partner             ; customer / buyer
:order/grand-total    bigdec                        ; denormalized; recomputed on
                                                    ; adjustment changes
:order/invoice-per-shipment? boolean                ; default false: one invoice on
                                                    ; ORDER_COMPLETED. True: one
                                                    ; invoice per shipment.
:order/priority       long                          ; inventory-reservation priority;
                                                    ; higher = grabs stock first
:order/agreement-id   string                        ; optional reference to a contract
:order/description    string
:order/note           string
```

#### Order item

```clojure
:order-item/order        ref → :order               ; back-ref
:order-item/seq-id       string                     ; sequence number in the order
:order-item/identity     tuple [order, seq-id] unique
:order-item/type         keyword                    ; :product | :service | :rental |
                                                    ; :digital | … free-form
:order-item/product-id   string                     ; consumer-supplied product ref
                                                    ; (no :product entity in kernel)
:order-item/description  string
:order-item/quantity     bigdec
:order-item/unit-price   bigdec                     ; price per unit, in :order/currency
:order-item/unit-list-price bigdec                  ; MSRP for discount display
:order-item/discount-rate bigdec                    ; line-level discount %
:order-item/cancel-quantity bigdec                  ; partial-cancel quantity
:order-item/status       keyword                    ; :order-item.status/{created,
                                                    ;   approved,completed,cancelled,
                                                    ;   rejected} facet (ADR-034)
:order-item/auto-reserve? boolean                   ; default true; false skips
                                                    ; reservation at creation
:order-item/reserve-after-date instant              ; optional; defer reservation
:order-item/estimated-ship-date instant
:order-item/estimated-delivery-date instant
:order-item/override-gl-account ref → :account      ; explicit GL override
:order-item/cost-center  ref → :analytic-account    ; ADR-032 cost-center plan
```

#### Ship group

```clojure
:ship-group/order        ref → :order
:ship-group/seq-id       string
:ship-group/identity     tuple [order, seq-id] unique
:ship-group/shipment-method-type keyword            ; :standard | :express | :overnight | …
:ship-group/carrier-partner ref → :partner          ; carrier with :partner-role :carrier
:ship-group/facility-id  string                     ; source warehouse (no :facility
                                                    ; entity yet — consumer-supplied)
:ship-group/contact-mech ref → :contact-mech        ; ship-to address from ADR-033
:ship-group/tracking-number string
:ship-group/shipping-instructions string
:ship-group/gift-message string
:ship-group/may-split?   boolean                    ; default true
:ship-group/is-gift?     boolean
:ship-group/ship-after-date instant
:ship-group/ship-by-date instant
:ship-group/estimated-ship-date instant
:ship-group/estimated-delivery-date instant
```

#### Ship-group association (item ↔ destination)

```clojure
:ship-group-assoc/order ref → :order
:ship-group-assoc/order-item ref → :order-item
:ship-group-assoc/ship-group ref → :ship-group
:ship-group-assoc/quantity bigdec
:ship-group-assoc/cancel-quantity bigdec
:ship-group-assoc/identity tuple [order-item, ship-group] unique
```

#### Inventory reservation (per-lot)

```clojure
:inv-reservation/order ref → :order
:inv-reservation/order-item ref → :order-item
:inv-reservation/ship-group ref → :ship-group
:inv-reservation/lot ref → :lot                      ; existing kernel :lot
:inv-reservation/quantity bigdec
:inv-reservation/quantity-not-available bigdec       ; backorder tracking
:inv-reservation/reserve-order-enum keyword          ; :fifo | :lifo | :priority
:inv-reservation/reserved-datetime instant
:inv-reservation/promised-datetime instant           ; original promise (immutable)
:inv-reservation/current-promised-date instant       ; latest revised promise
:inv-reservation/priority? boolean
:inv-reservation/identity tuple [order-item, ship-group, lot] unique
```

#### Order adjustment

```clojure
:order-adjustment/order ref → :order                 ; always set
:order-adjustment/scope ref                          ; ONE ref to :order, :order-item,
                                                     ; or :ship-group — the "what
                                                     ; this adjustment applies to"
                                                     ; (Sylius polymorphic pattern,
                                                     ; not OFBiz's three nullable FKs)
:order-adjustment/type keyword                       ; :discount | :tax | :shipping |
                                                     ; :surcharge | :promotion |
                                                     ; :tax-vat-included | …
:order-adjustment/amount bigdec
:order-adjustment/recurring-amount bigdec            ; for subscription-style
:order-adjustment/source-percentage bigdec           ; tax rate, when applicable
:order-adjustment/tax-auth-party ref → :partner      ; jurisdiction (ADR-016)
:order-adjustment/tax-auth-geo-id string             ; jurisdiction code
:order-adjustment/override-gl-account ref → :account ; explicit GL routing
:order-adjustment/include-in-tax? boolean            ; default true; controls
                                                     ; whether THIS adjustment is
                                                     ; in the BASE for OTHER tax
                                                     ; calculations
:order-adjustment/include-in-shipping? boolean       ; default true
:order-adjustment/is-manual? boolean                 ; default false; manuals survive
                                                     ; recalc passes
:order-adjustment/neutral? boolean                   ; default false; if true, does
                                                     ; NOT contribute to grand total
                                                     ; (for included-VAT-style flags)
:order-adjustment/origin-code string                 ; back-ref to source (promotion
                                                     ; code, tax-rate code)
:order-adjustment/note string
```

#### Order role (partner on order)

```clojure
:order-role/order ref → :order
:order-role/partner ref → :partner
:order-role/role-type keyword                        ; per ADR-033 vocabulary:
                                                     ; :customer | :supplier |
                                                     ; :bill-to | :ship-to |
                                                     ; :end-user | :carrier | …
:order-role/identity tuple [order, partner, role-type] unique
```

### State machines (seeded by `install!`)

Order facet (`:order/status`):

```
:order.status/nil       → :order.status/created
:order.status/created   → :order.status/approved | :order.status/hold |
                          :order.status/rejected | :order.status/cancelled
:order.status/hold      → :order.status/approved | :order.status/cancelled
:order.status/approved  → :order.status/completed | :order.status/cancelled
:order.status/completed → :order.status/approved              (re-open exception per OFBiz)
:order.status/rejected  → ()                                   (terminal)
:order.status/cancelled → ()                                   (terminal)
```

Item facet (`:order-item/status`):

```
:order-item.status/nil       → :order-item.status/created
:order-item.status/created   → :order-item.status/approved | :order-item.status/cancelled |
                               :order-item.status/rejected
:order-item.status/approved  → :order-item.status/completed | :order-item.status/cancelled
:order-item.status/completed → :order-item.status/approved   (re-open)
```

The companion's `install!` writes these as `:status-transition` rows. Multiple consumers sharing the table cohabit cleanly via the composite-tuple identity.

### Public surface

`kontor.sales` namespace ships:

- **Builders**: `make-order`, `add-item`, `add-ship-group`, `allocate-to-ship-group`, `add-adjustment`, `assign-role`. Each returns tx-data; transact yourself or use `create-order!` to do it in one call.
- **Reservation**: `reserve-order-inventory!` called by `create-order!` (unless `:order-item/auto-reserve?` is false). Per-item dispatch.
- **Status transitions**: `approve-order!`, `cancel-order!`, `hold-order!`, `complete-order!`, `reject-order!`, `set-item-status!`. Each wraps `kontor.status-machine/record-status-change!` with order-specific side effects (e.g. release reservations on cancel).
- **Promotion**: `check-and-promote-header!` — the OFBiz-pattern fn that scans items and promotes the header when all items reach a common terminal state.
- **Recalc**: `recalculate-order!` — the CompositeOrderProcessor pipeline; clears + reapplies adjustments by ordered processor chain. Pure-function processors registered via `register-processor!`.
- **Pulls / queries**: `pull-order`, `items-of`, `ship-groups-of`, `adjustments-of`, `reservations-of`, `roles-of`, `partner-on-order` (lookup by role-type).

### Bootstrap

The companion's `install!` does three things:
1. Transacts the `kontor.sales.schema/all` schema.
2. Seeds the order + order-item state machines into the kernel `:status-transition` table.
3. Idempotent — composite identities ensure re-running adds no datoms.

### Alternatives considered

- **Combined sales + invoice + procurement.** Rejected per Stage J planning (the OFBiz study's own recommendation): would force kontor-procurement to either reach into kontor-sales for invoice bridge code, or duplicate it. Splitting now avoids that churn.
- **OFBiz's three nullable FKs on OrderAdjustment** (`orderItemSeqId`, `shipGroupSeqId`). Rejected per Sylius study verdict: datahike's ref attributes don't care about target type, so a single `:scope` ref is cleaner. Queries on "all line-level adjustments" become `[?adj :order-adjustment/scope ?scope] [?scope :order-item/...]` — the join expresses the level.
- **Reservation at approval, not creation.** Rejected per OFBiz pattern: holding stock during fraud-check prevents stockouts on legitimate sales. The `:order-item/auto-reserve?` opt-out covers consumers who want to defer.
- **Four facets per Sylius (state + payment + shipping + checkout).** Rejected per the Stage J planning answer: payment-state and shipping-state are derived queries, not stored facets. Two facets (`:order/status` + `:order-item/status`) cover the load-bearing state-machine surface. Checkout state lives in the consumer's UI layer, not the accounting kernel.
- **Hardcoded transition table in Clojure (per `kontor.state-machine` pattern).** Rejected: ADR-034 is the right primitive. Seeding the table costs ~12 tx ops and makes the vocabulary queryable + per-org overridable for free.
- **Single `:adjustment` entity reused across order / invoice / quote.** Tempting (one schema for the multi-level discount/tax/shipping pattern), but the entity references differ (orders point at `:order`, invoices at `:invoice`, quotes at a future `:quote`). Picked separate `:order-adjustment` and let `kontor-invoice` introduce `:invoice-item-adjustment` similarly. If we ever do a `kontor-quote`, the same pattern applies. The schemas LOOK similar but each owns its own back-ref.

### Implications

- ~70 new attributes across 7 namespaces, all in `modules/sales/src/kontor/sales/schema.clj` (opt-in install).
- Per-(order, partner, role-type) composite identity prevents duplicate role assignments.
- Per-(order-item, ship-group) and per-(order-item, ship-group, lot) composite identities enforce the fulfillment-plan shape.
- The recalc pipeline (CompositeOrderProcessor) is a sequence of pure-function processors; consumers register tax / promotion / shipping processors that compose. The kernel ships a no-op default; tax recalc lives in `kontor-l10n-*` per ADR-005.
- Forward-compat: kontor-procurement (Stage K) adds `:requirement/*` + `:receipt/*` + the 3-way match on top, reusing `:order/*` with `:order/type :purchase`.
- kontor-invoice (ADR-036) reads `:order-item-billing/*` to track per-(order-item, invoice) quantities for partial invoicing.

Date: 2026-05-12.

---

## ADR-036 — `kontor-invoice`: order→invoice bridge, status machine, AcctgTrans posting

**Decision.** Land `kontor-invoice` as the third Stage J companion (under `modules/invoice/`). Extend the kernel's existing `:invoice/*` + `:invoice-line/*` schema with: `:invoice/order` (back-ref to the originating order from kontor-sales), `:invoice/type` discriminator (`:sales | :purchase | :credit-memo | :debit-memo`), `:invoice/posted-at` sealing marker, `:invoice/entity` (multi-entity scope per ADR-031), `:invoice-line/parent-line` (self-ref for adjustment-of-line), `:invoice-line/order-item` (bridge for partial-invoicing math), `:invoice-line/gl-account-type` (keyword discriminator for posting), `:invoice-line/tax-auth-party` + `:invoice-line/tax-auth-geo-id` (per-line jurisdiction), `:invoice-line/amount` (line total). Introduce `:order-item-billing` junction (tracks invoiced-quantity per `:order-item` ↔ `:invoice-line` pair for partial-invoice arithmetic). Introduce `:gl-account-default` table (per-(account-type, entity) → `:account` lookup, two-tier OFBiz pattern). Seed the invoice status machine (`:draft → :ready → :sent → :paid` plus `:cancelled` escape) into the kernel's ADR-034 `:status-transition` table. Ship `kontor.invoice.posting/post-to-ledger!` — the order→invoice→AcctgTrans bridge with three-tier GL resolution (`:invoice-line/override-account` → product-specific → `:gl-account-default`).

### Why a separate companion

The OFBiz study (research note 12 pass 2) recommended splitting `kontor-sales` and `kontor-invoice` because `kontor-procurement` (Stage K, next stage) will reuse the invoice machinery for vendor invoices. Without the split, procurement either reaches into sales for invoice bridge code or duplicates it. The kernel already ships `:invoice/*` + `:invoice-line/*` (lines 1191-1363 in `src/kontor/schema.clj`) — `kontor-invoice` extends those with order-aware fields, not reinvents the entity.

A user asking "where does my customer invoice live?" gets one answer in kontor: the kernel `:invoice` entity (uniform shape for B2B / B2G via Factur-X / XRechnung). Where does the order-link live, the posting bridge, the line-level GL routing? The `kontor-invoice` companion. Where does the sales-order shape live? `kontor-sales`. Where does the procurement requirement shape live? `kontor-procurement`. The split is along the lines that real-world customer scenarios already separate.

### Status machine

The kernel's existing `:invoice/status` attribute (a free `:db.type/keyword` already documented in the kernel with values `:draft | :sent | :paid | :cancelled`) gets a `:ready` intermediate state. Vocabulary stays as **bare keywords** to avoid collision with the kernel's existing direct writers (e.g. `kontor.l10n-de.invoice/send!` writes `:invoice/status :sent` directly). Seeded as ADR-034 `:status-transition` rows by the companion's `install!`:

```
:nil    → :draft       (Create Invoice)
:draft  → :ready       (Finalize — locks edits)
:draft  → :sent        (Post — direct, skips ready)
:draft  → :cancelled   (Abandon Draft)
:ready  → :sent        (Post)
:ready  → :cancelled   (Cancel Ready)
:sent   → :paid        (Settle)
:sent   → :cancelled   (Void Posted Invoice → reversal tx)
:paid   → :cancelled   (Refund flow)
```

**Semantics** (preserve existing kernel meaning):
- `:draft` — invoice under construction; no GL effect.
- `:ready` (new) — invoice frozen for edits; awaiting GL posting. Optional intermediate; consumers that batch-process can skip it.
- `:sent` — AcctgTrans created. The `:invoice/posted-at` and the kernel `:invoice/transaction` ref are populated. From the customer's perspective, the invoice is now "out the door." The kernel's existing `:invoice/sent-at` marker still applies.
- `:paid` — settled (kernel's existing transition via reconciliation).
- `:cancelled` — voided. If posted, requires a reversal `:transaction` per ADR-007 sealing.

**No retroactive enforcement on existing kernel callers.** The l10n-de invoice flow (`modules/l10n-de/src/kontor/l10n_de/invoice.clj`) and any other direct writers continue to work — they bypass the status machine by writing the attribute directly. Only callers that use `kontor.invoice/*` helpers go through `kontor.status-machine/record-status-change!` and get the enforcement + audit history. This matches ADR-007's sealing pattern: middleware enforces; raw datahike calls can bypass.

### Schema (companion-installed)

#### Invoice extensions (additive to kernel)

```clojure
:invoice/type        keyword          ; :sales | :purchase | :credit-memo |
                                      ; :debit-memo. Discriminator that
                                      ; kontor-procurement uses to route
                                      ; AP-side invoices.
:invoice/order       ref → :order     ; Optional. Set when invoice is
                                      ; created from a sales/purchase order.
                                      ; Nil for standalone bills.
:invoice/posted-at   instant          ; Sealing marker — when AcctgTrans
                                      ; was created. Distinct from
                                      ; :invoice/sent-at (which marks the
                                      ; "out the door to customer" event,
                                      ; per kernel doc).
:invoice/entity      ref → :entity    ; Multi-entity scope per ADR-031.
                                      ; Required for multi-entity tenants;
                                      ; optional for single-entity.
:invoice/invoice-per-shipment-of ref → :ship-group ; Optional. When set,
                                      ; this invoice is for ONE specific
                                      ; ship group of an order (the OFBiz
                                      ; invoicePerShipment pattern).
```

#### Invoice-line extensions (additive to kernel)

```clojure
:invoice-line/parent-line   ref → :invoice-line  ; Self-ref. When set,
                                                  ; this line is a derived
                                                  ; line of its parent (e.g.
                                                  ; a tax line attached to
                                                  ; a product line).
:invoice-line/order-item    ref → :order-item    ; The kontor-sales line
                                                  ; this invoice line was
                                                  ; created from. Set on
                                                  ; the bridge call. Used
                                                  ; for partial-invoice
                                                  ; tracking (subtract
                                                  ; already-billed quantity
                                                  ; on next invoice).
:invoice-line/order-adjustment ref → :order-adjustment ; The adjustment
                                                  ; (discount / tax /
                                                  ; surcharge) this line
                                                  ; was derived from. Set
                                                  ; for adjustment lines.
:invoice-line/gl-account-type keyword            ; :sales-revenue |
                                                  ; :sales-tax-payable |
                                                  ; :shipping-income |
                                                  ; :discount-given | …
                                                  ; Posting-time discriminator
                                                  ; for the GL account
                                                  ; lookup.
:invoice-line/tax-auth-party ref → :partner      ; Jurisdiction (ADR-016).
:invoice-line/tax-auth-geo-id string             ; Tax authority code
                                                  ; (e.g. "DE", "US-CA").
:invoice-line/amount        bigdec               ; Line total (denorm of
                                                  ; quantity × unit-price
                                                  ; for goods lines; just
                                                  ; "amount" for adjustment
                                                  ; lines where quantity
                                                  ; doesn't apply).
```

#### `:order-item-billing` junction (new entity)

```clojure
:order-item-billing/order-item   ref → :order-item
:order-item-billing/invoice-line ref → :invoice-line
:order-item-billing/quantity     bigdec             ; the quantity billed
                                                    ; on this invoice line
:order-item-billing/identity     tuple [order-item, invoice-line] unique
```

For an order item invoiced in two passes (e.g. 7 units now, 3 units later when the remaining stock arrives), there are two `:order-item-billing` rows pointing at two different `:invoice-line` rows. The partial-invoice query "how much of this order-item has been invoiced so far?" sums `:order-item-billing/quantity` across all rows for that order-item.

#### `:gl-account-default` (new entity, OFBiz GlAccountTypeDefault pattern)

```clojure
:gl-account-default/account-type keyword             ; :sales-revenue |
                                                     ; :cogs | :ar | :ap |
                                                     ; :sales-tax-payable | …
:gl-account-default/entity       ref → :entity       ; Optional. When nil,
                                                     ; tenant-wide default;
                                                     ; when set, scopes to
                                                     ; that org.
:gl-account-default/account      ref → :account
:gl-account-default/identity     tuple [account-type, entity] unique
```

The three-tier GL resolution order at posting time:
1. **Explicit override**: if `:invoice-line/account` (the kernel's existing field) is set, use it.
2. **Entity-specific default**: query `:gl-account-default` with `(account-type, :invoice/entity)`.
3. **Tenant-wide default**: query `:gl-account-default` with `(account-type, nil)`.
4. **Fail** with a `:invoice/missing-gl-default` exception if all three miss.

This matches OFBiz's `UtilAccounting.getProductOrgGlAccountId` algorithm (per the OFBiz study), simplified by dropping the per-product layer (kontor doesn't ship a product entity yet; consumers can add it later as a fourth tier).

### Posting bridge

`kontor.invoice.posting/post-to-ledger!` runs as **one atomic transaction**:

1. Resolves the invoice currency to a `:commodity` entity-id (throws `:invoice/unknown-commodity` if the kernel's `:invoice/currency` string doesn't match a seeded commodity).
2. Requires a `:journal-ref` opt — the kernel posting invariant (ADR-021) rejects journal-less transactions. Consumers seed their own journal (e.g. `[:journal/code "SALES"]`).
3. Defaults `:ledger-ref` to `kontor.ledger/primary` if not supplied. Multi-ledger tenants override per posting.
4. Builds the input map for `kontor.posting/build-transaction`, with each `:posting` carrying `:posting/commodity`, `:posting/ledger`, `:posting/account`, `:posting/amount` (signed per `(invoice-type, gl-account-type)` debit/credit map), `:posting/partner`, and optionally `:posting/entity` (multi-entity scope per ADR-031). The contra-side (AR for sales, AP for purchase) is computed as `(- sum line-postings)` and added.
5. Sets `:transaction/state :posted` and `:transaction/posted-at` together (ADR-007 sealing contract).
6. Composes the kernel posting tx-data with the invoice update (`:invoice/transaction` + `:invoice/posted-at`) AND the status-history row for `:draft|:ready → :sent` (via `kontor.status-machine/record-status-change-tx-data`, the pure-function variant of `record-status-change!`) into ONE tx-data vector.
7. Asserts the period isn't locked (`kontor.period/assert-not-in-locked-period!` on the full tx-data; ADR-014 enforcement — throws `:period/locked-period-violation` otherwise).
8. Calls `d/transact` once. Sum-to-zero per ADR-021 + ADR-031 (per-(entity, ledger, commodity)) is enforced by `build-transaction`'s `validate` pass; if the bridge mis-builds postings, the kernel rejects before commit.

The atomicity matters: if any step fails (status-machine rejects, period locked, sum-to-zero violation, missing GL account), nothing is committed. There's no half-state where the transaction exists but `:invoice/transaction` is unset, or where the invoice is `:sent` but no AcctgTrans was created.

The debit/credit direction map `(invoice-type, gl-account-type) → :debit | :credit` is closed today and lives in `kontor.invoice.posting/debit-credit-for`. Adding new account-types (e.g. `:revenue-deferred` for kontor-revrec, `:goods-receipt-accrual` for kontor-procurement) requires editing this map; moving it to a data table is a tracked P1 followup.

### Public surface

**Namespace naming note**: The kernel already ships a `kontor.invoice` namespace (`src/kontor/invoice.clj`) with `create!` / `send!` / `mark-paid!` / `cancel!` for non-order-aware flows (used by `kontor-l10n-de`'s SKR04 invoice flow, etc.). To avoid collision, the **companion's public surface lives at `kontor.invoice.bridge`**, not `kontor.invoice`. Schema and posting helpers live at `kontor.invoice.schema` and `kontor.invoice.posting` — no collision there. This split also clarifies semantic scope: the kernel ns handles raw invoice CRUD; the bridge handles order-aware lifecycles.

`kontor.invoice.bridge` namespace:
- `by-external-id`, `resolve-invoice` — lookup helpers.
- `pull-invoice` — pulled invoice with lines + order ref + entity.
- `lines-of` — pulled :invoice-line rows ordered by `:invoice-line/sequence`.
- `total-of` — sum of `:invoice-line/amount` across lines (or kernel `:invoice/total-gross` if set).
- `partial-billed-quantity` — sum of `:order-item-billing/quantity` for a given order-item.
- Status: `make-ready!`, `post-to-ledger!`, `mark-paid!`, `cancel!`.
- Bridge: `make-invoice-from-order!` — given an `:order` eid + optional `:ship-group`, build `:invoice` + `:invoice-line` rows + `:order-item-billing` junctions. Returns tx-data; transact in one call.

`kontor.invoice.posting` namespace:
- `resolve-gl-account` — the three-tier resolver.
- `build-postings` — per-line debit/credit construction.
- `post-to-ledger!` — the orchestrating transactor.

### Bootstrap

The companion's `install!`:
1. Transacts the schema extensions.
2. Seeds the invoice status machine into the `:status-transition` table.
3. Optionally accepts a `:gl-defaults` seed map for tenant defaults (e.g. `{:sales-revenue [:account/path "4000"]}`); skip when caller seeds separately.

### Alternatives considered

- **No status machine; keep `:invoice/status` free-form.** Rejected: ADR-034's whole point is generic state machines; not using it for invoice would leave an obvious gap. The opt-in pattern (enforcement only via `kontor.invoice` helpers) means existing direct-writers aren't broken.
- **Rename `:invoice/sent-at` → `:invoice/posted-at` and merge semantics.** Rejected: the kernel docs `:sent-at` as "sent to customer," distinct from "posted to GL." The two events COULD happen at different times (post to GL on Friday, email the customer Monday). Add `:posted-at` as a peer attribute, not a rename.
- **Bake the GL-resolution into the kernel.** Rejected: GL account choices are per-tenant / per-jurisdiction (SKR04 numbering in DE, ACDOCA in SAP migrations, US GAAP charts). The `:gl-account-default` table lives in the companion (consumer seeds it).
- **Generic `:adjustment` reused across order + invoice + quote.** Rejected per ADR-035: the back-refs differ. Each companion owns its own adjustment shape, even though they're similar.
- **`:invoice-line/parent-line` as cardinality-many `:invoice-line/children`.** Rejected: self-ref child→parent is queryable both ways via datalog; parent→children would force every adjustment line to be back-referenced from the product line, doubling write cost. Pick the direction that minimizes writes.
- **One AcctgTrans per invoice (one `:transaction` for the whole invoice).** Adopted, per OFBiz. The alternative (one `:transaction` per `:invoice-line`) breaks sum-to-zero per-line, since taxes and revenue offset each other across the invoice. The invoice is the unit of posting; the line is the unit of GL-account routing.

### Implications

- ~20 new attributes across the kernel's existing `:invoice/*` + `:invoice-line/*` namespaces, plus 2 new entity namespaces (`:order-item-billing/*`, `:gl-account-default/*`). All opt-in via `(kontor.invoice.schema/install! conn)`.
- 9 new `:status-transition` rows seeded.
- The kernel's existing `:invoice/status` semantics expand to include `:ready` (new intermediate state).
- Forward-compat: kontor-procurement (Stage K) reuses everything by setting `:invoice/type :purchase`.
- Forward-compat: future kontor-credit-memo / kontor-refund flows reuse by setting `:invoice/type :credit-memo`.
- Test coverage: schema install, status-transition seeds, three-tier GL resolution (override / entity / tenant default / missing → throw), order→invoice bridge (full-shipment + partial-shipment + invoice-per-shipment), AcctgTrans posting (sum-to-zero balance, partner attribution, multi-currency basics), post-then-cancel reversal flow.

Date: 2026-05-12.

---

## ADR-037 — `kontor` as a business operating system: positioning, scope, non-goals

**Decision.** `kontor` is a **business operating system kernel** — a Clojure / datahike library that ships the load-bearing primitives every business application needs (accounting, parties, orders, invoices, status machines, schedules, multi-entity, multi-currency, multi-jurisdiction), with opt-in companion modules that compose on the kernel without bloating it. Not an ERP suite, not a hosted SaaS, not a workflow engine. The target consumer is a senior engineer building an accounting / ERP / financial workflow as a product OR as internal infrastructure, who wants the substrate solved correctly so they can focus on their domain logic. Consumer apps (beleg, simmis, custom apps) wrap kontor with end-user-facing UX. License: EPL-1.0. Single runtime: JVM Clojure.

This ADR consolidates the positioning that has been implicit across ADRs 001-036 and supersedes the strictest reading of ADR-010 ("no ERP modules forever"). ADR-010 was right about its narrow claim — kontor doesn't ship a Python-Odoo-style monolith with one install. ADR-010 was too narrow about its broader claim — kontor DOES ship opt-in ERP-shaped companion modules under `modules/<name>/`, and that's been clear since the partner / sales / invoice work landed under ADRs 033-036.

### What "business operating system" means here

A business operating system is the **set of primitives every business application needs at its core**, independent of the application's specific domain. A SaaS billing product, a marketplace, a B2B distributor, a freelancer platform, and a SOC2-audited internal accounting system all need:

- A double-entry general ledger with bitemporal queries, multi-entity, multi-currency, multi-jurisdiction.
- A party model (customer / supplier / employee / partner) with subtype attributes, role-based capabilities, temporal relationships.
- Order/invoice/receipt aggregates with status machines, partial-fulfillment, partial-billing, and audit history.
- Status machines + audit trail + approval policy that satisfy SOX / GDPR / ISO 27001 by construction.
- Schedule / recurrence primitives that drive depreciation, revenue recognition, subscription billing, PTO accrual.
- Inventory / valuation primitives with multi-method costing (FIFO / LIFO / weighted-average / standard).

`kontor` ships exactly these primitives as a kernel, and provides a curated set of companion modules (`kontor-partner`, `kontor-sales`, `kontor-invoice`, future `kontor-procurement`, `kontor-revrec`, etc.) that extend the kernel with domain-specific machinery. **The crucial distinction**: every companion is opt-in via its own `install!` fn. A consumer who only needs accounting installs just the kernel. A consumer who needs the full business OS installs the kernel plus the companions they want.

### Target consumer

`kontor` is engineered for **senior software engineers** building:
1. **Accounting / ERP as a product** — e.g. a B2B SaaS that needs real accounting as backend, a vertical SaaS replacing the customer's QuickBooks/Xero, an embedded financial workflow in a marketplace.
2. **Internal financial infrastructure** — a growing company that has outgrown QuickBooks/Xero but doesn't want to pay SAP/NetSuite tax (~$100k+/yr) for licenses + implementation.
3. **Domain-specific applications** that need accounting as backbone — an art-tracking platform, a non-profit grants-management system, a co-op cooperative ownership ledger, a property-management portal, a legal-services trust-accounting backend.

`kontor` is NOT engineered for **bookkeepers / accountants / non-technical end users**. They're served by the consumer applications that wrap kontor (beleg for SMB invoice management, simmis for ERP-shaped workloads, custom apps for vertical needs). The kernel API is datalog + Clojure; the user-facing experience belongs to the consumer layer.

### Differentiation vs existing options

| System | License | Runtime | Differentiation cost |
|---|---|---|---|
| **Odoo** | LGPLv3 | Python | License contagion (LGPL "linking" rules in court-untested edge cases discourage commercial composition); Python monolith; FSF-translation risk for code reuse; UI-coupled architecture. |
| **SAP S/4HANA** | Closed | ABAP/Java | $$$$ ; BP-migration pain (Datalark: 67% of S/4HANA migrations blow budget); vendor lock-in; multi-decade implementation cycles. |
| **OFBiz** | Apache-2.0 | Java | Active community shrinking; Minilang XML business logic; coupled to Java/JDBC stack; no first-class bitemporal. **Useful as reference oracle**, not as runtime. |
| **Tryton** | GPLv3 | Python | License contagion (GPL is stricter than LGPL); Python monolith; modular but unmaintained companion gaps. |
| **Salesforce** | Closed | Apex | $$$$ ; CRM-shaped (accounting bolt-on via 3rd party); flow/process-builder lock-in; vendor-specific scripting language. |
| **NetSuite** | Closed | JavaScript (SuiteScript) | $$$ ; OneWorld upsell for multi-entity (~$50k/yr+); SuiteFlow + SuiteGL plugins for customization; closed ecosystem. |
| **ERPNext** | GPLv3 | Python (Frappe) | License contagion; web-coupled architecture; multiple open Customer/Supplier-unification issues; documented workflow bugs. |
| **Stripe Billing** | Closed | (hosted) | Subscription-focused, not full accounting; hosted SaaS only; no multi-entity / multi-jurisdiction GL; pricing scales with volume. |
| **QuickBooks / Xero** | Closed | (hosted) | SMB-only; flat ceiling on complexity; no programmability; multi-currency clumsy; no real audit chain. |
| **Custom-built** | — | varies | The inevitable in-house accounting system every growing company builds and regrets — wrong abstraction, no double-entry rigor, lost in technical debt. kontor is the substrate that prevents the rebuild. |

`kontor`'s position:
- **Clojure on JVM** — single runtime; no Python/Java/JavaScript mix; immutable data; REPL-driven.
- **Datahike bitemporal** — every read takes `:as-of-tx` + `:as-of-valid`; audit history is a query parameter, not a separate ETL pipeline. ADR-008.
- **EPL-1.0** — permissive enough for commercial composition without LGPL/GPL contagion risk.
- **Single dependency** (datahike) + optional Mustang for e-invoicing, instaparse for Beancount, etc. Minimal classpath surface.
- **Multi-entity from kernel day one** (ADR-031) — no NetSuite OneWorld upsell; multi-currency / multi-ledger built into the posting model.
- **Status-as-data** (ADR-034) — vocabulary changes are a tx, not a deploy. SAP/NetSuite/Sylius/Camunda all require code or config redeployment.
- **Sealing by middleware** (ADR-007) — `posted = sealed against silent retract` is structural, not ACL.
- **Companion modules opt-in** — kernel-only consumers stay minimal; full-stack consumers get the substrate.
- **Curated reference-oracle stack** — Apache OFBiz (Apache-2.0) for procurement / sales / asset patterns; Sylius (MIT) for order state machines; KillBill (Apache-2.0) for subscription catalogue versioning. License-clean lifting of structural patterns.

### Minimum coherent module set

#### Kernel (always installed)

Core attributes + helper namespaces in `src/kontor/`:

- **Accounting kernel**: `:account`, `:transaction`, `:posting`, `:commodity`, `:lot`, `:journal`, `:partner` (basic), `:period`, `:balance-assertion`, `:fiscal-position`, `:tax`, `:tax-rep`, `:tax-group`, `:account-tag`, `:analytic-plan`, `:analytic-account`, `:analytic-distribution`, `:posting-analytic`. Helpers: `Money`, `posting/build-transaction`, `validation`, `sealing`, `audit`.
- **Multi-entity** (ADR-031): `:entity`, `:posting/entity`, `:ledger-entity`. Helpers: `kontor.entity/{by-code, descendants, family}`.
- **Multi-ledger** (ADR-021): `:ledger`, `:posting/ledger`. Helpers: `kontor.ledger/{primary, by-code, install-defaults!}`.
- **Bitemporal** (ADR-008): `:transaction/effective-date`, `:posting/valid-from`. Helpers: `kontor.query/{as-of-tx, as-of-valid}`.
- **Period** (ADR-014): `:period/locked-at`, `:period/sealed-at`, `:period/tag`. Helpers: `kontor.period/{open?, close!, seal!, assert-not-in-locked-period!}`.
- **Status machine** (ADR-034): `:status-transition`, `:status-history`. Helpers: `kontor.status-machine/{legal-transition?, record-status-change!, record-status-change-tx-data, status-history-of}`.
- **Schedule** (ADR-032): `:schedule`, `:schedule-occurrence`. Helpers: `kontor.schedule/{by-code, pending-occurrences, record-occurrence!}`.
- **Inventory + valuation** (ADRs 027-030): `:valuation-book`, `:valuation-layer`, `:layer-consumption`, `:layer-adjustment`. Helpers: `kontor.valuation`, `kontor.costing-provider`, `kontor.posting/plan-stock-move`.
- **Country + state** (ADR-023): `:country`, `:state`, `:country-group`, `:state-code`.
- **Multi-attestation** (ADR-024): `:attestation`, `:transaction-attestations`. Helpers: `EInvoiceProvider` protocol.
- **Complemento** (ADR-025): `:complemento`, `:transaction-complementos`. For Mexico CFDI extension; pattern reusable.
- **Reconciliation**: `:bank-line`, `:invoice/transaction` bridge, `kontor.reconciliation` helpers.

#### Companion modules (opt-in)

Under `modules/<name>/`:

- **Foundation companions** (Stage I + J shipped):
  - `modules/partner/` — party-as-root + subtype entities + polymorphic contact-mech + roles + relationships (ADR-033).
  - `modules/sales/` — order header + items + ship-group + adjustment + role + state machine (ADR-035).
  - `modules/invoice/` — order→invoice bridge + invoice status machine + AcctgTrans posting (ADR-036).
- **Cross-cutting primitive companions** (Stage J-2, next):
  - Audit / governance (ADR-038 candidate) — reason vocabulary, supporting-doc slot, approval policy, SoD middleware.
  - Master data (ADR-039 candidate) — partner-merge, bank-account, credit-limit, partner-tag, KYC/sanctions hooks.
  - Jurisdiction primitives (ADR-040 candidate) — multi-tax-id junction, reverse-charge flag, tax-inclusive flag, recognition keyword, withholding-tax routing.
  - Workflow extensions (ADR-041 candidate) — time-based transitions, side-effect intent rows, bulk transitions, inverse-pair role-direction documentation.
- **Domain companions** (Stage K+):
  - `kontor-procurement` — requirement, receipt, 3-way match, RTV.
  - `kontor-asset` — fixed-asset register, depreciation schedules.
  - `kontor-revrec` — performance-obligation, ASC 606 / IFRS 15.
  - `kontor-subscription` — catalogue versioning, recurring billing.
  - `kontor-project` — projects, tasks, timesheets (timesheet = analytic-line).
  - `kontor-collections` — aging buckets, dunning, lockbox auto-match.
  - `kontor-commerce-adapter` — UBL 2.1 / Peppol BIS interchange.
  - `kontor-hr` + `kontor-payroll-<cc>-<vendor>` — :person + :employment + per-jurisdiction payroll providers.
- **Per-jurisdiction l10n** (`modules/l10n-<cc>/`):
  - `l10n-de` — DATEV SKR03/SKR04 charts, UStVA, EÜR, e-invoice provider.
  - `l10n-us`, `-ca`, `-fr`, `-in`, `-mx`, `-jp`, `-au`, `-cn`, `-br`, `-at` — each ships a country chart + tax engine binding.
- **Per-bank importers** (`modules/bank-<cc>/`):
  - `bank-de`, `bank-us`, `bank-ca`, `bank-fr`, `bank-at` — CAMT.053 / NACHA / per-country CSV adapters.
- **E-invoicing** (`modules/einvoice-<cc>/`):
  - `einvoice-de` — Factur-X / XRechnung (Mustang APL-2 wrapper). Future: `einvoice-it` (SdI), `einvoice-mx` (CFDI), `einvoice-br` (NF-e), `einvoice-in` (IRN).

### Architectural principles (locked, restated)

These principles inform every ADR and every line of code:

1. **Datahike-native.** No ORM. No JDBC mapping. Schema is data, queries are data, transitions are data.
2. **Bitemporal by construction.** Every read takes `:as-of-tx` + `:as-of-valid`. Audit is a query parameter.
3. **Immutable history.** Posted entries are sealed; corrections via reversal + new posting. `:db/purge` is itself a recorded commit.
4. **Sum-to-zero per (entity, ledger, commodity).** Cannot persist an unbalanced posting. Cross-cutting invariant enforced by `validate`.
5. **Status-as-data.** State machines are declarative `:status-transition` rows + auditable `:status-history` rows. Per-org overrides via composite identity.
6. **Multi-entity from day one.** `:posting/entity` is on the line (SAP ACDOCA pattern). No company-code preflight.
7. **Per-(jurisdiction, entity) overrides.** Tax rates, GL routing, status vocabularies all support per-org scope without forking code.
8. **Vocabulary as keywords, content as data.** Role types, purpose types, status names, account types — all Clojure keywords. Per-locale labels and i18n live in the consumer layer.
9. **Companion modules are opt-in.** Kernel-only consumers stay minimal. The `install!` pattern + composite-tuple `:db.unique/identity` ensures idempotent re-installation.
10. **No UI in the kernel.** Consumer apps own user-facing experience. ADR-010.
11. **No second runtime.** Pure Clojure + datahike. No Python helpers, no JS bridge, no shell-script glue. `bb` is the task runner; `clj-nrepl-eval` is the REPL interface.
12. **License-friendly composition.** EPL-1.0 lets commercial consumers embed without LGPL/GPL contagion. Reference oracles (OFBiz Apache-2.0, Sylius MIT, KillBill Apache-2.0) are explicitly chosen for license cleanliness.

### Non-goals (what we explicitly DON'T build)

- **No UI** (ADR-010). Consumer apps (beleg HTMX, simmis Replicant, custom apps) ship the user-facing experience.
- **No US sales-tax engine** (ADR-005, ADR-010). We provide the `TaxProvider` protocol; customers integrate Avalara / TaxJar / TaxCloud or a `StaticTableProvider` impl.
- **No workflow engine** (ADR-034). The kernel is a state-of-record primitive. Camunda / Temporal / Step Functions compose on top if the consumer needs BPMN-style multi-actor processes.
- **No BI / reporting tools.** Consumer apps run datalog queries directly OR plug in their own analytics layer.
- **No hosted SaaS.** kontor is a library. Hosting is the consumer's choice. (A future `kontor-cloud` could expose the kernel as a managed service; explicitly out of scope for the kernel itself.)
- **No DSL / visual modeler.** Datalog IS the DSL. SQL bridge via pg-datahike is a reference oracle, not a runtime target.
- **No translation of Odoo / Tryton code** (ADR-001 + research note 01). FSF treats Python translation as derivative work; we write our own implementation using OFBiz / Sylius / KillBill as license-clean reference oracles.
- **No bundled Avalara / TaxJar / TaxCloud API keys or rate data** (ADR-005). Customers hold their own.
- **No CRM / marketing automation / fleet / field-service.** These are commodity SaaS that integrate via APIs; we don't reimplement.
- **No ERP-suite feature parity for its own sake.** If a domain doesn't have a near-term consumer demand, we don't ship a companion for it. Manufacturing, helpdesk, CLM, marketing — deferred until concrete demand.

### Consumer story

`kontor` consumers compose the kernel + the companions they need into a domain-specific application. Two prototypical patterns:

**Pattern A: "I'm building a B2B SaaS that needs real accounting."**
- Install: kernel + `kontor-partner` + `kontor-sales` + `kontor-invoice` + `kontor-l10n-<cc>` + `kontor-einvoice-<cc>`.
- Wire your own UI (HTMX / React / Replicant / custom).
- Datalog queries for your domain reports.
- Integrate Avalara / TaxJar for tax compute.
- Your consumer namespace seeds journals, accounts, vocabularies; calls kontor helpers from your route handlers / queue workers.

**Pattern B: "I'm building internal infrastructure for my growing company."**
- Install: same as above plus `kontor-procurement` + `kontor-collections` + `kontor-hr` + `kontor-payroll-<cc>-<vendor>` as needs surface.
- Run on a single JVM with datahike Postgres backend (via pg-datahike) or LMDB / RocksDB / file-store.
- Consumer apps for each user role (sales, AP, AR, payroll, exec).
- Replaces QuickBooks / Xero / Sage upgrade pressure without paying SAP / NetSuite tax.

The reference consumer apps:
- **`beleg`** (planned first consumer, ADR-002) — contractor invoice management. Wraps `kontor-partner` + `kontor-sales` + `kontor-invoice` + `kontor-einvoice-de`. HTMX UI.
- **`simmis`** (planned later consumer) — distributed-scope ClojureScript ERP-shaped app on top of kontor + `spindel` reactive primitives.
- **Custom apps** — vertical SaaS, internal tools, embedded financial workflows.

### Roadmap implications

Per [research note 13](research/13-stage-j-pain-and-followups.md), Stage J shipped the order / invoice / partner foundation. The systematic next move is **Stage J-2: cross-cutting primitives**, broken across 4 ADRs:

- **ADR-038 (Audit + Governance primitives)** — `:status-history/reason` as keyword + `:reason-note` string + supporting-doc slot + `:approval-policy` companion. Resolves the SOX / GDPR / SoD gaps surfaced by all 5 research agents.
- **ADR-039 (Master-data primitives)** — `:partner-merge` non-destructive link + `:bank-account` entity + `:partner-bank-account` junction + `:partner/credit-limit` + `:partner-tag`. Resolves the MDM gaps surfaced by the partner-pain agent.
- **ADR-040 (Jurisdiction primitives)** — `:partner-tax-id` junction + `:invoice-line/reverse-charge?` + `:invoice/tax-inclusive?` + `:invoice-line/recognition` keyword + withholding-tax routing + `:pending-attestation` / `:rejected` invoice states. Resolves the multi-jurisdiction gaps surfaced by the invoicing-pain agent. Forward-compat for kontor-revrec.
- **ADR-041 (Workflow extensions)** — time-based transitions via `:schedule` integration + side-effect intent rows + bulk-transition API + inverse-pair role-direction documentation. Resolves the workflow gaps surfaced by the status-machine-pain agent.

Then **Stage K (kontor-procurement)** lands on the ADR-038-041 foundation. After that, faster cadence per the hybrid plan: Stage L (`kontor-asset`), M (`kontor-revrec`), N (`kontor-subscription`), etc.

Each cross-cutting ADR is small (~50-150 LOC + schema + tests + docs) and lands as its own commit. The set hangs together coherently because each ADR addresses a category, not a punch-list-of-bugs.

### Alternatives considered

- **`kontor` as a hosted SaaS first.** Rejected: library-first lets every consumer choose their own deployment model. A managed-cloud offering (`kontor-cloud`) can come later, but the kernel must be standalone first.
- **`kontor` as an Odoo competitor with bundled UI.** Rejected per ADR-010. UI is consumer-side.
- **`kontor` as an ERP-suite with all modules built upfront.** Rejected: domain-specific companions are added on concrete demand; speculative completeness creates maintenance burden without consumer pull.
- **`kontor` as a "lightweight QuickBooks alternative" only.** Rejected: deliberately narrow scope. The market for SMB accounting is saturated and price-sensitive; the market for "business OS substrate for senior engineers" is underserved and willing to pay for substrate quality.
- **`kontor` as an SAP competitor at SMB price point.** Closer to the actual positioning but framed defensively. The forward framing is "we're the substrate; we don't compete on every SAP feature; we compete on architectural primitives that make composing your own substrate possible."
- **Multi-language / multi-runtime (Python + Clojure bindings, JS bridge for browser-side).** Rejected: single-runtime discipline is a load-bearing simplification. Consumers can use any client they like (HTTP, WebSocket, language bindings to the JVM); the kernel stays JVM Clojure.

### Implications

- All current ADRs (001-036) and all current companions (partner, sales, invoice, l10n-de, einvoice-de, bank-de, l10n-* stubs) align with this positioning. Nothing changes structurally.
- ADR-010's "no ERP modules forever" is **superseded** by this ADR. Specifically: ADR-010 still applies to (a) US sales-tax engines (we wrap), (b) UI (consumer-side), (c) translation of Odoo source (we write our own). But ADR-010 does NOT preclude opt-in companion modules under `modules/<name>/`, which have been the pattern since ADR-006.
- Stage J-2 (cross-cutting primitives, ADR-038-041) is the next coherent body of work. Estimate: 4 ADRs × ~1-2 sessions each = 4-8 sessions before Stage K starts.
- All cross-cutting primitive ADRs share a common quality bar: opt-in install, composite-tuple identity for idempotency, bitemporal-by-construction (no separate fact tables), pure-function tx-data helpers (for atomic composition), and explicit alternatives-considered + implications sections.
- The 30+ market-pain items from research note 13 are not a punch list to tickle; they're a constraint set the cross-cutting ADRs are designed to resolve coherently.
- Forward-compat for Stage K is baked into ADR-040 (jurisdiction + `:invoice-line/recognition`) and the debit/credit data table that ADR-038 introduces.

Date: 2026-05-12.

---

## ADR-038 — Audit + governance primitives: codified reasons, supporting docs, segregation of duties

**Decision.** Change kernel `:status-history/reason` from `:db.type/string` to `:db.type/keyword` (codified reason code, SOX-friendly). Add new `:status-history/reason-note` (string, free-text human story alongside the code). Add new `:status-history/supporting-doc` (ref to a generic `:audit-doc` entity that the consumer attaches whatever proof matters — uploaded PDF, email thread, regulator clearance token, manager-override approval). Add kernel-level `:audit-doc` entity (minimal: code identity, type discriminator, content-hash, storage-uri, audit metadata). Add kernel-level `:approval-policy` entity expressing "transition X requires this kind of approval" with optional per-org override. Add `:no-self-approval` as the default segregation-of-duties rule — `kontor.status-machine/record-status-change-tx-data` checks it when an applicable policy exists.

This ADR is the FIRST of the four Stage J-2 cross-cutting primitive ADRs (per ADR-037). It addresses the audit / governance gaps surfaced consistently across all 5 research agents:
- Status-machine pain agent: codified reasons (SafePaaS / ConductorOne), supporting-doc gap, SoD enforcement (SOX 404).
- Invoicing pain agent: ASC 250 / PwC error-correction (supporting docs on reversals are auditor-mandatory), Stripe Billing pattern (cancel-then-reissue with credit-memo as supporting doc).
- Partner pain agent: GDPR DSAR + retention compliance (every PII redaction must have a recorded reason + retention basis).
- Order pain agent: amendment workflow approvals (Dynamics 365 BC, SAP S/4 Flexible Workflow).
- Local code review: P1-7 invariants for cross-attribute consistency (we use this same kernel `:approval-policy` shape).

The intent is to make every state change **auditor-ready by construction**, not as a post-hoc reporting concern.

### Why these three primitives belong together

The triple — codified reasons + supporting docs + SoD enforcement — is what an SOX auditor asks for on a sensitive transition: "Show me who, when, why (in a documented vocabulary), what supporting evidence backs the why, and what approval was required to make this change." These three answer those questions. Splitting them across separate ADRs would leave each one half-useful: a codified reason without a supporting doc is unverifiable; a supporting doc without an approval policy is just an attachment; an approval policy without codified reasons can't roll up to compliance reports.

### Schema changes

#### Modify: `:status-history/reason` (kernel)

```clojure
;; was
:status-history/reason :db.type/string
;; becomes
:status-history/reason :db.type/keyword
```

Codified reason codes following auditor-friendly conventions. The kernel does NOT enforce a finite enum — consumers extend per their compliance regime. Canonical starter vocabulary, documented in `kontor.status-machine` namespace:

- **Lifecycle codes**: `:created`, `:approved`, `:rejected`, `:cancelled`, `:completed`, `:reopened`.
- **Correction codes**: `:correction`, `:duplicate`, `:data-entry-error`, `:fraud-detected`, `:auto-reversed`.
- **Commercial codes**: `:customer-request`, `:vendor-request`, `:partial-shipment`, `:credit-memo-issued`, `:refund-issued`, `:write-off-uncollectible`.
- **Regulatory codes**: `:tax-correction`, `:period-close`, `:gdpr-erasure`, `:retention-expired`, `:regulator-rejected` (e.g. SdI / SEFAZ / IRP rejection).
- **Operational codes**: `:auto-promoted`, `:bulk-action`, `:reconciliation-match`, `:system-scheduled` (for time-based transitions per ADR-041).
- **Catch-all**: `:other` (requires a non-empty `:reason-note`).

Per the status-machine pain research, this approach is what SafePaaS / ConductorOne recommend: codified codes compress into compliance reports; free-form strings don't.

#### Add: `:status-history/reason-note` (kernel)

```clojure
:status-history/reason-note :db.type/string
```

Optional free-text human story attached to the codified `:reason`. Where the code answers "what kind of reason," the note answers "what specifically happened." E.g.:
- `:reason :customer-request` + `:reason-note "Customer Acme asked to defer to Q2"`.
- `:reason :fraud-detected` + `:reason-note "AVS mismatch + 3DS challenge failed; flagged by Stripe Radar"`.
- `:reason :other` + `:reason-note "..."` (note REQUIRED when reason is `:other`).

#### Add: `:status-history/supporting-doc` (kernel)

```clojure
:status-history/supporting-doc :db.type/ref     ; → :audit-doc
```

Optional ref to an `:audit-doc` entity. When the auditor asks "where's the customer's email asking for the credit memo," this points at the artifact.

#### Add: `:audit-doc` entity (kernel)

```clojure
:audit-doc/code            string :db.unique/identity   ; consumer-supplied opaque ID
:audit-doc/type            keyword
                          ;; :credit-memo | :customer-email | :vendor-email
                          ;; | :uploaded-pdf | :wet-signature-pdf
                          ;; | :regulator-clearance | :manager-override
                          ;; | :compliance-attestation | … free-form
:audit-doc/title           string                       ; human-readable label
:audit-doc/description     string                       ; longer note
:audit-doc/content-hash    string                       ; SHA-256 of the artifact for
                                                        ;   integrity verification
:audit-doc/storage-uri     string                       ; where the consumer stores
                                                        ;   the bytes ("s3://", "file://",
                                                        ;   "https://", "ipfs://", …)
:audit-doc/uploaded-by-uid ref → :create/uid
:audit-doc/uploaded-at     instant
```

The kernel does NOT store document bytes. Consumer attaches whatever artifact (uploaded PDF, email thread, downloaded clearance token, etc.) wherever they store it; the entity is just enough to point at it and verify integrity.

#### Add: `:approval-policy` entity (kernel)

```clojure
:approval-policy/entity-type    keyword          ; :order | :invoice | :payment | …
:approval-policy/facet          keyword          ; :order/status | :invoice/status | …
:approval-policy/transition-from keyword         ; from-state
:approval-policy/transition-to  keyword          ; to-state
:approval-policy/applies-to-org ref → :entity    ; optional per-org scope (per ADR-031)
:approval-policy/rule           keyword
                                ;; :no-self-approval — recorded actor must differ
                                ;;                     from :create/uid of the entity
                                ;; :requires-supporting-doc — :supporting-doc must be set
                                ;; :requires-non-empty-reason-note — :reason-note required
                                ;; … future rules extend the vocabulary
:approval-policy/active         boolean
:approval-policy/note           string
:approval-policy/identity       tuple [entity-type, facet, transition-from,
                                        transition-to, rule, applies-to-org]
                                unique
```

Composite identity ensures idempotent re-installation + multiple policies per transition (e.g. ALL OF `:no-self-approval` AND `:requires-supporting-doc`).

### Enforcement

`kontor.status-machine/record-status-change-tx-data` (the pure-function variant) gains a pre-flight check:

1. Look up the applicable `:approval-policy` rows for `(entity-type, facet, from, to)` with org scope per ADR-034 semantics (org-specific OR tenant-wide).
2. For each rule:
   - `:no-self-approval`: require `:changed-by-uid ≠ (:create/uid (d/pull db [:create/uid] entity))`.
   - `:requires-supporting-doc`: require `:supporting-doc` ref to be set in the change-spec.
   - `:requires-non-empty-reason-note`: require `:reason-note` to be a non-empty string.
3. On any rule violation, throw `ex-info :type :approval-policy/violation` with `{:policy ... :violations [...]}`.

This is a **kernel-level enforcement**. Consumers who write `:status-history/...` directly via `d/transact` bypass it (same pattern as ADR-007 sealing — the middleware enforces; raw datahike can bypass). Consumers using `kontor.status-machine/record-status-change!` and helpers get the enforcement.

### Vocabulary discipline

Per the status-machine pain research: codified vocabularies are an audit asset, but they bit-rot if every companion invents its own. Two disciplines to enforce in code review:

1. **Canonical kernel vocabularies are open-set**, not closed. Consumers extend by writing their own keywords. The kernel just provides the starter set.
2. **Companion modules that introduce new domain-specific vocabularies must document them in the companion's ADR.** E.g. `kontor-collections` will introduce `:reason :credit-hold-applied`, `:reason :dunning-d7-sent`, etc.; those land in the collections ADR's vocabulary section.

A future linter (`kontor.status-machine.lint`) can flag uses of `:other` without a `:reason-note`, or transitions that bypass a known policy. Out of ADR-038 scope.

### Helpers

`kontor.status-machine` namespace gains:

- `applicable-policies` — `(db entity-type facet from to org) → vec of :approval-policy maps`. Public.
- `check-policies` — `(db change-spec policies)` → `nil | (throws ex-info :approval-policy/violation)`. Internal.
- The existing `record-status-change!` / `record-status-change-tx-data` now run `check-policies` after the legality check, before building the tx-data.

A new `kontor.audit-doc` namespace ships:

- `by-code` / `resolve-doc`.
- `attach-supporting-doc!` — convenience that creates an `:audit-doc` + updates a target `:status-history` row's `:supporting-doc` ref in one tx.
- `verify-content-hash` — pulls the doc, downloads from `:storage-uri`, recomputes SHA-256, compares with `:content-hash`. Consumer-driven; not auto-run.

### Bootstrap

Kernel `kontor.core/install-schema!` gains zero new defaults beyond schema. Consumers seed their own `:approval-policy` rows. The companion `modules/audit/` ships a small set of sensible policy defaults (e.g. "invoice `:sent → :cancelled` after posted requires `:supporting-doc`") that consumers opt into.

### Alternatives considered

- **Keep `:reason` as a string and add a `:reason-code` keyword alongside.** Rejected: backwards-compatible but creates redundancy; two attrs that say similar things drift over time. Migrate hard.
- **Approval policy as Clojure data (a per-app registry of fns).** Rejected: ADR-034's whole point is "vocabulary is data." Policies belong in datahike, queryable + per-org overrideable + bitemporal.
- **N-of-m approval workflow.** Deferred. The minimum viable SoD is `:no-self-approval` + `:requires-supporting-doc`; n-of-m (with `:approval-request` entity + multiple approvers + timeout) is its own ADR when a consumer needs it.
- **Per-user override on policies.** Deferred. Same as above — the minimum is the policy itself; the override-with-justification flow is a future companion (`kontor-approval-override`).
- **Store document bytes in datahike via `:db.type/bytes`.** Rejected: datahike isn't a document store; large blobs bloat the index. Consumer stores the bytes elsewhere; kernel stores the ref + hash.
- **`:audit-doc/content-hash` as a Merkle root over multiple files.** Rejected: simple SHA-256 over a single file is what compliance auditors recognize. Composite hashes are out of scope.
- **Embed `:audit-doc/storage-uri` semantics (e.g. S3-specific fields).** Rejected: opaque URI keeps the kernel storage-agnostic; consumers parse the URI scheme.

### Migration

`:status-history/reason` already lives in the kernel schema (added by ADR-034). The type change from string → keyword is a hard migration:

1. **In-process tests**: all existing tests must update string `:reason "..."` to keyword + optional note (`:reason :customer-request :reason-note "..."`).
2. **Existing data** (no production deployment yet, so no real data exists): seeded values in tests / fixtures get updated as part of this commit.
3. **Going forward**: callers pass keywords; the schema rejects strings.

If a future consumer DOES have production data, the migration path is: `(d/transact conn (mapv (fn [[eid old-str]] [:db/add eid :status-history/reason :other]) ...))` plus updating `:reason-note` to carry the old string. Trivial migration; documented in this ADR for future reference.

### Implications

- 1 kernel attr type change (`:status-history/reason`).
- 4 new kernel attrs (`:status-history/reason-note`, `:status-history/supporting-doc`, `:audit-doc/*`, `:approval-policy/*`).
- 2 new kernel entities (`:audit-doc`, `:approval-policy`).
- ~50 LOC in `kontor.status-machine` for policy lookup + enforcement.
- New namespace `kontor.audit-doc` (~30 LOC, optional).
- Test coverage: `:reason :keyword` migration, `:no-self-approval` rejection, `:requires-supporting-doc` rejection, policy lookup with org scope, supporting-doc round-trip with content-hash verification.
- Companion module `modules/audit/` (small) with default policy seeds + helper for attaching docs to history rows.
- Forward-compat: ADR-039 master-data and ADR-040 jurisdiction can reference `:supporting-doc` for the cases that need it (e.g. credit memo's "this credit references customer email" link, GDPR erasure's "this PII redaction has a recorded request" link).

Date: 2026-05-12.

---

## ADR-039 — Master-data primitives: merge, bank-account, credit-limit, tags, KYC hooks

**Decision.** Extend the `:partner` model (ADR-033) with four cross-cutting MDM primitives:

1. **`:partner-merge`** — kernel-level non-destructive merge link entity. Marks `:partner B` as a duplicate-of `:partner A` without physical retraction. Resolution helper walks the chain.
2. **`:bank-account` entity** + **`:partner-bank-account` temporal junction** — kernel-level. Banking is master-data; every supplier has N accounts each with IBAN/BIC/currency/validity. Without this, the invoice → payment workflow has no AP target.
3. **`:partner/credit-limit` + `:partner/credit-commodity` + `:partner/credit-status`** — kernel-level partner extensions. Enables credit-hold automation.
4. **`:partner-tag`** — kernel-level temporal-validity tag junction. Customer segmentation, channel tagging, marketing/credit/AR segment classification.
5. **KYC hooks**: `:partner/kyc-status`, `:partner/kyc-checked-at`, `:partner/kyc-source` — kernel-level. Minimal scalar attrs on `:partner`. The actual sanctions-screening engine is a future `SanctionsProvider` companion (same pattern as `TaxProvider` / `EInvoiceProvider`).

This is the SECOND of the four Stage J-2 cross-cutting primitive ADRs (per ADR-037). It resolves the MDM gaps surfaced by the partner / customer-master-data market-pain research (research note 13). The whole MDM industry (Profisee, Informatica, Tamr, Reltio) exists because every commercial ERP gets merge / banking / credit / KYC structurally wrong; kontor's bitemporal + opt-in companion model lets us ship the structurally-right primitives without the technical debt those incumbents carry.

### Why these five primitives belong together

The five are interdependent in enterprise practice:

- **Merge** without **bank-account** support causes the worst data loss in NetSuite/Salesforce merges (banking fields silently drop, breaking payment processing).
- **Credit-limit** without **tag**-based segment classification doesn't scale: a tenant with three customer tiers (gold/silver/bronze) wants different default credit limits per tier.
- **KYC** without **bank-account** has no enforcement surface: KYC checks gate the ability to wire to a bank account, not just the partner record.
- **Bank-account** without **temporal validity** breaks audit (when did this AP target become valid? when did we last verify the IBAN?).

Splitting them across ADRs would force consumers to install three companions to get one coherent MDM story. Bundling them keeps the surface coherent.

### Schema

#### `:partner-merge` — non-destructive merge link (kernel)

```clojure
:partner-merge/duplicate-of  ref → :partner    ; the "good" record (canonical)
:partner-merge/superseded    ref → :partner    ; the "bad" record (duplicate)
:partner-merge/identity      tuple [duplicate-of, superseded] :db.unique/identity
:partner-merge/merged-at     instant
:partner-merge/merged-by-uid ref
:partner-merge/reason        keyword             ; ADR-038 vocabulary
:partner-merge/reason-note   string              ; free-text
:partner-merge/supporting-doc ref → :audit-doc   ; ADR-038
```

**Critical semantic**: this is NOT physical retraction. The `:partner B` (`:superseded`) entity is preserved with all its history. The `:partner-merge` row encodes "logically, B = A from `:merged-at` onward; queries resolve B → A."

A `kontor.partner/resolve-canonical-partner` helper walks the chain: if the partner has a `:partner-merge/superseded` ref pointing at it, follow to `:duplicate-of` (and recursively).

`:posting/partner [eid → B]` continues to resolve to `B` at query time; consumers calling `resolve-canonical-partner` get `A`. This preserves the bitemporal audit chain — "what posting referenced what partner at what time" — while presenting the post-merge canonical view to UIs.

Reversing a merge: retract the `:partner-merge` row. Audit chain records the retraction as its own tx-time event per ADR-007.

#### `:bank-account` entity (kernel)

```clojure
:bank-account/code           string :db.unique/identity
:bank-account/iban           string
:bank-account/bic            string
:bank-account/account-number string                 ; for non-IBAN banks (US ABA, etc.)
:bank-account/routing-number string                 ; US ABA / GB sort code
:bank-account/bank-name      string
:bank-account/country        ref → :country
:bank-account/commodity      ref → :commodity        ; the account's currency
:bank-account/holder-name    string                  ; on-the-account legal name
:bank-account/active         boolean
:bank-account/note           string
```

A bank-account is its own entity (not just attrs on `:partner`) because:
- Multiple partners can hold the same bank account (joint accounts in personal contexts; brand portfolios in commercial contexts where multiple `:partner` entries share an AP target).
- Temporal validity belongs to the relationship (when this partner started using this account), not the account itself (the IBAN persists across the relationship lifecycle).
- It can also be OUR OWN bank account (held by our `:internal-organization` partner) — same schema, used by payment-out flows.

#### `:partner-bank-account` junction (kernel, temporal)

```clojure
:partner-bank-account/partner       ref → :partner
:partner-bank-account/bank-account  ref → :bank-account
:partner-bank-account/from-date     instant
:partner-bank-account/thru-date     instant
:partner-bank-account/purpose       keyword
                                    ;; :disbursement — we pay TO this account
                                    ;; :collection   — we collect FROM this account
                                    ;; :both         — bidirectional
:partner-bank-account/preferred?    boolean         ; preferred-for-purpose flag
:partner-bank-account/verified?     boolean
:partner-bank-account/verified-at   instant
:partner-bank-account/identity      tuple [partner, bank-account, from-date] unique
```

Composite identity allows the same (partner, bank-account) pair across distinct time windows (e.g. supplier closes an account, opens it again later).

#### `:partner` extensions (kernel additions, additive)

```clojure
:partner/credit-limit      bigdec                  ; nil = unlimited
:partner/credit-commodity  ref → :commodity         ; currency of the limit
:partner/credit-status     keyword
                          ;; :open    — open for new orders
                          ;; :hold    — credit hold; new orders blocked
                          ;; :review  — manual review required before approval
                          ;; :closed  — relationship closed; no new business
:partner/kyc-status        keyword
                          ;; :not-required — no KYC needed (low-risk SMB customer)
                          ;; :pending      — KYC requested, not yet returned
                          ;; :cleared      — KYC passed
                          ;; :flagged      — needs human review
                          ;; :blocked      — sanctions/PEP/AML match; forbid trade
:partner/kyc-checked-at    instant
:partner/kyc-source        string                   ; "LexisNexis" / "Refinitiv" /
                                                    ; "ComplyAdvantage" / "Manual" / …
```

The `:credit-status` enum is opt-in: consumers that don't care about credit-hold automation leave the attribute nil. Same for `:kyc-status`.

The KYC trio is deliberately minimal — three scalar attrs. Full sanctions-list screening (downloading + matching SDN lists, PEP databases, adverse-media feeds) belongs to a future `SanctionsProvider` protocol following the `TaxProvider` (ADR-005) pattern. Out of ADR-039 scope.

#### `:partner-tag` junction (kernel)

```clojure
:partner-tag/partner    ref → :partner
:partner-tag/tag-type   keyword
                       ;; canonical starter vocabulary:
                       ;; :vip | :high-volume | :strategic-account
                       ;; :churn-risk | :do-not-contact | :test-account
                       ;; :gold-tier | :silver-tier | :bronze-tier
                       ;; … consumers extend per their segmentation
:partner-tag/from-date  instant
:partner-tag/thru-date  instant
:partner-tag/identity   tuple [partner, tag-type, from-date] unique
```

Bitemporal segmentation: "what tier was this customer when we issued this invoice" is one query, not a CRM ETL pipeline.

### Helpers

#### `kontor.partner` (extends existing namespace)

- `resolve-canonical-partner` — walks `:partner-merge` chain; returns the canonical eid.
- `merge-partners!` — convenience transactor: writes `:partner-merge` + sets `:partner B/status :archived` in one tx with audit metadata.
- `unmerge!` — retract a `:partner-merge` row (audit-chain records the retraction).
- `bank-accounts-of` — pulled `:bank-account` rows for a partner via the junction (`:as-of` opt for temporal filter).
- `primary-disbursement-account` / `primary-collection-account` — preferred-for-purpose lookup.
- `credit-available` — `(- credit-limit open-AR open-orders pending-orders)`. Computed live; consumer's responsibility to define "open" / "pending" via their domain queries.
- `tags-of` / `partners-with-tag` — segmentation queries (bitemporal).
- `kyc-required?` — predicate based on `:partner/kyc-status` + tenant policy (extension point).

#### `kontor.bank-account` (new namespace)

- `by-code` / `resolve-bank-account`.
- `validate-iban` — checksum validation (kontor doesn't ship the country-IBAN-format table; consumers can plug in libraries like `iban4j` if needed; the helper does mod-97 checksum which catches typos).

### Bootstrap

No seeds. Consumers transact their own partners, accounts, tags, credit limits, KYC source registrations.

### Alternatives considered

- **Destructive merge.** Rejected per partner-pain market research. NetSuite, Salesforce, Odoo all have destructive merge with documented data loss. Datahike's tx-time gives us reversible merge for free; ship it.
- **Bank-account attrs directly on `:partner`** (cardinality-many strings for IBANs). Rejected: defeats verification flags, temporal validity, joint accounts, our-own-bank-accounts.
- **`:partner-bank-account` as a `:db.unique/value` (1:1)** instead of junction. Rejected: forces a partner to have at most one bank account per kontor-tenant. Real B2B: many partners have N accounts (one per currency, one per regional bank, one per business unit).
- **`:partner-tag` as cardinality-many keyword on `:partner` directly.** Rejected: loses temporal validity. A customer was `:gold-tier` from `2024-01-01` to `2025-06-15`, then downgraded — the junction encodes this; a scalar set doesn't.
- **`:partner/credit-limit` as a separate entity `:credit-limit-policy`** with multiple tiers + history. Rejected for v1: most consumers want one scalar. Tiered limits can be encoded via the `:partner-tag` segmentation and a consumer-side lookup; promote to entity later if needed.
- **`:partner/kyc-status` as a full state machine via ADR-034.** Rejected for v1: KYC isn't transitions in our system; KYC is the EXTERNAL SCREENING result. The status attr captures the latest result; full lifecycle (`:requested → :in-progress → :cleared|:flagged → :renewal-due`) can layer on ADR-034 in a future companion.
- **Bake sanctions-list screening into kontor.** Rejected: regulated business, license-restricted data (Refinitiv, LexisNexis), customer-specific compliance regimes. Like Avalara (ADR-005), we provide the protocol + hooks; customer integrates the provider.
- **`:partner-merge` as cardinality-many `:partner/duplicate-of` ref directly on partner.** Rejected: loses audit (who merged, when, why), loses the supporting-doc link, loses the bidirectional traversal.

### Migration

`:partner` extensions (credit-limit, credit-status, KYC trio) are additive — existing partners unaffected when the attrs are nil.

`:partner-tag` / `:bank-account` / `:partner-bank-account` / `:partner-merge` are new entities. No migration needed.

### Implications

- 5 new kernel entities (`:partner-merge`, `:bank-account`, `:partner-bank-account`, `:partner-tag`, KYC trio on `:partner`).
- ~10 new kernel attrs + temporal junctions.
- Helpers added to existing `kontor.partner` namespace + new `kontor.bank-account` namespace.
- Test coverage: merge round-trip + resolve-canonical, bank-account temporal validity, credit-limit query helper, partner-tag segmentation, KYC scalar attr round-trip.
- Forward-compat for kontor-collections (Stage L): `:partner/credit-limit` + `:partner/credit-status` are exactly what collections needs.
- Forward-compat for kontor-procurement (Stage K): `:bank-account/holder-name` + `:partner-bank-account :purpose :disbursement` are the AP target shape.
- Cross-companion: `kontor-invoice.bridge/post-to-ledger!` can read `:partner/credit-status :hold` and refuse to post (or just warn — left as consumer policy).

Date: 2026-05-12.

---

## ADR-040 — Jurisdiction primitives: multi-tax-id, reverse-charge, tax-inclusive, recognition, withholding

**Decision.** Extend the kernel + the kontor-invoice companion with six jurisdiction-aware primitives that resolve multi-country / multi-VAT / e-invoicing-clearance / revrec-forward-compat gaps:

1. **`:partner-tax-id` junction** (kernel) — multi-VAT-per-jurisdiction with effective-date validity. The kernel's existing `:partner/tax-id` scalar stays as the "primary"; this junction handles the multi-country case.
2. **`:invoice-line/reverse-charge?` flag** (kontor-invoice) — EU B2B intracommunity services + future ViDA 2028 universal reverse-charge support.
3. **`:invoice/tax-inclusive?` flag** (kontor-invoice) — pins the discount-then-tax computation order; prevents the Odoo issue #23125 / #66875 class of rounding bugs.
4. **`:invoice-line/recognition`** keyword `:direct | :deferred` (kontor-invoice) — kontor-revrec forward-compat. When `:deferred`, the posting bridge credits a deferred-revenue account instead of revenue, and the consumer emits a `:schedule` (ADR-032) row that releases over the obligation period.
5. **`:invoice-line/withholding-on-payment?` flag + `:withholding-tax-payable` / `:withholding-tax-recoverable` GL-account-types** (kontor-invoice) — IN TDS, MX ISR, US 1099 backup withholding. The credit-leg defers to payment time, not invoice time.
6. **Two new invoice status states**: `:pending-attestation` and `:rejected` (kontor-invoice). For IT SdI, IN IRN, BR NF-e, ES Verifactu clearance lifecycles. Mirrors the kernel's existing `:transaction/state :pending-attestation` (ADR-018) at the invoice level.

This is the THIRD of the four Stage J-2 cross-cutting primitive ADRs (per ADR-037). It resolves the multi-jurisdiction gaps surfaced by the invoicing market-pain research (research note 13). All gaps reduce to: model jurisdictional variation as **data** (per-line / per-junction attrs), not as **code** (hardcoded per-country branches in posting logic).

### Why these six primitives belong together

The six are interdependent for a transnational customer:

- **Multi-tax-id** without **reverse-charge** is incomplete for intra-EU B2B: customer has DE + AT VAT IDs, but without the reverse-charge flag the bridge can't decide whether to apply VAT or zero-rate.
- **Tax-inclusive** without **withholding** can't model jurisdictions that mix both (e.g. Mexico: IVA is often inclusive on consumer receipts but ISR withholding is exclusive).
- **Recognition `:deferred`** without the **clearance lifecycle** (`:pending-attestation`) breaks Italy + India: deferred revenue scheduling can't fire until the invoice has cleared SdI / IRP.
- **Withholding** without **multi-tax-id** misses the case where withholding applies in jurisdiction A (vendor's country) but the invoice is in jurisdiction B's VAT regime.

Splitting them across ADRs would force the consumer to install three companions per transnational customer.

### Schema

#### `:partner-tax-id` junction (kernel)

```clojure
:partner-tax-id/partner       ref → :partner
:partner-tax-id/country       ref → :country         ; jurisdiction (ADR-023)
:partner-tax-id/tax-id-type   keyword
                              ;; :vat-eu | :gst-au | :gst-in | :tin-us
                              ;; | :rfc-mx | :cnpj-br | :cpf-br
                              ;; | :pan-in | :abn-au | :kvk-nl
                              ;; | :rsin-nl | :btw-nl | … consumers extend
:partner-tax-id/tax-id        string                  ; the ID value
:partner-tax-id/from-date     instant
:partner-tax-id/thru-date     instant
:partner-tax-id/verified?     boolean                 ; VIES, SAT, IRP, etc. checked
:partner-tax-id/verified-at   instant
:partner-tax-id/identity      tuple [partner, country, tax-id-type, from-date] unique
```

The kernel's `:partner/tax-id` scalar (per ADR-002) stays as the "primary" denormalization for ergonomics. The junction is the source of truth for multi-jurisdiction. The bridge resolves "VAT ID applicable for this country" via the junction at posting time.

#### `:invoice` extensions (companion-installed)

```clojure
:invoice/tax-inclusive?  boolean
                        ;; default false. When true, line :unit-price
                        ;; is gross (tax-included); discount applies
                        ;; to gross then tax is back-solved.
                        ;; When false (default), :unit-price is net;
                        ;; discount applies to net, tax computed on
                        ;; post-discount base.
```

#### `:invoice-line` extensions (companion-installed)

```clojure
:invoice-line/reverse-charge?      boolean
                                  ;; Default false. When true, the
                                  ;; bridge emits dual postings: buyer-
                                  ;; side AP-tax-payable + buyer-side
                                  ;; AP-tax-recoverable, netting to zero
                                  ;; in the line GL. The supplier-side
                                  ;; invoice doesn't charge VAT.

:invoice-line/recognition          keyword
                                  ;; :direct | :deferred (default :direct).
                                  ;; :direct  — bridge credits :sales-
                                  ;;            revenue immediately.
                                  ;; :deferred — bridge credits :sales-
                                  ;;            revenue-deferred (a
                                  ;;            liability); consumer
                                  ;;            emits a :schedule row
                                  ;;            (ADR-032) to release
                                  ;;            over the obligation
                                  ;;            period.

:invoice-line/withholding-on-payment? boolean
                                  ;; Default false. When true, the
                                  ;; withholding-tax credit-leg fires
                                  ;; at payment time (matching the
                                  ;; cash event), not at invoice
                                  ;; posting time. Captures the
                                  ;; \"compute at booking OR payment,
                                  ;; whichever is earlier\" rule for
                                  ;; IN TDS / MX ISR.
```

The bridge's `debit-credit-for` map (currently in `kontor.invoice.posting`) extends with the new GL-account-types:

- `:sales-revenue-deferred` — credit on `:deferred` recognition (liability account, not revenue).
- `:withholding-tax-payable` — credit-leg deferred to payment time.
- `:withholding-tax-recoverable` — debit-leg for the customer claiming TDS as a tax credit.

Moving the map to a data table (per ADR-041) makes this extension a transact, not a code edit. For now the map gets four new entries.

#### Invoice status machine extension (kontor-invoice seeds)

Adds two new transitions to the seeded `:status-transition` table:

```
:draft → :pending-attestation      (Submit for Clearance — IT SdI, IN IRN, BR NF-e)
:ready → :pending-attestation      (Submit for Clearance — finalized invoices)
:pending-attestation → :sent        (Cleared by Authority)
:pending-attestation → :rejected    (Rejected by Authority — needs revision)
:rejected → :draft                  (Revise and resubmit)
```

The existing `:draft → :sent` and `:ready → :sent` transitions are preserved (for jurisdictions without clearance, the invoice goes straight to `:sent`). The new states are opt-in; consumers in non-clearance jurisdictions never traverse them.

### Helpers

#### `kontor.partner` (extends existing namespace)

- `tax-ids-of` — pulled `:partner-tax-id` rows for a partner; filter by `:country` opt and `:as-of` opt.
- `tax-id-for-country` — single lookup: `(tax-id-for-country db partner country-ref :as-of date)` returns the active tax-id string or nil.
- `verify-tax-id!` — mark a tax-id verified at a timestamp (for consumers integrating VIES / SAT / IRP).

#### `kontor.invoice.bridge` (extends existing namespace)

- `make-invoice-from-order!` accepts `:tax-inclusive?` opt → sets `:invoice/tax-inclusive?` on the resulting invoice.
- New `:invoice-line/recognition`, `:reverse-charge?`, `:withholding-on-payment?` flags pass through from order-adjustment metadata when present (the consumer's pricing layer determines which apply).

#### `kontor.invoice.posting` (extends existing namespace)

- `debit-credit-for` map adds `:sales-revenue-deferred → credit`, `:withholding-tax-payable → credit`, `:withholding-tax-recoverable → debit`.
- New `withholding-postings` helper: for invoice lines with `:withholding-on-payment? true`, the bridge does NOT emit the withholding credit-leg at invoice-post time; the leg is deferred. Documented; full implementation lands when the first consumer requires it.

### Bootstrap

No seeds. Consumers seed their own `:partner-tax-id` rows, their own clearance state machine transitions (via `kontor-l10n-<cc>` modules), and their own GL-account-defaults for the new account-types.

### Alternatives considered

- **Multi-tax-id as cardinality-many scalar on `:partner`.** Rejected: loses country binding, validity window, verification timestamp. A `:partner/tax-ids #{"DE123..." "AT456..."}` set doesn't tell the bridge which one to use for a DE-invoiced order.
- **Per-invoice `:invoice/reverse-charge?` flag rather than per-line.** Rejected: a single invoice can mix reverse-charge B2B services + standard-rated goods. Per-line is the correct granularity (the OFBiz `OrderAdjustment` per-line tax pattern, generalized).
- **Recognition `:deferred` baked into kontor.invoice.posting's main flow** with the deferred-revenue account hardcoded. Rejected: the deferred-revenue account is per-tenant (DATEV SKR03 has 0990, SKR04 different, US GAAP different). Push to `:gl-account-default :sales-revenue-deferred` lookup.
- **Skip the recognition flag — let kontor-revrec subclass the bridge.** Rejected: a single attr on `:invoice-line` is cheaper to migrate than a parallel bridge code path. Revrec adds the lifecycle (release schedule, performance obligations) on top of this primitive.
- **Withholding tax as a dedicated entity** (`:withholding-event` with payment-time linkage). Rejected for v1: a boolean flag on `:invoice-line` plus a deferred-credit pattern is enough for IN TDS / MX ISR / US 1099. Full lifecycle (compute, accrue, remit) belongs to a future `kontor-tax-withholding` companion if a customer needs it.
- **Embed Italy SdI / India IRN status states in the invoice schema** (e.g. `:invoice/sdi-status` keyword). Rejected: jurisdiction-specific bits belong in `kontor-l10n-it` / `kontor-l10n-in`. The invoice-state machine ships generic `:pending-attestation` + `:rejected`; per-jurisdiction code paths interpret what those mean.
- **`:invoice/tax-inclusive?` as a per-line flag.** Rejected: tax-inclusive vs exclusive is an invoice-level convention (Stripe Tax, Shopify, ERPNext all treat it this way). Lines within one invoice share the convention.

### Migration

- `:partner-tax-id` is a new junction; no migration. Existing `:partner/tax-id` scalars remain. A future helper can backfill the junction from the scalar for tenants that adopt the multi-tax-id pattern later.
- `:invoice/tax-inclusive?` defaults to false (matches existing behavior — `:unit-price` is net).
- `:invoice-line/recognition` defaults to `:direct` (matches existing behavior — credit revenue immediately).
- The new invoice status transitions are additive; existing direct `:draft → :sent` transitions are preserved.

### Implications

- 1 new kernel junction entity (`:partner-tax-id`).
- 3 new attrs on `:invoice` / `:invoice-line` (companion-installed).
- 4 new entries in the `debit-credit-for` map (`kontor-invoice.posting`).
- 4 new `:status-transition` seeds for the invoice clearance lifecycle.
- Helpers added to `kontor.partner` (3 fns) + `kontor.invoice.bridge` (passthrough).
- Test coverage: multi-tax-id round-trip + per-country lookup + temporal validity, tax-inclusive flag, recognition :deferred routing to a different GL account, clearance state transitions.
- Forward-compat: kontor-revrec consumes `:invoice-line/recognition :deferred` + `:schedule` (ADR-032). kontor-l10n-it / -in / -br consume the clearance state machine + per-jurisdiction `EInvoiceProvider` impl per ADR-018 + ADR-024.

Date: 2026-05-12.

---

## ADR-041 — Workflow extensions: time-based transitions, side-effect intents, bulk API, account-type-direction table

**Decision.** Land four workflow primitives that complete the Stage J-2 cross-cutting pass and pave the way for Stage K (kontor-procurement):

1. **Time-based transitions** — `:status-transition/auto-after-millis` attr + `kontor.status-machine/sweep-time-based!` sweeper. Universal customer expectation (auto-cancel after 48h, auto-archive after 90d). Salesforce, Temporal, Camunda all ship; we were the only system without.
2. **Side-effect intent rows** — `:side-effect-intent` kernel entity + `kontor.side-effect` dispatcher namespace. Caller writes status-history + intent row in the SAME tx; a worker drains intents and marks them done. Prevents double-emails / double-EDI-fires on retry. Idempotency by key.
3. **Bulk transition API** — `kontor.status-machine/bulk-record-status-change-tx-data` returning composable tx-data for N entities in one tx. Bench-friendly, audit-correct (one history row per entity).
4. **`:account-type-direction` data table** (kernel) — moves the debit/credit map from a hardcoded `case` in `kontor.invoice.posting` to a queryable kernel entity. Procurement (kontor-procurement, Stage K) extends with `:goods-receipt-accrual`, `:landed-cost`, `:price-variance`, `:exchange-variance`. Revrec extends with `:revenue-recognized`, `:revenue-allocated`. Each is a transact, not a code edit.

This is the FOURTH and final Stage J-2 cross-cutting primitive ADR (per ADR-037). It resolves the workflow-extension + procurement-forward-compat gaps surfaced by the status-machine market-pain research + the local code review.

### Why these four primitives belong in one ADR

The four share a common theme: **make extensible what was hardcoded.** Time-based transitions extend the `:status-transition` table; intent rows extend `record-status-change-tx-data` composition; bulk API extends throughput; debit/credit table extends GL-routing semantics. Each addresses an extensibility gap that future companions (procurement, revrec, return, payment) need.

### Schema

#### Time-based transitions (kernel extension)

Add one attribute to the existing `:status-transition` entity (ADR-034):

```clojure
:status-transition/auto-after-millis  long
                                      ;; Duration in milliseconds.
                                      ;; When set, the sweeper auto-
                                      ;; applies this transition to
                                      ;; entities that have been in
                                      ;; the from-state longer than
                                      ;; the duration. Nil = manual
                                      ;; only.
```

The sweeper helper:

```clojure
(defn sweep-time-based!
  "Scan all :status-transition rows that have :auto-after-millis set.
   For each, find entities currently in the from-state and where
   tx-time of the last status-history row (or entity creation if no
   history) is older than `(now - auto-after-millis)`. Apply the
   transition for each, with :reason :system-scheduled."
  [conn]
  ...)
```

Bitemporal-friendly: the sweeper queries by tx-time, not wall-clock — missed sweeps replay correctly. Consumer runs the sweeper on a schedule (datahike's `:keep-history?` makes this idempotent).

#### Side-effect intent rows (kernel entity)

```clojure
:side-effect-intent/key            string :db.unique/identity
                                  ;; Caller-supplied idempotency key.
                                  ;; Convention: hash of (entity-id,
                                  ;; transition, attempt-no, payload-
                                  ;; hash). Worker dedupes on this.

:side-effect-intent/type           keyword
                                  ;; :send-email | :send-edi |
                                  ;; :send-peppol | :charge-card |
                                  ;; :webhook | :notify-slack | …

:side-effect-intent/payload        string
                                  ;; EDN or JSON blob the consumer
                                  ;; interprets. Kernel doesn't parse.

:side-effect-intent/status         keyword
                                  ;; :pending | :processing | :done |
                                  ;; :failed | :abandoned

:side-effect-intent/created-at     instant
:side-effect-intent/processing-at  instant     ; when worker claimed
:side-effect-intent/processed-at   instant     ; when worker finished
:side-effect-intent/last-error     string      ; for :failed
:side-effect-intent/retry-count    long
:side-effect-intent/max-retries    long
:side-effect-intent/origin-history ref → :status-history
                                  ;; The status-history row that
                                  ;; produced this intent.
```

The dispatcher pattern (in `kontor.side-effect`):

```clojure
;; Caller composes (status-change tx-data + side-effect intent) in ONE tx:
(d/transact conn
            (concat (sm/record-status-change-tx-data db change-spec)
                    [{:side-effect-intent/key (str entity-id "-" transition "-1")
                      :side-effect-intent/type :send-email
                      :side-effect-intent/payload (pr-str {:to ... :subject ...})
                      :side-effect-intent/status :pending
                      :side-effect-intent/created-at (java.util.Date.)
                      :side-effect-intent/origin-history -1}]))

;; Worker (consumer-side) drains:
(let [intents (kontor.side-effect/pending db {:type :send-email})]
  (doseq [intent intents]
    (try
      (do-the-email-thing intent)
      (mark-done! conn intent))
      (catch Exception e
        (mark-failed! conn intent (.getMessage e))))))
```

The kernel ships only the entity + the dispatcher namespace (queries + state-machine for the intent itself). The actual side-effect executors (email senders, EDI clients, etc.) are consumer-side.

#### Bulk transition API (helper, no schema)

```clojure
(defn bulk-record-status-change-tx-data
  "Validate + build tx-data for N status changes in one tx. Each
   change-spec is validated independently; if any fail, the whole
   batch is rejected.

   Returns a single tx-data vector — caller transacts it (or composes
   with other tx-data, same as record-status-change-tx-data).

   Optimization: applicable-policies is cached per (entity-type,
   facet, from, to, org) tuple within the call."
  [db change-specs]
  ...)
```

#### `:account-type-direction` data table (kernel)

```clojure
:account-type-direction/invoice-type  keyword
                                      ;; :sales | :purchase |
                                      ;; :credit-memo | :debit-memo
:account-type-direction/account-type  keyword
                                      ;; :sales-revenue |
                                      ;; :sales-tax-payable |
                                      ;; :goods-receipt-accrual | …
:account-type-direction/direction     keyword
                                      ;; :debit | :credit
:account-type-direction/active        boolean
:account-type-direction/identity      tuple [invoice-type, account-type]
                                      unique
```

`kontor.invoice.posting/debit-credit-for` rewrites to:

```clojure
(defn debit-credit-for
  "Look up the (invoice-type, account-type) → :debit | :credit map
   from the :account-type-direction kernel table. Falls back to the
   built-in default map for un-seeded entries (so consumers don't
   need to seed the canonical set just to post a vanilla invoice)."
  [db invoice-type account-type]
  (or (d/q '[:find ?dir .
             :in $ ?it ?at
             :where
             [?r :account-type-direction/invoice-type ?it]
             [?r :account-type-direction/account-type ?at]
             [?r :account-type-direction/direction ?dir]
             [?r :account-type-direction/active true]]
           db invoice-type account-type)
      (default-direction-for invoice-type account-type)))
```

`default-direction-for` is the existing hardcoded map (with the ADR-040 additions for `:sales-revenue-deferred`, `:withholding-tax-*`). It serves as the fallback when no row is seeded.

This is the SAME pattern as `kontor.status-machine/legal-transition?` (ADR-034) + `kontor.invoice.posting/resolve-gl-account` (ADR-036): consult a queryable table first; fall back to a sensible default if not seeded. Composition with ADR-031 per-org overrides: consumers can seed per-org direction rules if a regional GAAP requires opposite-side posting (rare but real).

### Inverse-pair role-direction (documentation only)

The status-machine pain research flagged that `:bill-to` is ambiguous: on a sales order it means "the customer being billed"; on a purchase order it means "we are the bill-to (the buyer's accounting view)." kontor's choice (per ADR-035): **`:order-role/role-type` is always buyer-perspective**. On a sales order, `:customer` is the customer; on a purchase order, `:supplier` is the supplier. Both use `:bill-to` to mean "the party being charged from our books" (i.e., the customer for sales, ourselves for purchase).

This convention is documented in:
- ADR-033 vocabulary section (canonical role-types).
- ADR-035 implications section.
- `kontor.partner` namespace docstring.
- `kontor.sales` namespace docstring.

No schema change; just align documentation. kontor-procurement (Stage K) inherits without redefining.

### Helpers

#### `kontor.status-machine` (extends existing)

- `sweep-time-based!` — scans and applies auto-transitions.
- `bulk-record-status-change-tx-data` — composable batch variant.
- `bulk-record-status-change!` — thin wrapper that transacts the bulk tx-data.

#### `kontor.side-effect` (new namespace)

- `by-key` / `resolve-intent`.
- `pending` — list `:pending` intents, optionally filtered by `:type`.
- `claim!` — atomic state transition `:pending → :processing`.
- `mark-done!` — `:processing → :done` with `:processed-at`.
- `mark-failed!` — `:processing → :failed` with `:last-error` + retry-count bump.
- `mark-abandoned!` — terminal failure (no more retries).

#### `kontor.invoice.posting` (refactor)

- `debit-credit-for` rewritten as documented above.
- `default-direction-for` private fn holding the fallback map.
- Public surface unchanged; the rewrite is backward-compatible.

### Bootstrap

No seeds. Consumers transact their own time-based transitions, intent types, account-type-direction overrides.

### Alternatives considered

- **Time-based transitions as a cron-style ADR-032 `:schedule`.** Tempting (we already have schedules), but schedules drive `:schedule-occurrence` rows tied to a posting amount. Time-based transitions drive `:status-history` rows tied to a state change. Different shape; conflating would muddy both. Keep separate.
- **Side-effects as datahike transactor middleware** (every transact triggers configured side-effect dispatchers). Rejected: defeats the bitemporal "tx-data is data" model. Intent rows are queryable + auditable; middleware fires-and-forgets.
- **Side-effect intent state machine** with full ADR-034 status-transition rows. Rejected for v1: the intent's lifecycle is simple (`:pending → :processing → :done | :failed`); doesn't need the full table-driven machinery. If it grows complex (priorities, deadlines, fan-out), promote later.
- **`:account-type-direction` as Clojure data (a registry of fns).** Rejected: ADR-037's whole "vocabulary as data" principle. Procurement needs per-tenant extension; can't be code.
- **Bulk transitions as a separate `:bulk-status-change` entity** (one row per batch with N child rows). Rejected: existing `:status-history` rows already form an implicit batch (same tx-time). Datahike's tx-time + `:db/txInstant` query give us bulk-grouping for free.
- **Inverse-pair role-direction as schema-level enum constraint.** Rejected: role vocabulary is an open keyword set (consumers extend). A constraint would block extensions. Documentation discipline is right.

### Implications

- 1 new kernel attr on `:status-transition` (`:auto-after-millis`).
- 1 new kernel entity (`:side-effect-intent`, 10 attrs).
- 1 new kernel entity (`:account-type-direction`, 5 attrs).
- 1 new kernel namespace (`kontor.side-effect`).
- Refactor of `kontor.invoice.posting/debit-credit-for` (backward-compatible).
- ~3 new helpers in `kontor.status-machine` (`sweep-time-based!`, `bulk-record-status-change-tx-data`, `bulk-record-status-change!`).
- Test coverage: time-based sweep fires the transition, idempotent on re-run; intent-row create + claim + mark-done round-trip; bulk-transition validates + writes N history rows in one tx; account-type-direction override beats the default.
- Forward-compat: Stage K (kontor-procurement) seeds new `:account-type-direction` rows for `:goods-receipt-accrual`, `:landed-cost`, `:price-variance`, `:exchange-variance`. Stage M (kontor-revrec) seeds rows for revenue lifecycle.
- Forward-compat: future `kontor-collections` (Stage L) seeds time-based transitions for auto-aging bucket promotion (`:order/status` → `:past-due-30 → :past-due-60` via `:auto-after-millis`).

### Stage J-2 close-out

This ADR completes the Stage J-2 cross-cutting primitive pass started by ADR-037. The four ADRs (038-041) collectively address the ~30 P1 items from research note 13:

- ADR-038 audit + governance: codified reasons, supporting-docs, SoD policy.
- ADR-039 master-data: merge, bank-account, credit, tags, KYC.
- ADR-040 jurisdiction: multi-tax-id, reverse-charge, tax-inclusive, recognition, withholding, clearance lifecycle.
- ADR-041 workflow: time-based, side-effect intents, bulk, account-type-direction.

**Stage K (kontor-procurement)** can now start with the substrate it needs: `:account-type-direction` for procurement-specific GL routing, `:requirement` entity scaffolded on ADR-035 order shape, 3-way match using ADR-040 `:invoice-line/order-item` linkage. Per ADR-037 hybrid plan: Stage K ~2-3 weeks, then Stages L+ at faster cadence.

Date: 2026-05-12.

---

## ADR-042 — `kontor-procurement`: requisition + receipt + 3-way match + drop-ship + RTV

**Decision.** Land Stage K (`kontor-procurement` companion under `modules/procurement/`) covering the full procure-to-pay cycle:

- **Forward flow**: `:requirement` (requisition) → commit-to-PO via `:requirement-commitment` junction → `:order/type :purchase` (reuses ADR-035) → `:receipt` (physical goods) OR `:service-acceptance` (services) → kernel `:invoice/type :purchase` (reuses ADR-036) with **explicit GR/IR clearing per (PO-line, commodity)** via ADR-041's `:account-type-direction`.
- **Reverse flow**: `:return` aggregate with `:return/type :customer | :vendor` discriminator (OFBiz `ReturnHeader.returnHeaderTypeId` pattern), `:return-item` lines, `:return-response` for what was done (replacement / credit / refund), `:return-item-billing` junction for credit memos.
- **Drop-ship link**: `:order-item-assoc` generalized junction handling drop-ship + substitution + replacement + upgrade (OFBiz `OrderItemAssoc` pattern; one entity, four `:type` values).
- **Tolerance policy**: `:match-tolerance` entity keyed `[entity, supplier?, product?]` with priority lookup (kontor improvement over OFBiz's lack of any tolerance config + SAP's per-company-code-only limitation).
- **3-way match as state**: `:invoice/match-status ∈ {:auto-matched | :exception-* | :manual-approved | :disputed | :cleared}` driven by ADR-034's table; composes with ADR-038's `:approval-policy/rule :requires-three-way-match-pass` (new rule keyword) for posting gating.

Three Stage K research-before agents (OFBiz deep dive, market-pain online, internal gap analysis — research note 14) converged on this shape. The substrate is unusually complete: ADR-030's `plan-stock-move` already handles `:direction :in` with GR/IR clearing callbacks; ADR-031's per-(entity, ledger, commodity) sum-to-zero structurally prevents GR/IR residuals from FX or partial-receipt mismatches; ADR-038's `:approval-policy` composes for SoD + tolerance-override; ADR-040's `:withholding-on-payment?` + `:invoice-line/reverse-charge?` flags cover IN TDS / MX ISR / US 1099 / EU intracommunity reverse-charge.

This ADR is large because the user (per the project's "complete stages" rhythm) asked for v1 to cover the full P2P + reverse flow rather than ship a minimal foundation and defer half. The implementation lands in **four coherent commits** (schema + state machines; forward 3-way match; reverse flow + credit memos; drop-ship + bridge polymorphism).

### Why full scope, not a foundation-only minimum

The Stage K research surfaced six places where deferring half would create rework risk for the deferred half:

1. **GR/IR clearing semantics depend on whether reverse-flow exists.** A receipt's `:gr-ir-clearing` credit nets against an invoice's debit; with RTV, a return-receipt's debit nets against a credit-memo's credit. Designing the GR/IR pattern without RTV in mind risks settling on a shape that doesn't extend cleanly.
2. **3-way match invariants need the full data shape.** Match validates `(received-qty - returned-qty = invoiced-qty - credited-qty)`. Half-shipping creates a 2-way-match-only system that has to be retrofitted.
3. **Status machine completeness.** Without RTV, `:order/status :completed` is the terminal end-state; with RTV, returns can extend the lifecycle. Better to seed all transitions in one pass.
4. **`:order-item-assoc` enables three things at once.** Drop-ship + substitution + replacement-order — shipping the join entity without the workflows is cheap; deferring the entity forces a junction-shape revisit later.
5. **`:account-type-direction` seeds for procurement.** All eight new account-types (`:gr-ir-clearing`, `:goods-receipt-accrual`, `:landed-cost-variance`, `:price-variance`, `:exchange-variance`, `:receive-reject-loss`, `:prepaid-expense`, `:vendor-credit-memo`) seed together; partial seeding leaves the bridge code branching on "is this account-type defined yet?"
6. **Cross-companion bridge polymorphism is single-edit.** `kontor.invoice.bridge/make-invoice-from-order!` becomes polymorphic on `:order/type` once; doing it twice for forward then reverse flow is duplicate work.

The trade-off accepted: the post-implement review-after pass will have a larger surface to audit (~12 entities + ~25 transitions + ~50 helpers vs ~5 entities for a foundation-only ship). Per ADR-037's "research-before / implement / review-after" rhythm, the review will find issues; that's the design intent.

### Entity inventory

**Forward flow (5 entities):**
- `:requirement` — requisition root (OFBiz `Requirement` lines 2171-2215).
- `:requirement-commitment` — many-to-many `:requirement ↔ :order-item` junction (OFBiz `OrderRequirementCommitment`).
- `:receipt` — shipment-receipt header (OFBiz `ShipmentReceipt` partial; we collapse `Shipment` + `ShipmentReceipt` for v1; full shipment-lifecycle is a follow-up).
- `:receipt-item` — per-PO-line receipt detail with `:quantity-accepted` + `:quantity-rejected` split.
- `:receipt-invoice-billing` — `:receipt ↔ :invoice-line` junction (mirror of `:order-item-billing` from ADR-036).

**Service procurement (1 entity):**
- `:service-acceptance` — parallel to `:receipt` for non-physical PO lines (`:order-item/requires-receipt? false`). Single attestation event with `:accepted-by-uid`, `:accepted-at`, `:acceptance-evidence` (ref to `:audit-doc` per ADR-038).

**Drop-ship + substitution + replacement (1 entity):**
- `:order-item-assoc` — generalized `:order-item ↔ :order-item` junction with `:type ∈ {:drop-shipment | :substitute | :replacement | :upgrade}`. OFBiz `OrderItemAssoc` pattern lifted verbatim.

**Match tolerance config (1 entity):**
- `:match-tolerance` — keyed `[entity, supplier?, product?]` with `:qty-pct-over`, `:qty-abs-over`, `:price-pct-over`, `:price-abs-over` bands.

**Reverse flow (4 entities):**
- `:return` — root with `:return/type :customer | :vendor` discriminator (OFBiz `ReturnHeader.returnHeaderTypeId`).
- `:return-item` — return lines with `:expected-disposition ∈ {:available | :defective | :scrap}`.
- `:return-response` — what was done (`:replacement-order` ref / `:payment` ref / `:billing-account` credit / amount).
- `:return-item-billing` — `:return-item ↔ :invoice-line` junction (for credit memos).

**Cross-cutting attribute extensions** (additive to existing schemas):
- `:order-item/requires-receipt?` (boolean, default true) — Stage K extension to ADR-035.
- `:order-item/category` (keyword: `:direct | :indirect | :services | :asset`) — Stage K extension to ADR-035 for reporting + cost-center routing.
- `:invoice/match-status` (keyword) — Stage K extension to ADR-036.

Total: **12 new entities + 3 attr extensions = ~110 new schema attrs**.

### Schema — Forward flow

#### `:requirement` (companion-installed)

```clojure
:requirement/external-id   string :db.unique/identity
:requirement/type          keyword          ; :product | :transfer | :production | :service | :asset-maint
:requirement/status        keyword          ; :proposed | :approved | :ordered | :received |
                                            ;   :rejected | :cancelled (state-machine driven)
:requirement/product-id    string           ; consumer-supplied product ref
:requirement/quantity      bigdec
:requirement/uom           keyword          ; or ref to :commodity
:requirement/facility-id   string           ; destination (consumer-supplied; no :facility entity yet)
:requirement/facility-to-id string          ; for :transfer requirements
:requirement/required-by-date instant
:requirement/start-date    instant
:requirement/estimated-budget bigdec
:requirement/budget-commodity ref → :commodity
:requirement/entity        ref → :entity    ; multi-entity scope (ADR-031)
:requirement/cost-center   ref → :analytic-account ; ADR-032
:requirement/justification string           ; replaces OFBiz `useCase` + `reason`
:requirement/description   string
:requirement/created-at    instant
:requirement/created-by-uid ref
```

The `:type` discriminator follows OFBiz: `:product` is the bulk case (replenishment, buy-for-resale), `:transfer` is inter-facility, `:production` is manufacturing demand, `:service` is non-physical, `:asset-maint` is maintenance.

#### `:requirement-commitment` junction (companion-installed)

```clojure
:requirement-commitment/requirement ref → :requirement
:requirement-commitment/order-item  ref → :order-item
:requirement-commitment/quantity    bigdec
:requirement-commitment/committed-at instant
:requirement-commitment/identity    tuple [requirement, order-item] unique
```

Many-to-many is load-bearing: one requirement for 100 widgets may aggregate with 50 other small requirements into a single PO line; conversely, a min-order-quantity constraint can split one requirement across multiple POs. Single-FK approach (`:requirement/order-item`) would force consolidation, breaking real workflows.

#### `:receipt` (companion-installed)

```clojure
:receipt/external-id        string :db.unique/identity
:receipt/order              ref → :order       ; the PO this receipt is against
:receipt/ship-group         ref → :ship-group  ; optional, for multi-destination POs
:receipt/status             keyword            ; :pending | :accepted | :rejected (state-machine)
:receipt/received-at        instant
:receipt/received-by-uid    ref
:receipt/inspector-uid      ref
:receipt/inspected-at       instant
:receipt/packing-slip-ref   ref → :audit-doc   ; ADR-038
:receipt/notes              string
:receipt/facility-id        string             ; destination facility
:receipt/carrier-partner    ref → :partner     ; :partner-role :carrier
:receipt/tracking-number    string
```

#### `:receipt-item` (companion-installed)

```clojure
:receipt-item/receipt        ref → :receipt
:receipt-item/order-item     ref → :order-item   ; the PO line received
:receipt-item/product-id     string              ; denorm of :order-item/product-id
:receipt-item/quantity-accepted bigdec
:receipt-item/quantity-rejected bigdec           ; with reason; routes to :receive-reject-loss GL
:receipt-item/rejection-reason keyword
                                                  ;; :damaged | :wrong-item | :expired |
                                                  ;; :quantity-mismatch | :quality-fail
:receipt-item/lot            ref → :lot          ; ADR optional; per-:valuation-book/lot-required? policy
:receipt-item/unit-cost      bigdec              ; actual cost at receipt; may differ from PO line
:receipt-item/identity       tuple [receipt, order-item] unique
```

#### `:receipt-invoice-billing` junction (companion-installed)

```clojure
:receipt-invoice-billing/receipt      ref → :receipt
:receipt-invoice-billing/invoice-line ref → :invoice-line
:receipt-invoice-billing/quantity     bigdec
:receipt-invoice-billing/identity     tuple [receipt, invoice-line] unique
```

This is the **third FK of the 3-way match**, mirroring `:order-item-billing` from ADR-036. The match invariant becomes a datalog query:

```
forall (:order-item ?oi)
  (sum :receipt-item.quantity-accepted from receipts of ?oi)
  - (sum :return-item.quantity from customer returns referencing ?oi)
  =
  (sum :invoice-line.quantity from billings of ?oi)
  - (sum :return-item-billing.quantity from credit memos against ?oi)
```

Tolerance bands offset the equality check by `:match-tolerance` bands.

### Schema — Service acceptance

```clojure
:service-acceptance/external-id      string :db.unique/identity
:service-acceptance/order            ref → :order
:service-acceptance/order-item       ref → :order-item
:service-acceptance/quantity-accepted bigdec    ; e.g., hours, days, deliverable count
:service-acceptance/accepted-at      instant
:service-acceptance/accepted-by-uid  ref
:service-acceptance/acceptance-evidence ref → :audit-doc  ; ADR-038
:service-acceptance/notes            string
:service-acceptance/identity         tuple [order-item, accepted-at] unique
```

3-way match degenerates cleanly: when `:order-item/requires-receipt? false`, the match query uses `:service-acceptance/quantity-accepted` instead of `:receipt-item/quantity-accepted`. Same invariant, different data source.

### Schema — Order-item-assoc (drop-ship link + substitution + replacement)

```clojure
:order-item-assoc/from-order-item ref → :order-item    ; the source line
:order-item-assoc/to-order-item   ref → :order-item    ; the linked line
:order-item-assoc/type            keyword
                                  ;; :drop-shipment   — SO line fulfilled by linked PO line
                                  ;; :substitute      — alt product offered for unavailable original
                                  ;; :replacement     — replacement-order line for customer return
                                  ;; :upgrade         — upgrade to a higher SKU
:order-item-assoc/quantity        bigdec
:order-item-assoc/note            string
:order-item-assoc/identity        tuple [from-order-item, to-order-item, type] unique
```

For drop-ship: SO line points to PO line via `:type :drop-shipment`. The PO's `:ship-group/contact-mech` references the SO's `:ship-group/contact-mech` (NOT a copy — bitemporal lookup answers "what was the address at PO time" for free). For substitution: SO line → another SO line (or PO line) with `:type :substitute`. For replacement: original SO line ← new SO line.

### Schema — Match tolerance

```clojure
:match-tolerance/entity       ref → :entity     ; required; tenant scope
:match-tolerance/supplier     ref → :partner    ; optional; supplier-specific
:match-tolerance/product-id   string            ; optional; product-specific
:match-tolerance/qty-pct-over bigdec            ; e.g., 0.05M = 5%
:match-tolerance/qty-abs-over bigdec            ; absolute unit allowance
:match-tolerance/price-pct-over bigdec
:match-tolerance/price-abs-over bigdec
:match-tolerance/price-abs-commodity ref → :commodity
:match-tolerance/active       boolean
:match-tolerance/identity     tuple [entity, supplier, product-id] unique
```

Lookup priority (mirrors `:gl-account-default` from ADR-036): `(entity, supplier, product)` → `(entity, supplier, nil)` → `(entity, nil, nil)` → kernel default (0% — strict match). Returns the first non-nil match.

### Schema — Reverse flow (RTV / customer return)

#### `:return` (companion-installed)

```clojure
:return/external-id    string :db.unique/identity
:return/type           keyword          ; :customer | :vendor
:return/status         keyword          ; :requested | :accepted | :received | :completed |
                                        ;   :cancelled | :rejected (state-machine driven)
:return/from-party     ref → :partner   ; for :customer → the customer; for :vendor → ourselves
:return/to-party       ref → :partner   ; for :customer → ourselves; for :vendor → the supplier
:return/order          ref → :order     ; the original SO (for :customer) or PO (for :vendor)
:return/entity         ref → :entity    ; multi-entity scope
:return/destination-facility-id string  ; where the returned goods land
:return/supplier-rma   string           ; vendor's RMA number (for :vendor returns)
:return/entry-date     instant
:return/notes          string
:return/supporting-doc ref → :audit-doc ; e.g., customer email request
```

#### `:return-item` (companion-installed)

```clojure
:return-item/return         ref → :return
:return-item/order-item     ref → :order-item     ; the original line being returned
:return-item/seq-id         string
:return-item/identity       tuple [return, seq-id] unique
:return-item/product-id     string                ; denorm
:return-item/return-quantity bigdec
:return-item/received-quantity bigdec             ; may differ from return-quantity
:return-item/return-price   bigdec                ; price at return (may differ from invoice)
:return-item/reason         keyword
                            ;; :damaged | :defective | :wrong-item | :not-as-described |
                            ;; :no-longer-needed | :late-delivery | :customer-request
:return-item/return-type    keyword
                            ;; :store-credit | :cash-refund | :exchange | :vendor-credit
:return-item/expected-disposition keyword
                            ;; :available | :defective | :scrap | :return-to-supplier
:return-item/status         keyword
:return-item/response       ref → :return-response
```

#### `:return-response` (companion-installed)

```clojure
:return-response/return-item ref → :return-item :db.unique/value  ; 1:1
:return-response/type        keyword                              ; :replacement-order | :credit-memo |
                                                                  ; :cash-refund | :billing-account-credit
:return-response/replacement-order ref → :order                   ; new SO/PO created in response
:return-response/credit-memo ref → :invoice                       ; the credit-memo invoice (kernel)
:return-response/payment-ref string                               ; for refunds processed externally
:return-response/amount      bigdec
:return-response/amount-commodity ref → :commodity
:return-response/created-at  instant
```

#### `:return-item-billing` junction (companion-installed)

```clojure
:return-item-billing/return-item     ref → :return-item
:return-item-billing/invoice-line    ref → :invoice-line     ; the credit-memo line
:return-item-billing/quantity        bigdec
:return-item-billing/amount          bigdec
:return-item-billing/identity        tuple [return-item, invoice-line] unique
```

Mirrors `:order-item-billing` from ADR-036 and `:receipt-invoice-billing` from this ADR. The trio of `:order-item-billing`, `:receipt-invoice-billing`, `:return-item-billing` closes the audit loop for every quantity flow.

### Cross-cutting attribute extensions

```clojure
;; On :order-item (kontor-sales schema extension)
:order-item/requires-receipt? boolean    ; default true; false for services
:order-item/category          keyword    ; :direct | :indirect | :services | :asset

;; On :invoice (kontor-invoice schema extension)
:invoice/match-status         keyword
                              ;; :auto-matched     — all qty/price match within tolerance
                              ;; :exception-price  — price variance breach
                              ;; :exception-qty    — qty variance breach
                              ;; :exception-missing-receipt — invoice with no receipt
                              ;; :exception-missing-po — invoice with no PO
                              ;; :manual-approved  — exception resolved by override
                              ;; :disputed         — held pending resolution
                              ;; :cleared          — fully reconciled (post-payment)
```

### State machine seeds (~25 transitions)

Requirement (`:requirement/status`):
```
:nil       → :proposed       (Create Requirement)
:proposed  → :approved       (Approve)
:proposed  → :rejected       (Reject)
:proposed  → :cancelled      (Cancel)
:approved  → :ordered        (Commit to PO — via :requirement-commitment)
:approved  → :cancelled      (Cancel Approved)
:ordered   → :received       (Auto-promoted when all linked POs received)
:approved  → :proposed       (Revise back to draft — auditable)
```

Receipt (`:receipt/status`):
```
:nil       → :pending        (Create Receipt)
:pending   → :accepted       (Inspection Pass)
:pending   → :rejected       (Inspection Fail — full reject)
:accepted  → :rejected       (Post-inspection reject — quality issue found later)
```

Match (`:invoice/match-status`):
```
:nil                 → :auto-matched               (within tolerance)
:nil                 → :exception-price            (price variance breach)
:nil                 → :exception-qty              (qty variance breach)
:nil                 → :exception-missing-receipt  (invoice w/o receipt)
:nil                 → :exception-missing-po       (invoice w/o PO)
:exception-*         → :manual-approved            (override approved)
:exception-*         → :disputed                   (held pending vendor response)
:manual-approved     → :cleared                    (post-payment)
:auto-matched        → :cleared                    (post-payment)
:disputed            → :manual-approved            (resolution accepted)
:disputed            → :auto-matched               (vendor corrected; re-matched)
```

Return (`:return/status`):
```
:nil        → :requested     (RMA requested)
:requested  → :accepted      (RMA approved)
:requested  → :rejected      (RMA denied)
:requested  → :cancelled
:accepted   → :received      (goods arrived back)
:accepted   → :cancelled
:received   → :completed     (refund/replacement issued)
```

### Account-type-direction seeds (~8 new)

```clojure
;; All for :purchase invoice-type (and mirror as needed for :credit-memo)
[:gr-ir-clearing            :credit]   ; on receipt (paired with :inventory or :purchase-expense debit)
[:gr-ir-clearing            :debit]    ; on invoice (paired with :ap credit) — same row reused via invoice-type discriminator
[:goods-receipt-accrual     :credit]   ; period-end accrual when receipt without invoice
[:landed-cost-variance      :debit]    ; freight/duty/insurance allocation
[:price-variance            :debit]    ; PO unit-price vs invoice unit-price
[:exchange-variance         :debit]    ; FX rate difference (receipt vs invoice timing)
[:receive-reject-loss       :debit]    ; rejected-quantity write-off
[:prepaid-expense           :debit]    ; advance payments to suppliers
[:vendor-credit-memo        :credit]   ; credit-memo posting (mirror of customer-side)
```

The bridge's `debit-credit-for` fn (rewrite per ADR-041) consults the table first, falls back to `default-direction-for`. Stage K seeds the procurement extensions; the default map keeps existing behavior.

### Public surface

#### `kontor.procurement.requirement` namespace

- `make-requirement!` — create a `:requirement` in `:proposed` state.
- `approve-requirement!` — `:proposed → :approved` with `:approval-policy` rule consultation per ADR-038.
- `commit-to-po!` — create `:requirement-commitment` rows linking requirement(s) to PO line(s); advances `:requirement/status :approved → :ordered` when fully committed.
- `auto-promote-to-received!` — scheduled or event-driven; advances `:approved → :received` (OFBiz `checkItemStatus` analog for requirements).
- `pending-of-supplier` — query requirements assigned via `:order-role :supplier` or unassigned.
- `pull-requirement` — pulled requirement with commitments + linked POs.

#### `kontor.procurement.receipt` namespace

- `make-receipt!` — create `:receipt` + `:receipt-item` rows; status `:pending`.
- `accept-receipt!` — `:pending → :accepted`; optionally fires `post-receipt-with-inventory!`.
- `reject-receipt!` — `:pending → :rejected` with reason.
- `post-receipt-with-inventory!` — composes (1) `:receipt` creation, (2) `kontor.posting/plan-stock-move :direction :in` via ADR-030 (producing GR/IR posting), (3) `:valuation-layer` write per ADR-028, all in ONE atomic tx.
- `receipts-of-order` — query receipts for a PO.
- `quantity-received-of-order-item` — sum of `:receipt-item/quantity-accepted` for a line.

#### `kontor.procurement.match` namespace

- `applicable-tolerance` — priority lookup `(entity, supplier, product) → :match-tolerance` row.
- `three-way-report` — per `:order-item`, returns `{:ordered-qty :received-qty :invoiced-qty :ordered-price :invoiced-price :qty-variance :price-variance :verdict :match | :within-tolerance | :exception-qty | :exception-price | :exception-missing-receipt}`.
- `match-status-of-invoice` — pull `:invoice/match-status` (denormalized).
- `recompute-match-status!` — scan invoice lines, compute report, write `:match-status` via `record-status-change!`.

#### `kontor.procurement.acceptance` namespace

- `make-acceptance!` — create `:service-acceptance` for non-physical PO lines.
- `acceptances-of-order` — query acceptances for a PO.

#### `kontor.procurement.returns` namespace

(Plural, not `return` — Clojure reserves `return` as nothing but the name reads more naturally as a verb in `(returns/make-return! ...)` and avoids any future-reserved-word risk.)

- `make-return!` — create `:return` + `:return-item` rows.
- `accept-return!` — `:requested → :accepted`.
- `receive-return!` — `:accepted → :received`; fires `kontor.posting/plan-stock-move :direction :out` for customer returns into available stock; or `:in` for vendor returns back from the supplier (RTV mechanics inverted).
- `complete-return!` — `:received → :completed`; creates `:return-response` + optionally fires credit-memo creation via `make-credit-memo-from-return!`.
- `make-credit-memo-from-return!` — creates a kernel `:invoice/type :credit-memo` (for customer returns) or `:invoice/type :debit-memo` (for vendor returns); writes `:return-item-billing` junctions.

#### Cross-companion edits to `kontor.invoice.bridge`

- `make-invoice-from-order!` dispatches `:invoice-line/gl-account-type` on `(:order/type order, :order-item/category item)`:
  - Sales × `*` → `:sales-revenue`
  - Purchase × `:direct` → `:inventory` (or `:purchase-expense` if `:order-item/requires-receipt? false`)
  - Purchase × `:indirect` → `:purchase-expense`
  - Purchase × `:services` → `:purchase-expense`
  - Purchase × `:asset` → `:asset-acquisition` (a new account-type-direction we seed)
- `make-invoice-line-from-adjustment` dispatches on `(invoice-type, adjustment-type)`:
  - Purchase × `:tax` → `:purchase-tax-recoverable`
  - Purchase × `:discount` → `:purchase-discount`
  - Purchase × `:shipping` → `:shipping-expense`
  - Sales × `*` → existing mapping unchanged

### Approval policy integration

ADR-038's `:approval-policy/rule` keyword extends with one new value:
- `:requires-three-way-match-pass` — applicable at `:invoice/match-status → :cleared` transition; checks `(kontor.procurement.match/match-status-of-invoice db invoice-eid)` is `:auto-matched` or `:manual-approved`. The override path is exactly the `:no-self-approval` + `:requires-supporting-doc` chain from ADR-038 — adding manual-approval requires an `:audit-doc` ref.

The bridge's `kontor.invoice.bridge/post-to-ledger!` (modified) consults `:requires-three-way-match-pass` policy for `:purchase` invoices before committing the AP posting. Without the rule installed, posting proceeds (current behavior). With the rule installed, posting fails until `:match-status` is `:auto-matched` / `:manual-approved` / `:cleared`.

### Bootstrap

The companion's `install!`:
1. Transacts the schema additions.
2. Seeds the requirement + receipt + match-status + return state machines into `:status-transition`.
3. Seeds the procurement `:account-type-direction` rows.
4. Idempotent via composite-tuple identities.

### Alternatives considered

- **RTV as `:order/type :return-to-vendor` reusing order machinery.** Considered (internal gap analysis recommendation). Rejected per OFBiz pattern: returns have a genuinely different lifecycle (disposition flags, RMA numbers, replacement-linkage) that a unified `:order` shape obscures. The `:return` entity matches the OFBiz `ReturnHeader.returnHeaderTypeId` discriminator pattern; the codepaths are 95% identical with role inversion, exactly as OFBiz models it. Keeping forward and reverse aggregates separate also keeps the order state-machine clean.
- **Tolerance config as scalar attrs on `:partner` (per-supplier only).** Rejected: real procurement needs per-(supplier, product) granularity. SAP supports it; customers commonly need it for high-volume + high-variability product categories.
- **GR/IR via accrual-reversal pattern (NetSuite default for non-perpetual books)** instead of explicit per-PO-line clearing (SAP pattern). Rejected: explicit GR/IR per PO line makes residuals queryable + auditable; the accrual-reversal pattern hides residuals in journal entries that don't link to the originating receipt or PO.
- **`:service-acceptance` as a `:receipt` with phantom `:quantity-accepted`.** Rejected: forces fake `:lot` refs, breaks valuation-layer invariants, confuses reports. Separate entity is cleaner.
- **3-way match as a per-invoice attribute** (`:invoice/match-passed? boolean`). Rejected: not enough state for the exception path (which exception type, when override approved, by whom). State machine is right.
- **`:order-item-assoc` per-type entities** (`:drop-ship-link`, `:substitution-link`, `:replacement-link`). Rejected: one entity with `:type` discriminator is what OFBiz does and what scales when new association types appear (kit-component, alternate-source).
- **Auto-emission of drop-ship PO from SO via SECA.** Rejected for v1: too much workflow coupling. Stage K v1 ships the `:order-item-assoc/type :drop-shipment` slot; the consumer (beleg or custom app) decides whether to auto-emit. A future companion `kontor-drop-ship-orchestrator` could ship the auto-emission policy.
- **Subcontracting / consignment / VMI in v1.** Rejected: each needs valuation-layer semantics that compose differently. Subcontracting belongs to manufacturing companion (Stage K+N); consignment + VMI belong to a sibling `kontor-consignment` companion.

### Implications

- ~110 new kernel + companion attrs across 12 new entities + 3 attr extensions.
- ~25 new `:status-transition` seeds.
- ~9 new `:account-type-direction` seeds.
- 5 new namespaces under `kontor.procurement.*`.
- 2 polymorphic edits in `kontor.invoice.bridge`.
- 1 new `:approval-policy/rule` value (`:requires-three-way-match-pass`).
- Test coverage target: ~25 new tests covering each major flow (forward, reverse, drop-ship, services, tolerance application, exception override, period-close accrual reversal).
- Forward-compat: `kontor-collections` (Stage L) reads `:invoice/match-status` for AR collections workflow. `kontor-revrec` (Stage M) reads `:order-item/category :services` for revrec rules. `kontor-l10n-*` add per-country e-invoice clearance to the procurement invoice path (already supported via ADR-040 `:pending-attestation`).

### Implementation plan

Four coherent commits:

1. **ADR doc + schema + state machines + seeds** (this commit). Documentation lands first; substrate readers can review the design before code.
2. **Forward 3-way match** — `:requirement`, `:receipt`, `:service-acceptance`, match helper, bridge dispatch on `:order/type`. End-to-end test: PO → receipt → invoice → posted.
3. **Reverse flow** — `:return`, `:return-item`, `:return-response`, `:return-item-billing`, credit-memo flow.
4. **Drop-ship + cross-cutting** — `:order-item-assoc`, drop-ship-link helpers, drop-ship test (SO with linked PO; address-by-reference invariant).

After all four land: review-after agents (code-review + market-pain delta against the implementation). Then user-story integration tests per the CLAUDE.md rhythm.

Date: 2026-05-12.

### Stage K-5 P0 fixes

Both review-after agents (code-review + market-pain delta) converged on the same P0 cluster: schema + state machines landed cleanly; the *integration glue* between schema and the existing kernel substrate had gaps. Stage K-5 closes them.

1. **`post-receipt-with-inventory!` shipped.** The helper this ADR promised but didn't deliver is now in `modules/procurement/src/kontor/procurement/receipt.clj`. It atomically transitions a `:pending` receipt to `:accepted`, calls `kontor.posting/plan-stock-move :direction :in` per `:receipt-item`, walks each invocation's tempid space to disjoint regions so N items cohabit in one tx, and writes the `:valuation-layer` rows. The GR/IR audit-as-data thesis is now real: receipt Dr's inventory + Cr's GR-IR-clearing; invoice Dr's GR-IR + Cr's AP; residual on `:gr-ir-clearing` per (PO-line, commodity) nets to zero.

2. **`:requires-three-way-match-pass` rule wired.** Added the case to `kontor.status-machine/check-policy`: queries the entity's `:invoice/match-status`, accepts `nil` (sales invoices pass through), `:auto-matched`, `:manual-approved`, `:cleared`; rejects any `:exception-*` or `:disputed`. A tenant seeds the policy on `:invoice/status :draft → :sent` and posting through `post-to-ledger!` now structurally cannot proceed on a 3-way exception unless overridden first.

3. **Credit/debit-memo GL polarity fixed.** `kontor.invoice.posting/default-direction-for` now flips the parent type's direction for `:credit-memo` and `:debit-memo`: a credit memo *reverses* a sale (Dr revenue, Cr AR), a debit memo *reverses* a purchase (Dr AP, Cr expense). Without the flip the memo was posting the same direction as the original, doubling rather than reversing. End-to-end posting test added.

4. **3-way invariant filters by receipt status + nets returned qty.** `quantity-received-of-order-item` now joins on `:receipt/status :accepted` (excludes `:pending` and `:rejected`) and subtracts `:return-item` quantities where the parent `:return/type` is `:vendor` and the return is past `:accepted`. The invariant `(received − returned) = (invoiced − credited)` is now actually computable, not aspirational. `auto-promote-to-received!` reuses the same helper.

5. **Four missing state transitions seeded.** `:exception-missing-receipt → :disputed`, `:exception-missing-po → :disputed`, `:disputed → :auto-matched` (vendor-corrected re-match path). The dispute paths from missing-receipt / missing-PO were the gap the audit flagged most prominently.

6. **`:receipt-invoice-billing` junction live.** `kontor.invoice.bridge/make-invoice-from-order!` now FIFO-allocates an invoice line's `bill-qty` across the order-item's `:accepted` receipts (oldest first) and emits one `:receipt-invoice-billing` row per receipt that gets allocated. The third FK of the 3-way match is no longer dead substrate.

7. **End-to-end procurement posting test.** `modules/procurement/test/kontor/procurement/posting_test.clj` covers: receipt posts Dr inventory / Cr GR-IR; invoice posts Dr GR-IR / Cr AP; GR/IR residual = 0; full P2P chain through `post-to-ledger!`; the match-pass policy blocks/allows transitions per match-status.

#### Two collateral fixes the P0 cluster surfaced

- **Bridge routes `:purchase + :direct` → `:gr-ir-clearing`** (was `:inventory`). Per the canonical receipt-first flow: receipt already debited inventory; invoice must clear GR-IR, not re-debit inventory. The `:direct + no-receipt` edge case (VMI / prepayment) requires explicit `:invoice-line/account` override; documented.
- **Seed `[:purchase :gr-ir-clearing :debit]`** (was `:credit`). The kernel-seeded direction is for the INVOICE leg (the receipt leg hardcodes credit in `kontor.posting/receipt-postings`). The previous `:credit` was a thinko that would have made the invoice post the same direction as the receipt. Removed the misleading `[:sales :gr-ir-clearing]` mirror per review P2-3 and the dead `:vendor-credit-memo` seeds (the bridge now routes debit-memo lines via `:order-item/category` dispatch).

#### Triaged for later

Per the per-stage rhythm: P1s (tolerance pct-over semantics, drop-ship ship-to enforcement, partial-commitment multi-requirement allocation, withholding-tax + reverse-charge flag wiring, install-non-idempotent) are followups, not Stage K blockers. Stage L (kontor-collections) will reveal which actually bite consumers.

Date: 2026-05-12.

---

## ADR-043 — `kontor-collections`: AR collections + dunning + partial-payment kernel primitive

**Decision.** Land Stage L (`kontor-collections` companion under `modules/collections/`) covering AR collections workflow: aging + dunning policy + payment-promise + dispute lifecycle + credit-hold overlay + collector assignment + Reg-F-compliant frequency cap. Plus a kernel-level `:payment-application` primitive that closes the partial-payment scope-cut at `src/kontor/reconciliation.clj:38-47` (revrec, subscription, and any AR consumer needs this; collections is just the loudest customer).

### Why now

Three research-before agents (research note 15) converged: OFBiz has no collections module after ~20 years of OSS history — kontor designs into a vacuum where competitors carry the design vocabulary. kontor's substrate is unusually ready: `balance.clj` + `ledger.clj` + `reconciliation.clj` + `status_machine.clj` + `audit_doc.clj` + `side_effect.clj` + ADR-039 credit-limit primitives all compose. The 35-item market-pain list clusters on substrate-natural fixes — bitemporal dispute, sum-to-zero unapplied-cash gating, state-machine-as-data PTPs, replayable allocation, per-customer term-relative aging, frequency-cap as policy-predicate. The one hard kernel gap (partial-payment) is also the prerequisite for Stage M (revrec) and Stage N (subscription); shipping it here unblocks the whole O2C chain.

### Roadmap repositioning

Stage L was `kontor-asset` per `doc/roadmap.md:209` but `kontor-collections` per `doc/decisions.md` (ADR-037, ADR-039, ADR-041, ADR-042). The conflict resolves in favor of collections: it's the natural continuation of the Stage K P2P substrate into O2C, and Stage M revrec consumes the partial-payment primitive collections lands. `kontor-asset` becomes **Stage L′** (depreciation register + ADR-032 `:schedule` consumer; ~2 weeks). Roadmap updated.

### Kernel-side: `:payment-application` (the prerequisite)

`reconciliation.clj` currently flips invoices `:sent → :paid` only on full settlement; partial payments either match-and-disappear (lossy) or remain fully-open (inaccurate aging). Stage L adds a kernel primitive.

#### Schema (kernel)

```clojure
:payment-application/payment       ref → :transaction (the cash receipt tx)
:payment-application/invoice       ref → :invoice
:payment-application/amount        bigdec (signed: + reduces buyer's open AR;
                                            − for an allocation reversal)
:payment-application/commodity     ref → :commodity
:payment-application/applied-at    instant (when the application was recorded)
:payment-application/applied-by-uid ref → :create/uid
:payment-application/strategy      keyword: :fifo | :customer-instruction
                                            | :proportional | :cherry-pick
:payment-application/reversal-of   ref → :payment-application (optional;
                                            null for forward allocations,
                                            set for replayable undoes)
:payment-application/identity      tuple [payment invoice applied-at]
                                            unique
```

The composite identity tuple + datahike's tx-time = free allocation replay: to "undo" an application, transact a new row with `:reversal-of` pointing at the original and `:amount` negated. The bitemporal query "what allocations applied to invoice X as of date D" answers itself.

#### Helpers (`src/kontor/payment_application.clj`)

```clojure
(apply-payment! conn {:payment :invoice :amount :strategy :applied-by-uid ...})
(applications-of db invoice-eid {:as-of-tx :as-of-valid})
(unapplied-cash-balance db partner-eid {:as-of-tx :as-of-valid})
;; Replayable reversal of a prior application.
(reverse-application! conn application-eid {:reason :reason-note :applied-by-uid})
;; FIFO allocation across N open invoices for a payment.
(allocate-fifo! conn {:payment :partner :total-amount :applied-by-uid
                      :exclude-disputed? boolean})
```

#### Aging extension (`src/kontor/aging.clj`)

Existing hardcoded `:transaction/due-date` becomes one of three aging methods:

```clojure
(aging-rows db {:method :due-date | :invoice-date | :statement-date
                :buckets ...
                :as-of-valid ...
                :partner-payment-terms ; optional map (partner-eid → days)
                                       ; for per-customer term-relative buckets
                ...})
```

`open-amount` per invoice is now `(invoice/total-gross − sum of applied-amounts as-of)`. Closes the `reconciliation.clj:38-47` scope-cut. Bitemporal: aging at `:as-of-valid date-D` reads applications that were `:applied-at ≤ D`.

#### Invoice status extension

The kernel status machine grows one transition: `:sent → :partially-paid` (when an application reduces but doesn't close). `:partially-paid → :paid` on final application; `:partially-paid → :partially-paid` legal for additional applications. The existing `:sent → :paid` keeps working for full-payment flows.

### Companion-side: `kontor-collections`

#### Entities

```
:collection-case             — the workflow root, one open case per (partner, entity)
  /code                      — string identity
  /partner                   — ref → :partner
  /entity                    — ref → :entity (ADR-031 scope)
  /state                     — facet, ADR-034
  /opened-at                 — instant
  /closed-at                 — instant
  /assigned-collector        — ref → :create/uid
  /collections-segment       — keyword: :strategic | :standard | :small | …
  /strategy                  — keyword: :reminder-only | :phone | :legal
                                        | :external-agency
  /total-overdue             — bigdec (denormed for fast filter; nightly sweep)
  /oldest-invoice            — ref → :invoice (denormed)

:payment-promise             — the PTP, first-class
  /case                      — ref → :collection-case
  /invoice                   — ref → :invoice (optional: case-level promises)
  /amount                    — bigdec
  /commodity                 — ref → :commodity
  /promised-by-date          — instant
  /captured-by-uid           — ref → :create/uid
  /captured-via              — keyword: :phone | :email | :portal | :api
  /status                    — facet, ADR-034
                                #{:open :kept :broken :renegotiated :cancelled}

:dispute                     — invoice (or line) dispute, first-class
  /invoice                   — ref → :invoice
  /scope                     — ref to invoice OR ref to :invoice-line
                              (line-level dispute per market-pain #18)
  /disputed-amount           — bigdec (subset of invoice total)
  /reason-code               — keyword (extensible per l10n)
                                #{:pricing :short-ship :damaged :duplicate-bill
                                  :tax :credit-misapplied :unauthorized :other}
  /opened-at                 — instant
  /opened-by-uid             — ref → :create/uid
  /sla-deadline              — instant (derived from segment + reason)
  /resolved-at               — instant
  /resolution                — keyword #{:credit-issued :customer-conceded
                                         :written-off :no-action}
  /state                     — facet, ADR-034
                                #{:open :under-review :resolved :escalated}

:credit-hold                 — per-entity overlay; default partner scalar stays
  /partner                   — ref → :partner
  /entity                    — ref → :entity (ADR-031 scope)
  /reason-code               — keyword
                                #{:overdue-threshold :dispute :insurer-decision
                                  :manual :compliance :external-agency}
  /placed-at                 — instant
  /placed-by-uid             — ref → :create/uid
  /released-at               — instant
  /released-by-uid           — ref → :create/uid
  /approver-uid              — ref → :create/uid (manager who signed off)
  /expires-at                — instant (auto-release; nil = manual-only)
  /supporting-doc            — ref → :audit-doc
  /identity                  — tuple [partner entity placed-at]

:dunning-policy              — per-(entity, segment) cadence config
  /code                      — string identity
  /entity                    — ref → :entity
  /applies-to-segment        — keyword (matches :collection-case/segment)
  /levels                    — vec of {:trigger-days N
                                       :template-ref keyword
                                       :late-fee-pct decimal?
                                       :late-fee-fixed decimal?}
  /frequency-cap-window-days — int (Reg-F default 7)
  /frequency-cap-max-events  — int (Reg-F default 7)
  /pause-on-dispute?         — boolean (default true)
  /pause-on-open-promise?    — boolean (default true)
  /active                    — boolean

:dunning-event               — one row per emission attempt
  /case                      — ref → :collection-case
  /invoice                   — ref → :invoice (optional; case-level emissions)
  /level                     — int (ordinal, no enum cap)
  /scheduled-at              — instant
  /sent-at                   — instant (nil = still pending)
  /channel                   — keyword: :email | :letter | :phone | :portal
  /template-ref              — keyword
  /locale                    — string (BCP-47, e.g. "de-DE")
  /audit-doc                 — ref → :audit-doc (the rendered letter PDF/HTML)
  /side-effect-intent        — ref → :side-effect-intent
                                (the outgoing-email queue entry)
  /skipped?                  — boolean (true when frequency-cap or dispute pause)
  /skip-reason               — keyword
                                #{:frequency-cap :open-dispute :open-promise
                                  :unapplied-cash-pending :credit-hold-released}
  /identity                  — tuple [case invoice level scheduled-at]

:dunning-pause               — explicit pause record with reason
  /case                      — ref → :collection-case
  /reason-code               — keyword
                                #{:dispute :ptp-active :holiday-freeze
                                  :key-account-exception :legal-hold}
  /placed-at                 — instant
  /placed-by-uid             — ref → :create/uid
  /expires-at                — instant (auto-resume; nil = manual-only)
  /supporting-doc            — ref → :audit-doc
```

#### State machines (ADR-034 seeds)

`:collection-case/state`:
```
nil → :open
:open → :dunning-l1 → :dunning-l2 → … (unbounded levels)
:open → :promised (PTP accepted; pauses dunning)
:open → :disputed (dispute opened; pauses dunning)
:disputed → :resolved → :open (or :paid)
:promised → :kept → :paid (or :open if broken)
:open → :legal (external escalation; supporting-doc required)
:legal → :written-off (supporting-doc required + :no-self-approval policy)
:open → :paid (final application closed it)
```

`:payment-promise/status`:
```
nil → :open → :kept (matching :payment-application landed)
:open → :broken (sweeper, past promised-by-date with no payment)
:open → :renegotiated → :open (new promise on the same case)
:open → :cancelled (collector or customer rescinds)
```

`:dispute/state`:
```
nil → :open → :under-review → :resolved
:open → :escalated → :resolved (manager-only path)
```

#### Helpers (`modules/collections/src/kontor/collections/...`)

```clojure
;; case.clj
(open-case! conn {:partner :entity :opened-by-uid ... :strategy ...})
(close-case! conn case {:reason :reason-note :supporting-doc})
(assign-collector! conn case {:collector-uid :assigned-by-uid})
(case-of-partner db partner-eid entity-eid {:as-of-valid})

;; promise.clj
(record-promise! conn {:case :invoice :amount :promised-by-date
                       :captured-by-uid :captured-via})
(mark-promise-kept! conn promise {:matching-application})
(mark-promise-broken! conn promise {:reason-note})
(sweep-broken-promises! conn)   ; ADR-041 sweeper

;; dispute.clj
(raise-dispute! conn {:invoice :scope :disputed-amount :reason-code
                      :opened-by-uid :supporting-doc})
(resolve-dispute! conn dispute {:resolution :reason-note :resolved-by-uid})
(open-disputes-for-invoice db invoice-eid {:as-of-valid})

;; credit-hold.clj
(place-hold! conn {:partner :entity :reason-code :placed-by-uid
                   :approver-uid :supporting-doc :expires-at?})
(release-hold! conn hold {:released-by-uid :reason-note})
(credit-status-for db {:partner :entity :as-of-valid})
  ;; Walks entity-specific :credit-hold rows → falls back to
  ;; :partner/credit-status scalar (the ADR-039 default).
(credit-utilization db {:partner :entity :as-of-valid})
  ;; Live posting-based; never a cached snapshot.

;; dunning.clj
(plan-dunning-run conn {:as-of :entity :policy})
  ;; Returns a vec of {:case :invoice :level :template-ref :locale
  ;;                   :skipped? :skip-reason} — pure planning, no side effects.
(emit-dunning-event! conn plan-row)
  ;; Writes the :dunning-event + :audit-doc + :side-effect-intent
  ;; atomically.
(frequency-cap-violations db {:case :within-days :max-events})

;; aging.clj (extends kernel aging.clj)
(open-amount-of-invoice db invoice-eid {:as-of-tx :as-of-valid})
(aging-rows-with-terms db {:method :buckets :as-of-valid
                           :include-partial-paid? true
                           :exclude-disputed? false})

;; writeoff.clj
(write-off-case! conn case {:account :amount :reason-note
                            :approver-uid :supporting-doc})
  ;; Composes kontor.posting/build-transaction Dr bad-debt-expense /
  ;; Cr AR + status-history :written-off + audit-doc.
```

#### Approval-policy rules added

The `:approval-policy/rule` vocabulary (ADR-038) extends with one new rule:

- **`:requires-collections-segment-match`** — applicable at `:collection-case/state :open → :legal` (or any escalation path). Checks that the actor's `:create/uid → :user/permission` includes legal-action authority for the case's segment. Permission vocabulary documented per-l10n.

The existing rules cover everything else:
- `:no-self-approval` on credit-hold release + write-off
- `:requires-supporting-doc` on `:legal` and `:written-off` transitions
- `:requires-non-empty-reason-note` on dispute resolution + write-off

#### Frequency cap (Reg-F)

`plan-dunning-run` consults a count query: `:dunning-event` rows for the case where `:sent-at` is within `(now - frequency-cap-window-days)` and `:sent-at` is set. If count ≥ `:frequency-cap-max-events`, the planned row is marked `:skipped? true` with `:skip-reason :frequency-cap`. The cap is *predicate*, not *checklist*: it's enforced before emission, not as a post-hoc warning. US Reg-F defaults (7 calls in 7 days) seed via `kontor-l10n-us`.

#### Dunning letters as typed `:audit-doc`

Per user decision: no `:dunning-letter` entity. Each `:dunning-event` references:
1. An `:audit-doc {:type :dunning-letter, :content-hash ..., :storage-uri ...}` for the rendered PDF/HTML.
2. A `:side-effect-intent {:type :send-email, :payload {:template :dunning-letter-de-level-1 ...}}` for the outgoing channel work.

The triple `(status-history-row, side-effect-intent, audit-doc)` is the canonical "letter L1 was sent on day D, the PDF is hashed Y, the email-send was attempted at T." This composes with ADR-038 audit-doc + ADR-041 side-effect-intent. l10n modules ship template-resolver implementations:

```clojure
(defprotocol DunningTemplateProvider
  (resolve-template [this {:keys [level locale jurisdiction currency
                                  template-ref segment]}]))
```

`kontor-l10n-de` ships templates with DE Mahnverfahren timers; `kontor-l10n-us` with Reg-F-compliant disclosures. Kernel ships a `StaticTableProvider` fallback that resolves to plain-EDN templates per `(level, locale)`.

#### Per-entity credit-hold overlay

Per user decision: keep ADR-039's `:partner/credit-status` scalar as the default. Add `:credit-hold` as an optional overlay: when a `:credit-hold` row exists for `(partner, entity)` and is `:placed-at` is non-null and `:released-at` is null (or set to a future date), that overrides the scalar.

```clojure
(credit-status-for db {:partner partner-eid :entity entity-eid :as-of-valid d})
  ;; 1. Look for an active :credit-hold for (partner, entity) at as-of-valid.
  ;; 2. If found and not yet released → returns :hold + the hold's reason-code.
  ;; 3. Otherwise → returns :partner/credit-status scalar (ADR-039 default).
```

Single-entity tenants never write `:credit-hold` rows — the scalar suffices. Multi-entity tenants get correctness without breaking ADR-039.

#### Dispute auto-suppresses dunning (market-pain #17)

`plan-dunning-run` consults `(open-disputes-for-invoice db invoice-eid)`. If any open dispute exists for the invoice (or for a line on the invoice with `:scope` pointing at the line), the planned row is `:skipped? true, :skip-reason :open-dispute`. No manual pause needed; no auditor surprise when invoice was dunned mid-dispute. This is structurally exact in kontor because disputes ARE bitemporal entities; SAP FSCM and NetSuite require manual pause flags that drift.

#### Unapplied-cash gates dunning (market-pain #23)

`plan-dunning-run` also consults `(unapplied-cash-balance db partner-eid)`. If a partner has unapplied cash for the entity, the plan rows for that partner are `:skipped? true, :skip-reason :unapplied-cash-pending`. The cash-app team's job: route remittance. The collections team's job: don't dunn while AR doesn't know what the cash is for. The query is exact because payments are postings (ADR-021); no separate unapplied-cash table to drift.

### `:invoice/collections-status` facet

Procurement's `:invoice/match-status` (ADR-042) is purchase-side semantics. Collections adds an independent facet on the same entity per ADR-034's multi-facet allowance:

```
:invoice/collections-status — facet
  #{:current        ; not yet due, or due within grace
    :overdue        ; past grace, no case yet
    :in-collection  ; an open :collection-case references it
    :disputed       ; an open :dispute references it
    :paid           ; closed via :payment-application
    :written-off}   ; bad-debt journal posted
```

Sales / AR invoices flow through this facet; purchase invoices ignore it. The two facets coexist on the same `:invoice` entity without collision because ADR-034 explicitly supports multiple state machines per entity (`status_machine.clj:18-21`).

### Public surface

```clojure
;; modules/collections/src/kontor/collections.clj — re-exports
(open-case! ...)
(close-case! ...)
(assign-collector! ...)
(record-promise! ...)
(raise-dispute! ...)
(resolve-dispute! ...)
(place-hold! ...)
(release-hold! ...)
(plan-dunning-run ...)
(emit-dunning-event! ...)
(sweep-broken-promises! ...)
(write-off-case! ...)
(credit-status-for ...)
(credit-utilization ...)
(unapplied-cash-balance ...)  ; re-exported from kernel
(aging-rows-with-terms ...)
```

### Implementation plan

Five coherent commits:

1. **ADR + research note + roadmap** (this commit). Documentation lands first; substrate readers can review the design before code.
2. **Kernel `:payment-application` primitive** — schema + helpers + `aging.clj` extension + `reconciliation.clj` partial-payment fix + tests. Closes the `reconciliation.clj:38-47` scope-cut. The Stage M / Stage N prerequisite.
3. **Companion schema + state machines + seeds** — `modules/collections/` directory; `:collection-case`, `:payment-promise`, `:dispute`, `:credit-hold`, `:dunning-policy`, `:dunning-event`, `:dunning-pause` schemas; ~30 status-transition seeds; ADR-038 approval-policy hooks.
4. **Case + promise + dispute + credit-hold helpers** — case lifecycle, PTP capture/sweep, dispute lifecycle, credit-hold overlay + `credit-status-for` resolver. Tests for the dispute-suppresses-dunning and PTP-suppresses-dunning predicates.
5. **Dunning policy + letters + frequency cap + enhanced aging** — `plan-dunning-run`, `emit-dunning-event!`, frequency-cap predicate, aging method extension, write-off flow. End-to-end test: open case → first letter → PTP captured → kept-then-broken → escalation → write-off.

After all five land: review-after agents (code-review + market-pain delta against the implementation). Then user-story integration tests per the CLAUDE.md cross-stage validation pattern.

### Alternatives considered

- **Skip partial-payment for v1; let collections work with full-pay-only.** Rejected. Aging is the most-read AR query in any finance workflow; if `open-receivables-by-tx` is structurally lossy or inaccurate, every collections decision is built on sand. Better to pay the kernel cost once and let Stages M / N / O consume the primitive.
- **First-class `:dunning-letter` entity.** Rejected per user decision. `(status-history, side-effect-intent, audit-doc)` covers the audit trail; first-class entity duplicates `:side-effect-intent` state. Promote only if analytics over letter-level distributions outgrows history-row scans.
- **Predictive payment-date ML in v1.** Rejected. Different domain (model + features pipeline); ship the substrate, let sibling `kontor-ml` plug in.
- **SMS / WhatsApp / voice channels in v1.** Rejected. Channel-specific compliance (TCPA, opt-in registries) is a deep rabbit hole. kontor emits `:side-effect-intent` events; channel adapters belong in consumer apps.
- **Debt sale / external-agency placement workflow.** Rejected for v1. Different legal regime (FDCPA validation-notice formality, data-sharing contracts). Sibling `kontor-collections-placement` companion.
- **Inline customer-portal UI.** Rejected per ADR-010. Portal lives in beleg/simmis.
- **Country-specific late-fee rate tables in kernel.** Rejected. Mirrors `TaxProvider` (ADR-005): kernel ships the protocol, l10n modules supply rate tables.

### Implications

- **Stage M revrec** consumes `:payment-application` for cash-receipt-pattern recognition rules.
- **Stage N subscription** consumes `:payment-application` for installment lineage. Recurring invoices set `:invoice/schedule` ref (ADR-032) so collections can detect them and apply subscription grace periods. Schema for `:invoice/schedule` lands here.
- **`kontor-l10n-de`** ships DE Mahnverfahren dunning template + EU late-payment-directive rates (ECB ref + 8%).
- **`kontor-l10n-us`** ships Reg-F frequency-cap defaults + state usury rate tables (or the protocol seam for them).
- **`kontor-l10n-eu`** (if it lands) covers GDPR retention rules + cross-border B2B compliance.

Date: 2026-05-12.

---

## Decisions deferred (open)

The following choices are NOT yet locked. Update this section as we converge.

- **Beancount parser implementation strategy.** instaparse vs hand-rolled vs Java port (Beancount has a Java port: `beancount-java`). Defer to first Beancount integration test.
- **Whether the kernel ships a basic `account-tag` engine (the join between accounts and tax-report boxes) in Phase 1 or defers to Phase 1.5.** Currently: include in Phase 1 because the tax engine needs tags to express VAT-report shapes.
- **Concrete commodity / lot model.** PTA-style (commodity is a string, lot is `(date, cost, label)`) vs richer (commodity is an entity with metadata, lot is a refed entity). Trade-off: PTA-style is lighter, richer is more queryable. Lean PTA-style for Phase 1.
- **Whether `period.locked-at` triggers a datahike branch automatically (per-fiscal-year branches as the persistence pattern), or stays as an attribute.** Fork-per-period is elegant but we want to feel out the ergonomics first.

## l10n-in / l10n-mx deferred slices (status snapshot 2026-05-11)

The architectural validation slices are in (states, identifiers, tax-engine with effective windows, IRN+EWB multi-attestation, CFDI envelope with complemento composition). Remaining slices that follow the established pattern but ship more bulk data / adapter code:

**`kontor-l10n-in`:**
- Full chart of accounts (~100 entries) — modest re-implementation following Indian Schedule III; not architecturally novel.
- Tax-report bucket enum (`l10n_in_gstr_section`-style) on postings for GSTR-1 classification (`b2b`/`b2cl`/`b2cs`/`cdnr`/`cdnur`/`exp`/`hsn`/`nil`/`sez-wp`/`sez-wop`/`exp-wp`/`exp-wop`/`deemed-exp`).
- GSTR-1 JSON emit — aggregation by bucket + HSN summary (Table 12 split B2B/B2C from May 2025).
- GSTR-3B JSON emit — Table 3.1 hard-locked from GSTR-1 since July 2025.
- GSTR-9 / 9C annual returns.
- RCM (reverse-charge) self-invoice path.
- Composition scheme (CMP-08 quarterly + GSTR-4 annual) — separate code path, no ITC.
- Bank-statement ingest: Account Aggregator (ReBIT) `deposit.xsd` parser as primary; HDFC/SBI/ICICI CSV adapters as thin wrappers.

**`kontor-l10n-mx`:**
- Full chart of accounts (~140 entries) + SAT `código agrupador` mapping (Anexo 24) — 1,080 agrupador groups, attached as `:state-code`-style external codes per ADR-019.
- Tax engine: IVA (16/8/0/exempt + border-zone), IEPS (Tasa rates + per-unit Cuotas; refresh annually per DOF), ISR retención (10% / 1.25% RESICO), IVA retención (4% fletes / 10.6667% honorarios / 16%).
- Pagos 2.0 complemento builder (currently only the placeholder fragment is wired into the composition test — a real builder constructs the `<pago20:Pagos>` tree from a payment entity).
- Carta Porte 3.1 complemento builder (the largest complemento by surface area; mandatory for goods transport).
- Nómina 1.2 Rev. E complemento.
- ComercioExterior 2.0 complemento (exports).
- PAC provider abstraction (`PacProvider` protocol) with Facturama + Solución Factible adapters (no bundled credentials).
- DIOT TXT generator targeting the post-Aug-2025 SAT platform format.
- Contabilidad Electrónica (Anexo 24) — monthly Balanza + Catálogo XML.
- Bank-statement ingest: Banorte MT940 (reuse kernel parser) → Santander XML → Citibanamex CSV → BBVA CSV → SPEI CEP per-transfer matcher.

Both modules currently include the architecturally-validating slices only. Each deferred slice is independent and ships incrementally without revisiting the ADRs.

---

## ADR-048 — Normalize valid-time to `:tx/valid-from` (drop `:posting/valid-from`)

**Decision.** Valid-time for postings lives on the writing tx via
`kontor.bitemporal`'s `:tx/valid-from` (cardinality-one, indexed,
`:db.type/instant`). The per-posting `:posting/valid-from` attribute
is removed. All postings written by one tx share that tx's
`:tx/valid-from`. Kernel builders (`build-transaction`, the inventory
helpers, the beancount importer) stamp it from
`:transaction/effective-date` automatically; callers that need
late-arriving valid-time (backdated corrections) pass `:vt-from` /
`:vt-to` opts to the transactor, which override the default.

**Why.** Before this change the kernel had two valid-time
mechanisms: `:posting/valid-from` (per-posting, indexed) for hot-path
balance/ledger/report queries, and `:tx/valid-from` (tx-meta) for
status transitions on mutable entities. The split was a perf-driven
denorm masquerading as a semantic distinction. Empirically:
- No call site mixes valid-froms within one tx — every kernel
  builder fills the per-posting attr from
  `:transaction/effective-date`, identical for all postings.
- `:tx/valid-from` is `:db/index true`, so AVET range scans on it are
  comparable to AVET on the old per-posting attr; the only
  performance gap is the extra `[?p :posting/transaction _ ?tx]`
  join, a constant-factor difference, not categorical.
- One mechanism is simpler than two. Documentation telling readers
  which to reach for is a smell.

**Shape after.**
- Schema: `:posting/valid-from` removed. `:tx/valid-from` + `:tx/valid-to`
  bundled in the kernel schema (`bitemporal-tx-attrs`).
- Writers: `posting/build-transaction` wraps its return with
  `(kbt/with-vt tx-data effective-date kbt/forever)`. `with-vt` is
  idempotent — callers wrapping again with explicit `:vt-from` /
  `:vt-to` override the default. Beancount importer + inventory
  helpers (`plan-stock-move`) wrap similarly.
- Readers: `balance.clj` / `ledger.clj` / `report.clj` /
  `period.clj` / DATEV exporter resolve a posting's valid-from via
  the `posting-vf` rule (`kontor.bitemporal/query-rules`) or the
  `kbt/posting-vf` lookup fn. The rule pattern is
  `[?p :posting/transaction _ ?tx][?tx :db/txInstant ?ti]
  [(get-else $ ?tx :tx/valid-from ?ti) ?vf]` — falls back to
  `:db/txInstant` when vf is absent (matches the resolver's default).
- Period-locking: `period/find-violations` reads `:tx/valid-from`
  off the inbound tx-data's `"datomic.tx"` map via
  `kbt/tx-data-vf`. One vf per tx; applies uniformly to every
  proposed posting in that tx. Tx-data without `:tx/valid-from`
  cannot be period-checked — wrap writes with `kbt/with-vt` (the
  kernel builders do).

**Tradeoffs.**
- *Lost*: ability to write postings within one tx with different
  valid-froms. No call site exercised this, so no semantic
  regression. If a future need arises (deferred-revenue split across
  recognition months in one tx, say), it's expressible as N
  transactions — bookkeeping-correct and audit-cleaner.
- *Gained*: single source of truth, fewer attributes on every
  posting row, no risk of `:posting/valid-from` and
  `:transaction/effective-date` drifting on careless writes, and
  late-arriving corrections (`:vt-from` / `:vt-to`) compose with the
  same mechanism as status-transition corrections elsewhere.

**Naming.** The "valid-from" name continues to mean "from when this
fact holds." The resolver in `kontor.bitemporal` handles
`:tx/valid-to` polygons for entities that need supersession (status
flips on invoices, disputes, etc.). Postings don't supersede — they
reverse-and-repost (ADR-008 revised) — so `:tx/valid-to` on a
posting's tx is always `forever`.

Date: 2026-05-13.

---

## ADR-049 — Legal hold: write-time invariant blocking purge of held entities

**Decision.** Stage M ships `:legal-hold/*` as a kernel entity that the
`kontor.sealing` middleware consults at write-time. Any `:db/purge`
against an entity in an open hold's scope is refused with a structured
ex-info; the purge cannot succeed even by mistake. This makes "no
purge while held" a **structural invariant**, not a policy enforced
by a separate background job.

A legal hold has two scope shapes that combine:
- `:legal-hold/scope-eids` — explicit `:db.cardinality/many` ref set.
  Fast path: O(1) check at the middleware. Best for narrow holds
  (e.g. "preserve invoices #42, #51, #67 for Acme v. Doe").
- `:legal-hold/scope-query` — EDN-encoded datalog expression
  evaluated against the speculative `txdb` at purge time.
  Expressive path: catches new entities that match a matter (e.g.
  "all transactions with `:partner` = Doe between 2024-Q1 and
  2025-Q2"). Optional `:legal-hold/scope-query-as-of` pins the
  query's valid-time anchor; defaults to now.

Both checks run; either firing blocks the destructive write.

**Destructive-write coverage.** The middleware blocks the full
datahike data-destruction surface, not just `[:db/purge eid]`:
whole-entity forms (`:db/purge`, `:db.purge/entity`, `:db/retractEntity`,
`:db.fn/retractEntity`) and attribute-level forms
(`:db.purge/attribute`, `:db/retract`). This
matters because the Zubulake / Pension Committee fact pattern is
exactly the preservation of *non-posted* business records — a draft
invoice, a partner row, a supporting-doc — which `:db/retractEntity`
would silently discard. (Entity-map nil-retracts are not yet
covered; the sealing middleware catches the posted-entity subset and
a single-datom retract is a far narrower exposure — a follow-up can
extend coverage if a real case surfaces.)

**Sweeper.** The kernel ships `refresh-scope-eids!` as a sweeper
*helper* — it re-evaluates a hold's `:scope-query` and additively
extends `:scope-eids` (monotonic; never retracts without counsel
re-attestation). The kernel does NOT ship a scheduler; the consumer
schedules the helper on its own cadence (a `bb hold-sweep` cron, say)
per ADR-010. The hot path doesn't depend on the sweeper running:
because the middleware ALSO evaluates `:scope-query` live against
the speculative `txdb` on every destructive write, sweep-lag never
produces a missed hold — it only produces a slightly slower purge
between sweeps.

**State machine** (ADR-034 facet `:legal-hold/state`):

```
nil               → :placed
:placed           → :pending-review     ; "do we still need this?"
:pending-review   → :placed             ; reaffirmed
:pending-review   → :released
:placed           → :released
:placed           → :expired            ; auto-fired by sweep-time-based!
:expired          → :released           ; admin reaffirms the auto-expiry
:released         → (terminal)
```

**Approval policies** (ADR-038): both placement and release require a
`:supporting-doc` (the preservation order PDF, the release order) and
a non-empty `:reason-note`. Release additionally requires `:no-self-
approval` (the person placing the hold cannot release it alone).

**Why.** Research note 23 (market-pain) catalogues 11 documented
spoliation-sanction cases with multi-million-dollar consequences;
5 of 11 are structurally preventable by a write-time hold-blocks-
purge invariant. The remaining 6 are out of scope (chat / ephemeral
data not in the accounting kernel). The most-quoted post-mortem
finding across these cases is "the cron beat the hold" — a backup
or sweep job ran during the gap between the hold being declared and
being recorded in the system. A write-time middleware closes that
gap by construction; no time window exists in which a purge can fire
without first consulting the hold table.

Research note 22 (reference study) confirmed that NO major open-
source ERP (Apache OFBiz, ERPNext, KillBill, Compiere/iDempiere,
Tryton) ships a legal-hold primitive. The closest references are
JCR 2.0's `RetentionManager` (JSR-283 §20; verbs-standard, data-
application-defined — kontor's hybrid scope is a strict superset)
and Datomic's `:db.excise/*` vocabulary. We adopt the JCR verb
shape and the Datomic vocabulary.

**Shape after.**

- New schema (one ~13-attr entity in `src/kontor/schema.clj` near
  the existing `:partner-merge` slot).
- New namespace `src/kontor/legal_hold.clj` (~150 LOC of helpers:
  `place!`, `release!`, `find-hold-violating-purges`,
  `assert-no-hold-violating-purges!`, `entity-held?`,
  `expand-scope-query`).
- New status-transition seeds + approval-policy seeds, installed
  via `kontor.schema/install!`.
- Single-line addition to `src/kontor/validation.clj:177-183`
  (`validate-and-apply`) calling
  `legal-hold/assert-no-hold-violating-destructive-writes!` BEFORE
  `sealing/assert-no-silent-retracts!` so the more-specific error
  wins on a destructive-write-of-held-posted-entity.
- `test/kontor/legal_hold_test.clj` (eid-set hold, scope-query hold,
  release-then-purge, ADR-038 enforcement on placement + release,
  destructive-form coverage (`:db/retractEntity`,
  `:db.purge/attribute`), multi-hold overlap, `:pending-review`
  release SoD, bitemporal scope-query resolution).

**No `:legal-hold/placed-at` denorm.** The placement instant IS the
`:tx/valid-from` of the placing tx and the
`:status-history/changed-at` of the nil → :placed row — resolve via
`(kbt/value-at db hold-eid :legal-hold/state at)` or the
status-history timeline. This follows the ADR-048 valid-time
normalization and the Stage-L denorm-removal pattern; carrying a
`:placed-at` scalar would be a third source of truth for the same
instant.

**Tradeoffs.**

- *Gained*: a write-time invariant the auditor sees in the chain.
  An eDiscovery-defensibility claim ("no data was destroyed under
  hold") that's mechanically true. Bitemporal "as of the subpoena
  date" hold-scope queries via `kbt/value-at` on
  `:legal-hold/scope-query`.

- *Lost*: nothing structural. The implementation cost is one
  middleware extension + one namespace; no other ADR retreats.

- *Performance*: write-time evaluation of `:scope-query` adds one
  datalog query per active hold per `:db/purge`. In practice purges
  are rare (annual GDPR-erasure cycles, not hot path), so the cost
  is negligible. The hot path (writes against held entities that
  aren't purges) is unchanged — only `:db/purge` triggers the
  check.

**Naming.** "Legal hold" is the industry term (Sedona Conference,
FRCP 37(e)). We use it unchanged. `:legal-hold/code` is a string
unique-identity following the existing pattern (`:audit-doc/code`,
`:journal/code`).

Date: 2026-05-13.

---

## ADR-050 — Retention policy + sweeper: effective-dated expiry that respects holds

**Decision.** Stage M ships `:retention-policy/*` as a kernel entity
(shape only — **no default policy data**) plus a `kontor.retention`
namespace with a sweeper. A retention policy says "entities of type
T, in jurisdiction J, expire `duration-years` after their
`triggered-by` anchor date, via `expiry-action`." The sweeper walks
candidate entities, computes each one's retention deadline, and —
for entities past their deadline AND not under an active legal hold
— produces an expiry work-item.

Per-jurisdiction policy *data* lives in l10n companion modules
(`kontor-l10n-de` ships HGB §257 / GoBD / AO §147; `kontor-l10n-us`
ships SOX §103 / IRC §6001; etc.), exactly mirroring ADR-026's
effective-dated tax-rate pattern. The kernel is jurisdiction-blind;
it ships the mechanism, never the numbers.

**Schema** (one ~13-attr entity in `src/kontor/schema.clj`):
`:retention-policy/code` (unique identity), `:applies-to`
(`:db.cardinality/many` keyword — entity-type discriminators),
`:jurisdiction` (ref to `:country`, nil = global), `:duration-years`,
`:triggered-by` (the *clock-anchor* attribute keyword — e.g.
`:transaction/effective-date`, `:audit-doc/uploaded-at`,
`:status-history/changed-at`), `:expiry-action`
(`:purge` | `:anonymize` | `:archive-to-cold-storage`),
`:anonymize-fields` (`:db.cardinality/many` keyword — the PII attrs
for the `:anonymize` action), `:legal-basis` (free-text statute
reference), `:effective-from`/`:effective-until` (ADR-026 pattern),
`:state` (status-machine facet), `:supporting-doc`, and a
`:retention-policy/identity` tuple `[code effective-from]`.

**Status machine** (ADR-034 facet `:retention-policy/state`):
`nil → :draft → :active → :superseded`. `:draft` lets a tenant
stage a policy without it firing; `:superseded` is terminal — to
"update" a policy you ship a new row with a later `:effective-from`
and supersede the old. Approval policy (ADR-038): `:draft → :active`
requires `:supporting-doc` + non-empty `:reason-note` — the auditor
needs to know *why* a retention rule changed.

**The hold-blocks-expiry invariant — composition with ADR-049.**
This is the headline. `kontor.retention/apply-expiry!` does not call
`d/transact` directly; it routes the purge/anonymize tx-data through
`[:db.fn/call kontor.validation/validate-and-apply …]`. That means
the ADR-049 hold-middleware (`assert-no-hold-violating-destructive-
writes!`) fires on every expiry action — `:purge` is a `:db/purge`,
`:anonymize` is N `:db.purge/attribute` ops, both of which the
hold-middleware now recognizes (ADR-049 review fix P0-1). The
sweeper *structurally cannot* expire data under hold; even a buggy
consumer that called `apply-expiry!` on a held entity would get the
`:legal-hold/purge-blocked` exception. `eligible?` *also* checks
`legal-hold/entity-held?` — but that check is an optimization and a
visibility feature (the sweeper reports "this entity would expire
today but is on hold"), not the load-bearing guarantee. The
guarantee is the middleware.

**Why kernel ships the sweeper.** The sweeper *must* respect legal
holds. If the sweeper lived in consumer-land, a consumer could write
their own expiry loop that bypasses the hold check. By shipping
`sweep!` + `apply-expiry!` in the kernel — and routing
`apply-expiry!` through `validate-and-apply` — the hold check is
unavoidable. The kernel does NOT ship a scheduler (per ADR-010);
the consumer schedules `sweep-and-apply!` on its own cadence (a
`bb retention-sweep` cron, daily/weekly).

**Why.** Research note 23 (market-pain) documents the EDPB's 2025
Coordinated Enforcement Action explicitly targeting right-to-erasure
across 32 DPAs, and the Finnish DPA's €856k Verkkokauppa fine —
levied not for keeping data too long but for *failing to define a
retention policy at all*. Research note 22 (reference study)
confirmed the OSS prior-art vacuum: no major open-source ERP ships a
retention primitive; the closest analogues are Odoo's `data_recycle`
(domain-filter + time-delta, no hold-awareness, anonymize-only
`privacy_lookup` as a cautionary tale) and SAP ILM's residence-vs-
retention-period model. The SOX-7y-vs-GDPR-erasure tension has an
established industry compromise — "anonymize but keep" — which the
`:anonymize` action + `:anonymize-fields` directly model.

**v1 scope boundaries.**
- `:purge` and `:anonymize` ship fully. `:anonymize` is implemented
  as N `:db.purge/attribute` ops over `:anonymize-fields` — the row
  survives, the PII fields are gone, and (because purgeAttribute
  goes through the hold-middleware) anonymize-of-held-entity is
  blocked just like purge.
- `:archive-to-cold-storage` is deferred — it needs an external
  archive store and a side-effect-intent round-trip. `apply-expiry!`
  throws an explicit "not implemented in v1" for it.
- Clock anchors are *direct-attribute* only in v1: `:triggered-by`
  must be an attribute on the entity itself. Cross-entity anchors
  (e.g. an `:invoice`'s deadline keyed off the underlying
  `:transaction`'s `:effective-date`) are deferred — the entity is
  simply skipped if it lacks the anchor attribute. A follow-up can
  add an anchor-resolver registry.
- The kernel ships ZERO default policies. A kontor install with no
  l10n module has retention disabled by construction. This is the
  reference-study agent's explicit recommendation: a wrong default
  retention is worse than none.

**Shape after.**
- New schema (~13 attrs) in `src/kontor/schema.clj`.
- New namespace `src/kontor/retention.clj` (~280 LOC):
  `define-policy!` / `activate-policy!` / `supersede-policy!`
  (transactors); `policy-for` / `retention-deadline` / `eligible?` /
  `due-for-expiry` (queries); `sweep!` (dry-run-friendly planner) /
  `apply-expiry!` / `sweep-and-apply!` (executors).
- `install-seeds!` (status-transition + approval-policy seeds),
  called from `kontor.core/install-schema!`, idempotent-guarded.
- `test/kontor/retention_test.clj`: aged-past-deadline eligibility,
  effective-dated policy resolution, `:anonymize` field-purge,
  hold-blocks-expiry (place hold → sweep → blocked → release →
  sweep → applied), dry-run, jurisdiction-specific-over-global.

**Tradeoffs.**
- *Gained*: a defensible retention story — every expiry is policy-
  driven, effective-dated, hold-aware, and audit-logged. The
  "failure to define retention" fine pattern is structurally
  addressed (a tenant either has policies or has retention disabled;
  there is no silent middle ground). "Anonymize but keep" is a
  first-class action, not a workaround.
- *Lost*: nothing structural. `:archive-to-cold-storage` and
  cross-entity clock anchors are deferred, not designed-out — both
  have clean extension points.
- *Performance*: the sweeper is O(candidate-entities) per policy per
  run. It's a batch job on a consumer-chosen cadence, never on the
  write path. `eligible?` calls `legal-hold/entities-held?` (the
  batched API added in the ADR-049 review fix) so the hold check is
  one query for the whole batch, not one per entity.

**Naming.** "Retention policy" / "retention schedule" is the
industry term (records-management, SAP ILM, NetSuite). `:triggered-
by` over `:clock-anchor` because "triggered by" is how records-
managers describe the event that starts the retention clock
("retention triggered by case closure").

Date: 2026-05-13.

---

## ADR-051 — Privilege classification on `:audit-doc`

**Decision.** Stage M adds one attribute, `:audit-doc/privilege`, a
keyword classifying a supporting document's legal-privilege status.
Changes to it go through the status machine (ADR-034) so every
re-classification carries who/why/supporting-doc; a privilege
*waiver* (the consequential change — it exposes a previously-
protected document) is governed by ADR-038 approval policy
(`:no-self-approval` + `:requires-supporting-doc` +
`:requires-non-empty-reason-note`).

**Starter vocabulary** (open-set, consumers extend — matching
ADR-038's open-vocabulary discipline):
`:none` (default; nil is treated as `:none`), `:attorney-client`,
`:work-product`, `:joint-defense`, `:settlement-communication`,
`:trade-secret`, `:pii-sensitive`. A consumer companion can add its
own (`:hipaa-phi`, `:ferpa-edu`, …) by transacting additional
`:status-transition` + `:approval-policy` rows.

**The kernel tags; the consumer enforces.** This is the load-bearing
boundary. There is NO kernel-level ACL, RBAC, or user-role system —
ADR-010 ("no UI, no auth") includes "no access control." The kernel
ships `:audit-doc/privilege` as a *label* and two pure helpers
(`visible-to?`, `filter-by-privilege`) that compute "would a viewer
holding privilege-set P see this document"; the *enforcement* — who
holds which privilege, how the URI is gated — lives entirely in the
consumer's auth layer. The helpers take a `viewer-privilege` set,
never a `requesting-uid`: the kernel does not know about users.
(This is the explicit push-back on research note 17's
`uri-for(doc, requesting-uid)` sketch — that signature would couple
the kernel to a user concept it must not have.)

**Privilege is a status-machine facet over a complete graph.** A
classification can change to any other classification — a document
determined `:work-product` can be re-determined `:attorney-client`,
waived to `:none`, etc. So the `:status-transition` seeds are the
*complete graph* over the starter vocabulary (every ordered pair,
generated) rather than a constrained lifecycle. The status machine
still earns its place: it gives every change a `:status-history`
row with `:reason` / `:reason-note` / `:supporting-doc` /
`:changed-by-uid`, and lets the approval policy fire on the waiver
edges. `reclassify-privilege!` is the transactor; it normalizes a
nil current value to `:none` for the `:from`.

**Approval policy — the waiver rule.** Every `<privileged> → :none`
edge carries `:no-self-approval` + `:requires-supporting-doc` +
`:requires-non-empty-reason-note`. Waiving privilege is one of the
most consequential acts in the kernel (it can expose attorney-client
material); the person who classified a document cannot waive it
alone, and the waiver determination must be documented. Upgrades
(`:none → <privileged>`) and re-classifications between two
privileged values are *not* approval-gated — over-classification is
the safe direction.

**Bitemporal composition (free).** Privilege tags change, and "what
was the privilege classification at the filing date" is a real
discovery question. It is answered with no new mechanism:
`(kbt/value-at db doc-eid :audit-doc/privilege filing-date)`. A
document upgraded from `:none` to `:attorney-client` after counsel
review is correctly discoverable in both states along the tx-time
and valid-time axes (ADR-048).

**Why.** Research note 22 (reference study) recommended a flat
keyword vocabulary over the W3C DPV tree — DPV models *what data
is*, not *who may access it*; it's the wrong shape for a privilege
*classification*. Research note 23 (market-pain) documented
inadvertent-production and failure-to-log-waiver as the top
privilege failure modes (FRE 502(b) "reasonable steps"; the
common-interest M&A trap) — both are addressed by making every
privilege change an audited, governed status transition rather than
a silent attribute write. Research note 24 (internal gap) confirmed
the substrate already carries the weight: `:audit-doc` (ADR-038),
the status machine (ADR-034), approval policy (ADR-038), and the
bitemporal resolver (ADR-048) compose with no new mechanism.

**Shape after.**
- One new attribute (`:audit-doc/privilege`) in `src/kontor/schema.clj`.
- `kontor.audit-doc` gains: the `privilege-vocab` def,
  `status-transition-seeds` + `approval-policy-seeds` (generated
  complete graph + waiver rules), `install-seeds!` (called from
  `kontor.core/install-schema!`, idempotent-guarded),
  `reclassify-privilege!` (transactor), `visible-to?` +
  `filter-by-privilege` (pure helpers).
- `test/kontor/audit_doc_privilege_test.clj`: classify / re-classify
  / waiver-requires-SoD / waiver-requires-supporting-doc / upgrade-
  is-ungated / visible-to? rules / filter-by-privilege / bitemporal
  value-at on the privilege facet.

**What this does NOT do** (deferred, by design):
- No DSAR-bundle privilege filtering yet — that is ADR-052's job;
  `filter-by-privilege` is the helper ADR-052 will call.
- No counsel-matter / counsel-engagement entity — research note 24
  §7 explicitly defers `:counsel-matter` and `:legal-invoice`; the
  privilege tag stands alone.
- No automatic privilege inheritance (a privileged document
  referenced by N status-history rows does not privilege those
  rows). The kernel returns everything; the consumer applies
  `filter-by-privilege` at render time.

Date: 2026-05-14.

---

## ADR-052 — Data-subject-access requests + the bitemporal `collect` walk

**Decision.** Stage M's fourth and final primitive: a `:dsar-request`
kernel entity tracking a GDPR/CCPA/LGPD-style data-subject request
through its lifecycle, plus `kontor.dsar/collect` — the bitemporal
walk that answers *"everything we held about this subject as of the
request date."* Research note 23 established this is genuinely hard
for every other system because subject data is pervasive and lives
across silos; in kontor it is one query because the substrate is one
bitemporal datalog DB.

**The companion-registered partner-attribute registry.** The hard
part of `collect` is that partner references are pervasive AND many
live in companion modules the kernel does not import. The answer is
a registry: `kontor.dsar/*partner-attrs*` is an atom seeded with the
kernel's own partner-referencing attributes; each companion calls
`(kontor.dsar/register-partner-attr! :collection-case/partner)` etc.
at load time. `collect` iterates the registry. Same dispatch pattern
as the schema-loader registry — the kernel ships the mechanism, the
companions extend it. The kernel seeds:
`:transaction/partner`, `:posting/partner`, `:invoice/buyer`,
`:invoice/seller`, `:partner-bank-account/partner`,
`:partner-tax-id/partner`, `:partner-tag/partner`,
`:partner-merge/duplicate-of`, `:partner-merge/superseded`.

**`collect` returns a companion-agnostic structure.** Not a
fixed-key map hardcoding `:transactions` / `:collection-cases` / …
(the kernel cannot know companion entity types). Instead:
`{:partner <pulled> :merged-from [<eids>] :references {<attr>
[<pulled-entities>]} :indirect-references {<tx-attr>
[<pulled-entities>]} :legal-holds [<hold-eids>] :on-legal-hold?
<bool>}`. Both reference maps are keyed by the registered
attribute; the consumer interprets.

**Two axes — direct and indirect** (added in the research-note-32
P1 review fix). `:references` is the *direct* walk: entities
referencing the subject `:partner` via a registered `partner-attrs`
attribute. `:indirect-references` is the *indirect* walk: from the
subject's transactions (anything with `:transaction/partner` =
subject), every entity referencing those transactions via a
registered `tx-attrs` attribute. A great deal of subject data — a
`:payment-application` pointing at the subject's payment tx, a
`:status-history/origin-transaction` row — references a
`:transaction`, not the `:partner` directly; the direct walk alone
ships an incomplete access response. The `tx-attrs-registry` mirrors
the partner-attrs registry: the kernel seeds it
(`:status-history/origin-transaction`, `:transaction/reverses`),
companions register their own (`:payment-application/payment`, …)
via `register-tx-attr!`.

**Privilege filtering is consumer-side.** `collect` returns the raw
reference walk; it does not itself filter by privilege. When the
consumer assembles the fulfillment bundle it runs any included
`:audit-doc`s through `kontor.audit-doc/filter-by-privilege`
(ADR-051) — privileged documents are NOT auto-included in a
subject's package; they need counsel review (the
`:awaiting-legal-review` state). This keeps `collect` a pure,
companion-agnostic walk and puts the privilege policy where bundle
assembly already lives — in the consumer.

**Bitemporal axis.** `collect` takes `:as-of-tx` and snapshots the
whole DB via `d/as-of` before walking. This is exactly the
legally-relevant question — *"produce everything we KNEW about this
subject as of date D"* — and `d/as-of` answers it precisely. A
per-entity `:as-of-valid` filter is a documented follow-up; the
tx-time snapshot is the load-bearing axis and ships in v1.

**Status machine** (ADR-034 facet `:dsar-request/state`):
`nil → :received`; `:received → {:verifying-identity, :withdrawn,
:extended}`; `:verifying-identity → {:in-progress, :denied}`;
`:extended → :in-progress`; `:in-progress → {:awaiting-legal-review,
:fulfilled, :denied}`; `:awaiting-legal-review → {:fulfilled,
:denied}`.

**Approval policy** (ADR-038). Fulfillment and denial are the
governed edges:
- `* → :fulfilled` (`:in-progress` or `:awaiting-legal-review`):
  `:no-self-approval` (the intake person cannot also be the
  fulfiller) + `:requires-supporting-doc` (the produced bundle).
- `* → :denied`: `:requires-supporting-doc` (the written denial
  rationale) + `:requires-non-empty-reason-note`.

**Composition with legal-hold (ADR-049) for erasure requests.** Held
data must still appear in a DSAR *access* response — the subject's
right of access is not waived by a hold. But held data cannot be
*deleted* by an *erasure* request. The workflow: `collect` returns
the held data alongside everything else (with `:on-legal-hold?` and
the covering `:legal-holds`); the consumer's erasure-fulfillment
bundles the access portion in full, purges/anonymizes only the
unheld portion (the ADR-050 sweeper's `apply-expiry!` would itself
refuse held data — the invariant is structural), and emits a
denial-rationale `:audit-doc` for the held portion. The kernel ships
the predicates; the partial-fulfillment workflow is consumer
territory, documented here.

**Why.** Research note 23: the EDPB's 2025 Coordinated Enforcement
Action targets right-of-access and right-to-erasure across 32 DPAs;
DSAR fulfillment cost runs ~$1,500/manual request (Gartner/DataGrail)
because the data is scattered. kontor's one-DB substrate collapses
the scatter. Research note 24 confirmed every needed primitive is
already shipped — `:partner` + the merge chain (ADR-039), the
bitemporal resolver (ADR-048), `legal-hold/entity-held?` (ADR-049),
`audit-doc/filter-by-privilege` (ADR-051), the status machine +
approval policy (ADR-034/038) — `collect` is a *composition*, not a
new mechanism.

**Shape after.**
- New schema (`:dsar-request/*`, ~15 attrs) in `src/kontor/schema.clj`.
- New namespace `src/kontor/dsar.clj`: `*partner-attrs*`
  registry + `register-partner-attr!` + `partner-attrs`,
  `install-seeds!`, `file-request!` (transactor; computes
  `:deadline-at`), `advance-state!` (generic transition transactor;
  merges `:fulfilled-at` / `:fulfilled-package` / `:denied-reason` /
  `:identity-verified-at` per the target state), `collect` (the
  bitemporal walk), `by-external-id`.
- `install-seeds!` (status-transition + approval-policy seeds),
  wired into `kontor.core/install-schema!`, idempotent-guarded.
- `test/kontor/dsar_test.clj`: file → collect returns referencing
  data; bitemporal collect (older `:as-of-tx` excludes later data);
  privilege side-band; partner-merge inclusion; legal-hold +
  erasure (held data in the bundle, not purged); fulfillment SoD;
  denial governance.

**What this does NOT do** (deferred):
- No auto-overdue-flagging facet — the research sketch's
  `:overdue-warning` / `:overdue` auto-after-millis transitions are
  deferred; v1 ships `:deadline-at` for a consumer cron to check.
- No per-entity `:as-of-valid` filter in `collect` — `:as-of-tx`
  snapshot is the v1 bitemporal axis.
- No privilege side-band in `collect` — privilege filtering happens
  consumer-side during bundle assembly via
  `audit-doc/filter-by-privilege` (ADR-051).
- No fulfillment-bundle assembly — `collect` returns the data; the
  consumer builds the PDF/JSON/portability artifact and attaches it
  as `:fulfilled-package`.
- No recursive merge-chain walk — `collect` walks one level of
  `:partner-merge` (canonical → its merged-from duplicates); deeper
  chains are a follow-up.

Date: 2026-05-14.

---

## ADR-053 — `kontor-asset`: the `:asset` register + lifecycle

**Decision.** Stage L′ ships `kontor-asset` as a companion module
(`modules/asset/`, cohabiting per ADR-002 like `kontor-invoice` /
`kontor-collections`). ADR-053 lays the foundation: the `:asset`
register, the `:asset-class` category, the `:asset-event` immutable
mid-life-event entity, and the `:asset/status` lifecycle status
machine — **GL-free**. The depreciation books, the depreciation
runner, and all GL postings (capitalisation, depreciation runs,
disposal/impairment/revaluation entries) are ADR-054's job;
ADR-053 is the data model + lifecycle + governance.

This split keeps each ADR independently shippable and testable:
ADR-053 = "you can model assets and their lifecycle as audited
state"; ADR-054 = "kontor builds the GL for you and runs
depreciation."

**Entities** (companion-owned `:asset/*` family — research note 31
§4):
- **`:asset`** — one physical capitalised asset. `:asset/code`
  (unique identity), `:name`, `:class` (ref `:asset-class`),
  `:acquisition-cost` + `:acquisition-commodity`,
  `:acquisition-date` (the valid-time that drives effective-dated
  rule resolution in ADR-055), `:in-service-date` (when the
  depreciation clock starts — may differ from acquisition: DE
  "Anschaffung" vs "betriebsbereit"; US/CA "placed in service"),
  `:salvage-value`, the three GL account refs
  (`:asset-account` / `:accumulated-account` / `:expense-account` —
  carried for ADR-054's posting helpers), `:cost-center` (ref
  `:analytic-account`), `:entity` (ADR-031 scope, optional),
  **`:asset/parent`** (componentisation — a component is just an
  `:asset` whose parent points at the whole; the lean reading, no
  separate `:asset-component` entity), `:origin-transaction` +
  `:origin-document`, `:status` (the lifecycle facet),
  `:serial-number` / `:location` / `:note`.
- **`:asset-class`** — the category. `:code` (unique identity),
  `:name`, `:parent` (hierarchy), `:default-useful-life-months`,
  `:note`. The companion ships the *entity*; l10n modules ship the
  *rows* (a DE class maps to an AfA-Tabelle row, a US class to a
  MACRS recovery class).
- **`:asset-event`** — an immutable mid-life-event fact (kontor's
  posting/layer immutability pattern). `:asset`, `:kind`
  (`:disposal` | `:impairment` | `:revaluation` |
  `:partial-disposal` | `:useful-life-revision` | `:addition` |
  `:transfer`), `:date` (valid-time of the event), `:amount` +
  `:commodity`, `:new-useful-life-months` (for
  `:useful-life-revision`), `:transaction` (the GL entry the event
  posts — populated by ADR-054's helpers, a ref the caller
  supplies in ADR-053), `:justification` (ref `:audit-doc` —
  required by approval policy on `:impairment` / `:disposal` /
  `:revaluation`), `:note`.

**Componentisation = `:asset/parent`.** IAS 16 components (a
building's roof depreciates separately from its frame) are modeled
as ordinary `:asset`s whose `:asset/parent` points at the whole.
Independent depreciation books (ADR-054), shared identity for
disposal. No separate `:asset-component` entity — that would
duplicate most of `:asset`'s shape and violate the anti-accretion
contract.

**The `:asset/status` lifecycle** (ADR-034 facet):
```
nil → :planned → :in-service        (acquire, then place-in-service)
nil → :in-service                   (acquire-in-service — the common case)
:in-service → :in-service           (impair / revalue — recurring events)
:in-service → :fully-depreciated    (the depreciation runner's last occurrence — ADR-054)
:in-service → :disposed             (dispose / write-off / sell)
:fully-depreciated → :disposed      (scrap a written-down asset)
:in-service → :transferred          (transfer to another :entity)
```
`:planned` covers the DE §7g Investitionsabzugsbetrag case (a
deduction *before* the asset exists) and the order-to-availability
gap. `:fully-depreciated` is a long-lived state (a written-down
machine still in use). `:disposed` and `:transferred` are terminal.

**Governance** (ADR-038 approval policy). The consequential events
are gated: `:in-service → :disposed` requires `:requires-supporting-
doc` (the disposal authorisation) + `:no-self-approval`; the
`:impairment` and `:revaluation` events require `:requires-
supporting-doc` (the impairment-test memo / valuation report) +
`:requires-non-empty-reason-note`. This is the same `:audit-doc` +
`:approval-policy` backbone every other Stage-J-onward entity uses.

**Transactors** (`modules/asset/src/kontor/asset/asset.clj`,
GL-free): `acquire!` / `acquire-in-service!` (create the `:asset`
+ the nil → :planned / :in-service status row),
`place-in-service!` (:planned → :in-service). The event
transactors split by whether they change the lifecycle:
- `dispose!` / `transfer!` change `:asset/status` — they write the
  immutable `:asset-event` AND drive the status machine, so the
  `:in-service → :disposed` approval policy fires (ADR-038).
- `impair!` / `revalue!` / `revise-useful-life!` / `record-addition!`
  keep the asset `:in-service` — they write the `:asset-event` with
  inline required-arg guards (`:supporting-doc` for impair/revalue/
  disposal, `:reason-note` for impair/revalue), matching the
  explicit-guard pattern `legal-hold/place!` and `retention/
  define-policy!` use for create-transactors. Per-event-kind
  configurable approval policy is a documented follow-up.

The `:asset-event/transaction` ref is supplied by the caller in
ADR-053 (the caller posts the GL entry); ADR-054's
`kontor.asset.posting` helpers will build those entries.

**Why a companion, not kernel.** Per ADR-010 + ADR-037: the kernel
is the double-entry substrate; fixed-asset accounting is a workload
that *composes on* the substrate. `kontor-asset` is a thin
companion — the heavy lifting (parallel valuation, the depreciation
schedule) reuses `:ledger` (ADR-021) and `:schedule` (ADR-032).

**Shape after.**
- `modules/asset/` companion (deps.edn wired).
- `modules/asset/src/kontor/asset/schema.clj` — the `:asset/*`,
  `:asset-class/*`, `:asset-event/*` attrs + `:asset/status`
  status-transition seeds + approval-policy seeds + `install!`.
- `modules/asset/src/kontor/asset/asset.clj` — the lifecycle
  transactors + resolvers (`by-code`, `pull-asset`).
- `modules/asset/test/kontor/asset/lifecycle_test.clj`.

**What ADR-053 does NOT do** (ADR-054/055/056):
- No `:asset-depreciation` book, no `DepreciationProvider`, no
  depreciation math — ADR-054 + ADR-055.
- No GL postings — the caller supplies `:origin-transaction` /
  `:asset-event/transaction` refs; ADR-054 ships the helpers that
  build them.
- No `compute-cash-flow` / `compute-equity-changes` / Anlagengitter
  — ADR-056.

**Review-after (research note 33).** `:asset-event` is append-only
*by transactor convention* — the lifecycle transactors only ever
create events — but this is **not** sealing-enforced; the earlier
"immutable" wording overclaimed an enforced guarantee and has been
corrected (wiring a companion entity into kernel sealing would
breach the anti-accretion contract). `:partial-disposal` is a
RESERVED `:asset-event/kind` — no transactor / posting builder /
re-plan path yet; a documented follow-up. `revise-useful-life!` /
`record-addition!` record the cross-book `:asset-event` only — the
per-book apply step is `depreciation/revise-book!` (ADR-055), now
stated explicitly in their docstrings.

Date: 2026-05-14.

## ADR-054 — `kontor-asset`: a depreciation area IS a `:ledger`

**Decision.** A `kontor-asset` **depreciation book is per `(asset,
ledger)`**. The "depreciation area" — SAP's term for "one physical
asset, N regulatory depreciation schedules" (Handelsbilanz +
Steuerbilanz, book + tax, IFRS + local GAAP) — is **a `:ledger`**
(ADR-021), not a `:valuation-book` (ADR-027), not a new
`:depreciation-area` concept. ADR-054 ships the `:asset-depreciation`
book entity, the `:asset-method-params` provider-config entity, the
GL posting builders (`kontor.asset.posting`), and book management
(`kontor.asset.depreciation`). The `DepreciationProvider` protocol
and the depreciation runner are ADR-055; the Jahresabschluss
reports are ADR-056.

**Why `:ledger`, not `:valuation-book` or a new concept** (research
note 31 §2):
- `:ledger` is the kernel's parallel-book primitive — sum-to-zero is
  enforced *per ledger* (`kontor.posting`), and a `:ledger` carries
  a `:ledger/framework` keyword (`:HGB`, `:IFRS`, `:tax-de`, …).
  ADR-021 *explicitly named* the fixed-asset register as its
  forward-compat target.
- A `:valuation-book` (ADR-027) is a *cost-basis selector*
  (FIFO/LIFO/…). A depreciation area is **not** a cost-basis
  question — the acquisition cost is one known number; what differs
  per book is the *method, life, convention* applied to that cost
  and the resulting *journal entries*. That is the `:ledger` axis.
- A new `:depreciation-area` entity would be a third parallel-book
  primitive next to `:ledger` and `:valuation-book` — rejected by
  the anti-accretion contract. SAP's "area ≠ ledger" split is a
  legacy artifact its own S/4HANA New Asset Accounting *removed*;
  kontor adopts the post-reconciliation model: **one depreciation
  area per ledger.** A depreciation view that never posts to the GL
  is a `:ledger/type :statistical` ledger — already provided.

**The audit guarantee is free.** The HGB-book depreciation run
posts `Dr Abschreibungsaufwand / Cr kumulierte Abschreibung` tagged
`:posting/ledger "hgb"`; the Steuerbilanz run posts the (different)
amount tagged `:posting/ledger "tax-de"`. Per-ledger sum-to-zero
means each book balances independently and an HGB depreciation
debit *cannot* net against a tax-book credit. "Handelsbilanz vs
Steuerbilanz side by side" is a ledger-filtered `compute-statement`
call (ADR-056 adds the filter).

**`kontor-asset` stays framework-agnostic.** It only ever takes a
`:ledger` ref. *Which* ledger is primary (HGB-primary for a DE
customer, IFRS-primary for another) is an l10n install-time
decision — `kontor-l10n-de` installs the `hgb` and `tax-de`
ledgers. The companion ships no jurisdiction data.

**Entities** (companion-owned):
- **`:asset-depreciation`** — the per-`(asset, ledger)` book.
  `:asset` + `:ledger` refs; `:identity` (`:db.unique/identity`
  tuple `[asset ledger]` — one book per pair, no nil-in-tuple
  caveat since both are always present); `:provider-id` (which
  `DepreciationProvider` — ADR-055); `:method-params` (ref to
  `:asset-method-params`, optional); `:useful-life-months` (this
  book's life — an HGB life ≠ the AfA-Tabelle life is common);
  `:convention` (`:full` | `:half-year` | `:mid-quarter` |
  `:mid-month` | `:zeitanteilig`); `:depreciable-base` (usually
  `acquisition-cost − salvage`; may differ per book — a tax bonus
  reduces the tax base); `:commodity`; `:start-date` (the
  depreciation clock start for this book — defaults to the asset's
  `:in-service-date`); `:schedule` (ref to the ADR-032 `:schedule`
  the runner fires); `:effective-rule` (ref to the l10n-owned
  effective-dated rule row — ADR-055 §effective-dating; optional);
  `:note`.
- **`:asset-method-params`** — a small entity holding the
  heterogeneous `DepreciationProvider` config (research note 31 Q1,
  maintainer chose the small-entity option over EDN-as-string for
  queryability). All attrs optional; a provider reads the ones it
  needs: `:rate-multiple` (declining-balance: 1.5× / 2× / 2.5×),
  `:ceiling-rate` (declining-balance absolute % ceiling),
  `:switch-to-straight-line` (the DB→SL auto-switch),
  `:total-units` (units-of-production lifetime count),
  `:table-key` (a keyword an l10n table-driven provider — MACRS,
  AfA — keys on), `:note`. It is a 1:1 component of a book (created
  inline by `open-book!`), so it carries no unique identity.

**`kontor.asset.depreciation`** — book management:
- `open-book!` — create an `:asset-depreciation` book for an
  `(asset, ledger)` pair *and* its `:schedule` (`:schedule/kind
  :depreciation`, `:origin-entity` → the book, `:frequency`
  defaults `:monthly`, `:start-date` = the book's start-date,
  `:end-date` = start + `useful-life-months` so
  `pending-occurrences` terminates, `:total-amount` =
  `depreciable-base`) and its optional `:asset-method-params`, in
  one tx. `:depreciable-base` defaults to `acquisition-cost −
  salvage-value` pulled from the asset.
- `book-for` / `books-of` / `pull-book` — resolvers.
- `accumulated-depreciation` — `Σ :schedule-occurrence/amount` over
  the book's schedule. Asset-local and ledger-aware *by
  construction* (each book owns its own schedule → its own
  occurrences) — it does **not** sum GL postings, because the GL
  accounts (`:asset/accumulated-account`) are shared across assets
  in a class and a posting carries no per-asset back-ref. The
  subsystem's own `:schedule-occurrence` log is the source of truth
  for the roll-forward; the GL postings are its *consequence*.
- `net-book-value` — `acquisition-cost − accumulated-depreciation`
  for a book. ADR-054's NBV reflects the depreciation schedule
  only; impairment/revaluation/addition adjustments to NBV arrive
  via re-planning (ADR-055's `plan-event`) and are folded in there.

**`kontor.asset.posting`** — pure GL posting builders. Each returns
tx-data ready for `datahike.api/transact` (built through
`kontor.posting/build-transaction`, so sum-to-zero per
`(ledger, commodity)` is enforced for free); none transacts.
- `plan-capitalisation` — `Dr :asset-account / Cr <credit-account>`
  (the credit side — AP, bank, an asset-clearing account — is
  caller-supplied).
- `plan-depreciation-charge` — `Dr :expense-account /
  Cr :accumulated-account` for the period amount, tagged
  `:posting/ledger` = the book's ledger. The runner (ADR-055)
  calls this per pending occurrence.
- `plan-disposal` — `Dr <proceeds-account> + Dr :accumulated-account
  / Cr :asset-account` ± gain/loss. Gain/loss = `proceeds − NBV`;
  a gain credits `<gain-account>`, a loss debits `<loss-account>`.
  Because NBV differs per book (HGB NBV ≠ tax NBV), disposal is a
  per-book posting.
- `plan-impairment` — `Dr <impairment-expense-account> /
  Cr :accumulated-account` (IAS 36 / HGB §253 außerplanmäßige
  Abschreibung).
- `plan-revaluation` — `Dr :asset-account /
  Cr <revaluation-surplus-account>` for an upward revaluation
  (the surplus is an OCI/equity line — ADR-056's equity statement
  picks it up); reversed for a downward revaluation.

Each builder takes a `:ledger` and threads it onto every posting,
so a multi-book asset's disposal/impairment is N calls (one per
book) — exactly the parallel-book shape.

**Composition with ADR-053.** ADR-053's lifecycle transactors
(`acquire!`, `dispose!`, `impair!`, `revalue!`) take an optional
`:transaction` / `:origin-transaction` ref. The ADR-054 flow:
build the GL tx-data with a `kontor.asset.posting` builder,
`d/transact` it, then pass the resulting transaction eid into the
ADR-053 transactor. The builders are the durable seam; fusing
build+transact+link into one call is a consumer-app ergonomic
choice, not a kernel concern.

**Composition with `:schedule` (ADR-032).** Each book owns one
`:schedule`. ADR-032 anticipated exactly this: *"Each companion's
posting-builder consumes the schedule for its domain math; the
kernel just records what happened."* `open-book!` creates the
schedule; ADR-055's runner fires it.

**What ADR-054 does NOT do** (ADR-055/056):
- No `DepreciationProvider` protocol, no method math, no runner —
  ADR-055.
- No effective-dated rule *resolution* — the `:effective-rule` ref
  is a slot ADR-055 + l10n fill.
- No Jahresabschluss reports — ADR-056.

**Shape after.**
- `modules/asset/src/kontor/asset/schema.clj` — `:asset-depreciation/*`
  + `:asset-method-params/*` attrs added; `install!` aggregate
  extended.
- `modules/asset/src/kontor/asset/depreciation.clj` — book
  management.
- `modules/asset/src/kontor/asset/posting.clj` — the pure GL
  posting builders.
- `modules/asset/test/kontor/asset/depreciation_book_test.clj`.

**Review-after (research note 33).** Three follow-ups landed here:
(a) `:asset-depreciation/opening-accumulated` — a pure reporting
scalar for the mid-life-import case (an asset already part-way
through its life on day one); `accumulated-depreciation` /
`net-book-value` add it to the occurrence sum, the provider never
sees it (the caller passes the *remaining* `:depreciable-base`).
(b) `open-book!` now throws if no `:commodity` is resolvable rather
than letting nil propagate into the occurrence log. (c)
`plan-disposal`'s `:asset-account-cost` is now optional, defaulting
to the asset's `:acquisition-cost` — one canonical NBV cost source.

Date: 2026-05-14.

## ADR-055 — `kontor-asset`: the `DepreciationProvider` protocol + the runner

**Decision.** `kontor-asset` ships a `DepreciationProvider` protocol
— the `TaxProvider` (ADR-005) / `CostingProvider` (ADR-029) pattern
applied to depreciation — plus four companion-shipped method
built-ins and a thin `run-depreciation!` / `catch-up!` runner.
l10n modules ship the jurisdiction-specific impls (MACRS,
AfA-degressive, CCA, full-expensing) and register them the same way.

**Why a provider protocol** (not a fixed method, not the kernel's
job). ADR-032 deliberately left `:schedule` amount-agnostic: *"The
kernel does NOT compute per-period amounts."* For depreciation the
method is genuinely runtime-pluggable — a DE customer needs
linear-HGB and degressive-tax *simultaneously* on the same asset,
and an l10n module must be able to inject MACRS without forking the
companion. So the seam is a provider protocol, exactly as ADR-032
anticipated (*"The `CostingProvider` analogue would be a
`ScheduleProvider`"*).

**The protocol** (`kontor.asset.depreciation-provider`):
```clojure
(defprotocol DepreciationProvider
  (provider-id   [provider])
  (plan-schedule [provider db book]))
```
`plan-schedule` takes a `db` value + an `:asset-depreciation` book
and returns the full forward plan: a `:periods` vector
(`{:sequence :date :amount :method-used :basis-remaining :fired?}`),
`:convention`, `:total`, `:provider-id`, and — for
units-of-production — `:requires-units` + `:rate-per-unit`. It is
**pure**: reads `db`, transacts nothing.

**One `plan-schedule`, not `plan-schedule` + `plan-event`.** The
research-note sketch (note 31 §3) had a separate `plan-event` method
to re-plan the tail after a mid-life event. We collapsed it: a
**`plan-schedule` that reads the book's `:schedule-occurrence`
log** keeps every already-fired period's actual amount untouched
(`:fired? true`) and re-plans *only the un-fired tail*. Because the
runner re-plans on every call, a useful-life revision or a
subsequent addition — applied to the book by
`depreciation/revise-book!` — is picked up automatically on the next
run, with fired periods never restated (IAS 16 estimate-changes are
prospective by construction). A single event-aware `plan-schedule`
is simpler and strictly subsumes the sketch's two methods.

**`db` is a protocol parameter on purpose** (research note 31 Q7).
The US MACRS mid-quarter convention is triggered by an *aggregate*
property of all of the year's additions — `MacrsProvider` for one
asset must query its siblings. Passing `db` lets an l10n provider
do that; the built-ins simply ignore it beyond their own book.

**Companion-shipped built-ins** (all `:full` convention, precisely):
- **`StraightLineProvider`** — `(depreciable-base − accumulated) /
  remaining-periods`, last period absorbs the rounding remainder.
- **`DecliningBalanceProvider`** — `book-value × rate`, rate =
  `:rate-multiple × (1/n)` capped by `:ceiling-rate`, with the
  optional `:switch-to-straight-line` (switch the moment
  SL-on-remaining ≥ DB; the standard §7 Abs. 2 EStG optimisation).
  The final un-fired period drives book value exactly to salvage so
  `Σ = depreciable-base`.
- **`SumOfYearsDigitsProvider`** — accelerated; weights re-spread
  over the remaining periods.
- **`UnitsOfProductionProvider`** — the one method whose schedule is
  *not* fully forward-computable. `plan-schedule` returns
  `:rate-per-unit` + `:requires-units true`; the runner supplies the
  per-period unit actuals (`:units` — a map or fn) at fire time.

`provider-for` resolves a built-in by `:asset-depreciation/provider-id`;
l10n impls are passed to the runner directly.

**Conventions are l10n territory.** The built-ins implement `:full`
precisely. `:convention` is *carried through* the plan so an l10n
provider can read it, but exact first/last-period proration
(half-year, mid-quarter, mid-month, zeitanteilig) is the l10n
provider's job — MACRS GDS and the AfA-Tabellen bake the convention
into their percentage tables. A built-in given a non-`:full`
convention still computes `:full`.

**The runner** (`kontor.asset.runner`). `run-depreciation!` is a
convenience over the ADR-032 machinery: for each `:schedule`
occurrence due but un-fired, ask the provider for the amount, build
the `Dr expense / Cr accumulated` entry with
`kontor.asset.posting/plan-depreciation-charge` (sealed by default —
`:posted-at` = the occurrence date, so the charge shows in
`:posted`-only statements), and `kontor.schedule/record-occurrence!`
it (idempotent on `[schedule, sequence]` — a re-run double-posts
nothing). On the last occurrence it drives `:asset/status` →
`:fully-depreciated` (ungated — no approval policy). `catch-up!` is
the named explicit-`as-of` variant for the missed-month case.

**Trigger ownership** (ADR-032, research note 31 Q6): `kontor-asset`
ships the runner *functions* but **not a scheduler**. Who calls them
— a consumer-app cron, a manual close step, a workflow engine — is
out of scope. The runner also does not enforce the
run-before-close sequencing rule (research note 31 §5.2: fire the
year's last occurrence before `close-fiscal-year!`); that is a
documented caller-ordering convention.

**`revise-book!`** (`kontor.asset.depreciation`) is the explicit
"supersede the pending tail" operation: it updates a book's
`:useful-life-months` / `:depreciable-base` and reschedules the
`:schedule` `:end-date`, then the next `run-depreciation!` re-plans
the un-fired tail. It is the per-book half of the cross-book
`:asset-event` recorded by `asset/revise-useful-life!` /
`record-addition!` — per-book because an HGB life ≠ an AfA-Tabelle
life. It refuses a revision that implies fewer periods than have
already fired.

**Effective-dated jurisdiction rules — ADR-026 applied, one
divergence.** *Which* rule governs an asset — the German
degressive-AfA statute windows, the MACRS §168(k)/§179 windows — is
an effective-dated-data problem, and it is **l10n's**, not the
companion's. l10n-de ships a `:depreciation-rule` entity
(l10n-owned namespace, l10n-de's own ADR per ADR-006) with
`:effective-from` / `:effective-until` bounds. The **one deliberate
divergence from ADR-026**: ADR-026 selects the rule whose window
contains the *transaction's effective date*; depreciation selects
on the asset's **`:asset/acquisition-date`** — the rule that governs
an asset is fixed at acquisition for its whole life (a 2021 machine
keeps the 2020-22 degressive window through 2031). The resolved row
is pinned permanently as `:asset-depreciation/effective-rule` at
`open-book!` time and never re-resolved — the valid-time-not-tx-time
argument: a 2026 recomputation of a 2021 asset must use the rule
legally in force on the 2021 acquisition date. The companion ships
the *slot* (`:effective-rule`, ADR-054 schema) + the *pattern*; l10n
ships the *rows* + the resolution helper; a built-in only ever reads
`:asset-method-params` (which l10n populates from the resolved
rule). The selection-by-window logic, the open-interval-on-the-right
semantics, and the overlap tie-break all transfer verbatim from
ADR-026 — so this is genuinely ADR-026, not a new effective-dating
ADR.

**Minor extension to ADR-054.** `kontor.asset.posting/build` gained
a `:posted-at` header key: when present the entry is built sealed
(`:transaction/state :posted` + `:posted-at` propagated to every
posting — the `kontor.sealing` invariant). The runner passes it;
every builder now optionally produces a sealed entry.

**What ADR-055 does NOT do** (ADR-056): the Anlagengitter
roll-forward report, `compute-cash-flow` / `compute-equity-changes`,
the `:ledger` filter on `compute-statement`, the
`:no-pending-depreciation` pre-close hook.

**Shape after.**
- `modules/asset/src/kontor/asset/depreciation_provider.clj` — the
  protocol + four built-ins + `provider-for`.
- `modules/asset/src/kontor/asset/runner.clj` — `run-depreciation!`
  + `catch-up!`.
- `modules/asset/src/kontor/asset/depreciation.clj` — gains
  `book-plan-inputs` (the provider's input map) + `revise-book!`;
  `periods-for` made public.
- `modules/asset/src/kontor/asset/posting.clj` — `build` gains
  `:posted-at`.
- `modules/asset/test/kontor/asset/depreciation_run_test.clj`.

**Review-after (research note 33).** Four fixes landed here:
(a) **P0** — `DecliningBalanceProvider` ignored
`:depreciable-base` (it threaded book value from
`:acquisition-cost`), silently over-depreciating bonus-base tax
books; `assemble` now threads from `depreciable-base +
salvage-value`, so all three built-ins depreciate exactly
`:depreciable-base`. (b) the built-ins now **throw** on a
non-`:full` convention instead of silently computing `:full` — a
non-`:full` convention needs an l10n provider. (c) the runner now
**stops at the earliest `:disposal` / `:transfer` event** (never
depreciates past a terminal event) and **refuses to post into a
soft-closed / sealed period** (`kontor.period/
assert-not-in-locked-period!`, surfaced with the partial progress —
research note 31 §5.3). (d) the declining-balance switch-to-SL flag
now rides the `assemble` accumulator instead of a `volatile!`.

Date: 2026-05-14.

## ADR-056 — Jahresabschluss extensions: Anlagengitter + cash-flow + equity-changes + the `:ledger` filter

**Decision.** Stage L′ closes the year-end story with four pieces —
two kernel touches and two companion additions — keeping the
"kernel does not evolve by accretion" contract intact (research note
31 §6.4):

1. **The `:ledger` filter on `compute-report` / `compute-statement`**
   (kernel). The hard prerequisite for a HGB-vs-IFRS Jahresabschluss:
   `compute-report` gains a `:ledger` option, `compute-statement`
   forwards it. When set, only postings on that ledger are summed.
   Per ADR-021 a posting with **no** `:posting/ledger` is
   conceptually in the *primary* book — so when the requested ledger
   is `:ledger/type :primary`, nil-ledger postings pass too. With the
   filter, "Handelsbilanz vs Steuerbilanz side by side" is two
   `compute-statement` calls.

2. **`compute-cash-flow`** (kernel, `kontor.financial-statements`).
   The indirect-method cash-flow statement (Kapitalflussrechnung /
   DRS 21 / IAS 7) — depreciation is its canonical non-cash add-back.
   The key realisation: **every line of an indirect cash-flow
   statement is a window delta** — the change in any account over
   `[from, to)` IS the sum of its postings in that window. Net
   income, the depreciation add-back, every working-capital movement
   — all window aggregations. So `compute-cash-flow` is
   `compute-statement` specialised to *require* a window, plus an
   optional `:reconcile-codes` check that computes the actual delta
   on the cash / cash-equivalent accounts and asserts the indirect
   statement reconciles to it. l10n supplies the definition; the
   kernel runs it. The `:line/negate` knob (added to
   `bucket-by-section`) gives l10n the per-line sign control
   working-capital lines need (an increase in receivables *reduces*
   cash).

3. **`compute-equity-changes`** (kernel, `kontor.financial-statements`).
   The statement of changes in equity (Eigenkapitalspiegel / DRS 22
   / IAS 1) — needed because the IAS 16 revaluation surplus flows
   through OCI/equity, not P&L. The definition is a
   `:statement/components` vector; per component the kernel computes
   the opening balance (point-in-time at `:from`), each movement (a
   window aggregation), the closing balance (point-in-time at
   `:to`), and `:component/reconciles?` — whether opening +
   Σmovements = closing. If the l10n definition's movement lines
   partition the component's window activity it reconciles; the
   kernel runs it and checks.

4. **`asset-roll-forward` — the Anlagengitter** (companion,
   `kontor.asset.report`). The statutory asset-history sheet (HGB
   §284 Abs. 3; US Form 4562; IAS 16.73) — the per-class gross-cost
   and accumulated-depreciation roll-forward over a date window. It
   is **not** a posting aggregation by account code — it is keyed on
   `:asset` + `:asset-event` + `:schedule-occurrence` history, so it
   stays correct even when many assets in a class share one GL
   account (the per-asset-attribution problem a GL sum cannot
   solve). The arithmetic is jurisdiction-free — every regime needs
   an asset roll-forward; only the *column layout / class grouping*
   is l10n. `cost-closing = cost-opening + cost-additions −
   cost-disposals`; `accum-closing = accum-opening + accum-period −
   accum-disposals`; NBV = cost − accumulated. The roll-forward is
   per `(window, ledger)` — a depreciation book is per-(asset,
   ledger), so the HGB Anlagengitter and the Steuerbilanz
   Anlagengitter are two calls.

   v1 folds `:transfer` events into disposals (a transfer-out IS a
   removal from this ledger's books); a dedicated transfers column
   is a documented follow-up.

5. **The `:no-pending-depreciation` pre-close hook**
   (`kontor.asset.report/pending-depreciation-issues`). A
   `kontor.period/close!`-compatible `:pre-checks` fn that flags any
   `:asset-depreciation` book with a `:schedule` occurrence due
   within the period window but not yet fired — "you forgot to run
   the depreciation runner." It is a *hook* the period already
   exposes — **no code change to `kontor.period`** — composed by the
   caller with `default-pre-close-checks`. The companion also
   documents the sequencing rule (research note 31 §5.2): fire the
   year's last depreciation occurrence *before*
   `close-fiscal-year!`, so the last charge does not land after the
   close.

**Why the two statements are kernel, not companion.** The cash-flow
and equity statements are *driven by* the asset subsystem (the
depreciation add-back, the revaluation surplus) but are not *of* it
— they are universal statements every accounting workload needs.
They belong next to `compute-statement` in the kernel; the
*definitions* (which line is which section) stay l10n. The
Anlagengitter, by contrast, is genuinely asset-domain — it belongs
in the companion.

**What stays l10n.** All statement *line layouts* (HGB §266/§275,
the cash-flow section mapping, the IFRS presentation); the
Anlagengitter column layout + SKR04 class grouping; the
Anhang/Lagebericht document assembly (arguably consumer-app, not
kontor at all).

**Shape after.**
- `src/kontor/report.clj` — `compute-report` gains `:ledger`;
  `pull-posting` pulls `:posting/ledger` → `:ledger-eid`.
- `src/kontor/financial_statements.clj` — `compute-statement` gains
  `:ledger`; `bucket-by-section` honours `:line/negate`;
  `compute-cash-flow` + `compute-equity-changes` added.
- `modules/asset/src/kontor/asset/report.clj` — `asset-roll-forward`
  + `pending-depreciation-issues`.
- `modules/asset/test/kontor/asset/jahresabschluss_test.clj`.

This completes Stage L′ (`kontor-asset`): ADR-053 (register +
lifecycle) → ADR-054 (depreciation books = `:ledger`) → ADR-055
(provider protocol + runner) → ADR-056 (Jahresabschluss).

**Review-after (research note 33).** `asset-roll-forward` now folds
the value-moving mid-life events into the Anlagengitter:
`:impairment` `:asset-event`s flow into the accumulated-depreciation
roll-forward (HGB §284 Abs. 3 shows außerplanmäßige Abschreibung),
`:revaluation` events adjust the gross-cost roll-forward, and a
book's `:opening-accumulated` (a mid-life import's pre-schedule
depreciation) is opening accumulated. `:impairments` /
`:revaluations` are exposed as in-window memo totals. The closing
identities (`accum-closing = accum-opening + accum-period −
accum-disposals`, NBV = cost − accumulated) still hold.

Date: 2026-05-14.

## ADR-057 — `kontor-inventory`: facilities + the physical stock ledger

**Decision.** Stage N ships `kontor-inventory` as a companion module
(`modules/inventory/`, cohabiting per ADR-002). ADR-057 lays the
foundation: the **physical/operational** inventory layer — facilities,
locations, the `:inventory-item` stock bucket, and `:inventory-detail`,
an append-only signed-quantity-delta ledger. The available-to-promise
engine + reservation bridge are ADR-058; the receive/issue/transfer
operations + GL integration are ADR-059; cycle counts + reconciliation
reports are ADR-060.

**Why a separate operational layer** (research notes 35 + 36;
maintainer-confirmed design call). OFBiz conflates physical tracking
and cost valuation in one `InventoryItem` row. kontor already split
valuation out into `:valuation-*` (ADR-027–030). So `kontor-inventory`
is the **physical half only** — it carries *no cost*. `:inventory-item`
(where stock physically is) sits **alongside** `:valuation-layer`
(what stock costs), the two joined by a shared `:lot`, many physical
buckets to one valuation layer. This mirrors the kernel's own
`:ledger` (financial) vs `:valuation-book` (costing) split — two
parallel append-only logs answering two questions. A bin-to-bin move
is a pure quantity event; only cross-entity / cross-book moves touch
valuation (ADR-059).

**Entities** (companion-owned):
- **`:facility`** — a node in a self-referential warehouse tree:
  `:code` (unique identity), `:name`, `:type` (`:warehouse` |
  `:store` | `:plant` | `:transit` | `:virtual`), `:parent`,
  `:owner-entity` (ref `:entity`, ADR-031 — *not* a `Party`),
  `:default-days-to-ship`, `:opened-at` / `:closed-at`, `:note`.
- **`:facility-location`** — a bin within a facility:
  `:facility` + `:seq-id`, `:identity` (`:db.unique/identity` tuple
  `[facility seq-id]`), `:type` (`:pickloc` | `:bulk` | `:staging` —
  `:pickloc` is walked first by the ADR-058 reservation algorithm),
  `:area` / `:aisle` / `:bin`, `:note`.
- **`:facility-product`** — the per-`(facility, product)` policy row:
  `:facility` + `:product`, `:identity` (`:db.unique/identity` tuple),
  `:min-stock`, `:reorder-qty`, `:days-to-ship`, `:replenish-method`,
  `:note`. ADR-058 adds `:safety-stock`; ADR-059 adds
  `:negative-allowed?`.
- **`:inventory-item`** — the physical stock bucket: one entity per
  `(product, facility, location, lot, owner)` combination of stock.
  `:product` (generic ref — the consumer's product entity),
  `:facility`, `:location` (optional), `:lot` (optional — the SAME
  `:lot` `:valuation-layer/lot` points at, the join between the
  physical and financial halves), `:owner-entity` (optional),
  `:kind` (`:non-serial` | `:serialized`), `:status` (an ADR-034
  status-machine facet), `:serial-number` (serialized only),
  `:received-at`, `:note`. **Carries no cost.** Buckets are resolved
  by query (`find-or-create-inventory-item`), not a unique-identity
  tuple — the natural key has nilable members (location/lot/owner),
  and a composite tuple with nils is non-idempotent (the caveat that
  bit Stages L′/M).
- **`:inventory-detail`** — *the spine*. An append-only ledger of
  **signed quantity deltas** against an `:inventory-item`:
  `:inventory-item`, `:effective-date` (the valid-time of the
  delta — a first-class queryable field, following
  `:schedule-occurrence/scheduled-date`'s precedent, *not* the
  `:tx/valid-from` tx-meta convention; a quantity-ledger row's
  valid-time IS its effective-date), `:qoh-diff` (signed: a receipt
  is `+`, an issue `−`, a variance `±`), `:atp-diff` (signed: a
  reservation is `−`, a cancel `+`, every physical event moves it
  too), `:reason` (a movement/variance reason keyword),
  `:description`, and a polymorphic source pointer `:source` (a
  generic ref) + `:source-kind` (`:opening` | `:receipt` |
  `:issuance` | `:reservation` | `:variance` | `:transfer` |
  `:adjustment`). ADR-059 adds `:transaction` (the GL link). Never
  updated, only appended — append-only **by transactor convention**
  (`record-detail!` is the only writer), not sealing-enforced
  (wiring a companion entity into kernel sealing, ADR-007, would
  breach the anti-accretion contract — same call as `:asset-event`).

**Quantity is derived, never stored** (maintainer-confirmed design
call). `on-hand-qty` is `Σ :inventory-detail/qoh-diff` — a bitemporal
query over the detail ledger (`:as-of-valid` filters
`:effective-date`; `:as-of-tx` is datahike's `d/as-of`). No
denormalized QOH cache: the ledger is the single source of truth, and
free bitemporal QOH falls out (the "what was on-hand at the cutoff"
report is a parameter, not a Field-Audit-Trail upgrade tier). If the
reservation walk's linear scan proves too slow at scale (research
note 36 §10), a materialized as-of snapshot is its own future ADR —
explicit, not silent.

**Transactors / queries** (`modules/inventory/src/kontor/inventory/`):
`define-facility!` / `define-location!` / `define-facility-product!`
(config); `find-or-create-inventory-item` (bucket resolution);
`record-detail!` (the low-level append — the single `:inventory-detail`
writer); `place-opening-stock!` (the migration / initial-load
convenience — a `:source-kind :opening` detail); `on-hand-qty`
(bitemporal QOH, per item or per `(product, facility)`); `details-of`
/ `items-at`. The atomic `receive!` / `issue!` (valuation + GL) are
ADR-059.

**What ADR-057 does NOT do** (ADR-058/059/060): no
`available-to-promise`, no reservation bridge (ADR-058); no GL
postings, no negative-inventory policy, no transfers (ADR-059); no
cycle counts, no reconciliation reports, no FEFO (ADR-060).

**Shape after.**
- `deps.edn` / `tests.edn` — `modules/inventory` wired.
- `modules/inventory/src/kontor/inventory/schema.clj` — the
  `:facility/* :facility-location/* :facility-product/*
  :inventory-item/* :inventory-detail/*` attrs + `:inventory-item/status`
  status-transition seeds + `install!`.
- `modules/inventory/src/kontor/inventory/core.clj` — facility config,
  bucket resolution, `record-detail!`, `place-opening-stock!`,
  `on-hand-qty`, queries.
- `modules/inventory/test/kontor/inventory/stock_ledger_test.clj`.

**Review-after (research note 37).** Two fixes touched ADR-057's
surface: (a) `:inventory-item/kind :serialized` is now marked
**RESERVED** in the schema doc — the value exists but the
reservation walk skips serialized buckets and `issue!` has no
serialized path yet (the serial-as-qty-1-lot ergonomics are a
documented follow-up; use `:non-serial` until then). (b)
`inventory-item-entity` is now public so transactors can build the
bucket INSIDE their own atomic tx rather than a prior one — the fix
for the `receive!` / `issue!` orphan-bucket-on-failure P1.

Date: 2026-05-14.

## ADR-058 — `kontor-inventory`: available-to-promise + the reservation bridge

**Decision.** ADR-058 makes `:inventory-detail` answer the
*can-I-sell-it* question and wires `kontor-sales`' long-orphaned
`:inv-reservation` schema to a real availability engine.

**ATP is `:atp-diff`, derived — not a reservation scan.** Every
`:inventory-detail` row carries TWO signed deltas: `:qoh-diff`
(physical) and `:atp-diff` (promiseable). A reservation appends a
detail with `:qoh-diff 0, :atp-diff -take` — the stock is still
physically present, just promised. So `atp-raw` = `Σ :atp-diff` is
`on-hand − reservations` *by construction*, a pure derivation over
the same ledger `on-hand-qty` reads (maintainer-confirmed: pure
derivation, no stored cache). `available-to-promise` =
`atp-raw − safety-stock`, where `:facility-product/safety-stock` is
the v1 buffer. The v1 netting is `on-hand − reservations −
safety-stock` (maintainer-confirmed); scheduled receipts (open POs)
and in-transit transfers are a documented follow-up.

**The `:inv-reservation` fix-up** (research note 35 §4 — "the
load-bearing integration fix"). `kontor-sales` shipped
`:inv-reservation` keyed on `[order-item ship-group lot]` because no
`:inventory-item` entity existed yet. ADR-058 replaces
`:inv-reservation/lot` with `:inv-reservation/inventory-item` and
moves the identity tuple to `[order-item ship-group inventory-item]`
— a reservation binds to a *physical bucket*, and a single order
line fans out into one reservation per bucket it draws from (the
OFBiz `OrderItemShipGrpInvRes` shape). The ref-attr is sales-owned;
the `:inventory-item` it points at is kontor-inventory's. The sales
test's standalone-reservation fixture is updated to the new shape
(a bare stand-in entity — the *real* reservation behaviour is tested
in kontor-inventory). `:inv-reservation/reserve-order-enum`'s doc is
corrected: it is the *physical picking strategy* (which bucket to
pull), explicitly distinct from `:valuation-book/cost-method` (the
*costing* method) — they share names but answer different
questions (note 35 §2.3).

**`reserve!` lives in `kontor-inventory`, not `kontor-sales`**
(research note 36 §3 — kontor-inventory owns the availability
picture). `reserve!` walks the candidate `:inventory-item` buckets
for a `(product, facility)`: `:available` + `:non-serial` buckets
with `atp-raw > 0`, **`:pickloc` locations before `:bulk` before
`:staging` before no-location**, then sorted by
`:reserve-order-enum` — v1 supports `:fifo-rec` / `:lifo-rec` (by
`:received-at`); the expiry-driven `:fifo-exp` / `:lifo-exp` need
`:lot/expires-at`, which ADR-060 ships (an unknown enum throws
`:inventory/unsupported-reserve-order` until then). It draws
`take = min(remaining, bucket-atp)` from each, appending an
`:atp-diff -take` `:inventory-detail` + an `:inv-reservation` row
per draw — all in ONE transaction.

**Back-order policy.** A shortfall, when `:require-inventory?` is
false (default), is back-ordered: the last drawn reservation row
carries `:quantity-not-available` and an extra negative-`:atp-diff`
detail drives that bucket's ATP negative (the OFBiz "push the
remainder onto the last item as negative ATP" rule — folded into
the last row, not a new one, to avoid an
`[order-item ship-group inventory-item]` tuple collision). When
nothing could be drawn at all, one back-order reservation is created
against a resolved bucket. With `:require-inventory? true`, a
shortfall throws `:inventory/insufficient-atp` and writes nothing.

**`release-reservation!`** (research note 13 P1 — the
`cancel-order!` → release path). Appends a compensating
`:atp-diff +(quantity + quantity-not-available)` detail (restoring
the ATP a back-order consumed too) and retracts the reservation row.
The `:inventory-detail` is the audit trail of the release;
`:inv-reservation` is a transient allocation, not an audited posting
— retracting it is correct.

**What ADR-058 does NOT do** (ADR-059/060): no GL postings, no
receive/issue/transfer operations, no negative-inventory cost policy
(ADR-058's back-order moves *ATP*, never *quantity-on-hand* — the
stock genuinely isn't there yet); no `:lot/expires-at` schema, so no
`:fifo-exp` / `:lifo-exp` picking (ADR-060); no cycle counts.

**Shape after.**
- `modules/sales/src/kontor/sales/schema.clj` — `:inv-reservation`
  fix-up (`:inventory-item` ref, retuple, dropped `:lot`,
  corrected `:reserve-order-enum` doc).
- `modules/inventory/src/kontor/inventory/schema.clj` —
  `:facility-product/safety-stock` added.
- `modules/inventory/src/kontor/inventory/core.clj` — `item-set`
  promoted to public `resolve-scope`.
- `modules/inventory/src/kontor/inventory/reservation.clj` —
  `atp-raw`, `available-to-promise`, `reserve!`,
  `release-reservation!`.
- `modules/inventory/test/kontor/inventory/reservation_test.clj`.

Date: 2026-05-14.

## ADR-059 — `kontor-inventory`: receive / issue / transfer + GL integration

**Decision.** ADR-059 ships the operations that move stock —
`receive!`, `issue!`, the two-phase transfer — and the
negative-inventory policy. Its defining guarantee: **`receive!` and
`issue!` write both halves in ONE transaction** — the valuation
layer + GL postings (via `kontor.posting/plan-stock-move`, ADR-030)
*and* the physical `:inventory-detail` — linked by
`:inventory-detail/transaction`. The physical and financial views
cannot drift, because they are written together and the GL inventory
account is *only* ever touched through `plan-stock-move` (research
note 36 §2 — the discipline that makes the "my balance-sheet
inventory number is wrong" complaint structurally hard).

**`receive!`** — `plan-stock-move :direction :in` produces the
`:valuation-layer` + the `Dr inventory / Cr GR-IR` postings;
`receive!` find-or-creates the physical bucket and appends an
`:inventory-detail` (`:qoh-diff +qty`, `:atp-diff +qty`,
`:source-kind :receipt`, `:transaction` → the GL tx). One atomic
transaction. (Procurement adopting `receive!` in place of its own
valuation-half write is a follow-up — `receive!` is a standalone
helper for now.)

**`issue!`** — `plan-stock-move :direction :out` produces the
`:layer-consumption` + the `Dr COGS / Cr inventory` postings;
`issue!` appends the physical `:inventory-detail` (`:qoh-diff -qty`).
The `:atp-diff` depends on whether a `:reservation` is being
realized: realizing a reservation moves QOH only (`:atp-diff 0` —
the reservation already dropped ATP in ADR-058) and **retracts** the
`:inv-reservation` (it is spent, not cancelled — no ATP restored,
unlike `release-reservation!`); a plain issue moves both
(`:atp-diff -qty`). Scrap is `issue!` with a loss-routing
`:account-fn` (no `:reservation`) — pure composition, no separate
helper.

**The negative-inventory policy** (maintainer-confirmed design
call). `:facility-product/negative-allowed?` is a per-(facility,
product) flag. `issue!` lets `plan-stock-move` be the authority on
availability — it tries the move, and on the `plan-stock-move`
underflow it consults the flag:
- flag false/absent (default) → throw `:inventory/negative-not-allowed`
  (Xero's "forbid" — but *opt-in*, not forced);
- flag true → `create-negative-fill!` writes an explicit
  **negative-fill `:valuation-layer`** for the shortfall at a
  caller-supplied `:estimated-unit-cost` + a `:negative-fill` record,
  *then* the issue retries and consumes it. The over-issue therefore
  *has* a real layer — there is no silent invented cost (the
  NetSuite/ERPNext failure mode, note 36 §1). This is the one place
  `issue!` is two transactions (the negative-fill, then the issue);
  each is a consistent state.

**`true-up-negative-fill!`** reconciles an `:open` `:negative-fill`
to actual cost: a `:layer-adjustment` on the negative-fill layer for
`(actual − estimated) × shortfall-qty` + a balanced GL correction,
linked back via `:negative-fill/true-up-adjustment`, marking it
`:trued-up`. The correction's exact account routing is a
simplification — a stricter consumer posts its own and stamps the
adjustment.

**Transfers are two-phase and GL-free.** `transfer!` creates an
`:inventory-transfer` (`:status :in-transit`) and appends a
`:qoh-diff -qty` detail on the source bucket — the stock is "on the
truck", off the source, not yet at the destination, so the
in-transit *balance* is the Σ quantity of `:in-transit` transfer
rows (the period-close cutoff exposure, note 36 §8).
`complete-transfer!` find-or-creates the destination bucket, appends
`:qoh-diff +qty`, sets `:status :complete`. `cancel-transfer!`
returns the qty to the source. A same-entity move is a *pure
quantity event* — **no GL posting** (research note 36 §4 — only
cross-entity / cross-book moves touch valuation; those, with a GL
leg, are a documented follow-up).

**Schema delta.** `:inventory-detail/transaction` (the GL link);
`:facility-product/negative-allowed?`; `:inventory-transfer/*` (the
two-phase document) + its `:status` status-transition seeds;
`:negative-fill/*` (the explicit estimated-cost-layer record).

**What ADR-059 does NOT do** (ADR-060): no cycle counts /
`:physical-inventory` / `:inventory-variance`; no
`inventory-roll-forward` / `valuation-tie-out` report helpers; no
`:lot/expires-at` / FEFO; no kit / consignment helpers (deferred
follow-ups per note 36 §7). Cross-entity transfers with a GL leg,
and procurement adopting `receive!`, are documented follow-ups.

**Shape after.**
- `modules/inventory/src/kontor/inventory/schema.clj` — the ADR-059
  schema delta + `:inventory-transfer/status` seeds.
- `modules/inventory/src/kontor/inventory/core.clj` —
  `define-facility-product!` gains `:negative-allowed?`.
- `modules/inventory/src/kontor/inventory/ops.clj` — `receive!`,
  `issue!`, `true-up-negative-fill!`, `transfer!` /
  `complete-transfer!` / `cancel-transfer!`.
- `modules/inventory/test/kontor/inventory/ops_test.clj`.

**Deferred follow-ups** (research note 36 §7 + review-after note 37):
kit / unbuild helpers, the consignment ownership flag, drop-ship as
a non-inventory path; cross-entity transfers with a GL leg;
procurement adopting `receive!`. Added to the deferral list by the
review-after:
- **Returns / RMA** — a return is mechanically a `receive!`, but the
  hard part (which unit cost? the link to the original
  `:layer-consumption`?) has no path; a `return!` helper that
  defaults cost from the original consumption is the follow-up.
- **In-transit ATP** — `transfer!` removes the qty from the source
  ATP at send time and the destination only gains it at
  `complete-transfer!`, so in-transit stock is promiseable at
  neither facility during transit. v1-acceptable; the fix is a
  virtual `:transit` facility the transfer moves *through*.
- **`:account/system-managed?`** — research note 36 §2 wanted a
  flag so only `plan-stock-move` may touch the inventory account
  (the *preventive* control; `valuation-tie-out` is only
  *detective*). Deliberately deferred: kontor ships no UI to
  fat-finger a raw JE, so this is a consumer-app-layer concern; the
  kernel flag + posting-middleware enforcement is a named follow-up.

**Review-after (research note 37).** No P0s. `receive!` / `issue!`
were restructured to plan the move FIRST (pure — throws before
anything is committed) and build the `:inventory-item` bucket inline
in the atomic tx, so a `plan-stock-move` failure leaves no orphan
bucket (CR P1-2). `:negative-fill/origin-issue` added — the
negative-fill now links back to the originating issue's
`:transaction` (market-pain P2). `cancel-transfer!` /
`release-reservation!` gained `:effective-date` opts (CR P2). The
`true-up-negative-fill!` docstring was clarified — its
`:layer-adjustment` is an audit marker (the layer is already
consumed); the GL correction posting does the work.

Date: 2026-05-14.

## ADR-060 — `kontor-inventory`: cycle counts + reconciliation + FEFO

**Decision.** ADR-060 closes Stage N: cycle counts, the
reconciliation reports, and the expiry-driven (FEFO) path.

**Cycle counts.** `:physical-inventory` is a count event header;
`:inventory-variance` is a counted line (`:expected-qty` vs
`:counted-qty` vs `:qoh-var`, reason-coded, with a `:recount-of`
self-ref for the recount-before-posting workflow). `start-count!` /
`record-count-line!` / `post-count!`. **The freeze is a valid-time
convention, not a DB lock** — `record-count-line!` snapshots the
perpetual `on-hand-qty` AS-OF the count's `:count-date`, so
concurrent picks at a later valid-time do not corrupt the count
(research note 36 §6 — a structural advantage over systems that
must literally freeze the warehouse). `post-count!` routes every
non-zero variance through `kontor.posting/plan-stock-move` — a count
adjustment **is** a GL event (shrinkage is an expense; found stock a
gain), and routing it through `plan-stock-move` is what keeps the
subledger and the GL from drifting — a negative variance is
`:direction :out` (the loss leg routed by `:account-fn`), a positive
variance `:direction :in` at `:found-unit-cost`. It also appends the
physical `:inventory-detail` (`:source-kind :variance`, linked to
both the `:inventory-variance` audit document and the GL
`:transaction`). Each line is its own transaction.

**Reconciliation reports** (`kontor.inventory.report`) — thin
queries over facts that already exist:
- `inventory-roll-forward` — `opening + Σ movements = closing`, the
  movements bucketed by `:inventory-detail/source-kind`. The
  append-only ledger *is* the roll-forward; reason codes and
  immutability are structural (research note 36 §9 — the Xero "no
  audit trail / no reason codes" complaint cannot occur here).
- `valuation-tie-out` — asserts the inventory subledger
  (`Σ valuation/on-hand-value` per book) equals the GL inventory
  account balance, surfacing any delta. The detective control for
  the "my balance-sheet inventory number is wrong" complaint
  (research note 36 §2).

**FEFO** (first-expiry-first-out). Two pieces:
- `:lot/expires-at` — a small *kernel* schema addition to `:lot/*`
  (the second and last kernel touch of Stage N, alongside the
  `compute-statement` `:ledger` filter — both minimal).
- `kontor.valuation/available-layers` gains `:order-by`
  (`:received-at` default | `:expires-at` — nearest lot expiry
  first, no-expiry layers last). `kontor.inventory.costing/FefoCostingProvider`
  is a companion-shipped `CostingProvider` (ADR-029's protocol is
  pluggable) — FIFO's draw loop over the FEFO-ordered layers.
- The ADR-058 `reserve!` walk gains `:fifo-exp` / `:lifo-exp`
  (deferred from ADR-058 precisely because `:lot/expires-at` did not
  exist yet) — the picking side of FEFO.

**Deferred follow-ups** (research note 36 §7, §10 — P2 /
vertical-dependent): kit / unbuild helpers, the consignment
ownership flag, drop-ship as a non-inventory path, the
serial-as-qty-1-lot ergonomics, a materialised QOH/ATP snapshot if
the derived-view linear scan proves slow at scale, and the
`:lot/status` quarantine flag. Each composes from the primitives
Stage N now ships — none needs a new primitive.

**Shape after.**
- `src/kontor/schema.clj` — `:lot/expires-at` added.
- `src/kontor/valuation.clj` — `available-layers` gains `:order-by`.
- `modules/inventory/src/kontor/inventory/schema.clj` —
  `:physical-inventory/*` + `:inventory-variance/*` + their
  `:status` seeds.
- `modules/inventory/src/kontor/inventory/reservation.clj` —
  `candidate-buckets` gains `:fifo-exp` / `:lifo-exp`.
- `modules/inventory/src/kontor/inventory/costing.clj` —
  `FefoCostingProvider`.
- `modules/inventory/src/kontor/inventory/count.clj` —
  `start-count!` / `record-count-line!` / `post-count!`.
- `modules/inventory/src/kontor/inventory/report.clj` —
  `inventory-roll-forward` / `valuation-tie-out`.
- `modules/inventory/test/kontor/inventory/count_report_test.clj`.

This completes Stage N (`kontor-inventory`): ADR-057 (facilities +
the physical stock ledger) → ADR-058 (available-to-promise + the
reservation bridge) → ADR-059 (receive / issue / transfer + GL) →
ADR-060 (cycle counts + reconciliation + FEFO). The #1 ERP
functional gap (research note 34) is closed, with exactly two small
kernel touches.

**Review-after (research note 37).** Two fixes touched ADR-060's
surface: `valuation-tie-out` now snapshots the db with `(d/as-of …)`
before the subledger reduce so `:as-of-tx` applies to *both* sides
(CR P1-1 — it was silently ignored on the subledger side, a
spurious-difference false positive); and
`kontor.inventory.report/in-transit-balance` was added — ADR-059
defined the in-transit balance but shipped no query helper for it
(market-pain P2). `post-count!` gained a pre-flight `:found-unit-cost`
validation + idempotent re-run (CR P1-3 — a partial failure no
longer leaves a count half-posted).

Date: 2026-05-14.

## ADR-061 — `kontor-expense`: employee expense reports

**Decision.** Ships `kontor-expense` as a companion module
(`modules/expense/`, cohabiting per ADR-002). It is the "cheapest
high-value win" of research note 34's ranked gap list — a month-1
need for any employer, and the substrate is **already 100% there**.
Research note 09 §5 is the research-before: it specs the canonical
entity shape (Odoo's `hr.expense`) and the load-bearing
`payment-mode` split.

**Why almost no new machinery.** An expense report is a small
approval-gated document that composes a GL entry. Every primitive
it needs already exists: `:partner` is the employee; `:audit-doc`
(ADR-038/051) is the receipt; the status machine (ADR-034) +
approval policy (ADR-038) drive submit → approve → post; analytic
distributions (ADR-012/022) carry the cost-center;
`kontor.posting/build-transaction` composes the GL; the
`:transaction/source` convention (`"expense-report:<id>"`, note 09
§7) is the back-link. `kontor-expense` adds **two entities and a
handful of transactors** — no kernel touch, no new primitive.

**Entities** (companion-owned):
- **`:expense-report`** — the submission header: `:code` (unique
  identity), `:employee` (ref `:partner`), `:status` (the ADR-034
  lifecycle facet), `:report-date`, `:commodity`, `:total` (a
  cached convenience — the truth is `Σ :expense-line/amount`),
  `:transaction` (the GL entry, set by `post-report!`),
  `:reimbursement-transaction` (set by `reimburse!`), `:note`.
- **`:expense-line`** — one expense: `:expense-report` (ref),
  `:category` (a generic ref — the consumer's expense-category
  entity; kontor ships the entity slot, not a vocabulary),
  `:expense-date`, `:amount` + `:commodity`, `:payment-mode`
  (`:own-account` | `:company-account` — *load-bearing*, see
  below), `:expense-account` (the P&L account this line debits),
  `:cost-center` (optional ref `:analytic-account`),
  `:supporting-doc` (ref `:audit-doc` — the receipt),
  `:description`.

**The `:payment-mode` split** (note 09 §5 — load-bearing). It
decides the *credit* leg of the GL entry:
- `:own-account` — the employee paid; `post-report!` builds
  `Dr :expense-account (per line, analytic) / Cr <employee-
  reimbursement-payable>`. A later `reimburse!` settles
  `Dr <reimbursement-payable> / Cr <cash>`.
- `:company-account` — paid on a company card; `Dr :expense-account
  / Cr <corporate-card-clearing>`. Bank-statement matching
  (`kontor-bank-*`) closes the clearing — no `reimburse!` step.

A report may mix modes across lines; `post-report!` groups the
credit legs by `(payment-mode, commodity)`.

**The `:expense-report/status` lifecycle** (ADR-034 facet):
```
nil → :draft → :submitted → :approved → :posted → :reimbursed
                    │            │
                    └── :rejected ┘   (:submitted / :approved → :rejected)
```
`:posted` is reached by `post-report!` (the GL entry); `:reimbursed`
by `reimburse!` (own-account only — a company-account report is
terminal at `:posted`). **Governance** (ADR-038): `:submitted →
:approved` is gated by `:no-self-approval` (the employee cannot
approve their own report) + `:requires-supporting-doc` overridable
per-policy by the l10n/consumer; `:requires-non-empty-reason-note`
on `:rejected`.

**Transactors** (`modules/expense/src/kontor/expense/`):
`create-report!` / `add-line!` (build the header + lines);
`submit!` / `approve!` / `reject!` (drive the status machine);
`post-report!` (build + transact the GL entry, group credit legs by
payment-mode, stamp `:transaction/source`, set `:status :posted`);
`reimburse!` (own-account settle, set `:status :reimbursed`);
plus `report-total` / `lines-of` / `pull-report` queries.

**Out of scope** (ADR-010 + note 09 §5): no UI (consumer apps —
beleg); no OCR / receipt-scanning; no per-vendor importer in the
kernel — an `ExpenseImporter` adapter protocol (SAP Concur,
Expensify, Pleo, Ramp, …) is a documented future seam, the same
shape as `TaxProvider`. Mileage rates, per-diem tables, policy
limits — l10n / consumer data, not kernel.

**Shape after.**
- `deps.edn` / `tests.edn` — `modules/expense` wired.
- `modules/expense/src/kontor/expense/schema.clj` — `:expense-report/*`
  + `:expense-line/*` attrs + `:expense-report/status`
  status-transition + approval-policy seeds + `install!`.
- `modules/expense/src/kontor/expense/core.clj` — the transactors +
  queries.
- `modules/expense/test/kontor/expense/expense_test.clj`.

**Review-after.** An independent code-review agent found GL
correctness, atomicity, and sealing all solid, and **one P0**:
`submit!` / `approve!` passed a *hard-coded* `:from` to the status
machine, which trusts a non-nil `:from` — so `approve!` on a
`:draft` report jumped straight to `:approved`, skipping the submit
gate entirely (and a bogus `:approved` report would then post to
the GL). **Fixed** — both now read the *real* `:expense-report/status`
and assert the expected `:from` (a clear typed error), the same
pattern `add-line!` / `post-report!` / `reimburse!` already used.
Also added (P2): a `:rejected → :draft` transition + a `reopen!`
transactor — the reset-to-draft path real expense workflows need so
a rejected report can be corrected and resubmitted rather than
re-created from scratch.

Date: 2026-05-14.

## ADR-062 — `kontor-lease`: the `:lease` contract + the ROU asset reuses `kontor-asset`

**Decision.** Ships `kontor-lease` as a companion module
(`modules/lease/`, cohabiting per ADR-002). Lessee-side lease
accounting under IFRS 16 and ASC 842. Research notes 38 (reference)
+ 39 (market-pain) are the research-before; the four load-bearing
design calls were maintainer-confirmed. `kontor-lease` is a **thin
companion** — the substrate (ADR-021 parallel `:ledger`, ADR-032
`:schedule`, ADR-031 multi-entity, and the whole `kontor-asset`
depreciation machinery) was built for it.

ADR-062 lays the foundation: the `:lease` contract entity, the
`:lease/status` lifecycle, the **decision that the Right-of-Use
asset IS an `:asset`** (not a new entity), and the short-term /
low-value exemption path. The `:lease-liability` book +
`LeaseProvider` + the operating-lease ROU plug + the full
`commence!` transactor are ADR-063; modifications + remeasurements +
variable payments + FX are ADR-064.

**The ROU asset reuses `kontor-asset` whole** (maintainer-confirmed:
one `:asset` per lease). A Right-of-Use asset is an `:asset` with
`:asset/class` a ROU class, `:asset/origin-document` → the lease
contract `:audit-doc`, depreciated by `kontor-asset`'s
`:asset-depreciation` books and runner. For an IFRS-16 / ASC-842
*finance* lease the existing `StraightLineProvider` drives ROU
depreciation with **zero new code**; the ASC-842 *operating*-lease
"plug" is one new `DepreciationProvider` impl (ADR-063). No
`:rou-asset` entity — that would duplicate `:asset`'s shape and N×
the lifecycle/disposal/impairment bookkeeping. The rare case where
the ROU *cost* differs per framework is absorbed by the existing
per-book `:asset-depreciation/depreciable-base` override.

**`:lease` carries framework-NEUTRAL facts only.** Classification
(`:finance` / `:operating` / exempt) is **per-`(lease, ledger)`**
(maintainer-confirmed) — the same lease is `:finance` on the IFRS
ledger and effectively off-balance on an HGB ledger — so it lives
on the `:lease-liability` book (ADR-063), *not* on `:lease`.
`:lease` carries: `:code` (unique identity), `:name`, `:lessor`
(ref `:partner`), `:underlying-asset-desc`, `:asset-class` (the ROU
class), `:commencement-date`, `:term-months` (the term *as
assessed* — the renewal-option judgement already folded in by the
consumer), `:payment-amount` + `:payment-frequency` +
`:payment-timing` (`:in-advance` | `:in-arrears`), `:commodity`,
`:discount-rate` (the IBR or implicit rate, pinned at commencement
— *not* kernel-computed, a consumer input), `:initial-direct-costs`
/ `:prepaid-at-commencement` / `:incentives-received` /
`:purchase-option-price` (the ROU-cost + liability-PV inputs),
`:rou-asset` (set by `commence!`), `:entity` (ADR-031 scope),
`:origin-document`, `:status`, `:note`.

**The `:lease/status` lifecycle** (ADR-034 facet):
```
nil → :draft → :active → :expired      (the lease runner's last occurrence)
                       → :terminated   (early termination)
                       → :purchased    (purchase option exercised)
```
`:draft` is the recorded-but-not-commenced state — `define-lease!`
creates the `:lease` at `:draft`; ADR-063's `commence!` does the
balance-sheet recognition and moves it `:draft → :active`. The
three terminal states are reached in ADR-063/064.

**Governance** (ADR-038). `:draft → :active` requires
`:requires-supporting-doc` (the signed lease contract); `:active →
:terminated` requires `:requires-supporting-doc` (the termination
agreement) + `:no-self-approval`.

**The exemption path needs NO `:lease` entity.** A short-term
(≤12-month) or low-value lease has no balance-sheet footprint — it
is a straight-line expense. `kontor-lease` ships
`register-exempt-lease!` (creates a plain `:schedule`,
`:schedule/kind :lease-expense`, with the straight-line per-period
amount) + `plan-exempt-lease-charge` (the `Dr lease-expense /
Cr cash-or-payable` posting builder). Modelling an exempt lease as
a full `:lease` with a flag would be over-engineering — the whole
point of the exemption is that it bypasses the ROU / liability
machinery.

**Transactors** (`modules/lease/src/kontor/lease/`): `define-lease!`
(create the `:lease` at `:draft`); `register-exempt-lease!` +
`plan-exempt-lease-charge` (the exemption path); `by-code` /
`resolve-lease` / `pull-lease` queries. The full `commence!` is
ADR-063.

**Shape after.**
- `deps.edn` / `tests.edn` — `modules/lease` wired.
- `modules/lease/src/kontor/lease/schema.clj` — `:lease/*` attrs +
  `:lease/status` status-transition + approval-policy seeds +
  `install!`.
- `modules/lease/src/kontor/lease/core.clj` — `define-lease!`,
  the exemption helpers, queries.
- `modules/lease/test/kontor/lease/lease_test.clj`.

Date: 2026-05-14.

---

## ADR-063 — `kontor-lease`: the `:lease-liability` book, the `LeaseProvider`, the operating-lease ROU plug, `commence!` + `run-lease!`

**Decision.** The substantive half of `kontor-lease` — balance-sheet
recognition and the period close. ADR-062 laid the `:lease` contract;
ADR-063 makes a `:draft` lease *live*: it opens the liability book,
the ROU asset, posts the day-one entry, and runs the periodic GL.

**`:lease-liability` is a per-`(lease, ledger)` book — the exact
sibling of `:asset-depreciation`.** This is where the per-ledger
classification confirmed in ADR-062 actually lives. One physical
`:lease` has N `:lease-liability` books, one per `:ledger` (ADR-021):
the same lease is `:finance` on the IFRS ledger and `:operating` on
the US-GAAP ledger, and the two books carry *different*
`:opening-liability` measurements when their discount rates differ.
Each book owns one ADR-032 `:schedule` (`:schedule/kind
:lease-liability`) the runner fires. Attrs: `:lease` + `:ledger`
refs, `:identity` (`:db.unique/identity` tuple `[lease ledger]` —
both members always present, no nil caveat), `:classification`
(`:finance` | `:operating`), `:provider-id`, `:opening-liability`,
`:discount-rate`, `:liability-account`, `:interest-account`,
`:opening-fired-through` (0 at commencement; ADR-064's modifications
move it forward and re-anchor `:opening-liability`), `:commodity`,
`:schedule`, `:note`. A short-term / low-value **exempt** lease gets
*no* `:lease-liability` book at all — the ADR-062 exemption path.

**The `LeaseProvider` protocol — the sibling of
`DepreciationProvider`.** `kontor-lease` ships the protocol +
`EffectiveInterestProvider` (the built-in); an l10n module could ship
a jurisdiction-specific impl and pass it to the runner directly.
`plan-schedule` is *pure* — it reads a `db` value and returns the
liability unwind: per period the cash `:payment`, its `:interest` /
`:principal` split, the `:balance-remaining`, plus
`:straight-line-expense`. The unwind is **fully deterministic** from
the book's `:opening-liability` + `:discount-rate` + the lease's
payment terms — so a re-plan mid-run reproduces every already-fired
period bit-exact (the same prospective-replan property ADR-055's
`DepreciationProvider` has). `EffectiveInterestProvider`: period rate
= `discount-rate / periods-per-year`; each non-final payment splits
into `interest = round2(balance × rate)` and `principal = payment −
interest`; the **final period drives the balance exactly to zero**
(`principal = balance`), absorbing the rounding drift into the last
payment. `:in-advance` period 1 — the contract's first payment, made
*at* commencement — carries zero interest.

**The operating-lease ROU "plug" is one `DepreciationProvider`
impl** (`:lease-rou-plug`, `kontor.lease.rou-provider`). This is the
ASC 842 operating-lease subtlety. An operating lease recognises ONE
straight-line lease cost per period, but the liability still unwinds
at the effective-interest rate (interest is front-loaded). So the
ROU asset's amortisation is the **plug** that keeps the total flat:

```
ROU amortisation(period) = straight-line-expense − interest(period)
```

with `straight-line-expense = (Σ undiscounted payments + initial
direct costs + prepaid − incentives) / n`. That numerator is chosen
precisely so the plug **sums exactly to the ROU asset's cost** over
the term: `Σ plug = Σ(SL − interest) = n·SL − Σinterest = ROU-cost`
(since `Σinterest = total-payments − PV` and `ROU-cost = PV + IDC +
prepaid − incentives`). The consequence: an operating lease reuses
the *entire* `kontor-asset` machinery — `:asset-depreciation` book,
runner, GL builder — by routing its ROU book through this provider
instead of a built-in, and `commence!` points both the interest leg
*and* the ROU charge at the single lease-expense account via the
**per-book `:asset-depreciation/expense-account` override** (the
small ADR-063 `kontor-asset` touch). The P&L then shows exactly one
straight-line lease-expense line — interest + plug meeting in one
account — as ASC 842 requires. A `:finance` lease ignores all this:
its ROU book just uses the `kontor-asset` `:straight-line` provider.

**The `kontor-asset` touch.** ADR-063 adds *one optional attribute* —
`:asset-depreciation/expense-account`, a per-book override of the
asset's `:asset/expense-account`. Without it a multi-book ROU asset
could not route depreciation to a different P&L account per ledger
(depreciation-expense on the finance book, the single lease-expense
account on the operating book). `kontor.asset.depreciation/open-book!`
takes it as an optional key; `kontor.asset.posting/book-context`
prefers it over the asset's account. Additive, backward-compatible,
covered by the existing `kontor-asset` suite.

**`commence!` — the balance-sheet recognition transactor.** Turns a
`:draft` lease `:active`. For the lease (asserting it *is* `:draft`
and has an `:origin-document`): (1) creates the **single** ROU
`:asset` via `kontor.asset.asset/acquire!` (`:in-service`,
`:asset/class` the lease's ROU class); (2) per ledger in `:books`,
computes the per-book PV (`present-value` of the payments at that
book's rate), opens the `:lease-liability` book + its schedule and
the ROU `:asset-depreciation` book + its schedule, and posts the
day-one entry `Dr ROU-asset / Cr lease-liability [/ Cr cash]` tagged
with the `:ledger`; (3) sets `:lease/rou-asset`; (4) drives
`:lease/status :draft → :active` (the `:requires-supporting-doc`
policy met by the lease's `:origin-document`). ROU cost = `PV +
initial-direct-costs + prepaid − incentives`; because PV is per-book,
each `:asset-depreciation` book carries its own `:depreciable-base`
and each `:lease-liability` book its own `:opening-liability` — the
parallel-ledger shape. The liability schedule and the ROU
depreciation schedule are given the **same start date** so occurrence
`k` of each lines up (`:in-advance` → commencement; `:in-arrears` →
the first period-end).

**`run-lease!` — the period close for one `(lease, ledger)`.** Fires
the liability schedule's due payment occurrences — `Dr interest +
Dr lease-liability(principal) / Cr cash`, logging the occurrence with
amount = the cash payment — then runs the sibling ROU
`:asset-depreciation` book through `kontor.asset.runner/run-
depreciation!` (with the `:lease-rou-plug` provider for an operating
book, the `kontor-asset` built-in for a finance book). When the
liability schedule completes it drives `:lease/status :active →
:expired` (ungated, like the asset runner's `:fully-depreciated`).
Each charge is period-lock-checked (`kontor.period`), and re-running
a window is idempotent on `[schedule, sequence]`. Like the
`kontor-asset` runner, `kontor-lease` ships the runner *functions*,
not a scheduler — and `commence!` / `run-lease!` each do several
`d/transact`s (acquire → open books → post per book), *not* one
atomic tx, consistent with the rest of the codebase.

**Why no `outstanding-liability` denorm.** The carrying amount of a
liability book is *derived* — `lease-provider/outstanding-liability`
runs the deterministic plan and reads the `:balance-remaining` of the
highest fired occurrence (or `:opening-liability` when nothing has
fired). The fired log says *which* periods ran; the provider says
what the running balance is. No stored running total to drift.

**Namespace layout** (`modules/lease/src/kontor/lease/`):
`core.clj` (ADR-062 + `present-value` / `periods-for`),
`liability.clj` (the `:lease-liability` book lifecycle +
`book-plan-inputs` + `open-liability-book!` — no provider dep),
`lease-provider.clj` (the protocol + `EffectiveInterestProvider` +
`provider-for` + `plan-for-book` + `outstanding-liability`),
`rou-provider.clj` (the `LeaseRouPlugProvider`), `posting.clj`
(`plan-lease-recognition` + `plan-lease-payment`), `runner.clj`
(`commence!` + `run-lease!`). Mirrors `kontor-asset`'s split
(`depreciation` ↔ `depreciation-provider` ↔ `posting` ↔ `runner`).

**Deferred to ADR-064.** `:lease-modification` (the append-only
modification event), the remeasurement / partial-termination
transactors, prospective re-planning via `:opening-fired-through`,
variable / index-linked payments (an index reset = a remeasurement),
and the FX rule (the liability is monetary — retranslated at the
closing rate; the ROU asset is non-monetary — frozen at the
historical rate). `EffectiveInterestProvider` already handles
`:opening-fired-through > 0`; ADR-064 wires the transactors that
move it.

**Shape after.**
- `modules/lease/src/kontor/lease/schema.clj` — `:lease-liability/*`
  attrs added (the `:lease/*` attrs are ADR-062).
- `modules/lease/src/kontor/lease/{liability,lease_provider,rou_provider,posting,runner}.clj`
  — new.
- `modules/lease/src/kontor/lease/core.clj` — `present-value` /
  `periods-for` / `periods-per-year` added.
- `modules/asset/src/kontor/asset/{schema,depreciation,posting}.clj`
  — the `:asset-depreciation/expense-account` per-book override.
- `modules/lease/test/kontor/lease/runner_test.clj` — new (PV, the
  unwind, `commence!`, finance `run-lease!`, the operating-lease
  single-cost result, multi-book parallel ledgers).

Date: 2026-05-14.

---

## ADR-064 — `kontor-lease`: modifications, remeasurements, terminations + FX

**Decision.** The final piece of `kontor-lease` — what happens when a
lease *changes*. ADR-062 was the contract, ADR-063 the recognition +
the periodic run; ADR-064 is the IFRS 16.39-46 / ASC 842 modification
machinery: index resets, term changes, rate resets, partial and full
terminations, purchase-option exercises, and the FX rule.

**`:lease-modification` — an append-only event, the sibling of
`:asset-event`.** Every modification records one. Attrs: `:lease`
ref, `:kind` (`:remeasurement` | `:index-reset` | `:term-change` |
`:rate-reset` | `:partial-termination` | `:termination` |
`:purchase`), `:date`, the revised terms (`:new-payment-amount` /
`:new-term-months` / `:new-discount-rate`), `:scope-decrease-pct`
(partial termination only), `:justification` (ref `:audit-doc`),
`:transaction` (`:db.cardinality/many` — one GL entry per affected
ledger book), `:note`. Append-only **by convention** — the
transactors only ever create one. The `:lease` contract facts ARE
mutated (a modification *is* a change to the contract), but every
change is documented by its event, and bitemporally the old facts
remain queryable (the mutation is `kbt/with-vt`-stamped from the
modification date).

**The re-anchor mechanism — `:opening-fired-through` finally earns
its keep.** A modification never restates a fired period.
`liability/revise-liability-book!` sets the book's new
`:opening-liability` (the remeasured balance) and advances
`:opening-fired-through` to the count of already-fired occurrences —
so `EffectiveInterestProvider` re-plans **only the un-fired tail**,
walking from the remeasured balance at period `opening-fired-through
+ 1`. ADR-063 built the provider event-aware for exactly this; ADR-064
is the transactor side. The ROU `:asset-depreciation` book is
re-anchored by `kontor.asset.depreciation/revise-book!` — the *same*
prospective re-plan `kontor-asset` already does for an IAS 16
useful-life revision. No new re-planning machinery: the modification
transactors are pure orchestration over primitives that already
existed.

**`remeasure!`** — the general remeasurement (`:index-reset`,
`:term-change`, `:rate-reset` all route through it; the keyword
records intent, the math is identical). For each `:lease-liability`
book: snapshot the *old* outstanding liability (before the `:lease`
facts move), update the `:lease` contract facts, remeasure the
liability at the PV of the revised remaining payments, and post the
difference against the ROU `:asset` — `Dr/Cr ROU-asset /
lease-liability` — with P&L absorbing the remainder **only** when the
adjustment would drive the ROU carrying amount below zero (IFRS
16.39). An index-linked payment change is just a `remeasure!` with a
`:new-payment-amount` — no separate variable-payment machinery, as
the research note 38 sketch anticipated.

**`partial-terminate!`** — a scope decrease, the **proportional
approach** (IFRS 16.46(b) — the load-bearing design call). Two effects
folded into one GL entry per book: (1) the liability and the ROU are
reduced in proportion to `:scope-decrease-pct`, the difference a P&L
gain/loss; (2) the remaining liability is then remeasured for the
revised payments, that delta adjusting the ROU. The alternative
(16.46(a) — remeasure-only, the gain/loss falling out of the
remeasurement) is the other permitted treatment; v1 commits to the
proportional approach and documents it. The maths is exact: every
test lease, after a partial termination, still unwinds to zero by end
of term.

**`terminate!`** — full early termination. Per book: derecognise the
liability and the ROU asset, pay any `:penalty`, book the difference
to P&L, cancel both schedules; then drive `:lease/status :active →
:terminated` (ADR-038: `:requires-supporting-doc` + `:no-self-
approval`). The ROU `:asset` *entity's* status is left untouched —
kontor-lease terminates the lease *accounting*; disposing the ROU
`:asset` from the fixed-asset register is a `kontor.asset.asset/
dispose!` call the consumer makes if its process requires it. A
deliberate, documented boundary, not an omission.

**`purchase!`** — a purchase option is exercised. Per book: settle the
remaining liability in cash, cancel both schedules; drive
`:lease/status :active → :purchased`. The ROU `:asset` **continues**
as an owned asset (IFRS 16.67 — no derecognition, the carrying amount
carries over); kontor-lease does not presume the owned-asset useful
life — the consumer opens a fresh `kontor.asset.depreciation/
open-book!` over the remaining life.

**The operating-lease ROU plug, post-modification.** ADR-063's
`LeaseRouPlugProvider` originally read the `LeaseProvider`'s
`:straight-line-expense`. But ASC 842 *recalculates* the single
straight-line cost on a modification — and the `LeaseProvider` never
sees the ROU book, so it cannot. ADR-064 moves the SL computation
*into* the plug provider: `straight-line-expense = (remaining ROU +
Σ un-fired interest) / count(un-fired)`. At commencement this reduces
to ADR-063's `(payments + IDC + prepaid − incentives) / n`; after a
modification it correctly re-levels over the remaining term. The plug
provider is now also fired-aware (already-fired ROU periods keep their
logged amount; only the un-fired tail is re-planned) — necessary
because after a modification the liability plan covers only the
un-fired periods.

**FX — a rule + a thin builder, not an engine.** The lease liability
is a **monetary** item — retranslated to the reporting currency at
the closing rate; the ROU asset is **non-monetary** — frozen at the
historical rate, so it does not move on retranslation.
`posting/plan-fx-retranslation` builds the `Dr/Cr lease-liability /
fx-gain-loss` entry from a caller-supplied signed `:gain-loss`.
kontor ships **no FX-rate engine** — the closing rate is a consumer
input, exactly as the discount rate is (consistent with ADR-005's "we
ship the protocol, not the rate data"). Building an FX-rate
subsystem into the accounting kernel would be the same category error
as writing a US sales-tax engine.

**v1 simplification — the remeasurement PV.** `remeasure!` discounts
the revised *remaining* payments as an ordinary annuity (in-arrears)
from the modification date. The post-modification unwind always
treats the first un-fired period as accruing interest, so this is
self-consistent; for an originally-`:in-advance` lease modified
mid-term there is a minor sub-period timing approximation — the
precise day-count is a consumer-level refinement, as the discount
rate itself is. Documented in the `kontor.lease.modification` ns.

**Shape after.**
- `modules/lease/src/kontor/lease/schema.clj` —
  `:lease-modification/*` attrs added.
- `modules/lease/src/kontor/lease/modification.clj` — new
  (`remeasure!`, `partial-terminate!`, `terminate!`, `purchase!`).
- `modules/lease/src/kontor/lease/liability.clj` —
  `revise-liability-book!` added.
- `modules/lease/src/kontor/lease/posting.clj` — `plan-adjustment` +
  `plan-fx-retranslation` added.
- `modules/lease/src/kontor/lease/rou_provider.clj` — the plug now
  computes its own (re-levelling, fired-aware) straight-line cost.
- `modules/lease/test/kontor/lease/modification_test.clj` — new.

This completes the `kontor-lease` companion: ADR-062 (the `:lease`
contract + the ROU-asset-reuse decision) → ADR-063 (the
`:lease-liability` book + `LeaseProvider` + the operating-lease ROU
plug + `commence!` / `run-lease!`) → ADR-064 (modifications).

**Review-after (research note 40).** Two independent agents audited
ADR-062..064. **No P0s** — the code-review agent confirmed by REPL
probe that the GL balances, the liability unwinds to exactly zero,
the operating-lease ROU plug sums to the depreciable base, and
modifications conserve money across multi-book parallel ledgers.
Three P1s were fixed in the review-fix commit: (1) **period-lock
enforcement** — `remeasure!` / `partial-terminate!` / `terminate!` /
`purchase!` and `commence!` now refuse to post a sealed GL entry into
a soft-closed / sealed period (`run-lease!` already did), and an
`assert-modifiable!` pre-flight gate refuses the whole modification
*before* `record-modification!` mutates `:lease`, so a period-locked
modification cannot leave an orphaned contract-fact change; (2)
**boundary validation** — `define-lease!` now rejects a non-positive
`:term-months` (it previously blew up opaquely deep in
`schedule/date-of-occurrence`), a non-positive `:payment-amount`, and
a negative `:discount-rate`; (3) **the liability ↔ ROU lockstep
invariant** — `run-lease!` now asserts the two schedules' fired-counts
match before running, throwing a clear `:lease/lockstep-divergence`
instead of a cryptic `…-misaligned` later. Cheap P2s (docstring
honesty on the commencement-only `:straight-line-expense` and the
per-book vs asset-level ROU cost, a `long`-coercion, an explicit
throw on a missing ROU book) were also fixed, and four review-after
coverage tests added (operating-lease `remeasure!`, a term-extension,
a modification into an already-modified book, period-lock refusal).
The market-pain agent found the hardest pains addressed and the
module genuinely thin; its findings were all **deferred scope** the
ADRs already flag — a mid-life portfolio-import / `catch-up!`
transactor, persisting the remeasurement deltas on
`:lease-modification` for a one-read liability roll-forward, a
per-`(lease, ledger)` index-reset fork (ASC 842 expenses an index
change, IFRS 16 remeasures it), a mandatory discount-rate
justification ref, stepped/rent-free payment profiles, and an FX
*transactor* (only the builder ships). These are captured as followup
tasks, not silently dropped. `bb test`: 909 tests, 3322 assertions,
0 failures.

Date: 2026-05-14.

---

## ADR-065 — `kontor-authz`: relationship-based access control (ReBAC), ported from EACL

**Decision.** Ships `kontor-authz` as a companion module
(`modules/authz/`, cohabiting per ADR-002) — a **relationship-based
access-control (ReBAC)** layer. It is a faithful, datahike-native
reimplementation of [EACL](https://github.com/theronic/eacl)'s
SpiceDB-shaped model. Research notes 40-pre (#90 ReBAC research) +
**41** (the hands-on EACL→datahike evaluation) are the
research-before; the build/fork/wrap call was maintainer-confirmed.

**Why ReBAC, why EACL's model.** kontor is multi-entity, multi-user
from the kernel out; "who may see / post / approve *this* entity" is
a real question consumers (beleg, simmis) will ask. ReBAC — SpiceDB's
model — answers it by *relationships* (`account owner: user`;
`server account: account`) and *derived permissions*
(`server view = account->admin`), which composes far better than
per-row ACLs or coarse roles. EACL already implements this model in
Clojure, and research note 41 **proved EACL's actual hot-path code
runs on datahike** — the tuple-attr `index-range` traversal, the
cursor, the lazy merge — with only a ~40-line `datomic.api`-compat
shim. So the model and the algorithm are validated; what remained was
a packaging decision.

**Why reimplement, not vendor or depend** (maintainer-confirmed).
Three options were on the table — depend on EACL as a library,
vendor its source behind the compat shim, or reimplement
informed-by-EACL. Depending pulls `com.datomic/peer` transitively
into a datahike companion (EACL's `deps.edn` pins it). Vendoring
raises an EPL-1.0 ↔ EPL-2.0 mixing question (EACL is EPL-2.0; kontor
EPL-1.0 — close kin, far friendlier than the LGPL/GPL cases ADR-001
rules out, but still a call). **Reimplementing** keeps the dependency
story clean (datahike-only, like every other companion), keeps the
licence clean (EPL-1.0 throughout), and lets the schema use
kontor's `:authz/*` namespaces (ADR-002) instead of EACL's
`:eacl.*` — the project's established lift-the-pattern-write-our-own
convention (ADR-001, research note 11). The datahike support is kept
kontor-internal for now; no EACL-upstream engagement.

**The model** (SpiceDB-shaped, ported verbatim in shape):
- a **Relation** is a typed edge *definition* — `(Relation :account
  :owner :user)` ≡ `account { relation owner: user }`.
- a **Permission** is a derived check — a *direct relation*
  (`{:relation :owner}`), an *arrow* through another relation or
  permission (`{:arrow :account :permission :admin}` ≡
  `account->admin`), or a *self* permission (`{:permission :other}`,
  the implicit `:self` arrow). A permission name may have several
  clauses — their union.
- a **Relationship** is an actual *edge instance* — `(Relationship
  (object-ref :user u) :owner (object-ref :account a))`.
`can?` / `lookup-resources` / `lookup-subjects` walk that graph.

**The schema — tuple indices are the whole performance story.**
`:authz.relation/*`, `:authz.permission/*`, `:authz.relationship/*`
component attributes, each backed by composite `:db/tupleAttrs`
index attributes. The two that matter most: `:authz.relationship/
forward` (subject-type, subject, relation, resource-type, resource —
`:db.unique/identity`) and `:authz.relationship/reverse` (the same
five, resource-first — `:db/index true`). Research note 41 proved
datahike auto-maintains these, enforces tuple `:db.unique/identity`,
and `index-range` over a tuple attr with full-arity `:start`/`:end`
bounds returns datoms **ordered by the trailing component** — for
the relationship tuples that trailing component is the
subject/resource ref eid, so the scan yields results in stable eid
order. **That eid order IS the pagination cursor** — no stored
offset, no sort key. The `forward` tuple's `:db.unique/identity` both
dedupes relationships and is the range-scan key; `reverse` only needs
to be indexed (`forward` already enforces uniqueness).

**`:authz/object-id`** — an optional `:db.unique/identity` string
handle for subjects / resources / definitions. A consumer that
exposes authz to an outside system coerces its IDs through it; a
consumer that only ever passes datahike eids ignores it. The
relationship `:subject` / `:resource` are plain `:db.type/ref`s —
they point at *whatever* entity a consumer relates (a `:partner`, an
`:account`, a consumer-defined `:user`), so kontor-authz has **no
kernel-attr dependency** and `install!` may run any time.

**Staged build.** ADR-065 lands the foundation — the `IAuthorization`
protocol (`kontor.authz.core`, pure), the entity-map builders
(`kontor.authz.base`, pure), the schema + `install!`
(`kontor.authz.schema`). The substantial next units: `kontor.authz.
indexed` (the permission-graph traversal — the port of EACL's
`indexed.clj`, ~700 lines: `can?`, `lookup-resources`,
`lookup-subjects`, `count-resources`, the cursor, the lazy
sorted-merge), `kontor.authz.client` (the `Spiceomic`-equivalent
`IAuthorization` reification + id coercion), and `kontor.authz.
spice-parser` (the SpiceDB-schema-string parser — convenience).
The EACL→datahike API gaps are settled (note 41): `index-range`
positional→map, an `entid` helper, `:db/retractEntity` not
`:db.fn/retractEntity`, `:max-tx` for the basis token.

**Known limitation inherited from the model.** Neither EACL nor
kontor-authz detects cycles in the permission *schema* (a permission
that arrows back to itself). The traversal will need a depth/visited
guard; flagged for the `indexed` unit.

**Shape after (this ADR).**
- `deps.edn` / `tests.edn` — `modules/authz` wired.
- `modules/authz/src/kontor/authz/core.clj` — `IAuthorization` +
  `Relationship` / `RelationshipUpdate` / `ObjectRef` + `object-ref`.
- `modules/authz/src/kontor/authz/base.clj` — `Relation` /
  `Permission` / `Relationship` builders + `Cursor`.
- `modules/authz/src/kontor/authz/schema.clj` — the `:authz/*`
  schema + `install!`.
- `modules/authz/test/kontor/authz/schema_test.clj`.

Date: 2026-05-14.

---

## ADR-066 — `kontor-authz`: the permission-graph traversal + the client

**Decision.** The substantial unit of kontor-authz — `can?`,
`lookup-resources`, `lookup-subjects`, `count-resources`, the
relationship-edge CRUD, and the `IAuthorization` client. A faithful,
datahike-native port of EACL's `eacl.datomic.impl.indexed` +
`eacl.lazy-merge-sort` + the relationship half of `eacl.datomic.impl`
+ `eacl.datomic.core` (research note 41 proved the algorithm runs on
datahike; ADR-065 ported the model + schema; this ADR ports the
engine).

**How the traversal works.** Two walks:
1. **schema walk** — `get-permission-paths` reads the
   `:authz.relation/*` + `:authz.permission/*` *definitions* and
   builds a tree of **paths**: every way `permission` on
   `resource-type` can be granted (a direct relation, an arrow
   through another relation or permission, a self-permission).
   Cheap — schema is sparse.
2. **data walk** — the `traverse-*` fns walk the
   `:authz.relationship/*` *edges* along those paths via
   `index-range` scans over the `forward` / `reverse` tuple indices.
   Each scan is already sorted ascending by the trailing ref eid
   (note 41); `kontor.authz.merge-sort` merges the parallel paths'
   scans into one sorted, deduplicated lazy seq — parallel paths can
   reach the same resource, and the dedup keeps the cursor correct.
That eid order **is** the pagination cursor: `lookup-resources`
resumes a scan at `[… cursor-eid]`. `can?` short-circuits on the
first granting path; `lookup-*` enumerate.

**Datahike adaptations from the EACL source** (all settled in note
41): `d/index-range` is positional in Datomic, map-arg
(`{:attrid :start :end}`) in datahike — a one-line `idx-range` helper
bridges every call site; `d/entid` does not exist — an `entid`
helper resolves eid / lookup-ref via `d/entity`; the relationship
`:delete` emits `[:db/retractEntity …]`, not Datomic's
`[:db.fn/retractEntity …]`; `d/transact` returns the report directly
(no deref); the basis token is `(:max-tx db)`, not `d/basis-t`.

**What was dropped from the EACL source.** Two dead functions
(`traverse-single-path` — only self-recursive, never called; and
`direct-match-datoms-in-relationship-index` — never called); the
`clojure.tools.logging` dependency (the `log/warn` diagnostics for
missing schema defs — the functions still return `[]` on a missing
def, a soft failure); and the `clojure.core.cache` dependency (the
LRU `permission-paths-cache`). The path cache is a **deferred perf
optimisation**, not correctness — `get-permission-paths` calls
`calc-permission-paths` directly; a plain `atom`-memoize (no new
dep) can be re-added if profiling demands it. **Net: kontor-authz
adds zero dependencies — datahike only**, like every other companion.

**The client.** `kontor.authz.client/make-client` wraps a datahike
`conn` in an `AuthzClient` reifying `IAuthorization`. Its job is **id
coercion + dispatch**: the traversal + the edge CRUD speak datahike
eids; a consumer speaks whatever external id it chose
(`:authz/object-id` strings by default, or raw eids — configurable
via `:entity->object-id` / `:object-id->ident`). The client coerces
subjects, resources, cursors, and relationship filters at the
boundary. `read-schema` / `write-schema!` / `expand-permission-tree`
throw "ADR-066-deferred" — `write-schema!` wants the
SpiceDB-schema-string parser (`kontor.authz.spice-parser`), a later
convenience unit.

**Known limitation (inherited from the EACL model, flagged in
ADR-065).** `calc-permission-paths` and `traverse-permission-path`
carry visited-sets for cycle detection, but
`traverse-permission-path-reverse`'s `:self-permission` branch does
not — a cyclic permission *schema* could loop `lookup-subjects`.
kontor's authz schemas are authored, not user-generated, so this is
low-risk; flagged for the kontor-authz review-after.

**Shape after.**
- `modules/authz/src/kontor/authz/merge-sort.clj` — the lazy
  sorted-merge-with-dedup (`lazy-fold2-merge-dedupe-sorted-by`).
- `modules/authz/src/kontor/authz/indexed.clj` — the traversal:
  `get-permission-paths`, `can?`, `lookup-resources`,
  `lookup-subjects`, `count-resources`, the `traverse-*` fns.
- `modules/authz/src/kontor/authz/relationships.clj` — the
  relationship-edge CRUD (`read-relationships`,
  `tx-update-relationship`, `find-one-relationship-id`).
- `modules/authz/src/kontor/authz/client.clj` — `make-client` +
  the `AuthzClient` `IAuthorization` reification.
- `modules/authz/test/kontor/authz/indexed_test.clj`.

This + ADR-065 are the kontor-authz companion; the SpiceDB-string
parser is the one deferred convenience.

**Review-after (research note 43).** Two independent agents audited
ADR-065/066. The traversal port is faithful and correct (REPL-
confirmed across all four path types, the cursor, multi-clause
dedup). **One P0 + three P1s, all REPL-confirmed, all fixed:** (1)
P0 — `read-relationships` silently *over-returned* every edge when
an external id did not resolve (the coerced `nil` dropped the
datalog filter); `coerce-id-filter` now throws on an unresolvable
id. (2) P1 — the permission-schema cycle guard was *narrower than
this ADR documented*: `can?`'s `:self-permission` branch also
recursed unguarded and `StackOverflow`-ed on a cyclic-schema typo;
`can?` and `traverse-permission-path-reverse` now thread visited-sets
like `traverse-permission-path` already did. (3) P1 — the `:a`/`:z`
keyword-sentinel scan in `relation-datoms` *silently dropped*
out-of-range subject-types (`:zebra`, uppercase `:Account`,
digit-leading), yielding a wrong `false`; `base/Relation` now throws
at definition time. Cheap P2s also fixed: the triplicated `entid`
→ `kontor.authz.util`, the dead `Cursor` record dropped, a missing
nil-guard added, the in-batch `:create`-duplicate semantics
documented.

**Notes the completeness review surfaced, recorded here as ADR
intent:** the **kernel does NOT call authz** — it stays
dependency-free; authorization is a *consumer* concern (a consumer
decides whether to gate `post-transaction!` on a `can?`). Consumers
**own their subject entity** — kontor ships no `:user`; a consumer
(beleg) defines its own. A **kontor-native consumer should use the
raw-eid client** (`{:object-id->ident identity :entity->object-id
:db/id}`) — the `:authz/object-id` default mode is for consumers
that expose authz to an external system. `object-ref`'s 3-arity
(the subject-relation / userset) is **reserved** — no engine behind
it yet; the `consistency` arg + `:authz/token` are accepted for
EACL API-shape compatibility and are **no-ops** on single-DB
datahike. The permission model is **union-only** (no `−`/`&`) — a
known limitation, not just a parser deferral.

**Deferred to a kontor-authz followup** (the consumer-readiness
unit): a non-string `write-schema!` arity + a real `read-schema`;
`write-schema!`-time schema validation (reject permissions
referencing undefined relations, reject schema cycles); an
end-to-end integration test wiring authz to real kontor entities;
the SpiceDB-string parser.

Date: 2026-05-14.

**Consumer-readiness followup landed (2026-05-15).** The non-string
`write-schema!` arity + a real `read-schema` are now wired into the
`AuthzClient` (replacing the two `ADR-066-deferred` `ex-info`
throws). Shape:

- `kontor.authz.schema/write-schema-tx-data db schema-defs` — pure
  builder (ADR-068) returning a tx-data vector. Takes a sequence of
  `Relation` + `Permission` entity maps (the existing
  `kontor.authz.base/Relation` and `Permission` defns produce them).
  Validates structurally before returning; **throws**
  `:authz/schema-invalid` on a Permission whose `:arrow` is not a
  defined Relation, or whose `{:relation r}`/`{:permission p}`
  target does not resolve on the target type.
- `kontor.authz.schema/write-schema! conn schema-defs` — standalone
  wrapper using raw `d/transact`. The carve-out from ADR-068
  applies: authz commonly runs on a minimal datahike conn without
  the kernel schema, so the wrapper does not route through
  `kontor.validation/transact-with-validation`. Composers on a
  kernel+authz conn drop `write-schema-tx-data` into a
  `kontor.process` step instead.
- `kontor.authz.schema/read-schema db` — reads `:authz.*` datoms
  back as `{:relations [Relation …] :permissions [Permission …]}`.
  Round-trips through `write-schema!` modulo `:db/id`s; idempotent
  on re-write (tuple `:db.unique/identity`).
- `kontor.authz.client/AuthzClient.write-schema!` and `read-schema`
  delegate to the two functions above. Tests in
  `modules/authz/test/kontor/authz/consumer_readiness_test.clj`
  (7 tests, 21 assertions).

**Still deferred** (not blocking consumer adoption): cycle detection
on a Permission graph (the runtime `:visited` set in `can?` /
`traverse-permission-path-*` already catches cyclic schemas at
evaluation time, just not at write-time); the SpiceDB-schema-string
parser (`kontor.authz.spice-parser`); an end-to-end integration
test wiring authz into a real kontor `:invoice` flow (covered
structurally by the cross-module composition test in
`test/kontor/composition_test.clj`).

## ADR-067 — `kontor.process`: multi-step transactional processes as pure step-lists

**Decision.** Ship `src/kontor/process.clj` — a kernel facility that
runs a *process* (a sequence of pure **step** fns) as **one atomic,
validated transaction**. A step is `(db, ctx) -> result` where `db`
is the speculative db reflecting every prior step's tx-data, and
`result` is `nil` | a tx-data vector | `{:tx-data … :ctx … :steps …}`.
`run-steps` threads the steps against a single start-snapshot,
accumulating one tx-data vector; `{:steps …}` returns **splice in**
(the monadic flatten — a step may return more steps). `run-process`
applies one outer `with-vt`, then commits the assembled tx-data
through the existing `kontor.validation/transact-with-validation`
gate. It **serializes on `conn`**. The multi-`d/transact`
orchestrators across the kernel and companions (`commence!`,
`run-depreciation!`, `run-lease!`, the `modification.clj`
transactors, `allocate-fifo!`, the inventory flows,
`close-fiscal-year!`) migrate onto it — one atomic, gated commit
instead of N unguarded ones. Research notes **42** (state/transaction
analysis), **44** (transaction-composition prior art), **45**
(read-set tracking feasibility) and **46** (the whole-tree
implementation survey) are the research-before.

**Why a process facility at all.** The companions grew ~110
`d/transact` sites; the orchestrators among them call
sub-transactors that *each* `d/transact` (note 46, problem A) — so a
half-completed `commence!` or `run-lease!` leaves the ledger in an
intermediate state, and the sub-transactor writes skip the kernel's
`validate-and-apply` gate entirely (problem F). A process collapses
the orchestrator to one transaction: atomic by construction, gated
once, and the sub-transactors stop being "called" — they become
pure step-lists that splice in. Notes 42/44 framed the model; note
46 confirmed it is sound across every transactor in the tree (sagas
and impure steps — problems C and D — simply do not exist here; B,
the reentrant-`d/transact` deadlock, is a *consequence* of A and
disappears with the leaf split).

**Why assemble outside the writer, not a `:db.fn/call` chain.** The
explored alternative — `run-process` emits `[:db.fn/call run-steps
…]` and the chain emits its own follow-up `:db.fn/call`s inside the
transactor (the "monadic flattening *as a transactor mechanism*"
sketch) — was **rejected for v1** for three concrete reasons. (1) It
cannot reuse the kernel's validation gate: `transact-with-validation`
runs the datalog-invariant pass (`invariant.datahike/assert-
invariants`) which needs `conn` + the *complete* tx-data from
*outside* `d/transact` — inside the chain the complete tx-data does
not exist until the transactor has already started applying it. The
chain would have to reimplement validation in the transactor and
either skip the datalog invariants or grow a new invariant-library
arity. (2) It runs the step *reads* inside the single writer —
note 46's expensive-read concern (`closing/close-period!`). (3)
`:dry-run?` would need a separate code path. Assembling outside via
`d/db-with`-threading reuses `transact-with-validation` wholesale,
keeps the (sometimes heavy) step reads off the writer, and makes
`:dry-run?` the *same* path minus the commit. The monadic
programming model the maintainer wanted is **fully preserved** — a
step may return `{:steps …}` and they splice in, sub-transactors are
step-lists — only the *bind mechanism* is `d/db-with`-threading
rather than `:db.fn/call`-emits-`:db.fn/call`. The transactor-chain
variant is recorded here as a deliberate future option, the natural
home for a *long* process one wants to run concurrently with other
writers.

**Why serialize.** Note 45's finding: cheap *precise* auto-capture
of read-sets does not exist off-the-shelf for datahike (the query
engine bypasses any wrappable seam; an upstream hook is the clean
path, deferred). kontor's processes are short and its domain is
serial by nature (the ledger is a total order), so `run-process`
takes a lock on `conn` — the `(d/db conn)` snapshot, the step
threading, and the commit are atomic w.r.t. other `run-process`
calls. That is the structural guarantee against the
snapshot-vs-commit race, with zero read-set bookkeeping.
datahike's built-in `:db.fn/cas` remains the lock-free single-datom
escape hatch; a hand-declared apply-time `:expect` predicate and
auto-captured read-sets are documented in note 45 as deferred, not
rejected.

**`run-process` owns valid-time.** Kernel builders
(`build-transaction`, the posting builders) already embed `with-vt`,
and a transaction may carry only one `{:db/id "datomic.tx"}` map
(note 46, problem E). So every step fragment is `strip-tx-meta`'d as
it accumulates (`kontor.bitemporal/strip-tx-meta` made public for
this) and `run-process` applies one outer `with-vt` for the whole
process. Steps must not emit tx-meta — a rogue `{:db/id
"datomic.tx"}` map is silently stripped.

**The cross-step identity rule.** Steps reference cross-step
entities by **string tempid**, never by an eid read off the
speculative db. The speculative db resolves tempids so a later step
*reads consistently*, but the final commit re-resolves them — an eid
captured off the speculative db is an artifact. The speculative db
is for reading *committed* data and prior-step *facts* ("has a book
been opened?"); *identity* threads via string tempids, which resolve
consistently across all fragments in the one final transaction.
`run-steps`' speculative db is `(d/db-with db0 acc)` over the *whole*
accumulated tx-data each step — faithful tempid resolution at
O(steps²) `d/db-with` calls, which is negligible for kontor's short
(O(periods)) processes.

**Datahike-level purity.** `run-steps` is the reusable engine and
touches only `d/db-with` + `strip-tx-meta` + ctx threading;
`run-process`'s commit fn is injectable (`:commit`, defaulting to
`transact-with-validation`). The facility could move upstream to
datahike later (alongside the read-set hook of note 45 / task #75)
with the kontor-specific gate left behind as the default injection.

**Migration.** Per note 46: ship the facility (this ADR), then
extract the ~10 leaf transactors into pure `*-tx-data` builders as
one coherent commit, then migrate the orchestrators —
`commence!` first as the proof-of-concept — deleting the
now-redundant mitigations (`:fired-before-violation`,
`assert-modifiable!`, the `run-lease!` lockstep guard, the
`commence!` re-read) as each lands.

**Composable tempids — addendum from the `commence!` migration.**
A builder used in a multi-entity / multi-call process step that
hardcodes a tempid (`{:db/id "asset-dep-book"}`, `{:db/id -1}`)
will *collide* with itself when N outputs accumulate into one
tx-data — datahike merges entities sharing a tempid. Leaf builders
that an orchestrator calls multiple times in one process therefore
need a knob to namespace their tempids. The convention this ADR
settles on:

  - **`:tempid-suffix`** — a string appended to every internal
    tempid the builder generates. `kontor.asset.depreciation/
    open-book-tx-data`, `kontor.lease.liability/open-liability-
    book-tx-data` carry this. Default `""` preserves the
    standalone-`!` behavior.
  - **`:tempid`** — explicit tempid for a builder's *one* primary
    entity, when a later step must reference it.
    `kontor.asset.asset/acquire-tx-data` carries this; `commence!`
    threads its ROU asset to the dep-book step via
    `:tempid "rou-asset"` + `:asset-tempid "rou-asset"`.
  - **`:asset-tempid`** (and analogues) — a builder that normally
    *resolves* a parent ref by code/eid (`open-book-tx-data`
    resolves `:asset`) accepts a passthrough alternative for when
    the parent is created by an earlier step. In passthrough mode
    the builder skips resolution + the parent pull, so the
    asset-derived defaults must be passed explicitly.
  - **`:tx-tempid`** (kernel-wide) — `kontor.posting/build-
    transaction` accepts an optional top-level `:tx-tempid` string.
    When given, the transaction gets that `:db/id` and postings
    derive as `"<:tx-tempid>-pN"`. Required when an orchestrator
    posts several entries from one process (commence!, the
    modification transactors, run-lease!, …); the default `-1`
    preserves the original behavior. The lease `plan-lease-*` /
    `plan-adjustment` builders thread `:tx-tempid` through.

These knobs are the v1 cost of "pure builders compose into one
atomic tx-data." They are small, local, and backward-compatible
(defaults preserve standalone-`!` behavior). The deeper
generalization — `run-steps` auto-namespacing tempids per step —
was rejected because it cannot distinguish a tempid's *definition*
(which should be renamed) from a *cross-step reference* (which
must not), so the namespacing belongs in the builders, where the
distinction is local and explicit.

Date: 2026-05-14.

## ADR-068 — every business write exposes a `*-tx-data` builder

**Decision.** ADR-067 introduced `kontor.process` for the
*orchestrators*. ADR-068 generalizes the same pattern to *every*
business-write transactor across the kernel and the companions —
every `defn xxx! [conn opts]` that does `d/transact` splits into:

  1. **`xxx-tx-data [db opts]`** — a pure function returning the
     tx-data vector (no `d/transact`, no `kbt/with-vt`). All
     entity-map construction, all validations that need only `db` +
     `opts`, live here. Where multiple outputs of the same builder
     can compose into one process tx-data, the builder accepts a
     `:tempid-suffix` (or analogue per ADR-067's addendum) so the
     internal tempids stay collision-free.
  2. **`xxx! [conn opts]`** — a thin standalone wrapper that calls
     the builder, wraps with `kbt/with-vt` (defaults per the
     transactor's existing convention), and commits through
     **`kontor.validation/transact-with-validation`** — i.e. the
     same gate the orchestrators route through (`legal-hold` /
     `sealing` / `period` / `state-machine` for transactions /
     `sum-to-zero` / datalog invariants).

This is the universal rule: **any business write to the kernel or
to a companion goes through the gate, and every business write is
expressible as a tx-data builder that composes into a
`kontor.process`.**

**Why universal, not just orchestrators.** Three concrete payoffs:

  - **Cross-module atomic composition.** A consumer (beleg, simmis,
    a future ERP shell) wants "create the invoice AND grant the
    buyer read access AND record the audit-doc upload" as ONE
    atomic event. ADR-067's process facility expresses it as a
    sequence of step fns each calling a builder; under ADR-068 the
    builders are universally available and the composition is
    trivial.
  - **Uniform gate routing.** Today a defensive subset of writes
    (invoice posting, payment application's invoice-status drive,
    legal-hold placements, modifications) skip the gate — the
    validators only run when the caller specifically routes
    through `transact-with-validation` or `post-transaction!`. Per-
    file inspection of which writes are gated is a maintenance
    burden; "every `!` is gated" is the rule that closes it.
  - **One audit boundary.** Per ADR-007 every commit is the audit
    chain; the gate is what makes "commit ⇒ validated commit" a
    structural invariant. Universal gate routing removes the
    "raw `d/transact` bypass" footgun without adding code paths.

**The `with-vt` discipline.** Where the wrapper sets `:vt-from`, it
follows the transactor's existing convention (e.g. `place-hold!`
defaults to `:placed-at`; `acquire!` defaults to
`:acquisition-date`; new entities default to a meaningful
event-time, not `now`). The builder DOES NOT embed `with-vt` — that
is the wrapper's job (so a `kontor.process` step can `strip-tx-meta`
the builder's output and apply one outer `with-vt` per process).
This is the same rule ADR-067 set for the orchestrators; ADR-068
makes it universal.

**Documented exception — `kontor.posting/build-transaction`** embeds
its own `kbt/with-vt` mapping `:transaction/effective-date →
:tx/valid-from` (since the kernel's per-transaction valid-time is
always the effective-date when nothing else is specified). Builders
that consume `build-transaction` (most posting orchestrators and the
GL bridges) therefore emit fragments that already carry tx-meta;
`kontor.process/run-steps` strips those via `strip-tx-meta`, and the
process's outer `with-vt` wins, so composition works correctly. The
exception is local to `build-transaction` and does not invalidate
the universal rule for every other builder. (Originally surfaced as
note 48 P1-5.)

**Documented carve-outs from "every `!` routes through the gate":**

  - **`kontor.authz` standalone path** — `kontor.authz.client/do-
    write-relationships!` stays on raw `d/transact`. The authz
    module is designed to run on its own minimal datahike conn
    *without* the kernel schema; kernel-gate routing would crash on
    missing kernel attrs. Composing authz writes with kernel writes
    uses `kontor.authz.client/write-relationships-tx-data` (the pure
    builder, public) inside a `kontor.process` step on a conn that
    has BOTH schemas installed — the consumer's process gates the
    combined tx-data.
  - **`kontor.period/close!`'s `:period/lock-tx` denorm** — a
    second raw `d/transact` records the gate's own tx-id as a
    queryable backref. `:db/current-tx` doesn't resolve as a
    `:db.type/long` *value* in datahike (only as a `:db/id`
    tempid), so the lock-tx denorm cannot ride inside the gated
    close tx itself. `close-fiscal-year!` consequently is not yet a
    `run-process` (it would chain through the lock-tx denorm
    write). A future revision could either generalize via a
    `:db/current-tx`-self-ref in the gated tx (requires datahike
    work — see task #75) or formalize the lock-tx denorm as a
    permanent bootstrap-class exception.

**Per-period replayers (`run-depreciation!` / `run-lease!`) deliberately
preserve the partial-failure mitigations** (`:fired-before-violation`
enrichment + `run-lease!`'s lockstep guard at
`modules/lease/src/kontor/lease/runner.clj`:392-396) instead of
deleting them as note 46 originally sketched. The trade-off: the
hybrid per-period processes keep per-period `:tx/valid-from` (so
bitemporal restated-history queries work across catch-ups) at the
cost of cross-period non-atomicity. Both reviews (notes 48 + 49)
flagged this; the mitigations stay because the bitemporal benefit
is the reason for the hybrid in the first place.

**Scope of "business write."** This ADR governs:

  - Every `defn` ending in `!` that does `d/transact` in `src/` or
    `modules/*/src/`, *except* the bootstrap layer (schema
    installation in `*/schema.clj`, l10n chart-of-account
    `define-chart!` seeders, `core.clj`'s test-db setup, the
    invariant rule installer in `validation.clj`). The bootstrap
    layer is one-time setup, not a business write — it does not
    benefit from gating and the gate is not yet installed when
    schema runs.
  - The pure builders the bootstrap layer relies on (e.g.
    `posting/build-transaction`, `record-status-change-tx-data`)
    stay unchanged — they are already builders.

**Test-suite implication.** Tests that today do raw `d/transact`
of business tx-data are encouraged to either (a) use the new `!`
wrappers (then the gate runs, matching production) or (b) compose
several builders' tx-data into a single `run-process` (the
headline "compositional write" pattern). Test FIXTURES that seed
schemas, accounts, journals — bootstrap data — stay on raw
`d/transact`. The litmus test: would a real consumer write this
through a business API? If yes, use a builder + the gate.

**Implementation.** Stage P. Sweep the ~40 source files with
business-write `d/transact` calls, cluster by ownership
(kernel → asset/lease/inventory leaves → companions → authz),
mechanical per-file. One coherent integration test in
`test/kontor/composition_test.clj` proves the cross-module
composition story — typically invoice creation + an authz grant +
an audit-doc link as a single `run-process`.

**Open at write-time, deliberately:** whether the `kontor.process`
facility itself moves upstream to datahike (task #75 territory —
deferred). ADR-068's surface area in the kontor codebase is
independent of that decision; the builders compose under any
implementation of the facility.

Date: 2026-05-14.

## ADR-069 — `kontor-lease`: mid-life portfolio import via `import-lease!`

**Decision.** Ship `kontor.lease.runner/import-lease!` for the
mid-life onboarding case: a lease whose contractual commencement is
in the *past* (it's already mid-term in a prior system) is brought
into kontor by carrying forward the prior system's
balance-sheet amounts rather than re-computing them from scratch
the way `commence!` does. The transactor sets up the new system's
records — the ROU `:asset`, the per-ledger `:lease-liability` book
+ ROU `:asset-depreciation` book + their schedules — and drives
`:lease/status :draft → :active` with `:reason :lease-imported`.

**Why the followup matters.** Note 40 ("kontor-lease review-after",
2026-05-14) flagged this as the highest-real-world-severity gap
in the kontor-lease v1: every adopting customer has a
**portfolio** of mid-life leases to onboard from their prior
system on the day kontor goes live; without `import-lease!` the
only options are (a) re-execute `commence!` and accept
recomputed PVs that disagree with the prior books, or (b)
bypass the gate and write raw `:lease-liability` + `:asset-
depreciation` rows, losing the validation guarantees. The first
is incorrect; the second is unsafe.

**No day-one GL entry.** Unlike `commence!`, `import-lease!` does
**not** post a `Dr ROU / Cr lease-liability` recognition journal.
The new system's BS is established by the **consumer's import-day
balance-sheet bridge journal** (one journal that records the
gross ROU + accumulated-amortisation + lease-liability + plug
movements against the prior-system clearing/RE accounts). The
bridge has to balance to whatever the prior system's books said;
forcing `import-lease!` to also emit a GL entry would create a
double-count. The trade-off is documented: the consumer owns the
bridge, kontor owns the schedules-and-status from that point on.

**Re-anchoring vs preservation.** The `:lease/commencement-date`
and `:lease/term-months` re-anchor to the import: they describe
the new system's recognition (the date the schedules start, the
remaining term). The contractual history is **preserved** in the
audit denorms `:lease/imported?` (true), `:lease/imported-as-of`,
`:lease/imported-original-commencement-date`, and
`:lease/imported-original-term-months`. Future-you can always
reconstruct the *contract*-original recognition; future-you should
not need to.

Rationale: the schedules (and the `LeaseProvider` unwind that reads
them) are simplest when the date-arithmetic starts at the
re-anchored commencement. The alternative — keeping
`:commencement-date` = original and threading
`:opening-fired-through` through every period read — would either
(a) require synthetic pre-import `:schedule-occurrence` rows
(which `:schedule-occurrence/transaction` schema-requires a
`:transaction` ref that does not exist for prior-system periods)
or (b) require modifying `book-plan-inputs` and every
`pending-occurrences` caller to filter on `opening-fired-through`.
The re-anchor preserves the audit trail (in denorms) at the cost
of one denorm read; the alternatives mutate more code paths.

**`define-lease!` gained four audit-denorm opts** —
`:imported?`, `:imported-as-of`,
`:imported-original-commencement-date`,
`:imported-original-term-months`. `import-lease!` validates all
four are present (and `:imported?` is true) before it runs;
calling `commence!` on an imported lease is fine (it just doesn't
read the denorms), but most consumers will not need this combo.

**Reporting scalar `:opening-accumulated`.** The `import-lease!`
book spec accepts `:pre-import-accumulated` and threads it to the
ROU `:asset-depreciation/opening-accumulated` field that ADR-054
already supports for mid-life imports. This is a **reporting
scalar** — `accumulated-depreciation` reads it as a starting
balance, and the dep schedule fires forward from the carried
NBV (`:remaining-rou-base`) for the remaining useful life. It is
distinct from the GL `:asset/accumulated-account` movement,
which the consumer posts in the bridge journal.

**Atomicity.** `import-lease!` runs as ONE atomic `kontor.process`
(ADR-067): the ROU `:asset`, the N per-ledger books + schedules,
and the `:draft → :active` status change all commit through
`transact-with-validation`. Period / sealing / sum-to-zero /
state-machine / invariants run in the gate; a partial failure
rolls back the whole import.

**Tests.** `modules/lease/test/kontor/lease/runner_test.clj` —
3 new tests (`import-lease-onboards-a-mid-life-lease`,
`import-lease-rejects-a-non-imported-lease`,
`imported-lease-runs-the-remaining-tail`). The third exercises
`run-lease!` on an imported tail and verifies the schedule fires
the remaining periods and auto-expires correctly.

**Still deferred** (from note 40's "smaller items" checklist —
rolled into the disclosure-support followup #124): the
discount-rate justification audit-doc ref on
`:lease-liability/discount-rate`; persisting the remeasurement
deltas on `:lease-modification`; per-`(lease, ledger)` index-reset
fork (ASC 842 expense vs IFRS 16 remeasurement); stepped-rent /
rent-free cash-flow profiles; the second ASC 842 partial-
termination method (16.46(a)); an FX retranslation transactor.

Date: 2026-05-15.

## ADR-070 — `kontor-lease`: disclosure-support deltas + discount-rate audit-doc

**Decision.** Land three small but high-leverage schema additions
that close the IFRS 16 / ASC 842 disclosure-roll-forward gap
flagged by note 40 §2 + §4:

1. **`:lease-modification/{liability-delta,rou-delta,pnl-delta}`** —
   each a `:db.type/bigdec` carrying the *aggregated* movement the
   modification caused across all affected per-(lease,ledger)
   books. `remeasure!`, `partial-terminate!`, `terminate!`,
   `purchase!` all compute these aggregations from their per-book
   plan and persist them on the `:lease-modification` event.
2. **`:lease-liability/rate-rationale`** — `:db.type/ref` to
   `:audit-doc`, threaded through both `commence!` and
   `import-lease!` per-book specs as the optional opt
   `:rate-rationale`.

**Why deltas matter.** Under IFRS 16 a lessee discloses a
**lease-liability roll-forward** (opening balance → interest →
payments → remeasurements → closing balance) plus a parallel
**ROU asset roll-forward**. Without the deltas, a consumer has to
JOIN every `:lease-modification` to its `:transaction(s)` to its
`:posting`s and reduce per-account to recover the movement — a
multi-source, expense-account-by-account query that is brittle
under restatement. Persisting the deltas reduces the disclosure to
a single `(d/q '[:find …] :where [?m :lease-modification/liability-
delta ?d])` aggregation, and the data is **immediately bitemporally
queryable** (a 2026-restatement of a 2025 modification picks up
the corrected delta automatically).

**Why a rate-rationale audit-doc.** ASC 842-20-30-3 and
IFRS 16 §27 BOTH require the lessee to use the rate implicit in
the lease where determinable; if it is not, the incremental
borrowing rate (IBR). The IBR is a **judgment call** —
typically supported by a treasury memo or appraiser report —
and is the first thing an auditor asks about under the assertion
that the lease liability is fairly measured. Carrying the
justification ref on the book itself (rather than on the lease)
means a per-(lease, ledger) audit trail: an IFRS book and an ASC
842 book on the SAME lease may use different rationales (each
book's own discount-rate already differs per ADR-063), and the
trail captures that. Optional but encouraged — the schema does
not enforce presence, mirroring `:asset/origin-document` (always
encouraged, not always required by every transactor).

**Sign convention for the deltas.**

| transactor | `:liability-delta` | `:rou-delta` | `:pnl-delta` |
|---|---|---|---|
| `remeasure!` | new-PV − old-outstanding (signed) | same as liability-delta (BS-only) | 0 in the common case |
| `partial-terminate!` | new-PV − old-outstanding (signed) | proportional ROU write-off + remeasurement adj | rou-delta − liability-delta (the IFRS 16.46(b) gain/loss) |
| `terminate!` | −Σ outstanding | −Σ ROU-carrying | (liability-removed − cash-paid) − ROU-removed |
| `purchase!` | −Σ outstanding | 0 (ROU continues per IFRS 16.67) | liability-settled − cash-paid |

**What we did NOT do in this ADR.** The other items on note 40's
"smaller items" checklist remain deferred: the per-(lease,
ledger) index-reset fork (ASC 842 wants period expense, IFRS 16
wants full remeasurement — currently `remeasure!` loops over all
books identically); stepped-rent / rent-free cash-flow profiles
(`:lease/payment-amount` is still a single scalar); ASC 842's
second partial-termination method (16.46(a) — the
remeasurement-only path, distinct from 46(b)'s proportional
approach); an FX retranslation transactor (the builder exists,
the orchestrator does not). Each of these is a substantive
follow-up that ought to ship with its own ADR and dedicated
tests; this ADR ships the bookkeeping-only pieces that the
disclosure-shaped consumer needs immediately.

**Tests.**
`modules/lease/test/kontor/lease/modification_test.clj` —
3 new tests: `remeasure-persists-liability-and-rou-deltas`,
`terminate-persists-derecognition-deltas`,
`rate-rationale-audit-doc-is-persisted-on-the-liability-book`.

Date: 2026-05-15.

---

## ADR-071 — Tax abstraction: `TaxRateProvider` + `TaxFacts` + `TaxPostingBuilder`

**Status.** Substrate **implemented** 2026-05-20 (research note 99 Stage 2) — see the Addendum at the end of this ADR. The protocol trio + `StaticTableProvider` ship; per-l10n migration of the 11 country modules remains consumer-demand-driven, as this ADR's "Implication" always stated. (Originally drafted 2026-05-17 as a decided-but-unimplemented ADR; `tax_rate_provider.clj` had never existed in git until note 99 Stage 2.)

**Decision.** Replace the dead `kontor.tax-provider/TaxProvider` (ADR-005, superseded) with a three-protocol-plus-data-shape design:

1. **`TaxRateProvider`** — determines rates. Pure: given a transaction context (line items, partner, jurisdiction, date, `:tax-use`), return a vector of `TaxFacts` — one per line that is subject to tax. Implementations: `StaticTableProvider` (DE/CA/CN-shaped), `AvalaraProvider` / `TaxJarProvider` (scaffolds, customer keys), `SstCsvProvider` (24 US SST states), and per-l10n providers that wrap the static `:tax/*` schema.
2. **`TaxFacts`** — pure data, the inter-protocol contract. Carries the line-base + commodity, jurisdiction (country + subdivision + place-of-supply), per-component rate items with `:component/kind` enum (`:output-vat | :input-vat | :sales-tax | :reverse-charge | :withholding | :pre-collection | :surcharge | :cess | :duty | :fee`), per-component `:provenance` (provider-id, rate-source, statute citation when available), and a `:jurisdiction-specific-codes` opaque slot (e.g., `{:br/icms-cst "60"}`) that downstream emitters consume.
3. **`TaxPostingBuilder`** — materializes GL postings from a `TaxFacts`. Per-country (SKR04, NCM-CST, GSTN account routing live here). The existing l10n `posting-builder` functions are ~90% of the impl; the refactor splits them into rate→facts + facts→postings with a thin adapter.

Two existing components stay in place, orthogonal:
- **Tax reporting / aggregation** is already done by `kontor.report` with `:engine :tax-tags`. No `defprotocol` needed; the tag-on-account model works.
- **Clearance / attestation** (NF-e, IRN, e-fapiao, CFDI) is already factored out via `:attestation/*` (ADR-018, ADR-024). It consumes `TaxFacts` on the way to per-country clearance envelopes — refactor enables sharing what each currently re-derives.

**Composition.** A new `kontor.tax-pipeline` namespace wires `TaxRateProvider` → `TaxFacts` → `TaxPostingBuilder`. It depends on both protocols; the protocols themselves stay pure-data. Existing `kontor.document.invoice/send!` calls a thin adapter that preserves today's `(send! conn invoice-eid posting-builder)` shape.

**Audit / freezing.** A `:posting/tax-fact-id` ref points to a separate `:tax-fact/*` entity with the full `TaxFacts` snapshot at posting time. Purchased rates (what Avalara returned for *this* transaction) are stored alongside the postings rather than re-derived. Aligns with how `:attestation/*` handles clearance evidence.

**Reverse charge: explicit asymmetry.** `:component/kind :reverse-charge` MEANS DIFFERENT THINGS based on `:tax-use`. Seller-side (`:sale`): reporting-tag-only marker; no postings beyond AR/revenue. Buyer-side (`:purchase`): materializes two postings (input-VAT receivable + output-VAT payable, both-sides pattern). The protocol docstring documents this; the posting-builder contract enforces it via per-`:tax-use` dispatch.

**Withholding** (TDS, retención, US backup-withholding) stays in `TaxRateProvider` with `:tax-use :purchase` returning input-side facts + the withholding the buyer is obligated to perform. A separate `WithholdingProvider` was considered (Q5 in research note 70) but rejected as premature; the `:tax-use` discriminator covers the asymmetry. Revisit if buyer-side withholding workflows grow seller-side rate-provider can't naturally express.

**Multi-jurisdiction transactions.** A `TaxFacts` carries one `:jurisdiction` at the line level, but per-component `:jurisdiction {:authority :subdivision}` slots let one TaxFacts route components to different authorities. Necessary for US (origin / destination / intermediate) and BR (DIFAL across origin and destination states).

**Cross-line rounding.** `:rounding-strategy` opt on the posting-builder construction. EU rules permit per-document reconciliation but don't require it; US is generally per-line. Kernel allows but never requires the reconciliation pass. Per-country builders choose.

**Effective-dated rates.** Schema partially supports `:tax/effective-from`/`-until` (`schema.clj:2809-2818`); ADR-026 referenced this for IN. Static-table provider must respect these; follow-up: confirm + complete the schema attrs if gaps.

**Test discipline.** Per-country golden-fixture tests (`test/kontor/l10n_*/posting_builder_test.clj`). Round-trip on totals (sum of generated postings = expected); account-routing (SKR04 vs NCM-CST) tested via fixtures, not derivable from TaxFacts alone. Per ADR-037 rhythm.

**Alignment with `kontor.einvoice-provider`.** Both protocols are output-side per-country contracts. The relationship: e-invoicing consumes `TaxFacts` (per-component `:jurisdiction-specific-codes` is the slot) on the way to the clearance envelope. The two providers may share a country's data feed (e.g., BR `MvaProvider` at `l10n-br/taxes.clj:328-345` is precedent). Full integration deferred; flag here for future.

**Why.** The current `TaxProvider` single-protocol design conflated rate-determination with posting-expansion, with the consequence that the abstraction was unused (l10n modules each hardcoded their own pattern). The three-protocol split separates concerns by consumer (rate providers don't need chart-of-accounts; posting builders don't need rate-lookup tables). The `TaxFacts` data shape carries enough structure (BR cascade, IN component-split, MX retenciones, AU GST, DE reverse-charge) without leaking jurisdictional logic into the kernel.

**Implication.** Per-country migration cost ranges from AU +120 LoC (trivial single-rate) through US +1500 LoC (Avalara/SST/nexus needs real work). Multi-week multi-module project; pilot per-module as consumer demand drives. The DE posting-builder is the natural first port (smallest refactor — already has the logic, just splits the function).

**Implication 2.** Existing `:tax/* :tax-rep/* :tax-group/*` schema (`schema.clj:1244-1393`) is preserved unchanged — backs the static-table impl of `TaxRateProvider`. The `kontor.tax-provider/TaxProvider` defrecord scaffolds (the old `StaticTableProvider`, `AvalaraProvider`, etc.) are dropped; their *names* migrate to `kontor.tax-rate-provider/` for the new protocol impls.

**Implication 3.** ADR-005's two invariants survive verbatim: no bundled API keys / ToS-restricted data; recoverable-vs-non-recoverable stays `:tax/recoverable?`.

**Research backing.** doc/research/70-tax-abstraction-design.md (10 open questions resolved as recommended; per-module survey of all 11 l10n modules; jurisdictional realities for US/BR/IN/CN/CA/DE/FR/AT/JP/MX/AU; reference systems Odoo/Avalara/TaxJar/SST).

Date: 2026-05-17.

### Addendum 2026-05-17 — Review note 76 design-polish clarifications

Five items from the independent design audit (note 76 ADR-071 section) that don't change the substrate decisions but clarify the contract for the still-pending implementation:

* **P1-71-1 — `TaxPostingBuilder` ↔ FX seam.** `TaxFacts` carries a `:line-base` Money + `:commodity`. When the tax authority's reporting commodity differs from the GL's commodity (e.g. a BR invoice in BRL but the authority files in USD-equivalent for some line items, or a CN special VAT fapiao that requires both CNY + USD reporting amounts), the `TaxPostingBuilder` is responsible for calling `kontor.fx/convert` against the configured `FxRateProvider` (ADR-072) BEFORE emitting GL postings. The base-commodity translation is NOT the kernel's job: each per-country builder knows its own jurisdiction's rules. Where the builder needs an FX rate, it takes an `:fx-provider` option of its own (mirroring `kontor.lease.posting/plan-fx-retranslation`'s pattern); the substrate does not push translation up to the kernel.

* **P1-71-2 — Reverse-charge contract enforcement.** ADR-071 documents that `:component/kind :reverse-charge` MEANS DIFFERENT THINGS by `:tax-use` (seller-side `:sale` → reporting-tag only; buyer-side `:purchase` → both-sides VAT postings). The contract is enforced at the `TaxPostingBuilder` level via per-`:tax-use` dispatch — the builder receives the `:tax-use` and the per-component `:kind`, and dispatches. The kernel does not enforce this in the protocol because the seam is per-jurisdiction (a BR-style reverse charge looks different from a DE-style one). Per-country builder docstrings + golden-fixture tests document the dispatch.

* **P2-71-1 — Effective-dated rates.** The existing `:tax/effective-from`/`-until` schema (schema.clj:2809-2818) is the source of truth for time-bounded rate changes. `TaxRateProvider`'s `StaticTableProvider` impl MUST respect these when ranking candidate rates for a given `(jurisdiction, kind, at-date)` triple. The contract: never return a `:tax-fact` whose backing `:tax` entity's window doesn't cover `at-date`. This is an implementation note for the static-table impl, not a protocol-level concern.

* **P2-71-2 — `:jurisdiction-specific-codes` opacity.** The slot is opaque by design — the kernel does not interpret its contents. Per-country golden-fixture tests (per ADR-037 rhythm) document the expected shape per jurisdiction: `{:br/icms-cst "60"}`, `{:in/gst-state-code "27" :in/place-of-supply "27-MH"}`, `{:cn/fapiao-type "01"}`, etc. Consumers downstream (einvoice envelope emitters, clearance providers) consume the slot directly.

* **P2-71-3 — US implementation cost.** ADR-071's "AU +120 LoC → US +1500 LoC" range stays — but the deeper structural point is that US Avalara/SST/nexus is not a single-protocol port but a multi-protocol integration: `TaxRateProvider` calls Avalara per-line; `TaxPostingBuilder` knows the per-state / per-jurisdiction account routing; the einvoice provider is not involved (US has no clearance regime). Expect a `kontor-l10n-us` companion to be its own multi-week sub-project; the substrate ADR enables it, doesn't deliver it.

Implementation work remains future. The clarifications above guide the per-l10n migration; no code changes in this addendum.

### Addendum 2026-05-20 — substrate implemented (note 99 Stage 2)

The protocol trio shipped as kernel namespaces:

* **`kontor.tax-rate-provider`** — the `TaxRateProvider` protocol; the `TaxFacts` record (with the `component-kinds` enum, `tax-facts` constructor, `taxable?` / `total-tax` helpers); `StaticTableProvider` — a real implementation that reads the `:tax/*` schema, respects the `:tax/effective-from`/`-until` window (P2-71-1), and maps `(`:tax/recoverable?`, `:tax-use`)` to a component `:kind`; throwing `AvalaraProvider` / `TaxJarProvider` / `SstCsvProvider` scaffolds (names migrated from the legacy ns per Implication 2 — no bundled keys, ADR-005); `ChainedProvider`.
* **`kontor.tax-posting-builder`** — the `TaxPostingBuilder` protocol; `StaticTablePostingBuilder` — walks each component's backing `:tax` entity's `:tax-rep` repartition lines, materialises `:posting/*` maps with the sign keyed off `:tax-use` (sale → credit, purchase → debit); a `compute-tax-postings` pipeline fn wiring the trio for one line.

The legacy `kontor.tax-provider` namespace was **removed** (Implication 2): its only real consumer, `kontor.core/make-default-tax-provider`, was rewired to the new ns; three sibling-provider docstrings were updated.

Tested: `test/kontor/tax_rate_provider_test.clj` — 7 tests / 27 assertions, exercising rate resolution (sale + purchase), the effective-window filter, the no-tax `nil` case, posting sign per `:tax-use`, and the end-to-end trio.

Deferred (unchanged from this ADR's "Implication"): the 11 per-l10n migrations; real Avalara / TaxJar / SST adapters; the `:tax-fact/*` audit-snapshot entity + `:posting/tax-fact-id`; a full `kontor.tax-pipeline` ns with the `kontor.document.invoice/send!` adapter. Tax groups (`:tax/amount-type :group`/`:division`) are not expanded by `StaticTableProvider` — a per-l10n provider handles those.

### Addendum 2026-05-21 — G1 + G4: reverse charge + withholding posting-side completion

Research note 100's per-l10n migration survey found that the Stage-2 substrate has two *posting-side* gaps: the `TaxPostingBuilder` had no branch for `:reverse-charge` (P1-71-2's buyer-side both-legs was contract-only) or `:withholding` (`sign-for` would mis-post a withholding leg as ordinary VAT). Research note 101 designed the fix; this addendum is the decision, implemented.

**The framing — closed at the vocabulary, open at the implementation.** The interface generalizes internationally *not* by making tax logic uniform — that is impossible — but by fixing the **vocabulary** the irregular per-country logic must report in. Separate the **mechanism** (the posting shape — a small, bounded, genuinely-international set, the `:component/kind` enum, because double-entry structure is the same everywhere) from the **application** (which mechanism fires, at what rate, for whom, when — irregular, jurisdiction-specific, and deliberately *not* the kernel's concern). `TaxRateProvider/rate-facts` is the irregular half: its body is unconstrained; only its output type — a `TaxFacts` over the closed enum — is fixed. `TaxPostingBuilder` is the regular half. The enum is a falsifiable bet; the per-l10n migration is the experiment; a genuine 11th mechanism is an ADR-gated enum extension, never a quiet per-country flag.

**Decision.**

1. **`:tax/mechanism`** — one new kernel attr on `:tax/*` (`:standard` | `:reverse-charge` | `:withholding`, default-absent = `:standard`). `StaticTableProvider`'s `component-kind` maps it to the `TaxFacts` component `:kind`; a bespoke per-country provider sets `:kind` directly and ignores it. The only schema change.

2. **`TaxPostingBuilder` — `:kind` dispatch.** `component-postings` is now a `case` on the component `:kind`; the previous body is the additive `standard` default (byte-identical for every existing VAT / sales-tax / cess / duty / fee / surcharge / pre-collection case).
   - **G1 `:reverse-charge`** — seller-side (`:sale`) emits `[]` (the VAT-return marker rides the base posting as a tag, applied by the consumer); buyer-side (`:purchase`) emits the both-legs pattern — `Dr` input-VAT receivable, `Cr` output-VAT payable — sourced from the backing `:tax`'s `:tax-group` `payable-account` + `receivable-account` pair. The pair self-nets, so reverse charge has no cash effect. Partial-deductibility is the documented bespoke-builder override.
   - **G4 `:withholding`** — a *contra* component. Its posting sign is inverted from VAT (`:sale → +` debit/receivable — the MX retención shape; `:purchase → −` credit/payable — the buyer-withholds shape). It reuses the ordinary `:tax-rep` rep-line walk, parameterized by `withholding-sign`.

3. **The netting contract.** Withholding does not *add* to the gross — it *subtracts* from the counterparty cash leg. `kontor.tax-rate-provider` gains a `kind-effect` classification (`:additive` | `:withheld` | `:neutral`) and three helpers — `additive-total`, `withheld-total`, `net-tax-effect` (= additive − withheld). A consumer sizes the AR/AP leg as `net + net-tax-effect`; for pure VAT that equals `total-tax` (unchanged), for a withholding invoice it is correctly smaller, for reverse charge it is neutral. `total-tax` stays as the gross-notional sum with a docstring caveat.

4. **`valid-tax-facts?`** — the structural / closed-vocabulary check (validation layer 1): every component's `:kind` is in `component-kinds` and `:amount` is a `BigDecimal`. An unknown `:kind` is the signal a provider has outrun the enum.

**Validation strategy** (note 101 §5b): structural (`valid-tax-facts?`); the kernel sum-to-zero gate (free — a structurally-wrong tax computation cannot be posted); per-country golden fixtures (the only check for wrong-but-balanced rates); property tests on the regular mechanism layer; differential validation (new path vs old hardcoded path) during the per-l10n migration.

**Tested.** `tax_rate_provider_test.clj` — 13 tests / 51 assertions: reverse-charge buyer-side both-legs + seller-side `[]`, withholding contra sign, the `net-tax-effect` netting, an end-to-end balanced withholding invoice (Ker σ), the helpers, `valid-tax-facts?`. Existing VAT behaviour byte-identical.

**Open limit** (note 101 §6): `:tax/mechanism` is a flat 3-value enum — a single `:tax` cannot be *both* reverse-charge *and* withholding. None of the 11 jurisdictions need that; such a tax would use a bespoke provider. Flagged, not pre-solved.

---

## ADR-072 — `FxRateProvider` protocol + `:fx-rate/*` schema + `kontor.fx` Money translation

**Decision.** Ship a small, opt-in FX subsystem in three parts:

1. **`:fx-rate/*` schema** (`schema.clj` immediately after `commodity-attrs`). One entity per (from-commodity, to-commodity, at-date, rate-type) sample. A composite `:fx-rate/by-tuple` (`:db/tupleAttrs` = the four identifying attrs, `:db/unique :db.unique/identity`) gives upsert semantics — re-transacting the same key replaces `:rate` / `:source` / `:source-doc`. Rate-type vocabulary follows IAS 21 / ASC 830: `:spot | :closing | :average | :opening | :historical`.

2. **`kontor.fx-rate-provider/FxRateProvider`** protocol with two operations: `resolve-rate` (single point) and `resolve-period-rates` (window of samples, for IAS 21 average-rate computations). Identical *shape* to `TaxRateProvider` (ADR-071) and `TaxProvider` (ADR-005-superseded) so the substrate's pluggable-seam vocabulary stays one pattern. Built-in impls:
   - **`StaticTableProvider`** — reads `:fx-rate/*` from the connected db. Defaults: `:fallback-on-or-before? true` (last-known sample wins when the asked date has no exact hit), `:allow-inverse? true` (USD→EUR derives from EUR→USD as 1/rate at 12-digit half-even precision), optional `:default-via` for triangulation through a base commodity.
   - **`EcbReferenceRatesProvider`** — wraps `StaticTableProvider` with `:default-via "EUR"`; ships an `ingest-ecb-csv-rows!` helper that persists parsed eurofxref-daily rows + their inverses tagged `:source :ecb`. The ECB dataset itself is NOT bundled; the customer ingests it at runtime. ECB attribution string exported as a public var (`fxp/ecb-attribution`).
   - **Scaffolds**: `XeProvider`, `OandaProvider`, `FedH10Provider`. Throw on `resolve-rate` with a hint pointing at this ADR. Same posture as ADR-005 / ADR-071: customer brings the credential or data file; we do not bundle.
   - **`ChainedProvider`** + `chain` helper — try in order, first non-nil non-zero wins. Pattern matches `kontor.tax-provider/ChainedProvider`.

3. **`kontor.fx`** — the Money-side of the story. Four operations:
   - **`convert`** — translate a `Money` into the target commodity at the provider's rate; rounds to a configurable precision (default 2, `nil` to skip). Identity short-circuits; missing-rate raises rather than silently returning the input (silent FX coercion has bitten too many accounting systems).
   - **`translate-money-seq`** — reduce a mixed-commodity sequence of Monies into ONE target commodity. Empty input returns `zero` in `:to`. Building block for IAS 21 P&L (`:rate-type :average`) and BS (`:closing`).
   - **`translate-amounts-by-commodity`** — re-base a `{commodity → BigDecimal}` summary (e.g. what `kontor.balance/account-balance` returns when the account is multi-commodity) into ONE presentation commodity.
   - **`to-functional-currency`** — translate a foreign-currency `Money` into the entity's `:entity/functional-commodity`. No-op when the entity hasn't opted in.

**Composition.** `kontor.fx` requires `kontor.fx-rate-provider`; the kernel itself never *uses* either namespace. Consumers compose: `lease/posting/plan-fx-retranslation` can drop the "consumer supplies `:gain-loss`" requirement and call `kontor.fx/convert` against the configured provider (followup); `kontor.report` can grow an optional `:translate-to` opt (followup); the still-undelivered `kontor.consolidation/translate-currency!` will be one `translate-amounts-by-commodity` per entity per ledger.

**Why a protocol (and not a plain lookup table).** Same reasoning as `TaxRateProvider`: different jurisdictions / books pin different sources (ECB for eurozone reporting, Fed H.10 for US, central-bank reference rates in BR / IN / CN). Paid feeds (XE, OANDA) require customer-owned API keys. In-DB + live-API composes via `ChainedProvider` exactly like `kontor.tax-provider/ChainedProvider`.

**Why `:fx-rate/by-tuple` is upsert-y.** Re-transacting (EUR, USD, 2026-01-02, :spot) replaces the prior rate. Provenance changes (`:fx-rate/source :ecb` → `:source :corrected`) flow through; the bitemporal layer (`:db.valid/from`/`:db.valid/to` via ADR-048 + datahike `feature/bitemporal-v1`) records both the prior and current rates, so the audit chain survives the upsert. Operationally identical to how `:tax-application/by-tuple` works (ADR-016).

**Why 12-digit half-even on inverse derivation.** Avoids cascading-rounding drift across triangulation. The result rounds to the target commodity's display precision at the `kontor.fx/convert` step, not at lookup.

**License posture.** The ECB euro-reference-rates dataset is published under "freely usable for any purpose provided the source is acknowledged" — EPL-compatible. We ship the *adapter* (CSV→`:fx-rate/*` ingest call) + the attribution string. We do NOT ship a CSV snapshot. ECB attribution must be surfaced wherever an ECB-sourced rate is displayed (consumer's responsibility; provider-id `:ecb` + the exported attribution string is the contract).

**Decision NOT to.** Not bundling a real-time-quote backend, hedge-accounting fair-value pricing (IFRS 9 / ASC 815), or central-bank XML poller. Those belong to `kontor-treasury` (deferred companion per research note 69 §4 Gap 6).

**Test discipline.** 28 tests in `test/kontor/fx_test.clj` (44 assertions) cover: identity short-circuit, exact + fallback + inverse + triangulation, rate-type discrimination, period-rates window, upsert via identity tuple, unknown-commodity errors, `ChainedProvider` first-non-nil semantics, ECB ingest produces both directions, Money conversion + precision overrides, mixed-commodity translation, JPY 0-precision handling, functional-currency rebase + passthrough.

**Gotcha documented.** Datalog with a composite-tuple attr: `[?e :fx-rate/by-tuple [?from ?to ?date ?type]]` does NOT do tuple-equality — datahike treats the position-vector as fresh per-slot bindings. Use `:in $ ?tuple` + `[?e :fx-rate/by-tuple ?tuple]` instead. The `query-exact` helper carries a comment to that effect.

**Implication 1.** The kernel's "single-dep on datahike per ADR-001" posture survives — no new dep. The ECB adapter's `ingest-ecb-csv-rows!` accepts pre-parsed rows; the *parser* lives in consumer code (one `clojure.data.xml/parse` call + a flat data->map; ~50 LoC).

**Implication 2.** The architecture review's §4 Gap 2 ("FX rate provider + currency translation, severity P1 for SMB / P0 for trans-national") is now closed at the substrate level. Wiring into `kontor.posting/build-transaction` (`:fx-provider` opt), `kontor.report/compute-report` (`:translate-to` opt), and `lease/posting/plan-fx-retranslation` (drop the `:gain-loss` consumer-input) is the follow-up.

**Implication 3.** Unblocks Gap 4 (consolidation primitive). `kontor.consolidation/translate-currency!` becomes a one-screen function over `kontor.fx/translate-amounts-by-commodity`.

**Research backing.** doc/research/69-architecture-review-and-fp-model.md §4 Gap 2 + §6 Item 6 + §8 Q4 (ECB license).

Date: 2026-05-17.

---

## ADR-073 — Consolidation primitive: `translate-trial-balance-tx-data` + `eliminate-intercompany-pair-tx-data` + `consolidate!`

**Decision.** Ship a multi-entity consolidation primitive in `kontor.consolidation` with three operations and one schema attr group:

1. **`translate-trial-balance-tx-data`** — pure tx-data builder. Given an :operating entity's trial balance and the consolidation entity's presentation commodity, translate per-(account, commodity) amounts via the configured `FxRateProvider` (ADR-072) at IAS 21 / ASC 830 rate-types, sum per account, and emit ONE balanced consolidation transaction stamped with `:posting/entity = consolidation-entity`. The CTA (cumulative translation adjustment) is the plug posted to a designated CTA account when translated amounts don't net to zero.

2. **`eliminate-intercompany-pair-tx-data`** — pure tx-data builder. Given a `:transaction/intercompany-pair-id` (a new schema attr — string, indexed, NOT unique) shared by N transactions across the family, emit one elimination tx whose postings exactly negate the postings of every paired transaction, stamped with `:posting/entity = elimination-entity`. Sum-to-zero per (entity, commodity) holds automatically because the source txs each balance and we just negate.

3. **`consolidate-tx-data`** + **`consolidate!`** — composer + orchestrator. `consolidate-tx-data` walks `kontor.entity/family` from a group root, runs translation for every :operating entity that has a non-empty trial balance, runs elimination for every distinct pair-id touching the family, and returns the vector of tx-data fragments. `consolidate!` commits them via `kontor.process/run-process` so the whole cycle lands as one atomic, validation-gated commit — any sum-to-zero failure rolls the cycle back.

**Schema additions** (`intercompany-pair-attrs` in `schema.clj`):
- `:transaction/intercompany-pair-id` (string, indexed, not unique)
- `:transaction/consolidation-source-entity` (ref) — provenance on translation txs
- `:transaction/consolidation-kind` (keyword: `:translation | :elimination`)

**IAS 21 default rate-type matrix** (`default-rate-type-by-account-type`):
- `:asset → :closing`, `:liability → :closing` — monetary BS items
- `:equity → :historical`
- `:income → :average`, `:expense → :average`

Customers override via `:rate-type-by-account-type` (per-type) or `:rate-type-by-account` (per-account, wins over per-type). The default lumps all assets as monetary; customers with material non-monetary holdings (PP&E, inventory at cost) should ship a per-account override for those — IAS 21 strictly requires historical rate for non-monetary items.

**What this primitive is NOT** (intentionally):
- Not IAS 27 / IFRS 10 / ASC 810 control + ownership. The maintainer passes the family; ownership %, minority interest, acquisition vs pooling are companion-tier (the future `kontor-consolidation` companion).
- Not deferred tax / transfer pricing / Pillar 2 GloBE. Those are `kontor-tax-provision` (research note 69 §4 Gap 5/7).
- Not the consolidation entity's post-translation period close. The entries land with `:transaction/state :draft`; callers post them via subsequent `post-transaction!` cycles when reviewed.

**Atomicity.** `consolidate!` uses `kontor.process/run-process` (ADR-067) so the cycle is ONE transaction under the validation gate. If the US-LLC translation balances but the elimination fails sum-to-zero, the whole cycle rolls back. The per-fragment validation that `transact-with-validation` runs catches sum-to-zero per (entity, commodity) breakage at the gate, which is exactly the right granularity for consolidation work.

**Why fragments-as-steps.** Each translation entry is a coherent transaction. The composer returns N fragments (one per entity + one per pair-id); the orchestrator wraps each as a `(constantly frag)` step. `run-process` accumulates them all and commits once. The alternative (`reduce into [] frags` into one mega-tx-data) would also work but loses the per-fragment audit-trail (multiple `:transaction/*` entities in one tx are fine, but the read path is cleaner with distinct txs per concern).

**The pair-id design.** Most ERP systems use a transaction-level "intercompany document number" or a ref-link between two txs. We picked the string-id pattern because:
- It generalizes to N-way pairs (rare but possible — three-leg intercompany loans).
- It survives entity renames + tx purges (string IDs don't cascade-fail).
- It plays nicely with import: an external feed (a parent ERP exporting txs) brings the IC ID as data, not as a relationship that has to be resolved post-hoc.

**Test discipline.** 5 tests in `test/kontor/consolidation_test.clj` (17 assertions) covering: trivial identity translation (functional = presentation, no CTA), USD-functional translation to EUR (with CTA plug as needed), elimination negation correctness, composer fragment count, end-to-end `consolidate!` producing the expected per-entity trial balances. Full suite at 985 tests / 3524 assertions.

**License posture.** ADR-001 single-dep on datahike survives. No new deps; only existing kontor namespaces. ADR-031 (per-entity sum-to-zero), ADR-067 (process orchestrator), ADR-068 (every business write is a `*-tx-data` builder), and ADR-072 (FxRateProvider) all compose naturally.

**Decision NOT to.** Not building `kontor.consolidation/translate-currency!` as a separate transactor — `consolidate!` IS the public surface; `translate-trial-balance-tx-data` is the building block. The architecture review §6 Item names were illustrative; the actual API is simpler. Not adding the `:consolidate-from`/`:consolidate-to` opts to `kontor.report` — that's a presentation concern; ADR-072's `:translate-to` already covers single-entity multi-currency reporting, and the consolidation entries themselves are reportable via `kontor.report :entity = consolidation-entity`.

**Implication 1.** The architecture review's §4 Gap 4 is now closed at the substrate level. The full `kontor-consolidation` companion (ownership %, minority interest, acquisition accounting, IFRS 3 goodwill, IAS 27 step acquisitions) layers on top.

**Implication 2.** Cross-entity intercompany pair tracking via `:transaction/intercompany-pair-id` is now schema, so any business write can carry it — the invoice module, the procurement module, the lease module, the payment-application module can all tag intercompany operations and have them eliminate cleanly at consolidation time without further coupling.

**Implication 3.** Combined with ADR-072, kontor's substrate now covers the full trans-national accounting story: per-entity books (ADR-031), per-jurisdiction tax (ADR-071), per-currency FX (ADR-072), per-family consolidation (this ADR). What remains is companion-shaped work (consolidation policy, tax-provision, treasury, HR/payroll).

**Research backing.** doc/research/69-architecture-review-and-fp-model.md §4 Gap 4 + §7.6.

Date: 2026-05-17.

### Addendum 2026-05-17 — Review-after fixes (note 76)

Three P0s from the independent review-after agent (note 76) addressed
the same day this ADR landed:

* **P0-73-1 (`consolidate!` non-idempotent — re-elim cascade).**
  `eliminate-intercompany-pair-tx-data` stamps its own output tx with
  `:transaction/intercompany-pair-id` (intentionally — useful for
  audit drill-back from elim → source pair). On a second run,
  `find-pair-postings` was matching that elim tx too and re-negating
  it, doubling the elimination on every cycle. Fix:
  `find-pair-postings` now filters
  `[(missing? $ ?t :transaction/consolidation-kind)]`; `consolidate-tx-data`
  gained an `elimination-exists?` guard that suppresses re-emission.

* **P0-73-2 (consolidation txs without `:db.valid/from`).**
  The pure tx-data builders did not stamp tx-meta; `consolidate!` did
  not pass `:vt-from`/`:vt-to` to `run-process`. Consumers calling
  `(d/valid-at db t)` saw zero consolidation postings regardless of
  `t`. Fix: `consolidate!` now requires `:at-date` and threads it as
  `(process/run-process conn {... :vt-from at-date :vt-to kbt/forever})`.

* **P0-73-3 (translation tx duplication on re-run).**
  Every `consolidate!` call spawned a new draft translation tx per
  operating entity, even when one already existed for the same date.
  Fix: `consolidate-tx-data` gained a `translation-exists?` guard
  keyed on `(:transaction/consolidation-source-entity,
  :transaction/effective-date)` that skips already-emitted entries.

Three new regression tests in `test/kontor/consolidation_test.clj` —
`p0-73-1-eliminate-skips-prior-consolidation-txs`,
`p0-73-2-consolidation-txs-carry-valid-time`,
`p0-73-3-translation-idempotent-on-rerun`.

Plus **P1-72-1 from the same review** (silent inverse-rate staleness
in ADR-072): `ingest-ecb-csv-rows!` now persists ONLY the forward
EUR→ccy direction. The reverse is derived at lookup time by
`StaticTableProvider`'s `:allow-inverse?` machinery (default `true`),
so a customer correcting the forward rate via `save-rates!` no longer
leaves a stale stored inverse. Regression test
`p1-72-1-inverse-stays-fresh-after-forward-rate-correction` in
`test/kontor/fx_test.clj`. Tightened `resolve-rate` to return `nil`
(not `false`) when no via-triangulation path applies.

Remaining P1s + P2s from note 76 deferred to follow-up.

---

## ADR-074 — `kontor.side-effect.cross`: cross-DB saga primitive

**Decision.** Generalize `:side-effect-intent` (ADR-041) for the case where the side effect IS a tx-data commit against a *different* datahike conn (another kontor instance, a stratum secondary index, a scriptum / proximum / yggdrasil sub-system, or any datahike-connected target). Per research note 71 §5.2.

Three components:

1. **`:cross-tx/step-id` schema attr** — string, `:db.unique :db.unique/identity`. Bundled into `kontor.schema/all`. Written on the *target* tx by drain workers as the saga-step idempotency marker. Non-kontor target consumers install the same attr.

2. **`kontor.side-effect.cross` namespace** (~250 LoC):
   - **`CrossTxRouter`** protocol — `(resolve-conn [_ system-id])`. The consumer's system-id → conn mapping; typically a `(reify ...)` closing over live conns at boot.
   - **`step-id`** — deterministic SHA-256 + Base64-URL of `(intent-key, canonical-edn target-tx-data)`, 43 chars. Pure; must agree across JVMs / restarts so re-claims converge.
   - **`cross-tx-intent-tx-data`** — builds a `:side-effect-intent` map for a cross-tx-post, ready to stitch into the source tx alongside the upstream status change (per ADR-041's "intent commits atomically with the change").
   - **`execute-one!`** — claim intent → resolve target → check target for the step-id → skip-or-transact-augmented → mark done (or failed). The check is what makes this crash-safe: if the worker crashed after target-commit but before mark-done, the next worker sees the step-id present and goes straight to mark-done without re-transacting.
   - **`drain!`** — single-pass execution of every pending `:cross-tx-post` intent. Returns `{:processed N :done N :failed N :abandoned N}`. Caller schedules re-runs.

3. **Reuses `:side-effect-intent/*` schema verbatim** (ADR-041). The intent `:type` is `:cross-tx-post`; the `:payload` is a `pr-str`'d EDN map with `:target/system-id`, `:target/tx-data`, `:step-id`.

**Why a saga + content-hash idempotency, not 2PC.** Per the cross-DB design study (note 71 §1): the JVM industry walked away from XA / JTA over the last 15 years because of operational complexity. Sagas + per-step idempotency match the actual shapes kontor faces (kontor + stratum index; intercompany kontor↔kontor; kontor + scriptum audit log), and the konserve-shared-store atomic path already covers the genuinely same-store cases at the datahike layer (note 71 §5.3). What's left is exactly what `:side-effect-intent` was already designed for — a queue of intents that drain to side effects. Generalizing it to "the side effect is itself a tx-data commit" is the cleanest extension.

**Why deterministic step-ids.** Without them, the crash-recovery story is "the worker's failure mode is the user's problem" — re-claims double-commit the target. The step-id moves the question from "did the source mark this done?" (a write-coordination problem) to "does the target already hold this step-id?" (a read-uniqueness problem). The latter has a single datahike index lookup.

**Why canonical EDN.** Different JVMs hash differently if map iteration order differs. The `canonical-edn` helper sorts map keys + set elements so the hash converges. Sequential collections keep insertion order (they're semantically order-bearing); records stringify as-is.

**Decision NOT to.** Not building a workflow engine on top. `kontor.process` is the single-DB orchestrator; `kontor.side-effect.cross` is the cross-DB orchestrator. Together they cover the project's needs; anything bigger (Temporal-style declarative workflows, Camunda-style BPMN) belongs in a consumer.

**Decision NOT to.** Not coupling to non-datahike backends. The target conn MUST support `(datahike.api/transact conn …)` + `(datahike.api/db conn)`. Heterogeneous backends (Kafka topics, S3 PUTs, HTTP webhooks) are still served by ADR-041's parent dispatcher pattern: ship the intent, drain via a custom worker.

**Test discipline.** 9 tests / 27 assertions in `test/kontor/side_effect/cross_test.clj` covering step-id determinism, canonical map-key ordering, intent-shape correctness, drain end-to-end against a target conn, idempotent re-run after simulated worker-crash, failed-intent error capture, target-schema assertion (skipped in-test because `kontor.schema/all` always ships the attr).

**Implication 1.** The cross-DB primitive from research note 71 §5.2 is now substrate-shipped. The other note 71 proposals — `kontor.cross-tx` (yggdrasil adapter), `datahike.api/multi-transact!` (datahike-side promotion of `konserve/multi-assoc`), and the audit-chain bridge (`kontor.audit-chain/verify-workspace!`) — remain follow-up work; they require touching yggdrasil + datahike respectively.

**Implication 2.** Consumers can now ship cross-DB writes with the same "write the intent in the same tx as the upstream change" pattern they already use for email / EDI / webhooks (ADR-041). The only new vocabulary is the `:cross-tx-post` type + the `CrossTxRouter` instance at boot.

**License posture.** ADR-001 single-dep on datahike survives — no new deps. Pure-Clojure, no JCA / JTA / XA / external coordinator.

**Research backing.** doc/research/71-cross-db-atomic-transact.md §5.2.

Date: 2026-05-17.

---

## ADR-075 — Stage R substrate: `kontor-hr` + `PayrollProvider` trio + two-axis `:audit-doc/category`

**Decision.** Land the HR / payroll substrate as planned by research notes 79 + 81. Three pieces:

1. **Two new kernel attrs.** `:audit-doc/category` (open-set keyword, nil = `:none`) is the subject-matter axis orthogonal to ADR-051's `:audit-doc/privilege` (legal-doctrine axis). `:retention-policy/category` (open-set keyword) lets the ADR-050 sweeper match per-category retention floors (payroll PII vs financial records have different statutory retention under GDPR Art. 17 + DE §28f SGB IV + HGB §257). These are the **only** kernel additions in Stage R; every other entity lives in the companion.

2. **New companion module `kontor-hr`** with seven entities under three discriminator-namespace groups:
   - `:person/*` — human-identity root; bears PII (`:person/birth-date`, `:person/citizenship`, `:person/national-id` → `:audit-doc`). `:person/state` lifecycle facet (`:active` → `:deceased` | `:purged`).
   - `:partner/person` — single ref linking a `:partner` (kernel) to a `:person` (companion). Set when `:partner/kind :employee` (open-set extension per ADR-039).
   - `:employment/*` — Workday-style multi-employment per person (one row per `(person, entity)` per note 79 Call 2), with the lifecycle facet `:applicant → :offered → :hired → :active → :on-leave → :terminated → :rehired` + new `:employment/work-time-fraction` (BigDecimal FTE) + `:employment/work-relationship-kind` (open-set keyword: `:standard` | `:secondment` | `:apprentice` | `:civil-servant` | `:intern` | …) from note 81 §9.7.
   - `:department/*` — recursive per-entity org tree; `:department/manager` refs an `:employment` (not a `:person`).
   - `:compensation/*` + `:compensation-component/*` — per note 81 §9.6 refactor. Compensation is its own entity with multi-cardinality components (base wage + bonus + employer SI + VWL + housing allowance + RSU vest as DISTINCT rows). Effective-dated via `:compensation/effective-from` / `effective-to`. The Workday / SuccessFactors / Oracle Fusion / Gusto / Frappe HR shape; the alternative (wage scalars on `:employment`) collapses on DE C2's Weihnachtsgeld + employer SI + VWL = different SKR04 accounts case.
   - `:pay-period/*` — per-entity (DE-monthly + US-biweekly coexist within a group); refs the kernel `:period` (ADR-014) so period-lock middleware refuses payroll into a locked fiscal period.
   - `:payroll-run/*` — one (pay-period × entity) execution. Carries `:payroll-run/control-total-gross/-net` for reconciliation against the engine's totals and refs the produced `:transaction` + emitted `:audit-doc`s.

3. **New kernel namespace `kontor.payroll-provider`** — mirrors the ADR-071 three-protocol shape with a fourth `PayrollEmitProvider` for jurisdictional event-bus emissions:
   - **`PayrollComputeProvider`** — pure gross-to-net wrapper around the engine (DATEV LODAS / ADP GLI / Wagepoint / etc.). Returns a vector of `PayrollFacts` maps. kontor never re-implements jurisdictional payroll math.
   - **`PayrollFacts`** data shape — per-employment + per-component, carrying `:gross`, `:net`, `:components [{:kind :amount :employer-side? :jurisdiction-codes}]`, and a `:jurisdiction-specific-codes` opaque slot.
   - **`PayrollPostingBuilder`** — materializes GL postings from `PayrollFacts`. Consumes a consumer-supplied `:accounts` map (component-kind → `:account` ref); kontor never bundles a chart.
   - **`PayrollEmitProvider`** — returns `:audit-doc` rows for required jurisdictional emissions (DE LODAS Lohnimport, UK FPS XML, AU STP Phase 2). Default `LocalfileEmitProvider` returns `[]` — adequate for US (no clearance regime).

The orchestrator `kontor.hr.payroll/run-payroll!` composes the three providers through `kontor.process/run-process` (ADR-067) so the kernel gate stack (legal-hold + period-lock + state-machine + datalog invariants) fires once atomically. It validates each fact's sum invariant (`gross = Σ positive employee-side components`; `net = gross + Σ negative deductions`) before producing tx-data — bad facts throw before they reach the gate.

**Why companion-tier (not kernel) for the entities.** Per note 79 §2.1: `:partner` is on the hot path of every posting and was promoted to the kernel for that reason; `:person` is one indirection off the hot path (posting → partner → person). Half the kernel's consumers (single-founder accounting, SaaS using external HR, embedded fintech) never need HR; loading them into the kernel costs schema-doc noise for zero gain. The shape matches `kontor-sales` / `kontor-procurement` / `kontor-lease` / `kontor-expense` (every other ADR-002 companion).

**Why multi-employment (Workday) not single-employment (ADP/Tryton).** Per note 79 §2.2 + note 81 §8 table: every enterprise-tier system (Workday, SAP SuccessFactors, Oracle Fusion HCM, Deel, Gusto, OFBiz) supports it; only flat-shaped SMB tools (BambooHR, OrangeHRM, Tryton, NetSuite, Rippling base) collapse it. The trans-national pitch (note 79 §2.2 — an executive employed by Acme-DE-GmbH AND seconded to Acme-US-LLC) requires it. Substrate cost is zero (one ref attr); retrofitting later is high blast radius (synthetic splits, ambiguous wage joins, downstream report re-disambiguation).

**Why hybrid `:person` + `:partner/kind :employee` linker (not pure `:partner` reuse, not pure separate `:person`).** Per note 79 §2.3: every identity-hub pattern in the gold-standard set (OFBiz Party+Person, Workday Worker, Oracle Person, SuccessFactors PerPerson, Rippling Employee Graph) separates "the human" from "the business-relationship party." A `:partner` may pre-date employment (was a vendor first) and post-date it (continues as customer); a `:person` is born once, dies once, GDPR-erasable per ADR-050. The privacy + lifecycle axes argue for the hybrid; OFBiz solves the same tension the same way.

**Why compensation-as-its-own-entity (note 81 §9.6 refactor).** Per note 81 §8 table + §9.6: Workday `Compensation` + `Pay Components`, SuccessFactors `EmpCompensation` + `EmpPayCompRecurring`, Oracle Fusion `PAY_*`, Gusto `Compensation per Job`, Frappe HR `Salary Structure Assignment`, OFBiz `PayHistory` — all model comp as separate, multi-cardinality, effective-dated. Only the flat-shaped SMB tools keep it scalar. The single-attr collapse on `:employment` cannot represent N simultaneously-active components (DE C2 Weihnachtsgeld + employer pension + VWL + base wage = 4 distinct SKR04 accounts; a single `:employment/wage` scalar forces the consumer to choose between (a) multiple `:employment` rows — wrong, they're all attributes of one employment — or (b) per-pay-period variable inputs — wrong, components are standing comp structure). The refactor saves ~3 days of migration debt at the cost of ~3 hours pre-C1 schema work.

**Why two-axis `(privilege, category)` not single-axis enum.** Per note 79 §2.5: `:audit-doc/privilege` is legal-doctrine classification (attorney-client, work-product, trade-secret); `:audit-doc/category` is subject-matter (payroll, hr-personnel, hr-medical, tax-filing). The auth grid needs both axes — "HR role can access category `:payroll` regardless of privilege" and "tax-prep contractor can access category `:tax-filing` UNLESS privilege `:attorney-client`". Conflating them destroys the grid. GDPR Art. 30 records-of-processing organize by "category of personal data" — the regulatory schema *is* two-axis. `:retention-policy/category` mirrors the attr so the ADR-050 sweeper can carry per-category floors.

**Decision NOT to.** Not shipping `:position` + `:position-fulfillment` (Workday Position Management vs Job Management). Workday supports BOTH staffing models within one tenant and explicitly treats Position Management as optional; Oracle Fusion has two-tier (no position layer) vs three-tier. Per note 81 §9.5 the deferral is right; consumers needing headcount budgeting can add the layer in C5+.

**Decision NOT to.** Not shipping recruitment (`:job-requisition` / `:job-interview` / `:employment-application`). Commodity SaaS (Greenhouse, Lever, Workable) covers this and we have no accounting-side stake.

**Decision NOT to.** Not shipping benefits + time-off entities (`:benefit-enrollment`, `:absence`, `:absence-allocation`). Deferred to C4+ per note 79 §3; SuccessFactors `EmpTimeAccount` + `EmpTimeOffCalendar` is the structural template for when we do land it.

**Decision NOT to.** Not bundling per-country wage-type catalogs (DE SKR04 wage accounts, US W-2 box mappings), engine API credentials, or proprietary mapping tables. Mirrors ADR-005 / ADR-071 / ADR-072 — the consumer holds the engine credential; kontor consumes engine output.

**Test discipline.** 9 tests / 34 assertions in `modules/hr/test/kontor/hr/hr_test.clj` covering install idempotency, schema-attrs-present round-trip, create-person + hire + multi-employment, compensation set + supersede + bitemporal wage query, check-facts sum invariant accept/reject, and full run-payroll! end-to-end with a hand-written mock provider trio (one fact per employment with employer-side SI, balanced postings via build-transaction, control totals + transaction link on the `:payroll-run`).

**Effort.** ~1 maintainer-day for the C1 substrate (2 kernel attrs + 8 companion files + protocol trio + tests + this ADR + status-machine seeds). Per-country adapters (C2 DE-DATEV-LODAS, C3 US-ADP-GLI, C4 CA-CRA-payroll) plan into ~6+6+4 days respectively; see notes 82 + 83 + 84 for the per-country research-before output.

**Research backing.** doc/research/72-hr-payroll-reference-study.md (OFBiz `humanres` Apache-2.0 reference), 73 (12-theme market-pain catalog), 74 (substrate gap analysis + the 5 design calls), 79 (the 5-design-calls implementation plan + per-country sequencing), 81 (gold-standards study confirming 5/5 calls + recommending the §9.6 compensation-as-entity refactor + 3 minor §9.7 adds), 82 (DE-DATEV-LODAS), 83 (US-ADP-GLI), 84 (CA-CRA-payroll).

Date: 2026-05-18.

---

## ADR-077 — `kontor-payroll-us-adp`: ADP General Ledger Interface adapter (Stage R C3)

**Context.** Stage R C1 (ADR-075) landed the `PayrollProvider` trio + `kontor-hr` substrate; per-country adapters compose on top. The US is one of the three baseline jurisdictions Stage R targets (alongside DE and CA — see note 79 §5). The US payroll market is concentrated in five engines — ADP (RUN + Workforce Now + InfoLink), Gusto, Paychex Flex, OnPay, Rippling — all of which converge on a "one row per GL line" CSV export. We adopt ADP's General Ledger Interface (GLI) as the C3 reference vendor because (a) it's the largest by US payroll-engine market share, (b) its 10-column format is exhaustively documented in Microsoft's "Payroll Connect for Dynamics GP" public reference, and (c) the format is identical across ADP RUN, Workforce Now, and InfoLink. See note 83 for the full research-before bundle.

**Decision.** Ship `modules/payroll-us-adp/` as a kontor companion module that:

1. **Parses the ADP GLI 10-column CSV** into a vector of `PayrollFacts` maps via `AdpGliComputeProvider` (satisfies `PayrollComputeProvider`). The parser detects + handles ADP's "balancing row" file-format artifact (note 83 §2.3) and enforces the file's sum-to-zero invariant on parse.
2. **Maps each parsed GLI row to a kontor wage-type role** via a consumer-supplied `description-rules` regex table. The reference fixture (`resources/kontor/payroll_us_adp/wage_type_map_reference.edn`) covers the canonical ADP vocabulary (~25 rules including the catch-all `.*` for unmapped descriptions) and per-row state extraction from regex capture groups (e.g. `^([A-Z]{2}) SUI$` → state = capture-1 → state-tax-payable analytic).
3. **Materializes balanced GL postings** via `UsPayrollPostingBuilder` (satisfies `PayrollPostingBuilder`). Each component routes to a consumer-supplied `:account` based on the `:account-key` declared on the matching wage-type-map rule. Parallel-ledger split per ADR-021: components carry `:ledgers #{:us-gaap :us-tax}` (or just `#{:us-gaap}` for book-only items like ER 401(k) match accrual under IRC §404(a)(6)).
4. **Records per-state wage allocation** via `:posting/analytic-distributions` on every wage-side posting — NOT `:posting/entity`. The substrate-installed `:analytic-plan/code "state"` plus per-state `:analytic-account` rows (50 states + DC + 5 territories, ISO-3166-2:US codes) constitute the per-state axis. See "Why analytic-distribution, not entity" below.
5. **Provides ASC 710 PTO accrual + 401(k) employer-match accrual** primitives via `kontor.payroll-us-adp.accrual/*`. Both land on the book ledger (`:us-gaap`) per pay-period; tax-ledger recognition under IRC §461(h) (PTO) and IRC §404(a)(6) (401(k) match) is consumer-driven via a separate `tax-recognize-401k-match-tx-data` primitive.
6. **Provides W-2 reconciliation** as a year-to-date per-employee report (`kontor.payroll-us-adp.w2-recon/ytd-by-employee`) — NOT a W-2 form generator. ADP files W-2s with SSA directly; kontor produces Box 1 / 3 / 5 / 12 / 16 / 17 / 19 totals from the posting log so the customer can cross-check against ADP's generated W-2 and resolve reconciliation deltas.

**Why analytic-distribution, not `:posting/entity` for per-state.** A US LLC employing remote workers in 15 states is **one legal entity** — it files one Form 1120 (or 1065 for an LLC taxed as a partnership). Per-state wage allocation is a *reporting / analytic* concern, not a separate balanced-books entity. If we mapped per-state to `:posting/entity` we would:

- force sum-to-zero per state (payroll debits wage-expense in one state and credits a single cash account at the corporate level — inherently cross-state, so 14 intercompany clearing pairs per payroll run),
- collide with the `:entity/functional-commodity` (one currency per entity — 15 USD sub-entities are degenerate),
- dilute the dedicated semantic of `:posting/entity` (the executive employed by Acme-DE-GmbH and seconded to Acme-US-LLC — the case `:posting/entity` was built for, per ADR-031).

`:analytic-account/state` rides cleanly on ADR-022 + ADR-032 analytic-accounting machinery: per-plan sum-to-100 holds at the posting level, a single `wages-by-state` report runs across all postings + their analytic distributions, state-tax-withholding-payable can split into per-state sub-accounts (`2150-CA`, `2150-NY`) without any cross-entity clearing, and a new state hire requires only adding an `:analytic-account` row — no schema migration. See note 83 §4.

**Why NOT extend the kernel schema with `:analytic-account/state`.** ADR-022 already provides the substrate (`:analytic-plan` + `:analytic-account` + `:posting/analytic-distributions`). We install the `:state` plan + 50 states + DC + 5 territories as a *companion-side data installation* under `kontor.payroll-us-adp.core/install!` — no new kernel attrs. States are a per-country thing; the kernel must stay country-agnostic.

**License posture (same as ADR-005 / ADR-071 / ADR-075).** No code lifted from ADP. The wage-type vocabulary and the 10-column format are derived from **public spec / customer-doc material**: Microsoft's "Payroll Connect for Dynamics GP" admin reference, ADP's own admin-portal PDFs (`support.adp.com/.../GL_Download_Instructions.pdf`, `RUN_GL_Guide_QBO.pdf`), the GLI Update Account Mapping quick-reference, plus third-party integration manuals (Juris, Sage 50/100, Shoptech E2). No ADP API credentials, rate tables, or proprietary mapping tables are bundled — every customer supplies their own wage-type → CoA mapping at install time (the reference fixture is a starting point, NOT a default).

**What kontor does NOT do (scope discipline per note 83 §1 + the task brief).**

- **NO US gross-to-net implementation.** FICA, FUTA, SUTA, multi-state withholding, 401(k) caps, garnishment-priority patchwork, supplemental-wage withholding — ADP did the math. kontor consumes the result.
- **NO W-2 / W-3 / Form 941 / Form 940 emission.** ADP files all federal + state payroll returns. The `PayrollEmitProvider` for US is the default `LocalfileEmitProvider` (no transmissions).
- **NO nexus determination.** Whether a new-state hire triggers SUTA registration, state-income-tax-withholding registration, or workers'-comp registration is a tax / registration question the customer's auth layer resolves. kontor's contribution: the `wages-by-state` report surfaces the new state immediately; a scheduled `kontor.report/compute-report` (per ADR-032) can alert when a previously-zero state has a non-zero total. The substrate **records** the per-employment per-row allocation; it does not enforce nexus.
- **NO convenience-of-the-employer rule enforcement** (note 83 §8.5). The NY convenience-rule, OH-IN reciprocity, PA-NJ reciprocity, and ~10 similar state-pair rules are the customer-side allocator's policy. kontor records the decision via `:analytic-account/state`.
- **NO SUTA wage-base cap validation.** Each state has its own ($7K Florida → $66K Washington range, 2024 numbers); ADP enforces the cap math. Re-deriving would fail in edge cases (mid-year state transitions, multi-employer wage-base sharing — note 83 §9.4 gotcha #4).

**Parallel-ledger split for accruals (ADR-021, note 83 §6).**

ASC 710 PTO accrual:
- Book ledger (`:us-gaap`): `Dr PTO Expense / Cr PTO Accrual` per pay-period as service is rendered. Conditions per FASB ASC 710-10-25-1 (vests OR accumulates AND probable AND reasonably estimable).
- Tax ledger (`:us-tax`): NO accrual. IRC §461(h) economic-performance test blocks tax-side accrual until the absence is taken (or via the narrow "recurring item" exception when paid within 8.5 months of year-end).

401(k) employer-match accrual:
- Book ledger: `Dr 401(k) Match Expense / Cr 401(k) Match Payable` per pay-period as wages are earned.
- Tax ledger: deferred to year-end (or beyond) per IRC §404(a)(6). The substrate provides `tax-recognize-401k-match-tx-data` as a primitive the consumer's process invokes when:
  - the contribution is paid by the corporate-return due-date (+ extensions; ~8.5 months for Form 1120),
  - the match is "on account of" deferrals from compensation earned during the tax year,
  - the plan document treats it as a prior-year contribution.

The substrate does NOT make the §404(a)(6) determination — that has plan-document-specific inputs (note 83 §10 item 3). The consumer's tax-prep engine answers; kontor records.

**Test discipline.** 38 tests / 457 assertions in `modules/payroll-us-adp/test/kontor/payroll_us_adp/`:

- `compute_test.clj` (9 tests / 71 assertions) — CSV round-trip, balancing-row trap, file-balance invariant rejection of corrupt files, wage-type regex matching with state capture groups, classify-row state resolution from capture / reference-3 fallback, PayrollFacts assembly aggregated by employee with gross/net matching `kontor.hr.payroll/check-facts`.
- `wage_types_test.clj` (4 tests / 7 assertions) — reference-map loads, regex compilation, validate catches missing vendor / empty rules / no-catch-all.
- `posting_builder_test.clj` (7 tests / 79 assertions) — wage-type → account routing via consumer-supplied `:accounts` map, missing-key explodes (no silent drop), multi-state allocation via `:posting/analytic-distributions` (NOT `:posting/entity`), hybrid 60/40 employee allocation via consumer override, parallel-ledger split (book-only ER 401(k) match), per-(ledger, commodity) sum-to-zero holds.
- `accrual_test.clj` (7 tests / 21 assertions) — HALF-EVEN rounding, ASC 710 PTO tx-data structure (Dr expense / Cr liability, book-ledger-only), required-fields validation, `!` wrapper routes through `transact-with-validation`, 401(k) match book accrual (book ledger only), tax-ledger recognition lands on `:us-tax` per IRC §404(a)(6) consumer-driven, negative-amount reverses the accrual (over-estimate clawback).
- `w2_recon_test.clj` (9 tests / 13 assertions) — Box 1 reduces by 401(k) + §125; Box 3 reduces by §125 only (not 401(k) traditional); Box 5 uncapped; Box 3 caps at SS wage base for high earners; Box 4 / 6 / 2 / 17 derive from posting log; Box 12 grouped by W-2 code; multi-pay-period YTD accumulation.
- `e2e_test.clj` (1 test / 7 assertions) — full headline scenario: US LLC, 3 engineers in CA / NY / TX, monthly payroll, end-to-end through `kontor.hr.payroll/run-payroll!` → `kontor.process/run-process` → `transact-with-validation`. Asserts payroll-run row + control totals + balanced transaction + per-state analytic distributions.

Fixtures cited as oracle sources: Microsoft Learn "Payroll Connect for Dynamics GP" (the 10-column GLI spec); ADP RUN General Ledger & QuickBooks Online guide; ADP General Ledger Documents API marketplace guide; ADP Multi-State Payroll how-to.

**Decision NOT to.** NOT extending the kernel schema with state attrs; NOT bundling ADP API credentials; NOT bundling customer CoAs / state withholding rate tables / SUTA wage-base tables / W-2 box-mapping tables; NOT writing W-2 generation (ADP does that); NOT writing Form 941 / 940 / state filings (ADP does that); NOT re-implementing US gross-to-net math; NOT enforcing nexus / convenience-rule / reciprocity (consumer's allocator policy decides; kontor records via `:analytic-account/state`).

**Effort.** ~1 maintainer-day for C3 (6 source files + 6 test namespaces + reference fixture + 3 CSV oracle fixtures + ADR + deps.edn / tests.edn wiring). Per the §1 bullet 4 strategic pitch, the same parser shape covers Gusto / Paychex / OnPay / Rippling with per-vendor column maps — those become small follow-on modules when consumer demand surfaces.

**Research backing.** doc/research/79 (Stage R plan); doc/research/81 §9.6 + §9.7 (compensation-as-entity + Worker-subtype refinements); doc/research/83 (full US-ADP-GLI research-before); doc/research/73 Theme B (multi-state pain), Theme D (401(k) match book/tax delta), Theme F (W-2 multi-jurisdiction reconciliation).

Date: 2026-05-18.

---

## ADR-078 — `kontor-payroll-ca`: CA-CRA payroll adapter (Stage R C4)

**Decision.** Land the CA-CRA payroll adapter as a new companion module `modules/payroll-ca/` on top of the Stage R C1 substrate (ADR-075) + the already-shipped `modules/l10n-ca/xml/` T619 + T4 emitters. Five pieces, no new kernel ADRs beyond a single open-set kernel attribute add (`:audit-doc/language`):

1. **`kontor.payroll-ca.wage-types`** — CA-specific `:component-kind` open-set extension. Maps every CA pay-element kind (`:base-wage`, `:income-tax-withheld`, `:employee-cpp`, `:employee-cpp2`, `:employee-ei`, `:employer-cpp`, `:employer-ei`, `:vacation-pay-accrual`, the QC carve-outs `:employee-qpp` / `:employee-qpip` / `:employee-qc-itx`, the carry-only T4 boxes `:ei-insurable-earnings` / `:cpp-pensionable-earnings` / `:pension-adjustment`, etc.) to an `:account-tag` keyword and (where applicable) a T4-box keyword. Consumer-extensible via an `:extras-map`. Per note 84 §10.2.

2. **`kontor.payroll-ca.compute`** — three `PayrollComputeProvider` impls:
   - `CeridianDayforceGlProvider` — reference CSV adapter with configurable column-mapping (per-customer-configurable Dayforce GL export; same shape works for Powerpay).
   - `AdpCanadaProvider` — the ADP RUN / Workforce Now 10-column GLI shape with CA-mode pay-element codes. Reuses the (Stage R C3 US-ADP) parser pattern with the ADP "balancing row" net-zero invariant check (note 83 §1 trap; note 84 §10.4 #2 → its application here).
   - `WagepointApiProvider` — partner-program-gated skeleton with a clearly-marked TODO docstring per note 84 §2.1 + §11 Q1.

3. **`kontor.payroll-ca.posting-builder`** — `CaPayrollPostingBuilder` impl of `PayrollPostingBuilder`. Per-pay-period CA journal entry: DR wages-expense / employer expense rows + CR three CRA payable buckets (`:ca-payroll-itx`, `:ca-payroll-cpp`, `:ca-payroll-ei` — NEVER collapsed per note 84 §3.3 + §7.2) + CR Wages-payable. Supports per-RP routing via `:rp-account-tag` opt that attaches an `:account-tag/name`-keyed `:posting/account-tags` ref to every posting (note 84 §4.2). QC passthrough emits `:employee-qpp` / `:employee-qpip` / `:employee-qc-itx` to the Revenu Québec parallel accounts (note 84 §8).

4. **`kontor.payroll-ca.t4-builder`** — payroll-facts → T4 slip aggregator + full T619+T4+T4-Summary submission builder. Group-by (person × RP × tax-year × province-of-employment) per note 84 §10.4 #2 (multi-province employees → multiple T4s). Box coverage spans 14/16/16A/17/17A/18/20/22/24/26/44/46/52/55/56 per note 84 §5.2 + the existing `kontor.l10n-ca.xml.t4` element list. Round-trip verified against the shipped 2026V4 `T619_T4.xsd`.

5. **`kontor.payroll-ca.pd7a`** — remittance helper (NOT an emitter). Sums the three statutory CRA payables (ITX / CPP / EI) for a period, optionally filtered to one RP via `:rp-account-tag`. Computes the suggested next due date per the four CRA remitter types (quarterly / regular / accel-T1 / accel-T2 — note 84 §3.2). Returns a `pd7a-audit-doc-tx-data` ADR-068 builder; consumer transacts the `:audit-doc/category :payroll-filing` row. kontor does NOT emit a PD7A form because there isn't one — PD7A is CRA-to-employer correspondence (note 84 §3.1 + §3.4).

6. **`kontor.payroll-ca.emit`** — `CaPayrollEmitProvider` impl + `terminate-employment-tx-data` (ADR-068) + `build-t4-audit-doc-tx-data`. Emits a single `:audit-doc/category :payroll` row per payroll run carrying the language flag; logs a warning on QC-employee detection that RL-1 emission deferred to C4.1. The termination helper emits a `:termination-event` audit-doc with the Block-15 data (insurable earnings rolling window, separation payments, Block-16 reason) the consumer's engine needs to file the ROE via Service Canada's ROE Web — kontor does NOT emit ROE itself (note 84 §6).

**Bilingual via `:audit-doc/language` (new kernel attr).** A single open-set keyword attribute `:audit-doc/language :en | :fr | :bilingual | <consumer extension>` lands on the kernel's `:audit-doc/*` group. Per ADR-051's open-set pattern this is non-breaking; nil is treated as `:en` by emit code. Why a kernel-level slot rather than a tag-name convention: same filing TYPE (T4) can be EN or FR depending on employee-correspondence preference; CRA T619 takes `lang_cd E|F` per submission; Revenu Québec RL-1 is FR by convention. DSAR / retention rules don't differ by language — so language is orthogonal to `:audit-doc/category`, exactly like ADR-051's `:audit-doc/privilege` is orthogonal. The kernel schema doc-string for `:audit-doc/category` is the entry-point for the three-axis (privilege × category × language) auth-grid story.

**Decision NOT to.** Not adding a kernel-level `:account-tag/program-account` attribute — the open question from note 84 §11 Q3 resolves to "stay with `:account-tag/name` convention" because the existing tag-name machinery handles per-RP routing cleanly (e.g. `:account-tag/name "ca-cra-rp-RP0001"` per posting via `:posting/account-tags`). The `:identifiers.clj` BN15 validator stays the gateway; no new typed slot needed.

**Decision NOT to.** Not emitting PD7A (CRA-to-employer correspondence — there's no employer-filed PD7A form per note 84 §3.1). Not emitting ROE (Service Canada via the engine — note 84 §6). Not implementing CPP / CPP2 / EI / federal+provincial income-tax math (engine is authoritative per note 84 §2 + ADR-075 architectural commitment).

**Decision NOT to.** Not shipping the QC carve-out emitter — RL-1 + RL-1 Summary + TPZ-1015 monthly remittance helper defer to C4.1. C4 ships passthrough: T4 boxes 17/17A/55/56 populate correctly if engine emits the QC components; an info-level warning logs that RL-1 emission isn't supported yet (note 84 §8.2 + §8.3).

**Decision NOT to.** Not shipping per-province EHT (Ontario / BC / Manitoba / Newfoundland) or per-province WSIB / WCB premium emitters in C4. Accrual posting works via the `:employer-eht` / `:employer-wsib` component kinds + the starter chart's `:ca-payroll-er-eht` / `:ca-payroll-er-wsib` accounts; per-province rate tables + filing helpers defer to C4.2 (note 84 §10.4 #7-8 + §11 Q6).

**Decision NOT to.** Not bundling vendor pay-element catalogs (the Ceridian / ADP / Wagepoint code lookups). Consumer supplies `:pay-element-codes` map at provider construction time (mirrors the ADR-005 / ADR-071 / ADR-072 / ADR-075 "consumer holds the engine" pattern).

**Test discipline.** 38 tests / 159 assertions across six namespaces in `modules/payroll-ca/test/kontor/payroll_ca/`:
- `wage_types_test` — catalog membership + T4-box mapping coverage + extras-map extension (3 tests / 28 assertions).
- `compute_test` — Ceridian + ADP-CA CSV parsers + balancer-row invariant + Wagepoint skeleton (10 tests / 22 assertions).
- `posting_builder_test` — pay-element → CoA mapping + RP routing + QC passthrough + vacation pay accrual + balanced-postings invariant (9 tests / 19 assertions).
- `t4_builder_test` — payroll-facts → T4 slip aggregator + box catalog + multi-province + bilingual + **XSD validation against the shipped 2026V4 T619_T4.xsd** (7 tests / 26 assertions).
- `pd7a_test` — remittance schedule + due-date per remitter type + three-bucket totals + RP-routing filter + audit-doc tx-data (7 tests / 25 assertions).
- `e2e_test` — full bilingual run-payroll! through ON + QC employees, with PD7A totals + QC passthrough warning (2 tests / 13 assertions).

**Effort.** ~1.5 maintainer-day for C4: 6 src namespaces + 6 test namespaces + 2 CSV fixtures + the ADR + one kernel attr add. Per the note 84 §11 acceptance criterion, C4 is "cheap follow-on" because half the work (T619 + T4 XML emitters + BN/RP validators + GST/HST infrastructure + CAD-defaulting chart) already shipped in `modules/l10n-ca/`.

**Open followups for review-after.**
- **P1** — Wagepoint live API wiring (skeleton ships; full implementation gates on partner-program access per note 84 §2.1).
- **P1** — QC C4.1 (RL-1 XML emitter + RL-1 Summary + TPZ-1015 remittance — note 84 §8).
- **P2** — Per-province EHT (Ontario priority) + WSIB / WCB per-province filing helpers (note 84 §10.4 #7-8).
- **P2** — Statutory-holiday-aware PD7A due-date shift (note 84 §10.4 #11 — current naive impl uses calendar days only).
- **P2** — T4 Box 40 ('Other Information' taxable-benefit subtotal) + Box 45 (dental coverage code) emit-side wiring; the aggregator carries the values but `xml/t4.clj` doesn't currently emit them (note 84 §11 Q4).

**Research backing.** doc/research/84-ca-cra-payroll-research-before.md (~9k words, 12 enumerated gotchas, full T4 box catalog, RL-1 carve-out spec, PD7A schedule table, license-clean source list).

Date: 2026-05-18.

---

## ADR-087 — `kontor-payroll-ca` C4.1: Quebec RL-1 emit + TPZ-1015 remittance helper

**Decision.** Extend the `modules/payroll-ca/` adapter (ADR-078) with the QC carve-out — Revenu Québec's parallel filing track that ADR-078 deferred. Four new src files in the existing module (no new module artifact) plus an `:employer-fss` wage type and four chart-of-account additions; eleven new src+test files / namespaces across ~1.4 kLoC.

1. **`kontor.payroll-ca.rl1`** — RL-1 slip aggregator. Reduces a year of `PayrollFacts` for one (person × employer × tax-year × QC) into the box-A..-O catalog per the public RL-1.T-V form (clean-room derivation; see "License posture" below). Box-mapping covers all common kinds (earnings → A; commission → A+M; taxable benefits → A+L; QPP/QPP2/QPIP/EI/QC-ITX/RPP/union-dues/charitable-donations/insurable-earnings/pensionable-earnings → B/B.A/H/C/E/D/F/N/I/G respectively). Open-set extension via `:rl1-extras-map` for boxes J/K/P-W (consumer-specific income codes). XML element emission uses French element names from the public form (`<Releve1>`, `<Cases>`, `<CaseA>`...) — consumers using a certified RL-1 software may need to remap; the data shape is the load-bearing seam.

2. **`kontor.payroll-ca.rl1-summary`** — RL-1 Summary (`RLZ-1.S` / "Sommaire 1") aggregator + submission envelope. Aggregates the slip vector into the seven Summary totals (Quebec ITX / QPP-employee / QPP2-employee / QPIP-employee / QPP-employer / QPIP-employer / FSS-contribution / slip count) and builds the `<Releves>` root containing `<Transmetteur>` + slip elements + `<Sommaire1>`. Employer FSS contribution + employer QPP/QPIP totals are consumer-supplied (substrate does NOT bundle rate tables; the FSS rate depends on the total-payroll-threshold bracket per Revenu Québec).

3. **`kontor.payroll-ca.tpz1015`** — Monthly source-deduction remittance helper analogous to the existing PD7A helper but for Revenu Québec's four buckets (QC-ITX / QPP / QPIP / FSS). Four remitter types (`:annual :monthly :twice-monthly :weekly`) with the same due-date computation pattern as PD7A. Uses `kontor.payroll-ca.pd7a/sum-postings-by-tag` for the underlying datalog totals — same RP/NEQ-routing seam. Returns `:audit-doc/category :payroll-filing :audit-doc/language :fr` (RQ correspondence is French by convention).

4. **`kontor.payroll-ca.qc-emit`** — `QcPayrollEmitProvider` that emits per-pay-period FR `:audit-doc` rows when QC payroll facts are detected; plus `build-rl1-submission!` that composes the three steps above into a full year-end submission element with the audit-doc tx-data fragment. The existing `CaPayrollEmitProvider`'s warning is suppressed via `:qc-emit-installed? true` opt — the warning now fires only when QC employees exist AND no QC emitter is wired.

**Wage-types catalog addition.** One new component-kind `:employer-fss` (Fonds des services de santé / Quebec Health Services Fund) routed to `:ca-payroll-er-fss` expense + `:ca-payroll-fss` payable; engine-computed amount supplied by the consumer per the bracket-based rate table. Two new accounts in `coa_starter.edn` (`2532 RQ-FSS` liability + `5417 Employer-FSS` expense), plus the `:qc-rq-tpz1015` routing tag added to the four QC liability accounts (alongside the existing `:qc-rq-rl1`).

**License posture (clean-room).** Revenu Québec's RL-1 / RLZ-1.S XSD bundle is **partner-only** (registration gate, unlike CRA's public T619 XSDs). Per CLAUDE.md + ADR-001 we therefore do NOT ship the XSD nor a validator against it. Box names + meanings come from the public forms (RL-1.T-V courtesy translation; RL-1.G-V filing guide; RLZ-1.S-G-V summary guide) — these are facts, not copyrightable. Element names are clean-room derivation from French source documentation ("Cases" for the box container, "Sommaire1" for the summary, "NEQ" / "NAS" for the Quebec NEQ / SIN identifiers). Consumers with the partner XSD plug `kontor.l10n-ca.xml.validation/validate!` against the file out-of-band. The same posture as ADR-076 (DATEV public spec) and ADR-077 (ADP GLI public spec): consume the public format documentation, never lift code.

**Why FR-by-default for audit-docs.** The kernel attr `:audit-doc/language` (ADR-078) is open-set; nil treated as `:en`. The RL-1 + TPZ-1015 audit-docs default to `:fr` per Revenu Québec correspondence convention. Consumers with bilingual reporting wire `:language` per call site as needed (mirrors ADR-078's CRA `:en` default + Sophie/James bilingual e2e test).

**Decision NOT to.** Not shipping CNESST emission (Commission des normes, de l'équité, de la santé et de la sécurité du travail — Quebec workers' comp + labour-standards). Per note 84 §8 + §10.4 #7: CNESST premiums are remitted quarterly via TPZ-1015's annexes; substrate-wise the `:employer-wsib`-equivalent component kind already exists (engines emit it); the dedicated quarterly emit-helper defers to a future C4.2 commit alongside the per-province EHT/WCB filing helpers.

**Decision NOT to.** Not bundling QPP / QPP2 / QPIP / Quebec-ITX rate tables or FSS bracket-rate tables. Per ADR-005 / ADR-071 / ADR-075 the engine is authoritative for the math; kontor records the engine's outputs. The FSS rate-bracket calculation (sub-$1M / $1M-$7M / >$7M payroll thresholds with industry-sector reductions) is non-trivial and changes annually; the consumer's engine (Ceridian / ADP-CA / Wagepoint / a Steuerberater-equivalent) supplies the computed contribution.

**Decision NOT to.** Not auto-filing or submitting to the Revenu Québec partner endpoint. The Revenu Québec online filing requires a transmitter number (NP-prefixed 8-character ID, partner-registered as of 2006); credential management lives in the consumer's deployment plane (mirrors ADR-005's "no bundled credentials"). kontor produces the XML; the consumer's ops uploads it.

**Decision NOT to.** Not emitting RL-2 / RL-25 / RL-31 / RL-32 slips (the other RL series). RL-1 covers employment income; RL-2 covers retirement / annuities; RL-25 covers profit-sharing; RL-31 covers landlord-to-tenant; RL-32 covers tips / gratuities pooled. Each is a separate XSD family and a separate filing track; out of payroll-ca scope.

**Test discipline.** 4 new test namespaces / **40 new tests** / ~120 new assertions in `modules/payroll-ca/test/kontor/payroll_ca/`:
- `rl1_test` — payroll-facts → RL-1 slip mapping (12 deftest covering Box A/B/B.A/C/E/F/G/H/I/L/M/N aggregation, commission double-routing to A+M, taxable-benefit double-routing to A+L, QPP2 detection, missing-key validation, report-type code mapping R/A/D, zero-amount omission, QC filtering predicate, audit-doc default-FR-language, box-mapping catalog membership).
- `rl1_summary_test` — Summary aggregation across slips + envelope XML (6 deftest covering box-21/22/22.1/23/27/28/30 totals across multi-slip vectors, zero-default for unsupplied employer contributions, missing-key validation, summary element emission with employer name + contact + amended report-type, full Releves envelope shape).
- `tpz1015_test` — Four-bucket totals + remitter-type schedule (9 deftest covering remitter-type vocabulary, monthly/twice-monthly/annual due-date computation, unknown-type throw, four-bucket sum across pay runs, RP-routing filter isolation, audit-doc FR-default + language override).
- `qc_emit_test` — QcPayrollEmitProvider + warn-suppression + build-rl1-submission! (10 deftest covering QC-only audit-doc emission, no-op when no QC, language override, warn-suppression when emitter installed, warn-fires-by-default, single-arity backward compat, full RL-1 submission build with QC filtering + slip-reference threading + Sommaire1 envelope, validation throws).
- Plus 2 new deftest in `e2e_test` — `qc-no-warn-when-emitter-installed` + `qc-rl1-end-to-end` (the C4.1 acceptance criterion: TPZ-1015 four-bucket totals from real datahike postings + build-rl1-submission! against the bootstrapped CA inc, with QC filtering verified by SIN check).

**Effort.** ~0.5 maintainer-day on top of C4: the four new namespaces reuse the C4 box-aggregator pattern + the existing PD7A `sum-postings-by-tag` helper; the chart-of-accounts addition is two rows of EDN; the wage-type addition is one map entry. The largest single artifact is `rl1.clj` (~280 lines) — comparable to `t4_builder.clj` (326 lines).

**Open followups (P2 — land before C5).**
- **P2** — CNESST quarterly remittance helper (parallel of TPZ-1015 + the per-province EHT/WCB story). Out of C4.1 scope per ADR-078 §"per-province".
- **P2** — Statutory-holiday-aware TPZ-1015 due-date shift (parallel of the PD7A P2 from ADR-078).
- **P2** — Partner-XSD validator pass: if/when the partner XSD becomes available out-of-band, surface a `kontor.payroll-ca.rl1/validate-against-partner-xsd!` shim that does the JAXP roundtrip (the existing `kontor.l10n-ca.xml.validation/validate!` is generic enough to reuse — the shim just locates the XSD file).
- **P2** — RL-1 box-J (private health-services-plan employer contribution) wiring; consumer-extension `:taxable-benefit-private-health` recommended; route to `:j` via `:rl1-extras-map` until a substrate-wide pattern emerges.
- **P2** — `run-payroll!` orchestrator should accept a `:qc-emit-provider` opt parallel to `:emit-provider` so the C4.1 wiring is first-class rather than consumer-composed (currently the QC emitter is wired alongside, not threaded). Same shape as the ADR-078 P1 backlog item "thread `:ledgers-map`".

**Research backing.** ADR-078 §"QC carve-out" + note 84 §8 + the public Revenu Québec form documentation (URLs cited per-namespace in the docstrings + verified 2026-05-18):
- RL-1 form (RL-1.T-V): https://www.revenuquebec.ca/en/online-services/forms-and-publications/current-details/rl-1-t/
- RL-1 filing guide (RL-1.G-V): https://www.revenuquebec.ca/en/online-services/forms-and-publications/rl-1-g-v/guide-to-filing-the-rl-1-slip-employment-and-other-income/
- RL-1 Summary guide (RLZ-1.S-G-V): https://www.revenuquebec.ca/en/online-services/forms-and-publications/rlz-1-s-g-v/guide-to-filing-the-rl-1-summary-summary-of-source-deductions-and-employer-contributions/
- Sending RL Slips Online: https://www.revenuquebec.ca/en/businesses/rl-slips-and-summaries/sending-rl-slips-and-summaries/sending-rl-slips-and-summaries-to-revenu-quebec/online/
- Amending RL Slips: https://www.revenuquebec.ca/en/businesses/rl-slips-and-summaries/amending-or-cancelling-rl-slips-or-summaries/amending-rl-slips/
- TPZ-1015 (monthly variant TPZ-1015.R.14.1-V): https://www.revenuquebec.ca/en/online-services/forms-and-publications/current-details/tpz-1015-r-14-1-v/

Date: 2026-05-18.

---

## ADR-076 — `kontor-payroll-de-datev`: the DE-DATEV-LODAS adapter (Stage R C2)

**Decision.** Land the first concrete `PayrollComputeProvider` / `PayrollPostingBuilder` / `PayrollEmitProvider` triple as `modules/payroll-de-datev/`, per the C2 plan in research notes [[79-hr-payroll-stage-r-plan]] §5.1 and [[82-de-datev-lodas-research-before]]. The adapter is a **clean-room connector around DATEV** — it consumes / produces DATEV's public file-format specifications (LODAS Importdatei + EXTF Buchungsstapel Schema 510 v21) and does **not** re-implement DE jurisdictional payroll math (EStG / SGB / ELStAM / DEÜV / GKV) which stays inside the DATEV appliance.

Five module files (~1.2 kLoC):

1. **`wage_types.clj`** — consumer-supplied wage-type catalog shape (`{:catalog/coa :skr04 | :skr03, :catalog/wage-types {<Lohnart-Nr> {:kind :base-salary | :base-wage | …, :account-hint :gehalt | :lohn | …}}}`) + the 10 load-bearing payroll account defaults for SKR04 + SKR03 (per note 82 §4.1). Validates the Personio convention (`Lohnart < 9000 ⇒ Bezug`; `≥ 9000 ⇒ Netto-Bezug/Abzug`; note 82 §6.1) so a misconfigured catalog rejects at install time rather than routing to the wrong LODAS table.

2. **`compute.clj`** — `DatevLodasComputeProvider` parses the **EXTF Buchungsbeleg** (Lohn-Buchungsbeleg / Report 80 export) into `PayrollFacts` (note 82 §3 + §3.4). Asserts the Verrechnungskonto (SKR04 3790 / SKR03 1755) zero-balance invariant per (Pnr, period) group; a corrupt Buchungsbeleg fails-loud per note 82 §9.4 gotcha 12. Aggregates per-fact `:gross :net :withholding-tax :employee-si :employer-si` from the Bruttomethode posting pattern (note 82 §4.2).

3. **`posting_builder.clj`** — `DatevLodasPostingBuilder` materializes the 10-leg Bruttomethode GL postings per `PayrollFacts` (Dr Gehalt / Cr Verrechnung; Dr Verrechnung / Cr Verb-Lohn; Dr Verrechnung / Cr Verb-LSt; Dr Verrechnung / Cr Verb-SV; Dr Soziale Aufw. / Cr Verb-SV). Resolves `:compensation-component/kind` → SKR04 (or SKR03) Konto via the consumer's `:accounts` map first, then the validated catalog's `account-overrides`, then `default-account-maps[coa]` (note 82 §4.4). Plus the simplified HGB §249 PTO accrual sketch from note 82 §5.1 (`urlaubsrueckstellung-amount` + `urlaubsrueckstellung-tx-data`) — fit-for-Mittelstand, takes a `:framework :hgb-handelsbilanz | :de-steuerbilanz` parameter so HGB / Steuerbilanz parallel-ledger postings can be composed (ADR-021).

4. **`emit.clj`** — `DatevLodasEmitProvider` writes the **LODAS Importdatei**: a 4-section (`[Allgemein] / [Satzbeschreibung] / [Stammdaten] / [Bewegungsdaten]`) ISO-8859-1 / CR-LF / semicolon-delimited ASCII file per note 82 §2. Per-fact compensation components surface as `u_lod_bwd_buchung_standard` Bewegungsdaten rows; unmapped components route to `:unmapped` and the result counts surface as `:audit-doc/unmapped-count` so consumers can drive a manual-review queue (note 82 §6.3.5). The emit produces one `:audit-doc/category :tax-filing` entity per pay-period.

5. **`core.clj`** — installer (registers four extra `:audit-doc/*` attrs for the LODAS payload + reconciliation metadata) + re-exports of the three `make-*-provider` constructors and the Urlaubsrückstellung helpers.

**Why public-spec, not lifted code.** DATEV's Schnittstellenhandbuch LODAS (45. Auflage 2016 + 2025-06 update) + the EXTF Buchungsstapel Schema 510 v21 (used by Personio, Sage, Lexware, sevDesk, ADDISON, Circula — every Mittelstand HR system) are **vendor-cooperative specifications** published as customer-facing interop standards. SKR04 / SKR03 account numbers + names are factual data not subject to EU copyright per the existing `kontor.l10n-de.chart` posture. The parser/encoder reads only the format spec; no proprietary code, no bundled wage-type catalog, no bundled SV-Beitragssatz table, no bundled BBG rate, no LODAS API credentials — same posture as ADR-005 / ADR-071 / ADR-075. The consumer's Steuerberater configures the wage-type catalog at install time (mirrors Personio's Mittelstand reference shape per note 82 §6.1).

**Why one module, both LODAS + LuG.** Note 82 §2.6 / §9.4 gotcha 11: the LODAS vs Lohn-und-Gehalt format overlap is high (the only differences are `Ziel=LODAS` vs `Ziel=LuG` in `[Allgemein]` and the `[Satzbeschreibung]` table-name namespace). A single module with `:datev-target :lodas | :lug` config is cheaper than two near-duplicate modules.

**Why ship the Bruttomethode-zero invariant in the parser.** Per note 82 §11.18 + §4.2: the 3790 / 1755 Verrechnungskonto MUST net to zero per (Pnr, period) group in a correctly-generated Buchungsbeleg. Asserting this at parse time catches truncated / hand-edited / wrong-Mandanten files immediately, surfacing them as `:payroll-run/state :buchungsbeleg-invalid` rather than silently posting garbage into the GL. The invariant is jurisdiction-specific so it lives in C2, not the substrate.

**Why simplified PTO accrual now, actuarial pensions later.** Per note 82 §5.1: HGB §249 Abs. 1 forces Urlaubsrückstellung as a Handelsbilanz liability; the simplified formula (annual gross + AG-SV add-on / regelmäßige Arbeitstage × accrued vacation days) is fit-for-Mittelstand without an actuary (note 82 §5.1 / Haufe / hrworks references). Pensionsrückstellungen (note 82 §5.3) require Anwartschaftsbarwert / -deckungsverfahren actuarial valuations with HGB §253 Abs. 2 discount rates — out of substrate scope; a future `kontor-pension-actuary-de` companion would integrate Heubeck-Richttafeln. The C2 module ships the PTO algorithm + a documented seam for the consumer-supplied pension valuation.

**Decision NOT to.** Not emitting DEÜV / GKV-Monatsmeldung / SV-Beitragsnachweis / ELStAM / A1-Bescheinigungen / Berufsgenossenschafts-Meldung / Lohnsteueranmeldung. Per note 82 §8.1: all jurisdictional event-bus emissions stay inside the DATEV-Rechenzentrum, triggered automatically by LODAS after the pay-period close. kontor only emits the LODAS Importdatei (the inbound variable-input + master-data payload).

**Decision NOT to.** Not bundling a DATEV Standardlohnarten catalog. Every customer's Steuerberater configures the LODAS Festbezugstabelle; even Personio with hundreds of customers cannot ship a universal mapping (note 82 §6.1). Catalog validation lives in `wage_types.clj`; default account-hint → SKR04 / SKR03 mappings DO ship (the 10 load-bearing accounts only, all derived from public DATEV cooperative standard).

**Decision NOT to.** Not implementing the `DatevOnlineEmitProvider` (Lohnimportdatenservice OAuth2 API). Per note 82 §8.2: out of C2 scope; requires per-customer DATEV-RZ credentials. C2 ships the `LocalfileEmitProvider` shape (writes the file to a directory the Steuerberater uploads manually).

**Decision NOT to.** Not fixing the existing `modules/l10n-de/resources/kontor/l10n_de/skr04.edn` chart in this commit. Per note 82 §4 head: the existing chart already carries `6020` for Gehälter — the load-bearing payroll account the e2e flow needs. The C2 module is self-contained on the consumer-supplied / catalog-default account map seam; a separate `kontor-l10n-de` PR can audit + fill in the remaining SKR04 account-numbering gaps. **P2 followup** documented for the next review pass.

**Test discipline.** **38 tests / 129 assertions in 5 namespaces** under `modules/payroll-de-datev/test/`:
- `wage_types_test.clj` — 10 tests / 14 assertions on catalog validation invariants + account resolution (SKR04 default, SKR03 default, overrides, the bezug-vs-netto range rule).
- `emit_test.clj` — 11 tests / 40 assertions on the LODAS Importdatei format compliance (CR/LF discipline, semicolon escaping with quotes, section markers, ordinal-keyed Satzbeschreibung, Bewegungsdaten row mapping incl. employer-side skip + unmapped surfacing, full Importdatei assembly, audit-doc shape).
- `compute_test.clj` — 7 tests / 29 assertions on EXTF Buchungsbeleg parsing (header field extraction, single-fact-per-Pnr-per-period grouping, the 3790-balanced invariant fires on corrupt files, multi-employment Pnr→eid mapping, pre-parsed-facts pass-through).
- `posting_builder_test.clj` — 9 tests / 28 assertions on SKR04 + SKR03 account resolution, balanced 10-leg Bruttomethode output, ledger tagging, Urlaubsrückstellung HGB / Steuerbilanz formulae + balanced posting pair.
- `e2e_test.clj` — 1 test / 18 assertions on the full `run-payroll!` round-trip through `kontor.process` against a DE GmbH fixture: parse Buchungsbeleg → derive PayrollFacts → build SKR04 postings → emit LODAS Importdatei → verify control totals + per-account amounts (6020 / 6110 / 3720 / 3730 / 3740 / 3790) + audit-doc payload.

The test fixture (`resources/.../fixtures/buchungsbeleg-2025-11.csv`) is a synthetic 5-row EXTF Buchungsstapel illustrating the Bruttomethode pattern from note 82 §3.3 / §4.2 (4000 € Bruttogehalt → 2500 € Nettoauszahlung; 700 LSt + 800 employee SV + 800 employer SV). No real-customer data; format aligned with the public DATEV-Community examples cited in note 82 §11.

**Effort.** ~1 maintainer-day (planned 6+ in note 82's effort estimate; the savings came from heavy reuse of the existing `kontor.l10n-de.datev` EXTF encoder discipline and the substrate's `kontor.process` + `transact-with-validation` gate stack already doing the heavy lifting). The PTO accrual + the LODAS Stammdaten emit slot landed lean; pensions + DEÜV / GKV emission remain consumer-supplied / DATEV-side per note 82 §5.3 + §8.1.

**P1 / P2 followups for the review-after pass.**
- **P1**: The substrate orchestrator `kontor.hr.payroll/run-payroll!` transacts the `emit-docs` but doesn't link them via `:payroll-run/emit-docs`. The e2e test queries by `:audit-doc/payroll-period` instead. The cleaner fix is to extend the substrate orchestrator to thread the emit-doc tempids back into `:payroll-run/emit-docs` (one-line change in `run-payroll!`). Documented in `modules/payroll-de-datev/test/kontor/payroll_de_datev/e2e_test.clj` (the comment cites this ADR).
- **P2**: The existing `modules/l10n-de/resources/kontor/l10n_de/skr04.edn` ships only `6020` (Gehälter) from the personnel-expense subset. The remaining 9 load-bearing accounts (6010 / 6035 / 6060 / 6110 / 6130 / 3066 / 3720 / 3730 / 3740 / 3790) deserve seeding by a future `kontor-l10n-de` PR so SKR04 consumers don't have to hand-write the chart entries.
- **P2**: The Stammdaten emit slot is empty in this version (only Bewegungsdaten emit). Adding `u_lod_psd_mitarbeiter` + `u_lod_psd_beschaeftig` + `u_lod_psd_monatslohn` rows (per note 82 §2.3) means a new-hire / salary-change / termination event delta surfaces in the LODAS file. Consumers can already emit Bewegungsdaten today; the Stammdaten extension is straight-forward growth.
- **P2**: Reconciliation harness — note 82 §3.4 / §8.3 outline a closed loop where the Buchungsbeleg parse reconciles against the prior `[Bewegungsdaten]` emission via `:cross-tx/step-id` (ADR-074). Not implemented in C2; deferred to a `reconciliation.clj` namespace in a follow-up commit.

**Research backing.** doc/research/82-de-datev-lodas-research-before.md (full spec source + the licence + accounting-pattern citations), 79 §5.1 (C2 plan), 81 §9.6 (compensation-component refactor C2 depends on), 72 §1.5 (Personio reference adapter shape).

License posture (final). Format spec public; EXTF v21 column layout public; SKR04 / SKR03 numbering public; Personio / Sage / Lexware / sevDesk / Circula adapter shapes read from public vendor documentation for *pattern*, never their code. No bundled DATEV wage-type catalog; no bundled BBG / Beitragssatz table; no LODAS API credentials. Algorithm sketches (HGB §249 PTO formula, AG-SV Mittelstand 21 %) drawn from cited public sources (Haufe, hrworks, BuchhaltungsButler, rechnungswesen-info.de) and re-derived; no GPL-contaminated reference code lifted.

Date: 2026-05-18.

---

## ADR-081 — `kontor-payroll-br`: BR eSocial payroll adapter (Stage R C7)

**Context.** Brazil is the highest-complexity adapter in the Stage R wave (research note 79 §5.3 originally labeled it C10 because eSocial's 40+ event types make it the most painful per note 73 Theme C P2; we land it as C7 alongside the trans-national wave because a real BR consumer surfaced). The BR payroll engine landscape is fragmented across mid-market (RH Sistemas, Senior HCM, TOTVS Datasul) and SMB (Pluxee — formerly Sodexo Folha — ContaAzul Folha) tiers; all converge on a wage-type-per-row CSV / GL-export shape similar to the US ADP GLI + CA Ceridian Dayforce patterns. The mandatory event-bus is eSocial — a federal SOAP webservice that consolidates labor + tax + social-security data for ALL Brazilian employers since 2018 (rollout phased by company size).

**Decision.** Land the BR-eSocial payroll adapter as `modules/payroll-br/` on top of the Stage R C1 substrate (ADR-075) + the already-shipped `modules/l10n-br/` chart + identifiers + NF-e / SPED Contábil emitters. Seven pieces, no new kernel ADRs:

1. **`kontor.payroll-br.wage-types`** — BR-specific `:component-kind` open-set extension per ADR-075. Maps every BR pay-element kind (`:base-wage`, `:overtime-50`, `:overtime-100`, `:night-shift-addition`, `:hazard-addition`, `:unhealthy-addition`, `:thirteenth-salary`, `:vacation-pay-paid-out`, `:vacation-bonus-paid-out`, `:meal-voucher`, `:transport-voucher`, `:inss-employee`, `:irrf-employee`, `:union-dues`, `:inss-employer` / CPP, `:fgts-employer`, `:sat-rat`, `:outras-entidades`, the three CPC-33 accruals `:ferias-accrual` / `:thirteenth-salary-accrual` / `:severance-fgts-accrual`, the carry-only bases `:inss-base` / `:irrf-base` / `:fgts-base`, etc.) to an `:account-tag` keyword + an `:esocial-rubrica-hint` keyword. Consumer-extensible via an `:extras-map`. Plus `validate-catalog` — throws ex-info on failure per the canonical convention (note 86 §2.5 P2-86-5 — DE/kernel convention, not the US validate's nil-on-success outlier).

2. **`kontor.payroll-br.compute`** — three `PayrollComputeProvider` impls:
   - `RhSistemasGlProvider` — reference CSV adapter with configurable column mapping. The mid-market BR engines (RH Sistemas / Senior HCM / TOTVS Datasul) converge on the same column shape (Brazilian payroll engines follow the SEFIP / GFIP layout norms).
   - `SeniorHcmGlProvider` — same parser shape with `:senior-hcm` provider-id for downstream provenance.
   - `PluxeeCsvGlProvider` — position-based CSV layout (no headers; column position dictates meaning) using `;` as the BR-Excel default field separator. Handles BR-locale decimal commas (`1.234,56`) via the shared `coerce-bigdec` helper.

3. **`kontor.payroll-br.posting-builder`** — `BrPayrollPostingBuilder` impl of `PayrollPostingBuilder`. Per-pay-period BR journal entry: DR wages-expense + DR per-employer-charge expense + DR per-CPC-33 accrual + CR per-statutory-payable bucket + CR Salários-a-pagar. Routes per-CNPJ via the `:cnpj-account-tag` opt that attaches an `:account-tag/name`-keyed `:posting/account-tags` ref to every posting (mirrors the CA-RP-routing pattern from ADR-078 §4 + note 84 §4.2).

4. **`kontor.payroll-br.accrual`** — the three load-bearing BR CPC-33 / IAS-19 accruals as ADR-068 out-of-band tx-data builders. These are mandatory employer obligations under Brazilian labor law:
   - **Férias + 1/3 adicional** — `(monthly-salary / 12) * (1 + 1/3)` (CLT art. 129+ + Constituição art. 7º XVII).
   - **13º salário** — `monthly-salary / 12` (Lei 4.090/62 + Decreto 57.155/65).
   - **Multa rescisória de 40% sobre FGTS** — `fgts-balance * 0.40` (Constituição art. 10 ADCT + Lei 8.036/90; involuntary-termination trigger).

   All three follow the US-payroll convention from ADR-077 §"Parallel-ledger split for accruals": separate ns + `!` wrapper through `transact-with-validation`. Per note 86 §2.3 the substrate ALSO supports the CA-style "engine-emits-accrual-component" pattern when the BR engine reports the accrual inline (the `:ferias-accrual` / `:thirteenth-salary-accrual` / `:severance-fgts-accrual` component-kinds route through the posting-builder's employer-side leg-pair path).

5. **`kontor.payroll-br.esocial`** — XML event builders for the load-bearing subset of the eSocial S-1.3 leiaute. V1 covers eleven events: S-1000 (Informações do Empregador), S-1005 (Tabela de Estabelecimentos), S-1010 (Tabela de Rubricas), S-1020 (Tabela de Lotações Tributárias), S-2200 (Cadastramento Inicial / Admissão), S-2299 (Desligamento), S-2300 / S-2399 (Trabalhador sem vínculo início/término), S-1200 (Remuneração de Trabalhador), S-1210 (Pagamentos), S-1299 (Fechamento dos Eventos Periódicos). All events use the gov.br/esocial S-1.3 leiaute as the schema source; the substrate emits the XML, the consumer's engine signs (ICP-Brasil) and transmits (SOAP webservice) — mirroring the ADR-017 NF-e separation.

6. **`kontor.payroll-br.emit`** — `BrESocialEmitProvider` impl. Per pay-period emits S-1200 + S-1210 per employee + a single S-1299 fechamento. All audit-docs carry `:audit-doc/category :payroll-filing` (canonical vocabulary per note 86 P0-86-2) + `:audit-doc/language :pt-br` (ADR-078's language axis). Plus `terminate-employment-tx-data` (ADR-068) wrapping S-2299 + `hire-employee-tx-data` wrapping S-2200 + `build-table-event-audit-docs` for the on-setup S-1000/1005/1010/1020 events.

7. **`kontor.payroll-br.core`** — installer (registers the three shared `:audit-doc/*` attrs `:audit-doc/inline-payload` / `:audit-doc/payroll-period` / `:audit-doc/payroll-entity` — same shape as `modules/payroll-de-datev/core.clj`; `d/transact` is idempotent so the DE + BR installers compose cleanly) + the payroll chart extension. Layers on top of `kontor.l10n-br.chart/install!`.

**Scope discipline — what v1 ships vs defers.** Per the task brief + ADR-081 §6 the v1 events list deliberately omits:
- **S-1280** BPO substitute employer
- **S-2240** Condições Ambientais de Trabalho (SST events — Saúde e Segurança do Trabalho)
- **S-2250** Aviso Prévio
- **S-2298** Reintegração
- **S-1202** Remuneração RPPS (public-sector regime)
- **S-1207** Benefícios — Entes Públicos
- **S-2205 / S-2206 / S-2210 / S-2220 / S-2230** — change events (alteração cadastral / contratual / CAT / monitoramento saúde / afastamento)
- **S-3000** Exclusão de Evento
- **S-5xxx** return events (kontor consumes; the engine receives the regulator's response payloads)

These land in BR follow-ups when consumer demand surfaces.

**License posture (same as ADR-005 / ADR-071 / ADR-075 / ADR-077 / ADR-078).** No code lifted from any BR engine. The eSocial XSDs + leiaute manuals at gov.br/esocial are public regulator publications — we read the schemas as facts and emit independent XML. CNPJ / CPF mod-11 checksum algorithms come from `kontor.l10n-br.identifiers` (independently derived from RFB Instrução Normativa publications). The BR Plano de Contas Referencial codes come from `kontor.l10n-br.chart` (already-shipped per ADR-019). The three CPC 33 accrual formulae (1/12 monthly, 1/3 constitutional, 40% severance) are statutory facts. NO bundled INSS / IRRF / FGTS / Salário-Família rate tables (these are regulator policy + change frequently; the engine is authoritative); NO bundled vendor API credentials (consumer holds OAuth / certificate / endpoint); NO bundled rubrica catalog (each company's eSocial S-1010 Tabela de Rubricas is unique to its payroll engine + Acordo Coletivo / Convenção Coletiva — consumer supplies the engine→kontor `:rubrica-codes` mapping at provider construction time).

**What kontor does NOT do (scope discipline).**

- **NO BR gross-to-net implementation.** INSS / IRRF / FGTS / Salário-Família / Salário-Maternidade / SAT-RAT / outras-entidades calculations — the engine (RH Sistemas / Senior / Pluxee / Datasul / ContaAzul) is authoritative. kontor consumes the result.
- **NO ICP-Brasil signing.** Consumer holds the cert (A1 PFX or A3 token); signing happens in the consumer's engine OR a separate signing partner. Mirrors ADR-017's NF-e separation.
- **NO eSocial WS transmission.** The consumer's engine handles SOAP, ack handling, and S-5001 / S-5011 consolidated-return reception.
- **NO Reforma Tributária do Consumo (LC 214/2025) reflection on payroll.** CBS / IBS / IS are indirect-tax-side concerns (NF-e XML groups per note 80); payroll is unaffected.
- **NO eSocial substitute-employer / BPO event handling.** S-1280 + S-1295 deferred to BR follow-ups.

**Decision NOT to.** Not bundling vendor rubrica catalogs (the RH Sistemas / Senior / Pluxee code lookups). Consumer supplies `:rubrica-codes` map at provider construction time. The same posture as ADR-005 / ADR-071 / ADR-072 / ADR-075 / ADR-078 — consumer holds the engine.

**Decision NOT to.** Not collapsing the four canonical BR statutory buckets (INSS-empregado / INSS-empregador / FGTS / IRRF). They MUST stay distinct because they have distinct DARF codes, distinct due dates, and distinct GFIP / eSocial S-1210 lines. Tests explicitly assert four DISTINCT accounts and exercise the leg routing.

**Decision NOT to.** Not implementing a CBS/IBS-payroll bridge. The 2026 Reforma Tributária mandates affect indirect-tax NF-e XML groups; payroll is unaffected. When the IS — Imposto Seletivo lands a payroll-incidence rule (currently it doesn't), a future ADR addresses it.

**Decision NOT to.** Not bundling INSS / IRRF / FGTS rate tables. These are regulator policy + change frequently; the engine is authoritative. Same posture as ADR-005.

**Test discipline.** Six test namespaces under `modules/payroll-br/test/kontor/payroll_br/`:
- `wage_types_test` — catalog membership + four-statutory-bucket distinctness + CPC-33 accrual-flag coverage + `validate-catalog` throw-on-failure convention + extras-map extension.
- `compute_test` — RH Sistemas + Senior HCM + Pluxee CSV parsers + BR-locale comma decimal handling + provider-id assertions + per-employee fact assembly.
- `posting_builder_test` — rubrica → CoA mapping + four-distinct-payable-buckets + CNPJ routing + CPC-33 accrual leg pairs + balanced-postings invariant + VR/VT routing.
- `accrual_test` — three CPC-33 formula correctness + `:include-employer-charges?` toggle + `:turnover-fraction` for severance + tx-data balanced postings + HALF-EVEN rounding discipline.
- `esocial_test` — eleven event-builder shapes + CNPJ / CPF validation throw-on-invalid + termination-cause keyword mapping + XML well-formedness round-trip through clojure.data.xml.
- `emit_test` — `BrESocialEmitProvider` produces S-1200 + S-1210 per fact + 1 S-1299 fechamento per pay-period + table-event builders + termination + hire tx-data builders.
- `e2e_test` — full BR pay-run through `run-payroll!` against an Acme do Brasil Ltda fixture with two employees; asserts the four statutory buckets land on distinct accounts, eSocial emit-docs are produced with `:payroll-filing` category + `:pt-br` language, payload XML contains the rubrica codes.

The fixtures (`resources/.../fixtures/rh_sistemas_sample.csv` + `pluxee_sample.csv`) use valid mod-11 CPFs / CNPJs (`11144477735`, `12345678909`, `11.222.333/0001-81`) drawn from the existing `kontor.l10n-br.identifiers-test` corpus.

**Effort.** ~2 maintainer-days for C7 (8 src namespaces + 6 test namespaces + 2 CSV fixtures + chart extension + this ADR). The BR adapter is more verbose than C4 (CA) because eSocial's eleven-event surface dwarfs the CRA T619 + PD7A scope, but most of the cognitive work was front-loaded — the parser pattern reuses the CA Ceridian shape, the posting builder reuses the CA leg-pair pattern, the emit-provider reuses the DE-LODAS audit-doc shape with `:audit-doc/category :payroll-filing` + `:pt-br`. The eSocial XML builders are the novel work (~600 LOC for the eleven event types).

**Followups for review-after.**
- **P1** — XSD validation: bundle the gov.br/esocial S-1.3 XSDs (public regulator publications, BR-government work) under `test/resources/esocial/xsd/` and add per-event XSD validation in `esocial_test.clj`. Current well-formedness round-trip is a P2 substitute.
- **P1** — Live rubrica-binding fixture: the test fixtures use synthetic rubrica codes (R001 / R200 / R210 / R900 / R901). A real BR consumer surfaces engine-specific catalogs; sample a public engine reference (RH Sistemas / Pluxee customer docs) for a more realistic fixture set.
- **P2** — S-1280 BPO + S-1295 substitution (a real BR consumer running a PEO / BPO arrangement).
- **P2** — S-2240 SST (Saúde e Segurança do Trabalho) events; gates on a BR consumer with hazardous-work exposure.
- **P2** — S-2250 Aviso Prévio + S-2298 Reintegração — these are termination-flow extensions; current code handles S-2299 only.
- **P2** — Reforma Tributária IS — Imposto Seletivo if a payroll-incidence rule lands (currently no impact).
- **P2** — DSAR collector for `:person` data tied to BR `:employment` rows; mirrors P1-86-5 carry-over from note 86.

**Research backing.** doc/research/79 §5.3 (C-wave plan + the BR-as-most-complex framing); 73 Theme C (multi-country pain — BR cited as P2); gov.br/esocial S-1.3 leiaute manual (public regulator publication); ADR-081 derives the BR conventions from public sources only.

Date: 2026-05-18.

---

## ADR-084 — `kontor-payroll-jp`: JP payroll adapter (Stage R C10)

**Decision.** Land the JP payroll adapter as a new companion module `modules/payroll-jp/` on top of the Stage R C1 substrate (ADR-075) + the already-shipped `modules/l10n-jp/` (chart + 法人番号 / QIS identifiers + 消費税 / JCT machinery + invoice + closing). Six pieces, no new kernel ADRs — the `:audit-doc/language` slot ADR-078 added already covers `:ja`; the `:audit-doc/privilege :pii-sensitive` + `:audit-doc/category :hr-personnel` already exist for My Number discipline.

1. **`kontor.payroll-jp.wage-types`** — JP-specific `:component-kind` open-set extension. Maps every JP pay-element kind to an `:account-tag` keyword and (where applicable) a `:gensen-box` keyword for the year-end 源泉徴収票. Covered: earnings (`:base-wage`, `:overtime`, `:bonus`, `:commuting-allowance`, `:housing-allowance`, `:family-allowance`, `:position-allowance`), 4-bucket statutory SI (`:employee-health-insurance` / `:employer-health-insurance` for 健康保険; `:employee-pension` / `:employer-pension` for 厚生年金; `:employee-employment-insurance` / `:employer-employment-insurance` for 雇用保険; `:employee-long-term-care` / `:employer-long-term-care` for 介護保険 — age-40-gated), tax withholding (`:income-tax-withheld` / `:resident-tax-withheld`), and voluntary deductions (`:zaikei-savings` for 財形貯蓄, `:union-dues` for 組合費, `:voluntary-deduction` catch-all). Carry-only kinds for Gensen inputs flagged `:posts? false`. Consumer-extensible via an `:extras-map`. Per ADR-084 §10.1.

2. **`kontor.payroll-jp.compute`** — four `PayrollComputeProvider` impls:
   - `FreeeProvider` — freee人事労務 CSV with the standard 区分 column (支給 / 控除 / 集計) and configurable column mapping. freee is the largest SaaS payroll engine in Japan by SMB share.
   - `MoneyForwardProvider` — Money Forwardクラウド給与 CSV with English snake_case headers.
   - `YayoiProvider` — 弥生給与 desktop CSV with Kanji column headers (similar pattern to freee).
   - `PcaKyuyoApiProvider` — 給与奉行 cloud API skeleton (partner-program-gated; throws helpful error until consumer wires OAuth).
   All four share the `:pay-element-codes` consumer-supplied mapping and `:column-mapping` configurable column names per ADR-084 §2. CSV parsing handles UTF-8 BOM (freee + MF emit with BOM) and CJK column header preservation.

3. **`kontor.payroll-jp.posting-builder`** — `JpPayrollPostingBuilder` impl. Per-pay-period JP journal entry: DR 給料手当 (wages-expense) + DR 賞与 (bonus-expense, separate per J-GAAP convention) + DR 法定福利費 (employer SI expense rolled up) + CR per-bucket 預り金 (Azukari-kin / holding) for each of the 4 SI buckets + CR 預り金 — 所得税 + CR 預り金 — 住民税 + CR 未払金 (net wages). The 4 SI buckets stay distinct because each cash-payment cycle targets a different agency (年金事務所 / 健保組合 / 税務署 / 市区町村). JPY rounds HALF-EVEN to whole yen per leg (ADR-013 precision-0).

4. **`kontor.payroll-jp.accrual`** — two accrual families:
   - **`bonus-accrual-tx-data`** + `bonus-accrual-amount` helper — 賞与引当金 monthly delta toward the next semi-annual (夏季 / 冬季) payout. The Japanese matching principle requires accruing bonus expense over the 6 periods that earned it. Per ADR-084 §6.
   - **Four per-bucket SI employer-side accrual primitives** — `health-insurance-accrual-tx-data` (健康保険), `pension-accrual-tx-data` (厚生年金), `employment-insurance-accrual-tx-data` (雇用保険), `long-term-care-accrual-tx-data` (介護保険). Each emits a DR 法定福利費 + CR 預り金 pair so per-bucket cash reconciliation is clean.

5. **`kontor.payroll-jp.gensen`** — year-end 源泉徴収票 (Gensen Choshu Hyo / Annual Withholding Tax Statement) aggregator. Reduces a year of `PayrollFacts` for one (person × employer × tax-year) into a structured `:gensen/*` map carrying the load-bearing boxes: 支払金額 (payment-amount), 源泉徴収税額 (withholding-amount), 社会保険料等 (social-insurance-paid), plus opaque carry-only slots for engine-computed 給与所得控除後の金額 / 課税対象額 / 配偶者控除 / 扶養控除. Resident tax (住民税) is INTENTIONALLY OMITTED from the Gensen (it goes to municipalities via 給与支払報告書, NOT to NTA). Bonus components roll into 支払金額 alongside monthly earnings — engine handles the bracket math (賞与表 vs 月額表) inside `:income-tax-withheld`. Per ADR-084 §7.

6. **`kontor.payroll-jp.emit`** — `JpPayrollEmitProvider` impl + `build-gensen-audit-doc-tx-data` + `record-my-number-attestation-tx-data` + `warn-if-my-number-leaked!` PII discipline helper. The provider emits one `:audit-doc/category :payroll-filing` row per payroll run with `:audit-doc/language :ja`. The Gensen audit-doc carries the same category + language for the year-end statement. The My Number attestation builder is the load-bearing PII helper (see below).

**My Number (個人番号 / Kojin Bangō) discipline.** ADR-084 §1 documents the load-bearing PII story: kontor NEVER stores the 12-digit My Number value. The value lives in the consumer's privileged store (encrypted at rest, access-gated by the consumer's auth layer). kontor records ONLY the attestation metadata via `record-my-number-attestation-tx-data`, producing an `:audit-doc` row with `:audit-doc/category :hr-personnel` + `:audit-doc/privilege :pii-sensitive` + `:audit-doc/language :ja`. The kernel substrate already has these three facets (ADR-051 privilege; ADR-075 category; ADR-078 language); no new kernel attr added. Consumers gate downstream access via kontor-authz (ADR-065/066); retention follows kontor.retention (ADR-050) keyed on `:retention-policy/category :hr-personnel`. Two helper functions surface PII discipline violations: `pii-employees-in-facts` returns the set of employments whose `PayrollFacts` accidentally carry an inline `:my-number` or `:個人番号` slot, and `warn-if-my-number-leaked!` logs a loud `[ERROR]` to `*err*` for engine-configuration audits.

**Decision NOT to.** Not bundling per-prefecture 健保 (Kenpo) SI rate tables — every prefecture has its own rate (e.g. 東京都 vs 大阪府 vs 北海道), and rates change annually each April. Per ADR-084 §2.5 the engine is authoritative for the math; the consumer supplies a configured engine with the current rates.

**Decision NOT to.** Not bundling vendor pay-element catalogs (freee's 項目名 vocabulary, MF's `item_code` lookup, Yayoi's 支給控除項目). Consumer supplies `:pay-element-codes` map at provider construction time (mirrors the ADR-005 / ADR-071 / ADR-072 / ADR-075 / ADR-078 "consumer holds the engine" pattern).

**Decision NOT to.** Not implementing 退職給付引当金 (Taishoku Kyufu Hikiatekin / retirement-benefit provision). ASBJ Statement No. 26 requires actuarial valuation (退職給付債務 / PBO equivalent + discount-rate selection). Out of substrate scope; deferred to a future `kontor-pension-actuary-jp` companion that would integrate the 退職給付に係る会計基準 actuarial tables. Per ADR-084 §7.

**Decision NOT to.** Not implementing 給与支払報告書 (Kyuyo Shiharai Hokokusho / Year-end Salary Payment Report to municipalities). This is the per-municipality companion to the NTA-bound Gensen; same per-employee tape, different recipient. Deferred to a future v2 since each municipality has its own filing format and the engine (freee / MF / Yayoi) typically handles this directly. Per ADR-084 §7.4.

**Decision NOT to.** Not implementing 法定調書合計表 (Hotei Chosho Goukei-hyo / Statutory Documents Summary). This is the NTA cover sheet that accompanies the Gensen submission tape. Deferred — the engine handles this for v1. Per ADR-084 §7.5.

**Decision NOT to.** Not implementing real-time JP payroll clearance — Japan has no mandatory event-bus reporting regime equivalent to UK FPS / AU STP / BR eSocial. The Gensen is annual + paper-friendly; monthly SI payments via 日本年金機構 happen via the consumer's bank channel without kontor involvement. The `JpPayrollEmitProvider` produces an audit-doc summary for the audit chain but does not transmit anything to a regulator.

**Decision NOT to.** Not implementing 年末調整 (Nenmatsu Chosei / year-end tax adjustment) brackets. The engine is authoritative — freee, MF, and Yayoi each ship calibrated bracket math for the 源泉徴収税額表 (月額表 + 賞与表) which updates each year. kontor consumes the engine's already-adjusted `:income-tax-withheld` for the December pay-period (which carries the full-year true-up).

**Test discipline.** 64 tests / 223 assertions across six namespaces in `modules/payroll-jp/test/kontor/payroll_jp/`:
- `wage_types_test` — catalog membership + employer-side flag + payable-tag routing + Gensen-box mapping + age-40-flag + carry-only kinds + Kanji labels + extras-map extension (9 tests / 70 assertions).
- `compute_test` — freee + Money Forward + Yayoi CSV parsers + protocol invocation + unknown-pay-element rejection + PCA-API skeleton error (9 tests / 30 assertions).
- `posting_builder_test` — pay-element → CoA mapping + bonus-separate-from-wages + 4-bucket SI routing + employer-side leg pairs + JPY rounding + ledger stamp + missing-tag-throws (11 tests / 23 assertions).
- `accrual_test` — bonus-accrual-amount helper + bonus-accrual-tx-data balance + four per-bucket SI accruals + missing-key rejection + ledger stamp (11 tests / 22 assertions).
- `gensen_test` — 12-month aggregation + resident-tax-NOT-on-Gensen + bonus-rolls-into-payment-amount + carry-only-from-jurisdiction-codes + whole-yen rounding + multi-employee submission + mandatory-key rejection (7 tests / 23 assertions).
- `emit_test` — payroll-filing/ja audit-doc category+language + Gensen audit-doc shape + storage-uri + My Number attestation (PII-sensitive + hr-personnel + deterministic code) + My Number leak detection (14 tests / 35 assertions).
- `e2e_test` — full `run-payroll!` for a 3-employee JP KK (junior/senior/Osaka profiles) + bonus accrual transact + 14-fact year-end Gensen aggregation (3 tests / 20 assertions).

**Effort.** ~1.5 maintainer-day. Six src namespaces + six test namespaces + two CSV fixtures + the ADR + 75-line coa_starter.edn extending modules/l10n-jp/. Heavy reuse: the kontor-payroll-ca module supplied the per-fact assembly pattern + the payroll-run linkage seam, modules/l10n-jp/ supplied the JPY commodity + Corporate Number validators.

**License posture (final).** No NTA / Nenkin Kiko form code lifted. CSV column shapes described from public vendor support docs (`support.freee.co.jp`, `biz.moneyforward.com/support`, `support.yayoi-kk.co.jp`). No vendor API keys / OAuth secrets bundled. No proprietary pay-element catalog bundled (consumer supplies via `:pay-element-codes`). No per-prefecture 健保 SI rate tables bundled (engine is authoritative). Kanji labels in the wage-type catalog are factual terminology from public NTA / 厚労省 references; not copyrightable. The Corporate-Number check-digit algorithm in `modules/l10n-jp/identifiers.clj` is mathematically derived from the NTA's published procedure (a fact, not protected expression).

**Open followups for review-after.**
- **P1** — Live freee / Money Forward / Yayoi CSV golden fixtures from a real consumer; current fixtures are synthetic. Compute providers all parse the format shape; field-level tolerance for engine quirks (mid-year transfers, retroactive adjustments, 中途入社) needs real samples.
- **P1** — 賞与 (bonus) bracket math is engine-side — but the kontor side of the bonus-payout posting needs to handle the reversal of the prior accrual cleanly. Currently each bonus pay-period emits a `:bonus` component the posting builder routes to the 賞与 expense; the consumer's process needs a `bonus-payout-reversal-tx-data` companion that DR's the 賞与引当金 liability + CR's a clearing account against which the engine's payout posts. Deferred to a follow-up commit.
- **P2** — 給与支払報告書 (per-municipality salary-payment report) emitter. The data is in the Gensen statement; routing per-employee to per-municipality is the missing piece (each municipality has its own filing format).
- **P2** — 法定調書合計表 (NTA cover sheet for Gensen submissions). Aggregates totals across all Gensens submitted; deferred.
- **P2** — 退職給付引当金 actuarial valuation companion (ASBJ Statement No. 26). Needs Heubeck-equivalent JP discount-rate + life-table tooling; out of scope for v1 substrate.
- **P2** — PCA 給与奉行 cloud API live wiring (skeleton ships; full implementation gates on partner-program access).
- **P2** — `kontor-l10n-jp-edinet` companion for XBRL-based 有価証券報告書 filings. Tangentially related — the EDINET payroll-expense lines could draw on `:account-tag/concept-iri` per research note 78.

**Research backing.** doc/research/79 §5.3 (C-wave plan; JP positioned as C8 in §5.3, C10 in the current task framing), 82/83/84 (Stage R per-country research-before bundle — DE / US / CA established the per-country adapter shape), 86 (Stage R review-after; canonical `:audit-doc/category :payroll-filing` vocabulary). Public sources: NTA Corporate Number publication site (https://www.houjin-bangou.nta.go.jp/), NTA QIS guidance (https://www.nta.go.jp/taxes/shiraberu/zeimokubetsu/shohi/keigenzeiritsu/invoice.htm), 日本年金機構 (https://www.nenkin.go.jp/), 厚生労働省 health-insurance / employment-insurance rate guidance, freee / Money Forward / Yayoi public support documentation.
## ADR-079 — `kontor-payroll-fr`: FR-DSN payroll adapter (Stage R C5)

**Context.** Stage R C1 (ADR-075) shipped the `PayrollProvider` trio + `kontor-hr` substrate; C2 (DE-DATEV-LODAS, ADR-076), C3 (US-ADP-GLI, ADR-077), and C4 (CA-CRA, ADR-078) shipped per-country adapters. France is the next-largest single market by SMB count and the only jurisdiction in scope where a single regulator-mandated event-bus (DSN, monthly to net-entreprises.fr) replaces ~20 legacy filings. The French payroll-engine market is concentrated in three engines — **Silae** (dominant SMB; ~30% of French expert-comptable firms), **Sage Paie & RH**, and **Cegid Paie** (top-3 by mid-market revenue) plus ADP Streamline France for international corporates. See doc/research/79 §5.3 (C6 plan; renumbered to C5 in implementation per the substrate's actual sequencing).

**Decision.** Ship `modules/payroll-fr/` as a kontor companion module mirroring the C4 (payroll-ca) structure. Five source files (~1.1 kLoC):

1. **`kontor.payroll-fr.wage-types`** — FR-specific `:component-kind` open-set extension. Maps every FR pay-element kind (`:base-salary`, `:13e-mois`, `:overtime`, `:cotisation-urssaf`, `:csg-deductible`, `:csg-non-deductible`, `:crds`, `:cotisation-arrco-agirc`, `:cotisation-pole-emploi`, `:cotisation-prevoyance`, `:medical-mutuelle`, `:pas-withholding`, `:tickets-restaurant`, `:participation`, `:interessement`, `:plan-epargne-entreprise`, the in-band `:conges-payes-accrual`, the employer-side `:employer-urssaf` / `:employer-arrco-agirc` / `:employer-pole-emploi` / `:employer-prevoyance`, the carry-only DSN bases `:base-soumise-urssaf` / `:plafond-secu` / `:tranche-a..c` / `:smic-mensuel` / `:heures-travaillees`) to an `:account-tag` keyword + a DSN-rubrique keyword. Consumer-extensible via an `:extras-map`. 40 standard kinds.

2. **`kontor.payroll-fr.compute`** — three `PayrollComputeProvider` impls plus a generic CSV parser:
   - `SilaeGlProvider` — reference CSV adapter (semicolon-delimited, French decimal commas, accent-tolerant headers). Per-customer column variation handled via a `:column-mapping` config map.
   - `SageGlProvider` — same parser with `sage-default-column-mapping` baked in (`code-rubrique` instead of `rubrique`).
   - `CegidApiProvider` — partner-program-gated skeleton with a clearly-marked TODO docstring; Cegid Paie API access requires enrolled OAuth credentials.
   The parser accepts a `:__skip-balancer` kind for pre-balanced mirror rows the engine emits (kontor's posting-builder derives the payable mirror from employer-side components on its own). French-decimal coercion handles `3500,00`, `1.234,56`, and non-breaking-space thousand separators.

3. **`kontor.payroll-fr.posting-builder`** — `FrPayrollPostingBuilder` impl of `PayrollPostingBuilder`. Per-pay-period FR journal entry: DR PCG-641 (Rémunérations) + DR PCG-6451..6455 (charges patronales) + DR PCG-6412 (congés payés provision) + CR PCG-431 (URSSAF — employee + employer halves both flow to the same liability bucket) + CR PCG-4371 (ARRCO/AGIRC) + CR PCG-4373 (Pôle emploi) + CR PCG-4374 (prévoyance/mutuelle) + CR PCG-4421 (PAS withholding) + CR PCG-427 (oppositions/saisies) + CR PCG-4282 (congés-payés liability) + CR PCG-421 (Personnel — Rémunérations dues / net). Supports per-établissement (SIRET) routing via `:etab-account-tag` opt that attaches an `:account-tag/name`-keyed `:posting/account-tags` ref to every posting.

4. **`kontor.payroll-fr.dsn`** — DSN structure helpers. `rubrique-line` formats one NEODES line (`S<bloc>,<rubrique>,'<valeur>'`); per-block builders cover S10.G00.00 envelope, S10.G00.01 type-envoi, S21.G00.06 entreprise, S21.G00.11 établissement (with NIC auto-extracted from SIRET), S21.G00.30 individu, S21.G00.50 versement-individu, S21.G00.51 rémunération (per-pay-element gross), S21.G00.81 cotisation individuelle (per-CTP). `build-payload` composes the blocks; `serialize` joins with CRLF per the net-entreprises.fr spec. The load-bearing convenience fn `facts->payload` consumes a vector of `PayrollFacts` + envelope/entreprise/établissement metadata + a `persons-by-emp` resolver and produces the full payload. Amounts render HALF-EVEN to 2 decimals; dates render DDMMYYYY; embedded single quotes are doubled per NEODES escaping.

5. **`kontor.payroll-fr.emit`** — `FrDsnEmitProvider` impl of `PayrollEmitProvider` (returns one `:audit-doc/category :payroll-filing` + `:audit-doc/language :fr` per pay-period; description carries the serialized NEODES payload when envelope metadata is supplied, else a skeleton) + `build-dsn-audit-doc-tx-data` (ADR-068 builder companion) + `terminate-employment-tx-data` (rupture de contrat with motif-rupture-codes table covering démission / licenciement / rupture conventionnelle / fin de CDD / retraite / décès / inaptitude — open-set extension allowed; engine handles the DSN événementielle S21.G00.62 submission under Article R243-13 of the Code de la sécurité sociale).

6. **`kontor.payroll-fr.chart`** + **`resources/kontor/payroll_fr/coa_starter.edn`** — PCG payroll subset (8 expense accounts in class 6 + 10 liability accounts in class 4). Account-tag vocabulary: `:fr-payroll-salaires`, `:fr-payroll-conges-payes`, `:fr-payroll-primes`, `:fr-payroll-avantages-nature`, `:fr-payroll-er-{urssaf,retraite,assedic,prevoyance}`, `:fr-payroll-conges-accrual`, `:fr-payroll-personnel-net`, `:fr-payroll-{acomptes,oppositions}`, `:fr-payroll-urssaf`, `:fr-payroll-retraite`, `:fr-payroll-pole-emploi`, `:fr-payroll-prevoyance`, `:fr-payroll-pas`, `:fr-payroll-conges-liability`.

**Canonical-key alignment (note 86 §2.4 + P2-86-4).** The compute provider uses `:csv-source` (matches CA + future adapters; the US `:adp-gli-csv-source` outlier remains the legacy exception). `:column-mapping`, `:pay-element-codes`, `:external-id->eid` mirror the CA Ceridian provider exactly. The accrual primitive (`:conges-payes-accrual`) is in-band (engine emits the component; posting-builder routes automatically) per note 86 P2-86-3's CA pattern — appropriate for FR's PCG 6412 → 4282 single-leg accrual which has no parallel-ledger book/tax split (French Plan Comptable Général is the single-source-of-truth standard, used for both Handelsbilanz and Steuerbilanz equivalents).

**Why public-spec, not lifted code.** The DSN format spec ('Cahier Technique de la Norme NEODES') is published by net-entreprises.fr as a public interop standard; algorithm sketches in `dsn.clj` are independently derived from the spec text. PCG account numbers (PCG 2014 art.934 series + class-6/4 payroll subset) are factual data not subject to EU copyright per the existing `kontor.l10n-fr.chart` posture. The Silae / Sage / Cegid GL-export column shapes are read from public vendor documentation for *pattern*, never their code. No bundled vendor wage-type catalog (consumer's expert-comptable supplies `:pay-element-codes` at provider construction); no bundled per-CCN (Convention Collective Nationale) rate tables; no bundled URSSAF / ARRCO / AGIRC / DGFiP rate tables; no bundled CTP code list (the S21.G00.81 code-cotisation map in `dsn.clj` ships a small illustrative subset only); no engine credentials.

**What kontor does NOT do (scope discipline + carve-outs per note 79 §5.3 + the task brief).**

- **NO French gross-to-net implementation.** PASS (Plafond annuel de la Sécurité sociale), per-tranche cotisation rates, CSG / CRDS rates, PAS personalized/neutral rate resolution, ARRCO/AGIRC tranche-A/B/C splits, per-CCN bonus add-ons — the engine (Silae / Sage / Cegid / ADP-FR) is authoritative. kontor consumes the engine's GL output.
- **NO DSN transmission to net-entreprises.fr.** The consumer's engine (or ops team) uploads the authoritative DSN file. kontor's `:audit-doc/category :payroll-filing` row preserves the GL-relevant subset for audit-trail reconstruction.
- **NO per-CCN rate tables.** France has ~700 active CCNs (Conventions Collectives Nationales); each may extend the base SMIC / bonus / IK rate table. kontor never bundles CCN tables; the engine resolves them.
- **NO mutuelle-specific computation.** ANI 2013 mandatory health-insurance contributions vary per mutuelle insurer; the engine resolves them, kontor records what the engine produced.
- **NO Sage / Cegid live API wiring.** `SageGlProvider` is file-based (CSV); `CegidApiProvider` is a skeleton because Cegid's REST API is partner-program-gated. Live API integration lands when a partner-program consumer surfaces.
- **NO Revenu-Québec / Belgian / Swiss / Luxembourgish payroll.** Each is a separate jurisdiction. C5 covers metropolitan France only.

**`:audit-doc/category :payroll-filing` for DSN per note 86 P0-86-2 (canonical vocabulary).** The DSN audit-doc carries the canonical category (not `:tax-filing`), aligning with the CA T4 IFT + PD7A + DE LODAS Importdatei treatment. `:audit-doc/language :fr` is the default; `:en` is accepted for English-language audit consumers (large international corporates with English audit reporting).

**Test discipline.** 44 tests / 202 assertions across six namespaces in `modules/payroll-fr/test/kontor/payroll_fr/`:
- `wage_types_test` (7 / 62) — catalog membership + PCG tag routing + employer-side flag + carry-only `:posts?` flag + DSN rubrique mapping + extras-map extension + unknown-kinds detection.
- `compute_test` (9 / 32) — Silae CSV parser (semicolon + French decimals + accent-tolerant headers + thousand-separator tolerance) + unknown-rubrique fails loud + fact assembly with engine/matricule carry-codes + mirror-row dropping + Sage provider with default column-mapping + Cegid skeleton.
- `posting_builder_test` (11 / 19) — PCG-641 gross routing + PCG-431 URSSAF accumulation (employee + employer mirror) + PCG-4371 ARRCO accumulation + PCG-4421 PAS + employer-side double-leg + net-wages PCG-421 + balanced-per-fact invariant + établissement (SIRET) tag attachment + vacation accrual in-band routing + missing-account fails loud + multi-fact composition.
- `dsn_test` (8 / 31) — NEODES line shape + embedded-quote escaping + amount HALF-EVEN rounding + date DDMMYYYY formatting + envelope shape (test vs réel, normal vs néant) + établissement NIC auto-extraction from SIRET + full `build-payload` block coverage (S10/S21.G00.06/11/30/50/51/81) + CRLF serialization + `facts->payload` round-trip + missing-person throws.
- `emit_test` (8 / 45) — `FrDsnEmitProvider` minimal mode (no envelope) + full-payload mode (serialized NEODES in description) + `build-dsn-audit-doc-tx-data` required-keys validation + audit-doc shape (category :payroll-filing + language :fr + custom code + storage-uri) + `terminate-employment-tx-data` (motif-rupture-codes lookup + final-pay-period-end-date threading + unknown-reason fallback to motif 999 + required-args validation) + `dsn-month-from-period` + `validate-period-code`.
- `e2e_test` (1 / 13) — full bilingual end-to-end: Acme France SAS with a cadre (gross 5000 € incl. vacation accrual) + non-cadre (gross 2200 €) through `kontor.hr.payroll/run-payroll!` → `kontor.process/run-process` → `transact-with-validation`. Asserts payroll-run row + control totals + balanced transaction + PCG account totals (431 URSSAF, 421 net, 4421 PAS, 4282 congés liability) + emit provider audit-doc with `:payroll-filing` + `:fr` slots.

**Decision NOT to.** NOT shipping per-CCN rate-table support (consumer-supplied); NOT shipping mutuelle/prévoyance computation (engine handles); NOT writing Sage / Cegid live API integration (partner-program-gated); NOT extending the kernel schema; NOT bundling vendor pay-element catalogs; NOT bundling CTP-code-cotisation tables (illustrative subset only ships); NOT transmitting DSN files (consumer's engine submits to net-entreprises.fr).

**Effort.** ~1.5 maintainer-day for C5: 6 source files (wage_types, compute, posting_builder, dsn, emit, chart) + 6 test namespaces + 1 starter CSV fixture + the ADR + deps.edn / tests.edn wiring. The CA-payroll module template kept C5 cheap; the new substrate is the DSN structure helper layer (NEODES tabular flat-file format is distinct from CA's T619 XML envelope).

**P1 / P2 followups for the review-after pass.**
- **P1**: Per-CCN bonus-extension support. The 700+ active French CCNs each may add bonus rubriques (prime d'ancienneté, prime de panier, IK kilométriques per CCN-specific rates). Currently the `:extras-map` slot accepts bespoke kinds but there is no per-CCN starter catalog. A future commit may add a `:ccn-extras-registry` that ships a small set of the most common CCN extensions (Syntec for IT consultancies; metallurgie; HCR for restaurants) as a starter — consumer always overrides.
- **P1**: Sage / Cegid live API wiring. The CSV path covers the bulk of customers; live API integration gates on partner-program access (mirrors the Wagepoint C4 carve-out).
- **P2**: DSN événementielle (event-based DSN). Stage R substrate covers the monthly periodic DSN payload + the termination audit-doc. The DSN événementielle (S21.G00.62 fin de contrat, S21.G00.65 arrêt de travail) is currently consumer-driven; a future `kontor.payroll-fr.events` namespace could add ADR-068 builders for the half-dozen event types.
- **P2**: TVA-on-tickets-restaurant + URSSAF-on-avantages-en-nature edge cases. The current builder routes `:tickets-restaurant` + `:avantage-nature-vehicule` + `:avantage-nature-logement` to PCG 6414; the TVA-recoverable subset (employer share of tickets restaurant) requires a separate :tax flow the consumer composes outside payroll. Worth documenting in the C5 user-story walkthrough.
- **P2**: PAS-on-correction (régularisation) helper. When an employee's PAS rate changes mid-period (DGFiP CR-M response), the engine emits a régularisation rubrique; the current compute provider treats it as a generic PAS adjustment. A dedicated `:pas-regularisation` kind with the régularisation-month metadata would be cleaner.

**Research backing.** doc/research/79 (Stage R plan; §5.3 C6 entry, renumbered to C5 in impl); doc/decisions.md ADR-075 (substrate), ADR-076 (DE pattern), ADR-077 (US pattern), ADR-078 (CA pattern); doc/research/86 (Stage R final review-after; the canonical-key matrix in §2.4 + accrual-primitive matrix in §2.3); net-entreprises.fr Cahier Technique de la Norme NEODES (DSN spec, accessed 2026-05-18 at https://www.net-entreprises.fr/declaration/dsn/); economie.gouv.fr PCG 2014 reference (PCG class-6/4 payroll subset); Silae / Sage / Cegid public vendor documentation for CSV column-shape patterns.

License posture (final). DSN/NEODES format spec public; PCG account numbering public; Silae / Sage / Cegid CSV column shapes read from public vendor documentation for *pattern*, never their code. No bundled vendor wage-type catalog; no bundled per-CCN rate tables; no bundled CTP code-cotisation list; no bundled URSSAF / ARRCO / AGIRC / Pôle emploi / DGFiP rate tables; no engine API credentials. Algorithm sketches (NEODES line format, NIC extraction from SIRET, DDMMYYYY date formatting, HALF-EVEN rounding to 2 decimals) are factual / re-derived from public spec.
## ADR-080 — `kontor-payroll-au`: AU-STP-Phase-2 payroll adapter (Stage R C6)

**Context.** Stage R C1 substrate (ADR-075) + C2 DE-DATEV-LODAS (ADR-076) + C3 US-ADP-GLI (ADR-077) + C4 CA-CRA (ADR-078) all landed. The AU adapter slots into the same per-country C-slice pattern (research note 79 §5.3, item C7). Australia is unusual among the trio in that STP Phase 2 — Single Touch Payroll Phase 2 — has been mandatory for ALL employers since 2022-01-01 per the ATO; every Australian pay-run requires a structured XML pay-event submitted to the ATO on or before pay-day. The market for AU SMB payroll is concentrated: Xero (dominant SMB), MYOB (mid-market), with Reckon / ADP Australia as longer-tail players. All four expose a CSV GL export the kontor adapter consumes.

**Decision.** Ship `modules/payroll-au/` as a kontor companion module that:

1. **Parses Xero / MYOB GL CSV exports** into `PayrollFacts` via `XeroGlComputeProvider` + `MyobGlComputeProvider` (both satisfy `PayrollComputeProvider`). The two share a parametric column-mapping parser; the column-header convention differs between vendors but the underlying balanced-journal shape is identical. Per-employee net-zero invariant enforced at parse time. `:csv-source` is the canonical key per note 86 P2-86-4 recommendation (CA pattern, not US legacy).

2. **Ships a substrate-canonical wage-type catalog** (`kontor.payroll-au.wage-types/standard-component-kinds`) keyed by component-kind keyword. Mirrors the CA pattern (catalog + extras-map extension); the US regex-driven map was vendor-specific to ADP's description vocabulary and doesn't generalize to AU's pay-element-code convention. Each kind carries an `:account-tag` + an `:stp2-income-type` slot driving the STP Phase 2 income-type disaggregation (SAW / OTE / Overtime / Bonus & Commission / Lump Sums A-E / Salary Sacrifice S+O / PAYGW / Super / RFBA per the ATO BIG). `assert-valid!` is the canonical entry point per P2-86-5 (throws on failure — DE/kernel convention).

3. **Materializes balanced GL postings** via `AuPayrollPostingBuilder` (satisfies `PayrollPostingBuilder`). Per-pay-period journal: DR wages + employer-super expense + employer state-payroll-tax expense + workers-comp expense / CR PAYGW payable + Super payable + salary-sacrifice clearing + state-payroll-tax payable + workers-comp payable + Wages payable (net). Each component routes via consumer-supplied `:accounts` map keyed by `:account-tag` keyword. Missing tags throw — no silent drop.

4. **Records per-state wage allocation** via `:posting/analytic-distributions` on every wage-side posting — NOT `:posting/entity`. Mirrors ADR-077's US-LLC rationale: an Australian Pty Ltd employing remote workers in 5 states is ONE legal entity (one ABN, one BAS, one PAYGW summary). Per-state lives on the `:analytic-plan/code "state"` plan + ISO-3166-2:AU `:analytic-account` rows (6 states + 2 territories = 8 jurisdictions); the C6 install layers this plan idempotently. Per-employee state allocation override via `:state-allocations` opt for hybrid employees (e.g. 60 % VIC / 40 % NSW).

5. **Emits STP Phase 2 pay-events** as `:audit-doc/category :payroll-filing` rows via `AuStpEmitProvider` (satisfies `PayrollEmitProvider`). Structured payload (per-payee period + YTD disaggregation, aggregate envelope with total-gross + total-PAYGW) shaped per the ATO MIG `PAYEVNT.PAYEVNTEMP` family. The actual SBR2 / AS4 transmission to the ATO is the consumer's engine's job; kontor records the structured payload as an `:audit-doc` so the audit chain has a row and the consumer's SBR adapter / clearing-house uploads. `:audit-doc/language :en` per the new kernel attr (ADR-078 — same three-axis pattern as CA, though AU is single-locale in practice).

6. **Provides SuperStream contribution-message helpers** (`kontor.payroll-au.super`). Super-guarantee contributions are remitted per the ATO SuperStream Alternative File Format; the helper builds the contribution-line + contribution-message-payload structures and produces an `:audit-doc/category :payroll-filing` audit-doc per ADR-068. Transmission via the consumer's clearing-house (ATO Small Business Superannuation Clearing House for ≤19-employee businesses; commercial CH otherwise) is out of kontor's scope. The SG rate (11.5 % from 2024-07-01, rising to 12.0 % from 2025-07-01) is NOT bundled — the engine computes; kontor records.

7. **Provides termination-event audit-doc helper** (`kontor.payroll-au.emit/terminate-employment-tx-data`). Records the cessation event including the ATO `CessationTypeCode` (V / I / D / R / F / C / T / O per the BIG) the consumer's engine surfaces to the next STP pay-event's cessation block. kontor does NOT generate the Employment Separation Certificate (Centrelink form) — that's engine-driven.

8. **Provides TFN structural validator** (`kontor.payroll-au.stp/valid-tfn?` + `assert-tfn!`). 9-digit weighted mod-11 check per the public ATO TFN algorithm. Algorithmically derived; the ATO test TFN "123 456 782" is the canonical positive case.

**Why per-state via analytic-distribution, not `:posting/entity`.** Same rationale as ADR-077 §4. An Australian Pty Ltd with workers in 5 states files ONE annual income-tax return (one TFN, one ABN, one Form C). Per-state payroll-tax — which Australia uniquely has 8 separate jurisdiction-level regimes for (threshold $700K–$1.5M depending on state, rate 4.75 %–6.85 %) — is a *reporting* / *analytic* concern. `:analytic-account/state` rides on ADR-022 + ADR-032; per-state-tax-payable can split into per-state sub-accounts (`2585-NSW`, `2585-VIC`) without cross-entity clearing.

**Why NOT a parallel-ledger (us-gaap vs us-tax) split for AU accruals.** Unlike the US (ASC 710 + IRC §461(h) PTO timing difference; IRC §404(a)(6) 401(k) match deferred-deduction window) or DE (HGB §249 vs Steuerbilanz Urlaubsrückstellung framework split), Australian AASB does not impose a comparable book-vs-tax timing-difference regime on payroll accruals. Annual leave + LSL (Long Service Leave) accruals follow AASB 119 employee benefits — a single book treatment that ATO accepts for tax. Fringe-benefits tax (FBT) is a separate quarterly cycle, not a parallel-ledger artifact. The adapter ships single-ledger output by default; consumers wanting a parallel split for their own internal reporting cadence can pass `:ledgers-map` per the substrate's standard mechanism (P1-86-1 threads it through `kontor.hr.payroll/run-payroll!`).

**License posture (same as ADR-005 / ADR-071 / ADR-075 / ADR-076 / ADR-077 / ADR-078).** No code lifted from Xero, MYOB, Reckon, or any vendor. The STP Phase 2 message format + SuperStream AFF + TFN algorithm + ABN algorithm + ACN algorithm are all *public* ATO + ASIC specifications (algorithms are facts, not copyrightable). Working from softwaredevelopers.ato.gov.au (accessed 2026-05-18), the ATO Software Developers Business Implementation Guide, the Standard Business Reporting (SBR2) MIG, and the SuperStream AFF Schedule 6. No vendor API keys, OAuth credentials, BMS-ID values, customer CoAs, super-fund USI lookup tables, state-payroll-tax rate tables, workers-comp premium rate tables, or SG rate tables bundled — every customer supplies their own.

**What kontor does NOT do (scope discipline).**

- **NO AU gross-to-net math.** PAYG-withholding tables, super-guarantee calculation, salary-sacrifice OTE-base maintenance, lump-sum averaging, ETP withholding — the engine (Xero / MYOB / Reckon / ADP-AU) did the math. kontor consumes the result.
- **NO SBR2 ebMS3 / AS4 envelope generation.** SBR2 transport-layer auth (Cloud Software Authentication + Authorisation, CSAA) is consumer-held.
- **NO ATO TFN-declaration form filing.** TFN declarations are per-employee paperwork the engine handles.
- **NO BAS / IAS submission.** Those are GST-side returns (`modules/l10n-au/bas.clj` covers them).
- **NO Employment Separation Certificate generation.** Centrelink form is engine-driven.
- **NO FBT quarterly cycle.** Out of scope; a future `kontor-fbt-au` companion if a consumer surfaces.
- **NO workers-comp rate tables.** Per-state premium rates are deferred to a follow-up; the substrate ships the accrual hook (`:workers-comp-employer` component kind + `:au-payroll-er-workers-comp` account tag), the consumer's engine provides the rate.
- **NO state-payroll-tax threshold / rate logic.** Threshold + rate are consumer-policy; the substrate records the engine's pre-computed accrual.

**Decision NOT to.** NOT extending the kernel schema with AU-specific attrs. NOT bundling vendor API credentials. NOT bundling customer CoAs / state-payroll-tax rate tables / SG rate / SuperStream USI registry. NOT writing the STP SBR transport-layer wiring (consumer's engine handles). NOT writing FBT / IAS / TFN-declaration forms. NOT bundling a super-fund USI registry.

**Test discipline.** 33 tests / ~170 assertions across six namespaces in `modules/payroll-au/test/kontor/payroll_au/`:

- `wage_types_test` (8 tests / ~40 assertions) — catalog membership + STP income-type mapping + employer-side invariants + carry-only kinds + extras-map + validate/assert-valid! convention.
- `compute_test` (9 tests / ~30 assertions) — Xero + MYOB CSV parsers + per-employee net-zero invariant rejection + unknown-pay-element-code rejection + provider-id + Reckon skeleton throw.
- `posting_builder_test` (7 tests / ~30 assertions) — wage-type → account routing + missing-tag throw + balanced posting set + per-state analytic distribution (NOT `:posting/entity`) + hybrid-state override + protocol satisfaction + commodity-required constructor.
- `stp_test` (8 tests / ~40 assertions) — payee-payload income-type disaggregation + YTD carry + pay-event aggregate totals + missing-key throws + update-event flag + summary-string + facts->payees walk + TFN mod-11 validator.
- `super_test` (5 tests / ~15 assertions) — contribution-line per-employee builder + bad-USI rejection + contribution-message-payload assembly + empty-lines rejection + audit-doc tx-data builder.
- `e2e_test` (3 tests / ~15 assertions) — full headline scenario: AU Pty Ltd, 3 employees in NSW/VIC/QLD, monthly run, end-to-end through `kontor.hr.payroll/run-payroll!` → `kontor.process/run-process` → `transact-with-validation`; SuperStream message composes standalone; termination-event audit-doc emits cessation-code.

**Fixture sources.** `xero_3_employees_3_states.csv` synthesizes a Xero AU Payroll GL Journal export shape (3 employees, monthly pay-event, NSW / VIC / QLD). `myob_single_employee.csv` synthesizes the MYOB AccountRight column-header convention with one employee in VIC. `xero_corrupt_unbalanced.csv` is a deliberately-broken file with a non-zero per-employee sum to exercise the parser's invariant rejection. All fixtures are synthetic — no real-customer data.

**Effort.** ~1 maintainer-day for C6 (7 source files + 6 test namespaces + 3 CSV fixtures + starter chart + ADR). Heavy reuse of the existing CA + US adapter shapes; the AU-specific work is the STP Phase 2 income-type disaggregation + the SuperStream AFF shape + the AU state vocabulary + the TFN/ABN/ACN validators (last two already shipped in `modules/l10n-au/identifiers.clj`).

**Open followups for review-after.**
- **P1** — Reckon One live wiring (skeleton ships; the column-mapping for Reckon One's GL export is documented at developer.reckon.com but the consumer-demand-gated wiring lands when surfaced).
- **P1** — ADP Australia: the ADP RUN AU export shape is similar to the US 10-column GLI but with AU pay-element codes. The current `XeroGlComputeProvider` with a custom column-mapping covers the bulk; a dedicated `AdpAuComputeProvider` (mirroring the US C3 + CA C4 `AdpCanadaProvider` shape) is a small follow-on.
- **P2** — Per-state workers-comp premium rate tables (8 jurisdictions × rate-category lookup). Currently the substrate ships the `:workers-comp-employer` accrual hook; engine-driven rate. A future `kontor-workers-comp-au` companion table would let consumers compute the accrual without an engine.
- **P2** — Per-state payroll-tax threshold + rate tables (8 jurisdictions). Same story — substrate hook in place, rates are consumer/engine policy.
- **P2** — FBT quarterly cycle. Out of scope for C6; future companion.
- **P2** — STP transport-layer adapter (SBR2 ebMS3 / AS4 envelope + CSAA auth). Out of substrate scope; consumer's SBR adapter handles. A future commercial `kontor-sbr-au` companion could ship the envelope generation.
- **P2** — RL-1-equivalent QC carve-out (none in AU — Australia is a unitary federation for payroll-tax purposes despite per-state rates).
- **P2** — Stammdaten / employee-master delta emit. Currently the STP pay-event carries only per-pay-period payee data; a future enhancement could emit a separate `:audit-doc` for new-hire / salary-change / termination events that surface in the next STP submission.

**Research backing.** doc/research/79 §5.3 (Stage R per-country sequencing, AU = C7); doc/research/82 (DE adapter template); doc/research/83 (US adapter template); doc/research/84 (CA adapter template); doc/research/86 (final review-after — the cross-cutting consistency findings driving the canonical-key + validation conventions adopted here); ATO Software Developers Business Implementation Guide STP Phase 2 + SuperStream AFF (accessed 2026-05-18 from softwaredevelopers.ato.gov.au); ATO TFN algorithm public reference; ATO MIG `PAYEVNT.PAYEVNTEMP` schema family.
## ADR-083 — `kontor-payroll-in`: IN payroll adapter (Stage R C9)

**Decision.** Land the IN payroll adapter as a new companion module `modules/payroll-in/` on top of the Stage R C1 substrate (ADR-075) + the already-shipped `modules/l10n-in/` chart + GSTIN/PAN identifiers + 37 GST state codes. Eight pieces, no new kernel ADRs and no new kernel attributes — the `:audit-doc/language` axis added in ADR-078 is reused (set to `:en-in` per CLAUDE.md vs CA's `:en` / `:fr`) and the kernel-level `:employment/province-of-employment` ISO-3166-2 attr added in ADR-075 (P1-86-3) carries IN state-of-employment on the same axis as US W-2 box 15 / DE Bundesland / CA province:

1. **`kontor.payroll-in.wage-types`** — IN-specific `:component-kind` open-set extension. Maps every IN pay-element kind (`:basic-salary`, `:dearness-allowance`, `:house-rent-allowance`, `:leave-travel-allowance`, `:medical-allowance`, `:special-allowance`, `:bonus`, `:overtime`, `:commission`, `:retroactive-pay`, `:gratuity-paid`, `:leave-encashment`, `:perquisite`, the four statutory deductions `:tds` / `:pf-employee` / `:esi-employee` / `:professional-tax`, the voluntary `:voluntary-deduction` / `:loan-recovery` / `:garnishment`, the employer accruals `:pf-employer` / `:pf-employer-eps` / `:pf-employer-epf` / `:pf-employer-edli` / `:esi-employer` / `:bonus-accrual` / `:leave-encashment-accrual` / `:employer-gratuity-accrual`, and the carry-only T4-equivalents `:pf-wages` / `:esi-wages` / `:section-80c-deduction` / `:section-80d-deduction` / `:section-80g-deduction` / `:hra-exemption-claimed` / `:taxable-income-ytd`) to an `:account-tag` keyword and (where applicable) a `:payable-tag`, `:form-24q-section`, `:pf-applicable?`, `:esi-applicable?` flag. The PF-applicability default treats `:special-allowance` as PF-applicable (the conservative position per RPFC v. Surya Roshni SC 2019); consumers with contrary counsel override via `:extras-map`. Ships a `pt-states` set of 17 PT-levying sub-jurisdictions (Maharashtra, Karnataka, WB, TN, Gujarat, AP, Telangana, Kerala, MP, Odisha, Tripura, Assam, Meghalaya, Sikkim, Mizoram, Manipur, Nagaland + Puducherry UT) — UP/Delhi/Haryana/Punjab/Rajasthan/Uttarakhand/HP/J&K/Ladakh + the legislatures-less UTs do NOT levy PT.

2. **`kontor.payroll-in.compute`** — three `PayrollComputeProvider` impls modeled on `kontor.payroll-ca.compute` (Stage R C4) but with IN engine vocabularies:
   - `KekaProvider` — Keka HR / Keka Payroll's GL CSV export with default Keka column names (`employee-id`, `component`, `amount`, `type` / `Earning`/`Deduction`/`Employer`, `work-state`, `department`). Consumer overrides columns for renamed instances.
   - `GreytHrProvider` — Greytip GreytHR's GL CSV export with the `EAR` / `DED` / `EMP` head-type vocabulary and the documented `emp-no` / `head-code` / `amount` / `head-type` shape.
   - `ZenHrProvider` — generic CSV adapter (handles ZenHR, ZingHR, SumoPayroll, Saral PayPack, in-house exports). REQUIRES `:column-mapping` (no engine default) — captures the long tail of IN SMB engines whose columns vary per instance.
   The parser correctly coerces `₹`-prefixed amounts (the Rupee symbol the engines pass through unstripped on occasion) and normalizes case-insensitive headers per the existing CA pattern.

3. **`kontor.payroll-in.posting-builder`** — `InPayrollPostingBuilder` impl. Per-pay-period IN journal entry: DR Salaries-and-Wages (gross) + DR Employer-PF (12% + 0.5% EDLI) + DR Employer-ESI (3.25%) + DR Accrual expenses (bonus / leave / gratuity when in-band) + CR four CRA-equivalent statutory buckets (`:in-payroll-tds-payable`, `:in-payroll-pf-payable`, `:in-payroll-esi-payable`, `:in-payroll-pt-payable` — NEVER collapsed, mirrors note 84 §3.3 CA pattern) + CR accrual liabilities + CR Wages-payable (net). Per-state PT routing via `:posting/analytic-distributions` on the substrate-installed `:analytic-plan/code "in-state"` plus per-state `:analytic-account` rows (28 states + 8 UTs + Ladakh, ISO-3166-2:IN codes) — NOT `:posting/entity`. A single Pvt Ltd with employees in MH + KA + TN is ONE legal entity (one PAN). Hybrid / multi-state allocations supported via `:state-allocations`.

4. **`kontor.payroll-in.accrual`** — IN-specific accrual primitives (ADR-068 builders):
   - `bonus-accrual-tx-data` / `bonus-accrual!` — Payment of Bonus Act 1965 minimum-bonus accrual (8.33% of basic for employees earning ≤ ₹21,000/mo basic+DA, accrued per period, paid annually by 30-Nov for the prior accounting year per Sec 19).
   - `leave-encashment-accrual-tx-data` / `leave-encashment-accrual!` — Ind AS 19 short-term compensated-absence accrual.
   - `gratuity-accrual-tx-data` / `gratuity-accrual!` — thin pure-tx-data shape for the case where the consumer has the Ind AS 19 defined-benefit actuarial valuation in hand. The substrate ships the PLUMBING; the Heubeck-style mortality + withdrawal + salary-escalation + discount-rate math is **out of substrate scope** (note 79 §5.3 deferral) — consumer's actuary supplies the amount; the builder accepts an `:audit-doc-code` for the valuation report's audit chain.

5. **`kontor.payroll-in.tds`** — Form 24Q quarterly e-TDS return helper. Sums per-quarter TDS-on-salary totals (Section 192 of the Income Tax Act 1961) against the consumer's TDS-payable account, optionally filtered by deductor-TAN tag. Emits the **Form 24Q FVU text payload** — 4-record-type (File-header / Batch-header / Challan / Deductee) pipe-delimited text with CR-LF terminators, per the publicly documented NSDL e-TDS RPU 4.x specification. Builds the matching `:audit-doc/category :payroll-filing` + `:audit-doc/language :en-in` row per ADR-068. Does NOT run FVU.exe (proprietary Java tool the consumer runs locally), upload to TRACES, or bundle TDS slabs (Finance Act updates them annually). IN fiscal year is April-March; the helpers (`fy-of`, `quarter-of`, `quarter-bounds`) align with the existing `kontor.l10n-in.closing` convention.

6. **`kontor.payroll-in.pf`** — EPFO Electronic Challan-cum-Return (ECR) monthly return helper. Sums per-month PF-payable totals (employee 12% + employer 12% + EDLI 0.5%) against the consumer's PF-payable account, optionally filtered by establishment-code tag. Emits the **ECR tab-delimited text payload** with 11 documented columns per employee (UAN, member name, gross / EPF / EPS / EDLI wages, EE contribution, ER EPS / ER EPF contributions, NCP days, refund-of-advances). Builds the matching audit-doc.

7. **`kontor.payroll-in.esi`** — ESIC monthly contribution helper. Sums per-month ESI-payable totals (employee 0.75% + employer 3.25%) for employees earning ≤ ₹21,000/mo gross. Emits the **ESIC CSV payload** (IP Number, IP Name, Days Paid, Total Monthly Wages, Reason Code, Last Working Day) — with a configurable header line. Builds the matching audit-doc.

8. **`kontor.payroll-in.emit`** — `InPayrollEmitProvider` impl + `terminate-employment-tx-data` (ADR-068). Emits per-run audit-doc rows with `:audit-doc/category :payroll-filing` + `:audit-doc/language :en-in`. When a payroll run spans multiple PT-levying states, emits an *additional* warning audit-doc flagging that PT filing is per-state — mirrors CA's QC passthrough warning (note 84 §8.3) but for India's per-state PT cadence + portal divergence. Termination helper carries the data the consumer's engine needs for **Form 16** (Sec 192 annual TDS certificate, issued by 15-June following the FY-end), **Form 12B** (prior-employer income certificate for the new employer), **gratuity payment** (Sec 4 of Payment of Gratuity Act 1972 — 15 days per year, capped at ₹20 lakhs after 5 years of service), **leave encashment** (Sec 10(10AA) tax-exempt portion), **PF final-settlement / transfer-out** (Form 19 / Form 10C / Form 10D via EPFO), **ESIC last-working-day update**. kontor does NOT generate Form 16 itself (consumer's engine does via TRACES download) — kontor records the issuance event.

**Why public-spec, not lifted code.** NSDL e-TDS FVU 4.x + EPFO ECR + ESIC contribution CSV formats are **publicly published** specifications on Protean / EPFO Unified Portal / ESIC portal help pages. CGST/SGST/IGST/UTGST schema + the IN state-code table + Schedule III layout (Companies Act 2013 Division II Ind AS-aligned) — already shipped under EPL-1.0-clean posture in `kontor.l10n-in` per the existing chart's research provenance. The Income-tax Act 1961 + EPF Act 1952 + ESI Act 1948 + Payment of Bonus Act 1965 + Payment of Gratuity Act 1972 are public statutes; algorithmic sketches drawn from public Indian-tax-practitioner commentary (no GPL-contaminated code lifted).

**Why one module covers Keka + GreytHR + ZenHR + Saral / SumoPayroll / Sumopayroll.** Per note 79 §5.3 + the C3 / C4 precedent (one US-ADP module covers ADP RUN / Workforce Now / InfoLink; one CA-payroll module covers Ceridian Dayforce / Powerpay / ADP-CA / Wagepoint skeleton): the **CSV column-mapping config** is the single seam that varies per engine. A single `:column-mapping` opts map plus a `:pay-element-codes` lookup turns the long tail of IN SMB engines into thin per-vendor wrappers. The two named records (`KekaProvider`, `GreytHrProvider`) ship documented column defaults; `ZenHrProvider` covers everything else (Saral PayPack, SumoPayroll, ZingHR, in-house) with consumer-supplied column-mapping.

**Why no per-run TDS / PF / ESI emission.** Per note 79 §5.3: TDS is **quarterly** (Form 24Q), PF and ESI are **monthly** aggregate filings (ECR + ESIC contribution CSV). They are NOT per pay-period — a monthly payroll for a 20-employee Pvt Ltd produces 12 ECRs / 12 ESIC CSVs / 4 Form-24Qs per FY, not 12 of each per pay-run. The `InPayrollEmitProvider` emits only the per-run summary audit-doc + the multi-state-PT warning; the aggregate filings flow through `tds.clj` / `pf.clj` / `esi.clj` which the consumer's quarterly / monthly batch invokes (typically driven from `kontor.schedule` per ADR-032).

**Decision NOT to.** NOT shipping per-state PT rate slabs (per-state, changed annually via state finance bills; the engine computes, kontor records via `:in-payroll-pt-payable` posting + per-state analytic distribution). NOT shipping TDS slab tables (Finance Act updates them annually; Old Tax Regime vs New Tax Regime per the FY 2023-24 split; surcharge / cess rates). NOT shipping the PF wage ceiling (₹15K/mo as of 2026 but EPFO can change via Notification). NOT shipping the ESI threshold (₹21K/mo). NOT bundling NSDL TAN / PAN credentials, EPFO Establishment Codes, ESIC employer codes, UAN lookups, or the per-employee IP numbers. NOT running FVU.exe (proprietary Java tool — consumer runs locally before TRACES upload). NOT generating Form 16 (consumer's engine downloads from TRACES; kontor's `terminate-employment-tx-data` records the data the issuance needs).

**Decision NOT to.** NOT implementing Ind AS 19 defined-benefit actuarial valuation for gratuity (Heubeck-style mortality / withdrawal / salary-escalation / HGB §253-equivalent discount-rate inputs — out of substrate scope per note 79 §5.3 deferral; a future `kontor-actuary-in` companion would integrate the actuarial table provider). The C9 module ships `gratuity-accrual-tx-data` as a thin builder accepting a consumer-supplied amount + the audit-doc code of the actuary's valuation report.

**Decision NOT to.** NOT shipping a per-state Labour Welfare Fund (LWF) collector. Per note 79 §5.3 scope discipline + the C9 deferral: LWF varies per state (₹6-₹240 per six-month period in different states), the cadence is half-yearly, and the regulatory diversity exceeds even PT. Deferred to a C9.1 follow-up.

**Decision NOT to.** NOT shipping Employment Exchange returns (ER-I, ER-II under Employment Exchanges (CNV) Act 1959 — quarterly + biennial), Maternity Benefits Act compliance helpers, Apprentices Act ratio compliance, Sexual Harassment Act ICC reports. These compose on top of the substrate via consumer-supplied policy + the existing `:audit-doc/category :compliance-attestation` channel; not payroll-specific.

**Why `:audit-doc/language :en-in` rather than `:en`.** Per ADR-078 the language axis is open-set; CLAUDE.md tags this codebase's IN-correspondence locale as `:en-in` (vs CA's `:en` / `:fr`, DE's `:de`, US's `:en`). The kernel doc-string already documents the open-set; no schema change needed. The distinction matters when a consumer runs `dsar/collect` across employees of a global parent with IN + US + UK subsidiaries — the three-axis (privilege × category × language) auth grid can filter to "show this US tax-prep contractor only `:en` payroll-filings, not the `:en-in` ones." Future Hindi / Tamil / Marathi correspondence land under `:hi-in` / `:ta-in` / `:mr-in` without schema change.

**Test discipline.** 55 tests / 258 assertions across 8 namespaces in `modules/payroll-in/test/kontor/payroll_in/`:
- `wage_types_test` — IN catalog membership + Form 24Q section mapping + PF/ESI applicability flags + PT-state set membership + extras-map extension + unknown-kinds detection (4 tests / 80 assertions).
- `compute_test` — Keka + GreytHR + ZenHR generic CSV parsers + balancer-row tolerance + unknown-code fail-loud + facts-assembly producing balanced substrate shape + provider records satisfying protocol (9 tests / 38 assertions).
- `posting_builder_test` — 8 scenarios: single-fact-balanced, PT carries state distribution, wages carries state distribution, multi-state allocation overrides fact state, missing-account-tag throws, builder record satisfies protocol, ledger-stamping, PF-employer split into EPS + EPF + EDLI balances, in-band bonus-accrual routes correctly (9 tests / 33 assertions).
- `accrual_test` — bonus / leave / gratuity tx-data shape + HALF-EVEN rounding + required-keys fail-loud + negative-amount reverses (6 tests / 13 assertions).
- `tds_test` — FY-of for April-March split, quarter-of for 12 months, quarter-bounds boundary cases (Q3 spans calendar year), pipe-delimited file-header / batch-header / challan / deductee rows, idempotency of FVU output, audit-doc carrying `:payroll-filing` + `:en-in` (9 tests / 47 assertions).
- `pf_test` — month-bounds half-open window, ECR row tab-delimited (11 fields), defaults NCP days + refunds, CR-LF terminators, idempotency, audit-doc shape (6 tests / 15 assertions).
- `esi_test` — month-bounds, ESIC row defaults, comma-in-name quote-escape, header-on-by-default + omitted-on-demand, idempotency, separation-row renders DDMMYYYY date, audit-doc shape (8 tests / 14 assertions).
- `e2e_test` — full headline scenario: Acme India Pvt Ltd, 3 employees in MH + KA + TN, monthly payroll, end-to-end through `kontor.hr.payroll/run-payroll!` → `kontor.process/run-process` → `transact-with-validation`. Asserts payroll-run row + control totals (gross 187500 / net 173450) + balanced transaction + per-state analytic distributions (`#{"IN-MH" "IN-KA" "IN-TN"}`) + emit provider produces `:payroll-filing` audit-docs with `:en-in` language. Plus 3 multi-state warning unit tests (4 tests / 18 assertions).

Fixtures cited as oracle sources: NSDL e-TDS RPU 4.x documentation (Protean website), EPFO Unified Portal ECR file-format help-page, ESIC portal contribution-upload help-page, Keka admin-portal documentation (public help-center pages), Greytip GreytHR help-center documentation, Schedule III (Companies Act 2013) + Ind AS framework (ICAI) for the Schedule-III-aligned 6900-series starter chart.

**Effort.** ~2 maintainer-days for C9 (8 src namespaces + 8 test namespaces + 2 CSV fixtures + 1 chart EDN + ADR + deps.edn / tests.edn wiring). The bulk of the work was the IN-specific wage-type catalog (covering basic / DA / HRA / LTA / Medical / Special / Bonus / OT / Commission / Retro + the 4 statutory deductions + the EDLI / EPS / EPF employer split) and the three text emitters (Form 24Q FVU + EPFO ECR + ESIC CSV) — the substrate orchestration came essentially for free from C1 / C3 / C4 templates.

**Research backing.** doc/research/79-hr-payroll-stage-r-plan.md §5.3 (the C9 plan + scope-discipline section), CLAUDE.md `:audit-doc/category :payroll-filing` + `:audit-doc/language :en-in` conventions, modules/l10n-in (existing chart + 37-state-code installer + PAN/GSTIN identifiers), modules/payroll-us-adp + modules/payroll-ca structural templates (ADR-077 + ADR-078).

License posture (final). NSDL e-TDS FVU spec public; EPFO ECR format public; ESIC monthly contribution format public; Schedule III + Ind AS framework public statutes; Keka + GreytHR + Saral / SumoPayroll / ZenHR CSV column shapes drawn from public vendor help-center documentation for *pattern*, never their code. No bundled vendor API keys / OAuth secrets; no bundled TDS slabs / PT rate tables / PF wage ceiling / ESI threshold (regulators update annually); no bundled UAN / TAN / PAN data (per-customer / per-employee secrets); no bundled FVU.exe Java runtime; no GPL-contaminated reference code lifted. PT-applicability set + PF-applicability defaults for `:special-allowance` documented with citation to Surya Roshni Ltd v. RPFC SC 2019 (public Supreme Court decision).
## ADR-085 — `kontor-payroll-cn`: CN payroll adapter (Stage R C11)

**Decision.** Ship the CN payroll adapter as `modules/payroll-cn/` — the fourth concrete `PayrollProvider` triple after DE (ADR-076), US (ADR-077), and CA (ADR-078). The adapter is a **clean-room connector around the dominant CN payroll engines** (用友 Yonyou NC / NCC / U8+, 金蝶 Kingdee K/3 + Cloud, 北森 Beisen, 薪人薪事 Salaryman): it consumes their per-period CSV export, classifies wage-type rows against a consumer-supplied mapping, produces balanced ASBE-aligned GL postings on the 应付职工薪酬 (2211) sub-tree, and emits the 个税申报 (IIT filing) audit-doc.

Per ADR-005 / ADR-071 / ADR-075 / ADR-076 / ADR-077 / ADR-078 posture: **kontor does NOT re-implement CN jurisdictional payroll math.** The IIT cumulative-method withholding, the per-city 五险一金 rate/base lookup, the 年终奖 separate-vs-combined election — all run inside the engine. kontor consumes the engine's gross-to-net output and produces the GL leg + the audit-doc.

Five module files (~0.9 kLoC + tests):

1. **`wage_types.clj`** — consumer-extensible component-kind catalog with 12 standard CN kinds + 3 carry-only (per note 87 §3): `:base-wage / :performance-bonus / :overtime / :annual-bonus / :allowance / :taxable-benefit / :iit-withheld / :ee-pension / :ee-medical / :ee-unemployment / :ee-housing-fund / :er-pension / :er-medical / :er-unemployment / :er-work-injury / :er-maternity / :er-housing-fund / :annual-bonus-accrual` plus the carry-only `:si-base / :hf-base / :cumulative-taxable-ytd`. Each maps to an `:account-tag` keyword (the consumer's chart-of-accounts lookup key) and optionally an `:asbe-sub-account` (`:wages | :si | :hf | :welfare`) for 应付职工薪酬 routing. Mirrors CA's `kontor.payroll-ca.wage-types` shape.

2. **`compute.clj`** — `YonyouCsvComputeProvider` / `KingdeeCsvComputeProvider` / `BeisenCsvComputeProvider` parsers. Per-customer column variation handled via a `:column-mapping` config map (mirrors CA's CeridianDayforce pattern); per-engine `provider-id` for audit logs. Consumer-supplied `:pay-element-codes` (engine wage-element code → kontor `:component-kind`) keeps the substrate engine-agnostic. The same `parse-cn-csv` core handles all three engines with different column maps.

3. **`posting_builder.clj`** — `CnPayrollPostingBuilder` materializes the per-period GL posting set:
   - DR 6602/5603 (or department-specific) wage expense for the gross
   - CR 2211.01 应付职工薪酬-工资 for the net (the cash-out leg)
   - CR 2221.01-个人所得税 for the IIT withholding
   - CR 2211.03 应付职工薪酬-社保 for the employee SI contributions
   - CR 2211.04 应付职工薪酬-公积金 for the employee HF
   - DR 5603/admin wage-expense + CR 2211.03 / 2211.04 for the employer SI + HF
   Routing is via `:account-tag/*` (per CA's pattern) so consumers can re-key without code change.

4. **`accrual.clj`** — `annual-bonus-accrual-tx-data` for monthly 1/12 accrual toward a year-end 年终奖. DR 6601/5602/5603 (department wage expense) / CR 2211.01 应付职工薪酬-工资. Mirrors the US ASC-710 PTO accrual + CA vacation-pay-accrual primitives.

5. **`emit.clj`** — `CnIitMonthlyEmitProvider` produces one `:audit-doc/category :payroll-filing` per pay-period with `:audit-doc/language :zh-cn`. The payload is a structured CSV with per-employee per-component breakout (column headers in zh-cn + en); the consumer's 自然人电子税务局 importer (or third-party convertor) bridges to the regulator's XML schema. v1 ships the CSV; the regulator's full XML schema (申报表 2024-04) is a consumer convertor step.

6. **`core.clj`** — installer (idempotent `install!` that lays down the `:account-tag` vocabulary for the 应付职工薪酬 sub-tree + the IIT payable + employer SI/HF expense; the consumer's `kontor.l10n-cn.chart` is the source of base accounts) + re-exports of the three `make-*-provider` constructors and the accrual helper.

**Why public-spec, not lifted code.** State Taxation Administration (国家税务总局) + Ministry of Finance (财政部) regulations on IIT, the 综合所得汇算清缴 reform, and the CAS 9 Employee Compensation standard (财会〔2014〕8号) are **public regulator publications** (政府信息公开 — Government Information Disclosure regulation). ASBE 4-digit account numbers + sub-account decomposition for 2211 / 2221 are factual data not copyrightable. The CSV column-mapping pattern reads from public vendor docs (Yonyou / Kingdee API guides) — no proprietary code lifted, no bundled per-city SI rate table, no bundled IIT bracket schedule, no 自然人电子税务局 credentials. The consumer holds the engine + the rate tables + the credentials.

**Why config-driven CSV parser, not three separate parsers.** Per note 87 §5: Yonyou, Kingdee, and Beisen all export per-period payroll runs as CSV (or XLSX-exported-as-CSV) with per-customer column variation. A single config-driven parser (the CA Ceridian pattern) is cheaper than three near-duplicate impls. The same `parse-cn-csv` accepts a `:column-mapping` and `:pay-element-codes` opts pair; the three provider records configure `provider-id` for audit log purposes + supply different default `:column-mapping` defaults.

**Why per-province (not per-city) for v1.** Per the task brief + note 87 §2.2: the 五险一金 rates vary per-city (200+ cities), not per-province. **However**, the substrate-level `:employment/province-of-employment` attr (ISO-3166-2:CN codes like `CN-BJ` / `CN-SH` / `CN-SZ`) is what kontor stores for the analytic split. Per-city allocation is **a follow-up** — consumers needing it tag postings via `:posting/analytic-distributions` with a city-axis analytic plan they install themselves. v1's posting-builder honours `:employment/province-of-employment` to attach a `:province` analytic-distribution; per-city follow-up runs through the same machinery.

**Why year-end bonus as a separate component-kind.** Per 财税〔2018〕164号 (extended to 2027) the 年终奖 has a **special election** between the separate-tax (单独计税) and the combined-tax (并入综合所得) methods. By tagging the bonus as `:component-kind :annual-bonus` (distinct from `:performance-bonus`), the audit-doc can break out the bonus and record the chosen method in `:jurisdiction-specific-codes {:cn/annual-bonus-method :single | :combined}` so the IIT engine and the consumer's 综合所得汇算清缴 reconciliation have unambiguous data.

**Decision NOT to.** Not implementing 企业年金 (enterprise annuity) detailed accounting — the vesting + asset-management complexity is out of v1 scope (note 87 §2.3). The component-kind set is open per ADR-075 so a consumer adding `:ee-enterprise-annuity` / `:er-enterprise-annuity` only needs to extend the catalog.

**Decision NOT to.** Not implementing 残保金 (disability employment guarantee fund — annual, formula-based), 工会经费 (trade union fund — 2% of wages), or 职工教育经费 (worker education fund — 1.5–2.5% of wages). All three are out of v1 scope per note 87 §7; consumers compute via their own annual close primitive and post to 5603 (Admin) / 2211.05 + 2211.06 with the kontor.l10n-cn chart already in place.

**Decision NOT to.** Not auto-emitting to 自然人电子税务局. The XML schema (申报表 2024-04) is public but is a consumer-side convertor step from kontor's CSV payload. v1 emits a `:audit-doc/category :payroll-filing` with `:audit-doc/language :zh-cn` and the structured CSV; the regulator-side upload + XML conversion is a consumer-held automation.

**Decision NOT to.** Not bundling per-city SI/HF rate tables. 200+ cities, the rate tables change ~annually (typically June or July re-basing), and the base-cap / base-floor are tied to local 城镇职工社平工资 announcements. The engine holds them; kontor consumes the engine output.

**Decision NOT to.** Not bundling IIT bracket tables. The 综合所得 brackets + quick-deductions are public regulation but regulator-versioned (last touched 2024). The engine applies the cumulative method; kontor consumes the result.

**Test discipline.** **Tests under `modules/payroll-cn/test/`** mirror the CA structure:
- `wage_types_test.clj` — component-kind catalog vocabulary, employer-side flag, posts? semantics, account-tag resolution.
- `compute_test.clj` — CSV parsing for Yonyou / Kingdee / Beisen fixtures, the per-engine column-mapping config, employee → eid resolution, the IIT-withheld + SI/HF deduction routing.
- `posting_builder_test.clj` — per-component routing to 2211 sub-accounts + IIT payable; sum-to-zero per (ledger, commodity); `:employment/province-of-employment` analytic.
- `accrual_test.clj` — `annual-bonus-accrual-tx-data` shape + balanced posting pair.
- `emit_test.clj` — `CnIitMonthlyEmitProvider` payload shape + `:audit-doc/category :payroll-filing` + `:audit-doc/language :zh-cn`.
- `e2e_test.clj` — 3 employees in BJ / SH / SZ, monthly payroll path + a 年终奖 path running through `kontor.hr.payroll/run-payroll!` per the ADR-075 orchestrator.

**Research backing.** doc/research/87-cn-payroll-research-before.md (full spec source + the licence + accounting-pattern citations), 79 §5.3 (C11 plan), 82 (DE pattern reference), 83 (US pattern reference), 84 (CA pattern reference).

**License posture (final).** STA + MoF specs public; ASBE 2211 / 2221 sub-account decomposition public (CAS 9 / 财会〔2014〕8号 / Cai Kuai [2016] No. 22); USCC (GB 32100-2015) public — already used in `kontor.l10n-cn.identifiers`. No bundled per-city SI rate table; no bundled IIT brackets; no 自然人电子税务局 credentials; no proprietary code from Yonyou / Kingdee / Beisen lifted. Component-kind catalog + account-tag map drawn from CAS 9 + the four references in §9 of note 87; the per-engine CSV column-mapping defaults are derived from public vendor API documentation.
## ADR-094 — Employee-monitoring substrate posture + consent schema + retention floors + refusal positions

**Status.** Accepted. Stage R+1.

**Context.** Research note 93 ("Employee track-record privacy", 2026-05-18) surveyed the multi-jurisdictional privacy + AI-Act + co-determination landscape that bounds what kontor's HR substrate can do credibly. Key findings:

- EU AI Act Article 5(1)(f)+(g) (in force since 2 Feb 2025) prohibits real-time biometric emotion recognition + biometric categorisation by sensitive characteristics in workplace + education.
- BAG 1 ABR 22/21 (13 Sep 2022) makes working-time recording mandatory under §3(2) Nr 1 ArbSchG — `:hr-activity-monitoring` is a positive obligation in DE, not a hazard.
- CA CPRA terminated the employee-data exemption on 1 Jan 2023; US employee data is now structurally GDPR-shaped under CCPA/CPRA.
- Illinois BIPA + Texas CUBI + Washington biometric statutes carry per-scan penalty exposure on fingerprint / facial time-clocks.
- GDPR Art. 6/9/22/35/88 + BDSG §26 + BetrVG §87 + DSGVO Art. 5(1)(e) are load-bearing for the multi-jurisdictional pitch.

Without a substrate-level posture, kontor cannot ship the trans-national showcase 06 or the `kontor-people-record` consumer credibly — compliance auditors would refuse the design without a documented category + consent + retention story.

**Decision.**

1. **Canonical category vocabulary (kernel).** Ship `kontor.audit-doc/canonical-categories` + `canonical-category-set` documenting the project-endorsed open-set of `:audit-doc/category` values. Adds 8 new HR values to the existing financial + payroll + tax + legal + compliance set: `:hr-track-record`, `:hr-activity-monitoring`, `:hr-activity-content`, `:hr-communications`, `:hr-background-check`, `:hr-compensation-negotiation`, `:hr-grievance`, `:hr-monitoring-consent`. Closes note 86 P0-86-2 vocabulary-canonicalization gap. No schema migration — `:audit-doc/category` is `:db.type/keyword` open-set.

2. **`:consent/*` mini-schema (kontor-hr).** Ten new attrs scoped to kontor-hr (per note 93 §5 "lives in kontor-hr initially"). A `:consent` row records that a `:person` has consented (or withdrawn consent) for processing data tagged with a particular `:audit-doc/category` scope, under a particular legal basis, supported by a particular DPIA / works-agreement / consent-form. `:consent/legal-basis` carries an open-set keyword vocabulary keyed to GDPR Art. 6(1)(a-f) + Art. 9(2)(a/b/h) + Art. 10 + BDSG §26(1/3/4) + a special `:ai-act-incompatible` substrate-level refusal marker (consumer-policy hook; kernel never enforces). `:consent/state` is an ADR-034 facet: `:proposed → :active → :withdrawn → :superseded`.

3. **`kontor.hr.consent` helpers.** Ship `grant!` / `withdraw!` / `supersede!` + matching `*-tx-data` builders (ADR-068 convention) + an `active-at?` query helper that respects the operational time window (`[granted-at, withdrawn-at)`) independent of the current `:state` — withdrawal does NOT retroactively invalidate prior processing.

4. **Per-jurisdiction retention-policy seeds (l10n companions).** Companion modules ship per-(jurisdiction × category) `:retention-policy` rows. The kernel ships shape only (ADR-050). ADR-094 lands the DE seeds at `modules/l10n-de/src/kontor/l10n_de/retention.clj` (7 seeds covering HGB §257 + BDSG §26 + GefStoffV §10a + DSGVO Art. 5 + BetrVG §82 + AO §147 + SGB IV §28f). Other l10n modules add their seeds incrementally — no schema change required.

5. **Two new `:approval-policy/rule` values (kernel).** `:requires-dpia-supporting-doc` enforces that the change-spec's `:supporting-doc` ref points to an `:audit-doc` carrying `:audit-doc/category :hr-monitoring-consent`. `:requires-works-agreement-ref` enforces that the change-spec includes `:works-agreement-ref` pointing at an audit-doc with `:audit-doc/type :betriebsvereinbarung` or `:works-agreement`. Both are kernel-side enforced in `kontor.status-machine/check-policy`, so any consumer transition can be gated on them via a transacted `:approval-policy` seed.

6. **Substrate neutrality.** The kernel does NOT enforce consent. `:consent/legal-basis :ai-act-incompatible` is a consumer-policy refusal marker — kontor.hr.consent treats it as just-another-keyword. Consumer policy layers (kontor-people-record, MCP agent tools, kontor.dsar bundlers) decide whether the marker blocks an action.

7. **Project refusal posture.** The kontor PROJECT publicly refuses to ship:
   - Default integrations with real-time biometric emotion recognition vendors (AI Act Art. 5(1)(f)).
   - Pre-canonicalized categories the maintainer believes facilitate AI-Act-banned use (`:hr-emotion-score`, `:hr-burnout-prediction`, `:hr-sentiment-derived`, etc.) — these stay possible as consumer extensions but never enter `canonical-categories`.
   - Bundled continuous-recording integrations with default-on configurations.
   - First-party "productivity score" derived metrics in the kernel or first-party companions. Third-party companions may; the project does not endorse.
   - Covert monitoring scaffolding ("stealth telemetry" features).

   This posture is the analog of ADR-001's no-Odoo-translation, ADR-005's no-bundled-Avalara-keys, ADR-010's no-UI-in-kernel. Substrate-tier neutrality coexists with project-tier opinion.

8. **AI Act forward-compat.** A `:ai-deployer-log/*` shape is reserved for the 2 Dec 2027 Article 26 high-risk-employment obligations (logs retained at least 6 months, deployer / provider chain, human-oversight events). Sketched in §3 of note 93; full landing as ADR-095 ~Q3 2027.

**Substrate posture summary.** The kernel tags + the substrate captures consent as bitemporal facts; the kernel does NOT decide. Consumer policy layers (kontor-people-record, kontor.dsar bundler, MCP agent tools, l10n-{de,us,gb,ca,eu} retention seeds) compose to enforce policy. The audit story is: "the substrate records every consent grant + withdrawal + supersession + retention policy + DSAR bundle + privilege classification, and a regulator can replay the bitemporal axis to verify what the consumer-policy layer decided at any past time T."

**What this does NOT do.**

- Does NOT add a UI for consent collection. Consumer companions (kontor-people-record) implement that.
- Does NOT bundle vendor-specific consent-form templates. Consumer companions ship those as `:audit-doc` template entries.
- Does NOT enforce consent at the kernel write path. Substrate stays neutral; refusal is consumer policy.
- Does NOT define biometric template storage shape. Deferred until a real consumer asks for fingerprint time-clock / facial door-access.
- Does NOT ship AI deployer logs. ADR-095 scope.

**Test discipline.**

- `modules/hr/test/kontor/hr/consent_test.clj` — 5 deftests / 32 assertions. Covers `canonical-categories` extension, `grant!` happy path, `active-at?` semantics across grant + withdrawal + scope-isolation windows, `supersede!` deactivation + replacement, empty-subject path.
- `modules/l10n-de/test/kontor/l10n_de/retention_test.clj` — 1 deftest / 15 assertions. Covers 7-seed install, hr-activity-content 0-year purge floor, hr-medical 30-year archive, country-ref binding, install! idempotency.

**Implication.**

- `src/kontor/audit_doc.clj` grows ~30 LoC for the canonical-categories defs.
- `modules/hr/src/kontor/hr/schema.clj` grows ~80 LoC for the `:consent/*` attrs + the status-transitions.
- `modules/hr/src/kontor/hr/consent.clj` is new (~190 LoC).
- `src/kontor/status_machine.clj` grows ~50 LoC for the two new rule branches in `check-policy`.
- `modules/l10n-de/src/kontor/l10n_de/retention.clj` is new (~120 LoC of seeds).
- No schema migration. No breaking change for existing callers. No behavior change for any consumer that does not opt in.

**Composition.**

- Composes with ADR-038 (`:approval-policy/rule`) via the two new rules.
- Composes with ADR-050 (`kontor.retention`) — l10n-de retention seeds plug straight in.
- Composes with ADR-051 (`:audit-doc/privilege`) — `:hr-grievance` typically also carries `:work-product` or `:attorney-client` privilege.
- Composes with ADR-052 (`kontor.dsar`) — `:consent` rows are part of a person's DSAR bundle.
- Composes with ADR-075 (`:audit-doc/category`) by extending the canonical vocabulary.
- Composes with ADR-090 (`:concept-iri` seam) — a consumer can attach an external-vocabulary IRI to a `:consent` row via a paired `:audit-doc` (consent forms get canonical taxonomy bindings the same way).

**Research backing.** doc/research/93-employee-tracking-privacy.md (~9656 words, full per-jurisdiction tables + ADR sketch + source citations); doc/research/94-strategy-synthesis-91-92-93.md (§3.1 priority queue rationale).

Date: 2026-05-18.

## ADR-090 — `:concept-iri` seam generalized across substrate entities

**Decision.** Add an optional `:concept-iri` attribute (string, cardinality-one, indexed, no constraint) to six substrate entity groups: `:account-tag`, `:account`, `:partner`, `:commodity`, `:tax`, `:document-type`. The attribute carries an IRI that binds a kontor entity to an external concept vocabulary (XBRL, FIBO, gist, regulator namespace, internal taxonomies). The kernel stores and indexes; verification + dereference is consumer-tier.

**Why.** Research note 80 ("McComb's *Future of Accounting* vs. kontor", 2026-05-18) surveyed Dave McComb's data-centric framework against kontor's substrate. The note concluded kontor is McComb-compatible at the substrate level but not McComb-conformant at the modeling level, and recommended URI-keyed external-concept seams as the cheapest aligned move (§7.1, §6.3). Research note 78 ("XBRL and accounting taxonomies", 2026-05-15) had already shipped the first such seam — `:account-tag/concept-iri` for filing-taxonomy bindings. ADR-090 generalizes that precedent to the five additional entity types where consumers want external-system concept identity.

**Substrate posture.** The kernel does NOT take a position on which vocabulary to use, how to dereference, or how to enforce ontology-level constraints. The attribute is a *transport slot* — a consumer with an IRI walks it forward via `kontor.explain/entities-with-concept-iri` (ADR-091) or backward via a custom datalog query. ADR-001's single-dep stance survives untouched; ADR-002's namespace cohabitation is honored (each entity type's `:<ns>/concept-iri` lives in its own namespace).

**Distinct from `:account/external-codes` (ADR-019).** `:account/external-codes` carries *regulator-specific reporting codes* (SKR04 numeric, DATEV, BR Plano Referencial) as many-cardinality refs to `:account-code` entities, each (account, regulator) pair distinct. `:account/concept-iri` carries *one cross-system concept identity* (XBRL line item, FIBO Account class) as a single IRI. Both can coexist on one account; neither is required. The split mirrors McComb's framing: regulator codes are *filing inputs*; concept IRIs are *cross-enterprise semantic identity*.

**Cardinality choice.** Single-cardinality. McComb's framing is that one entity has *one* canonical concept identity in an external vocabulary; multiple bindings to different vocabularies (e.g., one account is both `fibo-fbc:Receivable` AND `gist:Commitment`) suggests the consumer should use multiple `:account-tag` entities (each carrying its own `concept-iri`) rather than overloading the account's single slot. The decision is reversible — a future ADR could swap to many-cardinality if a real consumer use case emerges; the substrate-tier `:account-tag` workaround is the documented escape hatch.

**Index choice.** All six attrs are `:db/index true`. Inverse lookup (IRI → kontor entity) is the dominant query — `kontor.explain/entities-with-concept-iri` walks all six attrs by query, not by walking the substrate.

**Test discipline.** No new test ADR — the seams are tested via `kontor.explain-test` (ADR-091), specifically the `entities-with-concept-iri-finds-*` tests that round-trip a write through each of the six attrs.

**What this does NOT do.**

- Does NOT switch kontor to RDF/SPARQL (§5.4 of note 80 — divergence defensible). The substrate is still EAV + datalog; `:concept-iri` is an additive seam, not a substrate replacement.
- Does NOT commit kontor to a specific ontology (gist / FIBO / OntoREA). Consumers pick.
- Does NOT add resolution semantics. `:concept-iri` is opaque to the kernel; consumers verifying the IRI dereferences (HTTP 200, RDF parse, concept exists in vocabulary) build their own validator.

**Out of scope, deferred to future ADRs.**

- `:posting/concept-iri` — McComb's REA framing has no `gist:Posting` class; the closest mapping is `gist:Event + gist:hasMagnitude`. Per note 80 §9 Q4, the maintainer hasn't committed to that mapping work; defer until a real consumer pulls.
- `:transaction/concept-iri` — same reasoning. The transaction is the kontor projection of a business event; the *source event* mapping lives in consumer namespaces (beleg's `:invoice/*`, etc.).
- `:entity/concept-iri` — defer to multi-entity / consolidation rework.

**Implication.** Six +1-attribute schema additions; no behavior changes; no migration required (the attribute is optional). LoC: +6 attrs × ~8 lines = ~48 LoC in `src/kontor/schema.clj`. McComb-compatibility at the substrate level lifts from "one seam (XBRL tags)" to "six seams covering chart, partner, commodity, tax, document-type, account-tag" — every load-bearing kernel entity now has an optional external-identity slot.

**Research backing.** doc/research/80-mccomb-future-of-accounting-vs-kontor.md (§§5.1, 6.3, 7.1); doc/research/78-xbrl-and-accounting-taxonomies.md (the seam precedent on `:account-tag`); doc/research/87-mccomb-substrate-seams-round-1.md (this round's implementation rationale).

Date: 2026-05-18.

## ADR-091 — `kontor.explain`: substrate "explain this number" graph walks

**Decision.** Introduce `kontor.explain` as a substrate-tier read-only namespace exposing three helpers:

1. **`explain-balance`** — `(conn, account-eid, opts) → {:account :balance :postings :as-of-valid :as-of-tx}`. Returns the account-balance plus the ordered contributing postings (bitemporal-aware, `:as-of-valid` / `:as-of-tx`). Composes `kontor.balance/account-balance` + `kontor.ledger/postings-against` into one pull.

2. **`explain-posting`** — `(conn, posting-eid, opts) → {:posting :transaction :status-history :audit-docs :legal-holds :retention :origin-transaction-targets :as-of-tx}`. Walks from one posting back through the lifecycle stack: the originating transaction, the status-history rows on that transaction, the supporting audit-docs referenced from those history rows, the active legal-holds covering posting / transaction / caused entities, and the retention policy + deadline + eligibility for the posting.

3. **`entities-with-concept-iri`** — `(db, iri) → {:account :account-tag :partner :commodity :tax :document-type}`. Reverse-lookup: given an IRI from any of the ADR-090 seams, return the eids of all kontor entities binding to that concept. The McComb-aligned dereference: a semantic-web consumer with `ifrs-full:Revenue` walks back to the kontor accounts grounding it.

**Why.** Research note 80 §7 identified "the McComb killer feature" as: any computed number should be able to *explain itself* by walking the graph back through its sources. kontor already has the substrate (postings → transaction → status-history → audit-doc → legal-hold → retention); what's missing is the canonical walk. The note recommended `kontor.explain` as one of the highest value-to-effort McComb-aligned moves (§7.4 implicit, brief explicit).

**Substrate posture.** Pure read-only datalog over `d/db` snapshots — no transactors, no writes, no protocol surface. All shapes are plain Clojure maps with eids and keyword fields; consumers format and serialize. The maps are deliberately *data*, not Clojure records, matching McComb's "data outlives applications" framing.

**Bitemporal discipline.** `explain-balance` takes `:as-of-valid` + `:as-of-tx` (per ADR-008). `explain-posting` takes `:as-of-tx` but NOT `:as-of-valid` — explain is answering "where did this *recorded* fact come from?"; the valid-time of the underlying postings/transactions lives on `:db.valid/from` on the originating tx, which the result already carries via the pull pattern.

**Return-shape stability.** The three fns return data with stable keys (`:posting :transaction :status-history :audit-docs :legal-holds :retention :origin-transaction-targets :as-of-tx`); they OMIT keys when no data exists (no empty `[]` for sections with no rows). Consumers `(get r :audit-docs [])` to default. The omission-vs-empty discipline keeps the result terse for the common case (a posting with no holds and no audit docs).

**What this composes with.**

- `kontor.balance/account-balance` + `kontor.ledger/postings-against` (the per-account walks).
- `kontor.status-machine/status-history-of` (per-entity lifecycle).
- `kontor.audit-doc/pull-doc` (per-doc detail; explain pulls a summary).
- `kontor.legal-hold/holds-covering` + `kontor.retention/policy-for` (lifecycle gates).

**What this does NOT do.**

- Does NOT walk *across* postings on different transactions (an explain for posting P returns P's transaction only; it does not transitively explain *that transaction's other postings*). Consumers wanting transitive walks (e.g., "explain the trial-balance line for Receivable by walking each invoice's source event") compose `explain-balance` + `explain-posting` themselves.
- Does NOT format / render — no pretty-printing, no HTML / Markdown / JSON / RDF emission. The shape is data; consumers serialize.
- Does NOT understand REA / commitment-shaped reasoning. Per note 80 §7.2, a `kontor-commitment` companion (not landed in this round) is where commitment → fulfillment-event walks would live. `explain-posting` walks the audit-trail substrate; commitment walks are upstream of the audit trail.
- Does NOT emit events. Bus-style notification is `kontor.event-bus` (ADR-092).

**Test discipline.** `test/kontor/explain_test.clj`. 10 deftests / 41 assertions. Tests cover balance composition, bitemporal `:as-of-valid` filtering, transaction shape, status-history walking, audit-doc resolution via support-doc, the IRI reverse-lookup across all six ADR-090 seams (account / partner / commodity / and the empty case), and the "unknown eid returns nil" robustness case.

**Implication.** +1 namespace (`src/kontor/explain.clj`, ~280 LoC), +1 test namespace, no behavior changes elsewhere, no schema changes. Pure addition. McComb's "explain the number" loop is now substrate-tier — any consumer can build a "drill-down" UI in their own ergonomics on top of the data shape.

**Research backing.** doc/research/80-mccomb-future-of-accounting-vs-kontor.md (§7 — substrate-tier graph walk recommendation); doc/research/87-mccomb-substrate-seams-round-1.md (this round's rationale + design calls).

Date: 2026-05-18.

## ADR-092 — `kontor.event-bus`: in-process pub-sub for kontor transactions

**Decision.** Introduce `kontor.event-bus` as a substrate-tier in-process publish/subscribe facility for committed transactions. The public surface:

- **`register-handler!` / `unregister-handler!`** — subscribe / unsubscribe a handler `(fn [event] …)` with optional `:filter` and `:tag`. Returns a handler-id.
- **`dispatch`** — synchronously invoke every passing-`:filter` handler with one event map. Returns `{:invoked count}` with handler exceptions captured in metadata.
- **`commit-and-emit`** — a `:commit` fn for `kontor.process/run-process` (or any other path) that runs `kontor.validation/transact-with-validation` and then dispatches the bus event.
- **`->event`** — pure constructor for the event map shape; exposed so consumers with their own commit path can fire the bus directly.

Event shape (`{:event/kind :event/conn :event/tx-report :event/transactions :event/at}`). Currently only one `:event/kind`: `:transaction/committed`. Future kinds compose orthogonally; consumers filter on `:event/kind`.

**Why.** Research note 80 §2.3 + §4.2 identified event-driven storage as a defining McComb position. kontor's substrate IS event-shaped at the storage tier (every datom is a fact; every commit is an event), but a *consumer reacting to a commit* (refreshing a cache, mirroring to an external system, notifying a UI) has no canonical hook today. They have to poll the tx-log, mount their own `:tx-wrap`, or build the integration inside a `kontor.process` orchestrator. ADR-092 closes that gap with the smallest possible primitive.

**Substrate posture.** In-process pub-sub only. The bus is NOT Kafka / NATS / Redis Streams / RabbitMQ. A consumer wanting persistent / cross-process / cross-machine delivery writes an adapter on top — kontor explicitly stays in-process to honor ADR-001 (single-dep on datahike) and the single-runtime ADR-010 stance (no JS, no Python, no shell scripts).

**Failure isolation.** Handlers run AFTER `d/transact` returns. A handler crashing does NOT roll back the commit — the datahike commit is the durable event, the bus is the convenience. `dispatch` catches handler exceptions and accumulates them under `:errors` metadata on the return value; the writer's thread never sees a handler exception.

**Ordering.** Handler dispatch order is unspecified across handlers. A consumer needing ordering chains handlers internally (their `:filter` matches all events, their handler invokes their own subscriber list in deterministic order). The bus deliberately stays simple.

**Synchronicity.** Handlers run synchronously on the writer's thread by default. A consumer wanting async dispatch wraps their handler in a `future` or routes through their own work queue. The kernel offers no async facility because it would require a thread pool / lifecycle the single-dep constraint can't sustain.

**Process-local registry.** `kontor.event-bus/handlers` is a `defonce`'d atom. The registry survives ns reload and is shared across all connections — a single handler registered once fires for every conn's commits. Consumers wanting per-conn dispatch carry conn equality in their handler:

```clojure
(register-handler! (fn [ev]
                     (when (= my-conn (:event/conn ev))
                       …)))
```

Per-conn registries were considered (and rejected) for v1: most consumers run one conn per process, and the equality check is trivial for the multi-conn case. Adding a per-conn registry would couple the bus's lifecycle to conn lifecycle, and conn shutdown is a tricky cleanup signal datahike doesn't surface uniformly.

**What this composes with.**

- `kontor.process/run-process` — pass `:commit bus/commit-and-emit` to publish every process's commit.
- `kontor.validation/transact-with-validation` — same composition path (`commit-and-emit` calls it directly).
- A consumer's own commit path — call `(dispatch (->event conn tx-report))` after their own `d/transact`.

**What this does NOT do.**

- Does NOT publish *every* datahike write. Only writes routed through `commit-and-emit` (or where the consumer explicitly calls `dispatch`). Bare `d/transact` calls outside the gate bypass the bus deliberately — the substrate isn't a global tracer.
- Does NOT publish on transactor-side commits (pg-datahike SQL writes through `:tx-wrap`). Those go through `validation/validate-and-apply` inside the writer; the bus is outside-the-writer. A separate ADR can add that path if a consumer asks.
- Does NOT carry datom-level diff. The event carries the tx-report (with `:tx-data` as datoms) + a pulled summary of touched `:transaction` entities. Consumers wanting per-datom callbacks unpack `(:tx-data tx-report)` themselves.

**Test discipline.** `test/kontor/event_bus_test.clj`. 9 deftests / 27 assertions. Tests cover register / unregister round-trip, filter semantics, exception isolation, the `commit-and-emit` integration with `kontor.process/run-process`, the handler-crash-doesn't-block-commit invariant, and the no-handlers-no-emission base case.

**Implication.** +1 namespace (`src/kontor/event_bus.clj`, ~210 LoC), +1 test namespace, no behavior change for existing callers (the bus only fires on opt-in routing through `commit-and-emit`). A consumer wanting "every kontor write publishes" wires their app's commit fn to `commit-and-emit` once.

**Future extensions (deferred).**

- Additional `:event/kind`s: `:status-history/changed`, `:audit-doc/created`, `:posting/posted`. Each is a small constructor + filter contract; ship per-need.
- Adapter to Kafka / NATS — write outside the kernel; the adapter subscribes to the bus and forwards.
- Per-conn registries — defer until multi-conn deployment friction emerges.
- Async dispatch — defer until a real handler is slow enough to motivate it.

**Research backing.** doc/research/80-mccomb-future-of-accounting-vs-kontor.md (§2.3 + §4.2 — event-driven storage); doc/research/87-mccomb-substrate-seams-round-1.md (round 1 implementation rationale).

Date: 2026-05-18.

## ADR-082 — `kontor-payroll-mx`: CFDI Nómina 1.2 emit + Código Agrupador GL routing

**Status.** Accepted. Stage R C8.

**Context.** Mexico mandates electronic payroll (CFDI Nómina) for every payslip. The payslip XML is a CFDI 4.0 envelope (`TipoDeComprobante='N'`) carrying a `<nomina12:Nomina>` complemento, stamped by a PAC (Proveedor Autorizado de Certificación). Without a CFDI Nómina the worker's pay is not deductible for ISR and the worker cannot prove income. The kernel must therefore (a) produce the unsigned CFDI Nómina XML payload from canonical payroll facts, (b) route per-employee wage rows into the SAT Código Agrupador chart of accounts (the same chart the DPI / IVA returns lean on, shipped by `kontor.l10n-mx`), and (c) record the timbre / PAC stamp returned by the partner as an `:audit-doc` so the audit chain documents the filing.

The dominant mid-market MX payroll engines are CONTPAQi Nóminas, Aspel NOI, Microsip, NominasOnline. None of them compute payroll on the kontor side; they export a per-period file we *ingest*. The kernel therefore takes the same shape as `kontor-payroll-ca` (and the upcoming `kontor-payroll-de-datev`) — a Compute provider parses vendor output, a Posting builder routes the canonical facts to the GL, and an Emit provider produces the country-specific clearance XML.

**Decision.** Ship `kontor-payroll-mx` as a single companion module composing three protocols.

1. **`PayrollComputeProvider`** — vendor-export parsers. v1.0 ships `ContpaqiNominasProvider` and `AspelNoiProvider` (CSV; the XLSX export shares the column shape and is a consumer concern). Each parser projects rows into the canonical `:payroll-facts` map. The provider carries a configurable `code-map` from vendor concept code → kontor wage-type, so per-customer custom catalogs do not require provider subclasses.

2. **`PayrollEmitProvider`** — country-specific clearance-shape XML. v1.0 ships `MxCfdiNominaEmitProvider`, which assembles the `<nomina12:Nomina>` complemento + the CFDI 4.0 envelope. The XML is **unsigned**; the PAC stamp is partner-side. The provider returns a map carrying the load-bearing `:audit-doc/category :payroll-filing` and `:audit-doc/language :es-mx` tags per the task spec — the consumer hashes the XML and persists the `:audit-doc` via `kontor.audit-doc/create-doc!`.

3. **`posting-builder/build-period-tx-data`** — aggregates per-employee wage rows across an entire period and routes the totals through the SAT Código Agrupador. The chart-of-accounts entries are looked up by `:account/code` (the SAT 601.01 / 601.02 / 206.01 / 206.04 / 206.05 / 206.06 / 601.05 / 601.06 / 601.84 strings). One balanced journal records the period. Sum-to-zero is enforced by `kontor.posting/build-transaction`'s structural validator.

**Wage-type vocabulary.** The module ships a 14-entry registry covering the task spec: `:sueldo`, `:hora-extra-doble`, `:hora-extra-triple`, `:aguinaldo`, `:prima-vacacional`, `:vales-de-despensa`, `:fondo-de-ahorro`, `:isr-retencion`, `:imss-trabajador`, `:imss-patron`, `:infonavit-trabajador`, `:infonavit-patron`, `:rcv-patron`, `:subsidio-al-empleo`. Each entry binds the kontor keyword to (a) the SAT catálogo code (`c_TipoPercepcion` / `c_TipoDeduccion` / `c_TipoOtroPago`) per Anexo 20, (b) the `:percepcion | :deduccion | :otro-pago` partition that drives the CFDI block placement, (c) the SAT Código Agrupador account it routes to, and (d) whether the row is `:employer-only?` (IMSS patrón, INFONAVIT patrón, RCV patrón — these go to the GL but NOT to the worker-side CFDI). Consumers extend with custom wage-types by registering against the same shape; the kernel does not enumerate.

**GL routing (Código Agrupador):**

```
Dr 601.01 Sueldos y Salarios          (sueldo + horas extra)
Dr 601.02 Aguinaldo + Prima vacacional
Dr 601.05 IMSS patronales              (employer-only)
Dr 601.06 INFONAVIT patronales         (employer-only)
Dr 601.84 Otras prestaciones           (vales / fondo ahorro / non-taxable)

Cr 206.01 Sueldos por pagar (neto)
Cr 206.04 ISR retenido − subsidio al empleo
Cr 206.05 IMSS por pagar (trabajador + patrón + RCV-patron)
Cr 206.06 INFONAVIT por pagar (trabajador + patrón)
```

The provision aguinaldo accrual posts `Dr 601.02 / Cr 206.07`; the 206.07 code is the conventional Provisión Aguinaldo sub-account, overridable via `:provision-code` opt because not every chart installs it.

**Accruals.** `kontor.payroll-mx.accrual/aguinaldo-monthly-accrual` recognizes 1/12 of the legal entitlement each month (LFT Art. 87, minimum 15 days of salary by December 20). `prima-vacacional` computes the 25% surcharge on vacation pay (LFT Art. 80). The post-2023 LFT reform (Vacaciones Dignas) is encoded in `vacation-days-by-year` and the `vacation-days` function (12/14/16/18/20 + 2 every five years).

**Out of v1.0 (documented, not shipped):**
- **SUA** (Sistema Único de Autodeterminación) — the IMSS/INFONAVIT/RCV monthly remittance file. Fixed-width binary format, separate clearance shape, lives in a follow-up `kontor.payroll-mx.sua` namespace once the IMSS spec is captured.
- **PTU** (Participación de Trabajadores en Utilidades) — 10% of taxable profit, distributed annually by May 30. Needs the corporate ISR base; deferred to Stage R+ corporate-tax substrate.
- **DIOT** (Declaración Informativa de Operaciones con Terceros) — vendor reporting, lives in `kontor.l10n-mx`, not here.
- **PAC integration** — submitting the unsigned XML to a PAC and receiving the TFD UUID is partner-side (`kontor-l10n-mx-pac-*` adapters). The kernel records the returned UUID on `:audit-doc` per ADR-051; no PAC API keys are bundled.

**Schema additions.**
- `:audit-doc/category :db.type/keyword` — open-set keyword (`:payroll-filing`, `:tax-filing`, `:regulator-clearance`, …). Module-extensible.
- `:audit-doc/language :db.type/keyword` — BCP-47 tag (`:es-mx`, `:de-de`, …). Used downstream for localized rendering.

Both are kernel attrs (added to `src/kontor/schema.clj` under the `audit-doc-attrs` block) so other modules (DPI, e-invoice, lease disclosures, …) can reuse them.

**Discipline (per CLAUDE.md + ADR-068):**
- BigDecimal HALF-EVEN throughout; no doubles.
- Every business write exposes a `*-tx-data` pure builder (`build-period-tx-data`, `build-aguinaldo-accrual-tx-data`).
- No bundled SAT / IMSS / INFONAVIT rate tables. The consumer's payroll engine has them.
- No PAC credentials. No partner code in the kernel.
- Spanish (`:es-mx`) descriptions live in the wage-type registry — they appear in CFDI `Concepto` attributes and in `:audit-doc/description`, both legitimately localized by the regulator's spec.

**Trade-offs.**

*Aggregation-per-period journal vs per-employee journal.* We aggregate by SAT Código Agrupador across all employees in one period → one balanced journal. This matches how CONTPAQi and Aspel customers reconcile (per-period totals to a per-month bank reconciliation). The per-employee detail lives in `:payroll-facts` and in the per-employee CFDI Nómina XML; consumers wanting per-employee GL rows extend by emitting one `build-period-tx-data` per employee. We do not split because the kernel cannot infer payroll-cost-center routing without an analytic-plan mapping — that's a Stage R+ concern, deferred for the same reason as PTU.

*Employer-only rows in `:percepcion` partition.* IMSS patrón / INFONAVIT patrón / RCV patrón are recorded as `:percepcion` in our registry (they are employer expense) but flagged `:employer-only? true`. The CFDI Nómina filters them out on the worker-side block (`wt/employee-side`); the GL records them as `Dr 601.05/06`. This is the cleanest mapping: the registry partition follows GL direction (Dr expense = `:percepcion`), and the CFDI-vs-GL distinction is a separate label. Alternative was to add a `:employer-expense` kind, which would have leaked GL-direction concerns into the SAT-catálogo partition.

*Compute provider is CSV-only.* The vendors all also export XLSX. Adding `dk.ative/docjure` (Apache POI wrapper) for XLSX would bloat the kernel deps for a format that consumers can convert at ingest time. Decision: ship CSV, document XLSX-to-CSV as a one-liner in the module README, defer XLSX support to a consumer-side adapter.

**Tests.** `clojure -M:test --focus kontor.payroll-mx.*` exercises wage-types vocabulary, CONTPAQi+Aspel CSV parsing, accrual math, posting-builder balance + Código Agrupador routing, CFDI Nómina XML shape (TipoNomina O/E, Emisor/Receptor, Percepciones/Deducciones/OtrosPagos, totals roll-up, employer-only filter), and the full e2e (CSV → posting → CFDI → audit-doc). The XML round-trip test re-parses the emitted complemento and recovers TotalPercepciones, TotalDeducciones, TotalOtrosPagos.

**Acceptance.** `clojure -M:test --focus kontor.payroll-mx.*` clean.

Date: 2026-05-18.

## ADR-086 — `kontor-payroll-at`: the Austrian payroll adapter (engine-CSV in, GL + mBGM + L16 out)

**Status.** Accepted.

**Context.** Stage R adds per-country payroll adapters. Austria is the second jurisdiction (after Germany) — small market, but the kontor user base has an Austrian GmbH and the substrate must demonstrate that a second country lands as a thin adapter rather than a refactor of the kernel. Austrian Personalverrechnung has its own regulators (ÖGK, BMF, Gemeinde) and file formats (ELDA mBGM, FinanzOnline L16, BMD-CSV from the dominant local engine) — close to but disjoint from the German equivalents (DEÜV, ELStAM, DATEV LODAS).

**Decision.** A new module `modules/payroll-at/` (namespace root `kontor.payroll-at.*`) that:

1. Defines the **wage-type vocabulary** for Austria: `:grundgehalt`, `:überstunden`, `:urlaubsremuneration` (13th), `:weihnachtsremuneration` (14th), `:sachbezüge`, `:lohnsteuer`, `:sv-arbeitnehmer`, `:sv-arbeitgeber`, `:dienstgeberbeitrag-fond` (DB 4.1%), `:zuschlag-zum-db` (DZ ~0.32–0.40%), `:kommunalsteuer` (3%).
2. Defines the **default wage-type → RLG-1 account-code map** (6000, 6100, 6400, 6410, 6500, 6510, 6520, 6530, 6800, 3500, 3540, 3550, 3560, 3590, 3700). The map is data, overridable per consumer.
3. Ships **two engine adapters** as `compute-engine` defrecords: `BmdGlProvider` (BMD-NTCS Buchungsexport CSV, ISO-8859-15, semicolon-separated) and `RzlGlProvider` (RZL Lohn FibuExport CSV, same separator, smaller schema). Both produce the same normalized `:payroll-result` map.
4. Ships a **posting-builder** that consumes the normalized `:payroll-result` and produces ONE balanced kernel transaction per payroll period using the default account map (per ADR-068, the leaf is a pure `*-tx-data` builder with the `!` wrapper routing through `transact-with-validation`).
5. Ships **two accrual builders** — Urlaubsrückstellung (UGB §198 Abs.8) and Sonderzahlung-Rückstellung (13./14. monthly accrual). Both are leaf tx-data builders; both compose with `kontor.process` for a coherent month-end run.
6. Ships **two emit functions** — `emit-mbgm` (monthly ÖGK mBGM XML — Dachverband XSD shape) and `emit-l16` (annual BMF L16 XML — FinanzOnline Lohnzettel shape). Each computes the artifact bytes + a SHA-256 + transacts an `:audit-doc` row (`:audit-doc/category :payroll-filing`, `:audit-doc/language :de`).
7. Ships an **`AtPayrollEmitProvider`** record that composes both emit functions, mirroring `kontor.einvoice-provider/EInvoiceProvider`'s shape.

**Kernel additions.** Two new optional schema attrs:

- `:audit-doc/category` — open-set keyword (`:payroll-filing`, `:vat-filing`, `:income-tax-filing`, …); indexed; used by consumers to bucket emitted artifacts.
- `:audit-doc/language` — ISO-639-1 keyword (`:de`, `:en`, `:fr`, …); the natural-language code of the artifact's content.

Both are accepted by `kontor.audit-doc/create-doc-tx-data` (optional keys). Neither is gated by the kernel — pure descriptive metadata. Without these, per-country adapters would have to invent their own attrs (cohabitation hazard).

**No bundled rates / no credentials.** The module ships NO LSt tariff table, NO SV-rate table, NO Kommunalsteuer-Befreiungsliste, NO ÖGK API keys. The rates are annually-updated regulator data; the consumer or a `kontor-l10n-at-rates-<year>` partner artifact supplies them. The two engine adapters read the engine's CSV output — the engine computed the per-employee LSt + SV already; the adapter is in the IO-and-mapping business, not the rate-engine business.

**Reuse opportunity vs duplication.** AT payroll structurally shares a LOT with DE — both have CSV-from-engine ingest, both have wage-type → CoA maps, both have monthly accrual under §198/§249, both have monthly + annual regulator artifacts. We considered factoring a `kontor.payroll.de-at-common` shared layer (or even a kernel-level `kontor.payroll-provider` protocol). **Decision: keep modules independent.** Per the project culture of "each country adapter ships separately" (note 86 of CLAUDE.md's referenced research), and the existing `l10n-de` ↔ `l10n-at` precedent — they share German-language resource keys but the implementation is independently maintained. The duplication is minimal; a leaky shared abstraction would cost more.

If a third German-language country lands (a Swiss-DACH adapter or a Liechtenstein extension), revisit and factor. Today's call: copy patterns by hand from DE, write the AT code fresh.

**Scope discipline (v1).** In: gross-to-net mapping; the monthly GL transaction; the two accruals (Urlaubsrückstellung + 13./14. Sonderzahlung); mBGM XML emit; L16 XML emit; the emit-provider record. **Out:** Abfertigung-Alt actuarial (pre-2003 employees; requires actuarial model + Sterbetafel — a separate `kontor-l10n-at-abfertigung` artifact), BV / SVS post-2003 4.5% employer contributions (kept consumer-driven), BUAK (construction-industry sectoral fund), Reisekostenabrechnung (belongs in `kontor-expense`, not payroll).

**Composition over orchestration.** All write-side fns expose a pure `*-tx-data` leaf (ADR-068); a `!` wrapper routes through `kontor.validation/transact-with-validation`. A month-end `run-payroll-period!` orchestrator in `kontor.payroll-at.core` composes [compute → post-gl-tx → accrue-urlaub → accrue-sonderzahlung → emit-mbgm → maybe-emit-l16] as a `kontor.process` step-list. Per ADR-067, every step is pure-data; the orchestrator is what reads inputs from db and produces tx-data.

**Per-employee detail in mBGM.** The mBGM is per-employee; the GL transaction is the period summary. The two diverge — the mBGM needs VSNR + Beitragsgruppe per employee, the GL line aggregates them. The compute step produces both views: the normalized `:payroll-result` carries `:employees [{VSNR base lst sv ...} ...]` AND the period totals.

**Bitemporal.** Per-employee aggregates are stamped `:tx/valid-from <period-end>` so the mBGM-as-of-filing-date query is bitemporally well-defined. An amendment (correction posting in a later period) writes a new mBGM with the correction flag; the prior mBGM stays queryable as-of-its-filing-date.

**Per-country naming.** Namespace root is `kontor.payroll-at.*` (hyphen-separated, mirrors `kontor.l10n-at.*`). Directory `modules/payroll-at/`. Wire format keywords are `:at/mbgm`, `:at/l16` (the same convention as `kontor.einvoice-provider/envelope-format`).

**Why now.** Substrate audit-doc + status-machine + bitemporal + `*-tx-data` builder convention are in place. The AT adapter is a credibility marker that the substrate can host a second country; if it goes well, the (similar but harder) DE-payroll adapter follows.

**Implication.** Adds 2 schema attrs (1 indexed), 1 module (~8 source files + tests), 1 research note (80). Zero kernel API breakage.

**Research backing.** `doc/research/80-payroll-at-research.md`.

Date: 2026-05-18.

## ADR-095 — `kontor.book`: the verb facade

**Status.** Accepted. Stage 1 of research note 99.

**Context.** The McComb round (notes 80 / 88 / 97 / 98) and the critical reading in note 97 concluded that kontor does *not* build a stored-`:event` / θ-as-data framework: kontor's ~200 `*-tx-data` builders + `!` wrappers (ADR-068) already *are* the event vocabulary, spelled as function calls; sealing (ADR-007) neutralizes the framework's re-derivability payoff; "events" are the dispatch operations kontor already provides. Note 99 deflated the scope to three targeted moves. This ADR is move 1.

The friction the facade removes: there is no small, named, uniform on-ramp. A consumer posting a sale hand-assembles a `{:transaction {…} :postings [{…} {…}]}` map for `kontor.posting/post-transaction!`. The tedious, error-prone part is building the postings vector and remembering the header's required fields.

**Decision.** Ship a new kernel namespace `kontor.book` — *organizing sugar*, not a new layer.

1. **One builder.** `entry-tx-data` is the single pure ADR-068 builder behind every verb (composable into a `kontor.process` step list); it requires `:journal` + `:effective-date` explicitly. `entry!` routes it through the validation gate via `post-transaction!`, and adds two ergonomic conveniences: it resolves `:journal` from a `:journal/type` when `:journal` is absent, and defaults `:effective-date` to now.

2. **Eight named verbs as `!`-side conveniences** — `receive!` `pay!` `sell!` `buy!` `receive-payment!` `pay-bill!` `transfer!` `adjust!`. Each delegates to `entry!` with a baked-in `:journal/type` and carries a teaching docstring. There is no per-verb `*-tx-data` builder: there is exactly one business write here (post a balanced transaction), and `entry-tx-data` is it. The verbs are the named front door.

3. **Uniform signature.** One options map: `:debit-account` / `:credit-account` / `:amount` / `:commodity` / `:effective-date` / `:journal` (+ optional `:narration` / `:partner` / `:external-id`). `adjust!` instead takes `:postings` — a vector of friendly `{:account :amount :commodity?}` maps — for multi-leg / judgment entries (the synthetic residue of note 97 §8). The credit leg's amount is the negation of the debit leg's; sum-to-zero (`Ker(σ)`) holds by construction.

4. **`:debit-account` / `:credit-account`, not role-named keys.** kontor's facade audience is Clojure developers (ADR-010 — no UI); a two-leg entry has exactly two slots; naming them per-verb relocates rather than removes the debit/credit decision. The verb name + docstring teach the convention.

5. **Namespace name.** `kontor.book` — "to book a transaction" is the accounting term of art, and the namespace is free (`kontor.event` collides conceptually with ADR-092's `kontor.event-bus`; `kontor.activity` overlaps `kontor.process`; `record` is a `clojure.core` name).

**Acceptance criterion (orphan-class test, per note 99 DCR sharpening).** No verb without a real business event behind it; no common business event without a verb. The "minimal 8" close a complete cash + accrual single-entity book — verified end-to-end in `book_test.clj` (`buy → sell → receive-payment → pay-bill`, zero trial balance, balances matching a hand-built `post-transaction!` baseline).

**Implication.** One new kernel namespace, **zero schema change**, strictly additive — pure sugar over existing kernel builders. The verb signature is a published surface and is treated with the care ADR-068 gives `*-tx-data`. Deliberately excluded from the kernel facade: `deliver` (needs a `CostingProvider` — it is the inventory companion's verb) and `order` (a commitment, not a posting — Stage 4 / ADR-098).

**Research backing.** `doc/research/99-event-driven-accounting-staged-plan.md` (Stage 1); notes 97 (the critical reading), 80 (the McComb map).

Date: 2026-05-20.

## ADR-096 — The report engine as a family of marginalizations (σ_E)

**Status.** Accepted. Stage 3a of research note 99.

**Context.** Research note 97 §3 established that, in the algebra of accounting (Cruz Rambaud's balance module), a report — the balance sheet, a P&L, a tax-return box, a consolidation — is a **quotient epimorphism `σ_E`**: partition the postings by some equivalence `E` and sum within each class. `kontor.report` had two hard-wired `defmulti` engines (`:account-codes`, `:tax-codes`) — each genuinely a `σ_E` for one specific partition, but the partition was not first-class and there was no way to aggregate over an arbitrary dimension. The chart of accounts was the only axis a report could pivot on.

**Decision.** Generalize `kontor.report` so the engine is dimension-agnostic. No schema change; the kernel namespace is the designed extension point.

1. **`sum-postings`** — the shared fold every engine ends in: filter → signed-amount (`:raw` | `:inflow`) → reduce → `{:value Money :postings [eid…]}`. Extracted so the engines stop duplicating it.

2. **`marginalize`** — the `σ_E` primitive. `(marginalize postings dimension opts)` partitions `postings` by `dimension` (a built-in axis keyword or a `posting→class` function) and `sum-postings` each class, returning `{class {:value :postings}}`. For a scalar axis this is a true partition — the classes' values sum to the grand total (a marginalized trial balance is `Ker σ`); for the set-valued `:account-tags` axis it is a covering.

3. **Built-in dimensions** — `:account-type`, `:account-code`, `:ledger`, `:entity`, `:commodity`, `:partner`, `:account-tags` — each resolvable from the pulled-posting map (`pull-posting` gained `:posting/partner`). These are CBox-style classifications in McComb's TBox/CBox/ABox split (note 99 DCR sharpening): consumer-governed taxonomies, not kernel structure.

4. **`:dimension` engine** — a generic `run-engine` method: `{:engine :dimension :dimension <axis> :match <value-or-coll>}` sums the one class. The historical `:account-codes` and `:tax-tags` engines were **rewritten to delegate to `sum-postings`** — byte-identical behaviour (same filter, same fold) — so the 11 l10n tax-return modules that consume them are untouched.

5. **`report-postings`** — public fetch+bitemporal-filter, returning the pulled-posting vector the engines / `marginalize` consume. Exposed so `marginalize` is usable as a standalone `σ_E` primitive, not only through a report definition.

6. **`:posting-filter`** — an optional vector of extra datalog `:where` clauses on `compute-report`, appended to the candidate-posting query so a consumer can narrow the O(all-postings) scan at the datalog level (the cheap mitigation — a materialized / incremental report stays a deferred follow-up).

**Acceptance.** `test/kontor/report_test.clj` — `marginalize` over `:account-type` reproduces the balance sheet (each class correct; classes sum to zero — `Ker σ`); the `:dimension` engine on scalar and set axes; `:account-codes` / `:tax-tags` unchanged; `:posting-filter` narrows. The 11 l10n tax-return modules' tests are the behaviour-identical regression gate (full `bb test`).

**Implication.** Kernel, **no schema change**, strictly additive — pure generalization of an existing namespace. A pre-existing dead `kontor.bitemporal` require in `report.clj` was removed in passing. Stage 3b (ADR-097) adds the `:posting/dimension` schema so a posting can carry classification axes beyond `:account` — `marginalize` already supports the axis once the data exists.

**Research backing.** `doc/research/97-commitment-event-accounting-three-layer-model.md` §3 (reports as quotient epimorphisms); note 99 Stage 3a.

Date: 2026-05-20.

## ADR-097 — Classification dimensions: `:posting-dimension` + `:posting/dimensions`

**Status.** Accepted. Stage 3b of research note 99.

**Context.** ADR-096 made the report engine's `marginalize` dimension-agnostic — but a posting could only be *classified* by its `:posting/account` (and the few axes derivable from it: type, code, ledger, entity, commodity, partner). Real accounting also pivots on cost centre, project, segment, fund, and McComb-style business categories. The McComb critique (research notes 80 / 88 / 99) is precise here: the account number must not be THE classification axis — it is *one* axis, and overloading it (the "4400 = sales-19%-in-region-X" konto-smuggling that DE/AT charts do) is exactly the anti-pattern. Demote `:account` to one axis among several.

**Decision.** Add a kernel entity for a posting's classification tags. No change to balance/posting semantics — dimensions are read-only metadata `marginalize` partitions over.

1. **`:posting-dimension`** — `{:posting-dimension/axis <keyword> :posting-dimension/value <string>}`. The `:axis` is an open-set keyword (`:cost-center`, `:project`, `:segment`, `:fund`, …) consumers define; `:value` is a **flat string tag**.

2. **`:posting/dimensions`** — a `:db.cardinality/many` ref from a posting to its `:posting-dimension` entities. A posting with no analytic classification carries none — existing postings and the whole 11-module suite are untouched, zero migration, zero overhead.

3. **`report.clj`** — `pull-posting` reads `:posting/dimensions` into a `{axis #{values}}` map; `resolve-dimension` treats *any* keyword not in the built-in `dimension-extractors` as a `:posting/dimensions` axis (set-valued — a posting may carry several values on one axis). `marginalize` and the `:dimension` engine therefore pivot over a custom axis with no further code.

4. **`kontor.book`** — a friendly posting map in `:postings` accepts `:dimensions {axis value}` (a collection value expands to several). The two-leg verbs do not yet take `:dimensions` (which leg an analytic tag belongs on is a per-business call) — `adjust!`/`:postings` is the Stage-3b integration point.

**Flat tags, not an ontology.** Per the note-99 DCR sharpening (McComb's *Data-Centric Revolution*, the TBox/CBox/ABox split): a `:posting-dimension` is a CBox value — a consumer-governed classification, not kernel (TBox) structure. The `:value` is deliberately a string, not a ref into an entity with its own relational web; "taxonomies are flat tags." The anti-pattern explicitly rejected is **axis-as-attribute** — a bespoke `:posting/cost-center` + `:posting/project` + … attribute per axis (the SNOMED mistake of promoting every classification to a schema slot). Keep the axes few and structural, the values data.

**Why an entity, not a tuple or per-axis attr.** A `:db.type/tuple` `[axis value]` card-many attr would be lighter, but card-many tuple support is less load-bearing across datahike than a plain card-many ref — and a ref entity leaves room to attach provenance later without a schema break. Per-axis attributes are the rejected anti-pattern above. The `:posting-dimension` entities are not interned (no composite `(axis,value)` identity) — each dimensioned posting owns its rows; simple, and only dimensioned postings pay.

**Acceptance.** `test/kontor/report_test.clj` (Stage 3b section) — an entry booked through `kontor.book/adjust!` with `:dimensions` on its legs, then `marginalize`d over `:cost-center` and `:project`; the `:dimension` engine over both axes; the same postings repartitioned on a second axis.

**Implication.** Kernel schema add, strictly additive. `marginalize` already supported arbitrary axes (ADR-096) — this ADR is what gives a posting the *data* to be classified by something other than its account. Stage 4 (`kontor-commitment`, ADR-098) and the marginalizing report engine together are the McComb-aligned core of note 99.

**Research backing.** Research note 99 (Stage 3b + the DCR sharpening section); notes 80 / 88 (McComb survey + substrate seams).

Date: 2026-05-20.

## ADR-098 — `kontor-commitment`: recognising and liquidating obligations

**Status.** Accepted. Stage 4 of research note 99.

**Context.** The general ledger records what *moved* — postings, balanced to zero (`Ker σ`). It does not record what is *supposed to* move: a receivable a customer owes before they pay, a payable you owe before you settle it, an encumbrance you have reserved against a budget. The McComb reading behind note 99 frames this precisely — recognising and liquidating **obligations** is the half of accounting the ledger alone cannot see. kontor had pieces of this scattered (`:payment-promise` in kontor-collections, lease liabilities in kontor-lease, `:schedule` recurring postings, `:order` lines) but no first-class, general obligation entity.

**Decision.** A new companion module `modules/commitment/` — `kontor-commitment` — with a first-class `:commitment` entity. **The kernel is untouched.**

1. **`:commitment`** — `{:external-id (identity), :kind (:receivable|:payable|:encumbrance), :counterparty, :entity?, :committed-amount, :fulfilled-amount, :commodity, :due-date, :state, :recorded-by-uid, :recorded-at, :origin?, :notes?}`. `:state` is an ADR-034 status-machine facet: `:open → :partially-fulfilled → :fulfilled`, with `:open`/`:partially-fulfilled → :cancelled`.

2. **`:commitment-fulfillment`** — an edge entity `{:commitment, :transaction, :amount, :fulfilled-at, :recorded-by-uid, :notes?}`. The edge points *at* a kernel `:transaction` — but the kernel `:transaction` gains **no attribute**. A commitment can be fulfilled by many transactions (partial settlement); the edge is the many-to-one join, and it lives entirely in the companion.

3. **Helpers** (all ADR-068 — pure `*-tx-data` builder + `!` wrapper through `kontor.validation`, bitemporally stamped): `record-commitment!`, `fulfill!` (records the edge, advances the `:fulfilled-amount` denorm in the same tx, transitions `:state` when it actually changes), `cancel!`, plus the queries `open-commitments`, `outstanding`, `aging`, `pull-commitment` / `resolve-commitment`.

4. **`:fulfilled-amount` denorm.** `outstanding = committed − fulfilled`. The running total is denormalized onto `:commitment` and updated in the *same* transaction as each `:commitment-fulfillment` edge — no drift window. `aging` and `open-commitments` stay O(1) per commitment.

**Conservatively scoped.** `:commitment/origin` is an **opt-in soft link** (a plain ref, kernel-uninterpreted) to whatever the obligation arose from — an `:order` line, a `:schedule`, a lease liability. Those modules are **not changed**, and `:commitment` does not try to be their common supertype. Unifying the several obligation sources behind one vocabulary is a real piece of work and a deliberately deferred later pass — note 99 names it; this ADR does not attempt it. Building the general entity first, migrating origins later, is the low-risk order.

**Bitemporality.** `record-commitment!` / `fulfill!` / `cancel!` stamp `:tx/valid-from` via `kontor.bitemporal/with-vt`. `open-commitments` over `(d/as-of (d/db conn) t)` gives a fully consistent tx-time snapshot — state included. (A valid-time-resolved `open-commitments` is a follow-up; the entities carry the stamps for a stratum/valid-time query to use.)

**Acceptance.** `modules/commitment/test/kontor/commitment_test.clj` — record an open receivable; `sell!` then `receive-payment!` through the verb facade; `fulfill!` links the settling transaction; `open-commitments` closes it out; partial-then-complete; `cancel!`; `aging` buckets a still-open one; the tx-time snapshot before a fulfillment still sees the commitment open.

**Why a companion, not the kernel.** ADR-002 / ADR-010 — the kernel is the double-entry substrate; obligations-vs-ledger is a workflow layer. Keeping it a companion preserves the single-dep kernel and lets a consumer that only wants the ledger ignore it entirely. It cohabits the same DB via the `:commitment/*` namespace (ADR-002).

**Research backing.** Research note 99 (Stage 4 + the DCR sharpening — "recognition and liquidation of obligations"); notes 80 / 88 (McComb).

Date: 2026-05-20.

---

## ADR-099 — `PeriodTaxProvider`: the period-tax substrate

**Status.** Accepted. Iteration 1 (the substrate) implemented; research note 102.

**Context.** ADR-071's `TaxRateProvider` handles *transaction-incident* taxes — VAT, sales tax, withholding — one `TaxFacts` per invoice line. It is structurally incapable of *period/entity-incident* taxes — personal and corporate income tax, capital gains, property/wealth tax, standalone employer payroll levies — which attach to an **entity over a period** and are computed from an **aggregate** through a **schedule** (progressive brackets, not a flat per-line rate). Research note 102 surveyed all 11 kontor legislations and found kontor computes **exactly one** period tax today (CA personal income, `l10n-ca/y2024/t1` — a single-year, single-province prototype); corporate income tax and the entire property/asset/wealth family are uncomputed everywhere.

**Decision.** A **sibling** protocol, `PeriodTaxProvider` — not `TaxRateProvider` stretched. Both are the one general form `(scope, base-selector, schedule) → liability → posting`; they fill the slots with categorically different contents, so they are siblings sharing the *vocabulary discipline* (closed enum, open implementation — note 101) but no protocol operation. Three new kernel namespaces, **schema-free** (`TaxReturnFacts` is a runtime record, like `TaxFacts`):

1. **`kontor.tax-schedule`** — the schedule algebra: `apply-schedule` over four base shapes (`:flat`, `:progressive-bracket` — generalizing CA's `apply-brackets`, `:capped`, `:formula`) plus the `:elect` combinator (same base, pick min/max — taxpayer election); `surtax-on` for tax-on-a-tax (DE Soli, JP reconstruction surtax, IN/BR cess). Commodity-agnostic BigDecimal arithmetic; a `:flat` schedule is also what a transaction tax's `rate × base` is, so the two tax families share this layer.

2. **`kontor.period-tax-provider`** — the `PeriodTaxProvider` protocol (`period-tax-facts [this {:entity :period :db …}] → TaxReturnFacts | nil`), the `TaxReturnFacts` record (a component vector over a **closed 8-value `period-tax-kinds` enum** — `:personal-income-tax :corporate-income-tax :capital-gains-tax :property-tax :wealth-tax :payroll-tax-employer :minimum-tax :branch-or-presumptive-tax`), and the helpers `assessed?` / `total-liability` / `total-prepaid` / `balance` / `valid-return-facts?` (the closed-vocabulary structural check). The base-selector is `kontor.report/marginalize` (ADR-096) — a windowed σ_E for flow bases, a cumulative roll-up for wealth-tax stock bases.

3. **`kontor.tax-return-posting-builder`** — the `TaxReturnPostingBuilder` protocol (`provision-tx-data` / `payment-tx-data`) + a generic `StaticTaxReturnPostingBuilder`. Unlike the transaction `TaxPostingBuilder` (which returns legs to splice into an invoice), this returns **whole balanced transactions** via the `kontor.book` verb facade — a period tax IS its own transaction (provision at close, payment later). A tax provision is a `:payable` `kontor-commitment`-shaped obligation; the builder composes with kontor-commitment but does not require it.

**Design stresses (note 102 §7 / §9), carried not pre-solved.** Capital gains is a per-disposal/annual hybrid; presumptive regimes have a base not in the books; minimum taxes / surtaxes compose over *other components*; elective regimes (IN/BR/MX/CN) make a tax a *set* of `(transform, schedule)` pairs. The substrate accommodates these via the `:elect` combinator, `surtax-on`, the `:minimum-tax`/`:branch-or-presumptive-tax` enum members, and the component's `:regime` / `:composed-of` / `:line-items` / `:inputs` slots — naming the stress so it is ADR-trackable, never a hidden flag.

**Tested.** `period_tax_provider_test.clj` — 8 tests / 31 assertions: the schedule algebra (all shapes + `surtax-on` + monotonicity + one-bracket≡flat), the `TaxReturnFacts` helpers, `valid-return-facts?` rejecting an out-of-vocabulary `:kind`, and a synthetic `PeriodTaxProvider` exercising the **whole pipeline end to end** — book → `marginalize` (σ_E base-selector) → schedule → `TaxReturnFacts` → provision posting (`Ker σ`) → payment.

**Implication.** Kernel, schema-free, strictly additive. Iteration 1 is the substrate; the per-jurisdiction build is staged (note 102 §10) — pilot = CA `t1` re-expressed as a provider; then the unmodeled standalone payroll taxes (MX ISN, AU state payroll, AT Kommunalsteuer), flat-rate corporate income tax, and personal income tax (AT/AU clean, DE/FR as design-stress validators). Capital-gains lot/ACB tracking and a property-asset-register-with-assessed-value are named deferrals. Per-jurisdiction ports are consumer-demand-driven, as ADR-071 established for the transaction side.

**Research backing.** Research note 102 (the design + the 11-legislation gap map + the §9 reconciliation); notes 100 / 101 (the transaction-tax precedent + the closed-vocabulary/open-implementation discipline).

Date: 2026-05-21.

### ADR-099 — Addendum 2026-05-21: coverage-proof gaps closed (research note 103)

After ADR-099's substrate shipped, a two-agent coverage proof (research note
103) verified it against a 44-cell `(tax-type × legislation)` matrix across all
11 kontor legislations. Verdict: the design holds — 42/44 regular cells
expressible, property tax and standalone payroll levies clean across all 11.
But three gaps surfaced: each was foreseen by note 102 §9 yet had not been
carried from the §1–§4 v1 spec into iteration-1's code. All three are closed
here — small, additive, schema-free:

- **GAP 1 — the base transform.** Corporate income tax's taxable base is book
  profit ± statutory add-backs; BR Lucro Presumido's is a presumption ratio ×
  revenue. The pipeline was `base-selector → schedule` with no stage between.
  Added `kontor.tax-schedule/apply-base-transform` over a closed
  `:transform/type` set (`:identity` / `:presumption-ratio` / `:adjustments` /
  `:formula`; nil = identity) and an optional `:base-transform` component
  field. The provider pipeline is now `marginalize → apply-base-transform →
  apply-schedule`.

- **GAP 2 — minimum tax over divergent bases.** `:elect` applies its
  sub-schedules to one shared base; a genuine minimum tax (IN MAT, US AMT)
  compares regular tax against a minimum on a *different* base. Added the
  component-level combinators `greater-of` / `lesser-of` — `greater-of` is the
  minimum-tax shape; the provider computes both arms and composes them,
  recording the relationship in `:composed-of`.

- **GAP 3 — the tax-unit reaching the schedule.** `:formula` schedules were
  single-arity `(fn [base])`, so a household/filing-unit descriptor (FR
  quotient familial, DE Ehegattensplitting) had to be closed over at
  construction. `apply-schedule` now has a 3-arity `[schedule base ctx]` (the
  2-arity threads `ctx` = nil); `:formula` fns are `(fn [base ctx])` and read
  `:tax-unit` from `ctx`. `:elect` threads `ctx` into its sub-schedules.

**The `:inputs` capital-loss-carryforward convention.** note 103 §3a: the
capital-loss carry-in uses the fixed key `:inputs {:capital-loss-carryforward
{:short <Money> :long <Money>}}`; the residual after netting is reported in the
component's `:line-items`. A documented convention, no code — it closes the
`s3.clj` "handled at the NoA-ingestion layer" hole.

**Capital gains — sequencing.** note 103 confirms CGT is v1-expressible (gains
fed via `:inputs`, the `s3.clj` pattern) for CA/AT/FR/CN/IN; the 6
jurisdictions with holding-period splits or non-trivial cost-base rules
(US/AU/JP/DE/IN/MX) need a `:disposal/*` data model for a faithful build — a
companion module, zero kernel change, deferred and named. note 102 §10 is
revised: `:inputs`-fed CGT ships with the CA T1 pilot (CA folds CGT into
income).

**Tested.** `period_tax_provider_test.clj` — 11 tests / 44 assertions: the
three new capabilities (`apply-base-transform` over all shapes, `greater-of` /
`lesser-of`, `:formula`/`:elect` threading `ctx`) plus the iteration-1 suite.

Date: 2026-05-21.
