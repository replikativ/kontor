(ns kontor.l10n-us.invoice
  "US invoice posting builder — translates an issued invoice into
   kernel transaction + posting tx-data.

   Sits between `kontor.l10n-us.tax/compute-tax` (rate logic) and
   `kontor.posting/build-transaction` (structural validation +
   sum-to-zero). Accepts a US-shaped invoice and emits the
   appropriate per-state sales-tax liability postings:

     Dr  AR (or cash)                gross
     Cr  Sales revenue               net
     Cr  Sales Tax Payable — <state> tax    (one per ship-to state by default)

   ## Origin-vs-destination sourcing (not the builder's job)

   Roughly half the US sales-tax states are origin-based for in-state
   sales (TX, PA, OH, …) — the rate is determined by where the
   seller is located — and the rest are destination-based — rate by
   ship-to. The kernel substrate does NOT resolve this; the caller
   passes a single :invoice/ship-to-state and a pre-resolved :rate
   (or rate-table). The compute function returns the right number;
   the invoice builder just routes that number to the right
   liability account.

   For multi-state nexus, the seller's invoicing system (Avalara /
   TaxJar / in-house) is expected to:
     1. Determine ship-from / ship-to.
     2. Look up the sourcing rule for the state.
     3. Compute the combined rate (state + county + city + district).
     4. Hand the resolved rate back here.

   ## Tracking liability per state

   Default `:track-by-state? true`: each state's collected tax lands
   in its own liability sub-account (CA → 2210, TX → 2211, NY → 2212,
   WA → 2213, FL → 2214, … per `default-state-tax-code-map`). The
   filing-side `kontor.l10n-us.sales-tax/compute-state` then reads
   each state's liability account independently to produce the
   per-authority filing report.

   With `:track-by-state? false`, all states' collected tax lands in
   a single bucket account passed via `:single-tax-code`. Useful for
   tiny SMBs filing in only one state; loses the per-state
   filing-report split.

   ## Tax-exempt + resale routing

   Lines marked `:resale`, `:exempt`, or `:non-taxable-product`
   contribute to revenue only — no tax-account posting. The compute
   layer is the authority for the zero-tax decision; the builder
   just sees `:tax-amount 0` and skips the liability leg.

   ## Origin-state vs nexus-state revenue split

   `:invoice/out-of-state?` true routes the revenue leg to
   `default-out-of-state-revenue-code` (4200) for SMBs that want to
   surface the no-nexus / no-tax revenue separately. The state tax
   liability is still zero in that case (no nexus → no collection
   obligation), so the post still balances.

   ## ADR-068 — tx-data builder + side-effecting wrapper

   - **`plan-us-invoice-tx-data`** (pure) — takes a DB value + invoice
     map, returns the tx-data vector ready for the gate.
   - **`post-us-invoice!`** (side-effecting) — wraps the builder and
     routes through `kontor.validation/transact-with-validation`.

   Consumers composing several writes into one process (invoice +
   audit doc + e-invoice generation) call the pure builder from a
   `kontor.process` step."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [kontor.l10n-us.tax :as tax]
            [kontor.posting :as posting]
            [kontor.validation :as validation]))

;; ============================================================================
;; Default account codes (overridable per call)
;; ============================================================================

(def ^:const default-ar-code               "1200")    ; AR
(def ^:const default-cash-code             "1100")    ; Bank — Checking
(def ^:const default-sales-code            "4000")    ; Product sales
(def ^:const default-services-code         "4100")    ; Service revenue
(def ^:const default-out-of-state-revenue-code "4200") ; Out-of-state (no nexus)
(def ^:const default-journal-code          "INV")
(def ^:const default-commodity             "USD")

(def default-state-tax-code-map
  "Per-state liability-account codes — matches the seed chart.
   Customers extend with additional states by passing a custom
   :state-tax-code-map opt to plan-us-invoice-tx-data."
  {:CA "2210"
   :TX "2211"
   :NY "2212"
   :WA "2213"
   :FL "2214"})

;; ============================================================================
;; DB lookup helpers
;; ============================================================================

