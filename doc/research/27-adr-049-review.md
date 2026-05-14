# Research note 27 — ADR-049 (legal hold) independent code review

Review-after pass on commit `3c3f3cb` ("ADR-049 — Legal hold: write-time invariant blocking `:db/purge`"). Per the per-stage rhythm (ADR-037), the implementation is audited against the three Stage-M research notes (22 reference study, 23 market-pain, 24 internal-gap), against the substrate (sealing, status-machine, approval-policy, bitemporal) and against the eDiscovery industry's "reasonable steps to preserve" standard (FRCP 37(e), Sedona Principles, Zubulake / Pension Committee / Brookshire / Epic v. Google).

The implementation is **~280 LOC of helpers + ~180 LOC of tests + 13 schema attrs + 1 line in `validate-and-apply`**. ADR-049's body is ~100 LOC of decisions.md. This is comfortably in line with the budget the internal-gap agent forecast (note 24 §2: "3 new entities + 1 attr"; this ADR ships **1 of those 3** — hold only).

The review is ruthless: 8 distinct findings (1 P0, 3 P1, 4 P2), plus a forward-compat note and a test-coverage gap inventory.

## 1. Summary table

| Severity | Count | Headline |
|---|---|---|
| P0 (ship-blocker) | 1 | Hold middleware never fires for entity-map purges or `:db.fn/retractEntity`-style retracts (`legal_hold.clj:215-221`). |
| P1 (bites at scale / drift) | 3 | `:pending-review → :released` skips approval policy; `find-hold-violating-purges` re-evaluates `:scope-query` per target (O(N×holds), unbounded); `:legal-hold/placed-at` is the same denorm Stage-L review marked for removal. |
| P2 (polish / docs) | 4 | Bare `:scope-query` accepted with no shape validation; `active-holds` datalog idiom is non-idiomatic; no audit log for sweeper `refresh-scope-eids!`; ADR text describes a "sweeper" the kernel doesn't ship. |
| Test gaps | 6 | Multi-hold overlap, expiry auto-fire, refresh-scope-eids!, malformed scope-query, DSAR-read-still-works, empty scope-query result-set. |

**Headline assessment:** **needs-work-before-Stage-M-continues**. The P0 (single-arity purge form coverage) is structural: the entire defence-in-depth premise of ADR-049 rests on `find-hold-violating-purges` walking every shape of "destroy data" that datahike accepts, and today it walks only the four-tuple `[:db/purge eid]` form. The other findings are P1/P2 and can ride into ADR-050. **Recommendation: fix P0 + P1-1 (the `:pending-review → :released` policy seed) before ADR-050.**

## 2. P0 findings

### P0-1. `purge-targets` recognises only the `[:db/purge eid]` 2-element vector — every other datahike retract/purge form bypasses the middleware

`src/kontor/legal_hold.clj:215-221`:

```clojure
(defn- purge-targets
  "Walk tx-data; return seq of {:tx tx :eid eid} for every :db/purge
   form. Supports [:db/purge eid] vector form."
  [tx-data]
  (->> tx-data
       (filter #(and (vector? %) (= :db/purge (first %))))
       (map (fn [tx] {:tx tx :eid (second tx)}))))
```

The doc string admits the gap ("Supports [:db/purge eid] vector form"). Datahike supports at least four destructive shapes that need gating to make ADR-049's "no purge while held" claim structurally true:

1. `[:db/purgeAttribute eid attr]` — purge one attribute. The entity stays but a held attribute is gone. Today: not seen by `purge-targets`. ADR-049 fails silently.
2. `[:db.purge/attribute eid attr]` / `[:db.purge/entity eid]` — datahike's namespaced purge variants (cf. `datahike.api` purge surface). Today: not seen.
3. `[:db/retractEntity eid]` and `[:db.fn/retractEntity eid]` — full-entity retract. Per ADR-007 this is *not* the same as purge (history is preserved), but for FRCP 37(e) "reasonable steps to preserve" the held data is no longer in the current state. Today: handled by `kontor.sealing/find-silent-retracts` (`sealing.clj:28-32`) *only when the entity is `:posting/posted-at`-marked*. A held but un-posted entity (a draft invoice in the matter scope, a partner row, a `:legal-hold/supporting-doc`'s `:audit-doc`) gets through. ADR-049 does not protect them. The Zubulake/Brookshire fact pattern is exactly "un-posted draft / surveillance buffer / partner-side memo" — i.e., the non-posted-financial slice.
4. Entity-map retracts (`{:db/id eid :foo nil}`) — datahike's standard "set to nil = retract" semantics. Today: not seen.

The composition argument in ADR-049 (line 4811-4813) reads "no time window exists in which a purge can fire without first consulting the hold table." This is true *for one specific tx form*. The other forms have a 100% window.

**Reproducer**:

```clojure
;; Place a hold on ACME.
(lhold/place! conn {... :scope-eids [acme-eid]})
;; Today this succeeds; ADR-049 claims it should not.
@(d/transact conn [[:db/purgeAttribute acme-eid :partner/name]])
;; Or:
@(d/transact conn [[:db/retractEntity acme-eid]])     ; assuming not posted
```

**Proposed fix**:

```clojure
(defn- destructive-targets
  "Walk tx-data; return seq of {:tx :eid :form} for every form that
   destroys data on an existing entity. Handles purge / retract /
   retractEntity / retractAttribute variants — datahike's full
   purge+retract surface, not just the 2-element vector."
  [tx-data]
  (->> tx-data
       (keep (fn [tx]
               (cond
                 ;; [:db/purge eid] / [:db.fn/purge eid] / [:db/retractEntity eid]
                 (and (vector? tx) (#{:db/purge :db.fn/purge
                                       :db/retractEntity :db.fn/retractEntity}
                                    (first tx)))
                 {:tx tx :eid (second tx) :form (first tx)}

                 ;; [:db/purgeAttribute eid attr] / [:db/retract eid attr v]
                 (and (vector? tx) (#{:db/purgeAttribute :db.purge/attribute
                                       :db/retract} (first tx)))
                 {:tx tx :eid (second tx) :form (first tx) :attr (nth tx 2 nil)}

                 ;; entity-map with explicit nil attr value (retract)
                 ;; — left out here; relies on sealing to catch posted shape
                 :else nil)))))
```

Then `find-hold-violating-purges` walks `destructive-targets`. The hold blocks not only entity-purge but also attribute-purge / entity-retract on held eids. For `:db/retract` (silent retract — also handled by sealing for posted), the hold check fires when the entity is held *regardless of posted-state*, which is exactly the Zubulake/Brookshire shape. The remediation message stays the same.

**Why P0**: ADR-049's whole thesis is "the cron beat the hold" can't happen at the kernel layer. A `:db/retractEntity` on a held un-posted invoice fires unblocked today. This is the same Pension Committee fact pattern (sec. 1.4 of research note 23) where the defendant lost on "intent to deprive" because the system *could not* honor the hold structurally. We claim it can; right now it cannot.

## 3. P1 findings

### P1-1. `:pending-review → :released` allowed by status-transition seeds but no approval-policy seed exists for it

`src/kontor/legal_hold.clj:74-79` allows the transition; `approval-policy-seeds` (`:99-133`) seeds three rules for `:placed → :released` and two rules for `:nil → :placed`. **No rules are seeded for `:pending-review → :released`.** The `applicable-policies` lookup in `kontor.status-machine` (`status_machine.clj:131-167`) uses exact `(entity-type, facet, from, to)` match — a `:pending-review → :released` lookup returns the empty vector, so `check-policies` short-circuits as success.

Mechanically: a counsel-only workflow can do `:placed → :pending-review → :released` and the `:pending-review → :released` step has zero SoD enforcement. The same actor who flagged "do we still need this?" can release. This is precisely the FRCP 37(e) / Sedona "two-person rule" concern — releasing a hold is one of the most consequential actions in the entire kernel because the *next* purge fires unblocked. A reviewer should not be able to bless their own release.

**Fix**: extend `approval-policy-seeds` with the SoD + supporting-doc + reason-note triple for `:pending-review → :released`. Symmetrically for `:expired → :released` (admin reaffirms auto-expiry — the auto-expiry was triggered by the sweeper, but a human is signing off on the next-step-which-now-allows-purge).

```clojure
;; :pending-review → :released — same triple as :placed → :released
{:approval-policy/entity-type :legal-hold
 :approval-policy/facet :legal-hold/state
 :approval-policy/transition-from :pending-review
 :approval-policy/transition-to :released
 :approval-policy/rule :no-self-approval ...}
;; ... :requires-supporting-doc, :requires-non-empty-reason-note

;; :expired → :released — admin acknowledges auto-expiry; SoD optional
;;  but supporting-doc + reason-note required
{:approval-policy/entity-type :legal-hold ...
 :approval-policy/transition-from :expired
 :approval-policy/transition-to :released ...}
```

**Why P1, not P0**: the path requires the operator to first transition `:placed → :pending-review` which itself goes through the status machine (and could be SoD-gated in the future). Today nothing tests the `:pending-review` branch (see test-gap inventory below) and nothing in `release!` distinguishes from-state, so the bug is latent.

### P1-2. `find-hold-violating-purges` re-evaluates `:scope-query` once per (target, hold) — O(N × M) datalog calls with no caching across the tx

`src/kontor/legal_hold.clj:231-254`:

```clojure
(mapcat (fn [{:keys [eid] :as p}]
          (keep (fn [hold-eid]
                  (let [in-eids? (d/q '[...] txdb hold-eid eid)
                        in-query? (and (not in-eids?)
                                       (contains?
                                        (expand-scope-query txdb hold-eid)
                                        eid))]
                    ...))
                holds))
        purges)
```

For a tx that purges N entities against M holds with `:scope-query`, this evaluates each hold's query *N times*, even though the result is identical for all targets. `expand-scope-query` materialises a Clojure set via `(into #{} (map first) results)` (`legal_hold.clj:187`) — that set is recomputed every time.

In production this matters in three ways:

1. **GDPR-erasure annual sweep**. A consumer-side helper might submit a single tx with `[:db/purge eid]` for hundreds of stale partner records. With even one query-scoped hold open, the query runs N times. With 10 holds, 10×N. Per the ADR's own performance claim ("annual GDPR-erasure cycles, not hot path") this is the exact scenario where it matters.
2. **Sweeper-emitted retention purges** (ADR-050, forthcoming) — `kontor.retention/sweep!` will produce batched tx-data. Same shape as above.
3. **Memory**: each evaluation materialises a fresh set. A query that matches 1M entities produces 1M-eid sets, allocated N times.

**Proposed fix**: hoist the per-hold scope-query result-set out of the inner loop.

```clojure
(let [purges (purge-targets tx-data)
      holds (active-holds txdb)
      ;; Compute scope-query expansion ONCE per hold.
      hold->query-eids (into {}
                             (map (fn [h] [h (expand-scope-query txdb h)]))
                             holds)]
  (mapcat
   (fn [{:keys [eid] :as p}]
     (keep (fn [hold-eid]
             (let [in-eids? (d/q '[...] txdb hold-eid eid)
                   in-query? (and (not in-eids?)
                                  (contains? (hold->query-eids hold-eid) eid))]
               ...)))))
   purges)
```

**Why P1**: correctness is fine; performance is quadratic in (targets × holds), where today it's at-best linear in (targets + holds). At Stage-M scale (one tenant, a handful of active holds, occasional purges) the user won't see it; at SaaS scale (1000 active holds, batched retention sweeps) it is a real cost.

### P1-3. `:legal-hold/placed-at` (denorm) is the exact pattern Stage L's review marked as a P0 cleanup target

`src/kontor/schema.clj:498-503`:

```clojure
{:db/ident       :legal-hold/placed-at
 :db/valueType   :db.type/instant
 :db/cardinality :db.cardinality/one
 :db/doc         "When this hold was recorded in the system. The
                  authoritative anchor for hold visibility; the
                  hold blocks purges from this instant forward."}
```

Per **ADR-048** (`doc/decisions.md:4689-4756`): `:tx/valid-from` replaces every `:posting/valid-from`-shaped denorm. Per the **Stage-L post-review note** (`doc/review-2026-05-13.md:78-83`): `:credit-hold/placed-at` and `:dunning-pause/placed-at` are flagged as leftovers from `e25e8f8 Remove status-transition denorms; port transactors to tx-meta valid-time`. `:legal-hold/placed-at` is the same pattern — a denorm that duplicates `:tx/valid-from` (set by `with-vt` at `legal_hold.clj:355-357`).

**Place transactor**:

```clojure
placed-at (java.util.Date.)
...
:legal-hold/placed-at placed-at
...
(d/transact conn (kbt/with-vt (into [row] status-tx)
                              (or vt-from placed-at)
                              (or vt-to kbt/forever)))
```

The `placed-at` value is the same instant fed to both the denorm attr and `vt-from`. The denorm is redundant; consumers should resolve it via `kbt/timeline` on `:legal-hold/state` or via `kbt/value-at db hold-eid :legal-hold/state at-instant`.

**Counter-argument**: `:legal-hold/placed-at` is part of the status-history (the `:status-history/changed-at` for the `nil → :placed` row already carries the same data). Removing it costs one extra `value-at` lookup at query time but saves a confusing two-source-of-truth situation. Note 24 §1.5 (period-locking precedent) already established the irrevocability pattern via `:status-history` rather than denorm.

**Recommendation**: drop `:legal-hold/placed-at` in a follow-up ADR (or in the ADR-050 retention commit that revisits these patterns). Update the doc text on `:legal-hold/expires-at` (line 505-510) to read from `:status-history` for the placement anchor. Until then, document explicitly in ADR-049 *why* this denorm survives where the equivalent fields on `:credit-hold` are flagged for removal — currently the ADR makes no such case.

**Why P1**: clean-up debt; not a behavior bug. But landing it now is cheaper than later because every retention-sweeper test in ADR-050 will read `placed-at` to compute "how long has this been on hold."

## 4. P2 findings

### P2-1. `:scope-query` accepted as opaque string; no shape validation at placement time

`src/kontor/legal_hold.clj:162-167`:

```clojure
(defn- read-scope-query
  "Parse the EDN string into a datalog query. Returns nil if the
   string is empty/blank/nil."
  [s]
  (when (and (string? s) (not (clojure.string/blank? s)))
    (edn/read-string s)))
```

A malformed `:scope-query` (e.g. `"[:find ?e :wher [?e :foo]]"` — typo'd `:where`) succeeds at placement: `read-scope-query` returns a vector, `d/q` later throws at *purge time*. The hold is in `:placed` state, claims to scope something, but the scope-query branch is broken. The `:scope-eids` fast-path still works (so `:scope-eids`-only holds are fine), but the auditor's question "what is in scope?" cannot be answered for the query branch.

Worse: `read-scope-query` silently returns nil for blank strings (`legal_hold.clj:166-167`). `expand-scope-query` returns `#{}` for nil-query holds (`:180-181`). So a hold placed with `:scope-query ""` becomes a hold that scopes nothing via the query branch — but the operator believes they placed an inclusive hold. A "purge succeeds because the hold scoped nothing" is exactly the silent-failure mode Pension Committee chastised (note 23 §1.1).

**Recommendation**: validate at placement time.

```clojure
(defn- validate-scope-query! [s]
  (when-not (clojure.string/blank? (or s ""))
    (let [q (try (edn/read-string s)
                 (catch Exception e
                   (throw (ex-info "Invalid EDN in :scope-query"
                                   {:scope-query s :cause (.getMessage e)}))))]
      (when-not (and (vector? q) (= :find (first q)))
        (throw (ex-info ":scope-query must be a [:find ?eid :where ...] vector"
                        {:scope-query s :parsed q})))
      ;; Optionally: assert exactly one find-var, datalog-pattern shape, etc.
      q)))
```

Call from `place!` before transacting. Re-validate in `refresh-scope-eids!` (where a stale query could silently drift between runs).

**Why P2**: the failure mode is a runtime-throw at purge time, which is *louder* than silent-skip. The auditor sees an error. But the operational symptom is "the hold I placed yesterday now refuses purges with cryptic errors" — surfacing it at placement is friendlier and matches the "scope-preview" pattern that note 22 §2.3 calls out.

### P2-2. `active-holds` uses a non-idiomatic `(get #{...} ?s)` + `(some? …)` datalog filter

`src/kontor/legal_hold.clj:189-198`:

```clojure
(defn active-holds
  [db]
  (->> (d/q '[:find [?h ...]
              :where
              [?h :legal-hold/state ?s]
              [(get #{:placed :pending-review} ?s) ?active]
              [(some? ?active)]]
            db)
       set))
```

Two suspect choices: (1) the `(get set value)` returns `value` or `nil` — using `set` membership-as-truthy is idiomatic in regular Clojure but fragile in datalog because some implementations bind `?active` to `false` rather than `nil` for set-miss. Datahike happens to do the Clojure-idiom thing here, but the next line `(some? ?active)` is defensive coding around that exact uncertainty. (2) `(->> ... set)` is redundant — datalog's `:find [?h ...]` collection-binding already returns a distinct set.

**Idiomatic rewrite**:

```clojure
(defn active-holds
  [db]
  (->> (d/q '[:find [?h ...]
              :in $ [?active-state ...]
              :where
              [?h :legal-hold/state ?active-state]]
            db [:placed :pending-review])
       set))    ; still wrap to set for `contains?` later; or replace contains?
```

The `:in $ [?s ...]` binding is the canonical datalog-Clojure "value ∈ set" pattern. Same result; one fewer suspect line.

**Why P2**: works correctly today on datahike; reads as defensive-against-an-issue-that-isn't. Cleaner version makes the intent obvious.

### P2-3. `refresh-scope-eids!` is invisible — no audit-doc, no status-history, no return-shape that lets a caller log

`src/kontor/legal_hold.clj:406-425`. The function transacts new eids into `:legal-hold/scope-eids` and returns an integer count. There is **no `:audit-doc` written**, **no `:status-history` row**, **no `:side-effect-intent` emitted**. From the audit chain's perspective, the hold's scope just silently expanded.

This matters because:

1. The `:legal-hold/scope-preview` attribute (`schema.clj:552-560`) exists precisely to defend against scope-drift mid-litigation. The operational pattern is "counsel signs off on the preview; sweeper extends scope; counsel reviews the drift." Today the sweeper extends scope without leaving a trail of *what* it added on *which date* (the bitemporal trail does record it via tx-time on `:legal-hold/scope-eids`, but you can't easily query "what eids did the 2026-09 sweep add" without scanning tx-history).
2. The "ledger hold notice" Zubulake-III pattern (research note 23 §1.1) is about *notification* — counsel must know "the hold's scope grew to include 47 new entities last night." A consumer-side notifier needs an event hook.

**Fix sketch**: emit a `:audit-doc/type :legal-hold-scope-expansion` row with `:audit-doc/uploaded-at`, and link it from the hold's status-history via `record-status-change-tx-data` on a no-op transition (or via a new facet like `:legal-hold/scope-state`). Alternatively: emit a `:side-effect-intent :type :legal-hold-scope-expansion` (consumer-driven notification).

**Why P2**: not a correctness bug. But research note 23 §6 explicitly flags "DSAR responses keep no audit trail of their own" as a P1 enforcement-risk; the same applies to scope expansions.

### P2-4. ADR text refers to a "scope-cache refresh sweeper" that the kernel does not ship

ADR-049 (`decisions.md:4781-4784`):

> "A sweeper periodically refreshes `:scope-eids` with the query's current result-set so the hot path is purely eid-membership in the common case; the query branch only fires for sweep-lag corrections."

The kernel ships `refresh-scope-eids!` (`legal_hold.clj:406-425`), a one-shot helper. The ADR uses passive voice ("a sweeper … refreshes"); the implementation is "the consumer calls a function." That's fine — consistent with note 24 §3.1 ("consumer schedules; kernel provides predicate") — but the ADR should *say* so, otherwise the next reader looks for a scheduled job and concludes one's missing.

**Recommended ADR edit**: clarify in ADR-049's "Shape after" bullet:

> The kernel ships `refresh-scope-eids!` as a sweeper helper; consumers schedule (`bb hold-sweep` cron, daily) per their cadence. Per ADR-010 the kernel doesn't own scheduling.

This is a documentation fix. The architectural decision (kernel exposes the predicate; consumer drives cadence) is correct and matches ADR-005's tax-provider seam shape.

## 5. Cross-check against research notes 22 / 23 / 24

### Adopted

- **Hybrid scope-shape** (note 22 §2.1, note 24 §3.1). Both `:scope-eids` (fast) and `:scope-query` (expressive) ship. Both checked at write-time. ✓
- **JCR verb shape** (`addHold` → `removeHold` → `getHolds`) as `place!` / `release!` / `entity-held?`. ✓ Adopted unchanged.
- **Datomic excision vocabulary** (note 22 §1) — `:db/purge` already in datahike per ADR-007; new ADR-049 is the gating layer, no vocabulary change. ✓
- **Approval-policy on placement + release** (note 22 §7 design call #4). Both seeded. Release additionally gets `:no-self-approval`. ✓ — *except* `:pending-review → :released` is unseeded (P1-1 above).
- **Bitemporal scope-query-as-of** (note 22 §2.4, note 24 §1.4). `:scope-query-as-of` is an instant attr; `expand-scope-query` honors it via `d/as-of`. ✓ Test `scope-query-value-at-is-bitemporal` confirms the bitemporal read works on `:legal-hold/scope-query`.
- **`:scope-preview`** (note 22 §2.3 / §7 design call #5). Schema attr exists (`schema.clj:552`); `place!` accepts and stores. ✓ — *but* no test verifies its presence, no helper enforces that the preview matches the query's result-set at placement time. The note 22 author's intent (`note 22 §2.3`) was "verify on hold-open that the query, evaluated against the current `db`, returns a superset of the preview's eid list." This is not enforced. Minor; future ADR-050 / dedicated test can cover.

### Deferred / diverged

- **Scope-cache staleness budget** (note 22 §2.3 mitigation 2): "configurable max-staleness (default 24h, override per-hold). The purge-check refuses if the cache is older than the budget and a re-evaluation fails." The implementation runs the live query on *every* purge (`legal_hold.clj:243`) — strictly safer than a staleness budget, because no time-window exists in which a query might be stale. The note 22 mitigation is therefore strictly weaker; the implementation's choice is correct. ✓
- **Counsel-matter privilege defaults** (note 22 §7 design call #10): "ADR-051 should reserve `:counsel-matter` and `:legal-invoice` as future namespaces so they don't clash." ADR-049 does not reserve these. Defer to ADR-051 (privilege).
- **Race-immunity invariant documentation** (note 22 §7 design call #11): "document the race-immunity invariant in ADR-049 so future maintainers don't try to 'fix' it with locks." ADR-049 does *not* document this. Recommend adding one paragraph: datahike's CAS-ordered serial commits mean a `:db/purge` and a `:legal-hold/place` cannot race — whichever commits second sees the other.

### Pushed-back-on by note 24

- **Note 24 §7 item 1**: "`:legal-doc` entity is over-engineered for Stage M — defer." ADR-049 correctly does not introduce `:legal-doc`. ✓
- **Note 24 §7 item 4**: "the privilege-tag `uri-for(doc, requesting-uid)` signature couples the kernel to a user-uid concept." ADR-049 doesn't touch privilege; ADR-051 will. ✓
- **Note 24 §7 item 6**: "Note 17 §5.2 hybrid scope is right but underspecified. … the middleware checks BOTH the cache AND runs the query live." ADR-049 adopts the both-check correctly. ✓

## 6. Cross-check against FRCP 37(e) / Sedona Principles / case-law

Research note 23 catalogues 11 enforcement-failure cases. Tabulate ADR-049's coverage:

| # | Case | Failure mode (research 23 §1) | ADR-049 prevents? | Notes |
|---|---|---|---|---|
| 1 | Zubulake v. UBS Warburg (2003-04) | Cron-vs-hold; backup tape recycling | **Yes** for kernel-resident data | Backup tapes are out-of-kernel; the in-DB equivalent (purge cycle) is blocked. Pending P0-1 fix for `:db/retractEntity`. |
| 2 | Coleman v. Morgan Stanley ($1.45B) | Identical | **Yes**, same caveat | |
| 3 | Keir v. UnumProvident | Backup-vs-hold race | **Yes** for kernel data | "The hold lives in the same store as the data" — research 23 §1.2's exact prediction. |
| 4 | Pension Committee | Multiple — written hold, key players, former-employees | **Partial**. Hold-blocks-purge is structural. Written-hold-notice and key-players-identification are consumer-side concerns. | |
| 5 | Brookshire v. Aldridge | Surveillance auto-overwrite | **No** — out of kernel scope. Surveillance video isn't kernel data. | ADR-049 cannot prevent. |
| 6 | Epic Games v. Google | Chat 24-hr auto-delete | **No** — out of kernel scope. | |
| 7 | N.D. Ohio Slack 2024 | Retention-policy change destroyed data | **No** — out of kernel scope (Slack data isn't in kontor). For an analogue *inside* kontor: a `:retention-policy` change that increases `expiry-action` aggressiveness — this is **the ADR-050 problem**, not ADR-049's. | |
| 8 | Albertsons FTC | Intentional SMS deletion | **No** — out of scope | |
| 9 | Amazon Signal FTC | Ephemeral product default | **No** — out of scope | |
| 10 | Pable v. CTA | Ephemeral messaging | **No** — out of scope | |
| 11 | SEC off-channel sweep ($1.8B) | Records system missed unofficial channels | **No** — out of scope | |

**Net**: 4/11 prevented (the cron-vs-hold and backup-vs-hold subgroup). This matches research 23 §1.5's exact prediction ("What kontor structurally prevents: items 1, 2, 3, 4, 7 of the table"). The implementation is *almost* there. **The P0 fix above closes item 7 partially** (the in-kontor analogue) and protects items 1-4 against the `:db/retractEntity` form that today bypasses the check.

### Sedona Conference Principle 5 ("reasonable, good-faith efforts")

Once P0-1 is fixed, the implementation satisfies Sedona Principle 5 for the in-kernel data slice: a court reviewing the substrate sees that *every* form of destructive write consults the hold table at write-time. This is materially stronger than "we have a cron that runs at 02:00 every night and a hold-application process that runs at random times during the day" (the OneTrust / NetSuite / SAP-ILM shape research 23 §2.1-§2.3 documents). The court's "reasonable steps" standard is satisfied by construction, not by policy.

### FRCP 37(e)(2) "intent to deprive"

The Hoffer (2d Cir. 2025) and Gregory (9th Cir. 2024) decisions confirm that "intent to deprive" — not negligence — gates the worst sanctions. Research 23 §3 raised the concern: a system that physically cannot honor a hold *is* intent-to-deprive evidence. ADR-049 inverts this: a system where the hold structurally cannot be bypassed at the kernel level is *itself* evidence of due-care. Post-P0 fix, this is mechanically true; today, an adversary's expert could demonstrate `[:db/retractEntity eid]` succeeds on a held entity and the inversion fails.

## 7. Performance + operational assessment

### Hot path (writes that are NOT purges)

Zero impact. `validate-and-apply` (`validation.clj:178-182`) calls `assert-no-hold-violating-purges!` which in turn calls `purge-targets`. For tx-data without `:db/purge` forms, `purge-targets` returns an empty seq; `active-holds` is called only as `(let [holds (active-holds txdb)] ...)` *inside* `mapcat` so it's evaluated even when there are zero purges. **This is a bug**: at `legal_hold.clj:232-233`, `active-holds` is evaluated unconditionally even when `purges` is empty. One short-circuit:

```clojure
(let [purges (purge-targets tx-data)]
  (if (empty? purges)
    []
    (let [holds (active-holds txdb)]
      ...)))
```

`active-holds` runs one datalog query — typically returning `#{}` for a tenant without holds. Cost is sub-millisecond at small scale but is paid on *every write*. Short-circuit is free.

### Cold path (purges with active holds)

P1-2's quadratic per-target re-evaluation is the dominant cost. Post-fix it becomes O(targets + holds×|query-result-set|) which is fine for production purges.

### Memory

`expand-scope-query` (`legal_hold.clj:184-187`) materialises the full result-set. A query returning 10M tuples allocates a 10M-eid set per evaluation. For Stage-M scale this is fine; for the 2030 scale where a single hold might match millions of postings, this is unbounded.

**Recommendation**: don't fix in ADR-049; surface in ADR-050's retention sweeper design (which will need bounded eval of similar predicates). The kernel can add a `:scope-query/max-results` cap (default 100k) that throws-loud rather than-silently-overruns.

### Concurrency

Two transactors placing holds on overlapping scopes simultaneously: datahike's serial commit semantics handle this — whichever commits second sees the first's hold. No locking needed. Note 22 §7 item 11 anticipates the question; ADR-049 should document the answer in one sentence.

A `:db/purge` racing a `:legal-hold/place` is the more interesting case. Tx ordering: whichever commits second sees the other in its read-db. (a) Place wins: the next purge sees an active hold and blocks. (b) Purge wins: the purge succeeded, hold is placed afterward — but at the moment of the hold-placement, the data is already gone. **Per case-law (Zubulake / Pension Committee), the duty to preserve attaches the moment the matter is "reasonably anticipated."** A purge that happened the day before the hold was filed is — per case-law — defensible *if* the operator had no reasonable anticipation. ADR-049 doesn't address this; it can't, structurally. Document.

## 8. Forward-compat assessment

### ADR-050 (retention)

The retention sweeper will call `legal-hold/entity-held?` to gate purge eligibility (note 24 §3.2 explicitly designs this). Two concerns:

1. **`entity-held?` is single-eid**, not batched. The sweeper checks hundreds of candidates per run. Batching `entity-held?` to a sweeper-friendly `entities-held? db eids → set` would be ~10 lines and would avoid the same active-holds query being re-run for every candidate. Recommend adding before ADR-050 commits a sweeper.
2. `entity-held?` (`legal_hold.clj:200-213`) calls `active-holds` + per-hold scope-query expansion *inside* `some` — so the same lazy-evaluation issue P1-2 highlighted. For 1000 candidates against 10 query-scoped holds, that's 10,000 query evaluations. Same fix: hoist hold-set + scope-query expansions out of the loop.

### ADR-051 (privilege)

`:legal-hold/supporting-doc` will sometimes itself be privileged (the preservation order from outside counsel is typically `:attorney-client` privileged). The schema is ready for ADR-051's `:audit-doc/privilege` attribute on the supporting-doc; no schema change needed. ✓

### ADR-052 (DSAR)

DSAR-read must return all data on a subject including held data; only erasure is blocked. The current implementation correctly distinguishes — `entity-held?` is consulted by *purge* paths only, not by *read* paths. A DSAR walker (`kontor.dsar/collect` per note 24 §3.4) calling `d/pull` on held entities returns the data unimpeded. ✓ — but there's no test for this. Recommend: add a one-line test asserting `d/pull` against a held entity returns the entity's content.

### ADR-053 (authz, future)

Placing a hold requires "counsel" authority. Today the honor-system: anyone calling `place!` with a `:create/uid` ref claims to be counsel. Per ADR-010 the kernel doesn't own identity. When `kontor-authz` (ADR-053+, research note 26) ships, the consumer's authz layer must gate `place!`. Document the gap in ADR-049: today, the auth boundary is at the application layer; the kernel asserts mechanical invariants only.

## 9. Test-coverage gaps with priority

The 7 shipped tests cover the happy path. Missing:

| Pri | Test | File:line where it would land |
|---|---|---|
| P0 (with the P0-1 fix) | `entity-map-retract-of-held-entity-blocked` — `@(d/transact conn [{:db/id held-eid :partner/name nil}])` should throw. | `test/kontor/legal_hold_test.clj:138` (after `scope-query-blocks-purge`) |
| P0 (with the P0-1 fix) | `retract-entity-of-held-blocked` — `@(d/transact conn [[:db/retractEntity held-eid]])` should throw. | same |
| P1 | `multi-hold-overlap` — two holds reference the same eid; release one; entity is still held by the other. | new deftest |
| P1 | `expiry-auto-fires-then-purge-succeeds` — `:expires-at #inst "2026-05-12"`; sweep-time-based fires; subsequent purge succeeds. Confirms the `:placed → :expired` → `:expired → :released` chain. | new deftest |
| P1 | `pending-review-release-requires-policy` — places hold; transitions `:placed → :pending-review` → tries `:pending-review → :released` *with same actor* → asserts SoD check fires. Today this test would FAIL because P1-1: no policy is seeded. The failing test should drive the P1-1 fix. | new deftest |
| P2 | `refresh-scope-eids-monotonic` — places a query-scoped hold; adds new matching entity; calls `refresh-scope-eids!`; verifies the cache grew. Then removes the entity's qualifying attribute; calls `refresh-scope-eids!` again; verifies the cache did NOT shrink (the helper's monotonicity claim). | new deftest |
| P2 | `dsar-read-against-held-entity-succeeds` — places hold; `d/pull` on held eid returns full content. (Belt-and-suspenders for the future ADR-052 invariant.) | new deftest |
| P2 | `malformed-scope-query-rejected-at-placement` — drives the P2-1 fix. | new deftest |
| P2 | `empty-scope-query-rejected-at-placement` — drives same. | merge with above |

**8 new tests, ~150 LOC**. Cheap to land alongside the P0 fix.

## 10. Recommendation

**Fix P0-1 (purge-form coverage) and P1-1 (pending-review-release policy seed) before starting ADR-050.** These are mechanical:

- P0-1: extend `destructive-targets` per the proposed sketch; one new pred fn; the `validation.clj:182` call site changes from `assert-no-hold-violating-purges!` to e.g. `assert-no-hold-violating-destructive-writes!`. Update tests per the P0 table above (2 new tests).
- P1-1: append 3 approval-policy rows to `approval-policy-seeds` for `:pending-review → :released` and 2 rows for `:expired → :released`. Add one test (P1 table above).

P1-2 and P1-3 can ride into ADR-050 as part of the retention sweeper's design (which will repeat the same loop-pattern; refactoring once is cheaper than refactoring twice).

P2-1 through P2-4 are polish; address them in a clean-up commit when the retention work lands.

After these fixes, ADR-049 is **ship-ready** and Stage M can proceed to ADR-050 (retention). The substrate decisions (where the middleware lives, hybrid scope, write-time query evaluation, bitemporal scope-anchor) are all correctly settled and compose cleanly with what's planned for ADR-050 / 051 / 052.

The implementation is good. The findings are mostly the kind that one expects on a stage's first commit. The Stage-M research notes (22-24) earned their token cost: every adopted shape traces back to a specific finding, and the gaps the review now flags were the items the notes also flagged (notes 22 §2.3 mitigation, 22 §7 #5, 22 §7 #11, 24 §3.1 §3 hold-blocks-non-purge-retract — all explicit in the research, all confirmed to be incomplete in code).

## Sources

- Implementation (commit `3c3f3cb`): `src/kontor/legal_hold.clj:1-426`, `src/kontor/schema.clj:457-566`, `src/kontor/validation.clj:166-188`, `src/kontor/core.clj:75-86`, `test/kontor/legal_hold_test.clj:1-237`.
- ADR text: `doc/decisions.md:4761-4865` (ADR-049).
- Substrate references: `src/kontor/sealing.clj:28-77`, `src/kontor/status_machine.clj:127-304`, `src/kontor/bitemporal.clj:75-318`, `src/kontor/audit_doc.clj`, ADR-007 (`doc/decisions.md:106-122`), ADR-038 (`doc/decisions.md:3012-3186`), ADR-048 (`doc/decisions.md:4689-4756`).
- Research inputs: notes 22 (reference study), 23 (market-pain), 24 (internal-gap), 26 (EACL evaluation).
- Stage-L review precedent: `doc/review-2026-05-13.md:61-83` (the credit-hold denorm finding that the placed-at observation generalizes).
- Industry standards: FRCP 37(e); Sedona Conference *Commentary on Legal Holds, Second Edition* (2021); Zubulake V (229 F.R.D. 422); Pension Committee (685 F. Supp. 2d 456); Epic v. Google (N.D. Cal. Mar 2023); Hoffer (2d Cir. Feb 2025); Gregory (9th Cir. Sep 2024). All cited via the case roundup in research note 23 §1 with primary-source links there.

Date: 2026-05-13. Single-agent review-after for ADR-049. Verification: high — every code claim cites `src/test` file:line; every research-note claim cites note + section; every case-law claim traces to note 23's sourced citations.
