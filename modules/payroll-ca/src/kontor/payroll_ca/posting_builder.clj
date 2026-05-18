(ns kontor.payroll-ca.posting-builder
  "CA payroll posting builder. Materializes GL postings from
   `PayrollFacts` per note 84 §7.

   ## The per-pay-period journal entry shape (note 84 §7.1)

       DR  Wages expense (gross)
       DR  Employer CPP expense
       DR  Employer EI expense
       DR  Vacation pay accrual expense
           CR  Income tax payable (CRA)
           CR  CPP payable (employee + employer)
           CR  EI payable (employee + 1.4× employer)
           CR  Vacation pay liability
           CR  Wages payable (net)

   For each fact we emit:

   - one Wages-expense DEBIT for the gross (sum of positive
     employee-side component amounts that map to wage-tag accounts);
   - one credit per CRA / RQ payable bucket (CPP / EI / ITX / RPP /
     union dues / etc.) for each employee deduction;
   - one debit per employer-side expense + one credit per matching
     payable bucket;
   - one Wages-payable credit for the net.

   ## RP routing (note 84 §4)

   Per note 84 §4 we route per-RP via the `:account-tag/name`
   convention. The consumer's `:rp-account-tag` opt is a string like
   `\"ca-cra-rp-RP0001\"`; if supplied the builder appends this tag
   to every posting via `:posting/account-tags`, letting downstream
   PD7A + T4 aggregators filter by RP cheaply.

   ## Accounts map shape

   The consumer supplies an `:accounts` map keyed by the
   `:account-tag` keywords in `kontor.payroll-ca.wage-types`:

       {:ca-payroll-wages              <wages-expense :account ref>
        :ca-payroll-er-cpp             <employer CPP expense ref>
        :ca-payroll-er-ei              <employer EI expense ref>
        :ca-payroll-vacation-accrual   <vacation accrual expense ref>
        :ca-payroll-itx                <CRA income tax payable ref>
        :ca-payroll-cpp                <CRA CPP payable ref>
        :ca-payroll-ei                 <CRA EI payable ref>
        :ca-payroll-rpp                <RPP contributions payable ref>
        :ca-payroll-union              <union dues payable ref>
        :ca-payroll-charity            <charitable donations payable ref>
        :ca-payroll-garnishment        <garnishments payable ref>
        :ca-payroll-other-deduction    <other deductions payable ref>
        :ca-payroll-vacation-liability <vacation liability ref>
        :ca-payroll-net-wages          <net wages payable ref>
        ;; QC carve-out (passthrough — emit-side defers to C4.1)
        :ca-payroll-qpp                <QPP payable ref>
        :ca-payroll-qpip               <QPIP payable ref>
        :ca-payroll-qc-itx             <QC ITX payable ref>}

   Missing tags throw with a useful message; the consumer must
   register an account for every component-kind their engine emits.

   Reference: note 84 §7."
  (:require [clojure.string :as str]
            [kontor.payroll-provider :as pp]
            [kontor.payroll-ca.wage-types :as wt])
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

(defn- with-rp-tag
  "Optionally attach the RP routing tag to a posting via
   :posting/account-tags. The kernel's :posting/account-tags is a
   ref-many to :account-tag entities (looked up by name)."
  [posting rp-account-tag]
  (if (and rp-account-tag (not (str/blank? rp-account-tag)))
    (update posting :posting/account-tags
            (fnil conj [])
            [:account-tag/name rp-account-tag])
    posting))

;; Suppress 'unused' for forward ref pattern (some legs ignore the helper)
(comment with-rp-tag)

;; ============================================================================
;; Per-fact posting legs
;; ============================================================================

(defn- earnings-leg
  "DR wages-expense for the GROSS (sum of positive employee-side
   posting-generating components that map to wage tags)."
  [{:keys [components] :as _fact}
   {:keys [accounts commodity rp-account-tag extras-map narration]}]
  (let [wage-comps
        (->> components
             (remove :employer-side?)
             (filter (fn [c]
                       (and (pos? (compare ^BigDecimal (:amount c) 0M))
                            (= :ca-payroll-wages
                               (wt/account-tag (:kind c) extras-map))))))
        gross (sum-bd (map :amount wage-comps))]
    (when (pos? (compare gross 0M))
      [(cond->
        {:posting/account (account-for-tag! accounts :ca-payroll-wages)
         :posting/amount gross
         :posting/commodity commodity
         :posting/narration (or narration "Wages and salaries (gross)")}
         rp-account-tag
         (update :posting/account-tags
                 (fnil conj [])
                 [:account-tag/name rp-account-tag]))])))

