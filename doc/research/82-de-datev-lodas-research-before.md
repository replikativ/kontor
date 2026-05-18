---
date: 2026-05-18
title: 82 — DE DATEV LODAS research-before (Stage R C2 adapter)
status: draft
audience: maintainer / impl-agent picking up Stage R C2 after note 79 §5.1 schedules it
---

# 82 — DE DATEV LODAS research-before

Stage R C2 (`modules/payroll-de-datev/`) ships the first concrete
`PayrollComputeProvider` / `PayrollPostingBuilder` / `PayrollEmitProvider`
triple per note [[79-hr-payroll-stage-r-plan]] §5.1. The substrate
work (C1) is described in note 79 §3 + note 79 §4; this note is the
**research-before** for C2 — the format specs, account map, accrual
algorithms, vendor-adapter precedents, and component vocabulary the
impl agent will lean on so they do not re-derive the trade-offs.

License posture (CLAUDE.md / ADR-001 unchanged): DATEV's file
formats are **public specifications**; we describe them and write
clean-room implementations. We never bundle DATEV's wage-type
catalog as data we ship — the consumer's tax advisor (`Steuerberater`)
supplies the catalog at install time, mirroring ADR-005 / ADR-071
"no bundled rate tables." Personio / Sage / sevDesk / Lexware are
**reference vendors**; we read their documentation for shape, not
their code. SKR04 + SKR03 numbering conventions are facts (the
DATEV cooperative publishes the chart structure as a public
standard), not copyrighted material.

This note assumes the [[81-hr-data-model-gold-standards]] §9.6
refactor is in C1 — i.e. `:compensation` + `:compensation-component`
are entities rather than scalars on `:employment`. The DE
component vocabulary in §7 lands as `:compensation-component/kind`
keywords.

---

## §1 — TL;DR (10 bullets the impl agent can lean on)

1. **kontor never re-implements DE gross-to-net.** EStG/SGB/ELStAM
   /Kurzarbeit math stays in DATEV. C2 is a **glue** module that
   parses what LODAS already produced and routes it to the
   substrate. Mirrors ADR-005's posture (no bundled rate tables)
   and ADR-071's `TaxRateProvider` discipline.
2. **LODAS round-trip is a two-file ASCII contract.** Inbound:
   the **LODAS-Importdatei** — a single text file with
   `[Allgemein]` / `[Satzbeschreibung]` / `[Stammdaten]` /
   `[Bewegungsdaten]` sections, semicolon-delimited, encoded in
   ISO-8859-1, ending records with CR/LF. Outbound: per-employee
   wage results land in DATEV's appliance; the relevant data
   kontor consumes is the **Lohn-Buchungsbeleg** (Report 80) which
   exports in **EXTF Buchungsstapel** format — the same format
   kontor already supports via `kontor.l10n-de.datev`.
3. **The "Lohnauswertungsdatei" people usually mean is a PDF**
   from the `Lohnauswertungsdatenservice` online-API; it is not
   the structured data source. The structured GL impact arrives
   via the EXTF Buchungsbeleg (per §3 below). C2's
   `PayrollComputeProvider` parses the **Buchungsbeleg** (not a
   "Lohnauswertungsdatei CSV") to extract `:payroll-facts`.
4. **`[Satzbeschreibung]` is a per-file mini-schema.** Each line
   declares a record class (DB-table name like
   `u_lod_psd_mitarbeiter` or `u_lod_bwd_buchung_standard`)
   followed by the ordered field list for that class. **The same
   import file can mix Personalstammdaten and Bewegungsdaten** by
   declaring multiple record classes.
5. **DATEV "Lohnarten" (wage types) are a per-customer catalog,
   not a kontor-owned enum.** Personio + every other adapter
   surveyed treats Lohnart numbers as **consumer-supplied
   mapping**: the customer's Steuerberater configures the LODAS
   "Festbezugstabelle" and the consumer (Personio, Factorial,
   kontor) maps internal compensation components to those LODAS
   Bezug/Abzug numbers at install time. **Numbers <9000 = Bezug
   (gross), ≥9000 = Netto-Bezug/Netto-Abzug** is the only stable
   range convention.
6. **SKR04 wage-account map is publishable as a starter; SKR03
   is the parallel chart most older mandanten still use.** The
   load-bearing accounts (10 numbers across both charts) are in
   §4. The kontor module ships a `account-hint → SKR04-code` map
   plus a `account-hint → SKR03-code` map; the consumer picks
   based on `:entity/coa-variant`.
7. **HGB §249 forces Urlaubsrückstellung + Weihnachtsgeld-
   rückstellung + Pensionsrückstellung as Handelsbilanz-only
   liabilities** that the tax-book often does not carry (or
   carries with a different valuation). This is exactly what
   ADR-021's `:ledger/framework` parallel-ledger pattern was
   built for: HGB-book accrues, Steuerbilanz-book does not (or
   uses BFH-method numbers). C2 ships a vanilla simplified-PTO
   accrual; pensions stay actuarial-out-of-scope and require the
   consumer to plug in a `:pension-actuary-doc` audit-doc.
8. **Personio is the Mittelstand reference adapter.** Mandatory
   per-employee attrs: `DATEV Personalnummer`, `Consider in German
   payroll`, `Type of health insurance`, and **11-character
   Steuer-Identifikationsnummer**. Personio + DATEV separates
   master data (`stammdaten.txt`) from movement data
   (`bewegungsdaten.txt`); a ZIP is the typical bundle. This is
   the shape kontor's `EmitProvider` writes outbound.
9. **DEÜV / GKV-Monatsmeldung / ELStAM** are jurisdictional
   event-bus emissions that stay **inside the DATEV appliance**.
   Per ADR-005 / ADR-071 posture: kontor does not re-implement;
   the `PayrollEmitProvider` round-trips the file payload but
   transmission to gkv-datenaustausch.de or to the SV-Meldeportal
   is the customer's job. C2 ships only the LODAS-bound
   Importdatei (master + variable inputs); SV-side electronic
   messages remain DATEV's appliance.
10. **Effort estimate per note 79 §5.1 holds: ~8 days.** Parser
    (LODAS Importdatei + EXTF Buchungsbeleg) 2d, SKR04 + SKR03
    posting builder 2d, EmitProvider (Stammdaten + Bewegungsdaten
    writer) 2d, end-to-end test on DE GmbH cross-stage scenario
    1d, ADR + docs + review-after 1d.

---

## §2 — DATEV LODAS file format — Importdatei (Lohnimport)

The **inbound** side of the C2 adapter: kontor writes a LODAS
Importdatei carrying employee master-data and variable
movement-data; the customer's Steuerberater opens LODAS, navigates
**Mandant → Daten übernehmen → ASCII-Import**, selects the file,
and clicks **Start**. The file format is described in DATEV's
**SSH Schnittstellenhandbuch LODAS** (45. Auflage Januar 2016,
distributed inside the LODAS install at
`\PROGRAMM\LODAS\Handbuch\Deutsche Version\LODAS_SSH.pdf`; an
updated 2025-06 edition exists at help-center entity
`st36028834085966347_de.pdf` per the DATEV Hilfe-Center).
[§11.1, §11.2]

### 2.1 — File-level conventions

- **Encoding**: ISO-8859-1 (8-bit Latin-1). UTF-8 is not
  reliably accepted; Umlaute (ä/ö/ü/ß) must be in the Latin-1
  code points, not UTF-8 multibytes. *Same constraint EXTF
  Buchungsstapel inherits — `kontor.l10n-de.datev` already encodes
  ISO-8859-1.* [§11.1]
- **Line termination**: CR/LF (Windows convention). LF-only is
  not accepted by the importer. [§11.6]
- **Field separator**: semicolon (`;`). Configurable via the
  `Feldtrennzeichen=;` directive in `[Allgemein]` but semicolon
  is the de-facto standard. [§11.5]
- **Decimal separator**: comma (`,`) — German convention. The
  thousands separator is omitted. `1234,56` not `1.234,56`.
  [§11.5]
- **Date format**: configured per-file via `Datumsformat=TTMMJJJJ`
  in `[Allgemein]`. `TT.MM.JJJJ` is the other widely-used variant.
  Internally, kontor `java.time.LocalDate` formats to the
  declared pattern. [§11.5]
- **String quoting**: double-quote (`"…"`) when the value
  contains a semicolon or quote; internal quotes doubled
  (RFC 4180 style). [§11.5]
- **Comments**: any line starting with the character declared in
  `Kommentarzeichen=*` (typical `*`). [§11.5]
- **Filename convention**: `<scenario>_LODAS___<MM>.txt` is the
  common pattern; e.g. `ANF_LODAS___12.txt` for the December
  onboarding scenario per e2n. Personio and Circula bundle as
  ZIP with `stammdaten.txt` + `bewegungsdaten.txt` inside.
  [§11.7, §11.8]

### 2.2 — The four mandatory sections

Every LODAS import file consists of exactly these sections, in
this order. **`[Allgemein]` and `[Satzbeschreibung]` are always
required**; **`[Stammdaten]` and/or `[Bewegungsdaten]` is required
(at least one of them).** [§11.5]

#### `[Allgemein]` — file-level header

A small key=value block declaring the routing and the file-format
options. Required keys:

- `Ziel=LODAS` — the destination application (alternative: `LuG`
  for "Lohn und Gehalt"; format is **slightly** different —
  treated below in §2.6).
- `BeraterNr=<n>` — the tax-advisor's DATEV consultant number
  (4–7 digits).
- `MandantenNr=<n>` — the client number inside the consultant's
  scope.
- `Datumsformat=TTMMJJJJ` (or `TT.MM.JJJJ`).
- `StammdatenGueltigAb=<DD.MM.YYYY>` — the effective date for
  master-data changes in this file (required when shipping
  `[Stammdaten]`).
