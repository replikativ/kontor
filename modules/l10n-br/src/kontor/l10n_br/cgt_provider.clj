(ns kontor.l10n-br.cgt-provider
  "BR capital-gains tax provider — `PeriodTaxProvider` (ADR-099) over
   the ADR-102 disposal substrate + ADR-101 statute-as-data.

   ## Two callable shapes, three watertight individual lanes

   The provider exposes `:kind :individual | :corporation`:

   - **Individual** — three lanes, each compartmentalised:
       1. **PF ganho de capital** (Lei 13.259/2016 art. 21) — the
          four-bracket progressive ladder 15 / 17.5 / 20 / 22.5 % on
          real-asset + unlisted-participation disposals. Per-disposal
          R$ 35k/month aggregate pequeno-valor isenção (Lei 9.250/95
          art. 22) for `:asset-class :br-equity-comum` and other
          eligible small-share asset classes. art. 39 residence-
          reinvestment exemption when `:elective-regime :br-residence-
          reinvest` is set on the disposal.
       2. **PF renda variável B3 swing** (Lei 11.033/2004 art. 2 II)
          — flat 15 % on `:asset-class :br-renda-variavel-long`
          gains, with the R$ 20k/month aggregate isenção on the sum
          of the month's swing sale prices (Lei 11.033/2004 art. 3 I).
       3. **PF renda variável B3 day-trade** (Lei 11.033/2004 art. 2
          § 1) — flat 20 % on `:asset-class :br-renda-variavel-day`
          gains. NO monthly isenção.

     Losses carry forward only WITHIN the same lane (IN-RFB-1585/2015
     art. 64). The provider reads
     `:inputs :capital-loss-carryforward {:br-ganho-capital ...
                                          :br-renda-variavel-day ...
                                          :br-renda-variavel-long ...}`
     — three BR lanes, three independent buckets.

     IRRF dedo-duro prepayments (broker withholding — 0.005 % swing,
     1 % day-trade) ride `:inputs :br-irrf-withheld
     {:swing <bigdec> :day <bigdec>}` and feed each lane's `:prepaid`
.

   - **Corporation** — net gain folds into IRPJ + CSLL via
     `:cit-base-additions` (`:tax-unit :tax-regime :lucro-real`).
     `:lucro-presumido` raises informative error in v1 (different
     presumption base).

   ## PL 1087/2025 (Lei 15.270/2025) high-earner minimum tax

   Per.5, the new 10 % IRPFM minimum tax effective
   2026-01-01 EXPLICITLY CARVES OUT capital gains taxed under the
   PF ladder of §1.1. Substrate impact: ZERO on this provider; the
   IRPFM is a separate minimum-tax provider that consumes the
   dividend ledger, not the disposal log.

   ## Note on schedule

   The four-bracket PF ladder is annual (Receita walks the ladder
   against the year-to-date gain running-total per
   IN-RFB-1500/2014 art. 138 § 4); v1 sums all eligible
   ganho-capital lane disposals in the period and walks the bracket
   once. A consumer aggregating across an entire calendar year gets
   the correct cumulative result; a sub-annual period under-counts
   the ladder walk for taxpayers above R$ 5M — documented gap.

   ## DisposalProvider

   The provider depends on the kernel `DisposalProvider` protocol; a
   consumer without disposals wires `empty-provider` and gets zero
   components."
  (:require [kontor.provider.disposal-provider :as ds]
            [kontor.l10n-br.cgt-statute :as cgt-statute]
            [kontor.money :as money]
            [kontor.tax.period-tax-provider :as ptp]
            [kontor.tax.statute :as statute]
            [kontor.tax.tax-schedule :as ts]))

;; ============================================================================
;; Constants — the closed lane keywords + asset-class routing
;; ============================================================================

(def lanes
  "The closed set of BR CGT lanes a disposal classifies into. Mirrors
.3 + ADR-102 `:loss-bucket` convention."
  #{:br-ganho-capital            ; PF Lane A — four-bracket ladder
    :br-renda-variavel-long      ; PF Lane B swing-trade — 15 %, R$20k isento
    :br-renda-variavel-day       ; PF Lane B day-trade   — 20 %, NO isento
    :br-corp-net-capital})       ; PJ Lane C — folds to IRPJ+CSLL via CIT

