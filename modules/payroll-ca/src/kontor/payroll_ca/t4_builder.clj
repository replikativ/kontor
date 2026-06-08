(ns kontor.payroll-ca.t4-builder
  "Year-end T4 slip aggregator. Reduces a year of `PayrollFacts` for
   one (person × RP × tax-year × province-of-employment) into the
   shape `kontor.l10n-ca.xml.t4/slip->element` accepts, then composes
   into a full T619 + T4 + T4Summary submission via
   `kontor.l10n-ca.xml.t4/submission`.

   Reference:.

   ## Box coverage

   | T4 box | Component-kinds aggregated |
   |--------|----------------------------|
   | 14     | :base-wage :bonus :overtime :commission :vacation-pay-paid-out :statutory-holiday-pay :retroactive-pay :severance :retiring-allowance + :taxable-benefit-* |
   | 16     | :employee-cpp                                  |
   | 16A    | :employee-cpp2                                 |
   | 17     | :employee-qpp (QC passthrough)                 |
   | 17A    | :employee-qpp2 (QC passthrough)                |
   | 18     | :employee-ei                                   |
   | 20     | :employee-rpp-contribution                     |
   | 22     | :income-tax-withheld                           |
   | 24     | :ei-insurable-earnings (capped; engine-driven) |
   | 26     | :cpp-pensionable-earnings (capped)             |
   | 44     | :union-dues                                    |
   | 46     | :charitable-donation-payroll                   |
   | 52     | :pension-adjustment                            |
   | 55     | :employee-qpip (QC passthrough)                |
   | 56     | :qpip-insurable-earnings (QC passthrough)      |

   Box 40 (taxable benefits subset of box-14) + box 45 (dental
   coverage code) are read from the catalog's `:t4-box-40-include?`
   and `:dental-coverage-code` slots but NOT emitted by the current
   `xml/t4.clj` (the 2026V4 `T4_AMT` slot list omits them — they
   live in the T4 'Other Information' area not currently modeled).
   The T4 builder emits a `:box-40-other-info` map on the slip so a
   future extension to `xml/t4.clj` can pick them up; consumers
   needing these now print a paper-T4 supplement.

   ## Multi-province per employee

   An employee who worked in two provinces during the tax year gets
   TWO T4 slips per the CRA RC4120 guide. The aggregator groups by
   `(person × RP × tax-year × province-of-employment)`. The
   province is read from each PayrollFact's
   `:jurisdiction-specific-codes :province-of-employment`; if absent,
   the aggregator falls back to the employee's
   `:kontor.employment/province-of-employment` attribute."
  (:require [datahike.api :as d]
            [kontor.l10n-ca.xml.t4 :as xt4]
            [kontor.money :as money])
  (:import [java.math BigDecimal]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- sum-bd
  ^BigDecimal [bds]
  (reduce (fn [a v] (.add ^BigDecimal a ^BigDecimal v)) 0M bds))

(defn- cad [^BigDecimal x] (money/money x :CAD))

(defn- fact-components-by-kind
  "Return a map kind → BigDecimal sum (absolute value) over components.
   Carry-only kinds (insurable earnings, PA, dental code) come in via
   :jurisdiction-specific-codes."
  [{:keys [components jurisdiction-specific-codes]}]
  (let [from-comps
        (reduce (fn [m {:keys [kind amount]}]
                  (update m kind
                          (fn [v]
                            (.add ^BigDecimal (or v 0M) ^BigDecimal amount))))
                {} components)]
    (merge-with
     (fn [a b]
       (if (and (instance? BigDecimal a) (instance? BigDecimal b))
         (.add ^BigDecimal a ^BigDecimal b)
         (or a b)))
     from-comps
     (or jurisdiction-specific-codes {}))))

(defn- province-of
  "Resolve the province-of-employment for a fact: prefer the
   per-fact code (engines sometimes encode this), fall back to the
   :employment attribute, finally to a default. Tolerates a nil db
   (synthetic-eid test fixtures with no actual :employment row)."
  [db {:keys [employment jurisdiction-specific-codes]} default]
  (or (:province-of-employment jurisdiction-specific-codes)
      (when (and db (or (number? employment) (vector? employment)))
        (some-> (d/pull db [:kontor.employment/province-of-employment] employment)
                :kontor.employment/province-of-employment))
      default))

;; ============================================================================
;; Box aggregation
;; ============================================================================

(def ^:private box-mapping
  "Component-kind → T4-box keyword (mirror of wage_types.clj's :t4-box
   slot, but locally so the builder can be reasoned about without
   round-tripping through the catalog)."
  {:base-wage              :box-14
   :overtime               :box-14
   :bonus                  :box-14
   :commission             :box-14
   :vacation-pay-paid-out  :box-14
   :statutory-holiday-pay  :box-14
   :retroactive-pay        :box-14
   :severance              :box-14
   :retiring-allowance     :box-14
   :taxable-benefit-auto              :box-14
   :taxable-benefit-group-term-life   :box-14
   :taxable-benefit-parking           :box-14
   :taxable-benefit-other             :box-14
   :employee-cpp           :box-16
   :employee-cpp2          :box-16a
   :employee-qpp           :box-17
   :employee-qpp2          :box-17a
   :employee-ei            :box-18
   :employee-rpp-contribution :box-20
   :income-tax-withheld    :box-22
   :ei-insurable-earnings  :box-24
   :cpp-pensionable-earnings :box-26
   :union-dues             :box-44
   :charitable-donation-payroll :box-46
   :pension-adjustment     :box-52
   :employee-qpip          :box-55
   :qpip-insurable-earnings :box-56})