- Optional: `Kommentarzeichen=*`, `Feldtrennzeichen=;`,
  `BetrieblichePNrVerwenden=Ja` (if the customer uses their own
  Personalnummer space, not DATEV's auto-numbering),
  `Version_SST=<X.Y>` (interface version — typically `1.0`),
  `Version_DB=<X.Y>` (LODAS DB version — `12.2` was a stable
  baseline).

Example (drawn verbatim from DATEV-Community forum post 364028
[§11.4]):

```text
[Allgemein]
Ziel=LODAS
Kommentarzeichen=*
BeraterNr=1234
MandantenNr=99999
Datumsformat=TTMMJJJJ
StammdatenGueltigAb=01082023
```

#### `[Satzbeschreibung]` — per-file mini-schema

The file declares **which record classes appear in the body and
in what column order**. Each line has the shape:

```text
<ord>;<table_name>;<field1>;<field2>;…
```

- `<ord>` — record class ordinal (1, 2, 3, …) used as the first
  field of every body row to discriminate.
- `<table_name>` — a DATEV-LODAS DB table prefixed with one of:
  - `u_lod_psd_*` — **Personal-Stammdaten** (per-employee master
    data; e.g. `u_lod_psd_mitarbeiter` = address + name; per
    `Lodas Felder zur Bestückung der Import-Datei` [§11.9]).
  - `u_lod_mpd_*` — **Mandanten-Stammdaten** (per-client master
    data; e.g. `u_lod_mpd_baustelle`, `u_lod_mpd_lohntabelle`).
  - `u_lod_bwd_*` — **Bewegungsdaten** (per-employee per-period
    variable inputs; e.g. `u_lod_bwd_buchung_standard` for
    standard wage-type bookings, `u_lod_bwd_buchung_brutto` for
    gross-line entries).
- `<fieldN>` — the table column name with the suffix marker
  `#psd` (Personalstammdaten), `#mpd` (Mandantenstammdaten),
  or `#bwd` (Bewegungsdaten). Example: `pnr#psd`,
  `std_lohn_1#psd`, `bs_wert_butab#bwd`.

The per-file mini-schema lets the consumer choose **only the
fields they have data for** — no field that is not declared in
`[Satzbeschreibung]` needs to appear in the body. Missing
columns inside an existing record stay empty (`;;`). [§11.5,
§11.9]

Example (drawn from a DATEV-Community forum post on Bewegungsdaten
import [§11.4]):

```text
[Satzbeschreibung]
1;u_lod_bwd_buchung_standard;abrechnung_zeitraum#bwd;pnr#bwd;bs_nr#bwd;la_eigene#bwd;bs_wert_butab#bwd;kostenstelle#bwd;bemerkung#bwd;
```

#### `[Stammdaten]` — per-employee master rows (optional)

One row per master-data update, beginning with the record-class
ordinal. The field order matches the `[Satzbeschreibung]`
declaration for that ordinal. Example (verbatim shape, fields
elided after pos.12, per [§11.4]):

```text
[Stammdaten]
1;"BPNR 1234";"Müller";"Franz";"Baustraße";"27";"Teststadt";98765;000;23.02.1958;0;1;
```

In this example the `[Satzbeschreibung]` for ordinal `1` declared
`u_lod_psd_mitarbeiter` with columns `pnr_betriebliche#psd`,
`duevo_familienname#psd`, `duevo_vorname#psd`, `adresse_strassenname#psd`,
`adresse_strasse_nr#psd`, `adresse_ort#psd`,
`adresse_plz#psd`, `kz_eu_ausland#psd`, `geburtsdatum_ttmmjjjj#psd`,
`geschlecht#psd`, `familienstand#psd`. The `BPNR 1234` value is
the **betriebliche Personalnummer** (customer-owned id, used when
`BetrieblichePNrVerwenden=Ja`). [§11.4]

#### `[Bewegungsdaten]` — per-employee per-period variable inputs (optional)

One row per booking (overtime hours, bonus, retro-correction,
mileage reimbursement, garnishment payout). Same per-row ordinal
+ field-order convention. Example (verbatim, per [§11.4]):

```text
[Bewegungsdaten]
1;01.05.2018;4712;1;774;1,67;;
1;01.05.2018;4713;1;774;2,5;;
```

In this example the columns from `[Satzbeschreibung]` are
`abrechnung_zeitraum#bwd` (the pay period — `01.05.2018`),
`pnr#bwd` (employee no.), `bs_nr#bwd` (booking serial),
`la_eigene#bwd` (the customer's wage-type number — `774`),
`bs_wert_butab#bwd` (booking value — `1,67` hours / euros / units
depending on the wage-type's UoM), `kostenstelle#bwd` (cost
center, empty here), `bemerkung#bwd` (free-text memo, empty).
[§11.4]

### 2.3 — Personal-Stammdaten field catalog (the load-bearing
       LODAS DB tables)

From the 2010 yumpu summary of `LODAS Felder zur Bestückung der
Import-Datei` (still structurally accurate; the post-2025 PDF
adds fields but does not break the existing ones) [§11.9]:

- `u_lod_psd_mitarbeiter` — address + name + birth + gender +
  marital status (the example above shows columns 1..12).
- `u_lod_psd_avs_ag_proz` — Arbeitgeberanteil zur betrieblichen
  Altersvorsorge in Prozent.
- `u_lod_psd_beschaeftig` — Beschäftigungs-Stammdaten (job class,
  entry date, exit date, working hours, FLSA-analog flag
  `kz_dauer_befristet`).
- `u_lod_psd_monatslohn` — fixed monthly gross.
- `u_lod_psd_lohn_gehalt_bezuege` — recurring Bezug rows (each
  with a Bezug-Nr + amount).
- `u_lod_psd_sv_vorbw` — Sozialversicherungs-Vorbewertung (KV/RV/
  AV/PV/UV groupings, tax class, child allowances, religion code).

The C2 `EmitProvider` writes only the **minimum set required for
a clean hire / change** — `mitarbeiter` + `beschaeftig` +
`monatslohn` and the `lohn_gehalt_bezuege` rows that the
consumer's compensation-components map to. Per Personio's
mandatory-attribute list [§11.10]:

- `DATEV Personalnummer` (or `betriebliche Personalnummer` if
  `BetrieblichePNrVerwenden=Ja`)
- `Steueridentifikationsnummer` (11 digits, validated by Personio
  with a checksum; we cite the format but do not bundle the algorithm)
- `Sozialversicherungsnummer` (12 chars: 2-digit DDMM-area + 6-digit
  birth-date + 1-letter family-name initial + 2-digit serial +
  1-digit checksum — kontor stores as string, not parsed)
- `Krankenkasse` (Type of health insurance — gesetzlich / privat;
  drives which SV-Träger gets the Beitragsnachweis)
- `Geburtsdatum`, `Familienstand`, `Religionszugehörigkeit`,
  `Steuerklasse`, `Kinderfreibetrag`
- `Bankverbindung` (IBAN + BIC; net pay routes here)

### 2.4 — Bewegungsdaten field catalog

From the same source and the DATEV-Community examples:

- `u_lod_bwd_buchung_standard` — the most common Bewegungs-row:
  pay-period + Pnr + Bezug/Abzug-Nr + value + optional cost
  center + memo. **This is the primary table C2 writes for
  per-period variable inputs.**
- `u_lod_bwd_buchung_brutto` — gross-line entry (use when the
  consumer needs to override a Bruttobetrag directly).
- `u_lod_bwd_buchung_stu` — Stundenlohn-Verrechnung (hourly-wage
  hours, used by `kz_lohn_gehalt_kennung=1` employees).
- `u_lod_bwd_buchung_akkord` — Akkordlohn (piecework — rare).
- `u_lod_bwd_buchung_kal` — Kalendertag-basierte Buchung
  (calendar-day driven entries, e.g. for `Beschäftigungsverbot`).
- `u_lod_bwd_sonstige_an` — sonstige Bezüge/Abzüge (one-off
  bonuses, severance, etc.).

### 2.5 — Composite example: a single LODAS Importdatei carrying
       master + variable

From DATEV-Community 364028 [§11.4]:

```text
[Allgemein]
Ziel=LODAS
Kommentarzeichen=*
BeraterNr=1234
MandantenNr=99999
Datumsformat=TTMMJJJJ
StammdatenGueltigAb=01082023

[Satzbeschreibung]
1;u_lod_psd_lohn_gehalt_bezuege;pnr#psd;std_lohn_1#psd

[Bewegungsdaten]
1;1;25,00
1;2;11,99
```

This file says: "for Mandant 99999 under Berater 1234, effective
2023-08-01, set employee 1's standard wage-1 to 25,00 €/h and
employee 2's to 11,99 €/h." Two employees, one master-data
update each.

### 2.6 — LODAS vs. "Lohn und Gehalt" — same shape, different scope

DATEV ships two payroll products:

- **LODAS** — abrechnung im DATEV-Rechenzentrum (centralized
  cloud). The Steuerberater shop's typical product. Updates to
  Beitragssätze etc. arrive automatically via the DATEV-RZ.
  [§11.11]
- **Lohn und Gehalt (LuG)** — on-premise / Vor-Ort-Lohnabrechnung.
  Updates arrive via Abonnementorder.

Both accept similar ASCII import formats but:
- LuG uses `Ziel=LuG` in `[Allgemein]`.
- LuG's per-employee mass import uses
  `Feldbeschreibung_Stammdatenimport_LUG.pdf` field names — a
  separate field-naming scheme.
- LuG's per-period import uses the simpler **three-column**
  format `<Pnr> <Lohnart> <Betrag>` per line ([§11.8]'s Circula
  example), without sections.

**For C2 we target `Ziel=LODAS` first** — note 79 §5.1 frames it
as "DE-DATEV-LODAS", and the LODAS format is strictly richer
(LuG is a subset). A flag `:datev-target #{:lodas :lug}` on the
EmitProvider config switches the format; default `:lodas`.

---

## §3 — DATEV LODAS file format — Buchungsbeleg (the GL output kontor consumes)

LODAS / Lohn und Gehalt do not export a "Lohnauswertungsdatei
CSV" with per-wage-type amounts as a generic consumer file. The
official Lohnauswertungs-output product is the
**Lohnauswertungsdatenservice**, which delivers the per-period
results **as PDF** (optionally ZIP-bundled). PDF is not the
right input for a `PayrollComputeProvider` parser. [§11.12, §11.13]

The structured GL impact arrives via a different mechanism: the
**Lohn-Buchungsbeleg** (Report 80 in LODAS), which the LODAS
operator generates per pay-period and either submits to the
DATEV-RZ or exports as an ASCII file in **EXTF Buchungsstapel**
format. This is the **same EXTF format kontor already supports**
via `modules/l10n-de/src/kontor/l10n_de/datev.clj` — the only
difference is the export *direction* (LODAS writes, kontor
reads, vs kontor writes for Fibu-handoff today).

### 3.1 — Buchungsbeleg generation in LODAS

Per the LODAS Hilfe-Center docs [§11.14]:

- **Setup**: LODAS → Mandant → Lohnabrechnung → Buchungsbeleg →
  Konfiguration. Report 80 (Buchungsbeleg) is enabled per
  mandant.
- **Output format**: "DATEV-Format (ASCII-Format Standard) 7.0"
  is selected; this is the **EXTF Buchungsstapel schema 510
  v21** format (despite the user-facing label "7.0"). [§11.14]
- **Frequency**: once per pay-period after Monatsabschluss.
- **Sequencing**: the Buchungsbeleg is generated *after* LODAS
  has performed gross-to-net and run the DEÜV / SV / ELStAM
  emissions; the booking-stapel reflects the final monthly
  postings, not a pre-calc.

### 3.2 — EXTF Buchungsstapel — what kontor reads

Per `kontor.l10n-de.datev` lines 36-90 (the canonical reference
implementation of the format kontor already supports), every
Buchungsstapel file is:

- **Line 1**: header — `EXTF;510;21;Buchungsstapel;<line_count>;
  <YYYYMMDDHHmmssSSS_timestamp>;;HC;<company_name>;;
  <client_number>;<konto_nummer_prefix>;<period_start_YYYYMMDD>;
  4;<period_start>;<period_end>;Buchungen;;1;0;1;EUR;;;;`
- **Line 2**: column names (122 columns, schema 510 v21).
- **Lines 3..n**: data rows, one posting per row.

The 12 load-bearing columns of every data row (per
`datev.clj:42-89`):

| # | Column | What |
|---|---|---|
| 1 | `Umsatz (ohne Soll/Haben-Kz)` | amount, positive only, comma decimal |
| 2 | `Soll/Haben-Kennzeichen` | `S` debit / `H` credit |
| 3 | `WKZ Umsatz` | currency code, `EUR` |
| 7 | `Konto` | account number (4-digit SKR03 or SKR04) |
| 8 | `Gegenkonto (ohne BU-Schlüssel)` | contra-account number |
| 9 | `BU-Schlüssel` | tax-code (e.g. `9` = 19% USt) — usually empty for payroll |
| 10 | `Belegdatum` | `DDMM` (year from header) |
| 11 | `Belegfeld 1` | reference (often Pnr or Lohnart number) |
| 12 | `Belegfeld 2` | reference (often pay-period `MM/YYYY`) |
| 14 | `Buchungstext` | free text (often `Lohn/Gehalt Müller 11/2025`) |
| 37 | `KOST1 - Kostenstelle` | cost center if used |
| 38 | `KOST2 - Kostenstelle` | secondary cost center |

The remaining 110 columns stay empty for payroll postings.

### 3.3 — Per-pay-period Buchungsbeleg fixture (synthetic, gross-method)

A single employee, monthly Gehalt 4000 € brutto, AN-Anteil SV
800 €, AG-Anteil SV 800 €, LSt+KiSt+Soli 700 €, net 2500 €, paid
via bank. SKR04 numbering. (Plausible — illustrative; the actual
LODAS output would carry many more rows for KV/RV/AV/PV/UV split
and per-Lohnart line items.)

```text
EXTF;510;21;Buchungsstapel;9;20251130120000000;;HC;Acme GmbH;;12345;1;20251101;4;20251101;20251130;Buchungen;;1;0;1;EUR;;;;
"Umsatz (ohne Soll/Haben-Kz)";"Soll/Haben-Kennzeichen";"WKZ Umsatz";…(122 cols)…
4000,00;S;EUR;;;;6020;3790;;3011;1;11/2025;;Bruttogehalt Müller 11/2025;…
2500,00;H;EUR;;;;3790;3720;;3011;1;11/2025;;Nettoauszahlung Müller 11/2025;…
700,00;H;EUR;;;;3790;3730;;3011;1;11/2025;;LSt+KiSt+Soli Müller 11/2025;…
800,00;H;EUR;;;;3790;3740;;3011;1;11/2025;;SV AN-Anteil Müller 11/2025;…
800,00;S;EUR;;;;6110;3740;;3011;1;11/2025;;SV AG-Anteil Müller 11/2025;…
```

After the five rows, account **3790 (Lohn- und Gehaltsverrechnungs-
konto)** has a zero balance — this is the LODAS Buchungsbeleg
invariant per [§11.18]. **kontor's parser should assert it** at
load time and refuse a Buchungsbeleg that does not balance.

### 3.4 — How C2's `PayrollComputeProvider` consumes this

```clojure
(parse-buchungsbeleg
  (slurp "Buchungsbeleg_11-2025.csv" :encoding "ISO-8859-1"))
;; → [{:employee-pnr "3011" :period {:from #inst "2025-11-01" :to #inst "2025-11-30"}
;;     :gross 4000.00M :net 2500.00M
;;     :withholding-tax 700.00M
;;     :employee-si 800.00M :employer-si 800.00M
;;     :components [{:kind :base-wage :amount 4000.00M :account-code "6020"}
;;                  {:kind :employer-si :amount 800.00M :account-code "6110"}]
;;     :raw-postings [...]}]
;; — one :payroll-facts entity per (employee × pay-period)
```

The parser groups EXTF rows by `Belegfeld 1` (Pnr) + `Belegfeld 2`
(period); the grouping invariant (3790 balances to zero per
group) lets us sanity-check the partition. The 6xxx (SKR04) /
4xxx (SKR03) account codes drive the `:compensation-component/
kind` classification per §4.

---

## §4 — SKR04 wage-account map (and SKR03 parallel)

DATEV's German chart of accounts ships in two variants:

- **SKR04** (Standardkontenrahmen 04, "Abschluss-orientiert") —
  uses Klasse 6 for Personalaufwand and Klasse 3 for
  Verbindlichkeiten. Most newer mandanten.
- **SKR03** (Standardkontenrahmen 03, "Prozess-orientiert") —
  uses Klasse 4 for Personalaufwand and Klasse 1 for
  Verbindlichkeiten. Most older mandanten + many Steuerkanzleien.

The Konto numbers and German names are publicly published as a
DATEV cooperative standard (no copyright on the numbering
itself; per the existing `kontor.l10n-de.chart` ns header). C2
ships both maps; the consumer picks via
`:entity/coa-variant #{:skr04 :skr03}`.

**Note on the existing `resources/kontor/l10n_de/skr04.edn`**:
that file currently uses SKR03 numbers (4120, 4124, 6020/Gehälter
in a `:expense` slot but at the wrong Klasse boundary). The
C2 work should either (a) fix the SKR04 numbering in the existing
chart or (b) ship a parallel `skr04-personal.edn` for the
payroll subset. **Recommendation: fix in place, add the SKR03
parallel as a second file.** This is a separate cleanup PR
preceding C2.

### 4.1 — The 10 load-bearing payroll accounts

Source: BuchhaltungsButler [§11.15], rechnungswesen-info.de
[§11.16], Kontolino! [§11.17]; cross-checked with buchungssatz.de
SKR04 3730 [§11.19] and SKR04 3740 [§11.20].

| Account-hint (`:compensation-component/kind` or builder concept) | SKR04 | SKR03 | Name |
|---|---|---|---|
| `:base-wage` (hourly Löhne) | **6010** | **4120** | Löhne |
| `:base-wage` (monthly Gehälter) | **6020** | **4124** | Gehälter |
| `:employer-si` (Arbeitgeberanteil Soz.Vers.) | **6110** | **4130** | Gesetzliche soziale Aufwendungen |
| `:bonus`, `:weihnachtsgeld`, `:urlaubsgeld` (voluntary, taxable) | **6060** | **4145** | Freiwillige soziale Aufwendungen, lohnsteuerpflichtig |
| `:vwl` (Vermögenswirksame Leistungen, lohnsteuerpflichtig) | **6060** | **4145** | (same row as above) |
| `:imputed-income` (Sachzuwendungen / geldwerter Vorteil, steuerfrei) | **6130** | **4140** | Freiwillige soziale Aufwendungen, lohnsteuerfrei |
| `:imputed-income-taxable` (Sachzuwendung lohnsteuerpflichtig) | **6060** | **4145** | (lohnsteuerpflichtig) |
| Verb. Lohn/Gehalt (employee net payable) | **3720** | **1740** | Verbindlichkeiten aus Löhnen und Gehältern |
| Verb. LSt/KiSt/Soli (tax authority) | **3730** | **1741** | Verbindlichkeiten aus Lohn- und Kirchensteuer |
| Verb. Soz.Vers. (employer + employee SI to SV-Träger) | **3740** | **1755** | Verbindlichkeiten im Rahmen der sozialen Sicherheit |
| Lohn-/Gehaltsverrechnungskonto (clearing) | **3790** | **1755**-adjacent | Lohn- und Gehaltsverrechnungskonto |

### 4.2 — Bruttomethode bookings (what the EXTF Buchungsbeleg
       contains, per pay-period)

