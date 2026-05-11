# Openclaw Investigation Report

## TL;DR

Three real artifact bundles, one agent-runtime shell.

1. **`beleg/`** — Clojure + Datahike. Working bank-CSV importer for 11 German bank
   formats, full DATEV EXTF exporter, UStVA/EÜR calculators. **The crown jewels.**
2. **`RechnungsFee/`** — Spec/docs project + curated, anonymized bank-CSV fixtures
   for 11 German banks. **Pure data goldmine, no executable code worth porting.**
3. **`kluge-krabbe-buchhaltung/`** — Earlier Python sketch of the same idea. Code
   is superseded by `beleg/` (which is the Clojure rewrite). **Skip the code,
   keep one or two notes.**
4. **`duty_analyzer/`** — One-off airline-crew per-diem parser. Domain-specific.
   **Leave it.**

The rest is agent-runtime infrastructure (SOUL.md, IDENTITY.md, AGENTS.md,
HEARTBEAT.md, MEMORY.md, daily memory files, transcripts, QR PNGs, bedtime-
job.json). **All stays in openclaw.**

---

## 1. Inventory by Category

### Reusable artifacts (data — pull as resources)

- `RechnungsFee/vorlagen/bank-csv/*.csv` — 16 anonymized real-world CSV
  fixtures: dkb, ing, ing-mit-saldo, commerzbank, postbank, paypal, sparkasse-
  lzo (camt-v2, camt-v8, mt940), targobank (+ variation, .qif, .xlsx),
  vr-teilhaberbank (+ .mta), sparda-bank-west, gls-bank.
- `RechnungsFee/vorlagen/bank-csv/README.md` — **Excellent** format spec doc:
  delimiter, encoding, decimal separator, date format, header row offset,
  column-by-column. Documentation we'd otherwise have to write.
- `beleg/test-data/bank-csv/*.csv` — duplicates of above + 3 synthetic
  (synthetic-dkb/ing/postbank).
- `beleg/test-data/ground-truth/gt_*.json` — 18 ground-truth labels for the
  synthetic-receipt OCR test set.
- `beleg/test-data/transactions-2025.json` (140 KB) — fixtures for tests.
- `RechnungsFee/vorlagen/datev/datev-export.csv` — example DATEV ASCII output
  (validation oracle).
- `RechnungsFee/vorlagen/kassenbuch/kassenbuchfelder.csv` — Kassenbuch field
  spec.
- `RechnungsFee/vorlagen/steuern/anlage-eks.html` — German tax form snapshot.

### Source code (port-worthy)

- `beleg/src/org/replikativ/beleg/bank.clj` (609 LOC) — **highest-value file
  in the bundle.** `bank-configs` map covers DKB, ING, Commerzbank, Postbank,
  PayPal, Sparkasse-CAMT, Sparkasse-MT940, Targobank, GLS, Sparda-West,
  VR-Bank — encoding, skip-rows, date-format, separator, column index map.
  Plus `parse-german-amount` (handles `1.234,56`, `(123,45)`, `€` stripping,
  BOM), filename-based `detect-bank`, and an auto-categorizer with regex
  patterns for 13 expense classes. Currently writes Datahike entities under
  `:transaction/*` directly — ports cleanly to a stateless parser yielding
  posting candidates.
- `beleg/src/org/replikativ/beleg/tax.clj` (660 LOC) — `calculate-ustva`
  (German VAT return), `calculate-euer` (P&L for freelancers),
  `estimate-tax-reserve` (income-tax bracket calc 2026), full DATEV EXTF
  exporter (110-column header constant + per-row writer), SKR04 category
  → konto/gegenkonto/BU-schluessel mapping.
- `beleg/src/org/replikativ/beleg/schema.clj` (993 LOC) — see overlap
  section below.
- `beleg/src/org/replikativ/beleg/intake.clj`, `journal.clj`, `vision.clj`,
  `services.clj`, `core.clj`, `server.clj`, `render.clj`, `config.clj` —
  application code (HTTP server, invoice rendering, OCR vision pipeline).
  Out of scope for the kernel; if `beleg` continues to exist as a layer
  on top of kontor, this stays in beleg.
- `kluge-krabbe-buchhaltung/src/*.py` (Python, ~3000 LOC) — earlier
  prototype. The Clojure `beleg` IS the rewrite of this. Skip.
- `duty_analyzer/duty_parser.py` — airline-crew per-diem (Verpflegungs-
  pauschale). Out of accounting scope.

### Documentation / notes

