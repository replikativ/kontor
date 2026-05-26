(ns kontor.payroll-in.tds
  "TDS on salary (Section 192 of Income Tax Act 1961) — Form 24Q
   quarterly e-TDS return helper.

   ## Scope (per note 79 §5.3 C9 + ADR-083)

   The buyer (employer in the salary case) deducts tax at source under
   Section 192 from each salary payment and remits to the government
   via challan ITNS 281 by the 7th of the following month (30th April
   for March deductions). Quarterly, the buyer files Form 24Q
   (statement of TDS on salary) with NSDL / Protean via the e-TDS FVU
   (File Validation Utility) format.

   ## What this namespace does

   - Sums per-quarter TDS posted against the consumer-supplied
     TDS-payable account, optionally filtered by deductor TAN.
   - Builds an `:audit-doc` tx-data fragment recording the quarterly
     summary (one per deductor-TAN × FY × quarter).
   - Emits a Form-24Q FVU-text-format payload (4 record types — File
     Header, Batch Header, Challan Detail, Deductee Detail — per the
     publicly documented NSDL e-TDS RPU specification).

   ## What this namespace does NOT do

   - Decide TDS slabs / surcharge / cess rates (engine / Section 192
     mathematics) — the engine deducts; kontor records.
   - Run the actual FVU.exe validator (proprietary Java tool from
     NSDL / Protean — out of scope; consumer runs it locally before
     uploading).
   - Upload to TRACES / e-filing portal (consumer holds credentials).
   - Bundle TDS rate slabs (Finance Act updates them annually).

   ## Form 24Q FVU format (NSDL e-TDS RPU spec, public)

   Pipe (`|`)-delimited fixed-record-type text file. Each line is one
   record; the first field is the record type number:

     1 — File Header (one per file)
     2 — Batch Header (one per Form-24Q quarter)
     3 — Challan Detail (one per challan / ITNS 281 paid)
     4 — Deductee Detail (one per employee per challan)

   The structure documented here is the *minimum viable* superset
   sufficient for round-trip; consumers with custom-needs override
   the field-extension hook on the helper.

   Reference: NSDL e-TDS / e-TCS RPU 4.x documentation (publicly
   available on the Protean / NSDL website). License: public spec,
   non-copyrightable factual data.

   ## Posture (ADR-083)

   - No TAN / PAN credentials bundled.
   - No bundled FVU.exe Java runtime.
   - No bundled rate slabs (Finance Act updates them annually).
   - Pure tx-data + pure-text emitter."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [kontor.money :as money])
  (:import [java.math BigDecimal]
           [java.text SimpleDateFormat]
           [java.util Date Locale TimeZone]))

(def ^:private ^TimeZone utc-tz (TimeZone/getTimeZone "UTC"))

;; ============================================================================
;; Date / quarter helpers (IN FY is April-March)
;; ============================================================================

(defn fy-of
  "Return the IN financial-year integer that contains the given Date.
   E.g. 2026-05-01 → 2026 (FY 2026-27 starting 2026-04-01).
        2026-03-31 → 2025 (FY 2025-26).

   IN fiscal year runs 1-April to 31-March; this matches the existing
   `kontor.l10n-in/closing.clj` convention."
  [^Date d]
  (let [cal (doto (java.util.Calendar/getInstance utc-tz)
              (.setTime d))
        year (.get cal java.util.Calendar/YEAR)
        month (.get cal java.util.Calendar/MONTH)] ; 0-indexed
    (if (< month 3)            ; Jan/Feb/Mar = previous FY
      (dec year)
      year)))

(defn quarter-of
  "Return the Form 24Q quarter integer (1..4) for a given Date, where
   Q1 = April-June, Q2 = July-Sep, Q3 = Oct-Dec, Q4 = Jan-March."
  [^Date d]
  (let [cal (doto (java.util.Calendar/getInstance utc-tz)
              (.setTime d))
        month (.get cal java.util.Calendar/MONTH)]
    (case month
      (3 4 5)   1     ; Apr May Jun
      (6 7 8)   2     ; Jul Aug Sep
      (9 10 11) 3     ; Oct Nov Dec
      (0 1 2)   4)))  ; Jan Feb Mar

