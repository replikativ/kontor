(ns kontor.payroll-br.esocial
  "eSocial event XML builders. eSocial is the Brazilian federal
   event-bus that consolidates all employer-side labor / tax / social-
   security data into a single feed; mandatory since 2018 for all
   employers (rollout phased by company size + nature).

   Reference: gov.br/esocial — https://www.gov.br/esocial/pt-br
   XSDs published at https://www.gov.br/esocial/pt-br/documentacao-
   tecnica/leiautes-esocial — current version S-1.3 (2026).

   ## Scope discipline (ADR-081 §6 + task brief)

   The v1 module ships the MOST LOAD-BEARING subset for periodic
   payroll + admissão/desligamento:

   | Event   | Description                                        | Tier |
   |---------|----------------------------------------------------|------|
   | S-1000  | Informações do Empregador                          | Mestre |
   | S-1005  | Tabela de Estabelecimentos                         | Tabela |
   | S-1010  | Tabela de Rubricas                                 | Tabela |
   | S-1020  | Tabela de Lotações Tributárias                     | Tabela |
   | S-2200  | Cadastramento Inicial / Admissão de Trabalhador    | Não-periódico |
   | S-2299  | Desligamento                                       | Não-periódico |
   | S-2300  | Trabalhador Sem Vínculo de Emprego                 | Não-periódico |
   | S-2399  | Trabalhador Sem Vínculo — Término                  | Não-periódico |
   | S-1200  | Remuneração de Trabalhador (per-employee monthly)  | Periódico |
   | S-1210  | Pagamentos de Rendimentos do Trabalho              | Periódico |
   | S-1299  | Fechamento dos Eventos Periódicos                  | Periódico |

   Per ADR-081 §6 the following are deferred to BR follow-ups:
     - S-1280 BPO substitute employer
     - S-2240 Condições Ambientais de Trabalho
     - S-2250 Aviso Prévio
     - S-2298 Reintegração
     - S-1202 / S-1207 RPPS public-sector
     - S-2205 / S-2206 / S-2210 / S-2220 / S-2230 change events
     - S-3000 Exclusão de Evento
     - S-5xxx return events

   ## What kontor does and does NOT do

   - **DOES** build the XML payload per the published S-1.3 XSDs.
   - **DOES** wrap each event as an `:kontor.audit-doc/category :payroll-filing`
     entity carrying the language `:pt-br` so audit grids work.
   - **DOES NOT** sign with the ICP-Brasil certificate (consumer holds).
   - **DOES NOT** transmit over the eSocial Webservice.
   - **DOES NOT** compute INSS / IRRF / FGTS / Salário-Família.

   ## License posture

   gov.br/esocial XSDs + leiaute manuals are public regulator
   publications. We read the schemas as facts and emit independent
   XML. No vendor source has been lifted."
  (:require [clojure.data.xml :as xml]
            [clojure.string :as str]
            [kontor.l10n-br.identifiers :as br-id])
  (:import [java.math BigDecimal RoundingMode]
           [java.text SimpleDateFormat]
           [java.util Date TimeZone]))

(xml/alias-uri 'esocial "http://www.esocial.gov.br/schema/evt")

(def current-layout
  "Current eSocial leiaute version produced by this module."
  "S-1.3")

;; ============================================================================
;; Formatting helpers
;; ============================================================================

(defn ^:private fmt-amount
  "eSocial amounts are emitted with two decimals + dot separator (not
   the BR-locale comma). Per the XSDs all monetary fields use the
   '#0.00' pattern."
  ([m] (fmt-amount m 2))
  ([m precision]
   (cond
     (nil? m) nil
     (instance? BigDecimal m)
     (.toPlainString
      (.setScale ^BigDecimal m ^int precision RoundingMode/HALF_EVEN))
     :else (str m))))

(defn ^:private utc-fmt
  "Return a SimpleDateFormat with UTC TZ so #inst literals format
   consistently regardless of the JVM's default timezone."
  [^String pattern]
  (let [sdf (SimpleDateFormat. pattern)]
    (.setTimeZone sdf (TimeZone/getTimeZone "UTC"))
    sdf))

(defn ^:private fmt-yyyy-mm
  "Period code as 'yyyy-MM' (ano-mês competência)."
  [^Date d]
  (.format (utc-fmt "yyyy-MM") d))

