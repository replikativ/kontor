(ns kontor.l10n-at.invoice
  "Austrian invoice posting builder — translates an issued AT
   invoice into kernel transaction + posting tx-data.

   Sits between `kontor.l10n-at.tax/compute-tax` (rate logic) and
   `kontor.posting/build-transaction` (structural validation +
   sum-to-zero). Accepts an AT-shaped invoice and emits the
   appropriate per-rate revenue + USt postings:

     Dr  Forderungen (2000, or cash 2700 / bank 2800)  gross
     Cr  Erlöse 20% (4000)                              net @ 20%
     Cr  Erlöse 13% (4010)                              net @ 13%
     Cr  Erlöse 10% (4020)                              net @ 10%
     Cr  Erlöse intra-EU (4100)                         net @ 0% zero
     Cr  Erlöse Steuerfrei (4200)                       net @ 0% exempt
     Cr  Erlöse Reverse-Charge (4300)                   net (RC)
     Cr  USt 20% (3500)                                 ust @ 20%
     Cr  USt 13% (3510)                                 ust @ 13%
     Cr  USt 10% (3520)                                 ust @ 10%

   ## Reverse charge

   For `:reverse-charge` lines (§19 Abs.1a Bauleistungen, intra-EU
   B2B), the supplier emits ZERO output VAT — the recipient
   self-assesses. The supplier's invoice must disclose
   `Steuerschuldnerschaft des Leistungsempfängers gemäß §19
   Abs.1a UStG`; this builder posts revenue only (no 3530 USt
   posting) and never emits a recipient-side input-VAT credit
   (that belongs to the recipient's books, not ours).

   ## Zero-rated vs exempt revenue routing

   Both `:zero` (intra-EU §6 Abs.1 Z.6, exports §7 UStG) and
   `:exempt` (§6 Abs.1 — financial / medical / education / etc.)
   produce 0 USt arithmetic, but route revenue to different
   accounts:

     :zero   → 4100 (Erlöse intra-EU + Export, UVA Kz 011)
     :exempt → 4200 (Erlöse Steuerfrei,        UVA Kz 021)

   The UVA filing-side report uses the `[:uva-011]` and
   `[:uva-021]` account tags to bucket these correctly — so it's
   important the chart-defined tags on 4100 / 4200 match the
   compute classification.

   ## Per ADR-068 — tx-data builder + side-effecting wrapper

   - **`plan-at-invoice-tx-data`** (pure) — takes a DB value +
     invoice map, returns the tx-data vector ready for the gate.
   - **`post-at-invoice!`** (side-effecting) — wraps the builder
     and routes through `kontor.validation/transact-with-
     validation`.

   Consumers composing several writes into one process (invoice +
   Factur-X export + audit doc) call the pure builder from a
   `kontor.process` step."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [kontor.l10n-at.tax :as tax]
            [kontor.l10n-at.tax-provider :as tax-provider]
            [kontor.posting :as posting]
            [kontor.tax-posting-builder :as tpb]
            [kontor.validation :as validation]))

;; ============================================================================
;; Default account codes (overridable per call)
;; ============================================================================

(def ^:const default-ar-code "2000")
(def ^:const default-cash-code "2700")
(def ^:const default-bank-code "2800")
(def ^:const default-revenue-standard-code "4000")
(def ^:const default-revenue-reduced-13-code "4010")
(def ^:const default-revenue-reduced-10-code "4020")
(def ^:const default-revenue-zero-code "4100")
(def ^:const default-revenue-exempt-code "4200")
(def ^:const default-revenue-reverse-charge-code "4300")
(def ^:const default-ust-standard-code "3500")
(def ^:const default-ust-reduced-13-code "3510")
(def ^:const default-ust-reduced-10-code "3520")
(def ^:const default-journal-code "INV")
(def ^:const default-commodity "EUR")

(def ^:private vat-class->revenue-code
  {:standard       default-revenue-standard-code
   :reduced-13     default-revenue-reduced-13-code
   :reduced-10     default-revenue-reduced-10-code
   :zero           default-revenue-zero-code
   :exempt         default-revenue-exempt-code
   :reverse-charge default-revenue-reverse-charge-code})

;; USt-payable account routing per VAT class moved to
;; `kontor.l10n-at.tax-provider` (ADR-071 migration, research note 100).

;; ============================================================================
;; DB lookup helpers
;; ============================================================================

(defn- account-by-code [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :account/code ?c]] db code))

