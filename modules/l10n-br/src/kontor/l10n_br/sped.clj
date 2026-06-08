(ns kontor.l10n-br.sped
  "SPED EFD-ICMS/IPI — Brazilian fiscal bookkeeping export.

   SPED (Sistema Público de Escrituração Digital) is RFB's digital
   bookkeeping framework. Multiple modules:
     ECD       Escrituração Contábil Digital (full GL, annual)
     ECF       Escrituração Contábil Fiscal (tax reconciliation, annual)
     EFD ICMS/IPI  Monthly fiscal records (this module)
     EFD-Contribuições  PIS/COFINS records
     EFD-Reinf       Withholdings on services etc.
     eSocial         Payroll events
     DCTFWeb         Consolidated federal taxes

   This namespace ships **EFD-ICMS/IPI** subset — the monthly fiscal
   record covering invoices in/out and their per-tax breakdown.

   File format: PLAIN TEXT, pipe-delimited records. One record per
   line. First field after the leading | is the record code; remaining
   fields are positional per the SPED layout published at sped.rfb.gov.br.

   Block structure (subset):
     Bloco 0 — Opening + master data
       0000  Header (period, company, profile)
       0001  Opening of block 0
       0005  Company supplementary data
       0190  Unit-of-measure registry
       0200  Item registry (products/services)
       0990  Closing of block 0
     Bloco C — Fiscal documents (the core)
       C001  Opening of block C
       C100  NF-e header
       C170  NF-e line items (one per item, with per-tax breakdown)
       C190  Analytical totals per (CST, CFOP, rate)
       C990  Closing of block C
     Bloco 9 — Closing
       9001  Opening of block 9
       9900  Record-type counters
       9990  Closing of block 9
       9999  Closing of the SPED file

   This module scaffolds a few of these records. Production deployment
   needs the full block layout published in the EFD-ICMS/IPI Guia
   Prático (RFB), refreshed annually. Per ADR-015 we keep the kernel
   layer agnostic; consumers extend via their own emit-fns."
  (:require [clojure.string :as str]
            [kontor.money :as money]))

;; ============================================================================
;; Record framing
;; ============================================================================

(defn- fmt-field
  "SPED field formatting:
     - nil / blank → empty field
     - Money → 2-decimal amount with `,` (Brazilian) decimal separator,
       no thousand separators
     - dates → ddMMyyyy
     - strings/numbers → string"
  [v]
  (cond
    (nil? v) ""
    (and (map? v) (:amount v))
    (-> ^java.math.BigDecimal (:amount v)
        (.setScale 2 java.math.RoundingMode/HALF_EVEN)
        .toPlainString
        (str/replace "." ","))
    (instance? java.time.LocalDate v)
    (format "%02d%02d%04d" (.getDayOfMonth v) (.getMonthValue v) (.getYear v))
    (instance? java.util.Date v)
    (let [ld (-> v (.toInstant) (.atZone java.time.ZoneOffset/UTC) .toLocalDate)]
      (format "%02d%02d%04d" (.getDayOfMonth ld) (.getMonthValue ld) (.getYear ld)))
    :else (str v)))

(defn record
  "Render a SPED record from a code (e.g. \"C100\") + ordered fields.
   Returns a single line ending with |. The kernel uses \\n line
   endings; SPED canonical accepts \\r\\n which the caller can convert
   per file."
  [code & fields]
  (str "|" code "|" (str/join "|" (map fmt-field fields)) "|"))

;; ============================================================================
;; Bloco 0 — Opening + master
;; ============================================================================

(defn rec-0000
  "Opening header. Fields per the 2024 layout:
     COD_VER | COD_FIN | DT_INI | DT_FIN | NOME | CNPJ | CPF | UF |
     IE | COD_MUN | IM | SUFRAMA | IND_PERFIL | IND_ATIV"
  [{:keys [version finality period-start period-end company-name
           cnpj cpf state state-tax-id municipality-code
           municipal-tax-id suframa profile activity]
    :or {version "020" finality "0" profile "A" activity "0"}}]
  ;; COD_VER tracks the SPED *layout* revision, not the Guia Prático
  ;; document version. Layout "020" was published in
  ;; NT EFD ICMS IPI 2025.001 v1.0 (CONFAZ Ato COTEPE/ICMS 79/2025,
  ;; which amends the base Ato COTEPE/ICMS 44/2018 layout
  ;; specification) and is mandatory for fiscal periods from 2026-01.
  ;;
  ;; The companion Guia Prático sequence on layout 020 is:
  ;;   v3.2.1 (Nov 2025)  — initial
  ;;   v3.2.2 (Feb 2026)  — current in-force (as of 2026-05);
  ;;                        adds C100 exception #11: NF-e carrying
  ;;                        only IBS/CBS/IS are NOT scriptured in
  ;;                        EFD-ICMS/IPI
  ;;
  ;; Layout "019" (Guia Prático v3.1.9, May 2025) remains valid for
  ;; pre-2026 retro-filings; callers can override via :version.
  (record "0000" version finality period-start period-end company-name
          cnpj cpf state state-tax-id municipality-code municipal-tax-id
          suframa profile activity))

