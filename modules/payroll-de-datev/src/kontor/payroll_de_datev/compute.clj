(ns kontor.payroll-de-datev.compute
  "EXTF Buchungsstapel parser + `DatevLodasComputeProvider` impl.

   Per research note 82 §3: LODAS / Lohn und Gehalt do not export a
   structured 'Lohnauswertungsdatei CSV'; the GL impact ships as the
   **Lohn-Buchungsbeleg** (Report 80) which exports in the *same*
   EXTF Buchungsstapel format kontor already supports
   (`kontor.l10n-de.datev/export-buchungsstapel`). C2 reads that
   file and groups rows by (Belegfeld 1 = Pnr, Belegfeld 2 = Period)
   to materialize one PayrollFact per employee × pay-period.

   ## Format reminder (note 82 §3.2)

   Line 1: header — `EXTF;510;21;Buchungsstapel;<lines>;<ts>;…`
   Line 2: 122 column names quoted by `;` (we read the EXTF v21
           schema column ordering, but key off Konto / Gegenkonto /
           Belegfeld 1 / Belegfeld 2 / Buchungstext / Umsatz /
           Soll/Haben-Kz).
   Lines 3..n: data rows, one posting per row.

   ## Invariant (note 82 §3.3, §4.2, §11.18)

   After all postings for a (Pnr, period) group are summed, the
   Lohn-/Gehaltsverrechnungskonto (SKR04 3790 / SKR03 1755) must
   net to zero. The parser asserts this and refuses corrupt files
   by raising ex-info; the orchestrator surfaces this as
   `:kontor.payroll-run/state :buchungsbeleg-invalid` (per note 82 §9.4
   gotcha 12).

   License posture (ADR-001): the EXTF format is public DATEV spec;
   we describe it and write a clean-room parser. No proprietary
   code lifted."
  (:require [clojure.string :as str]
            [kontor.payroll-provider :as pp])
  (:import [java.math BigDecimal RoundingMode]
           [java.time LocalDate]
           [java.time.format DateTimeFormatter]
           [java.util Date]))

;; ============================================================================
;; Constants
;; ============================================================================

(def ^:const extf-encoding "ISO-8859-1")

;; Verrechnungskonto code per chart variant (note 82 §4.2 invariant).
(def verrechnungskonto-codes
  {:skr04 "3790"
   :skr03 "1755"})  ; SKR03 collapses Verrechnung onto Verb. SV

