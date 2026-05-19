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
[Showcase 06](showcases/06_de_gmbh_multi_year.clj) is the worked
example to read first: a misclassified Y1 expense gets caught and
corrected in Y2 via `close-validity!` + a new write at the
original valid-time, and both views — "what the Y1 books said at
year-end 2026" and "what they say now, restated" — remain queryable
forever.

#### `commit-tx-eid` — the close-validity footgun, averted

`close-validity!` operates on the **datahike commit-tx eid** (the
carrier of the `:db.valid/from` / `:db.valid/to` window), NOT on
any business entity created in that transaction. A kontor
`:transaction` / `:partner` / `:posting` entity's eid is *not* the
commit-tx eid; they are EAVT-distinct. Closing a business-entity
eid is a silent no-op — it looks valid (no error raised), but
nothing happens.

`kontor.bitemporal/commit-tx-eid` extracts the commit-tx eid from
a `d/transact` (or `transact-with-validation`) tx-report so the
caller can pass it back into `close-validity!` later:

```clojure
(require '[kontor.bitemporal :as kbt])

;; Make the original write — remember the commit-tx eid.
(let [report (validation/transact-with-validation
              conn (kbt/with-vt (posting/post-transaction-tx-data ...)
                                #inst "2026-11-22"))
      original-tx-eid (kbt/commit-tx-eid report)]
  (save-somewhere! original-tx-eid))

;; ... 11 months later ...

;; Close the original tx's valid-time window at the correction date,
;; then write the corrected posting at the original valid-time.
(kbt/close-validity! conn original-tx-eid #inst "2027-10-15")
(validation/transact-with-validation
 conn (kbt/with-vt (posting/post-transaction-tx-data ...)
                   #inst "2026-11-22")) ; original effective-date
```

If you ever see "I called close-validity! and nothing happened,"
the explanation is almost always that you passed a business-entity
eid (the invoice, the posting, the transaction row) instead of the
commit-tx eid. `commit-tx-eid` is the right ergonomic; it throws
`:type :kontor.bitemporal/no-commit-tx` if the report is malformed
rather than silently returning nil.

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

## Substrate-tier seams — `*-Provider` protocols

A handful of kernel namespaces expose **provider protocols** the
consumer implements once per (vendor, jurisdiction, valuation
method) and the kernel calls. The recurring shape is "the kernel
ships the protocol + a static-table default + a chain combinator;
the consumer ships the live integration." Three additions since
Stage P matter most for a consumer writing trans-national code:

### `TaxRateProvider` (ADR-071)

`kontor.tax-rate-provider/TaxRateProvider` (one method:
`rate-for`) returns a `TaxFacts` record given a `TaxQuery` (buyer
+ seller jurisdictions, product/service category, valid date,
amount). `TaxPostingBuilder` turns the `TaxFacts` into postings.
The kernel ships `StaticTableProvider` + scaffolds for Avalara,
TaxJar, SST; the consumer composes them via `chain`.

This supersedes the original `kontor.tax-provider/TaxProvider`
(ADR-005), which is kept for back-compat but new code should
target the ADR-071 shape — rate determination is now pure data,
and a `TaxFacts` is composable into a `kontor.process` step
without a side-effect to the rate engine inside the writer lock.

### `FxRateProvider` (ADR-072)

`kontor.fx-rate-provider/FxRateProvider` (`get-rate`,
`get-rates-batch`) returns the rate for a `(from, to, instant,
rate-type)` tuple. Rate-types are the IAS 21 / ASC 830 concepts —
`:spot`, `:closing`, `:average`, `:historical`. Built-ins:
`StaticTable` (test fixtures), `EcbReferenceRates` (CSV ingest
from the ECB), `Chained` (try providers in order, first hit
wins). `kontor.fx` then exposes `convert`, `translate-money-seq`,
`to-functional-currency` over `Money` values.

A typical consumer setup:

```clojure
(require '[kontor.fx-rate-provider :as fxp]
         '[kontor.fx :as fx])

(def fx (fxp/chain [(fxp/make-static-table-provider conn :override)
                    (fxp/make-ecb-reference-rates-provider conn)]))

(fx/convert fx (k/money 1000M "EUR") "USD"
            #inst "2026-09-30" :closing)
;; => Money[USD, 1063.50M]
```

