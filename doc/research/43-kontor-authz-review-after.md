# 43 — `kontor-authz` review-after (ADR-065 / 066)

**Date:** 2026-05-14
**Method:** the ADR-037 per-stage rhythm step 3 — two independent
background agents in parallel against the committed module:

1. **Code-review agent** — a line-by-line audit of the port against
   the EACL original (checked out at `../eacl`), the datahike
   adaptations, the traversal correctness, the cursor, and id
   coercion. The agent REPL-probed its findings.
2. **Completeness + integration review agent** — the wider angle: is
   the module complete enough to be useful, and does it fit kontor?

## Verdict

The port is faithful and the traversal is correct — the code-review
agent confirmed by REPL probe that every path type (direct relation,
arrow→relation, arrow→permission, self-permission) produces correct
`can?` / `lookup-*` results, cursor pagination works both ways,
multi-clause union dedup is correct, and the two dropped "dead"
functions were genuinely unreachable in EACL. **One P0** (a security
over-return, not in the auth-decision path) and **three P1s** were
found, all REPL-confirmed; **all four are fixed** in the review-fix
commit. The completeness review found no correctness bugs but a real
*consumer-readiness* gap — captured as followup tasks.

## Code-review findings — fixed in the review-fix commit

- **P0 — `read-relationships` silently over-returned on an
  unresolvable id.** `do-read-relationships` coerced `:subject/id` /
  `:resource/id`; a non-existent external id yielded `nil`, `cond->`
  left the `nil` in the filter map, and `build-query`'s dynamic
  `:in` (`cond->` on a *truthy* value — `nil` is falsy) dropped the
  filter entirely, leaving the datalog var unbound → matched **every**
  relationship. REPL-confirmed: `{:subject/id "ghost"}` returned all
  edges. EACL's `core.clj` had an explicit `assert`; the port dropped
  it. **Fix:** `coerce-id-filter` (`client.clj`) — a present-but-
  unresolvable id now **throws** `:authz/unresolvable-filter`.

- **P1 — the permission-schema cycle guard was narrower than
  ADR-066 documented.** ADR-066 flagged only `traverse-permission-
  path-reverse`'s `:self-permission` branch. But `can?`'s
  `:self-permission` branch *also* recursed with no visited-set — a
  one-line authoring typo `(Permission :account :loop {:permission
  :loop})` `StackOverflow`-ed the `can?` hot path (REPL-confirmed).
  **Fix:** `can?` now threads a `visited` set of `[subject-type
  subject-eid permission resource-type resource-eid]` triples (a 5-
  arity arity added; the 4-arity delegates), and `traverse-
  permission-path-reverse` threads a `visited` set of `[resource-type
  resource-eid permission]` keys through both its `:self-permission`
  and arrow→permission branches — exactly as `traverse-permission-
  path` already did. A cyclic schema now terminates and denies
  instead of looping.

- **P1 — the `:a`/`:z` keyword sentinel silently dropped out-of-range
  subject-types, yielding a wrong `false`.** `relation-datoms` range-
  scans `[rt rn :a]`..`[rt rn :z]`; a subject-type sorting outside
  that range (`:zebra` past `:z`, uppercase `:Account` before `:a`,
  digit-leading `:2fa-token`) was silently missed → empty paths →
  `can?` returned `false` for a *valid* relationship (REPL-confirmed).
  A silent wrong `false` is worse than a crash. **Fix:** `base/
  Relation` now **throws** `:authz/subject-type-out-of-range` at
  definition time if the subject-type does not sort within `:a`..`:z`
  — a loud authoring error instead of a silent deny. The docstring +
  the `indexed.clj` `relation-datoms` note carry the constraint. (A
  fully-robust fix — a 2-component `:authz.relation/by-resource`
  index instead of the sentinels — is a followup; the assertion
  closes the footgun.)

- **P1 — the unresolvable-id `:pre` failure was cryptic** — same
  root cause as the P0; the `coerce-id-filter` throw now gives a
  named error before `build-query` is reached.

## Code-review findings — P2, fixed where cheap

- The triplicated `entid` helper (`indexed` / `relationships` /
  `client`, byte-identical) → consolidated into `kontor.authz.util`.
