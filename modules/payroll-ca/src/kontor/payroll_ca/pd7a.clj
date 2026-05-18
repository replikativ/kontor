(ns kontor.payroll-ca.pd7a
  "PD7A remittance helper. NOT an emitter — kontor does NOT file a
   PD7A because PD7A is CRA-to-employer correspondence (note 84 §3.1
   + §3.3). What kontor DOES do is total the three statutory CRA
   payable buckets (Income Tax / CPP / EI) for a remittance period so
   the consumer can remit via My Payment / online banking / paper
   voucher and record the matching cash payment.

   Reference: note 84 §3.

   ## Remitter type → schedule (note 84 §3.2)

   | Type            | AMWA (avg monthly withholding)    | Remit by |
   |-----------------|-----------------------------------|----------|
   | :quarterly      | < $3,000 (new SMB)                | 15th after calendar quarter |
   | :regular        | ≤ $25,000                         | 15th of month following pay |
   | :accel-t1       | $25k–$99,999                      | 25th (paydays 1–15) / 10th of next month (paydays 16–end) |
   | :accel-t2       | ≥ $100,000                        | 3 working days after each weekly bucket |

   AMWA is computed from the second-prior calendar year. Consumer
   carries the type as configuration (`:remitter-type`).

   ## What kontor does and does NOT do (note 84 §3.4)

   - DOES compute totals per period + per RP.
   - DOES return an `:audit-doc` tx-data shape per ADR-068 for the
     remittance summary (consumer transacts it).
   - DOES NOT emit a PD7A form (there is no employer-filed PD7A form).
   - DOES NOT auto-remit (consumer holds CRA payment-channel creds).
   - DOES NOT assign the remitter type (consumer-configured)."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [kontor.money :as money])
  (:import [java.math BigDecimal]
           [java.time LocalDate]))

;; ============================================================================
;; Schedule helpers
;; ============================================================================

