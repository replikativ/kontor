# Architecture

A bird's-eye view of how `kontor` is laid out, why each layer exists, and how it composes with the surrounding stack (datahike, Mustang, beleg, simmis).

For *why* each choice was made, see [decisions.md](decisions.md). This document describes the *what*.

---

## The layer cake

```
┌──────────────────────────────────────────────────────────────────────┐
│  Consumer applications                                               │
│   beleg (HTMX billing)    simmis (Replicant SPA)    your app         │
│   own UI, own routing, own auth                                      │
└──────────────────────────────────────────────────────────────────────┘
                                ▲
                                │  (pulls in as deps)
                                │
┌──────────────────────────────────────────────────────────────────────┐
│  Per-country localization modules                                    │
│   kontor-l10n-de   (GPLv3, Tryton+GnuCash facts)       │
│   kontor-l10n-ca   (EPL-1.0, CRA-published facts)      │
│   kontor-l10n-us   (EPL-1.0 + SST CSVs)                │
└──────────────────────────────────────────────────────────────────────┘
                                ▲
                                │
┌──────────────────────────────────────────────────────────────────────┐
│  Optional adapter modules                                            │
│   kontor-einvoice-de  (Mustang APL2 wrapper)           │
│   kontor-einvoice-eu  (phax/ph-ubl APL2)               │
│   kontor-bank-camt053 (ISO 20022 CAMT.053 importer)    │
│   kontor-bank-nacha   (US ACH NACHA importer)          │
│   kontor-export-gobd  (DE tax-audit bundle)            │
│   kontor-export-datev (DE accountant-handoff)          │
└──────────────────────────────────────────────────────────────────────┘
                                ▲
                                │
┌──────────────────────────────────────────────────────────────────────┐
│  THIS REPO — kontor                                     │
│                                                                      │
│  ┌─ tax_provider ─┐  ┌── sealing ──┐  ┌── audit ──┐                 │
│  │  TaxProvider   │  │ posted-at   │  │ commit    │                 │
│  │  protocol      │  │ middleware  │  │ hash      │                 │
│  │  StaticTable   │  │             │  │ wrapper   │                 │
│  └────────────────┘  └─────────────┘  └───────────┘                 │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐    │
│  │  KERNEL — schema + posting + balance + period                │    │
│  │   account, journal, transaction, posting, commodity, lot,    │    │
│  │   balance-assertion, period, partner, fiscal-position,       │    │
│  │   tax, tax-repartition-line, account-tag, tax-group          │    │
│  │                                                              │    │
│  │   bitemporal: valid-from/to + :db/txInstant on every entity  │    │
│  │   posting validator: postings sum to zero                    │    │
│  │   query: trial balance, ledger view (datalog)                │    │
│  │   import: beancount round-trip                               │    │
│  └──────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
                                ▲
                                │  (uses, never embeds)
                                │
┌──────────────────────────────────────────────────────────────────────┐
│  datahike (replikativ)                                               │
│   schema, transactions, query, history, branches, commits            │
│                                                                      │
│   Track-B upstream PR adds:                                          │
│     • SHA-256 per-commit content hash                                │
│     • commit signature hook                                          │
│     • :crypto-hash? true documented for accounting use               │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Kernel: namespaces

```
src/kontor/
  schema.clj            ; the EDN schema for all kernel entities
  core.clj              ; create-db, transact-with-validation, public surface
  money.clj             ; Money type (BigDecimal + commodity), arithmetic, rounding
  posting.clj           ; build a transaction from postings; validates sum-to-zero
  balance.clj           ; account balance queries (as-of-tx, as-of-valid)
  ledger.clj            ; ledger view (postings against an account, ordered)
  trial.clj             ; trial balance over a date range
  period.clj            ; open/close periods, fiscal-year handling
  sealing.clj           ; :posting/posted-at + middleware refusing silent retract
  audit.clj             ; commit-hash wrapper, signature interface (Track B integration)
  tax_provider.clj      ; TaxProvider protocol + StaticTableProvider impl
  tax.clj               ; apply tax to a posting (delegates to provider)
  query.clj             ; convenience: bitemporal pairs, accounts-by-tag, etc.
  import/
    beancount.clj       ; Beancount parser + transactor + dumper (round-trip)
  schema/
    common.clj          ; shared bits (audit fields create_*/write_*, valid_*)
