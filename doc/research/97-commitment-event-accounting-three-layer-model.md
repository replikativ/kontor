---
date: 2026-05-20
title: 97 — Commitment-and-event accounting — the three-layer model and kontor's options
status: synthesis / design-discussion (positions marked "proposed")
audience: maintainer reading McComb & Dunn in real time; deciding kontor's long-game direction
---

# 97 — Commitment-and-event accounting: the three-layer model and kontor's options

## §0 — Why this note

The maintainer is reading McComb & Dunn's *The Future of Accounting*
(2025) and asked two things: (a) make the few financial business
events easy to transact — order / pay / receive / sell / deliver /
receive-payment — and (b) contextualize kontor with the
accounting-theory canon, which the project's research notes have not
done (they surveyed ERP vendors and McComb, not the theory).

Note 80 mapped McComb-vs-kontor. Note 88 shipped the cheap seams
(ADR-090/091/092). This note is the round-2 synthesis from three
parallel research agents — formal/algebraic accounting, ValueFlows /
hREA as a running reference, and the accounting-theory landscape —
plus the maintainer's own OiCOS-era Logseq notes (`Accounting.org`,
`Oicos.org`). The six-camp landscape map is split into a companion
reference, **note 98**.

The maintainer set the framing, preserved here un-mixed: **being
"event-driven" is not the win — having a compositional algebra of
pieces of information is.** This note is organized around exactly
that, as three layers kept deliberately separate.

## §1 — Three layers, kept un-mixed

| Layer | Question it answers | Bodies of work |
|---|---|---|
| **A — vocabulary** | *Which* primitives model business activity | REA (McCarthy), McComb/Dunn, ValueFlows |
| **B — structure** | *What algebraic object* a balance / transaction is | Ellerman, Cruz Rambaud, Mattessich, Ijiri |
| **C — composition** | *How* the pieces compose (across accounts, periods, entities, up into models) | Nester, Katis–Sabadini–Walters, REA↔Petri, Ehresmann |

They **stack**; they are not rivals. Layer A is the input vocabulary;
Layer B is the structure a classified event must land in; Layer C is
how many events / periods / entities fold together. McComb's book
contributes Layer A, gestures at B ("a simple classification table")
and C ("always closed"), and formalizes neither. The maintainer's own
decade of OiCOS work *is* B and C.

## §2 — Layer B: the algebra (the load-bearing finding)

The headline, robust across every formal source surveyed:

> **The double-entry ledger is the free abelian group** — Ellerman's
> *Pacioli group* `P(ℕ) ≅ ℤ`, the Grothendieck "group of differences"
> of the debit/credit monoid. **Balances are equivalence classes;
> "trial balance = 0" is membership in the kernel of the sum
> homomorphism; reports, consolidation and closing are quotient maps
> (module epimorphisms).**

Concretely, from **Cruz Rambaud, García Pérez, Nehmer & Robinson,
*Algebraic Models for Accounting Systems* (2010)** — the maintainer's
Zotero copy, a proof-based monograph:

- States of an `n`-account system = the free `R`-module `Rⁿ`.
- The **balance module** `Balₙ(R) = Ker(σ)` where `σ(v) = Σvᵢ` — a
  free `R`-module of rank `n−1`.
- **Transactions ≅ the balance module** (Thm 3.1.1) — states and
  state-changes live in *one* algebra.
- **Report generation = a quotient-system epimorphism `σ_E`**:
  partition the accounts, sum within each class (Ch. 5). Period
  close = a *composite* of quotients. First isomorphism theorem:
  every homomorphism factors as epi ∘ mono.