### `PayrollProvider` trio (ADR-075)

`kontor.payroll-provider` exposes three protocols that together
drive `kontor.hr.payroll/run-payroll!`:

- **`PayrollComputeProvider`** — `(compute period employment)`
  returns canonical `PayrollFacts` (gross, statutory withholdings,
  net) for one employment in one period. Country adapters parse
  their engine's export file (DATEV LODAS, ADP GLI, Ceridian
  Dayforce, …) into this canonical shape.
- **`PayrollPostingBuilder`** — `(postings-for facts)` returns
  tx-data routing the facts to the country's chart of accounts +
  any required accruals (HGB §249 PTO for DE, ASC 710 PTO + 401(k)
  match for US, CPC-33 férias + 13º for BR, etc.).
- **`PayrollEmitProvider`** — `(emit period facts)` produces the
  regulator filing for the period (LODAS Importdatei, W-2, T4 +
  T619, DSN NEODES, STP P2, eSocial S-1000 … S-2399, CFDI Nómina,
  Form 24Q, Gensen, IIT, mBGM + L16).

Same orchestrator, eleven country adapters
(`modules/payroll-{de-datev,us-adp,ca,fr,au,br,mx,in,jp,cn,at}`).
The trans-national Jane-Doe scenario in
`test/kontor/stage_r_cross_stage_test.clj` runs three concurrent
employments (DE + US + BR) on one global `:person` through one
month of payroll across three engines, demonstrating that the
substrate keeps the bitemporal audit chain intact across
jurisdictions.

## Consent — `:consent/*` as a bitemporal gate

ADR-094 ships a `:consent/*` mini-schema (in `kontor-hr`) that
records per-(subject, scope, legal-basis) consent as a bitemporal
fact, plus a query helper that answers "was consent operationally
in force at time T?" without re-reading the current `:state`:

```clojure
(require '[kontor.hr.consent :as consent])

(consent/active-at?
 (d/db conn)
 jane-doe-person-eid          ; subject
 :hr-monitoring-consent       ; scope (an :audit-doc/category)
 #inst "2026-09-15")          ; the instant to test
;; => true   ; consent was granted before and not yet withdrawn
```

`active-at?` deliberately respects the operational window
`[granted-at, withdrawn-at)` even when the current `:state` is
`:withdrawn` or `:superseded`. That's the regulator-aligned
semantic: a withdrawal does NOT retroactively invalidate
processing that happened lawfully under the prior consent.

