# Research note 32 — Stage M ADR-050 / ADR-051 / ADR-052 review-after

Independent code-review pass on the back half of Stage M (`kontor-legal`),
commits `a722b4f` / `c7e9477` / `8c28c1a`. ADR-049 (legal-hold) got its own
review (note 27 — all findings fixed and confirmed in `legal_hold.clj`). This
note reviews the other three Stage M ADRs:

- **ADR-050** — `:retention-policy/*` + `kontor.retention` (the sweeper that
  executes irreversible purges — the highest-stakes code in the stage).
- **ADR-051** — `:audit-doc/privilege` + the `kontor.audit-doc` privilege
  section.
- **ADR-052** — `:dsar-request/*` + `kontor.dsar/collect`.

Per the per-stage rhythm (ADR-037), the review hunts P0 ship-blockers, P1
drift, P2 nits, with file:line citations. The implementation is **~340 LOC
`retention.clj` + ~150 LOC privilege section + ~280 LOC `dsar.clj` + ~40 schema
attrs + 3 `install-seeds!` wired into `kontor.core/install-schema!`**. All
shipped tests pass (`bb test`, exit 0). Datahike's `:db/purge` and
`:db.purge/attribute` were REPL-probed and both route correctly through
`[:db.fn/call validate-and-apply …]` — the load-bearing ADR-050 mechanism
works.

The review is ruthless: **8 findings (1 P0, 4 P1, 3 P2)** plus a cross-check
against notes 22/23/24 and 27, a test-coverage-gap inventory, and a
forward-compat assessment.

## 1. Summary table

| ADR | P0 | P1 | P2 | Headline assessment |
|---|---|---|---|---|
| **ADR-050 retention** | 0 | 2 | 2 | **ship-ready with caveats** — the hold-blocks-expiry invariant is *structurally real* (REPL-confirmed), but the blocked exception is double-wrapped so consumers can't dispatch on `:type`; `candidate-eids` is an unbounded O(n) scan with no `:applies-to` cross-check. |
| **ADR-051 privilege** | 0 | 1 | 1 | **ship-ready** — clean composition; the only real gap is the SoD-anchor mismatch (`:create/uid` = uploader, not classifier) which the ADR acknowledges but under-rates. |
| **ADR-052 DSAR** | 1 | 1 | 0 | **needs-work** — P0: `collect` silently misses an entire class of partner data (indirect refs via `:transaction`) that note 24 §3.4 *explicitly* specified as in-scope; the ADR's "what this does NOT do" does not disclose it. |

**Cross-cutting headline:** the double-exception-wrap (P1, affects ADR-050 and
retroactively ADR-049) and the indirect-reference gap in `collect` (P0,
ADR-052) are the two findings that matter. The retention sweeper itself — the
thing the task flagged to scrutinize hardest — is **sound**: every expiry
action provably routes through the hold-middleware, REPL-confirmed for both
`:purge` and `:anonymize`. The structural guarantee holds.

**Recommendation: fix P0-1 (collect indirect refs) before Stage L′; the four
P1s ride into a Stage-M-cleanup commit or Stage L′.** Details in §9.

## 2. P0 findings

### P0-1. `collect` walks only the direct partner-attr registry — indirect partner data via `:transaction` is silently missing, contradicting note 24 §3.4

`src/kontor/dsar.clj:281-293`. `collect` iterates `@partner-attrs-registry` and
for each attr runs `[?e ?attr ?p]` — i.e. it finds entities that reference the
subject *directly*. Note 24 §3.4 ("The collect helper must walk both axes")
explicitly specified **two** axes:

> 1. Direct partner refs (the 19 attrs above).
> 2. Indirect via `:transaction` (any tx whose `:transaction/partner =
>    partner-eid` OR whose postings have `:posting/partner = partner-eid`).
>
> Plus the **implicit references** via downstream `:transaction` refs (every
> `:payment-application` walks to a `:transaction` which walks to a partner;
> every `:status-history/origin-transaction` ditto).

The shipped `collect` does walk `:transaction/partner` and `:posting/partner`
(both are in `kernel-partner-attrs`, `dsar.clj:55-56`) — so it finds the
transactions and postings themselves. But it does **not** then walk *from*
those transactions to the entities that reference them. A
`:payment-application` that points at a subject's `:transaction` (via
`:payment-application/transaction` or similar), a
`:status-history/origin-transaction` row whose tx belongs to the subject — none
of these surface in `collect`'s `:references` map. They are part of "everything
we held about this subject," and a GDPR access response that omits them is
**incomplete** — which is the exact failure mode research note 23 catalogues
($1,500/manual-DSAR cost *because* the data is scattered; an automated walk
that misses a silo is worse than a manual one because the operator trusts it).

**Why P0, not P1:** this is not a deferred-by-design gap. ADR-052's "What this
does NOT do" section (`decisions.md:5247-5261`) lists five deferrals —
auto-overdue-flagging, per-entity `:as-of-valid`, privilege side-band,
bundle-assembly, recursive merge-chain — and the indirect-`:transaction` walk
**is not among them**. So either the ADR's deferral list is incomplete (a
documentation P1) *or* the implementation dropped a specified requirement (an
implementation P0). Given note 24 §3.4 called it out twice and ADR-052's body
(`decisions.md:5223`) cites note 24 as confirming "every needed primitive is
already shipped — `collect` is a *composition*," the honest read is: the
requirement was specified, the ADR silently narrowed scope, and a consumer
DSAR portal built on `collect` will ship incomplete access responses believing
they are complete.

**Fix sketch.** After the direct-ref walk, take the eids found under
`:transaction/partner` (call them `subject-txs`) and run a second pass:

```clojure
;; after `refs` is built:
subject-txs (->> subjects
                 (mapcat (fn [p] (d/q '[:find [?t ...] :in $ ?p
                                        :where [?t :transaction/partner ?p]] db p)))
                 distinct)
;; the kernel knows :status-history/origin-transaction references a tx;
;; companions register their own tx-referencing attrs the same way the
;; partner-attrs registry works — add a `tx-attrs-registry`.
indirect (into {}
               (keep (fn [attr]
                       (let [eids (->> subject-txs
                                       (mapcat #(d/q '[:find [?e ...] :in $ ?a ?t
                                                       :where [?e ?a ?t]] db attr %))
                                       distinct)]
                         (when (seq eids) [attr (mapv #(d/pull db '[*] %) eids)]))))
               @tx-attrs-registry)
```

