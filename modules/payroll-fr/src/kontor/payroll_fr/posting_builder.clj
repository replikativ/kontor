(ns kontor.payroll-fr.posting-builder
  "FR payroll posting builder. Materializes GL postings from
   `PayrollFacts` per ADR-079.

   ## The per-pay-period journal entry shape

       DR  641  Rémunérations du personnel (gross)
       DR  6451 Charges URSSAF (employeur)
       DR  6453 Charges retraite (employeur)
       DR  6454 Charges Pôle emploi (employeur)
       DR  6455 Charges prévoyance (employeur)
       DR  6412 Congés payés (accrual expense)
           CR  431  URSSAF (employee + employer)
           CR  4371 ARRCO/AGIRC (employee + employer)
           CR  4373 Pôle emploi (employee + employer)
           CR  4374 Prévoyance / mutuelle (employee + employer)
           CR  4421 État — PAS (employee)
           CR  427  Oppositions (employee)
           CR  4282 Charges à payer congés payés (accrual)
           CR  421  Personnel — Rémunérations dues (net)

   For each fact we emit:

   - one Salaires-expense DEBIT for the gross (sum of positive
     employee-side component amounts that map to wage tags);
   - one credit per payable bucket (URSSAF / ARRCO-AGIRC / Pôle
     emploi / Prévoyance / PAS / oppositions / etc.) for each
     employee deduction;
   - one debit per employer-side expense + one credit per matching
     payable bucket;
   - one Personnel-net credit for the net wages payable to employees.

   ## Per-établissement routing

   Per ADR-079: multi-SIRET businesses route via `:kontor.account-tag/name`
   convention (e.g. `\"fr-etab-12345678900012\"`); the consumer's
   `:etab-account-tag` opt is a string the builder appends to every
   posting via `:kontor.posting/account-tags`, letting downstream DSN
   aggregators filter by établissement cheaply.

   ## Accounts map shape

   The consumer supplies an `:accounts` map keyed by the
   `:account-tag` keywords in `kontor.payroll-fr.wage-types`:

       {:fr-payroll-salaires           <PCG 6411 :account ref>
        :fr-payroll-conges-payes       <PCG 6412 :account ref>
        :fr-payroll-primes             <PCG 6413 :account ref>
        :fr-payroll-avantages-nature   <PCG 6414 :account ref>
        :fr-payroll-er-urssaf          <PCG 6451 :account ref>
        :fr-payroll-er-retraite        <PCG 6453 :account ref>
        :fr-payroll-er-assedic         <PCG 6454 :account ref>
        :fr-payroll-er-prevoyance      <PCG 6455 :account ref>
        :fr-payroll-conges-accrual     <PCG 6412 :account ref>
        :fr-payroll-personnel-net      <PCG 421  :account ref>
        :fr-payroll-acomptes           <PCG 425  :account ref>
        :fr-payroll-oppositions        <PCG 427  :account ref>
        :fr-payroll-urssaf             <PCG 431  :account ref>
        :fr-payroll-retraite           <PCG 4371 :account ref>
        :fr-payroll-pole-emploi        <PCG 4373 :account ref>
        :fr-payroll-prevoyance         <PCG 4374 :account ref>
        :fr-payroll-pas                <PCG 4421 :account ref>
        :fr-payroll-conges-liability   <PCG 4282 :account ref>}

   Missing tags throw with a useful message; the consumer must
   register an account for every component-kind their engine emits.

   Reference: ADR-079; doc/research/79 §5.3."
  (:require [clojure.string :as str]
            [kontor.payroll-fr.wage-types :as wt]
            [kontor.payroll-provider :as pp])
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

(defn- with-etab-tag
  "Optionally attach the établissement routing tag to a posting via
   :kontor.posting/account-tags. The kernel's :kontor.posting/account-tags is a
   ref-many to :account-tag entities (looked up by name)."
  [posting etab-account-tag]
  (if (and etab-account-tag (not (str/blank? etab-account-tag)))
    (update posting :kontor.posting/account-tags
            (fnil conj [])
            [:kontor.account-tag/name etab-account-tag])
    posting))

;; ============================================================================
;; Per-fact posting legs
;; ============================================================================

(def ^:private wage-tags
  "Account tags that route to a wages / earnings expense (the GROSS
   contributor). Sum of positive employee-side components against
   these tags is what the gross-leg debits."
  #{:fr-payroll-salaires
    :fr-payroll-conges-payes
    :fr-payroll-primes
    :fr-payroll-avantages-nature})

