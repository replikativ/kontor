(ns kontor.lease.core
  "kontor-lease — the :lease contract + lifecycle + the short-term /
   low-value exemption path (ADR-062).

   `define-lease!` records a :lease at `:draft` (framework-neutral
   contract facts). ADR-063's `commence!` does the balance-sheet
   recognition (the ROU :asset + the :lease-liability book + the
   initial-recognition GL entry) and moves it `:draft → :active`.

   The exemption path is deliberately separate: a short-term
   (≤12-month) or low-value lease has no balance-sheet footprint —
   it is a straight-line expense, so `register-exempt-lease!` just
   creates a plain `:schedule` (`:schedule/kind :lease-expense`) and
   `plan-exempt-lease-charge` builds the per-period expense posting.
   No `:lease` entity is involved."
  (:require [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.posting :as posting]
            [kontor.schedule :as schedule]
            [kontor.status-machine :as sm]
            [kontor.validation :as validation])
  (:import [java.math BigDecimal RoundingMode]
           [java.util Date]))

;; ============================================================================
;; Resolution / queries
;; ============================================================================

(defn by-code
  "Resolve a :lease eid by :lease/code."
  [db code]
  (d/q '[:find ?e . :in $ ?c :where [?e :lease/code ?c]] db code))

(defn resolve-lease
  "Coerce `spec` to a :lease eid (string → by-code)."
  [db spec]
  (cond
    (nil? spec)    nil
    (string? spec) (by-code db spec)
    :else          spec))

(defn pull-lease
  "Pull a :lease (by code or eid) with its lessor + ROU asset."
  [db spec]
  (when-let [eid (resolve-lease db spec)]
    (d/pull db
            '[* {:lease/lessor [:db/id :kontor.partner/external-id :kontor.partner/name]
                 :lease/rou-asset [:db/id :asset/code :asset/status]
                 :lease/asset-class [:db/id :asset-class/code]}]
            eid)))

;; ============================================================================
;; present-value — the lease-liability PV at commencement (ADR-063)
;; ============================================================================

(defn periods-per-year ^long [frequency]
  (case frequency :monthly 12 :quarterly 4 :annual 1
        (throw (ex-info "unsupported :frequency" {:frequency frequency}))))