(defn- account-by-code [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- require-account [db code]
  (or (account-by-code db code)
      (throw (ex-info (str "Account " code " not found — install l10n-us chart first")
                      {:type :l10n-us/missing-account
                       :code code}))))

(defn- journal-by-code [db code]
  (:db/id (d/entity db [:kontor.journal/code code])))

(defn- commodity-by-symbol [db sym]
  (:db/id (d/entity db [:kontor.commodity/symbol sym])))

;; ============================================================================
;; State-tax-code routing
;; ============================================================================

(defn- resolve-state-tax-code
  "Pick the sales-tax-payable account code for one line, based on the
   builder's tracking mode + the per-state code map. Returns the
   chart code (string) or nil when the line has no tax obligation
   (status :resale/:exempt/:non-taxable-product, or out-of-state
   revenue)."
  [{:keys [track-by-state? state-tax-code-map single-tax-code]
    :or {track-by-state? true
         state-tax-code-map default-state-tax-code-map}}
   ship-to-state]
  (if track-by-state?
    (or (get state-tax-code-map ship-to-state)
        (throw (ex-info
                (str "No sales-tax-payable account configured for state "
                     ship-to-state " — add an account to the chart and an "
                     "entry to :state-tax-code-map")
                {:type :l10n-us/missing-state-tax-account
                 :state ship-to-state
                 :known-states (vec (keys state-tax-code-map))})))
    (or single-tax-code
        (throw (ex-info "track-by-state? false requires :single-tax-code"
                        {:type :l10n-us/missing-single-tax-code})))))

;; ============================================================================
;; Per-line revenue routing
;; ============================================================================

(defn- revenue-code-for-line
  "Choose the revenue account for one line.
     - :invoice-line/account on the line overrides
     - :invoice/out-of-state? at the top-level routes everything to
       the out-of-state revenue account (4200)
     - default: :sales-code (4000)"
  [{:keys [sales-code out-of-state-revenue-code out-of-state?]
    :or {sales-code default-sales-code
         out-of-state-revenue-code default-out-of-state-revenue-code}}
   line]
  (or (:invoice-line/account line)
      (if out-of-state? out-of-state-revenue-code sales-code)))

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

(defn- nonzero? [^java.math.BigDecimal x]
  (not (zero? (.compareTo x 0M))))

(defn- revenue-postings
  "Per-line revenue credit postings. Groups by revenue account so two
   lines on the same account produce one summed posting, keeping the
   tx compact."
  [db lines codes commodity-eid date]
  (let [grouped (group-by
                 (fn [line]
                   (revenue-code-for-line codes line))
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
  "Build sales-tax-payable credit postings. When track-by-state? is
   true, each state's tax is its own posting (so the per-state
   filing report can aggregate). When false, all tax sums into one
   posting on :single-tax-code.

   `compute-summaries` is the per-line tax-compute output keyed by
   ship-to-state (we group lines by state internally; today a US
   invoice maps to one ship-to-state per the conventional model,
   but the structure leaves room for future multi-ship-to splits)."
  [db opts ship-to-state per-line commodity-eid date]
  (let [total-tax (reduce (fn [^java.math.BigDecimal acc {:keys [tax-amount]}]
                            (.add acc (:amount tax-amount)))
                          0M per-line)]
    (if (nonzero? total-tax)
      (let [code (resolve-state-tax-code opts ship-to-state)
            acct (require-account db code)]
        [{:kontor.posting/account acct
          :kontor.posting/amount (.negate total-tax)
          :kontor.posting/commodity commodity-eid
          :kontor.posting/posted-at date}])
      [])))

(defn plan-us-invoice-tx-data
  "Pure tx-data builder for a US sales invoice (ADR-068).

   Required input:
     {:invoice/external-id      <string>
      :invoice/issue-date       <java.util.Date>
      :invoice/ship-to-state    <keyword>   ; :CA :NY :TX :OR ...
      :invoice/lines            [<invoice-line>]
      ...}

   Each invoice-line:
     {:invoice-line/quantity    <number-or-bigdec>      ; OR
      :invoice-line/unit-price  <number-or-bigdec>      ; OR pre-computed:
      :invoice-line/line-total  <bigdec>                ; net per line
      :invoice-line/tax-status  <keyword>               ; default :taxable
      :invoice-line/rate        <bigdec>                ; per-line rate override
      :invoice-line/product-class <keyword>             ; :default :clothing ...
      :invoice-line/account     <code>                  ; revenue-account override
      ...}

   Optional top-level fields:
     :invoice/cash-sale?           debit cash (1100) instead of AR (1200)
     :invoice/cash-code            account-code override for the cash leg
     :invoice/buyer                partner ref (kernel :kontor.transaction/partner)
     :invoice/journal              journal code override (default INV)
     :invoice/out-of-state?        route revenue to 4200 (no-nexus state)
     :invoice/rate                 invoice-level rate (applied to taxable lines
                                   unless the line has its own rate)
     :invoice/rate-table           invoice-level rate-table for compute-tax

   Opts:
     :track-by-state?      default true; false → all tax to :single-tax-code
     :single-tax-code      required when :track-by-state? false
     :state-tax-code-map   override the per-state code map
     :ar-code              default \"1200\"
     :cash-code            default \"1100\"
     :sales-code           default \"4000\"
     :out-of-state-revenue-code  default \"4200\"
     :commodity            default \"USD\"
     :journal-code         default \"INV\"

   Returns a tx-data vector ready for
   `kontor.validation/transact-with-validation`.

   The builder enforces sum-to-zero per the kernel rules (via
   `kontor.posting/build-transaction`)."
  [db invoice opts]
  (let [{:invoice/keys [external-id issue-date ship-to-state
                        lines buyer cash-sale? journal out-of-state?
                        rate rate-table]} invoice
        _ (when-not external-id
            (throw (ex-info "Invoice missing :invoice/external-id" {:invoice invoice})))
        _ (when-not issue-date
            (throw (ex-info "Invoice missing :invoice/issue-date" {:invoice invoice})))
        _ (when-not ship-to-state
            (throw (ex-info "Invoice missing :invoice/ship-to-state" {:invoice invoice})))
        _ (when-not (keyword? ship-to-state)
            (throw (ex-info ":invoice/ship-to-state must be a keyword (e.g. :CA)"
                            {:invoice invoice})))
        _ (when (empty? lines)
            (throw (ex-info "Invoice has no :invoice/lines" {:invoice invoice})))
        commodity (or (:commodity opts) default-commodity)
        commodity-eid (or (commodity-by-symbol db commodity)
                          (throw (ex-info (str "Commodity " commodity " not found")
                                          {:commodity commodity})))
        jnl-code (or journal (:journal-code opts) default-journal-code)
        jnl (or (journal-by-code db jnl-code)
                (throw (ex-info (str "Journal " jnl-code " not found — create it before posting")
                                {:code jnl-code})))
        ;; Compute tax via the rate-table fn, line by line.
        compute-input
        (cond-> {:state ship-to-state
                 :lines (mapv (fn [l]
                                (cond-> {:line (line-net l)
                                         :tax-status (or (:invoice-line/tax-status l) :taxable)
                                         :product-class (or (:invoice-line/product-class l) :default)}
                                  (:invoice-line/rate l)
                                  (assoc :rate (:invoice-line/rate l))
                                  (and rate (not (:invoice-line/rate l))
                                       (= (or (:invoice-line/tax-status l) :taxable) :taxable))
                                  (assoc :rate rate)))
                              lines)}
          rate-table (assoc :rate-table rate-table))
        tax-r (tax/compute-invoice-tax compute-input)
        per-line (:per-line tax-r)
        gross (:total-gross tax-r)
        ;; Resolve debit (AR or cash)
        debit-code (if cash-sale?
                     (or (:invoice/cash-code invoice)
                         (:cash-code opts)
                         default-cash-code)
                     (or (:ar-code opts) default-ar-code))
        debit-acct (require-account db debit-code)
        rev-posts (revenue-postings db lines
                                    (merge opts
                                           {:out-of-state? out-of-state?})
                                    commodity-eid issue-date)
        tax-posts (tax-postings db opts ship-to-state per-line
                                commodity-eid issue-date)
        debit-post {:kontor.posting/account debit-acct
                    :kontor.posting/amount (:amount gross)
                    :kontor.posting/commodity commodity-eid
                    :kontor.posting/posted-at issue-date}
        tx-base (cond-> {:kontor.transaction/external-id external-id
                         :kontor.transaction/journal jnl
                         :kontor.transaction/effective-date issue-date
                         :kontor.transaction/narration (or (:invoice/narration invoice)
                                                    external-id)
                         :kontor.transaction/state :posted
                         :kontor.transaction/posted-at issue-date}
                  buyer (assoc :kontor.transaction/partner buyer))
        input {:transaction tx-base
               :postings (into [debit-post] (into rev-posts tax-posts))}]
    (posting/build-transaction input)))

(defn post-us-invoice!
  "Side-effecting wrapper around `plan-us-invoice-tx-data` (ADR-068).
   Routes the tx-data through `kontor.validation/transact-with-
   validation` so the kernel gate (sealing / period / sum-to-zero /
   invariants) applies.

   See `plan-us-invoice-tx-data` for the input shape + options."
  ([conn invoice] (post-us-invoice! conn invoice {}))
  ([conn invoice opts]
   (let [tx-data (plan-us-invoice-tx-data (d/db conn) invoice opts)]
     (validation/transact-with-validation conn tx-data))))

;; ============================================================================
;; Convenience predicates
;; ============================================================================

(defn no-state-sales-tax-state?
  "True iff `state-kw` is one of the NOMAD-extended five (AK / DE /
   MT / NH / OR). AK has local-only taxes; treat AK as 'state rate
   zero' here — local-tax resolution still goes through Avalara/
   TaxJar at the consumer."
  [state-kw]
  (contains? tax/states-without-state-sales-tax state-kw))

(defn sst-state?
  "True iff `state-kw` is a Streamlined Sales Tax member state."
  [state-kw]
  (contains? tax/sst-states state-kw))

;; ============================================================================
;; Validation helpers (caller-side)
;; ============================================================================

(defn validate-invoice
  "Return a vector of complaints; empty when ready to post. Used by
   consumers to surface input issues *before* hitting the gate."
  [invoice]
  (let [{:invoice/keys [external-id issue-date ship-to-state lines]} invoice]
    (cond-> []
      (or (nil? external-id) (and (string? external-id) (str/blank? external-id)))
      (conj {:field :invoice/external-id :issue :missing-or-blank})
      (nil? issue-date)
      (conj {:field :invoice/issue-date :issue :missing})
      (or (nil? ship-to-state) (not (keyword? ship-to-state)))
      (conj {:field :invoice/ship-to-state :issue :invalid-or-missing})
      (empty? lines)
      (conj {:field :invoice/lines :issue :empty}))))
