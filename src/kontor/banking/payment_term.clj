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
;; Instalment tranches — note 198 R3-FP-04
;;
;; A term with no `:kontor.payment-term-line` entities is the scalar
;; net-days case and explodes into exactly one tranche for the full amount,
;; so `compute-tranches` is safe to call on every term. A term WITH lines
;; explodes into one tranche per line, each carrying its own due date — which
;; is what lets the tranches age and settle independently in AR aging and
;; payment application.
;; ============================================================================

(def value-types
  "Closed vocabulary for `:kontor.payment-term-line/value-type`."
  #{:percent :fixed :balance})

(defn- term-eid
  "Coerce `term` (eid, `:kontor.payment-term/code` string, or pulled map) to
   an entity-id."
  [db term]
  (cond
    (string? term) (d/q '[:find ?e . :in $ ?c
                          :where [?e :kontor.payment-term/code ?c]]
                        db term)
    (map? term)    (:db/id term)
    :else          term))

(defn- term-entity
  "The pulled term map — passthrough when `term` already is one."
  [db term]
  (if (map? term)
    term
    (if-let [eid (term-eid db term)]
      (d/pull db [:db/id :kontor.payment-term/net-days] eid)
      {})))

(defn term-lines
  "The `:payment-term-line` entities of `term` (eid, code string, or pulled
   map), ordered by sequence. Empty for a scalar net-days term."
  [db term]
  (let [eid (term-eid db term)]
    (when eid
      (->> (d/q '[:find [?l ...]
                  :in $ ?t
                  :where [?l :kontor.payment-term-line/term ?t]]
                db eid)
           (map #(d/pull db [:db/id
                             :kontor.payment-term-line/sequence
                             :kontor.payment-term-line/value-type
                             :kontor.payment-term-line/value
                             :kontor.payment-term-line/nb-days]
                         %))
           (sort-by (juxt #(or (:kontor.payment-term-line/sequence %) 0) :db/id))
           vec))))

(defn- line-amount
  "The raw amount a single non-`:balance` line claims of `total`."
  [line ^java.math.BigDecimal total ^long scale]
  (let [v (:kontor.payment-term-line/value line)]
    (case (:kontor.payment-term-line/value-type line)
      :percent (.setScale (.divide (.multiply total ^java.math.BigDecimal v)
                                   100M
                                   (java.math.MathContext. 34))
                          scale java.math.RoundingMode/HALF_EVEN)
      :fixed   (.setScale ^java.math.BigDecimal v scale
                          java.math.RoundingMode/HALF_EVEN)
      (throw (ex-info "Unknown payment-term-line value-type"
                      {:value-type (:kontor.payment-term-line/value-type line)
                       :supported  value-types})))))

(defn compute-tranches
  "Explode `total` into instalment tranches for `term`, due-dated from
   `effective-date`.

   Returns a vector of
     `{:sequence :amount :due-date :value-type :line}`
   ordered by sequence. `scale` (decimal places, default 2) controls
   rounding.

   The LAST tranche absorbs the rounding residue so the tranches sum to
   `total` EXACTLY — a 1/3 split of 100.00 yields 33.33 / 33.33 / 33.34, never
   99.99. A `:balance` line is by definition the residue; when no line is
   `:balance` the final line still absorbs it, because an instalment plan that
   does not add up to the invoice is a silent shortfall in AR, not a rounding
   detail.

   A term with no lines yields one tranche: the full amount, due
   `effective-date + :net-days`."
  ([db term ^Date effective-date total]
   (compute-tranches db term effective-date total 2))
  ([db term ^Date effective-date total scale]
   (let [total (bigdec total)
         lines (term-lines db term)]
     (if (empty? lines)
       [{:sequence   0
         :amount     total
         :due-date   (compute-due-date effective-date (term-entity db term))
         :value-type :balance
         :line       nil}]
       (let [n     (count lines)
             final (last lines)]
         (when-let [bad (some (fn [l] (when (and (not= l final)
                                                 (= :balance (:kontor.payment-term-line/value-type l)))
                                        l))
                              lines)]
           (throw (ex-info "Only the LAST payment-term line may be :balance — an
                            earlier :balance leaves the following tranches with
                            nothing to claim"
                           {:line (:db/id bad) :term term})))
         (let [claimed (reduce (fn [^java.math.BigDecimal a line]
                                 (.add a ^java.math.BigDecimal (line-amount line total scale)))
                               0M (butlast lines))
               residue (.subtract total claimed)]
           (vec (map-indexed
                 (fn [i line]
                   {:sequence   (or (:kontor.payment-term-line/sequence line) i)
                    ;; the final tranche is the residue, so the plan always
                    ;; sums to `total` exactly
                    :amount     (if (= i (dec n)) residue (line-amount line total scale))
                    :due-date   (plus-days effective-date
                                           (or (:kontor.payment-term-line/nb-days line) 0))
                    :value-type (:kontor.payment-term-line/value-type line)
                    :line       (:db/id line)})
                 lines))))))))

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