```

A typical Phase-1 module is ~100-300 lines. Total Phase-1 kernel target: **<2000 LOC**.

## Schema namespaces

To cohabit cleanly with beleg in one DB (ADR-002), we namespace every attribute under one of:

- `:account/*` — chart-of-accounts entries
- `:journal/*` — journals (sale/purchase/cash/bank/general)
- `:transaction/*` — journal entry headers (Odoo's `account.move`)
- `:posting/*` — individual debit/credit postings (Odoo's `account.move.line`)
- `:commodity/*` — currencies and other tradeable units
- `:lot/*` — specific acquisitions of a commodity (for cost-basis tracking)
- `:tax/*` — tax definitions
- `:tax-rep/*` — tax repartition lines (where tax postings land)
- `:tax-group/*` — tax groupings (per-country VAT clearing accounts)
- `:account-tag/*` — tags that link accounts/postings to report boxes
- `:partner/*` — customers/vendors (referenced from postings)
- `:fiscal-position/*` — per-region tax/account remapping
- `:period/*` — open/closed accounting periods
- `:balance-assertion/*` — pinned balance checkpoints

Beleg's existing namespaces (`:invoice/*`, `:customer/*`, `:offer/*`, `:line-item/*`, `:lead/*`, `:advisor/*`) coexist without collision.

The convention: a `:journal` reference from a beleg `:invoice/journal` is the integration point — beleg writes invoice headers, accounting writes the matching transaction + postings, all in one tx.

## The double-entry kernel

A **transaction** is the atomic accounting unit. It has 2+ **postings**. The kernel enforces:

```
(reduce + 0 (map :posting/balance postings)) == 0
;; balance is debit - credit, expressed in the transaction's currency
;; per-currency; multi-currency moves balance per currency
```

A **posting** carries:
- `:posting/account` (which account is debited or credited)
- `:posting/amount` (positive = debit, negative = credit, in transaction currency)
- `:posting/amount-currency` (when in foreign currency, the original amount)
- `:posting/commodity` (the unit of `amount`)
- `:posting/valid-from` (the bitemporal valid-time of this posting)
- `:posting/display-type` (`:product :tax :payment-term :rounding :section :note` — the Odoo discriminator)
- `:posting/partner` (optional FK)
- `:posting/posted-at` (set when the parent transaction is posted; sealing trigger)

A transaction carries the header (date, journal, partner, narration, totals — denormalized for query speed but always recomputable).

**Postings are immutable once posted** (ADR-007 sealing rule). Corrections create new transactions that *reverse* and *replace*, not in-place edits.

## Bitemporal queries

Every query takes two implicit dimensions (ADR-008):

- **as-of-tx** — what the database knew at this transaction time. Wraps `(d/as-of db tx-time)`.
- **as-of-valid** — what was true in the world as of this valid date. Filters `(<= :posting/valid-from valid-time)` and `(or (nil? :posting/valid-to) (> :posting/valid-to valid-time))`.

```clojure
;; Trial balance as of Dec 31 2024 as known on Mar 31 2025 (post-audit)
(trial-balance conn
               {:as-of-valid #inst "2024-12-31"
                :as-of-tx    #inst "2025-03-31"})
```

Helpers in `query.clj` make this ergonomic. The default is `now`/`now`.

## Tax provider protocol

```clojure
(defprotocol TaxProvider
  (resolve-taxes [this {:keys [partner posting context]}]
    "Given a context (transaction date, partner country, fiscal position, etc.)
     and a base posting, return a vector of additional postings that
     materialize the applicable taxes — or [] if no taxes apply.
     The returned postings include :posting/account, :posting/amount,
     :posting/tax-repartition (the rep line that produced them), and
     :posting/account-tags (for report-time aggregation).")
  (provider-id [this]
    "A keyword identifying this provider; useful for audit / debug."))
```

Three Phase-1 implementations:

1. **`StaticTableProvider`** — reads a per-country EDN definition (covers DE, CA, and any country whose tax surface fits a static table).
2. **`SstCsvProvider`** (Phase 5-US) — reads quarterly Streamlined Sales Tax CSVs.
3. **`AvalaraProvider` / `TaxJarProvider`** (Phase 5-US, scaffolded) — wraps the customer's API key.

The provider is per-DB / per-tenant configuration. The kernel asks; the provider answers.

## Sealing

```clojure
;; Phase 1 — application middleware
(defn transact-with-sealing [conn tx-data]
  (let [forbidden (find-silent-retracts-of-posted tx-data (d/db conn))]
    (when (seq forbidden)
      (throw (ex-info "Refused: silent retract of posted entries"
                     {:posted-eids (mapv first forbidden)
                      :remediation "Use [:db/purge ...] explicitly with :purge/reason"})))
    (d/transact conn tx-data)))
```

`:db/purge` of posted entries is allowed but recorded — datahike commits the purge as its own transaction, so the audit chain documents it. We require a `:purge/reason` annotation on the same tx.

## Audit hash chain (Track B integration)

```
[ Phase 1 today ]                    [ After Track B ]
─────────────────                    ─────────────────
:hash = clojure.core/hash            :hash = SHA-256(canonical-EAVT-of-tx)
        summed (32-bit)                      stored in :meta
                                             feeds create-commit-id
commit-id =                          commit-id =
  UUID-5(SHA-512([hash max-tx          UUID-5(SHA-512([sha256-hash
         max-eid meta]))                       max-tx max-eid meta]))
                                            ↓
no commit signatures                 commit signature hook:
                                       (sign-commit-fn commit-hash)
                                     stored alongside commit
                                     verifiable externally
```

Phase 1 ships using today's datahike with `:crypto-hash? true` (which makes index nodes Merkle-addressed via SHA-512) and a documented gap on the leaf hash. When Track B lands, `audit.clj` cuts over to consume the new mechanism.

## Beancount round-trip

Beancount is the most-respected open-source double-entry implementation. We ship a parser and dumper to/from datahike representation. ADR-009 makes round-tripping a representative `.beancount` file a Phase-1 acceptance criterion: parse, transact, dump, byte-diff. This pins our semantics against a known-correct reference.

## Composition with beleg

```
beleg.invoice ─────────┬─────► kontor.transaction
   (issued)            │           (posted)
                       │
   :invoice/journal ───┴─► :journal entity in same DB
   :invoice/lines     ───►  :posting entities (one per line + tax + receivable)
```

When beleg "issues" an invoice (status → :issued), one tx writes:

1. `:invoice/status` change on the beleg side.
2. A new `:transaction` entity on the accounting side (journal=sales, date=invoice date, partner=customer).
3. N `:posting` entities: one per line item to revenue accounts, one per tax line to VAT clearing, one to the receivable account for the total.

All atomic. Sum-to-zero validated. Bitemporally indexed.

The reverse — voiding an issued invoice — emits a reversing transaction (not an in-place edit) per ADR-007.

## What lives upstream vs here

| Concern | Where |
|---|---|
| Schema, postings, balance, period, taxes, sealing, audit | here |
| Branches, commits, history, content addressing, hashing | datahike core |
| SHA-256 leaf hash, commit signature hook | datahike core (Track B PR) |
| Beancount parser | here, in `import/beancount.clj` |
| SKR03/SKR04 facts | `kontor-l10n-de` |
| Factur-X/XRechnung XML | Mustang (we wrap) |
| UBL/Peppol | phax/ph-ubl (we wrap) |
| KoSIT validation | KoSIT (Mustang invokes) |
| CAMT.053 parser | phax/ph-camt or our own importer |
| US sales tax data | SST CSVs / customer API key |
| UI | not here |