(defn ^:private fmt-yyyy-mm-dd
  [^Date d]
  (.format (utc-fmt "yyyy-MM-dd") d))

(defn ^:private el
  "Create an XML element with body string. Returns nil for nil body."
  [tag body]
  (when (some? body)
    (xml/element tag {} (str body))))

(defn ^:private group
  "XML group element wrapping non-nil children only. children is a
   sequence."
  [tag & children]
  (xml/element tag {} (vec (filter some? children))))

(defn ^:private gen-event-id
  "Generate the eSocial event ID. Pattern: 'ID' + tpInsc(1) +
   nrInsc(14) + yyyy-MM-ddTHH:mm:ss(14) + sequence(5)."
  [{:keys [employer-cnpj counter ^Date timestamp]
    :or {counter 1 timestamp (Date.)}}]
  (let [cnpj (br-id/strip employer-cnpj)
        ts (.format (utc-fmt "yyyyMMddHHmmss") timestamp)
        seq-str (format "%05d" counter)]
    (str "ID1" cnpj ts seq-str)))

(defn ^:private validate-cnpj!
  [cnpj field]
  (when-not (br-id/valid-cnpj? cnpj)
    (throw (ex-info (str "Invalid CNPJ for " field)
                    {:field field :cnpj cnpj}))))

(defn ^:private validate-cpf!
  [cpf field]
  (when-not (br-id/valid-cpf? cpf)
    (throw (ex-info (str "Invalid CPF for " field)
                    {:field field :cpf cpf}))))

(defn ^:private ide-evento-tabela
  "ideEvento group for the table events (S-1005 / S-1010 / S-1020).
   The op-fld attribute (`tpAmb`/`procEmi`/`verProc`) is consumer-
   configurable per environment (1 = production, 2 = approval-test)."
  [{:keys [tp-amb proc-emi ver-proc]
    :or {tp-amb 2 proc-emi 1 ver-proc "kontor-payroll-br"}}]
  (group ::esocial/ideEvento
         (el ::esocial/tpAmb tp-amb)
         (el ::esocial/procEmi proc-emi)
         (el ::esocial/verProc ver-proc)))

(defn ^:private ide-evento-periodico
  "ideEvento group for periodic events (S-1200 / S-1210 / S-1299).
   Adds :ind-retif + :nr-recibo + :per-apur."
  [{:keys [ind-retif nr-recibo per-apur tp-amb proc-emi ver-proc]
    :or {ind-retif 1 tp-amb 2 proc-emi 1 ver-proc "kontor-payroll-br"}}]
  (group ::esocial/ideEvento
         (el ::esocial/indRetif ind-retif)
         (when nr-recibo (el ::esocial/nrRecibo nr-recibo))
         (el ::esocial/perApur per-apur)
         (el ::esocial/tpAmb tp-amb)
         (el ::esocial/procEmi proc-emi)
         (el ::esocial/verProc ver-proc)))

(defn ^:private ide-empregador
  [{:keys [employer-cnpj]}]
  (group ::esocial/ideEmpregador
         (el ::esocial/tpInsc 1)
         (el ::esocial/nrInsc (-> employer-cnpj br-id/strip (subs 0 8)))))

(defn ^:private root
  "Wrap an event element in the top-level eSocial root."
  [evt]
  (xml/element ::esocial/eSocial {} [evt]))

;; ============================================================================
;; S-1000 — Informações do Empregador
;; ============================================================================

