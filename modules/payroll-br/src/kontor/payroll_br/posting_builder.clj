(ns kontor.payroll-br.posting-builder
  "BR payroll posting builder. Materializes GL postings from
   `PayrollFacts` per ADR-081 §7.

   ## The per-pay-period journal entry shape (ADR-081 §7.1)

       DR  Salários e ordenados (gross)
       DR  INSS empregador (CPP)
       DR  FGTS empregador (8%)
       DR  Provisão férias + 1/3
       DR  Provisão 13º salário
       DR  Provisão multa rescisória FGTS
           CR  INSS a recolher — empregado (employee withholding)
           CR  INSS a recolher — empregador (employer CPP)
           CR  FGTS a recolher
           CR  IRRF a recolher
           CR  Contribuição sindical a recolher
           CR  Pensão alimentícia / outras retenções
           CR  Provisão férias (liability)
           CR  Provisão 13º salário (liability)
           CR  Provisão multa rescisória FGTS (liability)
           CR  Salários a pagar (net)

   For each fact we emit:

   - one Wages-expense DEBIT for the gross (sum of positive employee-
     side component amounts that map to wage tags);
   - one credit per statutory payable bucket (INSS-EE / INSS-ER /
     FGTS / IRRF / União / Pensão / etc.) for each employee deduction;
   - one debit per employer-side expense + one credit per matching
     payable / liability bucket;
   - one Salários-a-pagar credit for the net.

   ## Statutory bucket discipline (ADR-081 §3.2)

   The four canonical BR statutory buckets MUST be kept distinct.
   Collapsing INSS-empregado + INSS-empregador or FGTS + INSS breaks
   GFIP and eSocial S-1210 because they have distinct DARF codes,
   distinct due dates, and distinct regulator views:

     - `:br-payroll-inss-employee`  — INSS retido do empregado
     - `:br-payroll-inss-employer`  — INSS patronal (CPP)
     - `:br-payroll-fgts`           — FGTS (Caixa, 8%)
     - `:br-payroll-irrf`           — IRRF retido

   ## CNPJ routing (ADR-081 §4)

   For a group with multiple CNPJs (matriz + filial; each filial has
   its own CNPJ-raiz/0002, /0003, etc.) the consumer routes per-CNPJ
   via the `:account-tag/name` convention. Pass `:cnpj-account-tag` opt
   (e.g. `\"br-cnpj-12345678000190\"`); the builder appends this tag
   to every posting via `:posting/account-tags`, letting downstream
   GFIP + eSocial aggregators filter by CNPJ cheaply.

   ## Accounts map shape

   The consumer supplies an `:accounts` map keyed by the
   `:account-tag` keywords in `kontor.payroll-br.wage-types`:

       {:br-payroll-wages              <wages-expense :account ref>
        :br-payroll-er-inss            <INSS employer expense ref>
        :br-payroll-er-fgts            <FGTS employer expense ref>
        :br-payroll-er-charges         <other employer-charge ref>
        :br-payroll-ferias-accrual     <férias accrual expense ref>
        :br-payroll-13th-accrual       <13º accrual expense ref>
        :br-payroll-severance-accrual  <severance accrual expense ref>
        :br-payroll-inss-employee      <INSS empregado payable ref>
        :br-payroll-inss-employer      <INSS empregador payable ref>
        :br-payroll-fgts               <FGTS payable ref>
        :br-payroll-irrf               <IRRF payable ref>
        :br-payroll-union-dues         <Contribuição sindical ref>
        :br-payroll-garnishment        <Pensão alimentícia ref>
        :br-payroll-other-deduction    <Outras retenções ref>
        :br-payroll-vr-vt              <VR / VT account ref>
        :br-payroll-benefits           <Outros benefícios ref>
        :br-payroll-net-wages          <Salários a pagar (net) ref>
        :br-payroll-ferias-liability   <Férias liability ref>
        :br-payroll-13th-liability     <13º liability ref>
        :br-payroll-severance-liability <Severance liability ref>}

   Missing tags throw with a useful message; the consumer must
   register an account for every component-kind their engine emits.

   Reference: ADR-081 §7."
  (:require [clojure.string :as str]
            [kontor.payroll-provider :as pp]
            [kontor.payroll-br.wage-types :as wt])
  (:import [java.math BigDecimal]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- sum-bd
  ^BigDecimal [bds]
  (reduce (fn [a v] (.add ^BigDecimal a ^BigDecimal v)) 0M bds))

(defn- account-for-tag!
  [accounts tag]
  (or (get accounts tag)
      (throw (ex-info (str "No account configured for tag " tag)
                      {:tag tag
                       :available-tags (set (keys accounts))}))))

(defn- with-cnpj-tag
  "Optionally attach the CNPJ routing tag to a posting via
   :posting/account-tags."
  [posting cnpj-account-tag]
  (if (and cnpj-account-tag (not (str/blank? cnpj-account-tag)))
    (update posting :posting/account-tags
            (fnil conj [])
            [:account-tag/name cnpj-account-tag])
    posting))

;; ============================================================================
;; Per-fact posting legs
;; ============================================================================

(def ^:private wage-account-tags
  "Account tags treated as 'wage gross' for the earnings-leg sum.
   Multiple tags feed the gross because BR distinguishes meal-voucher
   /transport-voucher (employer share) from the base wages; both
   surface as wage components but the chart segregates them onto
   different expense accounts."
  #{:br-payroll-wages
    :br-payroll-vr-vt
    :br-payroll-benefits})

