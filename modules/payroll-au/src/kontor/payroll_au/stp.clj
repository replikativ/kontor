(ns kontor.payroll-au.stp
  "STP Phase 2 event helpers — pure structure builders.

   ## What STP Phase 2 is

   Single Touch Payroll Phase 2 (mandatory for all Australian
   employers since 2022-01-01 per ATO Treasury Laws Amendment
   (2019 Measures No.2) Act and the subsequent ATO Software
   Developers' Business Implementation Guide accessed on
   2026-05-18 from softwaredevelopers.ato.gov.au) requires
   every employer to send a structured XML pay-event to the ATO
   on or before each pay day. Phase 2 (2022 expansion) adds
   per-pay-event:

     - Income-type disaggregation (SAW / OTE / Overtime / Bonus &
       Commission / Lump Sums A-E / Directors' Fees / ETP / Paid
       Leave / Salary Sacrifice S+O).
     - Country-code reporting for working-holiday-makers /
       inbound-assignees.
     - Disaggregated deductions (child-support /
       voluntary-after-tax-super / workplace-giving).
     - Tax treatment code (per-employee 6-character code; replaces
       former TFN-declaration-style flags).
     - Year-to-date carrying for OTE / gross / PAYGW / Super /
       allowances.

   ## What kontor produces

   This namespace produces the structured Clojure data shape that
   represents one STP pay-event payload. The actual XML serialization
   to ATO SBR2 (ebMS3 / AS4 envelope, signed via AUSkey / m2m
   credentials) is the consumer's engine's job — kontor records the
   payload as an `:audit-doc` so the audit chain has a row, and the
   consumer's SBR-adapter uploads.

   The XML element layout mirrors the ATO MIG (Message
   Implementation Guide) `PAYEVNT.PAYEVNTEMP` schema family. We
   produce the JSON-ish nested-map shape; a downstream consumer
   serializes via `clojure.data.xml` or a vendor library.

   ## What kontor does NOT do

   - **No SBR ebMS3 / AS4 envelope generation.** The transport-layer
     authentication (AUSkey / m2m / Cloud Software Authentication
     and Authorisation) is consumer-held.
   - **No XBRL-GL conversion.** Out of scope for the payroll
     adapter; that's an XBRL emit-side companion (per note 78).
   - **No PAYG-withholding math.** ATO publishes per-week / per-
     fortnight / per-month withholding tables; the engine computes,
     kontor records.

   Reference: ADR-080, ATO Software Developers BIG STP Phase 2
   (accessed 2026-05-18 from softwaredevelopers.ato.gov.au)."
  (:require [clojure.string :as str])
  (:import [java.math BigDecimal RoundingMode]
           [java.text SimpleDateFormat]
           [java.util Date]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- bd ^BigDecimal [x]
  (if (instance? BigDecimal x) x (BigDecimal. (str x))))

(defn- bd-add ^BigDecimal [^BigDecimal a ^BigDecimal b] (.add a (bd b)))

(defn- bd-fmt
  "Format a BigDecimal to two decimal places — ATO PAYEVNT amount
   precision."
  [x]
  (.toPlainString
   (.setScale (bd x) 2 RoundingMode/HALF_EVEN)))

(defn- fmt-date
  "STP date format is yyyy-MM-dd (ISO 8601) per the MIG. Format in UTC
   so `#inst` literals (which are UTC) round-trip without timezone
   surprise."
  [^Date d]
  (when d
    (let [fmt (SimpleDateFormat. "yyyy-MM-dd")]
      (.setTimeZone fmt (java.util.TimeZone/getTimeZone "UTC"))
      (.format fmt d))))

(defn- non-zero?
  [^BigDecimal x]
  (not (zero? (.signum x))))

;; ============================================================================
;; Per-payee income-type disaggregation
;; ============================================================================

(defn- accumulate-by-income-type
  "Reduce a PayrollFact's components into a map of STP income-type
   keyword → BigDecimal sum (absolute value). Driven by the
   `:stp2-income-type` slot on the wage-types catalog.

   Only `:posts?`-true components feed the period totals (carry-only
   kinds like :ytd-* don't contribute)."
  [{:keys [components]} extras-map-resolver]
  (->> components
       (keep (fn [{:keys [kind amount]}]
               (when-let [it (extras-map-resolver kind)]
                 [it (.abs ^BigDecimal amount)])))
       (reduce (fn [m [it amt]]
                 (update m it (fn [^BigDecimal v]
                                (.add ^BigDecimal (or v 0M) ^BigDecimal amt))))
               {})))

(defn payee-payload
  "Build one `:stp-payee` map for a single payee in the pay-event.
   Inputs:
     :payee              {:tfn :given-name :family-name
                          :date-of-birth :employee-id :tax-treatment-code
                          :address :phone :email}
     :fact               PayrollFact for this employee + period
     :income-type-fn     function (component-kind → STP income-type kw)
                         — the resolver, e.g.
                         (fn [k] (wt/stp2-income-type k extras-map))
     :ytd                map of {:gross :ote :paygw :super :rfba} year-
                         to-date BigDecimals (engine-supplied or
                         derived from prior periods; kontor does not
                         compute YTD from postings here — that's an
                         orthogonal report).

   Returns a map shaped per the ATO PAYEVNT.PAYEVNTEMP MIG."
  [{:keys [payee fact income-type-fn ytd]}]
  (when-not payee  (throw (ex-info ":payee required" {})))
  (when-not fact   (throw (ex-info ":fact required" {})))
  (when-not income-type-fn (throw (ex-info ":income-type-fn required" {})))
  (let [by-type (accumulate-by-income-type fact income-type-fn)
        gross-period (or (get by-type :gross) 0M)
        ote-period   (or (get by-type :ote) 0M)
        overtime     (or (get by-type :overtime) 0M)
        bonus-comm   (or (get by-type :bonus-commission) 0M)
        directors    (or (get by-type :directors-fees) 0M)
        paid-leave   (or (get by-type :paid-leave) 0M)
        allowance    (or (get by-type :allowance) 0M)
        ss-super     (or (get by-type :salary-sacrifice-s) 0M)
        ss-other     (or (get by-type :salary-sacrifice-o) 0M)
        paygw        (or (get by-type :paygw) 0M)
        super-g      (or (get by-type :super-guarantee) 0M)
        rfba         (or (get by-type :rfba) 0M)
        lump-a       (or (get by-type :lump-sum-a) 0M)
        lump-b       (or (get by-type :lump-sum-b) 0M)
        lump-d       (or (get by-type :lump-sum-d) 0M)
        lump-e       (or (get by-type :lump-sum-e) 0M)
        ytd          (or ytd {})
        ;; Phase 2 SAW gross is the sum of OTE + overtime + BC + DF +
        ;; PL + AS + lump-sums + any explicit gross.
        income-sum   (reduce bd-add 0M
                             [gross-period ote-period overtime bonus-comm
                              directors paid-leave allowance
                              lump-a lump-b lump-d lump-e])
        period-totals
        (cond-> {}
          (non-zero? gross-period) (assoc :stp/gross (bd-fmt gross-period))
          (non-zero? ote-period)   (assoc :stp/ote (bd-fmt ote-period))
          (non-zero? overtime)     (assoc :stp/overtime (bd-fmt overtime))
          (non-zero? bonus-comm)   (assoc :stp/bonus-commission (bd-fmt bonus-comm))
          (non-zero? directors)    (assoc :stp/directors-fees (bd-fmt directors))
          (non-zero? paid-leave)   (assoc :stp/paid-leave (bd-fmt paid-leave))
          (non-zero? allowance)    (assoc :stp/allowance (bd-fmt allowance))
          (non-zero? ss-super)     (assoc :stp/salary-sacrifice-s (bd-fmt ss-super))
          (non-zero? ss-other)     (assoc :stp/salary-sacrifice-o (bd-fmt ss-other))
          (non-zero? paygw)        (assoc :stp/paygw (bd-fmt paygw))
          (non-zero? super-g)      (assoc :stp/super-guarantee (bd-fmt super-g)))
        ytd-totals
        (cond-> {}
          (:gross ytd)            (assoc :stp.ytd/gross (bd-fmt (:gross ytd)))
          (:ote ytd)              (assoc :stp.ytd/ote (bd-fmt (:ote ytd)))
          (:paygw ytd)            (assoc :stp.ytd/paygw (bd-fmt (:paygw ytd)))
          (:super ytd)            (assoc :stp.ytd/super (bd-fmt (:super ytd)))
          (non-zero? rfba)        (assoc :stp.ytd/rfba (bd-fmt rfba)))]
    (cond-> {:stp.payee/tfn (:tfn payee)
             :stp.payee/given-name (:given-name payee)
             :stp.payee/family-name (:family-name payee)
             :stp.payee/employee-id (:employee-id payee)
             :stp.payee/tax-treatment-code (:tax-treatment-code payee)
             :stp.payee/income-sum (bd-fmt income-sum)
             :stp.payee/period-totals period-totals
             :stp.payee/ytd ytd-totals}
      (:date-of-birth payee) (assoc :stp.payee/date-of-birth
                                    (fmt-date (:date-of-birth payee)))
      (:address payee)       (assoc :stp.payee/address (:address payee))
      (:phone payee)         (assoc :stp.payee/phone (:phone payee))
      (:email payee)         (assoc :stp.payee/email (:email payee))
      (:country-code payee)  (assoc :stp.payee/country-code
                                    (:country-code payee)))))

;; ============================================================================
;; Pay-event payload
;; ============================================================================

(defn pay-event
  "Build a structured STP Phase 2 pay-event payload (the input to a
   downstream serializer / SBR-adapter).

   Required keys:
     :abn               employer's 11-digit ABN
     :pay-period-start  java.util.Date
     :pay-period-end    java.util.Date
     :submission-date   java.util.Date (the submit timestamp)
     :pay-date          java.util.Date (the date payment hits employees)
     :payees            vector of `payee-payload` results

   Optional keys:
     :bms-id            ATO Business Management Software identifier
                        (consumer holds — vendor-issued)
     :submission-id     consumer-deterministic submission identifier
     :branch-code       3-digit branch code (defaults '001')
     :final-event?      true on the final event of the year (the
                        STP equivalent of the old payment-summary
                        annual report)
     :update-event?     true when amending a prior submission
     :country-code      ISO-3166-1 alpha-2 (defaults 'AU')

   Returns a map ready for serialization."
  [{:keys [abn pay-period-start pay-period-end submission-date
           pay-date payees bms-id submission-id branch-code
           final-event? update-event? country-code]
    :or {branch-code "001"
         country-code "AU"
         final-event? false
         update-event? false}}]
  (when-not abn               (throw (ex-info ":abn required" {})))
  (when-not pay-period-start  (throw (ex-info ":pay-period-start required" {})))
  (when-not pay-period-end    (throw (ex-info ":pay-period-end required" {})))
  (when-not submission-date   (throw (ex-info ":submission-date required" {})))
  (when-not pay-date          (throw (ex-info ":pay-date required" {})))
  (when (empty? payees)       (throw (ex-info ":payees must be non-empty" {})))
  {:stp.event/version "2.0"
   :stp.event/abn abn
   :stp.event/branch-code branch-code
   :stp.event/bms-id bms-id
   :stp.event/submission-id submission-id
   :stp.event/country-code country-code
   :stp.event/pay-period-start (fmt-date pay-period-start)
   :stp.event/pay-period-end (fmt-date pay-period-end)
   :stp.event/submission-date (fmt-date submission-date)
   :stp.event/pay-date (fmt-date pay-date)
   :stp.event/final-event? final-event?
   :stp.event/update-event? update-event?
   :stp.event/payee-count (count payees)
   :stp.event/total-gross
   ;; Sum of per-payee :stp.payee/income-sum (sum of all payment
   ;; income-types: gross + OTE + overtime + bonus-commission +
   ;; directors-fees + paid-leave + allowance). Mirrors the BIG
   ;; Pay-Event-Total-Gross-Payments field.
   (bd-fmt
    (reduce (fn [^BigDecimal a payee]
              (let [g (or (:stp.payee/income-sum payee) "0")]
                (bd-add a g)))
            0M payees))
   :stp.event/total-paygw
   (bd-fmt
    (reduce (fn [^BigDecimal a payee]
              (let [w (-> payee :stp.payee/period-totals (:stp/paygw "0"))]
                (bd-add a w)))
            0M payees))
   :stp.event/payees payees})

(defn pay-event->summary-string
  "Human-readable single-line summary of a pay-event. Used in
   `:kontor.audit-doc/description` so the audit chain row carries an
   immediately-useful glance value."
  [event]
  (format
   "STP Phase 2 pay-event: ABN %s, period %s..%s, paid %s, %d payee(s), gross %s, PAYGW %s%s%s"
   (:stp.event/abn event)
   (:stp.event/pay-period-start event)
   (:stp.event/pay-period-end event)
   (:stp.event/pay-date event)
   (:stp.event/payee-count event)
   (:stp.event/total-gross event)
   (:stp.event/total-paygw event)
   (if (:stp.event/final-event? event) " [FINAL]" "")
   (if (:stp.event/update-event? event) " [UPDATE]" "")))

;; ============================================================================
;; Update event helper (per ATO BIG §9.2 — update events for prior
;; period corrections)
;; ============================================================================

(defn update-event
  "Build an STP update event payload — the amend-prior-period
   correction shape. Functionally similar to `pay-event` but with
   `:update-event? true` and typically `:pay-date` equal to the
   submission-date (the correction is reported at fix time, not
   the original pay-date).

   Per ATO BIG §9.2 the update event reports the NEW values
   (year-to-date totals reflect the correction); the engine handles
   the difference math + carries the cumulative effect."
  [opts]
  (pay-event (assoc opts :update-event? true)))

;; ============================================================================
;; Convenience: walk a vector of PayrollFacts → vector of payees
;; ============================================================================

(defn facts->payees
  "Materialize a vector of payee payloads from a vector of
   PayrollFacts. The `:payees-info` map keys on employment-eid; it
   carries the per-payee non-payroll inputs (TFN / names / tax-
   treatment-code / YTD). `kontor.payroll-au.emit` walks this fn for
   the canonical wiring."
  [{:keys [facts payees-info income-type-fn]}]
  (mapv (fn [fact]
          (let [emp (:employment fact)
                pi (get payees-info emp)]
            (when-not pi
              (throw (ex-info "No :payees-info entry for employment"
                              {:employment emp})))
            (payee-payload {:payee pi
                            :fact fact
                            :income-type-fn income-type-fn
                            :ytd (:ytd pi)})))
        facts))

;; ============================================================================
;; Convenience: TFN structural check (algorithmic, fact-based)
;; ============================================================================
;; The TFN check-digit is a public algorithm (ATO TFN Algorithm).
;; Each of the 9 digits is multiplied by a positional weight
;; [1 4 3 7 5 8 6 9 10]; the sum is valid iff (sum mod 11) = 0.
;; Algorithms are not copyrightable; this implementation is
;; independent.

(def ^:private tfn-weights [1 4 3 7 5 8 6 9 10])

(defn valid-tfn?
  "True iff `s` is a structurally-valid Australian Tax File Number
   (9 digits, weighted mod-11 check). Spaces / hyphens are tolerated.

   The all-zero TFN (000000000) is rejected explicitly even though
   it satisfies the algebraic mod-11 = 0 condition — the ATO does
   not issue an all-zero TFN; treating it as 'valid' is a common
   implementation trap."
  [s]
  (boolean
   (and (string? s)
        (let [d (str/replace s #"[\s\-]" "")]
          (and (= 9 (count d))
               (every? #(<= (long \0) (long %) (long \9)) d)
               (not= "000000000" d)
               (let [ds (mapv #(- (long %) (long \0)) d)
                     sum (reduce + (map * ds tfn-weights))]
                 (zero? (mod sum 11))))))))

(defn assert-tfn!
  "Throws ex-info on a structurally-invalid TFN; returns the input
   on success."
  [s]
  (when-not (valid-tfn? s)
    (throw (ex-info "Invalid TFN (Tax File Number)"
                    {:value s
                     :expected-format "9 digits, ATO weighted mod-11 check"})))
  s)
