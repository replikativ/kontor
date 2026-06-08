# Changelog

Notable changes are recorded here. See `doc/decisions.md` for the
distilled architecture decisions; per-decision ADR numbers are stable
references.

The kontor kernel follows pre-1.0 semantics: minor version bumps may
include schema additions and/or new validation gates. Removals or
incompatible changes are called out explicitly.

## [Unreleased]

### Cleanup
- Internal research notes (189 files) and development-history docs
  moved out of the public repo into a local-only `.internal/`
  directory; the substrate ADRs in `doc/decisions.md` remain the
  public-facing rationale.
- README + CONTRIBUTING + per-module wording refreshed for accurate
  licensing posture (all currently-shipped modules are Apache 2.0; charts
  of accounts independently keyed from public government sources;
  ADR-006 reserves the option for a future copyleft-data module — none
  currently triggers it).
- Bulk-trim of development-history note references across `src/` and
  `modules/`; substrate ADR references (ADR-068 `*-tx-data` discipline,
  ADR-101 statute-as-data, ADR-071 `TaxRateProvider`, ADR-099
  `PeriodTaxProvider`, …) kept as load-bearing pointers.
- Five deprecated `period_tax_provider` forwarder tests removed from
  `l10n-{at,au,cn,mx,us}` — the v0.x compat path is no longer
  exercised; the v1 substrate is covered by `cit_provider_test` /
  `pit_provider_test` in each module.

## [v0.1.0-alpha] — planned

### Substrate

- **Bitemporal** — `:tx/valid-from` + `:as-of-valid` keyword on every
  read; `kontor.bitemporal/close-validity!` + `commit-tx-eid` for
  backdated corrections. (ADR-008, ADR-048)
- **Schema namespacing** — every kernel attribute prefixed `:kontor.*`
  so kontor and consumer apps cohabit in one datahike connection.
  (ADR-002)
- **Sealing** — `:posting/posted-at` is the seal trigger; silent
  retraction of posted entries forbidden; explicit `:db/purge` is
  itself a recorded commit. (ADR-007)
- **Status machines** — `:status-transition` + `:status-history` +
  `:approval-policy` as data; every workflow companion writes through
  the same primitive. (ADR-034, ADR-038)
- **Compliance** — `:audit-doc` + `:legal-hold` + `:retention-policy`
  + `:dsar-request` + `:audit-doc/privilege`, all queryable schema
  entities. (ADR-038, ADR-049, ADR-050, ADR-051, ADR-052)
- **Validation gate** — `kontor.gate/transact-with-validation` runs
  legal-hold → sealing → period-lock → state-machine → sum-to-zero →
  invariants in order; vendored `datopia/invariant`. (ADR-011)
- **Write substrate** — `kontor.process` orchestrator + universal
  `*-tx-data` + `!` builder/wrapper split + `kontor.book` verb facade.
  (ADR-067, ADR-068, ADR-095)
- **Reports as marginalizations** — `kontor.report/marginalize` is the
  primitive; trial balance / P&L / VAT-return / cost-centre report are
  all instances over different axes. `:posting-dimension` extends the
  axis set without schema churn. (ADR-096, ADR-097)
- **McComb-aligned substrate seams** — `:concept-iri` across substrate
  entities for XBRL / FIBO / gist hooks; `kontor.explain` graph walks;
  in-process `kontor.workflow.event-bus`. (ADR-090, ADR-091, ADR-092)

### Tax substrate

- **`TaxRateProvider` + `TaxPostingBuilder`** — transactional tax
  (VAT / GST / sales tax). `StaticTableProvider` default + Avalara /
  TaxJar / SST scaffolds (no credentials bundled). (ADR-071)
- **`FxRateProvider`** — IAS 21 / ASC 830 vocabulary; `StaticTable` +
  `ECB` + `Chained` defaults; `kontor.fx` Money-level operations.
  (ADR-072)
- **`PeriodTaxProvider`** — period/entity-incident taxes (CIT / PIT /
  CGT / property / wealth / standalone employer-payroll). Closed
  8-value `period-tax-kinds` enum. (ADR-099)
- **Sole-prop + VAT-return rungs** — `kontor.tax.sole-proprietor` +
  `kontor.tax.vat-return`; the individual-to-corporation tax-completion
  continuum starts here. (ADR-100)
