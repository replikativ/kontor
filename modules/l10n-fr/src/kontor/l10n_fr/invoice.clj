(ns kontor.l10n-fr.invoice
  "French invoice posting builder — translates an issued invoice into
   kernel transaction + posting tx-data per the PCG (Plan Comptable
   Général) conventions.

   Sits between `kontor.l10n-fr.tax/compute-tax` (rate logic) and
   `kontor.posting/build-transaction` (structural validation + sum-to-
   zero). Accepts an FR-shaped invoice and emits the per-rate tax
   postings:

     Dr  Client (411x — AR)              gross
     Cr  Revenue (706/707/7065/.../7081) net (per rate / status)
     Cr  TVA collectée (4457x by rate)   tva

   ## Account routing — PCG conventions

   Revenue is split by rate AND by client destination:

     706     — Prestations de services (taux normal)
     707     — Ventes de marchandises (taux normal)
     7065    — Prestations à 10%
     7066    — Prestations à 5,5%
     7067    — Prestations à 2,1%
     7081    — Livraisons intra-UE exonérées (art.262 ter I)

   The default `:line-account-by-rate` table routes lines to the
   `:service` columns (706/7065/7066/7067) since most French SMBs are
   service-providers. Override per-line via `:kontor.invoice-line/account`
   when a line is a `:goods` sale that should hit `707`.

   TVA collectée is split by rate per CA3 reporting:

     44571 — TVA 20%
     44572 — TVA 10%
     44573 — TVA 5,5%
     44574 — TVA 2,1%

   ## Tax-status routing

   `:taxable` (default) — rate-table lookup; revenue + TVA postings.
   `:exempt`             — revenue only, no TVA (services bancaires,
                           location nue, santé, enseignement).
                           Routed to 706 by default (caller can
                           override via :kontor.invoice-line/account).
   `:intra-eu-b2b`       — revenue only, routed to 7081 (livraisons
                           intra-UE exonérées). Reverse-charge: buyer
                           self-assesses TVA in destination country.
   `:export`             — revenue only, routed to 7081 by default
                           too (most charts conflate). Caller can
                           override per-line.

   ## Encaissements vs débits (CGI art.269)

   FR service providers default to TVA `sur les encaissements` (the
   tax becomes due when payment is received), while goods sellers
   are always `sur les débits` (when the invoice issues). This
   distinction is modeled per-`:tax` entity (`:kontor.tax/exigibility
   :on-payment` vs `:on-invoice`) and surfaces in the CA3 report's
   inclusion test — NOT in this builder. The seller's *journal entry*
   is the same in both cases.

   ## Per ADR-068

   - **`plan-fr-invoice-tx-data`** (pure) — takes a DB value + invoice
     map, returns the tx-data vector ready for the gate.
   - **`post-fr-invoice!`** (side-effecting) — wraps the builder and
     routes through `kontor.validation/transact-with-validation`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [kontor.account :as kacct]
            [kontor.l10n-fr.tax :as tax]
            [kontor.l10n-fr.tax-provider :as tax-provider]
            [kontor.posting :as posting]
            [kontor.tax.tax-posting-builder :as tpb]
            [kontor.validation :as validation]))

;; ============================================================================
;; Default account codes (overridable per call)
;; ============================================================================

(def ^:const default-ar-code "411")
(def ^:const default-cash-code "5121")
(def ^:const default-journal-code "VTE")
(def ^:const default-commodity "EUR")

(def default-revenue-by-rate
  "Default revenue (Produits) account per TVA rate. Caller can
   override per-line via :kontor.invoice-line/account."
  {:std   "706"     ; Prestations de services
   :inter "7065"    ; Prestations à 10%
   :red   "7066"    ; Prestations à 5,5%
   :spec  "7067"    ; Prestations à 2,1%
   :zero  "7081"})  ; Livraisons intra-UE exonérées (closest default)

(def default-revenue-by-status
  "Default revenue account for non-taxable lines. The caller can
   override per-line."
  {:taxable      nil               ; rate-table lookup
   :exempt       "706"             ; conventional; caller usually overrides
   :intra-eu-b2b "7081"
   :export       "7081"})

;; TVA-collectée account routing per rate moved to
;; `kontor.l10n-fr.tax-provider` (ADR-071 migration).

;; ============================================================================
;; DB lookup helpers
;; ============================================================================

(defn- account-by-code [db code]
  (kacct/resolve-code db code {:context "FR invoice bridge"}))

(defn- require-account [db code]
  (or (account-by-code db code)
      (throw (ex-info (str "Account " code " not found — install l10n-fr chart first")
                      {:type :l10n-fr/missing-account
                       :code code}))))

(defn- journal-by-code [db code]
  (:db/id (d/entity db [:kontor.journal/code code])))

(defn- commodity-by-symbol [db sym]
  (:db/id (d/entity db [:kontor.commodity/symbol sym])))

;; ============================================================================
;; Per-line routing
;; ============================================================================

