(ns kontor.payroll-us-adp.compute
  "ADP General Ledger Interface (GLI) compute provider — Stage R C3
   (ADR-077).

   This namespace implements `AdpGliComputeProvider`, the
   `PayrollComputeProvider` impl that *parses* an ADP-emitted GLI CSV
   file into a vector of `PayrollFacts` maps. Per ADR-077 (and the
   broader ADR-005 / ADR-071 / ADR-075 posture) the compute side does
   NOT re-implement US gross-to-net math — FICA, FUTA, SUTA, multi-
   state withholding, 401(k) caps, garnishment priority, supplemental-
   wage withholding all live in ADP's payroll engine. kontor consumes
   the engine's CSV result.

   ## The file format

   ADP General Ledger Interface CSV, 10 columns, no header row, every
   field double-quoted, debits positive / credits negative, comma-
   delimited, ASCII (UTF-8-safe). Documented in Microsoft's 'Payroll
   Connect for Dynamics GP' reference page; the format is identical
   across ADP RUN, Workforce Now, and InfoLink. See
   doc/research/83-us-adp-gli-research-before.md §2 for the full spec.

   The 10 columns:

     0 :client-code           — ADP company code
     1 :gl-account            — customer's GL account number
     2 :journal-source-code   — typically 'PR'
     3 :date                  — MMDDYYYY pay-period end / check date
     4 :amount-signed         — BigDecimal, negative = credit
     5 :reference-1           — customer-defined (often employee ID)
     6 :description           — wage-type label ('GROSS', 'EE 401K', …)
     7 :reference-2           — customer-defined (often cost-center)
     8 :reference-3           — customer-defined (often state)
     9 :record-code           — internal ADP marker, usually '02'

   ## The balancing-row trap (note 83 §2.3)

   ADP's GLI emits a final row with an empty `:gl-account` to make the
   file sum to zero. This row is NOT a real posting — it's a file-
   format artifact ADP adds because the wage-expense + employer-tax
   debits don't naturally balance against the employee-side credits in
   a single pay-period. The parser MUST:

     1. Detect rows with empty / blank `:gl-account` AND skip them as
        postings.
     2. Verify the remaining rows + the balancing-row sum to zero
        (the `:sum-to-zero` invariant). If not, the file is corrupt
        or truncated; throw before transacting.

   ## Parser shape

   Config-driven so the same parser can handle Gusto / Paychex / OnPay
   / Rippling variants with a different column-map (see note 83 §3).
   For C3 only `:adp` is wired into the public surface; the others are
   reserved for future modules.

   ## What we produce

   The parser emits a vector of *raw GLI rows* (kept simple to make
   round-tripping testable), then `payroll-facts-from-rows` groups them
   by employee and assembles `PayrollFacts` per the
   `kontor.payroll-provider` contract."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kontor.payroll-provider :as pp])
  (:import [java.io BufferedReader Reader StringReader]
           [java.math BigDecimal]
           [java.text SimpleDateFormat]
           [java.util Date]))

;; ============================================================================
;; Loading the wage-type map
;; ============================================================================

(defn load-wage-type-map
  "Load + compile the consumer-supplied wage-type map. The on-disk
   shape uses *strings* for the regex (so the file remains pure EDN);
   we compile them to `java.util.regex.Pattern` at load time."
  [edn-shape]
  (update edn-shape :description-rules
          (fn [rules]
            (mapv (fn [r]
                    (cond-> r
                      (string? (:match r))
                      (assoc :match (re-pattern (:match r)))))
                  rules))))

(defn load-reference-map
  "Load the reference wage-type map shipped under
   `resources/kontor/payroll_us_adp/wage_type_map_reference.edn`.
   Convenience — production consumers ship their own."
  []
  (-> "kontor/payroll_us_adp/wage_type_map_reference.edn"
      io/resource slurp edn/read-string load-wage-type-map))

;; ============================================================================
;; CSV row parsing
;; ============================================================================
;; ADP GLI is comma-delimited with every field double-quoted; the field
;; vocabulary doesn't contain commas inside fields so a tolerant split-
;; on-comma-then-unquote works. Reasonable since the format is ASCII +
;; quoted; we don't depend on data.csv.