- **Statute as data** — `:tax-concept` / `:provision` / `:regime` /
  `:parameter` + a single `kontor.tax.statute/apply-provisions`
  evaluator. Per-country CIT / CGT for all 11 jurisdictions ships as
  `:provision` data. `:op :schedule-override` + `compose-greater-of`
  document the MAT / AMT pattern in code. (ADR-101)
- **Disposal + CGT substrate** — `:disposal/*` companion + kernel-side
  `DisposalProvider` protocol; **12 CGT providers** ship (US, DE, UK,
  JP, CA, FR, AU, BR, IN, MX, CN, AT). (ADR-102, ADR-103)
- **Consolidation** — `translate-trial-balance-tx-data` + intercompany
  elimination + `consolidate!` over `kontor.entity/family`. (ADR-073)
- **Fiscal-unit substrate** — `:fiscal-unit` + 3-style dispatch on
  `run-group-tax!` (`:single-base` / `:per-member-with-netting` /
  `:loss-surrender`); `compose-aggregate-of` records the economic
  delta vs separate filing. DE Organschaft pilot lands the BMF
  Müller-Gruppe worked example to the cent. (ADR-113)

### Jurisdictions

- **11 CIT jurisdictions on the ADR-101 statute-as-data path** — DE,
  FR, CA + QC, JP, BR, IN, US, AT, AU, CN, MX. Per-country
  authority-published worked examples match to the cent.
- **12 CGT providers** — same set + UK (CGT is decoupled from UK's
  iXBRL CIT gate).
- **11 payroll adapters** — DE LODAS, US ADP GLI, CA + QC, FR DSN, AU
  STP P2, BR eSocial, MX CFDI Nómina, IN TDS/PF/ESI/PT, JP Gensen, CN
  IIT + 五险一金, AT mBGM + L16. (ADR-075..087)
- **Per-country e-invoice** — DE Factur-X / XRechnung via Mustang.
  (ADR-017)
- **Per-country bank-statement importers** — bank-de (11 banks /
  14 file variants), bank-at, bank-ca, bank-fr, bank-us. Bank-CSV
  fixtures are synthesized or lifted from MIT-licensed third-party
  Beancount importers (per-file provenance in each module's
  `SOURCES.md`).

### Companions

`asset` (depreciation books), `lease` (IFRS 16 / ASC 842 with ASC 842
operating fork on `:index-reset`), `inventory` (FIFO/LIFO/WAC),
`hr` (personnel substrate), `expense` (per-diem + reimbursement),
`procurement` (drop-ship / substitute / replacement / upgrade),
`sales` (orders), `invoice` (order→invoice bridge), `collections`
(AR aging + dunning), `partner` (party root + person/org),
`disposal` (CGT substrate), `incorporation`,
`import-gleif` + `import-edgar`, `people-record`, `einvoice-de`.

### Gaps deliberately deferred

- **UK CIT + payroll** — gated on iXBRL substrate work.
- **Fiscal-unit beyond DE** — substrate ships; FR intégration / US
  §1502 / JP group-tsuusan / others land as consumer demand surfaces.
- **Pillar Two (OECD GloBE)** — explicitly out of scope until a
  customer asks.
- **Standalone `kontor-mcp` server** — composes with `dvergr`'s MCP
  today; standalone gated on a consumer ask.
- **`:local/root` companion deps + datahike branch pin** — v0.1.0-alpha
  publishing blockers; once datahike's bitemporal-v1 + DH-11 branch
  lands as a tagged release, the pins go away.

### Testing

- ~3,000 tests across `test/` and `modules/*/test/`. Per-jurisdiction
  CIT / CGT / payroll providers carry worked-example tests citing the
  authority bulletin / XSD they were sized against.
- `test/kontor/integration/cross_border_scenario_test.clj` is the
  README + quickstart's regression test; cross-border dividend
  (DE UG → CA personal) via `kontor.treaty.de-ca` exercises the
  cross-DB saga primitive (ADR-074).
- Six end-to-end Clay notebooks under `doc/showcases/`.

[Unreleased]: https://github.com/replikativ/kontor/compare/v0.1.0-alpha...HEAD
[v0.1.0-alpha]: https://github.com/replikativ/kontor/releases/tag/v0.1.0-alpha