(defn quarter-bounds
  "Return [start-inclusive end-exclusive] Dates for FY + quarter.
   FY is the calendar year of April-1.

   Examples:
     (quarter-bounds 2026 1) → [#inst \"2026-04-01\" #inst \"2026-07-01\"]
     (quarter-bounds 2026 4) → [#inst \"2027-01-01\" #inst \"2027-04-01\"]"
  [fy quarter]
  (let [[start-month start-year-offset
         end-month   end-year-offset]
        (case quarter
          1 [4 0  7 0]                 ; Apr..Jul
          2 [7 0  10 0]                ; Jul..Oct
          3 [10 0 1 1]                 ; Oct..Jan(+1y)
          4 [1 1  4 1])                ; Jan(+1y)..Apr(+1y)
        cal (java.util.Calendar/getInstance utc-tz)
        mk (fn [^long m ^long off]
             (.clear cal)
             (.set cal java.util.Calendar/YEAR (int (+ fy off)))
             (.set cal java.util.Calendar/MONTH (int (dec m)))
             (.set cal java.util.Calendar/DAY_OF_MONTH 1)
             (.set cal java.util.Calendar/HOUR_OF_DAY 0)
             (.set cal java.util.Calendar/MINUTE 0)
             (.set cal java.util.Calendar/SECOND 0)
             (.set cal java.util.Calendar/MILLISECOND 0)
             (.getTime cal))]
    [(mk start-month start-year-offset)
     (mk end-month end-year-offset)]))

(defn- fmt-date-iso
  "DDMMYYYY — the FVU date format. Returns a 8-char string. Renders
   in UTC so #inst dates round-trip cleanly across timezones."
  ^String [^Date d]
  (let [fmt (doto (SimpleDateFormat. "ddMMyyyy" Locale/US)
              (.setTimeZone utc-tz))]
    (.format fmt d)))

;; ============================================================================
;; TDS aggregation
;; ============================================================================

(defn- sum-postings-by-tag
  "Sum (as positive numbers) the absolute amount of TDS-payable
   postings in [period-start, period-end), optionally filtered by an
   account-tag-name applied via :kontor.posting/account-tags."
  [db {:keys [tag period-start period-end tan-account-tag]}]
  (let [q '[:find ?p ?amount
            :in $ ?tag ?start ?end
            :where
            [?acct :kontor.account/tags ?at]
            [?at :kontor.account-tag/name ?tag]
            [?p :kontor.posting/account ?acct]
            [?p :kontor.posting/amount ?amount]
            [?p :kontor.posting/transaction ?tx]
            [?tx :kontor.transaction/effective-date ?ed]
            [(.before ^java.util.Date ?ed ?end)]
            [(.after ^java.util.Date ?ed ?start)]]
        q-with-tan '[:find ?p ?amount
                     :in $ ?tag ?tan ?start ?end
                     :where
                     [?acct :kontor.account/tags ?at]
                     [?at :kontor.account-tag/name ?tag]
                     [?p :kontor.posting/account ?acct]
                     [?p :kontor.posting/amount ?amount]
                     [?p :kontor.posting/account-tags ?rt]
                     [?rt :kontor.account-tag/name ?tan]
                     [?p :kontor.posting/transaction ?tx]
                     [?tx :kontor.transaction/effective-date ?ed]
                     [(.before ^java.util.Date ?ed ?end)]
                     [(.after ^java.util.Date ?ed ?start)]]
        rows (if (and tan-account-tag (not (str/blank? tan-account-tag)))
               (d/q q-with-tan db (name tag) tan-account-tag period-start period-end)
               (d/q q db (name tag) period-start period-end))
        total (reduce (fn [^BigDecimal a [_ ^BigDecimal v]]
                        (.add a v))
                      0M rows)]
    (.abs ^BigDecimal total)))

(defn quarterly-tds-summary
  "Compute the per-quarter TDS-on-salary total. Returns a map suitable
   for both a Form-24Q payload and an :audit-doc audit row.

   Required opts:
     :fy            integer (IN financial year — calendar year of Apr-1)
     :quarter       integer 1..4

   Optional opts:
     :tan-account-tag string — when supplied, filters postings to
                               those tagged with the TAN's routing tag
                               (mirrors CA RP routing per note 84 §4)
     :as-of-tx :as-of-valid    bitemporal toggles, default now/now

   Returns:
     {:fy :quarter :period-start :period-end
      :tds Money :tan-account-tag}"
  [conn {:keys [fy quarter tan-account-tag as-of-tx as-of-valid]}]
  (when-not fy      (throw (ex-info ":fy required" {})))
  (when-not quarter (throw (ex-info ":quarter required" {})))
  (let [[period-start period-end] (quarter-bounds fy quarter)
        db (cond-> (d/db conn)
             as-of-tx    (d/as-of as-of-tx)
             as-of-valid (d/valid-at as-of-valid))
        tds (sum-postings-by-tag
             db {:tag :in-payroll-tds-payable
                 :period-start period-start
                 :period-end period-end
                 :tan-account-tag tan-account-tag})]
    {:fy fy
     :quarter quarter
     :period-start period-start
     :period-end period-end
     :tds (money/money tds :INR)
     :tan-account-tag tan-account-tag}))

