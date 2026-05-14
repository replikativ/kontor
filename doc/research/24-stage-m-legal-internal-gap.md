# Research note 24 — Stage M (kontor-legal) internal substrate audit

Inside-view gap analysis for the four Stage M kernel artifacts:
`:legal-hold/*`, `:retention-policy/*`, `:audit-doc/privilege`,
`:dsar-request/*`. Question: against what kontor already has (after
ADRs 007, 011, 014, 026, 034, 038, 041, 043, 048), what is genuinely
new schema vs. what is composition over existing primitives?

This note is brutally honest: kontor's substrate covers more of Stage
M than note 17 §3 acknowledged. Several of the entities Agent B
sketched there reduce to one attribute, a small helper, or
nothing-new-at-all. The handful that genuinely need new shape are
identified with proposed schema, status-machine seeds, and
composition rules.

Design calls already settled (do not relitigate; this note builds
on them):
1. Legal-hold scope = hybrid (explicit eid set OR datalog scope-query).
2. Retention defaults live in l10n companions, not kernel.
3. DSAR is in scope.
4. No e-sign, no redlining, no cap-table, no CLM in this stage.

## 1. Substrate audit — what kontor already gives us

### 1.1 The sealing + purge story (ADR-007 + `kontor.sealing`)

The most consequential existing primitive for Stage M is
`kontor.sealing/assert-no-silent-retracts!`
(`src/kontor/sealing.clj:56-77`). It runs from
`validate-and-apply` (`src/kontor/validation.clj:177-183`) which is
invoked both for Clojure callers (via `transact-with-validation`,
`src/kontor/validation.clj:202-220`) and for pg-datahike SQL
callers (via `pg-tx-wrap`, `src/kontor/validation.clj:185-200`).
That is the single chokepoint Stage M's legal-hold middleware
extends.

Today the chokepoint sees `:db/retract` and rejects them when the
entity has `:posting/posted-at` set
(`src/kontor/sealing.clj:41-42`, `:50-54`). It does NOT today see
`:db/purge` — note ADR-007's explicit decision: "Application
middleware refuses silent retract (`[:db/retract …]`) of any datom
on a posted entity. An explicit purge (`[:db/purge …]`) of a
posted entity is permitted." That permissiveness is exactly what
legal-hold needs to override, but it is also exactly what makes
GDPR right-to-erasure work. The hold extension cannot simply
"refuse all purge of posted entities"; it must refuse purge of
entities **scoped by an open hold**.

The implementation surface for that extension is small:
1. A new predicate alongside `find-silent-retracts` — call it
   `find-hold-violating-purges`.
2. A new assertion fn in `kontor.sealing` (or a new
   `kontor.legal-hold` namespace) that mirrors the shape of
   `assert-no-silent-retracts!`.
3. One additional line in `validate-and-apply` to call it.

The extension can stay in its own namespace (`kontor.legal-hold`)
and `validation.clj` just adds one require + one call — no change
to `kontor.sealing`'s existing API.

### 1.2 The status machine + approval policy (ADR-034, ADR-038)

`kontor.status-machine` (`src/kontor/status_machine.clj:127-247`)
already does everything Stage M needs for hold-state transitions
and DSAR-state transitions:

- `legal-transition?` (`:44-86`) gates transitions against
  declarative `:status-transition` rows. Per-tenant + per-org
  scope already handled (`:53-86`).
- `applicable-policies` + `check-policies` (`:131-247`) enforce
  approval policy rules: `:no-self-approval`,
  `:requires-supporting-doc`,
  `:requires-non-empty-reason-note`,
  `:requires-three-way-match-pass`. Adding new rules is a `case`
  branch in `check-policy` (`:178-223`) — open-set per ADR-038's
  "vocabulary discipline" section.
- `record-status-change-tx-data` (`:253-304`) composes the facet
  update + a `:status-history` row + the approval-policy check
  in one tx. Status changes are atomic with the rest of the
  business tx (per the `kontor.payment-application` pattern).
- `sweep-time-based!` (`:387-422`) auto-fires transitions whose
  `:status-transition/auto-after-millis` has elapsed since the
  entity entered its from-state. This is exactly the mechanism
  for DSAR statutory-deadline tracking (e.g. auto-flag a DSAR at
  T-7 days from `:dsar-request/deadline`) and for legal-hold
  expiry (`:legal-hold/expires-at` → auto-release).

For Stage M, this means: **every state-bearing entity we add gets
state-machine integration for free.** All we ship is the
`:status-transition` seed list per entity. The Stage L
collections companion (`modules/collections/...`) already does
this — Stage M follows the same pattern.

### 1.3 Audit-doc + supporting-doc (ADR-038)

`:audit-doc` (`src/kontor/schema.clj:3062-3088` schema definition,
`kontor.audit-doc` helpers at
`src/kontor/audit_doc.clj`) already covers:

- Content-addressed integrity (`:audit-doc/content-hash`,
  `kontor.audit-doc/sha-256`, `src/kontor/audit_doc.clj:97-105`).
- Opaque storage URI (`:audit-doc/storage-uri`).
- Free-form type discriminator (`:audit-doc/type` keyword) —
  consumers extend the vocabulary.
- Attach-after-the-fact (`attach-supporting-doc!`,
  `src/kontor/audit_doc.clj:75-91`) for `:status-history` rows.

Every Stage M decision (hold placement, hold release,
retention-policy creation, DSAR fulfillment, privilege-tag change)
is a status transition (or schema write) that hangs a
`:status-history/supporting-doc` ref onto an `:audit-doc`. The
`:approval-policy` rule `:requires-supporting-doc` enforces that
the auditor can always answer "where's the preservation order
PDF?"

The one Stage M addition is the privilege attribute — discussed
in §3.3. The rest of `:audit-doc` is reused as-is.

### 1.4 Bitemporal queries (ADR-008, ADR-048, `kontor.bitemporal`)

`kontor.bitemporal/value-at` (`src/kontor/bitemporal.clj:249-254`)
+ `values-between` (`:256-289`) + `timeline` (`:291-297`) +
`as-of-bitemporal` (`:303-318`) give Stage M everything it needs
for the four queries an auditor or DSAR-fulfiller asks:

- "What was the hold scope at the date of the subpoena?" →
  `value-at db hold-eid :legal-hold/scope-query subpoena-date`.
