# 76 — Review-after: ADR-071/072/073

Independent code-review audit per the ADR-037 review-after rhythm.
Reviewer worked from the ADRs + implementation files cold; the design
conversations were not visible. Findings cite file:line. Date: 2026-05-17.

## TL;DR

- **ADR-071 (tax abstraction, design-only)**: 0 P0, 2 P1, 3 P2. The
  three-protocol split is sound; the seams are clean. A small
  underspecification around `TaxPostingBuilder` ↔ FX is the biggest
  open question.
- **ADR-072 (FxRateProvider + kontor.fx)**: 0 hard-stop P0, 3 P1, 4 P2.
  The implementation is tidy and the composite-tuple gotcha is
  correctly handled. **One serious correctness trap** sits in inverse
  staleness after a manual override (P1 with a P0 framing argument).
  `convert`'s default `:precision 2` silently corrupts JPY conversions
  unless the caller knows to pass `:precision 0`.
- **ADR-073 (consolidation primitive)**: **3 P0**, 4 P1, 3 P2. Two of
  the P0s are repeat-run silent corruption (`consolidate!` is not
  idempotent — eliminations cascade on the second run, translations
  pile up as extra drafts). The third P0 is a bitemporal-axis miss:
  consolidation transactions are committed with **no `:db.valid/from`**,
  invisibly stripping them from valid-time queries that visit the
  group entity. The unit tests don't see this because they (a) run
  `consolidate!` once and (b) trial-balance through the as-of-tx side,
  not the as-of-valid side.

**Single most important finding**: the consolidation primitive is
silently non-idempotent at multiple layers, and its outputs are
invisible to valid-time queries. The substrate ships green tests but
the next consumer story (period-close re-runs, valid-time
restatement) will hit this immediately. Fix before any companion
work builds on it.

## ADR-071 — Tax abstraction (design-only audit)

ADR-071 (`doc/decisions.md:7628-7668`) supersedes ADR-005 and proposes
a three-protocol-plus-data-shape design: `TaxRateProvider` →
`TaxFacts` → `TaxPostingBuilder`. The relevant existing code is the
soon-to-be-superseded `src/kontor/tax_provider.clj` (147 LoC, mostly
stubs and scaffolds — the `StaticTableProvider.resolve-taxes` literally
returns `[]` at `tax_provider.clj:78-82`, confirming the ADR's claim
that the existing abstraction was unused).

### P0 findings

No findings. The design is design-only and the protocol split is
internally consistent; no implementation has been written yet, so
P0s would have to live entirely in the design. None rise to that bar.

### P1 findings

**P1-71-1. `TaxPostingBuilder` ↔ FX seam is not specified.**
The ADR specifies that `TaxFacts` carries `:line-base + commodity` and
`:jurisdiction-specific-codes`, and that `TaxPostingBuilder` materializes
GL postings. What it does NOT specify is whether the materialization
step is expected to perform any currency translation when the tax is
calculated in jurisdiction commodity X but posted to a GL whose
account base is in commodity Y. The DE case is degenerate (everything
is EUR); the cases the ADR is supposedly designing for (US sales tax
with origin/destination states, BR DIFAL, IN component-split) all have
real currency questions when the entity's functional commodity differs
from the tax-authority currency. ADR-071's `:jurisdiction` slot
captures the *authority* but not the *commodity*; ADR-072 has the
FxRateProvider but it's never referenced from ADR-071.
**Action**: ADR-071 should explicitly say either (a) `TaxFacts.per-component`
carries `:component/commodity` independently from `:line-base.commodity`
or (b) the `TaxPostingBuilder` is allowed to take an `FxRateProvider`
dependency for the cross-commodity case. Today it's ambiguous; the
DE/CA/AU pilots will have undefined behavior the moment they hit
multi-currency.

**P1-71-2. Reverse-charge asymmetry is documented but the contract
boundary is not enforceable.** ADR-071's `:component/kind :reverse-charge`
clause (`decisions.md:7644`) says "Seller-side: reporting-tag-only,
no postings beyond AR/revenue. Buyer-side: two postings (input-VAT
receivable + output-VAT payable)." The protocol docstring is supposed
to "enforce via per-`:tax-use` dispatch." But no protocol-level
schema validates that the `TaxRateProvider` returns the right shape
for the wrong `:tax-use`. A misconfigured provider that returns
buyer-side reverse-charge facts in a seller-side context will silently
materialize two extra postings on the wrong side. **Action**: spec the
`TaxFacts` schema with a precondition map and have `kontor.tax-pipeline`
assert it before handing off; otherwise the per-l10n posting-builder
test discipline (`decisions.md:7654`) is the only defense, and that
defense lives in 11+ modules.

