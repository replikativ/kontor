(ns kontor.regression.r3-einvoice-multicountry-test
  "R3 audit — e-invoicing beyond DE Factur-X: adversarial book-vs-document
   round-trips across the four clearance/e-invoice paths kontor ships.

     - DE Factur-X / XRechnung   (einvoice-de, org.mustangproject)
     - IN IRN payload            (l10n-in.irn)
     - MX CFDI 4.0 envelope      (l10n-mx.cfdi)
     - BR NF-e 4.0 emitter       (l10n-br.nfe)

   The round-2 DE fix (note 197) found a one-cent book-vs-document mismatch:
   kontor booked per-line HALF_EVEN VAT (302.00) while org.mustangproject
   emitted per-category HALF_UP VAT (302.01), shipping a document one cent
   off the ledger. This round probes IN / MX / BR for the SAME class of
   defect — where the number a consumer books diverges from the number the
   emitted legal document carries — plus required-field completeness.

   Green deftests confirm the booked ledger reconciles to the emitted
   document to the cent. ^:kaocha/pending deftests pin a genuine gap with a
   hand-derived expectation and an authority/spec cite (they are expected to
   FAIL until the gap is closed).

   Every asserted monetary figure is hand-derived in a comment above it."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [datahike.api :as d]
            ;; DE Factur-X
            [kontor.einvoice-de.invoice :as de-inv]
            ;; IN IRN + ledger bridge
            [kontor.l10n-in.preset :as in-preset]
            [kontor.l10n-in.invoice :as in-inv]
            [kontor.l10n-in.irn :as irn]
            ;; MX CFDI + ledger bridge
            [kontor.l10n-mx.preset :as mx-preset]
            [kontor.l10n-mx.invoice :as mx-inv]
            [kontor.l10n-mx.cfdi :as cfdi]
            ;; BR NF-e + ledger bridge
            [kontor.l10n-br.preset :as br-preset]
            [kontor.l10n-br.invoice :as br-inv]
            [kontor.l10n-br.nfe :as nfe]
            [kontor.money :as money]))

;; ============================================================================
;; helpers
;; ============================================================================

(defn bd= [a b]
  (zero? (.compareTo (bigdec a) (bigdec b))))

(defn bal-by-code
  "Signed sum of all posting amounts against the account with `code`."
  [db code]
  (or (d/q '[:find (sum ?amt) . :with ?p :in $ ?c :where
             [?a :kontor.account/code ?c]
             [?p :kontor.posting/account ?a]
             [?p :kontor.posting/amount ?amt]] db code)
      0M))

(defn neg [^java.math.BigDecimal x] (.negate x))

(defn xml-vals [xml tag]
  (mapv second (re-seq (re-pattern (str "<[a-zA-Z]+:" tag "[^>]*>([^<]*)</")) xml)))
(defn xml-val [xml tag] (first (xml-vals xml tag)))
(defn attr-of [xml a] (second (re-find (re-pattern (str a "=\"([^\"]*)\"")) xml)))

;; ============================================================================
;; DE — Factur-X category-VAT (note 197) — pure book helper, no Mustang
;; ============================================================================

;; Re-pins the note-197 invariant WITHOUT booting Mustang: the consumer books
;; VAT per tax-category on the summed base, HALF_UP (EN16931 BR-CO-17 + DIN
;; 1333). round(1589.50 x 19%) = round(302.005) = 302.01. This is the figure
;; org.mustangproject stamps on the emitted Factur-X (round-2 verified the XML
;; GrandTotalAmount 1891.51 / TaxTotalAmount 302.01).
(def de-single-rate
  {:kontor.invoice/number "RG-2026-0001"
   :kontor.invoice/issue-date #inst "2026-01-15T00:00:00Z"
   :kontor.invoice/currency "EUR"
   :kontor.invoice/seller {:party/name "ACME GmbH" :party/vat-id "DE123456789"
                           :party/country "DE" :party/city "Berlin" :party/zip "10115"}
   :kontor.invoice/buyer {:party/name "Kunden AG" :party/country "DE"}
   :kontor.invoice/items [{:item/name "Strategieberatung" :item/quantity 10
                           :item/unit-code "HUR" :item/unit-price 150.00M
                           :item/vat-rate 19.0M :item/vat-category "S"}
                          {:item/name "Reisekosten" :item/quantity 1
                           :item/unit-code "EA" :item/unit-price 89.50M
                           :item/vat-rate 19.0M :item/vat-category "S"}]})

