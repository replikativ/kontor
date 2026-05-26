(ns kontor.l10n-mx.invoice
  "Mexican invoice posting builder — translates a CFDI-aligned
   issued invoice into kernel transaction + posting tx-data.

   Sits between `kontor.l10n-mx.tax/compute-tax` (rate logic) and
   `kontor.posting/build-transaction` (structural validation +
   sum-to-zero).

   ## Cash-basis IVA — the critical MX routing

   **The output IVA on a freshly-issued (unpaid) invoice does NOT
   yet land on the cobrado liability account 208.01.** Per Ley del
   IVA Art. 1-B, IVA is recognised when payment is *received*, not
   when the CFDI is issued. The issuance posting therefore lands the
   IVA in the holding account 208.02.xxx (\"no cobrado\"):

     Dr  Clientes (AR)              gross
     Cr  Ingresos                   net
     Cr  IVA trasladado NO cobrado  iva-amount     ← 208.02.xxx
     Cr  IEPS trasladado NO cobrado ieps-amount    ← 209.02.xxx (when nonzero)

   When the AR settles (i.e. the buyer pays), a **separate payment-
   recognition flow** transfers the IVA balance:

     Dr  208.02 (no cobrado)        iva-amount
     Cr  208.01 (cobrado)           iva-amount

   That payment-side flow is OUT OF SCOPE for this commit. See the
   roadmap for the AR-settlement / pago-complemento story.

   For a true **cash-sale** (invoice + cash receipt in one step,
   `:invoice/cash-sale?` true), IVA is recognised immediately and
   lands directly on 208.01 (cobrado). The :cash-sale? flag drives
   this routing.

   ## Retenciones (buyer withheld at the source)

   When the buyer withholds IVA / ISR (gobierno or large taxpayer
   per Art. 1-A LIVA / Art. 106 LISR), the AR posting is reduced by
   the retención amount and the withheld portion lands on the
   supplier's retenciones-por-cobrar asset accounts (120.01 ISR /
   120.02 IVA), to be recovered against the supplier's own ISR /
   IVA liabilities later.

     Dr  Clientes (AR)                          cash-receipt
     Dr  Retenciones por cobrar — IVA           retencion-iva
     Dr  Retenciones por cobrar — ISR           retencion-isr
     Cr  Ingresos                               net
     Cr  IVA trasladado NO cobrado              iva-amount
     ...

   The kernel sum-to-zero still holds: AR + retenciones = gross
   = net + IVA + IEPS.

   ## Tax-status routing

   Lines marked `:zero-rated`, `:exempt`, or `:non-resident` land on
   the dedicated revenue accounts (401.01.003 zero-rated /
   401.01.004 exempt / 401.02.001 exportación) and emit no IVA
   posting. Caller can override per-line with `:invoice-line/account`.

   ## ADR-068 — pure builder + side-effecting wrapper

   - **`plan-mx-invoice-tx-data`** (pure) — takes a DB value + invoice
     map, returns the tx-data vector ready for the gate.
   - **`post-mx-invoice!`** (side-effecting) — wraps the builder and
     routes through `kontor.validation/transact-with-validation`.

   Consumers composing several writes into one process (invoice +
   audit doc + CFDI XML envelope) call the pure builder from a
   `kontor.process` step."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [kontor.l10n-mx.chart :as chart]
            [kontor.l10n-mx.tax :as tax]
            [kontor.posting :as posting]
            [kontor.validation :as validation]))

;; ============================================================================
;; Defaults (overridable per call)
;; ============================================================================

(def ^:const default-journal-code "INV")
(def ^:const default-commodity "MXN")