(defn build-s-1000-event
  "Build an eSocial S-1000 (employer master data) event."
  [{:keys [employer-cnpj nm-razao cl-trib ind-coop ind-constr ind-desf
           ind-opc-cp nm-cont cpf-cont fone-cont email-cont
           ide-evento event-id timestamp]
    :or {ind-coop 0 ind-constr 0 ind-desf 0 ind-opc-cp 0
         timestamp (Date.)}}]
  (when-not employer-cnpj (throw (ex-info ":employer-cnpj required" {})))
  (when-not nm-razao      (throw (ex-info ":nm-razao required" {})))
  (when-not cl-trib       (throw (ex-info ":cl-trib required" {})))
  (validate-cnpj! employer-cnpj :employer-cnpj)
  (when cpf-cont (validate-cpf! cpf-cont :cpf-cont))
  (let [evt-id (or event-id
                   (gen-event-id {:employer-cnpj employer-cnpj
                                  :counter 1000
                                  :timestamp timestamp}))]
    (root
     (xml/element
      ::esocial/evtInfoEmpregador
      {:Id evt-id}
      [(ide-evento-tabela ide-evento)
       (ide-empregador {:employer-cnpj employer-cnpj})
       (group ::esocial/infoEmpregador
              (group ::esocial/inclusao
                     (group ::esocial/idePeriodo
                            (el ::esocial/iniValid (fmt-yyyy-mm timestamp)))
                     (group ::esocial/infoCadastro
                            (el ::esocial/nmRazao nm-razao)
                            (el ::esocial/classTrib cl-trib)
                            (el ::esocial/natJurid "2062")
                            (el ::esocial/indCoop ind-coop)
                            (el ::esocial/indConstr ind-constr)
                            (el ::esocial/indDesFolha ind-desf)
                            (el ::esocial/indOpcCP ind-opc-cp)
                            (el ::esocial/indPorte 0)
                            (el ::esocial/indOptRegEletron 1)
                            (when nm-cont (el ::esocial/nmCtt nm-cont))
                            (when cpf-cont (el ::esocial/cpfCtt
                                               (br-id/strip cpf-cont)))
                            (when fone-cont (el ::esocial/foneFixo fone-cont))
                            (when email-cont (el ::esocial/email email-cont))
                            (el ::esocial/indAcordoIsenMulta 0))))]))))

;; ============================================================================
;; S-1005 — Tabela de Estabelecimentos
;; ============================================================================

(defn build-s-1005-event
  "Build an eSocial S-1005 event (Tabela de Estabelecimentos)."
  [{:keys [employer-cnpj estab-cnpj cnae-prep ali-rat fap ini-valid
           ide-evento event-id timestamp]
    :or {timestamp (Date.)}}]
  (when-not employer-cnpj (throw (ex-info ":employer-cnpj required" {})))
  (when-not estab-cnpj    (throw (ex-info ":estab-cnpj required" {})))
  (when-not cnae-prep     (throw (ex-info ":cnae-prep required" {})))
  (when-not ali-rat       (throw (ex-info ":ali-rat required" {})))
  (when-not fap           (throw (ex-info ":fap required" {})))
  (when-not ini-valid     (throw (ex-info ":ini-valid required" {})))
  (validate-cnpj! employer-cnpj :employer-cnpj)
  (validate-cnpj! estab-cnpj :estab-cnpj)
  (let [evt-id (or event-id
                   (gen-event-id {:employer-cnpj employer-cnpj
                                  :counter 1005
                                  :timestamp timestamp}))
        ali-rat-bd (BigDecimal/valueOf (long ali-rat))]
    (root
     (xml/element
      ::esocial/evtTabEstab
      {:Id evt-id}
      [(ide-evento-tabela ide-evento)
       (ide-empregador {:employer-cnpj employer-cnpj})
       (group ::esocial/infoEstab
              (group ::esocial/inclusao
                     (group ::esocial/ideEstab
                            (el ::esocial/tpInsc 1)
                            (el ::esocial/nrInsc (br-id/strip estab-cnpj)))
                     (group ::esocial/idePeriodo
                            (el ::esocial/iniValid (fmt-yyyy-mm ini-valid)))
                     (group ::esocial/dadosEstab
                            (el ::esocial/cnaePrep cnae-prep)
                            (group ::esocial/aliqGilrat
                                   (el ::esocial/aliqRat ali-rat)
                                   (el ::esocial/fap (fmt-amount fap 4))
                                   (el ::esocial/aliqRatAjust
                                       (fmt-amount (.multiply ali-rat-bd
                                                              ^BigDecimal fap)
                                                   4))))))]))))

;; ============================================================================
;; S-1010 — Tabela de Rubricas
;; ============================================================================

