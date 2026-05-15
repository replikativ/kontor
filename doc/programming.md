# kontor — Clojure programming model

This document is for **Clojure developers writing or reading kontor**:
either building a consumer app on top, contributing a kernel
extension, or maintaining an existing transactor. It assumes you've
read or skimmed [doc/value.md](value.md) for the why and have
[doc/architecture.md](architecture.md) open for the layer cake.

Audience: comfortable with Clojure, EDN, and ideally datalog. Not
required to know datahike specifically; the document introduces the
relevant pieces. Not required to know accounting terminology either;
the doc names accounting concepts as they appear with one-liner
explanations.

## The three axes you'll always be writing against

Every kontor transactor sits inside a **three-axis frame**:

```
                   bitemporal substrate
                   ───────────────────────
                       │
                       │  every fact has
                       │  :tx/valid-from + :db/txInstant
                       ▼
       status     ──────────────────  the transact gate
       machines                       ─────────────────────
       ──────                          (transact-with-validation
       :status-transition rows         routes through ALL of:
       :status-history rows             legal-hold • sealing
       :approval-policy rows            • period • state-machine
                                        • sum-to-zero
                                        • datalog invariants)
```

Understanding the three is the price of admission. They show up in
every business write.

### Axis 1 — the bitemporal substrate

Every datahike transaction in kontor carries two timestamps:

- **`:db/txInstant`** — the **system time**, set by datahike when the
  transaction commits. You don't write this; datahike does.
- **`:tx/valid-from`** — the **valid time**, set by the kernel via
  `kbt/with-vt`. This is the timestamp of the *business event* — the
  invoice's date, the depreciation period's effective-date, the
  legal-hold's placement instant.

A `kontor.bitemporal/with-vt` call wraps a tx-data vector with a tx-
meta map carrying `:tx/valid-from` (and optionally `:tx/valid-to`):

```clojure
(kbt/with-vt [{:db/id "x" :foo 1}]
             #inst "2026-03-15")
;; →
;; [{:db/id "x" :foo 1}
;;  {:db/id "datomic.tx" :tx/valid-from #inst "2026-03-15"
;;                       :tx/valid-to #inst "9999-12-31..."}]
```

Why two clocks: a Q1 invoice gets corrected in April. The correction
*happened* in April (`:db/txInstant = 2026-04-12`) but is *valid for*
Q1 (`:tx/valid-from = 2026-03-31`). Queries can ask either question:

```clojure
;; "What did the books say on March 31?"
(d/q '[:find ?account (sum ?amount)
       :in $ ?as-of-valid
       :where [?p :posting/account ?account]
              [?p :posting/amount ?amount]
              [?p :posting/transaction _ ?tx]
              [?tx :tx/valid-from ?vf]
              [(<= ?vf ?as-of-valid)]]
     db #inst "2026-03-31")
```

ADR-008 / ADR-048 cover this. `kontor.bitemporal` has the helpers.
Showcase #1 walks through a real bitemporal query.

### Axis 2 — status machines

Every business entity with a lifecycle has a **`:facet/status` attribute**
on it. Transitions are governed by `:status-transition` rows
(legality) and `:approval-policy` rows (governance — segregation of
duties, supporting docs, reason notes).

```clojure
;; A :status-transition row registers a legal edge.
{:status-transition/entity-type :invoice
 :status-transition/facet       :invoice/status
 :status-transition/from        :draft
 :status-transition/to          :sent
 :status-transition/active      true
 :status-transition/name        "Send Invoice"}

;; An :approval-policy enforces governance on the edge.
{:approval-policy/entity-type     :invoice
 :approval-policy/facet           :invoice/status
 :approval-policy/transition-from :paid
 :approval-policy/transition-to   :cancelled
 :approval-policy/requires-supporting-doc true
 :approval-policy/requires-non-empty-reason-note true
 :approval-policy/no-self-approval true
 :approval-policy/active true}
```

Every status change goes through `kontor.status-machine/record-status-
change!` (or `record-status-change-tx-data` for composition). The
gate enforces the policy.

ADR-034 (status machine) and ADR-038 (approval policy). The "kernel
state machine" `kontor.state-machine` is a sibling — it governs
`:transaction/state` (draft → posted → cancelled) with its own
sealing semantics; ADR-007.

### Axis 3 — the transact gate

Every business write routes through `kontor.validation/transact-
with-validation`. The gate runs (in order):

1. **`legal-hold/assert-no-hold-violating-destructive-writes!`** —
   refuses to retract / purge / cas an entity covered by an active
   legal hold (ADR-049).
2. **`sealing/assert-no-silent-retracts!`** — once a posting has
   `:posting/posted-at`, it cannot be silently retracted (ADR-007).
   Explicit `:db/purge` IS allowed but is itself a recorded commit
   the audit chain documents.
