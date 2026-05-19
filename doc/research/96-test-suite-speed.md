---
date: 2026-05-18
title: 96 — Test-suite speed audit
status: completed measurement; remediation deferred until consumer-need
audience: maintainer — read before any "make tests faster" attempt
---

# 96 — Test-suite speed audit

## Total

Two complementary measurements:

- **REPL `(t/run-tests …)` per-ns**, 77 sampled nses: ~5m 42s
  (`342080 ms`). Doesn't include kaocha loader / JVM warm-up.
- **Full kaocha run** (`clojure -M:test --plugin kaocha.plugin/
  profiling`) — official numbers: **~7m 38s** (`457.74 s`) across
  the 247 `deftest`s in the unit suite. Avg per-deftest: **1.85 s**.

2246 tests, 8813 assertions, 0 failures.

Kaocha's `profiling` plugin also surfaces the slowest *individual*
deftests, which the per-ns view hides:

| Test | ms | File |
|---:|---|---|
| 2112 | `kontor.l10n-at.invoice-test/invoice-postings-sum-to-zero` | `modules/l10n-at/test/kontor/l10n_at/invoice_test.clj:327` |
| 2008 | `kontor.l10n-br.invoice-test/invoice-postings-sum-to-zero` | `modules/l10n-br/test/kontor/l10n_br/invoice_test.clj:482` |
| 1866 | `kontor.l10n-mx.invoice-test/invoice-postings-sum-to-zero` | `modules/l10n-mx/test/kontor/l10n_mx/invoice_test.clj:355` |

Same-named deftest in three l10n modules, all ~2 s each. Pattern:
each builds a full invoice + posts + asserts sum-to-zero — likely
the heaviest single setup in the suite. Worth a focused read; if
the shape repeats across all 11 country invoice tests, that's
~20 s alone on a single named-after pattern.

## Top 20 slowest namespaces

| ms | ns | tests | asserts | ms/test |
|---:|---|---:|---:|---:|
| 14507 | `kontor.procurement.forward-flow-test`   | 14 |  29 | 1036 |
| 13254 | `kontor.invoice.bridge-test`             | 19 |  67 |  698 |
| 11798 | `kontor.fx-test`                         | 29 |  47 |  407 |
| 10503 | `kontor.procurement.posting-test`        | 10 |  25 | 1050 |
| 10003 | `kontor.sales-test`                      | 16 |  87 |  625 |
|  9953 | `kontor.partner.transactors-test`        | 21 |  73 |  474 |
|  9924 | `kontor.period-test`                     | 21 |  25 |  472 |
|  9852 | `kontor.partner-test`                    | 21 | 113 |  469 |
|  9413 | `kontor.lease.modification-test`         | 12 |  60 |  784 |
|  8256 | `kontor.valuation-test`                  | 18 |  41 |  459 |
|  8090 | `kontor.collections.lifecycle-test`      | 15 |  41 |  539 |
|  7366 | `kontor.consolidation-test`              | 10 |  30 |  737 |
|  7124 | `kontor.asset.depreciation-run-test`     | 12 |  45 |  594 |
|  6878 | `kontor.procurement.schema-test`         |  7 | 142 |  983 |
|  6722 | `kontor.lease.runner-test`               | 12 |  73 |  560 |
|  6720 | `kontor.inventory.count-report-test`     |  8 |  29 |  840 |
|  6692 | `kontor.retention-test`                  | 13 |  51 |  515 |
|  6479 | `kontor.status-machine-test`             | 14 |  53 |  463 |
|  5934 | `kontor.payment-application-test`        |  8 |  33 |  742 |
|  5882 | `kontor.reconciliation-test`             | 10 |  29 |  588 |

The top 20 sum to **~170s ≈ half the total runtime**. Median per-test
cost across these is ~570ms.

## Root cause

The dominant cost is **per-test schema install + DB boot via `:each`
fixture**. Pattern (forward-flow-test as canonical example):

```clojure
(defn- bootstrap [f]
  (binding [*conn* (core/create-test-db)]   ; in-mem datahike conn
    (partner-schema/install! *conn*)        ; ~3000-attr kernel
    (sales-schema/install! *conn*)          ; + companion attrs
    (inv-schema/install! *conn*)            ;
    (proc-schema/install! *conn*)           ;
    (f)))                                   ; → run the deftest

(use-fixtures :each bootstrap)              ; ← runs per deftest
```

Audit numbers:

- 139 of ~150 test files call `create-test-db`.
- 324 call-sites for `create-test-db` across the repo (some tests
  re-create inside the deftest).
- Most tests average ~500-1000ms but DO very little — most of the
  cost is the bootstrap, not the assertions.
- Only 18 explicit `(use-fixtures :each ...)` declarations (so most
  tests inline the boot inside the deftest body instead — same cost).
