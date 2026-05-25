(ns kontor.l10n-us.investment-income-provider
  "US investment-income tax provider — `PeriodTaxProvider` (ADR-099)
   over the ADR-101 statute-as-data substrate. Research note 148.

   The investment-income flow:

     - **Qualified dividends** (§1(h)(11)) — taxed at the same §1(h)
       0/15/20 brackets as long-term capital gains; the QD lane
       REUSES `kontor.l10n-us.cgt-provider/lt-schedule`.
     - **Ordinary dividends** + taxable interest (bank, corporate,
       OID, market-discount, Treasury) — fold into ordinary income
       via `:pit-base-additions`.
     - **§103 muni interest** — federally exempt; the provider does
       NOT see it (the chart-of-accounts convention is
       `Income:Interest:Municipal*`; the provider scans only the
       taxable income accounts).
     - **§163(d) investment-interest expense deduction** — limited
       to NII per the statute provision; surfaces as
       `:pit-base-additions [-deduction]` (negative).
     - **§901 foreign tax credit** — applied via `:credits`
       adjustment-layer items the consumer threads downstream.
     - **§1411 NIIT** — 3.8 % surtax on NII above MAGI threshold;
       OWNS NIIT by default (CGT provider opts out via
       `:emit-niit? false` when composed; note 148 §5).

   ## Composition

   The consumer wires THREE providers per period in this order:

     1. `us-investment-income-provider` (this) — emits QD-lane tax +
        ordinary-base additions + NIIT (when `:emit-niit? true`).
     2. `us-individual-cgt-provider` (with `:emit-niit? false`) —
        emits CGT lanes; NIIT skipped to avoid double-fire.
     3. `us-personal-income-tax-provider` — consumes all the
        `:pit-base-additions` via `kontor.cgt/fold-into-base-transform`.

   ## Chart-of-accounts convention

   The provider expects income accounts to follow these prefixes
   (consumer wires the chart):

     Income:Dividends:Qualified  → QD lane (§1(h)(11))
     Income:Dividends:Ordinary   → ordinary lane (→ PIT)
     Income:Dividends:REIT       → ordinary lane (§199A excluded)
     Income:Interest:Bank        → ordinary lane
     Income:Interest:Corporate   → ordinary lane
     Income:Interest:Treasury    → ordinary lane (state-exempt only)
     Income:Interest:OID         → ordinary lane (§1272)
     Income:Interest:Municipal   → EXCLUDED (§103 federally exempt)

   Note 148 §3.1."
  (:require [kontor.l10n-us.cgt-provider :as cgt]
            [kontor.l10n-us.investment-income-statute :as inv-statute]
            [kontor.money :as money]
            [kontor.period-tax-provider :as ptp]
            [kontor.report :as report]
            [kontor.statute :as statute]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; Compute-fn registration — §163(d) deduction cap
;; ============================================================================

(defn- as-of-from-ctx
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- us-§163d-cap
  "§163(d) investment-interest deduction = min(paid, NII).

   Caller supplies `:inputs :investment-interest-paid` (the GL
   expense bucket scanned via marginalize) + `:inputs :nii`
   (the consumer-supplied net investment income for the cap).
   Returns the allowed deduction; the excess is carryforward."
  ^java.math.BigDecimal [ctx]
  (let [paid (or (get-in ctx [:inputs :investment-interest-paid]) 0M)
        nii  (or (get-in ctx [:inputs :nii]) 0M)]
    (min paid (max 0M nii))))

(defn register!
  "Register the §163(d) compute-fn with `kontor.statute`. Called at
   namespace load; idempotent."
  []
  (statute/register-compute-fn! :us-§163d-cap us-§163d-cap))

(register!)

;; ============================================================================
;; Base-selector — what marginalizes into what
;; ============================================================================

(defn- sum-prefix
  "Sum `:value :amount` across keys whose stringified form starts
   with `prefix`. Returns BigDecimal."
  ^java.math.BigDecimal [marginalized-map prefix]
  (reduce + 0M
          (for [[k v] marginalized-map
                :when (and (some? k)
                           (.startsWith (str k) prefix))]
            (or (some-> v :value :amount) 0M))))

(defn- investment-income-base-selectors
  "Marginalize the GL income postings and split into the dividend +
   interest lanes per the chart-code convention (note 148 §3.1).
   Returns a map of `{<lane-key> <bigdec>}` keyed by chart prefix.

   Requires `:conn` in ctx (`report-postings` needs a connection to
   take a bitemporal snapshot). When no `:conn` is available, the
   consumer must pre-marginalize via `:inputs :investment-income-bases`."
  [{:keys [conn entity period]} commodity]
  (let [postings (report/report-postings
                  conn (cond-> {:from (:from period) :to (:to period)}
                         entity (assoc :entity entity)))
        by-code  (report/marginalize postings :account-code
                                     {:sign :inflow :commodity commodity})]
    {:qualified-dividends      (sum-prefix by-code "Income:Dividends:Qualified")
     :ordinary-dividends       (sum-prefix by-code "Income:Dividends:Ordinary")
     :reit-dividends           (sum-prefix by-code "Income:Dividends:REIT")
     :bank-interest            (sum-prefix by-code "Income:Interest:Bank")
     :corp-bond-interest       (sum-prefix by-code "Income:Interest:Corporate")
     :treasury-interest        (sum-prefix by-code "Income:Interest:Treasury")
     :oid-interest             (sum-prefix by-code "Income:Interest:OID")
     :market-discount          (sum-prefix by-code "Income:Interest:Market-Discount")
     :muni-interest            (sum-prefix by-code "Income:Interest:Municipal")
     :investment-interest-paid (sum-prefix by-code "Expense:Interest:Investment")}))

(defn- taxable-interest [bases]
  (+ (:bank-interest bases)
     (:corp-bond-interest bases)
     (:treasury-interest bases)
     (:oid-interest bases)
     (:market-discount bases)))

(defn- ordinary-investment-income [bases]
  (+ (:ordinary-dividends bases)
     (:reit-dividends bases)
     (taxable-interest bases)))

;; ============================================================================
;; Components
;; ============================================================================

(defn- qd-component
  "Qualified-dividend component — own §1(h)(11) bracket schedule
   (REUSES `cgt-provider/lt-schedule`)."
  [{:keys [commodity authority]} ctx ^java.math.BigDecimal qd-amount]
  (let [db       (:db ctx)
        as-of    (as-of-from-ctx ctx)
        status   (or (get-in ctx [:tax-unit :filing-status]) :single)
        schedule (cgt/lt-schedule db as-of status)
        gross    (ts/apply-schedule schedule qd-amount)]
    {:kind            :investment-income-tax
     :authority       authority
     :base            (money/money qd-amount commodity)
     :schedule        schedule
     :gross-liability (money/money gross commodity)
     :liability       (money/money gross commodity)
     :prepaid         (money/zero commodity)
     :regime          status
     :line-items      [{:line :qd-amount :label "Qualified dividends"
                        :value (money/money qd-amount commodity)}
                       {:line :qd-tax    :label "§1(h)(11) bracket tax"
                        :value (money/money gross commodity)}]
     :jurisdiction-specific-codes {:lane :qualified-dividend
                                   :filing-status status}}))

(defn- ordinary-investment-component
  "Ordinary investment income — folds into PIT base via
   `:pit-base-additions`. NIIT computation here is informational;
   the actual surtax fires in `niit-component` (when `:emit-niit?`)."
  [{:keys [commodity authority]} ^java.math.BigDecimal ordinary-amount]
  {:kind            :investment-income-tax
   :authority       authority
   :base            (money/money ordinary-amount commodity)
   :schedule        nil
   :gross-liability (money/zero commodity)
   :liability       (money/zero commodity)
   :prepaid         (money/zero commodity)
   :line-items      [{:line :ordinary-investment-net
                      :label "Net ordinary investment income (dividends + taxable interest)"
                      :value (money/money ordinary-amount commodity)}]
   :jurisdiction-specific-codes {:pit-base-additions [ordinary-amount]
                                 :lane :ordinary-investment}})

(defn- §163d-deduction-component
  "§163(d) investment-interest deduction — when claimed, surfaces as
   a negative `:pit-base-additions` (subtracts from PIT base).
   Excess over NII carries forward via
   `:investment-interest-carryforward` line item."
  [{:keys [commodity authority]} ^java.math.BigDecimal paid
   ^java.math.BigDecimal allowed]
  (let [carryforward (max 0M (- paid allowed))]
    {:kind            :investment-income-tax
     :authority       authority
     :base            (money/money (- allowed) commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :liability       (money/zero commodity)
     :prepaid         (money/zero commodity)
     :line-items      [{:line :§163d-paid
                        :label "§163(d) investment interest paid"
                        :value (money/money paid commodity)}
                       {:line :§163d-allowed
                        :label "§163(d) deduction allowed (capped at NII)"
                        :value (money/money allowed commodity)}
                       {:line :§163d-carryforward
                        :label "§163(d) carryforward to next year"
                        :value (money/money carryforward commodity)}]
     :jurisdiction-specific-codes
     {:pit-base-additions [(- allowed)]
      :investment-interest-carryforward carryforward
      :lane :§163d-deduction}}))

(defn- niit-component
  "§1411 NIIT — 3.8 % on NII above MAGI threshold (filing-status-
   conditioned). Reuses the `:us-niit` compute-fn already registered
   by the CGT provider; the only difference vs CGT is that NII here
   INCLUDES the dividend + interest portion (consumer supplies via
   `:inputs :net-investment-income`)."
  [{:keys [commodity authority]} ctx]
  (let [scoped-ctx (assoc ctx :kind :individual)
        as-of (as-of-from-ctx ctx)
        {:keys [tax-items]} (statute/apply-provisions
                             (:db ctx)
                             {:concept :surtax :jurisdiction :us :as-of as-of}
                             scoped-ctx)
        ;; Use a zero running so the NIIT lands as the entire surtax.
        {liability :liability resolved :resolved}
        (ts/apply-adjustments 0M tax-items scoped-ctx)]
    (when (pos? liability)
      {:kind            :investment-income-tax
       :authority       authority
       :base            (money/zero commodity)
       :schedule        nil
       :gross-liability (money/money liability commodity)
       :liability       (money/money liability commodity)
       :prepaid         (money/zero commodity)
       :line-items      (mapv (fn [r] {:line (:code r)
                                       :label (:label r)
                                       :value (money/money (:amount r) commodity)})
                              resolved)
       :jurisdiction-specific-codes {:lane :niit}})))

;; ============================================================================
;; Provider
;; ============================================================================

(defrecord USInvestmentIncomeTaxProvider
           [id authority commodity statute emit-niit?]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs] :as ctx}]
    (let [_         (or (:db ctx)
                        (throw (ex-info ":db required in ctx for US investment-income provider"
                                        {:ctx-keys (keys ctx)})))
          ;; If the consumer pre-marginalized the GL into :inputs
          ;; :investment-income-bases, use that (e.g. for tests or
          ;; when basis comes from a 1099-DIV upload, not the GL).
          ;; Otherwise, scan the GL by chart-prefix convention.
          bases     (or (:investment-income-bases inputs)
                        (investment-income-base-selectors ctx commodity))
          qd        (or (:qualified-dividends bases) 0M)
          ordinary  (ordinary-investment-income bases)
          paid      (or (:investment-interest-paid bases) 0M)
          ;; §1411(c)(1) defines NII as gross investment income MINUS
          ;; allocable deductions. The consumer may supply `:nii`
          ;; pre-netted (to fold in capital gains from a sibling CGT
          ;; provider); we use that as the pre-§163(d) base. If not
          ;; supplied, we infer NII-gross = QD + ordinary (interest +
          ;; non-qualified dividends).
          nii-gross (or (:nii inputs) (+ qd ordinary))
          ;; §163(d) cap = min(paid, NII-gross). Allowed deduction
          ;; reduces NII for NIIT base purposes per §1411(c)(1)(B).
          allowed   (min paid (max 0M nii-gross))
          nii       (max 0M (- nii-gross allowed))
          opts      {:authority authority :commodity commodity}
          ;; Inject the POST-§163(d) NII into ctx for the NIIT
          ;; compute-fn to read.
          ctx'      (-> ctx
                        (assoc-in [:inputs :net-investment-income] nii)
                        (assoc-in [:inputs :investment-interest-paid] paid))
          qd-cmp    (when (pos? qd)       (qd-component opts ctx' qd))
          ord-cmp   (when (pos? ordinary) (ordinary-investment-component opts ordinary))
          §163d-cmp (when (pos? paid)     (§163d-deduction-component opts paid allowed))
          niit-cmp  (when emit-niit?      (niit-component opts ctx'))
          components (->> [qd-cmp ord-cmp §163d-cmp niit-cmp]
                          (remove nil?)
                          vec)]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :us :authority authority}
        :functional-commodity commodity
        :components           components}))))

;; ============================================================================
;; Constructor
;; ============================================================================

(defn us-investment-income-provider
  "Build a US investment-income provider.
   `:emit-niit?` (default `true`) — when true, this provider owns
   §1411 NIIT. When wired alongside the CGT provider, set CGT's
   `:emit-niit? false` to avoid double-fire (note 148 §5)."
  [{:keys [id emit-niit?] :or {emit-niit? true}}]
  (->USInvestmentIncomeTaxProvider
   (or id :us-investment-income) :us-irs :USD
   "IRC §1(h)(11), §103, §163(d), §901, §1411"
   emit-niit?))

(defn install-statute!
  "Install the US investment-income statute. Requires the US CGT
   statute to be installed first (this statute references the CGT
   bracket parameters by code)."
  [conn]
  (inv-statute/install! conn))
