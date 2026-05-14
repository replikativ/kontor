(ns kontor.lease.rou-provider
  "The operating-lease ROU 'plug' — a `DepreciationProvider` (ADR-063).

   A FINANCE lease's Right-of-Use asset depreciates like any other
   asset: straight-line over the lease term — so its
   `:asset-depreciation` book just uses the kontor-asset built-in
   `:straight-line` provider, and this namespace is not involved.

   An OPERATING lease (ASC 842) recognises a single straight-line
   lease cost each period. The liability still unwinds at the
   effective-interest rate (interest falls over the term), so the ROU
   asset's amortisation is the *plug* that keeps the total flat:

       ROU amortisation(period) = straight-line-expense − interest(period)

   This provider computes `straight-line-expense` itself, over the
   REMAINING term:

       straight-line-expense = (remaining ROU + Σ un-fired interest)
                               / count(un-fired periods)

   so the un-fired plug sums exactly to the remaining ROU carrying
   amount — early periods amortise less (interest is high), late
   periods more. At commencement (nothing fired) this reduces to
   `(total payments + initial direct costs + prepaid − incentives)
   / n`; after an ADR-064 modification it correctly re-levels over the
   remaining periods (ASC 842 recalculates the single cost on a
   modification — and the `LeaseProvider`'s own `:straight-line-
   expense` cannot, since it never sees the ROU book).

   So an operating lease reuses the *entire* kontor-asset depreciation
   machinery — the `:asset-depreciation` book, the runner, the GL
   posting builder — by routing its book through THIS provider
   (`:asset-depreciation/provider-id :lease-rou-plug`) instead of a
   built-in. The book's `:asset-depreciation/expense-account`
   override (ADR-063) points the charge at the single lease-expense
   account, where it meets the interest leg — one P&L line.

   `plan-schedule` reads the SIBLING `:lease-liability` book: the ROU
   `:asset-depreciation` book → its `:asset` → the `:lease` whose
   `:lease/rou-asset` is that asset → the `:lease-liability` for the
   same `:ledger`. PURE."
  (:require [datahike.api :as d]
            [kontor.asset.depreciation :as asset-dep]
            [kontor.asset.depreciation-provider :as adp]
            [kontor.lease.lease-provider :as lp]
            [kontor.lease.liability :as liability]
            [kontor.schedule :as schedule])
  (:import [java.math BigDecimal RoundingMode]))

(defn- round2 ^BigDecimal [^BigDecimal x]
  (.setScale x 2 RoundingMode/HALF_EVEN))

(defn- sibling-liability-book
  "The `:lease-liability` book that is the sibling of a ROU
   `:asset-depreciation` book — same `:ledger`, and the liability's
   `:lease` is the one whose `:lease/rou-asset` is the dep book's
   asset. Throws when the dep book is not a ROU book or the sibling
   is missing."
  [db dep-asset-eid dep-ledger-eid]
  (let [lease-eid (d/q '[:find ?l .
                         :in $ ?asset
                         :where [?l :lease/rou-asset ?asset]]
                       db dep-asset-eid)
        _ (when-not lease-eid
            (throw (ex-info "rou-provider: this :asset is not a :lease/rou-asset — :lease-rou-plug is only valid for an operating-lease ROU book"
                            {:type :lease/not-a-rou-asset :asset dep-asset-eid})))
        liab (liability/book-for db lease-eid dep-ledger-eid)]
    (when-not liab
      (throw (ex-info "rou-provider: no sibling :lease-liability book for this (lease, ledger) — commence! opens both together"
                      {:type :lease/missing-sibling-liability
                       :lease lease-eid :ledger dep-ledger-eid})))
    liab))

(defn- fired-amounts
  "Map of `sequence → fired :schedule-occurrence/amount` for a ROU
   depreciation schedule."
  [db schedule-eid]
  (into {}
        (d/q '[:find ?seq ?amt
               :in $ ?s
               :where
               [?o :schedule-occurrence/schedule ?s]
               [?o :schedule-occurrence/sequence ?seq]
               [?o :schedule-occurrence/amount ?amt]]
             db schedule-eid)))

(defrecord LeaseRouPlugProvider []
  adp/DepreciationProvider
  (provider-id [_] :lease-rou-plug)
  (plan-schedule [_ db book]
    (let [{:keys [asset ledger depreciable-base n-periods start-date
                  frequency schedule convention] :as _inputs}
          (asset-dep/book-plan-inputs db book)
          liab-book (sibling-liability-book db asset ledger)
          lease-plan (lp/plan-for-book db liab-book)
          seq->interest (into {} (map (juxt :sequence :interest))
                              (:periods lease-plan))
          ;; Already-fired ROU periods keep their logged amount; the
          ;; un-fired tail is re-planned (the liability plan covers
          ;; exactly the un-fired periods — run-lease! fires the
          ;; liability + ROU schedules in lockstep, so the fired
          ;; counts always match).
          fired (fired-amounts db schedule)
          accumulated (reduce (fn [^BigDecimal a ^BigDecimal x] (.add a x))
                              0M (vals fired))
          unfired (vec (sort (remove fired (range 1 (inc n-periods)))))
          interest-of (fn [seq]
                        (or (get seq->interest seq)
                            (throw (ex-info "rou-provider: liability plan has no interest for an un-fired ROU period — the liability + ROU schedules are misaligned"
                                            {:book book :sequence seq}))))
          remaining-rou (.subtract ^BigDecimal depreciable-base accumulated)
          ;; The single straight-line cost, re-levelled over the
          ;; REMAINING term: (remaining ROU + Σ un-fired interest) /
          ;; count(un-fired). Correct at commencement AND after a
          ;; modification.
          unfired-interest (reduce (fn [^BigDecimal a seq]
                                     (.add a ^BigDecimal (interest-of seq)))
                                   0M unfired)
          sl (if (seq unfired)
               (round2 (.divide (.add remaining-rou unfired-interest)
                                (BigDecimal/valueOf (count unfired))
                                12 RoundingMode/HALF_EVEN))
               0M)
          ;; Plug = straight-line-expense − interest, per un-fired
          ;; period; the LAST un-fired period absorbs the rounding
          ;; drift so Σ (fired + un-fired) = the ROU depreciable base.
          head (mapv (fn [seq] (round2 (.subtract sl ^BigDecimal (interest-of seq))))
                     (butlast unfired))
          last-unfired-amt (.subtract
                            remaining-rou
                            ^BigDecimal (reduce (fn [^BigDecimal a ^BigDecimal x]
                                                  (.add a x))
                                                0M head))
          unfired->amt (zipmap unfired
                               (if (seq unfired)
                                 (conj head last-unfired-amt)
                                 []))
          periods (mapv (fn [seq]
                          {:sequence        seq
                           :date            (schedule/date-of-occurrence
                                             start-date frequency seq)
                           :amount          (or (get fired seq)
                                                 (get unfired->amt seq))
                           :method-used     :lease-rou-plug
                           :basis-remaining nil
                           :fired?          (contains? fired seq)})
                        (range 1 (inc n-periods)))]
      {:periods     periods
       :convention  convention
       :total       (reduce (fn [^BigDecimal a p]
                              (.add a ^BigDecimal (:amount p)))
                            0M periods)
       :provider-id :lease-rou-plug})))

(defn provider
  "A `LeaseRouPlugProvider` instance — pass it to
   `kontor.asset.runner/run-depreciation!` as `:provider` for an
   operating-lease ROU book (the runner cannot resolve
   `:lease-rou-plug` from the kontor-asset built-in registry — it is
   a kontor-lease provider).

   PREFER `kontor.lease.runner/run-lease!` over calling
   `run-depreciation!` on a ROU book directly. `plan-schedule` here
   demands that the liability plan covers every UN-fired ROU period —
   which holds only while the liability schedule and the ROU
   depreciation schedule are fired in **lockstep**. `run-lease!` fires
   both together AND guards the lockstep invariant up-front; firing a
   ROU book on its own can desync the two and make this provider
   throw `:lease/...-misaligned` on the next run."
  []
  (->LeaseRouPlugProvider))
