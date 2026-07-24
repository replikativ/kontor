(ns kontor.l10n-uk.vat
  "Turnkey UK VAT (HMRC VAT100) return — a thin l10n binding over the kernel
   `kontor.tax.vat-return`, with the `kontor.l10n-uk.chart` VAT codes baked in
   so a UK consumer needs no manual code wiring (mirrors
   `kontor.l10n-ca.gst-hst` / `kontor.l10n-de.ustva`). note 197.

   Chart binding:
     - output VAT  → 2200 `Creditors:Tax:VATPayable`
     - input  VAT  → 1120 `CurrentAssets:Debtors:VATRecoverable`

   A consumer whose chart codes VAT differently calls the kernel
   `kontor.tax.vat-return/compute-vat-return` with their own codes."
  (:require [kontor.tax.vat-return :as vat]))

(def ^:const output-vat-code "2200")
(def ^:const input-vat-code  "1120")

(defn compute-vat100
  "Compute an HMRC VAT100 for a filing period over the l10n-uk chart.

   opts: `:from` `:to` (half-open period, required), `:entity` (optional
   ADR-031 scope), `:commodity` (default `:GBP`).

   Returns the kernel `compute-vat-return` map (`:period :output-vat :input-vat
   :net-vat`) plus VAT100 box aliases:
     :box-1-vat-due-on-sales     — output VAT (box 1)
     :box-4-vat-reclaimed        — input VAT (box 4)
     :box-5-net-vat              — net VAT (box 5); > 0 ⇒ payable to HMRC,
                                   < 0 ⇒ repayment due."
  [conn {:keys [from to entity commodity]
         :or   {commodity :GBP}}]
  (let [r (vat/compute-vat-return
           conn (cond-> {:from from :to to
                         :output-vat-codes [output-vat-code]
                         :input-vat-codes  [input-vat-code]
                         :commodity commodity}
                  entity (assoc :entity entity)))]
    (assoc r
           :box-1-vat-due-on-sales (:output-vat r)
           :box-4-vat-reclaimed    (:input-vat r)
           :box-5-net-vat          (:net-vat r))))
