(ns kontor.regression.r2-einvoice-test
  "R2 audit — e-invoicing round-trips: does the emitted legal document
   agree with the ledger / totals kontor derives from the same invoice?

   Paths exercised:
     - DE Factur-X / XRechnung via org.mustangproject (einvoice-de)
     - IN IRN payload (l10n-in) driven off a real booked ledger
     - MX CFDI 4.0 envelope (l10n-mx)
     - BR NF-e 4.0 emitter + chave-de-acesso check digit (l10n-br)

   Green tests confirm correct behaviour; ^:kaocha/pending tests pin a
   genuine bug with a hand-derived expectation and an authority cite."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [datahike.api :as d]
            ;; DE Factur-X
            [kontor.einvoice-de.invoice :as de-inv]
            [kontor.einvoice-de.factur-x :as fx]
            ;; IN IRN + ledger bridge
            [kontor.l10n-in.preset :as in-preset]
            [kontor.l10n-in.invoice :as in-inv]
            [kontor.l10n-in.irn :as irn]
            [kontor.l10n-in.ewb :as ewb]
            ;; MX CFDI
            [kontor.l10n-mx.cfdi :as cfdi]
            ;; BR NF-e
            [kontor.l10n-br.nfe :as nfe]
            [kontor.money :as money]))

;; ============================================================================
;; helpers
;; ============================================================================

(defn bd= [a b]
  (zero? (.compareTo (bigdec a) (bigdec b))))

(defn xml-vals
  "All text contents of <ns:tag>...</ns:tag> occurrences in `xml`."
  [xml tag]
  (mapv second (re-seq (re-pattern (str "<[a-zA-Z]+:" tag "[^>]*>([^<]*)</")) xml)))

(defn xml-val [xml tag] (first (xml-vals xml tag)))

;; ============================================================================
;; DE — Factur-X / XRechnung (Mustang)
;; ============================================================================

;; Flagship-shape DE B2B invoice: 10 h @ 150 EUR + 1 travel @ 89.50, all 19 %.
(def de-sample
  {:kontor.invoice/number     "RG-2026-0001"
   :kontor.invoice/issue-date #inst "2026-01-15T00:00:00Z"
   :kontor.invoice/currency   "EUR"
   :kontor.invoice/seller     {:party/name "ACME GmbH" :party/vat-id "DE123456789"
                               :party/country "DE" :party/city "Berlin" :party/zip "10115"}
   :kontor.invoice/buyer      {:party/name "Kunden AG" :party/country "DE"}
   :kontor.invoice/items      [{:item/name "Strategieberatung" :item/quantity 10
                                :item/unit-code "HUR" :item/unit-price 150.00M
                                :item/vat-rate 19.0M :item/vat-category "S"}
                               {:item/name "Reisekosten" :item/quantity 1
                                :item/unit-code "EA" :item/unit-price 89.50M
                                :item/vat-rate 19.0M :item/vat-category "S"}]})

(deftest de-invoice-totals-hand-derived
  (testing "kontor's own totals helper — the figures a consumer books"
    (let [t (de-inv/invoice-totals de-sample)]
      ;; 10 x 150 = 1500.00 ; 1 x 89.50 = 89.50 ; net = 1589.50
      (is (bd= 1589.50M (:kontor.invoice/total-net t)))
      ;; FIXED (note 197): VAT is computed per category on the summed base
      ;; (EN16931 BR-CO-17) with HALF_UP (DIN 1333 kaufmännische Rundung) —
      ;; round(1589.50 x 19%) = round(302.005) = 302.01 — matching exactly what
      ;; org.mustangproject emits in the Factur-X document (verified: XML
      ;; CalculatedAmount/TaxTotalAmount 302.01, GrandTotalAmount 1891.51).
      (is (bd= 302.01M (:kontor.invoice/total-vat t)))
      (is (bd= 1891.51M (:kontor.invoice/total-gross t))))))

(deftest de-factur-x-required-fields-present
  (testing "EN16931 CII carries the load-bearing seller/buyer/currency fields"
    (let [xml (fx/generate-xml-string de-sample :en16931)]
      (is (str/includes? xml "CrossIndustryInvoice"))
      (is (str/includes? xml "RG-2026-0001"))
      (is (str/includes? xml "ACME GmbH"))
      (is (str/includes? xml "DE123456789") "seller VAT-ID (BT-31) surfaced")
      (is (str/includes? xml "Kunden AG"))
      (is (str/includes? xml "EUR"))))
  (testing "XRechnung profile URN reflected in document context"
    (is (str/includes? (fx/generate-xml-string de-sample :xrechnung) "xrechnung"))))

