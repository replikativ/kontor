# Research notes — index

These reports were produced during the design phase of `kontor`. They are point-in-time inputs to [decisions.md](../decisions.md) and [architecture.md](../architecture.md), not living documentation. If a fact in one of them later changes, **fix decisions.md first** and either annotate the original report or supersede it with a new one.

| # | Topic | Date | Source agent | Verified? |
|---|---|---|---|---|
| 01 | [Odoo LGPL reuse + open-source accounting landscape](01-odoo-reuse-and-landscape.md) | 2026-05-09 | general-purpose | partial (FSF interpretation, Tryton license) |
| 02 | [Datahike versioning + cryptographic hashing primitives](02-datahike-versioning-and-hashing.md) | 2026-05-09 | general-purpose | high (file:line references into datahike source) |
| 03 | [US + Canada coverage requirements](03-us-canada-coverage.md) | 2026-05-09 | general-purpose | medium (one US bill citation was wrong — flagged in decisions.md) |
| 04 | [`datopia/invariant` library fit](04-invariant-library-fit.md) | 2026-05-09 | Explore | high (file:line refs into invariant source) |
| 05 | [XTDB monetary + bitemporal patterns](05-xtdb-accounting-patterns.md) | 2026-05-09 | Explore | high (XTDB source + tests examined) |
| 06 | [openclaw exploratory work — inventory + extraction plan](06-openclaw-extraction-inventory.md) | 2026-05-09 | general-purpose | high (concrete file paths + LOC counts) |
| 07 | [Period semantics across SAP / Odoo / NetSuite / QuickBooks / Xero / Sage](07-period-semantics-comparison.md) | 2026-05-10 | general-purpose | high (Odoo source + vendor docs) |
| 08 | [Bitemporality in accounting — evidence review](08-bitemporality-evidence.md) | 2026-05-10 | general-purpose | medium (sparse production evidence either side) |

## What each report answers

**01 — Reuse and landscape.** What can we lift from Odoo (LGPLv3) without translating Python? Where does Tryton (GPLv3) fit? What chart-of-accounts data is publicly usable (DATEV SKR03/SKR04 sourcing options)? What's the plain-text-accounting kernel pattern (Beancount/ledger/hledger)? What JVM e-invoicing libraries solve the Factur-X/XRechnung/Peppol problem (Mustang APL2, phax/ph-ubl APL2)? Concludes: *write a small PTA-style kernel ourselves; lift CoA facts from Tryton/GnuCash/Odoo into per-country license-tagged data modules; wrap Mustang for German e-invoicing; defer UI and Peppol AP*.

**02 — Datahike versioning and hashing.** What does `datahike` actually expose for branching, commits, content addressing, history? Concrete API: `datahike.versioning` namespace, `branch!`/`branch-as-db`/`commit-as-db`/`branch-history`/`merge-db`, all stable. Two hashes: weak per-tx `:hash` (`clojure.core/hash` summed → 32-bit, must be replaced) and strong commit-id (SHA-512-derived UUID-5 over commit metadata). With `:crypto-hash? true` the EAVT/AEVT/AVET index nodes become a real Merkle tree. Gaps for accounting: cryptographic per-commit content hash, commit signatures, sealed/append-only branches. ~1-2 weeks of upstream work to close. Concludes: *audit story is potentially better than Odoo's per-row hash chain (Merkle DAG vs linear chain), but the leaf hash gap MUST be closed first*.

**03 — US and Canada coverage.** Canada is "Germany-hard" with a different shape (federal GST + 4 HST provinces + 3 PST/RST + Quebec QST). The US is the cliff — not the kernel itself but sales tax (~11,000 jurisdictions, no federal authority, ~24 SST states with free CSV data + 22 non-SST including all the big revenue states). Concludes: *recommended sequence DE → CA → US; design a `tax-provider` protocol from day 1 so SST CSV / static / Avalara-API impls all plug in; wrap don't compete on US sales tax*.

**04 — `datopia/invariant` fit.** What the lib does (declarative datalog invariants per attribute, throw-on-failure). Cost model (per-affected-attr query × four DB snapshots; sub-millisecond for typical postings). What it expresses well (sum-to-zero, account-active, commodity-match) vs not well (sealing, state machines, behavior constraints). Library is pinned to datahike 0.6.x and at risk of bitrot — we maintain it. Concludes: *hybrid — adopt invariant for state predicates, hand-roll behavior in middleware; bump to 0.8.x as a Phase-1 prerequisite*.

**05 — XTDB monetary + bitemporal patterns.** XTDB treats `BigDecimal` as a first-class type (no separate Currency type — model as parallel column). Bitemporal SQL ergonomics gain ~70% query boilerplate but cost ecosystem alignment. **XTDB's audit story is weaker than datahike's** (with our planned Track-B hardening) — no commit DAG introspection, no signature hook. Concludes: *stay datahike for Phase 1 (ADR-008 confirmed); consider a read-only SQL bitemporal-shim as a future ergonomic improvement*.

**06 — openclaw extraction inventory.** The user's prior exploratory work in `openclaw/` contains real, reusable artifacts: `beleg/bank.clj` (609 LOC, 11 German bank CSV formats + parser + auto-categorizer), `beleg/tax.clj` (660 LOC: UStVA / EÜR / DATEV EXTF exporter / SKR04 mapping), 16 anonymized bank-CSV fixtures, and German tax legislation specs in markdown. Extraction plan: bank.clj → Phase-4 bank-importer module; tax.clj → Phase-2 l10n-de; CSV fixtures → bank-importer test resources; the rest (agent runtime, transcripts, scratch) stays in `openclaw/`. **Flagged: kernel currently lacks analytic accounts (cost-center / profit-center) — beleg has them. Add to roadmap.**

**07 — period semantics comparison.** Concrete walk through Odoo source + SAP/NetSuite/QuickBooks/Xero/Sage docs. Verdict: our current single-lock-state model is the QuickBooks-class minimum and not enough for accountants. **Mandatory before Phase 2-DE**: (a) soft vs hard lock distinction (`:period/locked-at` vs `:period/sealed-at`), (b) special-period flag for DE period 13-16 year-end adjustments, (c) pre-close validation hook (no drafts, no unreconciled, trial-balance-zero). Defer per-user lock exceptions and fiscal-year entity. Drives ADR-014.

**08 — bitemporality evidence.** Sparse production evidence either side. Vendor marketing (XTDB, Datomic) is loud; SMB products (QBO/Xero/NetSuite) carry no bitemporal model and use reverse-and-repost in current open period. Nubank's Cavalcanti talk distinguishes tx-time from business post-date but uses append-only compensating entries, not full valid-time queries. Verdict: **keep but quietly demote** — drop `:posting/valid-to` and `:posting/temporal-key` tuple, keep `:posting/valid-from` (cheap, useful for backdated invoices). Make `:as-of-valid` opt-in; rename `:as-of-tx` to `:as-of-snapshot`. Forward-compatible if a regulated-fintech use case appears later. Drives ADR-008 update.

## What's NOT in these reports

- **Odoo's accounting schema** (the actual data model we discovered by running `--init=account` against pg-datahike) is captured separately in [../odoo-schema-reference.md](../odoo-schema-reference.md) — a study of the live system, not a web search.
- **Beleg's existing data model** is in `../../beleg/src/org/replikativ/beleg/schema.clj` — read it directly, it's small.

## How to update

If new research is needed, add a new numbered file under this directory and add a row to the table above. If a previous report becomes wrong, either annotate inline at the top with `> **Superseded N-NN-NN by report XX:** …` or replace and bump the date column.
