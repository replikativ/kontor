# Handoff — DE + CA compliance hardening

**Written:** 2026-05-10
**Status when written:** kernel + 5 jurisdictions in breadth; depth-first hardening for DE and CA is the next iteration.
**Why DE + CA:** DE is the most-developed l10n (UStVA, DATEV, EÜR, P&L, BS, closing, invoice). CA is personal — the author has CA personal tax filings outstanding after losing access to a prior Wealthsimple Tax (formerly SimpleTax) account.

---

## Status snapshot

### Kernel — done

Foundation is solid, no half-built features blocking new work:

- Double-entry kernel: `Money`, `posting/build-transaction` sum-to-zero, schema with namespaced kernel attrs.
- Invariants via `datopia/invariant` (on datahike 0.8.x): account-active, commodity-match, sealing, period-lock, state-machine.
- Bitemporal reads: `balance.clj`, `ledger.clj`, `trial.clj`. Period model with hard/soft locks + special periods. Year-end closing (P&L → retained, OB carry-forward, period seal).
- Analytic-account schema (ADR-012) — present but under-exercised.
- Beancount round-trip — Phase 1 acceptance criterion met.
- AR/AP aging + payment terms; invoice entity + workflow; bank reconciliation (single + multi-line settlement matchers).
- Income-tax basics: P&L + BS + DE EÜR.
- End-to-end demo scenario.

### Five jurisdictions — breadth in place

Each has chart + tax-return *model* (not yet a filable artefact):

- **DE** (`modules/l10n-de`): SKR04 chart, UStVA report model, DATEV oracle, EÜR, BS, P&L, invoice, closing.
- **AT** (`modules/l10n-at`): Kontenrahmen, UVA U30 model. `:tax/authority` schema attribute added.
- **FR** (`modules/l10n-fr`): Plan Comptable Général, CA3 TVA model.
- **CA** (`modules/l10n-ca`): chart, returns (GST/HST/PST/QST multi-province).
- **US** (`modules/l10n-us`): QBO chart, sales-tax provider scaffolds.

Bank-CSV importers + fixtures for DE/AT/FR/CA/US (`modules/bank-*`). Mustang Factur-X / XRechnung wrapper in `modules/einvoice-de`.

### Audit-chain — PR pending merge

