(ns kontor.l10n-ca.cgt-provider
  "CA capital-gains tax provider — `PeriodTaxProvider` (ADR-099) over
   the ADR-102 disposal substrate + ADR-101 statute-as-data. Research
   note 127.

   ## One callable shape — `:kind :individual | :corporation`

   Unlike US CGT (multi-lane: ST / LT / §1250 / §1245 / NIIT), CA
   CGT has NO statutory short-vs-long distinction (note 127 §1.6),
   NO own bracket schedule (note 127 §1.9), and NO surtax. Every
   disposal — short or long, public or private, depreciable or not —
   runs through the SAME 1/2 inclusion (ITA s.38(a)). The provider
   therefore returns ONE component per call whose `:base` is the
   period's net taxable capital gain (post-50%-inclusion,
   post-exemptions, post-rollover-exclusion, post-CCA-recapture-split).

   ## Composition with the CIT/PIT provider

   The CGT provider feeds the downstream CIT (corporations) or PIT
   (individuals) provider via `:jurisdiction-specific-codes
   {:cit-base-additions [...] | :pit-base-additions [...]}`. The
   consumer reads that vec and threads it into the CIT/PIT provider's
   `:inputs :base-transform` (or equivalent) on the same period.

   No own schedule (`:schedule nil`); no own gross liability. The
   downstream provider's bracket schedule taxes the gain.

   ## CCA recapture

   Depreciable-property disposal splits at `:disposal/depreciation-taken-amount`:

     recapture = min(proceeds, capital_cost) − NBV
       where  NBV = :disposal/basis-amount
              capital_cost = NBV + depreciation_taken

   The recapture slice is ordinary income (NOT capital — `s.13(1)`),
   added to the PIT/CIT base via a SEPARATE `:base-add` entry; the
   capital portion `max(0, proceeds − capital_cost)` enters the CGT
   pool at 50% inclusion as usual.

   ## ABIL

   `s.39(1)(c)` business investment loss; `s.38(c)` 1/2 deductible
   against ANY income (not just capital). When a disposal carries
   `:disposal/loss-bucket :ca-abil`, the provider folds 1/2 of the
   loss into the PIT/CIT base addition as a NEGATIVE adjustment AND
   reports it on a separate `:line-items` entry — but does NOT include
   it in the 50%-inclusion pool (where it would be wall-quarantined
   per `:ca-capital-loss` semantics). Surfacing it as an additional
   PIT/CIT base SUBTRACTION lets it offset ordinary income too.

   ## Principal residence + rollover exclusion

   When a disposal carries `:disposal/residence? true` AND
   `:disposal/elective-regime` contains `:ca-principal-residence`,
   the full gain is exempt — the disposal contributes 0 to every pool.
   The 1+ formula (s.40(2)(b) `(1 + designated-years) / years-owned`)
   is NOT modelled in v1 — the consumer enforces full vs partial via
   the flag.

   When a disposal carries any `:ca-§*-rollover` / `:ca-§*-replacement` /
   `:ca-§*-reorganisation` / `:ca-§*-spousal` regime, the gain is
   excluded from the pool entirely (deferred to the replacement asset's
   future disposal).

   ## LCGE — `:inputs :lcge-claimed-prior`

   The provider tracks per-call LCGE consumption: starting from the
   2026 statutory cap ($1,275,000), subtracts the consumer-supplied
   `:lcge-claimed-prior` (cumulative claims across all prior years).
   The remaining cap is the available pool. For each disposal carrying
   `:exemption-claimed` ∈ {`:ca-lcge-qsbcs`, `:ca-lcge-qfp`}, the gain
   is sheltered up to the remaining pool (floored at zero), in disposal
   iteration order.

   Note 127 §3.2: this is `:inputs` shape (per-taxpayer cumulative
   state), NOT a `:disposal` schema attr. The CNIL grind is OUT OF
   SCOPE for v1.

   ## Superficial loss

   Trusts a consumer-supplied `:disposal/exemption-claimed
   :ca-superficial-loss` flag (note 127 §1.7 +§6 — full detection
   deferred to v2). When the flag is present, the loss is dropped
   from the pool entirely (denied per s.40(2)(g)(i); the basis-bump
   on the substituted property happens in `kontor.book` future work).

   ## CDA

   The non-taxable 50% half is the corporation's Capital Dividend
   Account credit (s.83(2) / s.89(1)). This is a `kontor.book` verb
   concern (`book/credit-cda`), NOT this provider — see note 127 §3.7."
  (:require [kontor.disposal-source :as ds]
            [kontor.l10n-ca.cgt-statute :as cgt-statute]
            [kontor.money :as money]
            [kontor.period-tax-provider :as ptp]
            [kontor.statute :as statute]))

