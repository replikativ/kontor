(ns kontor.l10n-cn.invoice
  "Chinese invoice posting builder — translates an issued fapiao into
   kernel transaction + posting tx-data.

   Sits between `kontor.l10n-cn.tax/compute-tax` (rate logic) and
   `kontor.posting/build-transaction` (structural validation +
   sum-to-zero). Accepts a CN-shaped invoice and emits the seller-side
   postings:

     Dr  AR (or cash)              gross
     Cr  Sales revenue (per rate)  net  (routed to 5001.13 / .9 / .6 / .0)
     Cr  Output VAT                output-vat   (MOF-canonical 2221.01.01)

   ## MOF-canonical account routing (per chart.edn + ADR-019)

   The MOF's Cai Kuai [2016] No. 22 prescribes a SINGLE 销项税额
   account (2221.01.01) regardless of rate; per-rate aggregation is
   reconstructed at filing time from rate-tagged revenue accounts
   (5001.13 / 5001.9 / 5001.6 / 5001.0). This builder routes:

     - line at 13%       → revenue → 5001.13 (tag :cn-vat-line-sales-13)
     - line at 9%        → revenue → 5001.9  (tag :cn-vat-line-sales-9)
     - line at 6%        → revenue → 5001.6  (tag :cn-vat-line-sales-6)
     - line at 0%/export → revenue → 5001.0  (tag :cn-vat-line-sales-export)
     - all output-VAT    → 2221.01.01 (tag :cn-vat-output)

   For small-scale taxpayers, the rate is 1% / 3% / 5%; per-rate
   revenue accounts at those non-standard rates are NOT in the
   default chart (consumers add them per-deployment). When a small-
   scale rate is encountered and no specific revenue account is
   supplied, the builder routes to 5001.0 (the catch-all) — this
   makes the export-by-rate breakdown incomplete for small-scale
   but keeps the posting balanced. Callers can override via
   `:kontor.invoice-line/account` per-line.

   ## Fapiao type — seller-side equivalence

   The fapiao-type distinction (增值税专用发票 special vs.
   增值税普通发票 general vs. 电子发票 fully-digital) matters for
   the BUYER's accounts: a special fapiao lets a general-taxpayer
   buyer claim input VAT against their 进项税额 (2221.01.02); a
   general fapiao does not. **From the SELLER's side, all three
   fapiao types post identically** — output VAT to 2221.01.01.

   This builder accepts an optional `:kontor.invoice/fapiao-type` (∈
   #{:special :general :electronic-general :fully-digital}) and maps
   it to the kernel-level `:kontor.transaction/clearance-format` keyword
   (`:cn/fapiao-special-18` / `:cn/fapiao-general-20` /
   `:cn/fapiao-digital-20`) so downstream STA platform integrations
   can route the issuance request to the correct fapiao-issuing API.
   The recorded type does NOT influence the kernel postings.

   ## Cross-border services + 代扣代交增值税

   When the seller is a non-resident and the BUYER is the withholding
   agent (扣缴义务人), the postings flip — the buyer accrues the
   output-equivalent to 2221.10 代扣代交增值税 rather than 2221.01.01.
   This builder posts the **seller side** of a domestic invoice; the
   cross-border-withholding case is out of scope here and belongs in
   a separate `post-cn-cross-border-bill!` (in the consumer's
   procurement module).

   ## ADR-068 — tx-data builder + side-effecting wrapper

   - **`plan-cn-invoice-tx-data`** (pure) — takes a DB value + invoice
     map, returns the tx-data vector ready for the gate.
   - **`post-cn-invoice!`** (side-effecting) — wraps the builder and
     routes through `kontor.validation/transact-with-validation`.

   Consumers composing several writes into one process (invoice +
   fapiao request + audit doc) call the pure builder from a
   `kontor.workflow.process` step."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [kontor.l10n-cn.tax :as tax]
            [kontor.l10n-cn.tax-provider :as tax-provider]
            [kontor.posting :as posting]
            [kontor.tax.tax-posting-builder :as tpb]
            [kontor.validation :as validation]))

;; ============================================================================
;; Default account codes (overridable per call)
;; ============================================================================

