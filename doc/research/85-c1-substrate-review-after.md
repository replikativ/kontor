---
date: 2026-05-18
title: 85 — C1 HR substrate review-after (Stage R)
status: review-after
audience: maintainer + Stage R contributor — read before C2 (DE-DATEV-LODAS) lands
---

# 85 — C1 HR substrate review-after

Independent code-review of commits `5b8bcf7` (research note 81) +
`04c121b` (Stage R C1 substrate) against ADR-075 and notes 79 + 81.
Audit-only: I do not propose rewrites, only diagnose with `file:line`
citations + concrete fixes. Per ADR-037 review-after discipline.

The C1 substrate is, by and large, the shape the locked design calls
ask for. The kernel additions are minimal and well-scoped, the
companion module mirrors `kontor-expense` / `kontor-lease`'s structure,
and the protocol trio in `kontor.payroll-provider` lines up with
ADR-071's three-protocol pattern. Tests pass (per the commit message:
1610 / 5768 / 0).

What follows is the gap list — what's structurally off, what's
documented-but-not-wired, and where the per-design-call audit
diverges from the ADR-075 narrative.

---

## §1 — TL;DR (ship verdict + counts)

**Verdict: SHIP C1, but land the two P0 fixes before C2 starts.** The
P0s are both narrow (one missing approval-policy enforcement path, one
unused schema attribute that ADR-075 claims is load-bearing); each is
a ~30-line patch. After P0 closure, the substrate is solid enough that
C2 (DE-DATEV-LODAS) can begin without rework.

- **P0 ship-blockers: 2** (one approval-policy bypass; one
  unwired-but-load-bearing schema attr — see §2).
- **P1 followups: 6** (mostly missing creation-status-history seeds,
  documentation drift, and untested termination/back-dated-correction
  paths — see §3).
- **P2 nice-to-haves: 5** (asymmetry with privilege's reclassify path,
  DSAR registry pattern, fixed-string tempids, vt-from optionality — §4).

The five locked design calls are honored. Note 81's `§9.6`
compensation-as-entity refactor landed correctly; the three `§9.7`
micro-additions (`:person/kind`, `:work-time-fraction`,
`:work-relationship-kind`) are present + schema-doc'd. No P0 is on
the call-level decisions — every P0 is below the design-call abstraction
in the substrate's plumbing.

---

## §2 — P0 ship-blockers

### P0-85-1 — `terminate!` bypasses the `:requires-supporting-doc` approval-policy

**Citation.** `modules/hr/src/kontor/hr/employment.clj:94-117`
(`terminate-tx-data` + `terminate!`) writes the
`:employment/state :terminated` flat via `transact-with-validation`,
NOT through `kontor.status-machine/record-status-change!`.

The approval-policy seed exists at `modules/hr/src/kontor/hr/schema.clj:603-608`:
```clojure
{:approval-policy/entity-type     :employment
 :approval-policy/facet           :employment/state
 :approval-policy/transition-from :active
 :approval-policy/transition-to   :terminated
 :approval-policy/rule            :requires-supporting-doc
 :approval-policy/active          true}
```
But the rule check at `src/kontor/status_machine.clj:202-205`
ONLY fires inside `record-status-change-tx-data`. A raw
`{:db/id eid :employment/state :terminated}` datom bypasses it.
**The approval policy is dead.**

The kernel's canonical pattern is `modules/expense/src/kontor/expense/core.clj:187-212`
(`change-status-tx-data` + `change-status!`): every status transition
routes through `sm/record-status-change-tx-data` so the policy fires.
HR's `terminate!` does not follow this pattern.

The same defect affects ALL HR status writes:
- `person/create-person-tx-data` (sets `:person/state :active` directly)
- `employment/hire-tx-data` (sets `:employment/state :hired` / `:active`)
- `compensation/set-compensation-tx-data` (sets `:compensation/state :active`)
- `compensation/supersede-compensation-tx-data` (sets `:compensation/state :superseded`)
- `pay_period/create-pay-period-tx-data` (sets `:pay-period/state :open`)
- `payroll/create-payroll-run-tx-data` (sets `:payroll-run/state :computed`)