;; ============================================================================
;; CA CGT vocab — open-by-design, documented here for provider dispatch
;; ============================================================================

(def asset-classes
  "Documented CA asset-class values the provider dispatches on. The
   `:disposal/asset-class` attr is OPEN — consumers can supply other
   keywords; the provider falls back to plain 50%-inclusion behaviour
   for unrecognised values. See note 127 §3.1."
  #{:ca-public-shares
    :ca-qsbcs
    :ca-qfp-qfishing
    :ca-principal-residence
    :ca-personal-use
    :ca-listed-personal-property
    :ca-real-estate
    :ca-depreciable
    :ca-class-14.1
    :ca-debt-instruments
    :ca-foreign-currency
    :ca-crypto-asset})

(def elective-regimes
  "Documented CA elective-regime values that trigger rollover exclusion
   (gain deferred into the replacement asset / corp / spouse)."
  #{:ca-§85-rollover
    :ca-§86-reorganisation
    :ca-§51-conversion
    :ca-§87-amalgamation
    :ca-§44-replacement
    :ca-§73-spousal
    :ca-charitable-public-share
    :ca-principal-residence})

(def exemptions-claimed
  "Documented CA exemption-claimed values."
  #{:ca-lcge-qsbcs
    :ca-lcge-qfp
    :ca-principal-residence
    :ca-§38a1-listed-donation
    :ca-superficial-loss})

(def loss-buckets
  "Documented CA loss-bucket values."
  #{:ca-capital-loss
    :ca-abil
    :ca-superficial
    :ca-lpp-loss})

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- as-of-from-ctx
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- to-set
  "Coerce a card-many keyword attr (which datahike returns as a vec) to
   a set so `contains?` works."
  [x]
  (cond
    (nil? x) #{}
    (set? x) x
    :else    (set x)))

(defn- bigdec0
  ^java.math.BigDecimal [x]
  (or x 0M))

(defn- rollover-elected?
  "True when the disposal carries any of the rollover-style elective
   regimes — the gain is deferred and excluded from the period pool."
  [disposal]
  (let [regimes (to-set (:disposal/elective-regime disposal))]
    (boolean
     (some regimes
           #{:ca-§85-rollover
             :ca-§86-reorganisation
             :ca-§51-conversion
             :ca-§87-amalgamation
             :ca-§44-replacement
             :ca-§73-spousal}))))

(defn- principal-residence-exempt?
  "True when the disposal is the holder's principal residence AND the
   `:ca-principal-residence` regime is elected. v1 ⇒ full exemption."
  [disposal]
  (and (true? (:disposal/residence? disposal))
       (contains? (to-set (:disposal/elective-regime disposal))
                  :ca-principal-residence)))

(defn- charitable-public-share-zero?
  "True when the disposal is an in-kind donation of listed securities
   under s.38(a.1) — inclusion rate ZERO. The gain is still computed
   (for audit), but 0 × gain = 0 contribution to the taxable pool."
  [disposal]
  (contains? (to-set (:disposal/exemption-claimed disposal))
             :ca-§38a1-listed-donation))

