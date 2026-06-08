(ns kontor.l10n-au.tax-provider
  "Australian tax provider — the ADR-071 `TaxRateProvider` +
   `TaxPostingBuilder` for AU GST. Ports `kontor-l10n-au` onto the
   kontor tax abstraction; the AT module is
   the pilot template this one copies.

   ## Split

   - **`AuTaxRateProvider`** wraps `kontor.l10n-au.tax/compute-tax`
     (the rate logic — unchanged, still the published-rate source of
     truth) and emits a `TaxFacts`. This is the *irregular* half:
     anything AU-specific about *which* status applies stays in
     `tax/compute-tax`; the provider only re-shapes its output.
   - **`AuTaxPostingBuilder`** materializes the GST-payable posting
     from a `TaxFacts`, routing to the AU chart's 21500 GST-payable
     account. This is the *regular* half — a `:kind`-driven posting
     expansion.

   `kontor.l10n-au.invoice` composes the two per line via
   `kontor.tax.tax-posting-builder/compute-tax-postings` and collapses the
   result with `aggregate-postings` (the G5 multi-line wrapper).

   ## Tax-status → component mapping

   AU operates a single-rate (10%) federal GST — there is no state /
   territory sales tax. The trivial case:

   | `:tax-status`  | `TaxFacts` component        |
   |----------------|-----------------------------|
   | `:taxable`     | `:output-vat` (GST collected) |
   | `:gst-free`    | none — `rate-facts` → `nil`  |
   | `:input-taxed` | none — `rate-facts` → `nil`  |

   GST-free and input-taxed both produce 0% tax and no GST leg; they
   differ only on revenue routing (which is base-posting work
   `invoice.clj` keeps). The AU class rides the component's
   `:jurisdiction-specific-codes` so a downstream consumer can still
   tell the two apart."
  (:require [datahike.api :as d]
            [kontor.l10n-au.tax :as tax]
            [kontor.tax.tax-posting-builder :as tpb]
            [kontor.tax.tax-rate-provider :as trp]))

(def gst-payable-code
  "AU output-GST payable account code. Single federal rate — one
   account. GST-free and input-taxed route to no GST account; they
   emit no GST posting."
  "21500")

;; ============================================================================
;; AuTaxRateProvider — wraps kontor.l10n-au.tax/compute-tax
;; ============================================================================

(defrecord AuTaxRateProvider []
  trp/TaxRateProvider
  (provider-id [_] :l10n-au)
  (rate-facts [_ {:keys [base tax-status commodity] :or {tax-status :taxable}}]
    (let [r   (tax/compute-tax {:line base :tax-status tax-status})
          net (:amount (:net r))
          gst (:amount (:gst r))]
      ;; Single 10% GST: `:taxable` is the only status carrying a GST
      ;; leg. `:gst-free` / `:input-taxed` compute 0 GST and emit no
      ;; leg (their revenue still routes — base-posting work the
      ;; invoice builder keeps). Dispatch on the status, not the GST
      ;; sign, so a negative line (refund) still emits a (negative)
      ;; output-VAT component rather than silently dropping the leg.
      (if (= :taxable (:tax-status r))
        (trp/tax-facts
         {:tax-use   :sale
          :line-base net
          :commodity commodity
          :components
          [{:kind         :output-vat
            :rate         tax/gst-rate
            :base         net
            :amount       gst
            :recoverable? true
            :provenance   {:provider-id :l10n-au :rate-source "ATO GST"}
            :jurisdiction-specific-codes {:au/tax-status tax-status}}]})
        ;; gst-free / input-taxed — no GST, no tax leg.
        nil))))

(defn make-au-tax-rate-provider
  "Construct the Australian `TaxRateProvider`."
  [] (->AuTaxRateProvider))

;; ============================================================================
;; AuTaxPostingBuilder — TaxFacts → GST-payable postings
;; ============================================================================

(defn- account-by-code [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defrecord AuTaxPostingBuilder [opts]
  tpb/TaxPostingBuilder
  (builder-id [_] :l10n-au)
  (tax-postings [_ tax-facts {:keys [db date]}]
    (let [code (or (:gst-payable-code opts) gst-payable-code)]
      (vec
       (for [c (:components tax-facts)
             ;; :output-vat → one GST-payable credit; nothing else
             ;; reaches the builder for AU.
             :when (= :output-vat (:kind c))
             :let  [acct (account-by-code db code)
                    amt  (:amount c)]
             :when (and acct (not (zero? (.signum ^java.math.BigDecimal amt))))]
         {:kontor.posting/account      acct
          :kontor.posting/amount       (.negate ^java.math.BigDecimal amt)
          :kontor.posting/commodity    (:commodity tax-facts)
          :kontor.posting/display-type :tax
          :kontor.posting/posted-at    date})))))

(defn make-au-tax-posting-builder
  "Construct the Australian `TaxPostingBuilder`. `opts` may carry
   `:gst-payable-code` — an account-code override for callers that pin
   a different GST-payable account than the chart default 21500."
  ([] (make-au-tax-posting-builder {}))
  ([opts] (->AuTaxPostingBuilder opts)))
