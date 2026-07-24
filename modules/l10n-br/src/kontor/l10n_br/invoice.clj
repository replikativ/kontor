(ns kontor.l10n-br.invoice
  "Brazilian invoice posting builder — translates an issued NF-e /
   NFS-e / equivalent fiscal document into kernel transaction +
   posting tx-data.

   Sits between `kontor.l10n-br.taxes/compute-tax` (rate logic +
   base composition) and `kontor.posting/build-transaction`
   (structural validation + sum-to-zero). Accepts a BR-shaped invoice
   and emits the appropriate per-authority tax-payable postings:

     Dr  AR (or cash)                      gross
     Cr  Sales revenue                      net
     Cr  ICMS payable      (state)         icms outbound
     Cr  IPI payable       (federal)       ipi (manufacturing)
     Cr  PIS payable       (federal)       pis contribution
     Cr  COFINS payable    (federal)       cofins contribution
     Cr  ISS payable       (municipal)     iss (services only)
     Cr  ICMS payable      (destination)   difal differential
     Cr  ICMS payable      (FCP)           fcp surcharge

   Multiple taxes stack on a single Brazilian invoice — the kernel's
   sum-to-zero rule per (ledger, commodity) ties the whole stack
   together in one balanced posting set.

   ## ICMS-payable routing for DIFAL

   In a single seller-side journal entry, the ICMS portion that the
   seller actually keeps (origin share) and the DIFAL portion owed
   to the destination state both post to the same liability bucket
   here (`Liabilities:Tax:ICMS-Payable`, code 2.01.04.01.01).
   Production deployments typically split these across two
   sub-accounts so the monthly GIA / EFD-ICMS-IPI return can
   itemise the destination-state share — see the docstring for
   `:codes` override below.

   ## ISS — municipality-keyed liability

   Municipal ISS varies 2-5% by município, and many municipalities
   require per-municipality sub-accounts for the ISS recolhimento.
   This builder posts ISS to a single `Liabilities:Tax:ISS-Payable`
   (2.01.04.01.05) by default; pass `:codes {:iss-code ...}` to
   pin a per-municipality sub-code when the consumer maintains them.

   ## Buyer-side credits (ICMS / PIS / COFINS recoverable)

   Kontor posts the **seller side** of the invoice. The buyer's
   `:icms-recoverable` / `:pis-recoverable` / `:cofins-recoverable`
   postings are a separate transaction the buyer's kontor instance
   issues when recording the matching vendor bill. This builder
   never emits buyer-side ITC postings.

   ## Tax-classification routing

   Lines marked `:zero-rated`, `:exempt`, or `:export` contribute
   to revenue only — no tax-account postings. By default the builder
   routes `:export` lines to `Income:Sales:Export`
   (3.01.01.02.01) so the gross-revenue split for ECF / DIPJ already
   reflects the export carve-out. `:services` lines route to
   `Income:Sales:Services-Domestic` (3.01.01.01.02). Callers can
   override per-line via `:kontor.invoice-line/account`.

   ## ADR-068 — tx-data builder + side-effecting wrapper

   - **`plan-br-invoice-tx-data`** (pure) — takes a DB value + invoice
     map, returns the tx-data vector ready for the gate.
   - **`post-br-invoice!`** (side-effecting) — wraps the builder and
     routes through `kontor.validation/transact-with-validation`.

   Consumers composing several writes into one process (invoice +
   NF-e clearance call + SPED row + audit doc) call the pure builder
   from a `kontor.workflow.process` step."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [kontor.account :as kacct]
            [kontor.l10n-br.taxes :as tax]
            [kontor.posting :as posting]
            [kontor.validation :as validation]))

;; ============================================================================
;; Default account codes (overridable per call via :codes opts)
;;
;; All codes match the Plano Referencial-aligned starter shipped in
;; modules/l10n-br/resources/kontor/l10n_br/chart.edn. Where multiple
;; sub-accounts exist (e.g. ICMS payable vs DIFAL payable), the
;; defaults route everything to one bucket; override at call site
;; when the consumer maintains a more granular chart.
;; ============================================================================

