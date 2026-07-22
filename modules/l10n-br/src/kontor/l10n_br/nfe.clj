(ns kontor.l10n-br.nfe
  "NF-e (Nota Fiscal Eletrônica) — XML generator for Brazilian invoices.

   Verified against:
     - SEFAZ NF-e Manual de Integração 4.01 (portal.nfe.fazenda.gov.br)
     - Nota Técnica 2018.001 (NF-e 4.00)
     - Schema PL_009_V4 (leiauteNFe_v4.00.xsd)

   Per ADR-017 this module ships the **pure XML emitter** for NF-e
   4.0. Signing (ICP-Brasil cert) and SEFAZ transmission are partner
   concerns (`kontor-l10n-br-nfe`). Per ADR-018 the resulting
   cStat=100 chave de acesso (44-digit access key) lands in
   `:kontor.transaction/clearance-token`.

   ## Architecture — CST-driven dispatch (refactored 2026-05-11)

   Brazilian tax XML groups are dispatched by CST (Código de Situação
   Tributária). One CST → one XML element group. The module structure:

     emit-imposto      — entry point, dispatches to per-tax emitters
     emit-icms         — multimethod on ICMS CST/CSOSN → <ICMS{00..90}>
                          or <ICMSSN{101..900}>
     emit-icms-st-extra — <ICMSST> for ST-paid-elsewhere (CST 60)
     emit-icms-uf-dest  — <ICMSUFDest> for DIFAL B2C interstate
     emit-fcp           — embedded vFCP elements
     emit-ipi           — multimethod on IPI CST → <IPITrib> or <IPINT>
     emit-pis           — multimethod on PIS CST → <PISAliq/NT/Qtde/Outr>
     emit-cofins        — multimethod on COFINS CST

   ## Known remaining gaps

   - **(P0) CBS / IBS / IS XML groups — NT 2025.002 v1.34
     (published 2025-12-04).** Mandatory in production from
     **2026-01** for CRT 3 (Regime Normal) taxpayers and from
     **2027-01** for Simples Nacional / MEI. Adds `<IBSCBS>`,
     `<gIBSCBS>`, `<cClassTrib>`, `<IS>` element groups plus new
     CSTs tied to LC 214/2025 (Reforma Tributária do Consumo).
     2026 test-phase rates: CBS 0.9% / IBS 0.1% (net collection
     dispensed when accessory obligations are met). Without these
     groups, kontor cannot emit a valid 2026 NF-e. Schema is
     still evolving — track NT-2025.002 revisions.
   - **(P0) NFS-e NT 007/2026** updates the national NFS-e layout
     for IBS/CBS — relevant when service-line NFS-e is in scope.
   - <retTrib> withholding group (P2).
   - <ISSQN> for service-line NF-e (P2).
   - <transp> transport (P3).
   - <pag> payment block (P2 — required for NFC-e)."
  (:require [clojure.data.xml :as xml]
            [kontor.provider.einvoice-provider :as einvoice]
            [kontor.l10n-br.cst :as cst]
            [kontor.money :as money]))

