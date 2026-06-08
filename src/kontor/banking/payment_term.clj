(ns kontor.banking.payment-term
  "Payment-term helpers: derive due-date + discount-deadline from a
   `:payment-term` entity + an effective date. Also ships a small
   library of standard terms (NET14 / NET30 / 2/10-NET30) that
   tenants can install once on conn creation.

   Convention: terms are computed in calendar days (no business-day
   skipping). For DE bookkeeping, this matches the documented
   Zahlungsbedingungen / Skonto practice — the term clock starts on
   the invoice-date and ticks every calendar day."
  (:require [datahike.api :as d])
  (:import [java.time Instant ZoneOffset]
           [java.util Date]))

;; ============================================================================
;; Date math
;; ============================================================================

(defn- ^Date plus-days
  "Return a new Date `n` days after `from`. Calendar arithmetic; UTC."
  [^Date from ^long n]
  (-> (.toInstant from)
      (.atZone ZoneOffset/UTC)
      (.plusDays n)
      .toInstant
      Date/from))

(defn compute-due-date
  "Given an effective date (instant) and a payment-term entity map,
   return the due-date instant. Returns `effective-date` itself when
   the term is missing or has zero net-days (`:due-on-receipt`)."
  [^Date effective-date payment-term]
  (when effective-date
    (let [n (or (:kontor.payment-term/net-days payment-term) 0)]
      (if (zero? n) effective-date (plus-days effective-date n)))))

(defn compute-discount-deadline
  "Return the last day the early-pay discount applies, or nil if the
   term has no discount."
  [^Date effective-date payment-term]
  (when (and effective-date
             (:kontor.payment-term/discount-pct payment-term)
             (:kontor.payment-term/discount-days payment-term))
    (plus-days effective-date (:kontor.payment-term/discount-days payment-term))))

(defn apply-term
  "Return a transaction-attribute map fragment (plain map, not
   tx-data) populating :kontor.transaction/payment-term, :due-date, and
   :discount-deadline given an effective-date + payment-term entity.
   Caller merges this into their transaction map before transact."
  [^Date effective-date payment-term]
  (cond-> {:kontor.transaction/payment-term (:db/id payment-term)
           :kontor.transaction/due-date     (compute-due-date effective-date payment-term)}
    (compute-discount-deadline effective-date payment-term)
    (assoc :kontor.transaction/discount-deadline
           (compute-discount-deadline effective-date payment-term))))

;; ============================================================================
;; Standard library of terms
;; ============================================================================

(def standard-terms
  "Common payment terms. Install via `install-standard-terms!`.
   Tenants can add more; these are the universal ones."
  [{:kontor.payment-term/code         "DUE-ON-RECEIPT"
    :kontor.payment-term/name         "Zahlbar sofort"
    :kontor.payment-term/net-days     0
    :kontor.payment-term/active       true}
   {:kontor.payment-term/code         "NET7"
    :kontor.payment-term/name         "7 Tage netto"
    :kontor.payment-term/net-days     7
    :kontor.payment-term/active       true}
   {:kontor.payment-term/code         "NET14"
    :kontor.payment-term/name         "14 Tage netto"
    :kontor.payment-term/net-days     14
    :kontor.payment-term/active       true}
   {:kontor.payment-term/code         "NET30"
    :kontor.payment-term/name         "30 Tage netto"
    :kontor.payment-term/net-days     30
    :kontor.payment-term/active       true}
   {:kontor.payment-term/code         "NET60"
    :kontor.payment-term/name         "60 Tage netto"
    :kontor.payment-term/net-days     60
    :kontor.payment-term/active       true}
   {:kontor.payment-term/code         "2/10-NET30"
    :kontor.payment-term/name         "2% Skonto bei Zahlung in 10 Tagen, sonst 30 Tage netto"
    :kontor.payment-term/net-days     30
    :kontor.payment-term/discount-pct 2.0M
    :kontor.payment-term/discount-days 10
    :kontor.payment-term/active       true}
   {:kontor.payment-term/code         "3/10-NET30"
    :kontor.payment-term/name         "3% Skonto bei Zahlung in 10 Tagen, sonst 30 Tage netto"
    :kontor.payment-term/net-days     30
    :kontor.payment-term/discount-pct 3.0M
    :kontor.payment-term/discount-days 10
    :kontor.payment-term/active       true}])

(defn install-standard-terms!
  "Idempotently transact the standard payment-term entities into
   `conn`. The :kontor.payment-term/code uniqueness means re-installing is
   a no-op."
  [conn]
  (d/transact conn standard-terms))

(defn by-code
  "Look up a payment-term entity by its code, returning the pulled
   map (with :db/id) or nil."
  [db ^String code]
  (when-let [eid (d/q '[:find ?e .
                        :in $ ?code
                        :where [?e :kontor.payment-term/code ?code]]
                      db code)]
    (d/pull db [:db/id :kontor.payment-term/code :kontor.payment-term/name
                :kontor.payment-term/net-days :kontor.payment-term/discount-pct
                :kontor.payment-term/discount-days]
            eid)))