(deftest de-category-vat-half-up-single-rate
  (testing "note-197: category VAT is round-once HALF_UP, not per-line HALF_EVEN"
    (let [t (de-inv/invoice-totals de-single-rate)]
      (is (bd= 1589.50M (:kontor.invoice/total-net t)) "10x150 + 89.50")
      ;; 1589.50 x 19% = 302.005 -> HALF_UP -> 302.01 (per-line sum would be 302.00)
      (is (bd= 302.01M (:kontor.invoice/total-vat t)))
      (is (bd= 1891.51M (:kontor.invoice/total-gross t))))))

(deftest de-category-vat-multi-rate-aggregation
  (testing "each VAT category rounds independently, HALF_UP on its own base"
    ;; 19% line: net 1589.50 -> 302.005 -> 302.01 (HALF_UP)
    ;; 7%  line: net    7.50 ->   0.525 ->   0.53 (HALF_UP; HALF_EVEN would give 0.52)
    (let [inv (assoc de-single-rate :kontor.invoice/items
                     [{:item/name "Beratung" :item/quantity 1 :item/unit-code "EA"
                       :item/unit-price 1589.50M :item/vat-rate 19.0M :item/vat-category "S"}
                      {:item/name "Fachbuch" :item/quantity 1 :item/unit-code "EA"
                       :item/unit-price 7.50M :item/vat-rate 7.0M :item/vat-category "AA"}])
          t (de-inv/invoice-totals inv)
          breakdown (:kontor.invoice/vat-breakdown t)
          by-rate (into {} (map (juxt :vat/rate :vat/tax)) breakdown)]
      (is (bd= 302.01M (get by-rate 19.0M)) "19% category, round-once HALF_UP")
      (is (bd= 0.53M (get by-rate 7.0M)) "7% category, 0.525 -> HALF_UP 0.53")
      (is (bd= 1597.00M (:kontor.invoice/total-net t)))
      (is (bd= 302.54M (:kontor.invoice/total-vat t)))
      (is (bd= 1899.54M (:kontor.invoice/total-gross t))))))

;; ============================================================================
;; IN — IRN payload driven off a REAL booked ledger
;; ============================================================================

(def in-supplier-gstin "27AAPFU0939F1ZV")   ; Maharashtra (27)
(def in-buyer-gstin    "29AAACR4849R1ZL")   ; Karnataka   (29)

(defn- post-in! [invoice]
  (let [conn (in-preset/create-in-db)]
    (in-inv/post-in-invoice! conn invoice)
    conn))

