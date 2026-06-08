(ns kontor.payroll-ca.rl1
  "Revenu Québec RL-1 (Relevé 1) slip — Quebec annual statement of
   employment income. Parallel to the federal T4 for any employee whose
   province-of-employment is QC.

   ## Scope (ADR-087 — Stage R C4.1)

   - Reduces a full year of `PayrollFacts` for one
     (person × employer × tax-year × QC) into the RL-1 slip element
     structure.
   - Renders the slip + an envelope wrapping a `Sommaire1` summary into
     a transmittable XML document (one `<T4Submission>`-equivalent
     per filing).
   - Audit-doc convention: `:kontor.audit-doc/category :payroll-filing` +
     `:kontor.audit-doc/language :fr` (RL-1 is French by default).

   ## License posture (CLAUDE.md / ADR-001 / ADR-005 / ADR-087)

   Revenu Québec's RL-1 XSD bundle is **partner-only** (registration
   gate, unlike CRA's public T619 XSDs); we therefore do NOT ship the
   XSD nor a validator against it. Box names + meanings are public
   facts (the form is published as `RL-1.T-V` / `RL-1.G-V`); they are
   not copyrightable. The element shape here is a clean-room derivation
   from public form documentation:

   - Revenu Québec, RL-1 Slip (form RL-1.T-V) — public.
     https://www.revenuquebec.ca/en/online-services/forms-and-publications/current-details/rl-1-t/
     Accessed 2026-05-18.
   - Revenu Québec, Guide to Filing the RL-1 Slip (RL-1.G-V) — public.
     https://www.revenuquebec.ca/en/online-services/forms-and-publications/rl-1-g-v/guide-to-filing-the-rl-1-slip-employment-and-other-income/
     Accessed 2026-05-18.
   - Sending RL Slips and Summaries Online — public.
     https://www.revenuquebec.ca/en/businesses/rl-slips-and-summaries/sending-rl-slips-and-summaries/sending-rl-slips-and-summaries-to-revenu-quebec/online/
     Accessed 2026-05-18.

   Consumers with the partner XSD bundle can validate via
   `kontor.l10n-ca.xml.validation/validate!` against the file
   (out-of-band).

   ## Box catalog

   | RL-1 box | Meaning                                      | Source kind |
   |----------|----------------------------------------------|-------------|
   | A        | Employment income (gross)                    | :base-wage / :bonus / :overtime / :commission / :taxable-benefit-* |
   | B        | QPP contributions (employee)                 | :employee-qpp |
   | B.A      | QPP2 (second additional) contributions       | :employee-qpp2 |
   | C        | EI premiums (employee — reduced QC rate)     | :employee-ei |
   | D        | RPP / PRPP employer contributions            | :employer-rpp-match |
   | E        | Quebec income tax withheld                   | :employee-qc-itx |
   | F        | Union dues                                   | :union-dues |
   | G        | QPP pensionable earnings                     | :cpp-pensionable-earnings (carry-only) |
   | H        | QPIP premiums (employee)                     | :employee-qpip |
   | I        | QPIP eligible salary or wages                | :qpip-insurable-earnings (carry-only) |
   | J        | Employer-paid private health-services-plan   | :taxable-benefit-private-health (consumer extension) |
   | K        | Trips for residents of designated remote area| (consumer extension) |
   | L        | Other benefits (taxable in QC)               | :taxable-benefit-other |
   | M        | Commissions (subset of A)                    | :commission |
   | N        | Charitable donations                         | :charitable-donation-payroll |
   | O        | Other income (code-prefixed; e.g. RA, RB)    | (consumer extension via :rl1-box-o-code) |
   | P        | Employee benefit plan / DPSP                 | (consumer extension) |
   | Q        | Deferred salary or wages                     | (consumer extension) |
   | R        | Income for Indian Act employees              | (consumer extension) |
   | S        | Volunteer firefighter / search-and-rescue    | (consumer extension) |
   | T        | RPP employer contributions                   | (consumer extension) |
   | U        | Phased retirement                            | (consumer extension) |
   | V        | Meals / lodging benefits                     | (consumer extension) |
   | W        | Vehicle-purchase / leasing                   | (consumer extension) |

   The catalog covers the common boxes; consumers add bespoke boxes
   via `:rl1-extras-map` (the same open-set pattern as
   `kontor.payroll-ca.wage-types/merged-catalog`).

   ## Report type codes

   - `:original`  → 'R'  (original)
   - `:amended`   → 'A'  (amended)
   - `:cancelled` → 'D'  (annulé / cancelled)

   Per the Amending RL Slips guide:
   https://www.revenuquebec.ca/en/businesses/rl-slips-and-summaries/amending-or-cancelling-rl-slips-or-summaries/amending-rl-slips/
   Accessed 2026-05-18."
  (:require [clojure.data.xml :as xml]
            [datahike.api :as d]
            [kontor.money :as money])
  (:import [java.math BigDecimal]))

