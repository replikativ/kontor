(ns kontor.lease.liability
  "kontor-lease — the `:lease-liability` per-(lease, ledger) book
   lifecycle (ADR-063).

   Sibling of `kontor.asset.depreciation`. A `:lease-liability` book
   is per-(lease, ledger): IFRS 16, ASC 842 and a local-GAAP view are
   each a `:ledger` (ADR-021), and the SAME lease is classified
   `:finance` on one and `:operating` on another — so classification
   lives HERE, on the book, not on the framework-neutral `:lease`.

   Each book owns one ADR-032 `:schedule` (`:schedule/kind
   :lease-liability`) that the lease runner (ADR-063) fires. This
   namespace is the book lifecycle + the flat `book-plan-inputs` map
   the `LeaseProvider` impls consume; it has NO dependency on the
   provider (mirrors `kontor.asset.depreciation` ↔
   `kontor.asset.depreciation-provider`)."
  (:require [datahike.api :as d]
            [kontor.lease.core :as lease]
            [kontor.schedule :as schedule]
            [kontor.validation :as validation]))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn book-for
  "Resolve the `:lease-liability` book eid for a (lease, ledger) pair,
   or nil. `lease-spec` is a code or eid; `ledger` is an eid."
  [db lease-spec ledger]
  (when-let [lease-eid (lease/resolve-lease db lease-spec)]
    (d/q '[:find ?e .
           :in $ ?l ?led
           :where
           [?e :lease-liability/lease ?l]
           [?e :lease-liability/ledger ?led]]
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
                :where [?e :lease-liability/lease ?l]]
              db lease-eid))))

(defn pull-book
  "Pull a `:lease-liability` book with its lease + ledger + schedule."
  [db spec]
  (when-let [eid (resolve-book db spec)]
    (d/pull db
            '[* {:lease-liability/lease [:db/id :lease/code :lease/name
                                         :lease/status]
                 :lease-liability/ledger [:db/id :kontor.ledger/code :kontor.ledger/framework]
                 :lease-liability/commodity [:db/id]
                 :lease-liability/liability-account [:db/id]
                 :lease-liability/interest-account [:db/id]
                 :lease-liability/schedule [:db/id :schedule/code
                                            :schedule/kind :schedule/state
                                            :schedule/start-date
                                            :schedule/end-date
                                            :schedule/frequency]}]
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
      :payment-amount :payment-timing :payment-frequency
      :periods-per-year :period-rate
      :n-periods :start-date :commodity
      :purchase-option-price
      :initial-direct-costs :prepaid-at-commencement
      :incentives-received}

   `:period-rate` is `:discount-rate / :periods-per-year` carried to
   12dp — the per-period effective rate the unwind compounds at.
   `:n-periods` is `term-months` expressed at the payment frequency."
  [db book-spec]
  (let [eid (resolve-book db book-spec)
        _ (when-not eid
            (throw (ex-info "Lease-liability book not found" {:spec book-spec})))
        b (d/pull db
                  '[:lease-liability/provider-id
                    :lease-liability/classification
                    :lease-liability/opening-liability
                    :lease-liability/discount-rate
                    :lease-liability/opening-fired-through
                    {:lease-liability/lease
                     [:db/id :lease/term-months :lease/payment-amount
                      :lease/payment-timing :lease/payment-frequency
                      :lease/purchase-option-price
                      :lease/initial-direct-costs
                      :lease/prepaid-at-commencement
                      :lease/incentives-received]}
                    {:lease-liability/ledger [:db/id]}
                    {:lease-liability/commodity [:db/id]}
                    {:lease-liability/schedule [:db/id :schedule/frequency
                                                :schedule/start-date]}]
                  eid)
        l     (:lease-liability/lease b)
        sched (:lease-liability/schedule b)
        freq  (:lease/payment-frequency l)
        rate  (:lease-liability/discount-rate b)
        ppy   (lease/periods-per-year freq)
        period-rate (.divide ^java.math.BigDecimal rate
                             (java.math.BigDecimal/valueOf ppy)
                             12 java.math.RoundingMode/HALF_EVEN)]
    {:book                  eid
     :lease                 (:db/id l)
     :ledger                (:db/id (:lease-liability/ledger b))
     :schedule              (:db/id sched)
     :provider-id           (:lease-liability/provider-id b)
     :classification        (:lease-liability/classification b)
     :opening-liability     (:lease-liability/opening-liability b)
     :discount-rate         rate
     :opening-fired-through (or (:lease-liability/opening-fired-through b) 0)
     :payment-amount        (:lease/payment-amount l)
     :payment-timing        (:lease/payment-timing l)
     :payment-frequency     freq
     :periods-per-year      ppy
     :period-rate           period-rate
     :n-periods             (lease/periods-for (:lease/term-months l) freq)
     :start-date            (:schedule/start-date sched)
     :commodity             (:db/id (:lease-liability/commodity b))
     :purchase-option-price (:lease/purchase-option-price l)
     :initial-direct-costs  (or (:lease/initial-direct-costs l) 0M)
     :prepaid-at-commencement (or (:lease/prepaid-at-commencement l) 0M)
     :incentives-received   (or (:lease/incentives-received l) 0M)}))