3. **`period/assert-no-write-on-sealed!`** — no writes to entities
   inside a `:period/sealed-at`-marked period.
4. **`period/assert-not-in-locked-period!`** — no postings whose
   valid-time falls in a soft-closed period (`:period/locked-at`).
5. **`state-machine/assert-transition!`** — every `:transaction/state`
   change must be a legal edge per the kernel state machine.
6. **`assert-postings-sum-to-zero!`** — debits and credits sum to
   zero per (transaction, ledger, commodity). Double-entry by
   construction.
7. **`inv/assert-invariants`** — the datalog-invariant pass (account-
   active, commodity-match, plus consumer-installed rules). ADR-011.

Together: **the substrate is correct by default**. Pass invalid
tx-data, get a typed exception. The application code doesn't need to
remember to call validators — the gate calls them, every time.

## The transact programming model (post-Stage-P / ADR-068)

Every business-write transactor in kontor splits into a **pure
builder** and a **thin gated wrapper**:

```clojure
;; The pure builder. Takes db + opts, returns tx-data.
;; No d/transact, no kbt/with-vt. Reusable, composable.
(defn place-hold-tx-data
  [db {:keys [code matter-name issued-by-uid ...]}]
  ...
  [hold-row status-tx])

;; The thin wrapper. Wraps with vt, routes through the gate,
;; returns the tx-report.
(defn place-hold!
  [conn {:keys [vt-from vt-to] :as opts}]
  (validation/transact-with-validation
   conn (kbt/with-vt (place-hold-tx-data (d/db conn) opts)
                     (or vt-from (Date.))
                     (or vt-to kbt/forever))))
```

This is the **universal rule** (ADR-068): every business-write
namespace exposes both forms. Builders are values; wrappers are
side-effecting calls. The split unlocks composition.

### Single-event writes — call the wrapper

Most calls in a consumer app are single-event:

```clojure
(legal-hold/place-hold!
 conn {:code            "HOLD-ACME-2026"
       :matter-name     "Acme v. Doe"
       :issued-by-uid   counsel-eid
       :issued-at       #inst "2026-05-14"
       :supporting-doc  preservation-order-eid
       :scope-eids      [acme-customer-eid]
       :reason-note     "Preservation order received."})
```

This routes through the gate, applies `with-vt`, returns the
tx-report. All the validators run; if any one rejects, the whole
write aborts.

### Multi-step atomic events — `kontor.process`

For events that span multiple business entities or multiple modules,
use `kontor.process/run-process`:

```clojure
(process/run-process
 conn
 {:steps
  [;; Step 1 — upload the subpoena.
   (fn [sdb _ctx]
     (audit-doc/create-doc-tx-data
      sdb {:tempid       "subpoena"
           :code         "SUB-2026-001"
           :type         :subpoena
           :storage-uri  "s3://docs/sub-2026-001.pdf"
           :uploaded-by-uid counsel-eid}))

   ;; Step 2 — place the legal hold referencing the audit-doc
   ;; by STRING TEMPID (cross-step composition).
   (fn [sdb _ctx]
     (legal-hold/place-tx-data
      sdb {:tempid          "hold-acme"
           :code            "HOLD-ACME-2026"
           :matter-name     "Acme v. Doe"
           :issued-by-uid   counsel-eid
           :issued-at       #inst "2026-05-14"
           :supporting-doc  "subpoena"            ; ← step 1's tempid
           :scope-eids      [acme-customer-eid]
           :reason-note     "Preservation order received."}))]
  :vt-from #inst "2026-05-14"
  :vt-to   kbt/forever})
```

What `run-process` does:

1. Threads `(d/db-with db0 acc)` through the step seq, so each step
   sees the speculative db with all prior fragments applied.
2. Accumulates tx-data via `strip-tx-meta` (the process owns
   valid-time, not the individual builders).
3. Wraps once with `kbt/with-vt`.
4. Commits through `transact-with-validation` (the gate).
5. Returns the tx-report — `(:tempids report)` resolves your string
   tempids to their committed eids.

The `:steps` return shape is one of:

```clojure
nil                                   ; no-op
tx-data-vector                        ; just a fragment
{:tx-data tx-data}                    ; explicit fragment
{:tx-data tx-data :ctx ctx'}          ; fragment + threaded ctx
{:steps [step ...]}                   ; sub-process splices in
{:tx-data ... :steps [...] :ctx ...}  ; any combination
```

`{:steps [...]}` is the **monadic flatten** — a step that returns
more steps splices them into the queue. Sub-transactors aren't
"called" in the function sense; they emit step-lists.

### Composition without `run-process`

If you don't need the speculative-db threading or the
serialise-on-conn lock, you can compose builders directly:

