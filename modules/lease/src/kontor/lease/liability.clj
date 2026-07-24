(ns kontor.lease.liability
  "kontor-lease — the `:lease-liability` per-(lease, ledger) book
   lifecycle (ADR-063).

   Sibling of `kontor.asset.depreciation`. A `:lease-liability` book
   is per-(lease, ledger): IFRS 16, ASC 842 and a local-GAAP view are
   each a `:ledger` (ADR-021), and the SAME lease is classified
   `:finance` on one and `:operating` on another — so classification
   lives HERE, on the book, not on the framework-neutral `:lease`.

   Each book owns one ADR-032 `:schedule` (`:kontor.schedule/kind
   :lease-liability`) that the lease runner (ADR-063) fires. This
   namespace is the book lifecycle + the flat `book-plan-inputs` map
   the `LeaseProvider` impls consume; it has NO dependency on the
   provider (mirrors `kontor.asset.depreciation` ↔
   `kontor.asset.depreciation-provider`)."
  (:require [datahike.api :as d]
            [kontor.lease.core :as lease]
            [kontor.workflow.schedule :as schedule]
            [kontor.validation :as validation]))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn book-for
  "Resolve the `:lease-liability` book eid for a (lease, ledger) pair,
   or nil. `lease-spec` is a code or eid; `ledger` is an eid.

   A nil `ledger` THROWS. Passing one through to the query does not
   narrow the join — datahike leaves the variable unbound — so
   `:find ?e .` then returns an ARBITRARY book of the lease. On a
   single-book lease that is silently right; on the ADR-021
   parallel-ledger shape it hands back the wrong framework's book and
   nothing downstream can tell. Note 198 (found via the ROU plug,
   which was destructuring a `:ledger` key `book-plan-inputs` never
   supplied)."
  [db lease-spec ledger]
  (when (nil? ledger)
    (throw (ex-info "book-for: :ledger is required — a nil ledger would silently return an arbitrary book of the lease"
                    {:type :kontor.lease/ledger-required :lease lease-spec})))
  (when-let [lease-eid (lease/resolve-lease db lease-spec)]
    (d/q '[:find ?e .
           :in $ ?l ?led
           :where
           [?e :kontor.lease-liability/lease ?l]
           [?e :kontor.lease-liability/ledger ?led]]
         db lease-eid ledger)))

(defn resolve-book
  "Coerce `spec` to a `:lease-liability` eid. `spec` may be an eid, or
   a `[lease-spec ledger]` pair."
  [db spec]
  (cond
    (nil? spec)     nil
    (integer? spec) spec
    (vector? spec)  (book-for db (first spec) (second spec))
    :else           spec))

(defn books-of
  "All `:lease-liability` book eids for a lease."
  [db lease-spec]
  (when-let [lease-eid (lease/resolve-lease db lease-spec)]
    (set (d/q '[:find [?e ...]
                :in $ ?l
                :where [?e :kontor.lease-liability/lease ?l]]
              db lease-eid))))

(defn pull-book
  "Pull a `:lease-liability` book with its lease + ledger + schedule."
  [db spec]
  (when-let [eid (resolve-book db spec)]
    (d/pull db
            '[* {:kontor.lease-liability/lease [:db/id :kontor.lease/code :kontor.lease/name
                                                :kontor.lease/status]
                 :kontor.lease-liability/ledger [:db/id :kontor.ledger/code :kontor.ledger/framework]
                 :kontor.lease-liability/commodity [:db/id]
                 :kontor.lease-liability/liability-account [:db/id]
                 :kontor.lease-liability/interest-account [:db/id]
                 :kontor.lease-liability/schedule [:db/id :kontor.schedule/code
                                                   :kontor.schedule/kind :kontor.schedule/state
                                                   :kontor.schedule/start-date
                                                   :kontor.schedule/end-date
                                                   :kontor.schedule/frequency]}]
            eid)))

;; ============================================================================
;; Plan inputs — consumed by the LeaseProvider impls (ADR-063)
;; ============================================================================