(defn rec-0001
  "Bloco 0 opening flag. 0 = block has movement; 1 = no movement."
  [has-movement?]
  (record "0001" (if has-movement? "0" "1")))

;; ============================================================================
;; Bloco C — Fiscal documents (NF-e header + line items)
;; ============================================================================

(defn rec-c100
  "NF-e (and other model) header. Fields (subset):
     IND_OPER (0=entry / 1=exit) | IND_EMIT (0=own / 1=third-party) |
     COD_PART (partner code) | COD_MOD (55=NF-e) | COD_SIT (00=normal) |
     SER (series) | NUM_DOC (number) | CHV_NFE (44-digit access key) |
     DT_DOC | DT_E_S | VL_DOC | IND_PGTO | VL_DESC | VL_ABAT_NT |
     VL_MERC | IND_FRT | VL_FRT | VL_SEG | VL_OUT_DA | VL_BC_ICMS |
     VL_ICMS | VL_BC_ICMS_ST | VL_ICMS_ST | VL_IPI | VL_PIS | VL_COFINS |
     VL_PIS_ST | VL_COFINS_ST"
  [{:keys [operation issuer partner-code model situation series number
           chave-de-acesso doc-date in-out-date total payment discount
           merch-value freight-mode freight insurance other
           icms-base icms icms-st-base icms-st ipi pis cofins pis-st cofins-st]
    :or {operation "1" issuer "0" model "55" situation "00"
         payment "0" freight-mode "9"}}]
  (record "C100" operation issuer partner-code model situation series number
          chave-de-acesso doc-date in-out-date total payment discount nil
          merch-value freight-mode freight insurance other
          icms-base icms icms-st-base icms-st ipi pis cofins pis-st cofins-st))

(defn rec-c170
  "NF-e line item with per-tax breakdown. Fields (subset):
     NUM_ITEM | COD_ITEM | DESCR_COMPL | QTD | UNID | VL_ITEM |
     VL_DESC | IND_MOV | CST_ICMS | CFOP | COD_NAT | VL_BC_ICMS |
     ALIQ_ICMS | VL_ICMS | VL_BC_ICMS_ST | ALIQ_ST | VL_ICMS_ST |
     IND_APUR | CST_IPI | COD_ENQ | VL_BC_IPI | ALIQ_IPI | VL_IPI |
     CST_PIS | VL_BC_PIS | ALIQ_PIS_PCT | QUANT_BC_PIS | ALIQ_PIS_R |
     VL_PIS | CST_COFINS | VL_BC_COFINS | ALIQ_COFINS_PCT |
     QUANT_BC_COFINS | ALIQ_COFINS_R | VL_COFINS | COD_CTA |
     VL_ABAT_NT"
  [{:keys [item-no item-code description quantity unit value discount
           movement cst-icms cfop nat-code icms-base icms-rate icms
           icms-st-base icms-st-rate icms-st apur cst-ipi enq
           ipi-base ipi-rate ipi cst-pis pis-base pis-rate-pct
           pis-base-qty pis-rate-real pis cst-cofins cofins-base
           cofins-rate-pct cofins-base-qty cofins-rate-real cofins
           account-code abate]}]
  (record "C170" item-no item-code description quantity unit value discount
          movement cst-icms cfop nat-code icms-base icms-rate icms
          icms-st-base icms-st-rate icms-st apur cst-ipi enq
          ipi-base ipi-rate ipi cst-pis pis-base pis-rate-pct
          pis-base-qty pis-rate-real pis cst-cofins cofins-base
          cofins-rate-pct cofins-base-qty cofins-rate-real cofins
          account-code abate))

;; ============================================================================
;; Bloco 0 — additional records (0150 partners, 0200 items)
;; ============================================================================

(defn rec-0150
  "Partner registry. Each unique counter-party (customer or vendor)
   appearing on a C100 / D100 / etc. record must be registered here.
   Fields:
     COD_PART (free internal code) | NOME | COD_PAIS (3-digit ISO/BACEN)
     | CNPJ | CPF | IE | COD_MUN (7-digit IBGE) | SUFRAMA | END | NUM
     | COMPL | BAIRRO"
  [{:keys [code name country-code cnpj cpf state-tax-id
           municipality-code suframa street number complement neighborhood]
    :or {country-code "1058"}}]                ; 1058 = Brasil
  (record "0150" code name country-code cnpj cpf state-tax-id
          municipality-code suframa street number complement neighborhood))

(defn rec-0190
  "Unit-of-measure registry. Each :uCom value used in C170 must be
   declared here.
     UNID | DESCR"
  [{:keys [unit description]}]
  (record "0190" unit description))

(defn rec-0200
  "Item / product registry. Each :cod-item used in C170 must be
   registered here.
     COD_ITEM | DESCR_ITEM | COD_BARRA | COD_ANT_ITEM | UNID_INV |
     TIPO_ITEM | COD_NCM | EX_IPI | COD_GEN | COD_LST | ALIQ_ICMS"
  [{:keys [code description barcode prior-code inventory-unit
           item-type ncm ipi-exception generic-code service-list-code
           icms-rate]
    :or {item-type "00"}}]
  (record "0200" code description barcode prior-code inventory-unit
          item-type ncm ipi-exception generic-code service-list-code
          icms-rate))