The standard set, per [§11.16, §11.15]:

1. **Gross expense**: `6020 (Gehälter)` an `3790
   (Verrechnungskonto)` — full Bruttogehalt as expense.
2. **Net payment**: `3790` an `3720 (Verb. Lohn)` — net pay
   becomes a liability to the employee, settled when bank
   transfer clears.
3. **AN-Anteil SV** (employee SI deducted from gross): `3790`
   an `3740 (Verb. SozVers)`.
4. **AG-Anteil SV** (employer SI is *additional* expense + own
   liability): `6110 (Soziale Aufwendungen)` an `3740 (Verb.
   SozVers)`.
5. **LSt + KiSt + Soli** (employee withholding): `3790` an
   `3730 (Verb. LSt)`.
6. (When paid:) `3720` / `3730` / `3740` an `1810 (Bank)`.

**Invariant**: after entries 1-5 land, the `3790` clearing
account is zero per [§11.18]. This is the parser-side
sanity-check.

### 4.3 — Sachzuwendung / geldwerter Vorteil split

Sachbezüge are doctrinally tricky: the typical kontor `:imputed-
income` component carries both a "+" side (the value flows into
gross for tax computation) and a "-" side (the employee actually
received the in-kind benefit, not cash). The standard wage type
`2480 – Sachbezug ST+SV frei` (LODAS 869) maps to:

