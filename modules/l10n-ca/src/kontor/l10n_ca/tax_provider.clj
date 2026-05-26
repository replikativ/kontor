(ns kontor.l10n-ca.tax-provider
  "Canadian tax provider — the ADR-071 `TaxRateProvider` +
   `TaxPostingBuilder` for CA indirect tax (federal GST/HST +
   provincial PST/RST + Quebec QST). Ports `kontor-l10n-ca` onto the
   kontor tax abstraction; mirrors the `kontor-l10n-at` pilot
   (research notes 100 / 101).

   ## The multi-authority case

   CA is the genuinely-interesting Shape-B module: a *single* invoice
   line can attract up to FOUR parallel taxes — federal GST *or* HST,
   plus provincial PST/RST and/or QST. ADR-071's `TaxFacts` already
   supports this directly: its `:components` field is a *vector*, one
   component per authority that fires. No substrate change is needed —
   the multi-authority shape IS a multi-component `TaxFacts`.

   ## Split

   - **`CaTaxRateProvider`** wraps `kontor.l10n-ca.tax/compute-tax`
     (the rate logic — unchanged, still the published-rate source of
     truth) and re-shapes its per-authority `{:gst :hst :pst :qst}`
     breakdown into a `TaxFacts` with one component per non-zero
     authority. This is the *irregular* half.
   - **`CaTaxPostingBuilder`** routes each component to its own
     CA-chart tax-payable account, reading the component's
     `:jurisdiction` (the authority) to pick the account. This is the
     *regular* half — a `:kind` + `:authority`-driven posting
     expansion.

   `kontor.l10n-ca.invoice` composes the two per line via
   `kontor.tax-posting-builder/compute-tax-postings` and collapses the
   result with `aggregate-postings` (the G5 multi-line wrapper).

   ## Authority → component mapping

   | Authority      | Tax       | `:kind`        | Recoverable? |
   |----------------|-----------|----------------|--------------|
   | `:ca-cra`      | GST / HST | `:output-vat`  | yes (ITC)    |
   | `:ca-rq`       | QST       | `:output-vat`  | yes (ITR)    |
   | `:bc-finance`  | BC PST    | `:sales-tax`   | no           |
   | `:sk-finance`  | SK PST    | `:sales-tax`   | no           |
   | `:mb-finance`  | MB RST    | `:sales-tax`   | no           |

   GST/HST and QST are VAT-style — recoverable, mapped to
   `:output-vat`. PST/RST is a single-stage retail tax — *not*
   recoverable, mapped to `:sales-tax`. The `:kind` distinction is
   real (it drives the ADR-071 `kind-effect` netting) and matches the
   tax economics, even though for a *seller-side* invoice both add to
   the gross identically.

   `:zero-rated` / `:exempt` / `:non-resident` lines produce no tax
   component — `rate-facts` returns `nil`. Their revenue still routes
   to the zero-rated / exempt accounts, which is base-posting work
   `invoice.clj` keeps."
  (:require [datahike.api :as d]
            [kontor.l10n-ca.tax :as tax]
            [kontor.tax-posting-builder :as tpb]
            [kontor.tax-rate-provider :as trp]))

;; ============================================================================
;; Authority registry — per-authority metadata
;; ============================================================================

(def gst-hst-account-code
  "CA chart account code for GST/HST collected (CRA authority)."
  "2310")

(def qst-account-code
  "CA chart account code for QST collected (Revenu Québec authority)."
  "2330")

(def pst-account-code-by-province
  "Provincial-sales-tax-collected account code per PST province. MB's
   RST is semantically a PST and shares the routing shape."
  {:BC "2320"
   :SK "2321"
   :MB "2322"})

(def pst-authority-by-province
  "The provincial finance authority that administers PST/RST, by
   province. Stamped onto the component's `:jurisdiction` so the
   posting builder can route to the right liability account."
  {:BC :bc-finance
   :SK :sk-finance
   :MB :mb-finance})

;; ============================================================================
;; CaTaxRateProvider — wraps kontor.l10n-ca.tax/compute-tax
;; ============================================================================

(defn- bd
  "Extract the BigDecimal amount from a Money record (or pass a
   BigDecimal through)."
  ^java.math.BigDecimal [m]
  (cond
    (instance? java.math.BigDecimal m) m
    (and (map? m) (contains? m :amount)) (:amount m)
    (number? m) (bigdec m)
    :else (throw (ex-info "Cannot coerce to BigDecimal" {:value m}))))

(defn- nonzero?
  [^java.math.BigDecimal x]
  (not (zero? (.compareTo x 0M))))

(defn- vat-component
  "A `:output-vat` component (GST/HST or QST) — recoverable, additive."
  [{:keys [base amount rate authority ca-tax-code]}]
  {:kind         :output-vat
   :rate         rate
   :base         base
   :amount       amount
   :recoverable? true
   :provenance   {:provider-id :l10n-ca :rate-source "CRA / RQ rate tables"}
   :jurisdiction {:authority authority}
   :jurisdiction-specific-codes {:ca/tax-code ca-tax-code}})

