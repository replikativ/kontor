(ns kontor.l10n-jp.tax-provider
  "Japanese tax provider — the ADR-071 `TaxRateProvider` +
   `TaxPostingBuilder` for JP Consumption Tax (消費税 / JCT).

   This module is a Shape-B port (research notes 100 / 101): it
   carries `kontor-l10n-jp` onto the kontor tax abstraction, copying
   the `kontor-l10n-at` pilot template.

   ## Split

   - **`JpTaxRateProvider`** wraps `kontor.l10n-jp.consumption-tax/
     compute-tax` (the rate logic — unchanged, still the published-
     rate source of truth) and emits a `TaxFacts`. This is the
     *irregular* half: anything JP-specific about *which* rate
     applies stays in `consumption-tax/compute-tax`; the provider
     only re-shapes its output.
   - **`JpTaxPostingBuilder`** materializes the 仮受消費税
     (output-JCT-payable) posting from a `TaxFacts`, routing by JCT
     class to the JP chart's 215100 / 215200 accounts. This is the
     *regular* half — a `:kind`-driven posting expansion.

   `kontor.l10n-jp.invoice` composes the two per line via
   `kontor.tax-posting-builder/compute-tax-postings` and collapses
   the result with `aggregate-postings` (the G5 multi-line wrapper).

   ## JCT-class → component mapping

   | `:jct-class`                  | `TaxFacts` component        |
   |-------------------------------|-----------------------------|
   | `:standard` (10%) / `:reduced` (8%) | `:output-vat`         |
   | `:non-taxable` (非課税)        | none — `rate-facts` → `nil` |
   | `:export-exempt` (免税)        | none — `rate-facts` → `nil` |
   | `:out-of-scope` (不課税)       | none — `rate-facts` → `nil` |

   The three zero kinds are arithmetically identical (0% JCT) but
   carry distinct input-tax-credit semantics; all three produce NO
   JCT leg. Their revenue still routes to 413000 / 414000 — that is
   base-posting work `invoice.clj` keeps. The JP class rides the
   component's `:jurisdiction-specific-codes` so the QIS / Peppol
   PINT emitters can recover it."
  (:require [datahike.api :as d]
            [kontor.l10n-jp.consumption-tax :as jct]
            [kontor.tax-posting-builder :as tpb]
            [kontor.tax-rate-provider :as trp]))

(def jct-payable-account-codes
  "JP output-JCT (仮受消費税) payable account code per JCT class. The
   three zero classes route to no JCT account — they emit no JCT
   posting."
  {:standard "215100"
   :reduced  "215200"})

;; ============================================================================
;; JpTaxRateProvider — wraps kontor.l10n-jp.consumption-tax/compute-tax
;; ============================================================================

(defrecord JpTaxRateProvider []
  trp/TaxRateProvider
  (provider-id [_] :l10n-jp)
  (rate-facts [_ {:keys [base jct-class commodity] :or {jct-class :standard}}]
    (let [r    (jct/compute-tax {:line base :jct-class jct-class})
          net  (:amount (:net r))
          tax  (:amount (:jct r))
          rate (:rate r)]
      (if (pos? (.signum ^java.math.BigDecimal rate))
        ;; positive-rate JCT — one :output-vat component. JP collects
        ;; consumption tax; the netting kind is :additive (gross =
        ;; net + JCT), identical to a VAT.
        (trp/tax-facts
         {:tax-use    :sale
          :line-base  net
          :commodity  commodity
          :components [{:kind         :output-vat
                        :rate         rate
                        :base         net
                        :amount       tax
                        :recoverable? true
                        :provenance   {:provider-id :l10n-jp
                                       :rate-source "NTA 消費税法"}
                        :jurisdiction-specific-codes {:jp/jct-class jct-class}}]})
        ;; non-taxable / export-exempt / out-of-scope — three distinct
        ;; zero kinds, all 0% JCT, all → no tax leg. nil = no tax.
        nil))))

(defn make-jp-tax-rate-provider
  "Construct the Japanese `TaxRateProvider`."
  []
  (->JpTaxRateProvider))

;; ============================================================================
;; JpTaxPostingBuilder — TaxFacts → output-JCT-payable postings
;; ============================================================================

(defn- account-by-code [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defrecord JpTaxPostingBuilder [opts]
  tpb/TaxPostingBuilder
  (builder-id [_] :l10n-jp)
  (tax-postings [_ tax-facts {:keys [db date]}]
    (let [codes (merge jct-payable-account-codes (:jct-codes opts))]
      (vec
       (for [c (:components tax-facts)
             ;; only :output-vat (collected JCT) produces a leg — the
             ;; three zero kinds never reach here (rate-facts → nil).
             :when (= :output-vat (:kind c))
             :let  [jct-class (get-in c [:jurisdiction-specific-codes
                                         :jp/jct-class])
                    acct      (some->> (get codes jct-class)
                                       (account-by-code db))
                    amt       (:amount c)]
             :when (and acct
                        (not (zero? (.signum ^java.math.BigDecimal amt))))]
         ;; output JCT is a credit on a sale (negative amount).
         {:posting/account      acct
          :posting/amount       (.negate ^java.math.BigDecimal amt)
          :posting/commodity    (:commodity tax-facts)
          :posting/display-type :tax
          :posting/posted-at    date})))))

(defn make-jp-tax-posting-builder
  "Construct the Japanese `TaxPostingBuilder`. `opts` may carry
   `:jct-codes` — a `{jct-class account-code}` map merged over
   `jct-payable-account-codes` for callers that pin different
   仮受消費税 accounts."
  ([] (make-jp-tax-posting-builder {}))
  ([opts] (->JpTaxPostingBuilder opts)))
