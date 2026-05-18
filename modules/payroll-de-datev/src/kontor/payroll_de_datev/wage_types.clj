(ns kontor.payroll-de-datev.wage-types
  "Wage-type catalog shape + default SKR04 / SKR03 account map for the
   DE-DATEV-LODAS adapter (ADR-076 / research note 82 §4.4 + §9.2).

   The catalog is **consumer-supplied** — kontor never bundles a
   wage-type list (mirrors ADR-005 / ADR-071 / ADR-075 'no bundled
   rate tables'). The shape this module defines is the validation
   surface + a documented default `:account-map` covering the 10
   load-bearing payroll accounts (note 82 §4.1).

   ## Catalog shape

     {:catalog/version   1
      :catalog/mandant   \"99999\"     ;; LODAS Mandanten-Nr.
      :catalog/berater   \"1234\"      ;; DATEV Berater-Nr.
      :catalog/coa       :skr04        ;; :skr04 | :skr03
      :catalog/wage-types
      {100  {:kind :base-salary :account-hint :gehalt}
       200  {:kind :base-wage   :account-hint :lohn :uom :hours}
       ;; ... per-customer LODAS Lohnart-Nr → posting hints.
       }
      :catalog/employer-contributions
      {:employer-si {:account-hint :soziale-aufwendungen}}}

   ## Validation invariants (note 82 §6.1 Personio convention)

     - `:catalog/wage-types` keys are integers (LODAS Lohnart-Nr).
     - Lohnart < 9000  → :kind ∈ #{:base-wage :base-salary :overtime
                                   :weihnachtsgeld :urlaubsgeld
                                   :bonus :bonus-target :vwl
                                   :imputed-income-tax-exempt
                                   :imputed-income-taxable
                                   :bav-direktversicherung
                                   :kurzarbeitergeld}  (Bezug)
     - Lohnart ≥ 9000  → :kind ∈ #{:pfaendung :net-deduction
                                   :net-addition}  (Netto-Bezug/Abzug)
     - Each entry has :kind + :account-hint."
  (:require [clojure.set :as set]))

;; ============================================================================
;; The 10 load-bearing payroll accounts (note 82 §4.1)
;; ============================================================================

(def default-account-map-skr04
  "account-hint → SKR04 4-digit Konto. The starter set per note 82
   §4.1; consumers extend / override.

   Sources cross-checked: BuchhaltungsButler [§11.15],
   rechnungswesen-info.de [§11.16], Kontolino! [§11.17],
   buchungssatz.de [§11.19 + §11.20]. SKR04 account numbers are
   public DATEV cooperative standard (not copyrighted facts)."
  {:lohn                        "6010"  ; Löhne (hourly)
   :gehalt                      "6020"  ; Gehälter (monthly)
   :soziale-aufwendungen        "6110"  ; Ges. soziale Aufwendungen (AG-SV)
   :freiwillig-st-pflichtig     "6060"  ; Freiwillige soziale Aufwendungen, lohnsteuerpflichtig
   :sachbezug-frei              "6130"  ; Freiwillige soziale Aufwendungen, lohnsteuerfrei
   :urlaubsrueckstellung-aufw   "6035"  ; Aufwendungen Urlaubsrückstellung
   :verb-lohn                   "3720"  ; Verb. aus Löhnen und Gehältern
   :verb-lohnsteuer             "3730"  ; Verb. aus Lohn- und Kirchensteuer
   :verb-sozialversicherung     "3740"  ; Verb. soziale Sicherheit
   :verrechnung                 "3790"  ; Lohn- und Gehaltsverrechnungskonto
   :verbindlichkeiten-pfaendung "3791"  ; Verb. aus Pfändungen
   :urlaubsrueckstellung        "3066"  ; Urlaubsrückstellung
   :bank                        "1810"})

(def default-account-map-skr03
  "account-hint → SKR03 4-digit Konto. Parallel chart for older
   mandanten."
  {:lohn                        "4120"
   :gehalt                      "4124"
   :soziale-aufwendungen        "4130"
   :freiwillig-st-pflichtig     "4145"
   :sachbezug-frei              "4140"
   :urlaubsrueckstellung-aufw   "4960"
   :verb-lohn                   "1740"
   :verb-lohnsteuer             "1741"
   :verb-sozialversicherung     "1755"
   :verrechnung                 "1755"   ; same as Verb. SV under SKR03
   :verbindlichkeiten-pfaendung "1798"
   :urlaubsrueckstellung        "0974"
   :bank                        "1200"})

(def default-account-maps
  {:skr04 default-account-map-skr04
   :skr03 default-account-map-skr03})

;; ============================================================================
;; Allowed :kind sets per Lohnart range (Personio convention; note 82 §6.1)
;; ============================================================================

