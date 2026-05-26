(ns kontor.payroll-jp.posting-builder
  "JP payroll posting builder. Materializes GL postings from
   `PayrollFacts` per ADR-084 §5.

   ## The per-pay-period journal entry shape (ADR-084 §5.1)

       DR  Wages and salaries / 給料手当 (gross)
       DR  Bonus / 賞与 (if bonus pay-period)
       DR  Statutory benefits / 法定福利費 (employer 4-bucket SI)
           CR  Health insurance payable / 預り金 — 健康保険料 (ee + er)
           CR  Pension payable          / 預り金 — 厚生年金  (ee + er)
           CR  Employment ins. payable  / 預り金 — 雇用保険  (ee + er)
           CR  Long-term care payable   / 預り金 — 介護保険  (ee + er; ≥40)
           CR  Income tax payable       / 預り金 — 所得税    (NTA)
           CR  Resident tax payable     / 預り金 — 住民税    (municipality)
           CR  Wages payable (net)      / 未払金 — 給与

   For each fact we emit:

   - ONE wages-expense DEBIT for the gross (sum of positive
     employee-side component amounts that map to wage tags). Bonus
     components route to a separate 賞与 expense account per J-GAAP
     convention (not collapsed with 給料手当).
   - ONE credit per 預り金 (Azukari-kin / holding) bucket for each
     employee deduction.
   - ONE debit per employer-side expense + ONE credit per matching
     payable bucket (the 法定福利費 / 預り金 pair).
   - ONE 未払金 (Mibarai-kin / wages payable) credit for the net.

   ## Accounts map shape

   The consumer supplies an `:accounts` map keyed by the
   `:account-tag` keywords in `kontor.payroll-jp.wage-types`:

       {:jp-payroll-wages                  <wages-expense :account ref>
        :jp-payroll-bonus                  <bonus-expense :account ref>
        :jp-payroll-er-statutory-benefits  <法定福利費 expense ref>
        :jp-payroll-health-insurance       <健保 payable ref>
        :jp-payroll-pension                <厚生年金 payable ref>
        :jp-payroll-employment-insurance   <雇用保険 payable ref>
        :jp-payroll-long-term-care         <介護保険 payable ref>
        :jp-payroll-income-tax             <所得税 payable ref>
        :jp-payroll-resident-tax           <住民税 payable ref>
        :jp-payroll-zaikei                 <財形貯蓄 payable ref>
        :jp-payroll-union-dues             <組合費 payable ref>
        :jp-payroll-other-deduction        <その他控除 payable ref>
        :jp-payroll-net-wages              <net wages payable ref>}

   Missing tags throw with a useful message; the consumer must
   register an account for every component-kind their engine emits.

   ## JPY rounding (ADR-013)

   JPY has no sub-unit (precision 0). The posting builder accepts
   BigDecimal amounts that may carry a fractional part (e.g. the
   engine emits `1500.5` from a percentage calc); each leg is
   rounded HALF-EVEN to whole yen before posting. The kernel's
   sum-to-zero invariant fires AFTER rounding.

   Reference: ADR-084 §5."
  (:require [kontor.payroll-jp.wage-types :as wt]
            [kontor.payroll-provider :as pp])
  (:import [java.math BigDecimal RoundingMode]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- to-whole-yen
  "Round to whole yen (HALF-EVEN). JPY has precision 0."
  ^BigDecimal [^BigDecimal x]
  (.setScale x 0 RoundingMode/HALF_EVEN))

(defn- sum-bd
  ^BigDecimal [bds]
  (reduce (fn [a v] (.add ^BigDecimal a ^BigDecimal v)) 0M bds))

(defn- account-for-tag!
  [accounts tag]
  (or (get accounts tag)
      (throw (ex-info (str "No account configured for tag " tag)
                      {:tag tag
                       :available-tags (set (keys accounts))}))))

(defn- narration-for
  "Human-readable Kanji narration for a posting kind. Falls back to
   the keyword name when no Kanji label is registered."
  [kind extras-map]
  (or (wt/kanji kind extras-map) (name kind)))

;; ============================================================================
;; Per-fact posting legs
;; ============================================================================

(defn- earnings-leg
  "DR wages-expense for the GROSS minus any bonus subtotal (sum of
   positive employee-side posting-generating components that map to
   the :jp-payroll-wages tag).

   Bonus rows route to a SEPARATE 賞与 expense via `bonus-leg` below."
  [{:keys [components] :as _fact}
   {:keys [accounts commodity extras-map]}]
  (let [wage-comps
        (->> components
             (remove :employer-side?)
             (filter (fn [c]
                       (and (pos? (compare ^BigDecimal (:amount c) 0M))
                            (= :jp-payroll-wages
                               (wt/account-tag (:kind c) extras-map))))))
        gross (to-whole-yen (sum-bd (map :amount wage-comps)))]
    (when (pos? (compare gross 0M))
      [{:kontor.posting/account (account-for-tag! accounts :jp-payroll-wages)
        :kontor.posting/amount gross
        :kontor.posting/commodity commodity
        :kontor.posting/narration "Wages and salaries (gross) / 給料手当"}])))