- Expense: `6130 (steuerfrei)` or `6060 (steuerpflichtig)` an
  `3790`
- Net deduction: `3790` an the Sachzuwendung-Gegenkonto (often
  the same fixed-asset / inventory account the benefit came
  from, or a clearing account). [§11.21, §11.22]

The C2 posting builder ships these as a paired-debit-credit
under one `:transaction` row, marked `:compensation-component/
kind :imputed-income`, with the SKR account picked by
`:compensation-component/account-hint` (consumer-supplied per
ADR-071 P2-71-2 "opaque codes slot").

### 4.4 — Where the consumer plugs in their Lohnart catalog

The C2 `PostingBuilder` config map:

```clojure
{:wage-type-catalog
 ;; per-customer Lohnart-Nr → posting-builder hints
 {100 {:kind :base-wage         :account-hint :gehalt}
  101 {:kind :overtime          :account-hint :gehalt}
  200 {:kind :base-wage         :account-hint :lohn  :uom :hours}
  869 {:kind :imputed-income    :account-hint :sachbezug-frei}
  900 {:kind :bonus             :account-hint :freiwillig-st-pflichtig}
  9100 {:kind :pfaendung        :account-hint :verbindlichkeiten-pfaendung}
  ;; ...}
 :account-map
 ;; account-hint → SKR04 / SKR03 (per :entity/coa-variant)
 {:gehalt                     {:skr04 "6020" :skr03 "4124"}
  :lohn                       {:skr04 "6010" :skr03 "4120"}
  :soziale-aufwendungen       {:skr04 "6110" :skr03 "4130"}
  :freiwillig-st-pflichtig    {:skr04 "6060" :skr03 "4145"}
  :sachbezug-frei             {:skr04 "6130" :skr03 "4140"}
  :verb-lohn                  {:skr04 "3720" :skr03 "1740"}
  :verb-lohnsteuer            {:skr04 "3730" :skr03 "1741"}
  :verb-sozialversicherung    {:skr04 "3740" :skr03 "1755"}
  :verrechnung                {:skr04 "3790" :skr03 "1755"}
  :verbindlichkeiten-pfaendung {:skr04 "3791" :skr03 "1798"}}}
```

The `:wage-type-catalog` is **consumer-supplied** (Steuerberater
configures); kontor ships only the `:account-map` defaults +
inline docs. Mirrors ADR-005 / ADR-071 "no bundled rate
tables."

---

## §5 — HGB §249 parallel-ledger accruals

German GAAP requires three categories of `Rückstellungen`
(provisions for uncertain liabilities) that are *not* paid in
the current pay-period but accrue against future pay-outs.
**These are Handelsbilanz-only** (commercial-law book) in most
cases; the **Steuerbilanz** (tax book) either omits them or
uses a different valuation. This is exactly what ADR-021's
`:ledger/framework` parallel-ledger pattern enables:

- `:hgb-handelsbilanz` ledger accrues per HGB §249 / §253.
- `:de-steuerbilanz` ledger accrues per EStG / BFH method.
- Some accruals (e.g. simplified PTO) coincide; others diverge.

### 5.1 — Urlaubsrückstellung (PTO accrual)

**Legal basis**: HGB §249 Abs. 1 — "Rückstellungen sind für
ungewisse Verbindlichkeiten zu bilden". Untaken vacation at the
balance-sheet date is an existing liability under
Bundesarbeitsgericht jurisprudence (the employee earned the
vacation but did not consume it). [§11.23, §11.24]

**Formula (HGB-Handelsbilanz, full-employer-cost basis)**:

```
Rückstellung = (Gesamtaufwand Erfüllung Arbeitsverhältnis
              ÷ tatsächliche Arbeitstage)
              × offene Urlaubstage
```

Where `Gesamtaufwand` includes:
- Bruttogehalt (annualized)
- Voraussichtliche Erhöhungen (anticipated raises — HGB §253
  Abs. 1 S. 2)
- AG-Anteil zur SV (employer SI)
- Beiträge zur Berufsgenossenschaft (statutory accident
  insurance)
- Urlaubsgeld / 13. Monatsgehalt anteilig
- Other wage-dependent ancillary costs (overhead)

`tatsächliche Arbeitstage` = calendar workdays − public holidays
− vacation taken − expected sick days.

**Formula (Steuerbilanz, BFH simplified)**:

```
Rückstellung = (Jahresurlaubsentgelt ÷ regelmäßige Arbeitstage)
              × offene Urlaubstage
```

Where:
- `Jahresurlaubsentgelt` is base wage only (no AG-SV, no Urlaubsgeld).
- `regelmäßige Arbeitstage` = workdays - public holidays only
  (not subtracting vacation or sick days).
- In practice **250 Tage** for 5-day week. [§11.23, §11.25]

**Bookings** (per [§11.25] and the Haufe practical case):

- Year-end accrual (HGB ledger):
  - Debit: `6035 (SKR04) / 4960 (SKR03)` Aufwendungen
    Urlaubsrückstellung
  - Credit: `3066 (SKR04) / 0974 (SKR03)` Urlaubsrückstellung
- When the employee takes vacation in the next year:
  - Debit: `3066 / 0974` (release of provision)
  - Credit: `3720 / 1740` (Verb. Lohn/Gehalt) or directly `1810
    (Bank)` on payout.

**Discounting**: per HGB §253 Abs. 2, only if the residual term
is > 12 months. PTO is short-term (taken within 15 months per
BUrlG), so **no discounting** for the typical Mittelstand case.

**C2's algorithm** (simplified, fit-for-most-Mittelstand):

```clojure
(defn urlaubsrueckstellung-tx-data
  [{:keys [employments accrued-vacation-days as-of pay-rate-fn
           include-ag-sv? include-urlaubsgeld? framework]}]
  (mapv (fn [emp]
          (let [annual-gross    (pay-rate-fn emp :annual)
                ag-sv-rate      (if include-ag-sv? 0.21M 0M)
                urlaubsgeld     (if include-urlaubsgeld?
                                  (* annual-gross 0.05M) 0M)
                total-cost      (+ annual-gross
                                   (* annual-gross ag-sv-rate)
                                   urlaubsgeld)
                arbeitstage     (case framework
                                  :de-steuerbilanz 250
                                  :hgb-handelsbilanz 220) ;; minus expected vacation/sick
                tagessatz       (/ total-cost arbeitstage)
                rueckstellung   (* tagessatz
                                   (accrued-vacation-days emp))]
            {:transaction/...
             :posting/account [:account/path ...]
             :posting/amount  rueckstellung
             :ledger/framework framework
             :audit-doc/category :payroll-accrual
             ...}))
        employments))
```

The default `0.21` AG-SV factor is the rough Mittelstand
heuristic (~21% sum of KV+RV+AV+PV+UV+UV-Umlage employer
shares); consumers override per cohort. The 5% Urlaubsgeld
factor is also overridable.

### 5.2 — Weihnachtsgeld + 13. Monatsgehalt-Rückstellung

If the employer pays a Weihnachtsgeld in November/December that
covers the calendar year, the Rückstellung accrues monthly
through October. Same formula shape as PTO but driven by the
contractually-promised bonus amount, not the accrued days.

**Bookings** — same Konten (6060/4145 for the expense; 3066/0974
or a dedicated `3068 Weihnachtsgeldrückstellung` for the
provision). For HGB-Handelsbilanz only; Steuerbilanz typically
recognises Weihnachtsgeld when paid (cash basis) unless there
is a *legal obligation* (per EStG §5 Abs. 4). [§11.24, §11.26]

### 5.3 — Pensionsrückstellung (employer pension promises, bAV)

**Legal basis**: HGB §253 Abs. 2 + §249 Abs. 1. Valuation must
be by an actuarial method — choices:
- **Anwartschaftsbarwertverfahren** (PUC-equivalent) — HGB
  permitted, IFRS standard.
- **Anwartschaftsdeckungsverfahren** (similar to PUC) — HGB
  permitted, **EStG-only method allowed under §6a**.

Discount rate: **average market rate over past 10 fiscal years**
(per HGB §253 Abs. 2 S. 1). A simplified rule (§253 Abs. 2 S. 2)
allows assuming 15-year residual term. **The Steuerbilanz uses
the §6a EStG rate (6%)** — significant divergence from HGB.
[§11.26, §11.27, §11.28]

**C2 posture**: pensions are **out of scope** for the
substrate-level builder. The C2 module ships a stub:

```clojure
(defn pensionsrueckstellung-tx-data
  "Books a Pensionsrückstellung from a consumer-supplied actuarial
   valuation. kontor does NOT compute the actuarial valuation —
   that requires a versicherungsmathematisches Gutachten
   (actuary's report) per HGB §253. The consumer attaches the
   :audit-doc and supplies the valuation amount."
  [{:keys [employment as-of valuation-amount actuary-doc framework]}]
  ;; ... books amount against :pensionsrueckstellung account,
  ;;     links :audit-doc to the actuary's report.
  )
```

This keeps kontor's "no jurisdictional compute" posture intact;
actuarial valuations are domain-expert work, not substrate work.
A future `kontor-pension-actuary-de` module could integrate with
a Heubeck-Richttafeln-based engine, but that is post-C2.

### 5.4 — How this lands on kontor's `:ledger/framework`

```clojure
;; HGB-book accrual
{:transaction/narration "Urlaubsrückstellung 2026-12-31 (HGB)"
 :ledger/framework :de-handelsrecht
 :posting [{:account "6035" :amount 12000.00M :side :debit}
           {:account "3066" :amount 12000.00M :side :credit}]}

;; Steuerbilanz-book accrual (smaller, no AG-SV)
{:transaction/narration "Urlaubsrückstellung 2026-12-31 (Steuerbilanz)"
 :ledger/framework :de-steuerrecht
 :posting [{:account "6035" :amount 9800.00M :side :debit}
           {:account "3066" :amount 9800.00M :side :credit}]}
```

