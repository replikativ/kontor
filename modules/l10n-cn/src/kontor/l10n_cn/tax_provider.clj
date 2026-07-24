(ns kontor.l10n-cn.tax-provider
  "Chinese tax provider — the ADR-071 `TaxRateProvider` +
   `TaxPostingBuilder` for CN VAT (增值税). Ports `kontor-l10n-cn`
   onto the kontor tax abstraction, following the AT pilot module
 as the template.

   ## Split

   - **`CnTaxRateProvider`** wraps `kontor.l10n-cn.tax/compute-tax`
     (the rate logic — unchanged, still the published-rate source of
     truth: the general-taxpayer 13/9/6/0 ladder and the small-scale
     1/3/5/0 ladder) and emits a `TaxFacts`. This is the *irregular*
     half: anything CN-specific about *which* rate applies — the
     taxpayer-status dial, the export / exempt status — stays in
     `tax/compute-tax`; the provider only re-shapes its output.
   - **`CnTaxPostingBuilder`** materializes the output-VAT (销项税额)
     posting from a `TaxFacts`, routing to the MOF-canonical single
     2221.01.01 account regardless of rate (Cai Kuai [2016] No. 22 —
     per-rate aggregation is reconstructed at filing time from
     rate-tagged revenue accounts, not from per-rate VAT accounts).
     This is the *regular* half — a `:kind`-driven posting expansion.

   `kontor.l10n-cn.invoice` composes the two per line via
   `kontor.tax.tax-posting-builder/compute-tax-postings` and collapses the
   result with `aggregate-postings` (the G5 multi-line wrapper).

   ## VAT-class → component mapping

   CN has no `:vat-class` keyword — the rate is a BigDecimal pinned
   per line and the `:tax-status` (`:taxable` / `:zero-rated` /
   `:exempt`) selects the regime. The mapping to the closed `:kind`
   enum is therefore by *computed output*:

   | CN line                              | `TaxFacts` component        |
   |--------------------------------------|-----------------------------|
   | positive-rate taxable (13/9/6/3/1/5%)| `:output-vat`               |
   | `:zero-rated` / `:exempt` / rate 0   | none — `rate-facts` → `nil` |

   Zero / exempt produce no output-VAT leg (their revenue still
   routes to 5001.0, which is base-posting work `invoice.clj` keeps).

   ## Period-level surcharges are NOT here

   UMCT / education / local-education surcharges (computed on *net
   VAT payable* at filing time — `kontor.l10n-cn.vat`) are filing-side,
   not per-invoice-line. This provider migrates only the per-line
   output-VAT posting; the surcharges stay where they are."
  (:require [kontor.account :as kacct]
            [kontor.l10n-cn.tax :as tax]
            [kontor.tax.tax-posting-builder :as tpb]
            [kontor.tax.tax-rate-provider :as trp]))

(def ^:const default-output-vat-code
  "MOF-canonical output-VAT (销项税额) payable account code. Cai Kuai
   [2016] No. 22 prescribes a SINGLE 销项税额 account regardless of
   rate; per-rate aggregation is reconstructed at filing time."
  "2221.01.01")

;; ============================================================================
;; CnTaxRateProvider — wraps kontor.l10n-cn.tax/compute-tax
;; ============================================================================

(defn- nonzero? [^java.math.BigDecimal x]
  (not (zero? (.compareTo x 0M))))

(defrecord CnTaxRateProvider []
  trp/TaxRateProvider
  (provider-id [_] :l10n-cn)
  (rate-facts [_ {:keys [base rate taxpayer-status tax-status commodity]
                  :or {taxpayer-status :general tax-status :taxable}}]
    (let [r          (tax/compute-tax (cond-> {:line base
                                               :taxpayer-status taxpayer-status
                                               :tax-status tax-status}
                                        rate (assoc :rate rate)))
          net        (:amount (:net r))
          output-vat (:amount (:output-vat r))
          eff-rate   (:rate r)]
      (if (nonzero? output-vat)
        ;; positive-rate taxable VAT — one :output-vat component.
        (trp/tax-facts
         {:tax-use   :sale
          :line-base net
          :commodity commodity
          :components
          [{:kind         :output-vat
            :rate         eff-rate
            :base         net
            :amount       output-vat
            :recoverable? true
            :provenance   {:provider-id :l10n-cn
                           :rate-source "PRC VAT Law / Cai Shui 2019 No. 39"}
            :jurisdiction-specific-codes
            {:cn/vat-rate         eff-rate
             :cn/taxpayer-status  (:taxpayer-status r)
             :cn/tax-status       (:tax-status r)}}]})
        ;; zero-rated / exempt / 0% — no output VAT, no tax leg.
        nil))))

(defn make-cn-tax-rate-provider
  "Construct the Chinese `TaxRateProvider`."
  [] (->CnTaxRateProvider))

;; ============================================================================
;; CnTaxPostingBuilder — TaxFacts → output-VAT postings
;; ============================================================================

(defn- account-by-code [db code]
  (kacct/resolve-code db code {:context "CN tax posting builder"}))

(defrecord CnTaxPostingBuilder [opts]
  tpb/TaxPostingBuilder
  (builder-id [_] :l10n-cn)
  (tax-postings [_ tax-facts {:keys [db date]}]
    (let [code (:output-vat-code opts default-output-vat-code)
          acct (account-by-code db code)]
      (when-not acct
        (throw (ex-info (str "Account " code " not found — install l10n-cn chart first")
                        {:type :l10n-cn/missing-account :code code})))
      (vec
       (for [c (:components tax-facts)
             ;; CN only emits a leg for :output-vat; there is no
             ;; reverse-charge / withholding seller-side leg here.
             :when (= :output-vat (:kind c))
             :let  [amt (:amount c)]
             :when (nonzero? amt)]
         {:kontor.posting/account      acct
          :kontor.posting/amount       (.negate ^java.math.BigDecimal amt)
          :kontor.posting/commodity    (:commodity tax-facts)
          :kontor.posting/display-type :tax
          :kontor.posting/posted-at    date})))))

(defn make-cn-tax-posting-builder
  "Construct the Chinese `TaxPostingBuilder`. `opts` may carry
   `:output-vat-code` — the account code for the 销项税额 leg, merged
   over `default-output-vat-code` for callers that pin a different
   output-VAT account."
  ([] (make-cn-tax-posting-builder {}))
  ([opts] (->CnTaxPostingBuilder opts)))