(defn- superficial-loss-flagged?
  "Trust the consumer-supplied flag; drop the loss from the pool."
  [disposal]
  (contains? (to-set (:disposal/exemption-claimed disposal))
             :ca-superficial-loss))

(defn- abil?
  "True when the disposal's loss-bucket is `:ca-abil`. ABIL detours
   OUT of the capital lane into the ordinary lane at 1/2 deductibility."
  [disposal]
  (= :ca-abil (:disposal/loss-bucket disposal)))

(defn- lcge-eligible?
  "True when the disposal claims LCGE on QSBCS or QFP/QFishing."
  [disposal]
  (let [exempt (to-set (:disposal/exemption-claimed disposal))]
    (or (contains? exempt :ca-lcge-qsbcs)
        (contains? exempt :ca-lcge-qfp))))

(defn- depreciable?
  "True when the disposal's asset-class is one of the CCA-recapture
   asset classes."
  [disposal]
  (contains? #{:ca-depreciable :ca-class-14.1}
             (:disposal/asset-class disposal)))

(defn- realized-gain
  "Raw gain (positive) or loss (negative) on a disposal:
   `proceeds − basis − rollover-amount`."
  ^java.math.BigDecimal [disposal]
  (let [p (bigdec0 (:disposal/proceeds-amount disposal))
        b (bigdec0 (:disposal/basis-amount disposal))
        r (bigdec0 (:disposal/rollover-amount disposal))]
    (- p b r)))

(defn- cca-split
  "Split a depreciable-property gain/loss into ordinary recapture,
   capital portion, and terminal loss. Returns
   `{:recapture <bigdec> :capital <bigdec> :terminal-loss <bigdec>}`.

     NBV          = :disposal/basis-amount
     capital_cost = NBV + :disposal/depreciation-taken-amount
     gain         = proceeds − NBV (basis already = NBV)
     recapture    = min(proceeds, capital_cost) − NBV  (positive only)
     capital      = max(0, proceeds − capital_cost)
     terminal-loss = max(0, NBV − proceeds)            (positive magnitude)

   Per ITA s.20(16): when proceeds < NBV (UCC) on disposal of the last
   asset of a CCA class, the un-recovered UCC is a TERMINAL LOSS —
   deductible against ORDINARY income, not a capital loss. ITA
   s.39(1)(b)(i) explicitly excludes depreciable property from the
   capital-loss universe. CRA Folio S3-F4-C1 ¶1.92-1.96.

   When `:depreciation-taken-amount` is absent / zero AND there is a
   gain, recapture is 0 and the entire gain stays capital."
  [disposal]
  (let [proceeds     (bigdec0 (:disposal/proceeds-amount disposal))
        nbv          (bigdec0 (:disposal/basis-amount disposal))
        dep-taken    (bigdec0 (:disposal/depreciation-taken-amount disposal))
        capital-cost (+ nbv dep-taken)
        gain         (realized-gain disposal)]
    (cond
      ;; LOSS on depreciable property → s.20(16) terminal loss
      ;; (deductible against ordinary income). NOT a capital loss
      ;; (s.39(1)(b)(i) excludes depreciable property). The loss
      ;; magnitude is (NBV − proceeds), independent of dep-taken.
      (neg? gain)
      {:recapture 0M :capital 0M :terminal-loss (- nbv proceeds)}

      ;; No depreciation taken → no recapture; whatever gain there is
      ;; stays capital (zero-gain falls through here too: capital 0).
      (not (pos? dep-taken))
      {:recapture 0M :capital gain :terminal-loss 0M}

      :else
      (let [recapture (max 0M (- (min proceeds capital-cost) nbv))
            capital   (max 0M (- proceeds capital-cost))]
        {:recapture recapture :capital capital :terminal-loss 0M}))))

;; ============================================================================
;; Per-disposal classification
;; ============================================================================

