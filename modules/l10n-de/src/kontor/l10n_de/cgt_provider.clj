(ns kontor.l10n-de.cgt-provider
  "DE capital-gains tax providers — `PeriodTaxProvider` (ADR-099) over
   the ADR-102 disposal substrate + ADR-101 statute-as-data. Research
   note 113.

   ## TWO providers, not one

   Per note 113 §5, DE CGT splits cleanly along the corporate /
   individual axis. Each axis is a separate `PeriodTaxProvider`
   because the FILTERS, BASES, BUCKETS, COMPONENTS, and INTEGRATION
   targets differ — bundling them would force every consumer to
   discriminate at the call site.

   - **`de-corporate-cgt-provider`** (kind `:corporation`): §8b KStG
     (95 % participation exemption + 5 % non-deductible add-back) on
     corporate disposals of participations. §6b EStG rollover relief
     (when `:kontor.disposal/elective-regime` contains `:de-§6b-reserve` the
     gain is excluded from the §8b pool — deferred via reserve on the
     GL side, NOT this provider's concern). Output: a single component
     whose `:jurisdiction-specific-codes :cit-base-additions` carries
     the 5 % add-back the CIT provider composes downstream — mirrors
     `kontor.sole-proprietor/business-income-input` (ADR-100) between
     business-net and PIT.

   ## CGT-CIT integration (note 136 P0-3)

   When this corporate CGT provider runs alongside the DE CIT provider
   on the same period, the CGT provider is the SOURCE-OF-TRUTH for the
   §8b 5 % add-back: it computes the add-back from the disposal-level
   gain (after carry-in netting) and emits it as
   `:jurisdiction-specific-codes :cit-base-additions` on the `:de-§8b`
   component. To avoid double-counting against the CIT statute's
   `DE-KStG-§8b-Abs-5` provision (which can re-derive the same number
   from `:inputs :participation-gain` in standalone-CIT mode), the
   consumer wires the integration as:

     1. Call the CGT provider; get `TaxReturnFacts`.
     2. Read the §8b add-back via `cgt-§8b-addback-input` (sums the
        `:cit-base-additions` across all components).
     3. Call the CIT provider with `:tax-unit
        {:cgt-provider-active? true}` — this gates off the CIT
        statute's standalone §8b provision so the CGT-side add-back
        isn't re-derived. The consumer is responsible for folding the
        CGT-side add-back into the CIT base via whatever channel suits
        the consumer (it may continue to pass it as
        `:inputs :participation-gain` purely as a fact — the gated
        provision will not fire on it).

   Standalone-CIT consumers (no CGT provider) leave the flag absent and
   pass `:inputs :participation-gain` directly — the CIT statute's
   provision fires as before. No behaviour change for that path.

   - **`de-personal-cgt-provider`** (kind `:individual`): four loss-
     bucket-isolated regimes:
       - §17 EStG — wesentliche Beteiligung (≥1 % stake) →
         Teileinkünfteverfahren (60 % inclusion + €9 060 Freibetrag
         with €36 100 taper).
       - §20 EStG — Abgeltungsteuer (flat 25 % + Soli) on securities
         NOT meeting §17 threshold. TWO sub-buckets: `:de-§20-stock`
         vs `:de-§20-other` (stock-sale losses offset only stock-sale
         gains — §20 Abs. 6 wall).
       - §23 EStG — private speculation (real estate within 10 y;
         movable within 1 y; tax-FREE past cutoff). €1 000 Freigrenze
         (HARD threshold per §23 Abs. 3 S. 5: \"weniger als 1 000\" →
         < €1 000 tax-free; ≥ €1 000 → full amount taxable).
       - Günstigerprüfung: `:tax-unit
         :abgeltungsteuer-elect-marginal?  true` suppresses the §20
         standalone component and folds the §20 net into PIT base
         instead.

   ## Lane classification (`:kontor.disposal/asset-class`)

     :de-§8b-participation      → §8b lane (corporate)
     :de-§6b-eligible           → §6b lane (deferred when
                                   `:elective-regime :de-§6b-reserve`
                                   set; else falls through to the
                                   normal corporate residual gain
                                   into :cit-base-additions)
     :de-§17-wesentlich         → §17 lane (individual ≥1 %)
     :de-§20-stock              → §20-stock lane (individual)
     :de-§20-other              → §20-other lane (individual)
     :de-§23-real-estate        → §23 lane (10 y cutoff check)
     :de-§23-movable            → §23 lane (1 y cutoff check)

   Disposals with an unrecognised `:asset-class` for the given holder
   kind are silently dropped (forward-compat with new asset classes
   the substrate gains later).

   ## DisposalSource

   The provider depends on the kernel `DisposalSource` protocol
   (`src/kontor/disposal_source.clj`); a consumer without disposals
   wires `kontor.disposal-source/empty-source` and gets zero
   components."
  (:require [kontor.disposal-source :as ds]
            [kontor.l10n-de.cgt-statute :as cgt-statute]
            [kontor.money :as money]
            [kontor.period-tax-provider :as ptp]
            [kontor.statute :as statute]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; Constants
;; ============================================================================

(def corporate-asset-classes
  "Closed set of `:kontor.disposal/asset-class` keywords the corporate CGT
   provider recognises."
  #{:de-§8b-participation
    :de-§6b-eligible})

(def individual-asset-classes
  "Closed set of `:kontor.disposal/asset-class` keywords the individual CGT
   provider recognises."
  #{:de-§17-wesentlich
    :de-§20-stock
    :de-§20-other
    :de-§23-real-estate
    :de-§23-movable})

(def individual-loss-buckets
  "The four+1 isolated loss buckets (note 113 §1.6) the individual
   provider reads from `:inputs :capital-loss-carryforward`."
  #{:de-§17 :de-§20-stock :de-§20-other :de-§23})

;; ============================================================================
;; Helpers — date math, ctx, gain
;; ============================================================================

(defn- as-of-from-ctx
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- days-between
  "Whole days between two `java.util.Date` instants (Long)."
  ^long [^java.util.Date a ^java.util.Date b]
  (long (/ (- (.getTime b) (.getTime a))
           (* 1000 60 60 24))))

(defn- realized-gain
  "Gain (positive) or loss (negative) on one disposal, in the proceeds
   commodity: `proceeds − basis − rollover-amount`. Loss is signed
   negative so bucket-netting can sum gains + losses naturally."
  ^java.math.BigDecimal [disposal]
  (let [p (or (:kontor.disposal/proceeds-amount disposal) 0M)
        b (or (:kontor.disposal/basis-amount disposal) 0M)
        r (or (:kontor.disposal/rollover-amount disposal) 0M)]
    (- p b r)))

(defn- §6b-rollover-elected?
  "True iff this disposal has elected the §6b reserve — meaning the
   gain is deferred onto a replacement asset and NOT recognised in
   the current period. `:elective-regime` is cardinality-many in the
   schema; pull may return a vector OR (for a single value) a single
   keyword."
  [disposal]
  (let [r (:kontor.disposal/elective-regime disposal)]
    (cond
      (nil? r)         false
      (coll? r)        (contains? (set r) :de-§6b-reserve)
      (keyword? r)     (= r :de-§6b-reserve)
      :else            false)))

;; ============================================================================
;; Compute-fn registration — Soli on §20 Abgeltungsteuer
;; ============================================================================

(defn- de-soli-on-abgeltungsteuer
  "Soli rate × the running §20 Abgeltungsteuer (late-bound —
   apply-adjustments threads `:running` in at fold time)."
  [ctx]
  (let [rate (statute/parameter-value-at (:db ctx) "DE.Soli.rate" (as-of-from-ctx ctx))]
    (fn [ctx-w-running] (* (:running ctx-w-running) rate))))

(defn register!
  "Register DE CGT compute-fns with `kontor.statute`. Called at
   namespace load; idempotent."
  []
  (statute/register-compute-fn! :de-soli-on-abgeltungsteuer
                                de-soli-on-abgeltungsteuer))

(register!)

;; ============================================================================
;; §17 Freibetrag with taper — provider-internal
;; ============================================================================

(defn- §17-freibetrag-after-taper
  "§17 Abs. 3 EStG — €9 060 Freibetrag, reduced 1:1 by every euro of
   the GROSS Veräußerungsgewinn exceeding €36 100 (the
   Abschmelzungsgrenze). Negative results clamped to 0M.

   Per §17 Abs. 3 S. 2 EStG: \"Der Freibetrag ermäßigt sich um den
   Betrag, um den der **Veräußerungsgewinn** den Teil von 36 100 Euro
   übersteigt …\" — the taper-start comparison is against the GROSS
   (100 %) gain, NOT the 60 %-included Teileinkünfte. The
   Teileinkünfteverfahren (§3 Nr. 40 c / §3c Abs. 2 EStG) is a
   downstream inclusion mechanic at the §2 EStG income-aggregation
   stage; §17 Abs. 3's Freibetrag-with-taper sits inside §17 itself
   (note 136 P0-2).

   Worked example: gross gain €40 000 →
     taper-amount = max(0, 40 000 − 36 100) = 3 900
     freibetrag-after-taper = max(0, 9 060 − 3 900) = 5 160 → €5 160
     freed gain (then deducted from the Teileinkünfte downstream).

   Past €45 160 (= 9 060 + 36 100) gross gain the Freibetrag is fully
   consumed."
  ^java.math.BigDecimal [^java.math.BigDecimal gross-gain
                        ^java.math.BigDecimal freibetrag
                        ^java.math.BigDecimal taper-start]
  (let [excess (max 0M (- gross-gain taper-start))
        fb     (max 0M (- freibetrag excess))]
    fb))

;; ============================================================================
;; Classification — disposal → lane (provider-internal)
;; ============================================================================

(defn- classify-corporate
  "Classify one disposal into corporate lanes. Returns nil for
   disposals the corporate provider doesn't recognise (they're silently
   dropped — the disposal substrate is multi-jurisdiction and may carry
   asset-classes a given provider has no view on)."
  [disposal]
  (let [asset-class (:kontor.disposal/asset-class disposal)]
    (cond
      (not (contains? corporate-asset-classes asset-class))
      nil

      ;; §6b rollover-elected disposals: gain deferred via reserve;
      ;; NOT included in the §8b pool, NOT a base addition.
      (and (= asset-class :de-§6b-eligible)
           (§6b-rollover-elected? disposal))
      {:lane :de-§6b-deferred
       :gain (realized-gain disposal)
       :disposal disposal}

      ;; §6b-eligible but rollover NOT elected: residual gain folds
      ;; into the corporate base 1:1 (no §8b 95 % exemption — §6b
      ;; assets are not participations).
      (= asset-class :de-§6b-eligible)
      {:lane :de-§6b-residual
       :gain (realized-gain disposal)
       :disposal disposal}

      ;; §8b corp participation: the 5 % add-back lane.
      (= asset-class :de-§8b-participation)
      {:lane :de-§8b
       :gain (realized-gain disposal)
       :disposal disposal}

      :else
      nil)))