And from **Ellerman** ("On Double-Entry Bookkeeping: The Mathematical
Treatment", arXiv:1407.1898): the T-account is an ordered pair of
*unsigned* numbers; `P(ℕ) ≅ ℤ` in two ways (debit- and
credit-isomorphism); a transaction is a "zero-term"; posting is
"add a zero-term"; trial-balance is "zero + zero = zero". The
**SSS/DSU distinction**: store **S**ingle-**S**ided **S**igned
numbers internally, present **D**ouble-**S**ided **U**nsigned
debit/credit at the boundary.

**What this means for kontor — kontor is already a Layer-B system:**

- kontor's `sum-to-zero per (entity, ledger, commodity)` invariant
  (`src/kontor/posting.clj`) **is** membership in `Ker(σ)`. It is not
  an ad-hoc rule; it is the *defining algebraic property* of the
  balance module.
- kontor stores signed `:posting/amount` — Ellerman's SSS. There are
  no debit/credit columns. The "double" of double-entry is the
  two-sidedness of the T-account, not "two accounts touched".
- **Typed, not vectorified.** Ellerman's multidimensional accounting
  is `P(ℕⁿ) ≅ ℤⁿ` — *vector* accounting. The maintainer's OiCOS note
  records Viktor's correction of exactly this: *"sollte typed sein
  nicht vectorifiziert … Eine Geldtheorie muss typed sein"* — money
  is typed because of multiple currencies. kontor's
  **per-commodity** sum-to-zero is `P(ℕⁿ)` with the commodity as a
  *disjoint type index* — you cannot add EUR to USD; the ledger is a
  product of groups indexed by commodity-type. **kontor already
  realized the 2020 typed-not-vectorified correction.** The open
  question (§7) is whether `entity`, `ledger`, and McComb's
  "categories" should be equally typed, first-class dimensions.

Ijiri (momentum / force / his original 1980s triple-entry) and
Mattessich (matrix accounting, the double-effect axiom) are valuable
but *not* the algebra: Ijiri gives a complementary *derived view*
(income is the time-integral of momentum), Mattessich gives the
*axiom* every event must have a balanced double effect. Neither
supplies the homomorphism; the group theory does. Detail in note 98.

## §3 — Layer C: composition

- **Cruz Rambaud Ch. 5** already gives the compositional story:
  every report / consolidation / period-close is a **quotient
  epimorphism `σ_E`** for a partition `E` of the accounts. Closing
  temporary accounts is `𝒜 → 𝒜/E₁ → (𝒜/E₁)/E₂`.
- **Chad Nester, "Situated Transition Systems" (arXiv:2105.04355)**
  — the strongest modern result and the closest to kontor's goal:
  the double-entry ledger is a **compact closed category** whose
  group-of-objects is Ellerman's Pacioli group; transactions are
  *morphisms*; the trial-balance cancellation is the *counit* `ε`;
  **"derive the ledger from a transition system" is a functor**.
  Builds on Katis–Sabadini–Walters, "On Partita Doppia" (1998).
  Frames accounting as the measurement of a distributed *concurrent*
  system.
- **REA ↔ Petri nets**: economic events = transitions, resources =
  places/tokens, REA duality = input/output places. An *integer
  Petri net* has a free-abelian-group marking — the *same object*
  as the ledger — so an event→ledger map is automatically a
  homomorphism.
- kontor's `kontor.process` step-lists already **fold** transactions
  (which Cruz Rambaud proves form a module). kontor's bitemporal
  axis is the answer to Ehresmann's point in the OiCOS notes —
  *"time is emergent, the future is not compositional"*
  (`t:t+2 ≠ t:t+1 ; t+1:t+2`, a realization step sits between).
  **kontor is already a Layer-C substrate** — it just hasn't *named*
  its report engine as a family of quotient epimorphisms.

## §4 — Layer A: the vocabulary, and the homomorphism that links A→B

- McComb's six events are **three dualities** — order⇄sell,
  receive⇄deliver, pay⇄receive-payment — the same flow seen from two
  agents. ValueFlows confirms it: one `EconomicEvent` row carries
  `provider` + `receiver`; "sale + AR" vs "purchase + AP" is a
  *read-time projection parameterized by viewpoint*. (This is also
  how McComb's "no intercompany postings" discard works mechanically
  — one row, two views, nothing to eliminate.)
- ValueFlows' best design idea: the **`action` carries an effect
  table** — `accountingEffect` / `onhandEffect` / `accountableEffect`
  ∈ {increment, decrement, decrementIncrement, …}. You transact a
  *verb*; the table produces the balance change. A two-axis split
  (ownership vs physical custody) handles consignment / in-transit /
  FOB cleanly.
- **That effect table is the homomorphism `θ : Event → Balₙ(R)`.**
  McComb's "simple classification table for your industry" = `θ` in
  data form. The rigorous content of "machine-executable accounting
  policy" is: **`θ` is well-formed iff every event maps into
  `Ker(σ)`** (i.e. `θ(event)` sums to zero) **and `θ` respects
  composition.** Then the derived double-entry ledger is correct *by
  construction* — a sum of balance vectors is a balance vector
  (closure of a submodule). "Derive the traditional view" is
  literally `σ_E ∘ θ`.
- **Commitment = the planned tense of the same verb.** ValueFlows'
  `Commitment` and `EconomicEvent` share one property shape, linked
  by a *quantified* `Fulfillment` edge. AR/AP = open commitments
  (committed qty − fulfilled qty, bucketed by due-date). An "order"
  is an `Agreement` bundling two reciprocal commitments — no Order
  entity. Shyam Sunder's *Theory of Accounting and Control* gives
  the deepest "why": the firm is a nexus of contracts; a commitment
  is an executory contract; a posting is its settled residue (note
  98, Camp 1).

## §5 — Where kontor stands, per layer

- **Layer B — ✅ already there.** Typed Pacioli group via
  sum-to-zero-per-commodity; SSS storage. kontor *is* a realization
  of `Balₙ(R)`.
- **Layer C — ✅ substrate present.** `kontor.process` folds;
  bitemporal axis. Not yet *named* as quotient epimorphisms — the
  report engine could be reframed that way.
- **Layer A — ❌ missing.** No event/verb vocabulary, no commitment
  entity, no effect table. Consumers (beleg) hard-code the
  event→posting map inside transactors.

**The entire McComb gap is Layer A.** The hard, formal layers — B
and C — kontor already has, by good FP design. This is the
single most important takeaway: kontor is not behind McComb; it has
the parts McComb's book never formalizes and lacks only the
vocabulary layer his book *is*.

## §6 — McComb's keepers / discards, reconciled

**Keepers** (accounting standards, income statement, balance sheet,
cash-flow statement, accrual + cash basis, accounting policy,
counterparty + resource accounts, inventory valuation methods) — all
compatible with kontor as-is; nothing to change.

**Discards**, each against kontor + the algebra:

| McComb discards | Reconciliation |
|---|---|
| debits/credits as organizing principle | Discard the *notation*, keep the *group*. Ellerman SSS. kontor ✅ |
| ledgers, journal entries | In the algebra these are just the list-of-zero-terms; kontor's `:journal` is already a tag, not a book. ◐ |
| account numbers, chart of accounts | A *basis* of the free group. "Discard" = don't privilege one basis; any partition gives a valid report via `σ_E`. Design option §7.4 |
| reporting taxonomies | McComb aspirational; XBRL-GL + audit demand them. The algebra says a taxonomy *is* a quotient `σ_E` — keep, as derived |
| adjusting entries, manual accruals/deferrals | Machine-derived from commitments (the homomorphism, when the commitment carries the service period). kontor `:schedule` partial |
| closing process | = evaluating the fold; a composite of `σ_E`. "Always closed" = `θ` evaluated continuously. Honest version: *no human batch work* |
| manual classification / manual entry by bookkeepers | `θ` is the classification; `*-tx-data` builders are programmatic. kontor ✅ / ◐ |

kontor has already discarded or half-discarded ~6 of the ~11 — by
accident of good FP design. The genuinely open ones: chart-of-
accounts foundationalism, closing-as-process, taxonomies.

## §7 — Design options (proposed, for discussion)

1. **Event/verb layer as a companion** (`kontor-event`, or folded
   into the commitment companion). A small `action` enum + effect
   table — `θ` in data. The user transacts `receive` / `pay` /
   `sell`; the table emits the posting set. Kernel unchanged.
2. **Commitment substrate** — the *planned tense* of the verb;
   unify the already-scattered commitment-shaped entities (`:promise`,
   `:schedule`, the lease liability, procurement `:order`). AR/AP =
   open commitments. Note 80 §7.2, now sharpened.
3. **Make `θ` law-checked.** The validation gate asserts
   `θ(event) ∈ Ker(σ)` — every event produces a balanced posting
   set. Tiny code (the sum-to-zero check already exists), large
   payoff: the McComb model becomes *provable, not ad-hoc*. The
   non-homomorphic residue is exactly §8's judgment entries.
4. **Free the basis.** Demote `:account` from *the* axis to *a*
   dimension; let the report engine aggregate over any partition
   (`σ_E` for any `E`). kontor has `:account-tag` + analytic
   dimensions already; the move is a basis-agnostic report engine.
5. **Momentum / force derived view (Ijiri)** — someday. With the
   bitemporal axis this is a pure derived view (difference balances
   over period boundaries). Flag, don't build.
6. **Reframe the docs** (`value.md`, `programming.md`) around what
   kontor *is*: a typed, bitemporal realization of the balance
   module `Balₙ(R)`, with `kontor.process` folds and an optional
   event vocabulary. Note 80 §7.3, now precisely grounded.

## §8 — Two kinds of invariant: the epistemic status of the algebra

The algebra of §2 is real and worth having — but it is essential not
to over-claim for it. Accounting carries (at least) two kinds of
invariant, with sharply different epistemic status, and the design
must keep them apart.

**The closed (analytic) invariant.** Zero-sum / `Ker(σ)` / the
Pacioli group. It is true *by construction* — like "a T-account has
two sides" or "a sentence has a subject." It is **grammar, not
physics.** This cuts both ways against the temptation (Winschel's, in
the OiCOS years; McComb's "always closed" in different clothes) to
treat it as a conservation law: it is *more* certain than any
physical law — a conservation law could be empirically false, this
one cannot, because we *defined* the two entries as two sides of one
recording act — and it says *far less* — it constrains the *books*,
not the *world*. Calling it "a law of physics" is a category mistake
in both directions: it over-dignifies a tautology and under-dignifies
an analytic certainty. The kernel enforces it absolutely (the
validation gate); it is "free" in that it guarantees *form*, never
*truth*.

**The open (synthetic) invariants — standing bets.** "This asset is
worth its carrying amount." "This receivable is collectible." "The
entity is a going concern." These are empirical, contingent,
defeasible — and, because a firm is a far-from-equilibrium system
living on entropy gradients, *structurally unstable*: a stable
valuation would be the equilibrium of death. They are never
"enforced" and never "closed"; they are *asserted, dated, attributed,
and revised*. kontor's mechanisms for them are exactly the non-kernel
ones: bitemporality (the revision trail), `:audit-doc` + attestation
(governed human judgment with provenance), and the provider protocols
(pluggable, explicitly-external models of the unknown dynamics —
Fx / valuation / depreciation).

**The reconciliation.** The algebra is valuable *because* it is
epistemically modest: it claims only the form, so it is never wrong;
it is the one thing you can hold fixed *regardless of how the
dynamics resolve* — the still point you reason *from*, not a
description of the motion. A revaluation is *algebraically clean*
(it still sums to zero — debit the asset, credit a reserve) yet
*epistemically loaded* (the number came from outside the algebra).
The algebra catches the form; it cannot and must not pretend to catch
the content.

**Design payoff — a classification principle.** Every accounting
fact sorts by which invariant it touches, and that says which
mechanism owns it: closed → gate-enforced; open → bitemporal trail +
audit-doc + provider, never silent state. Proposed kernel principle:
*the kernel enforces the analytic invariant; the synthetic ones enter
only as dated, governed, provider-sourced events — never as silent
state.* This also locates McComb's "always closed" precisely: trivial
of the closed invariants, permanently false of the open ones (they
are forever provisional). "Always closed" can only honestly mean
"no human *batch* work."

**Bitemporality is the structural honesty here.** "State = integral
of events" *is* the Pacioli group (the ledger is the fold of
zero-terms — discrete FTC). What the OiCOS framing adds — events as
deltas, state as the integral, dual by the fundamental theorem — is
true and elegant, but the hard part is that *the integrand is unknown
and revisable*. That is exactly what the second time axis represents:
bitemporality is an FTC where you keep editing the integrand, and the
tx-time axis is the edit history. A purely event-sourced, one-axis
model (McComb's) has *no representation of "we changed our mind about
the past"*; kontor's two-axis substrate does. The maintainer's
critique — "the algebra does not catch devaluations / backdating" —
therefore lands as a kontor *strength*: kontor never asks the algebra
to; it routes revision through valid-time + tx-time instead.

**Lineage** (note 98 expands): Georgescu-Roegen, *The Entropy Law and
the Economic Process* (1971) — the economy is entropic and
irreversible, the equilibrium framing is wrong; the accounting
measurement debate (Chambers' CoCoA, Sterling, Edwards & Bell,
Ijiri's "hardness") — the field's century-long admission that
valuation is the unstable part; Mattessich's onion model — a stable
purpose-neutral core + mutable instrumental shells, already this
statics/dynamics layering; Sunder — accounting as evolving social
convention, not science; reflexivity — the Lucas critique, MacKenzie's
*An Engine, Not a Camera* (the model shapes the thing it measures);
the dissipative-structures / autopoiesis frame (Baecker / Luhmann in
the maintainer's own notes — accounting as "der endogene apollinische
Vers innerhalb des exogenen dionysischen Reigens").

### §8.1 — Where the algebra stops — instances of the synthetic residue

- **The homomorphism covers trade, not judgment.** Impairment, fair
  value, litigation provisions have *non-event* inputs; `θ` cannot
  produce them. They are non-homomorphic adjustments. The algebra is
  *useful* here precisely because it **localizes** the human: the
  accountant is needed exactly at the entries `θ` does not produce.
  kontor's `:audit-doc` + attestation + approval-policy substrate is
  their governance home. A McComb system that calls impairment "just
  an event" is hiding the judgment, not eliminating it.
- **Forty years of non-adoption.** REA/commitment-first has not
  displaced double-entry — not because it is wrong, but because GAAP
  recognition, XBRL-GL, and audit practice are ledger-shaped. Even
  REA's own proponents concede the system must *emit* double-entry.
  kontor's sealed posting ledger is therefore the *mandatory*
  audit-facing projection, not legacy baggage.
- **McComb's book is Layer A.** Vocabulary + prose. Crediting it
  with formal rigor it does not claim would be a mistake; crediting
  it with a useful vocabulary and a north star is right.

## §9 — Recommendation + open questions

**Proposed recommendation.** kontor is a Layer-B/C system missing
Layer A. The future-proof move is *not* a rewrite. It is: (a) **name
what kontor already is** — the typed `Balₙ(R)` realization — in the
docs and one ADR; (b) add Layer A as a **companion** (options 1+2),
kernel untouched; (c) make `θ` **law-checked** (option 3 — cheap,
high-leverage); (d) free the basis incrementally (option 4). This
keeps ADR-001 (single dep), keeps compliance coverage, and earns the
McComb "lean event system" as a *derivation*, with the traditional
view as `σ_E ∘ θ`.

**Open questions for the maintainer:**

1. Is the OiCOS "SAP für Nationalstaaten" horizon a kontor goal, or
   does it live one layer up (a consumer such as simmis), with
   kontor staying the disciplined accounting-kernel slice?
2. Build the event/commitment layer *in kontor* as a companion, or
   keep it a separate artifact that depends on kontor?
3. How far to take the categorical framing — is Nester's
   compact-closed-category functor a *design tool* (we build the
   event layer as a category of transition systems) or just a
   *correctness theorem we cite*?
4. Does McComb's book give *any* Layer-B/C formal structure the
   agents could not see, or is it — as the evidence says — vocabulary
   and prose throughout?

## §10 — Sources

Internal: notes 80 (McComb-vs-kontor), 88 (substrate seams round 1),
98 (the six-camp canon map); the maintainer's Logseq `Accounting.org`
+ `Oicos.org` (the OiCOS circle — Winschel / Baecker / Boyan — and
the typed-not-vectorified correction). `src/kontor/posting.clj`
(sum-to-zero = `Ker(σ)`), `src/kontor/process.clj` (the fold).

External — Layer B: Ellerman, "On Double-Entry Bookkeeping: The
Mathematical Treatment" (arXiv:1407.1898); Cruz Rambaud et al.,
*Algebraic Models for Accounting Systems* (World Scientific, 2010,
Zotero); Nehmer & Robinson (1997). Layer C: Nester, "Situated
Transition Systems" (arXiv:2105.04355); Katis–Sabadini–Walters,
"On Partita Doppia" (1998); the REA↔Petri-net literature. Layer A:
McComb & Dunn, *The Future of Accounting* (2025, maintainer's
paperback); ValueFlows (valueflo.ws) + hREA; McCarthy REA (1982).
Three full research-agent reports (formal-algebra, ValueFlows-hREA,
theory-canon) archived from the 2026-05-20 round.

§8 (two kinds of invariant): Georgescu-Roegen, *The Entropy Law and
the Economic Process* (1971); Chambers, *Accounting, Evaluation and
Economic Behavior* (1966, CoCoA); Sterling, *Theory of the
Measurement of Enterprise Income* (1970); MacKenzie, *An Engine, Not
a Camera* (2006); Mattessich's onion model + Sunder (note 98). The
distinction synthesizes a 2026-05-20 maintainer discussion — the
algebra as "fixing statics under unknown dynamics," not a law of
physics.