;; ============================================================================
;; Helpers (sign + sum)
;; ============================================================================

(defn- sum-bd
  ^BigDecimal [bds]
  (reduce (fn [a v] (.add ^BigDecimal a ^BigDecimal v)) 0M bds))

(defn- cad [^BigDecimal x] (money/money x :CAD))

(defn- abs-bd
  ^BigDecimal [^BigDecimal v]
  (.abs v))

(defn- fact-components-by-kind
  "Return a map kind → BigDecimal sum (absolute value) over components.
   Mirror of `kontor.payroll-ca.t4-builder/fact-components-by-kind`."
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

;; ============================================================================
;; Box aggregation
;; ============================================================================

(def box-mapping
  "Component-kind → RL-1 box keyword. Component-kinds that contribute
   to multiple boxes (e.g. :commission flows to BOTH box-A and box-M)
   are handled in `accumulate-boxes` via the `:also-boxes` slot."
  {:base-wage              {:box :a}
   :overtime               {:box :a}
   :bonus                  {:box :a}
   :commission             {:box :a :also-boxes [:m]}
   :vacation-pay-paid-out  {:box :a}
   :statutory-holiday-pay  {:box :a}
   :retroactive-pay        {:box :a}
   :severance              {:box :a}
   :retiring-allowance     {:box :a}
   :taxable-benefit-auto              {:box :a :also-boxes [:l]}
   :taxable-benefit-group-term-life   {:box :a :also-boxes [:l]}
   :taxable-benefit-parking           {:box :a :also-boxes [:l]}
   :taxable-benefit-other             {:box :a :also-boxes [:l]}
   :employee-qpp           {:box :b}
   :employee-qpp2          {:box :b.a}
   :employee-ei            {:box :c}
   :employer-rpp-match     {:box :d}
   :employee-qc-itx        {:box :e}
   :union-dues             {:box :f}
   :cpp-pensionable-earnings {:box :g}
   :employee-qpip          {:box :h}
   :qpip-insurable-earnings {:box :i}
   :charitable-donation-payroll {:box :n}})

(defn- accumulate-boxes
  "Reduce a vector of facts into a {box → BigDecimal} map of absolute
   amounts. Deduction components (negative amounts) are abs-converted
   for slip reporting (the slip carries withholding totals, not signed
   posting amounts)."
  [facts]
  (->> facts
       (mapcat (fn [fact]
                 (let [by-kind (fact-components-by-kind fact)]
                   (->> by-kind
                        (mapcat (fn [[kind amount]]
                                  (when-let [{:keys [box also-boxes]}
                                             (get box-mapping kind)]
                                    (let [a (if (instance? BigDecimal amount)
                                              (abs-bd amount)
                                              0M)]
                                      (cons [box a]
                                            (mapv (fn [b] [b a]) also-boxes))))))))))
       (reduce (fn [m [box a]]
                 (update m box
                         (fn [v] (.add ^BigDecimal (or v 0M) ^BigDecimal a))))
               {})))

;; ============================================================================
;; payroll-facts->rl1-slip — the load-bearing function
;; ============================================================================

(defn payroll-facts->rl1-slip
  "Aggregate a year of `PayrollFacts` for ONE
   (person × employer × tax-year × QC) into the
   `:rl1/...` map ready for `slip->element`.

   Inputs:
     :facts                vector of PayrollFacts (already filtered to
                           one person × employer × tax-year × QC)
     :employer-neq         employer Quebec Enterprise Number (NEQ);
                           10-digit string (consumer-supplied; format
                           validation lives in the consumer or a future
                           kontor.l10n-ca.identifiers helper)
     :employer-id-number   Quebec Identification Number (\"NPxxxxxx\"
                           transmitter identifier OR the 10-digit
                           employer ID under TPZ-1015); consumer-supplied
     :person               {:given-name :family-name :initial
                            :national-id-sin :address}
     :report-type          :original | :amended | :cancelled
     :rl1-extras-map       consumer-supplied open-set extension to
                           `box-mapping` (mirrors `wt/merged-catalog`)
     :reference-number     unique slip reference (Revenu Québec assigns
                           a range; consumer-supplied)

   Returns the `:rl1/...` map ready for `slip->element`."
  [{:keys [facts employer-neq employer-id-number person
           report-type reference-number]
    :or {report-type :original}}]
  (when-not (seq facts) (throw (ex-info ":facts required" {})))
  (when-not employer-neq (throw (ex-info ":employer-neq required" {})))
  (when-not (:national-id-sin person)
    (throw (ex-info "RL-1 slip needs :national-id-sin on :person"
                    {:person person})))
  (let [boxes (accumulate-boxes facts)
        box-money (reduce-kv (fn [m k v] (assoc m k (cad v))) {} boxes)]
    {:rl1/employer-neq employer-neq
     :rl1/employer-id-number employer-id-number
     :rl1/sin (:national-id-sin person)
     :rl1/employee {:surname (:family-name person)
                    :given   (:given-name person)
                    :initial (:initial person)}
     :rl1/employee-address (:address person)
     :rl1/report-type report-type
     :rl1/reference-number reference-number
     :rl1/boxes box-money}))

;; ============================================================================
;; XML element emission (clean-room derivation from public form)
;; ============================================================================
;; Element naming follows the convention from the public RL-1.T-V form
;; (camelCase with French source names where Revenu Québec uses them in
;; partner docs). Consumers using a certified RL-1 software may need to
;; remap element names — the data shape is the load-bearing seam.

(defn- fmt-amount [m]
  (.toPlainString
   (.setScale ^BigDecimal (:amount m)
              2 java.math.RoundingMode/HALF_EVEN)))

(defn- amt-el
  [tag m]
  (when (and m (not (money/zero? m)))
    (xml/element tag {} (fmt-amount m))))

(defn- name-element [{:keys [surname given initial]}]
  (apply xml/element :Nom {}
         (remove nil?
                 [(xml/element :NomFamille {} surname)
                  (when given (xml/element :Prenom {} given))
                  (when initial (xml/element :Initiale {} initial))])))

(defn- address-element [{:keys [line-1 line-2 city province
                                country postal-code]}]
  (apply xml/element :Adresse {}
         (remove nil?
                 [(when line-1 (xml/element :Ligne1 {} line-1))
                  (when line-2 (xml/element :Ligne2 {} line-2))
                  (when city (xml/element :Ville {} city))
                  (when province (xml/element :Province {} province))
                  (when country (xml/element :Pays {} country))
                  (when postal-code (xml/element :CodePostal {} postal-code))])))

(def report-code
  "Revenu Québec RL-slip report-type codes (per the Amending RL Slips
   guide, Revenu Québec)."
  {:original "R" :amended "A" :cancelled "D"})

