(ns kontor.l10n-br.cst
  "Brazilian CST (Código de Situação Tributária) reference tables.

   Each Brazilian tax has its own CST code namespace. CSTs drive
   NF-e XML schema dispatch — different CST → different XML element
   group (e.g. <ICMS00> vs <ICMS10> vs <ICMS30> vs <ICMS40>).

   Tables:
     icms-cst   — Tabela B (CST ICMS), for Regime Normal taxpayers (~10)
     icms-csosn — CSOSN, for Simples Nacional taxpayers (~10)
     icms-orig  — Tabela A (origem do produto), 1-digit (~9)
     ipi-cst    — CST IPI, both entrada and saída (~14)
     pis-cst    — CST PIS, ~30 codes
     cofins-cst — CST COFINS, same set as PIS (intentional alignment)

   Sources (CONFAZ + RFB):
     - Convênio s/nº 1970 + amendments (ICMS CST)
     - Resolução CGSN nº 94/2011 + amendments (CSOSN)
     - NF-e Manual de Integração 4.0, Anexo II (CST IPI/PIS/COFINS)
     - Instrução Normativa RFB 1.009/2010 + amendments (PIS/COFINS CST)

   These are statutory enumerations, not copyrightable expression.")

;; ============================================================================
;; ICMS origem (Tabela A) — 1-digit prefix
;; ============================================================================

(def icms-orig
  "Origin code for the product. Drives interstate-routing decisions:
   codes 1, 2, 3, 6, 7, 8 trigger 4% interstate rate (Res. SF 13/2012)."
  {"0" {:name "Nacional"
        :imported? false}
   "1" {:name "Estrangeira - Importação direta"
        :imported? true}
   "2" {:name "Estrangeira - Adquirida no mercado interno"
        :imported? true}
   "3" {:name "Nacional, conteúdo de importação > 40%"
        :imported? true}
   "4" {:name "Nacional, processo produtivo básico"
        :imported? false}
   "5" {:name "Nacional, conteúdo de importação <= 40%"
        :imported? false}
   "6" {:name "Estrangeira - Importação direta, sem similar nacional, lista CAMEX"
        :imported? true}
   "7" {:name "Estrangeira - Adquirida no mercado interno, sem similar nacional, lista CAMEX"
        :imported? true}
   "8" {:name "Nacional, conteúdo de importação > 70%"
        :imported? true}})

(defn imported-origin?
  "Returns true iff the origin code triggers the 4% interstate rate."
  [orig-code]
  (boolean (get-in icms-orig [(str orig-code) :imported?])))

;; ============================================================================
;; ICMS CST (Tabela B) — Regime Normal (non-Simples)
;; ============================================================================

(def icms-cst
  "ICMS Tabela B. The `:xml-group` key indicates which NF-e XML element
   group to emit (ICMS00, ICMS10, ICMS20, ICMS30, ICMS40, ICMS41,
   ICMS50, ICMS51, ICMS60, ICMS70, ICMS90)."
  {"00" {:name "Tributada integralmente"
         :xml-group :ICMS00 :rates :standard}
   "10" {:name "Tributada e com cobrança do ICMS por ST"
         :xml-group :ICMS10 :rates :standard-with-st}
   "20" {:name "Com redução de base de cálculo"
         :xml-group :ICMS20 :rates :reduced-base}
   "30" {:name "Isenta ou não tributada e com cobrança do ICMS por ST"
         :xml-group :ICMS30 :rates :exempt-with-st}
   "40" {:name "Isenta"
         :xml-group :ICMS40 :rates :exempt}
   "41" {:name "Não tributada"
         :xml-group :ICMS40 :rates :not-taxed}
   "50" {:name "Suspensão"
         :xml-group :ICMS40 :rates :suspended}
   "51" {:name "Diferimento"
         :xml-group :ICMS51 :rates :deferred}
   "60" {:name "ICMS cobrado anteriormente por ST"
         :xml-group :ICMS60 :rates :st-paid-upstream}
   "70" {:name "Com redução de base de cálculo e cobrança do ICMS por ST"
         :xml-group :ICMS70 :rates :reduced-base-with-st}
   "90" {:name "Outras"
         :xml-group :ICMS90 :rates :other}})

;; ============================================================================
;; CSOSN — Simples Nacional
;; ============================================================================

(def icms-csosn
  "Código de Situação da Operação no Simples Nacional. Emitted under
   the <ICMSSN###> XML group (a.k.a. ICMS Simples Nacional)."
  {"101" {:name "Tributada pelo Simples Nacional com permissão de crédito"
          :xml-group :ICMSSN101}
   "102" {:name "Tributada pelo Simples Nacional sem permissão de crédito"
          :xml-group :ICMSSN102}
   "103" {:name "Isenção do ICMS no Simples Nacional para a faixa"
          :xml-group :ICMSSN102}
   "201" {:name "Tributada pelo Simples com permissão de crédito e cobrança do ICMS-ST"
          :xml-group :ICMSSN201}
   "202" {:name "Tributada pelo Simples sem permissão de crédito e cobrança do ICMS-ST"
          :xml-group :ICMSSN202}
   "203" {:name "Isenção do ICMS no Simples Nacional para faixa e cobrança do ICMS-ST"
          :xml-group :ICMSSN202}
   "300" {:name "Imune"
          :xml-group :ICMSSN102}
   "400" {:name "Não tributada pelo Simples Nacional"
          :xml-group :ICMSSN102}
   "500" {:name "ICMS cobrado anteriormente por ST ou por antecipação"
          :xml-group :ICMSSN500}
   "900" {:name "Outros"
          :xml-group :ICMSSN900}})

;; ============================================================================
;; IPI CST
;; ============================================================================

(def ipi-cst
  "CST IPI. Codes 00-49 = entrada (purchases / inbound); 50-99 = saída
   (sales / outbound)."
  {"00" {:name "Entrada com recuperação de crédito"          :xml-group :IPITrib :direction :in}
   "01" {:name "Entrada tributada com alíquota zero"          :xml-group :IPITrib :direction :in}
   "02" {:name "Entrada isenta"                                :xml-group :IPINT   :direction :in}
   "03" {:name "Entrada não-tributada"                         :xml-group :IPINT   :direction :in}
   "04" {:name "Entrada imune"                                 :xml-group :IPINT   :direction :in}
   "05" {:name "Entrada com suspensão"                         :xml-group :IPINT   :direction :in}
   "49" {:name "Outras entradas"                               :xml-group :IPINT   :direction :in}
   "50" {:name "Saída tributada"                               :xml-group :IPITrib :direction :out}
   "51" {:name "Saída tributada com alíquota zero"             :xml-group :IPITrib :direction :out}
   "52" {:name "Saída isenta"                                  :xml-group :IPINT   :direction :out}
   "53" {:name "Saída não-tributada"                           :xml-group :IPINT   :direction :out}
   "54" {:name "Saída imune"                                   :xml-group :IPINT   :direction :out}
   "55" {:name "Saída com suspensão"                           :xml-group :IPINT   :direction :out}
   "99" {:name "Outras saídas"                                 :xml-group :IPINT   :direction :out}})

;; ============================================================================
;; PIS / COFINS CST
;;
;; PIS and COFINS share the CST code structure exactly (codes are
;; aligned). Per NF-e Manual de Integração, the same set of XML
;; element groups applies — PISAliq for standard percent-rate,
;; PISQtde for per-unit, PISNT for non-taxed, PISOutr for other.
;; COFINS variants are COFINSAliq / COFINSQtde / COFINSNT / COFINSOutr.
;; ============================================================================

(defn- pis-cofins-base
  "The PIS/COFINS CST table — same codes for both contributions."
  []
  {"01" {:name "Operação Tributável com Alíquota Básica" :xml-group :Aliq}
   "02" {:name "Operação Tributável com Alíquota Diferenciada" :xml-group :Aliq}
   "03" {:name "Operação Tributável com Alíquota por Unidade de Medida"
         :xml-group :Qtde}
   "04" {:name "Operação Tributável Monofásica - Revenda a Alíquota Zero"
         :xml-group :NT}
   "05" {:name "Operação Tributável por Substituição Tributária" :xml-group :NT}
   "06" {:name "Operação Tributável a Alíquota Zero" :xml-group :NT}
   "07" {:name "Operação Isenta" :xml-group :NT}
   "08" {:name "Operação sem Incidência" :xml-group :NT}
   "09" {:name "Operação com Suspensão" :xml-group :NT}
   "49" {:name "Outras Operações de Saída" :xml-group :Outr}
   "50" {:name "Operação com Direito a Crédito - Vinculada exclusivamente a Receita Tributada no Mercado Interno"
         :xml-group :Aliq}
   "51" {:name "Operação com Direito a Crédito - Vinculada exclusivamente a Receita Não-Tributada no Mercado Interno"
         :xml-group :Aliq}
   "52" {:name "Operação com Direito a Crédito - Vinculada exclusivamente a Receita de Exportação"
         :xml-group :Aliq}
   "53" {:name "Operação com Direito a Crédito - Vinculada a Receitas Tributadas e Não-Tributadas no Mercado Interno"
         :xml-group :Aliq}
   "54" {:name "Operação com Direito a Crédito - Vinculada a Receitas Tributadas no Mercado Interno e de Exportação"
         :xml-group :Aliq}
   "55" {:name "Operação com Direito a Crédito - Vinculada a Receitas Não-Tributadas no Mercado Interno e de Exportação"
         :xml-group :Aliq}
   "56" {:name "Operação com Direito a Crédito - Vinculada a Receitas Tributadas e Não-Tributadas no Mercado Interno e de Exportação"
         :xml-group :Aliq}
   "60" {:name "Crédito Presumido - Operação Vinculada exclusivamente a Receita Tributada no Mercado Interno"
         :xml-group :Aliq}
   "61" {:name "Crédito Presumido - Operação Vinculada exclusivamente a Receita Não-Tributada no Mercado Interno"
         :xml-group :Aliq}
   "62" {:name "Crédito Presumido - Operação Vinculada exclusivamente a Receita de Exportação"
         :xml-group :Aliq}
   "63" {:name "Crédito Presumido - Operação Vinculada a Receitas Tributadas e Não-Tributadas no Mercado Interno"
         :xml-group :Aliq}
   "64" {:name "Crédito Presumido - Operação Vinculada a Receitas Tributadas no Mercado Interno e de Exportação"
         :xml-group :Aliq}
   "65" {:name "Crédito Presumido - Operação Vinculada a Receitas Não-Tributadas no Mercado Interno e de Exportação"
         :xml-group :Aliq}
   "66" {:name "Crédito Presumido - Operação Vinculada a Receitas Tributadas e Não-Tributadas no Mercado Interno e de Exportação"
         :xml-group :Aliq}
   "67" {:name "Crédito Presumido - Outras Operações" :xml-group :Aliq}
   "70" {:name "Operação de Aquisição sem Direito a Crédito" :xml-group :NT}
   "71" {:name "Operação de Aquisição com Isenção" :xml-group :NT}
   "72" {:name "Operação de Aquisição com Suspensão" :xml-group :NT}
   "73" {:name "Operação de Aquisição a Alíquota Zero" :xml-group :NT}
   "74" {:name "Operação de Aquisição sem Incidência da Contribuição" :xml-group :NT}
   "75" {:name "Operação de Aquisição por Substituição Tributária" :xml-group :NT}
   "98" {:name "Outras Operações de Entrada" :xml-group :Outr}
   "99" {:name "Outras Operações" :xml-group :Outr}})

(def pis-cst
  (into {} (map (fn [[k v]] [k (update v :xml-group #(keyword (str "PIS" (name %))))])
                (pis-cofins-base))))

(def cofins-cst
  (into {} (map (fn [[k v]] [k (update v :xml-group #(keyword (str "COFINS" (name %))))])
                (pis-cofins-base))))

;; ============================================================================
;; Validation + lookup
;; ============================================================================

(defn valid-cst?
  "True iff `code` is a known CST for the given `kind`.

   kind ∈ #{:icms :icms-csosn :ipi :pis :cofins :icms-orig}"
  [kind code]
  (boolean
   (case kind
     :icms        (get icms-cst (str code))
     :icms-csosn  (get icms-csosn (str code))
     :icms-orig   (get icms-orig (str code))
     :ipi         (get ipi-cst (str code))
     :pis         (get pis-cst (str code))
     :cofins      (get cofins-cst (str code))
     nil)))

(defn cst-meta
  "Look up the metadata record for a CST code (or nil if unknown)."
  [kind code]
  (case kind
    :icms        (get icms-cst (str code))
    :icms-csosn  (get icms-csosn (str code))
    :icms-orig   (get icms-orig (str code))
    :ipi         (get ipi-cst (str code))
    :pis         (get pis-cst (str code))
    :cofins      (get cofins-cst (str code))
    nil))

(defn cst-xml-group
  "Return the NF-e XML element group keyword to emit for the given CST.
   Used by the per-CST multimethod dispatch in nfe.clj."
  [kind code]
  (:xml-group (cst-meta kind code)))