The kernel does NOT enforce consent at the write path —
substrate stays neutral. Consumer policy layers
(`kontor-people-record`, the `kontor.dsar` bundler, your MCP
agent's tool catalog) decide whether to refuse a write based on
the consent's `:legal-basis`. The substrate's job is to record
every grant + withdrawal + supersession as a bitemporal fact so a
regulator can replay the timeline.

Two new `:approval-policy/rule` values land alongside (ADR-094):

- **`:requires-dpia-supporting-doc`** — the change-spec's
  `:supporting-doc` ref must point to an `:audit-doc` carrying
  `:audit-doc/category :hr-monitoring-consent`.
- **`:requires-works-agreement-ref`** — the change-spec must
  include a `:works-agreement-ref` pointing at an audit-doc with
  `:audit-doc/type :betriebsvereinbarung` or `:works-agreement`.

Both are kernel-enforced in `kontor.status-machine/check-policy`,
so consumer transitions opt in via a transacted `:approval-policy`
seed without needing custom validator code.

## LLM / MCP agent integration — `kontor.agent-tools`

For agentic write-paths (MCP servers, OpenAI tool-use, Anthropic
tool-use), `kontor.agent-tools` ships a **server-agnostic tool
catalog** the agent invokes through the kernel's normal validation
gate. The catalog is plain data; the transport (MCP JSON-RPC,
HTTP, gRPC) is the consumer's choice.

A tool spec is a map:

```clojure
{:name          "snake_case_name"
 :description   "What it does — visible to the agent."
 :input-schema  {:type "object" :properties {...} :required [...]}
 :side-effects? true | false
 :handler       (fn [{:keys [conn db args]}] result-map)}
```

Read tools (`:side-effects? false`) read from `db` (defaults to
`(d/db conn)`). Write tools (`:side-effects? true`) route through
`kontor.validation/transact-with-validation` — every kernel gate
fires identically to a direct Clojure call. There is no separate
permission layer; the substrate gates are the only enforcement.

The bundled `default-catalog-tools` covers eight tools across the
read + write surface:

- **Read**: `kontor_explain_balance`, `kontor_account_balance`,
  `kontor_trial_balance`, `kontor_explain_posting`,
  `kontor_entities_with_concept_iri`, `kontor_dsar_collect`.
- **Write**: `kontor_create_audit_doc`,
  `kontor_post_transaction`.

Consumers register more with `register-tool!` (idempotent —
re-registration overwrites by `:name`, useful for REPL hot-reload).

`kontor.agent-tools` deliberately does NOT ship its own JSON-RPC
server. The kontor project's stance (note 94 §3.2): the
leverage point is the tool catalog, not another server.
For MCP transport, compose with [dvergr](https://github.com/replikativ/dvergr)'s
existing MCP server:

```clojure
(require '[dvergr.mcp.server :as dvergr-mcp]
         '[kontor.agent-tools :as kt])

(kt/install-default-catalog!)
(swap! dvergr-mcp/tool-handlers merge
       (kt/dvergr-handlers conn (kt/default-catalog conn)))
(dvergr-mcp/start! {:port 17888})
```

A standalone `kontor-mcp` is deferred until a consumer asks for
one without buying into dvergr's full stack.

## The documented carve-outs

Three places where the "every `!` routes through the gate" rule has
an exception, each for a structural reason:

1. **`kontor.authz.client/do-write-relationships!` and
   `kontor.authz.schema/write-schema!`** — the authz module is
   designed to run on its OWN minimal datahike conn without the
   kernel schema. Kernel-gate routing crashes on missing kernel
   attrs. To compose authz writes with kernel writes, use
   `kontor.authz.client/write-relationships-tx-data` (or
   `grant-tx-data` / `revoke-tx-data`) for relationships, and
   `kontor.authz.schema/write-schema-tx-data` for permission-schema
   definitions, inside a `kontor.process` step on a conn with BOTH
   schemas installed; the consumer's process gates the combined
   tx-data.

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

- **Single worked example to read first**:
  [doc/showcases/06_de_gmbh_multi_year.clj](showcases/06_de_gmbh_multi_year.clj)
  exercises every axis in this document — bitemporal correction
  via `close-validity!` + `commit-tx-eid`, status machines on
  invoice + employment + DSAR lifecycles, the transact gate on
  every business write, `kontor.process` for atomic cross-module
  composition (sealing a period closes related invoices and emits
  the BWA in one commit). The 10-minute walkthrough version lives
  at [doc/start-here.md](start-here.md).
- **The eight kernel concerns** kontor solves end-to-end: see
  [doc/value.md §"The eight pains kontor solves at the kernel"](value.md#the-eight-pains-kontor-solves-at-the-kernel).
- **ADR-067 + ADR-068** for the canonical design of the transact
  programming model: [doc/decisions.md](decisions.md). ADR-071..074
  for the trans-national substrate (`TaxRateProvider`,
  `FxRateProvider`, `kontor.consolidation`, `CrossTxRouter`).
  ADR-075 for the payroll provider trio. ADR-090..092 for the
  McComb-aligned substrate seams (`:concept-iri`,
  `kontor.explain`, `kontor.event-bus`). ADR-094 for the consent +
  retention + AI Act posture.
- **Research note 47** (inventory + transaction composition) for
  the prior-art analysis that informed the cross-module composition
  story across Odoo / Tryton / NetSuite / SAP.
- **Research notes 48 + 49** for the Stage P review-after — code-
  review findings + integration-shape analysis with the carve-outs
  documented.
- **Research note 92** for the company-as-software market
  positioning + note 94 §3.2 for the `kontor.agent-tools` design
  rationale.
- **Showcases**: [doc/showcases/](showcases/) — six fully-worked
  end-to-end scenarios. Showcase 05 (Apple 10-K/A) and showcase 06
  (DE GmbH multi-year) demonstrate the bitemporal headline;
  Showcase 4 (multi-entity intercompany) and Showcase 1 (DE
  Mahnverfahren) are good for grokking the cross-module
  composition story.

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