Both ledgers carry separate periodic balances; the HGB-Steuer-
delta (`12000 - 9800 = 2200`) is the *latente Steuern* (deferred
tax) input the Steuerberater consumes year-end. ADR-021's
parallel-ledger pattern makes this disposable; consumers who do
not need the Steuerbilanz can configure only one ledger.

---

## §6 — Personio / ADDISON / Sage / Lexware / sevDesk adapter conventions

The DE payroll adapter landscape has converged on a small set of
conventions. C2 inherits these.

### 6.1 — Personio + DATEV (the Mittelstand reference)

Personio is the dominant DE Mittelstand HRIS (per note 09 §3 +
note 72 §1.5). Its DATEV adapter shape:

- **Inbound to DATEV**: Personio generates a ZIP containing
  `stammdaten.txt` (master changes for the month) and
  `bewegungsdaten.txt` (variable inputs — overtime, bonuses,
  expenses). The user (Steuerberater) downloads the ZIP, opens
  LODAS, runs ASCII-Import per §2 above. [§11.7, §11.10]
- **Outbound from DATEV**: Personio currently does *not*
  consume the Buchungsbeleg — the Steuerberater handles that
  flow independently in DATEV-Rechnungswesen. Personio surfaces
  payroll PDFs via the Lohnauswertungsdatenservice.
- **Wage-type mapping**: Personio's UI lets the customer
  configure
  - a **default Bezug-ID** for fixed salaries (Personio default:
    LODAS `200`) [§11.29]
  - **custom-attribute-driven mappings**: arbitrary Personio
    attribute (e.g. "Cost Center") drives a per-attribute-value
    Bezug-ID lookup
  - **recurring compensations** with explicit DATEV wage-type
    numbers; Personio auto-routes based on `<9000 = Fixed
    table, >=9000 = Net table`. [§11.30, §11.31]
- **Mandatory employee attributes** per Personio's docs
  [§11.10]: DATEV Personalnummer, "Consider in German payroll",
  Krankenkassen-Typ, 11-char Steuer-Identifikationsnummer.

**Lesson for C2**: the per-customer wage-type catalog is
**unavoidable**; even Personio with hundreds of customers
cannot ship a "universal" mapping. C2's `:wage-type-catalog`
config map (§4.4) is the right shape.

### 6.2 — ADDISON / Sage / Lexware (DATEV-adjacent competitors)

- **ADDISON** (Wolters Kluwer): Lohn-Module ships its own
  Steuerkanzlei-bookkeeping flow; ADDISON-Lohn → DATEV bridges
  exist but are vendor-specific.
- **Sage HR / Sage Personalwirtschaft**: ships a DATEV-Export
  module that emits the same LODAS Importdatei shape. [§11.32]
- **Lexware Lohn+Gehalt**: SMB on-prem product; competes
  directly with DATEV LuG. Its DATEV-Schnittstelle exports the
  EXTF Buchungsstapel for handoff to a Steuerberater running
  DATEV-Rechnungswesen.
- **sevDesk Lohn**: smaller cloud play; the DATEV bridge is
  the same ZIP-of-TXT pattern Personio uses. [§11.33]

**Lesson for C2**: there is no single "DATEV LODAS adapter
spec"; every vendor wraps the same Importdatei + Buchungsbeleg
contract. kontor's adapter is differentiated by being
**substrate-pluggable** (any consumer can swap in their own
`PostingBuilder`), not by file-format novelty.

### 6.3 — Adapter-design lesson cluster

Cross-cutting observations from the surveyed vendors:

1. **Outbound master data is event-driven, not full-snapshot.**
   When a new hire / salary change / termination happens,
   ship the delta in `[Stammdaten]`. Full-snapshot exports
   exist but are operational (re-base) tools, not the steady
   state.
2. **Outbound variable inputs are monthly batches.** Personio
   ships `bewegungsdaten.txt` once per month around day-of-
   month 5-10, after the prior month's hour-tracking is
   finalized.
3. **Inbound GL postings (Buchungsbeleg) are monthly snapshots,
   not deltas.** Each pay-period's Buchungsbeleg is the complete
   set of postings for that period; re-importing is a re-
   computation of the same period, not an addendum.
4. **Roundtrip idempotency**: a Buchungsbeleg generated twice
   produces identical posting amounts (within DATEV's rounding
   discipline). kontor's `:cross-tx/step-id` (ADR-074) shapes
   onto this: the step-id for a payroll-import is `<mandant>-
   <pay-period>-<buchungsbeleg-hash>` and re-import is a no-op.
5. **Wage-type catalog drift is the #1 customer pain.** A
   wage-type added in LODAS by the Steuerberater but not
   mapped in the consumer's catalog produces an "unmapped wage
   type" warning. C2 ships an `:unmapped-wage-types` slot in
   `PayrollFacts` so the consumer can route to a manual-review
   queue (mirroring `kontor.bank_csv`'s un-categorized-line
   handling).

---

## §7 — DE payroll component vocabulary

The open-set enum kontor accepts on `:compensation-component/kind`
for DE. **kontor itself does not enforce these values**; per
note 79 §2.4 + §81 §9.6, `:compensation-component/kind` is an
open-set keyword. The list below is what the C2 module ships
as a documented vocabulary + a `posting-builder` mapping.

| `:kind` | German term | Account-hint | st-pflicht | sv-pflicht | Own SKR row? |
|---|---|---|---|---|---|
| `:base-wage` | Bruttolohn (hourly) | `:lohn` | ja | ja | yes — 6010 / 4120 |
| `:base-salary` | Bruttogehalt (monthly) | `:gehalt` | ja | ja | yes — 6020 / 4124 |
| `:overtime` | Überstundenvergütung | `:gehalt` | ja | ja | rolls up — same row as `:base-*` |
| `:weihnachtsgeld` | Weihnachtsgeld (13. ME) | `:freiwillig-st-pflichtig` | ja | ja | own row — 6060 / 4145 |
| `:urlaubsgeld` | Urlaubsgeld (14. ME) | `:freiwillig-st-pflichtig` | ja | ja | own row — 6060 / 4145 |
| `:bonus-target` | leistungsbezogener Bonus | `:freiwillig-st-pflichtig` | ja | ja | own row |
| `:vwl` | Vermögenswirksame Leistungen | `:freiwillig-st-pflichtig` | ja | nein (bis Höchstgrenze) | own row |
| `:imputed-income-tax-exempt` | Sachzuwendung st+sv-frei (Bezugskarte ≤50€) | `:sachbezug-frei` | nein | nein | own row — 6130 / 4140 |
| `:imputed-income-taxable` | Sachbezug st-pflichtig (Dienstwagen 1%-Regelung) | `:freiwillig-st-pflichtig` | ja | ja | own row — 6060 / 4145 |
| `:employer-si` | AG-Anteil SozVers (KV+RV+AV+PV+UV) | `:soziale-aufwendungen` | n/a | n/a | own row — 6110 / 4130 |
| `:employee-si` | AN-Anteil SozVers (Lohn-Reduktion) | n/a (contra `:verb-sozialversicherung`) | n/a | n/a | liability — 3740 / 1755 |
| `:withholding-tax` | LSt + KiSt + Soli | n/a (contra `:verb-lohnsteuer`) | n/a | n/a | liability — 3730 / 1741 |
| `:bav-direktversicherung` | bAV — Direktversicherung (§3 Nr.63 EStG bis 4% BBG steuerfrei) | `:freiwillig-st-pflichtig` (mixed) | abh. | abh. | own row + `:audit-doc` |
| `:kurzarbeitergeld` | Kurzarbeitergeld (BA-Zuschuss) | n/a (contra liability `1762 Forderung BA`) | nein | n.s.v. | own contra — passes through |
| `:pfaendung` | Pfändung (3. Person / 9. ZPO §850c) | n/a (contra `:verb-pfaendung`) | n/a | n/a | own row — 3791 / 1798 |
| `:net-deduction` | sonstiger Netto-Abzug | n/a (consumer-supplied contra) | nein | nein | consumer-supplied |
| `:net-addition` | sonstige Netto-Zuwendung | consumer-supplied | nein | nein | consumer-supplied |

**Notes**:

- "Own SKR row" = the posting builder emits a distinct expense /
  liability line (not just amount on the gross). Multi-row
  expense is what lets the Steuerberater run BWA per
  Kostenstelle and per Lohnart.
- `:bav-direktversicherung` complexity: up to 4% of the
  Beitragsbemessungsgrenze (BBG) is **lohnsteuerfrei und
  sozialversicherungsfrei** (§3 Nr.63 EStG / §1 Abs.1 Nr.9
  SvEV); the next 4% (8% total) is only lohnsteuerfrei but
  sozialversicherungspflichtig. The C2 builder splits a single
  bAV component into two postings when the amount crosses the
  threshold. The threshold itself is BBG-driven and changes
  annually — **consumer supplies the BBG value**; kontor does
  not bundle it (mirrors ADR-005 / ADR-071).
- `:kurzarbeitergeld` is special: the employer pays it on behalf
  of the Bundesagentur für Arbeit and is reimbursed via SV-
  Träger settlement. Goes through `1762 Forderungen BA`.
- `:pfaendung` requires the Pfändungsbeschluss as `:audit-doc`
  with `:audit-doc/category :legal-hold-instrument` (per ADR-
  051 + note 79 §2.5 category axis).

### 7.1 — Vocabulary maintenance

This vocabulary is the **C2-shipping** subset; consumers can
add `:reisekosten-vergütung`, `:fahrtkostenzuschuss`,
`:essensgutschein`, `:jobticket`, `:internetpauschale`, etc.
as needed. The `:wage-type-catalog` config (§4.4) is the
extension seam — consumers map their LODAS Lohnart numbers to
either the C2-shipped kinds or their own.

---

## §8 — `PayrollEmitProvider` scope for DE