(def bezug-kinds
  "Allowed :compensation-component/kind values for Lohnart-Nr < 9000
   (Bezug / gross-side wage types). Open-set; consumers extend by
   passing :allow-extra-bezug-kinds to validate-catalog."
  #{:base-wage :base-salary :overtime
    :weihnachtsgeld :urlaubsgeld
    :bonus :bonus-target :vwl
    :imputed-income-tax-exempt :imputed-income-taxable
    :bav-direktversicherung :kurzarbeitergeld
    ;; employer-side rolls in on Bewegungsdaten too:
    :employer-si :employer-pension})

(def netto-kinds
  "Allowed :compensation-component/kind values for Lohnart-Nr ≥ 9000
   (Netto-Bezug / Netto-Abzug — bypass gross-to-net engine)."
  #{:pfaendung :net-deduction :net-addition
    :employee-pension :voluntary-deduction
    :garnishment})

(defn- valid-coa? [coa]
  (contains? #{:skr04 :skr03} coa))

;; ============================================================================
;; validate-catalog — the entry point
;; ============================================================================

(defn validate-catalog
  "Validate a consumer-supplied wage-type catalog. Returns the catalog
   unchanged on success; throws ex-info with `:errors` on failure.

   Optional opts:
     :allow-extra-bezug-kinds  — additional :kind keywords valid for
                                  Lohnart < 9000.
     :allow-extra-netto-kinds — additional :kind keywords valid for
                                  Lohnart ≥ 9000."
  ([catalog] (validate-catalog catalog {}))
  ([{:keys [catalog/coa catalog/wage-types] :as catalog}
    {:keys [allow-extra-bezug-kinds allow-extra-netto-kinds]
     :or {allow-extra-bezug-kinds #{} allow-extra-netto-kinds #{}}}]
   (let [allowed-bezug (set/union bezug-kinds allow-extra-bezug-kinds)
         allowed-netto (set/union netto-kinds allow-extra-netto-kinds)
         errors
         (cond-> []
           (not (valid-coa? coa))
           (conj {:error :invalid-coa
                  :message (str ":catalog/coa must be :skr04 or :skr03; got "
                                (pr-str coa))})

           (not (map? wage-types))
           (conj {:error :missing-wage-types
                  :message ":catalog/wage-types map is required"}))
         errors (reduce
                 (fn [acc [lohnart entry]]
                   (let [{:keys [kind account-hint]} entry]
                     (cond-> acc
                       (not (integer? lohnart))
                       (conj {:error :non-integer-lohnart
                              :lohnart lohnart
                              :message "Lohnart-Nr keys must be integers"})

                       (nil? kind)
                       (conj {:error :missing-kind
                              :lohnart lohnart
                              :message ":kind required for every wage-type"})

                       (nil? account-hint)
                       (conj {:error :missing-account-hint
                              :lohnart lohnart
                              :message ":account-hint required for every wage-type"})

                       (and (integer? lohnart)
                            (< lohnart 9000)
                            kind
                            (not (contains? allowed-bezug kind)))
                       (conj {:error :kind-not-in-bezug-range
                              :lohnart lohnart
                              :kind kind
                              :message (str "Lohnart " lohnart " < 9000 expects "
                                            ":kind ∈ bezug-kinds; got " kind)})

                       (and (integer? lohnart)
                            (>= lohnart 9000)
                            kind
                            (not (contains? allowed-netto kind)))
                       (conj {:error :kind-not-in-netto-range
                              :lohnart lohnart
                              :kind kind
                              :message (str "Lohnart " lohnart " ≥ 9000 expects "
                                            ":kind ∈ netto-kinds; got " kind)}))))
                 errors
                 (when (map? wage-types) wage-types))]
     (if (seq errors)
       (throw (ex-info "wage-type catalog invalid"
                       {:errors errors :catalog catalog}))
       catalog))))

;; ============================================================================
;; lookup helpers
;; ============================================================================

(defn resolve-account-code
  "Resolve an :account-hint to a 4-digit Konto string. Uses the catalog's
   :coa-overrides (consumer customizations) first, falling back to the
   built-in default-account-maps for the catalog's :catalog/coa."
  [{:keys [catalog/coa catalog/account-overrides]} account-hint]
  (or (get account-overrides account-hint)
      (get-in default-account-maps [coa account-hint])))

(defn lookup-wage-type
  "Lookup a LODAS Lohnart-Nr in the catalog. Returns nil if not mapped
   (consumer routes unmapped to a manual-review queue per note 82
   §6.3.5)."
  [{:keys [catalog/wage-types]} lohnart-nr]
  (get wage-types lohnart-nr))
