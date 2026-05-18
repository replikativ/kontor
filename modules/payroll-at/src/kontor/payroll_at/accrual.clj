(ns kontor.payroll-at.accrual
  "Monthly accruals mandated by UGB §198 (ADR-072):

     - Urlaubsrückstellung — unused-vacation provision.
     - Sonderzahlungs-Rückstellung — 1/12 of the expected 13./14.
       per month, accrued every month so the period it actually pays
       out (June / November) doesn't book a one-time impact.

   Both are leaf `*-tx-data` builders (ADR-068). They post:

     Dr 6000 Personalaufwand (or 6500 Sozialaufwand for the employer-SV
        component of the accrual base)
       Cr 3710 Urlaubsrückstellung
       Cr 3720 Rückstellung Sonderzahlung

   Account codes 3710 / 3720 are conventional RLG-1 entries (UGB does
   not mandate specific account numbers; the chart_renderer's RGL-1
   convention does).

   The actuarial Abfertigung-Alt provision is explicitly OUT OF SCOPE
   for v1 — it requires Sterbetafel + discount-rate assumptions; a
   future `kontor-l10n-at-abfertigung` artifact covers it."
  (:require [datahike.api :as d]
            [kontor.posting :as posting]
            [kontor.validation :as validation])
  (:import [java.math BigDecimal RoundingMode]))

;; ============================================================================
;; Default RLG-1 accrual account codes
;; ============================================================================

(def default-accrual-accounts
  "Account codes for the accrual postings. Consumer can override at
   call time (account-map)."
  {:personalaufwand          "6000"   ; Personalaufwand (Gehälter)
   :sozialaufwand-arbeitgeber "6500"  ; same SV-AG aufwand
   :rueckstellung-urlaub     "3710"   ; Urlaubsrückstellung
   :rueckstellung-sonder     "3720"   ; Rückstellung 13./14.
   })

(defn- account-code-for
  [k account-map]
  (or (get account-map k)
      (get default-accrual-accounts k)
      (throw (ex-info "Unknown accrual account key"
                      {:key k :known (keys default-accrual-accounts)}))))

(defn- account-eid [db code]
  (or (d/q '[:find ?a . :in $ ?c :where [?a :account/code ?c]] db code)
      (throw (ex-info "Account code not in db"
                      {:code code
                       :hint "Install the AT Kontenrahmen first."}))))

;; ============================================================================
;; Urlaubsrückstellung
;; ============================================================================

(defn- ->cents
  ^BigDecimal [^BigDecimal bd]
  (.setScale bd 2 RoundingMode/HALF_EVEN))

(defn urlaubsrueckstellung-amount
  "Compute the per-period Urlaubsrückstellung amount.

   `avg-daily-base` is the employee's average wage per workday (the
   :grundgehalt + benefit-eligible add-ons divided by typical 20-22
   workdays/month). `days-earned-in-period` is how much vacation the
   employee earned this period (typically 25 days/year ÷ 12 = 2.083).
   `employer-sv-rate` is the gross-up multiplier (typically 0.2123 in
   Austria). Returns a bigdec.

   Formula:
     avg-daily-base × days-earned × (1 + employer-sv-rate)"
  ^BigDecimal [^BigDecimal avg-daily-base
               ^BigDecimal days-earned-in-period
               ^BigDecimal employer-sv-rate]
  (-> ^BigDecimal avg-daily-base
      (.multiply ^BigDecimal days-earned-in-period)
      (.multiply (.add 1M ^BigDecimal employer-sv-rate))
      ->cents))

