# Bank fixture provenance — bank-at

Three of the four AT fixtures are now anchored to **cited open-source
parser repos** that ship working CSV importers for the matching format.
The data values are synthesized (anonymous), but the column shape,
separator, encoding, and header-presence are taken from those references.

The fourth (Erste/George) is irreducibly user-configurable; the fixture
shows one *common* configuration.

## erste.csv  — user-configurable; one common configuration shown

George (Erste's online-banking app) lets the user drag-and-drop column
order and toggle individual fields when exporting. There is NO canonical
header.

Sources for the field NAMES used here:
  https://www.sparkasse.at/de/george-help/george/suchen-und-finden/ihre-suchergebnisse/export-von-suchergebnissen
  https://www.tabelle.at/ (3rd-party guide on George CSV export)

The 11-column configuration below is one common "all counterparty
fields enabled" layout:

    Buchungsdatum;Valutadatum;Buchungsinformation;
    IBAN Auftraggeber;BIC Auftraggeber;Auftraggeber;
    IBAN Empfänger;BIC Empfänger;Empfänger;Betrag;Währung

A real user with a different field selection should derive a per-export
config from this template. We treat this as a config-driven importer
rather than a fixed format.

Confidence: column NAMES are correct; column ORDER is one valid choice.

## raiffeisen.csv  — column shape verified

Source: nblock/ofxstatement-austrian sample
  https://github.com/nblock/ofxstatement-austrian/blob/master/src/ofxstatement/plugins/tests/samples/raiffeisen.csv
  (License: GPL-3. Only the column layout — a factual specification
   not subject to copyright — was consulted; no rows or code from
   the GPL-3 repo are present in this fixture. All values below are
   synthesized anonymous test data.)

Format: 6 cols, NO header, semicolon-separated. Counterparty,
Verwendungszweck, IBAN, etc. are all embedded in the quoted
`Buchungstext` field. Schema:

    Buchungstag;Buchungstext;Valutadatum;Betrag;Währung;Zeitstempel

Date: dd.MM.yyyy. Amount: signed German (`-175,16`).

(Earlier version invented separate Auftraggeber / IBAN / BIC / Saldo
columns — that was wrong. Corrected to the real 6-col embedded-text
shape.)

Confidence: HIGH for the ELBA-internet/Mein-ELBA web export;
ELBA-business desktop may emit a different format.

## bank-austria.csv  — column shape from 3rd-party importer (medium confidence)

Source: onetwoapps.de CSV importer config (3rd-party importer
documenting the Bank Austria export).

Format: 7 cols, WITH header, semicolon-separated:

    Buchungsdatum;Valutadatum;Buchungstext;Interne Notiz;Währung;Betrag;Belegdaten

Counterparty info is embedded in `Buchungstext`. Date dd.MM.yyyy,
amount signed German.

(Earlier version invented Empfänger/Auftraggeber + Verwendungszweck
as separate columns — that was wrong. Corrected.)

Confidence: MEDIUM. Bank Austria has not published an authoritative
CSV spec; this is from a 3rd-party importer.

## bawag-psk.csv  — column shape verified

Source: PeterTheOne/bawag-csv-parser unit-test fixture
  https://github.com/PeterTheOne/bawag-csv-parser/blob/master/tests/BawagCsvParserTest.php

Format: 6 cols, NO header, semicolon-separated:

    Account;Buchungstext;Buchungsdatum;Valutadatum;Betrag;Währung

Counterparty info embedded in `Buchungstext`. Date dd.MM.yyyy. Amount
signed German with `+` prefix on credits (e.g. `+350,00`).

(Earlier version invented separate Auftraggeber/Empfänger/Verwendungszweck
columns — that was wrong. Corrected.)

Confidence: HIGH for the native short form. An extended 18-col form
exists in newer Online-eBanking exports — model that as a separate
`:bawag-extended` config when needed.