```clojure
(let [doc-frag  (audit-doc/create-doc-tx-data db doc-opts)
      hold-frag (legal-hold/place-tx-data    db hold-opts)
      combined  (kbt/with-vt (into (vec doc-frag) hold-frag)
                              #inst "2026-05-14" kbt/forever)]
  (validation/transact-with-validation conn combined))
```

Same atomicity, same gate, less ceremony. The third test in
[test/kontor/composition_test.clj](../test/kontor/composition_test.clj)
demonstrates this.

### Cross-step identity — string tempids, not speculative eids

When a step references an entity another step created, use the
**string tempid** as the value, not an eid read off the speculative
db:

```clojure
;; ✓ RIGHT — string tempid threads consistently
(legal-hold/place-tx-data
 sdb {:supporting-doc "subpoena"   ; the tempid from step 1
      ...})

;; ✗ WRONG — speculative eid is a load-bearing assumption
(let [doc-eid (d/q '[:find ?e . :where [?e :audit-doc/code "SUB-001"]] sdb)]
  (legal-hold/place-tx-data sdb {:supporting-doc doc-eid ...}))
```

Datahike's tempid allocator IS order-stable (note 47, plus the
regression test in `process_test.clj`), but ADR-067 still treats
speculative-eid reads as a footgun: the speculative db is for
*reading committed data and prior-step facts*, not for capturing
identity.

The one exception is `inventory/issue!` — it deliberately uses the
order-stable speculative-eid pattern for the negative-fill layer,
covered by a regression test. See note 47 for the rationale.

### Composability knobs

When N builder outputs compose into one tx-data, internal tempids
collide. Builders that hardcode tempids accept knobs:

| Builder | Knob | Default |
|---|---|---|
| Most `*-tx-data` with an entity tempid | `:tempid` | the literal it used pre-Stage-P (e.g. `"hold-1"`, `"asset-1"`) |
| `open-book-tx-data` / `open-liability-book-tx-data` | `:tempid-suffix` | `""` |
| `open-book-tx-data` (for cross-step asset references) | `:asset-tempid` | nil (resolve mode) |
| `kontor.posting/build-transaction` | `:tx-tempid` (top-level) | `-1` |
| `apply-payment-tx-data`, `reverse-application-tx-data`, dunning `emit-dunning-event-tx-data` | `:tempid-suffix` | `""` |

`:tx-tempid` on `build-transaction` derives posting tempids as
`"<tx-tempid>-pN"` so multi-posting compositions collide-free.

## How to add a new transactor

Recipe for a typical kernel or companion transactor:

1. **Write the pure builder first.** Take `db` + opts; return
   tx-data. Inline the input validations (`when-not ...`). Use a
   string `:tempid` opt for any new entity. Don't call `d/transact`.
   Don't call `kbt/with-vt` (unless you're the kernel's
   `build-transaction` — the one documented exception).

2. **Write a `(declare xxx-tx-data)` if needed.** If the wrapper
   sits before the builder in the file, declare so the compile-time
   reference resolves.

3. **Write the wrapper.** Two lines:
   ```clojure
   (defn xxx! [conn opts]
     (validation/transact-with-validation
      conn (kbt/with-vt (xxx-tx-data (d/db conn) opts)
                        (or (:vt-from opts) (sensible-default opts))
                        (or (:vt-to opts) kbt/forever))))
   ```
   `sensible-default` is whatever event-time fits the transactor —
   `:placed-at` for a hold, `:effective-date` for a posting, `(Date.)`
   for a now-event.

4. **Test the wrapper + the builder separately.** The wrapper's test
   exercises end-to-end (`(d/q '[:find ...] (d/db conn))`); the
   builder's test ensures pure composability (it never touches a
   conn).

5. **Watch for cycles.** If your namespace is in
   `kontor.validation`'s required chain (legal-hold, period,
   sealing, state-machine, status-machine, posting), use
   `requiring-resolve` for the gate:
   ```clojure
   (defn- transact-with-validation [conn tx-data]
     ((requiring-resolve 'kontor.validation/transact-with-validation)
      conn tx-data))
   ```
   See `legal_hold.clj` for the canonical example.

## The documented carve-outs

Three places where the "every `!` routes through the gate" rule has
an exception, each for a structural reason:

1. **`kontor.authz.client/do-write-relationships!`** — the authz
   module is designed to run on its OWN minimal datahike conn
   without the kernel schema. Kernel-gate routing crashes on
   missing kernel attrs. To compose authz with kernel writes, use
   `kontor.authz.client/write-relationships-tx-data` (or
   `grant-tx-data` / `revoke-tx-data`) inside a `kontor.process`
   step on a conn with BOTH schemas installed; the consumer's
   process gates the combined tx-data.

2. **`kontor.period/close!`'s `:period/lock-tx` denorm** — records
   the gate's own tx-id as an audit denorm. `:db/current-tx` does
   not resolve as a `:db.type/long` value in datahike (only as a
   `:db/id` tempid), so the denorm cannot ride inside the gated
   tx itself. It's a follow-up raw `d/transact`. The carve-out is
   documented in ADR-068.