(def ^:const default-ar-code             "1.01.03.01.01")  ; Clientes Nacionais
(def ^:const default-cash-code           "1.01.01.01.01")  ; Caixa Matriz
(def ^:const default-sales-goods-code    "3.01.01.01.01")  ; Receita Bruta Mercadorias
(def ^:const default-sales-services-code "3.01.01.01.02")  ; Receita Bruta Serviços
(def ^:const default-sales-export-code   "3.01.01.02.01")  ; Receita Bruta Exportação
(def ^:const default-icms-code           "2.01.04.01.01")  ; ICMS a Recolher
(def ^:const default-ipi-code            "2.01.04.01.02")  ; IPI a Recolher
(def ^:const default-pis-code            "2.01.04.01.03")  ; PIS a Recolher
(def ^:const default-cofins-code         "2.01.04.01.04")  ; COFINS a Recolher
(def ^:const default-iss-code            "2.01.04.01.05")  ; ISS a Recolher
(def ^:const default-journal-code        "SJ")
(def ^:const default-commodity           "BRL")

;; ============================================================================
;; DB lookup helpers
;; ============================================================================

(defn- account-by-code [db code]
  (kacct/resolve-code db code {:context "BR invoice bridge"}))

(defn- require-account [db code]
  (or (account-by-code db code)
      (throw (ex-info (str "Account " code " not found — install l10n-br chart first")
                      {:type :l10n-br/missing-account
                       :code code}))))

(defn- journal-by-code [db code]
  (:db/id (d/entity db [:kontor.journal/code code])))

(defn- commodity-by-symbol [db sym]
  (:db/id (d/entity db [:kontor.commodity/symbol sym])))

;; ============================================================================
;; Per-line revenue routing
;; ============================================================================

(defn- revenue-code-for-classification
  "Choose the revenue account based on the line's tax-classification.
   Callers can pin a different account via :kontor.invoice-line/account."
  [classification codes]
  (case classification
    :services    (:sales-services-code codes default-sales-services-code)
    :export      (:sales-export-code   codes default-sales-export-code)
    ;; :goods, :goods-manufactured, :zero-rated, :exempt all route to
    ;; the goods-revenue account by default. zero-rated / exempt goods
    ;; sales still book gross revenue; only the tax-payable legs
    ;; collapse.
    (:sales-goods-code codes default-sales-goods-code)))

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

(defn- line->tax-input
  "Build the per-line compute-tax input from an invoice-line map.
   Inherits invoice-level :from-state / :to-state when the line omits
   them — every line on one NF-e normally shares the shipment."
  [line invoice]
  (let [classification (or (:kontor.invoice-line/tax-classification line) :goods)]
    (cond-> {:line (line-net line)
             :tax-classification classification}
      (or (:kontor.invoice-line/from-state line) (:kontor.invoice/from-state invoice))
      (assoc :from-state (or (:kontor.invoice-line/from-state line)
                             (:kontor.invoice/from-state invoice)))

      (or (:kontor.invoice-line/to-state line) (:kontor.invoice/to-state invoice))
      (assoc :to-state (or (:kontor.invoice-line/to-state line)
                           (:kontor.invoice/to-state invoice)))

      (or (:kontor.invoice-line/pis-regime line) (:kontor.invoice/pis-regime invoice))
      (assoc :pis-regime (or (:kontor.invoice-line/pis-regime line)
                             (:kontor.invoice/pis-regime invoice)))

      (or (:kontor.invoice-line/cofins-regime line) (:kontor.invoice/cofins-regime invoice))
      (assoc :cofins-regime (or (:kontor.invoice-line/cofins-regime line)
                                (:kontor.invoice/cofins-regime invoice)))

      (:kontor.invoice-line/ipi-rate line)
      (assoc :ipi-rate (:kontor.invoice-line/ipi-rate line))

      (:kontor.invoice-line/iss-rate line)
      (assoc :iss-rate (:kontor.invoice-line/iss-rate line))

      (:kontor.invoice-line/icms-rate line)
      (assoc :icms-rate (:kontor.invoice-line/icms-rate line))

      (or (:kontor.invoice-line/buyer-type line) (:kontor.invoice/buyer-type invoice))
      (assoc :buyer-type (or (:kontor.invoice-line/buyer-type line)
                             (:kontor.invoice/buyer-type invoice)))

      (or (:kontor.invoice-line/purpose line) (:kontor.invoice/purpose invoice))
      (assoc :purpose (or (:kontor.invoice-line/purpose line)
                          (:kontor.invoice/purpose invoice)))

      (some? (:kontor.invoice-line/imported? line))
      (assoc :imported? (:kontor.invoice-line/imported? line))

      (or (:kontor.invoice-line/fcp-rate line) (:kontor.invoice/fcp-rate invoice))
      (assoc :fcp-rate (or (:kontor.invoice-line/fcp-rate line)
                           (:kontor.invoice/fcp-rate invoice))))))

