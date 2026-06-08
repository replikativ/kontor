(ns kontor.tax.corporate-income-tax
  "A generic `PeriodTaxProvider` for flat-rate corporate income tax
   (ADR-099; note 102 §10 / note 103). US 1120, AU company tax, CN
   EIT, MX ISR personas morales, AT KöSt — all one mechanism:
   marginalize book profit → apply the book-to-taxable transform →
   a flat rate.

   The base-selector marginalizes the entity's P&L (Σ income −
   Σ expense) into book profit. The book → taxable adjustment
   (statutory add-backs / deductions — US Schedule M-1, FR
   réintégrations) is a tax-vs-book difference NOT derivable from the
   GL, so it rides `context :inputs` as a `:base-transform` (ADR-099
   addendum / note 103 GAP 1); absent ⇒ identity. An optional
   `minimum-tax` floor (AT Mindest-KöSt — payable even at a loss) is
   applied via `greater-of` (note 103 GAP 2).

   DE Gewerbesteuer, FR/JP corporate surtax stacks and other
   non-flat corporate taxes are a later iteration — this covers the
   flat-rate jurisdictions."
  (:require [kontor.money :as money]
            [kontor.tax.period-tax-provider :as ptp]
            [kontor.reporting.report :as report]
            [kontor.tax.tax-schedule :as ts]))

(defn book-profit
  "The base-selector: marginalize (σ_E) the entity's P&L into book
   profit — Σ income − Σ expense — over the period. `context` carries
   `:conn`, `:period`, and an optional `:entity`."
  [{:keys [conn entity period]} commodity]
  (let [postings (report/report-postings
                  conn (cond-> {:from (:from period) :to (:to period)}
                         entity (assoc :entity entity)))
        by-type  (report/marginalize postings :account-type
                                     {:sign :inflow :commodity commodity})]
    (money/sub (get-in by-type [:income :value]  (money/zero commodity))
               (get-in by-type [:expense :value] (money/zero commodity)))))

(defrecord CorporateIncomeTaxProvider
           [id rate authority commodity statute minimum-tax]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs] :as context}]
    (let [profit    (book-profit context commodity)
          transform (:base-transform inputs)
          taxable   (ts/apply-base-transform transform (:amount profit))
          schedule  (ts/flat rate)
          flat-tax  (max 0M (ts/apply-schedule schedule taxable))
          gross-bd  (if minimum-tax (ts/greater-of flat-tax minimum-tax) flat-tax)
          minimum?  (boolean (and minimum-tax (pos? (compare minimum-tax flat-tax))))
          gross     (money/money gross-bd commodity)]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:authority authority}
        :functional-commodity commodity
        :components
        [(cond-> {:kind            :corporate-income-tax
                  :authority       authority
                  :base            (money/money taxable commodity)
                  :schedule        schedule
                  :gross-liability gross
                  :liability       gross
                  :prepaid         (money/zero commodity)
                  :provenance      {:provider-id id :statute statute}
                  :line-items
                  (cond-> [{:line :book-profit :label "Book profit"
                            :value profit}
                           {:line :taxable-income :label "Taxable income"
                            :value (money/money taxable commodity)}
                           {:line :tax-at-rate :label "Tax at the statutory rate"
                            :value (money/money flat-tax commodity)}]
                    minimum-tax
                    (conj {:line  :minimum-tax :label "Minimum-tax floor"
                           :value (money/money minimum-tax commodity)}))}
           transform (assoc :base-transform transform)
           minimum?  (assoc :composed-of [:minimum-tax]))]}))))

(defn corporate-income-tax-provider
  "Construct a `CorporateIncomeTaxProvider`. Config keys:
     :id          — provider-id keyword
     :rate        — the flat statutory rate (BigDecimal)
     :authority   — the taxing-authority keyword
     :commodity   — the functional commodity
     :statute     — optional citation string
     :minimum-tax — optional BigDecimal floor on the liability
                    (AT Mindest-KöSt), applied via `greater-of`"
  [{:keys [id rate authority commodity statute minimum-tax]}]
  (->CorporateIncomeTaxProvider id rate authority commodity statute
                                minimum-tax))
