# Conventions

Coding and API conventions that the kernel and all companion modules
share. These aren't ADRs (they record no decision-rationale); they're
the codified shape that emerged from the ADRs and is enforced by code
review.

For *why* the layout looks the way it does, see
[architecture.md](architecture.md) and [decisions.md](decisions.md).

---

## Transactor opts shape (canonical)

Every public function that mutates an entity through a state machine
takes its first arg as `conn` and its second arg as an `opts` map.
The de-facto kernel convention for the opts map:

```clojure
{:vt-from         #inst "..."   ; valid-time start (default = now,
                                  ; or :transaction/effective-date
                                  ; for posting builders).
                                  ; kontor.bitemporal (ADR-048).
 :vt-to           #inst "..."   ; valid-time end (default = kbt/forever).
 :changed-by-uid  <ref>          ; actor on the :status-history row.
                                  ; Required for facets governed by an
                                  ; :approval-policy with :requires-
                                  ; non-anonymous (ADR-038).
 :reason          :keyword       ; codified vocabulary (ADR-038).
                                  ; :other is allowed but forces
                                  ; :reason-note to be non-empty.
 :reason-note     "free-text"    ; required when :reason is :other.
 :supporting-doc  <ref>          ; ref to :audit-doc — required by
                                  ; some :approval-policy rules.
 :origin-transaction <ref>}      ; ref to :transaction; lets the
                                  ; status-history row carry the
                                  ; commercial-tx context.
```

**Linter-grade rule.** If a fn writes a `:db.type/instant` attribute
named `*-at` to express a transition timestamp, it must accept
`:vt-from`/`:vt-to`. Audit-row timestamps (`:audit-doc/uploaded-at`,
`:status-history/changed-at`) are OK without the opts — they're
audit-row times (C4 per ADR-048's classification), not facet
denorms.

Transactors that already follow this shape:

- `kontor.payment-application/{apply-payment!,reverse-application!}`
- `kontor.status-machine/record-status-change!`
- `kontor.invoice/{send!,mark-paid!,cancel!,flip-paid-on-settlement}`
  (kernel surface; both surfaces ADR-034/038 compliant after the
  P0-4α port)
- `kontor.procurement.receipt/post-receipt-with-inventory!`
- `kontor.collections.case/{open-case!,advance-state!,close-case!}`
- `kontor.collections.dispute/{raise-dispute!,advance-state!,resolve-dispute!}`
- `kontor.collections.promise/{record-promise!,mark-promise-kept!,
  mark-promise-broken!}`
- `kontor.collections.credit-hold/{place-hold!,release-hold!}`
  (after the P0-5 port)
- `kontor.collections.pause/{place-pause!,release-pause!}` (same)
- `kontor.collections.writeoff/write-off-case!`
- `kontor.invoice.bridge/{make-ready!,post-to-ledger!,mark-paid!,cancel!}`

If you write a new transactor that mutates entity state, follow the
same shape. The shape composes — your transactor can call
`sm/record-status-change-tx-data` with the same map plus `:entity` /
`:entity-type` / `:facet` / `:to`, wrap with `kbt/with-vt`, and
transact.

---

## Status-machine writes

State changes go through `kontor.status-machine`:

- **`record-status-change-tx-data`** — pure; returns tx-data ready
  to compose with other writes in one tx. Use when the status flip
  must be atomic with other operations (e.g. posting + invoice-
  status-flip in one tx).
- **`record-status-change!`** — convenience transactor. Equivalent
  to `(d/transact conn (record-status-change-tx-data ...))`.

Both auto-detect `:from` from the current value when not supplied,
validate against `:status-transition` rows, run ADR-038 policy
checks, and emit a `:status-history` row.

**Don't write `:foo/status` directly via `d/transact`** unless the
attribute genuinely isn't governed by ADR-034 (e.g. `:transaction/
state` uses the older `kontor.state-machine` for kernel-internal
posting lifecycle).

---

## Valid-time stamping

`kontor.bitemporal/with-vt` is idempotent — wrap your tx-data with
it, optionally re-wrap downstream, the last `:vt-from`/`:vt-to`
wins. Kernel builders (`kontor.posting/build-transaction`,
`kontor.posting/plan-stock-move`, `kontor.import_.beancount/...`)
auto-stamp from `:transaction/effective-date` so the common path
needs no caller intervention.

Backdated corrections: pass `:vt-from` / `:vt-to` to the transactor;
that's it. ADR-048.

---

## Namespacing

Every datahike attribute namespaces under a kernel namespace
(`:account/*` `:journal/*` `:transaction/*` `:posting/*` ...) or a
companion-module namespace (`:invoice/*` `:order/*` `:collection-
case/*` `:dispute/*` ...). New namespaces need an ADR.

This convention is what lets kontor cohabit with consumer apps in
one datahike connection (ADR-002).

---

## Money

`Money` is always `BigDecimal` + commodity ref. Never doubles. The
type lives in `src/kontor/money.clj`. Rounding is HALF-EVEN unless a
regulator mandates otherwise (some VAT jurisdictions require HALF-
UP; those are documented case-by-case in the l10n modules).
ADR-013.

Computed sums use `money/sum-by-commodity`. Negation:
`.negate ^java.math.BigDecimal amount`. Never `(- amount)` — that
falls back to `clojure.core/-` and can lose the type hint.

---

## Test fixtures

- `(kontor.core/create-test-db)` returns a fresh in-memory conn in
  ~50ms with the kernel schema + bitemporal-tx-attrs + primary
  ledger installed. Use freely from the REPL.
- Companion-module tests must additionally call the companion's
  `install!` (`kontor.invoice.schema/install!`,
  `kontor.collections.schema/install!`, etc.) to get the relevant
  `:status-transition` seeds.
- Backdated test scenarios use `kbt/with-vt` (or the transactor's
  `:vt-from` opt) — never write removed denorm attrs like
  `:posting/valid-from` directly; the schema rejects them.

---

## Provider protocols

Three pluggable seams (ADR-005, ADR-017, ADR-029):

- `TaxProvider` — tax computation; default `StaticTableProvider`
  reads per-country EDN; per-customer adapters wrap external APIs
  (Avalara/TaxJar/TaxCloud).
- `EInvoiceProvider` — pure-data e-invoice generation; default
  `PureXmlProvider` covers UBL/Factur-X/NF-e shapes.
- `CostingProvider` — inventory valuation; built-in impls FIFO /
  LIFO / WeightedAverage / StandardCost.

Bundle adapters separately from credentials. The kernel never holds
API keys.

---

## REPL workflow

```bash
bb nrepl                                 # boots + prints port
clj-nrepl-eval --discover-ports          # find it
clj-nrepl-eval -p PORT "(require ... :reload)"
clj-nrepl-eval -p PORT "(test-vars ...)"
```

~200ms per cycle vs `bb test`'s full-JVM ~10s. Use for every
iteration; reserve `bb ci` for pre-commit.

---

## Documentation

- One-line comment is the max for almost everything. Write WHY when
  it isn't obvious from the code; never write WHAT.
- Per-stage rhythm (research-before / implement / review-after) is
  in [architecture.md](architecture.md). Codified by ADR-037.
- ADRs are the durable record of *why*. Every non-trivial design
  call gets one, numbered in `doc/decisions.md`, referenced from
  the code with a one-line comment.
