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
   service-providers. Override per-line via `:invoice-line/account`
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
                           override via :invoice-line/account).
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
   distinction is modeled per-`:tax` entity (`:tax/exigibility
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
            [kontor.l10n-fr.tax :as tax]
            [kontor.posting :as posting]
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
   override per-line via :invoice-line/account."
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

(def default-tva-by-rate
  "TVA collectée account per rate. Per the FR PCG (Plan Comptable
   Général) 4457x prefix is the TVA collectée family."
  {:std   "44571"
   :inter "44572"
   :red   "44573"
   :spec  "44574"})

;; ============================================================================
;; DB lookup helpers
;; ============================================================================

(defn- account-by-code [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :account/code ?c]] db code))

(defn- require-account [db code]
  (or (account-by-code db code)
      (throw (ex-info (str "Account " code " not found — install l10n-fr chart first")
                      {:type :l10n-fr/missing-account
                       :code code}))))

(defn- journal-by-code [db code]
  (:db/id (d/entity db [:journal/code code])))

(defn- commodity-by-symbol [db sym]
  (:db/id (d/entity db [:commodity/symbol sym])))

;; ============================================================================
;; Per-line routing
;; ============================================================================

(defn- revenue-code-for-line
  "Resolve the revenue account code for one line. Precedence:
     1. :invoice-line/account override (a code string)
     2. status-based default (:exempt / :intra-eu-b2b / :export)
     3. rate-based default for :taxable lines (:std/:inter/:red/...)."
  [{:invoice-line/keys [account tax-status rate]
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
       {:posting/account acct
        :posting/amount (.negate (bd net))
        :posting/commodity commodity-eid
        :posting/posted-at date}))))

(defn- nonzero? [^java.math.BigDecimal x]
  (not (zero? (.compareTo x 0M))))

(defn- tax-postings
  "Build the per-rate TVA-credit postings. Walks the compute-tax per-
   line breakdown and groups by rate, emitting one posting per non-
   zero TVA bucket."
  [db per-line codes commodity-eid date]
  (let [by-rate (group-by :rate per-line)
        tva-by-rate (:tva-by-rate codes)]
    (vec
     (for [[rate results] by-rate
           :let [;; Non-taxable rate keys (lines with :tax-status
                 ;; :exempt/:intra-eu-b2b/:export). These produce zero
                 ;; TVA per the compute and may not have a 4457x bucket
                 ;; (e.g. rate :zero / lines without a rate).
                 tva-code (get tva-by-rate rate)
                 tva-sum (reduce (fn [^java.math.BigDecimal acc r]
                                   (.add acc (:amount (:tva r))))
                                 0M results)]
           :when (and tva-code (nonzero? tva-sum))]
       {:posting/account (require-account db tva-code)
        :posting/amount (.negate tva-sum)
        :posting/commodity commodity-eid
        :posting/posted-at date}))))

(defn plan-fr-invoice-tx-data
  "Pure tx-data builder for a French sales invoice (ADR-068).

   Required input:
     {:invoice/external-id <string>
      :invoice/issue-date  <java.util.Date>
      :invoice/lines       [<invoice-line>]}

   Each invoice-line:
     {:invoice-line/quantity   <number-or-bigdec>      ; OR
      :invoice-line/unit-price <number-or-bigdec>      ; OR pre-computed:
      :invoice-line/line-total <bigdec>                ; net per line
      :invoice-line/rate       <keyword>               ; :std :inter :red :spec :zero
      :invoice-line/tax-status <keyword>               ; default :taxable
      :invoice-line/account    <code>                  ; optional override
      ...}

   Optional top-level fields:
     :invoice/cash-sale?     when true, post Dr cash (5121) instead of
                              AR (411).
     :invoice/cash-code      account-code override for the cash leg.
     :invoice/ar-code        account-code override for the AR leg.
     :invoice/buyer          partner ref (kernel :transaction/partner).
     :invoice/journal        journal code override (default VTE).

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
  (let [{:invoice/keys [external-id issue-date lines buyer cash-sale? journal
                        ar-code cash-code]} invoice
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
        codes' (merge {:revenue-by-rate default-revenue-by-rate
                       :revenue-by-status default-revenue-by-status
                       :tva-by-rate default-tva-by-rate}
                      codes)
        ;; Compute TVA via the rate-table fn, line by line.
        compute-input
        {:lines (mapv (fn [l]
                        {:line (line-net l)
                         :rate (or (:invoice-line/rate l) :std)
                         :tax-status (or (:invoice-line/tax-status l) :taxable)})
                      lines)}
        tax-r (tax/compute-invoice-tax compute-input)
        per-line (:per-line tax-r)
        gross (:total-gross tax-r)
        debit-code (cond
                     cash-sale? (or cash-code (:cash-code codes') default-cash-code)
                     ar-code    ar-code
                     :else      (or (:ar-code codes') default-ar-code))
        debit-acct (require-account db debit-code)
        rev-posts (revenue-postings db lines codes' commodity-eid issue-date)
        tax-posts (tax-postings db per-line codes' commodity-eid issue-date)
        debit-post {:posting/account debit-acct
                    :posting/amount (:amount gross)
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
  (let [{:invoice/keys [external-id issue-date lines]} invoice]
    (cond-> []
      (or (nil? external-id) (and (string? external-id) (str/blank? external-id)))
      (conj {:field :invoice/external-id :issue :missing-or-blank})
      (nil? issue-date)
      (conj {:field :invoice/issue-date :issue :missing})
      (empty? lines)
      (conj {:field :invoice/lines :issue :empty}))))
