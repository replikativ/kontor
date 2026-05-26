(ns kontor.payroll-cn.posting-builder
  "CN payroll posting builder. Materializes GL postings from
   `PayrollFacts` per note 87 §4 + §6.

   ## The per-pay-period journal entry shape (note 87 §4)

       DR  6602/5603 Wage expense (gross)         (admin/sales/mfg)
       DR  6602/5603 Employer SI expense
       DR  6602/5603 Employer HF expense
           CR  2211.01 应付职工薪酬-工资 (net to employee)
           CR  2221.xx 应交税费-个人所得税 (IIT withholding)
           CR  2211.03 应付职工薪酬-社保 (employee SI)
           CR  2211.04 应付职工薪酬-公积金 (employee HF)
           CR  2211.03 应付职工薪酬-社保 (employer SI)
           CR  2211.04 应付职工薪酬-公积金 (employer HF)

   For each fact we emit:

   - one Wages-expense DEBIT for the GROSS (sum of positive
     employee-side component amounts that map to wage tags);
   - one credit per payable bucket (IIT / SI / HF) for each employee
     deduction;
   - one debit per employer-side expense + one credit per matching
     payable bucket;
   - one Wages-payable (2211.01) credit for the net.

   ## Province routing (note 87 §2.2)

   Per-province allocation lives on `:kontor.posting/analytic-distributions`
   via an `:analytic-plan/code \"cn-province\"` (consumer-installed at
   install time, OR auto-installed by `kontor.payroll-cn.core/install!`),
   NOT on `:kontor.posting/entity`. A CN Ltd Co with employees in BJ / SH / SZ
   is ONE legal entity (one CIT filing) — `:kontor.posting/entity` is reserved
   for true cross-entity scenarios.

   Per-city allocation is a follow-up (note 87 §7): consumers needing
   it install a `cn-city` analytic plan and add per-city
   `:analytic-account` rows themselves.

   ## Accounts map shape

   The consumer supplies an `:accounts` map keyed by the
   `:account-tag` keywords in `kontor.payroll-cn.wage-types`:

       {:cn-payroll-wages-expense   <wage expense :account ref>
        :cn-payroll-net-wages       <2211.01 net wages payable ref>
        :cn-payroll-iit             <2221.xx IIT payable ref>
        :cn-payroll-ee-si           <2211.03 employee SI payable ref>
        :cn-payroll-ee-hf           <2211.04 employee HF payable ref>
        :cn-payroll-er-si-expense   <employer SI expense ref>
        :cn-payroll-er-si-payable   <2211.03 employer SI payable ref>
        :cn-payroll-er-hf-expense   <employer HF expense ref>
        :cn-payroll-er-hf-payable   <2211.04 employer HF payable ref>
        :cn-payroll-bonus-payable   <2211.01 annual bonus accrual ref>}

   Missing tags throw with a useful message; the consumer must
   register an account for every component-kind their engine emits.

   Reference: note 87 §4 + §6."
  (:require [clojure.string :as str]
            [kontor.payroll-provider :as pp]
            [kontor.payroll-cn.wage-types :as wt])
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
                      {:type :cn-payroll/missing-account-tag
                       :tag tag
                       :available-tags (set (keys accounts))
                       :hint "Add an entry to :accounts keyed by the wage-types/account-tag for this :component-kind."}))))

(defn- province-distribution
  "Build an analytic-distribution map for per-province allocation, or
   nil if the fact lacks a province tag. The consumer is responsible
   for installing the `:analytic-plan/code \"cn-province\"` plan and
   the per-province `:analytic-account` rows at bootstrap time
   (`kontor.payroll-cn.core/install!` does this idempotently).

   `province-code` is an ISO-3166-2:CN subdivision code like `CN-BJ`
   (or just the bare `BJ`)."
  [province-code]
  (when province-code
    (let [code (if (and (string? province-code)
                        (str/starts-with? province-code "CN-"))
                 (subs province-code 3)
                 (str province-code))]
      [{:analytic-distribution/plan [:analytic-plan/code "cn-province"]
        :analytic-distribution/account
        [:analytic-account/path (str "cn-province:" code)]
        :analytic-distribution/percent 100M}])))