The note 79 §2.4 fourth protocol (jurisdictional event-bus
emission) needs DE-specific scoping. The DE jurisdictional event
bus is rich: DEÜV-Meldungen, GKV-Monatsmeldung, SV-
Beitragsnachweis, ELStAM, A1-Bescheinigungen, Berufsgenossen-
schafts-Meldung, Statistiken (Lohnsteueranmeldung) … all of
which **DATEV LODAS itself emits to gkv-datenaustausch.de / SV-
Meldeportal / Finanzamt**. [§11.34, §11.35]

### 8.1 — What stays inside DATEV's appliance

Per the DATEV-RZ-as-clearing-house posture [§11.11], **all the
jurisdictional emissions stay inside DATEV**. The C2
`EmitProvider` does *not* re-implement these; it does not need
the SV-Träger XML schemas, the ELStAM-Anmeldung XML, the
DEÜV-Meldegrund codes, or the gkv-datenaustausch.de endpoint
URLs.

Specifically: the C2 EmitProvider does **NOT** emit:

- DEÜV-Meldungen (An-, Ab-, Jahres-, Sofort-, Unterbrechungs-
  Meldungen) to SV-Trägern
- GKV-Monatsmeldung (Reason 58)
- SV-Beitragsnachweis (monthly)
- ELStAM-Anmeldung / -Abmeldung (Steuerklasse + Kinder)
- A1-Bescheinigungen (cross-border SV-Anwendbarkeit)
- Berufsgenossenschafts-Meldungen
- Lohnsteueranmeldung (LStA) to Finanzamt
- DAKOTA-/sv.net-/SV-Meldeportal-Direktmeldungen

These remain inside the DATEV appliance, triggered automatically
by LODAS after the pay-period close.

### 8.2 — What C2 EmitProvider DOES emit

The single inbound payload to LODAS: **the LODAS Importdatei**.
Per §2:

- `[Stammdaten]` rows for: new hires, salary changes,
  termination, address changes, bank-account changes, tax-class
  changes, child-allowance changes.
- `[Bewegungsdaten]` rows for: pay-period overtime hours,
  one-off bonuses (Weihnachtsgeld, signing bonus, retention
  bonus), reimbursements (Reisekosten, Bewirtung — usually
  routed via the expense companion), Sachzuwendungen, retro
  corrections from prior pay-periods, garnishment payouts.

The emission is **per-pay-period** (typically monthly); the
output is one TXT (or a ZIP containing `stammdaten.txt` +
`bewegungsdaten.txt`) per month. Default delivery is the
`LocalfileEmitProvider` (per note 79 §4 default) — writes to a
local directory and surfaces as `:audit-doc` for the consumer
to manually upload to DATEV-RZ; future enhancement is a
`DatevOnlineEmitProvider` using `Lohnimportdatenservice` Online
API (per [§11.36]), but that requires OAuth2 + per-customer
DATEV-RZ credentials, which is **out of C2 scope**.

### 8.3 — Inbound reconciliation: re-importing the Buchungsbeleg

After LODAS produces the Buchungsbeleg, the C2 ComputeProvider
parses it (§3.4) and `PayrollFacts` are reconciled against the
prior `[Bewegungsdaten]` emission via `:cross-tx/step-id`
(ADR-074). The reconciliation:

1. Compute the SHA-256 of the prior `bewegungsdaten.txt`.
2. Pull the Buchungsbeleg; extract per-employee gross + net.
3. Verify each employee's `:payroll-facts.gross` matches the
   expected sum from the Bewegungsdaten the EmitProvider
   pushed.
4. If mismatch: surface `:payroll-run/state :reconciliation-
   failed` with the per-employee delta.
5. If match: `:payroll-run/state :reconciled`; the
   `PostingBuilder` runs against the verified `:payroll-facts`
   to build the GL postings.

This makes C2 a **closed loop**: the emission is hash-pinned to
the prior input, so a Steuerberater editing in LODAS without
informing the kontor consumer will surface as a reconciliation
failure rather than a silent drift.

---

## §9 — Concrete impl recommendations for C2

### 9.1 — File tree

```
modules/payroll-de-datev/
├── README.md
├── deps.edn                     ; deps on kontor + kontor-hr + l10n-de
├── src/
│   └── kontor/
│       └── payroll/
│           └── de/
│               └── datev/
│                   ├── core.clj             ; install! + register-providers!
│                   ├── lodas_format.clj     ; LODAS Importdatei encoder / parser
│                   ├── extf_buchungsbeleg.clj ; EXTF Buchungsbeleg parser
│                   ├── compute_provider.clj ; DatevLodasComputeProvider
│                   ├── posting_builder.clj  ; DatevLodasPostingBuilder (SKR04+SKR03)
│                   ├── emit_provider.clj    ; LocalfileLodasEmitProvider
│                   ├── wage_type_catalog.clj; consumer-config validation
│                   ├── hgb249/
│                   │   ├── urlaub.clj       ; Urlaubsrückstellung builder
│                   │   ├── weihnachtsgeld.clj ; Weihnachtsgeld-Rückstellung builder
│                   │   └── pension.clj      ; pension-provision stub (consumer-supplied valuation)
│                   └── reconciliation.clj   ; cross-tx step-id + Buchungsbeleg-vs-Bewegungsdaten check
├── resources/
│   └── kontor/payroll/de/datev/
│       ├── account_map_skr04.edn   ; account-hint → SKR04 code
│       ├── account_map_skr03.edn   ; account-hint → SKR03 code
│       └── example_lodas_import.txt ; fixture for tests
└── test/
    └── kontor/payroll/de/datev/
        ├── lodas_format_test.clj
        ├── extf_buchungsbeleg_test.clj
        ├── compute_provider_test.clj
        ├── posting_builder_test.clj
        ├── emit_provider_test.clj
        ├── hgb249_urlaub_test.clj
        ├── reconciliation_test.clj
        └── e2e_de_gmbh_payroll_test.clj  ; the cross-stage user-story validation
```

### 9.2 — Wage-type catalog data shape (EDN sketch)

```clojure
;; resources/kontor/payroll/de/datev/example_wage_type_catalog.edn
;; A worked-example catalog for the e2e test; consumer ships their own.
{:catalog/version   1
 :catalog/mandant   "99999"
 :catalog/berater   "1234"
 :catalog/coa       :skr04
 :catalog/wage-types
 {;; --- Bezug (gross-side; <9000) ---
  100  {:kind :base-salary           :account-hint :gehalt}
  101  {:kind :overtime              :account-hint :gehalt}
  200  {:kind :base-wage             :account-hint :lohn      :uom :hours}
  300  {:kind :weihnachtsgeld        :account-hint :freiwillig-st-pflichtig}
  301  {:kind :urlaubsgeld           :account-hint :freiwillig-st-pflichtig}
  400  {:kind :vwl                   :account-hint :freiwillig-st-pflichtig}
  500  {:kind :bav-direktversicherung
        :account-hint :freiwillig-st-pflichtig
        :bbg-rule :de-3-nr-63-estg}
  869  {:kind :imputed-income-tax-exempt :account-hint :sachbezug-frei}
  870  {:kind :imputed-income-taxable    :account-hint :freiwillig-st-pflichtig}

  ;; --- Netto-Bezug / Netto-Abzug (>=9000) ---
  9050 {:kind :pfaendung
        :account-hint :verbindlichkeiten-pfaendung
        :requires-audit-doc-category :legal-hold-instrument}
  9100 {:kind :net-deduction
        :account-hint :sonstige-verbindlichkeiten}}
 :catalog/employer-contributions
 {;; Implicit components computed by LODAS, surfaced in Buchungsbeleg as 6110/4130
  :employer-si             {:account-hint :soziale-aufwendungen}
  :berufsgenossenschaft    {:account-hint :soziale-aufwendungen}
  :u1-u2-umlage            {:account-hint :soziale-aufwendungen}}}
```

### 9.3 — Test fixture: hire-with-DE-master-data

```clojure
;; test/kontor/payroll/de/datev/e2e_de_gmbh_payroll_test.clj
(deftest e2e-de-gmbh-monthly-payroll
  (let [conn (kontor.core/create-test-db)
        _    (kontor.l10n-de.chart/install! conn) ; SKR04
        _    (kontor.hr.core/install! conn)
        _    (kontor.payroll.de.datev.core/install! conn)
        ;; -- hire --
        person  (hr/hire-person! conn
                  {:given-name "Franz" :family-name "Müller"
                   :birth-date #inst "1980-02-23"
                   :national-id-doc <audit-doc-with-pii-marker>})
        emp     (hr/create-employment! conn
                  {:person person :entity acme-de-gmbh
                   :start-date #inst "2025-11-01"
                   :job-title "Software Engineer"})
        comp    (hr/create-compensation! conn
                  {:employment emp :effective-from #inst "2025-11-01"
                   :commodity :eur
                   :components [{:kind :base-salary :amount 4000.00M :period :monthly}]})
        ;; -- emit Stammdaten + Bewegungsdaten to LODAS for Nov 2025 --
        emit-result (datev/emit-payroll-events!
                      conn
                      {:pay-period #inst "2025-11-01"
                       :entity acme-de-gmbh
                       :variable-inputs {emp [{:wage-type 100 :amount 4000.00M}]}})
        ;; -- (simulating LODAS run: Steuerberater imports, runs gross-to-net,
        ;;     exports Buchungsbeleg)
        buchungsbeleg-bytes (slurp "test-fixtures/buchungsbeleg-11-2025.csv"
                                   :encoding "ISO-8859-1")
        ;; -- consume Buchungsbeleg back into kontor --
        run    (datev/import-buchungsbeleg!
                  conn {:bytes buchungsbeleg-bytes :pay-period emit-result})]
    (is (= :reconciled (:payroll-run/state run)))
    (is (= 4000.00M (-> run :payroll-run/payroll-facts first :gross)))
    (is (= 2500.00M (-> run :payroll-run/payroll-facts first :net)))
    ;; assert the SKR04 postings landed
    (is (= 1 (count (q-postings-against conn "6020" #inst "2025-11-01"))))
    (is (= 1 (count (q-postings-against conn "6110" #inst "2025-11-01"))))
    (is (= 1 (count (q-postings-against conn "3720" #inst "2025-11-01"))))
    (is (= 1 (count (q-postings-against conn "3730" #inst "2025-11-01"))))
    (is (= 1 (count (q-postings-against conn "3740" #inst "2025-11-01"))))
    ;; ledger framework: HGB-handelsbilanz only (no Steuerbilanz this period)
    (is (every? #{:de-handelsrecht} (map :ledger/framework (q-postings conn))))))
```