### P2 findings

**P2-71-1. Effective-dated rates ambiguity.**
ADR-071 (`decisions.md:7652`) says "Schema partially supports
`:tax/effective-from`/`-until` (`schema.clj:2809-2818`); follow-up:
confirm + complete the schema attrs if gaps." This is a real
deferred-decision; the audit notes it as a P2 followup explicitly to
make sure it doesn't slip through.

**P2-71-2. `:jurisdiction-specific-codes` opacity in tests.**
The ADR plans per-country golden-fixture tests for the posting builders
but doesn't say anything about contract tests that the opaque slot
round-trips cleanly through `TaxFacts → posting → audit-readout`.
Without a contract test, country modules can drift on what they put
in the slot, and the audit story loses fidelity. Add a contract test
to the kernel that round-trips an arbitrary EDN map through the slot.

**P2-71-3. The migration plan undersells US.**
"+1500 LoC" for US (`decisions.md:7660`) is, charitably, the static
nexus side only; Avalara/TaxJar adapters in production typically run
4-6 kLoC including retry/idempotency/caching/test-fixtures. Worth
re-scoping before promising customer commits.

## ADR-072 — FxRateProvider + kontor.fx

Implementation is in `src/kontor/fx_rate_provider.clj` (442 LoC),
`src/kontor/fx.clj` (182 LoC), and `src/kontor/schema.clj` lines
113-173 (`fx-rate-attrs`). 28 tests in `test/kontor/fx_test.clj`,
5 integration tests in `test/kontor/fx_wiring_test.clj`.

### P0 findings