(defn- earnings-legs
  "Group positive employee-side components by their account-tag and
   emit one DR per account-tag with the summed amount. This handles
   the BR convention where wages + VR/VT + outros benefícios each
   route to a distinct expense account."
  [{:keys [components]}
   {:keys [accounts commodity cnpj-account-tag extras-map]}]
  (let [wage-comps
        (->> components
             (remove :employer-side?)
             (filter (fn [c]
                       (and (pos? (compare ^BigDecimal (:amount c) 0M))
                            (contains? wage-account-tags
                                       (wt/account-tag (:kind c) extras-map))))))
        by-tag (group-by #(wt/account-tag (:kind %) extras-map) wage-comps)]
    (->> by-tag
         (mapv (fn [[tag comps]]
                 (let [sum (sum-bd (map :amount comps))]
                   (cond->
                    {:posting/account (account-for-tag! accounts tag)
                     :posting/amount sum
                     :posting/commodity commodity
                     :posting/narration (str "Folha — " (name tag))}
                     cnpj-account-tag
                     (update :posting/account-tags
                             (fnil conj [])
                             [:account-tag/name cnpj-account-tag])))))
         (filter (fn [{:keys [posting/amount]}]
                   (pos? (compare ^BigDecimal amount 0M))))
         vec)))

(defn- deduction-legs
  "For each negative employee-side deduction component, CR the
   matching payable account."
  [{:keys [components]}
   {:keys [accounts commodity cnpj-account-tag extras-map]}]
  (->> components
       (remove :employer-side?)
       (filter (fn [c] (neg? (compare ^BigDecimal (:amount c) 0M))))
       (mapv (fn [{:keys [kind amount]}]
               (let [tag (wt/account-tag kind extras-map)
                     _ (when-not tag
                         (throw (ex-info (str "Unknown deduction kind: " kind)
                                         {:kind kind})))
                     acct (account-for-tag! accounts tag)]
                 (cond->
                  {:posting/account acct
                   :posting/amount amount
                   :posting/commodity commodity
                   :posting/narration (str "Folha desconto: " (name kind))}
                   cnpj-account-tag
                   (update :posting/account-tags
                           (fnil conj [])
                           [:account-tag/name cnpj-account-tag])))))))

(defn- employer-side-legs
  "For each employer-side component, DR the expense account AND CR the
   matching payable bucket. Each component produces TWO posting legs:
   one for the expense and one for the liability."
  [{:keys [components]}
   {:keys [accounts commodity cnpj-account-tag extras-map]}]
  (->> components
       (filter :employer-side?)
       (mapcat (fn [{:keys [kind amount]}]
                 (let [exp-tag (wt/account-tag kind extras-map)
                       pay-tag (wt/payable-tag kind extras-map)
                       _ (when-not exp-tag
                           (throw (ex-info (str "Unknown employer-side kind: " kind)
                                           {:kind kind})))
                       exp-acct (account-for-tag! accounts exp-tag)
                       pay-acct (when pay-tag (account-for-tag! accounts pay-tag))]
                   (cond-> [(cond->
                             {:posting/account exp-acct
                              :posting/amount amount
                              :posting/commodity commodity
                              :posting/narration (str "Encargo empregador: " (name kind))}
                              cnpj-account-tag
                              (update :posting/account-tags
                                      (fnil conj [])
                                      [:account-tag/name cnpj-account-tag]))]
                     pay-acct
                     (conj (cond->
                            {:posting/account pay-acct
                             :posting/amount (.negate ^BigDecimal amount)
                             :posting/commodity commodity
                             :posting/narration (str "Encargo a recolher: " (name kind))}
                             cnpj-account-tag
                             (update :posting/account-tags
                                     (fnil conj [])
                                     [:account-tag/name cnpj-account-tag])))))))))

(defn- net-wages-leg
  "CR salários-a-pagar for the net amount (gross + sum of deductions —
   already what the substrate carries as :net)."
  [{:keys [net]}
   {:keys [accounts commodity cnpj-account-tag]}]
  (when (pos? (compare ^BigDecimal net 0M))
    [(cond->
      {:posting/account (account-for-tag! accounts :br-payroll-net-wages)
       :posting/amount (.negate ^BigDecimal net)
       :posting/commodity commodity
       :posting/narration "Salários a pagar (líquido)"}
       cnpj-account-tag
       (update :posting/account-tags
               (fnil conj [])
               [:account-tag/name cnpj-account-tag]))]))

(defn fact->postings
  "Translate one PayrollFact into a balanced set of posting maps. The
   per-fact set sums to zero (the substrate's invariant). When
   multiple facts compose into one transaction, the kernel's
   per-(ledger × commodity) sum-to-zero check still passes."
  [fact opts]
  (vec
   (concat
    (earnings-legs    fact opts)
    (deduction-legs   fact opts)
    (employer-side-legs fact opts)
    (net-wages-leg    fact opts))))

;; ============================================================================
;; BrPayrollPostingBuilder — the PayrollPostingBuilder protocol impl
;; ============================================================================

(defrecord BrPayrollPostingBuilder [opts]
  pp/PayrollPostingBuilder
  (build-postings [_ payroll-facts
                   {:keys [accounts ledger]}]
    (let [commodity (or (:commodity opts)
                        (throw (ex-info ":commodity required in BrPayrollPostingBuilder opts" {})))
          cnpj-tag (:cnpj-account-tag opts)
          extras-map (:extras-map opts)
          base-opts {:accounts accounts
                     :commodity commodity
                     :cnpj-account-tag cnpj-tag
                     :extras-map extras-map}]
      (vec
       (mapcat
        (fn [fact]
          (let [postings (fact->postings fact base-opts)]
            (cond->> postings
              ledger (mapv #(assoc % :posting/ledger ledger)))))
        payroll-facts)))))

(defn make-posting-builder [opts]
  (->BrPayrollPostingBuilder opts))

;; suppress unused-warning for the helper retained for parity with CA
(comment with-cnpj-tag)
