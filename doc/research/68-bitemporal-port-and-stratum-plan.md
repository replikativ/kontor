---
date: 2026-05-17
title: 68 — Kontor bitemporal port + stratum-as-secondary-index plan
status: draft
audience: implementer about to port `kontor.bitemporal` and wire stratum
---

# 68 — Kontor bitemporal port + stratum-as-secondary-index plan

## TL;DR

- **Port is mostly mechanical, not architectural.** 19 public fns split
  ~ as 11 REDUNDANT (10 Allen helpers + `sum-at`-shaped sketch),
  4 WRAPPER (keep the kontor name, delegate), 4 DOMAIN-HELPER
  (`with-vt`/`strip-tx-meta`/`posting-vf`/`tx-data-vf` — keep, but
  retarget at upstream attrs). The resolver itself
  (`assertion-at`/`value-at`/`values-between`/`timeline`) becomes a
  thin call onto `d/valid-at` + supersession.
- **The naming decision is binary.** Either rewrite all `:tx/valid-*`
  call-sites to `:db.valid/*` (one big regex pass plus schema deletion;
  no persisted-data migration concerns inside kontor because none ship)
  OR keep `:tx/valid-*` as a kontor-only alias and write a two-line
  shim. Recommendation §3: **align with upstream** — payoff dominates,
  cost is one commit, and consumer DBs that already have data are
  fixable by a one-time backfill we can ship as a helper.
- **97 call-sites across 26 files** (`grep -c "kbt/" → 97`). The vast
  majority (~78) are `(kbt/with-vt ... kbt/forever)` write wrappers —
  pure mechanical retarget. ~10 sites use `kbt/value-at` (semantic but
  still mechanical — direct delegate). ~9 use `kbt/query-rules` /
  `posting-vf` inside `d/q` — those refactor as the rule moves to
  the upstream built-in name `(valid-at ?tx ?at)`.
- **Stratum-as-secondary wins are concentrated in 3 query paths**
  (`balance/account-balance` ↔ `trial/trial-balance`, `report/compute-
  report`, `aging/aging-rows` via `reconciliation/open-{receivables,
  payables}-by-tx`). Each currently pulls O(all-postings) into a
  Clojure seq and reduces — the canonical SIMD-aggregation shape. Wire
  trial-balance first (smallest blast radius, biggest mechanical
  benefit) then the report engine, then aging. The other paths either
  already use AVET seeks or have data volume too small to matter.
- **Total effort: ~22-30 engineering hours** spread across 5 steps,
  with the naming-decision branch front-loaded so the rest is
  parallelizable. The bitemporal port is the load-bearing step
  (~12-16h); stratum wiring is bounded by stratum-adapter readiness in
  `datahike-bitemporal-v1`'s `src-secondary/` (already on `local-root`
  in `deps.edn`).

---

## 1. Per-function audit of `kontor.bitemporal`

Source: `src/kontor/bitemporal.clj` (400 LoC, ns header at :1-69,
schema :75-92, helpers :109-198, resolver :204-299, period predicates
:329-376, aggregation :382-400).