- 0 explicit `(use-fixtures :once ...)` declarations.

The kernel schema is ~3000 attrs (~30 namespaces × tens of attrs
each); installing it transacts the whole `all` vector via
`d/transact`. Each install is ~50-200ms cold; companion schemas add
another ~50-100ms each. Multiply by 2246 tests and you're at ~3-5
minutes of pure setup overhead.

## Remediation levers (sorted: bang-for-buck)

### Lever 1 — `:once` per-ns fixture + per-test `d/with-db`

Pattern:

```clojure
(def ^:dynamic *base-db* nil)

(defn- install-once [f]
  (let [conn (core/create-test-db)]
    (partner-schema/install! conn)
    (proc-schema/install! conn)
    (binding [*base-db* (d/db conn)]
      (f))))

(use-fixtures :once install-once)

(deftest some-test
  (let [conn (atom @*base-db*)              ; or copy-on-write helper
        _ (swap! conn d/with-db tx-data1)
        _ (swap! conn d/with-db tx-data2)]
    (is (= ...))))
```

`d/with-db` is the datahike functional-DB primitive — applies tx-data
without persisting. Tests get isolated DBs without re-installing the
schema. **Estimated savings: 50-70% of total wall-clock** (eliminates
the per-test install cost on the top 20 alone).

Risk: tests that test the *transactor* layer (the `!` wrappers) need a
real connection; they can't use `d/with-db`. Those keep the `:each`
fixture. So this is mainly a win for query-side + pure-builder tests.

### Lever 2 — `kaocha.plugin/parallel-tests`

Run namespaces concurrently. Kaocha supports this natively; just
enable in `tests.edn`:

```clojure
:plugins [kaocha.plugin/parallel-tests]
:parallel-tests {:enabled? true :limit 4}
```

In-mem datahike conns are independent (each test owns its own),
so cross-namespace parallelism is safe. **Estimated savings:
40-60% on a 4-8 core box.** Compounds with Lever 1.

Risk: tests that share global state (some `:status-transition` /
`:approval-policy` registries are atom-backed and process-global)
break under parallel. Audit `(def ...registry... (atom ...))` sites
before enabling.

### Lever 3 — Split into `:unit` + `:integration` suites

Some tests are intrinsically heavy:
- `consolidation-test` (7.4s) — multi-entity translate + eliminate
- `lease.runner-test` (6.7s) — period sweep with many transacts
- `payroll-ca/e2e-test` and friends — full-month payroll runs

Move these to a `:integration` kaocha suite. Default `bb test` runs
only `:unit`; CI runs both. Devs running TDD locally on a non-
integration slice save ~30-60s per cycle.

```clojure
;; tests.edn
:tests [{:id :unit         :test-paths [...] :ns-patterns [...]}
        {:id :integration  :test-paths ["test"] :ns-patterns ["integration-test$"]}]
```

Requires renaming or annotating the chosen integration test
namespaces.

### Lever 4 — Investigate the `kontor.fx-test` outlier

`kontor.fx-test`: 11.8s / 29 tests / 47 assertions = 407 ms/test
with **only ~1.6 assertions per test**. That's a suspicious ratio.
Either the tests do expensive setup (fixture data + JVM HTTP
mocks?) or they're hitting a real ECB endpoint. Worth a focused
read.

## Recommendation

For the next consumer-ready milestone, the right answer is probably
**Lever 1 only** — biggest impact, smallest risk, no infra change.
~50% of the runtime back, no new tools, no parallel-state pitfalls.

If the suite then still feels slow, layer in Lever 2.

Lever 3 should wait until there's a real "fast TDD loop" need from a
specific contributor; it's a discipline-tax (every new test author has
to decide which suite to put their test in).

Lever 4 is a 30-minute investigation, not a project.

## Out of scope for this audit

- Profiling individual deftests below the namespace level (would need
  the `kaocha.plugin/profiling` output, which got stuck buffering in
  our run; structure of the slow nses tells us enough)
- Datahike installer micro-optimization (the schema is what it is;
  ADR-001 single-dep + the ~75 ADRs that landed all need their attrs)
- Stratum-secondary-index migration for tests (out of scope — tests
  use in-mem datahike, not the indexed Postgres backend)

## How this audit was produced

```clojure
;; In REPL
(doseq [n test-namespaces]
  (require n)
  (let [t0 (System/nanoTime)
        r  (clojure.test/run-tests n)
        ms (long (/ (- (System/nanoTime) t0) 1e6))]
    (swap! results assoc n (assoc r :ms ms))))
```

Per-ns precision (not per-deftest), but that's the right granularity
for the levers above — once the dominant nses are speeded up
fixture-wise, individual-test profiling becomes interesting again.
