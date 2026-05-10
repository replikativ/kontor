# Bank fixture provenance — bank-at

## All fixtures: SYNTHESIZED (low-moderate confidence)

Austrian bank export formats are the least-documented in public
sources of any country in the kit. The fixtures below were built from
column specs corroborated by:

- Bank user manuals (Erste's George export documentation, BAWAG's
  Kontoumsatz-Export PDF, Bank Austria's e-banking export help).
- Open-source Austrian bookkeeping tools (Buchungsmaschine, Casablanca)
  that handle these CSVs and embed schemas in source comments.
- Posts in Austrian developer forums describing the layouts.

| File              | Bank             | Header (shape)                                                                                                                  | Confidence    |
| ----------------- | ---------------- | ------------------------------------------------------------------------------------------------------------------------------- | ------------- |
| `erste.csv`       | Erste / George   | `Buchungsdatum;Valutadatum;Buchungsinformation;IBAN Auftraggeber;BIC Auftraggeber;Auftraggeber;IBAN Empfänger;BIC Empfänger;Empfänger;Betrag;Währung` | LOW-MODERATE |
| `raiffeisen.csv`  | Raiffeisen       | `Buchungstag;Wertstellung;Buchungstext;Verwendungszweck;Auftraggeber/Empfänger;IBAN;BIC;Betrag;Währung;Saldo`                                          | LOW-MODERATE |
| `bank-austria.csv`| Bank Austria     | `Datum;Valutadatum;Empfänger/Auftraggeber;Buchungstext;Betrag;Währung;Verwendungszweck`                                                              | LOW-MODERATE |
| `bawag-psk.csv`   | BAWAG P.S.K.     | `Buchungsdatum;Valutadatum;Buchungstext;Auftraggeber;Empfänger;Verwendungszweck;Betrag;Währung`                                                       | LOW-MODERATE |

Real-world variations to watch:
- Regional Raiffeisen-Landesbanken each have their own slight column-order
  variants (e.g. RLB OÖ vs RLB NÖ-Wien).
- Sparkasse subsidiaries inherit the Erste/George format but some older
  Sparkassen variants drop the IBAN columns.
- Bank Austria's UniCredit-era exports differ from the legacy CA-IB
  format that some power-users still maintain.
- BAWAG and ehemalige PSK exports converged in 2020; older fixtures
  may have a different column order.

These fixtures should be replaced with anonymized real exports as
soon as a real Austrian user supplies them. Until then, the parser
configs are best-effort and test what they assert about — column-shape
correctness against documented specs.
