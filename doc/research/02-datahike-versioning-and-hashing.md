# Datahike for Accounting Audit/Tamper-Evidence — Honest Survey

Datahike repo at `/home/christian-weilbach/Development/datahike` (HEAD `f3fc55de`, one commit past 0.8.1681). Konserve at `/home/christian-weilbach/Development/konserve`. Hasch (hashing library) verified from `~/.m2/repository/org/replikativ/hasch/0.4.98/hasch-0.4.98.jar`.

## 1. Versioning model — first-class but young

Datahike has a real, exposed versioning API in `datahike.versioning` (`src/datahike/versioning.cljc`). Surface (re-exported as `datahike.api/branch!`, `merge-db`, `commit-id`, etc., all marked `:stability :stable` in `src/datahike/api/specification.cljc:511-637`):

- `branches`, `branch!`, `delete-branch!`, `force-branch!` (`reset --hard`-equivalent, with warning).
- `branch-as-db`, `commit-as-db` — load any branch head or any historical commit by UUID as a queryable db value.
- `branch-history` — walks the parent DAG, returns dbs in order.
- `merge-db` / `merge-db!` — the *caller* supplies the merged tx-data; the function only records multiple parent commit-ids in metadata. There is no automatic 3-way merge or conflict resolution. The doc (`doc/versioning.md:213-238`) shows the standard pattern: `(d/q ...)` to compute a diff, then transact.
- `commit-id`, `parent-commit-ids` — read commit metadata off any db value.

A db value (`(d/db conn)` or `@conn`) is fully persistent/immutable, identical to Datomic's model — you can hand it around freely. Each commit is keyed in konserve under both its commit UUID and the branch keyword, so branches are root pointers into a shared, structurally-shared index store (`writing.cljc:208-253`).

Status: docs label versioning **Beta** (`doc/versioning.md:3`), even though specification spec marks operations `:stable`. There is no signed-tag, no release/tag concept, no protected branch.

## 2. Hashing — partial; not yet what GoBD wants

Two separate hashes need to be distinguished:

**(a) The db `:hash` field** (`src/datahike/db/transaction.cljc:229,241,247,266,335`). This is a `clojure.core/hash` of each datom, *summed* (`+/-`) into a `Long`. I confirmed in REPL: `(:hash @conn)` returns e.g. `5637489424`. It is order-independent (commutative), but it is also (i) only 32-bit collision space realistically, (ii) not cryptographic, (iii) trivially forgeable. It exists to short-circuit equality, not for tamper evidence.

**(b) The commit-id**, in `writing.cljc:190-195`:

```clj
(defn create-commit-id [db]
  (let [content-uuid (uuid [hash max-tx max-eid meta])]
    (if (:crypto-hash? config) content-uuid (squuid content-uuid))))
```

`hasch.core/uuid` ⇒ `edn-hash` ⇒ **SHA-512** via `MessageDigest/getInstance "sha-512"` (`hasch/platform.clj:30-31`), folded into a UUID-5. So the commit-id is a 128-bit prefix of a SHA-512 over `[hash max-tx max-eid meta]` — *which itself depends on the weak `:hash` field*. The chain `commit ⟵ parents ⟵ schema-meta-key` is therefore a Merkle DAG only at the metadata level: parents are content-addressed UUID-5s, but the leaf "I really contain these datoms" assertion goes through the 64-bit additive hash. That is the gap.

When `:crypto-hash? true` (default false), the **index nodes** in `persistent_set.cljc:216-221` also become content-addressed: `(uuid (vec (.addresses ^Branch node)))` for branches, `(uuid (mapv (comp vec seq) (.keys node)))` for leaves — so the EAVT/AEVT/AVET trees become a real Merkle tree where each node's address is SHA-512(child contents). That is the cryptographically meaningful structure. With `:crypto-hash? false` (default) addresses are sequential UUIDs, address recycling is allowed, and the tree is *not* Merkle.

Stability across re-orderings: I tested two databases with the same logical content inserted in different order. Hashes differed (`1976347861` vs `1706757449`) because tempid resolution assigned different EIDs. Also `:db/txInstant` flows into `:meta`, so two replays at different wall-clock times produce different commit-ids. Reproducible commits across machines therefore require deterministic EIDs and externally-supplied `:db/txInstant`.

Algorithm: SHA-512 (downgraded to UUID-5 / 128 bits at the storage key boundary). GoBD/HGB require "appropriate" hashes; SHA-256 and SHA-512 both qualify. The 128-bit truncation is fine for ID purposes but you would want the full 64-byte digest for an external attestation chain.

## 3. History queries — first-class, well-supported

`datahike.api/history`, `as-of`, `since` are stable (`api/impl.cljc:130-148`, spec line 467-505). Require `:keep-history? true`. They return wrapped db types (`HistoricalDB`, `AsOfDB`, `SinceDB`) over separate `temporal-eavt/aevt/avet` indices populated on retract/upsert (`db/transaction.cljc:243-249`).

I confirmed in REPL: `(d/q '[:find ?n ?tx ?op :where [?e :name ?n ?tx ?op]] (d/history @conn))` returns rows with op-flag, e.g. `(["c" 536870915 true] ["c" 536870916 false])` — full add/retract trail per datom.

For accounting "show me every change to journal entry X": trivial via `(d/history @conn)` filtered by `?e`. As-of by Date or tx-id is built in. A diff-between-commits is the exact pattern in `doc/versioning.md:213-238` (datalog `not` against a second db). No first-class `(diff db1 db2)` function — implement in 5 lines if needed.