(defn- default-codes []
  {:ar-code               chart/ar-code
   :ar-export-code        chart/ar-export-code
   :cash-code             chart/cash-code
   :bank-code             chart/bank-code
   ;; Revenue per rate (caller chooses via :invoice-line/account override
   ;; or via tax-status routing; default goes to 16% domestic).
   :sales-16-code         chart/sales-domestic-16-code
   :sales-8-code          chart/sales-domestic-8-code
   :sales-0-code          chart/sales-domestic-0-code
   :sales-exempt-code     chart/sales-exempt-code
   :sales-export-code     chart/sales-export-code
   ;; Output IVA — cash-basis split.
   ;; Issuance side lands on no-cobrado (208.02.xxx); cash-sale? routes
   ;; to cobrado (208.01.xxx) since IVA is recognised immediately.
   :iva-no-cobrado-16-code   chart/iva-trasladado-no-cobrado-16-code
   :iva-no-cobrado-8-code    chart/iva-trasladado-no-cobrado-8-code
   :iva-no-cobrado-0-code    chart/iva-trasladado-no-cobrado-0-code
   :iva-cobrado-16-code      chart/iva-trasladado-cobrado-16-code
   :iva-cobrado-8-code       chart/iva-trasladado-cobrado-8-code
   :iva-cobrado-0-code       chart/iva-trasladado-cobrado-0-code
   ;; IEPS, retenciones
   :ieps-no-cobrado-code     chart/ieps-trasladado-no-cobrado-code
   :ieps-cobrado-code        chart/ieps-trasladado-cobrado-code
   :retencion-iva-cobrar-code chart/iva-retenido-cobrar-code
   :retencion-isr-cobrar-code chart/isr-retenido-cobrar-code})

;; ============================================================================
;; DB lookup helpers
;; ============================================================================