;; ============================================================================
;; Per-fact posting legs
;; ============================================================================

(defn- province-of-fact
  "Extract the province code for a fact. Three sources, in order:
     1. `:province` directly on the fact (consumer override / test).
     2. `:jurisdiction-specific-codes :province-of-employment`.
     3. `:jurisdiction-specific-codes :cn/province-of-employment`."
  [fact]
  (or (:province fact)
      (get-in fact [:jurisdiction-specific-codes :province-of-employment])
      (get-in fact [:jurisdiction-specific-codes :cn/province-of-employment])))

(defn- earnings-leg
  "DR wages-expense for the GROSS (sum of positive employee-side
   posting-generating components that map to wage tags). Per-province
   analytic-distribution attached if available."
  [{:keys [components] :as fact}
   {:keys [accounts commodity extras-map]}]
  (let [wage-comps
        (->> components
             (remove :employer-side?)
             (filter (fn [c]
                       (and (pos? (compare ^BigDecimal (:amount c) 0M))
                            (= :cn-payroll-wages-expense
                               (wt/account-tag (:kind c) extras-map))))))
        gross (sum-bd (map :amount wage-comps))
        province (province-of-fact fact)
        dists (province-distribution province)]
    (when (pos? (compare gross 0M))
      [(cond->
        {:kontor.posting/account (account-for-tag! accounts :cn-payroll-wages-expense)
         :kontor.posting/amount gross
         :kontor.posting/commodity commodity
         :kontor.posting/narration "工资费用 (Wages expense — gross)"}
         (seq dists) (assoc :kontor.posting/analytic-distributions dists))])))

(defn- deduction-legs
  "For each negative employee-side deduction component, CR the
   matching payable account."
  [{:keys [components] :as fact}
   {:keys [accounts commodity extras-map]}]
  (let [province (province-of-fact fact)
        dists (province-distribution province)]
    (->> components
         (remove :employer-side?)
         (filter (fn [c] (neg? (compare ^BigDecimal (:amount c) 0M))))
         (mapv (fn [{:keys [kind amount]}]
                 (let [tag (wt/account-tag kind extras-map)
                       _ (when-not tag
                           (throw (ex-info (str "Unknown deduction kind: " kind)
                                           {:type :cn-payroll/unknown-deduction-kind
                                            :kind kind})))
                       acct (account-for-tag! accounts tag)
                       label (or (wt/chinese-name kind extras-map) (name kind))]
                   (cond->
                    {:kontor.posting/account acct
                     :kontor.posting/amount amount
                     :kontor.posting/commodity commodity
                     :kontor.posting/narration (str "扣除 — " label)}
                     (seq dists)
                     (assoc :kontor.posting/analytic-distributions dists))))))))

(defn- employer-side-legs
  "For each employer-side component, DR the expense account AND CR the
   matching payable bucket. Each component produces TWO posting legs:
   one for the expense and one for the liability."
  [{:keys [components] :as fact}
   {:keys [accounts commodity extras-map]}]
  (let [province (province-of-fact fact)
        dists (province-distribution province)]
    (->> components
         (filter :employer-side?)
         ;; Drop the annual-bonus-accrual kind — that's an explicit
         ;; out-of-band primitive (accrual.clj), not a per-pay-period
         ;; component. If the engine emits it, the consumer composes
         ;; it separately.
         (remove (fn [c] (= :annual-bonus-accrual (:kind c))))
         (mapcat (fn [{:keys [kind amount]}]
                   (let [exp-tag (wt/account-tag kind extras-map)
                         pay-tag (wt/payable-tag kind extras-map)
                         _ (when-not exp-tag
                             (throw (ex-info (str "Unknown employer-side kind: " kind)
                                             {:type :cn-payroll/unknown-er-kind
                                              :kind kind})))
                         exp-acct (account-for-tag! accounts exp-tag)
                         pay-acct (when pay-tag (account-for-tag! accounts pay-tag))
                         label (or (wt/chinese-name kind extras-map) (name kind))]
                     (cond-> [(cond->
                               {:kontor.posting/account exp-acct
                                :kontor.posting/amount amount
                                :kontor.posting/commodity commodity
                                :kontor.posting/narration (str "单位承担 — " label)}
                                (seq dists)
                                (assoc :kontor.posting/analytic-distributions dists))]
                       pay-acct
                       (conj (cond->
                              {:kontor.posting/account pay-acct
                               :kontor.posting/amount (.negate ^BigDecimal amount)
                               :kontor.posting/commodity commodity
                               :kontor.posting/narration (str "单位应付 — " label)}
                               (seq dists)
                               (assoc :kontor.posting/analytic-distributions dists))))))))))

