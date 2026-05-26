(ns kontor.l10n-jp.invoice
  "Japan Qualified Invoice System (QIS / 適格請求書) — in force since
   2023-10-01.

   Under QIS, a seller must register with the NTA as a Qualified
   Invoice Issuer (適格請求書発行事業者). The seller receives a 14-
   character registration number formatted as 'T' followed by 13
   digits (e.g. 'T1234567890123'). This number must appear on every
   invoice the seller issues; without it, the buyer cannot claim
   input consumption-tax credit. (Transitional 80%/50% input credit
   allowed from non-registered suppliers until Sep 2026 / Sep 2029
   respectively; 0% thereafter.)

   This namespace provides:
     - registration-number validation (T + 13 digits)
     - the per-line invoice shape JP buyers expect (rate breakdown
       per consumption-tax category)
     - a posting builder (`plan-jp-invoice-tx-data` / `post-jp-
       invoice!`) translating an issued QIS invoice into kernel
       transaction + posting tx-data.

   Per ADR-018 there is no clearance-token flow: NTA does not pre-
   approve invoices. The invoice flows directly from seller to buyer
   (or through Peppol). The :transaction goes :draft → :posted
   directly.

   ## Posting builder — ADR-071 tax abstraction (research note 100)

   The JCT (output-consumption-tax) postings are computed through
   the `kontor.l10n-jp.tax-provider` `TaxRateProvider` +
   `TaxPostingBuilder` pair, composed per line via
   `kontor.tax-posting-builder/compute-tax-postings` and collapsed
   with `aggregate-postings`. The provider wraps
   `kontor.l10n-jp.consumption-tax/compute-tax` — the rate logic
   (10% standard, 8% reduced, three zero kinds) is unchanged.

   Revenue routing stays here: revenue is a *base* posting, not a
   tax posting, so the per-class revenue account (411000 / 412000 /
   413000 / 414000) is chosen by this namespace, not the builder.

   Per ADR-068 — tx-data builder + side-effecting wrapper:
     - **`plan-jp-invoice-tx-data`** (pure) — DB value + invoice map
       → tx-data vector ready for the gate.
     - **`post-jp-invoice!`** (side-effecting) — wraps the builder
       and routes through `kontor.validation/transact-with-
       validation`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [kontor.l10n-jp.consumption-tax :as jct]
            [kontor.l10n-jp.tax-provider :as tax-provider]
            [kontor.posting :as posting]
            [kontor.tax-posting-builder :as tpb]
            [kontor.validation :as validation]))

;; ============================================================================
;; Registration number validation
;; ============================================================================