- `RechnungsFee/docs/01-projektvision.md` through `12-hilfe-system.md` —
  numbered chapters of a German-language product spec. `03-bank-integration.md`,
  `04-ustva.md`, `05-euer.md`, `08-import.md`, `10-steuersaetze.md` are
  directly relevant to our DE l10n work. `10-steuersaetze.md` enumerates
  all DE VAT rules (19/7/0%, Kleinunternehmer §19 UStG, Reverse-Charge §13b
  UStG, intra-EU §4 Nr 1b, exports §4 Nr 1a, §4 Nr 12, agriculture §24,
  Corona 16/5%) — **this is the requirements list for `kontor-
  l10n-de`'s tax tables.**
- `beleg/docs/accountable-integration.md` (9.5 KB) — research on
  `accountable.eu` integration patterns.
- `beleg/docs/eu-27-accounting-standardization.md` (14 KB) — EU-wide
  standardization survey. Useful background for the multi-country roadmap.
- `beleg/docs/integration-plan-detailed.md` (28 KB) — concrete integration
  plan; some of this is the *predecessor* to current ADRs.
- `kluge-krabbe-buchhaltung/docs/accountable-de-{research,vision-analysis}.md`
  — overlapping with beleg/docs/. Keep the beleg version, drop these.
- `RechnungsFee/discussion-bank-csv.md` — community-call doc explaining
  the bank-CSV contribution flow.

### Agent runtime (NOT to extract)

- Top-level: `SOUL.md`, `IDENTITY.md`, `AGENTS.md`, `BOOTSTRAP.md`,
  `HEARTBEAT.md`, `USER.md`, `MEMORY.md`, `TOOLS.md`, `bedtime-job.json`
- `memory/2026-02-{12,13,14}.md` — agent's daily journal entries
- `RechnungsFee/claude.md` (574 KB), `RechnungsFee/fragen.md` (144 KB) —
  agent conversation dumps

### Other / ignored

- 25 transcript files (`file_*.{srt,vtt,tsv,txt,json}`, `test.*`) — all
  near-empty placeholders, no content.
- `grundsteuer_qr.png`, `klinikum_qr.png` — random QR codes, drop.
- `beleg/screenshot.png` (121 KB), `beleg/logs/crm.*.log` (8.6 MB total)
  — runtime artifacts.

---

## 2. Bank-CSV Intake — what they tried

The team built a serious German-bank parser. Coverage:

| Bank        | Format(s)                | Encoding         | Skip rows | Quirks                                    |
| ----------- | ------------------------ | ---------------- | --------- | ----------------------------------------- |
| DKB         | CSV                      | UTF-8 BOM        | 4         | Eingang/Ausgang signs amount; gender-`*in`|
| ING         | CSV (2 variants)         | ISO-8859-1       | 12 / 13   | Saldo line variant                        |
| Commerzbank | CSV                      | UTF-8 BOM        | 1         | Long descriptions, mostly-empty Kategorie |
| Postbank    | CSV                      | UTF-8 BOM        | 8         | Soll/Haben split (no signed amount)       |
| PayPal      | CSV (international)      | UTF-8            | 1         | Comma sep, 36 cols, .  decimal            |
| Sparkasse   | CAMT v2/v8 + MT940       | UTF-8            | 1         | 17 vs 11 columns                          |
| Targobank   | CSV (two variants), QIF, XLSX | UTF-8       | 0         | NO header row; embedded data in col 1     |
| GLS         | CSV (Genobank style)     | UTF-8 BOM        | 1         | 18 cols, full SEPA fields                 |
| Sparda-West | CSV (= GLS shape)        | UTF-8 BOM        | 1         | Identical to GLS                          |
| VR-Teilhaber| CSV + MT940 (.mta)       | UTF-8 BOM        | 1         | Identical to GLS for CSV                  |

Schema target (in `beleg`): a flat `:transaction/*` row with `bank-account`
ref, date, value-date, amount, counterparty-name, counterparty-iban,
description, type, plus an auto-derived `:transaction/category` (from regex
patterns) and `:transaction/vat-rate`/`vat-amount`/`net-amount`.

Known issues observed:
- Auto-categorization is regex-only (no learning loop).
- Targobank parser duplicates col-index 1 across counterparty/description/
  type — comment says "embedded data" but this looks like a workaround.
- DKB sign-flip logic special-cases on `bank` keyword equality.
- Date timezone is hard-coded UTC (TODO: Europe/Berlin for value-date).

---

## 3. Accounting schema overlap with the new kernel