;; ============================================================================
;; CSV parser (lightweight; sufficient for DATEV's quoting rules)
;; ============================================================================

(defn- parse-cell
  "Strip quotes; un-double internal quotes (RFC 4180)."
  [^String cell]
  (let [s (str/trim cell)]
    (if (and (.startsWith s "\"") (.endsWith s "\""))
      (-> s (subs 1 (dec (count s))) (str/replace "\"\"" "\""))
      s)))

(defn- split-row
  "Split one DATEV row on `;`, respecting double-quote escaping
   (which doubles internal quotes). Pure; not the kitchen-sink CSV."
  [^String line]
  (loop [chars (.toCharArray line)
         i 0
         field (StringBuilder.)
         in-quote? false
         out (transient [])]
    (if (>= i (alength chars))
      (persistent! (conj! out (parse-cell (.toString field))))
      (let [c (aget chars i)
            next-c (when (< (inc i) (alength chars)) (aget chars (inc i)))]
        (cond
          (and in-quote? (= c \") (= next-c \"))
          (do (.append field c) (.append field next-c)
              (recur chars (+ i 2) field in-quote? out))

          (= c \")
          (do (.append field c)
              (recur chars (inc i) field (not in-quote?) out))

          (and (not in-quote?) (= c \;))
          (let [s (parse-cell (.toString field))]
            (recur chars (inc i) (StringBuilder.) in-quote?
                   (conj! out s)))

          :else
          (do (.append field c)
              (recur chars (inc i) field in-quote? out)))))))

(defn- parse-decimal
  "DATEV decimals: comma separator, no thousands grouping.
   `\"4000,00\"` → 4000.00M; `\"\"` → 0M."
  ^BigDecimal [s]
  (let [s (str/trim (or s ""))]
    (if (str/blank? s)
      0M
      (-> s
          (str/replace "," ".")
          (BigDecimal.)
          (.setScale 2 RoundingMode/HALF_EVEN)))))

(defn- parse-belegdatum
  "Belegdatum is DDMM (year from header). Returns a Date in UTC."
  [^String s ^long year]
  (when-not (str/blank? s)
    (let [s (str/trim s)]
      (when (and (= 4 (count s))
                 (every? #(Character/isDigit ^char %) s))
        (let [dd (Integer/parseInt (subs s 0 2))
              mm (Integer/parseInt (subs s 2 4))
              ld (LocalDate/of (int year) (int mm) (int dd))]
          (Date/from (.toInstant (.atStartOfDay ld (java.time.ZoneId/of "UTC")))))))))

;; ============================================================================
;; Header parsing
;; ============================================================================

(defn parse-header
  "Pull the load-bearing fields out of the EXTF header line.
   Returns a map: `{:client-number, :company-name, :year,
                    :period-start, :period-end}`."
  [^String header-line]
  (let [cols (split-row header-line)]
    {:format     (nth cols 0 nil)
     :schema     (nth cols 1 nil)
     :version    (nth cols 2 nil)
     :section    (nth cols 3 nil)
     :timestamp  (nth cols 5 nil)
     :company-name (nth cols 8 nil)
     :client-number (nth cols 10 nil)
     :year       (let [pe (nth cols 12 nil)]
                   (when (and pe (>= (count pe) 4))
                     (Integer/parseInt (subs pe 0 4))))
     :period-start (nth cols 12 nil)
     :period-end   (nth cols 15 nil)}))

;; ============================================================================
;; Row → posting projection
;; ============================================================================

(defn- row->posting
  "Project one EXTF data row to a flat posting map (key data only).
   Column ordinals per note 82 §3.2 / kontor.l10n-de.datev §1."
  [year cols]
  (let [umsatz   (parse-decimal (nth cols 0 ""))
        sh-kz    (str/trim (nth cols 1 ""))
        wkz      (str/trim (nth cols 2 ""))
        konto    (str/trim (nth cols 6 ""))
        gegen    (str/trim (nth cols 7 ""))
        belegdat (parse-belegdatum (nth cols 9 "") year)
        belegf1  (str/trim (nth cols 10 ""))   ; Pnr
        belegf2  (str/trim (nth cols 11 ""))   ; Period like 11/2025
        text     (parse-cell (nth cols 13 ""))
        signed   (if (= sh-kz "H") (.negate umsatz) umsatz)]
    {:amount   signed
     :sh-kz    sh-kz
     :umsatz   umsatz
     :commodity wkz
     :account-code konto
     :contra-code  gegen
     :belegdatum belegdat
     :pnr        belegf1
     :period     belegf2
     :text       text}))

;; ============================================================================
;; PayrollFact assembly (note 82 §3.4)
;; ============================================================================
;;
;; The Bruttomethode book-keeping pattern (note 82 §4.2):
;;
;;   1. Dr Gehalt (6020)            Cr Verrechnung (3790)   — gross expense
;;   2. Dr Verrechnung               Cr Verb. Lohn (3720)    — net pay liab
;;   3. Dr Verrechnung               Cr Verb. SV  (3740)     — employee SI
;;   4. Dr Soziale Aufw. (6110)      Cr Verb. SV  (3740)     — employer SI
;;   5. Dr Verrechnung               Cr Verb. LSt (3730)     — withholding
;;
;; Per fact we resolve:
;;   :gross               — sum of Dr postings against expense accounts
;;                          (Klasse 6 SKR04) that target Verrechnung
;;   :withholding-tax     — Cr posting against verb-lohnsteuer (3730)
;;   :employee-si         — Cr posting against verb-sozialversicherung
;;                          (3740) from the Verrechnung side
;;   :employer-si         — Dr posting on soziale-aufwendungen (6110)
;;   :net                 — Cr posting against verb-lohn (3720)
;;
;; Edge cases left to the consumer: Sachbezüge (4140/4145), bAV,
;; Pfändung, Kurzarbeitergeld — surfaced as raw components with
;; :kind :unmapped-postings.

(def ^:private skr04-account-kinds
  "Mapping from SKR04 Konto → :kontor.compensation-component/kind for the
   parser's structural classification."
  {"6010" :base-wage
   "6020" :base-salary
   "6035" :urlaubsrueckstellung-expense
   "6060" :imputed-income-taxable
   "6110" :employer-si
   "6130" :imputed-income-tax-exempt
   "3720" :verb-lohn
   "3730" :withholding-tax
   "3740" :employee-si
   "3790" :verrechnung
   "3791" :pfaendung})

(def ^:private skr03-account-kinds
  "Mapping from SKR03 Konto → :kontor.compensation-component/kind."
  {"4120" :base-wage
   "4124" :base-salary
   "4130" :employer-si
   "4140" :imputed-income-tax-exempt
   "4145" :imputed-income-taxable
   "4960" :urlaubsrueckstellung-expense
   "1740" :verb-lohn
   "1741" :withholding-tax
   "1755" :employee-si
   "1798" :pfaendung})

(defn account-kinds-for [coa]
  (case coa
    :skr03 skr03-account-kinds
    skr04-account-kinds))

(defn- classify
  [coa code]
  (get (account-kinds-for coa) code))

(defn- abs-bd ^BigDecimal [^BigDecimal bd] (.abs bd))

(defn expand-row-to-legs
  "Expand one DATEV-format row into a 2-leg balanced booking:
   one leg against Konto (signed +amount from the S/H perspective),
   one leg against Gegenkonto (signed -amount).

   This makes downstream classification symmetric — the parser can
   filter by Konto OR Gegenkonto-side without losing information."
  [{:keys [account-code contra-code amount belegdatum pnr period text]}]
  [{:account-code account-code
    :contra-code  contra-code
    :amount       amount
    :belegdatum   belegdatum
    :pnr          pnr
    :period       period
    :text         text
    :side         :primary}
   {:account-code contra-code
    :contra-code  account-code
    :amount       (.negate ^BigDecimal amount)
    :belegdatum   belegdatum
    :pnr          pnr
    :period       period
    :text         text
    :side         :contra}])

(defn- row-verrechnung-impact
  "Per-row signed contribution to the Verrechnungskonto balance.
   DATEV files write one row per balanced two-line booking: the
   Konto and Gegenkonto are the two halves. If Verrechnung appears
   on either side, sum its signed impact.

   Convention used here: `:amount` is already signed (positive when
   S/H = S; negative when S/H = H). When Konto = Verrechnung, the
   row contributes +amount (the Konto receives the signed value);
   when Gegenkonto = Verrechnung, the Gegen-side receives the
   opposite sign, so contribute -amount."
  [verr-code {:keys [account-code contra-code amount]}]
  (cond
    (= verr-code account-code) amount
    (= verr-code contra-code)  (.negate ^BigDecimal amount)
    :else                       0M))

(defn- assert-verrechnung-balanced!
  "Per note 82 §4.2 / §11.18 invariant: after all postings for a
   (Pnr, period) group are summed, the Verrechnungskonto must net
   to zero. Raise ex-info on violation; in single-row-per-posting
   DATEV the Verrechnung impact comes from BOTH the Konto and
   Gegenkonto sides — see `row-verrechnung-impact`."
  [coa group postings]
  (let [verr-code (get verrechnungskonto-codes coa)
        verr-sum  (reduce (fn [^BigDecimal a row]
                            (.add a ^BigDecimal (row-verrechnung-impact verr-code row)))
                          0M postings)]
    (when-not (zero? (.compareTo ^BigDecimal verr-sum 0M))
      (throw (ex-info (str "Buchungsbeleg corrupt: Verrechnungskonto "
                           verr-code " does not balance to zero for group "
                           (pr-str group))
                      {:group group
                       :verrechnungskonto verr-code
                       :verrechnungs-sum  verr-sum
                       :postings postings})))))

(defn- sum-positive-on
  "Sum the positive amounts of legs whose account-code's classified
   kind is in `kind-set`."
  [legs kinds-map kind-set]
  (->> legs
       (filter (fn [{:keys [account-code amount side]}]
                 (and (= side :primary)         ; avoid double-count
                      (pos? (.compareTo ^BigDecimal amount 0M))
                      (contains? kind-set (kinds-map account-code)))))
       (map :amount)
       (reduce (fn [^BigDecimal a ^BigDecimal v] (.add a v)) 0M)))

(defn group->payroll-fact
  "Build one PayrollFact from a vector of EXTF-projected posting maps
   sharing (:pnr, :period). Expands each row to its 2-leg balanced
   booking via `expand-row-to-legs`, asserts the Verrechnungskonto
   invariant on the original rows (note 82 §4.2), and aggregates
   amounts by SKR04/SKR03 account-class.

   Aggregation strategy (note 82 §3.4):
     - :gross — sum of Dr legs on expense accounts (Klasse 6 SKR04 /
                Klasse 4 SKR03) that classify as gross-side kinds.
     - :withholding-tax / :employee-si — sum of Cr legs on the
                respective liability accounts (3730/3740 SKR04).
     - :employer-si — sum of Dr legs on Soziale Aufwendungen
                (6110/4130).
     - :net — sum of Cr legs on Verb. Lohn (3720/1740)."
  [{:keys [coa] :or {coa :skr04}} group postings]
  (assert-verrechnung-balanced! coa group postings)
  (let [kinds-map (account-kinds-for coa)
        verr-code (get verrechnungskonto-codes coa)
        ;; The Bruttomethode pattern groups by which side of the row
        ;; the Verrechnung sits on (note 82 §4.2). Each row is a
        ;; two-line booking:
        ;;   Row { Konto, S/H, Umsatz, Gegenkonto }
        ;; with :amount already signed (positive for S, negative for H).
        ;;
        ;; Gross-expense rows: Konto is an expense (Klasse 6) AND
        ;;                     Gegenkonto = Verrechnung.
        ;; AN-Anteil rows:     Konto = Verrechnung, Gegen = verb-SV.
        ;; Net rows:           Konto = Verrechnung, Gegen = verb-lohn.
        ;; WHT rows:           Konto = Verrechnung, Gegen = verb-LSt.
        ;; AG-Anteil rows:     Konto = soziale-aufw (6110), Gegen = verb-SV.
        ;;
        ;; This binding-classifier mirrors the gross-method shape
        ;; cleanly without ambiguity on shared verb-SV / verrechnung
        ;; codes.
        gross-row? (fn [{:keys [account-code contra-code amount]}]
                     (and (= contra-code verr-code)
                          (pos? (.compareTo ^BigDecimal amount 0M))
                          (let [k (kinds-map account-code)]
                            (#{:base-wage :base-salary
                               :imputed-income-taxable
                               :imputed-income-tax-exempt} k))))
        verrechnung-cr-row? (fn [kind]
                              (fn [{:keys [account-code contra-code amount]}]
                                (and (= account-code verr-code)
                                     (= kind (kinds-map contra-code))
                                     (pos? (.compareTo ^BigDecimal amount 0M)))))
        employer-row? (fn [{:keys [account-code amount]}]
                        (and (pos? (.compareTo ^BigDecimal amount 0M))
                             (= :employer-si (kinds-map account-code))))
        sum-amount (fn [pred]
                     (->> postings
                          (filter pred)
                          (map :amount)
                          (reduce (fn [^BigDecimal a ^BigDecimal v] (.add a v)) 0M)))
        gross-rows (filterv gross-row? postings)
        gross (sum-amount gross-row?)
        net   (sum-amount (verrechnung-cr-row? :verb-lohn))
        wht   (sum-amount (verrechnung-cr-row? :withholding-tax))
        emp-si (sum-amount (verrechnung-cr-row? :employee-si))
        employer-si (sum-amount employer-row?)
        ;; The components vector reproduces the substrate's
        ;; PayrollFacts shape:
        ;;   positive employee-side       = Σ gross
        ;;   negative employee-side       = WHT + employee-si (signed)
        ;;   employer-side                = employer-si
        components
        (cond-> (mapv (fn [{:keys [account-code amount]}]
                        {:kind (or (kinds-map account-code) :base-salary)
                         :amount amount
                         :account-code account-code})
                      gross-rows)
          (pos? (.signum ^BigDecimal wht))
          (conj {:kind :withholding-tax
                 :amount (.negate ^BigDecimal wht)
                 :account-code (some #(when (= (kinds-map (:contra-code %))
                                               :withholding-tax)
                                        (:contra-code %))
                                     postings)})
          (pos? (.signum ^BigDecimal emp-si))
          (conj {:kind :employee-si
                 :amount (.negate ^BigDecimal emp-si)
                 :account-code (some #(when (= (kinds-map (:contra-code %))
                                               :employee-si)
                                        (:contra-code %))
                                     postings)})
          (pos? (.signum ^BigDecimal employer-si))
          (conj {:kind :employer-si
                 :amount employer-si
                 :employer-side? true
                 :account-code (some #(when (= (kinds-map (:account-code %))
                                               :employer-si)
                                        (:account-code %))
                                     postings)}))]
    {:employment-pnr (:pnr group)
     :pay-period    (:period group)
     :pay-period-from (when-some [d (first (keep :belegdatum postings))] d)
     :gross         gross
     :net           net
     :withholding-tax wht
     :employee-si    emp-si
     :employer-si    employer-si
     :components     components
     :raw-postings   postings
     :jurisdiction-specific-codes
     {:datev/coa coa
      :datev/pnr (:pnr group)
      :datev/period (:period group)}}))