;; --- FIXED (note 197): emitted Factur-X total == kontor's booked total --------
;; kontor.einvoice-de.invoice now computes VAT per tax-category on the summed
;; base with HALF_UP (EN16931 BR-CO-17 + DIN 1333), matching what Mustang emits:
;; 1589.50 x 19% = 302.005 -> 302.01, GrandTotal 1891.51. Previously it summed
;; per-line HALF_EVEN VAT (302.00 / 1891.50) and shipped a document one cent
;; higher than it booked. Verified against the actually-emitted XML.
(deftest de-factur-x-grand-total-matches-booked-total
  (let [t         (de-inv/invoice-totals de-sample)
        booked    (:kontor.invoice/total-gross t)      ; 1891.51
        booked-vat (:kontor.invoice/total-vat t)        ; 302.01
        xml       (fx/generate-xml-string de-sample :en16931)
        doc-gross (bigdec (xml-val xml "GrandTotalAmount"))
        doc-vat   (bigdec (xml-val xml "TaxTotalAmount"))]
    (is (bd= booked-vat doc-vat)
        "output VAT booked must equal the VAT stated on the emitted Factur-X")
    (is (bd= booked doc-gross)
        "grand total booked must equal the grand total on the emitted Factur-X")))

;; A second, larger-magnitude case: many sub-cent-VAT lines. 10 lines x 0.10 EUR
;; = 1.00 net. Category-level (both kontor and the document now): 1.00 x 19% =
;; 0.19 — where the old per-line sum was 0.20. FIXED (note 197).
(deftest de-factur-x-per-line-vat-aggregation
  (let [inv (assoc de-sample :kontor.invoice/items
                   (vec (repeat 10 {:item/name "Kleinposten" :item/quantity 1
                                    :item/unit-code "EA" :item/unit-price 0.10M
                                    :item/vat-rate 19.0M :item/vat-category "S"})))
        booked-vat (:kontor.invoice/total-vat (de-inv/invoice-totals inv))  ; 0.19
        doc-vat    (bigdec (xml-val (fx/generate-xml-string inv :en16931)
                                    "TaxTotalAmount"))]                       ; 0.19
    (is (bd= booked-vat doc-vat)
        "booked VAT (0.19) equals the emitted document VAT (0.19)")
    (is (bd= 0.19M booked-vat) "category-level VAT is 0.19, not the per-line-summed 0.20")))

;; ============================================================================
;; IN — IRN payload driven off a REAL booked ledger
;; ============================================================================

(def in-supplier-gstin "27AAPFU0939F1ZV")   ; Maharashtra (state code 27)
(def in-buyer-gstin    "29AAACR4849R1ZL")   ; Karnataka   (state code 29)

(defn- post-in-interstate! []
  "Book an MH->KA inter-state B2B sale: 10 units x INR 1000 @ 18% IGST."
  (let [conn (in-preset/create-in-db)]
    (in-inv/post-in-invoice!
     conn
     {:kontor.invoice/external-id    "INV-2026-IN-0001"
      :kontor.invoice/issue-date     #inst "2026-05-11T00:00:00Z"
      :kontor.invoice/supplier-state "27"
      :kontor.invoice/place-of-supply "29"
      :kontor.invoice/lines [{:kontor.invoice-line/quantity 10M
                              :kontor.invoice-line/unit-price 1000.00M
                              :kontor.invoice-line/tax-rate 0.18M}]})
    conn))

(defn- bal-by-code [db code]
  (or (d/q '[:find (sum ?amt) . :in $ ?c :where
             [?a :kontor.account/code ?c]
             [?p :kontor.posting/account ?a]
             [?p :kontor.posting/amount ?amt]] db code)
      0M))

(deftest in-invoice-ledger-postings
  (testing "GST inter-state supply books IGST only (no CGST/SGST)"
    (let [conn (post-in-interstate!)
          db   (d/db conn)]
      ;; 121100 AR (debit) = gross ; 410000 Sales (credit) = net ; 331300 IGST
      (is (bd= 11800.00M (bal-by-code db "121100")) "AR = 10000 + 1800 IGST")
      (is (bd= -10000.00M (bal-by-code db "410000")) "Sales revenue = net")
      (is (bd= -1800.00M (bal-by-code db "331300")) "Output IGST = 18% of 10000")
      (is (bd= 0M (bal-by-code db "331100")) "no CGST on inter-state supply")
      (is (bd= 0M (bal-by-code db "331200")) "no SGST on inter-state supply"))))