`feature/audit-chain` (PR #823) on the datahike fork. Implements:

- Per-commit `:merkle-roots` map (primary indexes + secondary indexes via shared `IAuditable` protocol).
- `create-commit-id` in audit-grade mode hashes merkle-roots + schema-meta + max-tx + max-eid + meta (including `:datahike/parents`) → cid is a true merkle root over the chain.
- `audit/verify-chain` BFS-walks parents and recomputes each cid; `:deep? true` mode re-derives index roots from storage (PSS via konserve content-addressing, proximum via `verify-from-cold`, scriptum via `verify-commit`).
- Upstream scriptum fix (`feature/concurrent-branch-writers`, merged) for per-branch write locks — was the blocker for scriptum-with-crypto-hash to be audit-grade.
- 12 audit-verify tests pass. PR is "minimal" per author preference.

**What audit-chain unlocks for compliance work (the reason it matters for this handoff):**

- **DE GoBD** Unveränderbarkeit — defensible technical claim of tamper-evidence for the books and any attached documents.
- **CRA T-661 / SOX-adjacent retention** — chain-of-custody for CA filings.
- **Verifiable handover** — when the author files via paper/CRA My Account/printed return, the underlying kernel state can be proven not to have been edited post-hoc.
- **Beleg lift** later — documents hashed into the chain inherit tamper-evidence without bespoke work.

Audit-chain is **orthogonal** to compliance work below: every deliverable here is a function over kernel state, and the chain just attests that state is what we say it is. Once PR #823 merges + scriptum is released, we bump `deps.edn` in pg-datahike and the chain is live.

### Outstanding non-accounting items (not blockers)

- `#5` substring(... FOR n) SQL parse gap (pg-datahike) — orthogonal.
- `#33` audit-chain investigation — closing once PR merges.
- `#39` historical schema for as-of queries (datahike upstream) — separate concern.

---

## The plan: depth-first DE + CA

Five jurisdictions in breadth, zero in depth. Going deep on two creates a template (entity model + integration shape + test harness) we can replicate to AT/FR/US later. Author also has personal CA-tax skin in the game, which keeps the work honest.

The pattern for both: **model → wire format → filing path → archive → audit**.

### DE deliverables

Priority 1 — **UStVA actually filable via ELSTER:**

- ELSTER ERiC integration (ELSTER Rich Client) or the alternative Java/REST path. Choice point: ERiC is a native binary distribution from Bayerisches Landesamt für Steuern; the REST API (ELSTER online) is the lighter alternative. Likely path: REST first, ERiC if forced by certificate requirements.
- Test certificate workflow (ELSTER provides test certs) before production. Production cert: `Organisationszertifikat` via Finanzamt.
- UStVA XML schema mapping from existing `ustva.clj` report model → ELSTER `Anmeldungssteuern` XML.
- E-Mail confirmation handling + Transferprotokoll archive (the receipt the FA returns).

Priority 2 — **GoBD-konforme Verfahrensdokumentation generator:**

- DE legally requires a *Verfahrensdokumentation*: a written description of how the bookkeeping system works (data capture, storage, retention, controls). Most SMBs hand-write this once and forget; we should *generate* it from the kernel — schema attributes, invariant rules, audit-chain proof, retention policy.
- Output: a single PDF/Markdown bundled with kernel version, schema hash, and chain head cid. Becomes a per-tenant archive.
- This is also marketing: nobody else's accounting system does this automatically.

Priority 3 — **IDEA / GoBD-Export (Z3 audit file):**

- The Finanzamt asks for a `Datenträgerüberlassung` (DTÜ) on audit. Formats: Z1 (read-only access), Z2 (FA-side analysis), Z3 (data export). Z3 is what we generate.
- IDEA-format CSV + `index.xml` + `gdpdu-01-09-2004.dtd` field descriptions.
- Source data: ledger postings, account master, transactions, invoices — already in the kernel. Mapping is mostly mechanical.

Priority 4 (optional this round) — **DSFinV-K** (cash register / POS export). Only relevant if a tenant operates a cash register; deferrable.

Open per-deliverable choices DE:
- ELSTER REST vs. ERiC (native binary)?
- Verfahrensdokumentation: PDF (TeX/Pandoc) vs. Markdown-only first?
- Z3 export: separate `compliance-de` module or extend `l10n-de`?

### CA deliverables

Priority 1 (personal motivation) — **T1 General + the schedules the author actually needs:**

- T1 General form (federal personal income tax).
- Schedules typically required for the author's profile (resident, employment income, possible investment income, possible foreign income): S1 (federal tax), S3 (capital gains), S4 (statement of investment income), T776 if rental, T2125 if self-employed, T1135 if foreign property >$100k CAD.
- Provincial form (the author's province — confirm at pickup; likely ON or BC based on context, but author to confirm).
- Foreign-income handling — non-trivial if there's DE-side income; treaty article 14 etc.

Filing path choice:
- **A. Print + paper file:** simplest, no certification needed. CRA accepts paper for any year.
- **B. NetFile via CRA-certified software:** requires CRA certification of our software (annual process, costs effort but no money). Restricted to current + 4 prior tax years.
- **C. Generate filable .tax file (UFile/StudioTax/Wealthsimple Tax format)** and the author re-uploads. Pragmatic stopgap.
- **D. Print, then upload PDF via "Submit documents" in CRA My Account.** Some forms accept this; T1 itself typically doesn't.

Recommended path A or C for the immediate filing; B as a longer-term ambition.

Priority 2 — **GST/HST NetFile / TELEFILE:**

- If the author is a registered GST/HST filer (likely, given self-employment), GST34-2 return is filed quarterly or annually.
- NetFile for GST/HST is simpler than T1 NetFile (no software certification required — just the access code from CRA).
- Output: GST/HST line-by-line totals + remittance. Source: kernel `tax/repartition` postings (already structured per ADR).

Priority 3 — **Provincial sales tax filings:**

- ON/BC/AB: HST or GST-only, covered above.
- QC: QST separate (Revenu Québec, not CRA) — different format/system.
- BC PST, SK PST, MB RST: separate provincial filings if registered. Defer unless author is registered.

Priority 4 — **Historical reconstruction (the lost-Wealthsimple-Tax data):**

- Author lost access to Wealthsimple Tax. Need to reconstruct prior-year filings — at minimum to roll-forward losses, RRSP room, capital cost allowance, capital-loss carryforwards.
- CRA `My Account` exposes the *assessed* version of prior returns (Notice of Assessment + return-summary). These can be ingested as authoritative ground truth — even without the original Wealthsimple file.
- Pragmatic flow: pull NoAs from CRA My Account → ingest as opening balances + carryforward facts → file current year against that.

Open per-deliverable choices CA:
- Province? (affects forms + sales-tax + Quebec carve-out)
- Self-employed or T4 employee or both? (drives T2125 vs T4 ingestion)
- Years to reconstruct (just current, or N years back)?
- NetFile certification ambition: yes / no / later.

---

## Out of scope this iteration

Explicit deferrals — call these out at pickup if priorities shift, but assume they're NOT being worked on:

- AT / FR / US compliance hardening (will follow the DE/CA template).
- Multi-currency + FX (large; would touch everything we've built).
- Fixed assets + depreciation (next after compliance, but not here).
- Beleg lift (waits for audit-chain merge anyway; then becomes natural).
- Inventory (FIFO/LIFO/weighted-avg).
- Payroll / HR — jurisdictional minefield; do per-jurisdiction later.
- Treasury / cash management.
- Cost accounting beyond what's in `analytic-account` schema.

---

## Pointers

### Code

- Kernel: `src/datahike_accounting/` — schema, posting, balance, ledger, trial, period, state-machine, sealing, validation.
- DE module: `modules/l10n-de/src/datahike_accounting/l10n_de/` — `bs.clj`, `eur.clj`, `pnl.clj`, `invoice.clj`, `closing.clj`, `datev.clj`, `ustva.clj`, `chart.clj`. **`ustva.clj` is the input to the ELSTER work.**
- CA module: `modules/l10n-ca/src/datahike_accounting/l10n_ca/` — `chart.clj`, `returns.clj`. **`returns.clj` is the input to the GST/HST work; T1 is greenfield.**
- Bank importers: `modules/bank-{de,at,fr,ca,us}/` — used to source the postings that feed reports.
- E-invoice: `modules/einvoice-de/` — Mustang wrapper for ZUGFeRD/XRechnung; relevant when DE invoice obligations mature.

### Decisions / context

- `doc/decisions.md` — locked ADRs (001-013+). Read first.
- `doc/architecture.md` — layer cake.
- `doc/roadmap.md` — phased plan; the present handoff supersedes Phase-X items relating to DE/CA wire formats.
- `doc/research/03-us-canada-coverage.md` — CA coverage analysis from earlier research.

### External

- **ELSTER:** developer.elster.de (REST), `eric` SDK (native).
- **CRA:** canada.ca/en/revenue-agency, NetFile certification at canada.ca/en/revenue-agency/services/e-services/digital-services-businesses/netfile.html, T1 General form package.
- **GoBD / IDEA:** Bundesfinanzministerium BMF letter 28.11.2019, IDEA Z3 description.

### Audit chain (datahike side)

- Branch: `feature/audit-chain` on the datahike fork (`../datahike`).
- PR: #823 against datahike (pending merge).
- Scriptum prerequisite: `feature/concurrent-branch-writers` (merged; awaiting release tag).
- After release: bump `deps.edn` in pg-datahike to the published scriptum version, drop the local 0.1.25 reference.

---

## First moves on pickup

A short checklist to drop straight into work:

1. **Confirm CA province + filer profile** (employed / self-employed / investment) so the T1 schedule list is real.
2. **Pick ELSTER path** (REST first, fall back to ERiC if blocked by cert constraints).
3. **Pull prior-year CRA assessments** via My Account → ingest as opening-balance + carryforward fixtures for the kernel.
4. **Scaffold `modules/compliance-de`** for ELSTER + IDEA + Verfahrensdokumentation (or extend `l10n-de`; decide based on whether "compliance" feels like its own concern).
5. **Scaffold `modules/compliance-ca`** for T1 + GST/HST.
6. **Set up a real test fixture per jurisdiction:** for DE, a small SKR04 sole-prop year with VAT; for CA, the author's actual current-year data (the personal motivation case).
7. **Verify audit-chain in pg-datahike** once scriptum releases + PR #823 merges — should be a deps bump and a smoke test.
