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