No hard-stop P0s. The composite-tuple gotcha is correctly handled at
`fx_rate_provider.clj:127-140` — I REPL-verified the broken naive
form returns the wrong answer on a discriminating dataset (asked
EUR→USD on jan-2, got 0.92M back which is the USD→EUR rate, demonstrating
that datahike's tuple-positional-binding really does rebind per slot)
and the `?tuple :in`-form returns 1.08M correctly.

### P1 findings

**P1-72-1. Manual override creates silent inverse staleness.**
File: `fx_rate_provider.clj:341-375` (ingest) and `:289-294` (save-rates).
When `ingest-ecb-csv-rows!` runs, it persists BOTH directions:
EUR→USD as 1.08 and USD→EUR as 1/1.08 = 0.925925925926 (12-digit).
If a customer later corrects EUR→USD via `save-rates!` to 1.085,
only the forward direction is upserted. The inverse remains stale at
0.925925... instead of being updated to 1/1.085 = 0.921658985200.
A consumer asking USD→EUR for the same date silently sees an
inconsistent quote. Verified in REPL:

```
{:forward 1.085M, :inverse 0.925925925926M}   ; should both reflect 1.085
```

**Why I'd push toward calling this P0**: this is *silent* data corruption
in an audit-sensitive substrate. The ADR explicitly markets
"audit chain survives the upsert via bitemporal" (`decisions.md:7694`),
which is true for the SAME composite-tuple slot but does not catch
the inverse-slot drift. **Disagreement-framed P0**: maintainer may
reasonably call this a P1 followup (the customer who manually overrides
ECB data can be expected to also override the inverse, or to use
`:allow-inverse? true` and not store inverses at all). Either decide
the policy and ship one path (don't persist inverses; always derive
on lookup), or document the inverse-staleness sharp edge in the ADR
and the `save-rates!` docstring.

**P1-72-2. `kontor.fx/convert` default precision ignores the commodity.**
File: `fx.clj:68-71`. The namespace docstring at `fx.clj:14-17` claims
"Rounds to the target commodity's precision via :half-even unless told
otherwise." The actual implementation defaults `:precision 2`
regardless of `:commodity/precision` on the target. Verified in REPL:
converting 100 EUR to JPY at rate 162.45 returns `16245.00M JPY`
(scale 2). The JPY commodity has `:commodity/precision 0`. The
`convert-jpy-precision-zero` test at `fx_test.clj:294-303` works
around this by passing `:precision 0` explicitly. Action: either
update the default to look up `:commodity/precision` (preferred — it's
already in the schema) or fix the docstring to match the
implementation.

**P1-72-3. Zero-rate handling is inconsistent across providers.**
`ChainedProvider` (`fx_rate_provider.clj:417-432`) explicitly rejects
`0M` rates ("first non-nil **non-zero** wins"), but `StaticTableProvider`
happily returns `0M` if that's what was stored. Verified in REPL: a
zero rate sample produces a zero `convert` result silently, but only
when the StaticTable serves it directly; behind a chain, the zero is
treated as a missing rate. This will cause confusing behavior when a
customer pre-seeds a zero (perhaps as a sentinel for "rate suspended")
and gets different outcomes depending on whether their provider is
direct vs chained. Pick a single policy at the protocol level: either
treat 0M as "no rate" everywhere or treat it as "rate is literally zero"
everywhere. Document in the protocol docstring.

### P2 findings

**P2-72-1. `query-last-on-or-before` does its own sort in Clojure
when datalog could.** File: `fx_rate_provider.clj:146-160`. The query
returns all hits ≤ cutoff and then sorts them by date descending in
Clojure. For a customer with years of historical rates and many
commodities this becomes a hot path; the sort is O(n log n) on every
fallback lookup. Two cleaner options: (a) use `(max ?date)` aggregation
in the datalog itself, or (b) add a secondary index on `:fx-rate/at-date`
and use a min-max-by-date sub-query. Followup, not blocking.

**P2-72-2. Scaffold providers throw raw `ExceptionInfo` instead of
returning nil.** Files: `fx_rate_provider.clj:387-411`. `XeProvider`,
`OandaProvider`, `FedH10Provider` all throw on `resolve-rate`. In a
ChainedProvider context this propagates instead of being caught,
breaking the "first non-nil wins" pattern. The same `ChainedProvider`
loop at `:421-426` doesn't try/catch. If a customer accidentally
chains a scaffold ahead of a real provider, the chain explodes. Either
return nil from scaffolds, or wrap chain's `(resolve-rate p q)` in
try/catch and skip throwing providers. The ADR explicitly says
scaffolds "throw on `resolve-rate` with a hint" which suggests the
intent is to fail loudly; that's defensible, but the ChainedProvider
documentation should note the asymmetry.

**P2-72-3. `:via` is consumed by StaticTable but is undocumented in
the protocol.** File: `fx_rate_provider.clj:55-96`. The protocol
docstring lists `:from-commodity :to-commodity :at-date :rate-type`
but says triangulation is "the provider's call" without naming the
`:via` opt. `make-static-table-provider` does document `:default-via`
in its options, and `StaticTableProvider.resolve-rate` consumes a
per-call `:via`, but a consumer reading the protocol docstring will
miss this. Add `:via` to the protocol-level documented option list,
even if "may be ignored by impls".

**P2-72-4. EcbReferenceRatesProvider constructs a fresh
StaticTableProvider per call.** File: `fx_rate_provider.clj:322-331`.
`(resolve-rate [_ q] (resolve-rate (make-static-table-provider ...) q))`
allocates a record on every lookup. Cheap, but for high-frequency
report rendering it adds up. Cache the wrapped provider in the
record's `opts` at construction (or just promote the wrapping to
`make-ecb-reference-rates-provider`).

## ADR-073 — Consolidation primitive

Implementation in `src/kontor/consolidation.clj` (458 LoC), schema in
`src/kontor/schema.clj:3130-3161`, tests in
`test/kontor/consolidation_test.clj` (5 tests, 17 assertions).

### P0 findings

**P0-73-1. `consolidate!` is not idempotent — double-run cascades the
elimination tx.** File: `consolidation.clj:259-281` and `:283-331`.

`eliminate-intercompany-pair-tx-data` stamps the new elimination tx
itself with `:transaction/intercompany-pair-id pair-id` (line 327).
On a second `consolidate!` run, `find-pair-postings` (`:259-281`) runs
the query `[?t :transaction/intercompany-pair-id ?pid]` which now
matches BOTH the original source txs AND the prior elimination tx.
The new elimination tx contains the negations of all those postings,
including its own predecessor's negations — so it doubles in size.

