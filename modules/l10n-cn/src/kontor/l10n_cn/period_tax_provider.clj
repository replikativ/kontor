(ns kontor.l10n-cn.period-tax-provider
  "Chinese Enterprise Income Tax (企业所得税, EIT) as a kontor
   `PeriodTaxProvider` (ADR-099; research note 103). A flat 25 %
   standard rate; 15 % for a qualified High / New-Technology
   Enterprise (HNTE). Taxable income is accounting profit adjusted by
   tax rules — the adjustment rides `context :inputs` as a
   `:base-transform`.

   The small-low-profit-enterprise reduced rates select the rate from
   a taxpayer attribute too; pass an explicit `:rate` for those."
  (:require [kontor.corporate-income-tax :as cit]))

(def eit-standard-rate
  "EIT standard rate — flat 25 % (企业所得税法 §4)."
  0.25M)

(def eit-hnte-rate
  "EIT reduced rate for a High / New-Technology Enterprise — 15 %."
  0.15M)

(defn cn-eit-provider
  "CN Enterprise Income Tax provider. Config:
     :hnte? — true for the High / New-Technology Enterprise 15 % rate
     :rate  — optional explicit override (small-low-profit regimes)"
  [{:keys [hnte? rate]}]
  (cit/corporate-income-tax-provider
   {:id        :cn-eit
    :rate      (or rate (if hnte? eit-hnte-rate eit-standard-rate))
    :authority :cn-tax
    :commodity :CNY
    :statute   "中华人民共和国企业所得税法"}))
