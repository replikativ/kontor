(ns kontor.l10n-de.invoice
  "DE-specific posting-builder for the kernel :invoice → :transaction
   translation. Maps SKR04 conventions:

     debit-side:
       receivable account (1400 default, override per line via
                          :invoice-line/account, or :seller's
                          configured AR account)

     credit-side (per VAT-rate bucket):
       19% standard:  4400 (Erlöse 19%)  + 3801 (USt 19%)
       7%  ermäßigt: 4300 (Erlöse 7%)   + 3806 (USt 7%)
       0%  steuerfrei: 4200 (Steuerfreie Umsätze §4 UStG)
       reverse-charge / EU: 4125 / 4120

   Pass `posting-builder` to invoice/send! to invoke."
  (:require [datahike.api :as d]
            [kontor.invoice :as inv]))

;; ============================================================================
;; Account lookup
;; ============================================================================

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :account/code ?c]] db code))

(def ^:private rev-account-by-rate
  "VAT rate (BigDecimal) → SKR04 revenue account code. Caller can
   override per-line via :invoice-line/account."
  {19.0M "4400"  ; Erlöse 19% USt
   7.0M  "4300"  ; Erlöse 7% USt
   0.0M  "4200"  ; Steuerfreie Umsätze §4 UStG
   })

(def ^:private vat-account-by-rate
  "VAT rate → SKR04 USt-payable account code. Steuerfrei has no
   VAT account (the line goes 100% to revenue)."
  {19.0M "3801"  ; Umsatzsteuer 19%
   7.0M  "3806"  ; Umsatzsteuer 7%
   })

;; ============================================================================
;; Per-rate bucketing
;; ============================================================================

(defn- bucket-by-rate
  "Group lines by VAT rate. Returns {rate {:net … :vat …}}."
  [lines]
  (->> lines
       (group-by :invoice-line/vat-rate)
       (reduce-kv
        (fn [acc rate group]
          (let [net (reduce
                     (fn [^java.math.BigDecimal a l]
                       (.add a (.setScale (.multiply (bigdec (:invoice-line/quantity l))
                                                     (bigdec (:invoice-line/unit-price l)))
                                          2 java.math.RoundingMode/HALF_EVEN)))
                     0M group)
                vat (.setScale (.multiply net
                                          (.divide (bigdec rate) 100M
                                                   6 java.math.RoundingMode/HALF_EVEN))
                               2 java.math.RoundingMode/HALF_EVEN)]
            (assoc acc rate {:net net :vat vat})))
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
                         :invoice/currency.

   Pass via the third arg of `invoice/send!`'s posting-builder
   closure."
  [{:keys [ar-code journal-code commodity-symbol]
    :or {ar-code "1400" journal-code "INV"}}
   db invoice]
  (let [ext-id    (:invoice/external-id invoice)
        date      (:invoice/issue-date invoice)
        currency  (or commodity-symbol (:invoice/currency invoice) "EUR")
        commodity (:db/id (d/entity db [:commodity/symbol currency]))
        recv      (ace db ar-code)
        jnl       (:db/id (d/entity db [:journal/code journal-code]))
        partner   (:db/id (:invoice/buyer invoice))
        gross     (:invoice/total-gross invoice)
        buckets   (bucket-by-rate (:invoice/lines invoice))
        ;; Build credit postings: one revenue + one VAT (when rate>0)
        ;; per bucket. Lines' explicit :invoice-line/account override
        ;; the default revenue account when present (rare; usually the
        ;; rate-driven default is right).
        credit-postings
        (vec
         (mapcat
          (fn [[rate {:keys [net vat]}]]
            (let [rev-code  (or (rev-account-by-rate rate) "4400")
                  rev-acct  (ace db rev-code)
                  postings [{:posting/account rev-acct
                             :posting/amount (.negate ^java.math.BigDecimal net)
                             :posting/commodity commodity
                             :posting/posted-at date}]]
              (if-let [vat-code (vat-account-by-rate rate)]
                (conj postings
                      {:posting/account (ace db vat-code)
                       :posting/amount (.negate ^java.math.BigDecimal vat)
                       :posting/commodity commodity
                       :posting/posted-at date})
                postings)))
          buckets))]
    {:transaction
     (cond-> {:transaction/external-id ext-id
              :transaction/journal jnl
              :transaction/effective-date date
              :transaction/narration ext-id
              :transaction/state :posted
              :transaction/posted-at date}
       partner (assoc :transaction/partner partner))
     :postings (into [{:posting/account recv
                       :posting/amount gross
                       :posting/commodity commodity
                       :posting/posted-at date}]
                     credit-postings)}))
