(ns kontor.payroll-in.posting-builder
  "IN payroll posting builder. Materializes GL postings from
   `PayrollFacts` per the Schedule III layout (Companies Act 2013
   Division II, Ind AS-aligned).

   ## Per-pay-period journal entry shape

       DR  Salaries and Wages          (gross — basic + DA + HRA + …)
       DR  Employer PF contribution    (12% — EPS 8.33% + EPF 3.67%
                                        + EDLI 0.5%)
       DR  Employer ESI contribution   (3.25%)
       DR  Bonus / Leave / Gratuity accrual expense (when in-band)
           CR  TDS payable             (Section 192 withholding)
           CR  PF payable              (employee 12% + employer 12% + EDLI)
           CR  ESI payable             (employee 0.75% + employer 3.25%)
           CR  Professional Tax payable (per-state)
           CR  Bonus / Leave / Gratuity liability
           CR  Wages payable (net to employees)

   Three CRA-style separate statutory buckets: TDS / PF / ESI / PT —
   NEVER collapsed (each has its own filing cadence + portal).

   ## Per-state Professional Tax

   PT varies per state. Routing:
     1. Each posting carries an `:analytic-distribution` on the
        `:kontor.analytic-plan/code \"in-state\"` plan (consumer-installed
        per `kontor.payroll-in.core/install!`), with the state code
        as `:kontor.analytic-account/code`.
     2. The PT-payable account itself is one liability; per-state
        breakdown lives on the analytic-distribution axis (mirrors
        US ADP's per-state W-2 box 15 routing per ADR-077).

   ## Multi-state hybrid employees

   When `:state-allocations` is passed to `build-postings`, the
   builder splits per-state distributions per employee. Default
   (single-state) uses the per-fact `:province-of-employment`
   (read from `:jurisdiction-specific-codes`) for 100% allocation.

   ## Accounts map shape

   The consumer's `:accounts` map keys on the wage-types' `:account-tag`
   keywords:

       {:in-payroll-salaries-wages   <wages-expense :account ref>
        :in-payroll-bonus            <bonus-expense ref>
        :in-payroll-bonus-accrual    <bonus-accrual-expense ref>
        :in-payroll-leave-accrual    <leave-accrual-expense ref>
        :in-payroll-gratuity-paid    <gratuity-paid-expense ref>
        :in-payroll-gratuity-accrual <gratuity-accrual-expense ref>
        :in-payroll-er-pf            <employer-PF-expense ref>
        :in-payroll-er-esi           <employer-ESI-expense ref>
        :in-payroll-tds-payable      <TDS-payable ref>
        :in-payroll-pf-payable       <PF-payable ref>
        :in-payroll-esi-payable      <ESI-payable ref>
        :in-payroll-pt-payable       <PT-payable ref>
        :in-payroll-bonus-payable    <bonus-payable ref>
        :in-payroll-leave-liability  <leave-liability ref>
        :in-payroll-gratuity-liability <gratuity-liability ref>
        :in-payroll-net-wages        <wages-payable ref>
        :in-payroll-other-deduction  <other-deduction ref>
        :in-payroll-loan-recovery    <loan-recovery ref>
        :in-payroll-garnishment      <garnishment ref>}

   Missing tags throw with a useful message — kontor never silently
   drops a posting (consistent with CA / US / DE builders).

   Reference: doc/research/79-hr-payroll-stage-r-plan.md §5.3,
   modules/payroll-ca/src/kontor/payroll_ca/posting_builder.clj
   (structural template)."
  (:require [clojure.string :as str]
            [kontor.payroll-in.wage-types :as wt]
            [kontor.provider.payroll-provider :as pp])
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

(defn- province-of-fact
  "Read the province-of-employment from a fact's
   :jurisdiction-specific-codes, with optional fallback."
  [fact default]
  (or (:province-of-employment (:jurisdiction-specific-codes fact))
      default))

(defn- state-distribution
  "Build analytic-distribution maps for per-state PT allocation, or nil
   if neither the fact nor the override carries state info.

   When :state-allocations supplies a per-employee {state-code → pct}
   map, emit one distribution per state. Otherwise emit one 100%
   distribution against the fact's primary state."
  [fact employee-allocations default-state]
  (let [primary (province-of-fact fact default-state)]
    (cond
      (and (map? employee-allocations) (seq employee-allocations))
      (mapv (fn [[s pct]]
              {:kontor.analytic-distribution/plan [:kontor.analytic-plan/code "in-state"]
               :kontor.analytic-distribution/account
               [:kontor.analytic-account/path (str "in-state:" (name s))]
               :kontor.analytic-distribution/percent (bigdec pct)})
            employee-allocations)

      primary
      [{:kontor.analytic-distribution/plan [:kontor.analytic-plan/code "in-state"]
        :kontor.analytic-distribution/account
        [:kontor.analytic-account/path (str "in-state:" primary)]
        :kontor.analytic-distribution/percent 100M}]

      :else nil)))

(defn- attach-distribution
  "Attach analytic-distribution refs to a posting when dists is non-nil."
  [posting dists]
  (cond-> posting
    (seq dists) (assoc :kontor.posting/analytic-distributions dists)))

;; ============================================================================
;; Per-fact posting legs
;; ============================================================================

(defn- earnings-leg
  "DR salaries-and-wages-expense for the gross. PT is NOT routed via
   this leg — it's a deduction (see deduction-legs)."
  [{:keys [components] :as fact}
   {:keys [accounts commodity extras-map narration default-state
           employee-allocations]}]
  (let [wage-comps
        (->> components
             (remove :employer-side?)
             (filter (fn [c]
                       (and (pos? (compare ^BigDecimal (:amount c) 0M))
                            (let [tag (wt/account-tag (:kind c) extras-map)]
                              (or (= tag :in-payroll-salaries-wages)
                                  (= tag :in-payroll-bonus)
                                  (= tag :in-payroll-gratuity-paid)))))))]
    (when (seq wage-comps)
      (->> wage-comps
           (group-by (comp #(wt/account-tag % extras-map) :kind))
           (mapv (fn [[tag comps]]
                   (let [total (sum-bd (map :amount comps))]
                     (attach-distribution
                      {:kontor.posting/account (account-for-tag! accounts tag)
                       :kontor.posting/amount total
                       :kontor.posting/commodity commodity
                       :kontor.posting/narration
                       (or narration
                           (str (name tag) " (gross — "
                                (str/join ", " (distinct (map (comp name :kind) comps)))
                                ")"))}
                      (state-distribution fact employee-allocations default-state)))))))))

(defn- deduction-legs
  "For each negative employee-side deduction component, CR the matching
   payable account. PT lands on PT-payable with the state distribution
   driving the per-state breakdown."
  [{:keys [components] :as fact}
   {:keys [accounts commodity extras-map default-state employee-allocations]}]
  (->> components
       (remove :employer-side?)
       (filter (fn [c] (neg? (compare ^BigDecimal (:amount c) 0M))))
       (mapv (fn [{:keys [kind amount]}]
               (let [tag (wt/account-tag kind extras-map)
                     _ (when-not tag
                         (throw (ex-info (str "Unknown IN deduction kind: " kind)
                                         {:kind kind})))
                     acct (account-for-tag! accounts tag)
                     ;; PT routing — attach state distribution.
                     dists (when (= tag :in-payroll-pt-payable)
                             (state-distribution fact employee-allocations
                                                 default-state))]
                 (attach-distribution
                  {:kontor.posting/account acct
                   :kontor.posting/amount amount        ; already negative
                   :kontor.posting/commodity commodity
                   :kontor.posting/narration (str "Payroll deduction: " (name kind))}
                  dists))))))

(defn- employer-side-legs
  "For each employer-side component, DR expense + CR payable.
   Routing rules:
     - PF-employer (incl. EPS / EPF / EDLI subkinds) → DR er-pf,
       CR pf-payable.
     - ESI-employer → DR er-esi, CR esi-payable.
     - bonus-accrual / leave-accrual / gratuity-accrual → DR matching
       accrual-expense, CR matching liability.
   All employer-side amounts arrive positive (engine convention)."
  [{:keys [components]}
   {:keys [accounts commodity extras-map]}]
  (->> components
       (filter :employer-side?)
       (mapcat (fn [{:keys [kind amount]}]
                 (let [exp-tag (wt/account-tag kind extras-map)
                       pay-tag (wt/payable-tag kind extras-map)
                       _ (when-not exp-tag
                           (throw (ex-info (str "Unknown IN employer-side kind: " kind)
                                           {:kind kind})))
                       exp-acct (account-for-tag! accounts exp-tag)
                       pay-acct (when pay-tag (account-for-tag! accounts pay-tag))]
                   (cond-> [{:kontor.posting/account exp-acct
                             :kontor.posting/amount amount
                             :kontor.posting/commodity commodity
                             :kontor.posting/narration (str "Employer expense: " (name kind))}]
                     pay-acct
                     (conj {:kontor.posting/account pay-acct
                            :kontor.posting/amount (.negate ^BigDecimal amount)
                            :kontor.posting/commodity commodity
                            :kontor.posting/narration (str "Employer payable: " (name kind))})))))))

(defn- net-wages-leg
  "CR wages-payable for the net (gross + Σ deductions, all
   employee-side — same as substrate :net)."
  [{:keys [net]}
   {:keys [accounts commodity]}]
  (when (and net (pos? (compare ^BigDecimal net 0M)))
    [{:kontor.posting/account (account-for-tag! accounts :in-payroll-net-wages)
      :kontor.posting/amount (.negate ^BigDecimal net)
      :kontor.posting/commodity commodity
      :kontor.posting/narration "Wages payable (net)"}]))

(defn fact->postings
  "Translate one PayrollFact into a balanced set of posting maps. The
   per-fact set sums to zero per the substrate invariant."
  [fact opts]
  (vec
   (concat
    (earnings-leg       fact opts)
    (deduction-legs     fact opts)
    (employer-side-legs fact opts)
    (net-wages-leg      fact opts))))

;; ============================================================================
;; build-postings (functional entry point)
;; ============================================================================

(defn build-payroll-postings
  "Pure functional entry — returns a vector of posting maps ready for
   `kontor.posting/build-transaction-tx-data`.

   Required keys:
     :facts             — vector of PayrollFacts
     :accounts          — :account-tag → :account ref map
     :commodity         — :commodity ref (INR typically)

   Optional keys:
     :ledger            — single ledger eid (assoc'd onto every posting
                          when present); used by run-payroll!.
     :extras-map        — wage-types extras for consumer-extended kinds
     :default-state     — ISO-3166-2 state code for facts that don't
                          carry one (fallback for PT routing)
     :state-allocations — keyed by employment-eid → {state-code → pct}
                          for hybrid / multi-state employees"
  [{:keys [facts accounts commodity ledger extras-map default-state
           state-allocations]}]
  (when-not commodity
    (throw (ex-info ":commodity required" {})))
  (let [base-opts {:accounts accounts
                   :commodity commodity
                   :extras-map extras-map
                   :default-state default-state}
        all (mapcat
             (fn [fact]
               (let [per-emp-allocs
                     (when state-allocations
                       (get state-allocations (:employment fact)))
                     opts (assoc base-opts
                                 :employee-allocations per-emp-allocs)
                     postings (fact->postings fact opts)]
                 (cond->> postings
                   ledger (mapv #(assoc % :kontor.posting/ledger ledger)))))
             facts)]
    (vec all)))

;; ============================================================================
;; InPayrollPostingBuilder — PayrollPostingBuilder protocol impl
;; ============================================================================

(defrecord InPayrollPostingBuilder [opts]
  pp/PayrollPostingBuilder
  (build-postings [_ payroll-facts
                   {:keys [accounts ledger state-allocations]}]
    (let [commodity (or (:commodity opts)
                        (throw (ex-info ":commodity required in InPayrollPostingBuilder opts" {})))
          extras-map (:extras-map opts)
          default-state (:default-state opts)]
      (build-payroll-postings
       {:facts payroll-facts
        :accounts accounts
        :commodity commodity
        :ledger ledger
        :extras-map extras-map
        :default-state default-state
        :state-allocations state-allocations}))))