(defn- account-by-code [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- require-account [db code]
  (or (account-by-code db code)
      (throw (ex-info (str "Account " code
                           " not found — install l10n-mx chart first")
                      {:type :l10n-mx/missing-account
                       :code code}))))

(defn- journal-by-code [db code]
  (:db/id (d/entity db [:journal/code code])))

(defn- commodity-by-symbol [db sym]
  (:db/id (d/entity db [:kontor.commodity/symbol sym])))

;; ============================================================================
;; Per-line revenue routing
;; ============================================================================

(defn- revenue-code-for-line
  "Choose the revenue account based on tax-status + effective IVA rate.

   Mapping:
     :zero-rated    → 401.01.003 ventas 0%
     :exempt        → 401.01.004 ventas exentas
     :non-resident  → 401.02.001 exportación
     :taxable + 16% → 401.01.001 ventas 16%
     :taxable + 8%  → 401.01.002 ventas frontera 8%
     :taxable + 0%  → 401.01.003 ventas 0% (rare; :zero-rated normally)

   Callers can pin a different account via `:invoice-line/account`."
  [status effective-iva-rate codes]
  (cond
    (= status :zero-rated)   (:sales-0-code codes)
    (= status :exempt)       (:sales-exempt-code codes)
    (= status :non-resident) (:sales-export-code codes)
    (zero? (.compareTo ^java.math.BigDecimal effective-iva-rate 0.08M))
    (:sales-8-code codes)
    (zero? (.compareTo ^java.math.BigDecimal effective-iva-rate 0M))
    (:sales-0-code codes)
    :else (:sales-16-code codes)))

(defn- iva-code-for-rate
  "Pick the IVA liability account based on rate + cash-basis routing.
   `cash-sale?` = true → cobrado (208.01); false → no cobrado (208.02)."
  [rate cash-sale? codes]
  (let [bd (bigdec rate)]
    (cond
      (zero? (.compareTo ^java.math.BigDecimal bd 0.16M))
      (if cash-sale? (:iva-cobrado-16-code codes) (:iva-no-cobrado-16-code codes))
      (zero? (.compareTo ^java.math.BigDecimal bd 0.08M))
      (if cash-sale? (:iva-cobrado-8-code codes) (:iva-no-cobrado-8-code codes))
      (zero? (.compareTo ^java.math.BigDecimal bd 0M))
      (if cash-sale? (:iva-cobrado-0-code codes) (:iva-no-cobrado-0-code codes))
      :else
      ;; Unrecognised rate — fall back to the 16% bucket; caller should
      ;; override via :codes if they have a custom rate.
      (if cash-sale? (:iva-cobrado-16-code codes) (:iva-no-cobrado-16-code codes)))))

;; ============================================================================
;; Math helpers
;; ============================================================================

(defn- line-net
  "Per-line net amount = qty × unit-price, rounded HALF-EVEN to 2dp.
   Tolerates `:invoice-line/line-total` when caller pre-computed it."
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

(defn- bd-add ^java.math.BigDecimal [^java.math.BigDecimal a ^java.math.BigDecimal b]
  (.add a b))

;; ============================================================================
;; Per-line breakdown
;; ============================================================================

(defn- line-breakdown
  "Run the tax compute for one line, returning the BigDecimal
   components the posting-assembly step needs:
     {:net :iva :ieps :retencion-iva :retencion-isr
      :iva-rate :status :line}"
  [{:invoice-line/keys [tax-status iva-rate ieps-rate
                        retencion-iva-rate retencion-isr-rate]
    :as line}
   region]
  (let [net (line-net line)
        compute-input
        (cond-> {:line net :region region}
          (some? tax-status)         (assoc :tax-status tax-status)
          (some? iva-rate)           (assoc :iva-rate iva-rate)
          (some? ieps-rate)          (assoc :ieps-rate ieps-rate)
          (some? retencion-iva-rate) (assoc :retencion-iva-rate retencion-iva-rate)
          (some? retencion-isr-rate) (assoc :retencion-isr-rate retencion-isr-rate))
        r (tax/compute-tax compute-input)
        amt (fn [k] (-> r k :amount))]
    {:net (.setScale ^java.math.BigDecimal net 2 java.math.RoundingMode/HALF_EVEN)
     :iva (amt :iva-amount)
     :iva-rate (:iva-rate r)
     :ieps (amt :ieps-amount)
     :retencion-iva (amt :retencion-iva)
     :retencion-isr (amt :retencion-isr)
     :status (or tax-status :taxable)
     :line line}))

;; ============================================================================
;; Posting builders
;; ============================================================================

(defn- revenue-postings
  "Group lines by (revenue-account, status) and emit one credit
   posting per group with the summed net."
  [db breakdown codes commodity-eid date]
  (let [grouped (group-by
                 (fn [{:keys [status iva-rate line]}]
                   [(or (:invoice-line/account line)
                        (revenue-code-for-line status iva-rate codes))
                    status])
                 breakdown)]
    (vec
     (for [[[acct-code _] rows] grouped
           :let [net (reduce (fn [^java.math.BigDecimal acc r]
                               (bd-add acc (:net r)))
                             0M rows)
                 acct (require-account db acct-code)]]
       {:posting/account acct
        :posting/amount (.negate ^java.math.BigDecimal net)
        :posting/commodity commodity-eid
        :posting/posted-at date}))))

(defn- iva-postings
  "Group taxable lines by (iva-rate) and emit one credit posting per
   rate bucket. Cash-basis split: routes to cobrado (208.01) for cash
   sales, no-cobrado (208.02) for credit sales."
  [db breakdown codes commodity-eid date cash-sale?]
  (let [grouped (group-by :iva-rate
                          (filter (fn [b] (nonzero? (:iva b))) breakdown))]
    (vec
     (for [[rate rows] grouped
           :let [iva-total (reduce (fn [^java.math.BigDecimal acc r]
                                     (bd-add acc (:iva r)))
                                   0M rows)
                 code (iva-code-for-rate rate cash-sale? codes)]
           :when (nonzero? iva-total)]
       {:posting/account (require-account db code)
        :posting/amount (.negate ^java.math.BigDecimal iva-total)
        :posting/commodity commodity-eid
        :posting/posted-at date}))))

(defn- ieps-posting
  "Single IEPS credit posting summed across lines, routed cobrado vs
   no-cobrado per cash-sale?. Returns nil when total is zero."
  [db breakdown codes commodity-eid date cash-sale?]
  (let [total (reduce (fn [^java.math.BigDecimal acc r]
                        (bd-add acc (:ieps r)))
                      0M breakdown)]
    (when (nonzero? total)
      (let [code (if cash-sale?
                   (:ieps-cobrado-code codes)
                   (:ieps-no-cobrado-code codes))]
        {:posting/account (require-account db code)
         :posting/amount (.negate ^java.math.BigDecimal total)
         :posting/commodity commodity-eid
         :posting/posted-at date}))))

(defn- retencion-postings
  "Per-retención-type DEBIT postings for amounts withheld by the
   buyer. These lower the AR/cash receipt and become a receivable
   the supplier recovers against own IVA / ISR liability.

   Returns 0..2 posting maps (one for IVA, one for ISR)."
  [db breakdown codes commodity-eid date]
  (let [iva-r (reduce (fn [^java.math.BigDecimal acc r]
                        (bd-add acc (:retencion-iva r))) 0M breakdown)
        isr-r (reduce (fn [^java.math.BigDecimal acc r]
                        (bd-add acc (:retencion-isr r))) 0M breakdown)]
    (cond-> []
      (nonzero? iva-r)
      (conj {:posting/account
             (require-account db (:retencion-iva-cobrar-code codes))
             :posting/amount iva-r
             :posting/commodity commodity-eid
             :posting/posted-at date})
      (nonzero? isr-r)
      (conj {:posting/account
             (require-account db (:retencion-isr-cobrar-code codes))
             :posting/amount isr-r
             :posting/commodity commodity-eid
             :posting/posted-at date}))))

(defn- debit-account-code
  "Resolve the debit-leg account code: cash / bank / export-AR / AR
   based on invoice flags."
  [{:invoice/keys [cash-sale? bank-sale? export?]} codes]
  (cond
    cash-sale?  (:cash-code codes)
    bank-sale?  (:bank-code codes)
    export?     (:ar-export-code codes)
    :else       (:ar-code codes)))

(defn- gross-amount
  "Total receivable / cash debit: sum of (net + iva + ieps) across
   all lines BEFORE retención withholding."
  ^java.math.BigDecimal [breakdown]
  (reduce (fn [^java.math.BigDecimal acc {:keys [net iva ieps]}]
            (-> acc (bd-add net) (bd-add iva) (bd-add ieps)))
          0M breakdown))

(defn- total-retencion
  ^java.math.BigDecimal [breakdown]
  (reduce (fn [^java.math.BigDecimal acc {:keys [retencion-iva retencion-isr]}]
            (-> acc (bd-add retencion-iva) (bd-add retencion-isr)))
          0M breakdown))

;; ============================================================================
;; Public: plan + post
;; ============================================================================

(defn plan-mx-invoice-tx-data
  "Pure tx-data builder for a Mexican sales invoice (ADR-068).

   Required input:
     {:invoice/external-id  <string>
      :invoice/issue-date   <java.util.Date>
      :invoice/lines        [<invoice-line>]
      ...}

   Each invoice-line:
     {:invoice-line/quantity    <number-or-bigdec>     ; OR
      :invoice-line/unit-price  <number-or-bigdec>     ; OR pre-computed:
      :invoice-line/line-total  <bigdec>               ; net per line
      :invoice-line/iva-rate    <bigdec>               ; optional override
      :invoice-line/ieps-rate   <bigdec>               ; optional, default 0
      :invoice-line/retencion-iva-rate <bigdec>        ; optional
      :invoice-line/retencion-isr-rate <bigdec>        ; optional
      :invoice-line/tax-status  <keyword>              ; default :taxable
      :invoice-line/account     <code-or-eid>          ; optional override}

   Optional top-level fields:
     :invoice/region        keyword (:general | :border-norte | :border-sur)
                            — applies to ALL lines (default :general)
     :invoice/cash-sale?    when true, debit Caja (101) AND route IVA
                            directly to cobrado (208.01) — payment
                            recognised at issuance
     :invoice/bank-sale?    when true, debit Bancos (102) instead of AR
     :invoice/export?       when true, debit Clientes Extranjero (105.02)
     :invoice/buyer         partner ref (kernel :transaction/partner)
     :invoice/journal       journal code override (default \"INV\")
     :invoice/narration     transaction narration (default = external-id)

   Opts:
     :codes        — map of code overrides (any of the default-codes keys)
     :commodity    — commodity symbol (default \"MXN\")
     :journal-code — journal code (default \"INV\")

   Returns a tx-data vector ready for
   `kontor.validation/transact-with-validation`. Enforces sum-to-zero
   via `kontor.posting/build-transaction`."
  [db invoice {:keys [codes commodity journal-code]
               :or {codes {} commodity default-commodity
                    journal-code default-journal-code}}]
  (let [{:invoice/keys [external-id issue-date lines buyer cash-sale?
                        journal region]} invoice
        _ (when-not external-id
            (throw (ex-info "Invoice missing :invoice/external-id" {:invoice invoice})))
        _ (when-not issue-date
            (throw (ex-info "Invoice missing :invoice/issue-date" {:invoice invoice})))
        _ (when (empty? lines)
            (throw (ex-info "Invoice has no :invoice/lines" {:invoice invoice})))
        merged-codes (merge (default-codes) codes)
        region (or region :general)
        commodity-eid (or (commodity-by-symbol db commodity)
                          (throw (ex-info (str "Commodity " commodity " not found")
                                          {:commodity commodity})))
        jnl-code (or journal journal-code)
        jnl (or (journal-by-code db jnl-code)
                (throw (ex-info (str "Journal " jnl-code " not found — create it before posting")
                                {:code jnl-code})))
        breakdown (mapv #(line-breakdown % region) lines)
        rev-posts  (revenue-postings db breakdown merged-codes commodity-eid issue-date)
        iva-posts  (iva-postings db breakdown merged-codes commodity-eid issue-date
                                 (boolean cash-sale?))
        ieps-post  (ieps-posting db breakdown merged-codes commodity-eid issue-date
                                 (boolean cash-sale?))
        ret-posts  (retencion-postings db breakdown merged-codes commodity-eid issue-date)
        debit-code (debit-account-code invoice merged-codes)
        debit-acct (require-account db debit-code)
        gross  (gross-amount breakdown)
        total-ret (total-retencion breakdown)
        ;; Debit (AR / cash / bank) carries the NET cash position:
        ;; gross minus retención withheld at source.
        debit-amount (.subtract gross total-ret)
        debit-post {:posting/account debit-acct
                    :posting/amount debit-amount
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
        all-postings (-> [debit-post]
                         (into ret-posts)
                         (into rev-posts)
                         (into iva-posts)
                         (cond-> ieps-post (conj ieps-post)))
        input {:transaction tx-base
               :postings all-postings}]
    (posting/build-transaction input)))

(defn post-mx-invoice!
  "Side-effecting wrapper around `plan-mx-invoice-tx-data` (ADR-068).
   Routes the tx-data through `kontor.validation/transact-with-
   validation` so the kernel gate (sealing / period / sum-to-zero /
   invariants) applies.

   See `plan-mx-invoice-tx-data` for the input shape + options."
  ([conn invoice] (post-mx-invoice! conn invoice {}))
  ([conn invoice opts]
   (let [tx-data (plan-mx-invoice-tx-data (d/db conn) invoice opts)]
     (validation/transact-with-validation conn tx-data))))

;; ============================================================================
;; Validation helpers (caller-side)
;; ============================================================================

(defn validate-invoice
  "Return a vector of complaints; empty when ready to post. Used by
   consumers to surface input issues *before* hitting the gate."
  [invoice]
  (let [{:invoice/keys [external-id issue-date lines region]} invoice]
    (cond-> []
      (or (nil? external-id) (and (string? external-id) (str/blank? external-id)))
      (conj {:field :invoice/external-id :issue :missing-or-blank})

      (nil? issue-date)
      (conj {:field :invoice/issue-date :issue :missing})

      (empty? lines)
      (conj {:field :invoice/lines :issue :empty})

      (and (some? region) (not (contains? tax/regions region)))
      (conj {:field :invoice/region :issue :invalid
             :valid tax/regions}))))