;; ============================================================================
;; Top-level parse
;; ============================================================================

(defn parse-buchungsbeleg
  "Parse a complete EXTF Buchungsstapel string into PayrollFacts.

   Required input: `content` — the file string (decoded ISO-8859-1).
   Optional opts:
     :coa  — :skr04 (default) | :skr03

   Returns a vector of PayrollFact maps. Order: by ascending Pnr,
   then by period. Throws ex-info if any group violates the
   Verrechnungskonto zero-balance invariant."
  ([content] (parse-buchungsbeleg content {}))
  ([^String content {:keys [coa] :or {coa :skr04}}]
   (let [lines (->> (str/split-lines content)
                    (remove str/blank?))
         _ (when (< (count lines) 3)
             (throw (ex-info "Buchungsbeleg too short — need header + columns + at least 1 row"
                             {:lines (count lines)})))
         header (parse-header (first lines))
         year (:year header)
         _ (when-not year
             (throw (ex-info "Buchungsbeleg header missing fiscal year"
                             {:header header})))
         data-lines (drop 2 lines)
         postings (mapv (fn [^String line]
                          (row->posting year (split-row line)))
                        data-lines)
         groups (->> postings
                     (group-by (juxt :pnr :period))
                     (sort-by key))
         facts (mapv (fn [[[pnr period] ps]]
                       (group->payroll-fact {:coa coa}
                                            {:pnr pnr :period period}
                                            ps))
                     groups)]
     {:header header
      :facts facts})))

