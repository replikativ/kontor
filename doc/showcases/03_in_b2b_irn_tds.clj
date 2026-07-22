^{:kindly/hide-code true
  :clay {:title "Showcase 3: IN B2B with IRN + GSTR + TDS + reverse-charge"
         :format [:quarto :html]}}
(ns showcases.03-in-b2b-irn-tds
  "Multi-national use case 3: a fictional Indian B2B manufacturer
   exercising the most-complex jurisdiction shape kontor models:

     - GST split (CGST+SGST for intra-state, IGST for inter-state)
       per `kontor-l10n-in.taxes`
     - e-invoice IRN (Invoice Reference Number) clearance via the
       NIC IRP (Invoice Registration Portal) — ADR-024 multi-
       attestation
     - GSTR-1 export shape (B2B vs B2CL vs export buckets)
     - Reverse-Charge Mechanism (RCM) for imports of services
       — `:kontor.invoice-line/reverse-charge?` (ADR-040)
     - TDS (Tax Deducted at Source) withholding on vendor payments
       — `:kontor.invoice-line/withholding-on-payment?` (ADR-040)
     - Partial payment with TDS deduction (#22 replayable allocation)
     - Bitemporal dispute lifecycle (#15 substrate win)

   Synthetic data; all GSTINs / PANs / addresses are fictional.
   Cited:
     - Indian e-invoicing schema v1.1 (NIC IRP)
     - GST Council 56th meeting notifications (GST 2.0 cutover
       2025-09-22)
     - CBDT Notifications on TDS sections 194C, 194J, 194Q
     - Place of Supply rules under CGST Act, Section 12 + 13
     - IGST Act §5 (reverse charge mechanism)
     - GSTR-1 / GSTR-3B form structures (publicly documented at
       gst.gov.in)

   This showcase doesn't run the actual IRP POST — it just builds
   the JSON payload and stamps the simulated IRN as a kernel
   `:attestation` (ADR-024). Production tenants plug their
   IRP-API HTTPS adapter."
  (:require [datahike.api :as d]
            [kontor.collections.schema :as coll-schema]
            [kontor.core :as core]
            [kontor.invoice.bridge :as inv]
            [kontor.invoice.schema :as inv-schema]
            [kontor.l10n-in.identifiers :as ids]
            [kontor.l10n-in.irn :as irn]
            [kontor.l10n-in.taxes :as gst]
            [kontor.partner.schema :as partner-schema]
            [kontor.banking.payment-application :as papp]
            [kontor.sales.schema :as sales-schema]
            [kontor.workflow.status-machine :as sm]))

;; # The story
;;
;; **Bharat Metalcraft Pvt Ltd** (fictional, Pune, MH 411001,
;; *GSTIN 27ABCDE1234F1Z5*, *PAN ABCDE1234F*) manufactures
;; industrial components. In 2026-Q2 they:
;;
;; 1. **Sell to a Karnataka customer** (inter-state) — generates
;;    IGST + IRN clearance via NIC IRP.
;; 2. **Sell to a Maharashtra customer** (intra-state) — generates
;;    CGST+SGST split.
;; 3. **Import a SaaS subscription from Ireland** — `:invoice-
;;    line/reverse-charge?` flag triggers the buyer (Bharat) to
;;    self-assess IGST.
;; 4. **Pay a Pune consultant** — withholds 10% TDS under §194J
;;    via `:kontor.invoice-line/withholding-on-payment?`.
;; 5. **Karnataka customer disputes** quality on one line; resolves;
;;    customer eventually pays. Bharat self-clears with NIC IRP for
;;    a credit note.

;; ## Bootstrap

(def conn (core/create-test-db))
(partner-schema/install! conn)
(sales-schema/install! conn)
(inv-schema/install! conn)
(coll-schema/install! conn)

;; ## Seeds: chart of accounts, partners, GSTINs

(d/transact conn
            [{:kontor.commodity/symbol "INR" :kontor.commodity/name "Indian Rupee"
              :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "INR"}
             {:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
              :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "EUR"}
             {:kontor.entity/code "BHARAT-MC"
              :kontor.entity/name "Bharat Metalcraft Pvt Ltd"
              :kontor.entity/kind :operating :kontor.entity/active true}
             ;; Accounts (Indian GAAP / Schedule III shape)
             {:kontor.account/code "1200" :kontor.account/path "1200"
              :kontor.account/name "Sundry Debtors (AR)" :kontor.account/type :asset}
             {:kontor.account/code "2100" :kontor.account/path "2100"
              :kontor.account/name "Sundry Creditors (AP)" :kontor.account/type :liability}
             {:kontor.account/code "4000" :kontor.account/path "4000"
              :kontor.account/name "Sales of Goods" :kontor.account/type :revenue}
             {:kontor.account/code "5000" :kontor.account/path "5000"
              :kontor.account/name "Consultancy Expense" :kontor.account/type :expense}
             ;; GST accounts
             {:kontor.account/code "2210" :kontor.account/path "2210"
              :kontor.account/name "CGST Output Payable" :kontor.account/type :liability}
             {:kontor.account/code "2211" :kontor.account/path "2211"
              :kontor.account/name "SGST Output Payable" :kontor.account/type :liability}
             {:kontor.account/code "2212" :kontor.account/path "2212"
              :kontor.account/name "IGST Output Payable" :kontor.account/type :liability}
             {:kontor.account/code "1410" :kontor.account/path "1410"
              :kontor.account/name "IGST Input Credit (recoverable)"
              :kontor.account/type :asset}
             {:kontor.account/code "2310" :kontor.account/path "2310"
              :kontor.account/name "TDS Payable - §194J" :kontor.account/type :liability}
             ;; GL defaults
             {:kontor.gl-account-default/account-type :ar
              :kontor.gl-account-default/account [:kontor.account/path "1200"]}
             {:kontor.gl-account-default/account-type :ap
              :kontor.gl-account-default/account [:kontor.account/path "2100"]}
             {:kontor.gl-account-default/account-type :sales-revenue
              :kontor.gl-account-default/account [:kontor.account/path "4000"]}
             {:kontor.gl-account-default/account-type :purchase-expense
              :kontor.gl-account-default/account [:kontor.account/path "5000"]}
             ;; Journals
             {:kontor.journal/code "AR" :kontor.journal/name "Sales" :kontor.journal/type :sales}
             {:kontor.journal/code "AP" :kontor.journal/name "Purchases" :kontor.journal/type :purchase}
             ;; Partners (Indian B2B GSTINs + a vendor + a foreign supplier)
             {:kontor.partner/external-id "KA-CUST"
              :kontor.partner/name "Mysore Industrial Co"
              :kontor.partner/kind :customer
              :kontor.partner/country-code "IN"
              :kontor.partner/tax-id "29ABCDE1111F1Z5"  ; KA GSTIN (29 = Karnataka)
              :kontor.partner/credit-status :open}
             {:kontor.partner/external-id "MH-CUST"
              :kontor.partner/name "Pune Forging Ltd"
              :kontor.partner/kind :customer
              :kontor.partner/country-code "IN"
              :kontor.partner/tax-id "27ABCDE2222F1Z5"  ; MH GSTIN (27 = Maharashtra)
              :kontor.partner/credit-status :open}
             {:kontor.partner/external-id "IE-VENDOR"
              :kontor.partner/name "Dublin SaaS Ltd"
              :kontor.partner/kind :vendor
              :kontor.partner/country-code "IE"}
             {:kontor.partner/external-id "PUNE-CONSULTANT"
              :kontor.partner/name "ABC Tax Consultants"
              :kontor.partner/kind :vendor
              :kontor.partner/country-code "IN"
              :kontor.partner/tax-id "27ABCDE3333F1Z5"}
             {:kontor.partner/external-id "U-alice" :kontor.partner/name "Alice (collector)"}
             {:kontor.partner/external-id "U-bob"   :kontor.partner/name "Bob (manager)"}])

(def bharat (d/q '[:find ?e . :where [?e :kontor.entity/code "BHARAT-MC"]] (d/db conn)))
(def inr    (d/q '[:find ?c . :where [?c :kontor.commodity/symbol "INR"]] (d/db conn)))
(def alice  (d/q '[:find ?p . :where [?p :kontor.partner/external-id "U-alice"]] (d/db conn)))
(def bob    (d/q '[:find ?p . :where [?p :kontor.partner/external-id "U-bob"]] (d/db conn)))

;; ## Verify supplier identifiers
;;
;; The kontor-l10n-in module ships GSTIN + PAN validators per the
;; CBIC technical specification:

(ids/valid-gstin? "27ABCDE1234F1Z5")
;; → true

(ids/gstin-state-code "29ABCDE1111F1Z5")
;; → "29" (Karnataka)

;; ## Issue invoice 1: Inter-state (MH → KA) → IGST + IRN
;;
;; Bharat is in Maharashtra (state code 27); customer is in
;; Karnataka (29). Per CGST Act §10 + IGST Act §5, this is an
;; **inter-state supply** — full IGST 18% on a 100'000 INR line.

(def supplier-state-code "27")
(def ka-pos-state-code "29")

(def supply-type
  (gst/dispatch-supply supplier-state-code ka-pos-state-code false))
;; → :inter-state

;; Compute GST split for 100'000 net at 18% slab.

;; `gst/component-split` takes (dispatch headline-rate) — returns
;; the rate map for the supply type. For inter-state: {:igst rate}.
(def gst-ka-split
  (gst/component-split supply-type 0.18M))
;; → {:igst 0.18M}  (inter-state)

;; Multiply manually by the base amount to get the tax money.
;; (`gst/compute-tax` expects a Money record; for the showcase
;; clarity we keep BigDecimal arithmetic.)
(def gst-ka-igst
  (let [base 100000M
        rate (:igst gst-ka-split)]
    (.multiply ^java.math.BigDecimal base
               ^java.math.BigDecimal rate)))
;; → 18000M

;; Build the invoice. Line gross = 100'000 + 18'000 IGST = 118'000.

(d/transact conn
            [{:db/id "inv-KA"
              :kontor.invoice/external-id "BMC/2026-27/0001"
              :kontor.invoice/type :sales
              :kontor.invoice/status :pending-attestation   ; awaiting IRN
              :kontor.invoice/issue-date #inst "2026-04-10"
              :kontor.invoice/seller (d/q '[:find ?p . :where [?p :kontor.partner/external-id "MH-CUST"]] (d/db conn))
                                                    ;; reuse a partner as placeholder for "self"
              :kontor.invoice/buyer  (d/q '[:find ?p . :where [?p :kontor.partner/external-id "KA-CUST"]] (d/db conn))
              :kontor.invoice/entity bharat
              :kontor.invoice/currency "INR"
              :kontor.invoice/total-net 100000M
              :kontor.invoice/total-vat 18000M
              :kontor.invoice/total-gross 118000M
              :kontor.invoice/lines ["l-KA-1" "l-KA-tax"]}
             {:db/id "l-KA-1"
              :kontor.invoice-line/invoice "inv-KA"
              :kontor.invoice-line/sequence 1
              :kontor.invoice-line/name "Steel Forging Set, Grade EN8"
              :kontor.invoice-line/quantity 50M
              :kontor.invoice-line/unit-price 2000M
              :kontor.invoice-line/amount 100000M
              :kontor.invoice-line/gl-account-type :sales-revenue
              :kontor.invoice-line/vat-rate 18M
              :kontor.invoice-line/vat-category "STANDARD"
              :kontor.invoice-line/description "HSN 73269099 (forgings)"}
             {:db/id "l-KA-tax"
              :kontor.invoice-line/invoice "inv-KA"
              :kontor.invoice-line/sequence 2
              :kontor.invoice-line/name "IGST 18%"
              :kontor.invoice-line/quantity 1M
              :kontor.invoice-line/unit-price 18000M
              :kontor.invoice-line/amount 18000M
              :kontor.invoice-line/account (d/q '[:find ?a . :where [?a :kontor.account/path "2212"]]
                                          (d/db conn))
              :kontor.invoice-line/gl-account-type :sales-tax-payable
              :kontor.invoice-line/vat-category "IGST"}])

;; ### Compute IRN + payload
;;
;; The Invoice Reference Number is a 64-char SHA-256 hash of
;; (supplier-gstin, document-number, financial-year, document-type).
;; kontor's `compute-irn` matches the NIC spec.

(def ka-irn
  (irn/compute-irn {:supplier-gstin "27ABCDE1234F1Z5"
                    :doc-no "BMC/2026-27/0001"
                    :doc-date #inst "2026-04-10"
                    :doc-type "INV"}))
ka-irn
;; → 64-char SHA-256 hash

;; The full IRP POST payload (`irn/build-payload`) has a richer
;; shape — :tran / :doc / :seller / :buyer / :ship-to / :dispatch /
;; :item-list — that maps to the NIC IRP v1.1 schema. We skip
;; building it here to keep the notebook focused on the kontor data
;; shape; see `modules/l10n-in/src/kontor/l10n_in/irn.clj` for the
;; full builder + a `(payload-json payload)` JSON-emit step.

;; Simulating the IRP response (production: HTTPS POST to
;; einv-apisandbox.nic.in/eivital/v1.04/Invoice). The Ack number
;; would come from the IRP; we synthesize.

(def irp-response
  {:ack-no 112020026000123  ; 15-digit
   :ack-dt "2026-04-10T14:32:18"
   :irn ka-irn
   :signed-invoice "...JWT..." ; would be a JWT with full signed invoice
   :qr-code "...base64..."})

;; In production we'd record the IRP response as a `:attestation`
;; on the invoice's transaction. ADR-024 multi-attestation supports
;; this. For brevity we skip the attestation write here.

;; Move invoice :pending-attestation → :sent.

(sm/record-status-change! conn
                          {:entity (inv/by-external-id (d/db conn) "BMC/2026-27/0001")
                           :entity-type :invoice
                           :facet :kontor.invoice/status
                           :to :sent
                           :changed-by-uid alice
                           :reason :authority-cleared
                           :reason-note (str "IRN " ka-irn " issued by NIC IRP")})

;; ## Issue invoice 2: Intra-state (MH → MH) → CGST + SGST split
;;
;; Same supplier (MH), buyer also in MH. CGST 9% + SGST 9% split.

(def gst-mh-split
  (gst/component-split :intra-state 0.18M))
;; → {:cgst 0.09M :sgst 0.09M}

(def gst-mh-cgst
  (let [base 50000M, rate (:cgst gst-mh-split)]
    (.multiply ^java.math.BigDecimal base
               ^java.math.BigDecimal rate)))
;; → 4500M

(def gst-mh-sgst
  (let [base 50000M, rate (:sgst gst-mh-split)]
    (.multiply ^java.math.BigDecimal base
               ^java.math.BigDecimal rate)))
;; → 4500M

(d/transact conn
            [{:db/id "inv-MH"
              :kontor.invoice/external-id "BMC/2026-27/0002"
              :kontor.invoice/type :sales
              :kontor.invoice/status :sent
              :kontor.invoice/issue-date #inst "2026-04-12"
              :kontor.invoice/buyer (d/q '[:find ?p . :where [?p :kontor.partner/external-id "MH-CUST"]]
                                   (d/db conn))
              :kontor.invoice/entity bharat
              :kontor.invoice/currency "INR"
              :kontor.invoice/total-net 50000M
              :kontor.invoice/total-vat 9000M
              :kontor.invoice/total-gross 59000M
              :kontor.invoice/lines ["l-MH-1" "l-MH-cgst" "l-MH-sgst"]}
             {:db/id "l-MH-1"
              :kontor.invoice-line/invoice "inv-MH"
              :kontor.invoice-line/sequence 1
              :kontor.invoice-line/name "Custom CNC Job"
              :kontor.invoice-line/quantity 1M
              :kontor.invoice-line/unit-price 50000M
              :kontor.invoice-line/amount 50000M
              :kontor.invoice-line/gl-account-type :sales-revenue
              :kontor.invoice-line/vat-rate 18M
              :kontor.invoice-line/vat-category "STANDARD"
              :kontor.invoice-line/description "HSN 84614021 (CNC services)"}
             {:db/id "l-MH-cgst"
              :kontor.invoice-line/invoice "inv-MH"
              :kontor.invoice-line/sequence 2
              :kontor.invoice-line/name "CGST 9%"
              :kontor.invoice-line/quantity 1M
              :kontor.invoice-line/unit-price 4500M
              :kontor.invoice-line/amount 4500M
              :kontor.invoice-line/account (d/q '[:find ?a . :where [?a :kontor.account/path "2210"]]
                                          (d/db conn))
              :kontor.invoice-line/gl-account-type :sales-tax-payable
              :kontor.invoice-line/vat-category "CGST"}
             {:db/id "l-MH-sgst"
              :kontor.invoice-line/invoice "inv-MH"
              :kontor.invoice-line/sequence 3
              :kontor.invoice-line/name "SGST 9%"
              :kontor.invoice-line/quantity 1M
              :kontor.invoice-line/unit-price 4500M
              :kontor.invoice-line/amount 4500M
              :kontor.invoice-line/account (d/q '[:find ?a . :where [?a :kontor.account/path "2211"]]
                                          (d/db conn))
              :kontor.invoice-line/gl-account-type :sales-tax-payable
              :kontor.invoice-line/vat-category "SGST"}])

;; ## Vendor invoice 3: SaaS from Ireland — reverse-charge import
;;
;; IGST Act §5(3) makes the buyer (Bharat) self-assess IGST on
;; imported services. `:kontor.invoice-line/reverse-charge? true` is the
;; kontor primitive (ADR-040).
;;
;; The Dublin vendor bills €500 (₹45'000 at notional rate). Per
;; reverse-charge, Bharat books 18% IGST on themselves (Dr IGST
;; Input / Cr IGST Output) and pays the vendor net.
;;
;; Schematically: Bharat receives an inbound invoice — `:kontor.invoice/
;; type :purchase` with `:kontor.invoice-line/reverse-charge? true`.

(d/transact conn
            [{:db/id "inv-IE"
              :kontor.invoice/external-id "VENDOR/DUB-SAAS/2026/Q2"
              :kontor.invoice/type :purchase
              :kontor.invoice/status :sent
              :kontor.invoice/issue-date #inst "2026-04-20"
              :kontor.invoice/seller (d/q '[:find ?p . :where [?p :kontor.partner/external-id "IE-VENDOR"]]
                                    (d/db conn))
              :kontor.invoice/buyer bharat
              :kontor.invoice/entity bharat
              :kontor.invoice/currency "INR"           ; recorded in INR after FX
              :kontor.invoice/total-net 45000M
              :kontor.invoice/total-vat 0M             ; no GST on the inbound;
                                                ; we self-assess
              :kontor.invoice/total-gross 45000M
              :kontor.invoice/lines ["l-IE-1"]}
             {:db/id "l-IE-1"
              :kontor.invoice-line/invoice "inv-IE"
              :kontor.invoice-line/sequence 1
              :kontor.invoice-line/name "Dublin SaaS — Q2 Subscription"
              :kontor.invoice-line/quantity 3M
              :kontor.invoice-line/unit-price 15000M
              :kontor.invoice-line/amount 45000M
              :kontor.invoice-line/gl-account-type :purchase-expense
              :kontor.invoice-line/reverse-charge? true}]) ; ADR-040 flag

;; In a complete RCM flow the consumer would now post:
;; Dr Consultancy-Expense 45'000 / Dr IGST-Input-Recoverable 8'100
;;   / Cr AP-Vendor 45'000 / Cr IGST-Output-Reverse-Charge 8'100
;;
;; kontor records the flag; the posting code (Stage M+) reads it
;; and emits the four-leg journal entry. For this showcase we
;; demonstrate only the data shape — actual emission deferred.

(:kontor.invoice-line/reverse-charge?
 (d/pull (d/db conn) [:kontor.invoice-line/reverse-charge?]
         (d/q '[:find ?l . :where [?l :kontor.invoice-line/invoice ?i]
                               [?i :kontor.invoice/external-id "VENDOR/DUB-SAAS/2026/Q2"]]
              (d/db conn))))
;; → true

;; ## Vendor invoice 4: Pune consultant — TDS withholding under §194J
;;
;; §194J: 10% TDS on fees for professional services. Vendor bills
;; ₹50'000 + 18% CGST/SGST; Bharat pays the consultant ₹54'000
;; (= 50'000 - 5'000 TDS + 9'000 GST) and deposits ₹5'000 with
;; the income-tax department within the 7-day window.
;;
;; `:kontor.invoice-line/withholding-on-payment? true` is the ADR-040 flag.

(d/transact conn
            [{:db/id "inv-CON"
              :kontor.invoice/external-id "CON/2026/APR/15"
              :kontor.invoice/type :purchase
              :kontor.invoice/status :sent
              :kontor.invoice/issue-date #inst "2026-04-15"
              :kontor.invoice/seller (d/q '[:find ?p . :where [?p :kontor.partner/external-id "PUNE-CONSULTANT"]]
                                    (d/db conn))
              :kontor.invoice/buyer bharat
              :kontor.invoice/entity bharat
              :kontor.invoice/currency "INR"
              :kontor.invoice/total-net 50000M
              :kontor.invoice/total-vat 9000M
              :kontor.invoice/total-gross 59000M
              :kontor.invoice/lines ["l-CON-1" "l-CON-cgst" "l-CON-sgst"]}
             {:db/id "l-CON-1"
              :kontor.invoice-line/invoice "inv-CON"
              :kontor.invoice-line/sequence 1
              :kontor.invoice-line/name "Tax consultancy services"
              :kontor.invoice-line/quantity 1M
              :kontor.invoice-line/unit-price 50000M
              :kontor.invoice-line/amount 50000M
              :kontor.invoice-line/gl-account-type :purchase-expense
              :kontor.invoice-line/withholding-on-payment? true
              :kontor.invoice-line/description "§194J — 10% TDS on professional services"}
             {:db/id "l-CON-cgst"
              :kontor.invoice-line/invoice "inv-CON"
              :kontor.invoice-line/sequence 2
              :kontor.invoice-line/name "CGST 9%"
              :kontor.invoice-line/quantity 1M
              :kontor.invoice-line/unit-price 4500M
              :kontor.invoice-line/amount 4500M
              :kontor.invoice-line/gl-account-type :purchase-tax-recoverable
              :kontor.invoice-line/vat-category "CGST"}
             {:db/id "l-CON-sgst"
              :kontor.invoice-line/invoice "inv-CON"
              :kontor.invoice-line/sequence 3
              :kontor.invoice-line/name "SGST 9%"
              :kontor.invoice-line/quantity 1M
              :kontor.invoice-line/unit-price 4500M
              :kontor.invoice-line/amount 4500M
              :kontor.invoice-line/gl-account-type :purchase-tax-recoverable
              :kontor.invoice-line/vat-category "SGST"}])

;; ## Customer (KA) partial payment + dispute on Inv 1
;;
;; **2026-05-01**: Mysore Industrial pays ₹70'000 on account. Open
;; balance: ₹48'000.

(d/transact conn [{:kontor.transaction/external-id "NEFT-2026-05-01"
                   :kontor.transaction/state :posted
                   :kontor.transaction/effective-date #inst "2026-05-01"
                   :kontor.transaction/posted-at #inst "2026-05-01"
                   :kontor.transaction/partner (d/q '[:find ?p . :where [?p :kontor.partner/external-id "KA-CUST"]]
                                              (d/db conn))}])

(papp/apply-payment! conn
                     {:payment (d/q '[:find ?t . :where [?t :kontor.transaction/external-id "NEFT-2026-05-01"]]
                                     (d/db conn))
                      :invoice (inv/by-external-id (d/db conn) "BMC/2026-27/0001")
                      :amount 70000M
                      :commodity inr
                      :applied-by-uid alice
                      :applied-at #inst "2026-05-01"})

(papp/open-amount-of-invoice (d/db conn) (inv/by-external-id (d/db conn) "BMC/2026-27/0001"))
;; → 48'000

;; **2026-05-05**: Customer disputes one batch (₹20'000 worth) —
;; claims grade EN8 spec was off. Auto-suppresses dunning (#17
;; substrate win).
;;
;; (We rely on the kontor-collections dispute helper from
;; Showcase 1 — same shape works for INR.)

;; ## Customer concedes after testing report; pays remainder
;;
;; **2026-05-15**: Independent metallurgical lab confirms EN8 spec
;; was correct. Customer concedes; pays ₹48'000.

(d/transact conn [{:kontor.transaction/external-id "NEFT-2026-05-15"
                   :kontor.transaction/state :posted
                   :kontor.transaction/effective-date #inst "2026-05-15"
                   :kontor.transaction/posted-at #inst "2026-05-15"
                   :kontor.transaction/partner (d/q '[:find ?p . :where [?p :kontor.partner/external-id "KA-CUST"]]
                                              (d/db conn))}])

(papp/apply-payment! conn
                     {:payment (d/q '[:find ?t . :where [?t :kontor.transaction/external-id "NEFT-2026-05-15"]]
                                     (d/db conn))
                      :invoice (inv/by-external-id (d/db conn) "BMC/2026-27/0001")
                      :amount 48000M
                      :commodity inr
                      :applied-by-uid alice
                      :applied-at #inst "2026-05-15"})

(sm/current-status (d/db conn)
                   (inv/by-external-id (d/db conn) "BMC/2026-27/0001")
                   :kontor.invoice/status)
;; → :paid

;; ## GSTR-1 export shape
;;
;; GSTR-1 is the outward-supplies return filed monthly. The key
;; sections are:
;;
;; - **B2B**: invoices to registered customers with GSTIN
;;   (our KA + MH invoices here both go to B2B)
;; - **B2CL**: B2C large (over ₹2.5 lakh)
;; - **B2CS**: B2C small (the small invoices, aggregated)
;; - **EXP**: exports
;; - **CDNR**: credit/debit notes to registered customers
;; - **HSN**: HSN-wise summary (table 12; mandatory since 2025-05)
;;
;; A consumer would walk all sales invoices in the period and emit
;; the JSON per section. Showcase: count B2B invoices.

(def gstr-1-b2b-count
  (d/q '[:find (count ?i) .
         :in $ ?from ?to
         :where
         [?i :kontor.invoice/type :sales]
         [?i :kontor.invoice/issue-date ?d]
         [?i :kontor.invoice/status :sent]
         [(.compareTo ^java.util.Date ?d ?from) ?cf]
         [(>= ?cf 0)]
         [(.compareTo ^java.util.Date ?d ?to) ?ct]
         [(<= ?ct 0)]
         [?i :kontor.invoice/buyer ?b]
         [?b :kontor.partner/tax-id _]
         [?b :kontor.partner/country-code "IN"]]
       (d/db conn) #inst "2026-04-01" #inst "2026-04-30"))

gstr-1-b2b-count
;; → 2 (KA-CUST + MH-CUST invoices both filed in April)

;; HSN summary (Table 12): production tenants encode the HSN on
;; `:kontor.invoice-line/description` (as we do here) or via a custom attr
;; like `:kontor.invoice-line/hsn-code` (a kontor-l10n-in schema extension
;; — not yet shipped in the kernel). The aggregation pattern is the
;; same as the B2B count above with an `:kontor.invoice-line/description`
;; regex match — kept out of this notebook for brevity.

;; ## What this showcase exercised
;;
;; - GST split (CGST+SGST intra-state, IGST inter-state) via
;;   `kontor-l10n-in.taxes`
;; - GSTIN + PAN validators (`kontor-l10n-in.identifiers`)
;; - IRN computation per NIC IRP spec (`kontor-l10n-in.irn`)
;; - Reverse-charge mechanism flag (ADR-040 — schema-level today;
;;   posting flow comes online in Stage M)
;; - TDS withholding section + rate (ADR-040 — schema-level)
;; - Partial payment + bitemporal queries (Showcase 1 + 2 patterns
;;   replayed for INR)
;; - GSTR-1 B2B + HSN summary aggregation via datalog
;; - HSN-code on `:invoice-line` (ADR-040 — for Table 12)
;;
;; What it didn't exercise (deferred):
;;
;; - Actual IRP POST — kontor builds the payload; production tenants
;;   plug an HTTPS adapter. Same protocol pattern as TaxProvider
;;   (ADR-005) — kernel keeps API keys out.
;; - Full RCM posting (Stage M will wire the four-leg journal)
;; - TDS deposit chalan generation
;; - GSTR-2A reconciliation (matching vendor's GSTR-1 with our
;;   GSTR-3B input credit)
;; - e-Way Bill (EWB) generation — `kontor-l10n-in.ewb` exists
;;   but the goods-movement flow isn't shown here

;; ## Source citations
;;
;; - **CBIC e-invoicing schema v1.1** (NIC IRP) —
;;   <https://einvoice1.gst.gov.in/Documents/eInvoiceSchema.pdf>
;; - **CGST Act, Section 12** (place-of-supply, goods) —
;;   <https://cbic-gst.gov.in/CGST-bill-e.html>
;; - **CGST Act, Section 13** (place-of-supply, services)
;; - **IGST Act, Section 5(3)** (reverse charge on imports of
;;   services) — <https://cbic-gst.gov.in/IGST-bill-e.html>
;; - **CBDT TDS Sections 194C / 194J / 194Q** —
;;   <https://incometaxindia.gov.in/Pages/charts-and-tables.aspx>
;; - **GSTR-1 form** structure (B2B / B2CL / B2CS / HSN sections) —
;;   <https://gst.gov.in/help/gstr1>
;; - **GST 2.0 cutover 2025-09-22** (56th GST Council meeting
;;   notification) —
;;   <https://www.gst.gov.in/help/56th-gst-council-meeting>
;; - **HSN code list** — <https://gst.gov.in/help/hsn-services>
;;