REPL-verified: first `consolidate!` produces an elimination tx with 4
postings. Second run produces one with **8 postings** (4 new + 4
re-negating the prior elimination). Third would produce 16. The bug
is silent because the new elim entries still sum to zero per
(entity, commodity) — negating a balanced tx is balanced. Sum-to-zero
catches arithmetic errors, not duplication.

**Fix options** (pick one):
- Have `find-pair-postings` filter by `[(missing? $ ?t :transaction/consolidation-kind)]`
  so prior elimination/translation txs are skipped.
- Do NOT stamp the elimination tx with `:transaction/intercompany-pair-id`
  (move the pair-id to e.g. `:transaction/eliminates-pair-id` on
  elimination txs).
- Track elimination idempotence with a separate
  `:transaction/elimination-of-pair-id` attr that asserts uniqueness
  per pair-id+date+elimination-entity.

This is the highest-severity finding in the whole audit. The unit
tests pass because they run `consolidate!` exactly once. A consumer
who runs it nightly (the obvious use case) accumulates exponentially
growing elimination txs.

**P0-73-2. Consolidation txs commit with no `:db.valid/from` /
`:db.valid/to`.** File: `consolidation.clj:441-458` (`consolidate!`) and
`:118-253` (`translate-trial-balance-tx-data`) and `:283-331`
(`eliminate-intercompany-pair-tx-data`).

The pure tx-data builders do NOT call `kontor.bitemporal/with-vt`, and
`consolidate!` calls `(process/run-process conn {:steps steps})`
without passing `:vt-from`/`:vt-to`. Compare to `kontor.posting`'s
`build-transaction` (`posting.clj:371-373`) which applies
`(kbt/with-vt … (:transaction/effective-date transaction) kbt/forever)`.

REPL-verified: after `consolidate!`, the consolidation tx datoms
include `:transaction/journal :transaction/effective-date
:transaction/consolidation-kind :transaction/state :transaction/narration`
and several `:posting/*` datoms, but NO `:db.valid/from` on the
tx-meta. Compare to the test's own `post-transaction!` calls (eid
536870928 in the trace) which include
`536870928 :db.valid/from #inst "2026-01-02"`.

**Consequence**: any subsequent `(d/valid-at db t)` query on the
group entity returns ZERO consolidation postings, regardless of `t`.
The default `kontor.balance/account-balance` calls `(d/as-of db tx)`
not `(d/valid-at db t)`, which is why the existing tests still pass
(they use `as-of-tx` semantics through the trial-balance default).
But any consumer that mixes valid-time queries on operating-entity
postings with valid-time queries on consolidation postings will see a
broken consolidated trial balance.

This isn't theoretical — ADR-073 explicitly markets bitemporal
correctness alignment with ADR-008. The fix is one line:
`(process/run-process conn {:steps steps :vt-from at-date :vt-to kbt/forever})`
in `consolidate!` line 458. The tx-data builders should ALSO be
audited for consumers who call them directly via `d/transact`.

**P0-73-3. `consolidate-tx-data` is not idempotent across translation
either.** File: `consolidation.clj:401-420`.

`(trial/trial-balance conn {:entity e})` defaults to
`:include-states #{:posted}`, so source-entity drafts are correctly
ignored. The consolidation translation entries themselves land as
`:draft` (line 247: `:transaction/state :draft`), so re-running
`consolidate!` will NOT include them in the next read of the
operating-entity trial balance.

However: every call to `consolidate!` creates a NEW translation tx on
the consolidation-entity, even if a prior one already exists for the
same date. The group entity accumulates draft translation txs.
If the user reviews-and-posts these drafts (as the ADR suggests they
should), they DOUBLE-COUNT.

Less severe than P0-73-1 because the user gets an "is this right?"
moment when they see N translation drafts piling up — but a nightly
re-consolidation job would silently spawn drafts indefinitely until
someone notices. Fix: the composer should detect existing translation
txs matching `(source-entity, presentation-commodity, at-date)` and
either skip or supersede them (with bitemporal `:db.valid/to` close
on the prior version).

### P1 findings

