(ns kontor.l10n-in.invoice
  "Indian invoice posting builder — translates an issued tax invoice
   into kernel transaction + posting tx-data.

   Sits between `kontor.l10n-in.taxes/compute-tax` (component split +
   rounding) and `kontor.posting/build-transaction` (structural
   validation + sum-to-zero). Accepts an India-shaped invoice and
   emits the appropriate per-component tax postings:

     Dr  AR (or Bank/Cash)         gross
     Cr  Sales revenue             net
     Cr  Output CGST (intra)       cgst
     Cr  Output SGST (intra)       sgst
     Cr  Output IGST (inter)       igst
     Cr  Output UTGST (UT supply)  utgst
     Cr  Output Cess (luxury/sin)  cess  (optional, item-specific)

   ## Place-of-supply dispatch (ADR-023)

   GST splits the headline rate based on the *place of supply*:

     supplier state = POS state, POS not a UT-without-legislature
        → intra-state    → CGST + SGST   (half each)
     supplier state ≠ POS state
        → inter-state    → IGST          (full headline)
     supplier state = POS state, POS IS a UT-without-legislature
        → UT supply      → CGST + UTGST  (half each)

   The caller passes `:kontor.invoice/supplier-state` + `:kontor.invoice/place-of-
   supply` + `:kontor.invoice/place-of-supply-is-ut?`; the builder asks
   `taxes/dispatch-supply` for the routing and `taxes/component-
   split` for the per-component rates, then `taxes/compute-tax` for
   the Money figures (so this builder doesn't duplicate the rate
   arithmetic).

   ## Reverse Charge (RCM)

   When `:kontor.invoice/reverse-charge?` is true the supplier-side journal
   entry posts the net to revenue but the GST liability NEVER lands
   on the supplier's books — the *buyer* pays GST direct to govt.
   So the revenue is credited, the receivable equals the net (NOT
   net + tax), and no output-tax postings are emitted. The seller's
   GSTR-1 still has to disclose the supply, but the bookkeeping is
   net-only on the seller side.

   The buyer's matching self-invoiced RCM posting (Dr RCM ITC asset
   + Cr RCM payable liability) is a *separate* tx in the buyer's
   own kontor instance — not this builder's concern.

   ## Compensation Cess

   Specific items (aerated drinks, luxury cars, tobacco products)
   carry a per-item Compensation Cess rate on top of the headline
   slab. The invoice-line carries `:kontor.invoice-line/cess-rate` (a
   BigDecimal, e.g. 0.12M for 12%); when present, the per-line
   cess amount lands on Output Cess (`331500`).

   ## Tax statuses (zero-rated / exempt / non-resident)

   For lines marked:
     :zero-rated   — exports, supplies to SEZ, deemed exports
     :exempt       — nil-rated / GST-exempt categories
     :non-resident — buyer is outside India, goods exported

   no tax is computed and the line lands on the relevant special-
   revenue account (Exports `410200` or Exempt `410300`) by default.
   Callers can override per-line via `:kontor.invoice-line/account`.

   ## ADR-068 — tx-data builder + side-effecting wrapper

   - **`plan-in-invoice-tx-data`** (pure) — takes a DB value + invoice
     map, returns the tx-data vector ready for the gate.
   - **`post-in-invoice!`** (side-effecting) — wraps the builder and
     routes through `kontor.validation/transact-with-validation`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [kontor.account :as kacct]
            [kontor.l10n-in.chart :as chart]
            [kontor.l10n-in.taxes :as taxes]
            [kontor.money :as money]
            [kontor.posting :as posting]
            [kontor.validation :as validation]))

;; ============================================================================
;; Defaults (overridable per call)
;; ============================================================================

(def ^:const default-journal-code "SJ")
(def ^:const default-commodity "INR")

(defn- default-codes []
  {:ar-code               chart/ar-code
   :ar-export-code        chart/ar-export-code
   :cash-code             chart/cash-code
   :bank-code             chart/bank-code
   :sales-code            chart/sales-domestic-code
   :sales-export-code     chart/sales-export-code
   :sales-exempt-code     chart/sales-exempt-code
   :output-cgst-code      chart/output-cgst-code
   :output-sgst-code      chart/output-sgst-code
   :output-igst-code      chart/output-igst-code
   :output-utgst-code     chart/output-utgst-code
   :output-cess-code      chart/output-cess-code})

;; ============================================================================
;; DB lookup helpers
;; ============================================================================

(defn- account-by-code [db code]
  (kacct/resolve-code db code {:context "IN invoice bridge"}))

