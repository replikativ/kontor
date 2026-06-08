# kontor showcases

Multi-national use-case notebooks demonstrating end-to-end kontor flows on synthetic data.

Each showcase is a Clojure file (Clay notebook) that:

- **Tells a story** — a fictional company in a specific jurisdiction operating through their fiscal year.
- **Exercises kontor** end-to-end (kernel + relevant companions + l10n).
- **Cites real-world sources** for the regulatory shapes — public-domain primary sources where possible, vendor docs only as comparison reference (no copyrighted data lifted).
- **Doubles as integration test**. Showcases re-run in `clojure -M:test`; regressions are caught.

## Files

- `00_index.clj` — index + reading order across the six showcases.
- `01_de_b2b_factur_x.clj` — German GmbH B2B SaaS with Factur-X invoicing + DE Mahnverfahren collections. Exercises: `kontor-l10n-de`, `kontor-einvoice-de` (Mustang), `kontor-collections`, sales+invoice posting bridge, ADR-040 jurisdiction primitives.
- `02_us_llc_multi_state.clj` — US LLC selling SaaS across CA/NY/TX/WA with Regulation F-compliant dunning. Exercises: TaxProvider stub, ADR-040 withholding-tax flag, ADR-038 approval-policy with frequency-cap, customer dispute lifecycle.
- `03_in_b2b_irn_tds.clj` — Indian B2B manufacturer with NIC IRN e-invoice clearance + GSTR-1 export shape + TDS withholding + intercompany reverse-charge. Exercises: ADR-024 multi-attestation, ADR-040 `:invoice-line/reverse-charge?`, `:withholding-on-payment?`, the kernel's clearance state-machine.
- `04_multi_entity_intercompany.clj` — Multi-entity intercompany scenario: parent ↔ subsidiary in different functional currencies, FX translation, intercompany elimination, multi-entity consolidation (ADR-073).
- `05_apple_10k_bitemporal.clj` — Apple 10-K/A bitemporal restatement: real SEC filing, real amendment, ASC 605-25 deltas re-stated. Demonstrates `:tx/valid-from` + `close-validity!` against public-data magnitudes.
- `06_de_gmbh_multi_year.clj` — Multi-year DE GmbH walkthrough with a misclassified Y1 expense caught in Y2 and corrected via `close-validity!` + a new write at the original valid-time. Demonstrates that both views — what the Y1 books said at year-end and what they say after the restatement — remain queryable.

## Render locally

```bash
clojure -M:notebooks
```

Then in the REPL:

```clojure
(require '[scicloj.clay.v2.api :as clay])
(clay/make! {:source-path "doc/showcases/01_de_b2b_factur_x.clj"})
```

## License

The showcase notebooks are Apache 2.0 along with the rest of `kontor`. The cited regulatory sources are linked, not embedded. Synthetic data (company names, addresses, tax-IDs) is fictional and does not represent any real entity.
