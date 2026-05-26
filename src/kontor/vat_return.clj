(ns kontor.vat-return
  "The periodic VAT / GST return — note 104 Phase 2 (the sole-proprietor
   rung; but any VAT-registered entity files one).

   VAT is collected per transaction — ADR-071's `TaxRateProvider` /
   `TaxPostingBuilder` post *output VAT* on a sale (a liability — VAT
   charged to the customer, owed to the authority) and *input VAT* on
   a purchase (an asset — VAT paid to a supplier, recoverable). The
   RETURN is the periodic reconciliation: over a filing period,
   `output VAT − input VAT` is the net payable to (or refundable from)
   the tax authority.

   This namespace does not compute new tax — it NETS already-posted
   tax. `compute-vat-return` marginalizes (σ_E, ADR-096) the period's
   output- and input-VAT postings; `vat-return-tx-data` materialises
   the remittance — clearing the two VAT accounts into the net VAT
   payable. The VAT accounts are identified by account code, the same
   way `kontor.standalone-payroll-tax` identifies wage accounts."
  (:require [kontor.book :as book]
            [kontor.money :as money]
            [kontor.report :as report]))

(defn- vat-total
  "Marginalize the period's VAT postings on accounts whose `:kontor.account/
   code` matches `codes` into one Money sum."
  [conn {:keys [from to entity]} codes commodity]
  (let [postings (report/report-postings
                  conn (cond-> {:from from :to to}
                         entity (assoc :entity entity)))]
    (:value (report/run-engine postings
                               {:engine    :account-codes
                                :codes     codes
                                :sign      :inflow
                                :commodity commodity}
                               {}))))

(defn compute-vat-return
  "Compute a VAT / GST return for a filing period. `opts`:
     :from :to           — the filing period (half-open)
     :entity             — optional entity (ADR-031); nil = the whole
                           book (a business kept standalone)
     :output-vat-codes   — account-code patterns for output VAT
     :input-vat-codes    — account-code patterns for input VAT
     :commodity          — the functional commodity
   Returns `{:period :output-vat :input-vat :net-vat}` — `:net-vat`
   positive ⇒ payable to the authority, negative ⇒ a refund due."
  [conn {:keys [from to output-vat-codes input-vat-codes commodity] :as opts}]
  (let [output (vat-total conn opts output-vat-codes commodity)
        input  (vat-total conn opts input-vat-codes commodity)]
    {:period     {:from from :to to}
     :output-vat output
     :input-vat  input
     :net-vat    (money/sub output input)}))

(defn vat-return-tx-data
  "Materialise the remittance for a `compute-vat-return` result: clear
   the period's output- and input-VAT accounts into the net VAT
   payable to the tax authority. One balanced transaction —
     Dr  output-VAT account     (clears its accumulated credit)
       Cr  input-VAT account      (clears its accumulated debit)
       Cr  VAT-payable account    (the net owed; a Dr when a refund).
   `opts`: `:output-vat-account` `:input-vat-account`
   `:vat-payable-account` `:journal` `:effective-date` `:commodity`
   `:narration`. Returns a `kontor.book` tx-data vector."
  [vat-return {:keys [output-vat-account input-vat-account vat-payable-account
                      journal effective-date commodity narration]}]
  (when-not (and output-vat-account input-vat-account vat-payable-account
                 journal effective-date commodity)
    (throw (ex-info "vat-return-tx-data: needs :output-vat-account, :input-vat-account, :vat-payable-account, :journal, :effective-date, :commodity"
                    {})))
  (let [output (:amount (:output-vat vat-return))
        input  (:amount (:input-vat vat-return))
        net    (:amount (:net-vat vat-return))]
    (book/entry-tx-data
     {:postings       [{:account output-vat-account  :amount output}
                       {:account input-vat-account   :amount (- input)}
                       {:account vat-payable-account :amount (- net)}]
      :commodity      commodity
      :journal        journal
      :effective-date effective-date
      :narration      (or narration "VAT return")})))