(xml/alias-uri 'nfe "http://www.portalfiscal.inf.br/nfe")

(def document-type-codes
  "SEFAZ document type codes (subset). Per ADR-020 these align with
   the kernel-level `:document-type` registry."
  {:nfe   "55"
   :ct-e  "57"
   :mdf-e "58"
   :nfc-e "65"
   :nfs-e "SE"})

;; ============================================================================
;; Formatting helpers
;; ============================================================================

(defn- fmt-amount
  ([m] (fmt-amount m 2))
  ([m precision]
   (.toPlainString
    (.setScale ^java.math.BigDecimal (:amount m)
               ^int precision java.math.RoundingMode/HALF_EVEN))))

(defn- amt-el [tag m]
  (when m (xml/element tag {} (fmt-amount m))))

(defn- pct-el
  "Emit a percentage as e.g. '18.00' (4-decimal max per NF-e schema)."
  [tag rate-bd]
  (when rate-bd
    (let [v (-> (bigdec (* 100M rate-bd))
                (.setScale 4 java.math.RoundingMode/HALF_EVEN))
          ;; strip trailing zeros below 2 decimal places
          s (.toPlainString v)]
      (xml/element tag {} s))))

;; ============================================================================
;; ICMS dispatch
;; ============================================================================

(defmulti emit-icms
  "Dispatch on ICMS CST or CSOSN. The icms map carries:
     :cst    — 2-digit CST (Regime Normal) OR
     :csosn  — 3-digit CSOSN (Simples Nacional)
     :orig   — 1-digit origem (default '0')
     plus per-CST-specific fields."
  (fn [icms]
    (or (when-let [c (:cst icms)] [:regime-normal (str c)])
        (when-let [c (:csosn icms)] [:simples (str c)])
        [:regime-normal "00"])))

(defn- icms-orig-el [icms]
  (xml/element ::nfe/orig {} (str (or (:orig icms) "0"))))

(defmethod emit-icms [:regime-normal "00"] [icms]
  (xml/element ::nfe/ICMS00 {}
               (icms-orig-el icms)
               (xml/element ::nfe/CST {} "00")
               (amt-el ::nfe/vBC (:base icms))
               (pct-el ::nfe/pICMS (:rate icms))
               (amt-el ::nfe/vICMS (:amount icms))))

(defmethod emit-icms [:regime-normal "10"] [icms]
  ;; ST cobrado at the same time
  (xml/element ::nfe/ICMS10 {}
               (icms-orig-el icms)
               (xml/element ::nfe/CST {} "10")
               (amt-el ::nfe/vBC (:base icms))
               (pct-el ::nfe/pICMS (:rate icms))
               (amt-el ::nfe/vICMS (:amount icms))
               ;; ST fields (mandatory in ICMS10)
               (let [st (:st icms)]
                 (when st
                   [(amt-el ::nfe/vBCST (:base-st st))
                   (pct-el ::nfe/pICMSST (:rate-dest st))
                   (amt-el ::nfe/vICMSST (:amount-st st))]))))

(defmethod emit-icms [:regime-normal "20"] [icms]
  ;; Com redução de base de cálculo
  (xml/element ::nfe/ICMS20 {}
               (icms-orig-el icms)
               (xml/element ::nfe/CST {} "20")
               (pct-el ::nfe/pRedBC (:reduction-pct icms))
               (amt-el ::nfe/vBC (:base icms))
               (pct-el ::nfe/pICMS (:rate icms))
               (amt-el ::nfe/vICMS (:amount icms))))

(defmethod emit-icms [:regime-normal "30"] [icms]
  ;; Isenta ou não tributada e com cobrança do ICMS por ST
  (xml/element ::nfe/ICMS30 {}
               (icms-orig-el icms)
               (xml/element ::nfe/CST {} "30")
               (when-let [st (:st icms)]
                 [(amt-el ::nfe/vBCST (:base-st st))
                  (pct-el ::nfe/pICMSST (:rate-dest st))
                  (amt-el ::nfe/vICMSST (:amount-st st))])))

(defmethod emit-icms [:regime-normal "40"] [icms]
  (xml/element ::nfe/ICMS40 {}
               (icms-orig-el icms)
               (xml/element ::nfe/CST {} "40")))
(defmethod emit-icms [:regime-normal "41"] [icms]
  (xml/element ::nfe/ICMS40 {}
               (icms-orig-el icms)
               (xml/element ::nfe/CST {} "41")))
(defmethod emit-icms [:regime-normal "50"] [icms]
  (xml/element ::nfe/ICMS40 {}
               (icms-orig-el icms)
               (xml/element ::nfe/CST {} "50")))

(defmethod emit-icms [:regime-normal "51"] [icms]
  ;; Diferimento
  (xml/element ::nfe/ICMS51 {}
               (icms-orig-el icms)
               (xml/element ::nfe/CST {} "51")
               (when (:rate icms)
                 [(pct-el ::nfe/pICMS (:rate icms))
                  (amt-el ::nfe/vICMSOp (:icms-op icms))
                  (pct-el ::nfe/pDif (:rate-dif icms))
                  (amt-el ::nfe/vICMSDif (:icms-dif icms))
                  (amt-el ::nfe/vBC (:base icms))
                  (amt-el ::nfe/vICMS (:amount icms))])))

(defmethod emit-icms [:regime-normal "60"] [icms]
  ;; ICMS cobrado anteriormente por ST — buyer side, no new ICMS
  (xml/element ::nfe/ICMS60 {}
               (icms-orig-el icms)
               (xml/element ::nfe/CST {} "60")
               (amt-el ::nfe/vBCSTRet (:st-base-retained icms))
               (amt-el ::nfe/vICMSSTRet (:st-amount-retained icms))))

(defmethod emit-icms [:regime-normal "70"] [icms]
  ;; Com redução de base e cobrança ST
  (xml/element ::nfe/ICMS70 {}
               (icms-orig-el icms)
               (xml/element ::nfe/CST {} "70")
               (pct-el ::nfe/pRedBC (:reduction-pct icms))
               (amt-el ::nfe/vBC (:base icms))
               (pct-el ::nfe/pICMS (:rate icms))
               (amt-el ::nfe/vICMS (:amount icms))
               (when-let [st (:st icms)]
                 [(amt-el ::nfe/vBCST (:base-st st))
                  (pct-el ::nfe/pICMSST (:rate-dest st))
                  (amt-el ::nfe/vICMSST (:amount-st st))])))

(defmethod emit-icms [:regime-normal "90"] [icms]
  ;; Outras — flexible
  (xml/element ::nfe/ICMS90 {}
               (icms-orig-el icms)
               (xml/element ::nfe/CST {} "90")
               (amt-el ::nfe/vBC (:base icms))
               (pct-el ::nfe/pICMS (:rate icms))
               (amt-el ::nfe/vICMS (:amount icms))))

(defmethod emit-icms [:simples "101"] [icms]
  (xml/element ::nfe/ICMSSN101 {}
               (icms-orig-el icms)
               (xml/element ::nfe/CSOSN {} "101")
               (pct-el ::nfe/pCredSN (:cred-sn-rate icms))
               (amt-el ::nfe/vCredICMSSN (:cred-icms-sn icms))))

(defmethod emit-icms [:simples "102"] [icms]
  (xml/element ::nfe/ICMSSN102 {}
               (icms-orig-el icms)
               (xml/element ::nfe/CSOSN {} "102")))

(defmethod emit-icms [:simples "201"] [icms]
  (xml/element ::nfe/ICMSSN201 {}
               (icms-orig-el icms)
               (xml/element ::nfe/CSOSN {} "201")
               (when-let [st (:st icms)]
                 [(amt-el ::nfe/vBCST (:base-st st))
                  (pct-el ::nfe/pICMSST (:rate-dest st))
                  (amt-el ::nfe/vICMSST (:amount-st st))])
               (pct-el ::nfe/pCredSN (:cred-sn-rate icms))
               (amt-el ::nfe/vCredICMSSN (:cred-icms-sn icms))))

(defmethod emit-icms [:simples "500"] [icms]
  (xml/element ::nfe/ICMSSN500 {}
               (icms-orig-el icms)
               (xml/element ::nfe/CSOSN {} "500")
               (amt-el ::nfe/vBCSTRet (:st-base-retained icms))
               (amt-el ::nfe/vICMSSTRet (:st-amount-retained icms))))

(defmethod emit-icms :default [icms]
  ;; Fallback for any other CST/CSOSN — minimum schema-valid output.
  (xml/element ::nfe/ICMS90 {}
               (icms-orig-el icms)
               (xml/element ::nfe/CST {} (or (:cst icms) "90"))
               (amt-el ::nfe/vBC (or (:base icms) (money/zero :BRL)))
               (pct-el ::nfe/pICMS (or (:rate icms) 0M))
               (amt-el ::nfe/vICMS (or (:amount icms) (money/zero :BRL)))))

;; ============================================================================
;; ICMSUFDest — DIFAL group (B2C interstate)
;; ============================================================================

(defn emit-icms-uf-dest
  "Emit <ICMSUFDest> for DIFAL (B2C interstate destination)."
  [{:keys [base rate-dest rate-orig amount fcp-base fcp-rate fcp-amount]}]
  (xml/element ::nfe/ICMSUFDest {}
               (amt-el ::nfe/vBCUFDest base)
               (when fcp-base (amt-el ::nfe/vBCFCPUFDest fcp-base))
               (when fcp-rate (pct-el ::nfe/pFCPUFDest fcp-rate))
               (pct-el ::nfe/pICMSUFDest rate-dest)
               (pct-el ::nfe/pICMSInter rate-orig)
               ;; pICMSInterPart = 100% to destination since 2019
               (xml/element ::nfe/pICMSInterPart {} "100.0000")
               (when fcp-amount (amt-el ::nfe/vFCPUFDest fcp-amount))
               (amt-el ::nfe/vICMSUFDest amount)
               ;; vICMSUFRemet retained in transition; 0 since 2019
               (xml/element ::nfe/vICMSUFRemet {} "0.00")))

;; ============================================================================
;; IPI dispatch
;; ============================================================================

(defmulti emit-ipi
  (fn [ipi]
    (cst/cst-xml-group :ipi (str (:cst ipi "50")))))

(defmethod emit-ipi :IPITrib [ipi]
  (xml/element ::nfe/IPI {}
               (xml/element ::nfe/cEnq {} (or (:enq ipi) "999"))
               (xml/element ::nfe/IPITrib {}
                            (xml/element ::nfe/CST {} (str (:cst ipi)))
                            (amt-el ::nfe/vBC (:base ipi))
                            (pct-el ::nfe/pIPI (:rate ipi))
                            (amt-el ::nfe/vIPI (:amount ipi)))))

(defmethod emit-ipi :IPINT [ipi]
  (xml/element ::nfe/IPI {}
               (xml/element ::nfe/cEnq {} (or (:enq ipi) "999"))
               (xml/element ::nfe/IPINT {}
                            (xml/element ::nfe/CST {} (str (:cst ipi))))))

(defmethod emit-ipi :default [ipi]
  (xml/element ::nfe/IPI {}
               (xml/element ::nfe/cEnq {} (or (:enq ipi) "999"))
               (xml/element ::nfe/IPINT {}
                            (xml/element ::nfe/CST {} (str (:cst ipi "49"))))))

;; ============================================================================
;; PIS dispatch
;; ============================================================================

(defmulti emit-pis
  (fn [pis]
    (cst/cst-xml-group :pis (str (:cst pis "01")))))

(defmethod emit-pis :PISAliq [pis]
  (xml/element ::nfe/PIS {}
               (xml/element ::nfe/PISAliq {}
                            (xml/element ::nfe/CST {} (str (:cst pis)))
                            (amt-el ::nfe/vBC (:base pis))
                            (pct-el ::nfe/pPIS (:rate pis))
                            (amt-el ::nfe/vPIS (:amount pis)))))

(defmethod emit-pis :PISQtde [pis]
  (xml/element ::nfe/PIS {}
               (xml/element ::nfe/PISQtde {}
                            (xml/element ::nfe/CST {} (str (:cst pis)))
                            (amt-el ::nfe/qBCProd (:quantity pis))
                            (amt-el ::nfe/vAliqProd (:rate-per-unit pis))
                            (amt-el ::nfe/vPIS (:amount pis)))))

(defmethod emit-pis :PISNT [pis]
  (xml/element ::nfe/PIS {}
               (xml/element ::nfe/PISNT {}
                            (xml/element ::nfe/CST {} (str (:cst pis))))))

(defmethod emit-pis :PISOutr [pis]
  (xml/element ::nfe/PIS {}
               (xml/element ::nfe/PISOutr {}
                            (xml/element ::nfe/CST {} (str (:cst pis)))
                            (amt-el ::nfe/vBC (:base pis))
                            (pct-el ::nfe/pPIS (:rate pis))
                            (amt-el ::nfe/vPIS (:amount pis)))))

(defmethod emit-pis :default [pis]
  (xml/element ::nfe/PIS {}
               (xml/element ::nfe/PISNT {}
                            (xml/element ::nfe/CST {} (str (:cst pis "08"))))))

;; ============================================================================
;; COFINS dispatch
;; ============================================================================

(defmulti emit-cofins
  (fn [cofins]
    (cst/cst-xml-group :cofins (str (:cst cofins "01")))))

(defmethod emit-cofins :COFINSAliq [c]
  (xml/element ::nfe/COFINS {}
               (xml/element ::nfe/COFINSAliq {}
                            (xml/element ::nfe/CST {} (str (:cst c)))
                            (amt-el ::nfe/vBC (:base c))
                            (pct-el ::nfe/pCOFINS (:rate c))
                            (amt-el ::nfe/vCOFINS (:amount c)))))

(defmethod emit-cofins :COFINSQtde [c]
  (xml/element ::nfe/COFINS {}
               (xml/element ::nfe/COFINSQtde {}
                            (xml/element ::nfe/CST {} (str (:cst c)))
                            (amt-el ::nfe/qBCProd (:quantity c))
                            (amt-el ::nfe/vAliqProd (:rate-per-unit c))
                            (amt-el ::nfe/vCOFINS (:amount c)))))

(defmethod emit-cofins :COFINSNT [c]
  (xml/element ::nfe/COFINS {}
               (xml/element ::nfe/COFINSNT {}
                            (xml/element ::nfe/CST {} (str (:cst c))))))

(defmethod emit-cofins :COFINSOutr [c]
  (xml/element ::nfe/COFINS {}
               (xml/element ::nfe/COFINSOutr {}
                            (xml/element ::nfe/CST {} (str (:cst c)))
                            (amt-el ::nfe/vBC (:base c))
                            (pct-el ::nfe/pCOFINS (:rate c))
                            (amt-el ::nfe/vCOFINS (:amount c)))))

(defmethod emit-cofins :default [c]
  (xml/element ::nfe/COFINS {}
               (xml/element ::nfe/COFINSNT {}
                            (xml/element ::nfe/CST {} (str (:cst c "08"))))))

;; ============================================================================
;; Imposto entry point
;; ============================================================================

(defn emit-imposto
  "Build the <imposto> element for one invoice line by dispatching to
   per-tax CST emitters. Order within <imposto> follows the schema
   sequence: ICMS, ICMSUFDest (DIFAL, when present), IPI, PIS, COFINS."
  [{:keys [icms icms-uf-dest ipi pis cofins]}]
  (apply xml/element ::nfe/imposto {}
         (remove nil?
                 [(when icms (emit-icms icms))
                  (when icms-uf-dest (emit-icms-uf-dest icms-uf-dest))
                  (when ipi (emit-ipi ipi))
                  (when pis (emit-pis pis))
                  (when cofins (emit-cofins cofins))])))

;; ============================================================================
;; Identification block (<ide>) — with required ordering
;; ============================================================================

;; ============================================================================
;; cDV — chave-de-acesso check digit (mod-11 base 2,9)
;;
;; The 44-digit chave-de-acesso consists of a 43-digit prefix + 1
;; check digit. The check is mod-11 with weights cycling 2..9
;; right-to-left.
;; ============================================================================

(defn compute-cdv
  "Compute the cDV (check digit) for a 43-digit chave-de-acesso prefix.
   Returns a 1-character string ('0'..'9')."
  [^String prefix-43]
  (let [digits (mapv #(- (int %) (int \0)) prefix-43)
        weights (cycle [2 3 4 5 6 7 8 9])         ; right-to-left
        sum (reduce + (map * (reverse digits) weights))
        rest (mod sum 11)]
    (str (if (< rest 2) 0 (- 11 rest)))))

(defn access-key-cdv
  "Extract or compute the cDV from a chave-de-acesso. If the access-key
   is 44 chars, use its last digit. If 43 chars, compute the cDV."
  [^String access-key]
  (cond
    (nil? access-key) "0"
    (= 44 (count access-key)) (subs access-key 43 44)
    (= 43 (count access-key)) (compute-cdv access-key)
    :else (str (last access-key))))

(defn determine-id-dest
  "NF-e idDest: 1 = intra-state, 2 = interstate, 3 = foreign export.
   Derived from issuer state + recipient state."
  [emit-state dest-state dest-country-code]
  (cond
    (and dest-country-code
         (not (re-matches #"(?i)br|bra|brazil" dest-country-code))) "3"
    (= emit-state dest-state) "1"
    :else "2"))

(defn emit-ide
  "Emit the <ide> identification block. Per Manual de Integração 4.0,
   the sequence is strict — including the mandatory cDV (mod-11 of
   the 43-digit chave-de-acesso prefix) and the idDest discriminator
   computed from emit-state vs dest-state.

   Optional id-data keys (defaults shown):
     :operation-type     \"1\"    ; 0=entry, 1=exit
     :env                \"2\"    ; 1=prod, 2=homologation
     :final?             \"0\"    ; 0=normal, 1=final consumer
     :presence           \"9\"    ; 9=non-applicable (default for B2B)
     :intermediary       \"0\"
     :process-emit       \"0\"    ; 0=normal-app emit
     :process-version    \"kontor-0.1\""
  [{:keys [uf-code random-code access-key operation-nature
           series number issue-date operation-type municipality-code
           env final? presence intermediary
           process-emit process-version
           emit-state dest-state dest-country]
    :or {operation-type "1"
         env             "2"
         final?          "0"
         presence        "9"
         intermediary    "0"
         process-emit    "0"
         process-version "kontor-0.1"}}]
  (xml/element
   ::nfe/ide {}
   (xml/element ::nfe/cUF {} (str uf-code))
   (xml/element ::nfe/cNF {} (str random-code))
   (xml/element ::nfe/natOp {} (str operation-nature))
   (xml/element ::nfe/mod {} "55")
   (xml/element ::nfe/serie {} (str series))
   (xml/element ::nfe/nNF {} (str number))
   (xml/element ::nfe/dhEmi {} (str issue-date))
   (xml/element ::nfe/tpNF {} (str operation-type))
   (xml/element ::nfe/idDest {} (determine-id-dest emit-state dest-state dest-country))
   (xml/element ::nfe/cMunFG {} (str municipality-code))
   (xml/element ::nfe/tpImp {} "1")            ; 1=portrait
   (xml/element ::nfe/tpEmis {} "1")           ; 1=normal emission
   (xml/element ::nfe/cDV {} (access-key-cdv access-key))
   (xml/element ::nfe/tpAmb {} (str env))
   (xml/element ::nfe/finNFe {} "1")           ; 1=normal NF-e
   (xml/element ::nfe/indFinal {} (str final?))
   (xml/element ::nfe/indPres {} (str presence))
   (xml/element ::nfe/indIntermed {} (str intermediary))
   (xml/element ::nfe/procEmi {} (str process-emit))
   (xml/element ::nfe/verProc {} (str process-version))))

;; ============================================================================
;; Submission shape
;; ============================================================================

(defn- emit-emit-party
  [{:keys [cnpj cpf name street number neighborhood municipality-code
           municipality-name state cep state-tax-id municipal-tax-id
           tax-regime]}]
  (apply xml/element ::nfe/emit {}
         (remove nil?
                 [(when cnpj (xml/element ::nfe/CNPJ {} cnpj))
                  (when cpf  (xml/element ::nfe/CPF  {} cpf))
                  (xml/element ::nfe/xNome {} name)
                  (xml/element
                   ::nfe/enderEmit {}
                   (xml/element ::nfe/xLgr {} street)
                   (xml/element ::nfe/nro {} number)
                   (when neighborhood (xml/element ::nfe/xBairro {} neighborhood))
                   (xml/element ::nfe/cMun {} (str municipality-code))
                   (when municipality-name
                     (xml/element ::nfe/xMun {} municipality-name))
                   (xml/element ::nfe/UF {} state)
                   (xml/element ::nfe/CEP {} cep))
                  (xml/element ::nfe/IE {} state-tax-id)
                  (when municipal-tax-id
                    (xml/element ::nfe/IM {} municipal-tax-id))
                  (xml/element ::nfe/CRT {} (str (or tax-regime "3")))])))
;; CRT: 1=Simples Nacional, 2=Simples Nacional - excesso, 3=Regime Normal

(defn- emit-dest-party
  [{:keys [cnpj cpf name street number neighborhood municipality-code
           municipality-name state cep state-tax-id ie-indicator]}]
  (apply xml/element ::nfe/dest {}
         (remove nil?
                 [(when cnpj (xml/element ::nfe/CNPJ {} cnpj))
                  (when cpf  (xml/element ::nfe/CPF  {} cpf))
                  (xml/element ::nfe/xNome {} name)
                  (xml/element
                   ::nfe/enderDest {}
                   (xml/element ::nfe/xLgr {} street)
                   (xml/element ::nfe/nro {} number)
                   (when neighborhood (xml/element ::nfe/xBairro {} neighborhood))
                   (xml/element ::nfe/cMun {} (str municipality-code))
                   (when municipality-name
                     (xml/element ::nfe/xMun {} municipality-name))
                   (xml/element ::nfe/UF {} state)
                   (xml/element ::nfe/CEP {} cep))
                  ;; indIEDest: 1=ICMS-contributor, 2=exempt, 9=non-contributor
                  (xml/element ::nfe/indIEDest {} (str (or ie-indicator "9")))
                  (when state-tax-id
                    (xml/element ::nfe/IE {} state-tax-id))])))

(defn- emit-item
  [idx {:keys [code name ncm cfop unit quantity unit-price line-total
               taxes]}]
  (xml/element
   ::nfe/det {:nItem (str (inc idx))}
   (xml/element ::nfe/prod {}
                (xml/element ::nfe/cProd {} code)
                (xml/element ::nfe/cEAN {} "SEM GTIN")
                (xml/element ::nfe/xProd {} name)
                (xml/element ::nfe/NCM {} ncm)
                (xml/element ::nfe/CFOP {} cfop)
                (xml/element ::nfe/uCom {} (or unit "UN"))
                (amt-el ::nfe/qCom quantity)
                (amt-el ::nfe/vUnCom unit-price)
                (amt-el ::nfe/vProd line-total)
                (xml/element ::nfe/cEANTrib {} "SEM GTIN")
                (xml/element ::nfe/uTrib {} (or unit "UN"))
                (amt-el ::nfe/qTrib quantity)
                (amt-el ::nfe/vUnTrib unit-price)
                (xml/element ::nfe/indTot {} "1"))
   (emit-imposto taxes)))

(defn- emit-total [{:keys [icms-base icms icms-st-base icms-st
                            products ipi pis cofins invoice-total
                            difal]}]
  (xml/element
   ::nfe/total {}
   (xml/element ::nfe/ICMSTot {}
                (amt-el ::nfe/vBC icms-base)
                (amt-el ::nfe/vICMS icms)
                (amt-el ::nfe/vICMSDeson (money/zero :BRL))
                (when difal (amt-el ::nfe/vICMSUFDest difal))
                (amt-el ::nfe/vFCPUFDest (money/zero :BRL))
                (amt-el ::nfe/vICMSUFRemet (money/zero :BRL))
                (amt-el ::nfe/vFCP (money/zero :BRL))
                (amt-el ::nfe/vBCST (or icms-st-base (money/zero :BRL)))
                (amt-el ::nfe/vST (or icms-st (money/zero :BRL)))
                (amt-el ::nfe/vFCPST (money/zero :BRL))
                (amt-el ::nfe/vFCPSTRet (money/zero :BRL))
                (amt-el ::nfe/vProd products)
                (amt-el ::nfe/vFrete (money/zero :BRL))
                (amt-el ::nfe/vSeg (money/zero :BRL))
                (amt-el ::nfe/vDesc (money/zero :BRL))
                (amt-el ::nfe/vII (money/zero :BRL))
                (amt-el ::nfe/vIPI ipi)
                (amt-el ::nfe/vIPIDevol (money/zero :BRL))
                (amt-el ::nfe/vPIS pis)
                (amt-el ::nfe/vCOFINS cofins)
                (amt-el ::nfe/vOutro (money/zero :BRL))
                (amt-el ::nfe/vNF invoice-total))))

(defn invoice-element
  "Build a complete NF-e <NFe> element from a structured invoice map.

   Input:
     {:nfe/doc-type :nfe
      :nfe/version  \"4.00\"
      :nfe/id-data  {...identification block fields...}
      :nfe/issuer   {...emit party fields...}
      :nfe/recipient {...dest party fields...}
      :nfe/items    [{...item map with :taxes...} ...]
      :nfe/totals   {...total totals...}}"
  [{:nfe/keys [doc-type id-data issuer recipient items totals version]
    :or {doc-type :nfe version "4.00"}}]
  (let [;; Inject emit-state and dest-state into id-data so emit-ide
        ;; can compute idDest correctly (intra-state vs interstate vs
        ;; foreign export).
        id-data* (assoc id-data
                        :emit-state (:state issuer)
                        :dest-state (:state recipient)
                        :dest-country (:country-code recipient))]
    (xml/element
     ::nfe/NFe {}
     (xml/element
      ::nfe/infNFe {:Id (str "NFe" (:access-key id-data))
                    :versao version}
      (emit-ide id-data*)
      (emit-emit-party issuer)
      (emit-dest-party recipient)
      (apply concat
             [(map-indexed emit-item items)
              [(emit-total totals)]])))))

(defn emit-string [el] (xml/emit-str el))

(defn provider
  "EInvoiceProvider for NF-e — pure XML, no transmission.
   Partner module (`kontor-l10n-br-nfe`) replaces with an attesting
   provider that signs + submits to SEFAZ."
  []
  (einvoice/pure-xml-provider :br/nfe-4.0
                              (fn [invoice]
                                (emit-string
                                 (invoice-element
                                  (:nfe/draft-data invoice))))))
