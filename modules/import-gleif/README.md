# kontor-import-gleif

Ingest GLEIF Golden Copy LEI data (CC0) into the kontor `:entity`
substrate.

## What it does

The Global LEI Foundation (https://www.gleif.org) publishes a daily
"Golden Copy" of every issued Legal Entity Identifier (LEI) — the
ISO 17442 20-character master id every legal entity that participates
in financial markets gets. The file is ~600 MB / day. **Licensed
CC0 1.0 Universal** — the best-possible open license, attribution
not required, commercial use unrestricted. Perfect for a substrate
that wants to populate its `:entity` table from reality:

- **`valid-lei?`** — shape predicate (20-char uppercase
  alphanumeric). Full ISO/IEC 7064 MOD 97-10 check-digit
  validation is GLEIF-side per note 91 §6.5; the importer treats
  LEIs as opaque master-data IDs.
- **Two-phase ingest** because GLEIF parent relationships forward-
  reference:
    1. **`import-level-1!`** (entity master, LEI-CDF 3.1) — every
       row becomes / updates an `:entity` with `:entity/lei`,
       `:entity/legal-form`, `:entity/registration-status`,
       `:entity/source-id`, `:entity/code` (prefix + LEI, default
       `"GLEIF-"`). Idempotent against `:entity/lei` (unique-
       value); re-ingest with the same LEI updates in place.
    2. **`import-level-2!`** (relationships, RR-CDF 2.1) — for
       each ACTIVE relationship, looks up the child + parent by
       LEI in the DB. `IS_DIRECTLY_CONSOLIDATED_BY` →
       `:entity/parent-entity` (resolved ref) AND
       `:entity/parent-lei` (raw string). `IS_ULTIMATELY_
       CONSOLIDATED_BY` → `:entity/ultimate-parent-lei` (raw
       string only — no resolved ref because the chain might not
       be loaded). Forward-referenced parents (Level 2 row
       referencing a LEI not in the DB) skip the ref but still
       record the raw string. A second pass after more Level 1
       data lands will resolve them.
- **Validation report** (`level-1-validation-report`) — pre-import
  classifier returning `{:ok-count :total-count :issues [...]}`
  for diagnostics before transacting.
- **Pure builders** (`import-level-1-tx-data`, `import-level-2-
  tx-data`). Easy to compose with `kontor.process` or to inspect
  before transacting.
- **Lookup helper** (`by-lei`).

The `:entity/*-lei` raw-string slots are **provenance** — they
survive re-ingest + give debugging visibility. The resolved
`:entity/parent-entity` ref (ADR-031, kernel-side) is what
`kontor.entity/family` (ADR-073 consolidation) walks.

## When to use it

- Populating the kontor `:entity` table from real legal-entity
  master data (no synthetic test fixtures)
- Validating that a counterparty's claimed entity name + form
  match GLEIF's record
- Building a corporate-family tree for consolidation
  (`kontor.consolidation/consolidate!`)
- Cross-referencing entities across kontor + external systems by
  LEI

When NOT to use it:
- LEI check-digit validation (MOD 97-10) — small algorithm,
  consumer-side, dozens of public libraries
- Real-time entity-status monitoring — re-ingest the daily Golden
  Copy or use GLEIF's API
- Non-corporate parties (partners, employees, contacts) →
  `kontor-partner` + `kontor-hr`

## Load-bearing ADRs

- [ADR-005](../../doc/decisions.md) — no bundled credentials, no
  bundled rate data (here: no bundled GLEIF dataset; consumers
  ingest the daily file themselves)
- [ADR-031](../../doc/decisions.md) — `:entity` + `:entity/parent-
  entity` as the multi-entity substrate
- [ADR-073](../../doc/decisions.md) — `kontor.consolidation` —
  the family walk that uses `:entity/parent-entity` populated
  by this importer
- [ADR-090](../../doc/decisions.md) — substrate-seam concept-IRI
  pattern (this module is one of the six new `:entity/*` substrate
  attrs introduced for ingest)
- Research note 91 §6 — GLEIF research-before with full license
  analysis + format spec walkthrough
- Research note 94 §3.3 — substrate-attr expansion plan

## Key namespaces

- `kontor.import-gleif.core` — `valid-lei?`, `level-1-validation-
  report`, `import-level-1-tx-data`, `import-level-1!`,
  `import-level-2-tx-data`, `import-level-2!`, `by-lei`

Schema attrs (`:entity/lei`, `:entity/legal-form`, `:entity/
registration-status`, `:entity/parent-lei`, `:entity/ultimate-
parent-lei`, `:entity/source-id`) live in the kernel schema
(`src/kontor/schema.clj`) — there is no per-module schema or
`install!` because these attrs are kernel-side substrate seams
(ADR-090 family).

## Minimal example

```clojure
(require '[kontor.core              :as k]
         '[kontor.import-gleif.core :as gleif])

(def conn (k/create-test-db))

;; Step 0 — diagnose before importing
(gleif/level-1-validation-report
  (-> "path/to/gleif-level-1-sample.csv" slurp ...))
;; => {:total-count 1234 :ok-count 1232 :issues [{:row-index 17 :status :missing-lei …}]}

;; Step 1 — ingest entity master
(gleif/import-level-1!
  conn "path/to/lei-golden-copy-level-1.csv"
  {:source-id "gleif://Golden-Copy/2026-05-18"})

;; Step 2 — ingest parent / subsidiary relationships
(gleif/import-level-2!
  conn "path/to/lei-golden-copy-rr-cdf.csv")

;; Step 3 — resolve an LEI
(gleif/by-lei (datahike.api/db conn) "529900T8BM49AURSDO55")
;; => <eid>

;; Step 4 — walk the family for consolidation
(require '[kontor.entity :as entity])
(entity/family (datahike.api/db conn) <eid>)
```

## What it does NOT do

- **No bundled GLEIF dataset.** Consumers ingest the ~600 MB
  daily Golden Copy themselves via GLEIF endpoints (per ADR-005).
  Small sample fixtures live in `test/`.
- **No check-digit validation.** ISO/IEC 7064 MOD 97-10 semantic
  validation is GLEIF-side; this importer only sanity-checks the
  20-char alphanumeric shape so a stray header row or empty
  field doesn't pollute `:entity`.
- **No "other" relationship types.** RR-CDF carries
  `IS_FUND-MANAGED_BY` and other types beyond consolidation;
  this importer models the two consolidation types and skips
  the rest.
- **No validation gate.** `import-level-1!` / `import-level-2!`
  call `d/transact` directly, NOT `kontor.validation/transact-
  with-validation`. Mass-import use case — the kernel
  invariants (sum-to-zero, period-lock, sealing) don't apply to
  master-data writes.
- **No incremental ingest of an LEI delta.** The transactors are
  idempotent against `:entity/lei`, so a full re-ingest converges
  but is O(N) on the dataset size. Delta ingest is a documented
  follow-up.

## Tests

`modules/import-gleif/test/kontor/import_gleif/core_test.clj` —
single file covering LEI shape validation, Level 1 import +
idempotency, Level 2 forward-reference resolution + the partial-
resolution behaviour for entities not in the dataset.

## License

EPL-1.0. **The ingested GLEIF data itself is CC0 1.0 Universal**
— no attribution required, commercial use unrestricted. See
https://www.gleif.org/en/meta/lei-data-terms-of-use.
