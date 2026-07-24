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

   PURE — reads a `db` value, transacts nothing.

   ## Fired periods are READ, not re-derived (note 198 HIGH-5)

   The un-fired tail is planned deterministically from the book's
   `:opening-liability` + `:discount-rate` + payment terms. The
   ALREADY-FIRED periods are not: they carry the amounts the GL posted,
   read back through the occurrence log
   (`liability/posted-period-legs`). Re-deriving them from current
   contract data was the drift vector — `:kontor.lease/payment-amount`
   is SHARED across a lease's per-ledger books, so any book that
   declines to remeasure (the ASC 842-10-30-5 operating + `:index-reset`
   fork) would have silently restated periods the GL had already posted
   at the old amount, and the liability subledger and the control
   account would part company for good."
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
   `(ofthr+1) … n`.

   An ALREADY-FIRED period carries the amounts the GL actually posted
   (`posted`, from `kontor.lease.liability/posted-period-legs`) — it is
   never re-derived. This is the liability sibling of the ROU plug's
   `fired-amounts` re-levelling, and it is what keeps the subledger tied
   to the control account when the contract facts move under an
   un-remeasured book (note 198 HIGH-5): a payment-amount change is
   PROSPECTIVE by construction, never a silent restatement of what the
   ledger is already carrying.

   An UN-FIRED non-final period pays the level `payment-amount` split
   into `interest = round2(balance × period-rate)` and `principal =
   payment − interest`; the FINAL period drives the balance exactly to
   zero (`principal = balance`, `payment = interest + principal`),
   absorbing the rounding drift.

   `:in-advance` period 1 (the contract's first payment, made AT
   commencement) carries zero interest; after a modification
   (`ofthr > 0`) the first un-fired period is mid-lease and accrues
   interest normally."
  [{:keys [opening-liability period-rate payment-amount payment-timing
           n-periods opening-fired-through start-date payment-frequency]}
   posted]
  (let [ofthr (or opening-fired-through 0)
        in-advance? (= payment-timing :in-advance)]
    (loop [seq (inc ofthr)
           balance ^BigDecimal opening-liability
           acc []]
      (if (> seq n-periods)
        acc
        (let [fired (get posted seq)
              interest (if fired
                         (:interest fired)
                         (if (and in-advance? (= seq 1))
                           0M
                           (round2 (.multiply balance ^BigDecimal period-rate))))
              last? (= seq n-periods)
              principal (cond
                          fired (:principal fired)
                          last? balance
                          :else (.subtract ^BigDecimal payment-amount
                                           ^BigDecimal interest))
              payment (cond
                        fired (:payment fired)
                        last? (.add ^BigDecimal interest ^BigDecimal principal)
                        :else payment-amount)
              balance' (.subtract balance ^BigDecimal principal)]
          (recur (inc seq)
                 balance'
                 (conj acc {:sequence          seq
                            :date              (schedule/date-of-occurrence
                                                start-date payment-frequency seq)
                            :payment           payment
                            :interest          interest
                            :principal         principal
                            :balance-remaining balance'
                            :fired?            (some? fired)})))))))

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
          periods (unwind inputs (liability/posted-period-legs db inputs))]
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
   book's `:opening-liability` when nothing has fired yet.

   The fired periods in the plan carry the amounts the GL ACTUALLY
   posted (see [[unwind]] / `liability/posted-period-legs`), so this is
   `opening-liability − Σ principal relieved` — a NETTING of the ledger,
   not a re-derivation of it from current contract data. It therefore
   ties to the GL control account by construction; a break is a real
   break, and `kontor.lease.report/reconcile-liability` surfaces it."
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
