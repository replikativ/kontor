# kontor-import-edgar

Ingest SEC EDGAR `companyfacts` JSON into the kontor
`:reported-fact/*` substrate, with bitemporal restatement
supersession.

## What it does

Showcase 05 (the Apple 10-K bitemporal restatement) is the canonical
demo of kontor's headline feature: same fact, two `:filed` dates,
`(d/valid-at db t)` returns the right one for any timeline point. To
make that demo real-data-driven rather than synthetic, the substrate
needs a way to ingest SEC EDGAR's actual filings. This module is
that:

- **`parse-companyfacts`** — pure JSON → flat seq of fact-row maps.
  Each row carries `:cik`, `:entity-name`, `:taxonomy` (`:us-gaap`,
  `:dei`, `:srt`, `:ifrs-full`, `:invest`), `:concept`,
  `:concept-iri` (e.g. `"us-gaap:AccruedLiabilitiesCurrent"`),
  `:unit` (`:usd`, `:shares`, …), `:end`, `:start` (for duration
  concepts), `:val`, `:accession`, `:form` (`"10-K"`, `"10-Q"`,
  `"10-K/A"`), `:filed`, `:fy` / `:fp`.
- **`:reported-fact/*` substrate** (separate from `:posting/*`).
  A `:reported-fact` is a regulator-filed external value about an
  `:entity` — distinct from `:posting`, which is an internal,
  balanced, journal-gated accounting datum. No balance constraint;
  supersession = restatement; bitemporal valid-time = SEC
  `:filed` date.
- **Per-(entity, concept, period-end, unit) own-transaction
  ingest.** Each fact lives in ITS OWN datahike transaction.
  `:db.valid/from` = SEC `:filed` date — the bitemporal valid-time
  axis aligns with the SEC reporting axis.
