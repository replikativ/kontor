(ns kontor.l10n-jp.investment-income-provider
  "JP investment-income tax provider — `PeriodTaxProvider` (ADR-099)
   over the ADR-101 statute-as-data substrate. 

   ## The JP investment-income story

   配当所得 (haitō-shotoku, dividends) and 利子所得 (rishi-shotoku,
   interest) carry up to THREE election choices per source per filing
   year:

   - **申告不要 (no-declaration)** — paying agent's 20.315 % WHT is
     FINAL; the dividend does not enter the return. Default for listed
     shares. The provider emits an INFORMATIONAL component with
     `:liability = :prepaid` (the withholding is the tax).
   - **申告分離課税 (separate self-assessment)** — same 20.315 % rate
     but the dividend joins the listed-securities offset compartment
     (carry-forward 3 yrs). The provider emits a standalone component
     with the listed-share rate, less any carry-in.
   - **総合課税 (aggregation)** — dividend joins ordinary income at
     the 7-band progressive rate; 配当控除 dividend tax credit
     applies. The provider emits no own liability component; instead
     it threads `:pit-base-additions` + `:pit-credits` to the PIT
     provider.

   The 3 % 大口株主 cliff (措置法 §8-4) FORCES the 総合課税 election
   for that source — the provider's election validator rejects any
   other choice for `:jp-listed-major-3%` class.

   ## NISA exemption

   When the consumer-supplied source carries `:exemption-claimed
   :jp-nisa` the provider DROPS the slice entirely — no component
   for it. Mirrors the JP CGT provider's §35 deduction pattern but
   simpler (NISA is binary exempt, no slice).

   ## §95 foreign-tax credit

   Foreign-dividend sources carry `:foreign-tax-paid` (per-country
   yen-equivalent). Per 所得税法 §95 the credit caps at
   `foreign-tax × foreign-income / world-income`; for v1 (per-source
   rather than country-aggregate) we apply the simpler cap: the WHT
   amount, capped at the JP tax on the same source. Excess emerges
   in `:jurisdiction-specific-codes :foreign-tax-carryforward-out`.

   ## Two callable shapes, one provider

   - **`:kind :individual`** — emits the election-lane components
     (申告不要 / 申告分離 / 総合 fan-out).
   - **`:kind :corporation`** — corporate dividends fold into CIT
     via 受取配当等の益金不算入 (法人税法 §23); v1 returns one
     component with `:cit-base-additions [<exclusion>]` per the
     stake ladder (100% / 100% / 50% / 20%). Out-of-scope refinements
     (subsidiary deductible-interest) documented; not implemented.

   ## Statutory base reuse

   The 復興特別所得税 2.1 % surtax is REUSED from the JP CGT
   statute — when this provider's `:pass :national` ctx fires
   `apply-provisions`, the same `JP-FUKKO-§13-reconstruction-surtax`
   provision the CGT provider registered fires here too. The compute-
   fn (`:jp-cgt-reconstruction-surtax`) is registered by
   `kontor.l10n-jp.cgt-provider` at namespace load; we `require` it
   so consumers who only use investment-income still get the
   registration."
  (:require [kontor.l10n-jp.cgt-provider :as _cgt-provider-side-effect]
            [kontor.l10n-jp.investment-income-statute :as inv-statute]
            [kontor.money :as money]
            [kontor.tax.period-tax-provider :as ptp]
            [kontor.tax.statute :as statute]
            [kontor.tax.tax-schedule :as ts]))

;; ============================================================================
;; Constants — closed sets
;; ============================================================================

(def dividend-classes
  "The closed set of `:dividend-class` values a source may carry.
   Each class drives election availability + 配当控除 rate selection."
  #{:jp-listed-non-major          ; default listed-share path
    :jp-listed-major-3%           ; 3% cliff forces 総合
    :unlisted                     ; cannot use 申告分離 / 申告不要
    :j-reit                       ; no 配当控除 even on 総合
    :foreign                      ; no 申告不要 — must declare
    :investment-trust-domestic    ; 5% 配当控除 rate
    :investment-trust-foreign     ; 2.5% 配当控除 rate
    :listed-bond-interest         ; 申告不要 default; 申告分離 elective
    :bank-interest})              ; locked at 申告不要