The new kernel (`src/kontor/schema.clj`, 16 namespaces:
account, journal, transaction, posting, commodity, lot, tax, tax-rep,
tax-group, account-tag, partner, fiscal-position, period, balance-assertion,
audit, lot) is a strict, PTA + Odoo-style **double-entry** schema with
bitemporal posting/transaction split.

`beleg/src/.../schema.clj` is a **flat, application-domain** schema:
lead, customer, company, advisor, task, task-group, offer, invoice,
line-item, bank-account, transaction (single-row, signed amount, no
explicit posting), expense, tax-period, document, journal-entry,
journal-line (debit/credit), period, cost-center, profit-center, account.

| beleg attribute / concept                  | Kernel equivalent                         | Action                            |
| ------------------------------------------ | ----------------------------------------- | --------------------------------- |
| `:transaction/*` (single signed row)       | `:transaction/*` + 2x `:posting/*`        | Split on import (kernel rule)     |
| `:journal-entry/*` + `:journal-line/*`     | `:transaction/*` + `:posting/*`           | Equivalent — kernel wins          |
| `:bank-account/*`                          | `:account/*` with `:account/type :asset`  | Fold into account hierarchy       |
| `:tax-period/*` (UStVA-specific)           | Computed view, not a schema entity        | Drop attribute, compute on demand |
| `:expense/*` (Beleg)                       | Lives in beleg (consumer layer), not kernel | Keep where it is                |
| `:cost-center/*`, `:profit-center/*`       | (not in kernel yet)                       | Possible Phase 5 extension        |
| `:invoice/*`, `:offer/*`, `:customer/*`    | Beleg consumer concerns                   | Stay in beleg                     |
| `:transaction/vat-breakdown-json`          | Kernel uses `:posting/tax-rep` per line   | Replace                           |
| `:transaction/country` + `:transaction/province` | `:partner/country-code`, fiscal-position | Replace                       |
| `:invariant/*` (datopia/invariant placeholder) | Kernel `sealing.clj` middleware       | Skip                              |

**Things the new kernel arguably needs but isn't there yet** — surfaced
by reading beleg:

- `cost-center` / `profit-center` (Odoo's "analytic accounts"). Roadmap
  may already cover this; if not, file under Phase 5.
- DATEV-specific account `:bu-schluessel` mapping. This is l10n-DE concern.

---

## 4. Tax legislation work

Single jurisdiction: **Germany**. Encoded as **code + constants**, not as
data. Three places:

- `beleg/src/.../tax.clj` — `vat-rates` map, `skr04-mapping` (category →
  konto/gegenkonto/BU-schluessel), `datev-header-columns` (the 110-col
  EXTF header), `get-bu-schluessel` (VAT rate → DATEV key), 2026
  `grundfreibetrag` constant, simplified progressive income-tax brackets.
- `kluge-krabbe-buchhaltung/src/tax_engine.py` — same German tax constants
  in Python.
- `RechnungsFee/docs/10-steuersaetze.md` — **specification**, not code.
  Lists every DE VAT case with §UStG citations, including historical
  Corona rates (16/5% for 2020-07-01 to 2020-12-31), reverse-charge
  triggers, intra-EU rules, agriculture (§24).

For `kontor-l10n-de`: the doc is the requirements list, the
`tax.clj` SKR04 mapping is the seed data, and the kernel's `:tax/*` +
`:tax-rep/*` shape is the right target schema. Translation is
reasonably mechanical.

---

## 5. Extraction recommendation

### Pull NOW (Phase 1 — kernel)

Nothing from openclaw needs to land in the kernel proper. The kernel
schema is more advanced. **Skip kernel-direct ports.**

### Pull for Phase 2 — `kontor-l10n-de`

| From                                                  | To                                                              |
| ----------------------------------------------------- | --------------------------------------------------------------- |
| `beleg/src/org/replikativ/beleg/tax.clj` (`skr04-mapping`, `vat-rates`, `datev-header-columns`, `format-datev-*`, `export-datev`) | `kontor-l10n-de/src/.../datev.clj` and `.../taxes_de.clj` (port to kernel `:tax/*` and `:tax-rep/*` schema) |
| `beleg/src/org/replikativ/beleg/tax.clj` (`calculate-ustva`, `calculate-euer`) | `kontor-l10n-de/src/.../reports.clj` (rewrite against kernel `:posting/*`) |
| `RechnungsFee/docs/10-steuersaetze.md`                | `kontor-l10n-de/doc/de-tax-rules.md` (verbatim — this is the spec) |
| `RechnungsFee/docs/04-ustva.md`, `05-euer.md`         | `kontor-l10n-de/doc/`                              |
| `RechnungsFee/vorlagen/datev/datev-export.csv`        | `kontor-l10n-de/test/resources/datev-fixture.csv` (validation oracle) |

### Pull for Phase 4 — bank-importer module

| From                                                  | To                                                              |
| ----------------------------------------------------- | --------------------------------------------------------------- |
| `beleg/src/org/replikativ/beleg/bank.clj`             | `kontor-bank-de/src/.../parser.clj` — keep `bank-configs` map verbatim, `parse-german-amount`, `detect-bank`. Strip the Datahike `transact!` writes; emit posting-candidate maps instead. |
| `RechnungsFee/vorlagen/bank-csv/*.csv` (16 files) **and** `RechnungsFee/vorlagen/bank-csv/README.md` | `kontor-bank-de/test/resources/bank-csv/` |
| `beleg/test-data/CATALOG.md`                          | `kontor-bank-de/test/resources/CATALOG.md`         |
| `beleg/test-data/bank-csv/synthetic-*.csv` (3 files)  | `kontor-bank-de/test/resources/bank-csv/`          |
| `beleg/src/org/replikativ/beleg/bank.clj` `category-patterns` | `kontor-bank-de/src/.../categorize.clj` (keep but mark experimental — better off as user-supplied rules) |

### Leave in openclaw

- All top-level `*.md` (SOUL, IDENTITY, AGENTS, BOOTSTRAP, HEARTBEAT,
  USER, MEMORY, TOOLS) and `bedtime-job.json`.
- `memory/` daily logs.
- All transcripts (`file_*`, `test.*`).
- QR PNGs.
- `beleg/logs/`, `beleg/screenshot.png`, `beleg/.cpcache/`.
- `RechnungsFee/claude.md`, `fragen.md` (huge LLM dumps).
- `RechnungsFee/community-ankuendigung.md`, `social-media-grafiken.md`,
  `github-setup-anleitung.md`, `issue-13-comment.md` (OSS-project meta).
- Beleg's app-layer Clojure files (`server.clj`, `services.clj`, `vision.clj`,
  `render.clj`, `intake.clj`, `journal.clj`, `core.clj`, `config.clj`)
  — if `beleg` continues as a downstream consumer of kontor,
  these stay there. If `beleg` is being deprecated, archive separately.