### 9.4 — Known gotchas

1. **The existing `skr04.edn` is mis-labelled** (carries SKR03
   numbers; see §4 head). Either fix in place or shadow.
   Without the fix, the SKR04 posting builder will reference
   accounts the chart doesn't have.
2. **ISO-8859-1 vs UTF-8 ingest**. Customers will occasionally
   send UTF-8-encoded `bewegungsdaten.txt` files. The parser
   must detect (BOM, invalid Latin-1 byte sequences) and reject
   with a clear error, not produce corrupted Umlaute.
3. **CR/LF vs LF**. LODAS rejects LF-only. The encoder must
   force CR/LF; the parser must accept either (LODAS exports
   are CR/LF; some Linux-side tools rewrite to LF in transit).
4. **The 11-character Steueridentifikationsnummer checksum**. We
   accept the number as a string; do not bundle the checksum
   algorithm. A future `kontor-l10n-de` enhancement could expose
   `valid-steueridentifikationsnummer?` as a helper.
5. **The `betriebliche Personalnummer` vs `DATEV Personalnummer`
   axis**. Customers using `BetrieblichePNrVerwenden=Ja` need
   to round-trip on the betriebliche-Pnr; the Buchungsbeleg
   carries the DATEV-Pnr in `Belegfeld 1`. The parser needs
   the catalog config to know which axis is in use, and the
   Stammdaten emitter needs to emit the betriebliche-Pnr in
   `pnr_betriebliche#psd` rather than `pnr#psd`.
6. **Personio's Bezug-Nr range convention (<9000 vs >=9000)**.
   This is a Personio convention layered on top of DATEV's
   range; kontor's `:wage-type-catalog` MUST validate that
   `:kind :base-*` or `:bonus` etc. land on <9000 and that
   `:net-deduction` / `:net-addition` land on >=9000. Otherwise
   the resulting Bewegungsdaten will route to the wrong LODAS
   table.
7. **Sachbezug 50€ Freigrenze monitoring**. The §8 Abs. 2 EStG
   monthly 50€ tax-free Sachbezug threshold is a **monthly
   cumulative** check. Crossing it converts the entire monthly
   amount to taxable. C2 does not enforce; the consumer must
   route via the catalog. Document the gotcha prominently.
8. **HGB §249 PTO accrual**: simplified formula in §5.1 is for
   the typical Mittelstand. Larger employers will need a more
   sophisticated calculator (e.g. cohort-weighted average wage,
   per-employee individual calculation). The C2 implementation
   should be a *default* that consumers can replace via a
   protocol-level extension; do not lock it down.
9. **Pensionsrückstellungen are out-of-scope.** The §5.3 stub
   merely books a consumer-supplied amount. The actuarial
   computation (Heubeck-Richttafeln, demographic assumptions,
   discount rate per 10-year-average) is its own world; a
   future `kontor-pension-actuary-de` companion is the right
   place.
10. **Holiday calendar drift**. The Urlaubsrückstellung formula
    uses "regelmäßige Arbeitstage" — which depends on the
    Bundesland (Bayern has 13 public holidays, Berlin has 10).
    C2's PTO accruer needs a `:bundesland` parameter; the
    holiday catalog stays consumer-supplied (do not bundle).
11. **LODAS vs Lohn und Gehalt**. §2.6 — the C2 module should
    support both `:datev-target :lodas` and `:datev-target :lug`
    as EmitProvider config; the lone difference is the
    `Ziel=LODAS` vs `Ziel=LuG` line and the
    `[Satzbeschreibung]` table-name namespace. Cheap; do not
    defer.
12. **The Buchungsbeleg "3790 balances to zero" invariant**.
    If a Buchungsbeleg arrives with non-zero 3790 balance, it
    is corrupt. The parser must refuse rather than silently
    book; surface as `:payroll-run/state :buchungsbeleg-
    invalid`.

---

## §10 — Open questions for impl-time clarification

These are NOT settled by this note; the impl agent should
raise them after C1 lands and before C2 ships.

1. **Re-using `kontor.l10n-de.datev/export-buchungsstapel` for
   round-trip testing?** The existing exporter is unidirectional
   (kontor → DATEV). For C2 we need a parser (DATEV → kontor).
   Should we share encoders/decoders via a thin
   `kontor.l10n-de.extf` namespace, or keep them parallel for
   isolation? Recommend: split `kontor.l10n-de.extf/encode!` +
   `kontor.l10n-de.extf/parse` symmetric API; both directions
   share the same column-list and ISO-8859-1 + CR/LF discipline.
2. **`:wage-type-catalog` as resource vs database?** The
   catalog drifts (new wage types added every year by the
   Steuerberater). Should kontor store it as a `:wage-type-
   catalog` entity in the DB (bitemporal, audit-doc-attachable)
   or as EDN-on-disk? Recommend: DB entity, with the EDN as a
   bulk-loader for the initial install.
3. **The `:datev-target` LODAS-vs-LuG split**. If consumer A
   uses LODAS and consumer B uses LuG, do we ship one module
   or two? Recommend: one module with the target as config;
   the format-overlap is high.
4. **Cross-employment payroll for the same Person**. note 79's
   multi-employment + the trans-national scenario (DE GmbH +
   US LLC). A single Person could have a DE-LODAS payroll and
   a US-ADP payroll concurrently. Does the C2 ComputeProvider
   know about the US side? Recommend: no — each provider sees
   only its employments via `:employment/entity` scoping.
5. **Mid-year onboarding scenario**. Per note 79 / [[69-lease]],
   mid-life imports are a substrate concern. Does C2 need a
   `mid-year-onboarding-tx-data` helper? Recommend: defer; the
   substrate's bitemporal `:db.valid/from` + the existing
   process discipline should suffice.
6. **Test-only RZ-credentials stub**. Should C2 ship a
   `:fake-datev-rz` test backend that simulates the RZ side?
   Useful for cross-stage testing. Recommend: yes, as a
   test-only fixture (not in `src/`).
7. **Lohnauswertungsdatenservice (PDF) integration**. The PDF
   per-employee Lohnauswertung is what employees expect to see.
   Should C2 surface it as `:audit-doc`? Recommend: yes — when
   the Buchungsbeleg is parsed, the consumer can attach the
   per-employee PDFs as `:audit-doc/category :payroll-document`
   with `:audit-doc/privilege :pii-payroll` per ADR-051 + note
   79 §2.5.

---

## §11 — Sources

All URLs accessed 2026-05-18.

**DATEV LODAS / Lohn und Gehalt file format**:

- [11.1] DATEV — *SSH Schnittstellenhandbuch LODAS*, 45.
  Auflage (Januar 2016). Distributed inside the LODAS install
  at `\PROGRAMM\LODAS\Handbuch\Deutsche Version\LODAS_SSH.pdf`;
  also at <https://www.datev.de/dnlexom/v2/content/files/st36028834085966347_de.pdf>
  (newer 2025-06 edition; HTTP 404 from public web today; the
  2010 yumpu mirror at [11.9] preserves the structural
  catalog).
- [11.2] DATEV — *SSH Schnittstellenhandbuch LODAS* (alternate
  help-center entity), <https://help-center.apps.datev.de/api/amr/knowledge-common/v1/entities/st54043232595448331_de.pdf>
  (HTTP 404 at access time; cited per search index).
- [11.3] DATEV-Community thread on Stammdaten import: *"Lodas
  Import Stammdaten: Stundenlohn 1"* (post 364028),
  <https://www.datev-community.de/t5/Personalwirtschaft/Lodas-Import-Stammdaten-Stundenlohn-1/td-p/364028>
  — example file content with `[Allgemein]` /
  `[Satzbeschreibung]` / `[Stammdaten]` sections.
- [11.4] DATEV-Community thread on Bewegungsdaten import:
  *"Lodas - Bewegungsdaten - Excel"* (post 96753),
  <https://www.datev-community.de/t5/Personalwirtschaft/Lodas-Bewegungsdaten-Excel/td-p/96753>
  — example with `u_lod_bwd_buchung_standard`.
- [11.5] amic.de — *DATEV ASCII-Schnittstelle*,
  <https://www.amic.de/hilfe/datevasciischnittstelle.htm>
  (semicolon delimiter, CR/LF, ISO-8859-1 encoding).
- [11.6] amic.de — *DATEV-Import Lohndaten*,
  <http://amic.de/hilfe/datevimportlohndaten.htm>.
- [11.7] saxess-software — *DATEV LODAS/Lohn & Gehalt-Anbindung
  mit manuellem Datenexport*, <https://help.saxess-software.de/oct-best-practice/v1/datev-lodas-lohn-gehalt-anbindung-mit-manuellem-da>
  (stammdaten.txt, bewegungsdaten.txt, lohnarten.txt filename
  convention).
- [11.8] Circula — *Exports for DATEV LODAS and Lohn- und
  Gehalt*, <https://help.circula.com/en/articles/279484-exports-for-datev-lodas-and-lohn-und-gehalt>
  (zip-of-txt bundle convention, LuG vs LODAS).
- [11.9] yumpu — *LODAS Felder zur Bestückung der Import-Datei*
  (25. Auflage, Mai 2010),
  <https://www.yumpu.com/de/document/view/21211842/lodas-felder-zur-bestuckung-der-import-datei>
  — the canonical Personal-Stammdaten + Bewegungsdaten table
  catalog (`u_lod_psd_*`, `u_lod_bwd_*`).
- [11.10] Personio Support — *FAQ: DATEV integration* and
  *Attributes for the DATEV integration*,
  <https://support.personio.de/hc/en-us/articles/360005769118-FAQ-DATEV-integration>,
  <https://support.personio.de/hc/en-us/articles/360013652158-Attributes-for-the-DATEV-integration>
  (mandatory attributes incl. 11-char Steuer-ID).
- [11.11] dasfinanzen.de — *Was ist der Unterschied zwischen
  LODAS und Lohn und Gehalt?*,
  <https://dasfinanzen.de/was-ist-der-unterschied-zwischen-lodas-und-lohn-und-gehalt>
  (LODAS = RZ-Abrechnung; LuG = Vor-Ort).
