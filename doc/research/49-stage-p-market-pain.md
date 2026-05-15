# 49 — Stage P market-pain / integration-shape review (ADR-067 + ADR-068)

**Date:** 2026-05-15
**Scope:** ADR-067 (`kontor.process`) + ADR-068 (universal `*-tx-data`
builders). Sister to the parallel ADR-068 code-review note.
**Method:** read of `src/kontor/process.clj`, the migrated
orchestrators (`modules/lease/src/kontor/lease/runner.clj`,
`modules/asset/src/kontor/asset/runner.clj`,
`modules/lease/src/kontor/lease/modification.clj`,
`modules/inventory/src/kontor/inventory/ops.clj`,
`src/kontor/payment_application.clj`,
`src/kontor/closing.clj`), three representative builders
(`src/kontor/legal_hold.clj`,
`modules/collections/src/kontor/collections/case.clj`,
`src/kontor/audit_doc.clj`), the authz client, `composition_test.clj`,
`process_test.clj`, then a `grep`-driven census of every
`d/transact` site in `src/` and `modules/*/src/`.

## Bottom line

**Shippable as-is for v1 cross-module consumers — with three
caveats.** The headline composition shape (`run-process` over
`*-tx-data` builders) works cleanly for the three scenarios it was
designed for (litigation onboarding, multi-period asset/lease run,
intercompany payment-application). The composition surface is
ergonomic — `composition_test.clj` is a 230-line existence proof that
two modules' builders concat by string tempid into one atomic, gated
commit. **The caveats are: (1) the authz client's only public write
surface bypasses the kernel gate via raw `d/transact`
(`modules/authz/src/kontor/authz/client.clj:157`) — composers can
reach `write-relationships-tx-data` directly but the
`IAuthorization` protocol does not expose it; (2) several "almost-
business" writes still bypass the gate (`schedule/mark-completed!`
et al., `side-effect/claim!`/`mark-done!`, `status-machine/record-
status-change!`'s standalone path, asset-depreciation
`open-book!`/`revise-book!`, `inventory/record-detail!`, several
inventory/count `d/transact` calls); (3) the per-period-vt
orchestrators (`run-depreciation!`, `run-lease!`) still issue N
`run-process` calls one per period — preserving per-period
`:tx/valid-from`, but reintroducing the partial-failure window the
ADR-067 design promised to delete (the `:fired-before-violation`
mitigation survives at `runner.clj:181` and
`modules/lease/src/kontor/lease/runner.clj:447`).** None of these is
a P0 ship-blocker for the v1 promise (atomic cross-module
composition of *single-event* writes) — but consumers writing
period-runner-like orchestrators will trip on (3).

## 1. Five concrete cross-module scenarios

### 1.1 "Create invoice + grant buyer access + attach audit-doc"

*The headline ADR-068 scenario.* The builders exist:
`kontor.invoice/create-tx-data` (`src/kontor/invoice.clj:62`),
`kontor.authz.client/write-relationships-tx-data`
(`modules/authz/src/kontor/authz/client.clj:135`),
`kontor.audit-doc/create-doc-tx-data` (`src/kontor/audit_doc.clj:66`).
**It works** — three step fns assemble into one process, the buyer-
ref-by-string-tempid threading is the same shape as the legal-
hold/audit-doc composition in `composition_test.clj:80-87`. **One
friction:** `write-relationships-tx-data` is *only* a defn, not a
method on `IAuthorization`. A consumer holding an `AuthzClient`
needs to know to call the namespaced fn directly rather than
through the client record. The `AuthzClient.write-relationships!`
method does its own raw `d/transact` (`client.clj:157`),
bypassing the gate. (Detail in §6.) **No missing knob, no design
gap — composes today; the protocol surface just under-advertises
the composability.**

### 1.2 "Receive goods + post GL + grant inventory-clerk access"

The receipt-with-inventory transactor is already an internal
composition: `post-receipt-with-inventory-tx-data`
(`modules/procurement/src/kontor/procurement/receipt.clj:204`)
returns the per-item `plan-stock-move` postings + the status-
history row + valuation rows as one tx-data vector. **Adding an
authz fragment is trivial** — wrap it in `run-process` instead of
the current `transact-with-validation` (`receipt.clj:337-342`),
add an authz step calling `write-relationships-tx-data`. The
existing wrapper composes via vec-concat, just like the third test
in `composition_test.clj:192-230`. **No friction** beyond §1.1's
"authz fn lives off the protocol".