(def ^:private registration-pattern #"^T\d{13}$")

(defn registration-number-valid?
  "True iff `s` matches the NTA-issued T-prefix registration format."
  [s]
  (boolean (and (string? s) (re-matches registration-pattern s))))

(defn assert-registration-number!
  "Throws on invalid registration number, returns the string on success."
  [s]
  (when-not (registration-number-valid? s)
    (throw (ex-info
            "Invalid JP qualified-invoice-issuer registration number"
            {:value s
             :expected-format "T + 13 digits (e.g. T1234567890123)"})))
  s)

;; ============================================================================
;; QIS-mandatory invoice fields
;; ============================================================================

(def required-fields
  "Per NTA's QIS guide, an invoice that allows the buyer to claim
   input tax credit must show:

     1. Issuer's name + registration number
     2. Transaction date
     3. Item descriptions
     4. Taxable amount per rate (separately for 10% and 8%)
     5. Tax-rate marker per item (8%-reduced items must be explicitly
        flagged with a '※' or similar)
     6. Consumption tax amount per rate
     7. Buyer's name

   This vector is the kontor-side checklist; the renderer (Peppol
   PINT JP or PDF / paper) is responsible for laying these out."
  [:issuer/name
   :issuer/registration-number
   :transaction/date
   :buyer/name
   :line-items/by-rate
   :totals/taxable-amount-by-rate
   :totals/tax-amount-by-rate])

(defn validate-qis-fields
  "Return a vector of missing-field complaints; empty when ready to issue.

   `invoice-map` is the user-side invoice shape with QIS-relevant
   fields populated (issuer info, line items split by rate, totals).
   This validator is intentionally permissive — it only checks
   *presence*; downstream layers verify numeric consistency."
  [invoice-map]
  (vec
   (keep (fn [k]
           (let [v (get invoice-map k)]
             (when (or (nil? v)
                       (and (sequential? v) (empty? v))
                       (and (string? v) (str/blank? v)))
               {:field k :issue :missing-or-blank})))
         required-fields)))

;; ============================================================================
;; Default account codes (overridable per call)
;; ============================================================================

(def ^:const default-ar-code "121000")        ; 売掛金 — Accounts receivable
(def ^:const default-cash-code "110100")       ; 現金 — Cash on hand
(def ^:const default-bank-code "110200")       ; 預金 — Bank deposits
(def ^:const default-revenue-standard-code "411000")  ; 売上高 (10%)
(def ^:const default-revenue-reduced-code "412000")   ; 軽減税率売上高 (8%)
(def ^:const default-revenue-exempt-code "413000")    ; 非課税売上
(def ^:const default-revenue-zero-code "414000")      ; 輸出売上 (zero-rated)
(def ^:const default-jct-standard-code "215100")      ; 仮受消費税 (10%)
(def ^:const default-jct-reduced-code "215200")       ; 仮受消費税 (8%)
(def ^:const default-journal-code "INV")
(def ^:const default-commodity "JPY")

(def ^:private jct-class->revenue-code
  "JCT class → revenue account. `:non-taxable` and `:out-of-scope`
   both route to 413000 (非課税売上); `:export-exempt` routes to
   414000 (輸出売上 / zero-rated export). The three zero kinds are
   arithmetically identical but the filing classification differs —
   the chart's `:jp-jct-line-sales-*` tags bucket the JCT return
   correctly off these accounts."
  {:standard      default-revenue-standard-code
   :reduced       default-revenue-reduced-code
   :non-taxable   default-revenue-exempt-code
   :export-exempt default-revenue-zero-code
   :out-of-scope  default-revenue-exempt-code})

;; JCT-payable account routing per JCT class lives in
;; `kontor.l10n-jp.tax-provider` (ADR-071 migration, research note 100).

;; ============================================================================
;; DB lookup helpers
;; ============================================================================

(defn- account-by-code [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :account/code ?c]] db code))

(defn- require-account [db code]
  (or (account-by-code db code)
      (throw (ex-info (str "Account " code " not found — install l10n-jp chart first")
                      {:type :l10n-jp/missing-account
                       :code code}))))

(defn- journal-by-code [db code]
  (:db/id (d/entity db [:journal/code code])))

(defn- commodity-by-symbol [db sym]
  (:db/id (d/entity db [:kontor.commodity/symbol sym])))

;; ============================================================================
;; Per-line helpers
;; ============================================================================

(defn- line-net
  "Per-line net amount = qty × unit-price, rounded HALF-EVEN to whole
   yen (JPY :kontor.commodity/precision 0). Tolerates :invoice-line/line-
   total when the caller pre-computed it."
  ^java.math.BigDecimal [{:invoice-line/keys [quantity unit-price line-total]}]
  (cond
    line-total
    (.setScale (bigdec line-total) 0 java.math.RoundingMode/HALF_EVEN)
    (and quantity unit-price)
    (.setScale (.multiply (bigdec quantity) (bigdec unit-price))
               0 java.math.RoundingMode/HALF_EVEN)
    :else
    (throw (ex-info "Invoice line needs either :invoice-line/line-total or both :invoice-line/quantity + :invoice-line/unit-price"
                    {:line (select-keys
                            {:invoice-line/quantity quantity
                             :invoice-line/unit-price unit-price}
                            [:invoice-line/quantity :invoice-line/unit-price])}))))

(defn- revenue-code-for-class
  "Choose the revenue account for a line's JCT class. Callers can pin
   a different account via :invoice-line/account."
  [jct-class codes]
  (or (get codes jct-class)
      (get jct-class->revenue-code jct-class)
      (throw (ex-info (str "No revenue account configured for JCT class " jct-class)
                      {:jct-class jct-class}))))

