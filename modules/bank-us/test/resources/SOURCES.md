# Bank fixture provenance — bank-us

Each fixture below lists where the data came from. Real-world MIT-licensed
fixtures are the gold standard; everything else is synthesized from documented
column specs and is marked as such.

## chase.csv  — REAL (MIT-licensed)

Lifted from rows embedded in the test suite of:

    https://github.com/mtlynch/beancount-chase-bank
    LICENSE: MIT (Copyright 2022 Michael Lynch)

The rows in `chase.csv` are concatenated unique data lines from
`beancount_chase/checking_test.py` (the `_unindent("""…""")` blocks).
Header row = the canonical Chase consumer-checking export header.

Confidence: HIGH. Format is verified by an actively-maintained Beancount
importer with its own test suite.

## chase-credit.csv  — REAL (MIT-licensed)

Same source repo (`beancount_chase/credit_test.py`).

Confidence: HIGH.

## wells-fargo.csv  — SYNTHESIZED (high confidence)

Built from the documented Wells Fargo CSV export shape: no header row,
five columns (Date, Amount, '*' marker, Check#, Description), MM/DD/YYYY
dates, signed English amounts, fields quoted.

Confidence: HIGH for column shape; the merchant text is fictitious.

## bofa.csv  — SYNTHESIZED (moderate confidence)

Built from the documented Bank of America consumer checking export
shape: 5–6 row preamble (Beginning balance / Total credits / Total
debits / Ending balance), then the real header
`Date,Description,Amount,Running Bal.`. MM/DD/YYYY dates, signed
English amounts, English-decimal numbers.

Confidence: MODERATE. The preamble structure varies slightly by
account type and export tool.

CORRECTION (ADR-131): as originally committed the preamble contradicted
the fixture's own rows by exactly $850.00 — it claimed
`Total debits -$3,612.50` / `Ending balance $7,033.10` where the rows
sum to `-$2,762.50` and the last `Running Bal.` is `$7,883.10`. The
per-row running balance is internally consistent, so the rows were
authoritative and the preamble was corrected to match. This matters
because the preamble is now a genuine SECOND oracle for this fixture:
`bofa-preamble-is-a-second-oracle` in `parser_test.clj` pins the parse
against it, which catches truncation at either end of the statement —
something the running-balance chain alone cannot see (the derived
opening just shifts).

## amex.csv  — SYNTHESIZED (moderate confidence)

Built from the documented American Express activity export shape with
the extended-details columns. Real exports vary depending on whether
"Include category" / "Include extended details" are toggled.

Confidence: MODERATE.

---

If you have anonymized real exports for Wells Fargo / BoA / AmEx, please
drop them in here (sed-replacing names + account numbers with `XXXX`)
and update this file.
