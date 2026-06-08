# Bank fixture provenance — bank-fr

## n26.csv  — REAL (MIT-licensed)

Lifted from rows embedded in the test suite of:

    https://github.com/siddhantgoel/beancount-n26
    LICENSE: MIT (Copyright (c) 2019 Siddhant Goel)

The rows in `n26.csv` are concatenated unique data lines from
`tests/test_n26_importer.py`. Header row is the canonical N26
EN-locale export header (DE/FR/IT/ES locales translate the headers
but keep identical column order — tests cover those headers via
detection patterns).

Confidence: HIGH. Format is verified by an actively-maintained
Beancount importer with a wide-locale test suite.

## credit-agricole.csv  — SYNTHESIZED (moderate confidence)

Built from the documented Crédit Agricole regional CR (Caisse
Régionale) "Téléchargement de mes opérations" export. Header:
`Date;Date valeur;Libellé;Débit euros;Crédit euros`. Split-debit-credit,
French numerals (1.234,56), dd/MM/yyyy.

Confidence: MODERATE. Regional CRs do vary slightly (e.g. column
naming, presence of value-date column). Real anonymized exports
welcomed.

## societe-generale.csv  — SYNTHESIZED (moderate confidence)

Built from the documented Société Générale Espace Client CSV export
shape. Header: `Date de l'opération;Libellé;Détail;Montant;Devise`.
Single signed-amount column, French numerals, dd/MM/yyyy.

Confidence: MODERATE.

## bnp-paribas.csv  — SYNTHESIZED (moderate confidence)

Built from the documented BNP Paribas Mes Comptes CSV export shape.
Header: `Date opération;Libellé opération;Montant`. Minimal three-column
layout used by the personal-banking export.

Confidence: MODERATE. Pro accounts use a richer schema.

---

If you have anonymized real exports for Crédit Agricole / Société Générale /
BNP Paribas, please drop them in here and update this file.