(defn- classify
  "Classify one disposal into its CA CGT contribution. Returns a map:

     {:capital-pre-inclusion  <bigdec>   ; gain that enters the 50%-inclusion pool
      :ordinary-recapture     <bigdec>   ; CCA recapture (positive only)
      :terminal-loss          <bigdec>   ; s.20(16) terminal loss (positive
                                         ; magnitude; consumer SUBTRACTS)
      :abil-deduction         <bigdec>   ; 1/2 × business-investment-loss
                                         ; (positive number; consumer SUBTRACTS)
      :lcge-eligible?         <bool>     ; true if LCGE may be applied to capital-pre-inclusion
      :line-items             [<map>]    ; per-disposal trace
      :disposal               <map>}

   The four major exclusion gates (in order): superficial loss,
   principal residence, rollover, charitable public share. Then ABIL
   detour, then CCA split (which may yield a TERMINAL LOSS for
   depreciable property at a loss — s.20(16)), then leave the rest as
   capital."
  [disposal]
  (let [external-id (:disposal/external-id disposal)
        gain        (realized-gain disposal)
        base-line   {:disposal/external-id external-id
                     :gain                 gain}]
    (cond
      ;; Superficial loss — fully denied; no contribution to any pool.
      (and (neg? gain) (superficial-loss-flagged? disposal))
      {:capital-pre-inclusion 0M
       :ordinary-recapture    0M
       :terminal-loss         0M
       :abil-deduction        0M
       :lcge-eligible?        false
       :line-items            [(assoc base-line :role :superficial-loss-denied)]
       :disposal              disposal}

      ;; Principal residence — fully exempt (s.40(2)(b) elected).
      (principal-residence-exempt? disposal)
      {:capital-pre-inclusion 0M
       :ordinary-recapture    0M
       :terminal-loss         0M
       :abil-deduction        0M
       :lcge-eligible?        false
       :line-items            [(assoc base-line :role :principal-residence-exempt)]
       :disposal              disposal}

      ;; Rollover — gain deferred into replacement; excluded from pool.
      (rollover-elected? disposal)
      {:capital-pre-inclusion 0M
       :ordinary-recapture    0M
       :terminal-loss         0M
       :abil-deduction        0M
       :lcge-eligible?        false
       :line-items            [(assoc base-line :role :rollover-deferred)]
       :disposal              disposal}

      ;; Charitable public-share donation — inclusion rate 0 per s.38(a.1).
      (charitable-public-share-zero? disposal)
      {:capital-pre-inclusion 0M
       :ordinary-recapture    0M
       :terminal-loss         0M
       :abil-deduction        0M
       :lcge-eligible?        false
       :line-items            [(assoc base-line :role :charitable-public-share-zero-inclusion)]
       :disposal              disposal}

      ;; ABIL — 1/2 of a business investment loss; goes to ordinary lane.
      ;; NOTE: rate is read from the parameter table by the caller; here
      ;; we return the raw loss magnitude as the basis for the 1/2 calc.
      (abil? disposal)
      (let [raw-loss (- gain)] ; positive number representing loss magnitude
        {:capital-pre-inclusion 0M
         :ordinary-recapture    0M
         :terminal-loss         0M
         :abil-deduction        raw-loss
         :lcge-eligible?        false
         :line-items            [(assoc base-line :role :abil-raw-loss
                                        :amount raw-loss)]
         :disposal              disposal})

      ;; Depreciable — split into recapture (ordinary) + capital +
      ;; terminal-loss (ordinary deduction per s.20(16)).
      (depreciable? disposal)
      (let [{:keys [recapture capital terminal-loss]} (cca-split disposal)]
        {:capital-pre-inclusion capital
         :ordinary-recapture    recapture
         :terminal-loss         terminal-loss
         :abil-deduction        0M
         :lcge-eligible?        false
         :line-items            (cond-> [(assoc base-line :role :depreciable-split
                                                :capital capital
                                                :recapture recapture
                                                :terminal-loss terminal-loss)]
                                  (pos? recapture)
                                  (conj {:disposal/external-id external-id
                                         :role :cca-recapture
                                         :amount recapture})
                                  (pos? terminal-loss)
                                  (conj {:disposal/external-id external-id
                                         :role :terminal-loss
                                         :amount terminal-loss
                                         :note "s.20(16) terminal loss — deducts vs ANY income; depreciable property is NOT a capital loss per s.39(1)(b)(i)"}))
         :disposal              disposal})

      ;; Default lane — plain capital gain or loss; LCGE-eligible if
      ;; the disposal flagged it (QSBC / QFP).
      :else
      {:capital-pre-inclusion gain
       :ordinary-recapture    0M
       :terminal-loss         0M
       :abil-deduction        0M
       :lcge-eligible?        (lcge-eligible? disposal)
       :line-items            [(assoc base-line :role :capital
                                      :lcge-eligible? (lcge-eligible? disposal))]
       :disposal              disposal})))