(defn- revenue-code-for-line
  "Resolve the revenue account code for one line. Precedence:
     1. :kontor.invoice-line/account override (a code string)
     2. status-based default (:exempt / :intra-eu-b2b / :export)
     3. rate-based default for :taxable lines (:std/:inter/:red/...)."
  [{:kontor.invoice-line/keys [account tax-status rate]
    :or {tax-status :taxable rate :std}}
   {:keys [revenue-by-rate revenue-by-status]}]
  (or (when (string? account) account)
      (when (not= tax-status :taxable)
        (get revenue-by-status tax-status))
      (get revenue-by-rate rate)
      (throw (ex-info "Cannot resolve revenue account for line"
                      {:tax-status tax-status :rate rate}))))

;; ============================================================================
;; Tx-data builder (ADR-068 pure form)
;; ============================================================================

(defn- bd ^java.math.BigDecimal [^java.math.BigDecimal x] x)

(defn- line-net
  "Per-line net amount = qty × unit-price, rounded HALF-EVEN to 2dp.
   Tolerates :kontor.invoice-line/line-total when caller pre-computed it."
  ^java.math.BigDecimal [{:kontor.invoice-line/keys [quantity unit-price line-total]}]
  (cond
    line-total (bigdec line-total)
    (and quantity unit-price)
    (.setScale (.multiply (bigdec quantity) (bigdec unit-price))
               2 java.math.RoundingMode/HALF_EVEN)
    :else
    (throw (ex-info "Invoice line needs either :kontor.invoice-line/line-total or both :kontor.invoice-line/quantity + :kontor.invoice-line/unit-price"
                    {:line (select-keys
                            {:kontor.invoice-line/quantity quantity
                             :kontor.invoice-line/unit-price unit-price}
                            [:kontor.invoice-line/quantity :kontor.invoice-line/unit-price])}))))

(defn- revenue-postings
  "Per-line revenue credit postings. Groups by revenue-account so two
   lines that route to the same account produce one summed posting,
   keeping the tx compact."
  [db lines codes commodity-eid date]
  (let [grouped (group-by
                 (fn [line] (revenue-code-for-line line codes))
                 lines)]
    (vec
     (for [[acct-code ls] grouped
           :let [net (reduce (fn [^java.math.BigDecimal acc l]
                               (.add acc (line-net l)))
                             0M ls)
                 acct (require-account db acct-code)]]
       {:kontor.posting/account acct
        :kontor.posting/amount (.negate (bd net))
        :kontor.posting/commodity commodity-eid
        :kontor.posting/posted-at date}))))

(defn- tax-postings
  "Per-invoice TVA-collectée postings, via the ADR-071 tax provider +
   builder (`kontor.l10n-fr.tax-provider`). Runs the provider per line
   and collapses the per-line TVA legs to one posting per rate-account
   with `kontor.tax.tax-posting-builder/aggregate-postings`.

   `codes` carries the resolved `:tva-by-rate` `{rate account-code}`
   override map, which the builder consumes as its `:tva-codes` opt."
  [db lines codes commodity-eid date]
  (let [provider (tax-provider/make-fr-tax-rate-provider)
        builder  (tax-provider/make-fr-tax-posting-builder
                  {:tva-codes (:tva-by-rate codes)})]
    (tpb/aggregate-postings
     (mapcat (fn [l]
               (tpb/compute-tax-postings
                provider builder
                {:base       (line-net l)
                 :rate       (or (:kontor.invoice-line/rate l) :std)
                 :tax-status (or (:kontor.invoice-line/tax-status l) :taxable)
                 :commodity  commodity-eid}
                {:db db :date date}))
             lines))))