(defn book-plan-inputs
  "Resolve everything a `LeaseProvider` needs to plan a liability
   book's unwind into one flat map. Pulls the book, its `:lease`, and
   its `:schedule`.

   Returns:
     {:book :lease :ledger :schedule
      :provider-id :classification
      :opening-liability :discount-rate :opening-fired-through
      :payment-amount :contract-payment-amount :variable-payment-delta
      :variable-expense-account
      :liability-account :interest-account :recognition-transaction
      :payment-timing :payment-frequency
      :periods-per-year :period-rate
      :n-periods :start-date :commodity
      :purchase-option-price
      :initial-direct-costs :prepaid-at-commencement
      :incentives-received}

   `:period-rate` is `:discount-rate / :periods-per-year` carried to
   12dp — the per-period effective rate the unwind compounds at.
   `:n-periods` is `term-months` expressed at the payment frequency.

   `:payment-amount` is the payment THIS book unwinds on — the
   per-book pin (`:kontor.lease-liability/payment-amount`) when set,
   else the shared `:kontor.lease/payment-amount`. The contract fact is
   always also returned as `:contract-payment-amount`, and their
   difference as `:variable-payment-delta` — the ASC 842 variable
   lease cost the runner recognises when the payment is made (ADR-064
   Addendum 2 / note 198 HIGH-5)."
  [db book-spec]
  (let [eid (resolve-book db book-spec)
        _ (when-not eid
            (throw (ex-info "Lease-liability book not found" {:spec book-spec})))
        b (d/pull db
                  '[:kontor.lease-liability/provider-id
                    :kontor.lease-liability/classification
                    :kontor.lease-liability/opening-liability
                    :kontor.lease-liability/discount-rate
                    :kontor.lease-liability/opening-fired-through
                    :kontor.lease-liability/payment-amount
                    {:kontor.lease-liability/lease
                     [:db/id :kontor.lease/term-months :kontor.lease/payment-amount
                      :kontor.lease/payment-timing :kontor.lease/payment-frequency
                      :kontor.lease/purchase-option-price
                      :kontor.lease/initial-direct-costs
                      :kontor.lease/prepaid-at-commencement
                      :kontor.lease/incentives-received]}
                    {:kontor.lease-liability/ledger [:db/id]}
                    {:kontor.lease-liability/commodity [:db/id]}
                    {:kontor.lease-liability/liability-account [:db/id]}
                    {:kontor.lease-liability/interest-account [:db/id]}
                    {:kontor.lease-liability/variable-expense-account [:db/id]}
                    {:kontor.lease-liability/recognition-transaction [:db/id]}
                    {:kontor.lease-liability/schedule [:db/id :kontor.schedule/frequency
                                                       :kontor.schedule/start-date]}]
                  eid)
        l     (:kontor.lease-liability/lease b)
        sched (:kontor.lease-liability/schedule b)
        freq  (:kontor.lease/payment-frequency l)
        rate  (:kontor.lease-liability/discount-rate b)
        ppy   (lease/periods-per-year freq)
        period-rate (.divide ^java.math.BigDecimal rate
                             (java.math.BigDecimal/valueOf ppy)
                             12 java.math.RoundingMode/HALF_EVEN)
        contract-payment ^java.math.BigDecimal (:kontor.lease/payment-amount l)
        book-payment ^java.math.BigDecimal (or (:kontor.lease-liability/payment-amount b)
                                               contract-payment)]
    {:book                  eid
     :lease                 (:db/id l)
     :ledger                (:db/id (:kontor.lease-liability/ledger b))
     :schedule              (:db/id sched)
     :provider-id           (:kontor.lease-liability/provider-id b)
     :classification        (:kontor.lease-liability/classification b)
     :opening-liability     (:kontor.lease-liability/opening-liability b)
     :discount-rate         rate
     :opening-fired-through (or (:kontor.lease-liability/opening-fired-through b) 0)
     :payment-amount        book-payment
     :contract-payment-amount contract-payment
     :variable-payment-delta (.subtract contract-payment book-payment)
     :variable-expense-account (:db/id (:kontor.lease-liability/variable-expense-account b))
     :liability-account     (:db/id (:kontor.lease-liability/liability-account b))
     :interest-account      (:db/id (:kontor.lease-liability/interest-account b))
     :recognition-transaction (:db/id (:kontor.lease-liability/recognition-transaction b))
     :payment-timing        (:kontor.lease/payment-timing l)
     :payment-frequency     freq
     :periods-per-year      ppy
     :period-rate           period-rate
     :n-periods             (lease/periods-for (:kontor.lease/term-months l) freq)
     :start-date            (:kontor.schedule/start-date sched)
     :commodity             (:db/id (:kontor.lease-liability/commodity b))
     :purchase-option-price (:kontor.lease/purchase-option-price l)
     :initial-direct-costs  (or (:kontor.lease/initial-direct-costs l) 0M)
     :prepaid-at-commencement (or (:kontor.lease/prepaid-at-commencement l) 0M)
     :incentives-received   (or (:kontor.lease/incentives-received l) 0M)}))