### 1.3 "Onboard a litigation matter" (subpoena → hold → DSAR ack)

The composition test (`composition_test.clj:58-121`) covers
**subpoena audit-doc → legal hold** in one process. The next-step
richness — a paired DSAR acknowledgement — is one builder away:
`kontor.dsar/file-request-tx-data` (`src/kontor/dsar.clj:387`)
follows the convention. The three-way compose (audit-doc → hold →
DSAR) is the same pattern in `composition_test.clj`, no new
machinery. **Works; richness is mechanical.**

### 1.4 "Year-end close" — multi-step orchestrator

This one **is awkward today.** `kontor.closing/close-fiscal-year!`
(`src/kontor/closing.clj:217-233`) is *two* gated commits: the
closing tx itself (`close-period!`, line 196), then `period/close!`
(line 231). Each is its own `transact-with-validation`. **It is NOT
a `kontor.process`.** A genuine year-end close also needs to
sequence per-asset `run-depreciation!` (each of which is N per-
period processes — see §1.5), per-lease `run-lease!`, then close-
period, then `period/seal!` — none of these chain through one
process today. The author of an "end-to-end close" orchestrator
must today (a) call N runners that internally fire N×M
`run-process` calls, then (b) call the two-tx
`close-fiscal-year!`, then (c) call `period/seal!`. **The friction:**
there is no "compose a closing tx with a period-lock tx in one
atomic commit"; `period/lock-tx` (`src/kontor/period.clj:369`)
*deliberately* runs as a *separate* raw `d/transact` after the
gated close (it stamps `:period/lock-tx` with the previously-
committed tx-id, an audit denorm). This carve-out is documented at
the call site but it is a real ADR-068 exception: an audit-denorm
write that *is* a business write but cannot route through the gate
because the value it writes is *the gate's own tx-id*. **Followup:
either generalize this via a `:db/current-tx`-self-ref pattern in
the gated tx, or document it as the second permanent bootstrap-
class carve-out.**

### 1.5 "Disputed-invoice workflow" (dispute + payment-application + audit-doc)

`kontor.payment-application/apply-payment-tx-data`
(`src/kontor/payment_application.clj:231`),
`kontor.collections.dispute/raise-dispute-tx-data`
(`modules/collections/src/kontor/collections/dispute.clj:113`),
`kontor.audit-doc/create-doc-tx-data` — all builders exist, all are
pure, all take a db arg + opts. They compose by vec-concat or via
`run-process`. **Works.** `apply-payment!` itself is already a
composition (the payment-application row + the invoice-status
change drive), and the FIFO variant
(`apply-fifo-payment!`, line 490) is a `run-process` over per-
invoice steps — proof that the orchestrator-pattern lands cleanly
where the per-step output stays inside ADR-067's identity rules.

### 1.5b The N-period orchestrator pattern (the real friction)

`run-depreciation!` and `run-lease!` end up doing **N separate
`run-process` calls, each one period.** Look at
`modules/asset/src/kontor/asset/runner.clj:168-183` — the per-
period try/catch is *the same `:fired-before-violation` mitigation
note 42 said would disappear*. Same shape at
`modules/lease/src/kontor/lease/runner.clj:435-450` *plus* the
lockstep-divergence guard at `runner.clj:392-396`. The reason is
explicit in the runner.clj comments (`asset/runner.clj:148-154`):
each period needs its own `:tx/valid-from` set to the period's
date, and ADR-067 says one process owns one `with-vt`. So one
process per period — atomic per period, NOT atomic per run. The
ADR-067 promise "the mitigations *delete themselves*"
(decisions.md:7124) holds for `commence!` (one process, one vt) and
for the modification transactors (one process per modification
event) but *not* for the period runners. The honest framing — the
per-period vt is a domain requirement, the cost is "one
`run-process` per period" — is in the runner.clj comments, but it
is not in ADR-067 itself. **A consumer reading just ADR-067 will
be surprised that `run-depreciation!` issues N transactions, not
one.** The doc would benefit from saying so.

## 2. The composability tax

### 2.1 Performance