(defn- net-wages-leg
  "CR 2211.01 应付职工薪酬-工资 for the net amount (gross + sum of
   deductions — already what the substrate carries as :net)."
  [{:keys [net] :as fact}
   {:keys [accounts commodity]}]
  (let [province (province-of-fact fact)
        dists (province-distribution province)]
    (when (pos? (compare ^BigDecimal net 0M))
      [(cond->
        {:kontor.posting/account (account-for-tag! accounts :cn-payroll-net-wages)
         :kontor.posting/amount (.negate ^BigDecimal net)
         :kontor.posting/commodity commodity
         :kontor.posting/narration "应付职工薪酬-工资 (net wages payable)"}
         (seq dists)
         (assoc :kontor.posting/analytic-distributions dists))])))

(defn fact->postings
  "Translate one PayrollFact into a balanced set of posting maps. The
   per-fact set sums to zero per (ledger, commodity) — the substrate's
   invariant. When multiple facts compose into one transaction, the
   kernel's per-(ledger × commodity) sum-to-zero check still passes."
  [fact opts]
  (vec
   (concat
    (earnings-leg     fact opts)
    (deduction-legs   fact opts)
    (employer-side-legs fact opts)
    (net-wages-leg    fact opts))))

;; ============================================================================
;; CnPayrollPostingBuilder — the PayrollPostingBuilder protocol impl
;; ============================================================================

(defrecord CnPayrollPostingBuilder [opts]
  pp/PayrollPostingBuilder
  (build-postings [_ payroll-facts
                   {:keys [accounts ledger]}]
    (let [commodity (or (:commodity opts)
                        (throw (ex-info ":commodity required in CnPayrollPostingBuilder opts"
                                        {:type :cn-payroll/missing-commodity})))
          extras-map (:extras-map opts)
          base-opts {:accounts accounts
                     :commodity commodity
                     :extras-map extras-map}]
      (vec
       (mapcat
        (fn [fact]
          (let [postings (fact->postings fact base-opts)]
            (cond->> postings
              ledger (mapv #(assoc % :kontor.posting/ledger ledger)))))
        payroll-facts)))))

;; ============================================================================
;; Pure functional entry (no providers required)
;; ============================================================================

(defn build-payroll-postings
  "Public functional entry — pure (no providers). Returns a vector of
   posting maps ready for `kontor.posting/build-transaction-tx-data`.

   Required keys:
     :facts        — vector of PayrollFacts
     :accounts     — consumer's wage-type → GL account ref map
     :commodity    — :commodity ref (typically CNY)

   Optional keys:
     :ledger       — :ledger eid to stamp on every posting
     :extras-map   — consumer-supplied :component-kind catalog
                     extensions"
  [{:keys [facts accounts commodity ledger extras-map]}]
  (when-not commodity
    (throw (ex-info "build-payroll-postings: :commodity required"
                    {:type :cn-payroll/missing-commodity})))
  (let [base-opts {:accounts accounts
                   :commodity commodity
                   :extras-map extras-map}]
    (vec
     (mapcat (fn [fact]
               (let [postings (fact->postings fact base-opts)]
                 (cond->> postings
                   ledger (mapv #(assoc % :kontor.posting/ledger ledger)))))
             facts))))
