(ns kontor.l10n-ca.invoice
  "Canadian invoice posting builder — translates an issued invoice
   into kernel transaction + posting tx-data.

   Sits between `kontor.l10n-ca.tax/compute-tax` (rate logic) and
   `kontor.posting/build-transaction` (structural validation +
   sum-to-zero). Accepts a CA-shaped invoice and emits the
   appropriate per-authority tax postings:

     Dr  AR (or cash)           gross
     Cr  Sales revenue          net
     Cr  GST/HST collected      gst + hst
     Cr  PST collected (BC/SK)  pst       — separate authority
     Cr  RST collected (MB)     pst       — semantically PST
     Cr  QST collected (QC)     qst       — Revenu Québec authority

   ## Multi-authority Quebec sales (ADR-021 note)

   ADR-021 introduces parallel ledgers for IFRS-vs-local-GAAP-style
   reporting splits. **Quebec's QST is NOT a parallel ledger** —
   QST postings live in the primary ledger alongside the federal
   GST. What's parallel about QC is the *tax authority* (Revenu
   Québec administers QST separately from CRA's GST), and that
   parallelism shows up as a separate liability account
   (`2330 QST collected`) and a separate filing process — NOT as a
   `:posting/ledger` ref. The compute-tax function returns a single
   per-authority breakdown; this builder posts each authority's
   liability to its own account in one balanced transaction.

   ## Buyer-side ITCs

   Kontor posts the **seller side** of an invoice (the seller's
   journal entry). The buyer's input tax credit (ITC) posting is a
   separate transaction the *buyer* would issue in their own kontor
   instance when they record the matching vendor bill. This builder
   never emits buyer-side ITC postings.

   ## Tax-status routing

   For lines marked `:zero-rated` (groceries, exports, prescription
   drugs), `:exempt` (residential rent, healthcare), or
   `:non-resident` (exports to a buyer outside Canada), the line
   contributes to revenue only — no tax-account posting.

   By default the builder routes zero-rated lines to the dedicated
   `4010 Sales — zero-rated` account (helps GST/HST line-101 +
   line-90/91 split for the filing report) and exempt lines to
   `4020 Sales — exempt`. Callers can override per-line via
   `:invoice-line/account`.

   ## ADR-068 — tx-data builder + side-effecting wrapper

   - **`plan-ca-invoice-tx-data`** (pure) — takes a DB value + invoice
     map, returns the tx-data vector ready for the gate.
   - **`post-ca-invoice!`** (side-effecting) — wraps the builder and
     routes through `kontor.validation/transact-with-validation`.

   Consumers composing several writes into one process (invoice +
   audit doc + e-invoice generation) call the pure builder from a
   `kontor.process` step."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [kontor.l10n-ca.tax :as tax]
            [kontor.posting :as posting]
            [kontor.validation :as validation]))

;; ============================================================================
;; Default account codes (overridable per call)
;; ============================================================================

(def ^:const default-ar-code "1100")
(def ^:const default-sales-code "4000")
(def ^:const default-sales-zero-rated-code "4010")
(def ^:const default-sales-exempt-code "4020")
(def ^:const default-gst-hst-collected-code "2310")
(def ^:const default-bc-pst-collected-code "2320")
(def ^:const default-sk-pst-collected-code "2321")
(def ^:const default-mb-rst-collected-code "2322")
(def ^:const default-qst-collected-code "2330")
(def ^:const default-journal-code "INV")
(def ^:const default-commodity "CAD")

;; ============================================================================
;; DB lookup helpers
;; ============================================================================

(defn- account-by-code [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :account/code ?c]] db code))

(defn- require-account [db code]
  (or (account-by-code db code)
      (throw (ex-info (str "Account " code " not found — install l10n-ca chart first")
                      {:type :l10n-ca/missing-account
                       :code code}))))

(defn- journal-by-code [db code]
  (:db/id (d/entity db [:journal/code code])))

(defn- commodity-by-symbol [db sym]
  (:db/id (d/entity db [:commodity/symbol sym])))

;; ============================================================================
;; PST account routing — per-province
;; ============================================================================