(defn- revenue-postings
  "Per-line revenue credit postings, grouped by (revenue-account,
   classification) so two lines of the same classification produce
   one summed posting and keep the tx compact."
  [db lines codes commodity-eid date]
  (let [grouped (group-by
                 (fn [line]
                   (let [classification (or (:kontor.invoice-line/tax-classification line) :goods)
                         override (:kontor.invoice-line/account line)]
                     [(or override (revenue-code-for-classification classification codes))
                      classification]))
                 lines)]
    (vec
     (for [[[acct-code _classification] ls] grouped
           :let [net (reduce (fn [^java.math.BigDecimal acc l]
                               (.add acc (line-net l)))
                             0M ls)
                 acct (require-account db acct-code)]]
       {:kontor.posting/account acct
        :kontor.posting/amount (.negate (bd net))
        :kontor.posting/commodity commodity-eid
        :kontor.posting/posted-at date}))))

(defn- nonzero? [^java.math.BigDecimal x]
  (not (zero? (.compareTo x 0M))))

(defn- tax-posting
  "Build a single per-tax credit posting. Returns nil if the amount
   is zero (so the caller can filter)."
  [db code ^java.math.BigDecimal amount commodity-eid date]
  (when (and code (nonzero? amount))
    {:kontor.posting/account (require-account db code)
     :kontor.posting/amount (.negate amount)
     :kontor.posting/commodity commodity-eid
     :kontor.posting/posted-at date}))

(defn- sum-amounts
  "Sum the :amount on a tax key across the per-line breakdowns.
   Returns a BigDecimal."
  ^java.math.BigDecimal [per-line k]
  (reduce (fn [^java.math.BigDecimal acc r]
            (.add acc ^java.math.BigDecimal (:amount (k r))))
          0M per-line))

(defn- tax-postings
  "Build the per-tax credit postings from the compute-tax per-line
   breakdown. Emits at most 6 entries (ICMS+DIFAL+FCP combined, IPI,
   PIS, COFINS, ISS) — each only when its aggregate is non-zero.

   ICMS / DIFAL / FCP collapse to a single ICMS-payable posting by
   default; pass `{:codes {:difal-code ..., :fcp-code ...}}` to split
   them across sub-accounts when the consumer maintains them."
  [db per-line codes commodity-eid date]
  (let [icms     (sum-amounts per-line :icms)
        ipi      (sum-amounts per-line :ipi)
        pis      (sum-amounts per-line :pis)
        cofins   (sum-amounts per-line :cofins)
        iss      (sum-amounts per-line :iss)
        difal    (sum-amounts per-line :difal)
        fcp      (sum-amounts per-line :fcp)
        icms-code   (get codes :icms-code   default-icms-code)
        difal-code  (get codes :difal-code  icms-code)
        fcp-code    (get codes :fcp-code    icms-code)
        ipi-code    (get codes :ipi-code    default-ipi-code)
        pis-code    (get codes :pis-code    default-pis-code)
        cofins-code (get codes :cofins-code default-cofins-code)
        iss-code    (get codes :iss-code    default-iss-code)
        ;; If DIFAL/FCP route to the same account as ICMS, sum them
        ;; into a single posting so the tx has one ICMS-payable line.
        icms-bundle (cond-> icms
                      (= icms-code difal-code) (.add difal)
                      (= icms-code fcp-code)   (.add fcp))]
    (->> [(tax-posting db icms-code   icms-bundle commodity-eid date)
          ;; Separate DIFAL posting only when its code differs from ICMS.
          (when (not= icms-code difal-code)
            (tax-posting db difal-code difal commodity-eid date))
          ;; Separate FCP posting only when its code differs from ICMS.
          (when (not= icms-code fcp-code)
            (tax-posting db fcp-code fcp commodity-eid date))
          (tax-posting db ipi-code    ipi    commodity-eid date)
          (tax-posting db pis-code    pis    commodity-eid date)
          (tax-posting db cofins-code cofins commodity-eid date)
          (tax-posting db iss-code    iss    commodity-eid date)]
         (remove nil?)
         vec)))