(defn- §23-cutoff-cleared?
  "True iff the disposal cleared its §23 holding-period cutoff
   (movable: > cutoff-days; real estate: > cutoff-days). Tax-FREE past
   the cutoff."
  [disposal cutoff-days]
  (let [acq (:kontor.disposal/acquired-on disposal)
        dis (:kontor.disposal/disposed-on disposal)]
    (and acq dis cutoff-days
         (> (days-between acq dis) cutoff-days))))

(defn- classify-individual
  "Classify one disposal into individual lanes. Returns nil for
   disposals the individual provider doesn't recognise OR for §23
   disposals past their cutoff (tax-free)."
  [disposal {:keys [§23-real-estate-cutoff §23-movable-cutoff]}]
  (let [asset-class (:kontor.disposal/asset-class disposal)]
    (cond
      (not (contains? individual-asset-classes asset-class))
      nil

      (= asset-class :de-§17-wesentlich)
      {:lane :de-§17 :gain (realized-gain disposal) :disposal disposal}

      (= asset-class :de-§20-stock)
      {:lane :de-§20-stock :gain (realized-gain disposal) :disposal disposal}

      (= asset-class :de-§20-other)
      {:lane :de-§20-other :gain (realized-gain disposal) :disposal disposal}

      ;; §23 real estate — tax-free past 10 y, else taxable in lane.
      (= asset-class :de-§23-real-estate)
      (if (§23-cutoff-cleared? disposal §23-real-estate-cutoff)
        {:lane :de-§23-tax-free :gain 0M :disposal disposal}
        {:lane :de-§23 :gain (realized-gain disposal) :disposal disposal})

      ;; §23 movable — tax-free past 1 y, else taxable in lane.
      (= asset-class :de-§23-movable)
      (if (§23-cutoff-cleared? disposal §23-movable-cutoff)
        {:lane :de-§23-tax-free :gain 0M :disposal disposal}
        {:lane :de-§23 :gain (realized-gain disposal) :disposal disposal})

      :else
      nil)))