(defn read-buchungsbeleg-file
  "Convenience: read a Buchungsbeleg file from `path` in ISO-8859-1
   and parse. Returns the same `{:header, :facts}` map as
   `parse-buchungsbeleg`."
  ([path] (read-buchungsbeleg-file path {}))
  ([path opts]
   (parse-buchungsbeleg (slurp path :encoding extf-encoding) opts)))

;; ============================================================================
;; DatevLodasComputeProvider — implements PayrollComputeProvider
;; ============================================================================

(defrecord DatevLodasComputeProvider [opts]
  pp/PayrollComputeProvider
  (provider-id [_] :datev-lodas)
  (compute-payroll [_ {:keys [employment-eids variable-inputs]}]
    ;; The 'compute' here is parser-driven: variable-inputs is
    ;; expected to carry the Buchungsbeleg content (or pre-parsed
    ;; facts) keyed by :buchungsbeleg-content or :facts. The
    ;; orchestrator wires this from the consumer's adapter that
    ;; fetches the file from the LODAS appliance (out of kontor
    ;; scope; consumer's concern).
    (let [{:keys [coa employment-pnr->eid]
           :or {coa (:coa opts :skr04)}} opts
          {:keys [buchungsbeleg-content facts]} variable-inputs
          all-facts (cond
                      facts facts
                      buchungsbeleg-content
                      (:facts (parse-buchungsbeleg buchungsbeleg-content {:coa coa}))
                      :else
                      (throw (ex-info "DatevLodasComputeProvider needs :buchungsbeleg-content or :facts"
                                      {:variable-inputs variable-inputs})))
          ;; Filter to facts whose Pnr maps to an employment in scope.
          resolved (keep (fn [f]
                           (let [pnr (:employment-pnr f)
                                 eid (or (get employment-pnr->eid pnr)
                                         (when (and (= (count employment-eids) 1)
                                                    (or (nil? employment-pnr->eid)
                                                        (empty? employment-pnr->eid)))
                                           (first employment-eids)))]
                             (when eid
                               (assoc f :employment eid))))
                         all-facts)]
      (vec resolved))))

(defn make-provider
  "Construct a DatevLodasComputeProvider.

   Optional opts:
     :coa                  — :skr04 (default) | :skr03
     :employment-pnr->eid  — map of LODAS Personalnummer → :employment
                              eid; needed when the run covers multiple
                              employments. For a single-employment run
                              the provider auto-binds to that eid."
  ([] (make-provider {}))
  ([opts] (->DatevLodasComputeProvider opts)))