(deftest in-irn-payload-matches-ledger
  (testing "The IRN ValDtls must reconcile to the booked ledger figures"
    (let [conn (post-in-interstate!)
          db   (d/db conn)
          ass-val (.negate ^java.math.BigDecimal (bal-by-code db "410000"))   ; 10000.00
          igst    (.negate ^java.math.BigDecimal (bal-by-code db "331300"))   ; 1800.00
          tot     (bal-by-code db "121100")                                    ; 11800.00
          payload (irn/build-payload
                   {:tran {:tax-scheme :gst :supply-type :b2b}
                    :doc  {:no "INV-2026-IN-0001"
                           :date #inst "2026-05-11T00:00:00Z" :type :inv}
                    :seller {:gstin in-supplier-gstin :legal-name "Acme India Pvt Ltd"
                             :addr1 "Plot 12" :loc "Mumbai" :pin 400001 :state "27"}
                    :buyer  {:gstin in-buyer-gstin :legal-name "Beta Karnataka Ltd"
                             :addr1 "100 MG Road" :loc "Bangalore" :pin 560001
                             :state "29" :pos "29"}
                    :items [{:sl-no 1 :prd-desc "Widget" :hsn-code "84799090"
                             :qty 10M :unit "PCS" :unit-price 1000.00M
                             :tot-amt 10000.00M :assess-amt 10000.00M :gst-rate 18M
                             :igst-amt igst :cgst-amt 0M :sgst-amt 0M
                             :tot-item-val tot}]
                    :val {:ass-val ass-val :igst-val igst
                          :cgst-val 0M :sgst-val 0M :tot-inv-val tot}})]
      (testing "ValDtls echoes the ledger"
        (is (bd= ass-val (get-in payload ["ValDtls" "AssVal"])))
        (is (bd= igst    (get-in payload ["ValDtls" "IgstVal"])))
        (is (bd= tot     (get-in payload ["ValDtls" "TotInvVal"]))))
      (testing "IRN totals are internally consistent (AssVal + IGST = TotInvVal)"
        (is (bd= tot (.add ^java.math.BigDecimal (get-in payload ["ValDtls" "AssVal"])
                           ^java.math.BigDecimal (get-in payload ["ValDtls" "IgstVal"])))))
      (testing "item TotItemVal reconciles to the invoice total"
        (is (bd= tot (get-in payload ["ItemList" 0 "TotItemVal"]))))
      (testing "place-of-supply routing (inter-state MH 27 -> KA 29)"
        (is (= "27" (get-in payload ["SellerDtls" "Stcd"])))
        (is (= "29" (get-in payload ["BuyerDtls" "Pos"]))))
      (testing "doc date rendered DD/MM/YYYY per NIC schema"
        (is (= "11/05/2026" (get-in payload ["DocDtls" "Dt"]))))
      (testing "JSON round-trips"
        (is (string? (irn/payload-json payload)))))))

(deftest in-irn-hash-authority-vector
  (testing "IRN = SHA-256(gstin_docno_FY_type), lowercase hex, deterministic"
    ;; independently computed:
    ;;   printf '27AAPFU0939F1ZV_INV-2026-IN-0001_2026-27_INV' | sha256sum
    (let [h (irn/compute-irn {:supplier-gstin in-supplier-gstin
                              :doc-no "INV-2026-IN-0001"
                              :doc-date #inst "2026-05-11T00:00:00Z"
                              :doc-type "INV"})]
      (is (= "b73b645b43412ccbc8edc9f132722e6029e3290dd54821cef1895a7ac09662e4" h))
      (is (re-matches #"[0-9a-f]{64}" h)))))

(deftest in-financial-year-and-ewb-validity
  (testing "Indian FY runs Apr 1 -> Mar 31"
    (is (= "2026-27" (irn/financial-year #inst "2026-05-11")))
    (is (= "2025-26" (irn/financial-year #inst "2026-03-31"))))
  (testing "EWB validity: 1 day / 200 km regular, part thereof rounds up"
    (is (= 1 (ewb/validity-days 200)))
    (is (= 2 (ewb/validity-days 201)))
    (is (= 2 (ewb/validity-days 400)))
    (is (= 3 (ewb/validity-days 401)))))

;; ============================================================================
;; MX — CFDI 4.0 envelope
;; ============================================================================

(deftest mx-cfdi-envelope-consistency
  (testing "SubTotal + IVA traslado = Total; required nodes + type present"
    (let [subtotal 1000.00M
          iva      160.00M          ; 16 % of 1000
          total    (.add subtotal iva)
          el (cfdi/invoice-element
              {:cfdi/serie "A" :cfdi/folio "123"
               :cfdi/fecha #inst "2026-05-11T16:30:00Z"
               :cfdi/forma-pago "03" :cfdi/no-certificado "30001000000400002434"
               :cfdi/certificado "" :cfdi/subtotal subtotal :cfdi/total total
               :cfdi/moneda "MXN" :cfdi/tipo-de-comprobante :income
               :cfdi/lugar-expedicion "45050"
               :cfdi/emisor {:rfc "AAA010101AAA" :nombre "Emisor SA"
                             :regimen-fiscal "601"}
               :cfdi/receptor {:rfc "XAXX010101000" :nombre "Publico"
                               :domicilio-fiscal "45050"
                               :regimen-fiscal-receptor "616" :uso-cfdi "S01"}
               :cfdi/conceptos [{:clave-prodserv "01010101" :cantidad 1M
                                 :clave-unidad "H87" :descripcion "Widget"
                                 :valor-unitario 1000.00M :importe 1000.00M
                                 :impuestos {:traslados [{:base 1000.00M :impuesto "002"
                                                          :tipo-factor "Tasa"
                                                          :tasa-o-cuota 0.160000M
                                                          :importe 160.00M}]}}]
               :cfdi/impuestos {:total-impuestos-trasladados iva
                                :traslados [{:base 1000.00M :impuesto "002"
                                             :tipo-factor "Tasa"
                                             :tasa-o-cuota 0.160000M :importe 160.00M}]}})
          xml (cfdi/emit-string el)
          attr (fn [a] (second (re-find (re-pattern (str a "=\"([^\"]*)\"")) xml)))]
      (is (= "I" (attr "TipoDeComprobante")) "Ingreso")
      (is (= "4.0" (attr "Version")))
      (is (bd= subtotal (attr "SubTotal")))
      (is (bd= total (attr "Total")))
      (is (bd= total (.add (bigdec (attr "SubTotal")) iva))
          "Total must equal SubTotal + trasladed IVA")
      (is (str/includes? xml "AAA010101AAA") "emisor RFC")
      (is (str/includes? xml "XAXX010101000") "receptor RFC (publico general)")
      (is (= "2026-05-11T16:30:00" (attr "Fecha")) "Fecha yyyy-MM-ddTHH:mm:ss"))))

;; ============================================================================
;; BR — NF-e 4.0 emitter + chave-de-acesso check digit
;; ============================================================================

(defn- brl [s] (money/money (bigdec s) :BRL))

(deftest br-nfe-check-digit-mod11
  (testing "cDV = mod-11 (weights 2..9 right-to-left) of the 43-digit prefix"
    ;; independently computed in Python: prefix ->  sum 621, 621 mod 11 = 5,
    ;; cDV = 11 - 5 = 6.
    (is (= "6" (nfe/compute-cdv "3526051234567800012355001000000012312345678")))
    (is (= "6" (nfe/access-key-cdv "3526051234567800012355001000000012312345678"))
        "43-char key -> computed cDV")
    (is (= "6" (nfe/access-key-cdv "35260512345678000123550010000000123123456786"))
        "44-char key -> its final digit"))
  (testing "idDest discriminator: intra=1, interstate=2, foreign=3"
    (is (= "1" (nfe/determine-id-dest "35" "35" nil)))
    (is (= "2" (nfe/determine-id-dest "35" "29" nil)))
    (is (= "3" (nfe/determine-id-dest "35" "29" "US")))))

(deftest br-nfe-emit-interstate
  (testing "SP->RJ interstate NF-e emits idDest 2 + line/total ICMS figures"
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
                                 :number "200" :municipality-code 3304557 :state "RJ"
                                 :cep "20000000" :country-code "BR"}
                 :nfe/items [{:code "P1" :name "Widget" :ncm "84799090" :cfop "6102"
                              :unit "UN" :quantity (brl "10") :unit-price (brl "100")
                              :line-total (brl "1000")
                              :taxes {:icms {:cst "00" :orig "0" :base (brl "1000")
                                             :rate 0.18M :amount (brl "180")}}}]
                 :nfe/totals {:icms-base (brl "1000") :icms (brl "180")
                              :products (brl "1000") :ipi (brl "0")
                              :pis (brl "16.50") :cofins (brl "76")
                              :invoice-total (brl "1180")}}))]
      (is (= "2" (xml-val xml "idDest")) "SP(35) -> RJ(33) is interstate")
      (is (= "6" (xml-val xml "cDV")) "computed check digit for 43-digit prefix")
      (is (= "1000.00" (xml-val xml "vProd")) "product total")
      (is (= "180.00" (xml-val xml "vICMS")) "ICMS on line")
      (is (= "18.0000" (xml-val xml "pICMS")) "ICMS rate 0.18 -> 18.0000")
      (is (= "1180.00" (xml-val xml "vNF")) "invoice total")
      (is (str/includes? xml "84799090") "NCM present"))))