(defn build-s-1010-event
  "Build an eSocial S-1010 event (Tabela de Rubricas)."
  [{:keys [employer-cnpj rubrica-code rubrica-desc nat-rubr tp-rubr
           ini-valid ide-tabRubr inc-irrf inc-cp inc-fgts
           ide-evento event-id timestamp]
    :or {timestamp (Date.)}}]
  (when-not employer-cnpj (throw (ex-info ":employer-cnpj required" {})))
  (when-not rubrica-code  (throw (ex-info ":rubrica-code required" {})))
  (when-not rubrica-desc  (throw (ex-info ":rubrica-desc required" {})))
  (when-not nat-rubr      (throw (ex-info ":nat-rubr required" {})))
  (when-not tp-rubr       (throw (ex-info ":tp-rubr required" {})))
  (when-not ini-valid     (throw (ex-info ":ini-valid required" {})))
  (validate-cnpj! employer-cnpj :employer-cnpj)
  (let [evt-id (or event-id
                   (gen-event-id {:employer-cnpj employer-cnpj
                                  :counter 1010
                                  :timestamp timestamp}))]
    (root
     (xml/element
      ::esocial/evtTabRubrica
      {:Id evt-id}
      [(ide-evento-tabela ide-evento)
       (ide-empregador {:employer-cnpj employer-cnpj})
       (group ::esocial/infoRubrica
              (group ::esocial/inclusao
                     (group ::esocial/ideRubrica
                            (el ::esocial/codRubr rubrica-code)
                            (when ide-tabRubr
                              (el ::esocial/ideTabRubr ide-tabRubr))
                            (el ::esocial/iniValid (fmt-yyyy-mm ini-valid)))
                     (group ::esocial/dadosRubrica
                            (el ::esocial/dscRubr rubrica-desc)
                            (el ::esocial/natRubr nat-rubr)
                            (el ::esocial/tpRubr tp-rubr)
                            (el ::esocial/codIncCP   (or inc-cp "00"))
                            (el ::esocial/codIncIRRF (or inc-irrf "00"))
                            (el ::esocial/codIncFGTS (or inc-fgts "00")))))]))))

;; ============================================================================
;; S-1020 — Tabela de Lotações Tributárias
;; ============================================================================

(defn build-s-1020-event
  "Build an eSocial S-1020 event (Tabela de Lotações Tributárias)."
  [{:keys [employer-cnpj cod-lotacao tp-lotacao tp-insc-lot nr-insc-lot
           fpas cod-tercs ini-valid ide-evento event-id timestamp]
    :or {tp-lotacao "01" tp-insc-lot 1 timestamp (Date.)}}]
  (when-not employer-cnpj (throw (ex-info ":employer-cnpj required" {})))
  (when-not cod-lotacao   (throw (ex-info ":cod-lotacao required" {})))
  (when-not ini-valid     (throw (ex-info ":ini-valid required" {})))
  (validate-cnpj! employer-cnpj :employer-cnpj)
  (let [evt-id (or event-id
                   (gen-event-id {:employer-cnpj employer-cnpj
                                  :counter 1020
                                  :timestamp timestamp}))]
    (root
     (xml/element
      ::esocial/evtTabLotacao
      {:Id evt-id}
      [(ide-evento-tabela ide-evento)
       (ide-empregador {:employer-cnpj employer-cnpj})
       (group ::esocial/infoLotacao
              (group ::esocial/inclusao
                     (group ::esocial/ideLotacao
                            (el ::esocial/codLotacao cod-lotacao)
                            (el ::esocial/iniValid (fmt-yyyy-mm ini-valid)))
                     (group ::esocial/dadosLotacao
                            (el ::esocial/tpLotacao tp-lotacao)
                            (el ::esocial/tpInsc tp-insc-lot)
                            (when nr-insc-lot
                              (el ::esocial/nrInsc (br-id/strip nr-insc-lot)))
                            (group ::esocial/fpasLotacao
                                   (el ::esocial/fpas (or fpas "515"))
                                   (el ::esocial/codTercs (or cod-tercs "0000"))))))]))))

;; ============================================================================
;; S-2200 — Cadastramento Inicial / Admissão de Trabalhador
;; ============================================================================