(defn- bonus-leg
  "DR bonus expense (賞与) for the bonus components. Per ADR-084 §5,
   J-GAAP convention treats bonus as a separate account from base
   salary so the P&L distinguishes the two."
  [{:keys [components]}
   {:keys [accounts commodity extras-map]}]
  (let [bonus-comps
        (->> components
             (remove :employer-side?)
             (filter (fn [c]
                       (and (pos? (compare ^BigDecimal (:amount c) 0M))
                            (= :jp-payroll-bonus
                               (wt/account-tag (:kind c) extras-map))))))
        bonus (to-whole-yen (sum-bd (map :amount bonus-comps)))]
    (when (pos? (compare bonus 0M))
      [{:kontor.posting/account (account-for-tag! accounts :jp-payroll-bonus)
        :kontor.posting/amount bonus
        :kontor.posting/commodity commodity
        :kontor.posting/narration "Bonus / 賞与"}])))

(defn- deduction-legs
  "For each negative employee-side deduction component, CR the
   matching 預り金 (Azukari-kin / holding) account."
  [{:keys [components]}
   {:keys [accounts commodity extras-map]}]
  (->> components
       (remove :employer-side?)
       (filter (fn [c] (neg? (compare ^BigDecimal (:amount c) 0M))))
       (mapv (fn [{:keys [kind amount]}]
               (let [tag (wt/account-tag kind extras-map)
                     _ (when-not tag
                         (throw (ex-info (str "Unknown deduction kind: " kind)
                                         {:kind kind})))
                     acct (account-for-tag! accounts tag)
                     amt (to-whole-yen amount)]
                 ;; amount is already negative; CR is negative in
                 ;; kontor's sign convention.
                 {:kontor.posting/account acct
                  :kontor.posting/amount amt
                  :kontor.posting/commodity commodity
                  :kontor.posting/narration (str "Payroll deduction: "
                                          (narration-for kind extras-map))})))))

(defn- employer-side-legs
  "For each employer-side component, DR the expense account AND CR
   the matching payable bucket. Each component produces TWO posting
   legs: one for the 法定福利費 expense, one for the 預り金
   liability."
  [{:keys [components]}
   {:keys [accounts commodity extras-map]}]
  (->> components
       (filter :employer-side?)
       (mapcat (fn [{:keys [kind amount]}]
                 (let [exp-tag (wt/account-tag kind extras-map)
                       pay-tag (wt/payable-tag kind extras-map)
                       _ (when-not exp-tag
                           (throw (ex-info (str "Unknown employer-side kind: " kind)
                                           {:kind kind})))
                       exp-acct (account-for-tag! accounts exp-tag)
                       pay-acct (when pay-tag (account-for-tag! accounts pay-tag))
                       amt (to-whole-yen amount)]
                   (cond-> [{:kontor.posting/account exp-acct
                             :kontor.posting/amount amt
                             :kontor.posting/commodity commodity
                             :kontor.posting/narration (str "Employer statutory benefit: "
                                                     (narration-for kind extras-map))}]
                     pay-acct
                     (conj {:kontor.posting/account pay-acct
                            :kontor.posting/amount (.negate ^BigDecimal amt)
                            :kontor.posting/commodity commodity
                            :kontor.posting/narration (str "Employer payable: "
                                                    (narration-for kind extras-map))})))))))

(defn- net-wages-leg
  "CR wages-payable for the net amount (gross + sum of deductions —
   already what the substrate carries as :net).

   When the per-leg sums (after rounding) don't exactly equal the
   :net carried by the fact, the net-wages leg absorbs the rounding
   delta. This keeps the per-transaction balance invariant intact."
  [{:keys [net components]}
   {:keys [accounts commodity extras-map]}]
  ;; Compute the rounded sum of all employee-side legs (positive +
  ;; negative), then set net-wages = -(rounded sum).
  (let [posting-comps (filterv #(wt/posts? (:kind %) extras-map) components)
        emp-rounded-sum
        (sum-bd (->> posting-comps
                     (remove :employer-side?)
                     (map :amount)
                     (map to-whole-yen)))
        net-amount (cond
                     ;; If we have a non-empty employee side, use the
                     ;; rounded sum so the legs balance.
                     (seq posting-comps) emp-rounded-sum
                     ;; Fallback: use the fact's :net as-given.
                     :else (to-whole-yen net))]
    (when (pos? (compare ^BigDecimal net-amount 0M))
      [{:kontor.posting/account (account-for-tag! accounts :jp-payroll-net-wages)
        :kontor.posting/amount (.negate ^BigDecimal net-amount)
        :kontor.posting/commodity commodity
        :kontor.posting/narration "Wages payable (net) / 未払金"}])))

(defn fact->postings
  "Translate one PayrollFact into a balanced set of posting maps.
   The per-fact set sums to zero (the substrate's invariant)."
  [fact opts]
  (vec
   (concat
    (earnings-leg     fact opts)
    (bonus-leg        fact opts)
    (deduction-legs   fact opts)
    (employer-side-legs fact opts)
    (net-wages-leg    fact opts))))

;; ============================================================================
;; JpPayrollPostingBuilder — the PayrollPostingBuilder protocol impl
;; ============================================================================

(defrecord JpPayrollPostingBuilder [opts]
  pp/PayrollPostingBuilder
  (build-postings [_ payroll-facts
                   {:keys [accounts ledger]}]
    (let [commodity (or (:commodity opts)
                        (throw (ex-info ":commodity required in JpPayrollPostingBuilder opts" {})))
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