The kernel seeds `tx-attrs-registry` with `:status-history/origin-transaction`
(and any kernel tx-ref); companions register `:payment-application/transaction`
etc. — the *exact same registry pattern* the direct walk already uses, so the
mechanism cost is ~25 LOC and one new atom. **Alternatively**, if the team
genuinely wants v1 to ship direct-only, that is a defensible *scope* call — but
then ADR-052's "What this does NOT do" must add a sixth bullet stating it
explicitly, and `collect`'s docstring (`dsar.clj:237-271`) must say "direct
partner-attribute references only; indirect references via `:transaction` are a
documented follow-up." Right now the docstring says "everything the DB holds
about `partner-eid`" — which is false.

## 3. P1 findings

### P1-1. The `:legal-hold/purge-blocked` exception is double-wrapped — `apply-expiry!`'s `[:db.fn/call …]` routing means consumers cannot dispatch on `(:type (ex-data e))`

`src/kontor/retention.clj:312`. `apply-expiry!` ends with
`(d/transact conn [[:db.fn/call validation/validate-and-apply tx-data]])`. When
the hold-middleware throws `ex-info {:type :legal-hold/purge-blocked …}` *inside
the transactor function*, datahike re-wraps it. REPL-confirmed:

```
;; (ret/apply-expiry! conn {:entity-eid <held> :action :anonymize ...})
MSG:        clojure.lang.ExceptionInfo: Refused: destructive write blocked by active legal hold {...}
EX-DATA keys: nil          ; <-- outer ex-data is {}
CAUSE:      clojure.lang.ExceptionInfo: Refused: ... {:type :legal-hold/purge-blocked, :violations [...]}
```

The structural guarantee **works** — the anonymize was blocked, the PII field
survived — so the *invariant* is real and the task's central worry ("is there a
path where `apply-expiry!` could purge held data") is answered: no. But the
*error contract* is broken. The retention test
`legal-hold-blocks-retention-expiry` (`retention_test.clj:282-286`) only asserts
`thrown-with-msg? #"blocked by active legal hold"` — message-substring, which
matches because `.getMessage` of the outer exception includes the cause's
message. So the test passes and the bug is latent.

A consumer writing the documented error-handling pattern —
`(catch ExceptionInfo e (when (= :legal-hold/purge-blocked (:type (ex-data e))) …))`
— gets `(:type (ex-data e))` ⇒ `nil` and mis-handles the block. The remediation
text in `legal_hold.clj:382-390` is rich and consumer-facing; it is unreachable
through the normal `ex-data` path.

Note this is **not new with ADR-050** — every `validate-and-apply`-routed
throw has always been wrapped this way, and ADR-049's tests (note 27) also use
`thrown-with-msg?` exclusively, so note 27 didn't catch it either. But ADR-050
*elevates* it: `apply-expiry!` is the kernel function a consumer cron calls,
and ADR-050's docstrings (`retention.clj:20-22`, `decisions.md:4948-4950`)
promise "even a buggy caller hitting `apply-expiry!` on a held entity gets the
`:legal-hold/purge-blocked` exception" — which is only true if you read
`.getCause`.

**Fix options.** (a) In `apply-expiry!`, catch and unwrap:
`(catch ExceptionInfo e (throw (or (ex-cause-with-type e) e)))` — re-raise the
cause when it carries a kontor `:type`. (b) Have `validate-and-apply` callers
that aren't `:db.fn/call` (i.e. call the validators directly then `d/transact`
the *plain* tx-data) — `apply-expiry!` could call
`(validation/validate-and-apply (d/db conn) tx-data)` directly for the check,
then `(d/transact conn tx-data)` for the apply, mirroring
`transact-with-validation`'s split. Option (b) is the cleaner fix and matches
the existing `transact-with-validation` pattern (`validation.clj:216-225`
already does invariants-outside, structural-inside). (c) At minimum, document
in ADR-050 + the docstring that the type is on `.getCause`, and update the
retention test to assert `(:type (ex-data (.getCause e)))` so the contract is
pinned. **Recommend (b) or (c)** — (a) is fragile.

### P1-2. `candidate-eids` is an unbounded full-attribute scan with no `:applies-to` cross-check — O(n) over every entity carrying the anchor attr, and a shared `:triggered-by` across policies double-counts

`src/kontor/retention.clj:214-223`. `candidate-eids` runs `[?e ?attr _]` for the
policy's `:triggered-by` — for a policy anchored on
`:transaction/effective-date` this enumerates **every transaction in the DB**.
`due-for-expiry` (`:240-258`) then calls `retention-deadline` (two `d/pull`s)
per candidate. There is:

1. **No batching story.** The ADR's "Tradeoffs" (`decisions.md:5022-5026`) says
   "O(candidate-entities) per policy per run … a batch job on a consumer-chosen
   cadence, never on the write path" — true, but `sweep!` has no `:batch-size`
   / cursor parameter (note 24 §6 ADR-050 Q4 anticipated "each sweep emits
   intents in one tx per batch"). For a tenant with 10M transactions, one
   `sweep!` call pulls 10M eids into a Clojure seq and does 20M `d/pull`s. The
   ADR-049 review (note 27 §8) explicitly flagged "the kernel can add a
   `:scope-query/max-results` cap … throws-loud rather than silently-overruns"
   as forward-compat for exactly this sweeper. It wasn't added.

2. **No `:applies-to` filter.** `candidate-eids` finds *every* entity with the
   `:triggered-by` attr, not every entity of the policy's `:applies-to` type.
   For the common case (`:applies-to [:audit-doc]`, `:triggered-by
   :audit-doc/uploaded-at`) the attr namespace happens to coincide with the
   type so it's harmless. But the schema *explicitly* permits cross-namespace
   anchors — the `:retention-policy/triggered-by` doc string
   (`schema.clj:613-619`) names `:status-history/changed-at` as a valid anchor,
   and a policy `:applies-to [:legal-hold]` `:triggered-by
   :status-history/changed-at` would sweep **every status-history row in the
   DB** as a candidate, compute a deadline for each, and — if past — purge
   `:status-history` rows that were never meant to be in scope. There is no
   guard that the candidate eid is actually of the `:applies-to` type. Two
   policies sharing a `:triggered-by` (e.g. two `:audit-doc` policies both
   anchored on `:uploaded-at` for different `:applies-to`/jurisdiction) each
   enumerate the full set independently — `sweep!` resolves only *one* via
   `policy-for`, so the other policy's entities are silently never swept, or
   (if it's the one resolved) entities outside its `:applies-to` get expired.

**Why P1, not P0:** at Stage-M scale (one tenant, modest entity counts, l10n
ships a handful of policies with namespace-aligned anchors) neither symptom
fires. But the schema *invites* the cross-namespace anchor (the doc string
advertises it), and the first l10n module that ships a `:status-history`-
anchored policy hits the over-broad-candidate bug. **Fix:** `candidate-eids`
should additionally check the candidate's entity-type against the policy's
`:applies-to`. The kernel already has the entity-type discriminator convention
(`:status-history/entity-type`); for a kernel entity the type is inferable from
the eid's attributes. Minimally, add a `(filter #(entity-of-type? db %
applies-to))` pass. And `sweep!` should grow a `:batch-size` + cursor so a
consumer cron can chunk.

### P1-3. `supersede-policy!` has no approval-policy gate — superseding a retention policy can *expand* what the sweeper purges, with zero governance

`src/kontor/retention.clj:461-487`. `supersede-policy!` writes the `:active →
:superseded` transition through `record-status-change-tx-data`, but
`approval-policy-seeds` (`:82-97`) seeds rules **only for `:draft → :active`**.
There is no `:active → :superseded` rule. So `check-policies` returns the empty
vector and the transition is ungoverned — `supersede-policy!` requires only
`:policy-eid` and `:changed-by-uid` (`:471`), no `:supporting-doc`, no
`:reason-note` (both are *optional* per the `cond->` at `:481-484`).

This is the **exact P1-1 pattern from note 27** (ADR-049's `:pending-review →
:released` was in the status-transition seed list but had no approval-policy
seed, so a counsel-only workflow skipped all SoD). Here the consequential edge
is `:active → :superseded`. The task framing asks: "is superseding a retention
policy — which can *expand* what gets purged — consequential enough to need
governance?" **Yes.** The workflow ADR-050 documents
(`decisions.md:4935-4936`: "to 'update' a policy you ship a new row with a
later `:effective-from` and supersede the old") means superseding `P-OLD-5yr`
with `P-NEW-3yr` *shortens* retention — entities that were not yet
deadline-eligible under the 5-year policy become eligible under the 3-year one
the moment the new policy is active and the old superseded. That is a
data-destroying change of exactly the kind ADR-038 governance exists for. The
ADR-050 body (`decisions.md:4936-4938`) only says "`:draft → :active` requires
`:supporting-doc` + non-empty `:reason-note`" — it never considers the
supersede edge.

**Why P1, not P0:** superseding does not *itself* purge anything; the next
sweep does. And to reach `:superseded` the policy must first be `:active`
(which *was* governed). But the asymmetry — activation gated, supersession
free — is a latent SoD hole and note 27's P1-1 established the precedent that
"any consequential terminal edge unseeded in `approval-policy-seeds` is a P1."
**Fix:** add `:active → :superseded` rows to `approval-policy-seeds` with at
minimum `:requires-supporting-doc` + `:requires-non-empty-reason-note` (the
records-retention-schedule revision memo); arguably `:no-self-approval` too
(the person who drafted the replacement policy shouldn't unilaterally retire
the incumbent). Then make `:supporting-doc` / `:reason-note` required in
`supersede-policy!` and update the test `supersede-makes-policy-terminal`
(`retention_test.clj:339-349`), which today supersedes with neither.

### P1-4. ADR-051 waiver SoD anchors on `:create/uid` (the doc *uploader*) not the *classifier* — "creator ≠ waiver" is the wrong segregation

`src/kontor/audit_doc.clj:163-180` + `:88-93`. The waiver approval-policy seeds
`:no-self-approval` on every `<privileged> → :none` edge.
`status-machine/check-policy` (`status_machine.clj:184-192`) implements
`:no-self-approval` as "transition actor (`:changed-by-uid`) must differ from
`:create/uid` of the entity." `create-doc!` stamps `:create/uid =
:uploaded-by-uid` (`audit_doc.clj:92-93`). So the SoD enforced is **uploader ≠
waiver-actor**.

But the consequential act is *classification*, not *upload*. The person who
*determined* a doc is `:attorney-client` privileged is the one who must not be
able to unilaterally waive it (FRE 502(b) "reasonable steps"; note 23's
inadvertent-production failure mode). The uploader is often a paralegal or an
automated intake pipeline; the classifier is counsel. Under the shipped rule, a
paralegal uploads the doc, counsel classifies it `:attorney-client`, and then
**counsel can waive it alone** — because counsel ≠ uploader, `:no-self-approval`
passes. Meanwhile if counsel *also* uploaded it, an unrelated second counsel is
*blocked* from waiving even though they had nothing to do with the
classification. The check fires on the wrong axis.

ADR-051 *acknowledges* this — `decisions.md` doesn't, but `audit_doc.clj:88-91`
comment says "the doc creator can't waive its privilege alone" and the test
`waiver-requires-no-self-approval` (`audit_doc_privilege_test.clj:97-114`)
deliberately uses the paralegal (= creator) as both classifier and
waiver-attempt. So the *test* conflates creator and classifier. The task asks
whether the acknowledgment is "sufficient or a real gap." **It is a real gap,
thinly acknowledged.** The right SoD is "the actor on the most-recent
`<… → privileged>` `:status-history` row ≠ the actor on the `<privileged> →
:none>` row." The substrate already records this — every reclassification
writes `:status-history/changed-by-uid` (`reclassify-privilege!`,
`audit_doc.clj:246`). A `:no-self-approval-vs-classifier` rule variant in
`check-policy` would pull the latest privilege-facet history row's
`:changed-by-uid` instead of `:create/uid`.

**Why P1, not P0:** the shipped rule is *a* segregation — better than none, and
in the common pipeline (paralegal uploads, counsel classifies, *different*
counsel waives) it accidentally does the right thing. But it is the wrong rule
and it gives a false sense of compliance: an auditor told "waivers are
SoD-gated" will assume classifier-vs-waiver. **Fix:** either (a) add a
`:no-self-approval-vs-last-classifier` `:approval-policy/rule` and seed it on
the waiver edges instead of plain `:no-self-approval`, or (b) at minimum, ADR-
051's `decisions.md` text and the `reclassify-privilege!` docstring must state
explicitly that the SoD anchor is the *uploader*, not the classifier, so
consumers know to enforce classifier-SoD in their own layer. (a) is correct;
(b) is the floor.

## 4. P2 findings + nits

### P2-1. `policy-for` jurisdiction tiebreaker: a global policy gets the same sort-rank as a jurisdiction-match when querying with `jurisdiction = nil`

`src/kontor/retention.clj:155-163`. The filter keeps a policy if
`(or (nil? juris) (= juris jurisdiction))`; the sort key is
`[(if (= juris jurisdiction) 1 0) effective-from]`. When `collect`'s caller
passes `jurisdiction = nil` (the "global only" case), a global policy has
`juris = nil`, so `(= nil nil)` ⇒ rank 1 — *correct*, it should win. But the
filter already excluded every jurisdiction-specific policy in that case (none
match `nil`), so the rank-1-vs-rank-0 distinction is moot for `jurisdiction =
nil`. For `jurisdiction = :de`: DE policy ⇒ rank 1, global policy ⇒ rank 0, DE
wins via `(first (last …))`. Correct. **No bug** — but the `(if (= juris
jurisdiction) 1 0)` reads as if it handles a case it doesn't, and there is no
test for "two policies, same `:effective-from`, one global one jurisdictional,
query with that jurisdiction" beyond the happy path in
`jurisdiction-specific-beats-global` (`retention_test.clj:191-200`). Also
untested: **two policies with the *identical* `:effective-from`** — `sort-by`
is stable so `last` returns whichever the datalog set iterated last, i.e.
non-deterministic. The ADR-026 tiebreaker (`decisions.md:1381`, "the one with
the latest `:effective-from` not exceeding `as-of`") is silent on exact ties;
`policy-for` inherits that ambiguity. Minor — flag for a test + a tiebreaker-of-
last-resort (e.g. lowest eid) so resolution is deterministic.

### P2-2. `retention-deadline` leap-year / TZ correctness is fine; the silent-skip on non-Date anchor is correct but undocumented at the call site

`src/kontor/retention.clj:170-194`. `plus-years` round-trips through
`LocalDate.plusYears` at `ZoneOffset/UTC` — `java.time` handles Feb-29 → Feb-28
correctly, and the UTC round-trip is consistent with how `:audit-doc/uploaded-
at` etc. are stored (`#inst` literals are UTC). The test
`deadline-and-eligibility` (`retention_test.clj:202-215`) confirms `2018-01-01
+ 7y = 2025-01-01`. Good. The `(when (instance? Date anchor) …)` guard
(`:193`) means an entity whose `:triggered-by` value is *not* a Date (or is
absent) returns `nil` ⇒ the entity is skipped by `due-for-expiry`'s `keep`
(`:246-247`). The task asks: "confirm that's the intended fail-safe, not a
silent data-loss skip." It **is** the intended fail-safe — skipping (not
expiring) is the safe direction, and ADR-050 (`decisions.md:4987-4992`) says so
("the entity is simply skipped if it lacks the anchor attribute"). But
`due-for-expiry`'s docstring doesn't mention it and `sweep!` returns no
"skipped-N-entities-for-missing-anchor" diagnostic — an operator running a
sweep that silently skips half the candidates (because a bulk import left
`:uploaded-at` null) gets no signal. P2: add a `:skipped` count to the
`sweep!` / `sweep-and-apply!` return shape, or at least document the skip in
the docstring.