(deftest in-interstate-per-line-igst-summation
  (testing "GST is per-LINE rounded then summed (NIC's per-item model), and
            that is what the IRN ItemList/ValDtls must echo"
    ;; Two lines, net 2.75 each, 18% inter-state (MH 27 -> KA 29 => IGST).
    ;; per line : 2.75 x 0.18 = 0.4950 -> HALF_EVEN 2dp = 0.50 (drop 5, 9 is odd -> up)
    ;; ledger   : IGST = 0.50 + 0.50 = 1.00 ; net = 5.50 ; AR = 6.50
    ;; round-ONCE on the summed base would be round(5.50 x 0.18) = round(0.99) = 0.99
    ;; NIC validates IgstVal against the SUM of per-item IgstAmt, so 1.00 is correct
    ;; and a per-invoice category recompute (a la DE) would be WRONG here.
    (let [conn (post-in! {:kontor.invoice/external-id "INV-IN-2LINE"
                          :kontor.invoice/issue-date #inst "2026-05-11T00:00:00Z"
                          :kontor.invoice/supplier-state "27"
                          :kontor.invoice/place-of-supply "29"
                          :kontor.invoice/lines
                          [{:kontor.invoice-line/quantity 1M
                            :kontor.invoice-line/unit-price 2.75M
                            :kontor.invoice-line/tax-rate 0.18M}
                           {:kontor.invoice-line/quantity 1M
                            :kontor.invoice-line/unit-price 2.75M
                            :kontor.invoice-line/tax-rate 0.18M}]})
          db (d/db conn)
          igst (neg (bal-by-code db "331300"))
          net  (neg (bal-by-code db "410000"))
          ar   (bal-by-code db "121100")]
      (is (bd= 1.00M igst) "IGST = per-line-summed 0.50+0.50 = 1.00 (NIC per-item)")
      (is (bd= 5.50M net))
      (is (bd= 6.50M ar) "AR = net + IGST")
      (is (not (bd= 0.99M igst)) "round-once (0.99) is NOT what GST books here")
      ;; IRN item block echoes per-line IgstAmt; ValDtls echoes their sum.
      (let [payload (irn/build-payload
                     {:tran {:tax-scheme :gst :supply-type :b2b}
                      :doc {:no "INV-IN-2LINE" :date #inst "2026-05-11T00:00:00Z" :type :inv}
                      :seller {:gstin in-supplier-gstin :legal-name "Acme India Pvt Ltd"
                               :addr1 "Plot 12" :loc "Mumbai" :pin 400001 :state "27"}
                      :buyer {:gstin in-buyer-gstin :legal-name "Beta Karnataka Ltd"
                              :addr1 "100 MG Road" :loc "Bangalore" :pin 560001
                              :state "29" :pos "29"}
                      :items [{:sl-no 1 :prd-desc "Widget" :hsn-code "84799090"
                               :qty 1M :unit "PCS" :unit-price 2.75M :tot-amt 2.75M
                               :assess-amt 2.75M :gst-rate 18M :igst-amt 0.50M
                               :tot-item-val 3.25M}
                              {:sl-no 2 :prd-desc "Widget" :hsn-code "84799090"
                               :qty 1M :unit "PCS" :unit-price 2.75M :tot-amt 2.75M
                               :assess-amt 2.75M :gst-rate 18M :igst-amt 0.50M
                               :tot-item-val 3.25M}]
                      :val {:ass-val net :igst-val igst :tot-inv-val ar}})]
        (is (bd= igst (get-in payload ["ValDtls" "IgstVal"])) "ValDtls IGST == ledger")
        (is (bd= ar (get-in payload ["ValDtls" "TotInvVal"])) "TotInvVal == booked AR")
        (is (bd= igst (.add ^java.math.BigDecimal (get-in payload ["ItemList" 0 "IgstAmt"])
                            ^java.math.BigDecimal (get-in payload ["ItemList" 1 "IgstAmt"])))
            "sum of per-item IgstAmt == ValDtls IgstVal")))))

(deftest in-intrastate-cgst-sgst-split
  (testing "intra-state supply splits the headline into CGST+SGST, ledger reconciles"
    ;; net 1000, 18% intra-state (MH 27 -> MH 27): CGST 9% = 90.00, SGST 9% = 90.00
    (let [conn (post-in! {:kontor.invoice/external-id "INV-IN-INTRA"
                          :kontor.invoice/issue-date #inst "2026-05-11T00:00:00Z"
                          :kontor.invoice/supplier-state "27"
                          :kontor.invoice/place-of-supply "27"
                          :kontor.invoice/lines
                          [{:kontor.invoice-line/quantity 1M
                            :kontor.invoice-line/unit-price 1000.00M
                            :kontor.invoice-line/tax-rate 0.18M}]})
          db (d/db conn)]
      (is (bd= 90.00M (neg (bal-by-code db "331100"))) "CGST = 9%")
      (is (bd= 90.00M (neg (bal-by-code db "331200"))) "SGST = 9%")
      (is (bd= 0M (bal-by-code db "331300")) "no IGST on intra-state")
      (is (bd= 1180.00M (bal-by-code db "121100")) "AR = 1000 + 90 + 90"))))

;; ============================================================================
;; MX — CFDI 4.0 driven off a REAL booked ledger (cash-basis IVA)
;; ============================================================================

(deftest mx-credit-sale-iva-matches-cfdi-traslado
  (testing "credit sale books IVA to 208.02 (no cobrado); the CFDI still
            declares the full traslado, and the two agree to the cent"
    ;; net 1000, region :general -> 16% IVA = 160.00. Credit sale (no flags):
    ;;   Dr 105.01.001 AR         1160.00
    ;;   Cr 401.01.001 Ingresos   1000.00
    ;;   Cr 208.02.001 IVA no-cob  160.00
    (let [conn (mx-preset/create-mx-db)]
      (mx-inv/post-mx-invoice!
       conn {:kontor.invoice/external-id "INV-MX-CR"
             :kontor.invoice/issue-date #inst "2026-05-11T16:30:00Z"
             :kontor.invoice/lines
             [{:kontor.invoice-line/quantity 1M
               :kontor.invoice-line/unit-price 1000.00M}]})
      (let [db (d/db conn)
            iva-nocob (neg (bal-by-code db "208.02.001"))
            iva-cob   (bal-by-code db "208.01.001")
            net       (neg (bal-by-code db "401.01.001"))
            ar        (bal-by-code db "105.01.001")]
        (is (bd= 160.00M iva-nocob) "output IVA sits on 208.02 (no cobrado)")
        (is (bd= 0M iva-cob) "nothing on 208.01 (cobrado) until payment received")
        (is (bd= 1000.00M net))
        (is (bd= 1160.00M ar))
        ;; The CFDI a consumer emits from the same invoice: SubTotal=net,
        ;; traslado=booked IVA, Total=SubTotal+traslado.
        (let [subtotal net
              iva iva-nocob
              total (.add ^java.math.BigDecimal subtotal ^java.math.BigDecimal iva)
              xml (cfdi/emit-string
                   (cfdi/invoice-element
                    {:cfdi/serie "A" :cfdi/folio "1" :cfdi/fecha #inst "2026-05-11T16:30:00Z"
                     :cfdi/forma-pago "99" :cfdi/no-certificado "30001000000400002434"
                     :cfdi/certificado "" :cfdi/subtotal subtotal :cfdi/total total
                     :cfdi/moneda "MXN" :cfdi/tipo-de-comprobante :income
                     :cfdi/lugar-expedicion "45050"
                     :cfdi/emisor {:rfc "AAA010101AAA" :nombre "Emisor SA" :regimen-fiscal "601"}
                     :cfdi/receptor {:rfc "XAXX010101000" :nombre "Publico"
                                     :domicilio-fiscal "45050"
                                     :regimen-fiscal-receptor "616" :uso-cfdi "S01"}
                     :cfdi/conceptos [{:clave-prodserv "01010101" :cantidad 1M
                                       :clave-unidad "H87" :descripcion "Widget"
                                       :valor-unitario subtotal :importe subtotal
                                       :impuestos {:traslados [{:base subtotal :impuesto "002"
                                                                :tipo-factor "Tasa"
                                                                :tasa-o-cuota 0.160000M
                                                                :importe iva}]}}]
                     :cfdi/impuestos {:total-impuestos-trasladados iva
                                      :traslados [{:base subtotal :impuesto "002"
                                                   :tipo-factor "Tasa"
                                                   :tasa-o-cuota 0.160000M :importe iva}]}}))]
          (is (bd= 1000.00M (attr-of xml "SubTotal")) "CFDI SubTotal == booked net")
          (is (bd= 1160.00M (attr-of xml "Total")) "CFDI Total == booked AR")
          (is (bd= iva-nocob (bigdec (attr-of xml "TotalImpuestosTrasladados")))
              "CFDI declared IVA traslado == booked 208.02 IVA"))))))

;; ============================================================================
;; BR — NF-e 4.0 driven off a REAL booked ledger
;; ============================================================================

(defn- post-br! [invoice]
  (let [conn (br-preset/create-br-db)]
    (br-inv/post-br-invoice! conn invoice)
    conn))

(deftest br-goods-tax-math-reconciles
  (testing "intra-state goods sale books ICMS + (Tema-69) PIS/COFINS; the
            per-tax ledger figures match the hand-derived compute"
    ;; net 1000, SP->SP intra-state, 18% ICMS, non-cumulative PIS/COFINS.
    ;;   ICMS  = 1000 x 0.18                      = 180.00
    ;;   base  = net + IPI - ICMS = 1000 - 180    = 820.00  (STF Tema 69)
    ;;   PIS   = 820 x 0.0165                     =  13.53
    ;;   COFINS= 820 x 0.076                      =  62.32
    (let [conn (post-br! {:kontor.invoice/external-id "INV-BR-GOODS"
                          :kontor.invoice/issue-date #inst "2026-05-11T00:00:00Z"
                          :kontor.invoice/from-state "SP"
                          :kontor.invoice/to-state "SP"
                          :kontor.invoice/lines
                          [{:kontor.invoice-line/quantity 1M
                            :kontor.invoice-line/unit-price 1000.00M
                            :kontor.invoice-line/tax-classification :goods}]})
          db (d/db conn)]
      (is (bd= 180.00M (neg (bal-by-code db "2.01.04.01.01"))) "ICMS a Recolher")
      (is (bd= 13.53M (neg (bal-by-code db "2.01.04.01.03"))) "PIS (Tema-69 base 820)")
      (is (bd= 62.32M (neg (bal-by-code db "2.01.04.01.04"))) "COFINS (Tema-69 base 820)")
      (is (bd= 1000.00M (neg (bal-by-code db "3.01.01.01.01"))) "gross revenue = net")
      ;; NF-e destacado figures a consumer reads off the same compute:
      (let [xml (nfe/emit-string
                 (nfe/invoice-element
                  {:nfe/id-data {:uf-code 35 :random-code "12345678"
                                 :operation-nature "Venda" :series 1 :number 12
                                 :issue-date "2026-05-11T10:00:00-03:00"
                                 :municipality-code 3550308
                                 :access-key "3526051234567800012355001000000012312345678"}
                   :nfe/issuer {:cnpj "12345678000123" :name "Emit LTDA" :street "Rua X"
                                :number "100" :municipality-code 3550308 :state "SP"
                                :cep "01000000" :state-tax-id "110042490114" :tax-regime 3}
                   :nfe/recipient {:cnpj "99999999000191" :name "Dest LTDA" :street "Av Y"
                                   :number "200" :municipality-code 3550308 :state "SP"
                                   :cep "01001000" :country-code "BR"}
                   :nfe/items [{:code "P1" :name "Widget" :ncm "84799090" :cfop "5102"
                                :unit "UN" :quantity (money/money 1M :BRL)
                                :unit-price (money/money 1000M :BRL)
                                :line-total (money/money 1000M :BRL)
                                :taxes {:icms {:cst "00" :orig "0"
                                               :base (money/money 1000M :BRL)
                                               :rate 0.18M :amount (money/money 180M :BRL)}}}]
                   :nfe/totals {:icms-base (money/money 1000M :BRL)
                                :icms (money/money 180M :BRL)
                                :products (money/money 1000M :BRL)
                                :ipi (money/money 0M :BRL)
                                :pis (money/money 13.53M :BRL)
                                :cofins (money/money 62.32M :BRL)
                                :invoice-total (money/money 1000M :BRL)}}))]
        (is (= "180.00" (xml-val xml "vICMS")) "NF-e ICMS destacado == ledger ICMS")
        (is (= "13.53" (xml-val xml "vPIS")) "NF-e PIS == ledger PIS")
        (is (= "62.32" (xml-val xml "vCOFINS")) "NF-e COFINS == ledger COFINS")
        (is (= "1" (xml-val xml "idDest")) "SP -> SP is intra-state")))))

;; ============================================================================
;; PENDING (NEW) — genuine gaps pinned with hand-derived expectations
;; ============================================================================

;; PENDING(NEW): BR indirect taxes are "por dentro" (tax-INCLUSIVE) — ICMS,
;; PIS and COFINS are embedded in the price the customer pays, not added on
;; top. The NF-e total vNF for a normal (regime-normal) goods sale = vProd
;; (+ vIPI + vST + vFrete - vDesc); ICMS/PIS/COFINS are *destacado* only and
;; do NOT sum into vNF (SEFAZ NF-e Manual de Integracao 4.0, tag W16 vNF
;; composition; ICMS "imposto por dentro", Lei Kandir / RICMS). But
;; kontor.l10n-br.invoice books AR = net + ICMS + PIS + COFINS + IPI
;; (l10n_br/taxes.clj :goods branch, total-gross = net + every tax), i.e. it
;; treats these por-dentro taxes as tax-EXCLUSIVE and adds them on top. So the
;; booked receivable (1255.85) overstates what the customer owes and cannot
;; equal the vNF (1000.00) a spec-correct NF-e carries for this sale — a
;; R$255.85 book-vs-document divergence, the same class as the DE note-197
;; cent bug but two orders of magnitude larger. There is no bridge or
;; documented reconciliation between the two. Odoo keeps ICMS inside
;; price_total and never adds it to amount_total (l10n_br account.tax
;; l10n_br_tax_ipi/"included in price"), so its NF-e vNF and the receivable
;; agree.
(deftest ^:kaocha/pending br-por-dentro-ar-should-equal-vnf
  (testing "booked AR should equal the NF-e vNF for a normal goods sale (IPI=0)"
    (let [conn (post-br! {:kontor.invoice/external-id "INV-BR-PORDENTRO"
                          :kontor.invoice/issue-date #inst "2026-05-11T00:00:00Z"
                          :kontor.invoice/from-state "SP"
                          :kontor.invoice/to-state "SP"
                          :kontor.invoice/lines
                          [{:kontor.invoice-line/quantity 1M
                            :kontor.invoice-line/unit-price 1000.00M
                            :kontor.invoice-line/tax-classification :goods}]})
          db (d/db conn)
          ar (bal-by-code db "1.01.03.01.01")]
      ;; vNF (por dentro, IPI=0) = vProd = 1000.00. Booked AR = 1255.85. FAILS.
      (is (bd= 1000.00M ar)
          "AR (por-dentro) should be 1000.00, not net+ICMS+PIS+COFINS = 1255.85"))))

;; PENDING(NEW): BR NF-e emitter has no CBS / IBS / IS element groups. NT
;; 2025.002 (LC 214/2025 Reforma Tributaria) makes <IBSCBS>/<gIBSCBS>/
;; <cClassTrib>/<IS> MANDATORY on production NF-e from 2026-01 for CRT-3
;; taxpayers (2026 pilot rates CBS 0.9% / IBS 0.1%). The module's own
;; docstring flags this P0: "Without these groups, kontor cannot emit a valid
;; 2026 NF-e." The taxes engine even ships the pilot rate constants
;; (cbs-rate-2026-pilot 0.009M / ibs-rate-2026-pilot 0.001M) but the invoice
;; posting builder never computes them and the emitter never renders them.
(deftest ^:kaocha/pending br-nfe-emits-cbs-ibs-2026
  (testing "a 2026 goods NF-e should carry the CBS/IBS pilot groups"
    ;; net 1000 -> CBS 0.9% = 9.00, IBS 0.1% = 1.00 should appear in the XML.
    (let [xml (nfe/emit-string
               (nfe/invoice-element
                {:nfe/id-data {:uf-code 35 :random-code "12345678"
                               :operation-nature "Venda" :series 1 :number 12
                               :issue-date "2026-05-11T10:00:00-03:00"
                               :municipality-code 3550308
                               :access-key "3526051234567800012355001000000012312345678"}
                 :nfe/issuer {:cnpj "12345678000123" :name "Emit LTDA" :street "Rua X"
                              :number "100" :municipality-code 3550308 :state "SP"
                              :cep "01000000" :state-tax-id "110042490114" :tax-regime 3}
                 :nfe/recipient {:cnpj "99999999000191" :name "Dest LTDA" :street "Av Y"
                                 :number "200" :municipality-code 3550308 :state "SP"
                                 :cep "01001000" :country-code "BR"}
                 :nfe/items [{:code "P1" :name "Widget" :ncm "84799090" :cfop "5102"
                              :unit "UN" :quantity (money/money 1M :BRL)
                              :unit-price (money/money 1000M :BRL)
                              :line-total (money/money 1000M :BRL)
                              :taxes {:icms {:cst "00" :orig "0"
                                             :base (money/money 1000M :BRL)
                                             :rate 0.18M :amount (money/money 180M :BRL)}}}]
                 :nfe/totals {:icms-base (money/money 1000M :BRL)
                              :icms (money/money 180M :BRL)
                              :products (money/money 1000M :BRL)
                              :ipi (money/money 0M :BRL)
                              :pis (money/money 13.53M :BRL)
                              :cofins (money/money 62.32M :BRL)
                              :invoice-total (money/money 1000M :BRL)}}))]
      (is (str/includes? xml "IBSCBS")
          "NT 2025.002 IBS/CBS group must be present on a 2026 NF-e"))))

;; PENDING(NEW): the IRN emitter (kontor.l10n-in.irn/build-payload) does no
;; totals validation — it formats whatever ValDtls / ItemList the caller
;; hands it, so a payload whose ValDtls or per-item TotItemVal disagrees with
;; the ledger (or with itself) is emitted silently and only bounces at the
;; NIC IRP. NIC schema validation rule requires TotItemVal = AssAmt + tax
;; components per item and TotInvVal = AssVal + tax + RndOff - Discount within
;; +/-1. There is no book->payload bridge: Odoo derives the whole JSON from
;; the posted move (l10n_in_edi/models/account_move.py:626
;; _l10n_in_edi_generate_invoice_json), so the document cannot diverge from
;; the ledger. kontor should at minimum reject an internally-inconsistent
;; payload.
(deftest ^:kaocha/pending in-irn-rejects-inconsistent-totals
  (testing "build-payload should reject an item whose TotItemVal != AssAmt + tax"
    (is (thrown? clojure.lang.ExceptionInfo
                 (irn/build-payload
                  {:tran {:tax-scheme :gst :supply-type :b2b}
                   :doc {:no "INV-BAD" :date #inst "2026-05-11T00:00:00Z" :type :inv}
                   :seller {:gstin in-supplier-gstin :legal-name "Acme" :addr1 "Plot 12"
                            :loc "Mumbai" :pin 400001 :state "27"}
                   :buyer {:gstin in-buyer-gstin :legal-name "Beta" :addr1 "MG Rd"
                           :loc "Bangalore" :pin 560001 :state "29" :pos "29"}
                   ;; AssAmt 10000 + IGST 1800 = 11800, but TotItemVal claims 99999.99
                   :items [{:sl-no 1 :prd-desc "Widget" :hsn-code "84799090"
                            :qty 1M :unit "PCS" :unit-price 10000.00M :tot-amt 10000.00M
                            :assess-amt 10000.00M :gst-rate 18M :igst-amt 1800.00M
                            :tot-item-val 99999.99M}]
                   :val {:ass-val 10000.00M :igst-val 1800.00M :tot-inv-val 11800.00M}}))
        "an item TotItemVal inconsistent with AssAmt+IGST must be rejected")))

;; PENDING(NEW): CFDI MetodoPago is not derived from the cash-basis routing.
;; A credit sale routes IVA to 208.02 (no cobrado) because payment has NOT
;; been received at issuance -> under SAT Anexo 20 the CFDI MetodoPago MUST be
;; "PPD" (Pago en Parcialidades o Diferido) with FormaPago "99" (Por definir);
;; "PUE" asserts payment-in-one-exhibition and is rejected by SAT when the
;; invoice is unpaid. kontor.l10n-mx.cfdi/invoice-element defaults MetodoPago
;; to :single-payment ("PUE") and offers no cash-sale?-aware derivation, so a
;; consumer who books a credit sale (deferred IVA) and emits the default CFDI
;; ships a SAT-inconsistent MetodoPago. Odoo sets l10n_mx_edi_payment_method /
;; MetodoPago from the payment/reconciliation state.
(deftest ^:kaocha/pending mx-cfdi-metodo-pago-follows-cash-basis
  (testing "credit sale (IVA deferred to 208.02) should emit MetodoPago PPD"
    (let [xml (cfdi/emit-string
               (cfdi/invoice-element
                {:cfdi/serie "A" :cfdi/folio "1" :cfdi/fecha #inst "2026-05-11T16:30:00Z"
                 :cfdi/forma-pago "99" :cfdi/no-certificado "30001000000400002434"
                 :cfdi/certificado "" :cfdi/subtotal 1000.00M :cfdi/total 1160.00M
                 :cfdi/moneda "MXN" :cfdi/tipo-de-comprobante :income
                 :cfdi/lugar-expedicion "45050"
                 ;; NB: no :cfdi/metodo-pago passed -> emitter defaults to PUE.
                 :cfdi/emisor {:rfc "AAA010101AAA" :nombre "Emisor SA" :regimen-fiscal "601"}
                 :cfdi/receptor {:rfc "XAXX010101000" :nombre "Publico"
                                 :domicilio-fiscal "45050"
                                 :regimen-fiscal-receptor "616" :uso-cfdi "S01"}
                 :cfdi/conceptos [{:clave-prodserv "01010101" :cantidad 1M
                                   :clave-unidad "H87" :descripcion "Widget"
                                   :valor-unitario 1000.00M :importe 1000.00M}]}))]
      (is (= "PPD" (attr-of xml "MetodoPago"))
          "deferred-IVA credit sale must be PPD, not the defaulted PUE"))))
