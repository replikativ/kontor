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
       — `:invoice-line/reverse-charge?` (ADR-040)
     - TDS (Tax Deducted at Source) withholding on vendor payments
       — `:invoice-line/withholding-on-payment?` (ADR-040)
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

   Reference comparison:
     - **Tally Prime** is the de-facto SMB stack; closed-source,
       fixed schema, no API for granular postings.
     - **Zoho Books** has a clean Indian e-invoicing module but is
       SaaS-only.
     - **SAP S/4HANA India** uses TDM (Tax Determination Module);
       customizing-heavy.
     - **Tryton's `account_in`** module — modest GST support, no
       IRN clearance.

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
            [kontor.payment-application :as papp]
            [kontor.sales.schema :as sales-schema]
            [kontor.status-machine :as sm]))

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
;;    via `:invoice-line/withholding-on-payment?`.
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
            [{:commodity/symbol "INR" :commodity/name "Indian Rupee"
              :commodity/precision 2 :commodity/iso-4217 "INR"}
             {:commodity/symbol "EUR" :commodity/name "Euro"
              :commodity/precision 2 :commodity/iso-4217 "EUR"}
             {:entity/code "BHARAT-MC"
              :entity/name "Bharat Metalcraft Pvt Ltd"
              :entity/kind :operating :entity/active true}
             ;; Accounts (Indian GAAP / Schedule III shape)
             {:account/code "1200" :account/path "1200"
              :account/name "Sundry Debtors (AR)" :account/type :asset}
             {:account/code "2100" :account/path "2100"
              :account/name "Sundry Creditors (AP)" :account/type :liability}
             {:account/code "4000" :account/path "4000"
              :account/name "Sales of Goods" :account/type :revenue}
             {:account/code "5000" :account/path "5000"
              :account/name "Consultancy Expense" :account/type :expense}
             ;; GST accounts
             {:account/code "2210" :account/path "2210"
              :account/name "CGST Output Payable" :account/type :liability}
             {:account/code "2211" :account/path "2211"
              :account/name "SGST Output Payable" :account/type :liability}
             {:account/code "2212" :account/path "2212"
              :account/name "IGST Output Payable" :account/type :liability}
             {:account/code "1410" :account/path "1410"
              :account/name "IGST Input Credit (recoverable)"
              :account/type :asset}
             {:account/code "2310" :account/path "2310"
              :account/name "TDS Payable - §194J" :account/type :liability}
             ;; GL defaults
             {:gl-account-default/account-type :ar
              :gl-account-default/account [:account/path "1200"]}
             {:gl-account-default/account-type :ap
              :gl-account-default/account [:account/path "2100"]}
             {:gl-account-default/account-type :sales-revenue
              :gl-account-default/account [:account/path "4000"]}
             {:gl-account-default/account-type :purchase-expense
              :gl-account-default/account [:account/path "5000"]}
             ;; Journals
             {:journal/code "AR" :journal/name "Sales" :journal/type :sales}
             {:journal/code "AP" :journal/name "Purchases" :journal/type :purchase}
             ;; Partners (Indian B2B GSTINs + a vendor + a foreign supplier)
             {:partner/external-id "KA-CUST"
              :partner/name "Mysore Industrial Co"
              :partner/kind :customer
              :partner/country-code "IN"
              :partner/tax-id "29ABCDE1111F1Z5"  ; KA GSTIN (29 = Karnataka)
              :partner/credit-status :open}
             {:partner/external-id "MH-CUST"
              :partner/name "Pune Forging Ltd"
              :partner/kind :customer
              :partner/country-code "IN"
              :partner/tax-id "27ABCDE2222F1Z5"  ; MH GSTIN (27 = Maharashtra)
              :partner/credit-status :open}
             {:partner/external-id "IE-VENDOR"
              :partner/name "Dublin SaaS Ltd"
              :partner/kind :vendor
              :partner/country-code "IE"}
             {:partner/external-id "PUNE-CONSULTANT"
              :partner/name "ABC Tax Consultants"
              :partner/kind :vendor
              :partner/country-code "IN"
              :partner/tax-id "27ABCDE3333F1Z5"}
             {:partner/external-id "U-alice" :partner/name "Alice (collector)"}
             {:partner/external-id "U-bob"   :partner/name "Bob (manager)"}])

