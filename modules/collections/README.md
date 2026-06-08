# kontor-collections

AR collections workflow for `kontor` — case / dispute / promise /
pause / credit-hold / aging / dunning / bad-debt write-off, all wired
through ADR-034 status machines + ADR-038 audit-doc + ADR-041
side-effect intents.

## What it does

A receivable past its due date becomes a collections problem: someone
has to chase it, dispute resolution may interrupt the chase, a
payment promise may pause it, a manager may grant a forbearance
window, and eventually the manager writes it off as uncollectible.
AR collections workflows differ widely across organisations;
`kontor-collections` ships the primitives so consumers can compose
theirs:

- **`:collection-case` workflow root** (`kontor.collections.case`,
  ADR-043). One open case per (partner, entity); close before
  re-opening. Cases coordinate over N invoices (distinct from
  `kontor.invoice`'s per-invoice lifecycle moves). State machine on
  `:collection-case/state`; assignment to collectors;
  `refresh-denorms!` walks open invoices to recompute
  `:collection-case/total-overdue` + `:oldest-invoice`.
- **`:collection-dispute`** (`kontor.collections.dispute`). Raise /
  resolve disputes against specific invoices; an open dispute is a
  pause-gate for dunning + a write-off pre-condition (you cannot
  write off a disputed invoice without resolving the dispute first).
- **`:collection-promise`** (`kontor.collections.promise`). A
  payment promise made by the customer (date + amount). Open
  promises pause dunning; `mark-promise-kept!` / `mark-promise-
  broken!` move the state; `renegotiate!` records a new promise
  replacing the prior; `sweep-broken-promises!` finds promises past
  their `:promised-by` without payment.
- **`:collection-pause`** (`kontor.collections.pause`). Manager-
  granted forbearance window. Pauses dunning for the duration
  (`:expires-at`). Multiple pauses can stack; `any-active-pause?`
  is the gate.
- **`:credit-hold` per-(partner, entity) overlay**
  (`kontor.collections.credit-hold`, ADR-043 + ADR-039). Composes
  with `:partner/credit-status` scalar: the resolver
  `credit-status-for` walks active `:credit-hold` rows first
  (multi-entity tenants can hold a partner on one subsidiary
  without blocking the others) then falls back to the scalar
  (single-entity tenants experience zero complexity). Live
  `credit-utilization` query — computed from current postings, not
  a cached snapshot. Hosts
  `unapplied-cash-balance` for the unapplied-cash dunning gate.
- **Aging** (`kontor.collections.aging`). `open-ar-invoices`
  bitemporal, `aging-rows` per invoice with configurable buckets,
  `aging-summary` + `aging-by-partner` aggregations.
- **Dunning** (`kontor.collections.dunning`, ADR-043).
  `plan-dunning-run` is PURE — reads the db at `:as-of`, walks open
  cases under a `:dunning-policy`, applies the pause gates (dispute
  open, promise open, unapplied-cash pending, frequency-cap
  exceeded), returns a vec of planned rows the caller chooses to
  emit. `emit-dunning-event!` materialises one row in ONE tx: writes
  the `:dunning-event` + an `:audit-doc` for the rendered letter +
  a `:side-effect-intent` for the outgoing channel (email / letter /
  phone / portal) — the channel work is decoupled.
- **`DunningTemplateProvider` protocol** with `static-template-
  provider` built-in. l10n modules (`kontor-l10n-de`,
  `kontor-l10n-us`) ship concrete impls with country-specific
  template content (BGB §286 Mahnung, CFPB Reg-F-compliant US
  notices).
- **`:dunning-policy` resolution** with 4-step fall-through:
  `(entity, segment)` → `(entity, :default)` → `(nil-entity,
  segment)` → `(nil-entity, :default)`. Most-specific wins.
- **Frequency cap** — `dunning-events-in-window` counts sent
  (non-skipped) events in the last N days deterministically (reads
  `:as-of`, not `System/currentTimeMillis` — P1 fix).
- **Bad-debt write-off** (`kontor.collections.writeoff`). One
  atomic tx per case: for each open invoice on the case, builds a
  kernel `:transaction` Dr `:bad-debt-expense` / Cr `:ar` at the
  open-amount; drives `:collection-case/state → :written-off`;
  writes an `:audit-doc` with `:type :write-off-supporting` and
  refs it on the status-history row. Requires the manager's
  pre-uploaded supporting doc (sign-off + customer outreach log).

## When to use it

- AR collections workflow on top of `kontor-invoice` (the case
  walks open invoices)
- Dunning batches (BGB §286 Mahnstufen, CFPB Reg-F US notices, etc.)
- Multi-entity tenants where credit holds need to be subsidiary-
  scoped
- Bad-debt write-off with audit trail

When NOT to use it:
- Pre-due-date payment-term + due-date calc → kernel
  `kontor.payment-term` + `kontor.aging`
- Bank reconciliation → kernel `kontor.reconciliation`
- Cash application against open invoices → kernel
  `kontor.payment-application`
- Dispute resolution as a CRM ticket — that lives in the consumer

## Load-bearing ADRs

- [ADR-043](../../doc/decisions.md) — AR collections + dunning +
  dispute + credit-hold + bad-debt write-off
- [ADR-039](../../doc/decisions.md) — `:partner/credit-status`
  scalar (the credit-hold overlay composes with this)
- [ADR-034](../../doc/decisions.md) — `:status-transition` machine
  shape used by every collection state (case, dispute, promise,
  pause, credit-hold)
- [ADR-038](../../doc/decisions.md) — `:audit-doc` + approval-policy
  (write-off supporting-doc + dunning-event rendered-letter docs)
- [ADR-041](../../doc/decisions.md) — `:side-effect-intent` for
  outgoing channel work (email / letter / phone / portal)
- [ADR-068](../../doc/decisions.md) — `*-tx-data` builders + `!`
  wrappers routing through `kontor.validation/transact-with-
  validation`

## Key namespaces

- `kontor.collections.schema` — `:collection-case/*`,
  `:collection-dispute/*`, `:collection-promise/*`,
  `:collection-pause/*`, `:credit-hold/*`, `:dunning-policy/*`,
  `:dunning-event/*` + status-transition seeds + `install!`
- `kontor.collections.case` — case lifecycle (`open-case!`,
  `advance-state!`, `close-case!`, `assign-collector!`,
  `refresh-denorms!`) + each with paired `*-tx-data` builder
- `kontor.collections.dispute` — `raise-dispute!`, `advance-state!`,
  `resolve-dispute!` + queries (`open-disputes-for-invoice`,
  `any-open-dispute-for-invoice?`, `disputes-by-reason`)
- `kontor.collections.promise` — `record-promise!`,
  `mark-promise-kept!`, `mark-promise-broken!`, `renegotiate!`,
  `sweep-broken-promises!`
- `kontor.collections.pause` — `place-pause!`, `release-pause!`,
  `active-pauses-for-case`, `any-active-pause?`
- `kontor.collections.credit-hold` — `place-hold!`, `release-hold!`,
  `release-all-for!`, `credit-status-for`, `current-hold`,
  `credit-utilization`, `unapplied-cash-balance`
- `kontor.collections.aging` — `open-ar-invoices`, `aging-rows`,
  `aging-summary`, `aging-by-partner`
- `kontor.collections.dunning` — `DunningTemplateProvider` protocol
  + `static-template-provider` built-in + `resolve-policy` +
  `plan-dunning-run` (PURE) + `emit-dunning-event!` +
  `frequency-cap-violated?` + `dunning-events-in-window`
- `kontor.collections.writeoff` — `write-off-case!`

## Minimal example

```clojure
(require '[kontor.core                       :as k]
         '[kontor.collections.case           :as case]
         '[kontor.collections.dunning        :as dunning]
         '[kontor.collections.aging          :as aging]
         '[kontor.collections.schema         :as coll-schema]
         '[kontor.invoice.schema             :as inv-schema])

(def conn (k/create-test-db))
(inv-schema/install! conn)
(coll-schema/install! conn)
;; ... + seed entity, partner, dunning-policy, AR invoices past due

;; Step 1 — open a case for the (partner, entity)
(case/open-case!
  conn {:code "CASE-2026-0001"
        :partner [:partner/external-id "acme-buyer"]
        :entity [:entity/code "acme-de"]
        :opened-by-uid <collector-uid>
        :supporting-doc <doc-eid>})

;; Step 2 — plan a dunning batch (PURE — no side effects)
(def policy (dunning/resolve-policy
              (datahike.api/db conn)
              {:entity [:entity/code "acme-de"]
               :segment :enterprise}))

(def plan
  (dunning/plan-dunning-run
    (datahike.api/db conn)
    {:as-of #inst "2026-05-18"
     :entity [:entity/code "acme-de"]
     :policy policy
     :cases [{:case-eid <case-eid>
              :invoice-eid <inv-eid>
              :partner <partner-eid>
              :locale :de}]}))

;; Step 3 — emit each non-skipped row (one tx per row:
;; :dunning-event + :audit-doc + :side-effect-intent)
(def provider (dunning/static-template-provider
                {1 {:de [:reminder :friendly]}
                 2 {:de [:mahnung :level-2]}}))

(doseq [row (remove :skipped? plan)]
  (dunning/emit-dunning-event!
    conn {:plan-row row
          :channel :email
          :provider provider
          :acting-uid <collector-uid>}))
```

## What it does NOT do

- **No per-country dunning templates.** The `DunningTemplate
  Provider` protocol + the `static-template-provider` fallback are
  here; jurisdiction-specific templates (BGB §286 Mahnstufen,
  CFPB Reg-F US notices, FCA Consumer-Duty UK letters) live in
  l10n modules.
- **No outgoing email / letter / SMS delivery.** The dunning
  emission writes a `:side-effect-intent` per ADR-041; the
  consumer's runtime drains the intent and actually sends the
  channel work.
- **No payment processor integration.** A promise is just a
  recorded customer commitment; the actual cash hits via
  `kontor.payment-application` (kernel).
- **No automated dispute-resolution scoring.** Disputes are
  workflow state; routing / scoring is consumer-side.
- **No credit-scoring or credit-limit determination.** The hold
  overlay enforces, but the *decision* (limit, score, override) is
  consumer-side.

## Tests

`modules/collections/test/kontor/collections/`:

- `schema_test.clj` — schema install + status-transition seeds
- `lifecycle_test.clj` — case / dispute / promise / pause / credit-
  hold lifecycle + status transitions
- `dunning_test.clj` — `plan-dunning-run` purity + pause-gate
  composition + frequency-cap + `emit-dunning-event!` shape
- `writeoff_test.clj` — multi-invoice `write-off-case!` + audit-doc
  reference invariants
- `p0_fixes_test.clj` — review-after P0 regressions (`plan-dunning-
  run` determinism, credit-hold valid-time semantics, etc.)

## License

Apache 2.0.
