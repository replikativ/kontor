(ns kontor.l10n-au.invoice
  "Australian invoice posting builder — translates an issued invoice
   into kernel transaction + posting tx-data.

   Sits between `kontor.l10n-au.tax/compute-tax` (rate logic) and
   `kontor.posting/build-transaction` (structural validation +
   sum-to-zero). Accepts an AU-shaped invoice and emits the GST
   posting plus revenue + receivable / cash legs:

     Dr  AR (or cash)         gross
     Cr  Sales revenue        net
     Cr  GST payable          gst         (10% taxable lines only)

   AU is single-level federal — there is no state / territory sales
   tax, so the credit side is at most two lines (revenue + GST).
   Multi-rate or multi-authority routing (the CA-shaped multi-line
   credit cluster) does not apply here.

   ## Tax-invoice vs adjustment-note

   The ATO distinguishes:
     - **Tax invoice** — the standard outbound invoice posting.
     - **Adjustment note** — a credit / debit adjustment that reverses
       (in part or full) a previously-issued tax invoice (price drop,
       returned goods, allowance). Mechanically an adjustment note is
       just a negated invoice with a different document title and a
       reference to the original invoice number.

   This builder accepts both via `:kontor.invoice/kind`:
     :tax-invoice (default) — posts the four standard legs above.
     :adjustment-note       — posts the same legs with signs negated.
                              The caller's net amounts may be negative
                              (refund / partial reversal); the builder
                              propagates the sign without overriding it.

   ## Tax-status routing

   - `:taxable` (default) — 10% GST; revenue → '41100' (taxable sales)
   - `:gst-free`           — 0% GST; revenue → '41200' (GST-free sales)
   - `:input-taxed`        — 0% GST; revenue → '41400' (input-taxed sales)

   The 41200 / 41400 routing maps to BAS labels G3 / G4 through the
   chart-installed tags. Callers can override per-line via
   `:kontor.invoice-line/account`.

   ## Cross-border exports

   AU treats exports as GST-free (zero-rated; supplier may claim
   ITCs). A line with `:kontor.invoice-line/tax-status :gst-free` and a
   `:kontor.invoice-line/account` override of '41300' (Sales — exports)
   gives the BAS-G2 routing for export sales specifically. The
   builder does not auto-detect 'this is an export' — that's a
   consumer-side determination based on shipment / customer data
   the builder doesn't see.

   ## ADR-068 — tx-data builder + side-effecting wrapper

   - **`plan-au-invoice-tx-data`** (pure) — takes a DB value + invoice
     map, returns the tx-data vector ready for the gate.
   - **`post-au-invoice!`** (side-effecting) — wraps the builder and
     routes through `kontor.validation/transact-with-validation`.

   Consumers composing several writes into one process (invoice +
   audit doc + Peppol PINT e-invoice generation) call the pure
   builder from a `kontor.workflow.process` step."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [kontor.l10n-au.tax-provider :as tax-provider]
            [kontor.posting :as posting]
            [kontor.tax.tax-posting-builder :as tpb]
            [kontor.validation :as validation]))

;; ============================================================================
;; Default account codes (overridable per call)
;; ============================================================================

(def ^:const default-ar-code "11200")
(def ^:const default-cash-code "11100")
(def ^:const default-sales-taxable-code "41100")
(def ^:const default-sales-gst-free-code "41200")
(def ^:const default-sales-input-taxed-code "41400")
(def ^:const default-gst-payable-code "21500")
(def ^:const default-journal-code "SJ")
(def ^:const default-commodity "AUD")

;; ============================================================================
;; DB lookup helpers
;; ============================================================================