(defn build-s-2200-event
  "Build an eSocial S-2200 (employee onboarding / initial registration)."
  [{:keys [employer-cnpj cpf nis nm-trab sexo raca-cor est-civil
           grau-instr dt-nascto dt-admissao tp-reg-trab tp-reg-prev
           cad-ini matricula cod-categ cbo-cargo nm-cargo remuneracao
           und-sal-fixo nm-mae pais-nascto ide-evento event-id
           timestamp]
    :or {cad-ini 0 tp-reg-trab 1 tp-reg-prev 1 sexo "M" raca-cor 1
         est-civil 1 grau-instr "07" und-sal-fixo 1 pais-nascto "105"
         cod-categ 101 timestamp (Date.)}}]
  (doseq [[k v] {:employer-cnpj employer-cnpj :cpf cpf :nis nis
                 :nm-trab nm-trab :dt-nascto dt-nascto
                 :dt-admissao dt-admissao :matricula matricula}]
    (when (nil? v) (throw (ex-info (str (subs (str k) 1) " required") {}))))
  (validate-cnpj! employer-cnpj :employer-cnpj)
  (validate-cpf! cpf :cpf)
  (let [evt-id (or event-id
                   (gen-event-id {:employer-cnpj employer-cnpj
                                  :counter 2200
                                  :timestamp timestamp}))]
    (root
     (xml/element
      ::esocial/evtAdmissao
      {:Id evt-id}
      [(ide-evento-tabela ide-evento)
       (ide-empregador {:employer-cnpj employer-cnpj})
       (group ::esocial/trabalhador
              (el ::esocial/cpfTrab (br-id/strip cpf))
              (el ::esocial/nmTrab nm-trab)
              (el ::esocial/sexo sexo)
              (el ::esocial/racaCor raca-cor)
              (el ::esocial/estCiv est-civil)
              (el ::esocial/grauInstr grau-instr)
              (when nm-mae (el ::esocial/nmMae nm-mae))
              (group ::esocial/nascimento
                     (el ::esocial/dtNascto (fmt-yyyy-mm-dd dt-nascto))
                     (el ::esocial/paisNascto pais-nascto)
                     (el ::esocial/paisNac pais-nascto)))
       (group ::esocial/vinculo
              (el ::esocial/matricula matricula)
              (el ::esocial/tpRegTrab tp-reg-trab)
              (el ::esocial/tpRegPrev tp-reg-prev)
              (el ::esocial/cadIni cad-ini)
              (group ::esocial/infoRegimeTrab
                     (group ::esocial/infoCeletista
                            (el ::esocial/dtAdm (fmt-yyyy-mm-dd dt-admissao))
                            (el ::esocial/tpAdmissao 1)
                            (el ::esocial/indAdmissao 1)
                            (el ::esocial/tpRegJor 1)
                            (el ::esocial/natAtividade 1)
                            (el ::esocial/dtOpcFGTS
                                (fmt-yyyy-mm-dd dt-admissao))))
              (group ::esocial/infoContrato
                     (el ::esocial/nmCargo (or nm-cargo "A definir"))
                     (when cbo-cargo (el ::esocial/CBOCargo cbo-cargo))
                     (el ::esocial/codCateg cod-categ)
                     (group ::esocial/remuneracao
                            (el ::esocial/vrSalFx
                                (fmt-amount (or remuneracao 0M)))
                            (el ::esocial/undSalFixo und-sal-fixo))
                     (group ::esocial/duracao
                            (el ::esocial/tpContr 1))
                     (group ::esocial/localTrabalho
                            (group ::esocial/localTrabGeral
                                   (el ::esocial/tpInsc 1)
                                   (el ::esocial/nrInsc
                                       (br-id/strip employer-cnpj))))
                     (group ::esocial/horContratual
                            (el ::esocial/qtdHrsSem "44.00")
                            (el ::esocial/tpJornada 1)
                            (el ::esocial/tmpParc 0))
                     (group ::esocial/filiacaoSindical
                            (el ::esocial/cnpjSindCategProf
                                (br-id/strip employer-cnpj)))))]))))

;; ============================================================================
;; S-2299 — Desligamento
;; ============================================================================

(def termination-cause-codes
  "Mapping kontor termination-reason keyword → eSocial mtvDeslig code
   (Tabela 19). Open-set on the kontor side."
  {:dismissal-without-cause "02"
   :dismissal-with-cause    "04"
   :resignation             "07"
   :mutual-agreement        "03"
   :death                   "06"
   :retirement              "08"
   :end-of-contract         "10"
   :other                   "11"})