(def ^:private box-40-kinds
  #{:taxable-benefit-auto
    :taxable-benefit-group-term-life
    :taxable-benefit-parking
    :taxable-benefit-other})

(defn- accumulate-boxes
  "Reduce a vector of facts into a {box-key → BigDecimal} map.
   Deduction components in facts come in as negative numbers — for T4
   box reporting we want the absolute value (the slip reports a
   positive 'amount withheld', not a signed posting amount)."
  [facts]
  (->> facts
       (mapcat (fn [fact]
                 (let [by-kind (fact-components-by-kind fact)]
                   (->> by-kind
                        (keep (fn [[kind amount]]
                                (when-let [box (get box-mapping kind)]
                                  [box (if (instance? BigDecimal amount)
                                         (.abs ^BigDecimal amount)
                                         0M)])))))))
       (reduce (fn [m [box amount]]
                 (update m box
                         (fn [v] (.add ^BigDecimal (or v 0M) ^BigDecimal amount))))
               {})))

(defn- accumulate-box-40
  "Reduce a vector of facts into the box-40 'Other Information'
   taxable-benefit subtotal (sum of benefit kinds that contribute to
   box-40)."
  [facts]
  (->> facts
       (mapcat (fn [fact]
                 (->> (fact-components-by-kind fact)
                      (keep (fn [[kind amount]]
                              (when (and (contains? box-40-kinds kind)
                                         (instance? BigDecimal amount))
                                (.abs ^BigDecimal amount)))))))
       sum-bd))

;; ============================================================================
;; payroll-facts->t4-slip — the load-bearing C4 function
;; ============================================================================

(defn payroll-facts->t4-slip
  "Aggregate a year of `PayrollFacts` for ONE
   (person × RP × tax-year × province-of-employment) into the
   `:t4/...` input shape that `kontor.l10n-ca.xml.t4/slip->element`
   accepts.

   Inputs:
     :facts         vector of `PayrollFacts` (already filtered to one
                    person × RP × tax-year × province)
     :rp-bn15       \"123456782RP0001\" (the employer BN15 to embed on
                    the slip)
     :person        {:given-name :family-name :initial
                     :national-id-sin :address :province-of-employment}
                    pulled from the database by caller (or filled
                    explicitly).
     :report-type   :original | :amended | :cancelled (defaults
                    :original).
     :cpp-qpp-exempt? :ei-exempt?  booleans (from
                    `:kontor.employment/cpp-exempt?` / `:kontor.employment/ei-exempt?`).
     :dental-coverage-code  integer 1..5 (T4 box-45) — informational;
                    `xml/t4.clj` currently doesn't emit it but the slip
                    map carries it for future extension.

   Returns the `:t4/...` map ready for `slip->element`."
  [{:keys [facts rp-bn15 person report-type
           cpp-qpp-exempt? ei-exempt? dental-coverage-code]
    :or {report-type :original
         cpp-qpp-exempt? false
         ei-exempt? false}}]
  (let [boxes (accumulate-boxes facts)
        box-40 (accumulate-box-40 facts)
        box-money (reduce-kv
                   (fn [m box bd]
                     (assoc m box (cad bd)))
                   {} boxes)
        province (or (:province-of-employment person)
                     (throw (ex-info "T4 slip needs :province-of-employment on :person"
                                     {:person person})))]
    (cond->
     {:t4/employer-bn rp-bn15
      :t4/sin (or (:national-id-sin person)
                  (throw (ex-info "T4 slip needs :national-id-sin on :person"
                                  {:person person})))
      :t4/employee {:surname (:family-name person)
                    :given   (:given-name person)
                    :initial (:initial person)}
      :t4/employee-address (:address person)
      :t4/province-of-employment province
      :t4/cpp-qpp-exempt? (boolean cpp-qpp-exempt?)
      :t4/ei-exempt?      (boolean ei-exempt?)
      :t4/report-type     report-type
      :t4/boxes box-money}
      (pos? (compare ^BigDecimal box-40 0M))
      (assoc :t4/box-40-other-info (cad box-40))
      dental-coverage-code
      (assoc :t4/box-45-dental-code dental-coverage-code))))

