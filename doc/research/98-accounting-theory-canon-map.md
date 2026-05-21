---
date: 2026-05-20
title: 98 — The accounting-theory canon — a six-camp map for kontor
status: reference (related-work contextualization)
audience: maintainer + anyone wanting to place kontor / McComb in the field
---

# 98 — The accounting-theory canon: a six-camp map for kontor

Companion reference to note 97. The kontor research notes had
surveyed ERP vendors (Odoo / SAP / Tryton) and McComb in depth but
never contextualized the project against the accounting-theory canon.
This note fixes that: six camps, kept deliberately **un-mixed**, each
with its canonical thinkers, must-read work, core thesis, and
relevance to a commitment-and-event accounting kernel. REA (McCarthy
1982) is the shared reference point all six are positioned against —
covered in note 80, not repeated as a camp.

The formal/algebraic camp is the spine of note 97's Layer B; it is
treated here only briefly because note 97 §2 develops it. The other
five camps are the genuinely new contextualization.

## Camp 1 — Accounting as contracts / information economics

The camp that supplies the *theoretical justification* for modeling
commitments at all.

- **Shyam Sunder**, *Theory of Accounting and Control* (South-Western,
  1997; full text free, CC BY-NC-ND, from his Yale page). Thesis: the
  firm is a **nexus of contracts** among self-interested agents
  (shareholders, creditors, employees, managers, suppliers,
  customers, government); accounting is the system that **administers
  the contract set** — measuring each party's contribution and
  inducement, distributing the common knowledge that sustains the
  equilibrium, enforcing terms. Financial / managerial / tax / audit
  / governmental accounting are five facets of one contract-
  enforcement function.
  *Relevance:* the deepest "why" for commitments. A McComb/REA
  commitment **is** Sunder's executory contract before settlement; a
  posting is its settled residue. The primitive ordering is
  contract/commitment ⟶ event ⟶ posting.

- **Joel Demski & Gerald Feltham** — the information-economics
  program (*Cost Determination*, 1976; Feltham, *Information
  Evaluation*, 1972). Thesis: accounting is not measurement of a
  pre-existing truth; it is the **design and evaluation of an
  information system**, valued only by its effect on decisions.
  Demski's "impossibility of a standard": no accounting rule is
  universally optimal.
  *Relevance:* the academic license that **no chart of accounts or
  report is canonical** — every report is one projection for one
  decision context. Exactly the architectural permission to treat
  the chart as a derived report-axis.

## Camp 2 — Management-accounting reform movements

Three distinct movements; shared enemy (the traditional cost/budget
apparatus), different remedies. Kept separate.

- **Beyond Budgeting** — Hope & Fraser, *Beyond Budgeting* (HBS Press,
  2003); Bjarte Bogsnes, *Implementing Beyond Budgeting* (Wiley,
  2009/2016). Abolish the annual fixed budget (a "performance trap"
  that bundles target-setting, forecasting and resource allocation
  into one gamed number); replace with relative targets, **rolling
  forecasts**, on-demand resource allocation, devolved authority.
  *Relevance:* wants planning artifacts continuously revised, not
  annually frozen — a bitemporal event-stream substrate's strength.
  The camp that most wants the budget itself modeled as revisable
  data.

- **Theory of Constraints / Throughput Accounting** — Goldratt;
  Thomas Corbett, *Throughput Accounting* (North River, 1998). Reject
  overhead allocation; use three measures — Throughput, Inventory/
  Investment, Operating Expense. A deliberately *different cost
  algebra*.
  *Relevance:* a worked proof that the **same event stream can be
  classified under a radically different algebra**. The strongest
  argument for kontor's `CostingProvider`-style pluggability — the
  cost model is a provider, not a chart fact.

- **Activity-Based Costing & Time-Driven ABC** — Kaplan & Cooper,
  *Cost & Effect* (1998); Kaplan & Anderson, *Time-Driven ABC*
  (2007). Trace cost through activities; TDABC drives cost by *time
  equations* over actual transactions and surfaces unused capacity.
  *Relevance:* TDABC is the costing model *designed to run off a
  transaction event stream* — argues that analytic dimensions on
  kontor events should be rich enough to feed a time-equation cost
  model.

## Camp 3 — Digital financial reporting / structured data

- **Charlie Hoffman** — "the father of XBRL"; *Digital Financial
  Reporting*, *The Seattle Method* (free PDFs at xbrlsite.com); plus
  **XBRL GL** (Global Ledger). Thesis: financial reports should be
  structured, machine-readable, logically *verifiable* data, not
  formatted documents. Hoffman engages REA directly on his blog —
  argues the data layer should be resources/events/agents and that
  "debits, credits, ledgers, journals are not necessary" at the data
  layer. XBRL GL is the standards-world embodiment: a transaction-
  level representation that flows up to the ledger and then to
  external-reporting taxonomies.
  *Relevance:* kontor's output + identity story. A sympathetic-but-
  pragmatic ally: agrees the data layer should be event-shaped, but
  insists the *reporting projection* must be a logically verifiable
  model with stable concept identifiers — validating the
  `:concept-iri` seam (note 78, ADR-090). XBRL GL is "what an
  event-level ledger looks like when standardized for interchange."

## Camp 4 — The programmer / plain-text accounting tradition

