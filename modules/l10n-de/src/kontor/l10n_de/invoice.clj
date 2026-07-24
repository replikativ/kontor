(ns kontor.l10n-de.invoice
  "DE-specific posting-builder for the kernel :invoice → :transaction
   translation. Maps SKR04 conventions:

     debit-side:
       receivable account (1400 default, override per line via
                          :kontor.invoice-line/account, or :seller's
                          configured AR account)

     credit-side (per VAT-rate bucket):
       19% standard:  4400 (Erlöse 19%)  + 3801 (USt 19%)
       7%  ermäßigt: 4300 (Erlöse 7%)   + 3806 (USt 7%)
       0%  steuerfrei: 4200 (Steuerfreie Umsätze §4 UStG)

   Pass `posting-builder` to invoice/send! to invoke.

   ## ADR-071 migration

   The USt computation + USt-payable account routing has moved to
   `kontor.l10n-de.tax-provider` — a bespoke `TaxRateProvider` +
   `TaxPostingBuilder` pair. This namespace composes them per VAT-rate
   bucket via `kontor.tax.tax-posting-builder/compute-tax-postings` and
   collapses the result with `aggregate-postings`. Revenue routing
   stays here — revenue is a base posting, not tax."
  (:require [datahike.api :as d]
            [kontor.account :as kacct]
            [kontor.l10n-de.tax-provider :as tax-provider]
            [kontor.tax.tax-posting-builder :as tpb]))

;; ============================================================================
;; Account lookup
;; ============================================================================

(defn- ace [db code]
  (kacct/resolve-code db code {:context "DE invoice bridge"}))

(def ^:private rev-account-by-rate
  "VAT rate (BigDecimal) → SKR04 revenue account code. Caller can
   override per-line via :kontor.invoice-line/account. Revenue is a base
   posting (not tax) so this routing stays in invoice.clj."
  {19.0M "4400"  ; Erlöse 19% USt
   7.0M  "4300"  ; Erlöse 7% USt
   0.0M  "4200"  ; Steuerfreie Umsätze §4 UStG
   })

;; USt-payable account routing per VAT rate moved to
;; `kontor.l10n-de.tax-provider` (ADR-071 migration).

;; ============================================================================
;; Per-rate bucketing
;; ============================================================================

(defn- bucket-net-by-rate
  "Group lines by VAT rate, summing the per-line net. Returns
   {rate net-BigDecimal}. Per-line net = qty × unit-price rounded
   HALF-EVEN to 2dp; the bucket net is the sum of those."
  [lines]
  (->> lines
       (group-by :kontor.invoice-line/vat-rate)
       (reduce-kv
        (fn [acc rate group]
          (let [net (reduce
                     (fn [^java.math.BigDecimal a l]
                       (.add a (.setScale (.multiply (bigdec (:kontor.invoice-line/quantity l))
                                                     (bigdec (:kontor.invoice-line/unit-price l)))
                                          2 java.math.RoundingMode/HALF_EVEN)))
                     0M group)]
            (assoc acc rate net)))
        {})))

;; ============================================================================
;; Posting-builder
;; ============================================================================

(defn posting-builder
  "Build the kernel posting tx-input from an invoice (pulled by
   invoice/send!). Returns the {:transaction … :postings …} shape
   posting/build-transaction expects.

   Default-overridable inputs:
     :ar-code     — SKR04 receivable code for the debit leg.
                    Default \"1400\".
     :journal-code — journal to file under. Default \"INV\".
     :commodity-symbol — commodity to use. Default the invoice's
                         :kontor.invoice/currency.

   Pass via the third arg of `invoice/send!`'s posting-builder
   closure.

   USt postings are produced by the ADR-071 tax provider/builder
   (`kontor.l10n-de.tax-provider`); revenue postings stay here."
  [{:keys [ar-code journal-code commodity-symbol]
    :or {ar-code "1400" journal-code "INV"}}
   db invoice]
  (let [ext-id    (:kontor.invoice/external-id invoice)
        date      (:kontor.invoice/issue-date invoice)
        currency  (or commodity-symbol (:kontor.invoice/currency invoice) "EUR")
        commodity (:db/id (d/entity db [:kontor.commodity/symbol currency]))
        recv      (ace db ar-code)
        jnl       (:db/id (d/entity db [:kontor.journal/code journal-code]))
        partner   (:db/id (:kontor.invoice/buyer invoice))
        gross     (:kontor.invoice/total-gross invoice)
        ;; Net per VAT-rate bucket — VAT is computed by the provider
        ;; per bucket, so the bucket-level rounding the legacy code
        ;; performed is preserved byte-for-byte.
        buckets   (bucket-net-by-rate (:kontor.invoice/lines invoice))
        provider  (tax-provider/make-de-tax-rate-provider)
        tax-bld   (tax-provider/make-de-tax-posting-builder)
        ;; Revenue credits: one per VAT-rate bucket. Lines' explicit
        ;; :kontor.invoice-line/account override the default revenue account
        ;; when present (rare; usually the rate-driven default is
        ;; right). Revenue is a base posting, not tax.
        revenue-postings
        (vec
         (for [[rate net] buckets
               :let [rev-code (or (rev-account-by-rate rate) "4400")
                     rev-acct (ace db rev-code)]]
           {:kontor.posting/account rev-acct
            :kontor.posting/amount (.negate ^java.math.BigDecimal net)
            :kontor.posting/commodity commodity
            :kontor.posting/posted-at date}))
        ;; USt credits via the ADR-071 provider/builder — run per
        ;; bucket then collapse per USt account.
        ust-postings
        (tpb/aggregate-postings
         (mapcat (fn [[rate net]]
                   (tpb/compute-tax-postings
                    provider tax-bld
                    {:base net :vat-rate rate :commodity commodity}
                    {:db db :date date}))
                 buckets))]
    {:transaction
     (cond-> {:kontor.transaction/external-id ext-id
              :kontor.transaction/journal jnl
              :kontor.transaction/effective-date date
              :kontor.transaction/narration ext-id
              :kontor.transaction/state :posted
              :kontor.transaction/posted-at date}
       partner (assoc :kontor.transaction/partner partner))
     :postings (into [{:kontor.posting/account recv
                       :kontor.posting/amount gross
                       :kontor.posting/commodity commodity
                       :kontor.posting/posted-at date}]
                     (into revenue-postings ust-postings))}))