(defn- require-account [db code]
  (or (account-by-code db code)
      (throw (ex-info (str "Account " code " not found — install l10n-at chart first")
                      {:type :l10n-at/missing-account
                       :code code}))))

(defn- journal-by-code [db code]
  (:db/id (d/entity db [:journal/code code])))

(defn- commodity-by-symbol [db sym]
  (:db/id (d/entity db [:commodity/symbol sym])))

;; ============================================================================
;; Per-line helpers
;; ============================================================================

(defn- bd ^java.math.BigDecimal [^java.math.BigDecimal x] x)

(defn- line-net
  "Per-line net amount = qty × unit-price, rounded HALF-EVEN to 2dp.
   Tolerates :invoice-line/line-total when caller pre-computed it."
  ^java.math.BigDecimal [{:invoice-line/keys [quantity unit-price line-total]}]
  (cond
    line-total (bigdec line-total)
    (and quantity unit-price)
    (.setScale (.multiply (bigdec quantity) (bigdec unit-price))
               2 java.math.RoundingMode/HALF_EVEN)
    :else
    (throw (ex-info "Invoice line needs either :invoice-line/line-total or both :invoice-line/quantity + :invoice-line/unit-price"
                    {:line (select-keys
                            {:invoice-line/quantity quantity
                             :invoice-line/unit-price unit-price}
                            [:invoice-line/quantity :invoice-line/unit-price])}))))

(defn- revenue-code-for-class
  "Choose the revenue account for a line's VAT class. Callers can
   pin a different account via :invoice-line/account."
  [vat-class codes]
  (or (get codes vat-class)
      (get vat-class->revenue-code vat-class)
      (throw (ex-info (str "No revenue account configured for VAT class " vat-class)
                      {:vat-class vat-class}))))

;; ============================================================================
;; Tx-data builder (ADR-068 pure form)
;; ============================================================================

(defn- revenue-postings
  "Per-line revenue credit postings. Groups by (revenue-account,
   vat-class) so two lines of the same class produce one summed
   posting, keeping the tx compact."
  [db lines codes commodity-eid date]
  (let [grouped (group-by
                 (fn [line]
                   (let [vat-class (or (:invoice-line/vat-class line) :standard)
                         override (:invoice-line/account line)]
                     [(or override (revenue-code-for-class vat-class codes))
                      vat-class]))
                 lines)]
    (vec
     (for [[[acct-code _vat-class] ls] grouped
           :let [net (reduce (fn [^java.math.BigDecimal acc l]
                               (.add acc (line-net l)))
                             0M ls)
                 acct (require-account db acct-code)]]
       {:posting/account acct
        :posting/amount (.negate (bd net))
        :posting/commodity commodity-eid
        :posting/posted-at date}))))

(defn- ust-codes-from
  "Translate the per-call `:codes` override map into the
   `{vat-class account-code}` shape `AtTaxPostingBuilder` expects."
  [codes]
  (into {} (keep (fn [[cls k]]
                   (when-let [c (get codes k)] [cls c]))
                 {:standard   :standard-ust-code
                  :reduced-13 :reduced-13-ust-code
                  :reduced-10 :reduced-10-ust-code})))

(defn- at-ust-postings
  "Per-invoice USt postings, via the ADR-071 tax provider + builder
   (`kontor.l10n-at.tax-provider`). Runs the provider per line and
   collapses the per-line USt legs to one posting per rate-account
   with `kontor.tax-posting-builder/aggregate-postings`."
  [db lines codes commodity-eid date]
  (let [provider (tax-provider/make-at-tax-rate-provider)
        builder  (tax-provider/make-at-tax-posting-builder
                  {:ust-codes (ust-codes-from codes)})]
    (tpb/aggregate-postings
     (mapcat (fn [l]
               (tpb/compute-tax-postings
                provider builder
                {:base      (line-net l)
                 :vat-class (or (:invoice-line/vat-class l) :standard)
                 :commodity commodity-eid}
                {:db db :date date}))
             lines))))

