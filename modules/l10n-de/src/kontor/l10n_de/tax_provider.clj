(ns kontor.l10n-de.tax-provider
  "German tax provider — the ADR-071 `TaxRateProvider` +
   `TaxPostingBuilder` for DE Umsatzsteuer (VAT). Ports
   `kontor-l10n-de` onto the kontor tax abstraction (research notes
   100 / 101); the AT module is the pilot template this one copies.

   ## Why a fresh rate layer

   Unlike `kontor-l10n-at`, `kontor-l10n-de` never had a `tax.clj` —
   the USt rates (19% / 7% / 0%) and the SKR04 account routing were
   hardcoded inside `invoice.clj` (the `rev-account-by-rate` /
   `vat-account-by-rate` tables). So this namespace authors the rate
   table fresh; it is still Shape B (a bespoke provider + builder,
   exactly like AT), the only difference being that the rate function
   is written here rather than wrapping a pre-existing `tax/compute-tax`.

   ## Split

   - **`DeTaxRateProvider`** holds the DE USt rate table and emits a
     `TaxFacts`. This is the *irregular* half: anything DE-specific
     about *which* rate applies lives here; the provider only
     re-shapes the outcome into the closed `TaxFacts` vocabulary.
   - **`DeTaxPostingBuilder`** materializes the USt-payable posting
     from a `TaxFacts`, routing by VAT rate to the SKR04 chart's
     3801 (19%) / 3806 (7%) accounts. This is the *regular* half — a
     `:kind`-driven posting expansion.

   `kontor.l10n-de.invoice` composes the two per line via
   `kontor.tax-posting-builder/compute-tax-postings` and collapses the
   result with `aggregate-postings` (the G5 multi-line wrapper).

   ## VAT-rate → component mapping

   | `:vat-rate` (BigDecimal) | `TaxFacts` component        |
   |--------------------------|-----------------------------|
   | `19.0M` (Regelsteuersatz)| `:output-vat`               |
   | `7.0M`  (ermäßigt)       | `:output-vat`               |
   | `0.0M`  (steuerfrei §4)  | none — `rate-facts` → `nil` |

   Steuerfreie Umsätze produce no USt leg (their revenue still routes
   to 4200, which is base-posting work `invoice.clj` keeps). The DE
   rate + USt-class ride the component's `:jurisdiction-specific-codes`
   so downstream consumers (UStVA reporting) can tell the rates apart.

   ## Rounding

   USt is `net × rate/100`, the rate/100 division taken at 6-digit
   HALF-EVEN and the product rounded to 2 decimal places HALF-EVEN —
   byte-identical to the legacy `bucket-by-rate` arithmetic so the
   per-account sums the existing tests assert stay green."
  (:require [datahike.api :as d]
            [kontor.tax-posting-builder :as tpb]
            [kontor.tax-rate-provider :as trp]))

;; ============================================================================
;; DE USt rate table — authored fresh (no l10n-de tax.clj existed)
;; ============================================================================

(def vat-rate->ust-class
  "DE USt rate (BigDecimal, as carried on `:invoice-line/vat-rate`) →
   a stable class keyword stashed in `:jurisdiction-specific-codes`."
  {19.0M :standard
   7.0M  :reduced
   0.0M  :exempt})

(def ust-account-codes
  "DE output-VAT (USt) payable account code per USt class. The exempt
   class routes to no USt account — steuerfreie Umsätze emit no USt
   posting (the whole line goes to revenue)."
  {:standard "3801"   ; Umsatzsteuer 19%
   :reduced  "3806"}) ; Umsatzsteuer 7%

(defn- compute-ust
  "USt amount for `net` at `vat-rate` (a percentage BigDecimal such as
   `19.0M`). Matches the legacy `bucket-by-rate` arithmetic exactly:
   the rate is divided by 100 at 6-digit HALF-EVEN, the product
   rounded to 2dp HALF-EVEN."
  ^java.math.BigDecimal [^java.math.BigDecimal net ^java.math.BigDecimal vat-rate]
  (.setScale (.multiply net
                        (.divide vat-rate 100M
                                 6 java.math.RoundingMode/HALF_EVEN))
             2 java.math.RoundingMode/HALF_EVEN))

;; ============================================================================
;; DeTaxRateProvider — DE USt rate table → TaxFacts
;; ============================================================================

(defrecord DeTaxRateProvider []
  trp/TaxRateProvider
  (provider-id [_] :l10n-de)
  (rate-facts [_ {:keys [base vat-rate commodity] :or {vat-rate 19.0M}}]
    (let [vat-rate  (bigdec vat-rate)
          net       (bigdec base)
          ust-class (get vat-rate->ust-class vat-rate :standard)
          ust       (compute-ust net vat-rate)]
      ;; A positive USt amount is the only taxable case. Steuerfreie
      ;; Umsätze (0%) compute to 0 and emit no tax leg.
      (if (pos? (.signum ust))
        (trp/tax-facts
         {:tax-use   :sale
          :line-base net
          :commodity commodity
          :components
          [{:kind         :output-vat
            ;; :rate as a fraction (0.19M / 0.07M) — the TaxFacts
            ;; convention (StaticTableProvider, AT/AU providers).
            :rate         (.divide vat-rate 100M
                                   6 java.math.RoundingMode/HALF_EVEN)
            :base         net
            :amount       ust
            :recoverable? true
            :provenance   {:provider-id :l10n-de :rate-source "BMF UStG"}
            :jurisdiction-specific-codes {:de/ust-class ust-class
                                          :de/vat-rate  vat-rate}}]})
        ;; steuerfrei §4 UStG — no USt, no tax leg.
        nil))))

(defn make-de-tax-rate-provider
  "Construct the German `TaxRateProvider`."
  [] (->DeTaxRateProvider))

;; ============================================================================
;; DeTaxPostingBuilder — TaxFacts → USt-payable postings
;; ============================================================================

(defn- account-by-code [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defrecord DeTaxPostingBuilder [opts]
  tpb/TaxPostingBuilder
  (builder-id [_] :l10n-de)
  (tax-postings [_ tax-facts {:keys [db date]}]
    (let [codes (merge ust-account-codes (:ust-codes opts))]
      (vec
       (for [c (:components tax-facts)
             ;; :output-vat → one USt-payable credit; nothing else
             ;; reaches the builder for DE.
             :when (= :output-vat (:kind c))
             :let  [ust-class (get-in c [:jurisdiction-specific-codes
                                         :de/ust-class])
                    acct      (some->> (get codes ust-class)
                                       (account-by-code db))
                    amt       (:amount c)]
             :when (and acct (not (zero? (.signum ^java.math.BigDecimal amt))))]
         {:kontor.posting/account      acct
          :kontor.posting/amount       (.negate ^java.math.BigDecimal amt)
          :kontor.posting/commodity    (:commodity tax-facts)
          :kontor.posting/display-type :tax
          :kontor.posting/posted-at    date})))))

(defn make-de-tax-posting-builder
  "Construct the German `TaxPostingBuilder`. `opts` may carry
   `:ust-codes` — a `{ust-class account-code}` map merged over
   `ust-account-codes` for callers that pin different USt accounts."
  ([] (make-de-tax-posting-builder {}))
  ([opts] (->DeTaxPostingBuilder opts)))