(defn- earnings-legs
  "For each positive employee-side component that maps to a wage tag,
   DEBIT the corresponding PCG expense account. We split by tag rather
   than collapsing into one 641 line so multi-rubrique payrolls (base +
   primes + congés) keep separate posting legs for downstream tracing.
   Sum-to-zero invariant holds at the fact level regardless."
  [{:keys [components]}
   {:keys [accounts commodity etab-account-tag extras-map]}]
  (->> components
       (remove :employer-side?)
       (filter (fn [c]
                 (and (pos? (compare ^BigDecimal (:amount c) 0M))
                      (contains? wage-tags
                                 (wt/account-tag (:kind c) extras-map)))))
       (group-by (fn [c] (wt/account-tag (:kind c) extras-map)))
       (mapv (fn [[tag comps]]
               (let [amount (sum-bd (map :amount comps))]
                 (cond->
                  {:kontor.posting/account (account-for-tag! accounts tag)
                   :kontor.posting/amount amount
                   :kontor.posting/commodity commodity
                   :kontor.posting/narration
                   (str "Rémunérations brutes — " (name tag))}
                   etab-account-tag
                   (update :kontor.posting/account-tags
                           (fnil conj [])
                           [:kontor.account-tag/name etab-account-tag])))))))

(defn- deduction-legs
  "For each negative employee-side deduction component, CR the
   matching payable account."
  [{:keys [components]}
   {:keys [accounts commodity etab-account-tag extras-map]}]
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
                  ;; amount is already negative; CR is negative in
                  ;; kontor's sign convention.
                  {:kontor.posting/account acct
                   :kontor.posting/amount amount
                   :kontor.posting/commodity commodity
                   :kontor.posting/narration (str "Retenue salariale: " (name kind))}
                   etab-account-tag
                   (update :kontor.posting/account-tags
                           (fnil conj [])
                           [:kontor.account-tag/name etab-account-tag])))))))

(defn- employer-side-legs
  "For each employer-side component, DR the expense account AND CR the
   matching payable bucket. Each component produces TWO posting legs:
   one for the expense and one for the liability."
  [{:keys [components]}
   {:keys [accounts commodity etab-account-tag extras-map]}]
  (->> components
       (filter :employer-side?)
       (mapcat (fn [{:keys [kind amount]}]
                 (let [exp-tag (wt/account-tag kind extras-map)
                       pay-tag (wt/payable-tag kind extras-map)
                       _ (when-not exp-tag
                           (throw (ex-info (str "Unknown employer-side kind: " kind)
                                           {:kind kind})))
                       exp-acct (account-for-tag! accounts exp-tag)
                       pay-acct (when pay-tag
                                  (account-for-tag! accounts pay-tag))]
                   (cond-> [(cond->
                             {:kontor.posting/account exp-acct
                              :kontor.posting/amount amount
                              :kontor.posting/commodity commodity
                              :kontor.posting/narration
                              (str "Charges patronales: " (name kind))}
                              etab-account-tag
                              (update :kontor.posting/account-tags
                                      (fnil conj [])
                                      [:kontor.account-tag/name etab-account-tag]))]
                     pay-acct
                     (conj (cond->
                            {:kontor.posting/account pay-acct
                             :kontor.posting/amount (.negate ^BigDecimal amount)
                             :kontor.posting/commodity commodity
                             :kontor.posting/narration
                             (str "Dette charges patronales: " (name kind))}
                             etab-account-tag
                             (update :kontor.posting/account-tags
                                     (fnil conj [])
                                     [:kontor.account-tag/name etab-account-tag])))))))))

(defn- net-wages-leg
  "CR personnel-net for the net amount (gross + sum of deductions —
   already what the substrate carries as :net)."
  [{:keys [net]}
   {:keys [accounts commodity etab-account-tag]}]
  (when (pos? (compare ^BigDecimal net 0M))
    [(cond->
      {:kontor.posting/account (account-for-tag! accounts :fr-payroll-personnel-net)
       :kontor.posting/amount (.negate ^BigDecimal net)
       :kontor.posting/commodity commodity
       :kontor.posting/narration "Personnel — Rémunérations dues (net)"}
       etab-account-tag
       (update :kontor.posting/account-tags
               (fnil conj [])
               [:kontor.account-tag/name etab-account-tag]))]))

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
;; FrPayrollPostingBuilder — the PayrollPostingBuilder protocol impl
;; ============================================================================

(defrecord FrPayrollPostingBuilder [opts]
  pp/PayrollPostingBuilder
  (build-postings [_ payroll-facts
                   {:keys [accounts ledger]}]
    (let [commodity (or (:commodity opts)
                        (throw (ex-info ":commodity required in FrPayrollPostingBuilder opts" {})))
          etab-tag (:etab-account-tag opts)
          extras-map (:extras-map opts)
          base-opts {:accounts accounts
                     :commodity commodity
                     :etab-account-tag etab-tag
                     :extras-map extras-map}]
      (vec
       (mapcat
        (fn [fact]
          (let [postings (fact->postings fact base-opts)]
            (cond->> postings
              ledger (mapv #(assoc % :kontor.posting/ledger ledger)))))
        payroll-facts)))))

;; ============================================================================
;; Use with-etab-tag in the fact-level helper for direct calls
;; ============================================================================
;; Public so consumers tagging postings outside the builder pipeline
;; can attach the établissement tag with the same shape.
(def attach-etab-tag with-etab-tag)