- **Restatement supersession.** When a later filing (a 10-K/A
  amendment, a subsequent 10-K's comparative period) re-reports
  the same `(entity, concept, period-end, unit)` quadruple:
    1. Close-validity on the prior fact's transaction at the new
       `:filed` date (`kontor.bitemporal/close-validity-tx-data`).
    2. Record a new `:reported-fact` row with `:tx/valid-from` =
       new `:filed`, set the prior fact's
       `:reported-fact/superseded-by` ref to the new fact.
  Result: `(d/valid-at db t)` returns the fact authoritative AS
  OF reporting time `t` (original 10-K before the amendment, the
  amendment after). The supersession chain is also navigable
  structurally via the `:superseded-by` ref — useful for
  "show me the history of this restatement" walks.
- **Idempotent re-ingest.** `:reported-fact/external-id` is the
  composite identity
  `"<source>:<accession>:<concept-iri>:<end>:<unit>"` with
  `:db.unique/identity`; re-ingesting the same filing is a no-op.
- **Convenience queries** — `current-fact` (the authoritative
  fact at valid-time `at`, defaults to now) and `fact-history`
  (the full supersession chain, oldest first).

## When to use it

- Populating the kontor `:reported-fact` substrate from real SEC
  filings (driving showcase 05 + similar reproductions)
- Cross-concept queries via the kernel's `kontor.explain/
  entities-with-concept-iri` walker (which uses
  `:reported-fact/concept-iri` per ADR-090)
- "What did Apple's accrued liabilities look like on date T, as
  filed by date F?" bitemporal queries

When NOT to use it:
- Internal accounting postings → `kontor.posting` (this is for
  externally-filed facts, not for internal bookkeeping)
- XBRL XML / iXBRL HTML parsing — the JSON API path sidesteps
  the parser problem entirely (note 78 §1); a future
  `kontor-xbrl` companion would add XBRL XML for FDTA municipal
  filings, Companies House (UK), ESEF (EU), E-Bilanz (DE)
- Non-SEC regulator filings — every regulator has its own
  ingest; this module is SEC-EDGAR-specific
- Real-time fact streaming — re-fetch the daily companyfacts
  JSON or use SEC's RSS feed at the consumer

## Load-bearing ADRs

- [ADR-001](../../doc/decisions.md) — clean-room reimplementation
  (no XBRL XML lift; JSON API is XBRL-derived)
- [ADR-005](../../doc/decisions.md) — no bundled credentials, no
  bundled rate-data (SEC mandates a User-Agent header; ingest at
  consumer)
- [ADR-008](../../doc/decisions.md) — bitemporal substrate that
  the supersession model rides on (`:tx/valid-from` = `:filed`)
- [ADR-013](../../doc/decisions.md) — BigDecimal money
  discipline (`:reported-fact/value-bigdec`)
- [ADR-031](../../doc/decisions.md) — `:entity` as the reporting
  entity for `:reported-fact/entity`
- [ADR-090](../../doc/decisions.md) — substrate-seam concept-IRI
  pattern; `:reported-fact/concept-iri` is indexed so
  `kontor.explain/entities-with-concept-iri` walks it
- Research note 91 §2 — EDGAR research-before (datasets,
  license, format)
- Research note 78 — XBRL primer + JVM-XBRL-libraries gap
  rationale (why we use the JSON API)
- Research note 94 §3.4 — `:reported-fact/*` substrate plan

## Key namespaces

- `kontor.import-edgar.schema` — `:reported-fact/*` attrs (the
  module DOES ship its own schema, distinct from import-gleif's
  schema-in-kernel pattern) + `install!`
- `kontor.import-edgar.core` — `parse-companyfacts`,
  `ingest-facts!`, `current-fact`, `fact-history`

## Minimal example

```clojure
(require '[clojure.java.io          :as io]
         '[kontor.core              :as k]
         '[kontor.import-edgar.core   :as edgar]
         '[kontor.import-edgar.schema :as edgar-schema])

(def conn (k/create-test-db))
(edgar-schema/install! conn)

;; Seed the entity. (import-gleif could do this from real GLEIF data;
;; here we hand-seed for the example.)
(require '[datahike.api :as d])
(d/transact conn
  [{:db/id "apple"
    :entity/code "AAPL"
    :entity/name "Apple Inc."
    :entity/active true
    :entity/lei "HWUPKR0MPOU8FGXBT394"}])
(def apple-eid
  (d/q '[:find ?e . :where [?e :entity/code "AAPL"]] (d/db conn)))

;; Step 1 — parse the SEC JSON (consumer fetched it with their own
;; SEC_EDGAR_USER_AGENT)
(def parsed
  (edgar/parse-companyfacts
    (slurp (io/resource "fixtures/apple-companyfacts-sample.json"))))

;; Step 2 — ingest. Each fact gets its own tx with
;; :tx/valid-from = :filed; restatements close-validity on the prior
;; tx + set :reported-fact/superseded-by on the new row.
(edgar/ingest-facts!
  conn parsed
  {:entity-eid apple-eid
   :source "edgar"})
;; => {:ingested 1234 :superseded 8 :skipped 0 :tx-reports [...]}

;; Step 3 — bitemporal queries
(edgar/current-fact conn apple-eid
                    "us-gaap:AccruedLiabilitiesCurrent"
                    #inst "2009-09-26"
                    :usd
                    #inst "2009-12-01")
;; => the value AS FILED in the original 10-K

(edgar/current-fact conn apple-eid
                    "us-gaap:AccruedLiabilitiesCurrent"
                    #inst "2009-09-26"
                    :usd
                    #inst "2010-02-01")
;; => the value AS RESTATED by the 10-K/A amendment

(edgar/fact-history conn apple-eid
                    "us-gaap:AccruedLiabilitiesCurrent"
                    #inst "2009-09-26"
                    :usd)
;; => [original, amendment, ...] — the full supersession chain
```

## What it does NOT do

- **No XBRL XML / iXBRL HTML parser.** The JSON API path is
  XBRL-derived and sidesteps the parser problem entirely (note
  78 §1). A future `kontor-xbrl` companion is the home for FDTA
  / Companies House / ESEF / E-Bilanz XML/iXBRL.
- **No bundled SEC dataset.** Consumers fetch the
  `companyfacts/CIK<10>.json` from `data.sec.gov` themselves; SEC
  mandates a `User-Agent` header — pass via opts or env var
  `SEC_EDGAR_USER_AGENT`.
- **No automated `:posting` bridge.** A `:reported-fact` is NOT
  a posting. A consumer that wants to bridge reported facts to
  internal postings does so explicitly (e.g.
  `kontor.l10n-us-gaap.bridge`); the substrate keeps them
  separate.
- **No CIK → :entity auto-creation.** The consumer pre-seeds the
  `:entity` row (typically via `kontor-import-gleif` from GLEIF's
  LEI master) and passes `:entity-eid` to `ingest-facts!`.
- **No validation gate.** `ingest-facts!` calls `d/transact`
  directly (one tx per fact), not `kontor.validation/transact-
  with-validation`. Mass-import use case for master facts;
  kernel invariants (sum-to-zero, period-lock) don't apply.
- **No streaming / RSS subscription.** Re-run `ingest-facts!`
  after re-fetching the JSON.

## Tests

`modules/import-edgar/test/kontor/import_edgar/core_test.clj` —
single file covering `parse-companyfacts` shape, single ingest,
re-ingest idempotency, restatement supersession (the bitemporal
valid-at semantic at both "before amendment" and "after amendment"
timeline points), and the `fact-history` chain walk.

## License

EPL-1.0. **SEC EDGAR data is public domain** — no license
restrictions on the data itself; SEC mandates a `User-Agent`
header on requests. See
https://www.sec.gov/edgar/sec-api-documentation.