;; Which BR asset-classes land in which lane. Open vocabulary — the
;; kernel ships these mappings; a consumer can extend by passing
;; `:asset-class-overrides {:br-foo :br-ganho-capital}` to the provider
;; constructor.
(def default-asset-class->lane
  "Default BR `:kontor.disposal/asset-class` → lane mapping (open
   vocabulary). The keys in this map are the canonical
   BR-namespaced asset classes; a consumer's custom asset class falls
   into `:br-ganho-capital` by default (the conservative — non-bolsa
   — lane) when not in this map."
  {:br-real-estate-residencial :br-ganho-capital
   :br-real-estate-comercial   :br-ganho-capital
   :br-real-estate-rural       :br-ganho-capital
   :br-unlisted-share          :br-ganho-capital
   :br-foreign-currency-cash   :br-ganho-capital
   :br-foreign-asset           :br-ganho-capital
   :br-other-movable           :br-ganho-capital
   :br-equity-comum            :br-ganho-capital
   :br-renda-variavel-long     :br-renda-variavel-long
   :br-listed-equity-swing     :br-renda-variavel-long  ; canonical alias
   :br-renda-variavel-day      :br-renda-variavel-day
   :br-listed-equity-day       :br-renda-variavel-day})

(def lucro-regimes
  "PJ tax regime enum carried in `:tax-unit :tax-regime`. v1 supports
   only :lucro-real; :lucro-presumido raises informative error
   (different presumption base —.3)."
  #{:lucro-real :lucro-presumido})

;; ============================================================================
;; Helpers — gain, classification, exemption-claimed predicates
;; ============================================================================

(defn- realized-gain
  "Gain (positive) or loss (negative) on one disposal in the proceeds
   commodity: `proceeds − basis − rollover-amount`."
  ^java.math.BigDecimal [disposal]
  (let [p (or (:kontor.disposal/proceeds-amount disposal) 0M)
        b (or (:kontor.disposal/basis-amount disposal) 0M)
        r (or (:kontor.disposal/rollover-amount disposal) 0M)]
    (- p b r)))

(defn- proceeds
  ^java.math.BigDecimal [disposal]
  (or (:kontor.disposal/proceeds-amount disposal) 0M))

(defn- claims-residence-reinvest?
  "True iff the disposal claims the art. 39 residence-reinvestment
   exemption (Lei 11.196/2005 art. 39). Reads either
   `:kontor.disposal/elective-regime` or `:kontor.disposal/exemption-claimed` for
   the closed BR keywords."
  [disposal]
  (let [reg (set (:kontor.disposal/elective-regime disposal))
        exm (set (:kontor.disposal/exemption-claimed disposal))]
    (boolean (or (contains? reg :br-residence-reinvest)
                 (contains? exm :br-art-39-residence-reinvest)))))

(defn- as-of-from-ctx
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- disposal-month
  "Return the `[year month]` pair for a disposal — the bucket key the
   monthly aggregate isenção folds against."
  [disposal]
  (let [d (:kontor.disposal/disposed-on disposal)
        cal (doto (java.util.Calendar/getInstance)
              (.setTime d))]
    [(.get cal java.util.Calendar/YEAR)
     (.get cal java.util.Calendar/MONTH)]))

(defn- classify
  "Classify one disposal into a BR CGT lane.

   For Lane A (`:br-ganho-capital`), the art. 39 residence-reinvest
   exemption is applied here: when claimed AND a `:rollover-amount`
   is set, the gain is proportionally reduced — `gain × (1 −
   rollover/proceeds)`. Per.2M sale
   with R$ 900k reinvested → 75 % exempt → 25 % taxable.

   Returns `{:lane <kw> :gain <bigdec> :proceeds <bigdec>
            :exempt-by-reinvest? <bool> :disposal <map>}`. The
   provider's monthly-aggregate pequeno-valor fold runs in a later
   pass."
  [disposal {:keys [asset-class->lane]}]
  (let [g           (realized-gain disposal)
        p           (proceeds disposal)
        ac          (:kontor.disposal/asset-class disposal)
        lane        (or (get asset-class->lane ac)
                        ;; default: real-asset Lane A
                        :br-ganho-capital)
        reinvest?   (and (= lane :br-ganho-capital)
                         (claims-residence-reinvest? disposal))
        rollover    (or (:kontor.disposal/rollover-amount disposal) 0M)
        reinvest-fraction (when reinvest?
                            (if (pos? p) (/ rollover p) 0M))
        ;; A reinvest exemption proportionally reduces the gain. Note
        ;; the gain itself was computed pre-rollover above; we re-add
        ;; the rollover into the proceeds-minus-basis before applying
        ;; the proportion (a reinvest-exempt sale shouldn't doubly
        ;; deduct the rollover slice).
        pre-rollover-gain (+ g rollover)
        taxable     (cond
                      reinvest? (* pre-rollover-gain (- 1M reinvest-fraction))
                      :else     g)]
    {:lane                lane
     :gain                taxable
     :proceeds            p
     :asset-class         ac
     :exempt-by-reinvest? reinvest?
     :reinvest-fraction   (or reinvest-fraction 0M)
     :disposal            disposal}))

;; ============================================================================
;; Monthly aggregate isenções — provider-side fold
;; ============================================================================

(defn- apply-monthly-aggregate-isenção
  "Apply a monthly aggregate price-cap exemption to `classified` —
   group by [year month], sum the `:proceeds`; if the monthly sum is
   <= `cap`, every disposal in that month is fully exempt; otherwise
   none of them are (the IN-RFB-1585/2015 art. 56 § 2 position — the
   exemption is binary at the monthly aggregate level, not a per-
   excess slice).

   Returns the same shape, with `:exempt-by-monthly-isenção?` added
   per disposal."
  [classified ^java.math.BigDecimal cap]
  (let [by-month (group-by disposal-month (map :disposal classified))
        monthly-totals (reduce-kv
                        (fn [acc month ds]
                          (assoc acc month (reduce + 0M (map proceeds ds))))
                        {}
                        by-month)]
    (mapv (fn [{:keys [disposal] :as c}]
            (let [month (disposal-month disposal)
                  monthly-total (get monthly-totals month 0M)
                  exempt? (<= monthly-total cap)]
              (assoc c
                     :monthly-total monthly-total
                     :exempt-by-monthly-isenção? exempt?)))
          classified)))

(defn- pequeno-valor-eligible?
  "Per.1.1, the R$ 35k pequeno-valor isenção (Lei 9.250/95
   art. 22) applies to small-share sales by individuals
   (`:br-equity-comum`) as the task spec singles out. Real-estate
   sales are NOT covered — those use art. 23 (sole-residence R$ 440k,
   once per 5 years) or art. 39 (residence reinvest), both narrower."
  [{:keys [asset-class]}]
  (= asset-class :br-equity-comum))