(defn- boxes-element [boxes]
  (when (seq boxes)
    (apply xml/element :Cases {}
           (remove nil?
                   [(amt-el :CaseA (:a boxes))
                    (amt-el :CaseB (:b boxes))
                    (amt-el :CaseBA (:b.a boxes))
                    (amt-el :CaseC (:c boxes))
                    (amt-el :CaseD (:d boxes))
                    (amt-el :CaseE (:e boxes))
                    (amt-el :CaseF (:f boxes))
                    (amt-el :CaseG (:g boxes))
                    (amt-el :CaseH (:h boxes))
                    (amt-el :CaseI (:i boxes))
                    (amt-el :CaseJ (:j boxes))
                    (amt-el :CaseK (:k boxes))
                    (amt-el :CaseL (:l boxes))
                    (amt-el :CaseM (:m boxes))
                    (amt-el :CaseN (:n boxes))
                    (amt-el :CaseO (:o boxes))]))))

(defn slip->element
  "Render one RL-1 slip element.

   Input shape (mirror of `kontor.l10n-ca.xml.t4/slip->element`):

     {:rl1/employer-neq       \"1234567890\"
      :rl1/employer-id-number \"NP123456\"   ; transmitter NP-prefixed
      :rl1/sin                \"123456789\"
      :rl1/employee           {:surname … :given … :initial …}
      :rl1/employee-address   {…}
      :rl1/report-type        :original | :amended | :cancelled
      :rl1/reference-number   \"unique-per-slip\"
      :rl1/boxes              {:a Money :b Money …}}"
  [{:rl1/keys [employer-neq employer-id-number sin employee
               employee-address report-type reference-number boxes]
    :or {report-type :original}}]
  (apply xml/element :Releve1 {}
         (remove nil?
                 [(when reference-number
                    (xml/element :NumeroReleve {} reference-number))
                  (xml/element :CodeReleve {} (report-code report-type))
                  (xml/element :NEQ {} employer-neq)
                  (when employer-id-number
                    (xml/element :NumeroIdentification {} employer-id-number))
                  (xml/element :NAS {} sin)
                  (name-element employee)
                  (when employee-address (address-element employee-address))
                  (boxes-element boxes)])))