(defn accrue-urlaubsrueckstellung-tx-data
  "Pure tx-data builder. Builds a balanced 2-line tx:
       Dr  Personalaufwand
         Cr  Urlaubsrückstellung

   Required opts:
     :amount         the accrual amount (bigdec)
     :journal        ref
     :commodity      ref
     :effective-date #inst — drives :tx/valid-from
   Optional:
     :account-map    override default-accrual-accounts
     :narration      default 'Urlaubsrückstellung <YYYY-MM>'
     :state          default :posted"
  [db {:keys [amount journal commodity effective-date
              account-map narration state]
       :or {state :posted}}]
  (when (nil? amount)        (throw (ex-info ":amount required" {})))
  (when-not journal          (throw (ex-info ":journal required" {})))
  (when-not commodity        (throw (ex-info ":commodity required" {})))
  (when-not effective-date   (throw (ex-info ":effective-date required" {})))
  (let [pa-acct (account-eid db (account-code-for :personalaufwand account-map))
        rs-acct (account-eid db (account-code-for :rueckstellung-urlaub account-map))
        period-str (let [fmt (doto (java.text.SimpleDateFormat. "yyyy-MM")
                               (.setTimeZone
                                (java.util.TimeZone/getTimeZone "UTC")))]
                     (.format fmt ^java.util.Date effective-date))
        narr (or narration (str "Urlaubsrückstellung " period-str))
        amt ^BigDecimal (->cents amount)
        postings [{:posting/account pa-acct :posting/amount amt
                   :posting/commodity commodity
                   :posting/narration narr}
                  {:posting/account rs-acct :posting/amount (.negate amt)
                   :posting/commodity commodity
                   :posting/narration narr}]
        postings (if (= state :posted)
                   (mapv #(assoc % :posting/posted-at effective-date) postings)
                   postings)]
    (posting/build-transaction
     {:transaction (cond-> {:transaction/journal journal
                            :transaction/effective-date effective-date
                            :transaction/external-id
                            (str "urlaubsrueck-" period-str)
                            :transaction/narration narr
                            :transaction/source (str "payroll-at:accrual:urlaub:" period-str)
                            :transaction/state state}
                     (= state :posted)
                     (assoc :transaction/posted-at effective-date))
      :postings postings})))

(defn accrue-urlaubsrueckstellung!
  [conn opts]
  (validation/transact-with-validation
   conn (accrue-urlaubsrueckstellung-tx-data (d/db conn) opts)))

;; ============================================================================
;; Sonderzahlung (13./14.) monthly accrual
;; ============================================================================

(defn sonderzahlung-monthly-amount
  "Compute the monthly accrual portion of an expected 13./14.
   Sonderzahlung.

   `expected-annual-sonder` is the sum of expected 13. + 14. for the
   year (typically 2 × monthly base). `employer-sv-rate` grosses up
   for the employer-borne SV (typically 0.2123). Returns
     (expected-annual-sonder × (1 + sv-rate)) / 12"
  ^BigDecimal [^BigDecimal expected-annual-sonder
               ^BigDecimal employer-sv-rate]
  (-> ^BigDecimal expected-annual-sonder
      (.multiply (.add 1M ^BigDecimal employer-sv-rate))
      (.divide (BigDecimal/valueOf 12) 12 RoundingMode/HALF_EVEN)
      ->cents))

(defn accrue-sonderzahlung-tx-data
  "Build the monthly Sonderzahlung accrual posting.

     Dr Personalaufwand
       Cr Rückstellung Sonderzahlung

   Required opts:
     :amount, :journal, :commodity, :effective-date.
   Optional:
     :account-map, :narration, :state."
  [db {:keys [amount journal commodity effective-date
              account-map narration state]
       :or {state :posted}}]
  (when (nil? amount)        (throw (ex-info ":amount required" {})))
  (when-not journal          (throw (ex-info ":journal required" {})))
  (when-not commodity        (throw (ex-info ":commodity required" {})))
  (when-not effective-date   (throw (ex-info ":effective-date required" {})))
  (let [pa-acct (account-eid db (account-code-for :personalaufwand account-map))
        rs-acct (account-eid db (account-code-for :rueckstellung-sonder account-map))
        period-str (let [fmt (doto (java.text.SimpleDateFormat. "yyyy-MM")
                               (.setTimeZone
                                (java.util.TimeZone/getTimeZone "UTC")))]
                     (.format fmt ^java.util.Date effective-date))
        narr (or narration (str "Sonderzahlungs-Rückstellung " period-str))
        amt ^BigDecimal (->cents amount)
        postings [{:posting/account pa-acct :posting/amount amt
                   :posting/commodity commodity
                   :posting/narration narr}
                  {:posting/account rs-acct :posting/amount (.negate amt)
                   :posting/commodity commodity
                   :posting/narration narr}]
        postings (if (= state :posted)
                   (mapv #(assoc % :posting/posted-at effective-date) postings)
                   postings)]
    (posting/build-transaction
     {:transaction (cond-> {:transaction/journal journal
                            :transaction/effective-date effective-date
                            :transaction/external-id
                            (str "sonderzahl-rueck-" period-str)
                            :transaction/narration narr
                            :transaction/source (str "payroll-at:accrual:sonder:" period-str)
                            :transaction/state state}
                     (= state :posted)
                     (assoc :transaction/posted-at effective-date))
      :postings postings})))

(defn accrue-sonderzahlung!
  [conn opts]
  (validation/transact-with-validation
   conn (accrue-sonderzahlung-tx-data (d/db conn) opts)))