(defn- sum-lane-gain
  "Sum the post-exemption gains in a lane. Negative gains (losses)
   net within-lane; the result is floored at 0 (losses do not refund
   tax, they carry forward).

   The classifier has ALREADY scaled the gain by the reinvest fraction
   for art. 39 claims (a 75 %-reinvest disposal carries a 25 %-of-gain
   `:gain`), so we keep those entries; we drop only the monthly-
   aggregate-isenção entries (binary per-month flag)."
  ^java.math.BigDecimal [classified lane]
  (reduce + 0M
          (->> classified
               (filter #(= lane (:lane %)))
               (remove :exempt-by-monthly-isenção?)
               (map :gain))))

(defn- net-lane
  "Net a lane's gain against the BR carry-in for that lane (a
   non-negative BigDecimal representing a carryforward LOSS available
   to offset gains). Returns the non-negative net amount."
  ^java.math.BigDecimal [^java.math.BigDecimal lane-gain
                        ^java.math.BigDecimal carry-in]
  (max 0M (- lane-gain (or carry-in 0M))))

;; ============================================================================
;; Schedule assembly — read parameter values + brackets
;; ============================================================================

(defn- ganho-capital-schedule
  "Read the four-bracket ladder out of `:parameter-bracket` rows."
  [db ^java.util.Date as-of]
  (ts/progressive
   (statute/parameter-brackets-at db "BR.CGT.PF.ganho-capital-brackets" as-of)))