- "What was the retention policy in force for this record type as
  of the deletion event?" →
  `value-at db policy-eid :retention-policy/duration-years
  deletion-date`. (Critical because statutes change — see
  ADR-026's GST 2.0 precedent.)
- "What was the partner's consent state at the moment of the
  marketing send?" → `value-at db partner-eid :partner/consent
  send-instant`. (Note 17 §5 design call #9 — the simple shape
  works.)
- "What did the privilege tag look like at filing time?" →
  `value-at db doc-eid :audit-doc/privilege filing-date`.

All of these compose with `(d/as-of db past-tx)` for full
bitemporal lattice queries — "what did we KNOW about the privilege
tag at filing time, as of the auditor's spreadsheet date." Stage M
does not need a new bitemporal mechanism; it reuses the four
existing functions on new attrs.

### 1.5 Period locking — orthogonal but composes

`kontor.period` (`src/kontor/period.clj:139-197`) prevents writes
into sealed periods. This is orthogonal to legal-hold (which is
horizontal across periods) but it composes: a hold prevents purge,
a sealed period prevents write/retract. The two never conflict
because they cover different operations on different time-slices.
Note that **`:period/sealed-at` is monotone irrevocable**
(`:282-304`, `:349-388`) — a precedent for the irrevocability
guarantee Stage M needs on hold-placed (you cannot uncreate a
hold; you can only release it).

### 1.6 Master-data substrate (ADR-039, `kontor-partner` companion)

`:partner-merge` (`src/kontor/schema.clj:458-501`) already exists
as a non-destructive duplicate-of marker — relevant to DSAR
because "the subject has been merged" affects what records appear
in the DSAR bundle. The `kontor.dsar/collect` helper sketched in
§3.4 below walks the merge chain.

### 1.7 Side-effect intents (ADR-041)

`:side-effect-intent` + `kontor.side-effect` namespace
(`src/kontor/side_effect.clj`) provide an idempotent worker queue.
Stage M uses this for the DSAR-fulfillment pipeline ("compile the
bundle, upload to portal, email the subject") and for the
retention sweeper's expiry-action emission ("purge this row OR
anonymize OR archive to cold storage" — each is an intent).

### 1.8 Schedule (ADR-032)

`:schedule` + `kontor.schedule` answer recurring-occurrence
modeling. Useful for **retention sweeper scheduling**: a
`:retention-policy` can carry a `:retention-policy/sweep-schedule`
ref pointing at a `:schedule` that says "check daily" or "check
quarterly." Consumers tick the schedule; the kernel runs the
sweep.

### 1.9 Effective-dating (ADR-026)

`:tax/effective-from` + `:tax/effective-until` is the precedent
pattern for "this rule was the rule for transactions effective in
this window." Note that this pattern is *valid-time* (when the
rate legally applied), not *tx-time* (when we recorded the rate
in the DB). The same pattern applies verbatim to
`:retention-policy/effective-from` + `:retention-policy/effective-until`
— a 2024-vintage record gets the 2024 retention rule, even if the
sweeper runs in 2030.

## 2. Capability gap table

|                          | Natively composes (no new schema) | Needs new schema/entity | Needs new helper(s) | Needs new ADR |
|---|---|---|---|---|
| **:legal-hold**          | sealing chokepoint, status-machine, approval-policy, audit-doc, bitemporal, side-effect-intent | 1 entity (8 attrs), 1 status-transition seed list, 1 approval-policy seed | `kontor.legal-hold/find-hold-violating-purges`, `place!`, `release!`, `expand-scope`, `entity-held?`; extension to `validate-and-apply` | **ADR-049** |
| **:retention-policy**    | bitemporal `value-at` for "policy at deletion date", schedule for sweep cadence, approval-policy for policy-change governance | 1 entity (7 attrs), 1 status-transition seed (`active` → `superseded`) | `kontor.retention/eligible?`, `sweep!`, `apply-action!` (delegates to side-effect-intent), `policy-for` | **ADR-050** |
| **:audit-doc/privilege** | `:audit-doc/*` schema reused; `:audit-doc/type` precedent; bitemporal `value-at` on the new attr | 1 new attr on existing `:audit-doc` entity (open-set keyword) | `kontor.audit-doc/visible-to?` predicate (consumer-driven role check), `filter-by-privilege` for DSAR bundling | **ADR-051** (small) |
| **:dsar-request**        | status-machine for state transitions, audit-doc for produced bundle, partner-merge resolution, bitemporal collect, approval-policy for fulfillment SoD | 1 entity (~10 attrs), 1 status-transition seed list, 1 approval-policy seed | `kontor.dsar/collect`, `record!`, `fulfill!`, `deny!`, partner-attr enumeration (kernel + companions) | **ADR-052** |

**Read across the table.** The four Stage M artifacts collectively
introduce **3 new entities + 1 new attribute** — comparable to
ADR-039 (5 primitives, similar fan-out). The infrastructure cost is
low; the **vocabulary discipline cost** (canonical reason-codes,
privilege-tag set, retention-action set) is where Stage M earns its
keep.

## 3. Per-artifact design sketch

### 3.1 `:legal-hold/*`

**Schema** (extending `src/kontor/schema.clj`'s pattern, ~470-500
LOC slot near `:partner-merge`):

```clojure
;; ADR-049 legal-hold attrs (kernel)
:legal-hold/code                string :db.unique/identity
:legal-hold/matter-name         string         ; "Acme v. Doe 24-CV-1234"
:legal-hold/issued-by-uid       ref → :create/uid    ; inside counsel
:legal-hold/issued-at           instant
:legal-hold/placed-at           instant        ; when we recorded it (may differ)
:legal-hold/released-at         instant        ; nil = still in force
:legal-hold/released-by-uid     ref → :create/uid
:legal-hold/release-reason      keyword
                                ;; :case-closed | :order-vacated |
                                ;; :ediscovery-complete | :withdrawn
:legal-hold/expires-at          instant        ; optional auto-release
:legal-hold/scope-eids          ref many       ; explicit entity-id set
:legal-hold/scope-query         string         ; EDN-string datalog (optional)
:legal-hold/scope-query-as-of   instant        ; vt-anchor for the query
:legal-hold/supporting-doc      ref → :audit-doc  ; the preservation-order PDF
:legal-hold/state               keyword        ; status-machine facet
:legal-hold/note                string
```

`:legal-hold/scope-eids` + `:legal-hold/scope-query` is the
**hybrid scope** per design-call #1. The eid set is the *fast
path* (direct datom lookup); the query is the *expressive path*
(e.g. "all transactions where the partner is Doe between 2024-Q1
and 2025-Q2"). At write-time-of-purge, the middleware checks both:

1. Is the target eid in any open hold's `:scope-eids`?
2. Does the target eid match any open hold's `:scope-query` when
   evaluated against `db` at `:scope-query-as-of` (or now)?

**Where does query evaluation live? Tradeoff:**
- **Option A — write-time-of-purge.** Cost: every purge of a
  posted entity pays the cost of running every open hold's
  query. For small-tenant deployments (1-3 active holds) this is
  negligible. For large deployments (10+ holds, complex queries)
  this is multiple datalog queries per purge. The cost only fires
  on `:db/purge` calls, which are rare — annual GDPR-erasure
  cycles, not hot path.
- **Option B — sweeper-refreshed cache.** A background job runs
  each hold's query periodically and materializes the result into
  `:legal-hold/scope-eids` (additively, never retracting). The
  middleware then only checks the cache. Cost: the cache can lag
  the query. New writes after the last sweep that satisfy the
  query are NOT in the cache, so a fast-follow purge can still
  succeed. This is a correctness bug for holds.
- **Recommendation: hybrid evaluation with a hot-path eid cache
  AND a tx-time fallback query.** The sweeper refreshes
  `:scope-eids` for performance. The middleware ALSO runs
  `:scope-query` against the speculative txdb when the purge
  arrives — even if the eid is not in the cache, the query catches
  it. Sweeper lag never produces a missed hold; it only produces
  slow purges between sweeps. Most production loads will rarely
  hit the query branch.

**Status machine** (the seed list ADR-049 ships):

```clojure
;; :legal-hold/state facet
nil               → :placed
:placed           → :pending-review     ; "do we still need this?"
:pending-review   → :placed             ; reaffirmed
:pending-review   → :released
:placed           → :released
:placed           → :expired            ; auto-fired by sweep-time-based!
:released         → (terminal)
:expired          → :released           ; admin reaffirms the auto-expiry
```

**Approval-policy seeds.** Both placement and release require
approval. The minimum:

```clojure
;; nil → :placed
{:approval-policy/entity-type     :legal-hold
 :approval-policy/facet           :legal-hold/state
 :approval-policy/transition-from :legal-hold.state/nil
 :approval-policy/transition-to   :placed
 :approval-policy/rule            :requires-supporting-doc}
;; same with :requires-non-empty-reason-note

;; :placed → :released  
{:approval-policy/entity-type     :legal-hold
 :approval-policy/facet           :legal-hold/state
 :approval-policy/transition-from :placed
 :approval-policy/transition-to   :released
 :approval-policy/rule            :no-self-approval}
;; AND :requires-supporting-doc (the release order)
;; AND :requires-non-empty-reason-note
```

ADR-049 ships the policy seeds as a small EDN file in
`resources/legal-hold/default-policies.edn`, the same pattern as
`modules/audit/` from ADR-038.

**Helpers** (~120 LOC `src/kontor/legal_hold.clj`):

```clojure
(place! conn {:code :matter-name :issued-by-uid :supporting-doc
              :scope-eids :scope-query :expires-at})
;; Uses record-status-change-tx-data; composes the entity creation
;; + the nil→:placed transition + the approval-policy check in 1 tx.

(release! conn hold-eid {:released-by-uid :release-reason
                          :supporting-doc :reason-note})

(find-hold-violating-purges db tx-data)
;; Returns vec of {:tx :eid :hold-eid} for any :db/purge against a
;; held entity. Empty in the happy case.

(assert-no-hold-violating-purges! db tx-data)
;; Mirror of assert-no-silent-retracts!. Throws ex-info
;; :type :legal-hold/purge-blocked.

(entity-held? db eid)
;; Predicate: is eid in any open hold's scope (eids or query)?

(expand-scope-query db hold-eid)
;; Run the scope-query against db at :scope-query-as-of, return eids.
;; Used by both the sweeper (cache refresh) and the middleware
;; (write-time check).
```

**Integration with `validate-and-apply`** —
`src/kontor/validation.clj:177-183`. Add one line:

```clojure
(legal-hold/assert-no-hold-violating-purges! txdb tx-data)
```

Placed BEFORE `sealing/assert-no-silent-retracts!` so that the
more-specific error wins on a purge-of-held-posted entity.

**Tests** (~250 LOC `test/kontor/legal_hold_test.clj`):
- Place a hold by eid-set; attempt purge → blocked.
- Place a hold by scope-query; attempt purge of matching entity →
  blocked.
- Place a hold; release it; subsequent purge succeeds.
- Self-approval rejected on placement.
- Self-approval rejected on release.
- Hold expires (auto-fire via `sweep-time-based!`) → subsequent
  purge succeeds.
- Bitemporal: `value-at` on `:legal-hold/scope-query` returns the
  query as it was at a past vt-cutoff.

### 3.2 `:retention-policy/*`

**Schema** (~90 LOC slot):

```clojure
;; ADR-050 retention-policy attrs (kernel)
:retention-policy/code              string :db.unique/identity
:retention-policy/applies-to        keyword many
                                    ;; #{:transaction :invoice :partner
                                    ;;    :audit-doc :status-history …}
:retention-policy/jurisdiction      ref → :country  ; optional, nil = global
:retention-policy/duration-years    long           ; or duration-millis for sub-year
:retention-policy/triggered-by      keyword
                                    ;; :created-at | :closed-at | :posted-at |
                                    ;; :transaction/effective-date | …
:retention-policy/expiry-action     keyword
                                    ;; :purge | :anonymize | :archive-to-cold-storage
:retention-policy/anonymize-fields  keyword many   ; for :anonymize action
:retention-policy/legal-basis       string         ; "HGB §257" / "GDPR Art. 5"
:retention-policy/effective-from    instant        ; ADR-026 pattern
:retention-policy/effective-until   instant
:retention-policy/state             keyword        ; :active | :superseded | :draft
:retention-policy/supporting-doc    ref → :audit-doc
:retention-policy/identity          tuple [code, effective-from] unique
```

**Why these attrs:**
- `:applies-to` is many-keyword (not many-ref to entity-type
  registry) because entity-types are bare keywords throughout
  kontor's `:status-history/entity-type` discriminator pattern
  (`src/kontor/schema.clj:2986-2991`).
- `:triggered-by` is the *anchor attribute* on the entity from
  which retention duration is measured. E.g. for `:invoice` the
  trigger is `:transaction/effective-date` of the underlying tx;
  for `:status-history` it is `:status-history/changed-at`; for
  `:audit-doc` it is `:audit-doc/uploaded-at`. Consumer extension
  via keyword.
- `:expiry-action` is the action the sweeper takes. `:purge` is
  the easy case (transact `[:db/purge eid attr v]`-or-entity).
  `:anonymize` overwrites specific fields with sentinel values
  (or a `:purge` of those fields only, per ADR-007 semantics).
  `:archive-to-cold-storage` emits a side-effect-intent and only
  purges after the consumer confirms successful archive.
- `:anonymize-fields` is the per-policy field list. For
  `:partner` the typical set is
  `#{:partner/name :partner/tax-id :person/first-name
     :person/last-name :person/birth-date …}` — the PII fields.
  Note 17 §3.3 worried that "anonymize is harder than purge —
  does kontor know which fields are PII?" The answer is **the
  policy carries it**, so kernel does not need a global PII
  registry. Companions (e.g. `kontor-l10n-de`) can ship
  pre-seeded policies that include the standard PII set.
- `:effective-from`/`:effective-until` follows ADR-026 pattern
  verbatim. Statutes change. A 2024-vintage record gets
  evaluated against the 2024 retention rule even if the sweeper
  runs in 2030 — that's the bitemporal-correct read.
- `:state` is the status-machine facet. Effective-dating handles
  most lifecycle; `:state :draft` lets a tenant stage a policy
  without it firing yet.

**Status machine** (minimal):
```clojure
nil       → :draft
:draft    → :active
:active   → :superseded   ; effective-until set; a new policy with
                          ; effective-from = old's effective-until takes over
```

`:superseded` is terminal. To "update" a policy, ship a new row
with overlapping windows handled per ADR-026's longest-effective-
from-not-exceeding-D tiebreaker.

**Approval-policy seeds:**
- `nil → :active`: `:requires-supporting-doc` +
  `:requires-non-empty-reason-note`. Auditor needs to know "why
  did this policy change."

**Sweeper** (~150 LOC `src/kontor/retention.clj`):

```clojure
(policy-for db entity-type {:keys [jurisdiction at]})
;; Returns the active retention-policy entity for this combo at vt.
;; Uses bitemporal value-at semantics on :effective-from/:effective-until.

(eligible? db entity-eid {:as-of (java.util.Date.)})
;; True iff entity has aged past its applicable policy's duration AND
;; no open legal-hold covers it. Composes legal-hold/entity-held?.

(sweep! conn {:entity-type :batch-size :dry-run?})
;; Walk entities of :entity-type. For each:
;;   1. policy-for to find applicable retention-policy.
;;   2. eligible? returns true → emit a :side-effect-intent of
;;      :type :retention-expiry-action with payload
;;      {:eid eid :action :purge | :anonymize | :archive…
;;       :policy policy-eid}.
;; Returns vec of {:entity-eid :action :policy-eid} (or :held when
;; legal-hold blocked it; the entity stays in the sweep queue for
;; the next run after release).

(apply-action! conn intent-eid)
;; Worker-side: claim the intent, execute the action (purge | 
;; anonymize | archive-to-cold-storage), mark done. Anonymize is
;; a kernel implementation (transact :db/purge on each
;; :anonymize-fields attr); archive is consumer-driven (the worker
;; uploads bytes to cold storage, records the archive-uri on an
;; :audit-doc, then runs the purge).
```

**Why kernel-ships-the-sweeper but consumer-schedules-it:**
The sweeper must *respect legal-hold* (the headline invariant —
hold blocks expiry). Holding the sweeper in user-land risks a
consumer bypassing the hold check by writing their own loop. By
shipping it in kernel, the hold check is unavoidable. Consumers
choose the cadence (`bb retention-sweep` cron, daily/weekly).

**The hold-blocks-purge invariant.** Concretely: `eligible?`
returns false when `(legal-hold/entity-held? db entity-eid)` is
true. The sweeper still produces the work-item — for visibility
("this entity would expire today but is on legal hold") — but the
work-item carries `:retention-expiry-intent/blocked-by-hold` true
and the action is NOT emitted. When the hold releases, the next
sweep produces an unblocked intent.

**Tests** (~300 LOC):
- Default policy (7-year SOX) on `:transaction`; transaction
  aged 6 years → not eligible. Aged 7y+1d → eligible.
- Policy effective-from 2025-01-01; transaction effective-date
  2024-06-15 → uses pre-2025 policy (effective-dating).
- Policy `:anonymize` action on `:partner` → fields listed in
  `:anonymize-fields` purged; other fields retained.
- Open legal-hold blocks expiry; release-then-sweep applies it.
- `:archive-to-cold-storage` emits intent; worker confirms;
  follow-up purge fires.

### 3.3 `:audit-doc/privilege`

**Schema** — one new attribute on existing `:audit-doc`:

```clojure
:audit-doc/privilege            keyword
                                ;; :none | :attorney-client |
                                ;; :work-product | :joint-defense |
                                ;; :settlement-communication |
                                ;; :trade-secret | :pii-sensitive |
                                ;; :hipaa-phi | :ferpa-edu | …
                                ;; Open-set; consumers extend.
                                ;; nil treated as :none.
```

**Open-set, not closed enum**, matching ADR-038's "canonical
kernel vocabularies are open-set" discipline. Adds to ADR-051 as
the canonical starter list; consumer companions extend
(e.g. `kontor-l10n-de-medical` might add `:patientendaten`).

**Access checks.** The kernel tags; the consumer enforces.
Important: **there is no kernel-level ACL or user-role system.**
ADR-010 explicitly says "no UI / no auth" — that includes "no
RBAC". The kernel ships `:audit-doc/privilege` as a *label*; the
consumer's auth layer (HTMX session, OAuth, whatever) gates URI
access. Stage M does not change this. State it explicitly in
ADR-051 to forestall the question.

**Helpers** (~30 LOC, added to `kontor.audit-doc`):

```clojure
(visible-to? db doc-eid privilege-level)
;; Boolean: is this doc visible to a viewer with this privilege-
;; level? Default rule: nil/:none/:trade-secret docs visible to
;; all; :attorney-client/:work-product visible only to viewers
;; with matching privilege. Consumer overrides via opts.

(filter-by-privilege db doc-eids viewer-privilege)
;; Keep only docs the viewer can see. Used by kontor.dsar/collect
;; — privileged docs are NOT auto-included in a DSAR bundle to
;; the subject; they need legal-review opt-in.
```

**URI/storage gating.** Note 17 §3.7 sketched a
`kontor.audit-doc/uri-for(doc, requesting-uid)` helper that
returns either the URI or `:redacted`. That ties the kernel to a
user-uid concept which lives at consumer level. **My push-back on
Agent B's sketch:** the kernel should NOT take a `requesting-uid`
parameter. It exposes the privilege tag; the consumer's auth
layer compares it against the viewer's role. The redaction is
consumer-side, the labeling is kernel-side. This keeps the
separation per ADR-010.

**Bitemporal composition.** Privilege tags change. A document
upgraded from `:none` to `:attorney-client` (because counsel
later determined it was privileged) needs to be discoverable in
the original state too: "what was the privilege tag at filing
date?" → `kontor.bitemporal/value-at db doc-eid
:audit-doc/privilege filing-date`. This composes for free with
ADR-048.

**Composition with `:status-history`.** When a `:status-history`
row references an `:audit-doc` with privilege `:attorney-client`
and the auditor is filtering, the helper applies. The
`status-history-of` query (`src/kontor/status_machine.clj:433-454`)
does NOT filter today; the consumer applies
`filter-by-privilege` to the returned vec. This is correct — the
kernel returns everything; the consumer's policy gates rendering.

**Privilege-change audit.** Every change to `:audit-doc/privilege`
on an existing doc should itself be a `:status-history` row on a
synthetic facet `:audit-doc/privilege-state` (or directly via
bitemporal `timeline`). Recommendation: add to ADR-051 that
privilege changes go through `record-status-change!` with reason
codes `:privilege-determined` / `:privilege-waived`.

**Approval-policy seed** — privilege changes need governance:
```clojure
;; any → :none transition (waiver)
{:approval-policy/entity-type :audit-doc
 :approval-policy/facet :audit-doc/privilege-state
 :approval-policy/transition-to :none
 :approval-policy/rule :no-self-approval}
;; AND :requires-supporting-doc (the waiver determination)
```

### 3.4 `:dsar-request/*` + `kontor.dsar/collect`

**Schema** (~100 LOC slot):

```clojure
;; ADR-052 dsar-request attrs (kernel)
:dsar-request/external-id        string :db.unique/identity
:dsar-request/partner            ref → :partner       ; the subject
:dsar-request/jurisdiction       ref → :country       ; GDPR | CCPA | LGPD | …
:dsar-request/kind               keyword
                                 ;; :access | :portability | :erasure |
                                 ;; :rectification | :restriction | :objection
:dsar-request/received-at        instant
:dsar-request/deadline-days      long                 ; e.g. 30 (GDPR), 45 (CCPA)
:dsar-request/deadline-at        instant              ; computed; queryable
:dsar-request/state              keyword              ; status-machine facet
:dsar-request/received-via       keyword
                                 ;; :email | :portal | :postal | :api
:dsar-request/identity-verified-at instant
:dsar-request/fulfilled-at       instant
:dsar-request/fulfilled-package  ref → :audit-doc     ; the bundle artifact
:dsar-request/denied-reason      keyword
                                 ;; :identity-not-verified | :no-data |
                                 ;; :legal-hold-override | :exempt-records
:dsar-request/supporting-doc     ref → :audit-doc     ; intake form etc.
:dsar-request/notes              string
```

**Status machine seed list:**
```clojure
nil               → :received
:received         → :verifying-identity
:verifying-identity → :in-progress
:in-progress      → :awaiting-legal-review  ; privileged docs detected
:in-progress      → :fulfilled
:in-progress      → :denied
:received         → :withdrawn              ; subject changed mind
:awaiting-legal-review → :fulfilled
:awaiting-legal-review → :denied
:received         → :extended               ; 60-day extension under GDPR
:extended         → :in-progress
```

**Approval-policy seeds:**
- `:in-progress → :fulfilled`: `:no-self-approval` (separation of
  intake person and fulfiller) + `:requires-supporting-doc` (the
  produced bundle).
- `:in-progress → :denied`: `:requires-supporting-doc` (the
  written denial rationale) + `:requires-non-empty-reason-note`.

**Statutory deadline auto-flagging.** ADR-041
`:status-transition/auto-after-millis` paired with a facet
`:dsar-request/deadline-warning-state`:
- Tx `:received → :overdue-warning` auto-fires at `(deadline-days
  − 7) * 86400000` ms after `:received-at`. Side-effect-intent
  emits an alert to the DPO.
- Tx `:overdue-warning → :overdue` auto-fires at `deadline-days *
  86400000` ms. A second alert.

This is the same pattern Stage L collections uses for dunning
auto-escalation. Free reuse.

**The collect helper** (~250 LOC `src/kontor/dsar.clj`):

```clojure
(defn collect
  "Return all kernel + companion data referring to `partner-eid`,
   snapshotted at the given valid-time + tx-time.

   Returns a map:
     {:partner          (pulled partner with all attrs)
      :merge-chain      [eids of merged-from partners]
      :transactions     [pulled transactions]
      :postings         [pulled postings referencing the partner]
      :invoices         [pulled invoices buyer-or-seller]
      :status-history   [history rows where :entity is partner OR
                         where origin-transaction references
                         partner's tx]
      :audit-docs       [docs referenced by partner's history,
                         filtered by privilege if :viewer-privilege
                         opt is set]
      :partner-bank-accounts [...]
      :partner-tax-ids  [...]
      :partner-tags     [...]
      :payment-applications [...]
      :collection-cases [...]
      :credit-holds     [...]
      :dunning-events   [...]
      :disputes         [...]
      :dsar-requests    [...]      ; prior requests by this subject
      :legal-holds      [holds whose scope includes partner]}

   opts:
     :as-of-tx     — datahike d/as-of snapshot.
     :as-of-valid  — bitemporal cutoff for :tx/valid-from filters.
     :viewer-privilege — for privilege filtering on :audit-docs;
                         when omitted, no filtering (raw collect).
     :include-merged? — boolean (default true) walk partner-merge
                        chain to include data from merged-FROM
                        duplicates."
  [db partner-eid opts] ...)
```

**The exhaustive partner-reference inventory.** This is the
critical piece — every attribute referencing `:partner/*` across
kernel + every shipped companion. Grep confirms:

| Attribute | Source | Notes |
|---|---|---|
| `:transaction/partner` | `src/kontor/schema.clj:1226` | header-level partner |
| `:posting/partner` | `src/kontor/schema.clj:1448` | per-line override |
| `:partner-merge/duplicate-of` + `/superseded` | `src/kontor/schema.clj:459,466` | merge link |
| `:partner-bank-account/partner` | `src/kontor/schema.clj:558` | banking junction |
| `:partner-tax-id/partner` | `src/kontor/schema.clj:792` | multi-tax-id (ADR-040) |
| `:partner-tag/partner` | `src/kontor/schema.clj:841` | segment tags |
| `:invoice/seller` | `src/kontor/schema.clj:1662` | invoice issuer |
| `:invoice/buyer` | `src/kontor/schema.clj:1668` | invoice receiver |
| `:person/partner` | `modules/partner/src/kontor/partner/schema.clj:75` | 1:1 with subtype |
| `:org/partner` | `modules/partner/src/kontor/partner/schema.clj:156` | 1:1 with subtype |
| `:partner-contact-mech/partner` | `modules/partner/src/kontor/partner/schema.clj:433` | contact mech junction |
| `:partner-contact-mech-purpose/partner` | `modules/partner/src/kontor/partner/schema.clj:502` | purpose junction |
| `:partner-role/partner` | `modules/partner/src/kontor/partner/schema.clj:545` | role junction |
| `:partner-relationship/partner-from` + `/partner-to` | `modules/partner/src/kontor/partner/schema.clj:584,589` | inter-partner links |
| `:order/bill-from-partner` | `modules/sales/src/kontor/sales/schema.clj:61` | supplier on order |
| `:order/bill-to-partner` | `modules/sales/src/kontor/sales/schema.clj:68` | buyer on order |
| `:order-role/partner` | `modules/sales/src/kontor/sales/schema.clj:495` | additional roles |
| `:collection-case/partner` | `modules/collections/src/kontor/collections/schema.clj:36` | AR case root |
| `:credit-hold/partner` | `modules/collections/src/kontor/collections/schema.clj:246` | per-entity overlay |

Plus the **implicit references** via downstream `:transaction`
refs (every `:payment-application` walks to a `:transaction` which
walks to a partner; every `:status-history/origin-transaction`
ditto). The collect helper must walk both axes:
1. Direct partner refs (the 19 attrs above).
2. Indirect via `:transaction` (any tx whose `:transaction/
   partner = partner-eid` OR whose postings have `:posting/
   partner = partner-eid`).

**The hard problem.** Note 17 §3.5 understated this: partner
references are pervasive AND many of them are in companions that
the kernel does not know about. A naive `collect` in the kernel
sees the 13 partner-refs it ships (kernel + collections is in
`modules/`, which the kernel does not import). The architectural
answer is **companion-registered ref-walkers**:

```clojure
;; In kontor.dsar:
(def ^:dynamic *partner-attrs*
  "Set of attributes referencing :partner/* that collect walks.
   Each companion registers its own attrs at load time."
  (atom #{:transaction/partner :posting/partner :invoice/buyer
          :invoice/seller :partner-bank-account/partner …}))

(defn register-partner-attr! [attr]
  (swap! *partner-attrs* conj attr))
```

`kontor-collections`'s init fn calls
`(kontor.dsar/register-partner-attr! :collection-case/partner)`
etc. The kernel collect helper iterates over the registry. This
is the same dispatch pattern as the schema-loader registry. ADR-052
documents it.

**Bitemporal walk.** Every query in `collect` takes `:as-of-tx`
and `:as-of-valid`, passes them to `d/as-of` + bitemporal filter:

```clojure
(let [db (cond-> db
           as-of-tx (d/as-of as-of-tx))
      ;; for each entity-type:
      eids (d/q ... db partner-eid)
      filtered (filter (fn [eid]
                         (or (nil? as-of-valid)
                             (let [vf (kbt/posting-vf db eid)]
                               (.before vf as-of-valid))))
                       eids)]
  ...)
```

**Privilege filtering for DSAR-vs-privilege edge case.** Note 17
§3.5 raised this and the task prompt asks me to confirm. When
producing a DSAR bundle, audit-docs with `:audit-doc/privilege` =
`:attorney-client` / `:work-product` are **NOT** auto-included in
the subject's bundle. They surface in
`:awaiting-legal-review` as a separate list that counsel reviews
before fulfillment. Concretely, `collect` returns the docs with a
side-band:

```clojure
{:audit-docs              [...]    ; the unfiltered set
 :audit-docs-privileged   [...]    ; docs requiring legal review}
```

The status-machine transition `:in-progress →
:awaiting-legal-review` auto-fires when `audit-docs-privileged`
is non-empty. The fulfillment helper checks it.

**Composition with legal-hold for DSAR-erasure.**
*Held data must be included in the DSAR access response
(subject's right doesn't waive).* But for an erasure-kind DSAR,
the held data CANNOT be deleted. The path:

1. Subject files erasure request.
2. `collect` returns the held data alongside the rest.
3. Fulfillment: bundle the access portion (everything) +
   anonymize/purge the unheld portion + emit a denial-rationale
   audit-doc for the held portion explaining "this data is
   preserved under matter $X; will be purged when the hold
   releases."
4. Subject is notified of partial-fulfillment.

This is a real workflow, not a kernel decision. ADR-052
documents the pattern; the kernel ships `legal-hold/entity-held?`
as the predicate the workflow consumes.

**Tests** (~400 LOC `test/kontor/dsar_test.clj`):
- Create partner; transact invoice + posting + audit-doc;
  `collect` returns all three.
- Bitemporal collect: snapshot at older tx-time excludes later
  data.
- Privilege filtering: privileged doc surfaces in the
  `:audit-docs-privileged` side-band, not `:audit-docs`.
- Partner-merge: subject is the duplicate; data references
  canonical; `:include-merged? true` finds both.
- Legal-hold + erasure: held data appears in bundle but is NOT
  purged by the erasure-fulfillment helper.
- Statutory deadline auto-fires at `deadline-days − 7`.

## 4. Composition diagrams

### 4.1 The hold-blocks-purge invariant — where does it live?

```
caller (transact-with-validation)
  └─ inv/assert-invariants conn tx-data            ; state-shape (account-active, …)
  └─ d/transact conn [[:db.fn/call validate-and-apply tx-data]]
       └─ inside transactor:
           sealing/assert-no-silent-retracts!       ; retract of posted
        ┌─ legal-hold/assert-no-hold-violating-purges!  ; NEW: purge on held
        │  period/assert-no-write-on-sealed!        ; sealed-period writes
        │  period/assert-not-in-locked-period!      ; locked-period writes
        │  state-machine/assert-transition!         ; tx-state transitions
        │  validation/assert-postings-sum-to-zero!  ; balanced books
        │  → returns tx-data; transactor applies
        │
        └─ ORDER MATTERS: hold check runs BEFORE sealing
           because a posted-purge is allowed (ADR-007) unless held.
           If sealing ran first, the error would mis-attribute
           ("silent retract on posted") instead of the correct
           ("blocked by hold $X").
```

### 4.2 Retention sweeper + hold + audit-doc

```
cron / consumer schedule
  └─ kontor.retention/sweep! conn {:entity-type :invoice :batch-size 1000}
       ├─ for each candidate invoice:
       │   ├─ policy = retention/policy-for db :invoice {...}
       │   ├─ aged?  = (- now (:transaction/effective-date inv)) > policy-duration
       │   ├─ held?  = legal-hold/entity-held? db inv-eid
       │   ├─ if (and aged? (not held?)):
       │   │   ├─ for :purge action:
       │   │   │   └─ emit side-effect-intent :type :retention-purge
       │   │   │      with payload {:eid eid :policy policy-eid}
       │   │   ├─ for :anonymize action:
       │   │   │   └─ emit intent :type :retention-anonymize
       │   │   │      with payload {:eid :fields …}
       │   │   └─ for :archive-to-cold-storage:
       │   │       └─ emit intent :type :retention-archive
       │   └─ record :audit-doc {:type :retention-action 
       │                          :description "policy X eligible for Y"}
       │      and reference from the intent
       └─ worker drains intents (kontor.side-effect/pending),
          executes action, marks done, links result to audit-doc.
```

### 4.3 DSAR fulfillment

```
intake (consumer):
  ↓ kontor.dsar/record! conn {:partner :kind :received-via :supporting-doc}
  ↓ → :received status-history row + approval-policy check on intake actor

verification (consumer):
  ↓ kontor.status-machine/record-status-change! :verifying-identity → :in-progress

collect (kernel):
  ↓ kontor.dsar/collect db partner-eid {:as-of-tx :as-of-valid}
  ↓ → {:invoices :postings :status-history :audit-docs
  ↓    :audit-docs-privileged :legal-holds …}
  ↓
  ↓ if (seq audit-docs-privileged):
  ↓   record-status-change! :in-progress → :awaiting-legal-review
  ↓   counsel reviews; manually marks each doc
  ↓     - include (move from :audit-docs-privileged to :audit-docs)
  ↓     - exclude (drop from bundle entirely)
  ↓
fulfillment (consumer + kernel):
  ↓ render bundle (consumer-side: JSON, PDF, whatever)
  ↓ kontor.audit-doc/create-doc! conn {:type :dsar-bundle :storage-uri …}
  ↓ status-change :in-progress → :fulfilled
  ↓ approval-policy: :no-self-approval, :requires-supporting-doc
  ↓   (the bundle's audit-doc)
  ↓ side-effect-intent :type :email-subject (delivery)
  ↓
for erasure-kind:
  ↓ separate retention-style action emit:
  ↓   for each unheld entity, intent :type :dsar-erasure-purge
  ↓   for each held entity, audit-doc :type :dsar-deferred
  ↓     (explaining "preserved under hold $X")
```

### 4.4 Privilege change → status-history → DSAR side-effect

```
counsel determines a doc is privileged:
  ↓ record-status-change! db conn
       {:entity doc-eid
        :entity-type :audit-doc
        :facet :audit-doc/privilege-state
        :to :attorney-client
        :reason :privilege-determined
        :supporting-doc ref-to-determination-memo
        :changed-by-uid counsel-uid}
  ↓ approval-policy check: :requires-supporting-doc.
  ↓ status-history row + facet update + supporting-doc ref + audit chain.
  ↓
later, a DSAR by the doc's partner:
  ↓ kontor.dsar/collect ... :viewer-privilege :external-subject
  ↓ filter-by-privilege drops the doc from :audit-docs into
  ↓   :audit-docs-privileged → triggers :awaiting-legal-review.
  ↓
bitemporal: a 2025-vintage DSAR is filed in 2027 after the 2026
privilege determination:
  ↓ kbt/value-at db doc-eid :audit-doc/privilege 2025-cutoff
  ↓ returns :none (the 2025 state) — but the 2027 fulfillment
  ↓ runs against the current state where the doc IS privileged.
  ↓ counsel's call: was the doc privileged AT FILING or NOW?
  ↓ ABA guidance: the privilege determination governs (i.e., now).
  ↓ ADR-051 documents this — privilege checks read CURRENT state,
  ↓ not historical.
```

## 5. Cross-stage interactions

Stage M touches every prior stage's substrate. Specifically:

### 5.1 Stage J (sales / invoice)

- `:invoice/buyer` + `:invoice/seller` are partner refs → both are
  in-scope for DSAR `collect`.
- `:invoice/status` transitions like `:sent → :cancelled` AFTER
  posted are already a sensitive operation under ADR-038's
  approval-policy. Adding `:legal-hold` makes them *blocked*
  outright when the invoice is held. Status-machine layer enforces.
- Effective-dated retention: an invoice's retention is anchored
  on its `:transaction/effective-date`. Stage M's
  `retention/policy-for` uses that anchor.

**Stage J needs no schema change.** Only documentation: ADR-049
(legal-hold) cites the existing `:invoice/buyer` + `:invoice/
seller` as default DSAR-collect attrs.

### 5.2 Stage K (procurement)

- `:requisition/buyer-partner` and various vendor refs in
  procurement schema — partner-attrs registry needs them.
- Vendor's data is in-scope for DSAR if the vendor is a natural
  person (consultant, freelancer) — GDPR applies. `kontor-
  procurement` ADR-042 already attaches `:audit-doc` refs
  liberally.

**Stage K needs no schema change.** Only the
`register-partner-attr!` calls in its init.

### 5.3 Stage L (collections)

This is the most surface-area-touching stage. `:collection-case/
partner`, `:credit-hold/partner`, dunning events tied to
partner — all in DSAR scope.

- **Legal-hold on a collections case.** A held partner's
  collection case continues to dun (subject's right doesn't
  pause collection). BUT the case's `:audit-doc/privilege` for
  any litigation-related docs is relevant. Add to ADR-049: held
  collection cases continue normal dunning unless the hold
  reason explicitly pauses them (ADR-043 already has a
  `:dunning-pause/reason-code :legal-hold` — confirm wiring).
- **DSAR-erasure on a partner with open AR.** This is the
  classic hard case Note 17 §5.3 flagged. Resolution:
  - Open AR is a legitimate-interest basis under GDPR Art. 6.
  - Retention basis cites it; the partner stays in the system
    until the receivable settles or is written off.
  - Marketing-consent records are separately erasable.
  - ADR-052 documents the split: erasure of marketing-touchable
    attrs proceeds; erasure of financial-history attrs is denied
    with a recorded basis-citation audit-doc.

### 5.4 Stage L′ / future (asset, revrec, subscription)

The same pattern repeats: any new state-bearing entity that
references partner gets registered in `*partner-attrs*` registry.
Revrec's `:performance-obligation` (Note 17 §5.7) similarly.

### 5.5 Bitemporal stages

ADR-048's `:tx/valid-from` is the foundation for
`retention/policy-for` (the policy active at the entity's
valid-time). No new bitemporal mechanism needed.

## 6. ADR-drafting hints

### ADR-049 (`:legal-hold`)

Questions the ADR must answer, with my preferred resolution:

1. **Where does scope-query evaluation run?** → Both at sweeper
   time (cache) AND at write-time (live re-evaluation against
   speculative txdb). Cache for performance, live for
   correctness.
2. **Can a hold cover non-posted entities?** → Yes. The
   middleware blocks purge of any held entity regardless of
   posted-state. Holds are about preservation, not about
   posted-ness. Document the contrast with sealing (which only
   cares about posted).
3. **Can a hold be amended after placement?** → Scope-eids:
   yes, additively (adding new eids to a hold is a normal write,
   not gated). Scope-query: no, immutable after `:placed` state;
   to broaden, release-and-replace. Document why: an amendable
   scope-query is an audit hole.
4. **Hold-on-hold (nested holds)?** → No. A single open hold is
   sufficient; the middleware OR's across all open holds. Nested
   structure adds no expressive power.
5. **Cross-tenant holds?** → No. Holds are tenant-scoped by
   construction; the scope-query runs against the tenant's db.
6. **Audit chain for the hold itself.** → A hold's
   `:status-history` rows + `:audit-doc/supporting-doc` is the
   chain. The hold-placed-tx and hold-released-tx are themselves
   audit-recorded via datahike's tx-time.

### ADR-050 (`:retention-policy`)

1. **Default policies in kernel?** → No. Per design-call #2,
   l10n companions ship defaults (e.g.,
   `kontor-l10n-de/retention.edn` has SOX-7y + HGB-10y).
   Kernel ships shape only.
2. **Effective-dating identity tuple.** → `[code,
   effective-from]` is the unique constraint. Re-installing the
   same code with a new `effective-from` creates a new row;
   re-installing the same `(code, effective-from)` upserts.
3. **What entity types does kernel support out-of-box?** →
   `:transaction`, `:invoice`, `:posting`, `:audit-doc`,
   `:status-history`, `:partner` — the canonical set. Companions
   register their own types via the same `*partner-attrs*` style
   registry (`*retention-types*` or similar).
4. **Sweeper transactional semantics?** → Each sweep emits
   intents in one tx per batch; each worker action is its own
   tx. Partial sweep failures don't roll back successful
   intents.
5. **Anonymize-fields validation?** → The kernel does not
   validate that listed fields are actually PII. The policy
   author is responsible. Document.
6. **Anonymize implementation.** → `[:db/purge eid attr v]` per
   field per the ADR-007 model. The purge is itself a recorded
   commit; the audit chain documents the anonymization.

### ADR-051 (`:audit-doc/privilege`)

1. **Open or closed enum?** → Open. Per ADR-038's vocabulary
   discipline. Document the canonical starter set (8 keywords).
2. **Default value semantics?** → `nil` and `:none` are
   equivalent. The schema does not enforce a default.
3. **Where does the access check live?** → Kernel exposes
   `visible-to?` predicate that takes
   `(db doc-eid viewer-privilege-level)`. The kernel does NOT
   know about user IDs. Consumer maps `(viewer-user → viewer-
   privilege-level)` per their auth model.
4. **Privilege change governance?** → Use existing
   `record-status-change!` with a new facet
   `:audit-doc/privilege-state`. Approval-policy seeds gate
   privilege upgrades (`:none → :attorney-client`) and waivers
   (`anything → :none`).
5. **DSAR vs privilege precedence?** → Privilege wins. Privileged
   docs surface separately for legal review before bundling.

### ADR-052 (`:dsar-request` + `kontor.dsar/collect`)

1. **Statutory deadline modeling?** → `:deadline-days` (input) +
   `:deadline-at` (computed at write-time, queryable). ADR-041
   `:status-transition/auto-after-millis` fires the warning
   transitions.
2. **Erasure-vs-retention conflict resolution?** → Legitimate-
   interest (open AR) > erasure right. Document the standard
   GDPR Art. 6 / Art. 17 framework. Cite recital 65.
3. **Erasure-vs-hold conflict resolution?** → Hold > erasure.
   Subject is notified of partial fulfillment; remainder pending
   hold release.
4. **The partner-attrs registry mechanism.** → Atom in
   `kontor.dsar` namespace, mutated by companion init. Document
   the API. Companion ADRs (043, etc.) reference it.
5. **What about implicit partner refs (via transactions)?** →
   `collect` walks `:transaction/partner` and `:posting/partner`
   in addition to direct partner-attrs. Document this two-axis
   walk.
6. **Should `collect` be kernel?** → Yes per design-call #3
   ("DSAR in scope"). The kernel ships the helper because the
   bitemporal walk needs `kontor.bitemporal` and the privilege
   filter needs `kontor.audit-doc` — both kernel namespaces.
7. **Cross-tenant DSAR?** → Out of scope. ADR-031 entity
   isolation is the tenant boundary; `collect` runs against one
   tenant's db.

## 7. What I would push back on from Note 17

Note 17 (Agent B's earlier work) made several proposals that the
substrate audit shows are wrong, fuzzy, or over-engineered.

1. **`:legal-doc` entity (Note 17 §3.1) is over-engineered for
   Stage M.** Note 17 sketches a thick `:legal-doc` subtype of
   `:audit-doc` with `:legal-doc/parties`, `:legal-doc/governing-
   jurisdiction`, `:legal-doc/parent-doc` (amendment chain),
   `:legal-doc/status` (state-machine), `:legal-doc/effective-
   from`/`-until`. This is a CLM primitive, not a Stage M
   primitive. Stage M is hold + retention + privilege + DSAR;
   `:legal-doc` belongs in a hypothetical future `kontor-clm`
   companion (Note 17 itself says "Probably companion-level, in a
   new `kontor-clm` or `kontor-legal` module"). **Defer entirely
   from Stage M.** Use bare `:audit-doc` with `:audit-doc/type`
   discriminator.

2. **`:contract-obligation` (Note 17 §3.2) is out of Stage M
   scope.** Sweepable obligations (renewal alerts, SLA deadlines,
   indemnity caps) are a CLM concern. Note 17 §6 confirms: "Heavy
   stages (kontor-counsel, kontor-privacy, kontor-clm) wait until
   a real user story pulls them." Stage M does not pull
   obligations. Defer.

3. **`:counsel-matter` + `:legal-invoice` + UTBMS (Note 17 §3.6)
   is squarely out of Stage M scope.** This is `kontor-counsel`.
   Note 17 §6 already says defer. Confirmed.

4. **The privilege-tag `uri-for(doc, requesting-uid)` signature
   (Note 17 §3.7) couples the kernel to a user-uid concept.**
   Push back: the kernel ships `visible-to?(db, doc-eid, viewer-
   privilege)` instead. The consumer maps user → privilege.
   Cleaner separation per ADR-010.

5. **Note 17 §5.1 says "Should `:retention-policy` be kernel or
   l10n?" and recommends "kernel-shape + l10n-data".** This is
   correct and matches design-call #2; restate explicitly in
   ADR-050.

6. **Note 17 §5.2 hybrid scope is right but underspecified.**
   Note 17 said "scope-query (EDN) + scope-entity-ids (computed
   cache, refreshed when query runs). The cache is what
   middleware checks at purge time." My push-back: the
   middleware checks BOTH the cache AND runs the query live.
   Cache-only has correctness holes between sweeps.

7. **Note 17 §5.3 wonders if DSAR should precede or follow
   collections.** The substrate now has collections (ADR-043);
   DSAR-after-collections is the right ordering. Confirmed by
   design-call #3 placing DSAR in Stage M.

8. **Note 17 §3.5 says DSAR is "Companion-level (kontor-
   privacy)".** Push back: design-call #3 puts DSAR in kernel
   (because the bitemporal-walk + privilege-filter helpers are
   kernel-internal). The intake portal, identity verification,
   email delivery — those remain consumer-level. The data
   primitives go in kernel.

9. **Note 17 §3.5's `:dsar-request/findings [edn]` field is
   wrong shape.** EDN blobs in datahike are anti-pattern; we use
   explicit refs. My substitution: `:dsar-request/fulfilled-
   package ref → :audit-doc` carries the bundle; intermediate
   findings live in the `:audit-docs-privileged` side-band that
   `collect` returns at runtime. No persisted findings field.

10. **Note 17 §3.4 worried about "bitemporal record of consent"
    needing a `:partner/consent` status-machine entity.** It
    doesn't need a new entity — it needs a facet on partner
    (`:partner/marketing-consent`) routed through
    `record-status-change!`. ADR-052 documents the pattern. No
    new entity in Stage M.

## What I would defer

Per Stage M's narrow scope, all of these are explicitly OUT:

- `:legal-doc` entity + thick contract metadata (Note 17 §3.1).
- `:contract-obligation` + sweepable obligations (Note 17 §3.2).
- `:counsel-matter` + UTBMS codes + `:legal-invoice` (Note 17
  §3.6).
- E-signature integration (DocuSign envelope IDs, certificate-
  of-completion verification) (Note 17 §3.7 partial).
- VDR primitives (Note 17 §4).
- Cap-table primitives + 409A (Note 17 item #13).
- Board governance, board resolutions (Note 17 item #14).
- Privilege-log generation UI (Note 17 §4).
- `:partner/marketing-consent` as a separate entity (it's just a
  facet — Note 17 §5.9 alluded to this).

Total Stage M deliverable: **3 new entities + 1 new attribute + ~700
LOC of helpers + ~900 LOC of tests + 4 ADRs.** Comparable in scope to
ADR-043 (collections) without the volume of state-machine
permutations.

## Sources / files cited

- `src/kontor/sealing.clj:28-77` — sealing middleware.
- `src/kontor/validation.clj:166-220` — validate-and-apply chain.
- `src/kontor/audit_doc.clj:1-105` — audit-doc helpers.
- `src/kontor/status_machine.clj:44-454` — full status machine.
- `src/kontor/bitemporal.clj:75-318` — bitemporal API.
- `src/kontor/period.clj:139-410` — period locking semantics.
- `src/kontor/payment_application.clj:185-411` — composition
  example for bitemporal + status-machine + supporting-doc.
- `src/kontor/state_machine.clj:41-145` — transaction lifecycle
  machine.
- `src/kontor/side_effect.clj:1-80+` — intent queue API.
- `src/kontor/schema.clj:380-1448` — kernel partner-touching
  attrs + transaction/posting/invoice.
- `src/kontor/schema.clj:2950-3210` — status-machine + audit-doc
  + approval-policy schema.
- `modules/partner/src/kontor/partner/schema.clj:75-589` —
  partner subtype + contact mech + role + relationship attrs.
- `modules/sales/src/kontor/sales/schema.clj:61-495` — sales
  partner refs.
- `modules/collections/src/kontor/collections/schema.clj:36,246`
  — collections partner refs.
- ADR-007 — purge semantics (`doc/decisions.md:106-122`).
- ADR-008 + ADR-048 — bitemporal model (`doc/decisions.md:125-152,
  4689-4756`).
- ADR-011 — hybrid validation strategy (`doc/decisions.md:184-206`).
- ADR-014 — soft/hard period locks (`doc/decisions.md:242-268`).
- ADR-026 — effective-dated tax rates (`doc/decisions.md:1381-1428`).
- ADR-034 — status-transition cross-cutting primitive
  (`doc/decisions.md:2247-...`).
- ADR-038 — audit + governance + SoD (`doc/decisions.md:3012-3186`).
- ADR-041 — time-based transitions + side-effect intent + bulk
  + account-type-direction (`doc/decisions.md:3538-3735`).
- ADR-043 — collections (`doc/decisions.md:4269-4480`).
- Note 17 — vendor legal landscape + agent B's primitive sketch
  (`doc/research/17-vendor-legal-process.md`).

Date: 2026-05-13. Single-agent internal-view study (parallel to two
external research agents handling reference + market-pain).
Verification: high — every claim about kontor's substrate cites
file:line and ADR reference; design-shape claims for Stage M are
constructive proposals to be validated by the parallel research
agents and resolved into ADR-049/050/051/052.