(defn- account-by-code [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- require-account [db code]
  (or (account-by-code db code)
      (throw (ex-info (str "Account " code " not found — install l10n-au chart first")
                      {:type :l10n-au/missing-account
                       :code code}))))

(defn- journal-by-code [db code]
  (:db/id (d/entity db [:kontor.journal/code code])))

(defn- commodity-by-symbol [db sym]
  (:db/id (d/entity db [:kontor.commodity/symbol sym])))

;; ============================================================================
;; Per-line revenue routing
;; ============================================================================

(defn- revenue-code-for-status
  "Choose the revenue account based on the line's tax-status.
   Callers can pin a different account via :kontor.invoice-line/account."
  [status codes]
  (case status
    :gst-free    (:sales-gst-free-code codes default-sales-gst-free-code)
    :input-taxed (:sales-input-taxed-code codes default-sales-input-taxed-code)
    (:sales-taxable-code codes default-sales-taxable-code)))

;; ============================================================================
;; Tx-data builder (ADR-068 pure form)
;; ============================================================================

(defn- line-net
  "Per-line net amount = qty × unit-price, rounded HALF-EVEN to 2dp.
   Tolerates :kontor.invoice-line/line-total when caller pre-computed it.
   Negative net is preserved (e.g. adjustment-note line items)."
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

(defn- sign-multiplier
  "Adjustment notes negate every leg. Tax-invoice (default) leaves
   signs as-is."
  ^java.math.BigDecimal [kind]
  (if (= :adjustment-note kind) -1M 1M))

(defn- nonzero? [^java.math.BigDecimal x]
  (not (zero? (.compareTo x 0M))))

(defn- revenue-postings
  "Per-line revenue credit postings. Groups by (revenue-account,
   tax-status) so two lines of the same status produce one summed
   posting, keeping the tx compact. Sign convention: revenue is
   credited on a tax-invoice (negative posting amount) and debited
   on an adjustment-note (positive posting amount via sign-mult)."
  [db lines codes commodity-eid date kind]
  (let [sm (sign-multiplier kind)
        grouped (group-by
                 (fn [line]
                   (let [status (or (:kontor.invoice-line/tax-status line) :taxable)
                         override (:kontor.invoice-line/account line)]
                     [(or override (revenue-code-for-status status codes))
                      status]))
                 lines)]
    (vec
     (for [[[acct-code _status] ls] grouped
           :let [net (reduce (fn [^java.math.BigDecimal acc l]
                               (.add acc (line-net l)))
                             0M ls)
                 acct (require-account db acct-code)
                 ;; Sign convention: revenue accrues as a CREDIT
                 ;; (negative). An adjustment note flips the sign.
                 amt (.multiply (.negate ^java.math.BigDecimal net) sm)]
           :when (nonzero? amt)]
       {:kontor.posting/account acct
        :kontor.posting/amount amt
        :kontor.posting/commodity commodity-eid
        :kontor.posting/posted-at date}))))

(defn- au-gst-postings
  "Per-invoice GST postings, via the ADR-071 tax provider + builder
   (`kontor.l10n-au.tax-provider`). Runs the provider per line and
   collapses the per-line GST legs to one posting per GST account with
   `kontor.tax.tax-posting-builder/aggregate-postings`. An adjustment note
   negates every leg (the builder always emits the tax-invoice sign —
   a GST credit — so the sign flip happens here).

   AU is single-level federal: at most one GST posting per invoice."
  [db lines codes commodity-eid date kind]
  (let [provider (tax-provider/make-au-tax-rate-provider)
        builder  (tax-provider/make-au-tax-posting-builder
                  {:gst-payable-code (get codes :gst-payable-code)})
        sm       (sign-multiplier kind)
        raw      (tpb/aggregate-postings
                  (mapcat (fn [l]
                            (tpb/compute-tax-postings
                             provider builder
                             {:base       (line-net l)
                              :tax-status (or (:kontor.invoice-line/tax-status l)
                                              :taxable)
                              :commodity  commodity-eid}
                             {:db db :date date}))
                          lines))]
    (mapv (fn [p]
            (update p :kontor.posting/amount
                    (fn [^java.math.BigDecimal a] (.multiply a sm))))
          raw)))

(defn plan-au-invoice-tx-data
  "Pure tx-data builder for an Australian sales invoice (ADR-068).

   Required input:
     {:kontor.invoice/external-id    <string>
      :kontor.invoice/issue-date     <java.util.Date>
      :kontor.invoice/lines          [<invoice-line>]
      ...}

   Each invoice-line:
     {:kontor.invoice-line/quantity    <number-or-bigdec>      ; OR
      :kontor.invoice-line/unit-price  <number-or-bigdec>      ; OR pre-computed:
      :kontor.invoice-line/line-total  <bigdec>                ; net per line
      :kontor.invoice-line/tax-status  <keyword>               ; default :taxable
      :kontor.invoice-line/account     <code>                  ; optional code override
      ...}

   Optional top-level fields:
     :kontor.invoice/kind          :tax-invoice (default) | :adjustment-note
                            Adjustment-note flips the sign of every
                            posting to reverse a prior tax-invoice.
     :kontor.invoice/cash-sale?    when true, post Dr cash (11100 default)
                            instead of AR (11200 default).
     :kontor.invoice/cash-code     account-code override for the cash leg.
     :kontor.invoice/buyer         partner ref (kernel :kontor.transaction/partner).
     :kontor.invoice/journal       journal code override (default INV).
     :kontor.invoice/narration     :kontor.transaction/narration override.

   Opts:
     :codes        — map of code overrides (`:ar-code`, `:cash-code`,
                     `:sales-taxable-code`, `:sales-gst-free-code`,
                     `:sales-input-taxed-code`, `:gst-payable-code`).
     :commodity    — commodity symbol (default \"AUD\").
     :journal-code — journal code (default \"SJ\").

   Returns a tx-data vector ready for
   `kontor.validation/transact-with-validation`.

   The builder enforces sum-to-zero per the kernel rules (via
   `kontor.posting/build-transaction`)."
  [db invoice {:keys [codes commodity journal-code]
               :or {codes {} commodity default-commodity
                    journal-code default-journal-code}}]
  (let [{:kontor.invoice/keys [external-id issue-date lines buyer cash-sale?
                        journal kind]} invoice
        kind (or kind :tax-invoice)
        _ (when-not external-id
            (throw (ex-info "Invoice missing :kontor.invoice/external-id" {:invoice invoice})))
        _ (when-not issue-date
            (throw (ex-info "Invoice missing :kontor.invoice/issue-date" {:invoice invoice})))
        _ (when (empty? lines)
            (throw (ex-info "Invoice has no :kontor.invoice/lines" {:invoice invoice})))
        _ (when-not (contains? #{:tax-invoice :adjustment-note} kind)
            (throw (ex-info "Invalid :kontor.invoice/kind — must be :tax-invoice or :adjustment-note"
                            {:value kind})))
        commodity-eid (or (commodity-by-symbol db commodity)
                          (throw (ex-info (str "Commodity " commodity " not found")
                                          {:commodity commodity})))
        jnl-code (or journal journal-code)
        jnl (or (journal-by-code db jnl-code)
                (throw (ex-info (str "Journal " jnl-code " not found — create it before posting")
                                {:code jnl-code})))
        ;; Tax via the ADR-071 provider/builder.
        ;; Revenue routing stays here — it is base-posting work, not tax.
        sm (sign-multiplier kind)
        debit-code (if cash-sale?
                     (get codes :cash-code default-cash-code)
                     (get codes :ar-code default-ar-code))
        debit-acct (require-account db debit-code)
        rev-posts (revenue-postings db lines codes commodity-eid issue-date kind)
        gst-posts (au-gst-postings db lines codes commodity-eid issue-date kind)
        ;; Net per-line sum (always positive). `rev-posts` and
        ;; `gst-posts` already carry the tax-invoice/adjustment sign;
        ;; the debit (AR / cash) is their offset, so it is the negation
        ;; of (Σ rev-posts + Σ gst-posts) — equivalently
        ;; (sm × net) − Σ(gst-posts), since a tax-invoice GST leg is a
        ;; credit (negative).
        net-sum (reduce (fn [^java.math.BigDecimal a l]
                          (.add a (line-net l)))
                        0M lines)
        debit-amt (reduce (fn [^java.math.BigDecimal a p]
                            (.subtract a ^java.math.BigDecimal (:kontor.posting/amount p)))
                          (.multiply net-sum sm) gst-posts)
        debit-post (when (nonzero? debit-amt)
                     {:kontor.posting/account debit-acct
                      :kontor.posting/amount debit-amt
                      :kontor.posting/commodity commodity-eid
                      :kontor.posting/posted-at issue-date})
        tx-base (cond-> {:kontor.transaction/external-id external-id
                         :kontor.transaction/journal jnl
                         :kontor.transaction/effective-date issue-date
                         :kontor.transaction/narration (or (:kontor.invoice/narration invoice)
                                                    external-id)
                         :kontor.transaction/state :posted
                         :kontor.transaction/posted-at issue-date}
                  buyer (assoc :kontor.transaction/partner buyer))
        postings (cond-> []
                   debit-post (conj debit-post)
                   :always (into rev-posts)
                   :always (into gst-posts))
        input {:transaction tx-base
               :postings postings}]
    (posting/build-transaction input)))

(defn post-au-invoice!
  "Side-effecting wrapper around `plan-au-invoice-tx-data` (ADR-068).
   Routes the tx-data through `kontor.validation/transact-with-
   validation` so the kernel gate (sealing / period / sum-to-zero /
   invariants) applies.

   See `plan-au-invoice-tx-data` for the input shape + options."
  ([conn invoice] (post-au-invoice! conn invoice {}))
  ([conn invoice opts]
   (let [tx-data (plan-au-invoice-tx-data (d/db conn) invoice opts)]
     (validation/transact-with-validation conn tx-data))))

;; ============================================================================
;; Validation helpers (caller-side)
;; ============================================================================

(defn validate-invoice
  "Return a vector of complaints; empty when ready to post.
   Used by consumers to surface input issues *before* hitting the
   gate."
  [invoice]
  (let [{:kontor.invoice/keys [external-id issue-date lines kind]} invoice]
    (cond-> []
      (or (nil? external-id) (and (string? external-id) (str/blank? external-id)))
      (conj {:field :kontor.invoice/external-id :issue :missing-or-blank})
      (nil? issue-date)
      (conj {:field :kontor.invoice/issue-date :issue :missing})
      (empty? lines)
      (conj {:field :kontor.invoice/lines :issue :empty})
      (and kind (not (contains? #{:tax-invoice :adjustment-note} kind)))
      (conj {:field :kontor.invoice/kind :issue :invalid}))))