(defn- pst-component
  "A `:sales-tax` component (PST/RST) — non-recoverable, additive."
  [{:keys [base amount rate authority ca-tax-code]}]
  {:kind         :sales-tax
   :rate         rate
   :base         base
   :amount       amount
   :recoverable? false
   :provenance   {:provider-id :l10n-ca :rate-source "Provincial finance rate tables"}
   :jurisdiction {:authority authority}
   :jurisdiction-specific-codes {:ca/tax-code ca-tax-code}})

(defn- effective-rate
  "Reconstruct the rate that `compute-tax` applied for one authority,
   so the component carries it. Pulls from the published rate tables
   keyed by the ship-to province."
  [authority-key province]
  (case authority-key
    :gst (when (contains? tax/gst-provinces province) tax/gst-rate)
    :hst (get tax/hst-rate-by-province province)
    :pst (get tax/pst-rate-by-province province)
    :qst (when (contains? tax/qst-provinces province) tax/qst-rate)
    nil))

(defrecord CaTaxRateProvider []
  trp/TaxRateProvider
  (provider-id [_] :l10n-ca)
  (rate-facts [_ {:keys [base ship-to-province tax-status commodity]
                  :or   {tax-status :taxable}}]
    (let [r          (tax/compute-tax {:line base
                                       :ship-to-province ship-to-province
                                       :tax-status tax-status})
          net        (bd (:net r))
          gst-amt    (bd (:gst r))
          hst-amt    (bd (:hst r))
          pst-amt    (bd (:pst r))
          qst-amt    (bd (:qst r))
          components
          (cond-> []
            ;; Federal — GST and HST are mutually exclusive per
            ;; province; both route to CRA as :output-vat.
            (nonzero? gst-amt)
            (conj (vat-component
                   {:base net :amount gst-amt
                    :rate (effective-rate :gst ship-to-province)
                    :authority :ca-cra :ca-tax-code :ca/gst}))
            (nonzero? hst-amt)
            (conj (vat-component
                   {:base net :amount hst-amt
                    :rate (effective-rate :hst ship-to-province)
                    :authority :ca-cra :ca-tax-code :ca/hst}))
            ;; Provincial PST/RST — non-recoverable :sales-tax.
            (nonzero? pst-amt)
            (conj (pst-component
                   {:base net :amount pst-amt
                    :rate (effective-rate :pst ship-to-province)
                    :authority (get pst-authority-by-province ship-to-province)
                    :ca-tax-code :ca/pst}))
            ;; Quebec QST — VAT-style, :output-vat, RQ authority.
            (nonzero? qst-amt)
            (conj (vat-component
                   {:base net :amount qst-amt
                    :rate (effective-rate :qst ship-to-province)
                    :authority :ca-rq :ca-tax-code :ca/qst})))]
      ;; zero-rated / exempt / non-resident → no components → nil facts
      (when (seq components)
        (trp/tax-facts {:tax-use      :sale
                        :line-base    net
                        :commodity    commodity
                        :jurisdiction {:country "CA" :subdivision ship-to-province}
                        :components   components})))))

(defn make-ca-tax-rate-provider
  "Construct the Canadian `TaxRateProvider`."
  []
  (->CaTaxRateProvider))

;; ============================================================================
;; CaTaxPostingBuilder — TaxFacts → per-authority tax-payable postings
;; ============================================================================

(defn- account-by-code [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- account-code-for-component
  "Resolve the CA-chart tax-payable account code for one `TaxFacts`
   component, dispatching on its authority. `codes` is the per-call
   override map (same shape `invoice.clj` already accepts)."
  [component codes]
  (let [authority (get-in component [:jurisdiction :authority])]
    (case authority
      :ca-cra (:gst-hst-code codes gst-hst-account-code)
      :ca-rq  (:qst-code codes qst-account-code)
      :bc-finance (:bc-pst-code codes (get pst-account-code-by-province :BC))
      :sk-finance (:sk-pst-code codes (get pst-account-code-by-province :SK))
      :mb-finance (:mb-rst-code codes (get pst-account-code-by-province :MB))
      nil)))

(defrecord CaTaxPostingBuilder [opts]
  tpb/TaxPostingBuilder
  (builder-id [_] :l10n-ca)
  (tax-postings [_ tax-facts {:keys [db date]}]
    (let [codes (:codes opts {})]
      (vec
       (for [c (:components tax-facts)
             :let  [code (account-code-for-component c codes)
                    acct (some->> code (account-by-code db))
                    amt  (bd (:amount c))]
             :when (and acct (nonzero? amt))]
         {:posting/account      acct
          :posting/amount       (.negate amt)
          :posting/commodity    (:commodity tax-facts)
          :posting/display-type :tax
          :posting/posted-at    date})))))

(defn make-ca-tax-posting-builder
  "Construct the Canadian `TaxPostingBuilder`. `opts` may carry
   `:codes` — the per-call account-code override map (keys
   `:gst-hst-code`, `:qst-code`, `:bc-pst-code`, `:sk-pst-code`,
   `:mb-rst-code`) — merged over the chart defaults."
  ([] (make-ca-tax-posting-builder {}))
  ([opts] (->CaTaxPostingBuilder opts)))