(def ^:private remitter-schedules
  "Per-remitter-type schedule metadata. See note 84 §3.2."
  {:quarterly {:label "Quarterly"
               :description "Remit by the 15th of the month after each calendar quarter"
               :cadence :calendar-quarter
               :due-day 15
               :due-offset-months 1}
   :regular   {:label "Regular"
               :description "Remit by the 15th of the month following pay"
               :cadence :calendar-month
               :due-day 15
               :due-offset-months 1}
   :accel-t1  {:label "Accelerated — Threshold 1"
               :description "Paydays 1–15 by 25th same month; 16–end by 10th of next month"
               :cadence :semi-monthly
               :due-day-15 25
               :due-day-end 10
               :due-offset-months-end 1}
   :accel-t2  {:label "Accelerated — Threshold 2"
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
   holidays. Per note 84 §10.4 #11 the proper holiday-aware version
   is a follow-up; this is enough for the helper to surface a date."
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
   end date. Returns a java.util.Date.

   `period-end` is the inclusive last day of the payroll period that
   triggered the remittance.

   Limitations: this is a calendar-day computation; statutory-holiday
   shifts (note 84 §10.4 #11) are a follow-up. For :accel-t1 we
   return the larger of the two due dates if the period straddles
   the mid-month split."
  [remitter-type ^java.util.Date period-end]
  (let [schedule (get remitter-schedules remitter-type)
        _ (when-not schedule
            (throw (ex-info "Unknown remitter type"
                            {:remitter-type remitter-type
                             :known (remitter-types)})))
        ld (ld-of period-end)]
    (case remitter-type
      :quarterly
      (let [month (.getMonthValue ld)
            quarter (cond (<= month 3) 1 (<= month 6) 2 (<= month 9) 3 :else 4)
            quarter-end-month (* quarter 3)
            quarter-end-year (.getYear ld)
            due (LocalDate/of quarter-end-year (mod (inc quarter-end-month) 13)
                              (:due-day schedule))]
        (inst-of due))
      :regular
      (let [next-month (.plusMonths ld 1)
            due (LocalDate/of (.getYear next-month) (.getMonthValue next-month)
                              (:due-day schedule))]
        (inst-of due))
      :accel-t1
      (let [day-of-month (.getDayOfMonth ld)]
        (if (<= day-of-month 15)
          (inst-of (LocalDate/of (.getYear ld) (.getMonthValue ld) 25))
          (let [next-month (.plusMonths ld 1)]
            (inst-of (LocalDate/of (.getYear next-month) (.getMonthValue next-month) 10)))))
      :accel-t2
      (inst-of (next-business-day-offset ld 3)))))

;; ============================================================================
;; Liability totals
;; ============================================================================

(defn- bd-add
  ^BigDecimal [^BigDecimal a ^BigDecimal b]
  (.add a b))

(defn sum-postings-by-tag
  "Sum the absolute amount of postings against accounts carrying any
   of the named tag, optionally filtered by an RP routing tag and a
   posting date range. Returns BigDecimal (positive = liability
   owed).

   Convention: liability accounts have credit balance → negative
   posting amounts. We sum the credits as positive numbers (the
   employer's view of 'what we owe').

   Implementation note (kontor.posting + datalog set-semantics):
   `:find (sum ?amount)` over a single var collapses duplicate values
   in the relation. We fetch `[?p ?amount]` tuples (distinct by entity
   id), then sum in Clojure — guaranteed correct even when two
   independent postings happen to carry the same numeric amount
   (employee CPP + employer-side CPP-payable mirror, etc.)."
  [db {:keys [tag rp-account-tag period-start period-end]}]
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
        q-with-rp '[:find ?p ?amount
                    :in $ ?tag ?rp ?start ?end
                    :where
                    [?acct :account/tags ?at]
                    [?at :account-tag/name ?tag]
                    [?p :posting/account ?acct]
                    [?p :posting/amount ?amount]
                    [?p :posting/account-tags ?rt]
                    [?rt :account-tag/name ?rp]
                    [?p :posting/transaction ?tx]
                    [?tx :transaction/effective-date ?ed]
                    [(.before ^java.util.Date ?ed ?end)]
                    [(.after ^java.util.Date ?ed ?start)]]
        rows (if (and rp-account-tag (not (str/blank? rp-account-tag)))
               (d/q q-with-rp db (name tag) rp-account-tag period-start period-end)
               (d/q q db (name tag) period-start period-end))
        total (reduce (fn [^BigDecimal a [_ ^BigDecimal v]]
                        (.add a v))
                      0M rows)]
    (.abs ^BigDecimal total)))

(defn pd7a-period-due
  "Compute the three statutory CRA payables owed for a remittance
   period. Returns a map suitable for both a remittance voucher and an
   `:audit-doc` of what was computed.

   Required opts:
     :period-start  inclusive (java.util.Date)
     :period-end    exclusive (java.util.Date)

   Optional:
     :rp-account-tag string — when supplied, filters postings to those
                              tagged with the RP routing tag (note 84
                              §4).
     :remitter-type  one of :quarterly :regular :accel-t1 :accel-t2 —
                              used to compute the suggested due date.
     :as-of-tx :as-of-valid    bitemporal toggles forwarded to the
                              query (defaults to now/now).

   Returns:
     {:itx Money :cpp Money :ei Money :total Money
      :period-start :period-end
      :remitter-type :due-date (Date or nil)
      :rp-account-tag}"
  [conn {:keys [period-start period-end rp-account-tag
                remitter-type as-of-tx as-of-valid]}]
  (when-not period-start (throw (ex-info ":period-start required" {})))
  (when-not period-end   (throw (ex-info ":period-end required" {})))
  (let [db (cond-> (d/db conn)
             as-of-tx     (d/as-of as-of-tx)
             as-of-valid  (d/valid-at as-of-valid))
        itx (sum-postings-by-tag db {:tag :ca-payroll-itx
                                     :rp-account-tag rp-account-tag
                                     :period-start period-start
                                     :period-end period-end})
        cpp (sum-postings-by-tag db {:tag :ca-payroll-cpp
                                     :rp-account-tag rp-account-tag
                                     :period-start period-start
                                     :period-end period-end})
        ei  (sum-postings-by-tag db {:tag :ca-payroll-ei
                                     :rp-account-tag rp-account-tag
                                     :period-start period-start
                                     :period-end period-end})
        total (bd-add (bd-add itx cpp) ei)]
    {:itx (money/money itx :CAD)
     :cpp (money/money cpp :CAD)
     :ei  (money/money ei  :CAD)
     :total (money/money total :CAD)
     :period-start period-start
     :period-end period-end
     :remitter-type remitter-type
     :due-date (when remitter-type (next-due-date remitter-type period-end))
     :rp-account-tag rp-account-tag}))

;; ============================================================================
;; pd7a-period-due-tx-data (ADR-068 builder) — :audit-doc for the helper
;; ============================================================================

(defn pd7a-audit-doc-tx-data
  "Build an `:audit-doc` tx-data fragment recording the PD7A period
   summary. Consumer transacts via `transact-with-validation`.

   Each :audit-doc carries:
     :audit-doc/code     deterministic from RP + period
     :audit-doc/category :payroll-filing
     :audit-doc/title    'CRA remittance for RP — period'
     :audit-doc/description a human-readable summary
     :audit-doc/language consumer-supplied :en | :fr (default :en)"
  [{:keys [pd7a-summary language]
    :or {language :en}}]
  (let [{:keys [itx cpp ei total period-start period-end
                rp-account-tag due-date]} pd7a-summary
        rp (or rp-account-tag "ALL-RPS")
        title (format "CRA remittance for %s — period %s..%s"
                      rp (str period-start) (str period-end))
        desc (format "ITX: %s | CPP: %s | EI: %s | Total: %s%s"
                     (:amount itx)
                     (:amount cpp)
                     (:amount ei)
                     (:amount total)
                     (if due-date (str " | Due: " due-date) ""))]
    [{:audit-doc/code (str "PD7A-" rp "-" (.getTime ^java.util.Date period-end))
      :audit-doc/type :regulator-clearance
      :audit-doc/title title
      :audit-doc/description desc
      :audit-doc/uploaded-at (java.util.Date.)
      :audit-doc/category :payroll-filing
      :audit-doc/language language}]))