;; ============================================================================
;; LCGE allocation — per-disposal consumption from a shared remaining pool
;; ============================================================================

(defn- apply-lcge
  "Walk classified disposals in iteration order, consuming the LCGE
   remaining pool on disposals flagged `:lcge-eligible?`. Returns the
   classification list AUGMENTED with `:lcge-applied <bigdec>` (the
   amount sheltered from each disposal), plus a `:lcge-consumed`
   summary entry.

   `remaining-cap` is the consumer-supplied available cap
   (statutory cap − prior-claimed). Negative values are floored at 0.

   The deduction is taken from the PRE-INCLUSION capital gain — the
   LCGE shelters the FULL gain (the half goes to the CDA per s.110.6(2)
   semantics, but we operate on the pre-inclusion number here)."
  [classified ^java.math.BigDecimal remaining-cap]
  (let [[items _ total-applied]
        (reduce
         (fn [[acc remaining applied-total] item]
           (let [gain (:capital-pre-inclusion item)]
             (if (and (:lcge-eligible? item)
                      (pos? gain)
                      (pos? remaining))
               (let [applied (min gain remaining)]
                 [(conj acc (assoc item :lcge-applied applied))
                  (- remaining applied)
                  (+ applied-total applied)])
               [(conj acc (assoc item :lcge-applied 0M))
                remaining
                applied-total])))
         [[] (max 0M remaining-cap) 0M]
         classified)]
    {:classified         items
     :lcge-total-applied total-applied}))

;; ============================================================================
;; Aggregation — taxable capital gain + recapture + ABIL
;; ============================================================================

