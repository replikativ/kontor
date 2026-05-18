(ns kontor.payroll-in.esi
  "ESIC (Employees' State Insurance Corporation) — monthly ESI return
   helper.

   ## Scope (per note 79 §5.3 C9 + ADR-083)

   The Employees' State Insurance Act 1948 funds medical / sickness /
   maternity / disablement / dependants' benefits via per-employee
   monthly contributions:
     - Employee: 0.75% of gross wages
     - Employer: 3.25% of gross wages
   Applies to employees earning ≤ ₹21,000/month (consumer-supplied
   threshold).

   Filing cadence: monthly. Due by 15th of the following month via
   the ESIC portal. The return is a CSV uploaded against the
   employer's ESIC Code (10 digits).

   ## What this namespace does

   - Sums per-month ESI-payable totals (employee + employer) against
     the consumer's ESI-payable account.
   - Builds the ESIC monthly contribution CSV.
   - Builds the matching `:audit-doc` per ADR-068.

   ## What this namespace does NOT do

   - Decide ESI threshold / rate (engine / ESIC notification).
   - Bundle IP (Insured Person) Number lookups.
   - Upload to ESIC portal.
   - Generate the digital signature for upload.

   ## ESI monthly contribution file format

   The ESIC portal accepts a CSV with one row per employee per month:

     1  IP Number              10 digits (ESIC ID)
     2  IP Name                string
     3  No. of days for which  integer — days worked
        wages paid
     4  Total Monthly Wages    BigDecimal
     5  Reason code            string (optional)
     6  Last Working day       Date (only for separated employees)

   Reference: ESIC Portal contribution-upload help page (public).

   ## Posture (ADR-083)

   - No bundled IP numbers (per-employee secrets).
   - No bundled threshold / rates.
   - No bundled portal credentials."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [kontor.money :as money])
  (:import [java.math BigDecimal]
           [java.text SimpleDateFormat]
           [java.util Calendar Date Locale TimeZone]))

(def ^:private ^TimeZone utc-tz (TimeZone/getTimeZone "UTC"))

;; ============================================================================
;; Month / period helpers (shared with pf.clj — re-used because the
;; ESI / PF / PT filing cadences share the same monthly bounds shape)
;; ============================================================================

(defn month-bounds
  "Return [start-inclusive end-exclusive] Date pair for (year, month);
   month 1-indexed."
  [year month]
  (let [cal (java.util.Calendar/getInstance utc-tz)
        _ (.clear cal)
        _ (.set cal Calendar/YEAR year)
        _ (.set cal Calendar/MONTH (dec month))
        _ (.set cal Calendar/DAY_OF_MONTH 1)
        start (.getTime cal)
        _ (.add cal Calendar/MONTH 1)
        end (.getTime cal)]
    [start end]))

(defn- fmt-date-dmy
  ^String [^Date d]
  (let [fmt (doto (SimpleDateFormat. "dd/MM/yyyy" Locale/US)
              (.setTimeZone utc-tz))]
    (.format fmt d)))

;; ============================================================================
;; ESI aggregation
;; ============================================================================

(defn- sum-postings-by-tag
  [db {:keys [tag period-start period-end ip-code-tag]}]
  (let [q '[:find ?p ?amount
            :in $ ?tag ?start ?end
            :where
            [?acct :account/tags ?at]
            [?at :account-tag/name ?tag]
            [?p :posting/account ?acct]
            [?p :posting/amount ?amount]
            [?p :posting/transaction ?tx]
            [?tx :transaction/effective-date ?ed]
            [(.before ^java.util.Date ?ed ?end)]
            [(.after ^java.util.Date ?ed ?start)]]
        q-with-ip '[:find ?p ?amount
                    :in $ ?tag ?ip ?start ?end
                    :where
                    [?acct :account/tags ?at]
                    [?at :account-tag/name ?tag]
                    [?p :posting/account ?acct]
                    [?p :posting/amount ?amount]
                    [?p :posting/account-tags ?rt]
                    [?rt :account-tag/name ?ip]
                    [?p :posting/transaction ?tx]
                    [?tx :transaction/effective-date ?ed]
                    [(.before ^java.util.Date ?ed ?end)]
                    [(.after ^java.util.Date ?ed ?start)]]
        rows (if (and ip-code-tag (not (str/blank? ip-code-tag)))
               (d/q q-with-ip db (name tag) ip-code-tag period-start period-end)
               (d/q q db (name tag) period-start period-end))
        total (reduce (fn [^BigDecimal a [_ ^BigDecimal v]]
                        (.add a v))
                      0M rows)]
    (.abs ^BigDecimal total)))

