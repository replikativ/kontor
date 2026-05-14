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
;; Four narrative notebooks. Each exercises a different
;; jurisdictional / multi-entity shape and cites the real-world
;; regulatory sources behind the workflow.
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
;; ## How these notebooks fit
;;
;; - **Synthetic data only**: company names, GSTINs, EINs are
;;   fictional. The accounting workflows are grounded in cited
;;   regulatory sources.
;; - **Reference comparisons**: each notebook ends with a section
;;   comparing the implementation to how Odoo / Tryton / SAP /
;;   NetSuite / Tally / etc. model the same flow.
;; - **Substrate validation**: every kontor primitive exercised in a
;;   showcase is also covered by deftest in modules/*/test/. See
;;   research note 16 for the feature-coverage audit.
;;
;; ## License
;;
;; The notebooks themselves are EPL-1.0 along with the rest of
;; kontor. The cited regulatory sources are linked, not embedded.
(comment :placeholder-for-clay-to-render-this-as-a-doc)