3. **`kontor.posting/build-transaction`'s embedded `kbt/with-vt`** —
   the kernel's per-transaction valid-time IS always
   `:transaction/effective-date` when nothing else is specified;
   forcing every caller to set it externally would be onerous.
   `kontor.process/run-steps` strips the embedded vt via
   `strip-tx-meta`, then the process's outer `with-vt` wins. The
   exception is local; the universal rule holds everywhere else.

## Bootstrap vs business writes

ADR-068 carves out **bootstrap-class** writes that stay on raw
`d/transact`:

- Schema installers (`*/schema.clj`, `kontor.schema`).
- l10n chart-of-account seeders (`modules/l10n-*/src/.../chart.clj`).
- `kontor.core/create-test-db` (in-memory test conn setup).
- The invariant rule installer (`validation/install-invariants!`).
- The Beancount import loader (`kontor.import_.beancount/load-into!`).
- The l10n-de `close-fiscal-year!` journal lazy-creation step.

These are one-shot setup paths, not business writes. The gate isn't
even installed yet for schema installers; the validators would have
nothing to enforce against. Per-customer chart-of-account installs
similarly seed configuration, not business events.

If you're unsure whether your code is bootstrap or business: does a
real consumer write this through a business API as part of normal
operation? If yes → business write, gate it. If no → bootstrap,
stay raw and document it as such in the docstring.

## Test patterns

### Composition tests

`test/kontor/composition_test.clj` is the canonical demo. Three
patterns:

1. **Cross-module compose via `run-process`** — multiple builders'
   outputs threaded as step fns, one atomic gated commit. Use this
   to demonstrate cross-cutting business events.

2. **Atomic abort on gate violation** — set up a state where one
   step's tx-data fails a validator (e.g. an `:approval-policy`
   violation, an unbalanced posting), assert that the whole event
   aborts and no fragment lands.

3. **Plain tx-data vec-concat** — for callers that don't need
   `run-process`'s ceremony.

### Speculative-eid regression guard

`test/kontor/process_test.clj/speculative-db-eid-round-trips-to-final-commit`
pins datahike's tempid-allocator invariant. The `inventory/issue!`
merger relies on it; if a future datahike upgrade changes the
allocator, this test fails loudly.

### Fixture seeding

Tests do raw `d/transact` for fixture data (commodities, accounts,
journals, partners). That's bootstrap, not business writes. Don't
route fixture seeding through the gate — it slows the suite and the
gate has nothing meaningful to enforce against bare schema data.

```clojure
(defn- catalog! [conn]
  (d/transact conn
              [{:db/id -1 :commodity/symbol "EUR" :commodity/name "Euro"
                :commodity/precision 2 :commodity/iso-4217 "EUR"}
               {:db/id -2 :account/path "Assets:Cash" ...}
               ...])
  (let [db (d/db conn)]
    {:eur (:db/id (d/entity db [:commodity/symbol "EUR"]))
     :cash (:db/id (d/entity db [:account/path "Assets:Cash"]))}))
```

Use the catalog's eids to drive the actual business-write under
test through its `!` wrapper.

## Where to go next

- **The eight kernel concerns** kontor solves end-to-end: see
  [doc/value.md §"The eight pains kontor solves at the kernel"](value.md#the-eight-pains-kontor-solves-at-the-kernel).
- **ADR-067 + ADR-068** for the canonical design of the transact
  programming model: [doc/decisions.md](decisions.md).
- **Research note 47** (inventory + transaction composition) for
  the prior-art analysis that informed the cross-module composition
  story across Odoo / Tryton / NetSuite / SAP.
- **Research notes 48 + 49** for the Stage P review-after — code-
  review findings + integration-shape analysis with the carve-outs
  documented.
- **Showcases**: [doc/showcases/](showcases/) — fully-worked
  end-to-end scenarios. Showcase 1 (DE Mahnverfahren), Showcase 4
  (multi-entity intercompany) are particularly good for grokking
  the cross-module composition story.

## A note on what's NOT here

This document covers the **transact programming model post-Stage-P**.
It doesn't cover:

- The detailed schema (see [src/kontor/schema.clj](../src/kontor/schema.clj)
  and the per-module schemas).
- The query/reporting story (see [doc/architecture.md](architecture.md)
  and the `kontor.balance` / `kontor.trial` / `kontor.ledger`
  namespaces).
- Per-country l10n details (each `kontor-l10n-*` module has its
  own README-like preamble in its core ns).
- The bitemporal query helpers in detail (covered briefly above,
  fully in `kontor.bitemporal`'s docstrings).

License: EPL-1.0. Patches welcome at
[github.com/replikativ/kontor](https://github.com/replikativ/kontor).
