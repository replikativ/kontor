(ns kontor.payroll-ca.tpz1015
  "TPZ-1015 — Revenu Québec monthly / quarterly / annual source-deduction
   remittance helper. The Quebec equivalent of CRA's PD7A (note 84 §3.1 /
   §8.1): kontor sums the Revenu Québec liability buckets for a remittance
   period; the consumer pays via Mes paiements / online banking / paper
   voucher and records the cash payment.

   This is **NOT** a form emitter — TPZ-1015 statements are
   Revenu-Québec-to-employer correspondence (analogous to PD7A); there
   is no employer-filed TPZ-1015 form. kontor produces the liability
   totals.

   ## Frequencies (per ADR-087 §3 + Revenu Québec)

   - Revenu Québec, Remittance of Source Deductions and Employer
     Contributions — annual / monthly (TPZ-1015.R.14.1-V).
     https://www.revenuquebec.ca/en/online-services/forms-and-publications/current-details/tpz-1015-r-14-1-v/
     Accessed 2026-05-18.
   - Quarterly variant — TPZ-1015.R.14.4-V.
   - Twice-monthly variant — TPZ-1015.R.14.2-V.
   - Pay-period variant — TPZ-1015.R.14.3-V / TPZ-1015.R.14.3D-V.

   Schedule rules (analogous to PD7A; the consumer carries the type as
   `:rq-remitter-type` configuration):

   | Type             | Description                          | Due |
   |------------------|--------------------------------------|-----|
   | `:annual`        | total annual remuneration ≤ C$30,000 | by Jan 15 of following year |
   | `:monthly`       | average monthly withholding < C$25k  | by 15th of next month |
   | `:twice-monthly` | C$25k–C$99,999                       | 25th same month / 10th next month |
   | `:weekly`        | ≥ C$100,000                          | 3 working days after each week |

   ## What kontor does / does NOT do

   - DOES sum the four Revenu Québec liability buckets for a period:
     - QC income tax (`:ca-payroll-qc-itx`)
     - QPP (`:ca-payroll-qpp`)
     - QPIP (`:ca-payroll-qpip`)
     - FSS (`:ca-payroll-fss`) — Fonds des services de santé
   - DOES return a `:kontor.audit-doc/category :payroll-filing` row.
   - DOES NOT emit a TPZ-1015 form (no employer-filed form exists).
   - DOES NOT auto-remit (consumer holds Revenu Québec payment-channel
     credentials).
   - DOES NOT assign the remitter type (consumer-configured).

   Reference: note 84 §3 (pattern) + §8.1 (the QC parallel)."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [kontor.money :as money]
            [kontor.payroll-ca.pd7a :as pd7a])
  (:import [java.math BigDecimal]
           [java.time LocalDate]))

;; ============================================================================
;; Schedule helpers (parallel of pd7a)
;; ============================================================================

(def ^:private remitter-schedules
  {:annual        {:label "Annual"
                   :description "Total annual remuneration ≤ C$30,000"
                   :cadence :calendar-year
                   :due-day 15
                   :due-month 1
                   :due-offset-years 1}
   :monthly       {:label "Monthly"
                   :description "Remit by the 15th of the month following pay"
                   :cadence :calendar-month
                   :due-day 15
                   :due-offset-months 1}
   :twice-monthly {:label "Twice-monthly"
                   :description "Paydays 1-15 by 25th same month; 16-end by 10th of next month"
                   :cadence :semi-monthly
                   :due-day-15 25
                   :due-day-end 10
                   :due-offset-months-end 1}
   :weekly        {:label "Weekly"
                   :description "Within 3 working days after each weekly bucket"
                   :cadence :weekly
                   :due-business-days-after 3}})

(defn remitter-types []
  (set (keys remitter-schedules)))

(defn describe-remitter-type [k]
  (get remitter-schedules k))

(defn- inst-of
  ^java.util.Date [^LocalDate ld]
  (java.util.Date/from
   (.toInstant (.atStartOfDay ld java.time.ZoneOffset/UTC))))

(defn- ld-of
  ^LocalDate [^java.util.Date d]
  (-> d .toInstant (.atZone java.time.ZoneOffset/UTC) .toLocalDate))

