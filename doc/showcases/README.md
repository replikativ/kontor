# kontor showcases

Multi-national use-case notebooks demonstrating end-to-end kontor flows on synthetic data.

Each showcase is a Clojure file (Clay notebook) that:

- **Tells a story** — a fictional company in a specific jurisdiction operating through their fiscal year.
- **Exercises kontor** end-to-end (kernel + relevant companions + l10n).
- **Cites real-world sources** for the regulatory shapes — public-domain primary sources where possible, vendor docs only as comparison reference (no copyrighted data lifted).
- **Compares to reference implementations**: Apache OFBiz (Apache-2.0), Tryton (GPLv3 — design reference only), Odoo (LGPLv3 — design reference only), commercial systems (NetSuite, SAP S/4HANA, Salesforce Revenue Cloud) via their public docs.
- **Doubles as integration test**. Showcases re-run in `bb test`; regressions are caught.

## Files

- `01_de_b2b_factur_x.clj` — German GmbH B2B SaaS with Factur-X invoicing + DE Mahnverfahren collections. Exercises: `kontor-l10n-de`, `kontor-einvoice-de` (Mustang), `kontor-collections`, sales+invoice posting bridge, ADR-040 jurisdiction primitives.
- `02_us_llc_multi_state.clj` — US LLC selling SaaS across CA/NY/TX/WA with Regulation F-compliant dunning. Exercises: TaxProvider stub, ADR-040 withholding-tax flag, ADR-038 approval-policy with frequency-cap, customer dispute lifecycle.
- `03_in_b2b_irn_tds.clj` — Indian B2B manufacturer with NIC IRN e-invoice clearance + GSTR-1 export shape + TDS withholding + intercompany reverse-charge. Exercises: ADR-024 multi-attestation, ADR-040 `:invoice-line/reverse-charge?`, `:withholding-on-payment?`, the kernel's clearance state-machine.

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

The showcase notebooks are EPL-1.0 along with the rest of `kontor`. The cited regulatory sources are linked, not embedded. Synthetic data (company names, addresses, tax-IDs) is fictional and does not represent any real entity.