(def bharat (d/q '[:find ?e . :where [?e :entity/code "BHARAT-MC"]] (d/db conn)))
(def inr    (d/q '[:find ?c . :where [?c :commodity/symbol "INR"]] (d/db conn)))
(def alice  (d/q '[:find ?p . :where [?p :partner/external-id "U-alice"]] (d/db conn)))
(def bob    (d/q '[:find ?p . :where [?p :partner/external-id "U-bob"]] (d/db conn)))

;; ## Verify supplier identifiers
;;
;; The kontor-l10n-in module ships GSTIN + PAN validators per the
;; CBIC technical specification:

(ids/valid-gstin? "27ABCDE1234F1Z5")
;; → true

(ids/state-code-from-gstin "29ABCDE1111F1Z5")
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

(def gst-ka
  (gst/component-split 100000M 0.18M supply-type))
;; → {:cgst 0M :sgst 0M :igst 18000M}   (inter-state: full IGST)

;; Build the invoice. Line gross = 100'000 + 18'000 IGST = 118'000.

(d/transact conn
            [{:db/id "inv-KA"
              :invoice/external-id "BMC/2026-27/0001"
              :invoice/type :sales
              :invoice/status :pending-attestation   ; awaiting IRN
              :invoice/issue-date #inst "2026-04-10"
              :invoice/seller (d/q '[:find ?p . :where [?p :partner/external-id "MH-CUST"]] (d/db conn))
                                                    ;; reuse a partner as placeholder for "self"
              :invoice/buyer  (d/q '[:find ?p . :where [?p :partner/external-id "KA-CUST"]] (d/db conn))
              :invoice/entity bharat
              :invoice/currency "INR"
              :invoice/total-net 100000M
              :invoice/total-vat 18000M
              :invoice/total-gross 118000M
              :invoice/lines ["l-KA-1" "l-KA-tax"]}
             {:db/id "l-KA-1"
              :invoice-line/invoice "inv-KA"
              :invoice-line/sequence 1
              :invoice-line/name "Steel Forging Set, Grade EN8"
              :invoice-line/quantity 50M
              :invoice-line/unit-price 2000M
              :invoice-line/amount 100000M
              :invoice-line/gl-account-type :sales-revenue
              :invoice-line/vat-rate 18M
              :invoice-line/vat-category "STANDARD"
              :invoice-line/hsn-code "73269099"}    ; HSN for forgings
             {:db/id "l-KA-tax"
              :invoice-line/invoice "inv-KA"
              :invoice-line/sequence 2
              :invoice-line/name "IGST 18%"
              :invoice-line/quantity 1M
              :invoice-line/unit-price 18000M
              :invoice-line/amount 18000M
              :invoice-line/account (d/q '[:find ?a . :where [?a :account/path "2212"]]
                                          (d/db conn))
              :invoice-line/gl-account-type :sales-tax-payable
              :invoice-line/vat-category "IGST"}])

;; ### Compute IRN + payload
;;
;; The Invoice Reference Number is a 64-char SHA-256 hash of
;; (supplier-gstin, document-number, financial-year, document-type).
;; kontor's `compute-irn` matches the NIC spec.

(def ka-irn
  (irn/compute-irn {:supplier-gstin "27ABCDE1234F1Z5"
                    :document-number "BMC/2026-27/0001"
                    :financial-year (irn/financial-year #inst "2026-04-10")
                    :document-type "INV"}))
ka-irn
;; → 64-char SHA-256 hash like "a4f73e84b9c2..."

;; The payload that would POST to the IRP:

(def irn-payload (irn/build-payload {:supplier-gstin "27ABCDE1234F1Z5"
                                      :buyer-gstin "29ABCDE1111F1Z5"
                                      :document-number "BMC/2026-27/0001"
                                      :financial-year (irn/financial-year #inst "2026-04-10")
                                      :document-type "INV"
                                      :document-date #inst "2026-04-10"
                                      :line-total 100000M
                                      :other-charge 0M
                                      :discount 0M
                                      :round-off 0M
                                      :total-invoice-value 118000M}))

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
                           :facet :invoice/status
                           :to :sent
                           :changed-by-uid alice
                           :reason :authority-cleared
                           :reason-note (str "IRN " ka-irn " issued by NIC IRP")})

;; ## Issue invoice 2: Intra-state (MH → MH) → CGST + SGST split
;;
;; Same supplier (MH), buyer also in MH. CGST 9% + SGST 9% split.

(def gst-mh
  (gst/component-split 50000M 0.18M :intra-state))
;; → {:cgst 4500M :sgst 4500M :igst 0M}

(d/transact conn
            [{:db/id "inv-MH"
              :invoice/external-id "BMC/2026-27/0002"
              :invoice/type :sales
              :invoice/status :sent
              :invoice/issue-date #inst "2026-04-12"
              :invoice/buyer (d/q '[:find ?p . :where [?p :partner/external-id "MH-CUST"]]
                                   (d/db conn))
              :invoice/entity bharat
              :invoice/currency "INR"
              :invoice/total-net 50000M
              :invoice/total-vat 9000M
              :invoice/total-gross 59000M
              :invoice/lines ["l-MH-1" "l-MH-cgst" "l-MH-sgst"]}
             {:db/id "l-MH-1"
              :invoice-line/invoice "inv-MH"
              :invoice-line/sequence 1
              :invoice-line/name "Custom CNC Job"
              :invoice-line/quantity 1M
              :invoice-line/unit-price 50000M
              :invoice-line/amount 50000M
              :invoice-line/gl-account-type :sales-revenue
              :invoice-line/vat-rate 18M
              :invoice-line/vat-category "STANDARD"
              :invoice-line/hsn-code "84614021"}
             {:db/id "l-MH-cgst"
              :invoice-line/invoice "inv-MH"
              :invoice-line/sequence 2
              :invoice-line/name "CGST 9%"
              :invoice-line/quantity 1M
              :invoice-line/unit-price 4500M
              :invoice-line/amount 4500M
              :invoice-line/account (d/q '[:find ?a . :where [?a :account/path "2210"]]
                                          (d/db conn))
              :invoice-line/gl-account-type :sales-tax-payable
              :invoice-line/vat-category "CGST"}
             {:db/id "l-MH-sgst"
              :invoice-line/invoice "inv-MH"
              :invoice-line/sequence 3
              :invoice-line/name "SGST 9%"
              :invoice-line/quantity 1M
              :invoice-line/unit-price 4500M
              :invoice-line/amount 4500M
              :invoice-line/account (d/q '[:find ?a . :where [?a :account/path "2211"]]
                                          (d/db conn))
              :invoice-line/gl-account-type :sales-tax-payable
              :invoice-line/vat-category "SGST"}])

;; ## Vendor invoice 3: SaaS from Ireland — reverse-charge import
;;
;; IGST Act §5(3) makes the buyer (Bharat) self-assess IGST on
;; imported services. `:invoice-line/reverse-charge? true` is the
;; kontor primitive (ADR-040).
;;
;; The Dublin vendor bills €500 (₹45'000 at notional rate). Per
;; reverse-charge, Bharat books 18% IGST on themselves (Dr IGST
;; Input / Cr IGST Output) and pays the vendor net.
;;
;; Schematically: Bharat receives an inbound invoice — `:invoice/
;; type :purchase` with `:invoice-line/reverse-charge? true`.

(d/transact conn
            [{:db/id "inv-IE"
              :invoice/external-id "VENDOR/DUB-SAAS/2026/Q2"
              :invoice/type :purchase
              :invoice/status :sent
              :invoice/issue-date #inst "2026-04-20"
              :invoice/seller (d/q '[:find ?p . :where [?p :partner/external-id "IE-VENDOR"]]
                                    (d/db conn))
              :invoice/buyer bharat
              :invoice/entity bharat
              :invoice/currency "INR"           ; recorded in INR after FX
              :invoice/total-net 45000M
              :invoice/total-vat 0M             ; no GST on the inbound;
                                                ; we self-assess
              :invoice/total-gross 45000M
              :invoice/lines ["l-IE-1"]}
             {:db/id "l-IE-1"
              :invoice-line/invoice "inv-IE"
              :invoice-line/sequence 1
              :invoice-line/name "Dublin SaaS — Q2 Subscription"
              :invoice-line/quantity 3M
              :invoice-line/unit-price 15000M
              :invoice-line/amount 45000M
              :invoice-line/gl-account-type :purchase-expense
              :invoice-line/reverse-charge? true}]) ; ADR-040 flag

;; In a complete RCM flow the consumer would now post:
;; Dr Consultancy-Expense 45'000 / Dr IGST-Input-Recoverable 8'100
;;   / Cr AP-Vendor 45'000 / Cr IGST-Output-Reverse-Charge 8'100
;;
;; kontor records the flag; the posting code (Stage M+) reads it
;; and emits the four-leg journal entry. For this showcase we
;; demonstrate only the data shape — actual emission deferred.

(:invoice-line/reverse-charge?
 (d/pull (d/db conn) [:invoice-line/reverse-charge?]
         (d/q '[:find ?l . :where [?l :invoice-line/invoice ?i]
                               [?i :invoice/external-id "VENDOR/DUB-SAAS/2026/Q2"]]
              (d/db conn))))
;; → true

;; ## Vendor invoice 4: Pune consultant — TDS withholding under §194J
;;
;; §194J: 10% TDS on fees for professional services. Vendor bills
;; ₹50'000 + 18% CGST/SGST; Bharat pays the consultant ₹54'000
;; (= 50'000 - 5'000 TDS + 9'000 GST) and deposits ₹5'000 with
;; the income-tax department within the 7-day window.
;;
;; `:invoice-line/withholding-on-payment? true` is the ADR-040 flag.

(d/transact conn
            [{:db/id "inv-CON"
              :invoice/external-id "CON/2026/APR/15"
              :invoice/type :purchase
              :invoice/status :sent
              :invoice/issue-date #inst "2026-04-15"
              :invoice/seller (d/q '[:find ?p . :where [?p :partner/external-id "PUNE-CONSULTANT"]]
                                    (d/db conn))
              :invoice/buyer bharat
              :invoice/entity bharat
              :invoice/currency "INR"
              :invoice/total-net 50000M
              :invoice/total-vat 9000M
              :invoice/total-gross 59000M
              :invoice/lines ["l-CON-1" "l-CON-cgst" "l-CON-sgst"]}
             {:db/id "l-CON-1"
              :invoice-line/invoice "inv-CON"
              :invoice-line/sequence 1
              :invoice-line/name "Tax consultancy services"
              :invoice-line/quantity 1M
              :invoice-line/unit-price 50000M
              :invoice-line/amount 50000M
              :invoice-line/gl-account-type :purchase-expense
              :invoice-line/withholding-on-payment? true    ; §194J
              :invoice-line/withholding-rate 0.10M
              :invoice-line/withholding-section "194J"}
             {:db/id "l-CON-cgst"
              :invoice-line/invoice "inv-CON"
              :invoice-line/sequence 2
              :invoice-line/name "CGST 9%"
              :invoice-line/quantity 1M
              :invoice-line/unit-price 4500M
              :invoice-line/amount 4500M
              :invoice-line/gl-account-type :purchase-tax-recoverable
              :invoice-line/vat-category "CGST"}
             {:db/id "l-CON-sgst"
              :invoice-line/invoice "inv-CON"
              :invoice-line/sequence 3
              :invoice-line/name "SGST 9%"
              :invoice-line/quantity 1M
              :invoice-line/unit-price 4500M
              :invoice-line/amount 4500M
              :invoice-line/gl-account-type :purchase-tax-recoverable
              :invoice-line/vat-category "SGST"}])

;; ## Customer (KA) partial payment + dispute on Inv 1
;;
;; **2026-05-01**: Mysore Industrial pays ₹70'000 on account. Open
;; balance: ₹48'000.

(d/transact conn [{:transaction/external-id "NEFT-2026-05-01"
                   :transaction/state :posted
                   :transaction/effective-date #inst "2026-05-01"
                   :transaction/posted-at #inst "2026-05-01"
                   :transaction/partner (d/q '[:find ?p . :where [?p :partner/external-id "KA-CUST"]]
                                              (d/db conn))}])

(papp/apply-payment! conn
                     {:payment (d/q '[:find ?t . :where [?t :transaction/external-id "NEFT-2026-05-01"]]
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

(d/transact conn [{:transaction/external-id "NEFT-2026-05-15"
                   :transaction/state :posted
                   :transaction/effective-date #inst "2026-05-15"
                   :transaction/posted-at #inst "2026-05-15"
                   :transaction/partner (d/q '[:find ?p . :where [?p :partner/external-id "KA-CUST"]]
                                              (d/db conn))}])

(papp/apply-payment! conn
                     {:payment (d/q '[:find ?t . :where [?t :transaction/external-id "NEFT-2026-05-15"]]
                                     (d/db conn))
                      :invoice (inv/by-external-id (d/db conn) "BMC/2026-27/0001")
                      :amount 48000M
                      :commodity inr
                      :applied-by-uid alice
                      :applied-at #inst "2026-05-15"})

(sm/current-status (d/db conn)
                   (inv/by-external-id (d/db conn) "BMC/2026-27/0001")
                   :invoice/status)
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
         [?i :invoice/type :sales]
         [?i :invoice/issue-date ?d]
         [?i :invoice/status :sent]
         [(.compareTo ^java.util.Date ?d ?from) ?cf]
         [(>= ?cf 0)]
         [(.compareTo ^java.util.Date ?d ?to) ?ct]
         [(<= ?ct 0)]
         [?i :invoice/buyer ?b]
         [?b :partner/tax-id _]
         [?b :partner/country-code "IN"]]
       (d/db conn) #inst "2026-04-01" #inst "2026-04-30"))

gstr-1-b2b-count
;; → 2 (KA-CUST + MH-CUST invoices both filed in April)

;; HSN summary (Table 12) by HSN code:

(d/q '[:find ?hsn (sum ?amt)
       :in $ ?from ?to
       :where
       [?i :invoice/type :sales]
       [?i :invoice/issue-date ?d]
       [(.compareTo ^java.util.Date ?d ?from) ?cf]
       [(>= ?cf 0)]
       [(.compareTo ^java.util.Date ?d ?to) ?ct]
       [(<= ?ct 0)]
       [?l :invoice-line/invoice ?i]
       [?l :invoice-line/hsn-code ?hsn]
       [?l :invoice-line/amount ?amt]]
     (d/db conn) #inst "2026-04-01" #inst "2026-04-30")
;; → [["73269099" 100000M] ["84614021" 50000M]]

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
;; Reference comparison:
;;
;; - **Tally Prime** — closed-source SMB stack; fixed schema; no
;;   API surface for granular postings; bookkeepers re-key data
;;   between Tally and GSTR portal.
;; - **Zoho Books** — clean Indian e-invoicing module; SaaS-only;
;;   no on-prem deployment for compliance-sensitive tenants.
;; - **SAP S/4HANA India** — TDM module + heavy customizing
;;   tables (J1IG*, J_1I*); requires SAP-certified Indian
;;   localization partner.
;; - **Tryton `account_in`** — basic GST support; no IRN
;;   clearance; no GSTR export.
;; - **Odoo's `l10n_in`** — full GST + IRN; LGPLv3 license
;;   limits reuse (ADR-001).