| Fn / def | Lines | Classification | Upstream replacement | Notes |
|---|---|---|---|---|
| `schema` | :75-92 | **REDUNDANT** | `datahike.constants/system-schema:121-136` ships `:db.valid/from` + `:db.valid/to` as preinstalled system attrs (`{:db/index true}` in `non-ref-implicit-schema:172-173`) | Delete `(d/transact conn kbt/schema)` everywhere. The two attrs exist on every new DB. The kontor schema mirror at `src/kontor/schema.clj:1004-1023` (`bitemporal-tx-attrs`) likewise vanishes from `install!` (`schema.clj:3598`). |
| `forever` | :94-98 | **WRAPPER** | Keep — same `#inst "9999-12-31T..."` sentinel that upstream's `built-in-rules` uses inline (`query.cljc:639,646,652`). Re-home as `kontor.bitemporal/forever` delegate or inline the literal | 78 call-sites pass `kbt/forever` as the `vt-to` default for `with-vt`; cheapest port is to keep the symbol. |
| `dawn` | :100-103 | **REDUNDANT** | Unused outside the docstring (grep confirms 0 callers in src/test/modules) | Delete. |
| `strip-tx-meta` | :109-118 | **DOMAIN-HELPER** | No upstream equivalent — kontor.process needs this for fragment composition (`process.clj:105`) | Keep, but retarget the predicate from `:db/id "datomic.tx"` to also accept upstream tx-meta shapes if needed. The "datomic.tx" tempid is datahike's convention — unchanged. |
| `with-vt` (2- and 3-ary) | :120-136 | **WRAPPER** | Rewrite as `(conj tx-data {:db/id "datomic.tx" :db.valid/from vf :db.valid/to vt})` | This is the load-bearing helper — 78 call-sites. The shape stays identical; only the attribute keys change. **Naming decision §3 directly determines this fn's body.** |
| `posting-vf` | :154-166 | **DOMAIN-HELPER** | Keep — it's a domain query ("what's the vf of this posting's creating tx?"). Inline the rule body to use `:db.valid/from` and `:db/txInstant` directly | 2 call-sites (`report.clj:94`, `l10n_de/datev.clj:208`). The fallback-to-txInstant semantic is kontor-specific (matches upstream's resolver default at `constants.cljc:125-127`). |
| `query-rules` | :168-188 | **DOMAIN-HELPER** | Keep — the `posting-vf` rule has no upstream equivalent (upstream rules operate on tx-eid, not posting-eid). Rewrite the rule body to use `:db.valid/from` | 5 call-sites (`balance.clj:71`, `ledger.clj:116`, `period.clj:236,259`, `posting_test.clj:440`). Could be deleted in favor of routing all reads through `(d/valid-at db cutoff)` — but that's a bigger refactor; defer. |
| `tx-data-vf` | :190-198 | **DOMAIN-HELPER** | Keep — it inspects inbound tx-data BEFORE commit (`period.clj:132`, used by `find-violations`). Retarget the key from `:tx/valid-from` to `:db.valid/from` | 1 call-site. |
| `ensure-history` (private) | :204-213 | **REDUNDANT** (post-port) | Once we delegate to `d/valid-at`, this string-classname sniff is no longer needed — `d/valid-at` does its own composition guard (`api/impl.cljc:150-157` raises on inverted wrap) | Delete with the resolver rewrite. |
| `candidates` (private) | :215-228 | **REDUNDANT** | Subsumed by `mk-vt-pred`'s polygon scan (`api/impl.cljc:200-227`, `find-eav-winner`) | Delete. |
| `in-window?` (private) | :230-234 | **REDUNDANT** | The Allen `interval-overlaps?` built-in (`query.cljc:667-669`) covers it for query use; in-Clojure use can inline the `.compareTo` boilerplate | Delete. |
| `assertion-at` | :236-249 | **WRAPPER** | `(d/pull (d/valid-at db cutoff) [...] eid)` returns the live value; the (vf, vt, tx-instant, tx-eid) metadata comes from one `d/q` against `(d/history db)` on `[?e ?a ?v ?tx]` + tx attrs. **NOT 1:1 — kontor's caller gets a single map; the upstream API returns a filtered db** | Rewrite as a 6-line helper that calls `d/valid-at` + a small `:find` to fetch the metadata. 2 call-sites including `value-at`. |
| `value-at` | :251-256 | **WRAPPER** | `(get (d/pull (d/valid-at db cutoff) [attr] eid) attr)` — direct delegate via `d/valid-at` | 6 call-sites (`payment_application.clj:155`, `invoice/invoice-status-at`, `audit_doc_privilege_test.clj:203`, `legal_hold_test.clj:233`, `collections/credit_hold.clj:46`, `bitemporal_test.clj:×8`). The polygon semantics now come from `d/valid-at`'s supersession check (`api/impl.cljc:229-267`) — STRONGER than today's "most-recent-by-txInstant" tiebreak. Behavior change is intentional and aligns with XTDB v2. |
| `values-between` | :258-291 | **DOMAIN-HELPER** | No direct upstream equivalent; `d/valid-between` returns a FilteredDB over OVERLAP semantics (`api/impl.cljc` — see `mk-vt-overlap-pred:319-323`), but the kontor fn returns a vec of `assertion-at`-shaped maps with breakpoint decomposition. **Keep, rewrite on top of `d/history` + the breakpoint algorithm but delegate the per-breakpoint resolution to `d/valid-at`** | 1 call-site (`bitemporal_test.clj:181`). Niche enough that a Clojure-side polygon decomposition is fine; no real callers in production code. Could be deferred until a consumer asks. |
| `timeline` | :293-299 | **WRAPPER** | Same shape as `candidates`; rewrite as a 6-line `d/q` against `(d/history db)` using `:db.valid/from` / `:db.valid/to` | 1 call-site (`bitemporal_test.clj:164`). Useful for "show timeline" UIs even if no production callers yet — keep. |
| `as-of-bitemporal` | :305-320 | **REDUNDANT** | `(d/valid-at (d/as-of db tx) vt)` is the canonical composition (`api/impl.cljc:141-148` documents the wrap order — vt outermost) | Delete after migration; the 0 call-sites in src/ (grep: only the docstring of `core.clj:14`) make this safe. |
| `vt-contains?` | :329-334 | **REDUNDANT** | `(interval-contains? ?af ?at ?bf ?bt)` (`query.cljc:674-676`) for query use; in-Clojure use can stay as a 1-liner if any non-query callers surface | 2 call-sites both in tests (`bitemporal_test.clj:199-200`). Delete. |
| `vt-strictly-contains?` | :336-339 | **REDUNDANT** | `(interval-strictly-contains? …)` (`query.cljc:678-680`) | 0 call-sites. Delete. |
| `vt-overlaps?` | :341-345 | **REDUNDANT** | `(interval-overlaps? …)` (`query.cljc:667-669`) | 2 call-sites in tests. Delete. |
| `vt-equals?` | :347-350 | **REDUNDANT** | `(interval-equals? …)` (`query.cljc:670-672`) | 0 call-sites. Delete. |
| `vt-precedes?` | :352-355 | **REDUNDANT** | `(interval-precedes? …)` (`query.cljc:682-683`) | 1 call-site in tests. Delete. |
| `vt-strictly-precedes?` | :357-359 | **REDUNDANT** | `(interval-strictly-precedes? …)` (`query.cljc:684-685`) | 1 call-site in tests. Delete. |
| `vt-immediately-precedes?` | :361-364 | **REDUNDANT** | `(interval-immediately-precedes? …)` (`query.cljc:687-688`) — alias of `interval-meets?` (`:698-699`) | 1 call-site in tests. Delete. |
| `vt-succeeds?` | :366-368 | **REDUNDANT** | `(interval-succeeds? …)` (`query.cljc:690-691`) | 0 call-sites. Delete. |
| `vt-strictly-succeeds?` | :370-372 | **REDUNDANT** | `(interval-strictly-succeeds? …)` (`query.cljc:692-693`) | 0 call-sites. Delete. |
| `vt-immediately-succeeds?` | :374-376 | **REDUNDANT** | `(interval-immediately-succeeds? …)` (`query.cljc:695-696`) | 0 call-sites. Delete. |
| `sum-at` | :382-400 | **DOMAIN-HELPER** | No upstream equivalent — it's an accounting-specific aggregation. **Rewrite to call `(d/valid-at db cutoff)` then route through a normal `d/q` or balance.clj's reduction** | 0 production call-sites (sketch / never wired). Either delete or keep as the public "sum any attr at vt" primitive. **Recommendation: delete** — `balance/account-balance` already covers the only real use, and `sum-at`'s API doesn't fit kontor's commodity-aware money model. |