(defn build-s-2299-event
  "Build an eSocial S-2299 (employee termination)."
  [{:keys [employer-cnpj cpf matricula dt-deslig mtv-deslig
           dt-projfimapi pensao-aliment perc-aliment
           ide-evento event-id timestamp]
    :or {pensao-aliment 0 timestamp (Date.)}}]
  (doseq [[k v] {:employer-cnpj employer-cnpj :cpf cpf
                 :matricula matricula :dt-deslig dt-deslig
                 :mtv-deslig mtv-deslig}]
    (when (nil? v) (throw (ex-info (str (subs (str k) 1) " required") {}))))
  (validate-cnpj! employer-cnpj :employer-cnpj)
  (validate-cpf! cpf :cpf)
  (let [code (if (keyword? mtv-deslig)
               (or (get termination-cause-codes mtv-deslig)
                   (throw (ex-info "Unknown termination cause keyword"
                                   {:keyword mtv-deslig
                                    :known (set (keys termination-cause-codes))})))
               mtv-deslig)
        evt-id (or event-id
                   (gen-event-id {:employer-cnpj employer-cnpj
                                  :counter 2299
                                  :timestamp timestamp}))]
    (root
     (xml/element
      ::esocial/evtDeslig
      {:Id evt-id}
      [(ide-evento-tabela ide-evento)
       (ide-empregador {:employer-cnpj employer-cnpj})
       (group ::esocial/ideVinculo
              (el ::esocial/cpfTrab (br-id/strip cpf))
              (el ::esocial/matricula matricula))
       (group ::esocial/infoDeslig
              (el ::esocial/mtvDeslig code)
              (el ::esocial/dtDeslig (fmt-yyyy-mm-dd dt-deslig))
              (when dt-projfimapi
                (el ::esocial/dtProjFimAPI (fmt-yyyy-mm-dd dt-projfimapi)))
              (el ::esocial/pensAlim pensao-aliment)
              (when (and (= 1 pensao-aliment) perc-aliment)
                (el ::esocial/percAlim (fmt-amount perc-aliment))))]))))

;; ============================================================================
;; S-2300 — Trabalhador Sem Vínculo / Início
;; ============================================================================

(defn build-s-2300-event
  "Build an eSocial S-2300 (worker without an employment-bond)."
  [{:keys [employer-cnpj cpf nm-trab dt-nascto dt-inicio cod-categ
           nat-atividade remuneracao ide-evento event-id timestamp]
    :or {nat-atividade 1 cod-categ 721 timestamp (Date.)}}]
  (doseq [[k v] {:employer-cnpj employer-cnpj :cpf cpf
                 :nm-trab nm-trab :dt-nascto dt-nascto
                 :dt-inicio dt-inicio}]
    (when (nil? v) (throw (ex-info (str (subs (str k) 1) " required") {}))))
  (validate-cnpj! employer-cnpj :employer-cnpj)
  (validate-cpf! cpf :cpf)
  (let [evt-id (or event-id
                   (gen-event-id {:employer-cnpj employer-cnpj
                                  :counter 2300
                                  :timestamp timestamp}))]
    (root
     (xml/element
      ::esocial/evtTSVInicio
      {:Id evt-id}
      [(ide-evento-tabela ide-evento)
       (ide-empregador {:employer-cnpj employer-cnpj})
       (group ::esocial/trabSemVinculo
              (group ::esocial/cpfTrab
                     (el ::esocial/cpf (br-id/strip cpf))
                     (el ::esocial/nmTrab nm-trab))
              (group ::esocial/nascimento
                     (el ::esocial/dtNascto (fmt-yyyy-mm-dd dt-nascto)))
              (group ::esocial/infoTSVInicio
                     (el ::esocial/codCateg cod-categ)
                     (el ::esocial/natAtividade nat-atividade)
                     (el ::esocial/dtInicio (fmt-yyyy-mm-dd dt-inicio))
                     (when remuneracao
                       (group ::esocial/remuneracao
                              (el ::esocial/vrSalFx (fmt-amount remuneracao))
                              (el ::esocial/undSalFixo 1)))))]))))

;; ============================================================================
;; S-2399 — Trabalhador Sem Vínculo / Término
;; ============================================================================