- **John Wiegley** (Ledger, 2003), **Martin Blais** (Beancount),
  **Simon Michael** (hledger; plaintextaccounting.org). A tradition
  in software + docs, not books. Thesis: bookkeeping is plain text in
  version control, processed by deterministic CLI tools; everything
  is a transaction in a file; a transaction is a dated set of
  postings that **sum to zero**; accounts are a `:`-delimited path
  created by use; reports are pure **queries** over the journal —
  no mutable database, no period close.
  *Relevance:* the closest **practitioner-validated** cousin of
  kontor's design, and a dissent kontor should respect — it keeps the
  zero-sum invariant *and* the account-as-path. kontor already
  mirrors it (`:account/path`, sum-to-zero per ledger/commodity,
  `:balance-assertion/*`, `:lot/*` cost basis, append-only journal,
  closing-as-report). Treat as the reference oracle for ergonomics.

## Camp 5 — Critical / historical perspectives on double-entry

- **Rob Bryer** (Marxist accounting history — DEB as the calculative
  technology of capitalist accountability, a reliable rate of return
  on pooled capital); **Paolo Quattrone** (DEB as a *rhetorical and
  ordering technology* — a generative grid that *produces* order and
  the appearance of completeness, not a transparent report);
  **James Aho**, *Confession and Bookkeeping* (DEB's two-sided form
  as a medieval rhetoric of moral justification).
  *Relevance — one paragraph:* double-entry persists for reasons that
  have nothing to do with arithmetic. The two-sided form carries
  *social trust, legitimacy, and a rhetoric of completeness* ("the
  books balance, therefore nothing is hidden"). Retiring debits/
  credits (McComb's proposal) is not merely swapping a data
  structure — it discards a 500-year-old legitimation device, and any
  replacement must consciously re-supply the audit-trust function.
  kontor's sealing + commit-hash chain + bitemporal audit trail is
  that conscious re-supply.

## Camp 6 — Triple-entry: the cryptographic lineage

(Distinct from Ijiri's 1980s momentum/force "triple-entry" — note 97
§2 / note 98's formal thread.)

- **Ian Grigg**, "Triple Entry Accounting" (2005) + "The Ricardian
  Contract" (2004); **Todd Boyle**. Thesis: a transaction's
  authoritative record is a **digitally-signed receipt** shared
  between the parties — the signed receipt *is* the third entry; the
  parties' journal entries become local cross-checked copies. The
  Ricardian Contract is a human- and machine-readable signed
  commitment artifact.
  *Relevance:* arrives at "the event/receipt is primary, ledger
  entries are derived" from cryptography rather than data modeling.
  The Ricardian Contract is a *commitment artifact* — a signed
  promise that precedes settlement. kontor's content-addressed
  hash-linked commit graph (ADR-003) already has the cryptographic
  primitive; what it lacks is externally-signed shared receipts.
  *Limit:* Grigg himself concedes the signed receipt secures
  *inter-party* agreement, not *intra-firm* classification — it is
  not a re-architecture of internal bookkeeping.

## The formal/algebraic camp (spine of note 97 — pointer only)

Ellerman (the Pacioli group), Cruz Rambaud et al. (the balance-module
/ homomorphism / quotient theory), Mattessich (matrix accounting +
the double-effect axiom), Ijiri (momentum/force — a *derived view*,
not an algebra), Nester + Katis–Sabadini–Walters (double-entry as a
compact closed category), the REA↔Petri-net correspondence. Developed
in **note 97 §2–§3**. This is the camp that actually answers "what
algebraic structure underlies accounting": the **free abelian
group**.

## Synthesis — who argues for event/commitment accounting, and where they part

**Most directly for an event/commitment rebuild:** McCarthy (REA,
1982 — the earliest and clearest) → McComb & Dunn (2025 — adds
*commitments* as a named primitive) → Hoffman (data layer should be
REA-shaped). Backstopped theoretically by Sunder (the firm *is* its
contracts) and Demski/Feltham (no report is canonical).

**They AGREE on:** the append-only event stream is ground truth;
balances / trial balance / statements / AR / AP are *derived
projections*; debits/credits are presentation, not substance.

**They DISAGREE on:** (1) **keep or drop the chart of accounts** —
REA/McComb say derivative, plain-text + Sunder treat the account axis
as load-bearing; (2) **what the primitive is** — event (REA) vs
commitment (McComb) vs contract (Sunder) vs signed receipt (Grigg);
(3) **retire or demote double-entry** — McComb leans retire, REA
demotes to a derived view, plain-text *keeps* the zero-sum rule,
triple-entry *preserves* intra-firm double-entry.

**Bottom line for kontor.** The chart-of-accounts foundational-vs-
derivative question is a genuinely **unsettled debate across the
whole field** — not a kontor lag. kontor's "accounts foundational"
stance is a defensible position in a live debate. The dissent kontor
should respect comes from the plain-text tradition and Camp 5: the
zero-sum invariant and the account axis are load-bearing — for
correctness and for audit-legitimacy respectively — and should be
*kept* even in an event-first design. That is already kontor's
posture (note 97 §5).

## Sources

Full URLs / ISBNs in the 2026-05-20 theory-canon research-agent
report (archived). Key: Sunder, *Theory of Accounting and Control*
(faculty.som.yale.edu/shyamsunder); Hope & Fraser, *Beyond
Budgeting* (ISBN 1-57851-866-0); Corbett, *Throughput Accounting*
(ISBN 0-88427-158-7); Kaplan & Anderson, *Time-Driven ABC* (ISBN
1-4221-0171-1); Hoffman, *The Seattle Method* (xbrlsite.com);
plaintextaccounting.org; Aho, *Confession and Bookkeeping* (SUNY,
2005); Grigg, "Triple Entry Accounting" (iang.org/papers).
