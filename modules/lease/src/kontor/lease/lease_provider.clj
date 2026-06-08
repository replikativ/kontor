(ns kontor.lease.lease-provider
  "The `LeaseProvider` protocol + the companion-shipped built-in —
   ADR-063.

   Sibling of `kontor.asset.depreciation-provider` (ADR-055): the
   companion ships the *protocol* + the effective-interest built-in;
   an l10n module could ship a jurisdiction-specific impl and pass it
   to the runner directly.

   ## What a LeaseProvider computes

   `plan-schedule` takes a `:lease-liability` book and returns the
   liability unwind — per period: the cash `:payment`, its `:interest`
   and `:principal` split, and the `:balance-remaining`. It also
   returns `:straight-line-expense` — the single periodic lease cost
   an OPERATING lease recognises (ASC 842-20-25-6). The operating-
   lease ROU 'plug' (`kontor.lease.rou-provider`) is
   `straight-line-expense − interest` each period; a FINANCE lease
   ignores it and depreciates the ROU asset straight-line.

   PURE — reads a `db` value, transacts nothing. The unwind is fully
   deterministic from the book's `:opening-liability` + `:discount-
   rate` + the lease's payment terms, so a re-plan mid-run reproduces
   the already-fired periods bit-exact (ADR-063 has
   `:opening-fired-through` = 0 always; ADR-064's modifications move
   it forward and re-anchor `:opening-liability`)."
  (:require [kontor.lease.liability :as liability]
            [kontor.workflow.schedule :as schedule])
  (:import [java.math BigDecimal RoundingMode]))

;; ============================================================================
;; Protocol
;; ============================================================================

(defprotocol LeaseProvider
  "Compute the liability unwind for one (lease, ledger) book."

  (provider-id [provider]
    "A keyword identifying this provider impl — matches the
     `:kontor.lease-liability/provider-id` stored on the book.")

  (plan-schedule [provider db liability-book]
    "Given a `db` value and a `:lease-liability` book (eid or
     `[lease ledger]` pair), return the forward unwind:

       {:periods [{:sequence          long      ; 1-indexed
                   :date              #inst     ; valid-time of the payment
                   :payment           bigdec    ; cash outflow this period
                   :interest          bigdec    ; the finance charge
                   :principal         bigdec    ; liability reduction
                   :balance-remaining bigdec    ; liability after this period
                   :fired?            boolean}  ; already in the occurrence log
                  ...]
        :total-interest        bigdec
        :total-payments        bigdec
        :straight-line-expense bigdec           ; the operating-lease single cost
        :provider-id           keyword}

     The `:periods` cover sequence `(opening-fired-through + 1) … n`
     — the un-fired tail. PURE."))

;; ============================================================================
;; Effective-interest — the built-in
;; ============================================================================

(defn- round2 ^BigDecimal [^BigDecimal x]
  (.setScale x 2 RoundingMode/HALF_EVEN))

(defn- unwind
  "Walk the liability from `opening-liability` for periods
   `(ofthr+1) … n`. Each non-final period pays the level
   `payment-amount` split into `interest = round2(balance ×
   period-rate)` and `principal = payment − interest`; the FINAL
   period drives the balance exactly to zero (`principal = balance`,
   `payment = interest + principal`), absorbing the rounding drift.

   `:in-advance` period 1 (only when nothing is yet fired — the
   contract's first payment, made AT commencement) carries zero
   interest; after a modification (`ofthr > 0`) the first un-fired
   period is mid-lease and accrues interest normally."
  [{:keys [opening-liability period-rate payment-amount payment-timing
           n-periods opening-fired-through start-date payment-frequency]}]
  (let [ofthr (or opening-fired-through 0)
        in-advance? (= payment-timing :in-advance)]
    (loop [seq (inc ofthr)
           balance ^BigDecimal opening-liability
           acc []]
      (if (> seq n-periods)
        acc
        (let [first-unfired? (= seq (inc ofthr))
              interest (if (and in-advance? (zero? ofthr) first-unfired?)
                         0M
                         (round2 (.multiply balance ^BigDecimal period-rate)))
              last? (= seq n-periods)
              principal (if last?
                          balance
                          (.subtract ^BigDecimal payment-amount interest))
              payment (if last?
                        (.add interest principal)
                        payment-amount)
              balance' (.subtract balance principal)]
          (recur (inc seq)
                 balance'
                 (conj acc {:sequence          seq
                            :date              (schedule/date-of-occurrence
                                                start-date payment-frequency seq)
                            :payment           payment
                            :interest          interest
                            :principal         principal
                            :balance-remaining balance'})))))))

(defn- straight-line-expense
  "The single periodic lease cost an OPERATING lease recognises
   (ASC 842-20-25-6): `(total undiscounted payments + initial direct
   costs + prepaid − incentives) / n`.

   This is the *as-originally-measured* (commencement) figure — it is
   computed from the book's current `:payment-amount` × `n-periods`,
   which is only the whole-term figure before any modification. After
   an ADR-064 modification ASC 842 re-levels the single cost over the
   remaining term; `kontor.lease.rou-provider` does that re-levelling
   itself (it can — it sees the ROU book; this provider cannot) and
   does NOT read this field. It is kept as a reporting convenience for
   the un-modified case."
  ^BigDecimal [{:keys [payment-amount n-periods initial-direct-costs
                       prepaid-at-commencement incentives-received]}]
  (let [total-payments (.multiply ^BigDecimal payment-amount
                                  (BigDecimal/valueOf (long n-periods)))
        numerator (-> total-payments
                      (.add ^BigDecimal (or initial-direct-costs 0M))
                      (.add ^BigDecimal (or prepaid-at-commencement 0M))
                      (.subtract ^BigDecimal (or incentives-received 0M)))]
    (round2 (.divide numerator (BigDecimal/valueOf (long n-periods))
                     12 RoundingMode/HALF_EVEN))))

(defrecord EffectiveInterestProvider []
  LeaseProvider
  (provider-id [_] :effective-interest)
  (plan-schedule [_ db book]
    (let [inputs (liability/book-plan-inputs db book)
          schedule-eid (:schedule inputs)
          fired (schedule/fired-sequences db schedule-eid)
          periods (mapv (fn [p] (assoc p :fired? (contains? fired (:sequence p))))
                        (unwind inputs))]
      {:periods               periods
       :total-interest        (reduce (fn [^BigDecimal a p]
                                        (.add a ^BigDecimal (:interest p)))
                                      0M periods)
       :total-payments        (reduce (fn [^BigDecimal a p]
                                        (.add a ^BigDecimal (:payment p)))
                                      0M periods)
       :straight-line-expense (straight-line-expense inputs)
       :provider-id           :effective-interest})))

;; ============================================================================
;; Registry
;; ============================================================================

(def ^:private built-ins
  {:effective-interest ->EffectiveInterestProvider})

(defn provider-for
  "Resolve a `:kontor.lease-liability/provider-id` keyword to a built-in
   `LeaseProvider` instance. An l10n module passes its own impl
   instance directly to the runner instead."
  [provider-id]
  (if-let [ctor (built-ins provider-id)]
    (ctor)
    (throw (ex-info "No built-in LeaseProvider for this id — pass an l10n provider instance directly"
                    {:provider-id provider-id
                     :built-ins   (set (keys built-ins))}))))

(defn plan-for-book
  "Resolve a `:lease-liability` book's `:provider-id` to its
   `LeaseProvider` and return its plan. The common entry point — the
   lease runner and the operating-lease ROU plug both go through
   here."
  [db book-spec]
  (let [inputs (liability/book-plan-inputs db book-spec)
        prov   (provider-for (:provider-id inputs))]
    (plan-schedule prov db (:book inputs))))

;; ============================================================================
;; Query helper
;; ============================================================================

(defn outstanding-liability
  "The carrying amount of a `:lease-liability` book right now: the
   `:balance-remaining` of the highest fired occurrence, or the
   book's `:opening-liability` when nothing has fired yet. Derived
   from the provider's deterministic plan — the fired log only says
   *which* periods have run, not the running balance."
  ^BigDecimal [db book-spec]
  (let [inputs (liability/book-plan-inputs db book-spec)
        plan   (plan-for-book db (:book inputs))
        fired-seqs (->> (:periods plan) (filter :fired?) (map :sequence))]
    (if (seq fired-seqs)
      (let [last-fired (apply max fired-seqs)]
        (->> (:periods plan)
             (filter #(= (:sequence %) last-fired))
             first
             :balance-remaining))
      (:opening-liability inputs))))