(defn plan-br-invoice-tx-data
  "Pure tx-data builder for a Brazilian sales invoice (ADR-068).

   Required input:
     {:kontor.invoice/external-id   <string>
      :kontor.invoice/issue-date    <java.util.Date>
      :kontor.invoice/lines         [<invoice-line>]
      ...}

   For goods invoices (`:tax-classification :goods` or
   `:goods-manufactured`):
     :kontor.invoice/from-state    <string>   ; 2-letter origin state e.g. \"SP\"
     :kontor.invoice/to-state      <string>   ; 2-letter destination state

   For services invoices: from-state/to-state are optional (services
   ISS is municipality-keyed, not state-keyed). Each services line
   must carry `:kontor.invoice-line/iss-rate` (e.g. 0.05M for 5%).

   Each invoice-line:
     {:kontor.invoice-line/quantity         <number-or-bigdec>           ; OR
      :kontor.invoice-line/unit-price       <number-or-bigdec>           ; OR pre-computed:
      :kontor.invoice-line/line-total       <bigdec>                     ; net per line
      :kontor.invoice-line/tax-classification <keyword>                  ; default :goods
      :kontor.invoice-line/iss-rate         <bigdec>                     ; required for :services
      :kontor.invoice-line/ipi-rate         <bigdec>                     ; required for :goods-manufactured
      :kontor.invoice-line/icms-rate        <bigdec>                     ; override (rare)
      :kontor.invoice-line/imported?        <boolean>                    ; CST origem 1/2/3/6/7/8
      :kontor.invoice-line/buyer-type       :non-contributor|:contributor
      :kontor.invoice-line/purpose          :consumption|:fixed-asset|:resale|:industrialization
      :kontor.invoice-line/from-state       <string>                     ; per-line override
      :kontor.invoice-line/to-state         <string>                     ; per-line override
      :kontor.invoice-line/pis-regime       :cumulative|:non-cumulative
      :kontor.invoice-line/cofins-regime    :cumulative|:non-cumulative
      :kontor.invoice-line/fcp-rate         <bigdec>
      :kontor.invoice-line/account          <code-or-eid>                ; revenue acct override
      ...}

   Optional top-level fields:
     :kontor.invoice/cash-sale?    when true, debit Caixa instead of AR.
     :kontor.invoice/cash-code     account-code override for the cash leg.
     :kontor.invoice/buyer         partner ref (kernel :kontor.transaction/partner).
     :kontor.invoice/journal       journal code override (default INV).

   Opts:
     :codes        map of code overrides:
                     :ar-code, :cash-code,
                     :sales-goods-code, :sales-services-code,
                     :sales-export-code, :icms-code, :difal-code,
                     :fcp-code, :ipi-code, :pis-code,
                     :cofins-code, :iss-code.
     :commodity    commodity symbol (default \"BRL\").
     :journal-code journal code (default \"INV\").

   Returns a tx-data vector ready for
   `kontor.validation/transact-with-validation`.

   The builder enforces sum-to-zero per the kernel rules (via
   `kontor.posting/build-transaction`)."
  [db invoice {:keys [codes commodity journal-code]
               :or {codes {} commodity default-commodity
                    journal-code default-journal-code}}]
  (let [{:kontor.invoice/keys [external-id issue-date lines buyer cash-sale? journal]} invoice
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
        ;; Compute tax via the rate-table fn, line by line.
        compute-input
        {:lines (mapv (fn [l] (line->tax-input l invoice)) lines)
         :from-state    (:kontor.invoice/from-state invoice)
         :to-state      (:kontor.invoice/to-state invoice)
         :buyer-type    (:kontor.invoice/buyer-type invoice)
         :purpose       (:kontor.invoice/purpose invoice)
         :pis-regime    (:kontor.invoice/pis-regime invoice)
         :cofins-regime (:kontor.invoice/cofins-regime invoice)
         :imported?     (:kontor.invoice/imported? invoice)
         :fcp-rate      (:kontor.invoice/fcp-rate invoice)}
        tax-r (tax/compute-invoice-tax compute-input)
        per-line (:per-line tax-r)
        gross (:total-gross tax-r)
        debit-code (if cash-sale?
                     (get codes :cash-code default-cash-code)
                     (get codes :ar-code   default-ar-code))
        debit-acct (require-account db debit-code)
        rev-posts (revenue-postings db lines codes commodity-eid issue-date)
        tax-posts (tax-postings db per-line codes commodity-eid issue-date)
        debit-post {:kontor.posting/account debit-acct
                    :kontor.posting/amount (:amount gross)
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

(defn post-br-invoice!
  "Side-effecting wrapper around `plan-br-invoice-tx-data` (ADR-068).
   Routes the tx-data through `kontor.validation/transact-with-
   validation` so the kernel gate (sealing / period / sum-to-zero /
   invariants) applies.

   See `plan-br-invoice-tx-data` for the input shape + options."
  ([conn invoice] (post-br-invoice! conn invoice {}))
  ([conn invoice opts]
   (let [tx-data (plan-br-invoice-tx-data (d/db conn) invoice opts)]
     (validation/transact-with-validation conn tx-data))))

;; ============================================================================
;; Convenience predicates
;; ============================================================================

(defn state?
  "True iff `s` is a recognised Brazilian state / federal-unit code
   (\"SP\", \"BA\", … 26 states + \"DF\"). Useful at the consumer
   boundary before handing an invoice off to the builder."
  [s]
  (contains? tax/all-states s))

(defn interstate?
  "True iff origin and destination differ. Drives the DIFAL path
   inside `compute-tax`."
  [from-state to-state]
  (and from-state to-state (not= from-state to-state)))

;; ============================================================================
;; Validation helpers (caller-side)
;; ============================================================================

(defn validate-invoice
  "Return a vector of complaints; empty when the invoice is ready to
   post. Used by consumers to surface input issues *before* hitting
   the gate.

   Checks (non-exhaustive):
     - :kontor.invoice/external-id present + non-blank
     - :kontor.invoice/issue-date present
     - :kontor.invoice/lines present + non-empty
     - For goods-classified invoices: :kontor.invoice/from-state +
       :kontor.invoice/to-state present (or each line carries its own)
     - For services lines: :kontor.invoice-line/iss-rate present"
  [invoice]
  (let [{:kontor.invoice/keys [external-id issue-date lines from-state to-state]} invoice
        any-goods?    (some (fn [l]
                              (let [c (or (:kontor.invoice-line/tax-classification l) :goods)]
                                (contains? #{:goods :goods-manufactured :export} c)))
                            lines)
        ;; A goods invoice must have origin+destination either at the
        ;; invoice level or on every goods line.
        all-goods-lines-have-states?
        (every? (fn [l]
                  (let [c (or (:kontor.invoice-line/tax-classification l) :goods)]
                    (or (not (contains? #{:goods :goods-manufactured :export} c))
                        (and (or (:kontor.invoice-line/from-state l) from-state)
                             (or (:kontor.invoice-line/to-state l)   to-state)))))
                lines)
        services-without-iss
        (filter (fn [l]
                  (and (= :services (:kontor.invoice-line/tax-classification l))
                       (nil? (:kontor.invoice-line/iss-rate l))))
                lines)]
    (cond-> []
      (or (nil? external-id) (and (string? external-id) (str/blank? external-id)))
      (conj {:field :kontor.invoice/external-id :issue :missing-or-blank})

      (nil? issue-date)
      (conj {:field :kontor.invoice/issue-date :issue :missing})

      (empty? lines)
      (conj {:field :kontor.invoice/lines :issue :empty})

      (and any-goods? (not all-goods-lines-have-states?))
      (conj {:field :kontor.invoice/from-state-to-state
             :issue :missing-for-goods-line})

      (seq services-without-iss)
      (conj {:field :kontor.invoice-line/iss-rate
             :issue :missing-for-services-line
             :count (count services-without-iss)}))))