**Caution:** `:db/purge` and `:db.history.purge/before` (`doc/time_variance.md:266-326`) permanently delete from history *including the temporal indices*. For accounting, schema attributes can't be purged, but data-level retraction-with-purge breaks any audit invariant. You must wrap or forbid `:db/purge` at the application layer.

## 4. Storage cost

- `:keep-history? true`: roughly 2× index size in steady state (one extra B-tree per primary index: `temporal-eavt/aevt/avet`). Updates write `[old-datom retract; new-datom add]` into temporal indices; pure inserts only touch primary. For an SMB ledger with mostly inserts and few corrections, expect well under 2×.
- Branches share storage by structural sharing of the persistent-sorted-set / hitchhiker-tree pages. Branching is O(1) — only the new root + modified pages. With `:crypto-hash? true` you cannot recycle freed addresses (`online_gc.cljc:185-188`).
- 5–10 years of SMB accounting (say 10⁶–10⁷ posted lines) is comfortably feasible. Real bottleneck will be konserve's per-key file overhead with the `:file` backend; for production you'd use `:lmdb`, `:rocksdb`, `:jdbc`, `:redis`, or `:s3`.
- Offline GC (`d/gc-storage`, `doc/gc.md`) reclaims unreachable snapshots but always preserves branch heads — safe to run.

## 5. Gaps for accounting

Concrete missing pieces, from biggest to smallest:

1. **No cryptographic commit signature.** The commit-id is a content hash but nobody signs it. To pass GoBD external-auditor verification you need an Ed25519/RSA signature over the commit-id by an HSM-held key, persisted alongside the commit. Build: ~100 lines; conceptually a tx-meta field plus a verify-chain helper.
2. **`:hash` is non-cryptographic.** Even with `:crypto-hash? true`, the *tx-level* hash that feeds into the commit UUID is the additive `clojure.core/hash` sum. A sufficiently determined attacker who can write to konserve directly could produce a colliding state. Recommendation: replace or augment with SHA-256(sorted-EAVT-datom-list) per commit, store in `:meta`, include in commit-id input. Probably 2-3 days of work in `db/transaction.cljc` + `writing.cljc`.
3. **No "sealed" / immutable commits.** `force-branch!` is explicitly `git reset --hard`. Nothing prevents `delete-branch!` then re-create. Need an append-only branch flag and refusal of `force-branch!`/`:db/purge` on it.
4. **No external timestamp anchoring.** `:db/txInstant` is whatever the writer's clock said. RFC 3161 TSA / blockchain anchoring is uncovered.
5. **Concurrent writes** are serialized through a single writer (`datahike.writer`) per branch, so audit ordering is well-defined — but cross-branch ordering is not. For a single-tenant ledger this is fine.
6. **Purge.** Must be disabled at the schema/middleware layer for posted entries.

## 6. Practical recommendation

What is free today, with `:crypto-hash? true` and `:keep-history? true`:

- A Merkle tree over EAVT/AEVT/AVET index nodes (SHA-512-derived addresses).
- A commit DAG where each commit-id is a SHA-512-UUID5 referencing parent commit-ids and the storage roots.
- A complete temporal index — `(d/history db)` gives you, per entity, the full add/retract series with tx-id and timestamp, queryable in datalog.
- Cheap branching (think: per-fiscal-year branches, or staging branches for closing entries).

What you must add to claim GoBD/FEC equivalence to Odoo's `inalterable_hash` chain:

- A real per-commit cryptographic content hash (SHA-256 or SHA-512 over canonical-encoded sorted EAVT datoms of the commit), stored in `:meta`, fed back into `create-commit-id`.
- A signature over that hash by a key held outside the database.
- A "posted" / append-only marker on entries that refuses retract/purge/force-branch.
- An RFC 3161 timestamp on each closing/posting commit (or a less formal "anchor every N commits to a blockchain / external log" scheme).

**Is it strictly better than Odoo's per-row hash?** *Different shape, potentially better, not yet better today.* Odoo signs each row; datahike (with the gaps above closed) would sign each *commit*, which transitively covers every datom because the commit hash is over the whole Merkle root. That is much closer to git/Certificate-Transparency-grade tamper evidence than Odoo's linear chain. But the leaf hash is currently a 64-bit `+`-sum, which is the one thing that absolutely must be replaced before any auditor will accept it. Estimated work to reach a defensible "kontor" baseline: ~1–2 weeks of focused work, almost all of it in two files (`writing.cljc`, `db/transaction.cljc`) plus an `accounting`-namespace overlay for posting/sealing semantics.

## Key file pointers

- `/home/christian-weilbach/Development/datahike/src/datahike/versioning.cljc` — branch/merge/commit API
- `/home/christian-weilbach/Development/datahike/src/datahike/writing.cljc:190-253` — `create-commit-id`, `commit!`
- `/home/christian-weilbach/Development/datahike/src/datahike/db/transaction.cljc:220-340` — where `:hash` is mutated per datom
- `/home/christian-weilbach/Development/datahike/src/datahike/index/persistent_set.cljc:216-246` — content-addressed index node addresses
- `/home/christian-weilbach/Development/datahike/src/datahike/api/impl.cljc:130-148` — `as-of`, `since`, `history`
- `/home/christian-weilbach/Development/datahike/src/datahike/api/specification.cljc:467-637` — public API spec, stability tags
- `/home/christian-weilbach/Development/datahike/doc/versioning.md`, `doc/time_variance.md`, `doc/gc.md` — official narrative
- `~/.m2/repository/org/replikativ/hasch/0.4.98/hasch-0.4.98.jar` ⇒ `hasch/core.cljc`, `hasch/platform.clj` — SHA-512 + UUID-5