;; ============================================================================
;; Bloco C — additional records (C190 analytical totals)
;; ============================================================================

(defn rec-c190
  "Analytical totals per (CST_ICMS, CFOP, ALIQ_ICMS). For any C100
   with C170 children, the C190 record aggregates by these three
   keys. Required by SPED validation.

   Fields:
     CST_ICMS | CFOP | ALIQ_ICMS | VL_OPR | VL_BC_ICMS | VL_ICMS |
     VL_BC_ICMS_ST | VL_ICMS_ST | VL_RED_BC | VL_IPI | COD_OBS"
  [{:keys [cst-icms cfop icms-rate operation-value icms-base icms
           icms-st-base icms-st reduction-base ipi observation-code]}]
  (record "C190" cst-icms cfop icms-rate operation-value icms-base icms
          icms-st-base icms-st reduction-base ipi observation-code))

;; ============================================================================
;; Bloco E — apuração de ICMS (the monthly apuração block; required to
;; close the period). Per CONFAZ Guia Prático EFD-ICMS/IPI v3.1.9.
;; ============================================================================

(defn rec-e001
  "Bloco E opening. 0 = movement; 1 = no movement."
  [has-movement?]
  (record "E001" (if has-movement? "0" "1")))

(defn rec-e100
  "Period of ICMS apuração.
     DT_INI | DT_FIN"
  [{:keys [period-start period-end]}]
  (record "E100" period-start period-end))

(defn rec-e110
  "Apuração consolidated totals for the ICMS period — the heart of the
   monthly ICMS settlement.

   Fields (Guia Prático v3.1.9):
     VL_TOT_DEBITOS         Total debit (output VAT collected)
     VL_AJ_DEBITOS          Other debits (adjustments)
     VL_TOT_AJ_DEBITOS      Total ajuste debits
     VL_ESTORNOS_CRED       Reversal of input credits
     VL_TOT_CREDITOS        Total credits (input VAT)
     VL_AJ_CREDITOS         Other credits
     VL_TOT_AJ_CREDITOS     Total ajuste credits
     VL_ESTORNOS_DEB        Reversal of output debits
     VL_SLD_CREDOR_ANT      Prior period credit balance carryforward
     VL_SLD_APURADO         Period balance (apurado)
     VL_TOT_DED             Total deductions
     VL_ICMS_RECOLHER       ICMS due (positive = pay)
     VL_SLD_CREDOR_TRANSP   Credit balance carried forward
     DEB_ESP                Special debit code"
  [{:keys [tot-debits aj-debits tot-aj-debits estornos-cred
           tot-credits aj-credits tot-aj-credits estornos-deb
           sld-credor-ant sld-apurado tot-ded icms-recolher
           sld-credor-transp deb-esp]
    :or {aj-debits (money/zero :BRL) tot-aj-debits (money/zero :BRL)
         estornos-cred (money/zero :BRL)
         aj-credits (money/zero :BRL) tot-aj-credits (money/zero :BRL)
         estornos-deb (money/zero :BRL)
         sld-credor-ant (money/zero :BRL) tot-ded (money/zero :BRL)
         sld-credor-transp (money/zero :BRL)
         deb-esp (money/zero :BRL)}}]
  (record "E110" tot-debits aj-debits tot-aj-debits estornos-cred
          tot-credits aj-credits tot-aj-credits estornos-deb
          sld-credor-ant sld-apurado tot-ded icms-recolher
          sld-credor-transp deb-esp))

(defn rec-e116
  "ICMS payment obligation (DARF / GNRE record).
     COD_OR | VL_OR | DT_VCTO | COD_REC | NUM_PROC | IND_PROC |
     PROC | TXT_COMPL | MES_REF"
  [{:keys [obligation-code amount due-date receipt-code
           process-number process-indicator process-description
           complement reference-month]}]
  (record "E116" obligation-code amount due-date receipt-code
          process-number process-indicator process-description
          complement reference-month))

(defn rec-e990
  "Bloco E closing — count of records in block E."
  [total]
  (record "E990" total))

;; ============================================================================
;; Bloco 9 — Closing
;; ============================================================================

(defn rec-9001 [has-movement?]
  (record "9001" (if has-movement? "0" "1")))

(defn rec-9900
  "Record-type counter."
  [record-type count]
  (record "9900" record-type count))

(defn rec-9990
  "Block 9 closing — count of records in block 9 itself."
  [total]
  (record "9990" total))

(defn rec-9999
  "File closing — total records in file."
  [total]
  (record "9999" total))

;; ============================================================================
;; File emit
;; ============================================================================

(defn emit-file
  "Render a sequence of record lines as a SPED file string.
   line-ending defaults to \\r\\n which is canonical for SPED; pass
   :unix-newlines true for kontor-side serialization (the consumer
   converts as needed)."
  ([lines] (emit-file lines false))
  ([lines unix-newlines?]
   (str/join (if unix-newlines? "\n" "\r\n") (concat lines [""]))))
