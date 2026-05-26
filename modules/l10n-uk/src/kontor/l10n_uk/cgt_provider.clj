(ns kontor.l10n-uk.cgt-provider
  "UK capital-gains tax providers — `PeriodTaxProvider` (ADR-099) over
   the ADR-102 disposal substrate + ADR-101 statute-as-data. Research
   note 114.

   ## Two providers, distinct shapes

   Individual CGT (TCGA 1992) and corporate chargeable gains
   (CTA 2010 + TCGA 1992) have categorically different mechanics
   (note 114 §1), so they are siblings, not branches of one record:

   - **`uk-individual-cgt-provider`** — standalone CGT with its own
     schedule: AEA → BADR / Investors'-Relief lifetime-capped slices at
     reduced rates → standard 18 % / 24 % slice (residential and non-
     residential aligned post Autumn Budget 2024, per note 114 §1.1)
     conditioned on `:tax-unit :income-band` (`:basic` / `:higher`).
     Single loss bucket (`:uk-capital`); carry forward indefinitely.

   - **`uk-corporate-cgt-provider`** — corporate chargeable gains fold
     into the Corporation Tax base at the company's main rate. Returns
     ONE component (`:kind :capital-gains-tax`) carrying
     `:cit-base-additions [net-gain]` for the consumer to thread into
     the CT provider's `:base-transform :adjustments`. Handles SSE
     (`:kontor.disposal/exemption-claimed #{:uk-sse}` ⇒ gain fully exempt).
     Indexation allowance (frozen Dec 2017) is handled OUT-OF-BAND:
     the consumer supplies an already-indexed `:kontor.disposal/basis-amount`
     per note 114 §3.2 recommendation; multi-tranche holdings emit one
     `:disposal` per tranche.

   ## DisposalSource

   Both providers depend on the kernel `DisposalSource` protocol
   (`src/kontor/disposal_source.clj`). A consumer without disposals
   wires `kontor.disposal-source/empty-source` and gets zero
   components.

   ## Lifetime-cumulative carry-in (BADR / IR)

   The £1M BADR lifetime cap spans disposals across years (often
   decades), so the cap state lives outside the current period. Per
   note 114 §3.3 + §5.4, the provider accepts the prior cumulative
   amount via `:tax-unit`:

       {:tax-unit
        {:badr-lifetime-claimed              <BigDecimal>  ;; £ already used
         :investors-relief-lifetime-claimed  <BigDecimal>
         :income-band                        :basic | :higher}}

   The provider computes the remaining cap `(max-cap - prior-used)`
   and caps the BADR / IR slice at that amount. Excess flows into the
   standard-rate slice. The consumer is responsible for keeping the
   carry-in correct (typically by querying prior `:disposal`s with
   `:exemption-claimed :uk-badr` + adding pre-kontor history)."
  (:require [kontor.disposal-source :as ds]
            [kontor.l10n-uk.cgt-statute :as cgt-statute]
            [kontor.money :as money]
            [kontor.period-tax-provider :as ptp]
            [kontor.statute :as statute]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; Constants — closed sets the provider routes on
;; ============================================================================

(def asset-classes
  "UK-tagged `:kontor.disposal/asset-class` values this provider recognises.
   Anything else routes through the standard non-residential lane."
  #{:uk-residential-property
    :uk-listed-shares
    :uk-trading-company-shares
    :uk-other})

(def income-bands
  "Closed `:tax-unit :income-band` values. The provider selects the
   standard-rate slice from these; missing defaults to `:higher` (the
   conservative choice — overstates tax slightly, never understates)."
  #{:basic :higher})

;; ============================================================================
;; Helpers — read parameters, compute gain
;; ============================================================================

(defn- as-of-from-ctx
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- realized-gain
  "Per-disposal realised gain (positive) or loss (negative), in the
   proceeds commodity: `proceeds − basis − rollover-amount`.

   For UK corporates, `:kontor.disposal/basis-amount` is expected to be the
   ALREADY-INDEXED cost base (consumer responsibility per note 114 §3.2).
   For individuals, indexation is irrelevant (abolished 2008) — the
   basis is the actual acquisition cost + allowable expenditure."
  ^java.math.BigDecimal [disposal]
  (let [p (or (:kontor.disposal/proceeds-amount disposal) 0M)
        b (or (:kontor.disposal/basis-amount disposal) 0M)
        r (or (:kontor.disposal/rollover-amount disposal) 0M)]
    (- p b r)))

(defn- exemption-claimed?
  "Does the disposal carry a given keyword in its
   `:kontor.disposal/exemption-claimed` cardinality-many set?"
  [disposal kw]
  (boolean (some #{kw} (:kontor.disposal/exemption-claimed disposal))))

(defn- residential?
  "True for residential-property disposals (drives the residential
   rate lane). UK identifies residential property by asset class
   and/or by `:kontor.disposal/residence?` (which is more of a §121-style
   primary-residence flag — kept for cross-jurisdiction symmetry)."
  [disposal]
  (= (:kontor.disposal/asset-class disposal) :uk-residential-property))

;; ============================================================================
;; Allocation — gain → BADR / IR / residential / standard lanes
;; ============================================================================

(defn- classify
  "Classify one disposal into a lane keyword. The `:lane` decides which
   rate the gain attracts:

     :badr-eligible      → BADR rate (capped by remaining lifetime)
     :ir-eligible        → Investors' Relief rate (capped by remaining
                            lifetime)
     :residential        → residential-property rates (18/24)
     :standard           → standard rates (18/24 post-Oct-2024)

   Eligibility for BADR / IR rests on the `:exemption-claimed` flag —
   the consumer asserts qualification (2-yr holding, 5 %+ shareholding,
   trading-company status for BADR; 3-yr hold + non-employee for IR).
   The provider trusts the flag and applies the rate."
  [disposal]
  (cond
    (exemption-claimed? disposal :uk-badr)        :badr-eligible
    (exemption-claimed? disposal :uk-investors-relief) :ir-eligible
    (residential? disposal)                       :residential
    :else                                         :standard))

(defn- gross-gains-and-losses
  "Split disposals into a per-lane gains map + a single losses total
   (UK has ONE :uk-capital loss bucket — losses are not lane-scoped;
   they offset gains in any lane). Returns:

     {:gains  {:badr-eligible <bigdec> :ir-eligible <bigdec>
               :residential <bigdec> :standard <bigdec>}
      :losses <bigdec> (non-negative)}"
  [classified]
  (let [zero-lanes {:badr-eligible 0M :ir-eligible 0M
                    :residential   0M :standard    0M}]
    (reduce (fn [{:keys [gains losses]} {:keys [lane gain]}]
              (if (pos? gain)
                {:gains  (update gains lane (fnil + 0M) gain)
                 :losses losses}
                {:gains  gains
                 :losses (+ losses (- gain))}))
            {:gains zero-lanes :losses 0M}
            classified)))

(defn- consume
  "Subtract `available` from `target` (both BigDecimal, non-negative),
   returning `[remaining-target consumed]`. consumed = min(target, avail)."
  [target available]
  (let [c (if (>= (compare target available) 0) available target)]
    [(- target c) c]))

(defn- allocate-losses-and-aea
  "Allocate the in-period loss-bucket + carry-in loss + AEA across the
   four lanes. Order (taxpayer-favourable, the HMRC guidance default):
   apply losses to STANDARD then RESIDENTIAL then IR-eligible then BADR-
   eligible (eat the highest-rate slices first); apply AEA the same way.

   Returns the post-deduction `gains` map. Pure."
  [gains losses aea]
  (let [order        [:standard :residential :ir-eligible :badr-eligible]
        deductions   (+ losses aea)
        [final _]    (reduce
                      (fn [[gs avail] lane]
                        (let [[g' c] (consume (get gs lane 0M) avail)]
                          [(assoc gs lane g') (- avail c)]))
                      [gains deductions]
                      order)]
    final))

(defn- apply-lifetime-cap
  "Cap `claim` at `(max-cap - prior-used)`. Returns
   `[capped-claim overflow]`. Negative remaining cap clamps to 0."
  [^java.math.BigDecimal claim
   ^java.math.BigDecimal max-cap
   ^java.math.BigDecimal prior-used]
  (let [remaining (max 0M (- max-cap prior-used))
        capped    (min claim remaining)]
    [capped (- claim capped)]))

;; ============================================================================
;; Individual provider — components
;; ============================================================================

(defn- band-rate
  "Pick basic vs higher rate by `:tax-unit :income-band` (default
   `:higher` — the conservative choice when no band supplied)."
  ^java.math.BigDecimal [{:keys [basic higher]} band]
  (case band
    :basic basic
    :higher higher
    higher))

(defn- individual-components
  "Build the individual provider's components from the classified
   disposals + carry-in + statute parameters. Returns a vector of
   component maps. Empty when no taxable gains."
  [{:keys [commodity authority]} ctx classified]
  (let [db        (:db ctx)
        as-of     (as-of-from-ctx ctx)
        param     (fn [code] (statute/parameter-value-at db code as-of))
        aea       (or (param "UK.CGT.AEA") 0M)
        ;; std + residential rates (post-Oct-2024 they're identical, but
        ;; the codes are independent so a future divergence is one row).
        std       {:basic  (param "UK.CGT.std.basic-rate")
                   :higher (param "UK.CGT.std.higher-rate")}
        resi      {:basic  (param "UK.CGT.residential.basic-rate")
                   :higher (param "UK.CGT.residential.higher-rate")}
        badr-rate (param "UK.CGT.BADR.rate")
        badr-cap  (or (param "UK.CGT.BADR.lifetime-cap") 0M)
        ir-rate   (param "UK.CGT.IR.rate")
        ir-cap    (or (param "UK.CGT.IR.lifetime-cap") 0M)
        unit      (:tax-unit ctx)
        band      (or (:income-band unit) :higher)
        badr-prior (or (:badr-lifetime-claimed unit) 0M)
        ir-prior   (or (:investors-relief-lifetime-claimed unit) 0M)
        carry-loss (or (get-in ctx [:inputs :capital-loss-carryforward :uk-capital]) 0M)

        {:keys [gains losses]} (gross-gains-and-losses classified)
        total-loss-bucket      (+ losses carry-loss)
        ;; Apply losses + AEA to gains (highest-rate lanes first).
        net-gains              (allocate-losses-and-aea gains total-loss-bucket aea)

        ;; BADR slice — cap at remaining lifetime allowance.
        badr-raw    (:badr-eligible net-gains)
        [badr-claim badr-overflow] (apply-lifetime-cap badr-raw badr-cap badr-prior)

        ;; IR slice — same pattern.
        ir-raw      (:ir-eligible net-gains)
        [ir-claim ir-overflow] (apply-lifetime-cap ir-raw ir-cap ir-prior)

        ;; Overflow from BADR / IR cascades into the STANDARD slice
        ;; (HMRC default — cap-excess attracts the standard rate, not
        ;; the residential rate — even when the underlying disposal
        ;; was for shares that happened to be of a residential-property
        ;; company; for v1 we follow this rule globally).
        std-amt     (+ (:standard net-gains) badr-overflow ir-overflow)
        resi-amt    (:residential net-gains)

        std-rate    (band-rate std band)
        resi-rate   (band-rate resi band)

        badr-tax    (if (and (pos? badr-claim) badr-rate)
                      (* badr-claim badr-rate) 0M)
        ir-tax      (if (and (pos? ir-claim) ir-rate)
                      (* ir-claim ir-rate) 0M)
        std-tax     (if (and (pos? std-amt) std-rate)
                      (* std-amt std-rate) 0M)
        resi-tax    (if (and (pos? resi-amt) resi-rate)
                      (* resi-amt resi-rate) 0M)

        gross-base  (+ badr-claim ir-claim std-amt resi-amt)
        liability   (+ badr-tax ir-tax std-tax resi-tax)
        unused-loss (max 0M (- total-loss-bucket
                               (+ (reduce + 0M (vals gains))
                                  ;; portion of in-period loss that
                                  ;; offset gains is (total - residual)
                                  0M)))]
    (when (pos? (+ gross-base resi-amt std-amt))
      [{:kind                         :capital-gains-tax
        :authority                    authority
        :base                         (money/money gross-base commodity)
        :schedule                     nil
        :gross-liability              (money/money liability commodity)
        :liability                    (money/money liability commodity)
        :prepaid                      (money/zero commodity)
        :regime                       band
        :line-items
        (cond-> []
          true        (conj {:line :gross-gains  :label "Σ gross gains (all lanes, pre-loss/AEA)"
                             :value (money/money (reduce + 0M (vals gains)) commodity)})
          true        (conj {:line :losses-used  :label "Capital losses (in-period + carry-in) applied"
                             :value (money/money total-loss-bucket commodity)})
          true        (conj {:line :aea          :label "Annual Exempt Amount applied"
                             :value (money/money aea commodity)})
          (pos? badr-claim) (conj {:line :badr-slice :label "BADR slice (capped to remaining lifetime)"
                                   :value (money/money badr-claim commodity)})
          (pos? ir-claim)   (conj {:line :ir-slice   :label "Investors' Relief slice (capped to remaining lifetime)"
                                   :value (money/money ir-claim commodity)})
          (pos? std-amt)    (conj {:line :standard-slice :label "Standard-rate slice"
                                   :value (money/money std-amt commodity)})
          (pos? resi-amt)   (conj {:line :residential-slice :label "Residential-property-rate slice"
                                   :value (money/money resi-amt commodity)})
          true              (conj {:line :liability  :label "CGT liability"
                                   :value (money/money liability commodity)}))
        :jurisdiction-specific-codes
        {:lane                       :uk-individual-cgt
         :income-band                band
         :badr-claimed-this-period   badr-claim
         :ir-claimed-this-period     ir-claim
         :badr-lifetime-used-after   (+ badr-prior badr-claim)
         :ir-lifetime-used-after     (+ ir-prior ir-claim)
         :unused-loss-carryforward   unused-loss}}])))

;; ============================================================================
;; Corporate provider — components
;; ============================================================================

(defn- corporate-components
  "Build the corporate chargeable-gains feeder. SSE-flagged disposals
   are dropped; remaining disposals' gains are summed (already-indexed
   basis per consumer responsibility) and netted against a single
   `:uk-capital` carry-in loss. The net flows to CT via
   `:cit-base-additions`."
  [{:keys [commodity authority]} ctx classified-all]
  (let [;; SSE filter on the underlying disposal — drop the entry entirely.
        post-sse   (remove (fn [{d :disposal}]
                             (exemption-claimed? d :uk-sse))
                           classified-all)
        gross      (reduce + 0M (map :gain post-sse))                  ; signed
        sse-amount (reduce + 0M (->> classified-all
                                     (filter (fn [{d :disposal}]
                                               (exemption-claimed? d :uk-sse)))
                                     (map :gain)))
        carry-loss (or (get-in ctx [:inputs :capital-loss-carryforward :uk-capital]) 0M)
        net        (- gross carry-loss)
        net'       (max 0M net)
        carry-out  (max 0M (- carry-loss gross))]
    (when (or (pos? net')
              (pos? sse-amount)
              (pos? carry-out))
      [{:kind                         :capital-gains-tax
        :authority                    authority
        :base                         (money/money net' commodity)
        :schedule                     nil
        :gross-liability              (money/zero commodity)
        :liability                    (money/zero commodity)
        :prepaid                      (money/zero commodity)
        :line-items
        (cond-> []
          true              (conj {:line :gross-chargeable-gains
                                   :label "Σ chargeable gains (post-SSE, indexed basis)"
                                   :value (money/money gross commodity)})
          (pos? sse-amount) (conj {:line :sse-exempt
                                   :label "SSE-exempt gains (TCGA Sch 7AC)"
                                   :value (money/money sse-amount commodity)})
          true              (conj {:line :losses-applied
                                   :label "Capital-loss carry-in applied"
                                   :value (money/money (min carry-loss gross) commodity)})
          true              (conj {:line :net-to-cit
                                   :label "Net chargeable gain → CT base"
                                   :value (money/money net' commodity)}))
        :jurisdiction-specific-codes
        {:lane                       :uk-corporate-cgt
         :cit-base-additions         [net']
         :sse-exempt-amount          sse-amount
         :unused-loss-carryforward   carry-out}}])))

;; ============================================================================
;; The provider record
;; ============================================================================

(defrecord UKCapitalGainsTaxProvider
           [id source authority commodity statute kind]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period] :as ctx}]
    (let [_  (or (:db ctx)
                 (throw (ex-info ":db required in ctx for UK CGT provider"
                                 {:ctx-keys (keys ctx)})))
          disposals  (ds/disposals-in source entity period)
          classified (mapv (fn [d] {:disposal d
                                    :gain (realized-gain d)
                                    :lane (classify d)})
                           disposals)
          opts       {:authority authority :commodity commodity}
          components (case kind
                       :individual  (individual-components opts ctx classified)
                       :corporation (corporate-components opts ctx classified)
                       (throw (ex-info "UK CGT provider :kind must be :individual or :corporation"
                                       {:kind kind})))]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :uk :authority authority}
        :functional-commodity commodity
        :components           (vec components)}))))

;; ============================================================================
;; Constructors
;; ============================================================================

(defn uk-individual-cgt-provider
  "Build a UK individual CGT provider. Required: `:source` — a
   `DisposalSource` (kernel protocol)."
  [{:keys [source id]}]
  (when-not source (throw (ex-info ":source DisposalSource required" {})))
  (->UKCapitalGainsTaxProvider
   (or id :uk-cgt-individual) source :uk-hmrc :GBP
   "TCGA 1992 + FA 2016 + FA 2020 + Autumn Budget 2024 (FA 2024-25)"
   :individual))

(defn uk-corporate-cgt-provider
  "Build a UK corporate chargeable-gains feeder. Required: `:source`.
   Output flows into the consumer's CT provider via `:cit-base-additions`."
  [{:keys [source id]}]
  (when-not source (throw (ex-info ":source DisposalSource required" {})))
  (->UKCapitalGainsTaxProvider
   (or id :uk-cgt-corporate) source :uk-hmrc :GBP
   "TCGA 1992 (corporate chargeable gains) + Sch 7AC (SSE) + FA 2018 (indexation freeze)"
   :corporation))

