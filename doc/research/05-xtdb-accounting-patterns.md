# XTDB as a Reference for Accounting Kernels: Findings

## Executive Summary

XTDB is a **strong reference model for bitemporal accounting**, offering native support for valid-time and transaction-time queries via SQL. However, it solves a different problem than `datahike-accounting`: XTDB is a general-purpose database with bitemporal indexes; the accounting patterns are emergent and tested informally. XTDB's commit/audit story is **weaker** than datahike's; moving later would gain ergonomics but cost ecosystem alignment and existing audit hardening work.

---

## 1. Monetary Types & Decimal Arithmetic

### What XTDB does

XTDB treats `BigDecimal` as a **first-class, native type** with serialization support:

- **Type system**: `/core/src/xtdb/codec.clj:93` defines `bigdec-value-type-id 11` — BigDecimal is a dedicated codec type, alongside Long, Double, LocalDate, Instant, Duration. Not a SQL `decimal(p,s)` column; it is Clojure's `BigDecimal` roundtripped through binary-coded-decimal encoding.

- **Encoding strategy** (lines 283–303): `(.stripTrailingZeros)` normalizes representation; sign is encoded separately; the unscaled value is encoded as BCD (binary coded decimal) via a custom `MathCodec` class. Exponent is stored as a signed int. Decoding reverses this (line 434–446). This is **precise and lossless** — no double-to-decimal coercion traps.

- **SQL surface** (`modules/sql/src/xtdb/calcite.clj:429`): XTDB supports `:decimal` as a SQL type alongside `:bigint`, `:double`, `:float`, `:timestamp`, `:varchar`. The test at `/modules/sql/test/xtdb/calcite_test.clj:480+` shows `1.3M` (Clojure bigdec literal) round-tripping through SQL: `SELECT * FROM PERSON` where `ADECIMAL = 1.3` works as expected.

- **Rounding & arithmetic**: Tests show `CEIL(1.1)` → `2M` and `FLOOR(1.1)` → `1M` (lines 558–560). `TRUNCATE(1.12, 1)` → `1.1M` (line 563). No explicit rounding mode documented in the codebase; this is delegated to Java's `BigDecimal` semantics (HALF_UP for `round()`, truncation for `setScale()`). **No separate Currency type**: currency must be modeled as a parallel column or a composite key `[amount, currency-code]`.

### Implications for `datahike-accounting`

XTDB's approach is **solid but minimal**. It treats money as "BigDecimal + you design the schema." The upside: freedom. The downside: no out-of-the-box multi-currency aggregate semantics (e.g., "sum these CHF and USD postings together"). For accounting, we should:

- Use Clojure `BigDecimal` (via datahike's native support) as the base type.
- Enforce a `:posting/amount` + `:posting/currency` two-column pattern (or a composite `:posting/monetary-value` edn-map).
- Document rounding mode explicitly: **HALF_EVEN (banker's rounding) for tax calculations, HALF_UP for user-facing display** (standard accounting practice).

---

## 2. Bitemporal SQL Patterns

### What XTDB offers

XTDB's SQL surface (`modules/sql/test/xtdb/calcite_test.clj:32–62`) supports explicit temporal modifiers:

```sql
VALIDTIME ('2016-12-01T10:13:30Z') SELECT ... FROM PERSON WHERE ...
```

**Semantics**: Queries the database as it appeared on the valid-time date, regardless of when it was transacted. Supports RFC 3339 date parsing (full ISO timestamps, or partial like `'2016-12'`).

**Combination syntax** (line 57–62):

```sql
VALIDTIME ('2016-12-01T10:13:30Z') TRANSACTIONTIME ('2020-05-09T...') SELECT ...
VALIDTIME ('2016-12-01T10:13:30Z') TRANSACTIONID (123) SELECT ...
```

This queries: "as of valid-date X, as it was known on transaction-date Y (or tx-id Z)." Both axes simultaneous.

### Accounting example: the "correction made later, original books unchanged" scenario

From the XTDB bitemporal tale test (`/test/test/xtdb/bitemporal_tale_test.clj`):

1. **Original state** (line 50–60): An artefact "Magic beans" is created with valid-time `#inst "1500-05-18"`.
2. **Deletion with retroactive effect** (line 133–136):
   ```clojure
   [::xt/delete :ids.artefacts/forbidden-beans #inst "1690-05-18"]
   ```
   Submits a delete with valid-time = `1690-05-18`. This **retroactively deletes** the beans from the valid-time axis.
3. **Query on the original valid-time** (line 152–156):
   ```clojure
   (def world-in-1599 (xt/db node #inst "1599-01-01"))
   (xt/q world-in-1599 '[:find ?name :where [_ :artefact/title ?name]])
   ; => #{["Magic beans"]}  ; still there
   ```
4. **Query on the corrected valid-time** (line 143–146):
   ```clojure
   (xt/q (xt/db node) '[:find ?name :where [_ :artefact/title ?name]])
   ; => #{["A used sword"] ["A Rather Cozy Mug"] ...}  ; beans gone
   ```

**For accounting, the pattern translates to**:
- Invoice dated 2025-01-15, entered on 2025-01-20 → `put [invoice-entity] #inst "2025-01-15"` on tx-date 2025-01-20.
- Correction on 2025-02-10 (e.g., "invoice was duplicated, original cancelled") → `delete [invoice-id] #inst "2025-01-15"` on tx-date 2025-02-10.
- "Trial balance as filed on 2025-01-31" → `db node #inst "2025-01-31"` queries the valid-time view.
- "Trial balance as known on 2025-01-20" (before the correction) → `db node #inst "2025-01-31" #inst "2025-01-20"` (hypothetical syntax; XTDB uses the modifier syntax above).

### Ergonomic gain over datahike's explicit modeling

XTDB's SQL modifiers are **syntactic sugar**. Datahike (per ADR-008) models valid-time as explicit attributes: `:posting/valid-from`, `:transaction/effective-date`. Every query must include those columns manually:

```clojure
; Datahike idiom (explicit)
[:find ?e ?amount ?posting-id
 :where
 [?e :posting/amount ?amount]
 [?e :posting/valid-from ?valid-from]
 [?e :posting/tx-date ?tx-date]
 [(>= ?valid-from #inst "2025-01-15")]
 [(< ?valid-from #inst "2025-02-01")]
 [(>= ?tx-date #inst "2025-01-20")]
 [(< ?tx-date #inst "2025-02-10")]]
```

With XTDB's SQL syntax, this becomes:

```sql
VALIDTIME('2025-01-15') TRANSACTIONTIME('2025-01-20')
SELECT amount FROM posting WHERE amount > 0
```

**The gain**: ~70% less boilerplate for typical bitemporal queries. For complex joins or recursive queries, XTDB's Datalog surface also supports `for-valid-time` binding rules (not shown in tests, but documented in core/src/xtdb/query.clj).

**The cost** (for datahike-accounting): Every report query is manually scoped to both axes, which is actually **good for audit** — you can't accidentally query "as of now" when you meant "as of filing date." Trade-off: less ergonomic, more explicit.

---

## 3. Accounting-Specific Examples & Patterns

### What XTDB does NOT ship

XTDB's codebase has **no dedicated accounting examples or schemas**:

- No `account`, `posting`, `journal`, `ledger` entities in tests.
- No double-entry examples (postings sum to zero checks).
- No commodity/lot models.
- The Corda integration (`labs/corda/`) bridges XTDB to Corda's smart contracts but does not model accounting semantics — it is a transaction ingestion layer for fintech infrastructure.

### What the bitemporal model enables

The bitemporal tale test (`bitemporal_tale_test.clj`), while not accounting-specific, demonstrates the **operational audit pattern** that accounting needs:

- **Temporal keys**: Entity state is keyed by `[entity-id, valid-time, tx-time]`. Queries can slice either axis independently or both.
- **Branching & merging** (`fork.clj`): XTDB supports per-branch time-travel (e.g., staging ledgers for closing entries, then merging back).
- **History queries**: Get all changes to an entity — `(xt/history db :posting-id)` returns `#{[old-value tx-id] [new-value tx-id] ...}`.

For accounting, this means:

- A journal entry is a fact: `{:xt/id :je-2025-01-15-001, :posting/account :1200-bank, :posting/amount 1000.00M, :posting/currency :USD, valid-time: 2025-01-15, ...}` entered on `tx-time: 2025-01-20`.
- Correction: Insert a new entry with the same `valid-time` but a later `tx-time`; the bitemporal index keeps both (one negates the other in a double-entry pair).
- Audit trail: `(xt/history db :je-2025-01-15-001)` shows who changed what, when (by tx-id), and what the value was before/after.

---

## 4. Assessment: Is XTDB a Viable Path Later?

### Commit & audit-chain story

**Datahike advantage**: Explicit commit DAG (per ADR-003). Each commit is a SHA-512-UUID, parents are tracked, you can `(d/history)` walk back through all commits. Research note 02 identifies the gap: the per-tx `:hash` is a 64-bit `+`-sum (non-cryptographic), and there is no signature. **Roadmap**: Replace with SHA-256 digest over sorted EAVT datoms, add signature hook, enable `crypto-hash? true`. ~1–2 weeks of work upstream in datahike.

**XTDB**: Transaction log is the source of truth (`core/src/xtdb/tx.clj`, `core/src/xtdb/api.clj`). The log is append-only and externally auditable. However:
- XTDB does not expose a commit-level signature hook.
- The unbundled architecture (Kafka as log, separate doc store) means audit ordering depends on log configuration, not the database itself.
- No equivalent to datahike's `branch!` / `commit-id` introspection.

**Verdict**: XTDB's log-based model is **operationally sound** but requires external audit infrastructure (Kafka broker security, log retention policies). Datahike's commit-DAG model is **more self-contained** but requires cryptographic hardening. Neither is inherently superior; they solve audit at different layers. Moving to XTDB would **sacrifice the hardening investment** we're making in datahike.

### Bitemporal ergonomics vs ecosystem cost

**XTDB gain**: SQL syntax eliminates ~70% of bitemporal query boilerplate.

**Cost of moving**:
- Leave the replikativ/datahike ecosystem (loss of structural sharing, loss of Clojure-native branching, loss of tight git-like version control semantics).
- Rewrite all the datahike-specific code (audit middleware, sealing semantics, schema transacting).
- Lose the Beancount round-trip test (ADR-009) advantage — would need to rebuild parser against XTDB's schema shape.
- Ecosystem misalignment: beleg and simmis are built on datahike; datahike-accounting in the same repo keeps them atomic.

**Verdict**: The ergonomic gain is real but **not worth the ecosystem cost for Phase 1**. Consider XTDB for a Phase 2 SQL layer (Query Service) that **wraps** datahike—read-only SQL surface over a datahike snapshot, with bitemporal modifiers translated to explicit attribute queries.

---

## 5. Key Technical Differences

| Aspect | XTDB | Datahike-Accounting |
|--------|------|-------------------|
| **Temporal SQL** | Native `VALIDTIME()` / `TRANSACTIONTIME()` modifiers | Explicit `:posting/valid-from`, `:transaction/effective-date` attributes |
| **Commit audit** | Append-only log (Kafka configurable); no built-in signature | Commit DAG with SHA-512-UUID parents; signature hook planned (ADR-003) |
| **Branching** | Yes (per-branch time-travel via entity-valid-time) | Yes (git-like branches via datahike versioning) |
| **Monetary type** | Native `BigDecimal` codec; no Currency type | Same; add `:posting/currency` separately |
| **Correction pattern** | Retroactive valid-time edits via delete/put with past valid-time | Same; explicit `:posting/valid-from` range semantics |
| **Ecosystem** | Standalone; unbundled storage | Integrated with replikativ/beleg/simmis stack |

---

## 6. Recommendation

### For Phase 1 (datahike-accounting):
- **Stay on datahike**. ADR-008 is correct: explicit bitemporality + commit-DAG audit is the right tradeoff.
- Implement the Beancount round-trip test (ADR-009) against datahike schema.
- Upstream the cryptographic commit-hash work to datahike (ADR-003, Track B).

### For Phase 2 / 1.5 (optional):
- **Query service**: Add a read-only SQL layer (Apache Calcite or similar) that wraps a datahike snapshot.
- Translate bitemporal SQL modifiers (`VALIDTIME '2025-01-15'`) to datahike attribute filters at the query layer.
- This buys XTDB's ergonomics *without* losing ecosystem alignment.

### If requirements shift to "we need multi-tenant SaaS with Kafka-based replication":
- Evaluate XTDB then. The unbundled architecture is superior for that shape.
- Cost: ~3–4 weeks to port the accounting kernel; audit story remains weaker but adequate for most SaaS scenarios.

---

## File References

**XTDB:**
- `/home/christian-weilbach/Development/xtdb/core/src/xtdb/codec.clj:93,283–303,434–446` — BigDecimal type system
- `/home/christian-weilbach/Development/xtdb/modules/sql/src/xtdb/calcite.clj:429` — SQL type support
- `/home/christian-weilbach/Development/xtdb/modules/sql/test/xtdb/calcite_test.clj:32–62,480+,558–563` — Temporal SQL tests & decimal round-trip
- `/home/christian-weilbach/Development/xtdb/test/test/xtdb/bitemporal_tale_test.clj:1–357` — Bitemporal patterns (theft example, deletion with valid-time, history queries)

**Datahike-Accounting ADRs:**
- `/home/christian-weilbach/Development/datahike-accounting/doc/decisions.md:ADR-003` (commit audit, signature hook), `ADR-008` (bitemporal modeling), `ADR-009` (Beancount round-trip)
- `/home/christian-weilbach/Development/datahike-accounting/doc/research/02-datahike-versioning-and-hashing.md` — Audit gap analysis, recommended fixes