(def ^:const default-ar-code "1122")           ; 应收账款
(def ^:const default-cash-code "1002")         ; 银行存款 (bank deposits)
(def ^:const default-cash-on-hand-code "1001") ; 库存现金
;; Output-VAT account routing moved to `kontor.l10n-cn.tax-provider`
;; (`default-output-vat-code` there) — ADR-071 migration.
;; The `:codes {:output-vat-code …}` opt still overrides it.
(def ^:const default-revenue-13-code "5001.13")
(def ^:const default-revenue-9-code "5001.9")
(def ^:const default-revenue-6-code "5001.6")
(def ^:const default-revenue-export-code "5001.0")
(def ^:const default-journal-code "INV")
(def ^:const default-commodity "CNY")

(def fapiao-types
  "Permitted `:kontor.invoice/fapiao-type` values — the four STA-recognised
   fapiao shapes. The kernel posting is identical for all four (see
   namespace docstring); the field exists so downstream platform
   integrations can route correctly."
  #{:special :general :electronic-general :fully-digital})

(def ^:private fapiao-type->clearance-format
  "Map `:kontor.invoice/fapiao-type` to the kernel-level
   `:kontor.transaction/clearance-format` keyword (per schema docstring at
   `:kontor.transaction/clearance-format` — ADR-020). The general/digital
   forms both use the 20-character clearance-token format and we
   distinguish them by clearance-format keyword."
  {:special            :cn/fapiao-special-18
   :general            :cn/fapiao-general-20
   :electronic-general :cn/fapiao-general-20
   :fully-digital      :cn/fapiao-digital-20})

;; ============================================================================
;; DB lookup helpers
;; ============================================================================

(defn- account-by-code [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- require-account [db code]
  (or (account-by-code db code)
      (throw (ex-info (str "Account " code " not found — install l10n-cn chart first")
                      {:type :l10n-cn/missing-account
                       :code code}))))

(defn- journal-by-code [db code]
  (:db/id (d/entity db [:kontor.journal/code code])))

(defn- commodity-by-symbol [db sym]
  (:db/id (d/entity db [:kontor.commodity/symbol sym])))

;; ============================================================================
;; Revenue account routing — per-rate / per-tax-status
;; ============================================================================

(defn- revenue-code-for-line
  "Choose the revenue account based on the line's rate AND tax-status.
   Zero-rated / exempt lines route to the export account (5001.0).
   Lines outside the default per-rate accounts (e.g. small-scale 1%)
   fall through to the export account as a catch-all; the caller
   should override via :kontor.invoice-line/account when those rates are in
   active use."
  [rate tax-status codes]
  (cond
    (contains? #{:zero-rated :exempt} tax-status)
    (:revenue-export-code codes default-revenue-export-code)

    (= rate 0.13M)
    (:revenue-13-code codes default-revenue-13-code)

    (= rate 0.09M)
    (:revenue-9-code codes default-revenue-9-code)

    (= rate 0.06M)
    (:revenue-6-code codes default-revenue-6-code)

    (= rate 0M)
    (:revenue-export-code codes default-revenue-export-code)

    :else
    ;; Rates outside the default per-rate accounts (e.g. small-scale
    ;; 1% / 3% / 5%). Fall back to the export catch-all so the
    ;; posting balances; consumer should set :kontor.invoice-line/account
    ;; explicitly for these.
    (:revenue-export-code codes default-revenue-export-code)))

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

(defn- line-rate
  "Resolve the effective rate for a line. Honours an explicit
   `:kontor.invoice-line/rate`; otherwise defaults to the taxpayer-status
   default. Zero-rated / exempt lines force rate to 0."
  ^java.math.BigDecimal [line taxpayer-status]
  (let [status (or (:kontor.invoice-line/tax-status line) :taxable)]
    (cond
      (contains? #{:zero-rated :exempt} status) 0M
      (:kontor.invoice-line/rate line) (bigdec (:kontor.invoice-line/rate line))
      :else (tax/default-rate taxpayer-status))))

(defn- revenue-postings
  "Per-line revenue credit postings, grouped by (revenue-account, rate,
   tax-status). Two lines at the same rate produce one summed posting,
   keeping the tx compact + matching the rate-tagged revenue convention
   the filing aggregator relies on."
  [db lines taxpayer-status codes commodity-eid date]
  (let [grouped (group-by
                 (fn [line]
                   (let [status (or (:kontor.invoice-line/tax-status line) :taxable)
                         rate (line-rate line taxpayer-status)
                         override (:kontor.invoice-line/account line)]
                     [(or override (revenue-code-for-line rate status codes))
                      rate status]))
                 lines)]
    (vec
     (for [[[acct-code _rate _status] ls] grouped
           :let [net (reduce (fn [^java.math.BigDecimal acc l]
                               (.add acc (line-net l)))
                             0M ls)
                 acct (require-account db acct-code)]]
       {:kontor.posting/account acct
        :kontor.posting/amount (.negate (bd net))
        :kontor.posting/commodity commodity-eid
        :kontor.posting/posted-at date}))))

(defn- cn-output-vat-postings
  "Per-invoice output-VAT postings, via the ADR-071 tax provider +
   builder (`kontor.l10n-cn.tax-provider`). Runs the provider per line
   and collapses the per-line output-VAT legs to one consolidated
   posting on the single MOF-canonical 2221.01.01 account with
   `kontor.tax.tax-posting-builder/aggregate-postings`."
  [db lines taxpayer-status codes commodity-eid date]
  (let [provider (tax-provider/make-cn-tax-rate-provider)
        builder  (tax-provider/make-cn-tax-posting-builder
                  (when-let [c (:output-vat-code codes)]
                    {:output-vat-code c}))]
    (tpb/aggregate-postings
     (mapcat (fn [l]
               (tpb/compute-tax-postings
                provider builder
                {:base            (line-net l)
                 :rate            (line-rate l taxpayer-status)
                 :taxpayer-status taxpayer-status
                 :tax-status      (or (:kontor.invoice-line/tax-status l) :taxable)
                 :commodity       commodity-eid}
                {:db db :date date}))
             lines))))

(defn plan-cn-invoice-tx-data
  "Pure tx-data builder for a Chinese sales invoice / fapiao (ADR-068).

   Required input:
     {:kontor.invoice/external-id      <string>
      :kontor.invoice/issue-date       <java.util.Date>
      :kontor.invoice/lines            [<invoice-line>]
      ...}

   Each invoice-line:
     {:kontor.invoice-line/quantity    <number-or-bigdec>      ; OR
      :kontor.invoice-line/unit-price  <number-or-bigdec>      ; OR pre-computed:
      :kontor.invoice-line/line-total  <bigdec>                ; net per line
      :kontor.invoice-line/rate        <bigdec>                ; 0.13 / 0.09 / 0.06 / 0
                                                         ;   defaults to
                                                         ;   tax/default-rate
                                                         ;   for the taxpayer
      :kontor.invoice-line/tax-status  <keyword>               ; default :taxable
                                                         ; :zero-rated / :exempt force rate=0
      :kontor.invoice-line/account     <code-or-eid>           ; optional override
      ...}

   Optional top-level fields:
     :kontor.invoice/taxpayer-status  :general | :small-scale (default :general)
     :kontor.invoice/fapiao-type      :special | :general |
                                :electronic-general | :fully-digital
                                — recorded on the transaction; the
                                  postings are identical regardless.
     :kontor.invoice/cash-sale?       when true, post Dr cash (1002 by default)
                                instead of AR (1122).
     :kontor.invoice/cash-code        account-code override for the cash leg.
     :kontor.invoice/buyer            partner ref (kernel :kontor.transaction/partner).
     :kontor.invoice/journal          journal code override (default INV).

   Opts:
     :codes        — map of code overrides (`:ar-code`, `:cash-code`,
                     `:output-vat-code`, `:revenue-13-code`,
                     `:revenue-9-code`, `:revenue-6-code`,
                     `:revenue-export-code`).
     :commodity    — commodity symbol (default \"CNY\").
     :journal-code — journal code (default \"INV\").

   Returns a tx-data vector ready for
   `kontor.validation/transact-with-validation`.

   The builder enforces sum-to-zero per the kernel rules (via
   `kontor.posting/build-transaction`)."
  [db invoice {:keys [codes commodity journal-code]
               :or {codes {} commodity default-commodity
                    journal-code default-journal-code}}]
  (let [{:kontor.invoice/keys [external-id issue-date lines buyer cash-sale?
                        journal taxpayer-status fapiao-type]
         :or {taxpayer-status :general}} invoice
        _ (when-not external-id
            (throw (ex-info "Invoice missing :kontor.invoice/external-id" {:invoice invoice})))
        _ (when-not issue-date
            (throw (ex-info "Invoice missing :kontor.invoice/issue-date" {:invoice invoice})))
        _ (when (empty? lines)
            (throw (ex-info "Invoice has no :kontor.invoice/lines" {:invoice invoice})))
        _ (when (and fapiao-type (not (contains? fapiao-types fapiao-type)))
            (throw (ex-info "Invalid :kontor.invoice/fapiao-type"
                            {:value fapiao-type :valid fapiao-types})))
        commodity-eid (or (commodity-by-symbol db commodity)
                          (throw (ex-info (str "Commodity " commodity " not found")
                                          {:commodity commodity})))
        jnl-code (or journal journal-code)
        jnl (or (journal-by-code db jnl-code)
                (throw (ex-info (str "Journal " jnl-code " not found — create it before posting")
                                {:code jnl-code})))
        ;; Tax via the ADR-071 provider/builder.
        ;; Revenue routing stays here — it is base-posting work, not tax.
        rev-posts (revenue-postings db lines taxpayer-status
                                    codes commodity-eid issue-date)
        vat-posts (cn-output-vat-postings db lines taxpayer-status
                                          codes commodity-eid issue-date)
        net-sum (reduce (fn [^java.math.BigDecimal a l]
                          (.add a (line-net l)))
                        0M lines)
        ;; Output-VAT postings are credits (negative); gross = net − Σ(vat).
        gross (reduce (fn [^java.math.BigDecimal a p]
                        (.subtract a ^java.math.BigDecimal (:kontor.posting/amount p)))
                      net-sum vat-posts)
        debit-code (if cash-sale?
                     (get codes :cash-code default-cash-code)
                     (get codes :ar-code default-ar-code))
        debit-acct (require-account db debit-code)
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
                  buyer       (assoc :kontor.transaction/partner buyer)
                  fapiao-type (assoc :kontor.transaction/clearance-format
                                     (fapiao-type->clearance-format fapiao-type)))
        input {:transaction tx-base
               :postings (into [debit-post] (into rev-posts vat-posts))}]
    (posting/build-transaction input)))

(defn post-cn-invoice!
  "Side-effecting wrapper around `plan-cn-invoice-tx-data` (ADR-068).
   Routes the tx-data through `kontor.validation/transact-with-
   validation` so the kernel gate (sealing / period / sum-to-zero /
   invariants) applies.

   See `plan-cn-invoice-tx-data` for the input shape + options."
  ([conn invoice] (post-cn-invoice! conn invoice {}))
  ([conn invoice opts]
   (let [tx-data (plan-cn-invoice-tx-data (d/db conn) invoice opts)]
     (validation/transact-with-validation conn tx-data))))

;; ============================================================================
;; Convenience predicates
;; ============================================================================

(defn taxpayer-status?
  "True iff `s` is a recognised CN VAT taxpayer status."
  [s]
  (contains? tax/taxpayer-statuses s))

(defn fapiao-type?
  "True iff `s` is a recognised fapiao type."
  [s]
  (contains? fapiao-types s))

;; ============================================================================
;; Validation helpers (caller-side)
;; ============================================================================

(defn validate-invoice
  "Return a vector of complaints; empty when ready to post.
   Used by consumers to surface input issues *before* hitting the
   gate."
  [invoice]
  (let [{:kontor.invoice/keys [external-id issue-date lines taxpayer-status
                        fapiao-type]} invoice]
    (cond-> []
      (or (nil? external-id) (and (string? external-id) (str/blank? external-id)))
      (conj {:field :kontor.invoice/external-id :issue :missing-or-blank})

      (nil? issue-date)
      (conj {:field :kontor.invoice/issue-date :issue :missing})

      (empty? lines)
      (conj {:field :kontor.invoice/lines :issue :empty})

      (and taxpayer-status (not (contains? tax/taxpayer-statuses taxpayer-status)))
      (conj {:field :kontor.invoice/taxpayer-status :issue :invalid})

      (and fapiao-type (not (contains? fapiao-types fapiao-type)))
      (conj {:field :kontor.invoice/fapiao-type :issue :invalid}))))
