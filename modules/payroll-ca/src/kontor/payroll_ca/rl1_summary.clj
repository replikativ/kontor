(ns kontor.payroll-ca.rl1-summary
  "Revenu Québec RL-1 Summary (RLZ-1.S — Summary of Source Deductions
   and Employer Contributions). Annual employer-level rollup that
   accompanies the RL-1 slip submission.

   ## Scope (ADR-087 §2)

   Aggregates RL-1 slips by employer × tax-year and builds the
   `:rlz-1-s/...` element shape that `summary->element` emits inside the
   envelope produced by `kontor.payroll-ca.rl1-envelope/submission`.

   The Summary rolls up:

   - **Box 21** — total Quebec income tax withheld (sum of RL-1 box E)
   - **Box 22** — total QPP contributions (sum of RL-1 box B)
   - **Box 22.1** — total QPP2 contributions (sum of RL-1 box B.A)
   - **Box 23** — total QPIP premiums (sum of RL-1 box H)
   - **Box 27** — employer QPP / QPP2 contributions (employer side)
   - **Box 28** — employer QPIP contributions (employer side)
   - **Box 30** — Fonds des services de santé (employer FSS / Health
     Services Fund contribution) — consumer-supplied (rate depends on
     the total-payroll-threshold bracket per Revenu Québec)
   - **Box 32** — RL-1 slip count (the number of `<Releve1>` elements
     in the submission)

   Per the official guide:
   - Revenu Québec, Guide to Filing the RL-1 Summary (RLZ-1.S-G-V) —
     public.
     https://www.revenuquebec.ca/en/online-services/forms-and-publications/rlz-1-s-g-v/guide-to-filing-the-rl-1-summary-summary-of-source-deductions-and-employer-contributions/
     Accessed 2026-05-18.
   - Revenu Québec, Filing RL Slips and the RL-1 Summary — General
     Information.
     https://www.revenuquebec.ca/en/businesses/source-deductions-and-employer-contributions/filing-rl-slips-and-the-rl-1-summary-general-information/
     Accessed 2026-05-18.

   ## License posture

   Box names + Sommaire 1 structure are public facts (form RLZ-1.S
   published as customer-facing documentation by Revenu Québec). No
   partner XSD lifted; this namespace is a clean-room derivation."
  (:require [clojure.data.xml :as xml]
            [kontor.money :as money])
  (:import [java.math BigDecimal]))

;; ============================================================================
;; Aggregation helpers
;; ============================================================================

(defn- sum-box
  ^BigDecimal [slips box-key]
  (reduce (fn [^BigDecimal a slip]
            (let [m (get-in slip [:rl1/boxes box-key])]
              (if (and m (instance? BigDecimal (:amount m)))
                (.add a ^BigDecimal (:amount m))
                a)))
          0M slips))

(defn build-summary
  "Aggregate a vector of RL-1 slips into the `:rlz-1-s/...` map ready
   for `summary->element`.

   Inputs:
     :slips             vector of slip maps returned by
                        `kontor.payroll-ca.rl1/payroll-facts->rl1-slip`
     :employer-neq      employer NEQ
     :employer-id-number  transmitter / Quebec employer ID (NP…)
     :employer-name     string or vector of up to 3 lines
     :employer-address  CanadaAddressType-equivalent map (optional)
     :tax-year          integer
     :report-type       :original (default) | :amended
     :fss-contribution  Money — employer FSS (Health Services Fund)
                        contribution amount; consumer-computed per the
                        total-payroll-threshold bracket. Optional;
                        consumer-supplied because the substrate does
                        not bundle the rate table.
     :employer-qpp      Money — employer QPP (matching) — optional,
                        consumer-supplied (engine total).
     :employer-qpip     Money — employer QPIP — optional.
     :contact           {:name :phone :email}  — Revenu Québec contact
                        for the RL-1 summary
     :reference-number  Revenu Québec summary slip reference (Sommaire
                        slip number assignment) — consumer-supplied"
  [{:keys [slips employer-neq employer-id-number employer-name
           employer-address tax-year report-type
           fss-contribution employer-qpp employer-qpip
           contact reference-number]
    :or {report-type :original}}]
  (when-not slips (throw (ex-info ":slips required" {})))
  (when-not employer-neq (throw (ex-info ":employer-neq required" {})))
  (when-not tax-year (throw (ex-info ":tax-year required" {})))
  (when-not employer-name (throw (ex-info ":employer-name required" {})))
  (let [box-e (sum-box slips :e)
        box-b (sum-box slips :b)
        box-ba (sum-box slips :b.a)
        box-h (sum-box slips :h)
        zero (money/zero :CAD)]
    {:rlz-1-s/employer-neq employer-neq
     :rlz-1-s/employer-id-number employer-id-number
     :rlz-1-s/employer-name employer-name
     :rlz-1-s/employer-address employer-address
     :rlz-1-s/tax-year tax-year
     :rlz-1-s/report-type report-type
     :rlz-1-s/slip-count (count slips)
     :rlz-1-s/contact contact
     :rlz-1-s/reference-number reference-number
     ;; Summary totals
     :rlz-1-s/quebec-income-tax-withheld (money/money box-e :CAD)
     :rlz-1-s/qpp-employee (money/money box-b :CAD)
     :rlz-1-s/qpp2-employee (money/money box-ba :CAD)
     :rlz-1-s/qpip-employee (money/money box-h :CAD)
     :rlz-1-s/qpp-employer (or employer-qpp zero)
     :rlz-1-s/qpip-employer (or employer-qpip zero)
     :rlz-1-s/fss-contribution (or fss-contribution zero)}))