- `duty_analyzer/` — out of scope.

### Discard

- `kluge-krabbe-buchhaltung/src/*.py` — superseded by `beleg/`'s Clojure
  rewrite. The Python is genuinely older/lesser. (Keep `README.md` for
  one day in case it has a phrase you want; then drop.)
- `kluge-krabbe-buchhaltung/docs/accountable-de-{research,vision-analysis}.md`
  — duplicate research; prefer `beleg/docs/accountable-integration.md`.
- `beleg/resources/schema.edn` — old/orphan: defines `:task/*`, `:offer/*`,
  `:customer/*` only and predates `beleg/src/.../schema.clj`. Drop.

---

## Concrete copy list (action-ready)

```
# Phase 4 (bank importer)
openclaw/beleg/src/org/replikativ/beleg/bank.clj
  → kontor-bank-de/src/kontor/bank_de/parser.clj  (port)
openclaw/RechnungsFee/vorlagen/bank-csv/
  → kontor-bank-de/test/resources/bank-csv/                    (copy as-is)
openclaw/RechnungsFee/vorlagen/bank-csv/README.md
  → kontor-bank-de/doc/bank-csv-formats.md                     (copy)
openclaw/beleg/test-data/CATALOG.md
  → kontor-bank-de/test/resources/CATALOG.md                   (copy)

# Phase 2 (DE l10n)
openclaw/beleg/src/org/replikativ/beleg/tax.clj
  → kontor-l10n-de/src/kontor/l10n_de/datev.clj   (port export-datev)
  + kontor-l10n-de/src/kontor/l10n_de/skr04.clj   (port skr04-mapping)
  + kontor-l10n-de/src/kontor/l10n_de/reports.clj (port calculate-ustva, calculate-euer)
openclaw/RechnungsFee/docs/10-steuersaetze.md
  → kontor-l10n-de/doc/de-tax-rules.md                         (copy)
openclaw/RechnungsFee/docs/04-ustva.md
  → kontor-l10n-de/doc/ustva.md                                (copy)
openclaw/RechnungsFee/docs/05-euer.md
  → kontor-l10n-de/doc/euer.md                                 (copy)
openclaw/RechnungsFee/vorlagen/datev/datev-export.csv
  → kontor-l10n-de/test/resources/datev-fixture.csv            (copy)
```

Everything else: leave in `openclaw/`.