(defn- swing-rate
  ^java.math.BigDecimal [db ^java.util.Date as-of]
  (statute/parameter-value-at db "BR.CGT.PF.renda-variavel.swing-rate" as-of))

(defn- day-rate
  ^java.math.BigDecimal [db ^java.util.Date as-of]
  (statute/parameter-value-at db "BR.CGT.PF.renda-variavel.day-rate" as-of))

(defn- swing-cap
  ^java.math.BigDecimal [db ^java.util.Date as-of]
  (statute/parameter-value-at db "BR.CGT.PF.bolsa-swing-cap" as-of))

(defn- pequeno-valor-cap
  ^java.math.BigDecimal [db ^java.util.Date as-of]
  (statute/parameter-value-at db "BR.CGT.PF.pequeno-valor-cap" as-of))

;; ============================================================================
;; Components — individual (three lanes)
;; ============================================================================

(defn- ganho-capital-component
  "Lane A — PF ganho de capital — four-bracket progressive on the
   net gain after the within-lane carry-in."
  [{:keys [commodity authority]} ctx classified
   ^java.math.BigDecimal net-gain]
  (let [db        (:db ctx)
        as-of     (as-of-from-ctx ctx)
        schedule  (ganho-capital-schedule db as-of)
        gross     (ts/apply-schedule schedule net-gain)
        line-items (->> classified
                        (filter #(= :br-ganho-capital (:lane %)))
                        (mapv (fn [c]
                                {:line  :ganho-capital-disposal
                                 :label (str (:kontor.disposal/external-id (:disposal c))
                                             (cond
                                               (:exempt-by-reinvest? c)
                                               " (art. 39 reinvest exempt)"
                                               (:exempt-by-monthly-isenção? c)
                                               " (art. 22 pequeno-valor exempt)"
                                               :else ""))
                                 :value (money/money (:gain c) commodity)})))]
    {:kind            :capital-gains-tax
     :authority       authority
     :base            (money/money net-gain commodity)
     :schedule        schedule
     :gross-liability (money/money gross commodity)
     :liability       (money/money gross commodity)
     :prepaid         (money/zero commodity)
     :line-items      (into [{:line :lane-net
                              :label "Net PF ganho-capital (post carry-in)"
                              :value (money/money net-gain commodity)}
                             {:line :lane-tax
                              :label "Tax at four-bracket ladder"
                              :value (money/money gross commodity)}]
                            line-items)
     :jurisdiction-specific-codes {:lane :br-ganho-capital
                                   :darf 4600}}))

(defn- swing-component
  "Lane B-swing — flat 15 % on the net swing gain after carry-in.
   IRRF prepayment (`:inputs :br-irrf-withheld :swing`) flows into
   `:prepaid`."
  [{:keys [commodity authority]} ctx ^java.math.BigDecimal net-gain]
  (let [db        (:db ctx)
        as-of     (as-of-from-ctx ctx)
        rate      (swing-rate db as-of)
        gross     (* net-gain rate)
        irrf      (or (get-in ctx [:inputs :br-irrf-withheld :swing]) 0M)]
    {:kind            :capital-gains-tax
     :authority       authority
     :base            (money/money net-gain commodity)
     :schedule        (ts/flat rate)
     :gross-liability (money/money gross commodity)
     :liability       (money/money gross commodity)
     :prepaid         (money/money irrf commodity)
     :line-items      [{:line :swing-net
                        :label "Net swing-trade gain (post R$ 20k isenção + carry-in)"
                        :value (money/money net-gain commodity)}
                       {:line :swing-tax
                        :label "Tax at 15 % swing rate"
                        :value (money/money gross commodity)}
                       {:line :swing-irrf
                        :label "Less: IRRF dedo-duro prepaid"
                        :value (money/money irrf commodity)}]
     :jurisdiction-specific-codes {:lane :br-renda-variavel-long
                                   :darf 6015}}))

(defn- day-component
  "Lane B-day — flat 20 % on the net day-trade gain after carry-in.
   IRRF prepayment (`:inputs :br-irrf-withheld :day`) flows into
   `:prepaid`. NO monthly isenção."
  [{:keys [commodity authority]} ctx ^java.math.BigDecimal net-gain]
  (let [db        (:db ctx)
        as-of     (as-of-from-ctx ctx)
        rate      (day-rate db as-of)
        gross     (* net-gain rate)
        irrf      (or (get-in ctx [:inputs :br-irrf-withheld :day]) 0M)]
    {:kind            :capital-gains-tax
     :authority       authority
     :base            (money/money net-gain commodity)
     :schedule        (ts/flat rate)
     :gross-liability (money/money gross commodity)
     :liability       (money/money gross commodity)
     :prepaid         (money/money irrf commodity)
     :line-items      [{:line :day-net
                        :label "Net day-trade gain (post carry-in)"
                        :value (money/money net-gain commodity)}
                       {:line :day-tax
                        :label "Tax at 20 % day-trade rate"
                        :value (money/money gross commodity)}
                       {:line :day-irrf
                        :label "Less: IRRF dedo-duro prepaid"
                        :value (money/money irrf commodity)}]
     :jurisdiction-specific-codes {:lane :br-renda-variavel-day
                                   :darf 6015}}))

;; ============================================================================
;; Components — corporation
;; ============================================================================

(defn- corp-net-component
  "Lane C — PJ Lucro Real — net capital gain folds into IRPJ + CSLL
   via `:cit-base-additions`. The consumer composes by reading
   `:cit-base-additions` from this component into the CIT provider's
   `:base-transform`."
  [{:keys [commodity authority]} ^java.math.BigDecimal net-cap]
  {:kind            :capital-gains-tax
   :authority       authority
   :base            (money/money net-cap commodity)
   :schedule        nil
   :gross-liability (money/zero commodity)
   :liability       (money/zero commodity)
   :prepaid         (money/zero commodity)
   :line-items      [{:line :net-capital
                      :label "PJ net capital gain (folds to IRPJ + CSLL)"
                      :value (money/money net-cap commodity)}]
   :jurisdiction-specific-codes {:cit-base-additions [net-cap]
                                 :lane :br-corp-net-capital
                                 :regime :lucro-real}})

;; ============================================================================
;; The provider
;; ============================================================================

(defrecord BRCapitalGainsTaxProvider
           [id source authority commodity statute kind asset-class->lane]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs tax-unit] :as ctx}]
    (let [db          (or (:db ctx)
                          (throw (ex-info ":db required in ctx for BR CGT provider"
                                          {:ctx-keys (keys ctx)})))
          as-of       (as-of-from-ctx ctx)
          disposals   (ds/disposals-facts source {:entity entity :period period})
          classified  (mapv #(classify % {:asset-class->lane asset-class->lane})
                            disposals)
          carry-in    (or (:capital-loss-carryforward inputs) {})
          opts        {:authority authority :commodity commodity}
          components
          (case kind
            :individual
            ;; Apply monthly aggregate isenções where applicable.
            (let [;; --- Lane A: pequeno-valor R$ 35k folds across :br-equity-comum
                  pv-cap     (pequeno-valor-cap db as-of)
                  lane-a     (->> classified
                                  (filter #(= :br-ganho-capital (:lane %))))
                  ;; Within Lane A, only the pequeno-valor-eligible subset
                  ;; participates in
                  ;; the R$ 35k aggregate.
                  pv-eligible (filter pequeno-valor-eligible? lane-a)
                  pv-other    (remove pequeno-valor-eligible? lane-a)
                  pv-flagged  (apply-monthly-aggregate-isenção pv-eligible pv-cap)
                  ;; --- Lane B-swing: R$ 20k folds across all swing disposals
                  swing-cap-bd (swing-cap db as-of)
                  lane-b-swing (filter #(= :br-renda-variavel-long (:lane %))
                                       classified)
                  swing-flagged (apply-monthly-aggregate-isenção
                                 lane-b-swing swing-cap-bd)
                  ;; --- Lane B-day: NO isenção
                  lane-b-day   (filter #(= :br-renda-variavel-day (:lane %))
                                       classified)
                  ;; Re-merge with isenção flags
                  merged      (vec (concat pv-flagged
                                           (map #(assoc % :monthly-total nil
                                                        :exempt-by-monthly-isenção? false)
                                                pv-other)
                                           swing-flagged
                                           (map #(assoc % :monthly-total nil
                                                        :exempt-by-monthly-isenção? false)
                                                lane-b-day)))
                  ;; Lane sums (post-exemption, in-lane net)
                  a-sum       (sum-lane-gain merged :br-ganho-capital)
                  swing-sum   (sum-lane-gain merged :br-renda-variavel-long)
                  day-sum     (sum-lane-gain merged :br-renda-variavel-day)
                  ;; Apply within-lane carry-ins
                  a-net       (net-lane a-sum     (:br-ganho-capital carry-in))
                  swing-net   (net-lane swing-sum (:br-renda-variavel-long carry-in))
                  day-net     (net-lane day-sum   (:br-renda-variavel-day carry-in))
                  ;; Build components; emit a component only when the
                  ;; lane had ACTIVITY (some disposal), even if exempt
                  ;; — so the audit trail surfaces the isenção.
                  has-a?      (boolean (seq lane-a))
                  has-swing?  (boolean (seq lane-b-swing))
                  has-day?    (boolean (seq lane-b-day))
                  a-cmp       (when has-a?
                                (ganho-capital-component opts ctx merged a-net))
                  swing-cmp   (when has-swing?
                                (swing-component opts ctx swing-net))
                  day-cmp     (when has-day?
                                (day-component opts ctx day-net))]
              (->> [a-cmp swing-cmp day-cmp]
                   (remove nil?)
                   vec))

            :corporation
            (let [regime (or (:tax-regime tax-unit) :lucro-real)]
              (when-not (contains? lucro-regimes regime)
                (throw (ex-info "BR CGT corporate :tax-regime must be in #{:lucro-real :lucro-presumido}"
                                {:regime regime})))
              (when (= regime :lucro-presumido)
                (throw (ex-info
                        (str "BR CGT v1 supports only :lucro-real. "
                             ":lucro-presumido has a different presumption "
                             "base — file an extension "
                             "request with the maintainer.")
                        {:regime regime})))
              (let [net-cap (- (reduce + 0M (map :gain classified))
                               (or (:br-corp-net-capital carry-in) 0M))
                    net-cap' (max 0M net-cap)]
                (if (pos? net-cap')
                  [(corp-net-component opts net-cap')]
                  [])))

            (throw (ex-info "BR CGT provider :kind must be :individual or :corporation"
                            {:kind kind})))]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :br :authority authority}
        :functional-commodity commodity
        :components           components}))))