;; ============================================================================
;; What the GL actually posted — the occurrence-log anchor
;; ============================================================================

(defn posted-period-legs
  "For each FIRED occurrence of a liability book's schedule, what the
   GL actually posted — `{sequence {:interest bigdec :principal bigdec
   :payment bigdec}}`.

   This is the subledger's anchor to the ledger. A liability book must
   never RE-DERIVE an already-fired period from current contract data:
   the payment amount, the term or the rate can all move under it
   (ADR-064 modifications; the ASC 842 fork that deliberately declines
   to remeasure), and the re-derivation then silently disagrees with the
   entry the GL is actually carrying — note 198 HIGH-5, the same bug
   class as the inventory AVCO drift. The ROU side already did this
   (`kontor.lease.rou-provider/fired-amounts`); this is its liability
   sibling.

   `:principal` is the sum of the occurrence transaction's legs on the
   book's `:liability-account`; `:interest` the legs on its
   `:interest-account`; `:payment` their sum (the liability-service
   cash — the ASC 842 variable-cost leg, which hits a THIRD account, is
   deliberately excluded: it services no liability).

   Takes the flat map from [[book-plan-inputs]]. PURE (a read)."
  [db {:keys [schedule liability-account interest-account]}]
  (let [rows (d/q '[:find ?seq ?p ?acct ?pamt
                    :in $ ?s
                    :where
                    [?o :kontor.schedule-occurrence/schedule ?s]
                    [?o :kontor.schedule-occurrence/sequence ?seq]
                    [?o :kontor.schedule-occurrence/transaction ?tx]
                    [?p :kontor.posting/transaction ?tx]
                    [?p :kontor.posting/account ?acct]
                    [?p :kontor.posting/amount ?pamt]]
                  db schedule)
        ;; Seed from the occurrence log, not from the join: a fired
        ;; occurrence whose entry happens to carry no leg on either
        ;; account must still read as FIRED (zero movement), never fall
        ;; back to being re-planned.
        seeded (into {} (map (fn [sq] [sq {:interest 0M :principal 0M}]))
                     (schedule/fired-sequences db schedule))
        by-seq (reduce
                (fn [acc [sq _p acct ^java.math.BigDecimal pamt]]
                  (let [m (get acc sq {:interest 0M :principal 0M})]
                    (assoc acc sq
                           (cond-> m
                             (= acct liability-account)
                             (update :principal #(.add ^java.math.BigDecimal % pamt))
                             (= acct interest-account)
                             (update :interest #(.add ^java.math.BigDecimal % pamt))))))
                seeded rows)]
    (reduce-kv (fn [acc sq {:keys [interest principal] :as m}]
                 (assoc acc sq (assoc m :payment
                                      (.add ^java.math.BigDecimal interest
                                            ^java.math.BigDecimal principal))))
               {} by-seq)))

;; ============================================================================
;; open-liability-book!
;; ============================================================================

(defn open-liability-book-tx-data
  "Pure tx-data builder for `open-liability-book!` — the entity-map
   construction without the `d/transact` wrapper. Use as a
   `kontor.workflow.process` step (ADR-067); `open-liability-book!` is the
   standalone wrapper. See `open-liability-book!` for the opts, plus
   `:tempid-suffix` — appended to the book/schedule tempids (default
   \"\"); pass a distinct suffix per book when several
   `open-liability-book-tx-data` outputs compose into one tx-data."
  [db {:keys [lease ledger classification opening-liability discount-rate
              liability-account interest-account commodity start-date
              n-periods frequency provider-id total-amount schedule-code
              note rate-rationale tempid-suffix]
       :or   {provider-id :effective-interest tempid-suffix ""}}]
  (when-not lease             (throw (ex-info ":lease required" {})))
  (when-not ledger            (throw (ex-info ":ledger required" {})))
  (when-not (#{:finance :operating} classification)
    (throw (ex-info ":classification must be :finance | :operating"
                    {:classification classification})))
  (when (nil? opening-liability) (throw (ex-info ":opening-liability required" {})))
  (when (nil? discount-rate)  (throw (ex-info ":discount-rate required" {})))
  (when-not liability-account (throw (ex-info ":liability-account required" {})))
  (when-not interest-account  (throw (ex-info ":interest-account required" {})))
  (when-not commodity         (throw (ex-info ":commodity required" {})))
  (when-not start-date        (throw (ex-info ":start-date required" {})))
  (when-not n-periods         (throw (ex-info ":n-periods required" {})))
  (when-not frequency         (throw (ex-info ":frequency required" {})))
  (let [lease-eid (lease/resolve-lease db lease)
        _ (when-not lease-eid (throw (ex-info "Lease not found" {:spec lease})))
        _ (when (book-for db lease-eid ledger)
            (throw (ex-info "A lease-liability book already exists for this (lease, ledger) — one book per pair (ADR-063)"
                            {:type :kontor.lease/duplicate-book
                             :lease lease :ledger ledger})))
        lease-code  (:kontor.lease/code (d/pull db [:kontor.lease/code] lease-eid))
        ledger-code (:kontor.ledger/code (d/pull db [:kontor.ledger/code] ledger))
        sched-code  (or schedule-code
                        (str lease-code "-liab-" (or ledger-code ledger)))
        end-date    (schedule/date-of-occurrence start-date frequency n-periods)
        total       (or total-amount
                        (some-> (:kontor.lease/payment-amount
                                 (d/pull db [:kontor.lease/payment-amount] lease-eid))
                                (.multiply (java.math.BigDecimal/valueOf
                                            (long n-periods)))))
        sched-tempid (str "lease-liab-sched" tempid-suffix)
        book-tempid  (str "lease-liab-book" tempid-suffix)
        schedule-entity (cond-> {:db/id sched-tempid
                                 :kontor.schedule/code sched-code
                                 :kontor.schedule/kind :lease-liability
                                 :kontor.schedule/origin-entity book-tempid
                                 :kontor.schedule/start-date start-date
                                 :kontor.schedule/end-date end-date
                                 :kontor.schedule/frequency frequency
                                 :kontor.schedule/total-commodity commodity
                                 :kontor.schedule/state :active
                                 :kontor.schedule/active true}
                          total (assoc :kontor.schedule/total-amount total))
        book-entity (cond-> {:db/id book-tempid
                             :kontor.lease-liability/lease lease-eid
                             :kontor.lease-liability/ledger ledger
                             :kontor.lease-liability/classification classification
                             :kontor.lease-liability/provider-id provider-id
                             :kontor.lease-liability/opening-liability opening-liability
                             :kontor.lease-liability/discount-rate discount-rate
                             :kontor.lease-liability/liability-account liability-account
                             :kontor.lease-liability/interest-account interest-account
                             :kontor.lease-liability/opening-fired-through 0
                             :kontor.lease-liability/commodity commodity
                             :kontor.lease-liability/schedule sched-tempid}
                      note (assoc :kontor.lease-liability/note note)
                      rate-rationale (assoc :kontor.lease-liability/rate-rationale
                                            rate-rationale))]
    [book-entity schedule-entity]))

(defn open-liability-book!
  "Create a `:lease-liability` book for a (lease, ledger) pair — plus
   its ADR-032 `:schedule` (`:kontor.schedule/kind :lease-liability`) — in
   one tx. Returns the tx-report.

   The `:kontor.lease-liability/identity` tuple (`:db.unique/identity` on
   `[lease ledger]`) means one book per (lease, ledger). ADR-063's
   `commence!` is the orchestrator that calls this once per ledger;
   it asserts the lease is `:draft` and that no book exists yet.

   Required opts:
     :lease              code or eid of :lease
     :ledger             eid of :ledger
     :classification     :finance | :operating
     :opening-liability  bigdec — the PV at commencement
     :discount-rate      bigdec — this book's annual rate
     :liability-account  eid — the BS lease-liability account
     :interest-account   eid — the P&L account the interest leg debits
     :commodity          eid of :commodity
     :start-date         instant — occurrence 1's date (for :in-arrears
                         this is commencement + one period; for
                         :in-advance it is commencement)
     :n-periods          long — number of payment occurrences
     :frequency          :monthly | :quarterly | :annual

   Optional opts:
     :provider-id        keyword (default :effective-interest)
     :total-amount       bigdec — the schedule's informational total
                         (default = payment-amount × n-periods, when
                         resolvable)
     :schedule-code      string (default \"<lease-code>-liab-<ledger-code>\")
     :note               string
     :rate-rationale     ref to :audit-doc — justification for this
                         book's :discount-rate (ADR-070 disclosure)

   The pure tx-data builder is `open-liability-book-tx-data` (ADR-067)."
  [conn opts]
  (validation/transact-with-validation
   conn (open-liability-book-tx-data (d/db conn) opts)))

;; ============================================================================
;; revise-liability-book! — re-anchor after a modification (ADR-064)
;; ============================================================================

(defn revise-liability-book-tx-data
  "Pure tx-data builder for `revise-liability-book!` — the entity-map
   construction without the `d/transact` wrapper. Use as a
   `kontor.workflow.process` step (ADR-067); `revise-liability-book!` is the
   standalone wrapper. See `revise-liability-book!` for the opts."
  [db {:keys [book new-opening-liability new-discount-rate note]}]
  (when (nil? new-opening-liability)
    (throw (ex-info ":new-opening-liability required" {})))
  (let [eid (resolve-book db book)
        _ (when-not eid
            (throw (ex-info "Lease-liability book not found" {:spec book})))
        b (d/pull db [{:kontor.lease-liability/lease [:kontor.lease/term-months
                                                      :kontor.lease/payment-frequency]}
                      {:kontor.lease-liability/schedule [:db/id :kontor.schedule/start-date]}]
                  eid)
        l (:kontor.lease-liability/lease b)
        sched (:kontor.lease-liability/schedule b)
        sched-eid (:db/id sched)
        freq (:kontor.lease/payment-frequency l)
        n (lease/periods-for (:kontor.lease/term-months l) freq)
        fired (long (count (schedule/fired-sequences db sched-eid)))
        _ (when (< n fired)
            (throw (ex-info "revise-liability-book!: revised term implies fewer periods than already fired"
                            {:type :kontor.lease/revision-below-fired
                             :revised-periods n :fired fired})))
        end-date (schedule/date-of-occurrence (:kontor.schedule/start-date sched) freq n)]
    [(cond-> {:db/id eid
              :kontor.lease-liability/opening-liability new-opening-liability
              :kontor.lease-liability/opening-fired-through fired}
       new-discount-rate
       (assoc :kontor.lease-liability/discount-rate new-discount-rate)
       note (assoc :kontor.lease-liability/note note))
     {:db/id sched-eid :kontor.schedule/end-date end-date}]))

(defn pin-book-payment-tx-data
  "Pure tx-data builder — pin the payment THIS book unwinds on
   (`:kontor.lease-liability/payment-amount`) and the account its
   variable lease cost is charged to.

   ADR-064 Addendum 2 / note 198 HIGH-5. `remeasure!` writes the new
   indexed rent onto the SHARED `:kontor.lease/payment-amount` because
   the IFRS 16 book on the same lease is genuinely remeasured — but an
   ASC 842 operating book on an index-only reset is NOT (ASC
   842-10-30-5), so it must keep unwinding on its ORIGINAL payments.
   Pinning is idempotent across successive resets: the pin is written
   ONCE, at the first reset, and later resets leave it alone (the
   measurement never moved), so each reset simply widens the variable
   delta.

   Required: :book, :payment-amount (the payment to pin),
             :variable-expense-account."
  [db {:keys [book payment-amount variable-expense-account]}]
  (when (nil? payment-amount)
    (throw (ex-info ":payment-amount required" {})))
  (when-not variable-expense-account
    (throw (ex-info ":variable-expense-account required" {})))
  (let [eid (resolve-book db book)
        _ (when-not eid
            (throw (ex-info "Lease-liability book not found" {:spec book})))]
    [{:db/id eid
      :kontor.lease-liability/payment-amount payment-amount
      :kontor.lease-liability/variable-expense-account variable-expense-account}]))

(defn revise-liability-book!
  "Re-anchor a `:lease-liability` book after an ADR-064 modification:
   set the new `:opening-liability`, advance `:opening-fired-through`
   to the count of already-fired occurrences, optionally update the
   `:discount-rate`, and reschedule the `:schedule` end-date for the
   period count implied by the lease's (possibly revised)
   `:term-months`. Fired occurrences are NEVER touched — the
   `LeaseProvider` re-plans only the un-fired tail from
   `:opening-fired-through + 1`.

   Call this AFTER the `:lease` contract facts (`:payment-amount` /
   `:term-months`) have been updated — it reads them to derive the
   new period count.

   Required: :book (eid or [lease ledger]), :new-opening-liability
   Optional: :new-discount-rate, :note
   Throws if the revised term implies fewer periods than already
   fired.

   The pure tx-data builder is `revise-liability-book-tx-data` (ADR-067)."
  [conn opts]
  (validation/transact-with-validation
   conn (revise-liability-book-tx-data (d/db conn) opts)))
