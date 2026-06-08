(ns kontor.payroll-jp.gensen
  "Year-end 源泉徴収票 (Gensen Choshu Hyo / Withholding Tax Statement)
   aggregator. Reduces a year of `PayrollFacts` for one
   (person × employer × tax-year) into a structured map ready for
   the consumer's PDF / paper rendering — kontor does NOT bundle a
   PDF template (NTA layout changes year-to-year; see ADR-084 §7.4).

   Reference: ADR-084 §7.

   ## What the Gensen reports (NTA form 給与所得の源泉徴収票)

   The Gensen Choshu Hyo is the annual statement an employer issues
   to each employee + files (in summary aggregate) with the税務署
   (Zeimusho / tax office). It carries:

     - 支払金額 (Shiharai Kingaku) — total payment amount (gross
       wages + bonus, taxable portion only)
     - 給与所得控除後の金額 (Kyuyo Shotoku Koujogo no Kingaku) —
       gross less the standard employment-income deduction (per
       所得税法 §28; engine-computed)
     - 所得控除の額の合計額 (Shotoku Koujo no Gaku no Goukei-gaku)
       — total deductions: SI paid + spouse + dependents + basic
       deduction + other (engine-computed)
     - 源泉徴収税額 (Gensen Choshu Zeigaku) — total income tax
       withheld (post-年末調整 / Nenmatsu Chosei reconciliation)
     - 社会保険料等の金額 (Shakai Hokenryo Tou no Kingaku) — SI
       contributions paid by the employee
     - Spouse / dependent counts + their respective deductions
     - 摘要 (Tekiyo / remarks) — free-text notes

   ## Scope (ADR-084 §7 — what we produce vs what we don't)

   - DOES aggregate per-employee YTD totals from PayrollFacts.
   - DOES emit a structured `:gensen/*` map for downstream rendering.
   - DOES produce an `:kontor.audit-doc/category :payroll-filing` +
     `:kontor.audit-doc/language :ja` row recording the emission.
   - DOES NOT generate the rendered PDF / paper form (NTA form
     layout shifts year-to-year; consumer renders via their preferred
     tool — freee / MF / Yayoi all generate the PDF themselves).
   - DOES NOT submit to the tax office (engine does this; the
     summary table 給与所得の源泉徴収票等の法定調書合計表 is the
     cover sheet — ADR-084 §7 documents this is deferred to a
     future companion).

   ## 年末調整 (Nenmatsu Chosei) reconciliation

   Nenmatsu Chosei is the year-end reconciliation of monthly
   withholding vs the actual annual income-tax liability (after the
   employee declares spouse / dependent / insurance-premium / housing-
   loan deductions). The engine performs this and emits the adjusted
   `:income-tax-withheld` for the December pay-period (typically) with
   the full-year true-up baked in. kontor consumes the engine's
   output; we do NOT re-implement the bracket arithmetic.

   The aggregator reads the engine's per-period `:income-tax-withheld`
   sum directly — that sum already reflects Nenmatsu Chosei when the
   engine ran year-end. Consumers who want pre-Chosei + Chosei-delta
   visibility pass `:facts` filtered to pre-December + post-December
   separately."
  (:require [datahike.api :as d]
            [kontor.payroll-jp.wage-types :as wt])
  (:import [java.math BigDecimal RoundingMode]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- to-whole-yen
  ^BigDecimal [^BigDecimal x]
  (.setScale x 0 RoundingMode/HALF_EVEN))

(defn- abs-bd
  ^BigDecimal [^BigDecimal x]
  (.abs x))

(defn- sum-bd
  ^BigDecimal [bds]
  (reduce (fn [a v] (.add ^BigDecimal a ^BigDecimal v)) 0M bds))

;; ============================================================================
;; Per-fact component aggregation
;; ============================================================================

(defn- fact-components-by-kind
  "Return a map kind → BigDecimal sum (absolute value) over components.
   Carry-only kinds (:gensen-employment-income-deduction, etc.) come
   in via :jurisdiction-specific-codes."
  [{:keys [components jurisdiction-specific-codes]}]
  (let [from-comps
        (reduce (fn [m {:keys [kind amount]}]
                  (update m kind
                          (fn [v]
                            (.add ^BigDecimal (or v 0M)
                                  ^BigDecimal amount))))
                {} components)]
    (merge-with
     (fn [a b]
       (if (and (instance? BigDecimal a) (instance? BigDecimal b))
         (.add ^BigDecimal a ^BigDecimal b)
         (or a b)))
     from-comps
     (or jurisdiction-specific-codes {}))))

;; ============================================================================
;; Gensen-box aggregation
;; ============================================================================

(defn- accumulate-gensen-boxes
  "Reduce a vector of facts into a {gensen-box → BigDecimal} map.

   Deduction components carry a negative amount in PayrollFacts — for
   Gensen reporting we want the ABSOLUTE value (the statement reports
   'amount withheld', not a signed posting amount).

   Carry-only components flow through :jurisdiction-specific-codes
   as a kind → BigDecimal map and contribute their values to the
   corresponding box."
  [facts extras-map]
  (->> facts
       (mapcat (fn [fact]
                 (let [by-kind (fact-components-by-kind fact)]
                   (->> by-kind
                        (keep (fn [[kind amount]]
                                (when-let [box (wt/gensen-box kind extras-map)]
                                  [box (if (instance? BigDecimal amount)
                                         (abs-bd amount)
                                         0M)])))))))
       (reduce (fn [m [box amount]]
                 (update m box
                         (fn [v] (.add ^BigDecimal (or v 0M)
                                       ^BigDecimal amount))))
               {})))

;; ============================================================================
;; payroll-facts->gensen-statement
;; ============================================================================

(defn payroll-facts->gensen-statement
  "Aggregate a year of `PayrollFacts` for ONE
   (person × employer × tax-year) into a structured Gensen statement.

   Inputs:
     :facts         vector of `PayrollFacts` (already filtered to one
                    person × employer × tax-year)
     :person        {:given-name :family-name :address :birth-date
                     :my-number-present? — a BOOLEAN indicating whether
                     the consumer's privileged store has the My Number
                     attested; kontor itself does NOT carry the value
                     per ADR-084 §1 (My Number is PII; consumers store
                     it behind an `:kontor.audit-doc/category :hr-personnel`
                     + `:kontor.audit-doc/privilege :pii-sensitive` audit-doc)}
     :employer      {:name :corporate-number :address :representative}
                    — corporate-number is the 法人番号 (13-digit NTA
                    Corporate Number per kontor.l10n-jp.identifiers)
     :tax-year      integer (e.g. 2026; this is the calendar year the
                    income was earned — Japan tax year = calendar year)
     :extras-map    consumer-extension catalog
     :remarks       optional 摘要 free-text

   Returns:
     {:gensen/tax-year       <integer>
      :gensen/employee       {:given-name :family-name :address
                              :birth-date :my-number-present?}
      :gensen/employer       {:name :corporate-number :address …}
      :gensen/payment-amount               <BigDecimal — 支払金額>
      :gensen/withholding-amount           <BigDecimal — 源泉徴収税額>
      :gensen/social-insurance-paid        <BigDecimal — 社会保険料等>
      :gensen/employment-income-deduction  <BigDecimal — 給与所得控除後>
      :gensen/taxable-income               <BigDecimal — 所得控除の合計>
      :gensen/spouse-deduction             <BigDecimal — 配偶者控除>
      :gensen/dependent-deduction          <BigDecimal — 扶養控除>
      :gensen/remarks                      <string>}

   All amounts are rounded to whole yen (HALF-EVEN)."
  [{:keys [facts person employer tax-year extras-map remarks]}]
  (when-not facts    (throw (ex-info ":facts required" {})))
  (when-not person   (throw (ex-info ":person required" {})))
  (when-not employer (throw (ex-info ":employer required" {})))
  (when-not tax-year (throw (ex-info ":tax-year required" {})))
  (let [boxes (accumulate-gensen-boxes facts extras-map)
        box-yen (reduce-kv (fn [m k v]
                             (assoc m k (to-whole-yen v)))
                           {} boxes)]
    (cond-> {:gensen/tax-year tax-year
             :gensen/employee (select-keys person
                                           [:given-name :family-name
                                            :address :birth-date
                                            :my-number-present?])
             :gensen/employer employer
             :gensen/payment-amount
             (or (:gensen/payment-amount box-yen) 0M)
             :gensen/withholding-amount
             (or (:gensen/withholding-amount box-yen) 0M)
             :gensen/social-insurance-paid
             (or (:gensen/social-insurance-paid box-yen) 0M)}
      (:gensen/employment-income-deduction box-yen)
      (assoc :gensen/employment-income-deduction
             (:gensen/employment-income-deduction box-yen))
      (:gensen/taxable-income box-yen)
      (assoc :gensen/taxable-income (:gensen/taxable-income box-yen))
      (:gensen/spouse-deduction box-yen)
      (assoc :gensen/spouse-deduction (:gensen/spouse-deduction box-yen))
      (:gensen/dependent-deduction box-yen)
      (assoc :gensen/dependent-deduction (:gensen/dependent-deduction box-yen))
      remarks (assoc :gensen/remarks remarks))))

;; ============================================================================
;; group-facts-for-statements — one Gensen per (person × employer × year)
;; ============================================================================

(defn group-facts-for-statements
  "Group facts by (person × employer-corporate-number × tax-year).
   Returns a map keyed by [person-resolved corp-number tax-year]
   of the facts vector.

   The employer is reduced to its corporate-number for grouping so
   per-fact employer-map identities don't fragment the bucket. Most
   employees have a single (person, employer, year) tuple; an
   employee who transferred between group entities mid-year gets
   multiple Gensens (one per employer corporate-number). This
   grouping is consumer-driven: the caller resolves person +
   employer from the `:employment` ref and threads them in."
  [facts {:keys [employment->person+employer tax-year]}]
  (group-by
   (fn [{:keys [employment]}]
     (let [{:keys [person employer]} (employment->person+employer employment)
           corp (cond
                  (nil? employer) nil
                  (string? employer) employer
                  (map? employer) (:corporate-number employer)
                  :else employer)]
       [person corp tax-year]))
   facts))

;; ============================================================================
;; build-gensen-submission — full year's statements
;; ============================================================================

(defn build-gensen-submission
  "Aggregate a year of `PayrollFacts` into one statement per
   (person × employer × tax-year) for an entire employer's workforce.

   Required opts:
     :facts                         full year of PayrollFacts
     :tax-year                      integer (e.g. 2026)
     :employer                      {:name :corporate-number :address …}
     :employment->person+employer   function (employment-eid →
                                     {:person <person map>
                                      :employer <employer map override
                                                 or nil>}).
                                     The caller resolves person + employer
                                     from datahike.

   Optional:
     :extras-map                    consumer-extension catalog
     :default-remarks               fallback 摘要 if no per-fact remark

   Returns: vector of `:gensen/*` statement maps (one per
   person × employer × tax-year)."
  [{:keys [facts tax-year employer employment->person+employer
           extras-map default-remarks]}]
  (when-not facts (throw (ex-info ":facts required" {})))
  (when-not tax-year (throw (ex-info ":tax-year required" {})))
  (when-not employer (throw (ex-info ":employer required" {})))
  (when-not employment->person+employer
    (throw (ex-info ":employment->person+employer fn required" {})))
  (let [grouped (group-facts-for-statements
                 facts {:employment->person+employer
                        employment->person+employer
                        :tax-year tax-year})]
    (mapv (fn [[[person-eid employer-corp-number _yr] emp-facts]]
            (let [{:keys [person employer-override]}
                  (let [r (employment->person+employer (-> emp-facts first :employment))]
                    {:person (:person r)
                     :employer-override (:employer r)})
                  resolved-employer (or employer-override employer)]
              (when (and employer-corp-number resolved-employer
                         (not= employer-corp-number
                               (:corporate-number resolved-employer)))
                (throw (ex-info "Mismatched employer corporate-number in fact grouping"
                                {:group-key employer-corp-number
                                 :resolved (:corporate-number resolved-employer)
                                 :person person-eid})))
              (payroll-facts->gensen-statement
               {:facts emp-facts
                :person person
                :employer resolved-employer
                :tax-year tax-year
                :extras-map extras-map
                :remarks default-remarks})))
          grouped)))