(defn build-s-2399-event
  "Build an eSocial S-2399 (TSV termination)."
  [{:keys [employer-cnpj cpf dt-termino mtv-deslig
           ide-evento event-id timestamp]
    :or {timestamp (Date.)}}]
  (doseq [[k v] {:employer-cnpj employer-cnpj :cpf cpf
                 :dt-termino dt-termino :mtv-deslig mtv-deslig}]
    (when (nil? v) (throw (ex-info (str (subs (str k) 1) " required") {}))))
  (validate-cnpj! employer-cnpj :employer-cnpj)
  (validate-cpf! cpf :cpf)
  (let [code (if (keyword? mtv-deslig)
               (or (get termination-cause-codes mtv-deslig)
                   (throw (ex-info "Unknown termination cause keyword"
                                   {:keyword mtv-deslig})))
               mtv-deslig)
        evt-id (or event-id
                   (gen-event-id {:employer-cnpj employer-cnpj
                                  :counter 2399
                                  :timestamp timestamp}))]
    (root
     (xml/element
      ::esocial/evtTSVTermino
      {:Id evt-id}
      [(ide-evento-tabela ide-evento)
       (ide-empregador {:employer-cnpj employer-cnpj})
       (group ::esocial/ideTSVTermino
              (el ::esocial/cpfTrab (br-id/strip cpf)))
       (group ::esocial/infoTSVTermino
              (el ::esocial/dtTerm (fmt-yyyy-mm-dd dt-termino))
              (el ::esocial/mtvDesligTSV code))]))))

;; ============================================================================
;; S-1200 — Remuneração de Trabalhador (per-employee monthly)
;; ============================================================================

(defn ^:private fact->rubrica-elements
  "Per-fact rubrica elements for the S-1200 itensRemun group."
  [{:keys [components]}]
  (vec
   (for [{:keys [rubrica amount]} components
         :when rubrica
         :let [fator-rubr "1.00"
               vr-rubr (fmt-amount (cond-> ^BigDecimal amount
                                     (neg? (.signum ^BigDecimal amount))
                                     (.negate)))]]
     (group ::esocial/itensRemun
            (el ::esocial/codRubr rubrica)
            (el ::esocial/ideTabRubr "TAB-PADRAO")
            (el ::esocial/qtdRubr "1.00")
            (el ::esocial/fatorRubr fator-rubr)
            (el ::esocial/vrRubr vr-rubr)
            (el ::esocial/indApurIR 1)))))

(defn build-s-1200-event
  "Build an eSocial S-1200 (Remuneração de Trabalhador) event for ONE
   employee × ONE pay-period."
  [{:keys [employer-cnpj cpf matricula per-apur fact cod-lotacao
           cod-categ ide-evento event-id timestamp]
    :or {cod-categ 101 timestamp (Date.)}}]
  (doseq [[k v] {:employer-cnpj employer-cnpj :cpf cpf
                 :matricula matricula :per-apur per-apur :fact fact
                 :cod-lotacao cod-lotacao}]
    (when (nil? v) (throw (ex-info (str (subs (str k) 1) " required") {}))))
  (validate-cnpj! employer-cnpj :employer-cnpj)
  (validate-cpf! cpf :cpf)
  (let [evt-id (or event-id
                   (gen-event-id {:employer-cnpj employer-cnpj
                                  :counter 1200
                                  :timestamp timestamp}))
        rubricas (fact->rubrica-elements fact)]
    (root
     (xml/element
      ::esocial/evtRemun
      {:Id evt-id}
      [(ide-evento-periodico (merge {:per-apur (fmt-yyyy-mm per-apur)}
                                    ide-evento))
       (ide-empregador {:employer-cnpj employer-cnpj})
       (group ::esocial/ideTrabalhador
              (el ::esocial/cpfTrab (br-id/strip cpf)))
       (group ::esocial/dmDev
              (el ::esocial/ideDmDev "DM-1")
              (el ::esocial/codCateg cod-categ)
              (group ::esocial/infoPerApur
                     (xml/element ::esocial/ideEstabLot {}
                                  (concat
                                   [(el ::esocial/tpInsc 1)
                                    (el ::esocial/nrInsc
                                        (br-id/strip employer-cnpj))
                                    (el ::esocial/codLotacao cod-lotacao)]
                                   [(xml/element ::esocial/detVerbas {}
                                                 rubricas)]))))]))))

;; ============================================================================
;; S-1210 — Pagamentos de Rendimentos do Trabalho
;; ============================================================================