(defn plan-fr-invoice-tx-data
  "Pure tx-data builder for a French sales invoice (ADR-068).

   Required input:
     {:kontor.invoice/external-id <string>
      :kontor.invoice/issue-date  <java.util.Date>
      :kontor.invoice/lines       [<invoice-line>]}

   Each invoice-line:
     {:kontor.invoice-line/quantity   <number-or-bigdec>      ; OR
      :kontor.invoice-line/unit-price <number-or-bigdec>      ; OR pre-computed:
      :kontor.invoice-line/line-total <bigdec>                ; net per line
      :kontor.invoice-line/rate       <keyword>               ; :std :inter :red :spec :zero
      :kontor.invoice-line/tax-status <keyword>               ; default :taxable
      :kontor.invoice-line/account    <code>                  ; optional override
      ...}

   Optional top-level fields:
     :kontor.invoice/cash-sale?     when true, post Dr cash (5121) instead of
                              AR (411).
     :kontor.invoice/cash-code      account-code override for the cash leg.
     :kontor.invoice/ar-code        account-code override for the AR leg.
     :kontor.invoice/buyer          partner ref (kernel :kontor.transaction/partner).
     :kontor.invoice/journal        journal code override (default VTE).

   Opts:
     :codes        — map of code overrides
                     (`:ar-code`, `:cash-code`,
                      `:revenue-by-rate`, `:revenue-by-status`,
                      `:tva-by-rate`).
     :commodity    — commodity symbol (default \"EUR\").
     :journal-code — journal code (default \"VTE\" — journal des ventes).

   Returns a tx-data vector ready for
   `kontor.validation/transact-with-validation`."
  [db invoice {:keys [codes commodity journal-code]
               :or {codes {} commodity default-commodity
                    journal-code default-journal-code}}]
  (let [{:kontor.invoice/keys [external-id issue-date lines buyer cash-sale? journal
                        ar-code cash-code]} invoice
        _ (when-not external-id
            (throw (ex-info "Invoice missing :kontor.invoice/external-id" {:invoice invoice})))
        _ (when-not issue-date
            (throw (ex-info "Invoice missing :kontor.invoice/issue-date" {:invoice invoice})))
        _ (when (empty? lines)
            (throw (ex-info "Invoice has no :kontor.invoice/lines" {:invoice invoice})))
        commodity-eid (or (commodity-by-symbol db commodity)
                          (throw (ex-info (str "Commodity " commodity " not found")
                                          {:commodity commodity})))
        jnl-code (or journal journal-code)
        jnl (or (journal-by-code db jnl-code)
                (throw (ex-info (str "Journal " jnl-code " not found — create it before posting")
                                {:code jnl-code})))
        ;; `:tva-by-rate` is intentionally absent from the defaults —
        ;; `kontor.l10n-fr.tax-provider` owns the TVA-account routing
        ;; and supplies its own default; a caller-passed `:tva-by-rate`
        ;; in `:codes` flows through as the builder's `:tva-codes`
        ;; override.
        codes' (merge {:revenue-by-rate default-revenue-by-rate
                       :revenue-by-status default-revenue-by-status}
                      codes)
        debit-code (cond
                     cash-sale? (or cash-code (:cash-code codes') default-cash-code)
                     ar-code    ar-code
                     :else      (or (:ar-code codes') default-ar-code))
        debit-acct (require-account db debit-code)
        ;; Revenue routing stays here — it is base-posting work, not
        ;; tax. TVA goes through the ADR-071 provider/builder.
        rev-posts (revenue-postings db lines codes' commodity-eid issue-date)
        tax-posts (tax-postings db lines codes' commodity-eid issue-date)
        ;; The debit (AR / cash) leg is gross = net + Σ(TVA). TVA
        ;; postings are credits (negative), so gross = net − Σ(amounts).
        net-sum   (reduce (fn [^java.math.BigDecimal a l]
                            (.add a (line-net l)))
                          0M lines)
        gross     (reduce (fn [^java.math.BigDecimal a p]
                            (.subtract a ^java.math.BigDecimal (:kontor.posting/amount p)))
                          net-sum tax-posts)
        debit-post {:kontor.posting/account debit-acct
                    :kontor.posting/amount gross
                    :kontor.posting/commodity commodity-eid
                    :kontor.posting/posted-at issue-date}
        tx-base (cond-> {:kontor.transaction/external-id external-id
                         :kontor.transaction/journal jnl
                         :kontor.transaction/effective-date issue-date
                         :kontor.transaction/narration (or (:kontor.invoice/narration invoice)
                                                    external-id)
                         :kontor.transaction/state :posted
                         :kontor.transaction/posted-at issue-date}
                  buyer (assoc :kontor.transaction/partner buyer))
        input {:transaction tx-base
               :postings (into [debit-post] (into rev-posts tax-posts))}]
    (posting/build-transaction input)))

(defn post-fr-invoice!
  "Side-effecting wrapper around `plan-fr-invoice-tx-data` (ADR-068).
   Routes the tx-data through `kontor.validation/transact-with-
   validation` so the kernel gate (sealing / period / sum-to-zero /
   invariants) applies.

   See `plan-fr-invoice-tx-data` for the input shape + options."
  ([conn invoice] (post-fr-invoice! conn invoice {}))
  ([conn invoice opts]
   (let [tx-data (plan-fr-invoice-tx-data (d/db conn) invoice opts)]
     (validation/transact-with-validation conn tx-data))))

;; ============================================================================
;; Convenience predicates
;; ============================================================================

(defn rate?
  "True iff `k` is a recognised TVA rate keyword."
  [k]
  (contains? tax/tva-rates k))

(defn tax-status?
  "True iff `k` is a recognised invoice tax-status keyword."
  [k]
  (contains? tax/tax-statuses k))

;; ============================================================================
;; Validation helpers (caller-side)
;; ============================================================================

(defn validate-invoice
  "Return a vector of complaints; empty when ready to post.
   Used by consumers to surface input issues *before* hitting the
   gate."
  [invoice]
  (let [{:kontor.invoice/keys [external-id issue-date lines]} invoice]
    (cond-> []
      (or (nil? external-id) (and (string? external-id) (str/blank? external-id)))
      (conj {:field :kontor.invoice/external-id :issue :missing-or-blank})
      (nil? issue-date)
      (conj {:field :kontor.invoice/issue-date :issue :missing})
      (empty? lines)
      (conj {:field :kontor.invoice/lines :issue :empty}))))