### Count summary

- **REDUNDANT (delete):** 14 fns — `schema`, `dawn`, `ensure-history`,
  `candidates`, `in-window?`, `as-of-bitemporal`, 9× `vt-*` predicates,
  `sum-at`. Total ~110 LoC removed.
- **WRAPPER (keep name, delegate):** 4 fns — `forever`, `with-vt`,
  `assertion-at`, `value-at`, `timeline`. Total ~50 LoC retained, mostly
  rewritten to ~10 LoC delegating to upstream.
- **DOMAIN-HELPER (keep, retarget):** 5 fns — `strip-tx-meta`,
  `posting-vf`, `query-rules`, `tx-data-vf`, `values-between`. Total
  ~80 LoC retained with key renames.

Post-port `kontor.bitemporal` shrinks from 400 LoC → ~140 LoC and
becomes a thin domain layer over `d/valid-at` / `d/valid-between` plus
the kontor-specific posting helpers.

---

## 2. Call-site walk (97 sites, classified)

Counts derived from `grep -rn "kbt/" src/ test/ modules/` (the
`bitemporal.clj` self-reference at :46/:50 is in the docstring and
excluded). Sorted by module.

### `src/kontor/` (53 sites)

| File:line | What it does | Class |
|---|---|---|
| `audit_doc.clj:262-265` | `kbt/with-vt (reclassify-…) (or vt-from now) (or vt-to kbt/forever)` | **Mechanical** |
| `balance.clj:71` | `kbt/query-rules` inside `pull-postings-against`'s `:where` (`posting-vf ?p ?vf`) | **Refactor** — rule keeps its name but the body retargets to `:db.valid/from` |
| `bitemporal.clj:46, :50` | self-references in docstring | **Mechanical** (delete the file) |
| `core.clj:14` | re-export comment in ns docstring | **Mechanical** (update comment) |
| `dsar.clj:381-384` | `kbt/with-vt … (or vt-to kbt/forever)` for `file-request!` | **Mechanical** |
| `dsar.clj:448-451` | same shape for `advance-state!` | **Mechanical** |
| `import_/beancount.clj:260-269` | `kbt/with-vt (into [tx-base] postings) date kbt/forever` | **Mechanical** |
| `invoice.clj:257-259` | `kbt/with-vt tx-data (or vt-from eff-date now) (or vt-to kbt/forever)` for `send!` | **Mechanical** |
| `invoice.clj:301-310` | same for `mark-paid!` | **Mechanical** |
| `invoice.clj:390-399` | same for `cancel!` | **Mechanical** |
| `invoice.clj:470-472` | same for `post-with-tax!` | **Mechanical** |
| `ledger.clj:116` | `kbt/query-rules` inside `postings-against`'s `:where` | **Refactor** (same as `balance.clj:71`) |
| `legal_hold.clj:511-513` | `kbt/with-vt (place-tx-data …)` | **Mechanical** |
| `legal_hold.clj:558-560` | `kbt/with-vt (release-tx-data …)` | **Mechanical** |
| `payment_application.clj:155` | `kbt/value-at db eid :invoice/status cutoff` | **Refactor** — direct delegate to `(get (d/pull (d/valid-at db cutoff) [:invoice/status] eid) :invoice/status)` |
| `payment_application.clj:225-227` | `kbt/with-vt` in `apply-payment!` (2- and 3-arity branches) | **Mechanical** |
| `payment_application.clj:334-336` | `kbt/with-vt` in `reverse-application!` | **Mechanical** |
| `period.clj:132` | `kbt/tx-data-vf tx-data` (key rename inside `find-violations`) | **Refactor** — `tx-data-vf` body retargets to `:db.valid/from` |
| `period.clj:236, :259` | `kbt/query-rules` inside `draft-postings-in-range` + `range-trial-balance` | **Refactor** (same as `balance.clj:71`) |
| `posting.clj:371-373` | `kbt/with-vt (into [tx-base] postings) effective-date kbt/forever` in `build-transaction` | **Mechanical** |
| `posting.clj:395` | `kbt/with-vt tx-data vf (or vt-to kbt/forever)` in `post-transaction-tx-data` | **Mechanical** |
| `posting.clj:743-744` | `kbt/with-vt … effective-date kbt/forever` for stock receipt | **Mechanical** |
| `posting.clj:783-784` | same for stock issue | **Mechanical** |
| `process.clj:105` | `kbt/strip-tx-meta (:tx-data r)` in fragment composition | **Mechanical** |
| `process.clj:132-133` | `kbt/with-vt tx-data vt-from vt-to` (2- and 3-arity) | **Mechanical** |
| `report.clj:94` | `kbt/posting-vf db p` to resolve a posting's vf from its tx | **Refactor** — `posting-vf` body retargets |
| `retention.clj:458-461` | `kbt/with-vt (define-policy-tx-data …)` | **Mechanical** |
| `retention.clj:516-519` | `kbt/with-vt (activate-policy-tx-data …)` | **Mechanical** |
| `retention.clj:557-560` | `kbt/with-vt (supersede-policy-tx-data …)` | **Mechanical** |
| `schema.clj:1004-1023` | declares `bitemporal-tx-attrs` (the duplicate) and references in `install!` at :3598 | **Domain-decision** — the duplicate vanishes; see §3 |
| `validation.clj:20` | docstring reference | **Mechanical** (update comment) |

### `modules/` (44 sites)

