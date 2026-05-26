(ns kontor.payroll-in.pf
  "EPFO Electronic Challan-cum-Return (ECR) — monthly PF return helper.

   ## Scope (per note 79 §5.3 C9 + ADR-083)

   The Employees' Provident Fund Organisation (EPFO) collects monthly
   contributions:
     - Employee: 12% of basic+DA up to wage ceiling (₹15,000/mo as of
       2026 — consumer-supplied)
     - Employer: 12% (split EPS 8.33% capped at ceiling-basis + EPF
       3.67% on remaining) + EDLI 0.5% on capped basis
   Filed via the **ECR** electronic challan-cum-return on the EPFO
   Unified Portal — a tab-delimited text file uploaded against the
   employer's PF Establishment Code.

   Filing cadence: monthly. Due by 15th of the following month (with
   ECR + payment together; the prior 'two-step' return + challan is
   superseded by ECR per the 2014 EPFO notification).

   ## What this namespace does

   - Sums per-month PF-payable totals (employee + employer + EDLI)
     against the consumer's PF-payable account.
   - Builds the ECR text payload (tab-delimited per-employee rows).
   - Builds the matching `:audit-doc` per ADR-068.

   ## What this namespace does NOT do

   - Decide PF wage ceiling / contribution split (engine / EPFO
     notification mathematics).
   - Bundle UAN (Universal Account Number) lookups.
   - Upload to EPFO portal (consumer holds credentials).
   - Generate the digital signature for upload (DSC-on-USB-token
     workflow — out of scope).

   ## ECR format (publicly documented on the EPFO Unified Portal)

   Tab-delimited text file. One row per employee per month. Columns:

     1  UAN                 12 digits — employee's Universal Account No.
     2  Member name         50 chars
     3  Gross wages         BigDecimal
     4  EPF wages           BigDecimal — basis for 12% employee + 3.67% employer
     5  EPS wages           BigDecimal — basis for 8.33% employer EPS (capped)
     6  EDLI wages          BigDecimal — basis for 0.5% employer EDLI (capped)
     7  EPF contrib EE      BigDecimal — 12% of EPF wages
     8  EPS contrib ER      BigDecimal — 8.33% of EPS wages
     9  EPF contrib ER      BigDecimal — 3.67% of (EPF wages - EPS wages)
                                          OR computed by EPFO from cols 7/8
     10 NCP days            integer — non-contributing-period days
     11 Refund of advances  BigDecimal — typically 0

   Reference: EPFO Unified Portal ECR file format help-page (public).
   Consumer's payroll engine produces the per-employee rows; kontor
   composes them into the ECR text + builds the audit-doc.

   ## Posture (ADR-083)

   - No bundled UAN data (per-employee secrets).
   - No bundled wage ceiling (EPFO updates it via notification).
   - No bundled DSC (PFX file is consumer-controlled)."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [kontor.money :as money])
  (:import [java.math BigDecimal]
           [java.util Calendar TimeZone]))

(def ^:private ^TimeZone utc-tz (TimeZone/getTimeZone "UTC"))

;; ============================================================================
;; Month / period helpers
;; ============================================================================

(defn month-bounds
  "Return [start-inclusive end-exclusive] Date pair for the given
   (year, month) — month is 1-indexed (1 = January, 12 = December)."
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

;; ============================================================================
;; PF aggregation
;; ============================================================================

(defn- sum-postings-by-tag
  "Sum absolute amount of postings against accounts carrying a tag,
   within [period-start, period-end). Optional :establishment-code-tag
   filters by the consumer's per-PF-establishment tag-name."
  [db {:keys [tag period-start period-end establishment-code-tag]}]
  (let [q '[:find ?p ?amount
            :in $ ?tag ?start ?end
            :where
            [?acct :kontor.account/tags ?at]
            [?at :kontor.account-tag/name ?tag]
            [?p :posting/account ?acct]
            [?p :posting/amount ?amount]
            [?p :posting/transaction ?tx]
            [?tx :transaction/effective-date ?ed]
            [(.before ^java.util.Date ?ed ?end)]
            [(.after ^java.util.Date ?ed ?start)]]
        q-with-est '[:find ?p ?amount
                     :in $ ?tag ?est ?start ?end
                     :where
                     [?acct :kontor.account/tags ?at]
                     [?at :kontor.account-tag/name ?tag]
                     [?p :posting/account ?acct]
                     [?p :posting/amount ?amount]
                     [?p :posting/account-tags ?rt]
                     [?rt :kontor.account-tag/name ?est]
                     [?p :posting/transaction ?tx]
                     [?tx :transaction/effective-date ?ed]
                     [(.before ^java.util.Date ?ed ?end)]
                     [(.after ^java.util.Date ?ed ?start)]]
        rows (if (and establishment-code-tag (not (str/blank? establishment-code-tag)))
               (d/q q-with-est db (name tag) establishment-code-tag period-start period-end)
               (d/q q db (name tag) period-start period-end))
        total (reduce (fn [^BigDecimal a [_ ^BigDecimal v]]
                        (.add a v))
                      0M rows)]
    (.abs ^BigDecimal total)))