(defn- deduction-legs
  "For each negative employee-side deduction component, CR the
   matching payable account."
  [{:keys [components]}
   {:keys [accounts commodity rp-account-tag extras-map]}]
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
                  {:posting/account acct
                   :posting/amount amount
                   :posting/commodity commodity
                   :posting/narration (str "Payroll deduction: " (name kind))}
                   rp-account-tag
                   (update :posting/account-tags
                           (fnil conj [])
                           [:account-tag/name rp-account-tag])))))))

(defn- employer-side-legs
  "For each employer-side component, DR the expense account AND CR the
   matching payable bucket. Each component produces TWO posting legs:
   one for the expense and one for the liability."
  [{:keys [components]}
   {:keys [accounts commodity rp-account-tag extras-map]}]
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
                              :posting/narration (str "Employer expense: " (name kind))}
                              rp-account-tag
                              (update :posting/account-tags
                                      (fnil conj [])
                                      [:account-tag/name rp-account-tag]))]
                     pay-acct
                     (conj (cond->
                            {:posting/account pay-acct
                             :posting/amount (.negate ^BigDecimal amount)
                             :posting/commodity commodity
                             :posting/narration (str "Employer payable: " (name kind))}
                             rp-account-tag
                             (update :posting/account-tags
                                     (fnil conj [])
                                     [:account-tag/name rp-account-tag])))))))))

(defn- net-wages-leg
  "CR wages-payable for the net amount (gross + sum of deductions —
   already what the substrate carries as :net)."
  [{:keys [net]}
   {:keys [accounts commodity rp-account-tag]}]
  (when (pos? (compare ^BigDecimal net 0M))
    [(cond->
      {:posting/account (account-for-tag! accounts :ca-payroll-net-wages)
       :posting/amount (.negate ^BigDecimal net)
       :posting/commodity commodity
       :posting/narration "Wages payable (net)"}
       rp-account-tag
       (update :posting/account-tags
               (fnil conj [])
               [:account-tag/name rp-account-tag]))]))

(defn fact->postings
  "Translate one PayrollFact into a balanced set of posting maps. The
   per-fact set sums to zero (the substrate's invariant). When
   multiple facts compose into one transaction, the kernel's
   per-(ledger × commodity) sum-to-zero check still passes."
  [fact opts]
  (vec
   (concat
    (earnings-leg     fact opts)
    (deduction-legs   fact opts)
    (employer-side-legs fact opts)
    (net-wages-leg    fact opts))))

;; ============================================================================
;; CaPayrollPostingBuilder — the PayrollPostingBuilder protocol impl
;; ============================================================================

(defrecord CaPayrollPostingBuilder [opts]
  pp/PayrollPostingBuilder
  (build-postings [_ payroll-facts
                   {:keys [accounts ledger]}]
    (let [commodity (or (:commodity opts)
                        (throw (ex-info ":commodity required in CaPayrollPostingBuilder opts" {})))
          rp-tag (:rp-account-tag opts)
          extras-map (:extras-map opts)
          base-opts {:accounts accounts
                     :commodity commodity
                     :rp-account-tag rp-tag
                     :extras-map extras-map}]
      (vec
       (mapcat
        (fn [fact]
          (let [postings (fact->postings fact base-opts)]
            (cond->> postings
              ledger (mapv #(assoc % :posting/ledger ledger)))))
        payroll-facts)))))