;; ============================================================================
;; open-liability-book!
;; ============================================================================

(defn open-liability-book-tx-data
  "Pure tx-data builder for `open-liability-book!` — the entity-map
   construction without the `d/transact` wrapper. Use as a
   `kontor.process` step (ADR-067); `open-liability-book!` is the
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
                            {:type :lease/duplicate-book
                             :lease lease :ledger ledger})))
        lease-code  (:lease/code (d/pull db [:lease/code] lease-eid))
        ledger-code (:kontor.ledger/code (d/pull db [:kontor.ledger/code] ledger))
        sched-code  (or schedule-code
                        (str lease-code "-liab-" (or ledger-code ledger)))
        end-date    (schedule/date-of-occurrence start-date frequency n-periods)
        total       (or total-amount
                        (some-> (:lease/payment-amount
                                 (d/pull db [:lease/payment-amount] lease-eid))
                                (.multiply (java.math.BigDecimal/valueOf
                                            (long n-periods)))))
        sched-tempid (str "lease-liab-sched" tempid-suffix)
        book-tempid  (str "lease-liab-book" tempid-suffix)
        schedule-entity (cond-> {:db/id sched-tempid
                                 :schedule/code sched-code
                                 :schedule/kind :lease-liability
                                 :schedule/origin-entity book-tempid
                                 :schedule/start-date start-date
                                 :schedule/end-date end-date
                                 :schedule/frequency frequency
                                 :schedule/total-commodity commodity
                                 :schedule/state :active
                                 :schedule/active true}
                          total (assoc :schedule/total-amount total))
        book-entity (cond-> {:db/id book-tempid
                             :lease-liability/lease lease-eid
                             :lease-liability/ledger ledger
                             :lease-liability/classification classification
                             :lease-liability/provider-id provider-id
                             :lease-liability/opening-liability opening-liability
                             :lease-liability/discount-rate discount-rate
                             :lease-liability/liability-account liability-account
                             :lease-liability/interest-account interest-account
                             :lease-liability/opening-fired-through 0
                             :lease-liability/commodity commodity
                             :lease-liability/schedule sched-tempid}
                      note (assoc :lease-liability/note note)
                      rate-rationale (assoc :lease-liability/rate-rationale
                                            rate-rationale))]
    [book-entity schedule-entity]))

(defn open-liability-book!
  "Create a `:lease-liability` book for a (lease, ledger) pair — plus
   its ADR-032 `:schedule` (`:schedule/kind :lease-liability`) — in
   one tx. Returns the tx-report.

   The `:lease-liability/identity` tuple (`:db.unique/identity` on
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
   `kontor.process` step (ADR-067); `revise-liability-book!` is the
   standalone wrapper. See `revise-liability-book!` for the opts."
  [db {:keys [book new-opening-liability new-discount-rate note]}]
  (when (nil? new-opening-liability)
    (throw (ex-info ":new-opening-liability required" {})))
  (let [eid (resolve-book db book)
        _ (when-not eid
            (throw (ex-info "Lease-liability book not found" {:spec book})))
        b (d/pull db [{:lease-liability/lease [:lease/term-months
                                               :lease/payment-frequency]}
                      {:lease-liability/schedule [:db/id :schedule/start-date]}]
                  eid)
        l (:lease-liability/lease b)
        sched (:lease-liability/schedule b)
        sched-eid (:db/id sched)
        freq (:lease/payment-frequency l)
        n (lease/periods-for (:lease/term-months l) freq)
        fired (long (count (schedule/fired-sequences db sched-eid)))
        _ (when (< n fired)
            (throw (ex-info "revise-liability-book!: revised term implies fewer periods than already fired"
                            {:type :lease/revision-below-fired
                             :revised-periods n :fired fired})))
        end-date (schedule/date-of-occurrence (:schedule/start-date sched) freq n)]
    [(cond-> {:db/id eid
              :lease-liability/opening-liability new-opening-liability
              :lease-liability/opening-fired-through fired}
       new-discount-rate
       (assoc :lease-liability/discount-rate new-discount-rate)
       note (assoc :lease-liability/note note))
     {:db/id sched-eid :schedule/end-date end-date}]))

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