(defn- aggregate
  "Aggregate the classified disposals into the period totals. Returns:

     {:gross-capital     <bigdec>    Σ :capital-pre-inclusion (pre-LCGE, pre-50%)
      :lcge-applied      <bigdec>    Σ shelter from LCGE
      :net-capital       <bigdec>    gross − lcge (still pre-50%)
      :taxable-capital   <bigdec>    50% × net-capital
                                      (the headline CGT base)
      :ordinary-recapture <bigdec>   Σ CCA recapture (goes to PIT/CIT
                                      as +base addition, ordinary)
      :terminal-loss     <bigdec>    Σ s.20(16) terminal losses
                                      (goes to PIT/CIT as −base
                                      addition, against ANY income)
      :abil-deduction    <bigdec>    1/2 × Σ ABIL losses
                                      (goes to PIT/CIT as −base
                                      addition, against ANY income)
      :line-items        [<map>]     concatenated per-disposal traces}"
  [classified ^java.math.BigDecimal inclusion-rate ^java.math.BigDecimal abil-rate]
  (let [gross-capital (reduce + 0M (map :capital-pre-inclusion classified))
        lcge-applied  (reduce + 0M (map (comp bigdec0 :lcge-applied) classified))
        net-capital   (- gross-capital lcge-applied)
        ;; Capital pool can go negative within the year (current-year
        ;; capital losses offset capital gains); floor it at 0 since
        ;; LCGE is for gains and a residual current-year capital loss
        ;; carries forward via `:inputs :capital-loss-carryforward`
        ;; (consumer reports the residual back). v1 simply floors the
        ;; pool at 0.
        net-capital'  (max 0M net-capital)
        taxable-capital (* net-capital' inclusion-rate)
        ordinary-recapture (reduce + 0M (map :ordinary-recapture classified))
        terminal-loss (reduce + 0M (map (comp bigdec0 :terminal-loss) classified))
        raw-abil      (reduce + 0M (map :abil-deduction classified))
        abil-deduction (* raw-abil abil-rate)
        line-items    (vec (mapcat :line-items classified))]
    {:gross-capital      gross-capital
     :lcge-applied       lcge-applied
     :net-capital        net-capital'
     :taxable-capital    taxable-capital
     :ordinary-recapture ordinary-recapture
     :terminal-loss      terminal-loss
     :abil-deduction     abil-deduction
     :line-items         line-items}))

;; ============================================================================
;; Component assembly
;; ============================================================================

(defn- component
  "Build the single `:capital-gains-tax` component. The provider
   returns ONE component per call (note 127 §5.1) regardless of `:kind`;
   the only difference is which `:jurisdiction-specific-codes` key
   carries the base addition (`:cit-base-additions` vs
   `:pit-base-additions`).

   `:base` = taxable capital gain (post-50%, post-LCGE, post-exclusions).
   `:schedule nil` — no own schedule; the downstream PIT/CIT applies
   its bracket schedule to the gain.

   `:jurisdiction-specific-codes` carries:
     - `:cit-base-additions` / `:pit-base-additions` — the taxable
       capital gain PLUS the CCA recapture (both flow to ordinary base)
     - `:base-deduction` — the ABIL deduction (negative against ANY
       income; consumer SUBTRACTS)
     - `:cgt-summary` — the structured pool numbers for audit
     - `:lcge-applied` / `:lcge-cap-remaining` — for carry-state echo"
  [{:keys [commodity authority kind]}
   {:keys [taxable-capital ordinary-recapture abil-deduction terminal-loss
           gross-capital net-capital lcge-applied
           line-items lcge-cap-remaining]}]
  (let [base-add-key (if (= kind :individual) :pit-base-additions :cit-base-additions)
        base-deduct-key (if (= kind :individual) :pit-base-deductions :cit-base-deductions)
        ;; Combine the taxable cap gain + CCA recapture into the
        ;; PIT/CIT base additions vector. ABIL + terminal loss go via
        ;; :base-deductions.
        ordinary-base-additions (cond-> []
                                  (pos? taxable-capital)    (conj taxable-capital)
                                  (pos? ordinary-recapture) (conj ordinary-recapture))
        ordinary-base-deductions (cond-> []
                                   (pos? abil-deduction)  (conj abil-deduction)
                                   (pos? terminal-loss)   (conj terminal-loss))
        ;; Summary line items for ABIL / terminal-loss / LCGE when present.
        line-items' (cond-> line-items
                      (pos? abil-deduction)
                      (conj {:role :abil-deduction-half
                             :amount abil-deduction
                             :note "1/2 × business investment loss; deducts vs ANY income per s.38(c)+s.39(1)(c)"})
                      (pos? terminal-loss)
                      (conj {:role :terminal-loss-total
                             :amount terminal-loss
                             :note "Σ s.20(16) terminal losses; deducts vs ANY income (depreciable property — NOT a capital loss per s.39(1)(b)(i))"})
                      (pos? lcge-applied)
                      (conj {:role :lcge-applied-total
                             :amount lcge-applied
                             :note "LCGE deduction (pre-50%-inclusion); halved at the inclusion step per s.110.6(2)"})
                      true
                      (conj {:role :summary
                             :gross-capital      gross-capital
                             :net-capital        net-capital
                             :taxable-capital    taxable-capital
                             :ordinary-recapture ordinary-recapture
                             :terminal-loss      terminal-loss
                             :abil-deduction     abil-deduction}))]
    {:kind            :capital-gains-tax
     :authority       authority
     :base            (money/money taxable-capital commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :liability       (money/zero commodity)
     :prepaid         (money/zero commodity)
     :line-items      line-items'
     :jurisdiction-specific-codes
     (cond-> {:lane :ca-cgt
              :kind kind
              :cgt-summary        {:gross-capital      gross-capital
                                   :lcge-applied       lcge-applied
                                   :lcge-cap-remaining lcge-cap-remaining
                                   :net-capital        net-capital
                                   :taxable-capital    taxable-capital
                                   :ordinary-recapture ordinary-recapture
                                   :terminal-loss      terminal-loss
                                   :abil-deduction     abil-deduction}}
       (seq ordinary-base-additions)  (assoc base-add-key ordinary-base-additions)
       (seq ordinary-base-deductions) (assoc base-deduct-key ordinary-base-deductions))}))

;; ============================================================================
;; The provider
;; ============================================================================

(defrecord CACapitalGainsTaxProvider
           [id source authority commodity statute kind]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs] :as ctx}]
    (when-not (#{:individual :corporation} kind)
      (throw (ex-info "CA CGT provider :kind must be :individual or :corporation"
                      {:kind kind})))
    (let [db          (or (:db ctx)
                          (throw (ex-info ":db required in ctx for CA CGT provider"
                                          {:ctx-keys (keys ctx)})))
          as-of       (as-of-from-ctx ctx)
          inclusion   (or (statute/parameter-value-at db "CA.CGT.inclusion-rate" as-of)
                          (throw (ex-info "CA.CGT.inclusion-rate parameter not installed at as-of"
                                          {:as-of as-of})))
          abil-rate   (or (statute/parameter-value-at db "CA.CGT.abil-rate" as-of) 0.5M)
          ;; Corporations are NOT eligible for the LCGE (it's individuals
          ;; + personal trusts only — note 127 §1.10); cap stays at 0.
          stat-cap    (if (= kind :individual)
                        (or (statute/parameter-value-at db "CA.CGT.lcge-cap" as-of) 0M)
                        0M)
          claimed     (bigdec0 (:lcge-claimed-prior inputs))
          remaining   (max 0M (- stat-cap claimed))
          disposals   (ds/disposals-in source entity period)
          classified  (mapv classify disposals)
          {classified' :classified
           lcge-used   :lcge-total-applied} (apply-lcge classified remaining)
          agg          (aggregate classified' inclusion abil-rate)
          comp-input   (assoc agg :lcge-cap-remaining (- remaining lcge-used))
          opts         {:authority authority :commodity commodity :kind kind}
          one-component (component opts comp-input)]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :ca :authority authority}
        :functional-commodity commodity
        :components           [one-component]}))))

;; ============================================================================
;; Constructors
;; ============================================================================

(defn ca-individual-cgt-provider
  "Build a CA individual CGT provider. Required: `:source` —
   a `DisposalSource` (kernel protocol)."
  [{:keys [source id]}]
  (when-not source (throw (ex-info ":source DisposalSource required" {})))
  (->CACapitalGainsTaxProvider (or id :ca-cgt-individual) source :ca-cra :CAD
                               "ITA s.38, s.39, s.40, s.110.6" :individual))

(defn ca-corporate-cgt-provider
  "Build a CA corporate CGT provider. Required: `:source`. Note that
   LCGE is NOT available to corporations (individuals + personal trusts
   only per s.110.6); the provider hard-zeroes the cap when
   `:kind :corporation`."
  [{:keys [source id]}]
  (when-not source (throw (ex-info ":source DisposalSource required" {})))
  (->CACapitalGainsTaxProvider (or id :ca-cgt-corporate) source :ca-cra :CAD
                               "ITA s.38, s.39, s.40 (corporate)" :corporation))