;; ============================================================================
;; XML emission
;; ============================================================================

(defn- fmt-amount [m]
  (.toPlainString
   (.setScale ^BigDecimal (:amount m)
              2 java.math.RoundingMode/HALF_EVEN)))

(defn- amt-el
  [tag m]
  (when (and m (not (money/zero? m)))
    (xml/element tag {} (fmt-amount m))))

(defn- name-element [name]
  (let [lines (if (vector? name) name [name])]
    (apply xml/element :NomEmployeur {}
           (remove nil?
                   [(xml/element :Ligne1 {} (first lines))
                    (when (second lines) (xml/element :Ligne2 {} (second lines)))
                    (when (nth lines 2 nil) (xml/element :Ligne3 {} (nth lines 2)))]))))

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

(defn- contact-element [{:keys [name phone email]}]
  (when (or name phone email)
    (apply xml/element :Contact {}
           (remove nil?
                   [(when name  (xml/element :Nom {} name))
                    (when phone (xml/element :Telephone {} phone))
                    (when email (xml/element :Courriel {} email))]))))

(def report-code
  "RLZ-1.S report-type codes — 'R' original, 'A' amended."
  {:original "R" :amended "A"})

(defn summary->element
  "Render a `<Sommaire1>` summary element. Consumes the map produced by
   `build-summary`."
  [{:rlz-1-s/keys [employer-neq employer-id-number employer-name
                   employer-address tax-year report-type slip-count
                   contact reference-number
                   quebec-income-tax-withheld
                   qpp-employee qpp2-employee qpip-employee
                   qpp-employer qpip-employer fss-contribution]
    :or {report-type :original}}]
  (apply xml/element :Sommaire1 {}
         (remove nil?
                 [(when reference-number
                    (xml/element :NumeroSommaire {} reference-number))
                  (xml/element :CodeReleve {} (report-code report-type))
                  (xml/element :Annee {} (str tax-year))
                  (xml/element :NEQ {} employer-neq)
                  (when employer-id-number
                    (xml/element :NumeroIdentification {} employer-id-number))
                  (name-element employer-name)
                  (when employer-address (address-element employer-address))
                  (contact-element contact)
                  (xml/element :NombreReleves {} (str slip-count))
                  ;; Box 21 — total Quebec ITX
                  (amt-el :Case21 quebec-income-tax-withheld)
                  ;; Box 22 / 22.1 — QPP / QPP2 (employee)
                  (amt-el :Case22 qpp-employee)
                  (amt-el :Case22-1 qpp2-employee)
                  ;; Box 23 — QPIP employee
                  (amt-el :Case23 qpip-employee)
                  ;; Box 27 — employer QPP / QPP2
                  (amt-el :Case27 qpp-employer)
                  ;; Box 28 — employer QPIP
                  (amt-el :Case28 qpip-employer)
                  ;; Box 30 — Fonds des services de santé
                  (amt-el :Case30 fss-contribution)])))

;; ============================================================================
;; Full RL-1 submission envelope (slips + Sommaire1 + transmitter)
;; ============================================================================

(defn submission
  "Build the complete Revenu Québec RL-1 submission XML. The envelope
   wraps:

       <Releves>            -- root
         <Transmetteur>     -- the transmitter info
         <Releve1>+         -- one per QC employee (per tax year)
         <Sommaire1>        -- exactly one
       </Releves>

   Element names are clean-room from the public RL-1 / RL-1 Summary
   form documentation; consumers using certified RL-1 software may
   need to remap (the data shape is the load-bearing seam).

   Input:
     {:transmitter {…}   ; see transmitter-element below
      :slips        [{…} …]
      :summary      {…}}  ; result of `build-summary`

   Returns a clojure.data.xml element. Use `emit-string` to serialize."
  [{:keys [transmitter slips summary]}]
  (xml/element
   :Releves {}
   ;; Transmitter
   (let [{:transmetteur/keys [np-number neq name contact]} transmitter]
     (apply xml/element :Transmetteur {}
            (remove nil?
                    [(when np-number
                       (xml/element :NumeroTransmetteur {} np-number))
                     (when neq (xml/element :NEQ {} neq))
                     (when name
                       (let [lines (if (vector? name) name [name])]
                         (apply xml/element :Nom {}
                                (remove nil?
                                        [(xml/element :Ligne1 {} (first lines))
                                         (when (second lines)
                                           (xml/element :Ligne2 {} (second lines)))]))))
                     (contact-element contact)])))
   ;; Each slip (load with `rl1/slip->element` from the caller; we
   ;; intentionally don't import rl1 here to keep the namespace lean.
   ;; The caller passes pre-rendered slip elements.)
   (xml/element :Releve1Group {}
                (apply xml/element :Releve1List {}
                       slips))
   ;; Summary
   (summary->element summary)))

(defn emit-string
  "Render a submission element to an XML string (UTF-8)."
  [submission-element]
  (xml/emit-str submission-element))