;; ============================================================================
;; Constructors
;; ============================================================================

(defn br-individual-cgt-provider
  "Build a BR individual CGT provider. Required: `:source` — a
   `DisposalProvider` (kernel protocol). Optional:
     `:asset-class-overrides` — `{<keyword> <lane>}` map extending
                                `default-asset-class->lane`."
  [{:keys [source id asset-class-overrides]}]
  (when-not source (throw (ex-info ":source DisposalProvider required" {})))
  (->BRCapitalGainsTaxProvider
   (or id :br-cgt-individual) source :br-rfb :BRL
   (str "Lei 13.259/2016 art. 21; Lei 9.250/95 art. 22; Lei 11.033/2004 "
        "art. 2-3; IN-RFB-1500/2014; IN-RFB-1585/2015")
   :individual
   (merge default-asset-class->lane (or asset-class-overrides {}))))

(defn br-corporate-cgt-provider
  "Build a BR corporate CGT provider. Required: `:source`. v1 supports
   only Lucro Real (the consumer signals via `:tax-unit :tax-regime
   :lucro-real`); `:lucro-presumido` raises informative error."
  [{:keys [source id asset-class-overrides]}]
  (when-not source (throw (ex-info ":source DisposalProvider required" {})))
  (->BRCapitalGainsTaxProvider
   (or id :br-cgt-corporate) source :br-rfb :BRL
   (str "Lei 9.249/95 art. 17, 21 (PJ Lucro Real CGT folds into "
        "IRPJ 15 % + 10 % adicional + CSLL 9 % = 34 % combined)")
   :corporation
   (merge default-asset-class->lane (or asset-class-overrides {}))))