- [11.12] DATEV — *DATEV Lohnauswertungsdatenservice*
  product page,
  <https://www.datev.de/web/de/datev-shop/personalwirtschaft/lohnauswertungsdatenservice/>
  (output is PDF / ZIP).
- [11.13] DATEV — *Lohnauswertungsdatenservice als Online-API*
  (Hilfe-Center document 1019845, page shows only header in
  public fetch; per search index).
- [11.14] DATEV Help-Center — *Buchungsbeleg erstellen und
  übergeben* (document 9232377) +
  <https://docplayer.org/218064364-Buchungsbeleg-erstellen-und-uebergeben-fenster.html>
  (Report 80 = Buchungsbeleg; export format "DATEV-Format 7.0"
  = EXTF Buchungsstapel).
- [11.15] BuchhaltungsButler — *Gehalt und Lohn buchen — mit
  Beispiel*, <https://www.buchhaltungsbutler.de/wiki/lohn-gehalt-buchen/>
  — SKR04 + SKR03 wage-account map; Bruttomethode booking
  sequence.
- [11.16] rechnungswesen-info.de — *Buchen von Lohnabrechnungen
  und Zahlungen*, <https://www.rechnungswesen-info.de/buchungen_lohn.html>
  — standard SKR04 Bruttomethode sequence; 3790 clearing
  account; invariant "Saldo 0".
- [11.17] Kontolino! — *Personalaufwendungen verbuchen*,
  <https://www.kontolino.de/arbeitshilfen/kontierungslexikon/personalaufwendungen/>
  — SKR04 6010 / 6020 / 6110 / 6072 listing; SKR03
  4110 / 4124 / 4130 / 4152 parallel.
- [11.18] BuchhaltungsButler — *Lohnbuchhaltung in
  BuchhaltungsButler erfassen*,
  <https://wissen.buchhaltungsbutler.de/hc/de/articles/11282286368541-Lohnbuchhaltung-in-BuchhaltungsButler-erfassen>
  — 3790 Saldo-0 invariant.
- [11.19] buchungssatz.de — *SKR04 — 3730 Verbindlichkeiten
  aus Lohn- und Kirchensteuer*,
  <https://www.buchungssatz.de/de_DE/konto/skr04/3730.html>.
- [11.20] buchungssatz.de — *SKR04 — 3740 Verbindlichkeiten im
  Rahmen der sozialen Sicherheit*,
  <https://www.buchungssatz.de/de_DE/konto/skr04/3740.html>.

**Wage-type catalog (DATEV Standardlohnarten)**:

- [11.21] sachbezugsteuerfrei.de — *Sachbezug Lohnarten korrekt
  buchen — DATEV, SKR03 + SKR04*,
  <https://www.sachbezugsteuerfrei.de/sachbezug-lohnarten-skr03-skr04-datev/>
  (LODAS 869 = Sachbezug st/sv-frei; LuG 2480 = same).
- [11.22] sachbezugkarte.de — *Sachbezugskarte Lohnabrechnung
  — u.a. mit DATEV Lohnarten*,
  <https://www.sachbezugkarte.de/sachbezugskarte-lohnabrechnung-lohnarten/>.

**HGB §249 / §253 — Rückstellungen**:

- [11.23] Haufe — *Urlaubsrückstellung nach HGB und Steuerrecht
  — die zu ermittelnden Eckdaten*,
  <https://www.haufe.de/finance/jahresabschluss-bilanzierung/urlaubsrueckstellung/die-zu-ermittelnden-eckdaten_188_290250.html>
  — HGB §253 valuation; HGB-vs-Steuerbilanz formulae.
- [11.24] hrworks.de — *Urlaubsrückstellung — wie sie berechnet
  und gebildet wird*,
  <https://www.hrworks.de/lexikon/urlaubsrueckstellung/>
  — HGB §249 legal basis; BFH-method 250 days.
- [11.25] Haufe — *Urlaubsrückstellung — Rückstellungsbetrag
  berechnen und buchen*,
  <https://www.haufe.de/finance/jahresabschluss-bilanzierung/urlaubsrueckstellung/den-rueckstellungsbetrag-berechnen-und-buchen_188_290252.html>
  — SKR04 6035 / 3066 booking sequence.
- [11.26] welt-der-bwl.de — *Pensionsrückstellungen (HGB)*,
  <https://welt-der-bwl.de/Pensionsr%C3%BCckstellungen> — HGB
  §253 Abs. 2 discount rule.
- [11.27] BPS Bayern — *Pensionsrückstellung — Bewertung nach
  §§ 249, 253 HGB und BilMoG*,
  <https://www.bps-online.bayern/bewertung-hgb.html>
  — Anwartschaftsbarwertverfahren vs Anwartschafts-
  deckungsverfahren.
- [11.28] BBH — *Pensionsrückstellungen — Herausforderungen
  bei der Bilanzierung und Bewertung*,
  <https://www.bbh-blog.de/alle-themen/pensionsrueckstellungen-herausforderungen-bei-der-bilanzierung-und-bewertung/>.

**Personio / vendor adapter shapes**:

- [11.29] Personio Support — *Setting up the export of fixed
  salaries with the DATEV integration*,
  <https://support.personio.de/hc/en-us/articles/360014368917-Setting-up-the-export-of-fixed-salaries-with-the-DATEV-integration>
  (default Bezug-ID 200; global vs custom mapping).
- [11.30] Personio Support — *Export of recurring compensations
  with the DATEV integration*,
  <https://support.personio.de/hc/en-us/articles/15855384565277-Export-of-recurring-compensations-with-the-DATEV-integration>
  (<9000 = Fixed table, >=9000 = Net table).
- [11.31] Personio Community — *Verschiedene Lohnarten von
  Personio an DATEV Lodas*,
  <https://community.personio.de/gehalt-lohnbuchhaltung-23/verschiedene-lohnarten-von-personio-an-datev-lodas-3643>.
- [11.32] Sage — *Sage HR / Sage Personalwirtschaft DATEV-
  Schnittstelle* (vendor docs; the Sage Personalwirtschaft
  module ships its own DATEV-LODAS-compatible exporter).
- [11.33] sevDesk — *Lohnbuchhaltung Hilfe* (DATEV bridge as
  ZIP-of-TXT; ref: hilfe.sevdesk.de Knowledge Base).

**DEÜV / GKV / ELStAM (event-bus emissions DATEV handles)**:

- [11.34] gkv-datenaustausch.de — *Datenerfassungs- und
  -übermittlungsverordnung (DEÜV)*,
  <https://www.gkv-datenaustausch.de/arbeitgeber/deuev/deuev.jsp>
  — official DEÜV catalog.
- [11.35] Paychex — *GKV-Monatsmeldung und SV-Beitragsnachweis
  im Überblick*,
  <https://www.paychex.de/wissenswertes/lohnabrechnung-updates/gkv-monatsmeldung-und-sv-beitragsnachweis>
  (SV-Meldeportal replaced sv.net as of 2024-06-30).
- [11.36] DATEV Developer Portal — *Lohnimportdatenservice
  Online API* (high-level: OAuth2 + JSON; per
  [developer.datev.de](https://developer.datev.de/) — public
  endpoint redirects; for C2's LocalfileEmitProvider the API
  is out of scope).

**kontor anchors**:

- `/home/christian-weilbach/Development/kontor/CLAUDE.md`
  (project posture, ADR-001 license, "no bundled rate tables").
- `/home/christian-weilbach/Development/kontor/doc/research/79-hr-payroll-stage-r-plan.md`
  §5.1 (C2 scope), §2.4 (PayrollProvider triple), §6 (HGB
  parallel-ledger requirement).
- `/home/christian-weilbach/Development/kontor/doc/research/81-hr-data-model-gold-standards.md`
  §9.6 (`:compensation-component` refactor — components as
  entities, the §7 vocabulary lands as `:compensation-
  component/kind`).
- `/home/christian-weilbach/Development/kontor/doc/research/72-hr-payroll-reference-study.md`
  §1.5 (Personio-DATEV adapter shape).
- `/home/christian-weilbach/Development/kontor/modules/l10n-de/src/kontor/l10n_de/datev.clj`
  (canonical EXTF Buchungsstapel encoder — directly reusable
  for the Buchungsbeleg parser; same ISO-8859-1, CR/LF,
  122-column, schema-510-v21 discipline).
- `/home/christian-weilbach/Development/kontor/modules/l10n-de/src/kontor/l10n_de/chart.clj`
  + `/home/christian-weilbach/Development/kontor/modules/l10n-de/resources/kontor/l10n_de/skr04.edn`
  (SKR04 chart — currently mis-labelled with SKR03 numbers;
  fix needed before C2).
- ADRs consumed: 001 (license posture), 005 (no bundled rate
  tables, superseded by 071), 014 (period), 021 (`:ledger/
  framework`), 031 (`:posting/entity`), 034 (status-machine
  for `:payroll-run/state`), 037 (per-stage rhythm), 038
  (audit-doc + approval), 049 (legal-hold for Pfändung),
  050 (retention), 051 (privilege + new `:category` axis),
  052 (DSAR), 067 (process), 068 (`*-tx-data`), 071
  (TaxRateProvider/TaxFacts/TaxPostingBuilder — **shape
  mirrored**), 072 (FxRateProvider), 074 (`:cross-tx/step-id`
  for Buchungsbeleg reconciliation).

**License posture summary**. DATEV LODAS Schnittstellenhandbuch
+ EXTF Buchungsstapel are **public specifications** — DATEV
publishes them as a vendor cooperative standard for customer
interoperability. SKR04 + SKR03 chart structures are facts
(account numbers + names) not subject to copyright in EU.
Personio + Circula + Sage + sevDesk + Lexware adapter shapes
are read from **public vendor documentation** for *pattern*,
never their code. No bundled DATEV wage-type catalog, no
bundled BBG rate, no bundled SV-Beitragssatz table — all
consumer-supplied at install time per ADR-005 / ADR-071. The
C2 implementation draws its shape from the format spec and
documented vendor conventions; no proprietary code lifted.

---

End of note 82.