I attempted a REPL micro-benchmark of `run-process` (one step) vs
the equivalent direct `transact-with-validation`. The running REPL
hit a stale core.async issue and the bench couldn't complete — but
the structural cost is bounded: `run-process` adds (a) one
`locking conn`, (b) one `(d/db conn)` snapshot, (c) one
`run-steps` reduce that for a one-step process is one
`d/db-with`-free pass through `normalize` + `strip-tx-meta` + the
step call, (d) one `with-vt` map-merge. The committed tx-data
arrives at `transact-with-validation` *identical* to what a hand-
written wrapper would assemble. **Estimated overhead: << 1ms on
the call path, dominated by the locking and the `(d/db conn)`
deref — both essentially free.** The `O(steps²)` `d/db-with` cost
(`src/kontor/process.clj:101`) is per-step, not per-call, and
kontor's processes are O(periods) short. The test-suite went 935
→ 938 with no observable runtime regression in the kaocha summary
output (the same per-test-file pattern, no time printed but no
suite slowdown noted).

### 2.2 API surface

Two fns per business write instead of one. **91 `*-tx-data`
builders today** (`grep` count across `src/` + `modules/*/src/`),
each paired with a `defn xxx!`. Maintenance:
the doublet adds ~10-15 lines per transactor (the wrapper is
typically 6-8 lines; the builder is 4-5 lines more than the
original because the validation paths are factored out). Across
the ~80 migrated transactors that is ~800-1200 lines of new code,
all mechanical. **Not free, but worth the composability.**

### 2.3 Docstring + naming discipline

Survey of three transactors:

- `kontor.legal-hold/place-tx-data` (`legal_hold.clj:421-477`) —
  docstring explicitly says "ADR-068; use as a `kontor.process`
  step; `:vt-from`/`:vt-to` owned by the caller." **Good.**
- `kontor.collections.case/open-case-tx-data`
  (`case.clj:124-165`) — docstring says only "Pure tx-data builder
  for `open-case!` (ADR-068). Optional `:tempid`/`:opened-at`."
  **Sparse** — a reader who has not read ADR-067 has to infer the
  semantics from the wrapper. The pattern "every `-tx-data`'s
  docstring is one sentence pointing at the `!` for opts" is
  efficient but leans on the wrapper to teach the reader.
- `kontor.audit-doc/create-doc-tx-data`
  (`audit_doc.clj:66-88`) — like case.clj, terse.