For the `:nil → <initial>` transitions the policies don't require docs
(the seeds permit `:nil → :hired` plainly), so the seeds are silent.
But **`terminate!` runs WITHOUT a supporting doc**, AND its target
transition's policy explicitly requires one. The audit chain has no
`:status-history` row recording who terminated whom and why.

**Fix (sketched).** Refactor `terminate-tx-data` to compose
`record-status-change-tx-data` for the `:employment/state` transition,
threading `:changed-by-uid`, `:reason`, `:reason-note`, `:supporting-doc`
through opts. Same pattern as `expense/change-status-tx-data`. Roll
the same fix through the other transactors as a separate P1 followup
(see P1-85-1 below — they don't violate an active policy *today*, but
do silently skip `:status-history` and so don't audit-trail correctly).

**Commit hint.** `fix(hr): route terminate! through record-status-change so :requires-supporting-doc fires`

### P0-85-2 — `:retention-policy/category` is added to schema but the sweeper never reads it

**Citation.** `src/kontor/schema.clj:754-761` adds the attribute. The
ADR-075 narrative at `doc/decisions.md:7913` says it "mirrors the
audit-doc attr; lets the ADR-050 sweeper carry per-category retention
floors." The sweeper at `src/kontor/retention.clj:127-170` (`policy-for`)
+ `src/kontor/retention.clj:220-250` (`candidate-eids` / `entity-of-type?`)
**never queries `:retention-policy/category` nor matches it against any
entity's `:audit-doc/category`**.

```
$ grep -n category src/kontor/retention.clj
(no output)
```

This means: if `kontor-l10n-de` (the C2 candidate) ships
```clojure
{:retention-policy/code "DE-GDPR-payroll-pii"
 :retention-policy/applies-to [:audit-doc]
 :retention-policy/category :payroll
 :retention-policy/duration-years 6  ; SGB IV §28f
 ...}
{:retention-policy/code "DE-HGB-financial"
 :retention-policy/applies-to [:audit-doc]
 :retention-policy/category :financial
 :retention-policy/duration-years 10  ; HGB §257
 ...}
```
both policies match the same `:audit-doc` entity-type, the
jurisdiction-pref tiebreaker doesn't disambiguate, and the
effective-from tiebreaker picks one arbitrarily. **The whole point
of the category axis** — per-jurisdiction floors by subject-matter —
is unenforced.

**Fix (sketched).** In `policy-for`, add a `:category` arg (default
nil); in the candidate filter, match the policy's
`:retention-policy/category` against either (a) the supplied
`:category` arg, or (b) for `:audit-doc` candidates, the entity's
own `:audit-doc/category`. In `candidate-eids`, when the policy
carries a category, restrict the candidate set to entities whose
`:audit-doc/category` matches. A 20-line patch + 2 test cases.

**Commit hint.** `fix(retention): policy-for + candidate-eids match :retention-policy/category against :audit-doc/category`

---

## §3 — P1 followups (land before C2 is "done")

### P1-85-1 — HR transactors don't record `:status-history` for entity creation

**Citation.** `modules/hr/src/kontor/hr/person.clj:43-50` (no
status-history); contrast with
`modules/expense/src/kontor/expense/core.clj:104-112` which threads
a `:nil → :draft` `record-status-change-tx-data` into the create tx.

Affected: `person/create-person-tx-data`, `employment/hire-tx-data`,
`compensation/set-compensation-tx-data`, `pay-period/create-pay-period-tx-data`,
`payroll/create-payroll-run-tx-data`. None records the `:nil → <init>`
transition. The lifecycle audit trail starts at "first explicit transition"
rather than at creation, which is what other kontor companions do.

**Fix.** Add the kernel-pattern status-history append to each of the
five create-tx-data builders. ~5 lines each.

### P1-85-2 — `:partner/kind` schema docstring still says ":customer | :vendor | :both"

**Citation.** `src/kontor/schema.clj:541`. Note 79 Call 3 + ADR-075
add `:employee` (and per note 81 §9.7 `:contingent`) to the open-set
vocabulary. The attr type is `:db.type/keyword` so the values work
fine; this is purely a documentation drift — a contributor reading the
schema doc would not know the open-set extension is sanctioned.

**Fix.** Update the docstring to `:customer | :vendor | :both |
:employee | :contingent | … (consumer extends per ADR-039 + ADR-075)`.

### P1-85-3 — No `terminate!` test

**Citation.** `modules/hr/test/kontor/hr/hr_test.clj` has 9 tests but
none exercises `employment/terminate!`. ADR-075's test discipline claim
("9 tests / 34 assertions … covering … terminate!") is **not borne
out** by the file's contents — the word `terminate` does not appear
in any test.

Combined with P0-85-1 (the bypass), there is zero test pressure on
the termination path. A test that calls `terminate!` and asserts (a)
the `:state` becomes `:terminated`, (b) `:end-date` is set, (c)
`:supporting-doc` is required by the policy (once P0-85-1 is fixed)
should land alongside the P0 fix.

### P1-85-4 — No back-dated `:compensation` correction test

**Citation.** `modules/hr/test/kontor/hr/hr_test.clj:202-234`
(`supersede-compensation-closes-prior`) tests only forward-dated
supersession (raise on `2027-01-01`). Note 79 §7's simmis pitch
explicitly mentions "Knob: 3% raise on 2027-01-01" — the forward case
— but the harder bitemporal case is a back-dated correction: "we
forgot to record Jane's raise from 2026-06-01; her wage record needs
to be `4500M` for May 2026 but `5000M` from June onwards retroactively."
With `:compensation/effective-from` separate from `:db.valid/from`,
this composes — but no test demonstrates it.

**Fix.** Add a test: set initial comp at `2026-05-01` (5000M); then
back-date a corrected comp envelope effective-from `2026-06-01` (4500M)
*transacted today*; assert `employment-current-wage` at `2026-07-01`
returns 4500M, and `2026-05-15` still returns 5000M (the May truth
was the initial value).

### P1-85-5 — `employment-current-wage` returns `0M` silently when the date precedes any comp

**Citation.** `modules/hr/src/kontor/hr/compensation.clj:166-179`. When
`current-compensation` returns nil (no envelope covers `at-date`),
the helper returns `0M`. This is the same shape as Workday's
`getCompensation` returning nil + a consumer fallback; but `0M`
silently is dangerous in payroll math — a `compute-payroll` impl that
naively reads `employment-current-wage` for the pay-period start
date will pay zero rather than failing loud.

**Fix (option 1, safest).** Throw `ex-info :type :hr/no-compensation`
when no envelope covers the date. Forces the consumer to handle
the "before any comp" case explicitly. Aligns with the protocol's
"throws ex-info on missing-data; does NOT silently zero" stance in
`payroll_provider.clj:136`.

**Fix (option 2).** Return nil rather than 0M; let the caller decide.
Smaller blast radius; less safe.

### P1-85-6 — `:purged` transitions from `:deceased` lack approval-policy gating

**Citation.** `modules/hr/src/kontor/hr/schema.clj:621-632` gates
`:active → :purged` with both `:requires-supporting-doc` AND
`:requires-non-empty-reason-note`. But `:deceased → :purged` is
permitted by the status-transition seed at `modules/hr/src/kontor/hr/schema.clj:508`
without ANY approval-policy. So a consumer calling
`record-status-change!` to push a `:deceased` person → `:purged`
(legitimate GDPR/retention-floor erasure) gets NO gate.

The same edge in real life still wants both the supporting-doc (the
retention-clearance memo) AND the legal-basis note. The omission is
asymmetric with the `:active → :purged` rules.

**Fix.** Add two approval-policy rows mirroring the `:active → :purged`
pair but with `:transition-from :deceased`. Two-line patch.

---

## §4 — P2 nice-to-haves

### P2-85-1 — `:audit-doc/category` has no `reclassify!` path

**Citation.** Contrast `src/kontor/audit_doc.clj:243-291`
(`reclassify-privilege-tx-data` — drives `:audit-doc/privilege`
through the status-machine + approval-policy) with the absence of
any `reclassify-category` analogue. The ADR-075 stance is "the kernel
TAGS; the consumer ENFORCES." OK — but the **category may legitimately
change** (a doc reclassified from `:hr-personnel` to `:hr-medical`),
and the kernel has no audit trail for it because there's no
status-history-aware reclassify transactor.

This is asymmetric with privilege and may bite later. Defer to C2 or
the first time a consumer hits it; document as a known gap.

### P2-85-2 — kontor-hr doesn't register `:partner/person` with the kernel DSAR registry

**Citation.** `modules/hr/src/kontor/hr/core.clj:30-36`
(`install!`) calls only `schema/install!`. Compare with the documented
pattern at `src/kontor/dsar.clj:73-78`: "A companion module calls
[`register-partner-attr!`] for each of its own :partner-referencing
attrs at load time."

The HR side instead provides `kontor.hr.dsar/collect-employee` which
manually walks both axes. That works but duplicates the kernel's
walker logic; the documented composition pattern would have
`kontor.dsar/collect` follow `:partner/person` automatically if it
were registered. The discrepancy means future kontor consumers using
the kernel `collect` directly miss the HR side until they know to
swap in `kontor.hr.dsar/collect-employee`.

**Fix.** In `kontor.hr.core/install!`, call
`(kontor.dsar/register-partner-attr! :partner/person)` after the
schema install. The walker then crawls into `:person` and follows
the inverse refs to `:employment` / `:compensation` if those are
also registered. Then `collect-employee` becomes a thin convenience
that just adds the pulled HR view — not load-bearing for completeness.

### P2-85-3 — `set-compensation-tx-data` tempids collide across multi-call composition

**Citation.** `modules/hr/src/kontor/hr/compensation.clj:47-75`. The
parent tempid defaults to `"compensation-1"`; the components use
`"comp-1"` / `"comp-2"` / … . When two `set-compensation!` calls
compose into one process step, the tempids collide. The kernel's
`build-transaction` solved this same problem by deriving posting
tempids from the tx-tempid (`src/kontor/posting.clj:351-354`).

**Fix.** Derive component tempids from the parent: `(str tempid "-c"
(inc i))`. Two-line patch.

### P2-85-4 — `run-payroll!` makes `:vt-from` optional but the build-transaction's stamp is stripped

**Citation.** `modules/hr/src/kontor/hr/payroll.clj:189-192` only
applies `with-vt` when caller passes `:vt-from`. But `posting/build-transaction`
at `src/kontor/posting.clj:371-373` already wraps the postings in
`with-vt(effective-date, forever)`, and `kontor.process/run-steps`
at `src/kontor/process.clj:105` STRIPS that meta. Net effect: a
payroll run without an explicit `:vt-from` produces tx-data with NO
`:db.valid/from`. Datahike's valid-time resolver then defaults via
`get-else` (per the bitemporal.clj docstring), but the semantic — "this
payroll's valid-time anchors to `:transaction/effective-date`" — is
silent rather than explicit.

**Fix.** Have `run-payroll!` default `:vt-from` to the
`:pay-period/start-date` (or pull the `:transaction/effective-date`
out of the assembled tx for the outer with-vt). Aligns with
`expense/change-status!` which always supplies a vt.

### P2-85-5 — `:rehired` state has dual semantics (marker on old row vs new-row state)

**Citation.** `modules/hr/src/kontor/hr/schema.clj:530` permits
`:terminated → :rehired`. ADR-075 line 7918 lists `:rehired` as a
lifecycle state. Note 79 §2.2 says "Re-hire = new :employment with
later :start-date" (a new row). The implementation follows note 79
(a new row gets `:hired`), so `:rehired` is a marker on the OLD row.
That convention is fine but undocumented; a future maintainer may
read the lifecycle as `:terminated → :rehired → :active` which is
not the design.

**Fix.** Add a one-line docstring next to the `:terminated → :rehired`
seed clarifying that `:rehired` is a marker on the prior row.

---

## §5 — Cross-cutting findings

### Architectural pattern drift: status-machine usage

The HR module is unique among the companions in writing status facets
*directly* on entity rows rather than through
`kontor.status-machine/record-status-change-tx-data`. Comparison:

| Companion | Initial-creation status-history | Lifecycle transitions through status-machine? |
|---|---|---|
| `kontor-expense` | yes (`core.clj:104-112`) | yes (`core.clj:187-212`) |
| `kontor-sales` | yes | yes |
| `kontor-lease` | yes | yes |
| **`kontor-hr`** | **no** | **no** |

This is the substrate-level finding behind P0-85-1 + P1-85-1: the
HR module needs a `change-status-tx-data` helper that all the
lifecycle transitions route through, plus the initial-state
`:nil → <init>` row threaded into create.

No ADR addendum is needed; the convention is already documented in
`doc/conventions.md` (transactor opts + status-machine writes). The
fix is to bring kontor-hr in line with the convention.

### ADR-075 narrative vs implementation: minor inaccuracies

ADR-075 § "Test discipline" claims tests cover `terminate!` — they
don't (P1-85-3). ADR-075 line 7913 ("`:retention-policy/category`
lets the ADR-050 sweeper match per-category") is aspirational, not
implemented (P0-85-2). These are narrative drift — fixable by either
landing the implementation or sharpening the narrative.

### Per-design-call audit summary

All five locked design calls (companion-tier, multi-employment,
hybrid linker, three-protocol PayrollProvider, two-axis category)
land in the schema correctly. The §9.6 + §9.7 refinements are
present. **The structural design is right.** The defects are below
the design-call level — they are about how HR composes with the
shipped substrate (status-machine, retention sweeper, DSAR walker).

---

## §6 — Per-design-call audit

### Call 1 (companion-tier placement) — implemented correctly

`modules/hr/src/kontor/hr/schema.clj` carries all 7 HR entities; the
kernel `src/kontor/schema.clj` carries only the two new attrs
(`:audit-doc/category` line 3675, `:retention-policy/category` line
754). No `:person/*` in the kernel. Symmetric with `kontor-sales` /
`kontor-procurement` / `kontor-lease` / `kontor-expense`. ✓

### Call 2 (multi-employment) — implemented correctly

`:employment/person :db.type/ref :db.cardinality/one`
(`modules/hr/src/kontor/hr/schema.clj:141-149`). datahike auto-derives
the reverse `:person/_employments`, so multi-employment is queryable
via a single back-reference. Test at
`modules/hr/test/kontor/hr/hr_test.clj:140-166` proves N concurrent
employments per person work. ✓

### Call 3 (hybrid `:partner/person` linker + DSAR collector) — mostly implemented

`:partner/person :db.type/ref :db.cardinality/one`
(`modules/hr/src/kontor/hr/schema.clj:115-126`). Note: the comment
"set when :partner/kind is :employee" is informational only — there
is no datalog invariant or middleware constraint enforcing it. If a
consumer sets `:partner/person` on a `:partner/kind :customer`
partner, nothing rejects it. This is by design (kontor "tags only";
the consumer enforces) but worth noting.

DSAR collector exists: `kontor.hr.dsar/collect-employee` at
`modules/hr/src/kontor/hr/dsar.clj:78-95`. **Not registered with the
kernel registry** — see P2-85-2. Functional but architecturally
asymmetric. ✓ (with P2 caveat)

### Call 4 (three-protocol PayrollProvider) — implemented; one shape note

`src/kontor/payroll_provider.clj:105-181` defines all three protocols
plus the fourth `PayrollEmitProvider`. The `provider-id` slot is on
the `PayrollComputeProvider` only — `PayrollPostingBuilder` and
`PayrollEmitProvider` do NOT expose it. The reference
`FxRateProvider` at `src/kontor/fx_rate_provider.clj:55-60` has
`provider-id` on the protocol; the missing TaxRateProvider analogue
that ADR-075 cites (the file referenced in the docstring,
`tax_rate_provider.clj`, does not exist in this tree) means we can't
sanity-check against the *actual* ADR-071 shape.

For symmetry it would be cleaner to add `(provider-id [this])` to
`PayrollPostingBuilder` and `PayrollEmitProvider` — the audit log
on `:payroll-run/provider-id` only records the compute-provider's id;
when (in C2 / C3) we ship dual-ledger builders or
country-specific emit providers, the audit chain is silent about WHICH
posting/emit provider ran. **Logged as P2-85-1-bis** (folded into the
P2 set for cleanness).

The default impls (`StaticTableComputeProvider` line 187,
`LocalfileEmitProvider` line 200) are correctly minimal — empty
stubs that prove the protocol is satisfiable. There is no default
`PayrollPostingBuilder` impl. That's OK — note 79 §4 explicitly says
the posting builder is per-country and consumer-supplied. ✓

### Call 5 (`:audit-doc/category` orthogonal) — actually orthogonal

`:audit-doc/category :db.type/keyword` (`src/kontor/schema.clj:3675-3682`)
is independent of `:audit-doc/privilege :db.type/keyword`
(`src/kontor/schema.clj:3655-3661`). Both default nil; no cross-rule
constrains them. A consumer auth layer reading both axes gets the
two-dimensional grid the ADR promised.

**Asymmetry caveat**: privilege has a status-machine facet +
`reclassify-privilege!` transactor (the kernel-tagged change goes
through the audit chain); category has no analogous mutator. See
P2-85-1. ✓ (with P2 caveat)

### Note 81 §9.6 (comp-as-entity) — shape complete

`modules/hr/src/kontor/hr/schema.clj:293-377` ships `:compensation/*` +
`:compensation-component/*` per the §9.6 recommendation.
`:compensation/state` lifecycle (`:proposed → :active → :superseded`)
matches `§9.6`. `:compensation-component/account-hint` matches
`§9.6`'s SKR04-mapping hook. `:compensation-component/period` carries
the per-component cadence (`:hourly` / `:monthly` / `:annual` /
`:one-time` / `:on-event`). The §9.6 helper
`employment-current-wage` is at
`modules/hr/src/kontor/hr/compensation.clj:158-179` — hides the
derived query. ✓

One nit: `:compensation/effective-from` is required (line 300-308)
but the SuccessFactors `EmpCompensation` / Workday Compensation
pattern uses a `(startDate, endDate)` PAIR — the
`:compensation/effective-to` being optional (= open-ended `nil`) is
fine for the "current" case, but the `supersede!` flow at line 102-113
silently mutates an `:active` row's `:effective-to`. That's an
in-place attribute change rather than the "close-validity"
ADR-048-style pattern. The state-transition (`:active → :superseded`)
captures the lifecycle change; the `:effective-to` mutation is its
own datom. This is the right level of explicit but worth being aware
of.

### Note 81 §9.7 (3 minor adds) — all present + (partially) tested

- `:person/kind` (`modules/hr/src/kontor/hr/schema.clj:97-102`,
  docstring covers Workday-Worker subtypes, default `:employee`). ✓
  Not tested.
- `:employment/work-time-fraction` (`schema.clj:207-211`). ✓
  Tested at `hr_test.clj:152-166` (multi-employment test sets
  `0.40M`).
- `:employment/work-relationship-kind` (`schema.clj:217-224`,
  open-set keyword, default `:standard`). ✓ Tested at
  `hr_test.clj:131-134` (default-value round-trip).

---

## §7 — Sources (file:line by topic)

### Kernel additions

- `src/kontor/schema.clj:754-761` — `:retention-policy/category` attr.
- `src/kontor/schema.clj:3663-3682` — `:audit-doc/category` attr.
- `src/kontor/retention.clj:127-170` — `policy-for` (the sweeper's
  resolver; does NOT match `:retention-policy/category`). P0-85-2.
- `src/kontor/audit_doc.clj:243-291` — `reclassify-privilege!`
  reference path; category has no analogue. P2-85-1.

### Payroll provider

- `src/kontor/payroll_provider.clj:105-137` — `PayrollComputeProvider`.
- `src/kontor/payroll_provider.clj:139-160` — `PayrollPostingBuilder`.
- `src/kontor/payroll_provider.clj:162-181` — `PayrollEmitProvider`.
- `src/kontor/payroll_provider.clj:187-198` — `StaticTableComputeProvider` stub.
- `src/kontor/payroll_provider.clj:200-204` — `LocalfileEmitProvider` stub.
- `src/kontor/fx_rate_provider.clj:55-106` — sibling shape reference
  (FxRateProvider — `provider-id` on protocol).

### kontor-hr companion

- `modules/hr/src/kontor/hr/schema.clj:51-109` — `:person/*` (incl.
  `:person/kind` line 97-102).
- `modules/hr/src/kontor/hr/schema.clj:115-126` — `:partner/person`
  linker (kernel↔companion bridge).
- `modules/hr/src/kontor/hr/schema.clj:132-246` — `:employment/*`
  (incl. `:work-time-fraction` 207-211; `:work-relationship-kind`
  217-224).
- `modules/hr/src/kontor/hr/schema.clj:293-377` — `:compensation/*` +
  `:compensation-component/*` (§9.6 refactor).
- `modules/hr/src/kontor/hr/schema.clj:498-582` — status-transition
  seeds.
- `modules/hr/src/kontor/hr/schema.clj:588-632` — approval-policy seeds
  (P1-85-6 gap at 621-632).
- `modules/hr/src/kontor/hr/employment.clj:94-117` — `terminate-tx-data`
  + `terminate!` (P0-85-1 — bypasses status-machine).
- `modules/hr/src/kontor/hr/payroll.clj:36-70` — `check-facts` sum
  invariant.
- `modules/hr/src/kontor/hr/payroll.clj:97-192` — `run-payroll!`
  orchestrator.
- `modules/hr/src/kontor/hr/dsar.clj:19-95` — DSAR walker
  (P2-85-2 — not registered with kernel registry).
- `modules/hr/src/kontor/hr/core.clj:30-36` — `install!` (P2-85-2
  registration site).

### Tests

- `modules/hr/test/kontor/hr/hr_test.clj:72-79` — idempotent install.
- `modules/hr/test/kontor/hr/hr_test.clj:81-97` — schema-attrs-present.
- `modules/hr/test/kontor/hr/hr_test.clj:103-138` — create-person + hire.
- `modules/hr/test/kontor/hr/hr_test.clj:140-166` — multi-employment.
- `modules/hr/test/kontor/hr/hr_test.clj:172-200` — set-compensation.
- `modules/hr/test/kontor/hr/hr_test.clj:202-234` — supersede-compensation
  (forward-dated only — P1-85-4 gap on back-dated).
- `modules/hr/test/kontor/hr/hr_test.clj:240-257` — check-facts.
- `modules/hr/test/kontor/hr/hr_test.clj:299-357` — run-payroll!
  end-to-end.
- **Missing**: terminate, back-dated comp correction, DSAR walk,
  `employment-current-wage` at pre-comp date.

### Reference kernel patterns (canonical comparators)

- `modules/expense/src/kontor/expense/core.clj:104-112` — status-history
  on creation (the pattern HR doesn't follow).
- `modules/expense/src/kontor/expense/core.clj:187-212` — status-machine
  routing through `change-status-tx-data` (the pattern HR's
  `terminate!` doesn't follow).
- `src/kontor/status_machine.clj:186-231` — `check-policy` (the
  approval-policy rule check that fires only inside
  `record-status-change-tx-data`).
- `src/kontor/dsar.clj:60-117` — `partner-attrs-registry` +
  `register-partner-attr!` pattern (the registry HR doesn't extend).
- `src/kontor/posting.clj:299-373` — `build-transaction` shape +
  composable tempid convention (the pattern
  `set-compensation-tx-data` doesn't follow for components — P2-85-3).

### Canonical decision records read

- `doc/decisions.md:7909-7956` — ADR-075.
- `doc/research/79-hr-payroll-stage-r-plan.md` (full) — the design plan.
- `doc/research/81-hr-data-model-gold-standards.md:824-1023` — §9.6 +
  §9.7 the refactor recommendations.

---

End of note 85.