(defn plan-at-invoice-tx-data
  "Pure tx-data builder for an Austrian sales invoice (ADR-068).

   Required input:
     {:invoice/external-id   <string>
      :invoice/issue-date    <java.util.Date>
      :invoice/lines         [<invoice-line>]
      ...}

   Each invoice-line:
     {:invoice-line/quantity    <number-or-bigdec>      ; OR
      :invoice-line/unit-price  <number-or-bigdec>      ; OR pre-computed:
      :invoice-line/line-total  <bigdec>                ; net per line
      :invoice-line/vat-class   <keyword>               ; default :standard
                                                        ; one of
                                                        ; #{:standard
                                                        ;   :reduced-13
                                                        ;   :reduced-10
                                                        ;   :zero
                                                        ;   :exempt
                                                        ;   :reverse-charge}
      :invoice-line/account     <code-or-eid>           ; optional override
      ...}

   Optional top-level fields:
     :invoice/cash-sale?    when true, post Dr cash (2700 by default)
                             instead of AR (2000).
     :invoice/cash-code     account-code override for the cash leg
                             (e.g. \"2800\" for bank).
     :invoice/buyer         partner ref (kernel :transaction/partner).
     :invoice/journal       journal code override (default INV).
     :invoice/narration     transaction narration (default external-id).

   Opts:
     :codes        — map of code overrides (`:ar-code`, `:cash-code`,
                     `:standard-code`, `:reduced-13-code`,
                     `:reduced-10-code`, `:zero-code`, `:exempt-code`,
                     `:reverse-charge-code`, `:standard-ust-code`,
                     `:reduced-13-ust-code`, `:reduced-10-ust-code`).
     :commodity    — commodity symbol (default \"EUR\").
     :journal-code — journal code (default \"INV\").

   Returns a tx-data vector ready for
   `kontor.validation/transact-with-validation`.

   The builder enforces sum-to-zero per the kernel rules (via
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
        ust-posts (at-ust-postings db lines codes commodity-eid issue-date)
        net-sum   (reduce (fn [^java.math.BigDecimal a l]
                            (.add a (line-net l)))
                          0M lines)
        ;; USt postings are credits (negative); gross = net − Σ(ust).
        gross     (reduce (fn [^java.math.BigDecimal a p]
                            (.subtract a ^java.math.BigDecimal (:posting/amount p)))
                          net-sum ust-posts)
        debit-code (if cash-sale?
                     (get codes :cash-code default-cash-code)
                     (get codes :ar-code default-ar-code))
        debit-acct (require-account db debit-code)
        debit-post {:posting/account debit-acct
                    :posting/amount gross
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
               :postings (into [debit-post] (into rev-posts ust-posts))}]
    (posting/build-transaction input)))

(defn post-at-invoice!
  "Side-effecting wrapper around `plan-at-invoice-tx-data` (ADR-068).
   Routes the tx-data through `kontor.validation/transact-with-
   validation` so the kernel gate (sealing / period / sum-to-zero /
   invariants) applies.

   See `plan-at-invoice-tx-data` for the input shape + options."
  ([conn invoice] (post-at-invoice! conn invoice {}))
  ([conn invoice opts]
   (let [tx-data (plan-at-invoice-tx-data (d/db conn) invoice opts)]
     (validation/transact-with-validation conn tx-data))))

;; ============================================================================
;; Validation helpers (caller-side)
;; ============================================================================

(defn validate-invoice
  "Return a vector of complaints; empty when ready to post.
   Used by consumers to surface input issues *before* hitting the
   gate."
  [invoice]
  (let [{:invoice/keys [external-id issue-date lines]} invoice
        line-classes (map #(or (:invoice-line/vat-class %) :standard) lines)]
    (cond-> []
      (or (nil? external-id) (and (string? external-id) (str/blank? external-id)))
      (conj {:field :invoice/external-id :issue :missing-or-blank})
      (nil? issue-date)
      (conj {:field :invoice/issue-date :issue :missing})
      (empty? lines)
      (conj {:field :invoice/lines :issue :empty})
      (some #(not (contains? tax/vat-classes %)) line-classes)
      (conj {:field :invoice-line/vat-class :issue :invalid-class
             :seen (vec line-classes)}))))

(defn reverse-charge?
  "True iff this invoice contains any reverse-charge line — the
   invoice must then disclose `Steuerschuldnerschaft des
   Leistungsempfängers gemäß §19 Abs.1a UStG` per the supplier's
   §11 Abs.1 Z.6 disclosure obligation."
  [invoice]
  (boolean
   (some #(= :reverse-charge (or (:invoice-line/vat-class %) :standard))
         (:invoice/lines invoice))))

(defn intra-eu?
  "True iff this invoice contains any zero-rated line (intra-EU
   §6 Abs.1 Z.6 / export §7 UStG). The invoice must then carry the
   recipient's UID + the supplier's UID; this flag is a hint to
   the consumer to gather both."
  [invoice]
  (boolean
   (some #(= :zero (or (:invoice-line/vat-class %) :standard))
         (:invoice/lines invoice))))