**Inconsistency:** `legal-hold/place-tx-data` documents the
ADR-068 semantics; most others just reference it. **Followup:** a
template comment block at the top of each module file ("ADR-068:
every `xxx!` here is `xxx-tx-data` + `with-vt` + gate") would let
each builder docstring stay one-line without losing the link.

### 2.4 The composable-tempid knobs

ADR-067's addendum (`decisions.md:7221-7262`) introduces
`:tempid-suffix`, `:tempid`, `:asset-tempid`/etc, `:tx-tempid` per-
builder. Across the migrated builders the convention is followed
consistently — `kontor.lease.liability/open-liability-book-tx-data`
takes `:tempid-suffix` (used at `lease/runner.clj:247`),
`kontor.asset.depreciation/open-book-tx-data` takes `:asset-
tempid` (used at `runner.clj:251`). The cost is locally legible —
the tempid space management lives in the builder, where the
distinction between *defining* and *referencing* a tempid is clear.
**Working as intended.** A consumer composing a custom process
across two builders that *both* hardcode tempid `"x"` would
collide; they would have to manually pass `:tempid-suffix` on at
least one. Documented in the addendum, not in the per-builder
docstrings — minor friction.

## 3. The bootstrap-vs-business-write line

ADR-068 explicitly carves out: schema installers, l10n chart
seeders, `core.clj`'s test-db setup, `validation.clj`'s
invariant-rule installer. **Audit:**

| Site | Class | Status |
|------|-------|--------|
| `src/kontor/schema.clj:3610` | schema bootstrap | OK |
| `src/kontor/core.clj:91` | test-db bootstrap | OK |
| `src/kontor/validation.clj:91,92,225` | invariant installer + the gate's own `d/transact` | OK |
| `src/kontor/bitemporal.clj:144,147,150` | `with-vt!` convenience (ungated) | borderline — used in tests + a few migration call sites |
| `src/kontor/payment_term.clj:103` | `define-standard-terms!` (chart-of-terms) | OK — l10n-like |
| `src/kontor/ledger.clj:44` / `valuation.clj:40` | primary-book seeders | OK |
| `src/kontor/period.clj:369` | `period/lock-tx` denorm (see §1.4) | **Carve-out** — gated tx already committed; this is the audit-denorm write |
| `src/kontor/legal_hold.clj:203` / `audit_doc.clj:214` / `dsar.clj:211` / `retention.clj:113` | status-transition + approval-policy seeds | OK — bootstrap |
| `src/kontor/import_/beancount.clj:301-337` | beancount loader | Borderline — these *look* like real business writes (every transaction in a beancount file). Currently raw `d/transact`. ADR-068 says "would a real consumer write this through a business API?" — yes, they would. **Gap.** |
| `modules/*/schema.clj` | schema bootstrap | OK |
| `modules/l10n-*/chart.clj` | chart seeders | OK |
| `modules/l10n-de/closing.clj:44` | auto-create CLOSE journal (bootstrap-like) | Borderline — runs at first close, not at install time |
| `modules/l10n-in/states.clj:79,106` | tax-state seeds | OK — l10n bootstrap |

**Crispness assessment.** The line is *clear in principle* (bootstrap
= one-time setup; business write = a real consumer event). In
practice **3 borderline cases** sit on the wrong side:
`bitemporal/with-vt!` is the leakiest (it pre-dates ADR-068 and is
called from tests + a few migration sites for convenience),
`beancount` is a real business path that bypasses the gate,
`l10n-de/closing` first-time journal creation is benign but happens
on the first business-event call.

### Test-side audit

Three test files spot-checked:

- `test/kontor/composition_test.clj:42-49` — raw `d/transact` for
  *partner seed* (a `:partner/external-id` row used as user-id
  stand-in). **Correct.** Bootstrap fixture.
- `test/kontor/process_test.clj:23-31` — raw `d/transact` for the
  catalog (commodity, accounts, journal). **Correct.** Fixture.
- (eyeballed) `modules/inventory/test/kontor/inventory/ops_test.clj`,
  similar pattern — facility + product fixtures via raw d/transact,
  the actual `issue!` / `transfer!` exercises business APIs.
  **Correct.**

**The litmus test holds in the tests checked.** No "fixture seeding
that should be a business call" surfaced in the spot-check.

## 4. Market-pain patterns from comparable systems

### 4.1 Surprise rollbacks under composition

> NetSuite SuiteScript / Tryton wizards / SAP CDS: one module's
> validator can veto another's work in a way the composer cannot
> predict.

kontor's exposure: **partial.** The gate runs all validators on the
*assembled* tx-data, so a destructive write in a legal-hold scope
(`assert-no-hold-violating-destructive-writes!`,
`legal_hold.clj:387`) inside a composite process aborts the whole
process — the composer might not have *known* a target was under
hold. **Mitigation in place:** the error carries `:violations` with
`:hold-eid` + `:hold-code` + a remediation pointer
(`legal_hold.clj:403-414`). **Likely complaint:** "I composed two
seemingly-independent writes and the entire process aborted because
of a third entity's hold I had no idea about." That is the right
behavior; it needs *good error reporting* to not feel arbitrary.
The error message is good; the composition story should advertise
this. (Followup: a `dry-run? true` mode for `run-process` already
exists (`process.clj:127-138`) — document it as the
"validate-before-commit" pattern for risky composes.)

### 4.2 Raw-write escape hatch

> "Why can't I just transact this directly?" — consumers want a
> bypass for one-off scripts.

kontor's exposure: **immune in principle, partially leaky in
practice.** `d/transact` is still callable; the kernel doesn't
fight you. ADR-068 expresses a *discipline*, not a structural lock.
The `:commit` override on `run-process`
(`process.clj:119-122,138`) exists explicitly as a per-call bypass.
This is the right tradeoff — a v1 consumer who needs a low-level
escape has it, but the default is gated.

### 4.3 Performance of multi-module composes at scale

> N × O writers contending for a single gate — observed in SAP under
> month-end batch loads.

kontor's exposure: **partially mitigated by the single-writer
shape.** datahike is one writer; `run-process` serializes on
`conn`. Composition is "more writes per logical-event," not "more
writers competing." The `O(steps²)` `d/db-with` cost in
`run-steps` is real but bounded; the gate's invariant pass runs
*once* per process. **The genuine concern is the N-period
orchestrators (§1.5b):** each fires N `run-process` calls in a
loop, each takes the lock + runs the gate. For a 360-month lease
that is 360 gate-runs at run-lease! time. **Followup:** measure;
likely fine for accounting cadences but a 10k-asset fleet running
catch-up could spend a lot of cumulative time in the gate. The
escape valve is the `:db.fn/call`-the-whole-process variant ADR-067
*deliberately deferred* (decisions.md:7144-7168) — re-evaluate when
a real perf problem surfaces.

### 4.4 Debugging atomic-tx failures

> Error message points at the tx, not the step.

kontor's exposure: **partial — depends on the validator.** Step-
level builder failures (the `when-not :code (throw ...)` guards in
e.g. `legal_hold.clj:432-438`) throw with their own context. The
gate-level validators (`sealing`, `period`, `legal-hold`,
`state-machine`, `sum-to-zero`) throw `ex-info` with `:type` +
domain context but **do not point at which step in a process
produced the offending fragment.** A composite process that fires
six builders and aborts at the sum-to-zero check carries `:tx-data`
with the entire assembled vector but no "which step" annotation.
**Followup:** `run-steps` could optionally tag each fragment with
a `:source-step idx` marker that survives into the error context.
Small enhancement; useful at scale.

## 5. Gaps relative to ADR-068's universal-gate promise

Census of `d/transact` sites that *look like business writes* but
do not go through the gate:

1. **`src/kontor/status_machine.clj:335`** — `record-status-change!`
   (standalone). This is a business write; the recommended path
   per ADR-068 would be to route through `transact-with-validation`.
   **Workaround:** in practice every business transactor already
   calls `record-status-change-tx-data` (the pure builder) and
   composes it into a gated commit. The standalone `!` is rarely
   the right call. But it exists, and a caller hitting it skips
   the gate.
2. **`src/kontor/status_machine.clj:355`** — `bulk-record-status-
   change!` — same as above.
3. **`src/kontor/schedule.clj:207`** — `record-occurrence!`. Used
   in the per-period runners as the *composition fragment*
   (`record-occurrence-tx-data` at line 162). The standalone `!` is
   ungated. Likely safe because every real caller goes through a
   runner.
4. **`src/kontor/schedule.clj:228,233,239`** — `mark-completed!` /
   `mark-paused!` / `mark-cancelled!`. **Genuinely ungated
   business writes.** The pure builder
   (`set-state-tx-data`, line 215) exists; the wrapper should
   route through the gate. *(Mentioned by the user in §5 of the
   brief; confirmed.)*
5. **`src/kontor/side_effect.clj:75,83,99,110`** — `claim!`,
   `mark-done!`, `mark-failed!`, `mark-abandoned!`. The ADR-041
   side-effect intent state-machine writes. All raw `d/transact`.
   **Genuinely ungated.** No pure builder split today. These are
   worker-thread writes — they *would benefit* from the period /
   sealing gate (an intent inside a sealed period should not flip
   state without notice).
6. **`src/kontor/retention.clj:362`** — `apply-expiry!` runs
   `validate-and-apply` directly then `d/transact` separately. A
   TOCTOU window exists between validate and transact, documented
   in the comment at line 358-361 as a deliberate tradeoff (so
   that the `:legal-hold/purge-blocked` error surfaces unwrapped).
   **Architectural exception, documented**, but worth noting as a
   deviation from the "every gate-route is one `transact-with-
   validation`" invariant.
7. **`src/kontor/payment_application.clj:229`** — `apply-payment!`
   does its own `d/transact` (not `transact-with-validation`).
   *Looking again*: it routes the tx-data through the gate via the
   wrapping `with-vt` but uses raw `d/transact`. **This is a gap**
   — the gate's invariants do *not* run on this path. The pure
   builder is correct; the wrapper should call
   `validation/transact-with-validation`.
8. **`src/kontor/period.clj:369`** — `:period/lock-tx` audit
   denorm. **Documented carve-out** (see §1.4). The chicken-and-
   egg "I need the gate's tx-id to record the audit pointer" is a
   real design issue.
9. **`modules/authz/src/kontor/authz/client.clj:157`** — see §6.
10. **`modules/asset/src/kontor/asset/depreciation.clj:332,403`** —
    `open-book!` and `revise-book!` use raw `d/transact`. Both
    have pure builders; the `!`s should be the gated wrappers.
11. **`modules/inventory/src/kontor/inventory/core.clj:287`** —
    `record-detail!` (the low-level inventory-detail appender)
    uses raw `d/transact`. Genuine ungated business write.
12. **`modules/inventory/src/kontor/inventory/core.clj:316`** —
    `place-opening-stock!` uses raw `d/transact`. Borderline (it
    is a migration path) but per ADR-068 it is a business write
    on a live system.
13. **`modules/inventory/src/kontor/inventory/count.clj:51,85`** —
    `start-count!` and `record-count-line!` (per
    `:physical-inventory`). Both pure-builders-not-yet — the
    transactor is the only call site. **Ungated business writes.**
14. **`src/kontor/import_/beancount.clj:301-337`** — the beancount
    loader does ~7 raw `d/transact`s of business tx-data. The doc
    comment treats it as bulk-load, but ADR-068's litmus says
    "would a consumer call this as a business API?" — yes, it is
    a real ingestion path.

**Severity triage.**
- **P1** (genuine ungated business writes that should be fixed):
  #4 (`schedule lifecycle`), #5 (`side-effect/*!`), #7 (`apply-
  payment!`), #10 (asset `open-book!` / `revise-book!`), #11–13
  (inventory low-level writes), #14 (beancount loader).
- **P2** (architectural carve-outs already documented or with a
  documented rationale): #6 (`retention/apply-expiry!`), #8
  (`period/lock-tx`).
- **P3** (in practice unreachable via real flows): #1–3, #9 (the
  authz protocol method is a public surface; consumers expecting
  the gate would be surprised — but the alternative entry exists).

ADR-068's "universal" promise should be either (a) tightened to
"every `!` in the curated business-write surface" plus an explicit
list of the P2 carve-outs, or (b) honored by closing the P1 gaps
in a follow-up sweep.

## 6. Authz integration story — the next step

**The gap.** `kontor.authz.client.AuthzClient` implements
`IAuthorization` (the SpiceDB-shape protocol). Every write method
on the record calls `do-write-relationships!` which calls
`(d/transact conn (write-relationships-tx-data …))` — raw
`d/transact`, no gate (`client.clj:155-159`). A consumer wanting
to compose an authz write with a kernel write in one atomic
event must:
- *Not* call `(.write-relationships! client …)`.
- Instead call the *function* `(write-relationships-tx-data db
  opts updates)` directly and stitch it into a `run-process` step
  (the `:object-id->entid` lives on `(:opts client)`; reachable
  but undocumented).
- Hope the consumer knows that the protocol's `write` methods
  bypass the kernel gate.

**The fix.** Two cohabitating shapes:

1. **Keep `IAuthorization` as the read API** + a thin write
   surface (raw `d/transact`) for an authz-only consumer who is
   not composing. This is the SpiceDB-compatible shape.
2. **Add a `kontor.authz.process` namespace** (or extend
   `kontor.authz.client`) with explicit composable surface:
   `(grant-tx-data db client-opts subject relation resource)` and
   `(revoke-tx-data db client-opts subject relation resource)`,
   the canonical ADR-068 builders that a `kontor.process` step
   calls. These wrap `write-relationships-tx-data` with the
   coercion glue from the client's opts.
3. The current `IAuthorization` write methods continue to work
   for non-composing callers, but their docstrings should say
   "for atomic composition with kernel writes, use
   `kontor.authz.process/grant-tx-data` and route through
   `kontor.process/run-process`." A follow-up could route these
   methods through the gate too — the only blocker is they don't
   currently know about `kontor.validation`, which is a cycle the
   `requiring-resolve` pattern (used in `legal_hold.clj:56-59`)
   already solves.

**Recommendation:** a `kontor.authz` namespace-level docstring +
two builder fns aliased from the client, *plus* a gated variant of
`do-write-relationships!`. The internal cycle is solvable
(`requiring-resolve` of `kontor.validation/transact-with-
validation` from `client.clj`). This closes #9 and turns it into a
docs-and-routing question.

## 7. Followups (prioritized)

**P0 — closes universal-gate promise**
- *Followup-49.1.* Route `schedule/mark-completed!` /
  `mark-paused!` / `mark-cancelled!` through
  `transact-with-validation`. Tiny — pure builder already exists.
- *Followup-49.2.* Route
  `kontor.payment-application/apply-payment!` through
  `transact-with-validation` (currently raw `d/transact` at
  `payment_application.clj:229`).
- *Followup-49.3.* Route `kontor.asset.depreciation/open-book!`
  and `revise-book!` through the gate
  (`depreciation.clj:332,403`).
- *Followup-49.4.* Route the authz client's `do-write-
  relationships!` through the gate via `requiring-resolve` of
  `transact-with-validation` (cycle workaround already in use in
  `legal_hold.clj`). Add `kontor.authz.client/grant-tx-data` +
  `revoke-tx-data` as the composable surface.

**P1 — closes the "almost-business-write" gaps**
- *Followup-49.5.* `kontor.side-effect/claim!`/`mark-done!`/
  `mark-failed!`/`mark-abandoned!` — split into `*-tx-data` +
  gated wrapper. Worker writes deserve the period gate.
- *Followup-49.6.* `inventory/record-detail!`,
  `inventory/place-opening-stock!`,
  `inventory/count/start-count!`, `record-count-line!` — gate them.
- *Followup-49.7.* `beancount` loader — at minimum route the per-
  transaction `d/transact` through the gate. Bulk loads of
  business data should not bypass invariants.

**P2 — design + doc clarifications**
- *Followup-49.8.* ADR-067 doc tweak: explicitly say "per-period
  runners issue N `run-process` calls" so a consumer reading the
  ADR is not surprised by `:fired-before-violation` surviving in
  `run-depreciation!` / `run-lease!`. Note that the lockstep guard
  at `lease/runner.clj:392-396` is intrinsic to the per-period-vt
  design.
- *Followup-49.9.* Add a `:source-step idx` annotation to
  `run-steps`' fragments so gate-level error contexts can point at
  which step contributed the offending fragment.
- *Followup-49.10.* Document `:dry-run? true` as the "validate-
  before-commit" pattern for risky composes — surface it in the
  ADR-067 text, not just the `process.clj` docstring.
- *Followup-49.11.* Add a per-module top-of-file ADR-068 comment
  template so each `*-tx-data` docstring can stay one line without
  losing the ADR link.
- *Followup-49.12.* Document the `period/lock-tx` carve-out in
  ADR-068 as the second permanent bootstrap-class exception
  (audit-denorm pointing at the gate's own tx-id). Or generalize
  via a `:db/current-tx` self-ref pattern so the denorm rides
  inside the gated tx.

**P3 — perf, if needed**
- *Followup-49.13.* If the N-period orchestrators show real
  contention under load, reconsider the `:db.fn/call`-the-whole-
  process variant for *that specific case* (deferred per
  decisions.md:7163-7168).

## Sources / file citations

- ADR-067 + ADR-068: `doc/decisions.md:7108-7361`.
- Research notes 42, 44, 45, 46, 47.
- `src/kontor/process.clj` (138 LOC).
- `src/kontor/validation.clj` (`validate-and-apply` at line 167;
  `transact-with-validation` at line 207).
- `src/kontor/legal_hold.clj:421-560` (representative kernel
  builder + wrapper pair).
- `modules/lease/src/kontor/lease/runner.clj:50-308` (one-process
  `commence!`); `runner.clj:314-500` (per-period `run-lease!`).
- `modules/asset/src/kontor/asset/runner.clj:60-217` (per-period
  `run-depreciation!`).
- `modules/lease/src/kontor/lease/modification.clj:60-340` (the
  four atomic-modification transactors).
- `modules/inventory/src/kontor/inventory/ops.clj:340-398` (the
  `issue!` migration per note 47 option b).
- `modules/authz/src/kontor/authz/client.clj:135-160` (the authz
  write-tx-data + the ungated client method).
- `test/kontor/composition_test.clj` (230 LOC, three deftests).
- `test/kontor/process_test.clj` (184 LOC).
- `grep` census of `d/transact` sites across `src/` and
  `modules/*/src/` (Section 5).
- Test suite: `bb test` — 938 tests, 3421 assertions, 0 failures
  (verified 2026-05-15).
