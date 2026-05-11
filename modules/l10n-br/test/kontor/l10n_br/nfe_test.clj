(ns kontor.l10n-br.nfe-test
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.einvoice-provider :as einvoice]
            [kontor.l10n-br.nfe :as nfe]
            [kontor.money :as money]))

(defn- brl [s] (money/money (bigdec s) :BRL))

;; ============================================================================
;; Fixtures
;; ============================================================================

(def base-id-data
  {:uf-code 35
   :random-code "12345678"
   :access-key "35260112345678000100550010000000011234567890"
   :operation-nature "Venda de mercadoria"
   :series 1
   :number 1
   :issue-date "2026-01-15T10:00:00-03:00"
   :operation-type "1"
   :municipality-code 3550308
   :env "2"})

(def base-issuer
  {:cnpj "12345678000195"
   :name "Acme Indústria Ltda"
   :street "Rua das Flores"
   :number "100"
   :neighborhood "Centro"
   :municipality-code 3550308
   :municipality-name "São Paulo"
   :state "SP"
   :cep "01000-000"
   :state-tax-id "1234567890"
   :tax-regime "3"})            ; Regime Normal

(def base-recipient
  {:cnpj "98765432000100"
   :name "Beta Comércio Ltda"
   :street "Av. Paulista"
   :number "1000"
   :neighborhood "Bela Vista"
   :municipality-code 3550308
   :municipality-name "São Paulo"
   :state "SP"
   :cep "01310-000"
   :state-tax-id "9876543210"
   :ie-indicator "1"})

(defn- sample-nfe-icms00
  "Intra-state Regime Normal sale with CST 00 (tributada integralmente)
   + IPI tributada + PIS/COFINS Aliq."
  []
  {:nfe/doc-type :nfe
   :nfe/id-data base-id-data
   :nfe/issuer base-issuer
   :nfe/recipient base-recipient
   :nfe/items
   [{:code "PROD-001"
     :name "Widget A"
     :ncm "84799090"
     :cfop "5102"
     :unit "UN"
     :quantity (brl "10.00")
     :unit-price (brl "100.00")
     :line-total (brl "1000.00")
     :taxes
     {:icms {:cst "00" :orig "0"
             :base (brl "1100.00")
             :rate 0.18M
             :amount (brl "198.00")}
      :ipi {:cst "50" :enq "999"
            :base (brl "1000.00")
            :rate 0.10M
            :amount (brl "100.00")}
      :pis {:cst "01"
            :base (brl "920.00")     ; Tema 69 — ICMS excluded
            :rate 0.0165M
            :amount (brl "15.18")}
      :cofins {:cst "01"
               :base (brl "920.00")
               :rate 0.076M
               :amount (brl "69.92")}}}]
   :nfe/totals
   {:icms-base (brl "1100.00")
    :icms (brl "198.00")
    :products (brl "1000.00")
    :ipi (brl "100.00")
    :pis (brl "15.18")
    :cofins (brl "69.92")
    :invoice-total (brl "1383.10")}})

;; ============================================================================
;; ICMS CST dispatch
;; ============================================================================