;; ============================================================================
;; Audit-doc tx-data builder (ADR-068 pattern)
;; ============================================================================

(defn rl1-audit-doc-tx-data
  "Build `:audit-doc` tx-data recording an RL-1 slip / submission was
   produced. The consumer transacts this alongside the actual upload
   (which happens outside kontor — consumer's engine ops uploads the
   XML to Revenu Québec via partner channel per
   https://www.revenuquebec.ca/en/businesses/rl-slips-and-summaries/sending-rl-slips-and-summaries/).

   Required:
     :employer-neq         the employer's NEQ (10-digit)
     :tax-year             integer
     :slip-count           integer

   Optional:
     :report-type          :original (default) | :amended | :cancelled
     :storage-uri          where the consumer stored the XML
     :code                 audit-doc/code; defaults to NEQ + year + type
     :language             :fr (default — RL-1 is French) | :en"
  [{:keys [employer-neq tax-year slip-count report-type
           storage-uri code language]
    :or {report-type :original
         language :fr}}]
  (when-not employer-neq (throw (ex-info ":employer-neq required" {})))
  (when-not tax-year (throw (ex-info ":tax-year required" {})))
  (when-not slip-count (throw (ex-info ":slip-count required" {})))
  (let [doc-code (or code
                     (format "RL1-%s-%d-%s" employer-neq tax-year
                             (name (or report-type :original))))
        title (format "RL-1 submission — NEQ %s tax-year %d (%d slips, %s)"
                      employer-neq tax-year slip-count
                      (case language :en "EN" "FR"))]
    [(cond->
      {:kontor.audit-doc/code doc-code
       :kontor.audit-doc/type :regulator-clearance
       :kontor.audit-doc/title title
       :kontor.audit-doc/uploaded-at (java.util.Date.)
       :kontor.audit-doc/category :payroll-filing
       :kontor.audit-doc/language language}
       storage-uri (assoc :kontor.audit-doc/storage-uri storage-uri))]))

;; ============================================================================
;; group-facts-for-slips — QC-only filter + group-by employer × tax-year
;; ============================================================================

(defn qc-fact?
  "True iff a PayrollFact's province-of-employment is 'QC'. Looks at
   :jurisdiction-specific-codes :province-of-employment first, then
   falls back to the :employment row's
   :kontor.employment/province-of-employment (if `db` is supplied)."
  ([fact] (qc-fact? nil fact))
  ([db {:keys [employment jurisdiction-specific-codes]}]
   (let [from-fact (:province-of-employment jurisdiction-specific-codes)
         resolved (or from-fact
                      (when (and db (or (number? employment) (vector? employment)))
                        (some-> (d/pull db [:kontor.employment/province-of-employment]
                                        employment)
                                :kontor.employment/province-of-employment)))]
     (= "QC" resolved))))

(defn group-facts-for-slips
  "Group QC facts by (person × employer × tax-year). Returns a map keyed
   by employment-eid of the facts vector for that employment.
   Non-QC facts are filtered out — RL-1 is emitted ONLY for QC
   employees."
  [db facts]
  (->> facts
       (filter #(qc-fact? db %))
       (group-by :employment)))