(defn monthly-pf-summary
  "Compute the per-month PF-payable total (employee 12% + employer 12%
   + EDLI 0.5%). Returns a map suitable for the ECR + audit-doc.

   Required opts:
     :year   integer (e.g. 2026)
     :month  integer 1..12 (1 = January; payroll for Apr lands here as
             month 4 — IN's PF cadence is straight calendar month,
             NOT the FY-aligned April-March quarter the TDS uses)

   Optional opts:
     :establishment-code-tag string  — filter by an establishment
                                       routing tag (consumer
                                       installs per their PF code).
     :as-of-tx :as-of-valid

   Returns:
     {:year :month :period-start :period-end
      :pf-total Money
      :establishment-code-tag}"
  [conn {:keys [year month establishment-code-tag as-of-tx as-of-valid]}]
  (when-not year  (throw (ex-info ":year required" {})))
  (when-not month (throw (ex-info ":month required (1..12)" {})))
  (let [[period-start period-end] (month-bounds year month)
        db (cond-> (d/db conn)
             as-of-tx    (d/as-of as-of-tx)
             as-of-valid (d/valid-at as-of-valid))
        pf (sum-postings-by-tag
            db {:tag :in-payroll-pf-payable
                :period-start period-start
                :period-end period-end
                :establishment-code-tag establishment-code-tag})]
    {:year year
     :month month
     :period-start period-start
     :period-end period-end
     :pf-total (money/money pf :INR)
     :establishment-code-tag establishment-code-tag}))

;; ============================================================================
;; ECR text emitter
;; ============================================================================
;;
;; Tab-delimited; one row per employee per month. The consumer's
;; payroll engine produces the per-row values (UAN, member name, EPF
;; wages, etc.) — kontor just composes them into the ECR text + builds
;; the audit-doc.

(defn- tab-row
  ^String [fields]
  (->> fields
       (map (fn [f] (if (nil? f) "" (str f))))
       (str/join "\t")))

(def ecr-columns
  "Ordered column names for the ECR. Consumer-supplied per-employee
   maps are read against these keys."
  [:uan :member-name :gross-wages :epf-wages :eps-wages :edli-wages
   :epf-contrib-ee :eps-contrib-er :epf-contrib-er :ncp-days
   :refund-of-advances])

(defn ecr-row
  "Build one tab-delimited ECR line for an employee."
  [{:keys [uan member-name gross-wages epf-wages eps-wages edli-wages
           epf-contrib-ee eps-contrib-er epf-contrib-er ncp-days
           refund-of-advances]
    :or {ncp-days 0 refund-of-advances 0M}}]
  (tab-row [uan
            member-name
            gross-wages
            epf-wages
            eps-wages
            edli-wages
            epf-contrib-ee
            eps-contrib-er
            epf-contrib-er
            ncp-days
            refund-of-advances]))

(defn ecr-text
  "Render the complete ECR text payload for ONE PF month.

   `rows` is a vector of per-employee maps shaped per `ecr-row`'s
   docstring. The function emits CR-LF line terminators (the EPFO
   spec convention for upload).

   Per note 86 P2-86-1 idempotency: re-rendering the same logical
   input produces byte-identical output."
  [rows]
  (str/join "\r\n" (mapv ecr-row rows)))

;; ============================================================================
;; ECR audit-doc tx-data
;; ============================================================================

(defn ecr-audit-doc-tx-data
  "Build an :audit-doc tx-data fragment recording the monthly ECR
   summary. Carries :audit-doc/category :payroll-filing +
   :audit-doc/language :en-in per ADR-083."
  [{:keys [pf-summary establishment-code language]
    :or {language :en-in}}]
  (let [{:keys [year month pf-total period-start period-end
                establishment-code-tag]} pf-summary
        est (or establishment-code establishment-code-tag "ALL-EST")
        title (format "EPFO ECR — %s, %04d-%02d" est year month)
        desc (format "PF payable (EE 12%% + ER 12%% + EDLI 0.5%%): %s | Period %s..%s"
                     (:amount pf-total) (str period-start) (str period-end))]
    [{:audit-doc/code (str "EPFO-ECR-" est "-" year "-" (format "%02d" month))
      :audit-doc/type :regulator-clearance
      :audit-doc/title title
      :audit-doc/description desc
      :audit-doc/uploaded-at (java.util.Date.)
      :audit-doc/category :payroll-filing
      :audit-doc/language language}]))

;; ============================================================================
;; build-ecr-submission — end-to-end
;; ============================================================================

(defn build-ecr-submission
  "End-to-end monthly ECR submission builder.

   Required opts:
     :conn / :db
     :year :month
     :establishment-code   string — the consumer's EPFO Establishment
                                    Code (e.g. 'MH-BOM-12345')
     :rows                 vector of per-employee maps (see ecr-row)

   Optional opts:
     :establishment-code-tag  filter the kontor-side summary
     :language

   Returns:
     {:summary    monthly-pf-summary
      :ecr-text   the tab-delimited payload
      :audit-doc-tx-data the [{...}] fragment}"
  [conn {:keys [year month establishment-code rows
                establishment-code-tag language]
         :or {language :en-in}}]
  (when-not establishment-code
    (throw (ex-info ":establishment-code required" {})))
  (when-not rows
    (throw (ex-info ":rows required (vector of per-employee maps)" {})))
  (let [summary (monthly-pf-summary
                 conn {:year year :month month
                       :establishment-code-tag establishment-code-tag})
        ecr (ecr-text rows)
        audit (ecr-audit-doc-tx-data
               {:pf-summary summary
                :establishment-code establishment-code
                :language language})]
    {:summary summary
     :ecr-text ecr
     :audit-doc-tx-data audit}))
