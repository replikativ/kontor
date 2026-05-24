(ns kontor.l10n-us.cgt-provider
  "US capital-gains tax provider — `PeriodTaxProvider` (ADR-099) over
   the ADR-102 disposal substrate + ADR-101 statute-as-data. Research
   note 112.

   ## Two callable shapes, one provider

   Individuals and corporations have structurally different CGT under
   US law (note 112 §1):

   - **Individual** — short-term gain flows into ordinary income at
     IRC §1 marginal rates; long-term gain has its own §1(h) 0/15/20
     bracket schedule (taxable-income-threshold-conditioned by filing
     status); §1250 unrecaptured depreciation gain capped at 25 %;
     §1411 NIIT (3.8 %) surtaxes the net investment income above MAGI
     thresholds; §1211(b) caps capital-loss-against-ordinary at
     $3 000 /yr (indefinite carryforward of the excess).

   - **Corporation** — net capital gain is just ordinary income at the
     §11 21 % rate; §1212(a) quarantines capital losses (3 yr back /
     5 yr forward); §1245 / §1250 recapture splits depreciation off as
     ordinary income (note 112 §1.2).

   Both shapes are reached via `:kind :individual | :corporation` on
   the provider. The provider returns a `TaxReturnFacts` whose
   component layout differs per kind:

   - individual: up to FOUR components — ST cap gain (folded into PIT
     via `:pit-base-additions`), LT cap gain (own §1(h) schedule),
     §1250 unrecaptured (own 25 % flat), NIIT (a `:surtax` registered
     in `kontor.statute`).
   - corporation: ONE component — net cap gain (folded into CIT base
     via `:cit-base-additions`); §1245/§1250 ordinary recapture goes
     into `:pit-/cit-base-additions` as a separate code.

   ## Composition with the CIT/PIT provider

   The consumer composes both providers explicitly: it asks the CGT
   provider for `TaxReturnFacts`, reads `:jurisdiction-specific-codes
   {:cit-base-additions :pit-base-additions}` from each component,
   and threads them as `:inputs :base-transform` to the CIT/PIT
   provider on the same period. No new substrate operator — mirrors
   `kontor.sole-proprietor` (ADR-100) between business-net and PIT.

   ## DisposalSource

   The provider depends on the kernel `DisposalSource` protocol
   (`src/kontor/disposal_source.clj`); a consumer without disposals
   wires `kontor.disposal-source/empty-source` and gets zero
   components."
  (:require [kontor.disposal-source :as ds]
            [kontor.l10n-us.cgt-statute :as cgt-statute]
            [kontor.money :as money]
            [kontor.period-tax-provider :as ptp]
            [kontor.statute :as statute]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; Constants — the closed lane keywords
;; ============================================================================

(def lanes
  "The closed set of US CGT lanes a disposal classifies into.
   :st                  — short-term capital gain (≤1 yr) → ordinary
   :lt                  — long-term capital gain (>1 yr) → §1(h)
   :§1250-unrecaptured  — real-property depreciation (individuals 25 %)
   :§1245-recapture     — personal-property depreciation → ordinary
   :§1250-ordinary      — corporate real-property excess depreciation"
  #{:st :lt :§1250-unrecaptured :§1245-recapture :§1250-ordinary})

(def filing-statuses
  "Closed set of US filing statuses (PIT-aligned)."
  #{:single :mfj :mfs :hoh})

;; ============================================================================
;; Classification — disposal → lane (provider-internal)
;; ============================================================================

(defn- days-between
  "Whole days between two `java.util.Date` instants (Long)."
  ^long [^java.util.Date a ^java.util.Date b]
  (long (/ (- (.getTime b) (.getTime a))
           (* 1000 60 60 24))))

(defn- long-term?
  "True iff held strictly more than the §1222 holding-period cutoff."
  [disposal cutoff-days]
  (let [acq (:disposal/acquired-on disposal)
        dis (:disposal/disposed-on disposal)]
    (and acq dis (> (days-between acq dis) cutoff-days))))

(defn- depreciation-taken
  "Non-negative BigDecimal — the depreciation-taken amount, defaulting
   to 0 when absent."
  ^java.math.BigDecimal [disposal]
  (or (:disposal/depreciation-taken-amount disposal) 0M))

(defn- realized-gain
  "Gain (positive) or loss (negative) on one disposal, in the proceeds
   commodity: `proceeds − basis − rollover-amount`."
  ^java.math.BigDecimal [disposal]
  (let [p (or (:disposal/proceeds-amount disposal) 0M)
        b (or (:disposal/basis-amount disposal) 0M)
        r (or (:disposal/rollover-amount disposal) 0M)]
    (- p b r)))

(defn- classify
  "Classify one disposal into one or more US CGT lane contributions,
   returning a VECTOR of `{:lane <kw> :gain <bigdec>
   :recapture-ordinary <bigdec> :disposal <map>}` entries. A single
   disposal can contribute to MULTIPLE lanes — e.g. a real-property
   sale with depreciation splits the gain into the §1250-unrecaptured
   slice (capped at depreciation taken) AND a residual that runs
   through the §1(h) brackets at LT rates (IRC §1(h)(6)(A) +
   Pub 544).

   The classification is conservative: when `:depreciation-taken` is
   absent or zero, the whole gain stays in the :st / :lt lane.

   Lane mechanics per kind / asset class:
   - **Personal property §1245** (asset-class :us-personal-property-§1245):
     `min(gain, dep-taken)` → :ordinary-recapture lane (rides
     :recapture-ordinary, folds to PIT/CIT base). Residual stays
     in :st or :lt by holding period.
   - **Individual real property §1250** (asset-class :us-real-property,
     kind :individual): `min(gain, dep-taken)` → :§1250-unrecaptured
     at 25 % cap rate (IRC §1(h)(6)(A) — the part of LT gain 'due to
     depreciation'). Residual `gain − dep` flows to :lt at §1(h)
     0/15/20 brackets. Per Pub 544 ch. 3.
   - **Corporation real property §1250** (corporation kind): for v1,
     no recapture split — straight-line depreciation stays in the
     capital lane; accelerated-over-straight-line would be ordinary
     (deferred — would need consumer-supplied accelerated portion)."
  [disposal {:keys [kind cutoff-days]}]
  (let [g            (realized-gain disposal)
        dep-taken    (depreciation-taken disposal)
        long?        (long-term? disposal cutoff-days)
        asset-class  (:disposal/asset-class disposal)
        positive?    (pos? g)
        residual-lane (if long? :lt :st)
        base         {:disposal disposal :recapture-ordinary 0M}]
    (cond
      ;; §1245 personal property — depreciation slice ordinary; rest
      ;; capital by holding period.
      (and positive? (= asset-class :us-personal-property-§1245))
      (let [recap    (min dep-taken g)
            residual (- g recap)]
        (cond-> []
          (pos? recap)
          (conj (assoc base :lane :ordinary-recapture :gain 0M
                       :recapture-ordinary recap))
          (pos? residual)
          (conj (assoc base :lane residual-lane :gain residual))))

      ;; §1250 individual real property — unrecaptured slice at 25 %;
      ;; residual at §1(h) brackets per IRC §1(h)(6)(A).
      (and positive?
           (= kind :individual)
           (= asset-class :us-real-property)
           long?
           (pos? dep-taken))
      (let [unrecap  (min dep-taken g)
            residual (- g unrecap)]
        (cond-> [(assoc base :lane :§1250-unrecaptured :gain unrecap)]
          (pos? residual)
          (conj (assoc base :lane :lt :gain residual))))

      ;; Anything else (corp real property, plain capital, losses): the
      ;; whole gain (or loss) goes in :st or :lt.
      :else
      [(assoc base :lane residual-lane :gain g)])))

;; ============================================================================
;; Compute-fn registration — §1411 NIIT
;; ============================================================================

(defn- as-of-from-ctx
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- us-niit
  "§1411 NIIT — 3.8 % surtax on net investment income above MAGI
   threshold (by filing status). Late-bound to the running tax via
   apply-adjustments, but the amount is base-driven (NII × rate) —
   so we ignore `:running` and return the BigDecimal directly."
  [ctx]
  (let [db        (:db ctx)
        as-of     (as-of-from-ctx ctx)
        nii       (or (get-in ctx [:inputs :net-investment-income]) 0M)
        status    (or (get-in ctx [:tax-unit :filing-status]) :single)
        threshold-code (case status
                         :mfj "US.CGT.§1411.threshold-mfj"
                         :mfs "US.CGT.§1411.threshold-mfs"
                         "US.CGT.§1411.threshold-single")
        threshold (statute/parameter-value-at db threshold-code as-of)
        magi      (or (get-in ctx [:inputs :magi]) 0M)
        excess    (max 0M (- magi threshold))
        taxable   (min nii excess)
        rate      (statute/parameter-value-at db "US.CGT.§1411.rate" as-of)]
    (fn [_ctx-w-running] (* taxable rate))))

(defn register!
  "Register US-specific compute-fns with `kontor.statute`. Called at
   namespace load; idempotent."
  []
  (statute/register-compute-fn! :us-niit us-niit))

(register!)

;; ============================================================================
;; LT bracket assembly — read per-filing-status parameters → schedule
;; ============================================================================

(defn- lt-schedule
  "Assemble the §1(h) 0/15/20 progressive-bracket schedule for the
   given filing status by reading the per-status thresholds + the
   three universal rates from the parameter table."
  [db ^java.util.Date as-of filing-status]
  (let [param   #(statute/parameter-value-at db % as-of)
        [t01 t12] (case filing-status
                    :mfj [(param "US.CGT.LT.threshold-mfj-0to15")
                          (param "US.CGT.LT.threshold-mfj-15to20")]
                    :mfs [(param "US.CGT.LT.threshold-mfs-0to15")
                          (param "US.CGT.LT.threshold-mfs-15to20")]
                    :hoh [(param "US.CGT.LT.threshold-hoh-0to15")
                          (param "US.CGT.LT.threshold-hoh-15to20")]
                    [(param "US.CGT.LT.threshold-single-0to15")
                     (param "US.CGT.LT.threshold-single-15to20")])
        r0 (param "US.CGT.LT.rate-0")
        r1 (param "US.CGT.LT.rate-15")
        r2 (param "US.CGT.LT.rate-20")]
    (ts/progressive
     [{:upper t01 :rate r0}
      {:upper t12 :rate r1}
      {:upper nil :rate r2}])))

;; ============================================================================
;; Lane netting + carry-in
;; ============================================================================

(defn- net-lane
  "Net the gains/losses in a lane against the supplied carry-in (a
   BigDecimal — positive means a carryforward LOSS available to offset
   gains). Returns the net amount (positive = gain after carry; 0 if
   carry > gain)."
  ^java.math.BigDecimal [^java.math.BigDecimal lane-gain ^java.math.BigDecimal carry-in]
  (max 0M (- lane-gain (or carry-in 0M))))

(defn- sum-recapture
  "Sum the :recapture-ordinary slices across the classified disposals."
  ^java.math.BigDecimal [classified]
  (reduce + 0M (map :recapture-ordinary classified)))

(defn- sum-lane
  ^java.math.BigDecimal [classified lane]
  (reduce + 0M (map :gain (filter #(= lane (:lane %)) classified))))

;; ============================================================================
;; Components — individual
;; ============================================================================

(defn- st-component
  "Individual ST component — flows into PIT base (ordinary income).
   The component has NO own schedule; the consumer composes with the
   PIT provider by reading :pit-base-additions."
  [{:keys [commodity authority]} ^java.math.BigDecimal net-st]
  {:kind            :capital-gains-tax
   :authority       authority
   :base            (money/money net-st commodity)
   :schedule        nil
   :gross-liability (money/zero commodity)
   :liability       (money/zero commodity)
   :prepaid         (money/zero commodity)
   :line-items      [{:line :st-net :label "Net ST capital gain" :value (money/money net-st commodity)}]
   :jurisdiction-specific-codes {:pit-base-additions [net-st]
                                 :lane :st}})

(defn- lt-component
  "Individual LT component with its own §1(h) progressive-bracket
   schedule. The bracket thresholds are filing-status-conditioned;
   the §1(h) layering rule (LT gain stacks on top of ordinary income
   for threshold-crossing purposes) is handled by the consumer when
   it computes the effective bracket via `:inputs :ordinary-taxable-
   income-band`. v1 uses the simple bracket on the LT amount only —
   correctly under-reports tax for taxpayers near a bracket cusp; a
   note 112 §4 §1(h) layering follow-up adds it."
  [{:keys [commodity authority]} ctx ^java.math.BigDecimal net-lt]
  (let [db       (:db ctx)
        as-of    (as-of-from-ctx ctx)
        status   (or (get-in ctx [:tax-unit :filing-status]) :single)
        schedule (lt-schedule db as-of status)
        gross    (ts/apply-schedule schedule net-lt)]
    {:kind            :capital-gains-tax
     :authority       authority
     :base            (money/money net-lt commodity)
     :schedule        schedule
     :gross-liability (money/money gross commodity)
     :liability       (money/money gross commodity)
     :prepaid         (money/zero commodity)
     :regime          status
     :line-items      [{:line :lt-net   :label "Net LT capital gain" :value (money/money net-lt commodity)}
                       {:line :lt-tax   :label "LT tax at §1(h) brackets" :value (money/money gross commodity)}]
     :jurisdiction-specific-codes {:lane :lt
                                   :filing-status status}}))

(defn- §1250-component
  "Individual §1250 unrecaptured component — flat 25 % cap rate on the
   unrecaptured-depreciation portion of LT real-property gain."
  [{:keys [commodity authority]} ctx ^java.math.BigDecimal net-amount]
  (let [db    (:db ctx)
        as-of (as-of-from-ctx ctx)
        rate  (statute/parameter-value-at db "US.CGT.§1250.rate" as-of)
        gross (* net-amount rate)]
    {:kind            :capital-gains-tax
     :authority       authority
     :base            (money/money net-amount commodity)
     :schedule        (ts/flat rate)
     :gross-liability (money/money gross commodity)
     :liability       (money/money gross commodity)
     :prepaid         (money/zero commodity)
     :line-items      [{:line :§1250-net :label "Unrecaptured §1250 gain"
                        :value (money/money net-amount commodity)}
                       {:line :§1250-tax :label "Tax at 25 % cap rate"
                        :value (money/money gross commodity)}]
     :jurisdiction-specific-codes {:lane :§1250-unrecaptured}}))

(defn- ordinary-recapture-component
  "Ordinary recapture (§1245 personal property, §1250 corporate
   excess accelerated) — flows into the PIT/CIT base as ordinary
   income via `:pit-/cit-base-additions`."
  [{:keys [commodity authority]} ctx ^java.math.BigDecimal recapture-amount kind]
  (let [code (if (= kind :individual) :pit-base-additions :cit-base-additions)]
    {:kind            :capital-gains-tax
     :authority       authority
     :base            (money/money recapture-amount commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :liability       (money/zero commodity)
     :prepaid         (money/zero commodity)
     :line-items      [{:line :ordinary-recapture
                        :label "§1245 / §1250 ordinary recapture"
                        :value (money/money recapture-amount commodity)}]
     :jurisdiction-specific-codes {code [recapture-amount]
                                   :lane :ordinary-recapture}}))

(defn- niit-component
  "§1411 NIIT — a `:surtax` that adjusts a standalone component's
   liability via `apply-adjustments`. The provider runs it OVER the
   sum of standalone-tax components (LT + §1250); the result rides as
   a separate component with its own :liability."
  [{:keys [commodity authority]} ctx ^java.math.BigDecimal cgt-running-tax]
  (let [scoped-ctx (assoc ctx :db (:db ctx) :kind :individual)
        as-of (as-of-from-ctx ctx)
        {:keys [tax-items]} (let [r (statute/apply-provisions
                                     (:db ctx)
                                     {:concept :surtax :jurisdiction :us :as-of as-of}
                                     scoped-ctx)]
                              r)
        {liability :liability resolved :resolved} (ts/apply-adjustments
                                                   cgt-running-tax tax-items scoped-ctx)
        niit-amount (- liability cgt-running-tax)]
    (when (pos? niit-amount)
      {:kind            :capital-gains-tax
       :authority       authority
       :base            (money/zero commodity)
       :schedule        nil
       :gross-liability (money/money niit-amount commodity)
       :liability       (money/money niit-amount commodity)
       :prepaid         (money/zero commodity)
       :line-items      (mapv (fn [r] {:line (:code r)
                                       :label (:label r)
                                       :value (money/money (:amount r) commodity)})
                              resolved)
       :composed-of     [:lt :§1250-unrecaptured]
       :jurisdiction-specific-codes {:lane :niit}})))

;; ============================================================================
;; Components — corporation
;; ============================================================================

(defn- corp-net-component
  "Corp net cap gain — flows into the §11 21 % CIT base (no preferential
   rate for corps under US law). The consumer composes by reading
   :cit-base-additions."
  [{:keys [commodity authority]} ^java.math.BigDecimal net-capital]
  {:kind            :capital-gains-tax
   :authority       authority
   :base            (money/money net-capital commodity)
   :schedule        nil
   :gross-liability (money/zero commodity)
   :liability       (money/zero commodity)
   :prepaid         (money/zero commodity)
   :line-items      [{:line :net-capital :label "Net capital gain"
                      :value (money/money net-capital commodity)}]
   :jurisdiction-specific-codes {:cit-base-additions [net-capital]
                                 :lane :corp-net-capital}})

;; ============================================================================
;; The provider
;; ============================================================================

(defrecord USCapitalGainsTaxProvider
           [id source authority commodity statute kind]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs] :as ctx}]
    (let [db          (or (:db ctx)
                          (throw (ex-info ":db required in ctx for US CGT provider"
                                          {:ctx-keys (keys ctx)})))
          as-of       (as-of-from-ctx ctx)
          cutoff-days (long (statute/parameter-value-at
                             db "US.CGT.holding-period-cutoff-days" as-of))
          disposals   (ds/disposals-in source entity period)
          ;; classify returns a vector of lane entries per disposal (one
          ;; or two — §1245/§1250 split). mapcat flattens.
          classified  (into [] (mapcat #(classify % {:kind kind :cutoff-days cutoff-days})
                                       disposals))
          carry-in    (or (:capital-loss-carryforward inputs) {})
          opts        {:authority authority :commodity commodity}
          components
          (case kind
            :individual
            (let [recapture (sum-recapture classified)
                  st-net    (net-lane (sum-lane classified :st)
                                      (:short carry-in))
                  lt-net    (net-lane (sum-lane classified :lt)
                                      (:long carry-in))
                  §1250-net (net-lane (sum-lane classified :§1250-unrecaptured)
                                      (:§1250 carry-in))
                  lt-cmp    (when (pos? lt-net)    (lt-component opts ctx lt-net))
                  §1250-cmp (when (pos? §1250-net) (§1250-component opts ctx §1250-net))
                  st-cmp    (when (pos? st-net)    (st-component opts st-net))
                  rec-cmp   (when (pos? recapture)
                              (ordinary-recapture-component opts ctx recapture :individual))
                  running   (+ (or (some-> lt-cmp :gross-liability :amount) 0M)
                               (or (some-> §1250-cmp :gross-liability :amount) 0M))
                  niit-cmp  (when (pos? running) (niit-component opts ctx running))]
              (->> [lt-cmp §1250-cmp st-cmp rec-cmp niit-cmp]
                   (remove nil?)
                   vec))

            :corporation
            (let [recapture (sum-recapture classified)
                  net-cap   (- (+ (sum-lane classified :st)
                                  (sum-lane classified :lt))
                               (or (:capital carry-in) 0M))
                  net-cap'  (max 0M net-cap)
                  corp-cmp  (when (pos? net-cap')
                              (corp-net-component opts net-cap'))
                  rec-cmp   (when (pos? recapture)
                              (ordinary-recapture-component
                               opts ctx recapture :corporation))]
              (->> [corp-cmp rec-cmp] (remove nil?) vec))

            (throw (ex-info "US CGT provider :kind must be :individual or :corporation"
                            {:kind kind})))]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :us :authority authority}
        :functional-commodity commodity
        :components           components}))))

;; ============================================================================
;; Constructors
;; ============================================================================

(defn us-individual-cgt-provider
  "Build a US individual CGT provider. Required: `:source` — a
   `DisposalSource` (kernel protocol)."
  [{:keys [source id]}]
  (when-not source (throw (ex-info ":source DisposalSource required" {})))
  (->USCapitalGainsTaxProvider (or id :us-cgt-individual) source :us-irs :USD
                               "IRC §1(h), §1411, §1211(b)" :individual))

(defn us-corporate-cgt-provider
  "Build a US corporate CGT provider. Required: `:source`."
  [{:keys [source id]}]
  (when-not source (throw (ex-info ":source DisposalSource required" {})))
  (->USCapitalGainsTaxProvider (or id :us-cgt-corporate) source :us-irs :USD
                               "IRC §1211(a), §1212(a), §1245, §1250" :corporation))

(defn install-statute!
  "Install the US CGT statute (parameters + provisions) into `conn`."
  [conn]
  (cgt-statute/install! conn))