| File:line | What it does | Class |
|---|---|---|
| `inventory/ops.clj:103-104, :433-435, :516-518, :638-641` | `kbt/with-vt … kbt/forever` for stock-move ops | **Mechanical** (×4) |
| `inventory/reservation.clj:55, :211-213, :332-335` | `kbt/with-vt` + `kbt/forever` as as-of-valid sentinel | **Mechanical** (×3) |
| `inventory/core.clj:379` | `(or as-of-valid kbt/forever)` sentinel | **Mechanical** |
| `invoice/posting.clj:335-336` | `vt (or :vt-to opts kbt/forever)` + `kbt/with-vt` | **Mechanical** |
| `l10n_de/datev.clj:208` | `vf (kbt/posting-vf db p)` for DATEV export | **Refactor** (posting-vf retarget) |
| `collections/credit_hold.clj:46` | `(kbt/value-at db hold-eid :credit-hold/state as-of)` | **Refactor** — direct delegate |
| `collections/credit_hold.clj:235-238, :297-300` | `kbt/with-vt … kbt/forever` for place/release | **Mechanical** (×2) |
| `collections/writeoff.clj:133-135` | `kbt/with-vt … kbt/forever` | **Mechanical** |
| `lease/core.clj:129-132` | `kbt/with-vt (define-lease-tx-data) … kbt/forever` | **Mechanical** |
| `lease/runner.clj:300, :557` | `:vt-to kbt/forever` in process opts | **Mechanical** (×2) |

### Tests (8+ direct sites in `test/kontor/`, plus 4 in `modules/*/test/`)