(def elections
  "The closed set of `:election` values."
  #{:申告不要 :申告分離 :sogo})

(def permitted-elections
  "Per-dividend-class election whitelist. Provider validator rejects
   any election not in the set."
  {:jp-listed-non-major          #{:申告不要 :申告分離 :sogo}
   :jp-listed-major-3%           #{:sogo}             ; the cliff
   :unlisted                     #{:sogo}             ; no listed regime
   :j-reit                       #{:申告不要 :申告分離 :sogo}
   :foreign                      #{:申告分離 :sogo}    ; no 申告不要
   :investment-trust-domestic    #{:申告不要 :申告分離 :sogo}
   :investment-trust-foreign     #{:申告不要 :申告分離 :sogo}
   :listed-bond-interest         #{:申告不要 :申告分離}
   :bank-interest                #{:申告不要}})

(def haitō-kōjo-eligible-classes
  "Classes that receive the 配当控除 dividend tax credit on the 総合
   election. J-REIT + foreign + bank/bond-interest do NOT — see note
   151 §1.3."
  #{:jp-listed-non-major
    :jp-listed-major-3%
    :unlisted
    :investment-trust-domestic
    :investment-trust-foreign})

;; ============================================================================
;; Compute-fn registration — 配当控除 (national + jūmin)
;; ============================================================================

(defn- as-of-from-ctx
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- haitō-kōjo-rate-for
  "Look up the per-asset-class 配当控除 rate at as-of for the
   `dividend slice ≤ ¥10M` tier. Cash dividends use the standard
   national + jūmin rate; trust variants use distinct parameter
   codes3 (trust-domestic = ½ cash; trust-foreign
   = ¼ cash)."
  ^java.math.BigDecimal [db asset-class as-of pass]
  (let [code (case [asset-class pass]
               [:investment-trust-domestic :national] "JP.InvIncome.haitō-kōjo.trust-domestic-rate"
               [:investment-trust-foreign  :national] "JP.InvIncome.haitō-kōjo.trust-foreign-rate"
               [:investment-trust-domestic :local]    "JP.InvIncome.haitō-kōjo.jūmin-trust-domestic-rate"
               [:investment-trust-foreign  :local]    "JP.InvIncome.haitō-kōjo.jūmin-trust-foreign-rate"
               (case pass
                 :national "JP.InvIncome.haitō-kōjo.standard-rate"
                 :local    "JP.InvIncome.haitō-kōjo.jūmin-standard-rate"))]
    (or (statute/parameter-value-at db code as-of) 0M)))

(defn- haitō-kōjo-high-rate-for
  "Look up the > ¥10M high-income-slice 配当控除 rate. Cash dividends
   use the `high-income-rate` (national 5 %, jūmin 1.4 %); trust
   variants have distinct parameter codes (trust-domestic high
   = 2.5 % / 0.7 %; trust-foreign high = 1.25 % / 0.35 %) per
.3."
  ^java.math.BigDecimal [db asset-class as-of pass]
  (let [code (case [asset-class pass]
               [:investment-trust-domestic :national] "JP.InvIncome.haitō-kōjo.trust-domestic-high-rate"
               [:investment-trust-foreign  :national] "JP.InvIncome.haitō-kōjo.trust-foreign-high-rate"
               [:investment-trust-domestic :local]    "JP.InvIncome.haitō-kōjo.jūmin-trust-domestic-high-rate"
               [:investment-trust-foreign  :local]    "JP.InvIncome.haitō-kōjo.jūmin-trust-foreign-high-rate"
               (case pass
                 :national "JP.InvIncome.haitō-kōjo.high-income-rate"
                 :local    "JP.InvIncome.haitō-kōjo.jūmin-high-income-rate"))]
    (or (statute/parameter-value-at db code as-of) 0M)))

(defn- haitō-kōjo-amount
  "The 配当控除 amount given a `dividend-slice` of an `asset-class`
   and a `total-taxable-income` (the income figure that includes the
   dividend). Two-tier per 所得税法 §92:

   - Below threshold: full rate on the dividend slice.
   - Above threshold: full rate on the slice below the threshold;
     half rate on the slice above.

   Returns the BigDecimal credit on the `pass` side (national or
   local)."
  ^java.math.BigDecimal
  [db asset-class ^java.math.BigDecimal dividend-slice
   ^java.math.BigDecimal total-taxable-income as-of pass]
  (let [threshold (or (statute/parameter-value-at
                       db "JP.InvIncome.haitō-kōjo.threshold" as-of)
                      10000000M)
        std-rate  (haitō-kōjo-rate-for db asset-class as-of pass)
        high-rate (haitō-kōjo-high-rate-for db asset-class as-of pass)
        non-div   (- total-taxable-income dividend-slice)
        non-div   (if (neg? non-div) 0M non-div)
        below     (max 0M (- threshold non-div))
        below     (min below dividend-slice)
        above     (max 0M (- dividend-slice below))]
    (+ (* below std-rate)
       (* above high-rate))))

(defn- jp-haitō-kōjo-national
  "Compute-fn registered with `kontor.tax.statute`. Reads ctx for
   `:dividend-slice`, `:asset-class`, and `:inputs
   :total-taxable-income`. Returns the national-side credit amount."
  ^java.math.BigDecimal [ctx]
  (let [db          (:db ctx)
        as-of       (as-of-from-ctx ctx)
        slice       (or (:dividend-slice ctx) 0M)
        asset-class (or (:asset-class ctx) :jp-listed-non-major)
        tti         (or (get-in ctx [:inputs :total-taxable-income]) slice)]
    (haitō-kōjo-amount db asset-class slice tti as-of :national)))

(defn- jp-haitō-kōjo-jūmin
  "Compute-fn registered with `kontor.tax.statute` for the inhabitants
   side of the 配当控除."
  ^java.math.BigDecimal [ctx]
  (let [db          (:db ctx)
        as-of       (as-of-from-ctx ctx)
        slice       (or (:dividend-slice ctx) 0M)
        asset-class (or (:asset-class ctx) :jp-listed-non-major)
        tti         (or (get-in ctx [:inputs :total-taxable-income]) slice)]
    (haitō-kōjo-amount db asset-class slice tti as-of :local)))

(defn register!
  "Register JP investment-income compute-fns with `kontor.tax.statute`.
   Called at namespace load; idempotent."
  []
  (statute/register-compute-fn! :jp-haitō-kōjo-national jp-haitō-kōjo-national)
  (statute/register-compute-fn! :jp-haitō-kōjo-jūmin    jp-haitō-kōjo-jūmin))

(register!)

;; ============================================================================
;; Election validation
;; ============================================================================

(defn- validate-election!
  "Reject any (class, election) pair that violates the permitted-elections
   map."
  [asset-class election]
  (when-not (contains? dividend-classes asset-class)
    (throw (ex-info "JP investment-income: unknown :dividend-class"
                    {:asset-class asset-class
                     :supported   dividend-classes})))
  (when-not (contains? elections election)
    (throw (ex-info "JP investment-income: unknown :election"
                    {:election  election
                     :supported elections})))
  (let [permitted (get permitted-elections asset-class)]
    (when-not (contains? permitted election)
      (throw (ex-info "JP investment-income: election not permitted for this class"
                      {:asset-class asset-class
                       :election    election
                       :permitted   permitted
                       :hint        (cond
                                      (= asset-class :jp-listed-major-3%)
                                      "≥3 % shareholder cliff (措置法 §8-4) forces 総合課税"
                                      (= asset-class :unlisted)
                                      "Unlisted dividends must be aggregated (no listed regime)"
                                      (= asset-class :bank-interest)
                                      "Bank interest is locked at 申告不要 final WHT")})))))

;; ============================================================================
;; Source filtering — NISA / iDeCo exemption
;; ============================================================================

(defn- exempt?
  "True iff the source claims a NISA or iDeCo exemption — provider
   drops the slice (no component emitted, no withholding recorded)."
  [source]
  (let [claims (set (:exemption-claimed source))]
    (or (contains? claims :jp-nisa)
        (contains? claims :jp-ideco))))

;; ============================================================================
;; Per-lane component builders
;; ============================================================================

(defn- listed-rates
  "Look up the listed-dividend rates (national + local) at as-of."
  [db as-of]
  {:national (or (statute/parameter-value-at db "JP.InvIncome.listed.national-rate" as-of) 0.15M)
   :local    (or (statute/parameter-value-at db "JP.InvIncome.listed.local-rate" as-of)    0.05M)})

(defn- fukko-tax-items
  "Fold the JP CGT statute's 復興特別所得税 surtax provision over a
   national-pass ctx. Returns the resolved tax-items list."
  [db ctx]
  (let [scoped-ctx (assoc ctx :db db :pass :national)
        as-of      (as-of-from-ctx ctx)]
    (:tax-items (statute/apply-provisions
                 db {:concept :surtax :jurisdiction :jp :as-of as-of}
                 scoped-ctx))))

(defn- shinkokufuyō-component
  "申告不要 (no-declaration) — INFORMATIONAL component. The paying
   agent already withheld 20.315 %; the provider records the slice +
   the withholding as `:prepaid` = `:liability`. No statute fold
   needed."
  [{:keys [commodity authority]} ctx source]
  (let [db       (:db ctx)
        as-of    (as-of-from-ctx ctx)
        amount   (or (:amount source) 0M)
        {:keys [national local]} (listed-rates db as-of)
        ;; Composite 20.315 % = 15 % nat + 0.315 % 復興 + 5 % local
        nat-gross (* amount national)
        surtax    (* nat-gross 0.021M)
        loc-gross (* amount local)
        total     (+ nat-gross surtax loc-gross)
        withheld  (or (:withheld source) total)]
    {:kind            :investment-income-tax
     :authority       authority
     :base            (money/money amount commodity)
     :schedule        (ts/flat national)
     :gross-liability (money/money total commodity)
     :liability       (money/money total commodity)
     :prepaid         (money/money withheld commodity)
     :regime          :申告不要
     :line-items      [{:line :gross-dividend
                        :label "Gross dividend / interest"
                        :value (money/money amount commodity)}
                       {:line :national-tax
                        :label "National income tax (15 %)"
                        :value (money/money nat-gross commodity)}
                       {:line :reconstruction-surtax
                        :label "復興特別所得税 (2.1 % × national)"
                        :value (money/money surtax commodity)}
                       {:line :local-tax
                        :label "Inhabitants tax (5 %)"
                        :value (money/money loc-gross commodity)}
                       {:line :withheld
                        :label "Withheld by paying agent (final)"
                        :value (money/money withheld commodity)}]
     :jurisdiction-specific-codes {:lane         :申告不要
                                   :asset-class  (:asset-class source)
                                   :source-id    (:source-id source)
                                   :election     :申告不要}}))

(defn- shinkokubunri-component
  "申告分離 (separate self-assessment) — same composite 20.315 %
   rate as 申告不要 BUT with carry-in loss offset and a refundable
   差額 against the withholding. Includes the §95 foreign-tax credit
   for foreign-source slices."
  [{:keys [commodity authority]} ctx source carry-in]
  (let [db          (:db ctx)
        as-of       (as-of-from-ctx ctx)
        amount      (or (:amount source) 0M)
        asset-class (:asset-class source)
        net-base    (max 0M (- amount (or carry-in 0M)))
        {:keys [national local]} (listed-rates db as-of)
        nat-gross   (* net-base national)
        ;; 復興 surtax via the reused JP CGT statute provision
        nat-ctx     (assoc ctx :db db :pass :national
                           :asset-class asset-class
                           :base net-base
                           :element net-base)
        tax-items   (fukko-tax-items db nat-ctx)
        {:keys [liability resolved]}
        (ts/apply-adjustments nat-gross tax-items nat-ctx)
        nat-with-surtax liability
        loc-gross   (* net-base local)
        foreign-paid (or (:foreign-tax-paid source) 0M)
        ;; §95 foreign tax credit — cap at the JP tax on this slice
        jp-tax-on-source (+ nat-with-surtax loc-gross)
        ftc-allowed (min foreign-paid jp-tax-on-source)
        ftc-carry   (max 0M (- foreign-paid ftc-allowed))
        gross-total (+ nat-with-surtax loc-gross)
        liability'  (max 0M (- gross-total ftc-allowed))
        withheld    (or (:withheld source) 0M)
        residual-loss (max 0M (- (or carry-in 0M) amount))]
    {:kind            :investment-income-tax
     :authority       authority
     :base            (money/money net-base commodity)
     :schedule        (ts/flat national)
     :gross-liability (money/money gross-total commodity)
     :liability       (money/money liability' commodity)
     :prepaid         (money/money withheld commodity)
     :regime          :申告分離
     :line-items
     (cond-> [{:line :gross-dividend
               :label "Gross dividend (申告分離)"
               :value (money/money amount commodity)}]
       (pos? (or carry-in 0M))
       (conj {:line :carry-in
              :label "Capital-loss carry-in (jp-listed-securities)"
              :value (money/money (- carry-in) commodity)})
       true
       (into [{:line :taxable-base
               :label "Taxable base (after carry)"
               :value (money/money net-base commodity)}
              {:line :national-tax
               :label "National income tax (15 %)"
               :value (money/money nat-gross commodity)}
              {:line :reconstruction-surtax
               :label "復興特別所得税 (2.1 % × national)"
               :value (money/money (- nat-with-surtax nat-gross) commodity)}
              {:line :local-tax
               :label "Inhabitants tax (5 %)"
               :value (money/money loc-gross commodity)}])
       (pos? foreign-paid)
       (conj {:line :foreign-tax-credit
              :label "§95 外国税額控除 (per-source cap)"
              :value (money/money (- ftc-allowed) commodity)}))
     :surtaxes        (mapv #(select-keys % [:code :label :amount :provenance])
                            resolved)
     :provenance      {:provider-id :jp-investment-income
                       :statute     "措置法 §9-3 / §37-12-2 / 所得税法 §95"
                       :as-of       as-of}
     :jurisdiction-specific-codes
     {:lane                            :申告分離
      :asset-class                     asset-class
      :source-id                       (:source-id source)
      :election                        :申告分離
      :carry-in                        (or carry-in 0M)
      :residual-loss                   residual-loss
      :foreign-tax-paid                foreign-paid
      :foreign-tax-credit-allowed      ftc-allowed
      :foreign-tax-carryforward-out    ftc-carry}}))

(defn- sogo-component
  "総合課税 (aggregation) — emits NO own liability component;
   instead returns an informational component carrying:

   - `:pit-base-additions`  the dividend amount the PIT provider folds
     into its base.
   - `:pit-credits`          a 配当控除 credit (national side) the
                              PIT provider folds in its adjustment
                              layer, computed via `apply-provisions`
                              on the §92 provision.
   - `:foreign-tax-credit`   §95 credit threaded similarly.

   The component itself has `:liability = 0` — the actual tax fires
   inside the PIT provider's downstream pass."
  [{:keys [commodity authority]} ctx source]
  (let [db          (:db ctx)
        as-of       (as-of-from-ctx ctx)
        amount      (or (:amount source) 0M)
        asset-class (:asset-class source)
        tti         (or (get-in ctx [:inputs :total-taxable-income]) amount)
        ;; 配当控除 — only fires for eligible classes; we look it up
        ;; via the statute fold so all priority + ambiguity rules
        ;; apply.
        kojo-ctx    (assoc ctx :db db :pass :national
                           :election :sogo
                           :asset-class asset-class
                           :dividend-slice amount)
        national-credit
        (if (contains? haitō-kōjo-eligible-classes asset-class)
          (let [{:keys [tax-items]}
                (statute/apply-provisions
                 db {:concept :non-refundable-credit
                     :jurisdiction :jp :as-of as-of}
                 kojo-ctx)]
            (->> tax-items
                 (filter #(= :jp-haitō-kōjo-national (:code %)))
                 (map :amount)
                 (reduce + 0M)))
          0M)
        local-kojo-ctx (assoc ctx :db db :pass :local
                              :election :sogo
                              :asset-class asset-class
                              :dividend-slice amount)
        local-credit
        (if (contains? haitō-kōjo-eligible-classes asset-class)
          (let [{:keys [tax-items]}
                (statute/apply-provisions
                 db {:concept :non-refundable-credit
                     :jurisdiction :jp :as-of as-of}
                 local-kojo-ctx)]
            (->> tax-items
                 (filter #(= :jp-haitō-kōjo-jūmin (:code %)))
                 (map :amount)
                 (reduce + 0M)))
          0M)
        foreign-paid (or (:foreign-tax-paid source) 0M)
        withheld     (or (:withheld source) 0M)]
    {:kind            :investment-income-tax
     :authority       authority
     :base            (money/money amount commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :liability       (money/zero commodity)
     :prepaid         (money/money withheld commodity)
     :regime          :sogo
     :line-items
     [{:line :gross-dividend
       :label "Gross dividend → PIT base (総合課税)"
       :value (money/money amount commodity)}
      {:line :haitō-kōjo-national
       :label "配当控除 — national tax credit (所得税法 §92)"
       :value (money/money (- national-credit) commodity)}
      {:line :haitō-kōjo-jūmin
       :label "配当控除 — inhabitants tax credit"
       :value (money/money (- local-credit) commodity)}
      {:line :withheld-prepaid
       :label "Withheld by paying agent (becomes prepaid)"
       :value (money/money withheld commodity)}]
     :jurisdiction-specific-codes
     {:lane                :sogo
      :asset-class         asset-class
      :source-id           (:source-id source)
      :election            :sogo
      :pit-base-additions  [amount]
      :pit-credits         (cond-> []
                             (pos? national-credit)
                             (conj {:code     :jp-haitō-kōjo-national
                                    :label    "配当控除 (national)"
                                    :amount   national-credit
                                    :pass     :national})
                             (pos? local-credit)
                             (conj {:code     :jp-haitō-kōjo-jūmin
                                    :label    "配当控除 (inhabitants)"
                                    :amount   local-credit
                                    :pass     :local})
                             (pos? foreign-paid)
                             (conj {:code     :jp-foreign-tax-credit
                                    :label    "§95 外国税額控除"
                                    :amount   foreign-paid
                                    :pass     :national}))}}))

;; ============================================================================
;; Corporate component — 法人税法 §23 受取配当等の益金不算入
;; ============================================================================

;; ⅓ as a BigDecimal — fixed precision to avoid the non-terminating-
;; decimal-expansion trap when dividing 1M / 3M at runtime.
(def ^:private ^java.math.BigDecimal one-third 0.3333333333333333M)

(defn- corp-exclusion-rate
  "Per 法人税法 §23 — domestic-corporate-dividend exclusion ladder
   by ownership stake. Note 151 §1.8."
  ^java.math.BigDecimal [^java.math.BigDecimal stake-pct]
  (cond
    (>= (compare stake-pct 1.00M) 0)      1.00M    ; ≥100 % (kanzen-shihai)
    (>= (compare stake-pct one-third) 0)  1.00M    ; ≥⅓ (kanren-hōjin)
    (>  (compare stake-pct 0.05M) 0)      0.50M    ; >5 % – <⅓
    :else                                 0.20M))  ; ≤5 %

(defn- corp-component
  "Corporate dividend folds into CIT via 受取配当等の益金不算入. The
   provider returns ONE component aggregating ALL domestic-source
   dividends, with the per-source exclusion already applied. Returns
   the residual taxable amount via `:cit-base-additions`."
  [{:keys [commodity authority]} sources]
  (let [taxable-sum
        (reduce +
                0M
                (map (fn [{:keys [amount asset-class stake-pct]}]
                       (let [amt   (or amount 0M)
                             stake (or stake-pct 0M)
                             ;; Foreign dividends are FULLY taxable to a JP corp.
                             excl  (case asset-class
                                     :foreign 0M
                                     (corp-exclusion-rate stake))]
                         (- amt (* amt excl))))
                     sources))]
    {:kind            :investment-income-tax
     :authority       authority
     :base            (money/money taxable-sum commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :liability       (money/zero commodity)
     :prepaid         (money/zero commodity)
     :regime          :jp-corporate
     :line-items      [{:line  :corp-taxable-dividend
                        :label "Net taxable dividend (after §23 exclusion)"
                        :value (money/money taxable-sum commodity)}]
     :jurisdiction-specific-codes {:cit-base-additions [taxable-sum]
                                   :regime             :jp-corporate}}))

;; ============================================================================
;; Provider record
;; ============================================================================

(defrecord JpInvestmentIncomeTaxProvider
           [id authority commodity statute kind]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs] :as ctx}]
    (let [_ (or (:db ctx)
                (throw (ex-info ":db required in ctx for JP investment-income provider"
                                {:ctx-keys (keys ctx)})))
          sources (or (:investment-income-sources inputs) [])
          ;; Filter NISA/iDeCo exempt slices BEFORE classification.
          live    (remove exempt? sources)
          ;; The consumer's election map keyed by source id (per source);
          ;; falls back to the per-class default for missing keys.
          per-source-election
          (fn [src]
            (or (get (:jp-dividend-elections inputs) (:source-id src))
                (get (:jp-dividend-elections inputs) (:asset-class src))
                ;; default per class
                (case (:asset-class src)
                  :bank-interest        :申告不要
                  :listed-bond-interest :申告不要
                  :j-reit               :申告不要
                  :foreign              :申告分離
                  :unlisted             :sogo
                  :jp-listed-major-3%   :sogo
                  :申告不要)))
          opts {:authority authority :commodity commodity}]
      (case kind
        :individual
        (let [classified
              (for [src live
                    :let [election (per-source-election src)]
                    :when (do (validate-election! (:asset-class src) election) true)]
                {:src src :election election})
              ;; carry-in for the 申告分離 path; one bucket
              carry-listed (or (get-in inputs [:capital-loss-carryforward :jp-listed-securities]) 0M)
              ;; track remaining carry per-source as we apply it greedily
              ;; (statutory cascade —.1).
              {components :components}
              (reduce
               (fn [{:keys [carry components]} {:keys [src election]}]
                 (case election
                   :申告不要
                   {:carry carry
                    :components (conj components (shinkokufuyō-component opts ctx src))}

                   :申告分離
                   (let [amt (or (:amount src) 0M)
                         applied (min carry amt)
                         carry'  (- carry applied)
                         cmp     (shinkokubunri-component opts ctx src applied)]
                     {:carry carry' :components (conj components cmp)})

                   :sogo
                   {:carry carry
                    :components (conj components (sogo-component opts ctx src))}))
               {:carry carry-listed :components []}
               classified)]
          (ptp/tax-return-facts
           {:entity               entity
            :period               period
            :jurisdiction         {:country :jp :authority authority}
            :functional-commodity commodity
            :components           components}))

        :corporation
        (let [components (cond-> []
                           (seq live)
                           (conj (corp-component opts live)))]
          (ptp/tax-return-facts
           {:entity               entity
            :period               period
            :jurisdiction         {:country :jp :authority authority}
            :functional-commodity commodity
            :components           components}))

        (throw (ex-info "JP investment-income provider :kind must be :individual or :corporation"
                        {:kind kind}))))))

;; ============================================================================
;; Constructors
;; ============================================================================

(defn jp-individual-investment-income-provider
  "Build a JP individual investment-income provider. The consumer
   supplies dividend / interest sources via `:inputs
   :investment-income-sources` — a vector of maps:

       {:source-id  :ticker-or-uuid
        :asset-class <dividend-classes>
        :amount      <bigdec>           ; gross dividend in JPY
        :withheld    <bigdec>           ; paying-agent WHT
        :foreign-tax-paid <bigdec>      ; §95 input (foreign only)
        :exemption-claimed #{:jp-nisa :jp-ideco}  ; optional}

   The consumer also threads:

       :inputs {:jp-dividend-elections
                {<source-id-or-class> :申告不要 | :申告分離 | :sogo}
                :capital-loss-carryforward
                {:jp-listed-securities <bigdec>}
                :total-taxable-income <bigdec>}  ; for 配当控除 threshold"
  [{:keys [id]}]
  (->JpInvestmentIncomeTaxProvider
   (or id :jp-investment-income-individual)
   :jp-nta :JPY
   "所得税法 §24 / §92 / §95 + 措置法 §8-4 / §9-3 / §37-12-2"
   :individual))

(defn jp-corporate-investment-income-provider
  "Build a JP corporate investment-income provider. Sources carry
   `:stake-pct` (ownership as a decimal) so the 受取配当等の益金不算入
   ladder (法人税法 §23) can be applied per-source. Returns ONE
   component with `:cit-base-additions [<taxable-sum>]` for the
   consumer to thread into the JP CIT provider."
  [{:keys [id]}]
  (->JpInvestmentIncomeTaxProvider
   (or id :jp-investment-income-corporate)
   :jp-nta :JPY
   "法人税法 §23 受取配当等の益金不算入"
   :corporation))

