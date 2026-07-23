# kontor-import-datev

The general **DATEV EXTF** codec — import and export of the German
tax-advisor ecosystem's interchange format (ADR-120).

## What it does

DATEV eG's **EXTF** ("Export-Format") is the semicolon-separated file
German businesses and Steuerberater exchange journal data with. This
companion owns the format grammar — one spec-correct implementation,
parse and render, parallel to the `bank-*` importers.

- **`kontor.import-datev.extf`** — the shared codec: header render/parse,
  row split/escape, and the amount/date scalar codecs (ISO-8859-1, CR-LF,
  `;`, comma-decimals, RFC-4180 quoting). The header is spec-correct —
  field 5 is the **Formatversion** (pinned to the Versionsnummer via
  `version->formatversion`: 510→7, 700→13), not a line count.
- **`kontor.import-datev.buchungsstapel`** — Datenkategorie 21 GL journals:
  - `export-buchungsstapel` — posted `:kontor.posting/*` → EXTF string,
  - `parse-buchungsstapel` — EXTF string → neutral `booking` maps,
  - `booking->tx-data` — a booking → a balanced two-leg transaction
    (you supply the Konto→account resolver; codes aren't globally unique,
    per ADR-119).

The keystone is a proven **export→import round-trip** on two-leg entries.
`kontor.l10n-de.datev` re-exports this as the DE entry point (legacy
`:client-number`/`:konto-nummer` opts still accepted).

## Scope

v1 ships the round-trip keystone + the corrected header. Deferred
(research note 195): BU-Schlüssel tax keys, lossless >2-leg contra split,
Debitoren/Kreditoren (cat 16) + Sachkontenbeschriftungen (cat 20)
master-data records, and folding `payroll-de-datev` onto this codec.

## License

Format grammar only — clean-roomed from developer.datev.de and
cross-checked against the MIT-licensed
[`ledermann/datev`](https://github.com/ledermann/datev) example header
(attributed in the tests). Per ADR-005 discipline: this module never
bundles SKR03/SKR04 chart data (DATEV database right) or any real
customer EXTF sample — a consumer supplies its own accounts.
