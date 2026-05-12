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
