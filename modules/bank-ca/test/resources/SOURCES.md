# Bank fixture provenance — bank-ca

All fixtures are now anchored to **cited open-source parser repos** that
ship working CSV importers for the matching format. The data values are
synthesized (anonymous) but the column shape, separator, encoding, and
header-presence are taken from those references.

## rbc.csv  — column shape verified (gist-anchored)

Source: cphoward beangulp importer
  https://gist.github.com/cphoward/50d6218771f234f96f6d73351de659c9

Header IS present in the native RBC "Manage My Money → Account Activity"
download:
  `"Account Type","Account Number","Transaction Date","Cheque Number","Description 1","Description 2","CAD$","USD$"`

Date: M/d/yyyy. Amount: signed English in CAD$ (USD$ blank when CAD-denominated
and vice versa).

(Earlier version of this fixture used "no-header" assumption — corrected.)

Confidence: HIGH for column shape; merchant text is anonymized.

## td.csv  — column shape verified

Source: deb-sig/double-entry-generator example
  https://github.com/deb-sig/double-entry-generator/blob/master/example/td/example-td-records.csv

NO header, 5 cols: Date, Description, Withdrawal, Deposit, Balance.
Date MM/dd/yyyy, split-debit-credit amounts.

Confidence: HIGH.

## scotiabank.csv  — column shape verified

Source: chuck3r/sb_parser
  https://github.com/chuck3r/sb_parser/blob/master/scotiaChequingParser.py

NO header, 5 cols: Date, signed Amount, [reserved], Type-of-transaction,
Description. The reserved column is always blank in personal-banking
exports. Date is whatever the user's locale defaulted to when downloading
(commonly MM/dd/yyyy).

(Earlier version of this fixture had the ScotiaConnect *business-banking*
shape with `Filter,Date,Time,Description,...` header; that's a different
product entirely. Replaced with the personal-banking format.)

Confidence: HIGH.

Future work: model ScotiaConnect business export as a separate
`:scotia-business` config — its columns are user-customizable via export
templates, so any spec is approximate.

## bmo.csv  — column shape verified (2023-vintage)

Source: deb-sig/double-entry-generator example
  https://github.com/deb-sig/double-entry-generator/blob/master/example/bmo/debit/example-bmo-records.csv

Format: 1 informational preamble line + 2 blanks + header + 2 blanks +
data (5 leading rows total). Header has a literal leading space before
"Transaction Amount":

    First Bank Card,Transaction Type,Date Posted, Transaction Amount,Description

Card number field is single-quoted. Date: yyyyMMdd. Amount: signed
English (negative for debits).

(Earlier version of this fixture overcounted the preamble as 5 lines of
content; the actual layout is 1 content line + blanks. Corrected.)

Confidence: HIGH for the 2023 export; no evidence of a breaking change since.
