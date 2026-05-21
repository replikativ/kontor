(ns kontor.l10n-fr.tax-provider
  "French tax provider — the ADR-071 `TaxRateProvider` +
   `TaxPostingBuilder` for FR TVA (Taxe sur la valeur ajoutée). Ports
   `kontor-l10n-fr` onto the kontor tax abstraction, following the
   `kontor-l10n-at` pilot (research notes 100 / 101).

   ## Split

   - **`FrTaxRateProvider`** wraps `kontor.l10n-fr.tax/compute-tax`
     (the rate logic — unchanged, still the published-rate source of
     truth: CGI art.278 à 278-0 ter) and emits a `TaxFacts`. This is
     the *irregular* half: anything FR-specific about *which* rate
     applies stays in `tax/compute-tax`; the provider only re-shapes
     its output.
   - **`FrTaxPostingBuilder`** materializes the TVA-collectée posting
     from a `TaxFacts`, routing by TVA rate to the FR PCG's
     44571 / 44572 / 44573 / 44574 accounts. This is the *regular*
     half — a `:kind`-driven posting expansion.

   `kontor.l10n-fr.invoice` composes the two per line via
   `kontor.tax-posting-builder/compute-tax-postings` and collapses the
   result with `aggregate-postings` (the G5 multi-line wrapper).

   ## FR class → component mapping

   FR splits the line classification into two axes: a `:rate` keyword
   (`:std :inter :red :spec :zero`) and a `:tax-status` keyword
   (`:taxable :exempt :intra-eu-b2b :export`). The provider context
   carries both; `compute-tax` is the authority on whether TVA falls.

   | FR class                          | `TaxFacts` component        |
   |-----------------------------------|-----------------------------|
   | `:taxable` + `:std/:inter/:red/:spec` | `:output-vat`           |
   | `:taxable` + `:zero`              | none — `rate-facts` → `nil` |
   | `:intra-eu-b2b` (reverse charge)  | `:reverse-charge` (seller-side → no leg) |
   | `:export` / `:exempt`            | none — `rate-facts` → `nil` |

   Zero / exempt / export produce no TVA leg (their revenue still
   routes to 7081 / 706, which is base-posting work `invoice.clj`
   keeps). Intra-EU B2B is seller-side reverse charge — the buyer
   self-assesses (CGI art.283-1), so this builder emits no TVA leg.

   ## Rounding

   The provider does NOT round — it forwards whatever
   `kontor.l10n-fr.tax/compute-tax` produces (HALF-EVEN at 2dp per
   that ns's documented mode). Rounding stays the rate ns's concern."
  (:require [datahike.api :as d]
            [kontor.l10n-fr.tax :as tax]
            [kontor.tax-posting-builder :as tpb]
            [kontor.tax-rate-provider :as trp]))

(def tva-account-codes
  "FR TVA-collectée payable account code per TVA rate keyword. Zero,
   exempt, export, and intra-EU B2B route to no TVA account — they
   emit no TVA posting. PCG 4457x is the TVA collectée family."
  {:std   "44571"
   :inter "44572"
   :red   "44573"
   :spec  "44574"})

;; ============================================================================
;; FrTaxRateProvider — wraps kontor.l10n-fr.tax/compute-tax
;; ============================================================================

(defn- amount-of
  "Pull the BigDecimal out of a `kontor.money/Money` record (or pass a
   bare BigDecimal through). `compute-tax` returns Money for its TVA /
   net fields; the `TaxFacts` contract is plain BigDecimal."
  ^java.math.BigDecimal [m]
  (if (instance? java.math.BigDecimal m) m (:amount m)))

(defrecord FrTaxRateProvider []
  trp/TaxRateProvider
  (provider-id [_] :l10n-fr)
  (rate-facts [_ {:keys [base rate tax-status commodity]
                  :or   {rate :std tax-status :taxable}}]
    (let [r    (tax/compute-tax {:line base :rate rate :tax-status tax-status})
          net  (amount-of (:net r))
          tva  (amount-of (:tva r))
          rate-value (:rate-value r)
          component
          (fn [kind]
            {:kind         kind
             :rate         rate-value
             :base         net
             :amount       tva
             :recoverable? true
             :provenance   {:provider-id :l10n-fr :rate-source "CGI art.278"}
             :jurisdiction-specific-codes {:fr/tva-rate   rate
                                           :fr/tax-status tax-status}})]
      (cond
        ;; intra-EU B2B — reverse charge. FR is seller-side, so the
        ;; builder emits no leg; the marker rides the base posting.
        (= tax-status :intra-eu-b2b)
        (trp/tax-facts {:tax-use :sale :line-base net :commodity commodity
                        :components [(component :reverse-charge)]})

        ;; positive-rate TVA — one :output-vat component
        (pos? (.signum ^java.math.BigDecimal tva))
        (trp/tax-facts {:tax-use :sale :line-base net :commodity commodity
                        :components [(component :output-vat)]})

        ;; zero-rate / exempt / export — no TVA, no tax leg
        :else nil))))

(defn make-fr-tax-rate-provider
  "Construct the French `TaxRateProvider`."
  [] (->FrTaxRateProvider))

;; ============================================================================
;; FrTaxPostingBuilder — TaxFacts → TVA-collectée postings
;; ============================================================================

(defn- account-by-code [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :account/code ?c]] db code))

(defrecord FrTaxPostingBuilder [opts]
  tpb/TaxPostingBuilder
  (builder-id [_] :l10n-fr)
  (tax-postings [_ tax-facts {:keys [db date]}]
    (let [codes (merge tva-account-codes (:tva-codes opts))]
      (vec
       (for [c (:components tax-facts)
             ;; :reverse-charge → no leg (FR seller-side); :output-vat
             ;; → one TVA-collectée credit.
             :when (= :output-vat (:kind c))
             :let  [tva-rate (get-in c [:jurisdiction-specific-codes :fr/tva-rate])
                    acct     (some->> (get codes tva-rate) (account-by-code db))
                    amt      (:amount c)]
             :when (and acct (not (zero? (.signum ^java.math.BigDecimal amt))))]
         {:posting/account      acct
          :posting/amount       (.negate ^java.math.BigDecimal amt)
          :posting/commodity    (:commodity tax-facts)
          :posting/display-type :tax
          :posting/posted-at    date})))))

(defn make-fr-tax-posting-builder
  "Construct the French `TaxPostingBuilder`. `opts` may carry
   `:tva-codes` — a `{tva-rate account-code}` map merged over
   `tva-account-codes` for callers that pin different TVA accounts."
  ([] (make-fr-tax-posting-builder {}))
  ([opts] (->FrTaxPostingBuilder opts)))