**P1-73-1. IAS 21 rate-type matrix lumps non-monetary assets as
monetary.** File: `consolidation.clj:67-88` (`default-rate-type-by-account-type`).

The ADR (`decisions.md:7738`) and the docstring both explicitly
acknowledge this: "lumps all assets as monetary by default. Real
IAS 21 distinguishes monetary assets ... from non-monetary
(inventory at cost, PP&E, prepaid expenses)." Customers must ship
per-account overrides for non-monetary holdings.

This is technically the ADR's documented limit, but I'm calling it
P1 not P2 because: (a) PP&E and inventory at cost are the materially
larger BS items for most manufacturers; (b) shipping a default that
gives wrong results on the most common non-trivial case is a footgun
that any new consumer will trip over; (c) the schema has nothing to
mark an account as monetary/non-monetary, so customers can't even
ship a structured override — they need a per-account-eid map of
booleans, which doesn't scale and is invisible to documentation
tools. **Action**: add `:account/monetary?` (default `true` for
asset/liability) to schema; let `pick-rate-type` consult it; document
the migration for customers who already shipped account hierarchies.

**P1-73-2. Re-running `consolidate!` doesn't supersede prior runs
even bitemporally.** File: `consolidation.clj:441-458`. Tied to
P0-73-2 and P0-73-3 but worth calling out separately: even if you
fix `vt-from` plumbing, you also need `vt-to` on the PRIOR run's
translations to be set when a new run lands, so that
`d/valid-at db now` returns the latest set of translations rather
than the union of all prior runs. This is the standard
"correction of a sample" pattern that datahike's `feature/bitemporal-v1`
supports but only when the writer cooperates. The fix is to query for
existing translation txs at the same `(source-entity, at-date)` and
emit retraction-style updates with `:db.valid/to (now-1)`. Not trivial.

**P1-73-3. `find-pair-postings` doesn't filter by `:transaction/state`.**
File: `consolidation.clj:259-281`. The query picks up postings from
DRAFT source txs in addition to POSTED ones. Per the ADR-007 sealing
story, drafts can still be edited. A consumer who has draft IC
adjustments in flight will see them silently eliminated. Filter by
`:transaction/state :posted` (and document the choice in the
docstring).

**P1-73-4. CTA-plug calculation doesn't carry rate-type provenance.**
File: `consolidation.clj:194-238`. The CTA plug is the sum of
translated amounts negated, but the substrate emits ONE plug posting
with no audit trail to WHY this much: which accounts contributed at
which rate-types. For a real auditor reading the books, the plug is
opaque. Two options: (a) emit one plug per rate-type so the
decomposition is in the postings themselves; (b) carry a
structured `:transaction/cta-provenance` map. The kernel's
`:posting/narration` is the wrong place for structured data.

### P2 findings

**P2-73-1. `consolidate-tx-data`'s entity-filter is naive.** File:
`consolidation.clj:392-400`. `(:entity/kind ... or :operating)` means
an entity with no `:entity/kind` attr defaults to operating. That's
defensible, but combined with "skip consolidation + elimination",
the operating filter relies on the synthetic entities being properly
tagged. A misconfigured family (consolidation-entity left as
:operating) silently includes itself in the translation loop, which
will cascade duplicate postings. Add a precondition: throw if
`consolidation-entity` resolves to `:operating` or has no
`:entity/kind`.

**P2-73-2. `translate-trial-balance-tx-data` doesn't accept
:vt-from / :vt-to.** File: `consolidation.clj:118-253`. Per
ADR-068 every business-write tx-data builder should be composable
with bitemporal stamping by the caller. The composer/orchestrator
fix in P0-73-2 will paper over this, but a direct consumer of the
pure builder still has to remember to add `with-vt`. Document this
limitation explicitly or make it the builder's responsibility.

**P2-73-3. Test coverage gaps.** File: `test/kontor/consolidation_test.clj`.
- No test exercises >2 entities (only acme-de + acme-us).
- No test for >2-leg pair-id (the ADR markets "N-way pairs (rare but
  possible — three-leg intercompany loans)" at `decisions.md:7750`).
- No test for CTA non-zero (both test entities happen to round to
  exact cancellation; the `:translate-usd-entity-emits-eur-postings-without-cta-on-balanced-tb`
  test name even broadcasts that no CTA appears).
