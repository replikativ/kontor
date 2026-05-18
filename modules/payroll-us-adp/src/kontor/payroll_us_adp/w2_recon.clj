(ns kontor.payroll-us-adp.w2-recon
  "W-2 reconciliation report — year-to-date per-employee Box 1/3/5/12
   reconciliation (ADR-077, note 83 §7.3).

   ## Scope (note 83 §1 bullet 9)

   kontor does NOT generate W-2 forms — ADP files W-2s with SSA
   directly. What kontor produces is a *data-prep* report: per-employee
   YTD totals broken down by W-2 box, so the customer can cross-check
   against ADP's generated W-2 and resolve reconciliation deltas.

   ## Box mapping (per IRS Pub 15 + Year-End Reconciliation Worksheet)

   Computed from PayrollFacts components + the wage-type-map's box
   flags:

     - Box 1  (Federal wages)        = Σ wage-expense components
                                       − Σ deductions where :reduces-box-1?
     - Box 3  (Social Security wages) = Σ wage-expense - Σ :reduces-box-3?
                                       (capped at the annual SS wage base —
                                       cap is consumer-supplied, default
                                       2024 = 168_600)
     - Box 4  (SS tax withheld)      = Σ :ee-fica-ss absolute amounts
     - Box 5  (Medicare wages)       = Σ wage-expense - Σ :reduces-box-5?
                                       (no cap)
     - Box 6  (Medicare tax withheld) = Σ :ee-fica-medicare absolute
                                       amounts
     - Box 12 codes                  = grouped sum by :w2-code
                                       (D = 401(k) pre-tax,
                                        AA = Roth 401(k),
                                        DD = employer health,
                                        W = HSA, …)
     - Box 16 (State wages) by state = state-allocated Box 1
     - Box 17 (State tax)    by state = Σ :ee-state-withheld per state

   ## Operating shape

   This is a PURE function from a vector of PayrollFacts (typically
   accumulated across multiple pay-periods for the year) → a per-
   employee box-keyed map. Consumers run it at year-end, compare
   against ADP-generated W-2s, and surface deltas as audit-doc
   anomalies for resolution.

   See doc/research/83-us-adp-gli-research-before.md §7.3 for the box
   mapping rules and rationale."
  (:import [java.math BigDecimal]))

(defn- abs-bd ^BigDecimal [^BigDecimal x] (.abs x))

(defn- add ^BigDecimal [^BigDecimal a ^BigDecimal b] (.add a b))

(defn- sum-amounts
  ^BigDecimal [comps]
  (reduce add 0M (map (comp abs-bd :amount) comps)))

(def ^BigDecimal default-ss-wage-base-2024
  "2024 SS wage base — 168,600 USD. Consumer overrides per year."
  168600M)

(defn- cap-at [^BigDecimal x ^BigDecimal cap]
  (if (and cap (pos? (compare x cap))) cap x))

(defn- wage-expense-components [components]
  (filter (fn [c] (#{:wage-expense :pto-paid} (:role c))) components))

(defn- box-12-grouped
  "Group :w2-box \"12\" components by :w2-code and sum (absolute value).
   Returns {code-string → BigDecimal}."
  [components]
  (->> components
       (filter (fn [c] (and (= "12" (:w2-box c)) (:w2-code c))))
       (group-by :w2-code)
       (reduce-kv (fn [acc code cs]
                    (assoc acc code (sum-amounts cs)))
                  {})))

(defn- by-state
  "Group components by `:state` (skipping those with nil state) and
   apply `summarize-fn` to each per-state component vector. Returns
   {state-string → result-of-summarize-fn}."
  [summarize-fn components]
  (->> components
       (filter :state)
       (group-by :state)
       (reduce-kv (fn [acc s cs] (assoc acc s (summarize-fn cs)))
                  {})))

(defn employee-w2-from-facts
  "Compute one employee's W-2-shaped totals from their accumulated
   PayrollFacts (typically one fact per pay-period across a year, all
   keyed by the same `:employment`).

   `opts` may carry `:ss-wage-base` (a BigDecimal; default 2024's
   168_600); leave nil to disable the cap (useful for tests)."
  [facts {:keys [ss-wage-base year]
          :or {ss-wage-base default-ss-wage-base-2024}}]
  (let [all-components (mapcat :components facts)
        wage-comps     (wage-expense-components all-components)
        wages-gross    (sum-amounts wage-comps)
        ;; Box 1 reductions: section-125 + 401(k) traditional + HSA + dep-care FSA.
        box1-reducers  (filter :reduces-box-1? all-components)
        box1-reduction (sum-amounts box1-reducers)
        box-1          (.subtract ^BigDecimal wages-gross ^BigDecimal box1-reduction)
        ;; Box 3/5: section-125 + HSA reduce; 401(k) pre-tax does NOT.
        box35-reducers (filter (some-fn :reduces-box-3? :reduces-box-5?) all-components)
        box35-reduction (sum-amounts box35-reducers)
        box-3-pre-cap  (.subtract ^BigDecimal wages-gross ^BigDecimal box35-reduction)
        box-3          (cap-at box-3-pre-cap ss-wage-base)
        box-5          box-3-pre-cap            ; no cap on Medicare wages
        ee-fica-ss     (filter (comp #{:ee-fica-ss} :role) all-components)
        ee-fica-medi   (filter (comp #{:ee-fica-medicare} :role) all-components)
        ee-fed         (filter (comp #{:ee-fed-withheld} :role) all-components)
        ee-state       (filter (comp #{:ee-state-withheld} :role) all-components)
        ee-local       (filter (comp #{:ee-local-withheld} :role) all-components)
        employment     (some :employment facts)]
    {:employment employment
     :year year
     :w2-box-1  box-1
     :w2-box-2  (sum-amounts ee-fed)
     :w2-box-3  box-3
     :w2-box-4  (sum-amounts ee-fica-ss)
     :w2-box-5  box-5
     :w2-box-6  (sum-amounts ee-fica-medi)
     :w2-box-12 (box-12-grouped all-components)
     :w2-box-16 (by-state (fn [_]
                            ;; Per-state Box 16 = state's share of Box 1.
                            ;; In the simple single-state case (state is
                            ;; uniform across wage rows) this == box-1.
                            ;; In hybrid scenarios the consumer overlays
                            ;; per-employee per-state percentages.
                            box-1)
                          wage-comps)
     :w2-box-17 (by-state sum-amounts ee-state)
     :w2-box-19 (by-state sum-amounts ee-local)
     :wages-gross wages-gross}))

(defn ytd-by-employee
  "Group a vector of PayrollFacts by `:employment` and produce one
   W-2-shaped report per employee. Returns a vector of maps."
  [facts opts]
  (let [groups (group-by :employment facts)]
    (mapv (fn [[employment per-emp-facts]]
            (employee-w2-from-facts per-emp-facts (assoc opts :employment employment)))
          (seq groups))))