(defn- require-account [db code]
  (or (account-by-code db code)
      (throw (ex-info (str "Account " code " not found — install l10n-in chart first")
                      {:type :l10n-in/missing-account
                       :code code}))))

(defn- journal-by-code [db code]
  (:db/id (d/entity db [:kontor.journal/code code])))

(defn- commodity-by-symbol [db sym]
  (:db/id (d/entity db [:kontor.commodity/symbol sym])))

;; ============================================================================
;; Per-line revenue routing
;; ============================================================================

(defn- revenue-code-for-status
  "Choose the revenue account based on the line's tax-status. Callers
   can pin a different account via `:kontor.invoice-line/account`.

   Mapping:
     :taxable      → 410000 Sales — domestic taxable
     :zero-rated   → 410200 Sales — Exports (zero-rated)
     :exempt       → 410300 Sales — Exempt / Nil-rated
     :non-resident → 410200 Sales — Exports (export rule)"
  [status codes]
  (case status
    :zero-rated   (:sales-export-code codes)
    :exempt       (:sales-exempt-code codes)
    :non-resident (:sales-export-code codes)
    (:sales-code codes)))

;; ============================================================================
;; Math helpers
;; ============================================================================

(defn- line-net
  "Per-line net amount = qty × unit-price, rounded HALF-EVEN to 2dp.
   Tolerates `:kontor.invoice-line/line-total` when caller pre-computed it."
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

(defn- nonzero? [^java.math.BigDecimal x]
  (not (zero? (.compareTo x 0M))))

(defn- bd-add ^java.math.BigDecimal [^java.math.BigDecimal a ^java.math.BigDecimal b]
  (.add a b))

;; ============================================================================
;; Tx-data builder (ADR-068 pure form)
;; ============================================================================

(defn- inr-money
  "Construct a Money record in INR that `taxes/compute-tax` accepts."
  [^java.math.BigDecimal amount]
  (money/money amount :INR))

(defn- taxable-line-tax
  "Run `taxes/compute-tax` for a single line and return a map shape
   the posting-assembly step can use:
     {:net BigDecimal
      :cgst BigDecimal :sgst BigDecimal :igst BigDecimal
      :utgst BigDecimal :cess BigDecimal
      :total BigDecimal  ; gross
     }"
  [line dispatch]
  (let [net (line-net line)
        headline (:kontor.invoice-line/tax-rate line)
        cess-rate (:kontor.invoice-line/cess-rate line)
        _ (when (nil? headline)
            (throw (ex-info "Taxable invoice line needs :kontor.invoice-line/tax-rate"
                            {:line line})))
        r (taxes/compute-tax (inr-money net) headline dispatch cess-rate)
        amt (fn [m] (:amount m))
        cgst  (some-> (-> r :components :cgst) amt)
        sgst  (some-> (-> r :components :sgst) amt)
        igst  (some-> (-> r :components :igst) amt)
        utgst (some-> (-> r :components :utgst) amt)
        cess  (some-> (:cess r) amt)]
    {:net net
     :cgst  (or cgst 0M)
     :sgst  (or sgst 0M)
     :igst  (or igst 0M)
     :utgst (or utgst 0M)
     :cess  (or cess 0M)}))

(defn- per-line-breakdown
  "For each line, return either a taxable-line-tax map (`:taxable`
   status) or a zero-tax sentinel for the special statuses
   (`:zero-rated` / `:exempt` / `:non-resident`)."
  [lines dispatch reverse-charge?]
  (vec
   (for [l lines]
     (let [status (or (:kontor.invoice-line/tax-status l) :taxable)]
       (cond
         (contains? #{:zero-rated :exempt :non-resident} status)
         {:status status
          :line   l
          :net    (line-net l)
          :cgst 0M :sgst 0M :igst 0M :utgst 0M :cess 0M}

         reverse-charge?
         ;; Supplier-side under RCM: revenue only, no tax on seller's books.
         {:status :reverse-charge
          :line   l
          :net    (line-net l)
          :cgst 0M :sgst 0M :igst 0M :utgst 0M :cess 0M}

         :else
         (assoc (taxable-line-tax l dispatch)
                :status status
                :line l))))))

(defn- revenue-postings
  "Per-line revenue credit postings. Groups by (revenue-account,
   tax-status) so multiple lines of the same routing collapse into
   one summed posting."
  [db breakdown codes commodity-eid date]
  (let [grouped (group-by
                 (fn [{:keys [status line]}]
                   [(or (:kontor.invoice-line/account line)
                        (revenue-code-for-status status codes))
                    status])
                 breakdown)]
    (vec
     (for [[[acct-code _] rows] grouped
           :let [net (reduce (fn [^java.math.BigDecimal acc r]
                               (bd-add acc (:net r)))
                             0M rows)
                 acct (require-account db acct-code)]]
       {:kontor.posting/account acct
        :kontor.posting/amount (.negate ^java.math.BigDecimal net)
        :kontor.posting/commodity commodity-eid
        :kontor.posting/posted-at date}))))