;; ============================================================================
;; tds-audit-doc-tx-data (ADR-068 builder)
;; ============================================================================

(defn tds-audit-doc-tx-data
  "Build an :audit-doc tx-data fragment recording the Form-24Q
   quarterly TDS summary. Consumer transacts via
   transact-with-validation. Each :audit-doc carries:

     :kontor.audit-doc/code      deterministic from TAN + FY + quarter
     :kontor.audit-doc/category  :payroll-filing
     :kontor.audit-doc/language  :en-in (default; per ADR-083 + CLAUDE.md)
     :kontor.audit-doc/title     'Form 24Q TDS — TAN, FY YYYY-YY, Q[1..4]'
     :kontor.audit-doc/description human-readable summary

   Per note 79 §5.3 + ADR-083 — :kontor.audit-doc/language is :en-in (not
   bare :en) so the three-axis (privilege × category × language)
   filter can distinguish IN from US/UK English correspondence."
  [{:keys [tds-summary language]
    :or {language :en-in}}]
  (let [{:keys [fy quarter tds period-start period-end tan-account-tag]} tds-summary
        tan (or tan-account-tag "ALL-TANS")
        title (format "Form 24Q TDS — %s, FY %d-%02d, Q%d"
                      tan fy (mod (inc fy) 100) quarter)
        desc (format "TDS deducted (Sec 192): %s | Period %s..%s"
                     (:amount tds) (str period-start) (str period-end))]
    [{:kontor.audit-doc/code (str "FORM-24Q-" tan "-" fy "-Q" quarter)
      :kontor.audit-doc/type :regulator-clearance
      :kontor.audit-doc/title title
      :kontor.audit-doc/description desc
      :kontor.audit-doc/uploaded-at (java.util.Date.)
      :kontor.audit-doc/category :payroll-filing
      :kontor.audit-doc/language language}]))