(defn monthly-esi-summary
  "Compute the per-month ESI-payable total (employee 0.75% +
   employer 3.25%). Returns a map suitable for the CSV + audit-doc.

   Required opts:
     :year   integer
     :month  integer 1..12

   Optional opts:
     :ip-code-tag   string — filter postings to a per-establishment
                              routing tag (typically the ESIC code).
     :as-of-tx :as-of-valid

   Returns:
     {:year :month :period-start :period-end
      :esi-total Money :ip-code-tag}"
  [conn {:keys [year month ip-code-tag as-of-tx as-of-valid]}]
  (when-not year  (throw (ex-info ":year required" {})))
  (when-not month (throw (ex-info ":month required (1..12)" {})))
  (let [[period-start period-end] (month-bounds year month)
        db (cond-> (d/db conn)
             as-of-tx    (d/as-of as-of-tx)
             as-of-valid (d/valid-at as-of-valid))
        esi (sum-postings-by-tag
             db {:tag :in-payroll-esi-payable
                 :period-start period-start
                 :period-end period-end
                 :ip-code-tag ip-code-tag})]
    {:year year
     :month month
     :period-start period-start
     :period-end period-end
     :esi-total (money/money esi :INR)
     :ip-code-tag ip-code-tag}))

;; ============================================================================
;; ESIC CSV emitter
;; ============================================================================

(defn esic-row
  "Render one ESIC monthly-contribution CSV line for an employee.

   Required:
     :ip-number    10-digit ESIC employee number (string)
     :ip-name      string
     :days-paid    integer
     :monthly-wages BigDecimal — total monthly wages (gross before
                                 deductions; the ESIC portal computes
                                 0.75% + 3.25% from this)
   Optional:
     :reason-code  string — '0' (active), '5' (separated), …
     :last-working-day Date — only when reason-code indicates separation"
  [{:keys [ip-number ip-name days-paid monthly-wages reason-code
           last-working-day]
    :or {reason-code "0"}}]
  ;; ESIC portal accepts CSV — fields delimited by commas, quoted
  ;; when containing commas (member names rarely do, but tolerated).
  (str ip-number ","
       (if (str/includes? (or ip-name "") ",")
         (str "\"" ip-name "\"") ip-name) ","
       days-paid ","
       monthly-wages ","
       reason-code ","
       (some-> last-working-day fmt-date-dmy)))

(defn esic-csv
  "Render the complete ESIC monthly-contribution CSV payload.

   `rows` is a vector of per-employee maps shaped per `esic-row`.
   Header line is OPTIONAL on the ESIC portal — defaults to including
   it for human-readability; pass `:include-header? false` to omit.

   Returns a string with CR-LF line terminators."
  ([rows] (esic-csv rows {}))
  ([rows {:keys [include-header?] :or {include-header? true}}]
   (let [header "IP Number,IP Name,No. of Days,Total Monthly Wages,Reason Code,Last Working Day"
         body (mapv esic-row rows)
         lines (if include-header? (cons header body) body)]
     (str/join "\r\n" lines))))

;; ============================================================================
;; ESI audit-doc tx-data
;; ============================================================================

(defn esi-audit-doc-tx-data
  "Build an :audit-doc tx-data fragment recording the monthly ESI
   submission. Carries :audit-doc/category :payroll-filing +
   :audit-doc/language :en-in per ADR-083."
  [{:keys [esi-summary esic-code language]
    :or {language :en-in}}]
  (let [{:keys [year month esi-total period-start period-end
                ip-code-tag]} esi-summary
        code (or esic-code ip-code-tag "ALL-ESIC")
        title (format "ESIC monthly return — %s, %04d-%02d" code year month)
        desc (format "ESI payable (EE 0.75%% + ER 3.25%%): %s | Period %s..%s"
                     (:amount esi-total) (str period-start) (str period-end))]
    [{:audit-doc/code (str "ESIC-" code "-" year "-" (format "%02d" month))
      :audit-doc/type :regulator-clearance
      :audit-doc/title title
      :audit-doc/description desc
      :audit-doc/uploaded-at (java.util.Date.)
      :audit-doc/category :payroll-filing
      :audit-doc/language language}]))

;; ============================================================================
;; build-esi-submission — end-to-end
;; ============================================================================

(defn build-esi-submission
  "End-to-end monthly ESIC submission builder.

   Required opts:
     :conn / :db
     :year :month
     :esic-code   string — the consumer's ESIC employer code (10 digits)
     :rows        vector of per-employee maps (see esic-row)

   Optional opts:
     :ip-code-tag   filter the kontor-side summary
     :language
     :include-header? — CSV header line (default true)

   Returns:
     {:summary    monthly-esi-summary
      :csv-text   the CSV payload string
      :audit-doc-tx-data the [{...}] fragment}"
  [conn {:keys [year month esic-code rows ip-code-tag language
                include-header?]
         :or {language :en-in include-header? true}}]
  (when-not esic-code
    (throw (ex-info ":esic-code required" {})))
  (when-not rows
    (throw (ex-info ":rows required" {})))
  (let [summary (monthly-esi-summary
                 conn {:year year :month month
                       :ip-code-tag ip-code-tag})
        csv-text (esic-csv rows {:include-header? include-header?})
        audit (esi-audit-doc-tx-data
               {:esi-summary summary
                :esic-code esic-code
                :language language})]
    {:summary summary
     :csv-text csv-text
     :audit-doc-tx-data audit}))
