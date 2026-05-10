# Bank fixture provenance — bank-ca

## All fixtures: SYNTHESIZED (moderate confidence)

No MIT-licensed real fixtures were lifted for the Canadian banks; the
files in this directory were built from documented column specs
published by each bank's online-banking download UI and corroborated
by Beancount community plugins (which embed the same column shapes
in source comments rather than as test fixtures).

| File              | Bank             | Header / Shape                                                                    | Confidence |
| ----------------- | ---------------- | --------------------------------------------------------------------------------- | ---------- |
| `rbc.csv`         | RBC              | NO HEADER • Account Type, Account Number, Date (M/d/yyyy), Cheque #, Description, Description-2, CAD$, USD$ | MODERATE |
| `td.csv`          | TD Canada Trust  | NO HEADER • Date (MM/dd/yyyy), Description, Withdrawal, Deposit, Balance          | MODERATE |
| `scotiabank.csv`  | Scotiabank       | `Filter,Date,Time,Description,Sub-description,Status,Type of Transaction,Amount` (yyyy-MM-dd) | MODERATE |
| `bmo.csv`         | BMO              | 5-row preamble + `First Bank Card,Transaction Type,Date Posted,Transaction Amount,Description` (yyyyMMdd) | MODERATE |

Real-world variations to watch:
- RBC sometimes injects a header row when exporting from "Statement and Document Centre"
  vs not when exporting from "Manage My Money → Account Activity".
- TD's CSV format depends on whether you export from EasyWeb or the mobile app
  (the mobile app prepends a header).
- BMO's preamble line count has been observed at 4–6 lines depending on account type.

If you have anonymized real exports for these banks, please drop them in here
(sed-replacing names + account numbers with `XXXX`) and update this file.