(defn build-s-1210-event
  "Build an eSocial S-1210 (Pagamentos) event for ONE employee × ONE
   pay-period."
  [{:keys [employer-cnpj cpf per-apur dt-pgto net-amount ide-dm-dev
           tp-pgto ide-evento event-id timestamp]
    :or {tp-pgto 1 timestamp (Date.)}}]
  (doseq [[k v] {:employer-cnpj employer-cnpj :cpf cpf
                 :per-apur per-apur :dt-pgto dt-pgto
                 :net-amount net-amount :ide-dm-dev ide-dm-dev}]
    (when (nil? v) (throw (ex-info (str (subs (str k) 1) " required") {}))))
  (validate-cnpj! employer-cnpj :employer-cnpj)
  (validate-cpf! cpf :cpf)
  (let [evt-id (or event-id
                   (gen-event-id {:employer-cnpj employer-cnpj
                                  :counter 1210
                                  :timestamp timestamp}))]
    (root
     (xml/element
      ::esocial/evtPgtos
      {:Id evt-id}
      [(ide-evento-periodico (merge {:per-apur (fmt-yyyy-mm per-apur)}
                                    ide-evento))
       (ide-empregador {:employer-cnpj employer-cnpj})
       (group ::esocial/ideBenef
              (el ::esocial/cpfBenef (br-id/strip cpf)))
       (group ::esocial/infoPgto
              (el ::esocial/dtPgto (fmt-yyyy-mm-dd dt-pgto))
              (el ::esocial/tpPgto tp-pgto)
              (el ::esocial/perRef (fmt-yyyy-mm per-apur))
              (el ::esocial/ideDmDev ide-dm-dev)
              (el ::esocial/vrLiq (fmt-amount net-amount)))]))))

;; ============================================================================
;; S-1299 — Fechamento dos Eventos Periódicos
;; ============================================================================

(defn build-s-1299-event
  "Build an eSocial S-1299 (Fechamento dos Eventos Periódicos)."
  [{:keys [employer-cnpj per-apur ev-pertrab ev-pgtoscom ev-aqprod
           ev-comprod ev-contratavnp ide-evento event-id timestamp]
    :or {ev-pertrab true ev-pgtoscom false ev-aqprod false
         ev-comprod false ev-contratavnp false timestamp (Date.)}}]
  (when-not employer-cnpj (throw (ex-info ":employer-cnpj required" {})))
  (when-not per-apur (throw (ex-info ":per-apur required" {})))
  (validate-cnpj! employer-cnpj :employer-cnpj)
  (letfn [(bool->s [b] (if b "S" "N"))]
    (let [evt-id (or event-id
                     (gen-event-id {:employer-cnpj employer-cnpj
                                    :counter 1299
                                    :timestamp timestamp}))]
      (root
       (xml/element
        ::esocial/evtFechaEvPer
        {:Id evt-id}
        [(ide-evento-periodico (merge {:per-apur (fmt-yyyy-mm per-apur)}
                                      ide-evento))
         (ide-empregador {:employer-cnpj employer-cnpj})
         (group ::esocial/ideRespInf
                (el ::esocial/nmResp "Kontor Payroll Engine")
                (el ::esocial/cpfResp "00000000000")
                (el ::esocial/telefone "00000000")
                (el ::esocial/email "noreply@example.com"))
         (group ::esocial/infoFech
                (el ::esocial/evtRemun  (bool->s ev-pertrab))
                (el ::esocial/evtPgtos  (bool->s true))
                (el ::esocial/evtAqProd (bool->s ev-aqprod))
                (el ::esocial/evtComProd (bool->s ev-comprod))
                (el ::esocial/evtContratAvNP (bool->s ev-contratavnp))
                (el ::esocial/evtPgComerc (bool->s ev-pgtoscom)))])))))

;; ============================================================================
;; XML emit helper
;; ============================================================================

(defn emit-xml
  "Serialize an event element to XML string. eSocial requires UTF-8 +
   the XML declaration. Consumer signs (ICP-Brasil) + transmits."
  [event-element]
  (-> event-element
      (xml/emit-str)
      (str/trim)))

;; ============================================================================
;; Validation of fact-shape
;; ============================================================================

(defn validate-fact-for-s1200!
  "Ensure a PayrollFact carries the required :rubrica annotation on
   every posting-generating component. Throws if any are missing."
  [{:keys [components] :as fact}]
  (let [missing (->> components
                     (filter (fn [c] (not= false (:posts? c))))
                     (remove :rubrica))]
    (when (seq missing)
      (throw (ex-info "PayrollFact components missing :rubrica annotation"
                      {:fact fact
                       :missing-components missing}))))
  fact)