- The arrow→permission branch of `traverse-permission-path-via-
  subject` was missing the `(and rid …)` nil-guard its siblings have
  → added for symmetry (cannot bite today — full-arity tuples never
  yield nil — but consistent now).
- The dead `Cursor` record in `base.clj` (never constructed; cursors
  flow as plain maps) → dropped.
- `:create` "throws on duplicate" is not enforced *within one batch*
  (the check is against the pre-tx snapshot; the `forward`
  `:db.unique/identity` tuple then merges the in-batch duplicate into
  one edge — data stays consistent, it just does not throw) →
  documented in `tx-update-relationship`'s docstring.

## Completeness + integration findings — triaged to followups

No correctness bugs; genuine consumer-readiness gaps:

1. **No usable schema-write/read API** *(highest leverage)*.
   `write-schema!` / `read-schema` both throw "ADR-066-deferred" — a
   consumer's only route to install relations/permissions is to
   hand-build `base/Relation` / `Permission` maps and `d/transact`
   them, bypassing the client. A *non-string* `write-schema!` arity
   (a vector of `Relation`/`Permission` maps) + a real `read-schema`
   (the data is already in `:authz.*` datoms) are both small and
   remove the single biggest "can a consumer use this" blocker.

2. **No schema validation.** Nothing rejects a `Permission`
   referencing an undefined relation/permission, or a schema cycle —
   a typo becomes a *silent deny-all* (`calc-permission-paths`
   soft-fails to `[]`). A `write-schema!`-time validator would
   convert the silent deny into a loud error and simultaneously
   retire the cycle-guard concern. Pairs naturally with #1.

3. **No integration example.** The central ADR-065 claim — "a
   consumer relates kontor's own entities" — is asserted, never
   demonstrated; every test entity is synthetic. A consumer (beleg)
   needs: an end-to-end test relating a real kontor entity
   (`:partner` / a consumer-defined `:user`) to an `:account` /
   `:transaction`; and ADR-065 should *state* what the silence
   implies — the kernel does **not** call authz (it stays
   dependency-free; authz is a consumer concern), consumers own
   their subject (`:user`) entity, and a kontor-native consumer
   should use the **raw-eid client** (`:object-id->ident identity`),
   not the `:authz/object-id` default.

4. **EACL-lineage surface that implies absent features.**
   `object-ref`'s 3-arity (the subject-relation / userset) has *no
   engine behind it* — `Relationship` ignores it, no
   `:authz.relationship/subject-relation` attr exists. The
   `consistency` arg on `can?` / `lookup-*` is threaded nowhere
   (datahike is single-DB-consistent); the `:authz/token` is an EACL
   ZedToken vestige. Decide: implement usersets or drop the 3-arity;
   document the consistency arg as a deliberate API-shape no-op.

5. **The permission model is union-only** — no exclusion (`-`) or
   intersection (`&`). "approver = manager − self" (can't approve
   your own expense) — a real accounting pattern — cannot be
   expressed. Framed in ADR-066 as a *parser* deferral; it is
   actually a *model* limitation. Worth an explicit known-limitation
   note.

6. **Test gaps** — most are now closed by the review-fix commit's
   regression tests (parallel-path dedup, union-of-clauses,
   arrow→relation, the cyclic-schema termination, the unresolvable-id
   rejection, out-of-range subject-type). Still open: deep (3+-hop)
   arrow chains, real multi-page pagination, `lookup-subjects` cursor
   pagination (note 41 flagged the EACL `lookup-subjects` cursor as
   leaking an un-coerced eid — the client's `coerce-cursor-out`
   handles the default mode, but a raw-eid-mode cursor still leaks).

## Outcome

`bb test` after the review-fix commit: the kontor-authz suite grew
11 → 16 tests / 54 → 70 assertions, all green; full suite still
clean. clj-kondo + cljfmt clean. The kontor-authz companion
(ADR-065 + ADR-066) is complete as an *engine*; the consumer-
readiness work (schema API + validation + the integration story) is
captured as a followup task. The deferred SpiceDB-string parser
folds into that followup.