;; ============================================================================
;; Bucket netting — gains + losses + carry-in within a single bucket
;; ============================================================================

(defn- sum-lane
  ^java.math.BigDecimal [classified lane]
  (reduce + 0M (map :gain (filter #(= lane (:lane %)) classified))))

(defn- net-bucket
  "Net `lane-amount` (signed sum of gains+losses) against the carry-in
   (a positive BigDecimal — the available carryforward LOSS pool from
   prior periods). Returns the BigDecimal net amount: positive ⇒ a
   gain after consuming the carry; 0 ⇒ carry fully absorbs the gain
   (or there was a loss already; loss-residual reporting deferred —
   provider only emits POSITIVE tax bases)."
  ^java.math.BigDecimal [^java.math.BigDecimal lane-amount
                        ^java.math.BigDecimal carry-in]
  (max 0M (- lane-amount (or carry-in 0M))))

;; ============================================================================
;; Components — corporate
;; ============================================================================

(defn- §8b-component
  "Corporate §8b component — 95 % of gain exempt, 5 % add-back rides
   in `:cit-base-additions` (the CIT provider composes downstream).
   The component's own `:liability` is 0M (the substrate's CIT
   provider does the rate × add-back work, not this provider)."
  [{:keys [commodity authority]} ctx
   ^java.math.BigDecimal §8b-gross-gain
   ^java.math.BigDecimal exempt-amount
   ^java.math.BigDecimal addback-amount]
  {:kind            :capital-gains-tax
   :authority       authority
   :base            (money/money §8b-gross-gain commodity)
   :schedule        nil
   :gross-liability (money/zero commodity)
   :liability       (money/zero commodity)
   :prepaid         (money/zero commodity)
   :line-items      [{:line :§8b-gross-gain
                      :label "§8b Abs. 2 KStG — corporate share-disposal gain (gross)"
                      :value (money/money §8b-gross-gain commodity)}
                     {:line :§8b-exempt
                      :label "§8b Abs. 2 KStG — 95 % exempt"
                      :value (money/money exempt-amount commodity)}
                     {:line :§8b-addback
                      :label "§8b Abs. 3 KStG — 5 % fiktive Betriebsausgaben (Pauschalzuschlag)"
                      :value (money/money addback-amount commodity)}]
   :jurisdiction-specific-codes {:cit-base-additions [addback-amount]
                                 :lane :de-§8b}})

(defn- §6b-deferred-component
  "Corporate §6b component — gain rolled into reserve, fully deferred.
   No CIT impact this period; the component exists for audit + balance-
   sheet reconciliation."
  [{:keys [commodity authority]} ^java.math.BigDecimal deferred-amount]
  {:kind            :capital-gains-tax
   :authority       authority
   :base            (money/money deferred-amount commodity)
   :schedule        nil
   :gross-liability (money/zero commodity)
   :liability       (money/zero commodity)
   :prepaid         (money/zero commodity)
   :line-items      [{:line :§6b-deferred
                      :label "§6b EStG — Reinvestitionsrücklage (gain deferred)"
                      :value (money/money deferred-amount commodity)}]
   :jurisdiction-specific-codes {:cit-base-additions [0M]
                                 :lane :de-§6b-deferred}})

(defn- §6b-residual-component
  "Corporate §6b-eligible-but-not-elected gain. Falls into the CIT
   base 1:1 (no exemption applies — §6b property is not a
   participation). Folds via `:cit-base-additions`."
  [{:keys [commodity authority]} ^java.math.BigDecimal residual-gain]
  {:kind            :capital-gains-tax
   :authority       authority
   :base            (money/money residual-gain commodity)
   :schedule        nil
   :gross-liability (money/zero commodity)
   :liability       (money/zero commodity)
   :prepaid         (money/zero commodity)
   :line-items      [{:line :§6b-residual
                      :label "§6b-eligible disposal — no rollover elected — gain into CIT base"
                      :value (money/money residual-gain commodity)}]
   :jurisdiction-specific-codes {:cit-base-additions [residual-gain]
                                 :lane :de-§6b-residual}})

;; ============================================================================
;; Components — individual
;; ============================================================================

(defn- §17-component
  "Individual §17 component — Teileinkünfteverfahren (60 % inclusion)
   minus Freibetrag-after-taper. The taxable amount folds into PIT
   base (marginal rate); no own liability."
  [{:keys [commodity authority]} ctx ^java.math.BigDecimal §17-net-gain]
  (let [db        (:db ctx)
        as-of     (as-of-from-ctx ctx)
        inclusion (statute/parameter-value-at db "DE.EStG.§17.inclusion-rate" as-of)
        fb        (statute/parameter-value-at db "DE.EStG.§17.freibetrag" as-of)
        taper-start (statute/parameter-value-at db "DE.EStG.§17.taper-start" as-of)
        teileinkünfte (* §17-net-gain inclusion)
        ;; §17 Abs. 3 S. 2 — Freibetrag taper anchors on the GROSS
        ;; Veräußerungsgewinn (not the 60 % Teileinkünfte). The
        ;; Teileinkünfteverfahren is downstream at §2 EStG income
        ;; aggregation; §17's Freibetrag-with-taper sits inside §17
        ;; (note 136 P0-2).
        fb-after-taper (§17-freibetrag-after-taper §17-net-gain fb taper-start)
        taxable   (max 0M (- teileinkünfte fb-after-taper))]
    {:kind            :capital-gains-tax
     :authority       authority
     :base            (money/money taxable commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :liability       (money/zero commodity)
     :prepaid         (money/zero commodity)
     :line-items      [{:line :§17-gross-gain
                        :label "§17 EStG — gross gain on wesentliche Beteiligung"
                        :value (money/money §17-net-gain commodity)}
                       {:line :§17-teileinkünfte
                        :label "§17 EStG — 60 % Teileinkünfte (taxable share)"
                        :value (money/money teileinkünfte commodity)}
                       {:line :§17-freibetrag
                        :label "§17 Abs. 3 EStG — Freibetrag after taper"
                        :value (money/money fb-after-taper commodity)}
                       {:line :§17-taxable
                        :label "§17 EStG — taxable amount (into PIT base)"
                        :value (money/money taxable commodity)}]
     :jurisdiction-specific-codes {:pit-base-additions [taxable]
                                   :lane :de-§17}}))

(defn- §20-component
  "Individual §20 component — Abgeltungsteuer flat 25 % + Soli surtax.
   Sub-buckets (stock vs other) netted separately upstream; the
   compound net flows into ONE schedule. When `:tax-unit
   :abgeltungsteuer-elect-marginal?` is true this component is
   SUPPRESSED upstream and the net folds into PIT base instead
   (Günstigerprüfung)."
  [{:keys [commodity authority]} ctx ^java.math.BigDecimal §20-total-net]
  (let [db        (:db ctx)
        as-of     (as-of-from-ctx ctx)
        flat-rate (statute/parameter-value-at db "DE.EStG.§20.flat-rate" as-of)
        schedule  (ts/flat flat-rate)
        gross     (ts/apply-schedule schedule §20-total-net)
        scoped    (assoc ctx :component :de-§20 :db db :as-of as-of)
        {tax-items :tax-items :as _surtax-result}
        (statute/apply-provisions
         db {:concept :surtax :jurisdiction :de :as-of as-of} scoped)
        {liability :liability resolved :resolved}
        (ts/apply-adjustments gross tax-items scoped)]
    {:kind            :capital-gains-tax
     :authority       authority
     :base            (money/money §20-total-net commodity)
     :schedule        schedule
     :gross-liability (money/money gross commodity)
     :surtaxes        (mapv #(select-keys % [:code :label :amount :provenance]) resolved)
     :liability       (money/money liability commodity)
     :prepaid         (money/zero commodity)
     :regime          :abgeltungsteuer
     :line-items      (into [{:line :§20-net :label "§20 net gain (stock + other)"
                              :value (money/money §20-total-net commodity)}
                             {:line :§20-tax :label "§20 Abgeltungsteuer (25 % flat)"
                              :value (money/money gross commodity)}]
                            (mapv (fn [r]
                                    {:line  (:code r)
                                     :label (:label r)
                                     :value (money/money (:amount r) commodity)})
                                  resolved))
     :jurisdiction-specific-codes {:lane :de-§20}}))

(defn- §23-component
  "Individual §23 component — private speculation. Freigrenze applied:
   if net ≥ €1 000 the FULL amount is taxable (HARD threshold); if
   < €1 000 the whole thing is tax-free. Taxable amount folds into
   PIT base. Per §23 Abs. 3 S. 5 EStG (\"weniger als 1 000 Euro\") the
   €1 000.00 boundary is fully taxable — not the tax-free edge."
  [{:keys [commodity authority]} ctx ^java.math.BigDecimal §23-net-gain]
  (let [db         (:db ctx)
        as-of      (as-of-from-ctx ctx)
        freigrenze (statute/parameter-value-at db "DE.EStG.§23.freigrenze" as-of)
        ;; HARD threshold per §23 Abs. 3 S. 5: if net < Freigrenze the
        ;; entire amount is tax-free; if net ≥ Freigrenze the FULL
        ;; net (not just the excess) is taxable. The statute reads
        ;; "weniger als 1 000" — strict less-than in the tax-free
        ;; direction, equivalently ≥ in the taxable direction.
        taxable    (if (>= §23-net-gain freigrenze) §23-net-gain 0M)]
    {:kind            :capital-gains-tax
     :authority       authority
     :base            (money/money taxable commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :liability       (money/zero commodity)
     :prepaid         (money/zero commodity)
     :line-items      [{:line :§23-gross
                        :label "§23 EStG — gross gain on private Veräußerungsgeschäfte"
                        :value (money/money §23-net-gain commodity)}
                       {:line :§23-freigrenze
                        :label "§23 Abs. 3 EStG — Freigrenze applied (hard threshold)"
                        :value (money/money freigrenze commodity)}
                       {:line :§23-taxable
                        :label "§23 EStG — taxable amount (into PIT base)"
                        :value (money/money taxable commodity)}]
     :jurisdiction-specific-codes {:pit-base-additions [taxable]
                                   :lane :de-§23}}))

(defn- §20-pit-fold-component
  "Günstigerprüfung-on component — `:tax-unit
   :abgeltungsteuer-elect-marginal?` true → §20 net folds into PIT base
   at marginal rate instead of the standalone 25 % flat. No own
   schedule; the PIT provider applies marginal brackets."
  [{:keys [commodity authority]} ^java.math.BigDecimal §20-total-net]
  {:kind            :capital-gains-tax
   :authority       authority
   :base            (money/money §20-total-net commodity)
   :schedule        nil
   :gross-liability (money/zero commodity)
   :liability       (money/zero commodity)
   :prepaid         (money/zero commodity)
   :regime          :günstigerprüfung
   :line-items      [{:line :§20-günstig
                      :label "§20 net (Günstigerprüfung — folded into PIT base at marginal rate)"
                      :value (money/money §20-total-net commodity)}]
   :jurisdiction-specific-codes {:pit-base-additions [§20-total-net]
                                 :lane :de-§20-günstig}})

;; ============================================================================
;; The provider
;; ============================================================================

(defrecord DECapitalGainsTaxProvider
           [id source authority commodity statute kind]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs] :as ctx}]
    (let [db        (or (:db ctx)
                        (throw (ex-info ":db required in ctx for DE CGT provider"
                                        {:ctx-keys (keys ctx)})))
          as-of     (as-of-from-ctx ctx)
          disposals (ds/disposals-in source entity period)
          carry-in  (or (:capital-loss-carryforward inputs) {})
          opts      {:authority authority :commodity commodity}
          components
          (case kind
            :corporation
            (let [classified (->> disposals (keep classify-corporate) vec)
                  §8b-gross  (sum-lane classified :de-§8b)
                  §8b-net    (net-bucket §8b-gross (:de-§8b carry-in))
                  §6b-def    (sum-lane classified :de-§6b-deferred)
                  §6b-resid  (sum-lane classified :de-§6b-residual)
                  ex-rate    (statute/parameter-value-at
                              db "DE.KStG.§8b.cgt-exemption-rate" as-of)
                  ab-rate    (statute/parameter-value-at
                              db "DE.KStG.§8b.cgt-addback-rate" as-of)
                  exempt     (* §8b-net ex-rate)
                  addback    (* §8b-net ab-rate)
                  §8b-cmp    (when (pos? §8b-net)
                               (§8b-component opts ctx §8b-net exempt addback))
                  §6b-def-c  (when (pos? §6b-def)
                               (§6b-deferred-component opts §6b-def))
                  §6b-res-c  (when (pos? §6b-resid)
                               (§6b-residual-component opts §6b-resid))]
              (->> [§8b-cmp §6b-def-c §6b-res-c] (remove nil?) vec))

            :individual
            (let [§23-re-cut (statute/parameter-value-at
                              db "DE.EStG.§23.real-estate-cutoff-days" as-of)
                  §23-mv-cut (statute/parameter-value-at
                              db "DE.EStG.§23.movable-cutoff-days" as-of)
                  classified (->> disposals
                                  (keep #(classify-individual
                                          % {:§23-real-estate-cutoff §23-re-cut
                                             :§23-movable-cutoff     §23-mv-cut}))
                                  vec)
                  §17-net    (net-bucket (sum-lane classified :de-§17)
                                         (:de-§17 carry-in))
                  §20-stock  (net-bucket (sum-lane classified :de-§20-stock)
                                         (:de-§20-stock carry-in))
                  §20-other  (net-bucket (sum-lane classified :de-§20-other)
                                         (:de-§20-other carry-in))
                  §20-total  (+ §20-stock §20-other)
                  §23-net    (net-bucket (sum-lane classified :de-§23)
                                         (:de-§23 carry-in))
                  §23-freigrenze (statute/parameter-value-at
                                  db "DE.EStG.§23.freigrenze" as-of)
                  günstig?   (boolean (get-in ctx [:tax-unit
                                                   :abgeltungsteuer-elect-marginal?]))
                  §17-cmp    (when (pos? §17-net) (§17-component opts ctx §17-net))
                  §20-cmp    (cond
                               (not (pos? §20-total)) nil
                               günstig? (§20-pit-fold-component opts §20-total)
                               :else    (§20-component opts ctx §20-total))
                  ;; §23 component is emitted only when the net clears
                  ;; the Freigrenze (HARD threshold per §23 Abs. 3 S. 5:
                  ;; "weniger als 1 000" → ≥ €1 000 fully taxable).
                  ;; Below the line the entire gain is tax-free and we
                  ;; suppress the component entirely — there is no tax
                  ;; to fold.
                  §23-cmp    (when (and (pos? §23-net)
                                        (>= §23-net §23-freigrenze))
                               (§23-component opts ctx §23-net))]
              (->> [§17-cmp §20-cmp §23-cmp] (remove nil?) vec))

            (throw (ex-info "DE CGT provider :kind must be :corporation or :individual"
                            {:kind kind})))]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :de :authority authority}
        :functional-commodity commodity
        :components           components}))))

;; ============================================================================
;; Constructors
;; ============================================================================

(defn de-corporate-cgt-provider
  "Build a DE corporate CGT provider. Required: `:source` — a
   `DisposalSource` (kernel protocol).

   Optional opts:
     :id        — provider id (default :de-cgt-corporate)
     :commodity — functional commodity (default :EUR)"
  [{:keys [source id commodity]
    :or   {id :de-cgt-corporate commodity :EUR}}]
  (when-not source (throw (ex-info ":source DisposalSource required" {})))
  (->DECapitalGainsTaxProvider id source :de-finanzamt commodity
                               "KStG §8b + EStG §6b" :corporation))

(defn de-personal-cgt-provider
  "Build a DE individual CGT provider. Required: `:source` — a
   `DisposalSource` (kernel protocol).

   Optional opts:
     :id        — provider id (default :de-cgt-personal)
     :commodity — functional commodity (default :EUR)"
  [{:keys [source id commodity]
    :or   {id :de-cgt-personal commodity :EUR}}]
  (when-not source (throw (ex-info ":source DisposalSource required" {})))
  (->DECapitalGainsTaxProvider id source :de-finanzamt commodity
                               "EStG §17 + §20 + §23 + SolZG §4" :individual))


;; ============================================================================
;; CGT-CIT bridge — feed the §8b add-back into the CIT provider's :inputs
;; ============================================================================

(defn cgt-§8b-addback-input
  "Sum the `:cit-base-additions` across all CGT components into a single
   BigDecimal — the §8b add-back the CIT provider should compose into
   the KSt base. Returns 0M when there are no contributions (no §8b
   disposals in the period).

   Wire-shape recap (note 136 P0-3): the corporate CGT provider emits
   `:cit-base-additions` on its `:de-§8b` (+ `:de-§6b-residual`,
   `:de-§6b-deferred`) components. This fn collapses them to one
   number a consumer threads into the CIT provider's `:inputs`. Per
   the CGT-CIT integration convention, the consumer ALSO sets
   `:tax-unit :cgt-provider-active? true` on the CIT call to suppress
   the CIT statute's standalone §8b provision."
  ^java.math.BigDecimal [cgt-facts]
  (reduce + 0M
          (mapcat (fn [c]
                    (get-in c [:jurisdiction-specific-codes :cit-base-additions]))
                  (:components cgt-facts))))
