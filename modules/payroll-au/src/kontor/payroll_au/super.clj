(ns kontor.payroll-au.super
  "SuperStream contribution message helper.

   ## What SuperStream is

   SuperStream is the ATO-mandated electronic standard for paying
   employee superannuation contributions to super funds. Employers
   (or their clearing-house) submit a structured XML message
   (SuperStream Alternative File Format, AFF, per ATO Schedule
   6 — accessed 2026-05-18 from softwaredevelopers.ato.gov.au)
   carrying per-employee contribution lines, paired with an
   electronic funds transfer to the matching aggregate amount.

   The substrate ships:
     - `contribution-message-payload` — structured map per ATO AFF.
     - `superstream-audit-doc-tx-data` — ADR-068 builder that
       records the payload as an `:kontor.audit-doc/category :payroll-filing`
       row for the audit chain.

   ## What kontor does NOT do

   - **No SBR / clearing-house transmission.** The consumer's
     clearing-house (ATO Small Business Superannuation Clearing
     House for ≤19-employee businesses, or a commercial
     clearing-house) submits the XML + funds; kontor records the
     submission intent.
   - **No SG-rate determination.** Super Guarantee rate is 11.5 %
     from 2024-07-01 and rises to 12.0 % from 2025-07-01 per
     statutory schedule; the rate is consumer-policy (the engine
     applies it; kontor reads the result).
   - **No fund-USI / SPIN registry.** Each super fund has its own
     USI (Unique Superannuation Identifier); the consumer's HR
     system holds it.

   Reference: ADR-080, ATO Software Developers BIG SuperStream
   AFF (accessed 2026-05-18 from softwaredevelopers.ato.gov.au)."
  (:require [clojure.string :as str])
  (:import [java.math BigDecimal RoundingMode]
           [java.text SimpleDateFormat]
           [java.util Date]))

(defn- bd ^BigDecimal [x]
  (if (instance? BigDecimal x) x (BigDecimal. (str x))))

(defn- bd-fmt
  [x]
  (.toPlainString
   (.setScale (bd x) 2 RoundingMode/HALF_EVEN)))

(defn- fmt-date
  [^Date d]
  (when d (.format (SimpleDateFormat. "yyyy-MM-dd") d)))