(defn- tax-posting
  "Build a single credit posting for one tax component. Returns nil
   when the amount is zero (so the caller can filter)."
  [db code ^java.math.BigDecimal amount commodity-eid date]
  (when (nonzero? amount)
    {:kontor.posting/account (require-account db code)
     :kontor.posting/amount (.negate amount)
     :kontor.posting/commodity commodity-eid
     :kontor.posting/posted-at date}))

(defn- tax-postings
  "Build the per-component output-tax postings by summing the
   breakdown across lines. Emits at most five entries — only when
   the per-component total is non-zero. RCM short-circuits to no
   postings (handled upstream by the breakdown sentinel)."
  [db breakdown codes commodity-eid date]
  (let [sum-of (fn [k]
                 (reduce (fn [^java.math.BigDecimal acc r]
                           (bd-add acc (k r)))
                         0M breakdown))
        cgst  (sum-of :cgst)
        sgst  (sum-of :sgst)
        igst  (sum-of :igst)
        utgst (sum-of :utgst)
        cess  (sum-of :cess)]
    (->> [(tax-posting db (:output-cgst-code codes)  cgst  commodity-eid date)
          (tax-posting db (:output-sgst-code codes)  sgst  commodity-eid date)
          (tax-posting db (:output-igst-code codes)  igst  commodity-eid date)
          (tax-posting db (:output-utgst-code codes) utgst commodity-eid date)
          (tax-posting db (:output-cess-code codes)  cess  commodity-eid date)]
         (remove nil?)
         vec)))

(defn- gross-amount
  "Sum the line-level (net + cgst + sgst + igst + utgst + cess) into
   the total receivable / cash debit amount."
  ^java.math.BigDecimal [breakdown]
  (reduce (fn [^java.math.BigDecimal acc {:keys [net cgst sgst igst utgst cess]}]
            (-> acc (bd-add net) (bd-add cgst) (bd-add sgst)
                (bd-add igst) (bd-add utgst) (bd-add cess)))
          0M breakdown))

(defn- debit-account-code
  "Resolve the debit-leg account code: cash/bank/export-AR/AR based
   on invoice flags."
  [{:kontor.invoice/keys [cash-sale? bank-sale? export?]} codes]
  (cond
    cash-sale?  (:cash-code codes)
    bank-sale?  (:bank-code codes)
    export?     (:ar-export-code codes)
    :else       (:ar-code codes)))

