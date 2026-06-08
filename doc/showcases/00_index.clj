^{:kindly/hide-code true
  :clay {:title "kontor showcases — index"
         :format [:quarto :html]}}
(ns showcases.00-index
  "Index of the multi-national use-case Clay notebooks.

   Each showcase tells a story end-to-end on synthetic data, with
   cited real-world sources, exercising different parts of the
   kontor substrate.")

;; # kontor showcases
;;
;; Six narrative notebooks. Showcases 1-4 exercise different
;; jurisdictional / multi-entity shapes on synthetic data. Showcase
;; 5 exercises the bitemporal substrate on REAL SEC EDGAR data —
;; Apple's 2009 10-K/A restatement. Showcase 6 demonstrates the
;; full bitemporal-correction story on a multi-year synthetic DE
;; GmbH including consent + DSAR + retention per ADR-094.
;;
;; ## Showcase 1 — DE B2B Factur-X + Mahnverfahren
;;
;; [Open Showcase 1 →](showcases.01_de_b2b_factur_x.html)
;;
;; *Schnitzel & Code GmbH* (München) bills *Goldener Brezel GmbH*
;; ₹19'932.50 EUR. Customer partial-pays, opens a line-level dispute
;; (auto-suppresses dunning), concedes, partial-pays again,
;; triggers Mahnstufe 1+2 of the 3-level Mahnverfahren, finally
;; settles. Bitemporal aging snapshots at past `:as-of-valid`
;; dates demonstrate the audit-replay structural advantage.
;;
;; Cited: BGB §286 / §288 (German Civil Code default + interest),
;; EU Late Payment Directive 2011/7/EU, HGB §238-263, DATEV SKR04,
;; Factur-X 1.0.07, Mustang Project (APL-2).
;;
;; ## Showcase 2 — US LLC multi-state sales tax + Reg-F dunning
;;
;; [Open Showcase 2 →](showcases.02_us_llc_multi_state.html)
;;
;; *Skyline Analytics LLC* (Delaware/NY) bills SaaS at $399/month
;; across 4 states (CA 9.50%, NY 8.875%, TX 8.25%, WA 10.5%) via a
;; TaxProvider stub. CA + NY + WA become past-due; WA disputes a
;; tax line (auto-suppresses dunning); Reg-F frequency-cap kicks
;; in when an over-eager collector tries to re-send. All settle.
;;
;; Cited: 12 CFR Part 1006 (CFPB Regulation F), South Dakota v.
;; Wayfair (2018), Streamlined Sales Tax (SST) member states.
;;
;; ## Showcase 3 — IN B2B with IRN + GSTR + TDS + reverse-charge
;;
;; [Open Showcase 3 →](showcases.03_in_b2b_irn_tds.html)
;;
;; *Bharat Metalcraft Pvt Ltd* (Pune, MH, GSTIN 27ABCDE1234F1Z5)
;; runs 4 invoices: inter-state sale to Karnataka (IGST + NIC IRN
;; clearance), intra-state sale to Maharashtra (CGST+SGST split),
;; SaaS import from Ireland (reverse-charge mechanism), Pune
;; consultant payment (§194J TDS withholding 10%). Partial-pay +
;; dispute + concede + final-pay. GSTR-1 B2B + HSN-summary
;; aggregation via datalog.
;;
;; Cited: CBIC e-invoicing schema v1.1, CGST Act §12/§13, IGST
;; Act §5(3), CBDT TDS §194C/§194J/§194Q, GSTR-1 form structure.
;;
;; ## Showcase 4 — Multi-entity intercompany + cost-center
;;
;; [Open Showcase 4 →](showcases.04_multi_entity_intercompany.html)
;;
;; *Acme Industries Holding GmbH* (DE parent) owns *Acme NA LLC*
;; (US sub). US sub procures steel, sells finished goods to
;; Megacorp; DE parent issues an intercompany services invoice
;; $100k to US sub. Cost-centers (CC-MFG, CC-SALES, CC-CORP) tag
;; postings via `:posting-analytic` distribution. ADR-031 sum-to-
;; zero per-(entity, ledger, commodity) is verified. ADR-038
;; `:no-self-approval` enforcement rejects self-approved payments
;; then succeeds with a different actor.
;;
;; ## Showcase 5 — Apple 10-K bitemporal restatement (REAL DATA)
;;
;; [Open Showcase 5 →](showcases.05_apple_10k_bitemporal.html)
;;
;; *Apple Inc.* — ingest the real SEC EDGAR companyfacts JSON for
;; CIK 0000320193. Apple's 2009 10-K (filed 2009-10-27) reported
;; AccruedLiabilities FY2008 = $3.719B; the 10-K/A amendment filed
;; 2010-01-25 restated it to $4.224B (+$505M) per ASC 605-25
;; revenue-recognition adoption. The substrate records both,
;; stamping each fact with `:tx/valid-from` = SEC `:filed` date;
;; `(d/valid-at db 2009-12-01)` returns the original 10-K value,
;; `(d/valid-at db 2010-02-01)` returns the amended value.
;;
;; Cited: SEC EDGAR API (public domain, 17 U.S.C. § 105), Apple
;; 10-K accession 0001193125-09-214859, 10-K/A accession
;; 0001193125-10-012091, ASC 605-25 (FASB revenue recognition).
;;
;; ## Showcase 6 — Multi-year DE GmbH (bitemporal + consent + DSAR + retention)
;;
;; [Open Showcase 6 →](showcases.06_de_gmbh_multi_year.html)
;;
;; *Acme Manufacturing GmbH* (München) — 3-year company history
;; exercising the substrate end-to-end. Y1: hire + consent grants
;; under BDSG §26(1), monthly DATEV LODAS payroll. Y1 Q4: a 1200 EUR
;; expense gets misclassified to Reisekosten when it should have
;; been Bewirtungskosten (EStG §4(5) Nr. 2 — 70% deductible). Y2:
;; promotion + comp supersession; Y2 Q4 the Steuerberater catches
;; the Y1 misclassification — kontor.bitemporal/close-validity!
;; closes the original posting and the corrected split lands
;; without rewriting history. `(d/valid-at db 2026-12-31)` still
;; sees the original; `(d/valid-at db 2027-11-01)` sees the
;; correction. Y3: terminated employee triggers DSAR via the
;; kontor.dsar walker + people-record bundle; ADR-094 + l10n-de
;; retention seeds drive eligibility check.
;;
;; Cited: BGB / HGB §238-263, BDSG §26, DSGVO Art. 5(1)(e),
;; EStG §4(5) Nr. 2, BetrVG §82-83, DATEV SKR04, ADR-094 + research
;; notes 93 + 94.
;;
;; ## How these notebooks fit
;;
;; - **Showcases 1-4 + 6: synthetic data**. Company names, GSTINs,
;;   EINs are fictional. The accounting workflows are grounded in
;;   cited regulatory sources.
;; - **Showcase 5: real public data**. Apple's actual SEC filings;
;;   the amendment is real; the values restated are the actual
;;   ASC 605-25 deltas.
;; - **Substrate validation**: every kontor primitive exercised in a
;;   showcase is also covered by deftest in modules/*/test/.
;;
;; ## License
;;
;; The notebooks themselves are Apache 2.0 along with the rest of
;; kontor. The cited regulatory sources are linked, not embedded.
(comment :placeholder-for-clay-to-render-this-as-a-doc)