;; ============================================================================
;; Form 24Q FVU emitter
;; ============================================================================
;;
;; The FVU text format is pipe-delimited, fixed record types. Each
;; line is one record; first field is the record-type integer. The
;; spec below is a MINIMUM VIABLE shape (the FVU is permissive — most
;; fields are positional; consumer's RPU does the field-level lint).
;;
;; Per the publicly documented NSDL e-TDS RPU 4.x specification:
;;   Record 1 — File Header
;;   Record 2 — Batch Header (one per Form-24Q quarter submission)
;;   Record 3 — Challan Detail (one per ITNS 281 paid)
;;   Record 4 — Deductee Detail (one per employee per challan)

(defn- pipe-row
  "Render one FVU record. Nil → empty field. Pipe-delimited."
  ^String [fields]
  (->> fields
       (map (fn [f] (if (nil? f) "" (str f))))
       (str/join "|")))

(defn file-header-row
  "Record type 1 — File Header.

   Required opts:
     :record-count       integer — total records in the file (cap)
     :file-creation-date Date
     :rpu-version        string (e.g. '4.7')
     :fvu-version        string (e.g. '8.2')
     :sam-version        string (e.g. '1.0' — schema accessor map)
   Optional:
     :nature-of-correction nil (for original) or one of NSDL codes"
  [{:keys [record-count file-creation-date rpu-version fvu-version
           sam-version nature-of-correction]}]
  (pipe-row [1                              ; record-type
             "H"                            ; header indicator
             record-count
             "NSDL"                         ; uploader (consumer overrides)
             (some-> file-creation-date fmt-date-iso)
             rpu-version
             fvu-version
             sam-version
             (or nature-of-correction "")]))

(defn batch-header-row
  "Record type 2 — Batch Header (per Form-24Q quarter).

   Required opts:
     :batch-number      integer (1 in single-batch file)
     :challan-count     integer
     :deductee-count    integer
     :form-no           '24Q'
     :tan               deductor's TAN (10 chars: 4 letters + 5 digits + 1 letter)
     :pan               deductor's PAN (10 chars)
     :fy                integer (e.g. 2026 for FY 2026-27)
     :quarter           integer 1..4
     :prev-rrr-no       prior receipt-number for correction filings; nil for original
     :deductor-name     string
     :deductor-address1 string
     :deductor-pin      string (6 digits)
     :deductor-state    ISO-3166-2:IN code (e.g. 'IN-MH')
     :responsible-person {:name :pan :designation :address :pin :state}
     :statement-type    'O' (original) | 'C' (correction) | 'X' (cancellation)

   FVU validates field widths + cross-record consistency; the
   consumer's RPU.exe / FVU.exe does the final lint before upload."
  [{:keys [batch-number challan-count deductee-count form-no tan pan
           fy quarter prev-rrr-no deductor-name deductor-address1
           deductor-pin deductor-state responsible-person
           statement-type]}]
  (let [rp (or responsible-person {})]
    (pipe-row [2                            ; record-type
               batch-number
               challan-count
               deductee-count
               (or form-no "24Q")
               tan
               pan
               fy
               (str "Q" quarter)
               (or statement-type "O")
               (or prev-rrr-no "")
               deductor-name
               deductor-address1
               deductor-pin
               deductor-state
               (:name rp)
               (:pan rp)
               (:designation rp)
               (:address rp)
               (:pin rp)
               (:state rp)])))

(defn challan-row
  "Record type 3 — Challan Detail (one per ITNS 281 paid in the
   quarter).

   Required opts:
     :seq-no             1-based integer within the batch
     :total-tds          BigDecimal — total TDS on this challan
     :total-interest     BigDecimal — interest u/s 201(1A); default 0
     :total-fee          BigDecimal — late-filing fee u/s 234E; default 0
     :total-other        BigDecimal — penalty / other; default 0
     :total-amount       BigDecimal — sum of the four
     :challan-date       Date — date paid to bank
     :bsr-code           string (7 digits) — bank-branch BSR code
     :challan-serial     string (5 digits) — challan serial within BSR/day
     :deductee-count     integer — number of deductee records linked
     :section            string — '92A' (regular salary) | '92B' (lump-sum) | '92C' (pension)
     :nature-of-payment  string — '00' (book entry) / '01' (challan) — per NSDL spec"
  [{:keys [seq-no total-tds total-interest total-fee total-other
           total-amount challan-date bsr-code challan-serial
           deductee-count section nature-of-payment]
    :or {total-interest 0M total-fee 0M total-other 0M
         section "92A" nature-of-payment "01"}}]
  (pipe-row [3                              ; record-type
             seq-no
             total-tds
             total-interest
             total-fee
             total-other
             (or total-amount
                 (.add (.add (.add ^BigDecimal total-tds
                                   ^BigDecimal total-interest)
                             ^BigDecimal total-fee)
                       ^BigDecimal total-other))
             (some-> challan-date fmt-date-iso)
             bsr-code
             challan-serial
             deductee-count
             section
             nature-of-payment]))

(defn deductee-row
  "Record type 4 — Deductee Detail (one per employee per challan).

   Required opts:
     :seq-no             1-based integer within the challan
     :challan-seq        the parent challan's :seq-no
     :pan                deductee's PAN
     :name               deductee name
     :amount-paid        BigDecimal — total taxable pay this challan
     :tax-deducted       BigDecimal — TDS for this deductee
     :tds-deposit-date   Date — when TDS was actually deposited
     :section            inherited from challan (default '92A')
   Optional:
     :surcharge          BigDecimal
     :education-cess     BigDecimal  ; HEC under sec 192 4% (since FY 2018-19)
     :remarks            string — 'A' (lower deduction certificate)
                                | 'B' (no PAN — 20% rate)
                                | 'C' (deductor higher-rate)
                                | 'T' (no deduction transporter)"
  [{:keys [seq-no challan-seq pan name amount-paid tax-deducted
           tds-deposit-date section surcharge education-cess remarks]
    :or {section "92A" surcharge 0M education-cess 0M remarks ""}}]
  (pipe-row [4                              ; record-type
             seq-no
             challan-seq
             pan
             name
             amount-paid
             tax-deducted
             surcharge
             education-cess
             (some-> tds-deposit-date fmt-date-iso)
             section
             remarks]))

