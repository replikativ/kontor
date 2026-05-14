(ns kontor.lease.posting
  "kontor-lease GL posting builders — ADR-063.

   Pure functions: each `plan-*` builder takes a spec and returns
   tx-data ready for `datahike.api/transact` — none transacts. Every
   builder routes through `kontor.posting/build-transaction`, so
   sum-to-zero per (ledger, commodity) is enforced for free.

   Sign convention (kernel-wide): a debit is a positive
   `:posting/amount`, a credit is negative.

   Two entries make up a lessee lease's GL life:

   - `plan-lease-recognition` — the day-one entry `commence!` posts
     per book: `Dr ROU-asset / Cr lease-liability [/ Cr cash]`.
   - `plan-lease-payment` — each period's payment the runner posts:
     `Dr interest + Dr lease-liability(principal) / Cr cash`.

   The ROU asset's *depreciation* entry is NOT here — it is built by
   `kontor.asset.posting/plan-depreciation-charge`, since the ROU
   asset is an `:asset` and reuses the kontor-asset machinery whole."
  (:require [kontor.posting :as posting]))

;; ============================================================================
;; Internals (mirrors kontor.asset.posting)
;; ============================================================================

(defn- posting*
  "Build one posting map, tagging `:posting/ledger` only when `ledger`
   is non-nil (ADR-021 — a nil ledger is the primary book)."
  [account amount commodity ledger]
  (cond-> {:posting/account   account
           :posting/amount    amount
           :posting/commodity commodity}
    ledger (assoc :posting/ledger ledger)))

(defn- build
  "Assemble + build a transaction from a journal/date/narration header
   and a vector of posting maps. Drops zero-amount postings.

   When the header carries `:posted-at`, the entry is built *sealed*:
   `:transaction/state :posted` + `:transaction/posted-at`, every
   posting stamped `:posting/posted-at`."
  [{:keys [journal date narration external-id posted-at]} postings]
  (when-not journal (throw (ex-info ":journal required" {})))
  (when-not date    (throw (ex-info ":date required" {})))
  (let [nonzero (filterv (fn [p]
                           (not (zero? (.signum ^java.math.BigDecimal
                                        (:posting/amount p)))))
                         postings)
        nonzero (if posted-at
                  (mapv #(assoc % :posting/posted-at posted-at) nonzero)
                  nonzero)]
    (posting/build-transaction
     {:transaction (cond-> {:transaction/journal journal
                            :transaction/effective-date date}
                     narration   (assoc :transaction/narration narration)
                     external-id (assoc :transaction/external-id external-id)
                     posted-at   (assoc :transaction/state :posted
                                        :transaction/posted-at posted-at))
      :postings nonzero})))

;; ============================================================================
;; Initial recognition (commence!)
;; ============================================================================

(defn plan-lease-recognition
  "Build the day-one recognition entry for ONE (lease, ledger) book:

     Dr  <rou-asset-account>     rou-cost
     Cr  <liability-account>     pv
     Cr  <cash-account>          net-cash      (= IDC + prepaid − incentives)

   where `rou-cost = pv + net-cash`. When `net-cash` is zero the cash
   leg is dropped (and `:cash-account` may be omitted); when it is
   negative (a net incentive) the cash leg becomes a debit.

   Required: :rou-asset-account, :liability-account, :rou-cost, :pv,
             :net-cash, :commodity, :journal, :date
   Optional: :cash-account (required iff :net-cash ≠ 0), :ledger
             (nil = primary book), :narration, :external-id,
             :posted-at (seal the entry)"
  [{:keys [rou-asset-account liability-account cash-account rou-cost pv
           net-cash commodity ledger] :as spec}]
  (when-not rou-asset-account (throw (ex-info ":rou-asset-account required" {})))
  (when-not liability-account (throw (ex-info ":liability-account required" {})))
  (when (nil? rou-cost)       (throw (ex-info ":rou-cost required" {})))
  (when (nil? pv)             (throw (ex-info ":pv required" {})))
  (when (nil? net-cash)       (throw (ex-info ":net-cash required" {})))
  (when-not commodity         (throw (ex-info ":commodity required" {})))
  (when (and (not (zero? (.signum ^java.math.BigDecimal net-cash)))
             (not cash-account))
    (throw (ex-info ":cash-account required when :net-cash ≠ 0" {})))
  (build spec
         [(posting* rou-asset-account rou-cost commodity ledger)
          (posting* liability-account (.negate ^java.math.BigDecimal pv)
                    commodity ledger)
          (posting* cash-account (.negate ^java.math.BigDecimal net-cash)
                    commodity ledger)]))

;; ============================================================================
;; Periodic payment (run-lease!)
;; ============================================================================

(defn plan-lease-payment
  "Build one period's lease-payment entry for ONE (lease, ledger)
   book:

     Dr  <interest-account>      interest
     Dr  <liability-account>     principal
     Cr  <cash-account>          payment       (= interest + principal)

   For a FINANCE book `:interest-account` is an interest-expense
   account; for an OPERATING book `commence!` set it to the single
   lease-expense account — so the interest leg lands in the same P&L
   line as the ROU plug, and the two sum to the straight-line cost.

   Required: :interest-account, :liability-account, :cash-account,
             :interest, :principal, :payment, :commodity, :journal,
             :date
   Optional: :ledger, :narration, :external-id, :posted-at"
  [{:keys [interest-account liability-account cash-account interest principal
           payment commodity ledger] :as spec}]
  (when-not interest-account  (throw (ex-info ":interest-account required" {})))
  (when-not liability-account (throw (ex-info ":liability-account required" {})))
  (when-not cash-account      (throw (ex-info ":cash-account required" {})))
  (when (nil? interest)       (throw (ex-info ":interest required" {})))
  (when (nil? principal)      (throw (ex-info ":principal required" {})))
  (when (nil? payment)        (throw (ex-info ":payment required" {})))
  (when-not commodity         (throw (ex-info ":commodity required" {})))
  (build spec
         [(posting* interest-account interest commodity ledger)
          (posting* liability-account principal commodity ledger)
          (posting* cash-account (.negate ^java.math.BigDecimal payment)
                    commodity ledger)]))