(defn- pst-account-code-for-province
  "Resolve which PST-style liability account a given province posts to.
   Returns nil when the province has no PST/RST (i.e. it's an HST,
   GST-only, or QC jurisdiction)."
  [province codes]
  (case province
    :BC (:bc-pst-code codes default-bc-pst-collected-code)
    :SK (:sk-pst-code codes default-sk-pst-collected-code)
    :MB (:mb-rst-code codes default-mb-rst-collected-code)
    nil))

;; ============================================================================
;; Per-line revenue routing
;; ============================================================================

(defn- revenue-code-for-status
  "Choose the revenue account based on the line's tax-status.
   Callers can pin a different account via :invoice-line/account."
  [status codes]
  (case status
    :zero-rated   (:sales-zero-rated-code codes default-sales-zero-rated-code)
    :exempt       (:sales-exempt-code codes default-sales-exempt-code)
    :non-resident (:sales-zero-rated-code codes default-sales-zero-rated-code)
    (:sales-code codes default-sales-code)))

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
  "Per-line revenue credit postings. Groups by (revenue-account,
   tax-status) so two lines of the same status produce one summed
   posting, keeping the tx compact."
  [db lines codes commodity-eid date]
  (let [grouped (group-by
                 (fn [line]
                   (let [status (or (:invoice-line/tax-status line) :taxable)
                         override (:invoice-line/account line)]
                     [(or override (revenue-code-for-status status codes))
                      status]))
                 lines)]
    (vec
     (for [[[acct-code _status] ls] grouped
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

(defn- tax-posting
  "Build a single per-authority credit posting. Returns nil if the
   amount is zero (so the caller can filter)."
  [db code ^java.math.BigDecimal amount commodity-eid date]
  (when (and code (nonzero? amount))
    {:posting/account (require-account db code)
     :posting/amount (.negate amount)
     :posting/commodity commodity-eid
     :posting/posted-at date}))

(defn- tax-postings
  "Build the per-authority tax-credit postings from the compute-tax
   per-line breakdown. Emits at most 3 entries (GST/HST combined,
   PST/RST, QST) — each only when its per-authority amount is
   non-zero."
  [db per-line province codes commodity-eid date]
  (let [sum-of (fn [k]
                 (reduce (fn [^java.math.BigDecimal acc r]
                           (.add acc (:amount (k r))))
                         0M per-line))
        gst+hst (.add (sum-of :gst) (sum-of :hst))
        pst (sum-of :pst)
        qst (sum-of :qst)
        gst-hst-code (get codes :gst-hst-code default-gst-hst-collected-code)
        qst-code (get codes :qst-code default-qst-collected-code)
        pst-code (pst-account-code-for-province province codes)]
    (->> [(tax-posting db gst-hst-code gst+hst commodity-eid date)
          (tax-posting db pst-code     pst     commodity-eid date)
          (tax-posting db qst-code     qst     commodity-eid date)]
         (remove nil?)
         vec)))

(defn plan-ca-invoice-tx-data
  "Pure tx-data builder for a Canadian sales invoice (ADR-068).

   Required input:
     {:invoice/external-id      <string>
      :invoice/issue-date       <java.util.Date>
      :invoice/ship-to-province <keyword>   ; :ON :BC :QC :AB :NS ...
      :invoice/lines            [<invoice-line>]
      ...}

   Each invoice-line:
     {:invoice-line/quantity    <number-or-bigdec>      ; OR
      :invoice-line/unit-price  <number-or-bigdec>      ; OR pre-computed:
      :invoice-line/line-total  <bigdec>                ; net per line
      :invoice-line/tax-status  <keyword>               ; default :taxable
      :invoice-line/account     <code-or-eid>           ; optional override
      ...}

   Optional top-level fields:
     :invoice/cash-sale?    when true, post Dr cash (1010 by default)
                             instead of AR.
     :invoice/cash-code     account-code override for the cash leg.
     :invoice/buyer         partner ref (kernel :transaction/partner).
     :invoice/journal       journal code override (default INV).

   Opts:
     :codes        — map of code overrides (`:ar-code`, `:cash-code`,
                     `:sales-code`, `:sales-zero-rated-code`,
                     `:sales-exempt-code`, `:gst-hst-code`,
                     `:bc-pst-code`, `:sk-pst-code`, `:mb-rst-code`,
                     `:qst-code`).
     :commodity    — commodity symbol (default \"CAD\").
     :journal-code — journal code (default \"INV\").

   Returns a tx-data vector ready for
   `kontor.validation/transact-with-validation`.

   The builder enforces sum-to-zero per the kernel rules (via
   `kontor.posting/build-transaction`)."
  [db invoice {:keys [codes commodity journal-code]
               :or {codes {} commodity default-commodity
                    journal-code default-journal-code}}]
  (let [{:invoice/keys [external-id issue-date ship-to-province
                        lines buyer cash-sale? journal]} invoice
        _ (when-not external-id
            (throw (ex-info "Invoice missing :invoice/external-id" {:invoice invoice})))
        _ (when-not issue-date
            (throw (ex-info "Invoice missing :invoice/issue-date" {:invoice invoice})))
        _ (when-not ship-to-province
            (throw (ex-info "Invoice missing :invoice/ship-to-province" {:invoice invoice})))
        _ (when (empty? lines)
            (throw (ex-info "Invoice has no :invoice/lines" {:invoice invoice})))
        commodity-eid (or (commodity-by-symbol db commodity)
                          (throw (ex-info (str "Commodity " commodity " not found")
                                          {:commodity commodity})))
        jnl-code (or journal journal-code)
        jnl (or (journal-by-code db jnl-code)
                (throw (ex-info (str "Journal " jnl-code " not found — create it before posting")
                                {:code jnl-code})))
        ;; Compute tax via the rate-table fn, line by line.
        compute-input
        {:ship-to-province ship-to-province
         :lines (mapv (fn [l]
                        {:line (line-net l)
                         :tax-status (or (:invoice-line/tax-status l) :taxable)})
                      lines)}
        tax-r (tax/compute-invoice-tax compute-input)
        per-line (:per-line tax-r)
        gross (:total-gross tax-r)
        debit-code (if cash-sale?
                     (get codes :cash-code "1010")
                     (get codes :ar-code default-ar-code))
        debit-acct (require-account db debit-code)
        rev-posts (revenue-postings db lines codes commodity-eid issue-date)
        tax-posts (tax-postings db per-line ship-to-province codes
                                commodity-eid issue-date)
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

(defn post-ca-invoice!
  "Side-effecting wrapper around `plan-ca-invoice-tx-data` (ADR-068).
   Routes the tx-data through `kontor.validation/transact-with-
   validation` so the kernel gate (sealing / period / sum-to-zero /
   invariants) applies.

   See `plan-ca-invoice-tx-data` for the input shape + options."
  ([conn invoice] (post-ca-invoice! conn invoice {}))
  ([conn invoice opts]
   (let [tx-data (plan-ca-invoice-tx-data (d/db conn) invoice opts)]
     (validation/transact-with-validation conn tx-data))))

;; ============================================================================
;; Convenience predicates
;; ============================================================================

(defn province?
  "True iff `s` is a recognised Canadian provincial / territorial
   code (`:ON`, `:BC`, …). Useful at the consumer boundary before
   handing an invoice off to the builder."
  [s]
  (contains? tax/all-provinces s))

(defn supports-hst?
  "True iff `province` is in the HST zone (ON / NS / NB / NL / PE)."
  [province]
  (contains? tax/hst-provinces province))

(defn supports-pst?
  "True iff `province` levies a non-recoverable provincial sales tax
   (BC / SK / MB). Quebec's QST is VAT-style and is NOT a PST."
  [province]
  (contains? tax/pst-provinces province))

(defn supports-qst?
  "True iff `province` is Quebec (the only QST jurisdiction)."
  [province]
  (contains? tax/qst-provinces province))

;; ============================================================================
;; Validation helpers (caller-side)
;; ============================================================================

(defn validate-invoice
  "Return a vector of complaints; empty when ready to post.
   Used by consumers to surface input issues *before* hitting the
   gate."
  [invoice]
  (let [{:invoice/keys [external-id issue-date ship-to-province lines]} invoice]
    (cond-> []
      (or (nil? external-id) (and (string? external-id) (str/blank? external-id)))
      (conj {:field :invoice/external-id :issue :missing-or-blank})
      (nil? issue-date)
      (conj {:field :invoice/issue-date :issue :missing})
      (or (nil? ship-to-province) (not (contains? tax/all-provinces ship-to-province)))
      (conj {:field :invoice/ship-to-province :issue :invalid-or-missing})
      (empty? lines)
      (conj {:field :invoice/lines :issue :empty}))))
