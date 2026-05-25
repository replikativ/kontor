(ns kontor.l10n-mx.investment-income-provider
  "MX investment-income tax provider — `PeriodTaxProvider` (ADR-099)
   over the ADR-101 statute-as-data substrate. Research note 157.

   ## Two callable shapes — `:kind :individual | :corporation`

   - **Individual** (persona física) — up to FOUR component lanes
     depending on which items are present in
     `:inputs :mx-investment-income`:

     1. Dividend acumulable lane — feeder to PIT. Gross-up by 1.4286
        is folded into `:pit-base-additions`; a `:credit` adjustment
        item carries the 30 % corporate-ISR factor-credit. When the
        consumer signals `:elective-regime :mx-cufin-paid` (i.e. the
        dividend is sourced from pre-2014 CUFIN per the constancia)
        the Adicional is suppressed; otherwise the law's presumption
        applies and the next component fires.

     2. Dividend Adicional lane (when applicable) — standalone
        definitive component at 10 % flat on the gross dividend
        amount. The consumer's payer-side journal will have already
        withheld it (constancia de retenciones e información de
        pagos); the provider just records the liability so it
        reconciles against the withholding.

     3. Bank-interest lane — folds real interest (consumer-supplied as
        `:real-interest` = nominal − INPC inflation adjustment, or the
        provider falls back to `:nominal-interest` when no INPC factor
        was provided) into the PIT base via `:pit-base-additions`;
        emits a `:credit` adjustment item for the 0.50 %/0.90 %
        provisional withholding (rate from parameter; the 2026
        bitemporal cliff rides automatic `as-of` lookup).

     4. Foreign-source dividend lane — folds at gross into PIT
        (no Mexican gross-up); emits a `:credit` adjustment item for
        the foreign-tax credit, capped per art. 5 LISR.

   - **Corporation** (persona moral) — TWO outcomes by source:

     1. PJ-to-PJ dividend from CUFIN (signalled by
        `:elective-regime :mx-cufin-paid`) — emits a ZERO-tax audit-
        only component recording the CUFIN credit movement. Art. 16
        fr. III LISR exemption.

     2. Other dividends + interest → fold into CIT base via
        `:cit-base-additions`. The 30 % art. 9 rate fires at the CIT
        provider, not here.

   ## Consumer inputs

   `:inputs :mx-investment-income` is a map shaped:

       {:dividends      [<dividend-source>]
        :interest       [<interest-source>]
        :foreign-tax-credits {<country-kw> {:income <bd> :paid <bd>}}}

   where `<dividend-source>` is:

       {:source-id           :payer-A
        :amount              <bd>                   ; gross declared
        :cufin-bucket        :pre-2014 | :post-2014  ; defaults to :post-2014
        :elective-regime     #{:mx-cufin-paid}      ; suppresses Adicional
        :adicional-withheld  <bd>                   ; consumer-known WHT (recipient side)
        :foreign?            <bool>                 ; foreign-source flag (PF only)}

   and `<interest-source>` is:

       {:source-id          :bbva-deposit
        :nominal-interest   <bd>                    ; nominal received
        :real-interest      <bd>                    ; optional — overrides INPC math
        :inpc-factor        <bd>                    ; e.g. 1.03 = 3 % inflation
        :daily-avg-balance  <bd>                    ; principal daily-avg
        :withholding-applied <bd>                   ; bank-reported WHT (optional)
        :sofipo?             <bool>}                ; 5-UMA exempt (out of scope v1 — flag noted)

   ## Substrate posture

   Per note 157 §3.7: ZERO schema changes; ZERO new primitives. The
   provider reads parameters via `kontor.statute/parameter-value-at`
   and threads `:pit-base-additions` / `:cit-base-additions` /
   `:credits` to the downstream PIT / CIT providers."
  (:require [kontor.l10n-mx.investment-income-statute :as inv-statute]
            [kontor.money :as money]
            [kontor.period-tax-provider :as ptp]
            [kontor.statute :as statute]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- as-of-from-ctx
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- bigdec0
  ^java.math.BigDecimal [x]
  (or x 0M))

(defn- cufin-paid?
  "True when this dividend source signals the pre-2014 CUFIN slice
   (i.e. Adicional suppressed). The consumer sets the
   `:elective-regime` to `:mx-cufin-paid` on the constancia-attested
   pre-2014 portion."
  [source]
  (let [elects (set (or (:elective-regime source) #{}))]
    (or (contains? elects :mx-cufin-paid)
        (= :pre-2014 (:cufin-bucket source)))))

(defn- real-interest-of
  "Compute real-interest for one bank-interest source. The consumer
   may supply `:real-interest` directly (most reliable — it's what the
   PF declares); otherwise we derive from `:nominal-interest`,
   `:inpc-factor` (a multiplier > 1 means inflation), and
   `:daily-avg-balance`. Falls back to `:nominal-interest` when neither
   inpc factor nor balance is supplied."
  ^java.math.BigDecimal [src]
  (cond
    (some? (:real-interest src))
    (bigdec0 (:real-interest src))

    (and (:inpc-factor src) (:daily-avg-balance src))
    (let [nominal (bigdec0 (:nominal-interest src))
          balance (bigdec0 (:daily-avg-balance src))
          factor  (bigdec0 (:inpc-factor src))
          ;; inflation = balance × (factor − 1)
          inflation (* balance (- factor 1M))]
      (- nominal inflation))

    :else
    (bigdec0 (:nominal-interest src))))

;; ============================================================================
;; Individual components
;; ============================================================================

(defn- dividend-acumulable-component
  "PF dividend acumulable lane — feeder to PIT. Gross-up + factor-credit.
   Returns nil when the source has zero amount."
  [{:keys [commodity authority]} ctx db as-of source]
  (let [amount       (bigdec0 (:amount source))
        gross-up-rate (or (statute/parameter-value-at
                           db "MX.INV.dividendos.gross-up-factor" as-of)
                          1.4286M)
        credit-rate  (or (statute/parameter-value-at
                          db "MX.INV.dividendos.corporate-isr-credit-rate" as-of)
                         0.30M)
        grossed-up   (* amount gross-up-rate)
        credit       (* grossed-up credit-rate)]
    (when (pos? amount)
      {:kind            :investment-income-tax
       :authority       authority
       :base            (money/money grossed-up commodity)
       :schedule        nil
       :gross-liability (money/zero commodity)
       :credits         [{:code        :mx-corporate-isr-proxy
                          :label       "Art. 140 corporate-ISR factor-credit (30 % × grossed-up)"
                          :amount      credit
                          :refundable? false}]
       :liability       (money/zero commodity)
       :prepaid         (money/zero commodity)
       :line-items      [{:line  :gross-dividend
                          :label "Gross dividend received"
                          :value (money/money amount commodity)}
                         {:line  :gross-up
                          :label (str "Grossed-up (× " gross-up-rate ")")
                          :value (money/money grossed-up commodity)}
                         {:line  :corporate-isr-credit
                          :label (str "Factor-credit (× " credit-rate ")")
                          :value (money/money credit commodity)}]
       :provenance      {:provider-id :mx-investment-income
                         :statute     "LISR art. 140"
                         :as-of       as-of
                         :source-id   (:source-id source)}
       :jurisdiction-specific-codes
       {:lane                :mx-pf-dividend-acumulable
        :source-id           (:source-id source)
        :cufin-bucket        (or (:cufin-bucket source) :post-2014)
        :pit-base-additions  [grossed-up]
        :mx/factor-credit    credit}})))

(defn- dividend-adicional-component
  "PF dividend Adicional lane — standalone definitive 10 % flat.
   Suppressed when source signals pre-2014 CUFIN (cufin-paid?)."
  [{:keys [commodity authority]} db as-of source]
  (let [amount (bigdec0 (:amount source))
        rate   (or (statute/parameter-value-at
                    db "MX.INV.dividendos.isr-adicional-rate" as-of)
                   0.10M)
        gross  (* amount rate)
        withheld (bigdec0 (:adicional-withheld source))]
    (when (and (pos? amount) (not (cufin-paid? source)))
      {:kind            :investment-income-tax
       :authority       authority
       :base            (money/money amount commodity)
       :schedule        (ts/flat rate)
       :gross-liability (money/money gross commodity)
       :liability       (money/money gross commodity)
       :prepaid         (money/money withheld commodity)
       :line-items      [{:line  :adicional-base
                          :label "Gross dividend (post-2014 CUFIN slice)"
                          :value (money/money amount commodity)}
                         {:line  :adicional-tax
                          :label "Art. 140 — 10 % definitive Adicional"
                          :value (money/money gross commodity)}
                         {:line  :adicional-withheld
                          :label "Withheld at source by payer (constancia)"
                          :value (money/money withheld commodity)}]
       :provenance      {:provider-id :mx-investment-income
                         :statute     "LISR art. 140 — ISR Adicional"
                         :as-of       as-of
                         :source-id   (:source-id source)}
       :jurisdiction-specific-codes
       {:lane         :mx-pf-dividend-adicional
        :source-id    (:source-id source)
        :cufin-bucket (or (:cufin-bucket source) :post-2014)
        :mx/scjn-confirmed-2026? true}})))

(defn- bank-interest-component
  "PF bank-interest lane — folds real interest into PIT base via
   `:pit-base-additions`; emits a `:credit` for the provisional WHT
   (rate from MX.INV.bank-interest.wht-rate, bitemporally resolved).

   The withholding is computed as rate × daily-average-balance per
   art. 54 LISR; if the consumer supplied `:withholding-applied` we
   use that (the bank's authoritative figure) — otherwise we compute."
  [{:keys [commodity authority]} db as-of source]
  (let [nominal (bigdec0 (:nominal-interest source))
        real    (real-interest-of source)
        rate    (or (statute/parameter-value-at
                     db "MX.INV.bank-interest.wht-rate" as-of)
                    0.0090M)
        balance (bigdec0 (:daily-avg-balance source))
        computed-wh (* balance rate)
        wh      (if (some? (:withholding-applied source))
                  (bigdec0 (:withholding-applied source))
                  computed-wh)]
    (when (or (pos? nominal) (pos? real) (pos? wh))
      {:kind            :investment-income-tax
       :authority       authority
       :base            (money/money real commodity)
       :schedule        nil
       :gross-liability (money/zero commodity)
       :liability       (money/zero commodity)
       :prepaid         (money/money wh commodity)
       :credits         [{:code        :mx-bank-interest-provisional-wh
                          :label       (str "Art. 54 LISR provisional WHT (rate " rate ")")
                          :amount      wh
                          :refundable? true}]
       :line-items      [{:line  :nominal-interest
                          :label "Nominal interest received"
                          :value (money/money nominal commodity)}
                         {:line  :real-interest
                          :label "Real interest (after INPC inflation adjustment)"
                          :value (money/money real commodity)}
                         {:line  :wht-rate
                          :label "Provisional WHT rate applied"
                          :value rate}
                         {:line  :provisional-wh
                          :label "Provisional WHT credit"
                          :value (money/money wh commodity)}]
       :provenance      {:provider-id :mx-investment-income
                         :statute     "LISR arts. 54, 133-135"
                         :as-of       as-of
                         :source-id   (:source-id source)}
       :jurisdiction-specific-codes
       {:lane                :mx-pf-bank-interest
        :source-id           (:source-id source)
        :pit-base-additions  [real]
        :mx/wht-rate         rate
        :mx/sofipo-exempt?   (boolean (:sofipo? source))}})))

(defn- foreign-dividend-component
  "PF foreign-source dividend — included at gross (no MX gross-up);
   foreign-tax credit applied per art. 5 LISR. The cap is applied by
   the consumer's downstream PIT against the MX ISR otherwise due; we
   surface the FTC amount as a `:credit` adjustment item."
  [{:keys [commodity authority]} db as-of source foreign-credit]
  (let [amount (bigdec0 (:amount source))
        paid   (bigdec0 (:paid foreign-credit))
        income (bigdec0 (:income foreign-credit))
        cap-rate (or (statute/parameter-value-at
                      db "MX.INV.foreign-tax-credit-cap-rate" as-of)
                     1.00M)
        ;; v1 cap = paid (the foreign tax actually paid), bounded by
        ;; (income × cap-rate). The consumer's PIT enforces the
        ;; otherwise-due MX-ISR ceiling.
        cap     (* income cap-rate)
        allowed (ts/lesser-of paid cap)]
    (when (pos? amount)
      {:kind            :investment-income-tax
       :authority       authority
       :base            (money/money amount commodity)
       :schedule        nil
       :gross-liability (money/zero commodity)
       :liability       (money/zero commodity)
       :prepaid         (money/zero commodity)
       :credits         (cond-> []
                          (pos? allowed)
                          (conj {:code        :mx-foreign-tax-credit
                                 :label       (str "Art. 5 LISR foreign-tax credit ("
                                                   (:country foreign-credit) ")")
                                 :amount      allowed
                                 :refundable? false}))
       :line-items      [{:line  :foreign-dividend-gross
                          :label "Foreign-source dividend (no MX gross-up)"
                          :value (money/money amount commodity)}
                         {:line  :foreign-tax-paid
                          :label "Foreign tax paid"
                          :value (money/money paid commodity)}
                         {:line  :foreign-tax-credit-allowed
                          :label "FTC allowed (capped at income × cap rate)"
                          :value (money/money allowed commodity)}]
       :provenance      {:provider-id :mx-investment-income
                         :statute     "LISR arts. 5, 142"
                         :as-of       as-of
                         :source-id   (:source-id source)}
       :jurisdiction-specific-codes
       {:lane                :mx-pf-foreign-dividend
        :source-id           (:source-id source)
        :pit-base-additions  [amount]
        :mx/ftc-paid         paid
        :mx/ftc-allowed      allowed
        :mx/ftc-carryforward (max 0M (- paid allowed))
        :mx/foreign-country  (:country foreign-credit)}})))

;; ============================================================================
;; Corporate components
;; ============================================================================

(defn- corp-pj-to-pj-exempt-component
  "PJ-to-PJ dividend from CUFIN — art. 16 fr. III LISR exemption.
   Audit-only ZERO-tax component. Emits `:line-items :cufin-credit`
   recording the post-tax pool transferred up."
  [{:keys [commodity authority]} as-of source]
  (let [amount (bigdec0 (:amount source))]
    (when (pos? amount)
      {:kind            :investment-income-tax
       :authority       authority
       :base            (money/money amount commodity)
       :schedule        (ts/flat 0M)
       :gross-liability (money/zero commodity)
       :liability       (money/zero commodity)
       :prepaid         (money/zero commodity)
       :line-items      [{:line  :pj-to-pj-exempt
                          :label "Art. 16 fr. III LISR — PJ-to-PJ dividend from CUFIN (exempt)"
                          :value (money/money amount commodity)}
                         {:line  :cufin-credit
                          :label "CUFIN balance transferred to recipient PJ"
                          :value (money/money amount commodity)}]
       :provenance      {:provider-id :mx-investment-income
                         :statute     "LISR art. 16 fr. III"
                         :as-of       as-of
                         :source-id   (:source-id source)}
       :jurisdiction-specific-codes
       {:lane                :mx-pj-pj-dividend-exempt
        :source-id           (:source-id source)
        :cufin-bucket        (or (:cufin-bucket source) :post-2014)
        :mx/cufin-credit-in  amount}})))

(defn- corp-cit-feeder-component
  "Corporate — non-exempt dividends + interest fold into CIT base via
   `:cit-base-additions`. The 30 % art. 9 fires at the CIT provider."
  [{:keys [commodity authority]} as-of taxable-divs interest-sum]
  (let [total (+ taxable-divs interest-sum)]
    (when (pos? total)
      {:kind            :investment-income-tax
       :authority       authority
       :base            (money/money total commodity)
       :schedule        nil
       :gross-liability (money/zero commodity)
       :liability       (money/zero commodity)
       :prepaid         (money/zero commodity)
       :line-items      (cond-> []
                          (pos? taxable-divs)
                          (conj {:line  :corp-taxable-dividends
                                 :label "Corporate dividends (non-CUFIN portion → CIT base)"
                                 :value (money/money taxable-divs commodity)})
                          (pos? interest-sum)
                          (conj {:line  :corp-interest
                                 :label "Corporate interest income → CIT base"
                                 :value (money/money interest-sum commodity)}))
       :provenance      {:provider-id :mx-investment-income
                         :statute     "LISR art. 16"
                         :as-of       as-of}
       :jurisdiction-specific-codes
       {:lane                :mx-pm-cit-feeder
        :cit-base-additions  [total]}})))

;; ============================================================================
;; Provider record
;; ============================================================================

(defn- individual-facts
  [db ctx as-of entity period commodity authority items]
  (let [dividends     (or (:dividends items) [])
        interests     (or (:interest items) [])
        ft-credits    (or (:foreign-tax-credits items) {})
        opts          {:commodity commodity :authority authority}
        ;; Dividend components: each source spawns up to 2 (acumulable +
        ;; Adicional) for domestic; foreign dividends go via the foreign
        ;; lane.
        dividend-cmps
        (->> dividends
             (mapcat
              (fn [src]
                (if (:foreign? src)
                  ;; Foreign dividend — uses the FTC slice (by source-id or country)
                  (let [country (or (:country src) :unknown)
                        ftc     (or (get ft-credits (:source-id src))
                                    (get ft-credits country)
                                    {:country country :paid 0M :income (bigdec0 (:amount src))})]
                    [(foreign-dividend-component opts db as-of src
                                                 (assoc ftc :country country))])
                  [(dividend-acumulable-component opts ctx db as-of src)
                   (dividend-adicional-component opts db as-of src)])))
             (remove nil?)
             vec)
        interest-cmps (->> interests
                           (map #(bank-interest-component opts db as-of %))
                           (remove nil?)
                           vec)
        components (vec (concat dividend-cmps interest-cmps))]
    (ptp/tax-return-facts
     {:entity               entity
      :period               period
      :jurisdiction         {:country :mx :authority authority}
      :functional-commodity commodity
      :components           components})))

(defn- corporate-facts
  [db _ctx as-of entity period commodity authority items]
  (let [dividends    (or (:dividends items) [])
        interests    (or (:interest items) [])
        opts         {:commodity commodity :authority authority}
        ;; PJ-to-PJ CUFIN-sourced dividends emit an exempt audit-only
        ;; component each; non-exempt dividends fold into CIT.
        exempt-cmps  (->> dividends
                          (filter cufin-paid?)
                          (map #(corp-pj-to-pj-exempt-component opts as-of %))
                          (remove nil?)
                          vec)
        taxable-divs (->> dividends
                          (remove cufin-paid?)
                          (map (comp bigdec0 :amount))
                          (reduce + 0M))
        interest-sum (->> interests
                          (map (comp bigdec0 :nominal-interest))
                          (reduce + 0M))
        feeder       (corp-cit-feeder-component opts as-of taxable-divs interest-sum)
        components   (vec (cond-> exempt-cmps
                            feeder (conj feeder)))]
    (ptp/tax-return-facts
     {:entity               entity
      :period               period
      :jurisdiction         {:country :mx :authority authority}
      :functional-commodity commodity
      :components           components})))

(defrecord MXInvestmentIncomeTaxProvider [id kind commodity authority statute]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs] :as ctx}]
    (when-not (#{:individual :corporation} kind)
      (throw (ex-info "MX investment-income provider :kind must be :individual or :corporation"
                      {:kind kind})))
    (let [db    (or (:db ctx)
                    (throw (ex-info ":db required in ctx for MX investment-income provider"
                                    {:ctx-keys (keys ctx)})))
          as-of (as-of-from-ctx ctx)
          items (or (:mx-investment-income inputs) {})]
      (case kind
        :individual  (individual-facts db ctx as-of entity period commodity authority items)
        :corporation (corporate-facts  db ctx as-of entity period commodity authority items)))))

;; ============================================================================
;; Constructors
;; ============================================================================

(defn mx-individual-investment-income-provider
  "Build an MX individual investment-income provider. Consumer supplies
   sources via `:inputs :mx-investment-income`. See namespace docstring
   for input shape."
  [{:keys [id commodity authority]
    :or   {id :mx-investment-income-individual
           commodity :MXN
           authority :mx-sat}}]
  (->MXInvestmentIncomeTaxProvider
   id :individual commodity authority
   "LISR Título IV Cap VI + Cap VIII (arts 54, 133-136, 140, 142); art. 5 FTC"))

(defn mx-corporate-investment-income-provider
  "Build an MX corporate investment-income provider. PJ-to-PJ
   CUFIN-sourced dividends emit an exempt audit-only component;
   non-CUFIN dividends + interest fold into CIT base."
  [{:keys [id commodity authority]
    :or   {id :mx-investment-income-corporate
           commodity :MXN
           authority :mx-sat}}]
  (->MXInvestmentIncomeTaxProvider
   id :corporation commodity authority
   "LISR Título II — arts 9, 16 fr. III"))

(defn install-statute!
  "Install the MX investment-income statute (parameters + provisions)
   into `conn`."
  [conn]
  (inv-statute/install! conn))
