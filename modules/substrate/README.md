# kontor-substrate

The dependency-light **value layer** of the kontor accounting kernel — the pieces
a lightweight consumer can use without pulling in datahike, the transact gate,
or any country/tax/payroll module.

## Why this module exists

`kontor.money` is the canonical money representation for the whole kernel
(ADR-013): a `Money` record carrying a `BigDecimal` amount + a commodity tag,
with commodity-checked arithmetic and HALF_EVEN rounding. It has **no datahike
dependency** — the only datahike-shaped helpers (`posting->money`,
`money->posting-fragment`) work by pure map-shape matching on `{:db/id N}`, so
they impose no runtime coupling.

That makes `Money` reusable *outside* an accounting DB. The first external
consumer is the **dvergr** agent harness (`org.replikativ/dvergr`), which meters
LLM/compute spend and wants to record it as commodity-tagged money — a
kontor-conformant **single-entry subledger** — without depending on the full
kernel. The double-entry, sealed, bitemporal ledger stays in the kernel; the
harness only needs the value type. See kontor research note 190
(`.internal/research/190-simmis-kontor-integration-and-meta-accounting.md`).

## What's here

- `kontor.money` — `Money` + arithmetic (`+`/`-`/`*`/negate/sum), rounding,
  construction from string/bigdec, and the datahike-boundary helpers.

## How it's wired

- The kontor monorepo root `deps.edn` merges `modules/substrate/src` onto the
  shared classpath, so the kernel and every other module keep referring to
  `kontor.money` with no code change.
- External consumers depend on it directly. During development, via a local
  root:

  ```clojure
  ;; consumer deps.edn
  {:deps {org.replikativ/kontor-substrate
          {:local/root "../kontor/modules/substrate"}}}
  ```

  When it hardens, it publishes as `org.replikativ/kontor-substrate` with no
  code changes — only the coordinate moves (the ADR-006 monorepo split story).

## License

Apache-2.0, same as kontor.
