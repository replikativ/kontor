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

   Because `straight-line-expense` is chosen as `(total payments +
   initial direct costs + prepaid − incentives) / n` (see
   `kontor.lease.lease-provider`), the plug sums exactly to the ROU
   asset's cost over the term — early periods amortise less (interest
   is high), late periods more.

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
          sl ^BigDecimal (:straight-line-expense lease-plan)
          fired (set (schedule/fired-sequences db schedule))
          ;; Plug = straight-line-expense − interest, per period; the
          ;; final period absorbs the rounding drift so Σ = the ROU
          ;; asset's depreciable base.
          head (mapv (fn [seq]
                       (let [i (get seq->interest seq)]
                         (when-not i
                           (throw (ex-info "rou-provider: liability plan has no interest for a ROU period — the liability + ROU schedules are misaligned"
                                           {:book book :sequence seq})))
                         (round2 (.subtract sl ^BigDecimal i))))
                     (range 1 n-periods))
          last-amt (.subtract ^BigDecimal depreciable-base
                              ^BigDecimal (reduce (fn [^BigDecimal a ^BigDecimal x]
                                                    (.add a x))
                                                  0M head))
          amts (conj head last-amt)
          periods (mapv (fn [seq amt]
                          {:sequence        seq
                           :date            (schedule/date-of-occurrence
                                             start-date frequency seq)
                           :amount          amt
                           :method-used     :lease-rou-plug
                           :basis-remaining nil
                           :fired?          (contains? fired seq)})
                        (range 1 (inc n-periods))
                        amts)]
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
   a kontor-lease provider). `kontor.lease.runner/run-lease!` wires
   this automatically."
  []
  (->LeaseRouPlugProvider))