(defn form-24q-fvu
  "Render a complete Form 24Q FVU text payload for ONE quarter.

   `opts` is a map:
     {:file-header {...}
      :batch-header {...}
      :challans [{...challan...
                  :deductees [{...deductee...}]} ...]}

   Returns a string with CR-LF line terminators (the spec) ready to
   write to a .txt file the consumer feeds to FVU.exe.

   Note 86 P2-86-1 idempotency: re-rendering the same logical-input
   map produces byte-identical output (no `(System/currentTimeMillis)`
   or non-deterministic operations during emit). The
   `:file-header/:file-creation-date` is consumer-supplied — we never
   read the clock."
  [{:keys [file-header batch-header challans]}]
  (let [fh (file-header-row file-header)
        bh (batch-header-row batch-header)
        challan-lines
        (mapcat (fn [{:keys [deductees] :as ch}]
                  (cons (challan-row (assoc ch :deductee-count
                                            (count deductees)))
                        (mapv (fn [d]
                                (deductee-row
                                 (assoc d :challan-seq (:seq-no ch))))
                              deductees)))
                challans)
        all-rows (cons fh (cons bh challan-lines))]
    (str/join "\r\n" all-rows)))

(defn build-form-24q-submission
  "End-to-end Form-24Q quarterly submission builder. Combines:
     - per-quarter TDS summary (database query)
     - the FVU text payload (the .txt the consumer feeds to FVU.exe)
     - the :audit-doc tx-data the consumer transacts

   Required opts:
     :conn / :db        the database
     :fy :quarter
     :deductor          {:tan :pan :name :address1 :pin :state}
     :responsible-person {:name :pan :designation :address :pin :state}
     :challans          consumer-supplied vector — kontor doesn't infer
                        per-challan splits; the engine emits one
                        challan per ITNS 281 paid in the quarter, and
                        the consumer fills in :deductees per challan
                        from their own engine
     :file-creation-date Date — when this file is generated by consumer
     :rpu-version :fvu-version :sam-version

   Optional opts:
     :tan-account-tag   filter the kontor-side summary by TAN
     :language          :en-in (default), :hi-in, etc.

   Returns:
     {:summary        the quarterly-tds-summary map
      :fvu-text       the .txt payload string
      :audit-doc-tx-data the [{...}] :audit-doc fragment
      :rrr-placeholder true}   ; consumer fills the Receipt Reference Number
                                ; after upload + posts an update tx"
  [conn {:keys [fy quarter deductor responsible-person challans
                file-creation-date rpu-version fvu-version sam-version
                tan-account-tag language statement-type prev-rrr-no]
         :or {rpu-version "4.7"
              fvu-version "8.2"
              sam-version "1.0"
              statement-type "O"
              language :en-in}}]
  (when-not deductor (throw (ex-info ":deductor required" {})))
  (when-not responsible-person
    (throw (ex-info ":responsible-person required" {})))
  (when-not file-creation-date
    (throw (ex-info ":file-creation-date required" {})))
  (let [summary (quarterly-tds-summary
                 conn {:fy fy :quarter quarter
                       :tan-account-tag tan-account-tag})
        challan-count (count challans)
        deductee-count (reduce + 0 (map (comp count :deductees) challans))
        record-count (+ 2 challan-count deductee-count)
        file-header {:record-count record-count
                     :file-creation-date file-creation-date
                     :rpu-version rpu-version
                     :fvu-version fvu-version
                     :sam-version sam-version}
        batch-header (merge {:batch-number 1
                             :challan-count challan-count
                             :deductee-count deductee-count
                             :form-no "24Q"
                             :tan (:tan deductor)
                             :pan (:pan deductor)
                             :fy fy
                             :quarter quarter
                             :prev-rrr-no prev-rrr-no
                             :deductor-name (:name deductor)
                             :deductor-address1 (:address1 deductor)
                             :deductor-pin (:pin deductor)
                             :deductor-state (:state deductor)
                             :responsible-person responsible-person
                             :statement-type statement-type})
        fvu (form-24q-fvu {:file-header file-header
                           :batch-header batch-header
                           :challans (mapv (fn [i ch]
                                             (assoc ch :seq-no (inc i)))
                                           (range) challans)})
        audit (tds-audit-doc-tx-data {:tds-summary summary
                                      :language language})]
    {:summary summary
     :fvu-text fvu
     :audit-doc-tx-data audit
     :rrr-placeholder true}))