(defn- unquote-field
  "Strip leading + trailing double-quotes from a CSV field. Tolerates
   no-quote variants."
  ^String [^String f]
  (let [f (str/trim f)]
    (if (and (> (count f) 1)
             (= \" (.charAt f 0))
             (= \" (.charAt f (dec (count f)))))
      (subs f 1 (dec (count f)))
      f)))

(defn- split-line
  "Split a single CSV line into a vector of unquoted fields. Tolerates
   `\\r\\n` and `\\n` line terminators."
  [^String line]
  (mapv unquote-field (str/split line #",")))

(defn parse-amount
  "Parse a GLI signed-amount field into a BigDecimal. Negative amounts
   are credits. Blank → 0."
  ^BigDecimal [^String s]
  (let [s (str/trim s)]
    (if (str/blank? s)
      0M
      (BigDecimal. s))))

(defn parse-date
  "Parse a GLI date in `MMDDYYYY` format. Returns a `java.util.Date`."
  ^Date [^String s]
  (let [s (str/trim s)]
    (when (str/blank? s)
      (throw (ex-info "Empty date field" {})))
    (.parse (SimpleDateFormat. "MMddyyyy") s)))

(defn- row->map
  "Turn one parsed-fields vector into a normalized row-map keyed by the
   column-map roles. Skips columns the column-map doesn't declare.

   The `:format` and `:credit-sign` keys on each column-spec are
   read by other parser paths (cross-vendor variants); we don't use
   them here for the ADP-only flow but they're carried in the EDN."
  [fields columns]
  (reduce (fn [acc {:keys [idx role]}]
            (let [raw (nth fields idx "")]
              (assoc acc role
                     (case role
                       :amount-signed (parse-amount raw)
                       :date          (parse-date raw)
                       (str/trim raw)))))
          {} columns))

;; ============================================================================
;; File parsing (public)
;; ============================================================================

(defn read-lines
  "Read raw lines from a `Reader` or string. Drops trailing blank lines."
  [src]
  (let [reader (cond
                 (instance? Reader src) src
                 (string? src)          (StringReader. ^String src)
                 :else                  (io/reader src))]
    (with-open [r (BufferedReader. ^Reader reader)]
      (->> (line-seq r)
           (remove str/blank?)
           vec))))

(defn- balancing-row?
  "A row whose `:gl-account` is blank — ADP's file-balance artifact.
   See note 83 §2.3."
  [row]
  (str/blank? (str (:gl-account row))))

(defn parse-gli
  "Parse an ADP GLI CSV source into a structured result map.

   `src`: a path, a `java.io.Reader`, or a raw CSV string.
   `wage-type-map`: the loaded map (see `load-wage-type-map`).

   Returns:

     {:rows           [{:gl-account ... :amount-signed ... :description ...
                        :state ... :cost-center ... ...}]   ; real postings
      :balancing-rows [...]                                  ; the artifact
      :sum-of-amounts BigDecimal                             ; should be 0
      :file-balanced? boolean}

   Throws if `:balance-check :sum-to-zero` is set and the sum is non-
   zero. Per note 83 §2.3 + §9.4 gotcha #1: ADP files MUST sum to
   zero; a non-zero sum means corruption / truncation."
  [src wage-type-map]
  (let [{:keys [csv-format reference-mappings]} wage-type-map
        {:keys [columns has-header balance-check]} csv-format
        lines (read-lines src)
        body  (if has-header (rest lines) lines)
        all-rows (mapv #(row->map (split-line %) columns) body)
        ;; Apply the reference-mappings: re-key reference-N → cost-center / state.
        all-rows (mapv (fn [row]
                         (reduce (fn [r {:keys [column role]}]
                                   (let [v (get r column)]
                                     (if (str/blank? v) r (assoc r role v))))
                                 row reference-mappings))
                       all-rows)
        {real true balancing false} (group-by (complement balancing-row?)
                                              all-rows)
        sum (reduce (fn [^BigDecimal a r]
                      (.add a ^BigDecimal (or (:amount-signed r) 0M)))
                    0M all-rows)
        balanced? (zero? (.signum sum))]
    (when (and (= balance-check :sum-to-zero) (not balanced?))
      (throw (ex-info "ADP GLI file does not sum to zero — corrupt or truncated."
                      {:type :adp-gli/unbalanced
                       :sum sum
                       :row-count (count all-rows)})))
    {:rows real
     :balancing-rows balancing
     :sum-of-amounts sum
     :file-balanced? balanced?}))

;; ============================================================================
;; Wage-type matching
;; ============================================================================

(defn match-rule
  "Match a row's `:description` against the description-rules. Returns
   the first matching rule augmented with `:capture` (regex match
   groups), or `nil` if nothing matches (the catch-all `.*` rule
   should ensure something always matches)."
  [rules description]
  (when description
    (some (fn [rule]
            (let [m (re-matcher ^java.util.regex.Pattern (:match rule)
                                ^String description)]
              (when (.matches m)
                (assoc rule :capture
                       (vec (for [i (range (.groupCount m))]
                              (.group m (inc i))))))))
          rules)))

(defn classify-row
  "Annotate a parsed GLI row with the matching wage-type-map rule and
   the derived `:state` (from regex capture or reference-3, in that
   order). Returns the row enriched with `:rule` + final `:state`."
  [row rules]
  (let [rule (match-rule rules (:description row))
        state-from-rule (when-let [g (:state-from-group rule)]
                          (let [cap (nth (:capture rule) (dec g) nil)]
                            (when-not (str/blank? cap) cap)))
        state (or state-from-rule (:state row))]
    (cond-> (assoc row :rule rule)
      state (assoc :state state))))

;; ============================================================================
;; Component classification: which component-kind does this row belong to?
;; ============================================================================

(def role->component-kind
  "Map of wage-type-map :role → PayrollProvider component :kind. Used
   to assemble PayrollFacts per the substrate contract."
  {:wage-expense       :base-wage
   :pto-paid           :base-wage
   :er-fica-ss         :employer-si
   :er-fica-medicare   :employer-si
   :er-futa            :employer-si
   :er-suta            :employer-si
   :er-health          :employer-benefit
   :er-401k-match      :employer-pension
   :er-workers-comp    :employer-benefit
   :ee-fed-withheld    :withholding-tax
   :ee-state-withheld  :withholding-tax
   :ee-local-withheld  :withholding-tax
   :ee-fica-ss         :employee-si
   :ee-fica-medicare   :employee-si
   :ee-401k-deferral   :employee-pension
   :ee-roth-deferral   :employee-pension
   :ee-section125      :voluntary-deduction
   :ee-hsa             :voluntary-deduction
   :ee-fsa             :voluntary-deduction
   :ee-dep-care-fsa    :voluntary-deduction
   :garnishment        :garnishment
   :child-support      :garnishment
   :net-pay-liability  :net-pay
   :unmapped           :unmapped})

(def employer-side-roles
  "Roles whose amount lives on the employer side — they don't reduce
   employee gross→net but produce their own posting legs."
  #{:er-fica-ss :er-fica-medicare :er-futa :er-suta
    :er-health :er-401k-match :er-workers-comp})

;; ============================================================================
;; PayrollFacts assembly
;; ============================================================================

(defn- abs-bd ^BigDecimal [^BigDecimal x]
  (.abs x))

(defn- ->fact
  "Assemble one PayrollFacts map from a vector of *already-classified*
   GLI rows (each carrying `:rule`) that share the same employee."
  [employee-id rows]
  (let [classified rows
        components
        (mapv (fn [r]
                (let [rule (:rule r)
                      role (:role rule)
                      raw  (:amount-signed r)
                      ;; Sign convention for PayrollFacts (per kontor.hr.payroll):
                      ;;   + amount = paid TO employee / earned;
                      ;;   - amount = deducted FROM employee / withheld.
                      ;; ADP signs amounts from the GL perspective:
                      ;;   wages-expense Dr = +, ee-withholding Cr = -.
                      ;; For employee-side rows we want to flip:
                      ;;   ee-fed-withheld Cr (-) → :amount -<abs>
                      ;;   gross Dr (+)        → :amount +<abs>
                      ;;   net-pay Cr (-)      → represented as gross+sum(neg);
                      ;;     drop the net-pay row from components (it's
                      ;;     the residual, not a separate component).
                      ;; For employer-side rows (er-fica-ss etc) the GL has Dr +;
                      ;; we keep the magnitude but mark :employer-side?.
                      amount (cond
                               (= role :net-pay-liability) nil ; drop from components
                               (= role :unmapped)          raw
                               (employer-side-roles role)  (abs-bd raw)
                               ;; employee-side: + for wage-expense (earned),
                               ;; - for all withholdings/deductions (already Cr).
                               (= role :wage-expense)      (abs-bd raw)
                               (= role :pto-paid)          (abs-bd raw)
                               :else
                               (.negate (abs-bd raw)))]
                  (when amount
                    {:kind (role->component-kind role)
                     :role role
                     :account-key (:account-key rule)
                     :ledgers (:ledgers rule)
                     :amount amount
                     :employer-side? (boolean (employer-side-roles role))
                     :w2-box (:w2-box rule)
                     :w2-code (:w2-code rule)
                     :reduces-box-1? (boolean (:reduces-box-1? rule))
                     :reduces-box-3? (boolean (:reduces-box-3? rule))
                     :reduces-box-5? (boolean (:reduces-box-5? rule))
                     :section-125? (boolean (:section-125? rule))
                     :irc-404a6? (boolean (:irc-404a6? rule))
                     :state (:state r)
                     :cost-center (:cost-center r)
                     :gl-account (:gl-account r)
                     :description (:description r)})))
              classified)
        components (filterv some? components)
        gross (->> components
                   (filter #(and (not (:employer-side? %))
                                 (pos? (.signum ^BigDecimal (:amount %)))))
                   (map :amount)
                   (reduce (fn [^BigDecimal a ^BigDecimal v] (.add a v)) 0M))
        deductions (->> components
                        (filter #(and (not (:employer-side? %))
                                      (neg? (.signum ^BigDecimal (:amount %)))))
                        (map :amount)
                        (reduce (fn [^BigDecimal a ^BigDecimal v] (.add a v)) 0M))
        net (.add ^BigDecimal gross ^BigDecimal deductions)]
    {:employment employee-id   ; consumer maps external-id → :employment eid
     :gross gross
     :net net
     :components components
     :jurisdiction-specific-codes
     {:source :adp-gli
      :employee-ref-1 employee-id}}))

(defn payroll-facts-from-rows
  "Given parsed + classified GLI rows, group by `:reference-1`
   (employee identifier) and assemble one `PayrollFacts` per employee.

   Returns a vector of facts; the order follows the first-row order
   of each employee group."
  [classified-rows]
  (let [order (vec (distinct (keep :reference-1 classified-rows)))
        groups (group-by :reference-1 classified-rows)]
    (mapv (fn [employee-id] (->fact employee-id (groups employee-id)))
          order)))

(defn parse-and-classify
  "End-to-end CSV → classified-rows. Convenience wrapper that handles
   the rule-attachment step (parse-gli leaves rows raw; classify-row
   needs the rules)."
  [src wage-type-map]
  (let [{:keys [rows balancing-rows]} (parse-gli src wage-type-map)
        rules (:description-rules wage-type-map)
        classified (mapv (fn [row] (classify-row row rules)) rows)]
    {:classified classified
     :balancing-rows balancing-rows}))

;; ============================================================================
;; AdpGliComputeProvider record
;; ============================================================================

(defrecord AdpGliComputeProvider [opts]
  pp/PayrollComputeProvider
  (provider-id [_] :adp-gli)
  (compute-payroll [_ {:keys [variable-inputs employment-eids]}]
    ;; The compute-provider's job here is to PARSE a CSV the consumer
    ;; supplies via `:variable-inputs` and produce PayrollFacts. We do
    ;; NOT re-implement gross-to-net; ADP already did that.
    ;;
    ;; The consumer wires the CSV to the run via
    ;;   :variable-inputs {:csv-source <path-or-reader-or-string>
    ;;                     :employee->employment {"E101" <eid> ...}}
    ;;
    ;; (`:adp-gli-csv-source` still accepted as a legacy alias for
    ;;  back-compat per note 86 P2-86-4; canonical key is `:csv-source`
    ;;  to match the rest of the kontor adapter family.)
    ;;
    ;; The provider returns ONE PayrollFacts per employee in the CSV
    ;; (keyed back to :employment eid via :employee->employment, which
    ;; the consumer supplies because ADP's reference-1 vocabulary is
    ;; opaque to kontor).
    (let [{:keys [csv-source adp-gli-csv-source wage-type-map
                  employee->employment]}
          (or variable-inputs {})
          csv-source (or csv-source adp-gli-csv-source)]
      (when-not csv-source
        (throw (ex-info "AdpGliComputeProvider needs :variable-inputs {:csv-source ...}"
                        {})))
      (let [wtm (or wage-type-map (load-reference-map))
            {:keys [classified]} (parse-and-classify csv-source wtm)
            raw-facts (payroll-facts-from-rows classified)
            ;; Rewrite :employment from the GLI's employee-ref-1 string
            ;; to the consumer-supplied eid.
            facts (mapv (fn [fact]
                          (let [ext-id (:employment fact)
                                eid (get employee->employment ext-id)]
                            (when (and (seq employment-eids)
                                       eid
                                       (not (contains? (set employment-eids) eid)))
                              (throw (ex-info "AdpGli compute: GLI references an employment outside the run's :employment-eids"
                                              {:external-id ext-id :eid eid})))
                            (assoc fact :employment (or eid ext-id))))
                        raw-facts)]
        facts))))
