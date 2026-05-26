(ns kontor.payroll-au.posting-builder
  "AU payroll posting builder. Materializes GL postings from
   `PayrollFacts` per ADR-080.

   ## The per-pay-period journal entry shape

       DR  Wages expense (gross)
       DR  Employer superannuation expense (SG)
       DR  Employer state-payroll-tax expense (accrual; per-state via analytic)
       DR  Employer workers-comp expense (accrual)
           CR  PAYG withholding payable (ATO)
           CR  Superannuation payable (SuperStream)
           CR  Salary-sacrifice clearing
           CR  Wages payable (net)
           CR  State payroll tax payable (per state)
           CR  Workers comp payable

   For each fact we emit:
     - one Wages-expense DEBIT for the gross (sum of positive
       employee-side component amounts that map to wage tags);
     - one credit per ATO / super-fund / state payable bucket for
       each employee deduction;
     - one debit per employer-side expense + one credit per
       matching payable bucket;
     - one Wages-payable credit for the net.

   ## Multi-state allocation (mirror US ADP per ADR-077)

   AU has 8 jurisdictions (NSW / VIC / QLD / WA / SA / TAS / ACT / NT)
   with state payroll tax that varies wildly: threshold-then-rate
   between 4.75 % and 6.85 % depending on state. Allocation lives on
   `:kontor.posting/analytic-distributions` via the `:kontor.analytic-plan/code
   \"state\"` plan + ISO-3166-2:AU per-state `:analytic-account`
   rows (installed via `kontor.payroll-au.core/install-state-analytic-plan!`).

   Same rationale as the US adapter (note 83 §4): one Australian
   Pty Ltd employing remote workers in 5 states is ONE legal entity
   filing ONE BAS + one PAYGW summary; the per-state split is a
   reporting / analytic concern, not a separate balanced-books
   entity. `:kontor.posting/entity` stays reserved for true multi-entity
   scenarios (PEO secondment, intercompany).

   Per-state allocation override is supported via
   `:state-allocations` opt: `{employment-eid {state-code → percent}}`
   for hybrid employees (e.g. 60 % VIC / 40 % NSW for a worker who
   genuinely splits time across state offices).

   ## Accounts map shape

   Consumer supplies an `:accounts` map keyed by the `:account-tag`
   keywords in `kontor.payroll-au.wage-types`:

       {:au-payroll-wages              <wages-expense ref>
        :au-payroll-er-super           <employer super expense ref>
        :au-payroll-er-state-tax       <state-payroll-tax expense ref>
        :au-payroll-er-workers-comp    <workers-comp expense ref>
        :au-payroll-paygw              <ATO PAYGW payable ref>
        :au-payroll-super              <SuperStream payable ref>
        :au-payroll-super-employee     <employee super contribution payable ref>
        :au-payroll-salary-sacrifice   <salary-sacrifice clearing ref>
        :au-payroll-state-tax          <state payroll tax payable ref>
        :au-payroll-workers-comp       <workers comp payable ref>
        :au-payroll-child-support      <child support payable ref>
        :au-payroll-other-deduction    <other deductions payable ref>
        :au-payroll-net-wages          <wages payable ref>}

   Missing tags throw with a useful message; the consumer must
   register an account for every component-kind their engine emits.

   ## RP-equivalent routing (single Australian Pty Ltd model)

   Unlike CA (where a single corporation can have multiple CRA RP
   program-accounts), an Australian business operates under one ABN
   per legal entity. Multi-RP routing is therefore not modeled; per-
   state allocation is the analogous orthogonal split.

   Reference: ADR-080."
  (:require [kontor.payroll-au.wage-types :as wt]
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

(defn- state-distribution
  "Build an `:analytic-distribution` vector for per-state allocation,
   or nil if no state info is available.

   Two sources:
     (a) consumer-supplied `:state-allocations` override (hybrid /
         multi-state employee) — wins when present.
     (b) per-component `:state` (e.g. set on the parsed CSV row).

   Returns nil when neither has a value (single-state SMB stays
   non-analytic — no clutter on the posting)."
  [component state-allocations]
  (cond
    (and (map? state-allocations) (seq state-allocations))
    (mapv (fn [[s pct]]
            {:kontor.analytic-distribution/plan [:kontor.analytic-plan/code "state"]
             :kontor.analytic-distribution/account
             [:kontor.analytic-account/path (str "state:" (name s))]
             :kontor.analytic-distribution/percent (bigdec pct)})
          state-allocations)

    (:state component)
    [{:kontor.analytic-distribution/plan [:kontor.analytic-plan/code "state"]
      :kontor.analytic-distribution/account
      [:kontor.analytic-account/path (str "state:" (name (:state component)))]
      :kontor.analytic-distribution/percent 100M}]

    :else nil))

(defn- with-state-dist
  [posting component state-allocations]
  (let [dists (state-distribution component state-allocations)]
    (cond-> posting
      (seq dists) (assoc :kontor.posting/analytic-distributions dists))))

;; ============================================================================
;; Per-fact posting legs
;; ============================================================================

(defn- earnings-leg
  "DR wages-expense for the GROSS (sum of positive employee-side
   posting-generating components that map to wage tags).

   Salary-sacrifice components are NEGATIVE employee-side rows that
   carry tag `:au-payroll-salary-sacrifice` (NOT `:au-payroll-wages`)
   so they don't reduce gross-wages-DR; they cycle through the
   salary-sacrifice-clearing payable independently."
  [{:keys [components]}
   {:keys [accounts commodity extras-map state-allocations]}]
  (let [wage-comps
        (->> components
             (remove :employer-side?)
             (filter (fn [c]
                       (and (pos? (compare ^BigDecimal (:amount c) 0M))
                            (= :au-payroll-wages
                               (wt/account-tag (:kind c) extras-map))))))
        gross (sum-bd (map :amount wage-comps))]
    (when (pos? (compare gross 0M))
      [(with-state-dist
         {:kontor.posting/account (account-for-tag! accounts :au-payroll-wages)
          :kontor.posting/amount gross
          :kontor.posting/commodity commodity
          :kontor.posting/narration "Wages and salaries (gross)"}
         (first wage-comps) state-allocations)])))

(defn- deduction-legs
  "For each negative employee-side deduction component, CR the
   matching payable account."
  [{:keys [components]}
   {:keys [accounts commodity extras-map state-allocations]}]
  (->> components
       (remove :employer-side?)
       (filter (fn [c] (neg? (compare ^BigDecimal (:amount c) 0M))))
       (mapv (fn [{:keys [kind amount] :as comp}]
               (let [tag (wt/account-tag kind extras-map)
                     _ (when-not tag
                         (throw (ex-info (str "Unknown deduction kind: " kind)
                                         {:kind kind})))
                     acct (account-for-tag! accounts tag)]
                 (with-state-dist
                   {:kontor.posting/account acct
                    :kontor.posting/amount amount
                    :kontor.posting/commodity commodity
                    :kontor.posting/narration (str "Payroll deduction: " (name kind))}
                   comp state-allocations))))))

(defn- employer-side-legs
  "For each employer-side component (super-guarantee, state payroll
   tax, workers-comp), DR the expense account AND CR the matching
   payable bucket. Each component produces TWO posting legs."
  [{:keys [components]}
   {:keys [accounts commodity extras-map state-allocations]}]
  (->> components
       (filter :employer-side?)
       (mapcat (fn [{:keys [kind amount] :as comp}]
                 (let [exp-tag (wt/account-tag kind extras-map)
                       pay-tag (wt/payable-tag kind extras-map)
                       _ (when-not exp-tag
                           (throw (ex-info (str "Unknown employer-side kind: " kind)
                                           {:kind kind})))
                       exp-acct (account-for-tag! accounts exp-tag)
                       pay-acct (when pay-tag (account-for-tag! accounts pay-tag))]
                   (cond-> [(with-state-dist
                              {:kontor.posting/account exp-acct
                               :kontor.posting/amount amount
                               :kontor.posting/commodity commodity
                               :kontor.posting/narration (str "Employer expense: " (name kind))}
                              comp state-allocations)]
                     pay-acct
                     (conj (with-state-dist
                             {:kontor.posting/account pay-acct
                              :kontor.posting/amount (.negate ^BigDecimal amount)
                              :kontor.posting/commodity commodity
                              :kontor.posting/narration (str "Employer payable: " (name kind))}
                             comp state-allocations))))))))