(defn plan-in-invoice-tx-data
  "Pure tx-data builder for an Indian sales invoice (ADR-068).

   Required input:
     {:kontor.invoice/external-id            <string>
      :kontor.invoice/issue-date             <java.util.Date>
      :kontor.invoice/supplier-state         <string>   ; e.g. \"MH\"
      :kontor.invoice/place-of-supply        <string>   ; e.g. \"KA\"
      :kontor.invoice/lines                  [<invoice-line>]
      ...}

   Each invoice-line:
     {:kontor.invoice-line/quantity    <number-or-bigdec>     ; OR
      :kontor.invoice-line/unit-price  <number-or-bigdec>     ; OR pre-computed:
      :kontor.invoice-line/line-total  <bigdec>               ; net per line
      :kontor.invoice-line/tax-rate    <bigdec>               ; e.g. 0.18M
      :kontor.invoice-line/cess-rate   <bigdec>               ; optional, e.g. 0.12M
      :kontor.invoice-line/tax-status  <keyword>              ; default :taxable
      :kontor.invoice-line/account     <code-or-eid>          ; optional override
      ...}

   Optional top-level fields:
     :kontor.invoice/place-of-supply-is-ut?  boolean — true when POS is a UT
                                       *without legislature* (CH, LD,
                                       AN, LA, DD). Delhi & Puducherry
                                       have legislatures → false.
     :kontor.invoice/reverse-charge?         when true, no output-tax
                                       postings on the seller side.
     :kontor.invoice/cash-sale?              debit cash (122100) instead of AR
     :kontor.invoice/bank-sale?              debit bank (122200) instead of AR
     :kontor.invoice/export?                 use export AR (121200)
     :kontor.invoice/buyer                   partner ref (kernel
                                       :kontor.transaction/partner)
     :kontor.invoice/journal                 journal code override (default INV)

   Opts:
     :codes        — map of code overrides (`:ar-code`, `:cash-code`,
                     `:sales-code`, `:sales-export-code`,
                     `:sales-exempt-code`, `:output-cgst-code`,
                     `:output-sgst-code`, `:output-igst-code`,
                     `:output-utgst-code`, `:output-cess-code`).
     :commodity    — commodity symbol (default \"INR\").
     :journal-code — journal code (default \"SJ\").

   Returns a tx-data vector ready for
   `kontor.validation/transact-with-validation`.

   The builder enforces sum-to-zero per the kernel rules (via
   `kontor.posting/build-transaction`)."
  [db invoice {:keys [codes commodity journal-code]
               :or {codes {} commodity default-commodity
                    journal-code default-journal-code}}]
  (let [{:kontor.invoice/keys [external-id issue-date supplier-state place-of-supply
                        place-of-supply-is-ut? reverse-charge?
                        lines buyer journal]} invoice
        _ (when-not external-id
            (throw (ex-info "Invoice missing :kontor.invoice/external-id" {:invoice invoice})))
        _ (when-not issue-date
            (throw (ex-info "Invoice missing :kontor.invoice/issue-date" {:invoice invoice})))
        _ (when-not supplier-state
            (throw (ex-info "Invoice missing :kontor.invoice/supplier-state" {:invoice invoice})))
        _ (when-not place-of-supply
            (throw (ex-info "Invoice missing :kontor.invoice/place-of-supply" {:invoice invoice})))
        _ (when (empty? lines)
            (throw (ex-info "Invoice has no :kontor.invoice/lines" {:invoice invoice})))
        merged-codes (merge (default-codes) codes)
        commodity-eid (or (commodity-by-symbol db commodity)
                          (throw (ex-info (str "Commodity " commodity " not found")
                                          {:commodity commodity})))
        jnl-code (or journal journal-code)
        jnl (or (journal-by-code db jnl-code)
                (throw (ex-info (str "Journal " jnl-code " not found — create it before posting")
                                {:code jnl-code})))
        dispatch (taxes/dispatch-supply supplier-state place-of-supply
                                        (boolean place-of-supply-is-ut?))
        breakdown (per-line-breakdown lines dispatch (boolean reverse-charge?))
        rev-posts (revenue-postings db breakdown merged-codes commodity-eid issue-date)
        tax-posts (tax-postings db breakdown merged-codes commodity-eid issue-date)
        debit-code (debit-account-code invoice merged-codes)
        debit-acct (require-account db debit-code)
        gross (gross-amount breakdown)
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

(defn post-in-invoice!
  "Side-effecting wrapper around `plan-in-invoice-tx-data` (ADR-068).
   Routes the tx-data through `kontor.validation/transact-with-
   validation` so the kernel gate (sealing / period / sum-to-zero /
   invariants) applies.

   See `plan-in-invoice-tx-data` for the input shape + options."
  ([conn invoice] (post-in-invoice! conn invoice {}))
  ([conn invoice opts]
   (let [tx-data (plan-in-invoice-tx-data (d/db conn) invoice opts)]
     (validation/transact-with-validation conn tx-data))))

;; ============================================================================
;; Validation helpers (caller-side)
;; ============================================================================

(defn validate-invoice
  "Return a vector of complaints; empty when ready to post. Used by
   consumers to surface input issues *before* hitting the gate."
  [invoice]
  (let [{:kontor.invoice/keys [external-id issue-date supplier-state place-of-supply
                        lines]} invoice]
    (cond-> []
      (or (nil? external-id) (and (string? external-id) (str/blank? external-id)))
      (conj {:field :kontor.invoice/external-id :issue :missing-or-blank})

      (nil? issue-date)
      (conj {:field :kontor.invoice/issue-date :issue :missing})

      (or (nil? supplier-state) (and (string? supplier-state) (str/blank? supplier-state)))
      (conj {:field :kontor.invoice/supplier-state :issue :missing-or-blank})

      (or (nil? place-of-supply) (and (string? place-of-supply) (str/blank? place-of-supply)))
      (conj {:field :kontor.invoice/place-of-supply :issue :missing-or-blank})

      (empty? lines)
      (conj {:field :kontor.invoice/lines :issue :empty}))))