| File | Sites | Class |
|---|---|---|
| `bitemporal_test.clj` | 30 references (whole file is the spec for the resolver) | **Refactor** — most tests should keep passing verbatim after the body retargets; a few that check the specific tiebreak ("most-recent-by-txInstant") need adjusting to the new supersession semantic (tx-id-ordered) |
| `posting_test.clj:11, :434, :440` | `kontor.bitemporal/query-rules` in a rule-injection test | **Refactor** |
| `payment_application_test.clj:303, :309, :311` | requires `kbt/schema`, transacts it | **Mechanical** — drop the schema transact (it's already system-installed) |
| `audit_doc_privilege_test.clj:201-203` | `kbt/value-at` | **Refactor** — body change |
| `legal_hold_test.clj:233-235` | `kbt/value-at` | **Refactor** |
| `composition_test.clj:89, :194, :218-219` | `kbt/with-vt`, `kbt/forever` | **Mechanical** |
| `schema_test.clj:75, :92` | docstring text checks (mention `:tx/valid-from`) | **Mechanical** (update assertions) |

### Aggregate counts by class

- **Mechanical:** ~78 sites (~80% — write-side `with-vt` wrappers,
  `forever` defaults, `strip-tx-meta`, ns comments, test transacts of
  `kbt/schema`)
- **Refactor:** ~17 sites (~17% — `value-at`, `posting-vf`, the rule
  body inside `query-rules`, `tx-data-vf` key rename; tests that
  exercise the resolver tiebreak semantic)
- **Domain-decision:** ~2 sites (the schema deletion in
  `schema.clj:1004-1023, :3598`; whether to keep `values-between` /
  `timeline` / `query-rules` or migrate callers to `d/valid-at`-based
  shapes)

---

## 3. The naming decision (`:tx/valid-from` vs `:db.valid/from`)

### Where the kontor name shows up

- Declared as `bitemporal-tx-attrs` at
  `src/kontor/schema.clj:1004-1023` (the `:tx/valid-from` +
  `:tx/valid-to` definition).
- Referenced by `install!` at `src/kontor/schema.clj:3598`.
- Used in `kbt/with-vt`'s tx-meta map at
  `src/kontor/bitemporal.clj:131,135-136`.
- Read in `kbt/posting-vf` at `src/kontor/bitemporal.clj:165`.
- Read in `kbt/query-rules` at `src/kontor/bitemporal.clj:184-188`.
- Read in `kbt/tx-data-vf` at `src/kontor/bitemporal.clj:197`.
- Read in `kbt/candidates` at `src/kontor/bitemporal.clj:224-225`.
- Mentioned by name in 10+ docstrings across the codebase (cheap
  s/replace).

**Datahike-side fact:** `:db.valid/from` and `:db.valid/to` are
pre-installed system attrs (`datahike/constants.cljc:121-136`,
indexed via `non-ref-implicit-schema:172-173`), shipped on every new
DB by `bitemporal-v1`. Both attrs are tx-attached, AVET-indexed, and
type `:db.type/instant` — identical to kontor's `:tx/valid-from`
schema, modulo the namespace.

### Three options re-stated

1. **Align with upstream** — rewrite `:tx/valid-from` → `:db.valid/from`
   everywhere. Delete `bitemporal-tx-attrs`. One mechanical regex pass
   over src/ + modules/ + test/, plus a release note for downstream
   consumers (who must run a one-time backfill).

2. **Keep `:tx/valid-from`, add alias layer** — the kontor attrs stay
   declared; `with-vt` writes BOTH (`:tx/valid-from` and
   `:db.valid/from` to the same value) for back-compat. Reads keep
   going through `:tx/valid-from`. Storage cost: doubles the
   bitemporal attr count. Confuses the planner re. which attr the AVET
   seek lands on.

3. **`:db.valid/from` for new code; keep `:tx/valid-from` for back-
   compat** — dual schema, dual-write (or migration), conditional reads
   that try both. Worst of both worlds.

### Recommendation: **Option 1 (align)**

**Rationale**:

- The kontor `:tx/valid-from` was always a stopgap — see the ADR-048
  framing ("on top of stock datahike" / "matches XTDB v2's bitemporal
  semantics") and the explicit "future per-datom valid-time" hedge at
  `kontor/bitemporal.clj:26-29,40-41`. `bitemporal-v1` lands the
  blessed version with the same semantics.
- **No persisted-data migration concern inside kontor itself.** The
  kernel ships no preloaded data; the schema lives in `schema.clj` and
  is materialized fresh per consumer install. Migration burden falls
  on consumers who already use kontor; the kontor team can ship a
  100-LoC `kontor.migrate.bitemporal-v1` helper that walks tx-history
  and copies `:tx/valid-{from,to}` → `:db.valid/{from,to}` (via
  `:db/add` + `:db/retract` on the tx entities). That helper is
  trivial because both attrs have identical types.
- **The cost of option 2 is paid forever.** Every test and every doc
  has to explain "we have two attrs that mean the same thing." Future
  cross-consumer code (pg-datahike, scriptum, proximum, stratum
  adapters — all of which speak `:db.valid/*` per the bitemporal-v1
  ADR) would need to know about the kontor-only alias.
- **Option 1 unlocks `d/valid-at`'s `IValidTimeAware` pushdown for
  free.** Per `datahike/index/secondary/secondary.cljc:112-137`, a
  vt-aware secondary (stratum, eventually scriptum + proximum) pushes
  the vt filter via the upstream attr names; if kontor uses
  `:tx/valid-from`, no pushdown happens.

**User-decision point (USER, please confirm)**: do any kontor consumers
already have meaningful persisted data carrying `:tx/valid-from`? If
yes, agree on the migration helper before deleting the old attrs. If
no (the likely answer per the project culture of "rebuild from source
when you change shape"), the align-and-delete approach is one commit.

### What the migration helper looks like (if option 1, deferred)

```clojure
(defn migrate-tx-meta-to-system-attrs!
  "Walk the tx-history and copy :tx/valid-{from,to} onto :db.valid/*.
   One-shot; safe to re-run. Returns the count of migrated txes."
  [conn]
  (let [hist (d/history (d/db conn))
        txes (d/q '[:find ?tx ?vf
                    :in $
                    :where [?tx :tx/valid-from ?vf]] hist)
        copies (mapv (fn [[tx vf]]
                       (let [vt (d/q '[:find ?vt . :in $ ?tx :where
                                       [?tx :tx/valid-to ?vt]] hist tx)]
                         (cond-> {:db/id tx :db.valid/from vf}
                           vt (assoc :db.valid/to vt))))
                     txes)]
    (when (seq copies) (d/transact conn copies))
    (count copies)))
```

A second one-shot retracts the old attrs after verification.

---

## 4. Stratum-as-secondary-index opportunities

Per `secondary.cljc:112-154`, a secondary index implements
`IValidTimeAware` to take the vt push-down fast path; stratum's
implementation at
`datahike-bitemporal-v1/src-secondary/datahike/index/secondary/stratum.clj:619-..`
does so, plus implements `IColumnarAggregate` (`stratum.clj:606-617`)
which fuses entity-filter + aggregate in one SIMD-vectorized pass.
That's the leverage to consider per query path.

### 4.1 `kontor.trial/trial-balance` — **TOP CANDIDATE**

- **File:** `src/kontor/trial.clj:25-50`
- **Shape:** iterates all account eids (`all-account-eids` at :18-23
  via `[?a :account/path _]`), then for **each** account calls
  `balance/account-balance` which in turn calls
  `pull-postings-against` (`balance.clj:59-83`) — a `d/q` + per-row
  `d/pull` (3 attrs + nested) + a per-row Clojure-side filter
  (`include-posting?` at `:42-57`).
- **Data volume per call:** O(`accounts × postings-per-account`). On
  a real book this is 100-1000 accounts × 10²-10⁴ postings each —
  **10⁴-10⁷ postings touched per trial balance**. Today's
  implementation runs ~hundreds of thousands of `d/pull` calls and
  accumulates BigDecimals in a Clojure reduce.
- **Stratum benefit:** push the aggregation down. `trial-balance`
  becomes ~one stratum query: `SELECT account_eid, commodity_eid,
  SUM(amount) FROM postings WHERE valid_from <= ? AND tx_state = ?
  GROUP BY account_eid, commodity_eid`. The stratum vt-aware
  secondary's zone-map pruner skips chunks outside the vt window
  (per `stratum.clj:619-630`). The IColumnarAggregate fast path
  (`stratum.clj:606-617`) handles the sum + group-by without
  materializing rows. Realistic speedup: **10×-100×** for the
  reduction step; the bigger win is eliminating the per-account
  inner loop.
- **Engineering cost: Medium.** ~1-2 days. Need (a) a stratum-
  registered posting index keyed on `(account, commodity, amount)`
  with the vt-mode option, (b) a new `trial-balance-via-secondary`
  function that detects the registered index and routes through it,
  (c) fallback to the existing Clojure reduction if no secondary
  registered. The fallback semantics are already what kontor ships,
  so the new path is opt-in.
- **Rank: 1.**

### 4.2 `kontor.report/compute-report` — **HIGH-LEVERAGE**

- **File:** `src/kontor/report.clj:233-280`
- **Shape:** `all-pids (d/q '[:find [?p ...] :where [?p :posting/
  account _]] db)` (:262) — materializes EVERY posting eid into a
  Clojure seq, then `(mapv #(pull-posting db %) all-pids)` (:263) —
  a `d/pull` per posting with nested account + tag refs. Then a
  Clojure-side filter on `:valid-from in window` + `:tx-state` +
  `:ledger-eid`, then per-engine reduction.
- **Data volume per call:** O(`all-postings`). For a year-end
  UStVA on a book with 100k postings, this is the worst-case OLAP
  shape in kontor.
- **Stratum benefit:** the `:account-codes` and `:tax-tags` engines
  both reduce to "sum amounts WHERE account.code matches prefixes OR
  posting.tags ∩ wanted-tags is non-empty, GROUP BY engine-line".
  Both fit SQL. Engine selection can be lifted into a query builder;
  the IColumnarAggregate fast path makes the actual reduce ~free.
  Per `stratum.clj:606-617` the entity-filter (e.g. a ledger filter
  bitset) is pushed as a `:fn` predicate on the `:eid` column,
  fused into the aggregate loop. Realistic speedup: **10×-50×**
  because the per-posting `d/pull` (which is the actual hot path
  today) goes away entirely.
- **Engineering cost: Medium-High.** ~2-3 days. The report engine
  is more polymorphic than trial-balance (engines, signs, tag
  matching), so the SQL builder is nontrivial. Tag matching wants
  the tags denormalized onto each posting in the secondary index, or
  the index needs an array-column for tags + array-contains
  predicate (stratum supports the latter per its SQL grammar).
- **Rank: 2.**

### 4.3 `kontor.aging/aging-rows` via `reconciliation/open-{receivables,payables}-by-tx` — **HIGH-LEVERAGE**

- **Files:** `src/kontor/aging.clj:57-89`,
  `src/kontor/reconciliation.clj:133-203` (AR) and `:205-260` (AP).
- **Shape:** two `d/q`s per call. The first
  (`reconciliation.clj:147-160`) pulls all postings on AR-coded
  accounts joined with their tx external-id / partner / date /
  journal-type / state — every row of every sale. The second
  (`reconciliation.clj:176-184`) pulls all settling postings. Then
  two Clojure-side reductions group-by-tx and subtract settlements.
- **Data volume per call:** O(`AR-postings + settling-postings`).
  For a year of operation on a mid-SMB book: 1k-10k transactions ×
  1-3 postings each → 3k-30k rows. **Likely the second-hottest
  reporting path** after trial-balance.
- **Stratum benefit:** the two queries become two stratum
  aggregations (group + sum), and the final "subtract settlements"
  step becomes one SQL JOIN with a `SUM` aggregate. Avoids the
  Clojure-side per-row reduce. Stratum's ASOF JOIN
  (`secondary/stratum.clj:..` per the 62-* research notes) could
  even handle the as-of-date semantics natively.
- **Engineering cost: Medium.** ~1-2 days. Needs the partner/date
  denorm columns in the secondary's posting index, plus the
  settles-join expressed in SQL. Aging buckets are a simple `CASE`.
- **Rank: 3.**

### 4.4 `kontor.financial-statements/compute-statement` — **MEDIUM**

- **File:** `src/kontor/financial_statements.clj:1-120` (P&L and BS
  builders).
- **Shape:** delegates entirely to `report/compute-report`; the
  benefit comes for free once §4.2 lands. The bucketing logic at
  :88-120 stays in Clojure.
- **Engineering cost: Zero** if §4.2 lands first; rides on it.
- **Rank: 4 (zero-cost win once §4.2 ships).**

### 4.5 `kontor.balance/account-balance` — **MEDIUM**

- **File:** `src/kontor/balance.clj:89-118`
- **Shape:** `pull-postings-against` (the same as the trial-balance
  per-account inner loop). For a single-account query the data
  volume is only the postings against that one account —
  10²-10⁴ rows in real books. Useful, but not enormous.
- **Stratum benefit:** if the trial-balance path already routes
  through stratum, account-balance gets pushed-down filtering for
  free (entity-filter on `account_eid` per
  `stratum.clj:614-616`). Standalone benefit is modest because the
  query is small.
- **Engineering cost: Low** if §4.1 lands first; ~0.5 day to add
  the secondary route.
- **Rank: 5 (rides on §4.1).**

### 4.6 `kontor.ledger/postings-against` — **LOW**

- **File:** `src/kontor/ledger.clj:84-149`
- **Shape:** same query shape as `balance/account-balance` (per-
  account scan via `posting-vf`), but presents the rows as the
  account-statement view (no aggregation). Stratum's strength is
  aggregation; for raw row-presentation the AVET seek is already
  fine.
- **Engineering cost: Low** but **little payoff**. Skip unless a
  consumer surfaces a complaint.
- **Rank: 6 (not worth wiring first).**

### 4.7 `kontor.reconciliation/suggest-match` — **NOT A FIT**

- **File:** `src/kontor/reconciliation.clj` (subset-sum search for
  Sammelüberweisung matching).
- **Shape:** combinatorial, not bulk-aggregate. Stratum doesn't
  help — this is algorithmic.
- **Rank: out of scope.**

### Ranked recommendation: wire in this order

1. **Trial balance via stratum** (§4.1). Smallest API surface
   change, biggest mechanical speedup. Sets the pattern.
2. **Report engine via stratum** (§4.2). Larger refactor but
   highest hot-path volume; financial-statements rides on it (§4.4).
3. **Aging via stratum** (§4.3). The third real OLAP shape;
   moderate effort, real customer-visible value (the collections
   workflow drives this report).
4. Account-balance (§4.5) opportunistically rides on (1).
5. Ledger postings-against (§4.6), reconciliation matchers (§4.7) —
   defer indefinitely.

### Cross-cutting prerequisites

- **A stratum index registration helper in kontor.** ~50 LoC. Wraps
  `:db.secondary/attrs` config so consumers declare which posting
  attrs to project into stratum, plus the vt-mode flag. Documented
  ADR.
- **A `kontor.olap` namespace** (or `kontor.report.olap`) that
  exposes the "if stratum index is registered, use it; else fall
  back to datalog reduce" router. All 3 query paths share this.
- **A benchmark harness** (in `dev/`) that measures
  trial-balance, compute-report, and aging-rows against varying
  posting counts (10², 10³, 10⁴, 10⁵, 10⁶) — establishes the
  baseline before stratum wiring and validates the speedup
  afterward.

---

## 5. Coordinated execution plan

Ordered steps; durations are real-clock hours of focused engineering
time (not including review-after agents per ADR-037).

### Step 0 — Setup + decision (1-2h)

- Confirm `deps.edn` already targets
  `:local/root "../datahike-bitemporal-v1"` (it does — see line ~52
  of `deps.edn`).
- Run `bb test` to capture a green baseline. Snapshot the test
  count + time.
- **Get user sign-off on §3 (the naming decision).** Recommendation:
  Option 1 (align). All downstream steps assume this.
- Open an ADR draft in `doc/decisions.md` (next number; presumably
  ADR-NN with N around the next free slot) titled "Bitemporal port
  to datahike system attrs".
- **Dependencies:** none.
- **What could go wrong:** baseline doesn't actually compile against
  the datahike-bitemporal-v1 branch — fix any path drift first.
- **Rollback:** none; this step makes no changes.

### Step 1 — Bitemporal port (12-16h)

Sub-steps in order:

**1a. Rename schema constants (1h).** Edit
`src/kontor/schema.clj:1004-1023` to delete `bitemporal-tx-attrs`;
edit `:3598` to drop the inclusion. Add a `(deprecated)` comment
pointing to `:db.valid/{from,to}`. `bb test` — most tests will fail
(`:tx/valid-from` is read all over). Expected.

**1b. Rewrite the resolver + `with-vt` (4-5h).** Replace the body
of `src/kontor/bitemporal.clj`:
- `with-vt` writes `:db.valid/from` / `:db.valid/to` on the tx-meta
  map.
- `value-at` / `assertion-at` delegate to `d/valid-at` (`api/impl.cljc:269-317`).
  Behavior change: tiebreak goes from "max-by-:db/txInstant" to
  "max-by-tx-id" — semantically identical post-DH-11
  (`research/67`-issue-DH-11 confirmed strict-monotonic
  `:db/txInstant`), but tests need re-check.
- `timeline`, `values-between` keep their shape, just retarget the
  `:tx/valid-from` → `:db.valid/from` reads.
- `posting-vf`, `query-rules`, `tx-data-vf` retarget the attr names
  in their bodies.
- Delete the 14 REDUNDANT fns (`dawn`, `as-of-bitemporal`,
  `ensure-history`, `candidates`, `in-window?`, all 9 `vt-*`
  predicates, `sum-at`).
- Update the ns docstring.

**1c. Migrate `kbt/with-vt` call sites (1-2h).** The 78 mechanical
sites need no edits if `with-vt`'s API is unchanged. `bb test` to
catch anything that depends on the deleted helpers.

**1d. Migrate refactor sites (4-6h).** The ~17 sites that touch
`value-at`, `posting-vf`, `query-rules`, `tx-data-vf` — verify
each. Adjust tests that lock in the "max-by-txInstant" tiebreak to
expect "max-by-tx-id" (search for `:db/txInstant` in
`bitemporal_test.clj`).

**1e. Test transactor migration (1h).** Drop
`(d/transact *conn* kbt/schema)` from test fixtures
(`bitemporal_test.clj:13`,
`payment_application_test.clj:311`); the system attrs are
preinstalled.

**1f. Full `bb ci` (1h).** All tests + cljfmt + clj-kondo. Iterate
on any failure surface.

- **Dependencies:** step 0 (decision).
- **What could go wrong:**
  - **Supersession semantic differs from old resolver.** The new
    polygon uses tx-id ordering for tiebreaks; the old resolver
    used `:db/txInstant`. Now that DH-11 forces strict-monotonic
    `:db/txInstant`, the two orderings are bijective — but a test
    that injects backdated `:db/txInstant` (rare) would break.
    Grep for explicit `:db/txInstant` in tests; expected hit count
    = 0 or 1.
  - **`(d/valid-at db <past-vt>)` returns no datoms for txes
    written before the schema landed.** Mitigation: txes without
    `:db.valid/from` are treated as `[-∞, +∞)` per
    `api/impl.cljc:179-198`. Verified.
  - **Process composition (`run-process`) loses vt because
    `strip-tx-meta` strips upstream attrs too.** Mitigation: kontor's
    fragments use the kontor convention exclusively; once retargeted
    to `:db.valid/*` the strip-then-rewrap pattern still works.
- **Rollback:** `git checkout main -- src/kontor/bitemporal.clj
  src/kontor/schema.clj` reverts; no commits are pushed inside the
  step.

### Step 2 — Stratum integration: trial-balance (3-4h)

**2a. Add `org.replikativ/stratum {:mvn/version "0.3.69"}` to
`deps.edn`** (2-line edit). Verify `bb test` still green.

**2b. Write the kontor-side secondary registration helper** in
`src/kontor/olap.clj` (~50 LoC). Takes a config map, registers a
stratum-backed `:db.secondary/attrs` for `:posting/*`. Documented
ADR.

**2c. Write `kontor.trial/trial-balance-via-olap`** (~80 LoC). If
the secondary is registered, route through the columnar aggregate;
else delegate to today's `trial-balance`.

**2d. Tests** in `test/kontor/trial_olap_test.clj` — assert
parity between the two implementations on a 10⁴-posting fixture.
Benchmark both with `:bench` tag.

- **Dependencies:** step 1 (bitemporal port). Without aligned
  attrs the secondary push-down doesn't work.
- **What could go wrong:**
  - **Stratum's IValidTimeAware path only works in vt-mode** per
    `datahike-bitemporal-v1/src-secondary/datahike/index/secondary/stratum.clj:619-630`.
    The kontor-side config must opt in.
  - **Stratum 0.3.69's IColumnarAggregate may not yet support
    multi-column GROUP BY.** Read its release notes; if it
    doesn't, fall back to a SIMD-aggregate over single-column
    grouping then Clojure-side rebucket. Still wins.
- **Rollback:** drop the deps.edn line + delete the new ns.

### Step 3 — Stratum integration: report engine (5-7h)

**3a. Project tag and ledger denormalizations** into the stratum
index (per ADR write-up, the index needs columns for `account.code`,
`account.type`, all tag keywords as a stringified array, ledger
eid, valid-from, state).

**3b. Rewrite the two engines** (`:account-codes` and `:tax-tags`)
to issue stratum aggregates when a secondary is registered, else
fall back. Keep the `defmulti` shape.

**3c. Tests + benchmark** — UStVA-shape parity + speedup
measurement.

- **Dependencies:** step 2 (the registration helper + the OLAP
  router).
- **What could go wrong:** account-code prefix matching in SQL is
  awkward (no `LIKE 'pat%'` ergonomics in stratum's grammar?).
  Mitigation: pre-resolve the prefix-match into an account-eid bitset
  in Clojure, push as the entity-filter. Stratum's IColumnarAggregate
  takes the bitset (`stratum.clj:609-617`).
- **Rollback:** keep the new engine paths behind a feature flag;
  fall back if the flag is off.

### Step 4 — Stratum integration: aging (3-4h)

**4a. Lift `open-receivables-by-tx` / `open-payables-by-tx`** to
two stratum aggregates each (gross-AR group-sum, settlements
group-sum), join in Clojure or via stratum if its JOIN grammar
fits.

**4b. Tests + benchmark.**

- **Dependencies:** step 2 (the router).
- **What could go wrong:** the cross-tx settlement join is harder
  than the trial-balance pattern. If stratum doesn't yet handle the
  JOIN, do both aggregates separately and join in Clojure — still
  wins because each side becomes O(1) SQL.
- **Rollback:** keep the new paths behind a flag.

### Step 5 — ADR + tests + docs (2-3h)

- Write ADR-NN ("Bitemporal port to datahike system attrs"). One
  page; references this research note 68 for the per-fn breakdown.
- Write ADR-NN+1 ("Stratum-as-secondary for OLAP query paths").
  Cites §4 of this note; describes the registration shape.
- Update `CLAUDE.md`'s "Bitemporal" section to reference the
  upstream attrs.
- Update `doc/architecture.md` if the layer cake gains a stratum
  block.
- Update `doc/research/00-index.md`.
- `bb ci` final.

- **Dependencies:** steps 1-4 done.
- **What could go wrong:** docs drift from code; mitigate by
  cross-referencing file:line at write time.
- **Rollback:** docs only; trivial to revert.

### Cumulative

- **Total: 26-36 hours.**
- **Critical path: step 0 → step 1 → step 2 → step 3.**
- Steps 4 and 5 can interleave with 2-3 if pipelined; sequential as
  written is the conservative estimate.

---

## 6. Open questions

1. **§3 naming decision — alignment vs alias.** Recommended Option 1
   (align). User sign-off needed before step 1 begins. If consumers
   have meaningful persisted data, the migration helper at §3 ships
   first and step 1 gates on its run.
2. **Stratum version pinning.** 0.3.69 is on Clojars per the task
   brief; does the kontor team want `:mvn/version` or `:local/root`?
   The other bitemporal-stack deps use local-root during the
   coordinated transition (see `deps.edn:46`); consistent with that
   pattern would be `:local/root "../stratum"`. Per the
   pg-datahike PG-7 note in research 67, the team plans to revert to
   `:mvn/version` once releases land — same here.
3. **Should we keep `values-between` and `timeline`** post-port, or
   migrate the few callers (all in tests) to `d/valid-between` +
   `d/q` shapes? Recommend **keep** for now — they're domain-readable
   names with zero-cost wrappers around upstream.
4. **The `query-rules` rule (`posting-vf`) keeps its name and body.**
   Alternative: rewrite all 5 `query-rules` callers
   (`balance.clj:71`, `ledger.clj:116`, `period.clj:236,259`,
   `posting_test.clj:440`) to instead route their queries through
   `(d/valid-at db cutoff)` and drop the rule entirely. That
   eliminates a kontor abstraction in favor of upstream API, but
   it's a bigger refactor (~3-4h extra). **Defer** — punt to a
   follow-up if the rule becomes a maintenance burden.
5. **Stratum index location.** Where does the stratum-backed
   posting projection live on disk? The kontor consumer config
   needs a directory for the columnar files. The OLAP registration
   helper at step 2b needs to decide whether this is opt-in
   (caller supplies path) or convention (kontor picks
   `<datahike-root>/.stratum/`). Recommend opt-in for v1.
6. **Bitemporal regression-test seed.** Per DH-11 (research 67), a
   `tx_instant_monotonic_test.clj` was added as a regression lock.
   We should run a similar bitemporal-resolver-parity test against
   the new `d/valid-at`-backed `value-at` on the kontor side to
   confirm the supersession tiebreak matches the kontor pre-port
   behavior under realistic workloads. ~1h to add; folds into step
   1d.
7. **What happens to `kontor.bitemporal/schema` callers in
   consumer projects** (beleg, simmis, etc.) once we delete it?
   Mitigation: the next kontor release notes must call out the
   removal; consumers do nothing because the system attrs are
   preinstalled. Worst case is `kontor.bitemporal/schema` becomes
   `(def schema [])` for one release cycle as a soft-deprecate.

---

*Note 68 sits alongside 66 (gap analysis) and 67 (issue tracker)
in the bitemporal-arc cluster; the porter should also re-read note
60 (XTDB-vs-datahike feature comparison) for the broader semantic
context and note 62 (datahike-vs-stratum bitemporal comparison)
for the stratum-side capability map.*
