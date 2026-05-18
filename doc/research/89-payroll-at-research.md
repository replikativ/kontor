# 80 — Austrian payroll (Personalverrechnung) adapter

Research note for the `kontor-payroll-at` module (Stage R C12). Mirrors the
structure of the DE-payroll notes; calls out the Austrian specifics that
differ from Germany. Sources are public regulator + vendor docs — no
proprietary code is lifted (CLAUDE.md "What NOT to do").

Date: 2026-05-18.

## 1. Regulators, formats, file channels

The Austrian payroll-filing landscape has **three** main regulator touch-
points; each has its own format + channel. They are independent (one
period of payroll emits one of each, all three are required):

### 1.1 ÖGK — `mBGM` (monatliche Beitragsgrundlagenmeldung)

- **Recipient:** Österreichische Gesundheitskasse (ÖGK), the merged
  social-insurance fund (since 2020 replaces the nine regional GKKs).
- **Cadence:** monthly, due by the **15th** of the following month.
- **Replaces:** the pre-2019 `L16` (annual) + `Lohnzettel/Beitragsnachweis`
  monthly split. Since 1 Jan 2019 mBGM IS the single monthly contribution-
  base report.
- **Channel:** ELDA (Elektronischer Datenaustausch mit den österreichischen
  Sozialversicherungsträgern). XML or fixed-width text; submitted via the
  WEBEKU portal, the ELDA-Software-Client, or as a direct upload from
  payroll software.
- **Per-employee rows:** one mBGM row per (employee, contribution-period,
  Beschäftigtengruppe). Fields: VSNR (Versicherungsnummer = the 10-digit
  SV-number), Beitragsgrundlage (contribution base), Beitragsgruppe
  (D1, A1, B1, etc.), Sondernzahlungen-Anteil.
- **Reference:** Dachverband der Sozialversicherungen mBGM-XSD,
  Lohnzettel/Beitragsnachweis-Handbuch (public).

### 1.2 FinanzOnline / BMF — `L16` Lohnzettel (annual)

- **Recipient:** Bundesministerium für Finanzen (BMF).
- **Cadence:** annual, due by **end of February** of the following calendar
  year (employer push) OR concurrent with the year-end Lohnsteuer-
  Anmeldung if filed earlier.
