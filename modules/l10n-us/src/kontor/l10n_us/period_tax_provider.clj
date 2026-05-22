(ns kontor.l10n-us.period-tax-provider
  "US federal corporate income tax (Form 1120) as a kontor
   `PeriodTaxProvider` (ADR-099; research note 103). A flat 21 %
   (IRC §11, post-TCJA) on taxable income — book profit adjusted by
   the Schedule M-1 / M-3 book-to-tax reconciliation, which rides
   `context :inputs` as a `:base-transform`.

   State corporate income tax and the 15 % corporate AMT (CAMT) are a
   later iteration — those need the multi-component fan-out / a
   `greater-of` over a separate book-income base."
  (:require [kontor.corporate-income-tax :as cit]))

(def federal-rate
  "US federal corporate income tax — flat 21 % (IRC §11)."
  0.21M)

(defn us-corporate-income-tax-provider
  "US federal corporate income tax (Form 1120) provider. Config:
     :rate — optional override (default 21 %)"
  [{:keys [rate]}]
  (cit/corporate-income-tax-provider
   {:id        :us-1120
    :rate      (or rate federal-rate)
    :authority :us-irs
    :commodity :USD
    :statute   "IRC §11"}))