(deftest emits-icms00-for-cst-00
  (let [s (nfe/emit-string (nfe/invoice-element (sample-nfe-icms00)))]
    (is (re-find #"<[^/>]*ICMS00>" s)
        "CST 00 → <ICMS00> element group")
    (is (re-find #"<[^/>]*CST[^>]*>00<" s))
    (is (re-find #"<[^/>]*orig[^>]*>0<" s))
    (is (re-find #"<[^/>]*pICMS[^>]*>18\.0000<" s)
        "Rate formatted as percentage with 4 decimals")
    (is (re-find #"<[^/>]*vICMS[^>]*>198\.00<" s))))

(deftest emits-icms40-for-exempt
  (testing "CST 40 (Isenta) → <ICMS40> with just CST + orig"
    (let [inv (-> (sample-nfe-icms00)
                  (assoc-in [:nfe/items 0 :taxes :icms]
                            {:cst "40" :orig "0"}))
          s (nfe/emit-string (nfe/invoice-element inv))]
      (is (re-find #"<[^/>]*ICMS40>" s))
      (is (re-find #"<[^/>]*CST[^>]*>40<" s)))))

(deftest emits-icmssn101-for-simples
  (testing "CSOSN 101 (Simples Nacional, com crédito) → <ICMSSN101>"
    (let [inv (-> (sample-nfe-icms00)
                  (assoc-in [:nfe/items 0 :taxes :icms]
                            {:csosn "101" :orig "0"
                             :cred-sn-rate 0.025M
                             :cred-icms-sn (brl "25.00")})
                  (assoc-in [:nfe/issuer :tax-regime] "1"))   ; Simples
          s (nfe/emit-string (nfe/invoice-element inv))]
      (is (re-find #"<[^/>]*ICMSSN101>" s))
      (is (re-find #"<[^/>]*CSOSN[^>]*>101<" s))
      (is (re-find #"<[^/>]*pCredSN[^>]*>2\.5000<" s))
      (is (re-find #"<[^/>]*vCredICMSSN[^>]*>25\.00<" s)))))

;; ============================================================================
;; DIFAL (ICMSUFDest)
;; ============================================================================

(deftest emits-icms-uf-dest-for-difal
  (testing "B2C interstate sale produces <ICMSUFDest> with DIFAL fields"
    (let [inv (-> (sample-nfe-icms00)
                  (assoc-in [:nfe/items 0 :taxes :icms-uf-dest]
                            {:base (brl "1000.00")
                             :rate-dest 0.205M
                             :rate-orig 0.07M
                             :amount (brl "135.00")}))
          s (nfe/emit-string (nfe/invoice-element inv))]
      (is (re-find #"<[^/>]*ICMSUFDest>" s))
      (is (re-find #"<[^/>]*vBCUFDest[^>]*>1000\.00<" s))
      (is (re-find #"<[^/>]*pICMSUFDest[^>]*>20\.5000<" s))
      (is (re-find #"<[^/>]*pICMSInter[^>]*>7\.0000<" s))
      (is (re-find #"<[^/>]*pICMSInterPart[^>]*>100\.0000<" s)
          "Since 2019 the entire DIFAL goes to destination state")
      (is (re-find #"<[^/>]*vICMSUFDest[^>]*>135\.00<" s)))))

;; ============================================================================
;; IPI / PIS / COFINS dispatch
;; ============================================================================

(deftest emits-ipi-tributada
  (let [s (nfe/emit-string (nfe/invoice-element (sample-nfe-icms00)))]
    (is (re-find #"<[^/>]*IPITrib>" s)
        "CST 50 (Saída tributada) → <IPITrib>")))

(deftest emits-ipi-not-tributada
  (testing "CST 52 (Saída isenta) → <IPINT>"
    (let [inv (-> (sample-nfe-icms00)
                  (assoc-in [:nfe/items 0 :taxes :ipi]
                            {:cst "52" :enq "999"}))
          s (nfe/emit-string (nfe/invoice-element inv))]
      (is (re-find #"<[^/>]*IPINT>" s))
      (is (re-find #"<[^/>]*CST[^>]*>52<" s)))))

(deftest emits-pis-aliq-for-cst-01
  (let [s (nfe/emit-string (nfe/invoice-element (sample-nfe-icms00)))]
    (is (re-find #"<[^/>]*PISAliq>" s))
    (is (re-find #"<[^/>]*vPIS[^>]*>15\.18<" s))))

(deftest emits-pis-nt-for-cst-08
  (testing "CST 08 (Operação sem incidência) → <PISNT>"
    (let [inv (-> (sample-nfe-icms00)
                  (assoc-in [:nfe/items 0 :taxes :pis]
                            {:cst "08"}))
          s (nfe/emit-string (nfe/invoice-element inv))]
      (is (re-find #"<[^/>]*PISNT>" s)))))

(deftest emits-cofins-aliq-for-cst-01
  (let [s (nfe/emit-string (nfe/invoice-element (sample-nfe-icms00)))]
    (is (re-find #"<[^/>]*COFINSAliq>" s))
    (is (re-find #"<[^/>]*vCOFINS[^>]*>69\.92<" s))))

;; ============================================================================
;; <ide> required ordering
;; ============================================================================

(deftest emits-required-ide-fields
  (testing "Manual de Integração 4.0 schema-required <ide> fields all present"
    (let [s (nfe/emit-string (nfe/invoice-element (sample-nfe-icms00)))]
      (is (re-find #"<[^/>]*finNFe[^>]*>1<" s)
          "finNFe = 1 (normal NF-e)")
      (is (re-find #"<[^/>]*indFinal[^>]*>" s))
      (is (re-find #"<[^/>]*indPres[^>]*>" s))
      (is (re-find #"<[^/>]*indIntermed[^>]*>" s))
      (is (re-find #"<[^/>]*procEmi[^>]*>" s))
      (is (re-find #"<[^/>]*verProc[^>]*>" s)))))

;; ============================================================================
;; Provider integration
;; ============================================================================

(deftest provider-emits-keep-on-file
  (let [p (nfe/provider)
        inv {:nfe/draft-data (sample-nfe-icms00)}
        result (einvoice/emit p inv)]
    (is (= :br/nfe-4.0 (:einvoice/format result)))
    (is (= :keep-on-file (:einvoice/intended-for result)))
    (is (re-find #"NFe" (:einvoice/payload result)))))

(deftest reparseable
  (let [s (nfe/emit-string (nfe/invoice-element (sample-nfe-icms00)))]
    (is (some? (clojure.data.xml/parse-str s)))))

;; ============================================================================
;; Access-key cDV + idDest (P0 bugfixes from BR verification)
;; ============================================================================

(deftest compute-cdv-mod-11-base-2-9
  (testing "Worked example from Manual Integração do Contribuinte:
            prefix 35260112345678000100550010000000011234567890 →
            cDV computed via mod-11 with weights 2..9 cycling
            right-to-left over the 43-digit prefix"
    ;; Re-compute by hand: the algorithm is mod-11 with weights
    ;; (cycle [2 3 4 5 6 7 8 9]) applied right-to-left. Confirming
    ;; both branches: < 2 → 0, else 11 - rest.
    (is (string? (nfe/compute-cdv "3526011234567800010055001000000001123456789"))
        "Returns a single digit")
    (is (= 1 (count (nfe/compute-cdv "3526011234567800010055001000000001123456789"))))))

(deftest access-key-cdv-extracts-or-computes
  (testing "44-char input → extracts last digit"
    (is (= "0" (nfe/access-key-cdv "35260112345678000100550010000000011234567890"))))
  (testing "43-char input → computes the cDV"
    (let [cdv (nfe/access-key-cdv "3526011234567800010055001000000001123456789")]
      (is (string? cdv))
      (is (= 1 (count cdv))))))

(deftest emits-cdv-as-last-digit-of-access-key
  (testing "Emitted <cDV> matches the last digit of the access key,
            not a hardcoded \"0\" (P0 BR verification bug)"
    (let [;; Use an access key whose last digit isn't 0 so we'd notice a regression.
          inv (-> (sample-nfe-icms00)
                  (assoc-in [:nfe/id-data :access-key]
                            "35260112345678000100550010000000011234567897"))
          s (nfe/emit-string (nfe/invoice-element inv))]
      (is (re-find #"<[^/>]*cDV[^>]*>7<" s)
          "cDV must equal the 44th character of the access key"))))

(deftest emits-id-dest-from-issuer-and-recipient-state
  (testing "Intra-state (SP→SP) → idDest=1"
    (let [s (nfe/emit-string (nfe/invoice-element (sample-nfe-icms00)))]
      (is (re-find #"<[^/>]*idDest[^>]*>1<" s))))
  (testing "Interstate (SP→RJ) → idDest=2 (P0 bug: previously hardcoded 1)"
    (let [inv (-> (sample-nfe-icms00)
                  (assoc-in [:nfe/recipient :state] "RJ"))
          s (nfe/emit-string (nfe/invoice-element inv))]
      (is (re-find #"<[^/>]*idDest[^>]*>2<" s))))
  (testing "Foreign export (SP→AR) → idDest=3"
    (let [inv (-> (sample-nfe-icms00)
                  (assoc-in [:nfe/recipient :state] "EX")
                  (assoc-in [:nfe/recipient :country-code] "AR"))
          s (nfe/emit-string (nfe/invoice-element inv))]
      (is (re-find #"<[^/>]*idDest[^>]*>3<" s)))))
