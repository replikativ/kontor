(ns kontor.l10n-at.tax-provider
  "Austrian tax provider — the ADR-071 `TaxRateProvider` +
   `TaxPostingBuilder` for AT USt (VAT). This is the pilot module of
   the per-l10n tax-provider migration (research notes 100 / 101): it
   ports `kontor-l10n-at` onto the kontor tax abstraction and is the
   template the other Shape-B modules copy.

   ## Split

   - **`AtTaxRateProvider`** wraps `kontor.l10n-at.tax/compute-tax`
     (the rate logic — unchanged, still the published-rate source of
     truth) and emits a `TaxFacts`. This is the *irregular* half:
     anything AT-specific about *which* rate applies stays in
     `tax/compute-tax`; the provider only re-shapes its output.
   - **`AtTaxPostingBuilder`** materializes the USt-payable posting
     from a `TaxFacts`, routing by VAT class to the AT chart's
     3500 / 3510 / 3520 accounts. This is the *regular* half — a
     `:kind`-driven posting expansion.

   `kontor.l10n-at.invoice` composes the two per line via
   `kontor.tax-posting-builder/compute-tax-postings` and collapses the
   result with `aggregate-postings` (the G5 multi-line wrapper).

   ## VAT-class → component mapping

   | `:vat-class`                  | `TaxFacts` component        |
   |-------------------------------|-----------------------------|
   | `:standard` / `:reduced-13/10`| `:output-vat`               |
   | `:reverse-charge`             | `:reverse-charge` (seller-side → no leg) |
   | `:zero` / `:exempt`           | none — `rate-facts` → `nil` |

   Zero / exempt produce no USt leg (their revenue still routes to
   4100 / 4200, which is base-posting work `invoice.clj` keeps)."
  (:require [datahike.api :as d]
            [kontor.l10n-at.tax :as tax]
            [kontor.tax-posting-builder :as tpb]
            [kontor.tax-rate-provider :as trp]))

(def ust-account-codes
  "AT output-VAT (USt) payable account code per VAT class. Zero,
   exempt, and reverse-charge route to no USt account — they emit no
   USt posting."
  {:standard   "3500"
   :reduced-13 "3510"
   :reduced-10 "3520"})

;; ============================================================================
;; AtTaxRateProvider — wraps kontor.l10n-at.tax/compute-tax
;; ============================================================================

(defrecord AtTaxRateProvider []
  trp/TaxRateProvider
  (provider-id [_] :l10n-at)
  (rate-facts [_ {:keys [base vat-class commodity] :or {vat-class :standard}}]
    (let [r    (tax/compute-tax {:line base :vat-class vat-class})
          net  (:amount (:net r))
          ust  (:amount (:ust r))
          rate (:rate r)
          component
          (fn [kind]
            {:kind         kind
             :rate         rate
             :base         net
             :amount       ust
             :recoverable? true
             :provenance   {:provider-id :l10n-at :rate-source "BMF UStG"}
             :jurisdiction-specific-codes {:at/vat-class vat-class}})]
      (cond
        ;; reverse charge — a component (AT is seller-side, so the
        ;; builder emits no leg; the marker rides the base posting).
        (= vat-class :reverse-charge)
        (trp/tax-facts {:tax-use :sale :line-base net :commodity commodity
                        :components [(component :reverse-charge)]})

        ;; positive-rate VAT — one :output-vat component
        (pos? (.signum ^java.math.BigDecimal rate))
        (trp/tax-facts {:tax-use :sale :line-base net :commodity commodity
                        :components [(component :output-vat)]})

        ;; zero / exempt — no output VAT, no tax leg
        :else nil))))

(defn make-at-tax-rate-provider
  "Construct the Austrian `TaxRateProvider`."
  [] (->AtTaxRateProvider))

;; ============================================================================
;; AtTaxPostingBuilder — TaxFacts → USt-payable postings
;; ============================================================================

(defn- account-by-code [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defrecord AtTaxPostingBuilder [opts]
  tpb/TaxPostingBuilder
  (builder-id [_] :l10n-at)
  (tax-postings [_ tax-facts {:keys [db date]}]
    (let [codes (merge ust-account-codes (:ust-codes opts))]
      (vec
       (for [c (:components tax-facts)
             ;; :reverse-charge → no leg (AT seller-side); :output-vat
             ;; → one USt-payable credit.
             :when (= :output-vat (:kind c))
             :let  [vat-class (get-in c [:jurisdiction-specific-codes :at/vat-class])
                    acct      (some->> (get codes vat-class) (account-by-code db))
                    amt       (:amount c)]
             :when (and acct (not (zero? (.signum ^java.math.BigDecimal amt))))]
         {:posting/account      acct
          :posting/amount       (.negate ^java.math.BigDecimal amt)
          :posting/commodity    (:commodity tax-facts)
          :posting/display-type :tax
          :posting/posted-at    date})))))

(defn make-at-tax-posting-builder
  "Construct the Austrian `TaxPostingBuilder`. `opts` may carry
   `:ust-codes` — a `{vat-class account-code}` map merged over
   `ust-account-codes` for callers that pin different USt accounts."
  ([] (make-at-tax-posting-builder {}))
  ([opts] (->AtTaxPostingBuilder opts)))