- No test that re-running `consolidate!` produces consistent results.
- No test of `:rate-type-by-account` override.
- No bitemporal-as-of test (read consolidated trial balance at
  multiple as-of points).

Add at minimum: 4-entity scenario, 3-leg pair, CTA-non-zero case
where translation difference creates a real plug, and an idempotence
test.

## Cross-cutting findings

**CC-1. Provider-protocol shape consistency across ADRs.** ADR-071's
`TaxRateProvider`, ADR-072's `FxRateProvider`, ADR-005-superseded's
`TaxProvider`, and the existing `EinvoiceProvider`, `CostingProvider`,
`DepreciationProvider` all share the "provider-id + resolve-X + maybe
ChainedProvider" pattern. **Good**: ADR-072 explicitly cites this
("Identical *shape* to TaxRateProvider"). **Bad**: the `provider-id`
return type is a keyword in `fx-rate-provider`, but other providers
in the codebase use varying conventions. Worth a meta-ADR or a
one-page "kontor provider conventions" appendix codifying:
provider-id keyword, nil-on-no-opinion semantics, ChainedProvider
behavior (skip nil and zero), scaffold throw-policy.

**CC-2. Bitemporal-axis consistency.** Now that `:db.valid/from` is the
canonical write attr (per the bitemporal port plan in research note
68), every transactor in the codebase should either set it explicitly
or document why it doesn't. P0-73-2 is one violation; an audit pass
across `kontor.consolidation`, `kontor.posting`, `kontor.process`,
the asset/lease module retranslation paths, and the soon-to-land
`kontor.tax-pipeline` would surface any others. Recommend a
test-fixture: "for every commit produced by a kontor.* transactor,
the tx-meta carries `:db.valid/from`."

**CC-3. Single-dep posture holding.** Both ADR-072 and ADR-073 add no
new deps; only `kontor.fx`/`kontor.fx-rate-provider`/`kontor.consolidation`/
extended schema. Confirmed by reading `deps.edn` (unchanged) — good.
ADR-071's `kontor.tax-pipeline` will need to maintain this when it
lands.

**CC-4. ECB attribution string is a public var but not wired
anywhere.** File: `fx_rate_provider.clj:317-320`. `ecb-attribution`
is `def`ined and exported but no consumer in `kontor.report`,
`kontor.fx`, etc. references it. The "consumer's responsibility"
posture in the ADR (`decisions.md:7698`) is fine in principle but
silently sets the customer up to forget the ECB attribution
requirement. A `compute-report` opt `:include-fx-provenance? true`
that includes per-line provider-id + attribution in the result map
would help, and is the kind of thing that catches reviewers' eyes
in actual auditor reviews. Followup.

**CC-5. The intercompany-pair-id pattern needs a kernel-level
convention.** File: `schema.clj:3131-3145`. Once ADR-073 lands, every
consumer module (invoice, procurement, lease, payment-application
per ADR-073 Implication 2) can stamp `:transaction/intercompany-pair-id`.
Without a convention for HOW the ID is generated (UUID? counter? tenant
prefix?), modules will diverge and cross-module IC reconciliation
becomes a manual-mapping nightmare. Add a kernel-level
`kontor.intercompany/next-pair-id!` or document the convention.

## Recommended next moves

Priority-ordered, lowest-numbered first:

1. **Fix P0-73-1**: `consolidate!` idempotence. The
   `find-pair-postings` query must exclude prior elimination/
   translation transactions. One-line query addition + one assertion
   test.

2. **Fix P0-73-2**: thread `:vt-from at-date :vt-to kbt/forever`
   through `consolidate!` → `run-process`. Update tests to use
   `(d/valid-at db at-date)` rather than only `(d/as-of db ...)`.

3. **Fix P0-73-3 + P1-73-2**: design supersession for repeat
   `consolidate!` runs. Either query-and-skip on
   `(source-entity, presentation-commodity, at-date)` or
   query-and-close-prior with bitemporal `:db.valid/to`. Pick one
   per ADR-008.

4. **Fix P1-72-1**: pick a single inverse-storage policy.
   Recommendation: do NOT persist inverses in `ingest-ecb-csv-rows!`.
   Derive on lookup via `:allow-inverse? true`. Eliminates the
   stale-inverse class of bugs entirely. Doc that change in the ADR.