### P2-3. `holds-covering` (dsar.clj) re-implements scope-checking instead of using `legal-hold/entities-held?`

`src/kontor/dsar.clj:224-235`. `holds-covering` filters `legal-hold/active-
holds` by, for each hold, running an inline `[?h :legal-hold/scope-eids ?e]`
query *plus* `(contains? (legal-hold/expand-scope-query db hold-eid) partner-
eid)`. This is correct, but it **duplicates logic that `legal-hold` already
owns** — `scoped-eids-by-hold` (`legal_hold.clj:258-273`) computes exactly
"explicit scope-eids ∪ scope-query expansion" per hold, and the ADR-049 review
(note 27 §8) specifically added the batched `entities-held?`
(`legal_hold.clj:275-286`) "as ADR-050 forward-compat." `holds-covering`
re-runs `expand-scope-query` once per active hold inside `filterv` — the same
per-hold cost `scoped-eids-by-hold` already pays — so it's not a *perf*
regression, but it is a **maintenance hazard**: if `legal-hold`'s scope
semantics change (e.g. a future `:scope-query-as-of` refinement), `holds-
covering` silently drifts. It also returns hold *eids* but `collect` wants
"which holds cover the subject" — `legal-hold` could expose a `holds-covering-
eid db eid → [hold-eid …]` that `dsar` calls, keeping scope logic in one place.
**Fix:** add `legal-hold/holds-covering` (the inverse of `entities-held?` —
"which holds, not which eids") and have `dsar/collect` call it. Not a
correctness bug today; cite note 27's explicit "compose, don't re-implement"
intent.

### Nits (no severity)