(defn- next-business-day-offset
  "Naive business-day offset — skips Sat/Sun, ignores statutory
   holidays. Stat-holiday-aware refinement deferred (parallel of PD7A
   note 84 §10.4 #11)."
  [^LocalDate from days]
  (loop [d from remaining days]
    (cond
      (zero? remaining) d
      :else (let [d' (.plusDays d 1)
                  dow (.getValue (.getDayOfWeek d'))]
              (recur d' (if (or (= 6 dow) (= 7 dow))
                          remaining
                          (dec remaining)))))))

(defn next-due-date
  "Compute the next remittance due date given a remitter type + period
   end date. Returns a java.util.Date."
  [remitter-type ^java.util.Date period-end]
  (let [schedule (get remitter-schedules remitter-type)
        _ (when-not schedule
            (throw (ex-info "Unknown TPZ-1015 remitter type"
                            {:remitter-type remitter-type
                             :known (remitter-types)})))
        ld (ld-of period-end)]
    (case remitter-type
      :annual
      (inst-of (LocalDate/of (inc (.getYear ld)) 1 15))
      :monthly
      (let [next-month (.plusMonths ld 1)
            due (LocalDate/of (.getYear next-month) (.getMonthValue next-month)
                              (:due-day schedule))]
        (inst-of due))
      :twice-monthly
      (let [day-of-month (.getDayOfMonth ld)]
        (if (<= day-of-month 15)
          (inst-of (LocalDate/of (.getYear ld) (.getMonthValue ld) 25))
          (let [next-month (.plusMonths ld 1)]
            (inst-of (LocalDate/of (.getYear next-month) (.getMonthValue next-month) 10)))))
      :weekly
      (inst-of (next-business-day-offset ld 3)))))

;; ============================================================================
;; Liability totals
;; ============================================================================

(defn tpz1015-period-due
  "Compute the four Revenu Québec source-deduction + employer
   contribution buckets owed for a remittance period. Returns a map
   suitable for both a remittance voucher AND an `:audit-doc` of what
   was computed.

   Required opts:
     :period-start  inclusive (java.util.Date)
     :period-end    exclusive (java.util.Date)

   Optional:
     :rp-account-tag  string — when supplied, filters postings to those
                              tagged with the RP / NEQ routing tag
                              (parallel of PD7A §4.2).
     :remitter-type   one of `:annual :monthly :twice-monthly :weekly`
                              — drives the due-date suggestion.
     :as-of-tx :as-of-valid    bitemporal toggles forwarded to the
                              query (defaults to now/now).

   Returns:
     {:qc-itx Money :qpp Money :qpip Money :fss Money :total Money
      :period-start :period-end
      :remitter-type :due-date :rp-account-tag}"
  [conn {:keys [period-start period-end rp-account-tag
                remitter-type as-of-tx as-of-valid]}]
  (when-not period-start (throw (ex-info ":period-start required" {})))
  (when-not period-end   (throw (ex-info ":period-end required" {})))
  (let [db (cond-> (d/db conn)
             as-of-tx     (d/as-of as-of-tx)
             as-of-valid  (d/valid-at as-of-valid))
        sum-tag (fn [tag]
                  (pd7a/sum-postings-by-tag
                   db {:tag tag
                       :rp-account-tag rp-account-tag
                       :period-start period-start
                       :period-end period-end}))
        qc-itx (sum-tag :ca-payroll-qc-itx)
        qpp    (sum-tag :ca-payroll-qpp)
        qpip   (sum-tag :ca-payroll-qpip)
        fss    (sum-tag :ca-payroll-fss)
        total  (.add ^BigDecimal qc-itx
                     (.add ^BigDecimal qpp
                           (.add ^BigDecimal qpip ^BigDecimal fss)))]
    {:qc-itx (money/money qc-itx :CAD)
     :qpp (money/money qpp :CAD)
     :qpip (money/money qpip :CAD)
     :fss (money/money fss :CAD)
     :total (money/money total :CAD)
     :period-start period-start
     :period-end period-end
     :remitter-type remitter-type
     :due-date (when remitter-type (next-due-date remitter-type period-end))
     :rp-account-tag rp-account-tag}))

;; ============================================================================
;; tpz1015-audit-doc-tx-data (ADR-068 builder)
;; ============================================================================

(defn tpz1015-audit-doc-tx-data
  "Build an `:audit-doc` tx-data fragment recording the TPZ-1015 period
   summary. Consumer transacts via `transact-with-validation`.

   Each :audit-doc carries:
     :kontor.audit-doc/code     deterministic from RP/NEQ + period
     :kontor.audit-doc/category :payroll-filing
     :kontor.audit-doc/title    'RQ TPZ-1015 remittance for NEQ — period'
     :kontor.audit-doc/description a human-readable summary
     :kontor.audit-doc/language consumer-supplied; defaults to :fr (Revenu
                         Québec correspondence is French)."
  [{:keys [tpz1015-summary language]
    :or {language :fr}}]
  (let [{:keys [qc-itx qpp qpip fss total period-start period-end
                rp-account-tag due-date]} tpz1015-summary
        rp (or rp-account-tag "ALL")
        title (format "RQ TPZ-1015 remittance for %s — period %s..%s"
                      rp (str period-start) (str period-end))
        desc (format
              (str "QC-ITX: %s | QPP: %s | QPIP: %s | FSS: %s | "
                   "Total: %s%s")
              (:amount qc-itx)
              (:amount qpp)
              (:amount qpip)
              (:amount fss)
              (:amount total)
              (if due-date (str " | Due: " due-date) ""))]
    [{:kontor.audit-doc/code (str "TPZ1015-" rp "-"
                           (.getTime ^java.util.Date period-end))
      :kontor.audit-doc/type :regulator-clearance
      :kontor.audit-doc/title title
      :kontor.audit-doc/description desc
      :kontor.audit-doc/uploaded-at (java.util.Date.)
      :kontor.audit-doc/category :payroll-filing
      :kontor.audit-doc/language language}]))

;; Suppress 'unused' for the str/blank ref pattern at REPL inspection.
(comment str/blank?)