(defn- valid-usi?
  "True iff `s` looks like a structurally-valid USI (Unique
   Superannuation Identifier). The ATO spec is 'an alphanumeric
   string up to 15 chars'; we only check the structural envelope."
  [s]
  (boolean
   (and (string? s)
        (let [t (str/trim s)]
          (and (>= (count t) 4)
               (<= (count t) 15)
               (re-matches #"[A-Za-z0-9]+" t))))))

(defn contribution-line
  "Build one SuperStream contribution line per employee + super-fund
   pair. Required keys:

     :member            {:given-name :family-name :tfn :member-number
                         :date-of-birth :address :gender}
     :fund              {:usi :abn :name}
     :sg-amount         BigDecimal — employer SG contribution
                        (the 11.5 %-of-OTE amount the engine computed)
     :member-voluntary  BigDecimal — employee additional contribution
                        (defaults to 0M)
     :salary-sacrifice  BigDecimal — salary-sacrifice S amount
                        (defaults to 0M)
     :pay-period-start  java.util.Date
     :pay-period-end    java.util.Date

   Returns a map shape per the ATO SuperStream AFF v2.0
   `SuperContrib` element."
  [{:keys [member fund sg-amount member-voluntary salary-sacrifice
           pay-period-start pay-period-end]
    :or {member-voluntary 0M
         salary-sacrifice 0M}}]
  (when-not member            (throw (ex-info ":member required" {})))
  (when-not fund              (throw (ex-info ":fund required" {})))
  (when-not sg-amount         (throw (ex-info ":sg-amount required" {})))
  (when-not pay-period-start  (throw (ex-info ":pay-period-start required" {})))
  (when-not pay-period-end    (throw (ex-info ":pay-period-end required" {})))
  (let [usi (:usi fund)
        _ (when-not (valid-usi? usi)
            (throw (ex-info "Invalid super fund USI"
                            {:usi usi
                             :expected "alphanumeric, 4-15 chars"})))]
    {:super.line/member-tfn (:tfn member)
     :super.line/member-given (:given-name member)
     :super.line/member-family (:family-name member)
     :super.line/member-dob (fmt-date (:date-of-birth member))
     :super.line/member-number (:member-number member)
     :super.line/fund-usi usi
     :super.line/fund-abn (:abn fund)
     :super.line/fund-name (:name fund)
     :super.line/pay-period-start (fmt-date pay-period-start)
     :super.line/pay-period-end (fmt-date pay-period-end)
     :super.line/sg-amount (bd-fmt sg-amount)
     :super.line/member-voluntary (bd-fmt member-voluntary)
     :super.line/salary-sacrifice (bd-fmt salary-sacrifice)
     :super.line/total
     (bd-fmt (.add ^BigDecimal (.add ^BigDecimal (bd sg-amount)
                                     (bd member-voluntary))
                   (bd salary-sacrifice)))}))

(defn contribution-message-payload
  "Build a SuperStream contribution message payload.

   Required keys:
     :abn              employer ABN
     :usi              employer's clearing-house USI (or scheme-USI)
     :pay-period-start java.util.Date
     :pay-period-end   java.util.Date
     :submission-date  java.util.Date
     :lines            vector of `contribution-line` results
     :total-amount     BigDecimal — total contributions (sum of line totals)

   Optional:
     :clearing-house-name  string (the SBSCH or commercial CH)
     :branch-code          3-digit branch (defaults '001')"
  [{:keys [abn usi pay-period-start pay-period-end submission-date
           lines total-amount clearing-house-name branch-code]
    :or {branch-code "001"}}]
  (when-not abn (throw (ex-info ":abn required" {})))
  (when-not usi (throw (ex-info ":usi required" {})))
  (when-not pay-period-start (throw (ex-info ":pay-period-start required" {})))
  (when-not pay-period-end   (throw (ex-info ":pay-period-end required" {})))
  (when-not submission-date  (throw (ex-info ":submission-date required" {})))
  (when (empty? lines)       (throw (ex-info ":lines must be non-empty" {})))
  (when-not total-amount     (throw (ex-info ":total-amount required" {})))
  {:super.message/version "2.0"
   :super.message/abn abn
   :super.message/usi usi
   :super.message/branch-code branch-code
   :super.message/clearing-house clearing-house-name
   :super.message/pay-period-start (fmt-date pay-period-start)
   :super.message/pay-period-end   (fmt-date pay-period-end)
   :super.message/submission-date  (fmt-date submission-date)
   :super.message/line-count (count lines)
   :super.message/total-amount (bd-fmt total-amount)
   :super.message/lines lines})

(defn superstream-audit-doc-tx-data
  "Pure ADR-068 builder — record a SuperStream contribution-message
   intent as an `:kontor.audit-doc/category :payroll-filing` entity. The
   consumer transacts this alongside the actual outbound transmission
   (which happens in the consumer's engine / clearing-house adapter
   — kontor records, it does not transmit).

   Required keys:
     :payload          result of `contribution-message-payload`

   Optional:
     :code             consumer-supplied audit-doc/code (defaults from
                       ABN + period-end)
     :storage-uri      where the consumer stored the serialized payload
     :language         :en (default) — the AU adapter is single-locale"
  [{:keys [payload code storage-uri language]
    :or {language :en}}]
  (when-not payload (throw (ex-info ":payload required" {})))
  (let [doc-code (or code
                     (format "SUPER-%s-%s"
                             (:super.message/abn payload)
                             (:super.message/pay-period-end payload)))
        title (format "SuperStream contribution message — ABN %s, period %s..%s"
                      (:super.message/abn payload)
                      (:super.message/pay-period-start payload)
                      (:super.message/pay-period-end payload))
        desc (format "%d line(s); total %s AUD%s"
                     (:super.message/line-count payload)
                     (:super.message/total-amount payload)
                     (if storage-uri (str " | URI " storage-uri) ""))]
    [(cond->
      {:kontor.audit-doc/code doc-code
       :kontor.audit-doc/type :superstream-contribution
       :kontor.audit-doc/title title
       :kontor.audit-doc/description desc
       :kontor.audit-doc/uploaded-at (Date.)
       :kontor.audit-doc/category :payroll-filing
       :kontor.audit-doc/language language}
       storage-uri (assoc :kontor.audit-doc/storage-uri storage-uri))]))