;; ============================================================================
;; Tx-data builder (ADR-068 pure form)
;; ============================================================================

(defn- revenue-postings
  "Per-line revenue credit postings. Groups by (revenue-account,
   jct-class) so two lines of the same class produce one summed
   posting, keeping the tx compact."
  [db lines codes commodity-eid date]
  (let [grouped (group-by
                 (fn [line]
                   (let [jct-class (or (:invoice-line/jct-class line) :standard)
                         override  (:invoice-line/account line)]
                     [(or override (revenue-code-for-class jct-class codes))
                      jct-class]))
                 lines)]
    (vec
     (for [[[acct-code _jct-class] ls] grouped
           :let [net  (reduce (fn [^java.math.BigDecimal acc l]
                                (.add acc (line-net l)))
                              0M ls)
                 acct (require-account db acct-code)]]
       {:posting/account   acct
        :posting/amount    (.negate net)
        :posting/commodity commodity-eid
        :posting/posted-at date}))))

(defn- jct-codes-from
  "Translate the per-call `:codes` override map into the
   `{jct-class account-code}` shape `JpTaxPostingBuilder` expects."
  [codes]
  (into {} (keep (fn [[cls k]]
                   (when-let [c (get codes k)] [cls c]))
                 {:standard :standard-jct-code
                  :reduced  :reduced-jct-code})))

(defn- jct-postings
  "Per-invoice output-JCT postings, via the ADR-071 tax provider +
   builder (`kontor.l10n-jp.tax-provider`). Runs the provider per
   line and collapses the per-line JCT legs to one posting per
   rate-account with `kontor.tax-posting-builder/aggregate-postings`."
  [db lines codes commodity-eid date]
  (let [provider (tax-provider/make-jp-tax-rate-provider)
        builder  (tax-provider/make-jp-tax-posting-builder
                  {:jct-codes (jct-codes-from codes)})]
    (tpb/aggregate-postings
     (mapcat (fn [l]
               (tpb/compute-tax-postings
                provider builder
                {:base      (line-net l)
                 :jct-class (or (:invoice-line/jct-class l) :standard)
                 :commodity commodity-eid}
                {:db db :date date}))
             lines))))

