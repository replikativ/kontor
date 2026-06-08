# Bank fixture provenance — bank-de

Per-bank field-level format documentation lives in `FORMATS.md`
in this same directory (German-language; references each bank's
published export specification or online-banking export dialog).

## Data values

All values in the `.csv`, `.qif`, `.xlsx`, and `.mta` fixtures in
this directory are synthesized anonymous test data. They are not
real customer transactions and do not correspond to any natural
person or business.

## Format references

Each fixture's columns + separators + date format + amount format
follow the bank's published export specification (Sparkasse,
Commerzbank, ING, DKB, Postbank, PayPal, Targobank, Sparda-Bank
West, GLS, VR-Teilhaberbank). The Sparkasse fixtures additionally
follow the SEPA-CAMT.052 / MT940 industry standards (ISO 20022 +
SWIFT-published format specs respectively).

## Categorizer code (not data)

The German-language transaction auto-categorizer in
`src/kontor/bank_de/parser.clj` (the keyword-to-`:account`
heuristic) was adapted from the author's prior MIT-licensed
`openclaw/beleg` project (Copyright (c) 2023-2025 replikativ).
MIT → Apache 2.0 is one-way compatible; the upstream attribution is
preserved in the namespace docstring.

The CSV fixtures themselves were not lifted from openclaw.