- **What:** the employee's annual wage statement — total Bruttogehalt, LSt
  withheld, SV-Beiträge withheld, Sonderzahlungen total (the 6% special-
  rate base + the special rate's LSt). The employee gets a paper copy; the
  copy that goes to BMF is the canonical one.
- **Channel:** electronically via FinanzOnline (the BMF portal) OR via
  ELDA (which forwards to BMF). XML-via-FinanzOnline upload is the
  modern path; the legacy `L16-Tonband` (fixed-width tape format) is
  still accepted.
- **Reference:** BMF L16-Verordnung (Verordnung über das amtliche
  Formular L16), public.

### 1.3 Kommunalsteuer (3%) + DB-FLAG (4.1%) + DZ — municipal/regional

- **Recipients:** the Gemeinde (municipality) for Kommunalsteuer; the
  Finanzamt for Dienstgeberbeitrag-zum-Familienlastenausgleichsfonds (DB)
  + Zuschlag-zum-DB (DZ — varies per Bundesland, ~0.32–0.40 %).
- **Cadence:** monthly (15th of the next month) for filing; annual
  reconciliation via L 16 / KommSt-Jahreserklärung.
- **What:** payroll-tax bases independent of LSt + SV. KommSt taxes the
  wage bill paid to people working in that municipality; DB+DZ tax the
  same bill at the federal level.

## 2. File formats

### 2.1 ELDA file format (mBGM)

ELDA accepts two physical formats:

- **ELDA XML** (preferred, since 2019) — well-formed XML against the
  Dachverband XSD. Root element `<dvb:mBGM>`; declares the
  Beitragskontonummer of the employer, then a `<Person>` list.
- **ELDA fixed-width text** (legacy DGS — Datenträgerstandard Gesundheit)
  — 1024-character records, code-page 850. Still in production at small
  employers using BMD-NTCS legacy export.

For the kernel adapter v1 we emit XML (the format that's growing in
acceptance and that other modern payroll vendors target).

### 2.2 FinanzOnline L16 XML

FinanzOnline accepts an XML wire format defined by the BMF: root
element `<Lohnzettel xmlns="…">`; per-employee a `<L16-Datensatz>`
with the annual aggregates. There's a separate "Bruttosumme",
"Sonderzahlungen brutto", "Lohnsteuer", "SV-Beiträge",
"Sonderzahlungen-Lohnsteuer" set of elements.

The structure mirrors the printed L16 form (the form Austrian
employees see at the end of the year). Section "I" carries the regular
income; section "II" carries the Sonderzahlungen at the begünstigte
6% special rate.

### 2.3 DATEV-Buchungsexport (vendor file)

BMD-NTCS is the Austrian mid-market dominant payroll engine; RZL Lohn
is the smaller competitor. Both export their period summary as
CSV-or-XML for ingestion into the Hauptbuch (GL). The adapter
v1 ingests the BMD CSV (semicolon-separated, ISO-8859-15) — its
structure is `Datum;BS-Nr;Konto;Gegenkonto;Betrag;Soll/Haben;Text;
KSt;UID-Nr;Belegnummer`. This is the same shape as the DE-DATEV EXTF
abbreviated.

The RZL adapter ingests a related CSV — also semicolon-separated, but
columns `Datum;Konto;Gegenkonto;Betrag;Text;Kostenstelle`. Both
adapters output the same normalized `:payroll-result` map, so
downstream posting/accrual code doesn't care which engine produced it.

## 3. Wage-type vocabulary (Lohnarten)

The Austrian Personalverrechnung distinguishes per-period wage types
much like Germany's, with notable specifics:

- `:grundgehalt` — base monthly salary.
- `:überstunden` — overtime; the first 5 ÜStd/month are surcharged
  50%, the rest 100% (the consumer-side rate engine emits these as a
  separate Lohnart).
- `:urlaubsremuneration` — the 13th salary (Urlaubsremuneration / 14
  Gehalt typical for Austrian employees). Paid in June (one month
  ahead of holiday season).
- `:weihnachtsremuneration` — the 14th salary, paid in November.
  Both 13/14 ARE Sonderzahlungen — taxed at the begünstigte 6%
  special rate (sechstel-regel) up to the annual "Jahressechstel"
  threshold.
- `:sachbezüge` — non-cash benefits (Dienstwohnung, Firmenauto, etc.).
  Sachbezugswertverordnung sets the imputed values; the consumer
  computes (these are RATE TABLES, NOT BUNDLED).
- `:lohnsteuer` — LSt withholding, monthly tariff table (Lohnsteuer-
  Tabelle, BMF-published).
- `:sv-arbeitnehmer` — employee-borne SV (pension + health + unemp +
  Wohnbauförderung). Total ~18.12 % of base.
- `:sv-arbeitgeber` — employer SV (~21.23 % of base).
- `:dienstgeberbeitrag-fond` — DB-FLAG, 4.1 % federal.
- `:zuschlag-zum-db` — DZ, 0.32 - 0.40 % per Bundesland.
- `:kommunalsteuer` — 3 % municipal tax (Gemeinde).

The kernel module ships ONLY the wage-type keyword *vocabulary* and
the wage-type → GL account map. Rate tables (SV %, KomSt %, LSt
tariff) live in the consumer's payroll engine or in
`kontor-l10n-at-rates-<year>` partner modules that the user installs.

## 4. CoA mapping — RLG-1

Austrian Einheitskontenrahmen (UGB-aligned) account map for payroll:

| Account | Name | Wage type |
|---|---|---|
| 6000 | Gehälter | `:grundgehalt`, `:überstunden` |
| 6100 | Löhne | (hourly workers) |
| 6400 | Urlaubsremuneration | `:urlaubsremuneration` |
| 6410 | Weihnachtsremuneration | `:weihnachtsremuneration` |
| 6500 | Sozialaufwand-Arbeitgeber | `:sv-arbeitgeber` |
| 6510 | Dienstgeberbeitrag DB | `:dienstgeberbeitrag-fond` |
| 6520 | Kommunalsteuer | `:kommunalsteuer` |
| 6530 | Zuschlag zum DB | `:zuschlag-zum-db` |
| 6800 | Sachbezugsaufwand | `:sachbezüge` (Aufwand-Seite) |
| 3500 | LSt-Verbindlichkeit | `:lohnsteuer` (payable) |
| 3540 | SV-Verbindlichkeit | `:sv-arbeitnehmer` + `:sv-arbeitgeber` (payable) |
| 3550 | DB-Verbindlichkeit | DB + DZ (payable) |
| 3560 | KommSt-Verbindlichkeit | KommSt (payable) |
| 3590 | Verrechnungskonto Lohn | clearing line |
| 3700 | Verbindlichkeit Lohn | net pay (employee receivable) |

The kernel's `posting-builder` resolves these account codes via the
generic `:account/external-codes` (ADR-019) keyed by `:rlg-1` — same
pattern as the German `:skr-04`.

## 5. Accruals (mandatory under UGB §198)

### 5.1 Urlaubsrückstellung

UGB §198 Abs.8 mandates a balance-sheet provision for unused vacation.
The kernel module computes the monthly accrual as:

  `(avg-daily-base × days-earned-this-period × (1 + employer-SV-rate))`

posts `Dr 6000 Personalaufwand / Cr 3700 Verbindlichkeit-Urlaub`
(plus consumer-supplied account refs).

### 5.2 13./14. Sonderzahlung accrual

Mandatory monthly accrual of 1/12 of expected 13./14. Sonderzahlung
(plus pro-rated employer SV — 21.23 %). Posted as:
  `Dr 6400/6410 / Cr 3700-Urlaubs+Weihnachtsrückstellung`.

In June/November the actual payment reverses against the accrual; if
actuals ≠ accrual the residue lands in `:adjustment-period` (per
ADR-014) for that fiscal year.

### 5.3 Abfertigung-alt — OUT OF SCOPE for v1

Pre-2003 hire-date employees retain the *Abfertigung-Alt* (severance)
entitlement, an actuarial obligation that gets re-measured per year.
This requires an actuarial model + Sterbetafel + discount-rate
assumption — explicitly **out of scope for v1**, as is BV (4.5 %
employer-paid post-2003 "Mitarbeitervorsorge", BUAK (construction-
industry), and Reisekosten-Abrechnung.

## 6. Decision: DE-AT code-share?

The AT and DE payroll-engine adapters share **structure** (CSV ingest,
wage-type map, accrual model, audit-doc emission), but the **content**
(account codes, regulator names, ELDA-vs-DEÜV, rate tables) is fully
disjoint.

**Decision: keep modules independent.** Mirrors note 86's "each country
adapter ships separately" guidance, and the existing `l10n-de` ↔
`l10n-at` precedent (the two share the German *language* in resource
keys, but the implementation is duplicated). The duplication is
minimal — a `kontor.payroll.de-at-common` shared layer would have to
re-export country-specific things anyway, and a small amount of
duplicated code beats a leaky abstraction.

## 7. Acceptance criteria summarized

A working `kontor-payroll-at` v1 ships:

- BMD-CSV adapter (`BmdGlProvider`).
- RZL-CSV adapter (`RzlGlProvider`).
- Per-component wage-type → RLG-1 account map (default; consumer can
  override).
- `compute-payroll!` function that ingests one CSV + posts a single
  monthly transaction.
- `Urlaubsrückstellung-accrue!` + `sonderzahlung-accrue!`.
- `emit-mbgm` builds the monthly mBGM XML artifact and records it as
  an `:audit-doc` (`:category :payroll-filing`, `:language :de`,
  `:type :mbgm`).
- `emit-l16` builds the annual L16 XML artifact and records it as an
  `:audit-doc` (`:category :payroll-filing`, `:language :de`,
  `:type :l16-lohnzettel`).
- A `AtPayrollEmitProvider` record that composes the above.
- An end-to-end test: an AT GmbH with 2 employees, runs 1 monthly +
  13th/14th Sonderzahlung, verifies the GL balance, the accrual
  balance, the mBGM XML content, the L16 XML content.

## 8. Out of scope

- LSt-Tariff table (BMF-published, annually changes).
- SV-rate table (Dachverband-published, annually changes).
- Kommunalsteuer % override (federal 3 %; some Gemeinden offer
  Befreiungen — consumer concern).
- Abfertigung-Alt actuarial.
- BV / SVS post-2003 employer-paid 4.5 % (kept consumer-driven).
- BUAK (Bauarbeiter-Urlaubs- und Abfertigungskasse) — construction-
  industry sectoral fund. Sectoral; consumer-driven.
- Reisekostenabrechnung — see `kontor-expense` rather than payroll.

## 9. References (all public)

- BMF — Lohnsteuerrichtlinien 2025/2026, L16-Verordnung.
- ÖGK — mBGM XML XSD, ELDA-Software-Client Dokumentation.
- Dachverband der Sozialversicherungen — Beitragsnachweis-Handbuch.
- BMD NTCS — Buchungsexport CSV-Format (BMD Knowledgebase, public).
- RZL Lohn — Datenaustausch FibuExport (RZL FAQ, public).
- UGB §198 — Bilanzansatzgebot Rückstellungen.