5. **Fix P1-72-2**: have `kontor.fx/convert` consult
   `:commodity/precision` when no `:precision` opt is passed.
   The JPY test workaround at `fx_test.clj:294-303` becomes
   unnecessary and the namespace docstring becomes truthful.

6. **Address CC-2**: add a property-based test "every kontor.*
   transactor sets :db.valid/from on its commits" — would have caught
   P0-73-2 mechanically.

7. **Address P1-71-1**: clarify ADR-071 on the `TaxPostingBuilder` ↔
   FX seam before any l10n posting-builder refactor starts.

8. **Address P1-73-1**: schema-level `:account/monetary?` for IAS 21
   non-monetary correctness. Migration plan for existing charts.

9. **Address P1-73-3 + P1-73-4**: state filter on
   `find-pair-postings`; CTA provenance.

10. **P2 cleanup** (consolidate into one follow-up commit): provider
    convention appendix; `:via` in protocol docstring; scaffold
    throw vs nil policy; test coverage gaps for N>2, 3-leg pairs,
    non-zero CTA, idempotence.

## Sources

- `/home/christian-weilbach/Development/kontor/doc/decisions.md` lines
  7628-7668 (ADR-071), 7672-7714 (ADR-072), 7718-7766 (ADR-073).
- `/home/christian-weilbach/Development/kontor/src/kontor/tax_provider.clj`
  (about to be superseded).
- `/home/christian-weilbach/Development/kontor/src/kontor/fx_rate_provider.clj`
  (ADR-072 impl).
- `/home/christian-weilbach/Development/kontor/src/kontor/fx.clj` (ADR-072
  Money-side).
- `/home/christian-weilbach/Development/kontor/src/kontor/consolidation.clj`
  (ADR-073 impl).
- `/home/christian-weilbach/Development/kontor/src/kontor/schema.clj`
  lines 88-173 (`fx-rate-attrs`), 3060-3161 (entity + intercompany-pair).
- `/home/christian-weilbach/Development/kontor/src/kontor/bitemporal.clj`
  (`with-vt`, `forever`, `strip-tx-meta`).
- `/home/christian-weilbach/Development/kontor/src/kontor/posting.clj`
  lines 355-395 (canonical post-transaction with-vt wiring; the
  reference implementation that ADR-073 should match).
- `/home/christian-weilbach/Development/kontor/src/kontor/process.clj`
  lines 100-138 (`run-process`, vt-from / vt-to handling).
- `/home/christian-weilbach/Development/kontor/src/kontor/balance.clj`
  lines 100-146 (default include-states `#{:posted}`; as-of-tx wiring).
- `/home/christian-weilbach/Development/kontor/src/kontor/trial.clj`
  (trial-balance default state filter).
- `/home/christian-weilbach/Development/kontor/src/kontor/money.clj`
  lines 60-211 (Money constructors, arithmetic, rounding).
- `/home/christian-weilbach/Development/kontor/src/kontor/entity.clj`
  lines 85-122 (family, by-kind, operating?).
- `/home/christian-weilbach/Development/kontor/src/kontor/report.clj`
  lines 280-342 (`compute-report :translate-to` wiring).
- `/home/christian-weilbach/Development/kontor/modules/lease/src/kontor/lease/posting.clj`
  lines 172-237 (`plan-fx-retranslation` provider mode).
- `/home/christian-weilbach/Development/kontor/test/kontor/fx_test.clj`
  (28 tests / 44 assertions).
- `/home/christian-weilbach/Development/kontor/test/kontor/fx_wiring_test.clj`
  (5 tests).
- `/home/christian-weilbach/Development/kontor/test/kontor/consolidation_test.clj`
  (5 tests / 17 assertions).
- REPL validation against the running nREPL session on `localhost:42099`,
  confirming: composite-tuple naive-form bug (returned 0.92M instead
  of 1.08M on a discriminating dataset), `consolidate!` non-idempotence
  on second run (elimination tx grew from 4 to 8 postings), absence
  of `:db.valid/from` on consolidation tx-meta, JPY-precision default
  drift, manual-override inverse staleness, zero-rate inconsistency
  across providers.