- **`merged-from-partners` is one level only** — task asks if this is a
  documented deferral or P1. It **is** documented: ADR-052's "What this does
  NOT do" (`decisions.md:5259-5261`) explicitly says "No recursive merge-chain
  walk — `collect` walks one level." `collect`'s docstring also says "one
  level" (`dsar.clj:246`). Correctly disclosed. Not a finding. (Contrast P0-1,
  where the indirect-ref gap is *not* disclosed — that's the difference between
  a deferral and a P0.)
- **`advance-state!` `cond->` side-effect-attr logic is correct.**
  `dsar.clj:393-401`: `:identity-verified-at` is stamped only on
  `(and (= to :in-progress) (= from :verifying-identity))` — correct, the
  `:extended → :in-progress` edge does *not* stamp it (right, identity was
  verified earlier). `:fulfilled-at` is stamped on `(= to :fulfilled)` from
  *either* `:in-progress` or `:awaiting-legal-review` — both are legal
  `→ :fulfilled` edges per the status-transition seeds (`dsar.clj:124,132`), so
  there's no path that reaches `:fulfilled` where `:fulfilled-at` shouldn't be
  stamped. `:fulfilled-package` / `:denied-reason` ride along whenever the
  caller supplies them regardless of target state — slightly loose (a caller
  *could* pass `:denied-reason` on a `→ :fulfilled` call and it'd be stored)
  but harmless and the docstring scopes them to the right transitions. Fine.
- **`create-doc!` stamping `:create/uid` does not break existing callers.**
  `:create/uid` is a global cardinality-one ref attr (`schema.clj:42-47`), not
  namespaced — stamping it on an `:audit-doc` or `:dsar-request` is schema-
  valid (datahike attrs are global; the `:audit-doc/*` namespacing convention
  is about *cohabitation*, ADR-002, not a per-entity allowlist). The
  `uploaded-by-uid (assoc … :create/uid uploaded-by-uid)` change
  (`audit_doc.clj:92-93`) only adds a datom when `:uploaded-by-uid` is supplied
  — callers that omit it are unaffected. `bb test` passes, confirming no
  existing audit-doc test broke. Same for `file-request!` stamping `:create/uid`
  (`dsar.clj:349`). Fine.
- **`status-transition-seeds` complete-graph generator (ADR-051) is correct.**
  `audit_doc.clj:152-161`: `(for [from privilege-vocab to privilege-vocab :when
  (not= from to)] …)` over a 7-value vocab ⇒ 7×6 = 42 rows. Confirmed. The
  `record-status-change-tx-data` `:from` handling — `reclassify-privilege!`
  passes `:from (privilege-of db doc-eid)` which normalizes nil ⇒ `:none`
  (`audit_doc.clj:199-205, 237`), and the complete graph includes every
  `:none → X` edge, so the first classification of a nil-privilege doc resolves
  `:none → :attorney-client` and finds the seed. Correct.
- **`install-seeds!` presence-guard idempotency** — all three
  (`retention.clj:99-113`, `dsar.clj:164-177`, `audit_doc.clj:182-197`) use the
  same `(d/q … [?e :status-transition/entity-type <type>] …)` presence check,
  matching `legal-hold/install-seeds!` (`legal_hold.clj:178-195`). Correct and
  consistent. The privilege one additionally filters on `:facet
  :audit-doc/privilege` (`audit_doc.clj:192-193`) — necessary because
  `:audit-doc` might later carry *other* status-facets, so a bare
  `entity-type :audit-doc` check would false-positive. Good catch by the
  implementer. No race: `install-schema!` is single-threaded
  (`core.clj:78-90`).
- **`visible-to?` / `filter-by-privilege` handle consumer-extension vocab
  correctly.** `audit_doc.clj:255-282`: both are generic set-membership over
  `viewer-privilege` — a privilege value not in the starter `privilege-vocab`
  (a consumer's `:hipaa-phi`) works fine: `:none`/nil ⇒ visible to all,
  anything else ⇒ visible iff in the viewer set. The kernel-tags-consumer-
  enforces boundary is clean. Confirmed.
- **`privilege-value-at-is-bitemporal` test is thin.**
  `audit_doc_privilege_test.clj:191-204` classifies *once* (`:none →
  :attorney-client` at `:vt-from #inst "2026-05-14"`) and then `value-at` at
  `2026-05-20` ⇒ `:attorney-client`. It does **not** exercise a *changed*
  classification — classify, reclassify, then `value-at` at a cutoff *between*
  the two — which is the actual discovery question ADR-051 sells
  (`decisions.md:5092-5098`, "a document upgraded from `:none` to `:attorney-
  client` after counsel review is correctly discoverable in both states"). The
  test as written would pass even if `value-at` ignored valid-time entirely
  and just read current state. See §8 test gaps.

## 5. Cross-check against research notes 22 / 23 / 24

### ADR-050 (retention)

**Adopted.** Kernel-ships-shape-not-data (note 24 design-call #2, ADR-050 Q1) —
`retention.clj` ships zero policies; l10n companions seed them. ✓ Effective-
dated `[code, effective-from]` identity tuple (note 24 §6 ADR-050 Q2) —
`schema.clj:679-687`. ✓ `:anonymize` via per-field `:db.purge/attribute` (note
24 §6 ADR-050 Q6) — `retention.clj:303-304`, REPL-confirmed the op exists and
works (note 27 P0-1 had warned `:db/purgeAttribute` does *not* exist; ADR-050
correctly uses the namespaced `:db.purge/attribute`). ✓ Kernel-ships-sweeper-
consumer-schedules (note 24 §3.2) — no scheduler in `retention.clj`, `sweep-
and-apply!` is the consumer-cron entry point. ✓ Hold-blocks-expiry is
*structural* not soft (note 24 §3.2, note 27 forward-compat) — confirmed:
`apply-expiry!` routes through `validate-and-apply`, `eligible?` *also* checks
`entity-held?` as the documented optimization/visibility layer. ✓

**Diverged / under-delivered.** Note 24 §6 ADR-050 Q4 ("Sweeper transactional
semantics? → Each sweep emits intents in one tx per batch") — the implementation
does **not** use `:side-effect-intent` at all; `apply-expiry!` transacts
directly, one tx per work-item. That's arguably *cleaner* (the intent-queue
indirection the note sketched added a worker round-trip ADR-050 v1 doesn't
need) — but it's a divergence the ADR doesn't call out. Note 24 §3.2's sketch
had `sweep!` *emit intents* and `apply-action!` *drain* them; the shipped
`sweep!`/`apply-expiry!` split is synchronous. **Acceptable divergence, should
be noted in the ADR.** Note 24 §1.8 (`:retention-policy/sweep-schedule` ref to
`:schedule`) was dropped entirely — also fine (consumer owns cadence) but
undisclosed. Note 27 §8 forward-compat asked for a batched candidate cap —
**not delivered** (see P1-2).

### ADR-051 (privilege)

**Adopted.** Flat keyword vocab over W3C DPV tree (note 22 reference study, note
24 §3.3) — `privilege-vocab` is a 7-keyword flat list. ✓ Open-set, consumer-
extends (note 24 §6 ADR-051 Q1) — `audit_doc.clj:132-138` docstring says so;
`visible-to?` confirmed generic. ✓ `nil ≡ :none` (note 24 §6 Q2) —
`privilege-of` normalizes (`audit_doc.clj:199-205`). ✓ `visible-to?(db,
doc-eid, viewer-privilege-set)` — *not* `uri-for(doc, requesting-uid)` (note 24
§7 item 4, the explicit push-back on note 17) — `audit_doc.clj:255-270` takes a
privilege *set*, never a uid. ✓ Privilege changes go through
`record-status-change!` (note 24 §6 Q4) — `reclassify-privilege!` does.
✓ Waiver edges approval-gated (note 24 §3.3, §6 Q4) — seeded, *but* see P1-4 for
the SoD-anchor mismatch.

**Diverged.** Note 24 §3.3 sketched the facet name as `:audit-doc/privilege-
state` (a separate status-facet from the data attr); the implementation uses
`:audit-doc/privilege` as *both* the facet and the stored value
(`schema.clj:3453`, `audit_doc.clj:156`). This is actually **better** — one
attr, the status-machine writes the facet *and* the value in the same
`[:db/add entity facet to]` (`status_machine.clj:303`), no denorm drift. Worth
a one-line ADR note that the sketch's two-attr split was collapsed. Note 24
§6 ADR-051 Q5 ("DSAR vs privilege precedence? → Privilege wins. Privileged docs
surface separately for legal review") is correctly *deferred* to ADR-052 (which
defers the side-band itself — see below).

### ADR-052 (DSAR)

**Adopted.** Companion-registered partner-attrs registry (note 24 §3.4, the
"hard problem") — `partner-attrs-registry` atom + `register-partner-attr!`,
seeded with 9 kernel attrs. ✓ Cross-checked the 9 against note 24 §3.4's
inventory: `:transaction/partner`, `:posting/partner`, `:invoice/buyer`,
`:invoice/seller`, `:partner-bank-account/partner`, `:partner-tax-id/partner`,
`:partner-tag/partner`, `:partner-merge/duplicate-of`,
`:partner-merge/superseded` — **all 9 kernel-level partner-refs from the table
are present**; the companion attrs (`:person/partner`, `:org/partner`,
`:collection-case/partner`, `:order/bill-to-partner`, etc.) are correctly *not*
seeded (companions register them). One inventory note: note 24 §3.4's table
also lists `:partner-contact-mech/partner` and `:partner-relationship/partner-
from`/`-to` as `modules/partner` attrs — correctly companion-registered, not
kernel. **The kernel registry is complete for kernel attrs.** ✓ Companion-
agnostic return structure (note 24, ADR-052 body) — `{:partner :merged-from
:references :legal-holds :on-legal-hold?}`, `:references` keyed by attr. ✓
Bitemporal `d/as-of` snapshot on `:as-of-tx` (note 24 §3.4, ADR-052 body) —
`collect` does `(if as-of-tx (d/as-of db as-of-tx) db)` *once* at the top
(`dsar.clj:274`) and every sub-query (`merged-from-partners`, the ref walk,
`holds-covering`) uses that rebound `db` — **traced, no path queries the live
db**. ✓ One-level merge-chain (note 24 — and ADR-052 discloses the deferral).
✓ Status machine + approval-policy on `:fulfilled`/`:denied` edges (note 24
§3.4) — seeded. ✓

**Diverged / under-delivered.** The indirect-`:transaction` walk (note 24 §3.4,
specified *twice*) — **dropped, undisclosed** — this is P0-1. Note 24 §3.4's
privilege side-band (`:audit-docs-privileged`) — *deferred*, and ADR-052
**does** disclose this one (`decisions.md:5253-5255`) — acceptable. The per-
entity `:as-of-valid` filter (note 24 §3.4 sketched it) — deferred and
disclosed (`decisions.md:5251-5252`). ✓ Auto-overdue-flagging (note 24 §3.4) —
deferred and disclosed. ✓

## 6. Cross-check against the ADR-049 review (note 27)

**Did 050/052 inherit the *fixed* legal-hold API correctly?** Mostly yes:

- **`entities-held?` batched (note 27 forward-compat ask).** `retention/due-
  for-expiry` (`retention.clj:254`) calls `legal-hold/entities-held?` with the
  *whole* past-deadline batch — exactly the batched form note 27 added "as
  ADR-050 forward-compat." ✓ `eligible?` (`retention.clj:208`) calls the
  single-eid `entity-held?` — fine, `eligible?` is a single-entity predicate.
- **`assert-no-hold-violating-destructive-writes!` (note 27 P0-1 fix).**
  `apply-expiry!` routes both `:purge` ([:db/purge]) and `:anonymize`
  ([:db.purge/attribute …]) through `validate-and-apply`, and `legal-hold`'s
  `destructive-ops` / `destructive-attr-ops` sets (`legal_hold.clj:293-302`)
  now include `:db/purge` *and* `:db.purge/attribute` — so both expiry actions
  are caught. REPL-confirmed: `:anonymize` on a held doc throws `:legal-hold/
  purge-blocked` and the field survives. ✓ This is the single most important
  thing to get right in Stage M and it is correct.
- **`holds-covering` (dsar) did *not* reuse the note-27 batched API.** P2-3 —
  `dsar.clj:224-235` re-implements scope-checking inline instead of calling
  `legal-hold`. Note 27 §8 explicitly said ADR-052's DSAR walker should
  *consume* `legal-hold` predicates; `holds-covering` consumes `active-holds`
  and `expand-scope-query` but not the cohesive `entities-held?`/`scoped-eids-
  by-hold` logic. Minor drift, flagged.

**Did any note-27 finding recur?** Yes — **note 27 P1-1 recurs as this note's
P1-3.** Note 27 P1-1: "`:pending-review → :released` is in the status-
transition seed list but has no approval-policy seed → ungoverned consequential
edge." This note's P1-3: ADR-050's `:active → :superseded` is in the status-
transition seed list (`retention.clj:75-80`) but has no approval-policy seed →
ungoverned consequential edge. **Same class of bug, one ADR later.** The lesson
from note 27 ("any consequential terminal edge unseeded in `approval-policy-
seeds` is a P1") was not internalized into ADR-050. ADR-052's `:withdrawn` and
`:extended` edges are *also* unseeded — but those are non-consequential
(withdrawing or extending a DSAR destroys nothing), so they're correctly
ungoverned. The `:fulfilled`/`:denied` edges *are* seeded. So ADR-052 got the
pattern right; ADR-050 missed the supersede edge.

Note 27 P1-3 (`:legal-hold/placed-at` denorm) — ADR-050 was the commit note 27
suggested should "revisit these patterns." `retention.clj` has **no `placed-
at`-style denorm** on `:retention-policy` — the policy's effective dates are
`:effective-from`/`:effective-until` (legitimate ADR-026 data, not status
denorms) and lifecycle timing lives in `:status-history`. ✓ ADR-050 did not
re-introduce the anti-pattern. (Whether ADR-050 *also removed* `:legal-hold/
placed-at` is out of this review's scope — note 27 left it as a follow-up; a
grep shows `:legal-hold/placed-at` is no longer in `schema.clj`'s legal-hold
block at `:457-566`, so it appears to have been dropped — good, but verify
separately.)

## 7. Test-coverage gaps — prioritized

| Pri | Gap | Landing spot |
|---|---|---|
| **P0** | `collect` indirect-ref: a `:status-history/origin-transaction` row (or a companion `:payment-application`) pointing at a subject's `:transaction` is **not** in `collect`'s output. Test should assert it *is* (drives the P0-1 fix). | `test/kontor/dsar_test.clj` — new deftest after `collect-returns-referencing-entities:109` |
| **P1** | `:legal-hold/purge-blocked` type-on-cause: assert `(:type (ex-data (.getCause e)))` (or, post-fix, `(:type (ex-data e))`) in the hold-blocks-expiry test — pins the P1-1 contract. | `retention_test.clj:282-286` — extend the existing `is` |
| **P1** | `supersede-policy!` ungoverned: a supersede with no `:supporting-doc`/`:reason-note` currently *succeeds* — test should assert it's *rejected* once P1-3 is fixed. | `retention_test.clj` — extend `supersede-makes-policy-terminal:339` |
| **P1** | `candidate-eids` over-broad: a policy `:applies-to [:audit-doc]` `:triggered-by :status-history/changed-at` should **not** sweep `:status-history` rows. No test exercises a cross-namespace anchor at all. | `retention_test.clj` — new deftest |
| **P1** | privilege `value-at` on a *changed* classification: classify `:none→:attorney-client` at vt=T1, reclassify `:attorney-client→:work-product` at vt=T2, assert `value-at` at a cutoff between T1 and T2 returns `:attorney-client` (the current test would pass even if `value-at` ignored vt — see §4 last nit). | `audit_doc_privilege_test.clj:191-204` — replace/extend |
| **P2** | retention `:as-of` exactly = `:effective-until`: `in-effect?` is half-open `[from, until)` — a policy with `:effective-until = #inst "2025-01-01"` should be *out* of effect at `as-of = #inst "2025-01-01"`. `effective-dating-picks-vintage-policy` tests `2026-06-01` (well inside) and `2010-06-01` (well inside the old window), never the boundary. | `retention_test.clj:172-189` — add boundary asserts |
| **P2** | two policies identical `:effective-from` — `policy-for` resolution is non-deterministic (P2-1). Add a test + a deterministic last-resort tiebreaker. | `retention_test.clj` — new deftest |
| **P2** | `:draft` / `:superseded` policy ignored by `policy-for` — `supersede-makes-policy-terminal` covers `:superseded`, but no test confirms a `:draft` policy is *not* resolved (the `:retention-policy/state :active` clause in `policy-for`'s query should exclude it — confirm). | `retention_test.clj` — extend `policy-for-resolves-active-policy:164` |
| **P2** | `collect` on a held subject for an *erasure* request — note 27 §8 explicitly asked for "DSAR-read against held data succeeds." `collect-reports-legal-holds` confirms `:on-legal-hold?` but never asserts the held entity's *content* is still in `:references` (i.e. held data appears in the access response). | `dsar_test.clj:146-167` — extend |
| **P2** | `sweep!` partial-failure / re-entrancy: if `apply-expiry!` throws mid-batch in `sweep-and-apply!`, the `:applied` vec is whatever completed before the throw; the next sweep re-picks the rest (purged entities are gone so no double-purge). Untested. Task asked to confirm idempotency — it's *probably* safe (purge is idempotent-by-absence) but a test pins it. | `retention_test.clj` — new deftest |

## 8. Forward-compat

### For Stage L′ (`:ledger` + `kontor.closing`)

Stage L′ adds `:asset` / `:asset-depreciation` entities (notes 28/29/31). Two
touch-points:

1. **Retention `:applies-to` will grow.** An l10n module shipping a
   `:asset`-retention policy (HGB §257 covers Anlagenbuchhaltung) needs
   `:applies-to [:asset]` `:triggered-by :asset/disposal-date` or similar. P1-2
   (the missing `:applies-to` cross-check in `candidate-eids`) becomes *more*
   likely to bite once non-namespace-aligned anchors are in play — fix P1-2
   before Stage L′ ships asset-retention.
2. **`collect` and assets.** A subject's `:asset` rows (a sole proprietor's
   equipment) are partner data. Stage L′'s `:asset/owner-partner` (or whatever
   the ref is named) must `register-partner-attr!` — and if asset events
   reference `:transaction`, the *indirect* walk (P0-1) must exist by then or
   asset depreciation history won't appear in DSAR responses.

### For consumer apps

- **`collect` API stability** — the return-map shape (`:partner :merged-from
  :references :legal-holds :on-legal-hold?`) is clean and companion-agnostic;
  adding `:indirect-references` (P0-1 fix) is *additive*, won't break a
  consumer that already destructures `:references`. Stable enough to build a
  DSAR portal on **once P0-1 lands** — before that, portals built on it ship
  incomplete responses.
- **`sweep-and-apply!` API for consumer crons** — the `{:applied :blocked
  :would-apply}` shape is good; the `:dry-run?` flag is the right ergonomics.
  Missing: a `:batch-size`/cursor (P1-2) and a `:skipped` diagnostic (P2-2). A
  consumer cron at scale will want both. The `:as-of` parameter is well-
  documented and the dry-run-then-apply split is the correct safety pattern for
  irreversible purges. **The exception-unwrap (P1-1) is the one thing a consumer
  cron's error handler will trip over** — fix it or document it loudly.
- **`reclassify-privilege!` / `visible-to?` / `filter-by-privilege`** — stable,
  pure, well-scoped. The SoD-anchor issue (P1-4) is a semantics gap, not an API-
  shape gap; the signatures don't change when it's fixed (a new
  `:approval-policy/rule` value is additive).

## 9. Recommendation

**Proceed to Stage L′ after fixing P0-1.** Triage:

- **P0-1 (collect indirect refs)** — *fix before Stage L′*. It's a dropped
  requirement, not a deferral; either implement the second-axis walk (~25 LOC +
  one registry atom, the mechanism already exists) or — if v1 ships direct-only
  by deliberate choice — add the sixth "What this does NOT do" bullet to ADR-052
  and correct `collect`'s docstring. The honest-disclosure version is the
  *minimum*; the implementation version is correct.
- **P1-1 (exception double-wrap)** — *fix in the Stage-M-cleanup commit*. Prefer
  the option-(b) split (validate-then-transact-plain, mirroring `transact-with-
  validation`). Affects ADR-049 retroactively but the fix lives in `retention/
  apply-expiry!`.
- **P1-3 (supersede ungoverned)** — *fix in the cleanup commit*. It's a
  three-row addition to `approval-policy-seeds` + making two opts required +
  one test edit. It's the *exact recurrence* of note 27 P1-1; closing it also
  closes the "did the team internalize note 27's lesson" question.
- **P1-2 (candidate scan)** and **P1-4 (privilege SoD anchor)** — *can ride into
  Stage L′* but P1-2 should land *before* Stage L′ ships any non-namespace-
  aligned retention anchor. P1-4's floor (document the anchor is the uploader)
  costs one sentence; the real fix (classifier-SoD rule) is a clean additive
  change.
- **P2s** — polish; fold into the cleanup commit opportunistically.

The retention sweeper — the highest-stakes code in Stage M — is **sound**. The
hold-blocks-expiry invariant is structural and REPL-verified for both expiry
actions. The implementation quality is high; the findings are the kind a
first-commit review surfaces, and three of the eight (P0-1, P1-2, P1-3) were
*predicted* by the research notes (24 §3.4 two-axis walk, 27 §8 batched cap, 27
P1-1 the unseeded-edge pattern) — the notes earned their cost; the gaps are
where the notes also pointed.

## Sources

- Implementation (commits `a722b4f` / `c7e9477` / `8c28c1a`):
  `src/kontor/retention.clj:1-488`, `src/kontor/dsar.clj:1-416`,
  `src/kontor/audit_doc.clj:1-283` (privilege section `:129-283`),
  `src/kontor/schema.clj:572-687` (retention), `:693-791` (dsar),
  `:3453-3458` (`:audit-doc/privilege`), `src/kontor/core.clj:78-90`.
- Tests: `test/kontor/retention_test.clj:1-350`,
  `test/kontor/dsar_test.clj:1-282`,
  `test/kontor/audit_doc_privilege_test.clj:1-205`. `bb test` exit 0.
- ADR text: `doc/decisions.md:4901-5034` (ADR-050), `:5038-5137` (ADR-051),
  `:5141-5263` (ADR-052).
- Substrate: `src/kontor/validation.clj:167-205` (`validate-and-apply`),
  `src/kontor/legal_hold.clj:247-391` (active-holds / scoped-eids-by-hold /
  entities-held? / destructive-targets / assert-no-hold-violating-…),
  `src/kontor/status_machine.clj:131-304` (approval-policy + record-status-
  change-tx-data), `src/kontor/bitemporal.clj:116-254` (with-vt / value-at),
  `src/kontor/sealing.clj:1-90`.
- Research inputs: notes 22/23/24 (Stage M research-before), note 27 (ADR-049
  review-after). `doc/conventions.md` (transactor-opts shape).
- REPL verification: `:db/purge` and `:db.purge/attribute` both route correctly
  through `[:db.fn/call validate-and-apply …]`; `:anonymize` on a held entity
  throws `:legal-hold/purge-blocked` (on `.getCause`) and the PII field
  survives — the structural guarantee confirmed.

Date: 2026-05-14. Single-agent review-after for ADR-050 / 051 / 052.
Verification: high — every code claim cites `src`/`test` file:line; the
hold-blocks-expiry invariant and datahike purge-op behavior were REPL-probed;
every research-note and note-27 claim cites note + section.