(defn periods-for
  "Number of payment periods over `term-months` at `frequency`."
  ^long [^long term-months frequency]
  (case frequency
    :monthly   term-months
    :quarterly (long (Math/ceil (/ term-months 3.0)))
    :annual    (long (Math/ceil (/ term-months 12.0)))
    (throw (ex-info "unsupported :frequency"
                    {:frequency frequency
                     :supported #{:monthly :quarterly :annual}}))))

(defn present-value
  "Present value of a level lease — `n` payments of `payment` at the
   per-period rate `period-rate`, plus an optional `:final-value`
   (a reasonably-certain purchase-option price) discounted from
   period n. `:in-advance` (annuity-due — payment at the start of the
   period) discounts each payment one period less than `:in-arrears`
   (ordinary annuity). Returns a bigdec rounded to 2dp.

   This is the liability measurement at commencement; `commence!`
   stores it as `:lease-liability/opening-liability`."
  ([payment period-rate n timing]
   (present-value payment period-rate n timing {}))
  ([^BigDecimal payment ^BigDecimal period-rate n timing {:keys [final-value]}]
   (let [one-plus-r (.add 1M period-rate)
         ;; df starts at (1+r)^0; after k divisions df = (1+r)^-k.
         [pv-payments df-n]
         (loop [k 1, df 1M, acc 0M]
           (if (> (long k) (long n))
             [acc df]
             (let [df-after (.divide ^BigDecimal df one-plus-r
                                     12 RoundingMode/HALF_EVEN)
                   ;; in-arrears: payment k at time k → (1+r)^-k = df-after
                   ;; in-advance: payment k at time k-1 → (1+r)^-(k-1) = df
                   factor (if (= timing :in-advance) df df-after)]
               (recur (inc k) df-after
                      (.add ^BigDecimal acc (.multiply payment ^BigDecimal factor))))))
         pv-final (if final-value
                    (.multiply ^BigDecimal final-value ^BigDecimal df-n)
                    0M)]
     (.setScale (.add ^BigDecimal pv-payments ^BigDecimal pv-final)
                2 RoundingMode/HALF_EVEN))))

;; ============================================================================
;; define-lease!
;; ============================================================================

(declare define-lease-tx-data register-exempt-lease-tx-data)

(defn define-lease!
  "Record a :lease at `:draft` — the contract facts, before
   balance-sheet recognition. ADR-063's `commence!` moves it
   `:draft → :active`. The `:changed-by-uid` actor is stamped as
   `:kontor.audit/create-uid` so the ADR-038 `:no-self-approval` rule can fire on
   a later termination. Returns the tx-report.

   Required opts: :code, :name, :lessor, :asset-class,
                  :commencement-date, :term-months, :payment-amount,
                  :payment-frequency (#{:monthly :quarterly :annual}),
                  :payment-timing (#{:in-advance :in-arrears}),
                  :commodity, :discount-rate.
   Optional: :underlying-asset-desc, :initial-direct-costs,
             :prepaid-at-commencement, :incentives-received,
             :purchase-option-price, :entity, :origin-document,
             :note, :changed-by-uid, :vt-from / :vt-to (default
             :vt-from = :commencement-date)."
  [conn {:keys [vt-from vt-to commencement-date] :as opts}]
  (let [now (Date.)]
    (validation/transact-with-validation
     conn (kbt/with-vt (define-lease-tx-data
                         (d/db conn) (assoc opts :recorded-at now))
                       (or vt-from commencement-date)
                       (or vt-to kbt/forever)))))

(defn define-lease-tx-data
  "Pure tx-data builder for `define-lease!` (ADR-068). Optional
   `:tempid` (default `\"lease-1\"`) and `:recorded-at` (default now).

   For an imported (mid-life) lease, pass `:imported? true` plus the
   ADR-069 audit denorms `:imported-as-of`,
   `:imported-original-commencement-date`,
   `:imported-original-term-months` — those are recorded on the
   :lease so `import-lease!` (ADR-069) can validate them later."
  [_db {:keys [code name lessor asset-class commencement-date term-months
               payment-amount payment-frequency payment-timing commodity
               discount-rate underlying-asset-desc initial-direct-costs
               prepaid-at-commencement incentives-received
               purchase-option-price entity origin-document note
               imported? imported-as-of imported-original-commencement-date
               imported-original-term-months
               changed-by-uid tempid recorded-at]
        :or {tempid "lease-1"}}]
  (when-not code              (throw (ex-info ":code required" {})))
  (when-not name              (throw (ex-info ":name required" {})))
  (when-not lessor            (throw (ex-info ":lessor required" {})))
  (when-not asset-class       (throw (ex-info ":asset-class required" {})))
  (when-not commencement-date (throw (ex-info ":commencement-date required" {})))
  (when-not term-months       (throw (ex-info ":term-months required" {})))
  (when-not (and (integer? term-months) (pos? term-months))
    (throw (ex-info ":term-months must be a positive integer — a 0/negative term has no schedule"
                    {:term-months term-months})))
  (when (nil? payment-amount) (throw (ex-info ":payment-amount required" {})))
  (when-not (pos? (.signum ^BigDecimal payment-amount))
    (throw (ex-info ":payment-amount must be positive"
                    {:payment-amount payment-amount})))
  (when-not (#{:monthly :quarterly :annual} payment-frequency)
    (throw (ex-info ":payment-frequency must be :monthly | :quarterly | :annual"
                    {:payment-frequency payment-frequency})))
  (when-not (#{:in-advance :in-arrears} payment-timing)
    (throw (ex-info ":payment-timing must be :in-advance | :in-arrears"
                    {:payment-timing payment-timing})))
  (when-not commodity         (throw (ex-info ":commodity required" {})))
  (when (nil? discount-rate)  (throw (ex-info ":discount-rate required" {})))
  (when (neg? (.signum ^BigDecimal discount-rate))
    (throw (ex-info ":discount-rate must be non-negative"
                    {:discount-rate discount-rate})))
  (let [row (cond-> {:db/id tempid
                     :lease/code code
                     :lease/name name
                     :lease/lessor lessor
                     :lease/asset-class asset-class
                     :lease/commencement-date commencement-date
                     :lease/term-months term-months
                     :lease/payment-amount payment-amount
                     :lease/payment-frequency payment-frequency
                     :lease/payment-timing payment-timing
                     :lease/commodity commodity
                     :lease/discount-rate discount-rate
                     :lease/status :draft}
              underlying-asset-desc   (assoc :lease/underlying-asset-desc
                                             underlying-asset-desc)
              initial-direct-costs    (assoc :lease/initial-direct-costs
                                             initial-direct-costs)
              prepaid-at-commencement (assoc :lease/prepaid-at-commencement
                                             prepaid-at-commencement)
              incentives-received     (assoc :lease/incentives-received
                                             incentives-received)
              purchase-option-price   (assoc :lease/purchase-option-price
                                             purchase-option-price)
              entity                  (assoc :lease/entity entity)
              origin-document         (assoc :lease/origin-document origin-document)
              note                    (assoc :lease/note note)
              imported?               (assoc :lease/imported? imported?)
              imported-as-of          (assoc :lease/imported-as-of imported-as-of)
              imported-original-commencement-date
              (assoc :lease/imported-original-commencement-date
                     imported-original-commencement-date)
              imported-original-term-months
              (assoc :lease/imported-original-term-months
                     imported-original-term-months)
              ;; The recording actor IS the creator — stamp :kontor.audit/create-uid
              ;; so ADR-038 :no-self-approval can fire on termination.
              changed-by-uid          (assoc :kontor.audit/create-uid changed-by-uid))
        ;; status-tx needs `db` for the legal-transition check; we
        ;; pass nil because `:from :nil :to :draft` is the very first
        ;; entry — record-status-change-tx-data tolerates this when
        ;; the from is explicit. (See identical pattern in
        ;; retention/define-policy-tx-data.)
        status-tx (sm/record-status-change-tx-data
                   _db (cond-> {:entity tempid
                                :entity-type :lease
                                :facet :lease/status
                                :from :nil :to :draft
                                :changed-at (or recorded-at (Date.))
                                :reason :lease-recorded}
                         changed-by-uid (assoc :changed-by-uid changed-by-uid)))]
    (into [row] status-tx)))

;; ============================================================================
;; The short-term / low-value exemption path — a plain :schedule
;; ============================================================================

(defn register-exempt-lease!
  "Register a short-term (≤12-month) or low-value lease — which has
   NO balance-sheet footprint, hence NO `:lease` entity. Creates a
   plain `:schedule` (`:schedule/kind :lease-expense`) whose
   `:schedule/total-amount` is the total undiscounted payments;
   `plan-exempt-lease-charge` builds each period's straight-line
   expense posting, fired by the generic `kontor.schedule`
   mechanism. Returns the tx-report.

   Required: :code (schedule code), :total-payments (the total
             undiscounted amount over the term), :commodity,
             :start-date, :term-months.
   Optional: :frequency (default :monthly), :name, :note."
  [conn opts]
  (validation/transact-with-validation
   conn (register-exempt-lease-tx-data (d/db conn) opts)))

(defn register-exempt-lease-tx-data
  "Pure tx-data builder for `register-exempt-lease!` (ADR-068)."
  [_db {:keys [code total-payments commodity start-date term-months
               frequency name note]
        :or {frequency :monthly}}]
  (when-not code           (throw (ex-info ":code required" {})))
  (when (nil? total-payments) (throw (ex-info ":total-payments required" {})))
  (when-not commodity      (throw (ex-info ":commodity required" {})))
  (when-not start-date     (throw (ex-info ":start-date required" {})))
  (when-not term-months    (throw (ex-info ":term-months required" {})))
  (let [n (periods-for term-months frequency)
        end-date (schedule/date-of-occurrence start-date frequency n)]
    [(cond-> {:schedule/code code
              :schedule/kind :lease-expense
              :schedule/start-date start-date
              :schedule/end-date end-date
              :schedule/frequency frequency
              :schedule/total-amount total-payments
              :schedule/total-commodity commodity
              :schedule/state :active
              :schedule/active true}
       name (assoc :schedule/name name)
       note (assoc :schedule/note note))]))

(defn exempt-lease-period-amount
  "The straight-line per-period expense for an exempt lease's
   `:schedule` — `:schedule/total-amount / n-periods`, the last
   period absorbing the rounding remainder. `sequence` is 1-indexed.
   Returns a bigdec."
  ^BigDecimal [db schedule-spec ^long sequence]
  (let [sched-eid (schedule/resolve-schedule db schedule-spec)
        s (d/pull db [:schedule/total-amount :schedule/start-date
                      :schedule/end-date :schedule/frequency]
                  sched-eid)
        total ^BigDecimal (:schedule/total-amount s)
        freq (:schedule/frequency s)
        ;; n-periods = how many occurrences from start to end inclusive.
        n (loop [k 1]
            (if (pos? (.compareTo (schedule/date-of-occurrence
                                   (:schedule/start-date s) freq k)
                                  (:schedule/end-date s)))
              (dec k)
              (recur (inc k))))
        per (.setScale (.divide total (BigDecimal/valueOf n) 12 RoundingMode/HALF_EVEN)
                       2 RoundingMode/HALF_EVEN)]
    (if (= sequence n)
      (.subtract total (.multiply per (BigDecimal/valueOf (dec n))))
      per)))

(defn plan-exempt-lease-charge
  "Build one period's straight-line lease-expense posting for an
   exempt lease: `Dr <lease-expense-account> / Cr <credit-account>`
   (cash, or a payable). Returns sealed tx-data ready for
   `kontor.schedule/record-occurrence!` — the GL transaction is at
   tempid -1, so it composes with `record-occurrence!`'s back-ref.

   Required: :amount, :commodity, :journal, :date,
             :lease-expense-account, :credit-account.
   Optional: :narration."
  [{:keys [amount commodity journal date lease-expense-account
           credit-account narration]}]
  (when (nil? amount)           (throw (ex-info ":amount required" {})))
  (when-not commodity           (throw (ex-info ":commodity required" {})))
  (when-not journal             (throw (ex-info ":journal required" {})))
  (when-not date                (throw (ex-info ":date required" {})))
  (when-not lease-expense-account (throw (ex-info ":lease-expense-account required" {})))
  (when-not credit-account      (throw (ex-info ":credit-account required" {})))
  (posting/build-transaction
   {:transaction (cond-> {:kontor.transaction/journal journal
                          :kontor.transaction/effective-date date
                          :kontor.transaction/state :posted
                          :kontor.transaction/posted-at date}
                   narration (assoc :kontor.transaction/narration narration))
    :postings [{:kontor.posting/account lease-expense-account
                :kontor.posting/amount amount
                :kontor.posting/commodity commodity
                :kontor.posting/posted-at date}
               {:kontor.posting/account credit-account
                :kontor.posting/amount (.negate ^BigDecimal amount)
                :kontor.posting/commodity commodity
                :kontor.posting/posted-at date}]}))