(defn- net-wages-leg
  "CR wages-payable for the net amount (gross + sum of deductions —
   already what the substrate carries as `:net`)."
  [{:keys [net]}
   {:keys [accounts commodity]}]
  (when (pos? (compare ^BigDecimal net 0M))
    [{:kontor.posting/account (account-for-tag! accounts :au-payroll-net-wages)
      :kontor.posting/amount (.negate ^BigDecimal net)
      :kontor.posting/commodity commodity
      :kontor.posting/narration "Wages payable (net)"}]))

(defn fact->postings
  "Translate one PayrollFact into a balanced set of posting maps."
  [fact opts]
  (vec
   (concat
    (earnings-leg     fact opts)
    (deduction-legs   fact opts)
    (employer-side-legs fact opts)
    (net-wages-leg    fact opts))))

;; ============================================================================
;; AuPayrollPostingBuilder — the PayrollPostingBuilder protocol impl
;; ============================================================================

(defrecord AuPayrollPostingBuilder [opts]
  pp/PayrollPostingBuilder
  (build-postings [_ payroll-facts
                   {:keys [accounts ledger state-allocations]}]
    (let [commodity (or (:commodity opts)
                        (throw (ex-info ":commodity required in AuPayrollPostingBuilder opts" {})))
          extras-map (:extras-map opts)
          per-emp-allocs state-allocations]
      (vec
       (mapcat
        (fn [fact]
          (let [emp (:employment fact)
                allocs (when per-emp-allocs (get per-emp-allocs emp))
                base-opts {:accounts accounts
                           :commodity commodity
                           :extras-map extras-map
                           :state-allocations allocs}
                postings (fact->postings fact base-opts)]
            (cond->> postings
              ledger (mapv #(assoc % :kontor.posting/ledger ledger)))))
        payroll-facts)))))