;; ============================================================================
;; Multi-province group-by + multi-slip generation
;; ============================================================================

(defn group-facts-for-slips
  "Group facts by (person × RP × tax-year × province-of-employment).
   Returns a map keyed by [person-eid rp-bn15 tax-year province] of
   the facts vector. Single-province employees → one slip per (person,
   RP, year); multi-province employees → multiple."
  [db facts {:keys [rp-bn15 tax-year default-province]}]
  (group-by
   (fn [{:keys [employment] :as fact}]
     [employment rp-bn15 tax-year (province-of db fact default-province)])
   facts))

;; ============================================================================
;; build-t4-return-submission — full payroll year → IFT submission
;; ============================================================================

(defn build-t4-return-submission
  "Aggregate a year of `PayrollFacts` for ONE RP into the
   {:t619 :t4-summary :slips} shape that
   `kontor.l10n-ca.xml.t4/submission` accepts.

   Required opts:
     :facts          full year of PayrollFacts (already filtered by RP
                     + tax-year)
     :rp-bn15        the employer BN15
     :tax-year       integer (e.g. 2026)
     :employer-name  string OR vector of up to 3 lines
     :employer-address {:line-1 :city :province :country :postal-code}
                       (optional)
     :transmitter    {:account-number :name :country-code :contact
                      :reference-id :summary-count} per
                     `kontor.l10n-ca.xml.t619/->element`
     :persons-by-emp function (employment-eid → person map). The
                     caller (e.g. an HR-companion-aware adapter) walks
                     :person + :employment + :address to construct it.
     :default-province fallback province code if neither the fact nor
                       :kontor.employment/province-of-employment is set.
     :language       :en (default) | :fr — emits T619 lang_cd E|F
                     AND tags the submission's audit-doc entry.
     :report-type    :original (default) | :amended

   Returns a clojure.data.xml-ready element (call
   `kontor.l10n-ca.xml.t4/emit-string` to serialize for IFT upload).

   The function also returns the `:audit-doc` tx-data fragment the
   `kontor.payroll-ca.emit/build-t4-audit-doc-tx-data` can transact."
  [db {:keys [facts rp-bn15 tax-year employer-name employer-address
              transmitter persons-by-emp default-province
              language report-type]
       :or {language :en
            report-type :original}}]
  (when-not facts        (throw (ex-info ":facts required" {})))
  (when-not rp-bn15      (throw (ex-info ":rp-bn15 required" {})))
  (when-not tax-year     (throw (ex-info ":tax-year required" {})))
  (when-not employer-name (throw (ex-info ":employer-name required" {})))
  (when-not transmitter  (throw (ex-info ":transmitter required" {})))
  (when-not persons-by-emp
    (throw (ex-info ":persons-by-emp fn required" {})))
  (let [grouped (group-facts-for-slips
                 db facts
                 {:rp-bn15 rp-bn15 :tax-year tax-year
                  :default-province default-province})
        slips
        (mapv (fn [[k emp-facts]]
                (let [[emp-eid rp _tax-yr province] k
                      person (-> (persons-by-emp emp-eid)
                                 (assoc :province-of-employment province))]
                  (payroll-facts->t4-slip
                   {:facts emp-facts
                    :rp-bn15 rp
                    :person person
                    :report-type report-type
                    :cpp-qpp-exempt? (:cpp-qpp-exempt? person)
                    :ei-exempt? (:ei-exempt? person)
                    :dental-coverage-code (:dental-coverage-code person)})))
              grouped)
        summary {:t4-summary/employer-bn rp-bn15
                 :t4-summary/employer-name employer-name
                 :t4-summary/employer-address employer-address
                 :t4-summary/contact (:transmitter/contact transmitter)
                 :t4-summary/tax-year tax-year
                 :t4-summary/report-type
                 (case report-type
                   :original :original
                   :amended  :modified ; T4Summary has O/A/M only
                   :cancelled :amended)}
        t619 (assoc transmitter
                    :submission/language
                    (case language :fr :french :en :english))]
    (xt4/submission {:t619 t619
                     :t4-summary summary
                     :slips slips})))
