(ns kontor.tax-return-posting-builder
  "`TaxReturnPostingBuilder` — ADR-099, research note 102 §4. The
   posting-expansion half of the period-tax abstraction: materialize
   the GL transactions for a `TaxReturnFacts`.

   Unlike `kontor.tax-posting-builder` (which returns tax *legs* to
   splice into someone else's invoice transaction), this builder
   returns **whole balanced transactions** via the `kontor.book` verb
   facade (ADR-095) — a period tax IS its own transaction, not a
   rider on an invoice. Two transactions, at two times:

     - **provision** (period close) — recognise the period tax as an
       EXPENSE + a PAYABLE:
         Dr  income-tax expense       liability
           Cr  income-tax payable       liability
     - **payment** (later) — settle the payable:
         Dr  income-tax payable       amount
           Cr  bank / cash              amount

   A recognised-but-unpaid period tax is precisely a `:payable`
   `kontor-commitment` (ADR-098): the consumer may, optionally, also
   `commitment/record-commitment!` the provision and `fulfill!` it
   from each payment — `commitment/aging` then surfaces overdue tax
   obligations for free. The builder does not require kontor-commitment
   (kernel single-dep); it composes with it.

   `StaticTaxReturnPostingBuilder` is the generic implementation —
   the provision/payment are a fixed two-account shape, so unlike the
   per-country transaction `TaxPostingBuilder`s, one generic builder
   serves every jurisdiction; only the account configuration varies."
  (:require [kontor.book :as book]
            [kontor.period-tax-provider :as ptp]))

;; ============================================================================
;; Protocol
;; ============================================================================

(defprotocol TaxReturnPostingBuilder
  "Materialize the GL transactions for a `TaxReturnFacts`. Chart-of-
   accounts-aware; it never determines liability."
  (builder-id [this]
    "A keyword identifying the implementation.")
  (provision-tx-data [this return-facts opts]
    "The accrual at period close: recognise the period tax as an
     expense + a payable. Returns a `kontor.book` tx-data vector
     (one balanced transaction). `opts` supplies `:effective-date`
     and may override the builder's account / journal config.")
  (payment-tx-data [this return-facts payment opts]
    "The settlement: a later cash payment liquidating the payable.
     `payment` is `{:amount <number> :date <#inst>}`. Returns a
     `kontor.book` tx-data vector."))

;; ============================================================================
;; StaticTaxReturnPostingBuilder
;; ============================================================================

(defrecord StaticTaxReturnPostingBuilder [config]
  TaxReturnPostingBuilder
  (builder-id [_] :static-tax-return)

  (provision-tx-data [_ return-facts opts]
    (let [{:keys [expense-account payable-account journal commodity]}
          (merge config opts)
          effective-date (:effective-date opts)
          liability      (ptp/total-liability return-facts)]
      (when-not (and expense-account payable-account journal commodity)
        (throw (ex-info "provision-tx-data needs :expense-account, :payable-account, :journal, :commodity"
                        {:config config :opts opts})))
      (when-not effective-date
        (throw (ex-info "provision-tx-data needs :effective-date" {:opts opts})))
      (book/entry-tx-data
       {:debit-account  expense-account
        :credit-account payable-account
        :amount         (:amount liability)
        :commodity      commodity
        :journal        journal
        :effective-date effective-date
        :narration      (or (:narration opts) "Period tax provision")})))

  (payment-tx-data [_ _return-facts payment opts]
    (let [{:keys [payable-account cash-account journal commodity]}
          (merge config opts)]
      (when-not (and payable-account cash-account journal commodity)
        (throw (ex-info "payment-tx-data needs :payable-account, :cash-account, :journal, :commodity"
                        {:config config :opts opts})))
      (when-not (:date payment)
        (throw (ex-info "payment-tx-data: payment needs :date" {:payment payment})))
      (book/entry-tx-data
       {:debit-account  payable-account
        :credit-account cash-account
        :amount         (:amount payment)
        :commodity      commodity
        :journal        journal
        :effective-date (:date payment)
        :narration      (or (:narration opts) "Period tax payment")}))))

(defn make-static-tax-return-posting-builder
  "Construct a `StaticTaxReturnPostingBuilder`. `config` may carry
   `:expense-account`, `:payable-account`, `:cash-account`, `:journal`
   as defaults (each also overridable per call via `opts`)."
  ([] (make-static-tax-return-posting-builder {}))
  ([config] (->StaticTaxReturnPostingBuilder config)))