(defn plan-jp-invoice-tx-data
  "Pure tx-data builder for a Japanese QIS sales invoice (ADR-068).

   Required input:
     {:invoice/external-id   <string>
      :invoice/issue-date    <java.util.Date>
      :invoice/lines         [<invoice-line>]
      ...}

   Each invoice-line:
     {:invoice-line/quantity    <number-or-bigdec>      ; OR
      :invoice-line/unit-price  <number-or-bigdec>      ; OR pre-computed:
      :invoice-line/line-total  <bigdec>                ; net per line
      :invoice-line/jct-class   <keyword>               ; default :standard
                                                        ; one of
                                                        ; #{:standard
                                                        ;   :reduced
                                                        ;   :non-taxable
                                                        ;   :export-exempt
                                                        ;   :out-of-scope}
      :invoice-line/account     <code>                  ; optional override
      ...}

   Optional top-level fields:
     :invoice/cash-sale?    when true, post Dr cash (110100 default)
                             instead of AR (121000).
     :invoice/cash-code     account-code override for the cash leg
                             (e.g. \"110200\" for bank).
     :invoice/buyer         partner ref (kernel :transaction/partner).
     :invoice/journal       journal code override (default INV).
     :invoice/narration     transaction narration (default external-id).

   Opts:
     :codes        — map of code overrides (`:ar-code`, `:cash-code`,
                     `:standard-code`, `:reduced-code`, `:exempt-code`,
                     `:zero-code`, `:standard-jct-code`,
                     `:reduced-jct-code`).
     :commodity    — commodity symbol (default \"JPY\").
     :journal-code — journal code (default \"INV\").

   Returns a tx-data vector ready for
   `kontor.validation/transact-with-validation`. The builder enforces
   sum-to-zero per the kernel rules (via
   `kontor.posting/build-transaction`)."
  [db invoice {:keys [codes commodity journal-code]
               :or {codes {} commodity default-commodity
                    journal-code default-journal-code}}]
  (let [{:invoice/keys [external-id issue-date lines buyer
                        cash-sale? journal]} invoice
        _ (when-not external-id
            (throw (ex-info "Invoice missing :invoice/external-id" {:invoice invoice})))
        _ (when-not issue-date
            (throw (ex-info "Invoice missing :invoice/issue-date" {:invoice invoice})))
        _ (when (empty? lines)
            (throw (ex-info "Invoice has no :invoice/lines" {:invoice invoice})))
        commodity-eid (or (commodity-by-symbol db commodity)
                          (throw (ex-info (str "Commodity " commodity " not found")
                                          {:commodity commodity})))
        jnl-code (or journal journal-code)
        jnl (or (journal-by-code db jnl-code)
                (throw (ex-info (str "Journal " jnl-code " not found — create it before posting")
                                {:code jnl-code})))
        ;; Tax via the ADR-071 provider/builder (research note 100).
        ;; Revenue routing stays here — it is base-posting work, not tax.
        rev-posts (revenue-postings db lines codes commodity-eid issue-date)
        jct-posts (jct-postings db lines codes commodity-eid issue-date)
        net-sum   (reduce (fn [^java.math.BigDecimal a l]
                            (.add a (line-net l)))
                          0M lines)
        ;; JCT postings are credits (negative); gross = net − Σ(jct).
        gross     (reduce (fn [^java.math.BigDecimal a p]
                            (.subtract a ^java.math.BigDecimal (:posting/amount p)))
                          net-sum jct-posts)
        debit-code (if cash-sale?
                     (get codes :cash-code default-cash-code)
                     (get codes :ar-code default-ar-code))
        debit-acct (require-account db debit-code)
        debit-post {:posting/account   debit-acct
                    :posting/amount    gross
                    :posting/commodity commodity-eid
                    :posting/posted-at issue-date}
        tx-base (cond-> {:transaction/external-id external-id
                         :transaction/journal jnl
                         :transaction/effective-date issue-date
                         :transaction/narration (or (:invoice/narration invoice)
                                                    external-id)
                         :transaction/state :posted
                         :transaction/posted-at issue-date}
                  buyer (assoc :transaction/partner buyer))
        input {:transaction tx-base
               :postings (into [debit-post] (into rev-posts jct-posts))}]
    (posting/build-transaction input)))

(defn post-jp-invoice!
  "Side-effecting wrapper around `plan-jp-invoice-tx-data` (ADR-068).
   Routes the tx-data through `kontor.validation/transact-with-
   validation` so the kernel gate (sealing / period / sum-to-zero /
   invariants) applies.

   See `plan-jp-invoice-tx-data` for the input shape + options."
  ([conn invoice] (post-jp-invoice! conn invoice {}))
  ([conn invoice opts]
   (let [tx-data (plan-jp-invoice-tx-data (d/db conn) invoice opts)]
     (validation/transact-with-validation conn tx-data))))

;; ============================================================================
;; Validation helpers (caller-side)
;; ============================================================================

(defn validate-invoice
  "Return a vector of complaints; empty when ready to post. Used by
   consumers to surface input issues *before* hitting the gate."
  [invoice]
  (let [{:invoice/keys [external-id issue-date lines]} invoice
        line-classes (map #(or (:invoice-line/jct-class %) :standard) lines)]
    (cond-> []
      (or (nil? external-id) (and (string? external-id) (str/blank? external-id)))
      (conj {:field :invoice/external-id :issue :missing-or-blank})
      (nil? issue-date)
      (conj {:field :invoice/issue-date :issue :missing})
      (empty? lines)
      (conj {:field :invoice/lines :issue :empty})
      (some #(not (contains? jct/jct-classes %)) line-classes)
      (conj {:field :invoice-line/jct-class :issue :invalid-class
             :seen (vec line-classes)}))))
